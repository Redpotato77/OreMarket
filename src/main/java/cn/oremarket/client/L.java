/*
 * oremarket - Minecraft Forge Mod
 * Copyright (C) 2026 Redpotato77
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package cn.oremarket.client;

import cn.oremarket.OreMarketMod;
import cn.oremarket.market.Stock;
import cn.oremarket.market.StockRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import cn.oremarket.common.Num;
import cn.oremarket.common.RecursionGuard;
import cn.oremarket.common.TemplateMatch;

/**
 * Client language overlay (the multilingual core; resolved at render time, never freezing translations into UI state):
 *
 * The mod's internal original text is Chinese (screen literals, server messages, stock names, event names, etc.).
 * This tool builds a key-aligned mapping from the Chinese original to the current game language's translations:
 * - zh_cn.json values = the Chinese originals (bundled with the jar, the baseline);
 * - translations for the current language come straight from {@link Language#getInstance()}'s table
 * (lang/&lt;language&gt;.json loads automatically via vanilla, and player-made translation resource packs work too).
 *
 * Vanilla rebuilds the Language instance when the language option changes; this class lazily rebuilds its maps keyed by
 * instance identity, and since all screen text is immediate-mode and re-resolved every frame, switching languages turns the
 * mod UI, toasts, log page, event bar and chat hints into the new language on the very next frame -- no screen restart.
 *
 * {@link #tr(String)} three-tier strategy:
 * 1. exact whole-string match (full labels, whole sentences);
 * 2. on no exact match, longest-first replacement over registered fragments
 * (in a buy coal 10 shares cost 123 coins style sentence, the buy verb, the stock name and the unit tail each translate on their own);
 * 3. no match: return the input as-is (player custom stock names and other user data staying untouched is the correct behaviour).
 * Composed results have a small cache (cleared on language switch), so even the quote list's hundreds of calls per frame are map lookups.
 */
public final class L {

    private static final Logger LOG = LoggerFactory.getLogger("OreMarket/Lang");

    private static final ResourceLocation ZH_FILE =
            new ResourceLocation(OreMarketMod.MOD_ID, "lang/zh_cn.json");
    /** Mod translation-key prefix (the recognition basis when an argument is itself a key). */
    private static final String MOD_KEY_PREFIX = OreMarketMod.MOD_ID + ".";
    /** printf / slf4j style placeholders; argument numbering starts at 1. */
    private static final Pattern FORMAT_TOKEN = Pattern.compile(
            "%%|%(\\d+\\$)?[-+ 0#]*\\d*(?:\\.\\d+)?[sdfx]|\\{\\}");

    /** Language instance identity: mappings rebuild automatically when it changes (language switch / resource reload). */
    private static Language boundLanguage = null;
    /** Exact mapping: Chinese original -> current-language translation. */
    private static Map<String, String> exact = Map.of();
    /**
     * The forward table key -> current-language template.
     * Log entries carry a translation key + parameters; at render time the template is fetched by key and filled -- no whole-Chinese-sentence reverse lookup.
     */
    private static Map<String, String> keyTable = Map.of();
    /**
     * Fragment mapping (sorted by original length descending so the longest fragment always wins).
     *
     * <p>Beyond the per-entry originals of the language table, this also appends the de-whitespaced variant: registered copy often ends
     * with a space (e.g. a frozen-for-T+1 tooltip), while wrapping and tooltips split on spaces and trim line ends -- the UI then receives
     * a half-sentence without the space, and if the table lacks it, shorter words bite it apart and the leftover fragment is the Chinese
     * that leaks onto the English screen.
     */
    private static List<String[]> segments = List.of();
    /** Runtime templates containing printf / {} placeholders; still translatable whole after the server fills values. */
    private static List<FormatTemplate> formatTemplates = List.of();
    /**
     * Bucketed by the first character of a template's leading literal: an anchored template can only match
     * strings starting with its leading literal, so whole-string matching consults one bucket -- 278 entries become a dozen on average.
     */
    private static Map<Character, List<FormatTemplate>> formatBuckets = Map.of();
    /** The few templates with no leading literal (starting with a placeholder): every first character must consult them, kept separately. */
    private static List<FormatTemplate> formatNoBucket = List.of();
    /** Translations of templates containing the {stock} placeholder, used to reverse-match text the server already filled a stock name into. */
    private static List<String[]> stockTemplates = List.of();
    /** Cache of composed-string translations (cleared on language switch). */
    private static final Map<String, String> cache =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 8192;
                }
            });
    /** The baseline table (zh_cn.json, read once, kept resident). */
    private static Map<String, String> zhBase = null;
    /** Diagnostics: how many times the mapping tables were rebuilt. */
    private static int rebuildCount = 0;
    /**
     * Language epoch: +1 on every Language instance change that rebuilds the mapping tables.
     *
     * <p>The screen layer caches many wrap results / header strings / name width measurements keyed by the ORIGINAL text; they must
     * be invalidated when the language changes -- otherwise a cached wrap holds the pre-translation Chinese (computed before the
     * overlay was ready), and feeding one of its lines back into the translation layer later yields a half-translated sentence --
     * exactly the path that mixed Chinese onto English screens. See {@code MarketScreen.syncLanguageCaches()}.</p>
     */
    private static volatile int languageGeneration = 0;
    /**
     * Whether the current effective display language is Chinese: judged by translation CONTENT, not only the language code.
     * Traditional Chinese (zh_tw / zh_hk) ships a built-in table -> Chinese; if some Chinese variant lacks a translation file, vanilla
     * falls back to en_us and the UI really shows English, which then must format numbers on the English convention (K/M/B/T), avoiding
     * the 100x error of a 1.0-yi value (=1e8) being fragment-replaced into 1.0B (=1e9).
     */
    private static volatile boolean displayChinese = true;

    private L() {
    }

    /** Current game language code (e.g. zh_cn / en_us). */
    public static String langCode() {
        return Minecraft.getInstance().options.languageCode;
    }

    /**
     * Whether to display in a Chinese family (wan/yi abbreviations, ideographic comma and other typography branches).
     * The judgment reads the translations actually in effect, not the language-code prefix:
     * zh_cn / zh_tw / zh_hk show Chinese -> true; English or other languages (including Chinese variants whose files fell back to
     * English) -> false; numbers then uniformly use K/M/B/T, consistent with the on-screen wording.
     */
    public static boolean zh() {
        ensureBound();
        return displayChinese;
    }

    /**
     * Recursion-depth guard cap: beyond it a template cycle is assumed (e.g. a zh_hk stock name identical in both scripts),
     * return the raw key and warn once -- never go deeper.
     */
    private static final int MAX_TR_DEPTH = 5;
    /**
     * Per-thread recursion-depth guard (implementation in common/RecursionGuard, unit-testable without the game):
     * tr() is mostly called on the render thread; network/reload callbacks go through this same guard;
     * ThreadLocal counters never cross threads and have no atomic contention.
     */
    private static final RecursionGuard TR_GUARD = new RecursionGuard(MAX_TR_DEPTH);
    /** Warn once per template cycle (cleared on language rebuild / registry swap), preventing per-frame flooding. */
    private static final Set<String> RECURSION_WARNED =
            java.util.Collections.synchronizedSet(new LinkedHashSet<>());
    /**
     * Shallow stock-name -> current-language mapping (exact / segments only, never callback into template matching),
     * already sorted by name length descending (long names win first, so a short name cannot bite a long one apart).
     * The table is computed once and kept resident; reverse matching no longer rebuilds + re-sorts per string.
     */
    private static volatile List<String[]> stockNameTrCache = null;

    /**
     * Translation entry: the mod's Chinese original -> the current game language; unmatched input returns as-is, never null.
     * A recursion-depth guard wraps it: any translation path re-entering tr() increments depth; past the cap the raw key is returned, so
     * the reverse matching inside {@link #doTr(String)} (event templates, format templates) can never provoke a StackOverflowError.
     * The finally restores the depth value from before entry rather than decrementing, so nested calls never drift.
     *
     * <p>Multi-piece encoded strings (joined by {@code EffectText.PIECE}, e.g. effect abbreviations composed by the server) are split
     * here: <b>each piece runs the FULL translation separately</b>, rejoined with a half-width space. Translating the whole string lets
     * short fragments bite it apart (price-limits torn into price + Limit-down), while per-piece translation lets each piece match whole.</p>
     */
    public static String tr(String raw) {
        if (cn.oremarket.common.EffectText.isComposed(raw)) {
            return trPieces(raw);
        }
        return trSingle(raw);
    }

    /** Join piece translations with spaces; empty pieces are skipped outright, never leaving holes made by consecutive separators. */
    private static String trPieces(String raw) {
        List<String> pieces =
                cn.oremarket.common.EffectText.split(raw);
        if (pieces.isEmpty()) {
            return "";
        }
        if (pieces.size() == 1) {
            return trSingle(pieces.get(0));
        }
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (String piece : pieces) {
            String one = trSingle(piece);
            if (one == null || one.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(one);
        }
        return sb.toString();
    }

    /** Single-string translation (guard + reverse matching + non-Chinese hard gate); tr() and per-piece translation share this one path. */
    private static String trSingle(String raw) {
        int prev = TR_GUARD.depth();
        if (!TR_GUARD.tryEnter()) {
            warnRecursion(raw);
            return asciiPunct(raw);
        }
        try {
            return strictGate(raw, asciiPunct(doTr(raw)));
        } catch (RuntimeException overlayCrash) {
            // Fixed: any overlay path (regex overrun / broken template / dirty language table) only degrades to
            // original-text rendering plus one warning; an exception must never crash the game through a screen frame.
            warnOverlayCrash(raw, overlayCrash);
            return raw == null ? "" : asciiPunct(raw);
        } finally {
            TR_GUARD.leave(prev);
        }
    }

    /** Overlay crash degradation diagnostics: warn once per original; the screen keeps rendering the original. */
    private static void warnOverlayCrash(String raw, RuntimeException crash) {
        if (RECURSION_WARNED.size() >= 256) {
            RECURSION_WARNED.clear();
        }
        if (raw != null && RECURSION_WARNED.add(raw)) {
            // Console pure-English discipline: raw / crash may carry Chinese originals; they must be sanitized before hitting the log.
            LOG.warn("OreMarket language overlay threw on \"{}\"; rendering source text: {}",
                    cn.oremarket.common.DebugLog.ascii(
                            raw.length() > 64 ? raw.substring(0, 64) + "\u2026" : raw),
                    cn.oremarket.common.DebugLog.ascii(
                            crash.toString()));
        }
    }

    /** Only UNregistered originals may pass through verbatim; registered copy that leaks degrades to its translation key (refreshed on language rebuild). */
    private static volatile Set<String> registeredRuns = Set.of();
    private static final Set<String> STRICT_WARNED = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /**
     * Suppress hard-gate warnings during overlay self-scans: the self-check force-joins unrelated registered copy into pathological
     * sentences no player will ever see; the hard gate still drops the fragments, but these blocked cases must not flood the log
     * with I18n leak blocked. Real leak paths do not set the flag and still warn as usual.
     */
    private static final ThreadLocal<Boolean> SILENCE_LEAK_WARN = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * The last hard gate in non-Chinese environments: if any translation output still holds Hanzi, it must NOT fall back to the zh_cn
     * original -- residue belonging to registered copy shows the translation key (with one warning); user-defined text passes untouched.
     */
    private static String strictGate(String raw, String out) {
        if (zh() || out == null || !containsCjk(out)) {
            return out;
        }
        String fullKey = cn.oremarket.common.RawText.keyOf(raw);
        if (fullKey != null) {
            warnLeak(fullKey, raw);
            return fullKey;
        }
        StringBuilder sb = new StringBuilder(out.length() + 24);
        int i = 0;
        while (i < out.length()) {
            if (isCjk(out.charAt(i))) {
                int j = i;
                while (j < out.length() && isCjk(out.charAt(j))) {
                    j++;
                }
                String run = out.substring(i, j);
                // Fragments first run a shallow translation of their own (entry substring replacement only, never re-entering doTr,
                // so it cannot recurse. When layout tears registered copy in half, the half often still matches whole against
                // shorter registered entries, giving the screen complete English instead of a bitten-away discarded remainder.
                String shallow = applySegments(run);
                if (shallow != null && !shallow.equals(run) && !containsCjk(shallow)) {
                    sb.append(shallow);
                    i = j;
                    continue;
                }
                if (registeredRuns.contains(run)) {
                    String runKey = cn.oremarket.common.RawText.keyOf(run);
                    warnLeak(runKey == null ? run : runKey, raw);
                    if (runKey != null) {
                        sb.append(runKey);
                    }
                } else if (isRegisteredFragment(run)) {
                    // registered copy torn apart by short-name replacement (a leading fragment of a long stock name): dropped, never onto the English screen
                    warnLeak(run, raw);
                } else {
                    sb.append(run);                        // user data (custom stock names etc.)
                }
                i = j;
            } else {
                sb.append(out.charAt(i++));
            }
        }
        return sb.toString();
    }

    private static void warnLeak(String key, String raw) {
        if (Boolean.TRUE.equals(SILENCE_LEAK_WARN.get())) {
            return;
        }
        if (STRICT_WARNED.add(key)) {
            // with call site: which render path the fragment came from cannot be guessed from the text alone.
            // logged once per key; never floods despite per-frame redraws.
            LOG.warn("I18n leak blocked in non-Chinese locale: key={} raw={} caller={}",
                    key, cn.oremarket.common.DebugLog.ascii(raw),
                    leakCaller());
        }
    }

    /** The mod stack frame nearest the translation layer (skipping JDK / Forge / this class); pinpoints leak warnings. */
    private static String leakCaller() {
        try {
            return StackWalker.getInstance().walk(frames -> frames
                    .filter(f -> {
                        String c = f.getClassName();
                        return c.startsWith("cn.oremarket.") && !c.endsWith(".client.L");
                    })
                    .limit(1)
                    .map(f -> {
                        String c = f.getClassName();
                        return c.substring(c.lastIndexOf('.') + 1) + "." + f.getMethodName();
                    })
                    .findFirst()
                    .orElse("?"));
        } catch (RuntimeException impossible) {
            // missing diagnostics must not weaken the interception: the hard gate already fired, only the call-site hint is dropped
            return "?";
        }
    }

    /**
     * Overlay self-check entry: the same translation path as {@link #tr(String)}, just without treating hard-gate interception as leak spam.
     * Screen rendering, chat and the log page keep using {@link #tr(String)} with warnings as usual.
     */
    public static String trForScan(String raw) {
        SILENCE_LEAK_WARN.set(Boolean.TRUE);
        try {
            return tr(raw);
        } finally {
            SILENCE_LEAK_WARN.set(Boolean.FALSE);
        }
    }

    /**
     * Fragments of registered copy torn apart by short-name replacement (e.g. the leading characters of a long compound stock name, or the
     * first character of price limits). On English screens such fragments are never user-defined names and must be dropped.
     *
     * +/- 10% bitten into price + Limit-down); anything that is part of some longer registered entry is dropped.</p>
     */
    private static boolean isRegisteredFragment(String run) {
        if (run == null || run.isEmpty()) {
            return false;
        }
        for (String full : registeredRuns) {
            if (full != null && full.length() > run.length() && full.contains(run)) {
                return true;
            }
        }
        return false;
    }

    /** Single CJK Hanzi test (basic plane + extension A, same convention as containsCjk). */
    private static boolean isCjk(char c) {
        return c >= 0x3400 && c <= 0x9fff;
    }

    /** Whether a whitespace character (decides whether to add a half-width space next to punctuation). */
    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u3000';
    }

    /** Whether a half-width space is needed after this character (not at line end, not after an existing space, not before a closing symbol). */
    private static boolean needSpaceAfter(String s, int i) {
        if (i + 1 >= s.length()) {
            return false;
        }
        char nx = s.charAt(i + 1);
        return !isBlank(nx) && nx != ')' && nx != ']' && nx != '}' && nx != ',' && nx != '.'
                && nx != ':' && nx != ';' && nx != '?' && nx != '!';
    }

    /** Whether a half-width space is needed before this closing symbol (not at line start, not after an existing space, not after an opening bracket). */
    private static boolean needSpaceBefore(String s, int i) {
        if (i == 0) {
            return false;
        }
        char pv = s.charAt(i - 1);
        return !isBlank(pv) && pv != '(' && pv != '[' && pv != '{';
    }

    /**
     * Chinese/full-width punctuation fallback conversion.
     *
     * <p>The server writes logs, command output and event copy using the zh_cn baseline originals, which carry full-width punctuation and
     * typographic symbols (multiplication sign, plus-minus, arrows, ideographic full stop, greater-equal). An exact whole-sentence match
     * never shows them, but once the path falls to fragment replacement, or a sentence gets new parameters spliced in, they leak onto the
     * English screen. This is the unified fallback at the {@link #tr(String)} exit: in non-Chinese languages convert them to ASCII
     * equivalents and follow English typography by adding a space after punctuation (never doubling an existing one).
     *
     * <p>Never converted: ellipsis U+2026 (legitimately used in en_us), em dash U+2014 (equally English),
     * and Chinese-family languages ({@link #zh()} true returns the text untouched, typography byte for byte).</p>
     */
    private static String asciiPunct(String s) {
        if (s == null || s.isEmpty() || zh()) {
            return s;
        }
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep = null;
            switch (c) {
                case '\uFF08', '\uFF3B' -> rep = needSpaceBefore(s, i) ? " (" : "(";
                case '\uFF09', '\uFF3D' -> rep = needSpaceAfter(s, i) ? ") " : ")";
                case '\uFF1A' -> rep = needSpaceAfter(s, i) ? ": " : ":";
                case '\uFF1B' -> rep = needSpaceAfter(s, i) ? "; " : ";";
                case '\uFF0C' -> rep = needSpaceAfter(s, i) ? ", " : ",";
                case '\u3001' -> rep = needSpaceAfter(s, i) ? ", " : ",";
                case '\uFF01' -> rep = needSpaceAfter(s, i) ? "! " : "!";
                case '\uFF1F' -> rep = needSpaceAfter(s, i) ? "? " : "?";
                case '\u201C', '\u201D' -> rep = "\"";
                case '\u2018', '\u2019' -> rep = "'";
                case '\u3010' -> rep = needSpaceBefore(s, i) ? " [" : "[";
                case '\u3011' -> rep = needSpaceAfter(s, i) ? "] " : "]";
                case '\u300A' -> rep = needSpaceBefore(s, i) ? " <" : "<";
                case '\u300B' -> rep = needSpaceAfter(s, i) ? "> " : ">";
                case '\u3000' -> rep = " ";
                // full stops and typographic symbols genuinely exist in the baseline originals (ideographic stop, times, divide, plus-minus, minus, arrows, up/down, greater-equal); when fragment
                // replacement or splicing bypasses the whole-sentence match they leak onto the English screen; the runtime self-check judges every
                // non-ASCII character (except ellipsis and em dash) as residue, so they must all convert to ASCII equivalents here.
                case '\u3002' -> rep = needSpaceAfter(s, i) ? ". " : ".";
                case '\u00D7' -> rep = "x";
                case '\u00F7' -> rep = "/";
                case '\u00B1' -> rep = "+/-";
                case '\u2212' -> rep = "-";
                case '\u2192' -> rep = "->";
                case '\u2190' -> rep = "<-";
                case '\u2191' -> rep = "^";
                case '\u2193' -> rep = "v";
                case '\u2265' -> rep = ">=";
                case '\u2264' -> rep = "<=";
                default -> {
                    // Full-width digits / letters (U+FF10-FF19 / FF21-FF3A / FF41-FF5A) -> half-width
                    if (c >= 0xFF10 && c <= 0xFF19) {
                        rep = String.valueOf((char) ('0' + (c - 0xFF10)));
                    } else if (c >= 0xFF21 && c <= 0xFF3A) {
                        rep = String.valueOf((char) ('A' + (c - 0xFF21)));
                    } else if (c >= 0xFF41 && c <= 0xFF5A) {
                        rep = String.valueOf((char) ('a' + (c - 0xFF41)));
                    }
                }
            }
            if (rep == null) {
                if (sb != null) {
                    sb.append(c);
                }
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(s.length() + 8);
                sb.append(s, 0, i);
            }
            sb.append(rep);
        }
        return sb == null ? s : sb.toString();
    }

    /** The original logic of tr() (resolution order untouched apart from the guard). */
    private static String doTr(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        ensureBound();
        if (exact.isEmpty()) {
            return raw;
        }
        // First restore a Chinese 11.0-yi value (including the traditional form) to 1,100,000,000, then output 1.1B in English units.
        // Only unit fragments carrying digits are replaced; ordinary copy mentioning the yi/wan words is untouched.
        String normalized = zh() ? raw : normalizeChineseUnits(raw);
        String hit = exact.get(normalized);
        if (hit != null) {
            return hit;
        }
        String cached = cache.get(raw);
        if (cached != null) {
            return cached;
        }
        // The fallback path (template reverse lookup + fragment replacement) is the only screen cost that scales with stock count;
        // counts and durations accumulate here for the pure-English console profiling log under the debug switch.
        long fallbackStart = System.nanoTime();
        String formatted = applyFormatTemplate(normalized);
        String result = formatted;
        if (result == null) {
            String templated = applyStockTemplate(normalized, stockNameTranslations());
            result = templated == null ? applySegments(normalized) : applySegments(templated);
        }
        recordFallback(System.nanoTime() - fallbackStart);
        cache.put(raw, result);
        return result;
    }

    /** Fallback-path profile (read/written on one client thread only, no atomics needed; diagnostics only). */
    private static long fallbackCalls = 0L;
    private static long fallbackNanos = 0L;
    private static long fallbackWorstNanos = 0L;

    private static void recordFallback(long elapsedNanos) {
        fallbackCalls++;
        fallbackNanos += elapsedNanos;
        if (elapsedNanos > fallbackWorstNanos) {
            fallbackWorstNanos = elapsedNanos;
        }
    }

    /**
     * When the debug switch is on ({@code oremarket.toml -> debug -> enableDebugLog = true}) print a **pure English** text-resolution
     * profile: fallback count / average cost / worst cost / stock count / table size. The console is developer and server-owner
     * output, independent of the player's game language. When off, zero overhead: callers check {@code DebugLog.on()} first; this method reads a few existing fields.
     */
    public static void reportFallbackProfile(int stocksInRegistry) {
        long calls = fallbackCalls;
        long nanos = fallbackNanos;
        long worst = fallbackWorstNanos;
        fallbackCalls = 0L;
        fallbackNanos = 0L;
        fallbackWorstNanos = 0L;
        LOG.info("i18n fallback profile: stocks={}, registry-entries={}, tables(segments={}, format={}, stock={}), "
                        + "uncached-resolutions={}, avg={}us, worst={}us, cache-size={}",
                stocksInRegistry, StockRegistry.client().snapshot().size(),
                segments.size(), formatTemplates.size(), stockTemplates.size(),
                calls, String.format(java.util.Locale.ROOT, "%.1f", nanos / 1000.0 / Math.max(1L, calls)),
                String.format(java.util.Locale.ROOT, "%.1f", worst / 1000.0), cache.size());
    }

    /** Depth-overrun diagnostics: warn once per key; the screen keeps rendering the original and the game continues. */
    private static void warnRecursion(String raw) {
        if (RECURSION_WARNED.size() >= 256) {
            RECURSION_WARNED.clear();
        }
        if (RECURSION_WARNED.add(raw)) {
            LOG.warn("OreMarket translation recursion limit hit (depth {}), returning source: {}",
                    MAX_TR_DEPTH,
                    cn.oremarket.common.DebugLog.ascii(raw));
        }
    }

    /** Whether the current language is loaded (decides whether the overlay runs at all; Chinese players pay nothing). */
    public static boolean overlayActive() {
        ensureBound();
        return !exact.isEmpty();
    }

    /**
     * Language epoch: +1 every time the overlay maps rebuild. The screen layer uses it to invalidate wrap results computed on the
     * original, avoiding caching un-translated Chinese half-sentences and later re-translating them as separate copy.
     */
    public static int languageGeneration() {
        ensureBound();
        return languageGeneration;
    }

    /** Whether it contains CJK Hanzi (the screen layer uses this to detect a cached piece of not-yet-translated original). */
    public static boolean hasChinese(String s) {
        return containsCjk(s);
    }

    // ------------------------------------------------------------ log key-value rendering

    /**
     * Translation key -> current-language template. Misses return null (callers fall back); never a whole-sentence Chinese reverse
     * lookup -- that was exactly the old root of Chinese surviving in English logs.
     */
    public static String keyTemplate(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        ensureBound();
        return keyTable.get(key);
    }

    /**
     * Event name / description: prefer the current-language template by key, replacing {stock} with the translation of the first target
     * stock; only when the key is absent does it fall back to reverse lookup. This is the forward channel for the mixed-language
     * screenshot bug -- the server sends only key + stock codes, the client fills them in its own language instead of tearing a Chinese
     * sentence apart for replacement.
     */
    public static String eventPhrase(String key, String fallback, java.util.Collection<String> targetCodes) {
        if (key != null && !key.isEmpty()) {
            String tpl = keyTemplate(key);
            if (tpl != null) {
                if (tpl.contains("{stock}")) {
                    return tpl.replace("{stock}", firstStockName(targetCodes));
                }
                return tpl;
            }
            if (!zh()) {
                return key;
            }
        }
        return tr(fallback);
    }

    /** Target code list -> current-language scope string (whole market / stock names joined with slashes). */
    public static String eventTargets(java.util.Collection<String> codes, String fallback) {
        if (codes == null || codes.isEmpty()) {
            String wide = keyTemplate("oremarket.event.target.market_wide");
            return wide != null ? wide : tr(fallback);
        }
        List<Stock> act = StockRegistry.getActiveStocks();
        int active = act == null ? 0 : act.size();
        if (active > 0 && codes.size() >= active) {
            String wide = keyTemplate("oremarket.event.target.market_wide");
            return wide != null ? wide : tr(fallback);
        }
        StringBuilder sb = new StringBuilder();
        for (String code : codes) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(stockName(code));
        }
        return sb.toString();
    }

    /** Stock code -> current-language display name; custom names go through the overlay; a missing code falls back to the code itself. */
    public static String stockName(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        Stock s = StockRegistry.getByCode(code);
        if (s == null) {
            return code;
        }
        return translateStockName(s.displayName());
    }

    private static String firstStockName(java.util.Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            String several = keyTemplate("oremarket.text.0624");
            return several != null ? several : tr(cn.oremarket.common.RawText.t("oremarket.text.0624"));
        }
        return stockName(codes.iterator().next());
    }

    /** Translation key + parameters -> current-language sentence; unregistered keys return null. */
    public static String trKey(String key, String... args) {
        String tpl = keyTemplate(key);
        if (tpl == null) {
            return null;
        }
        List<String> list = new ArrayList<>(args == null ? 0 : args.length);
        if (args != null) {
            java.util.Collections.addAll(list, args);
        }
        return fillTemplate(tpl, list);
    }

    /**
     * Parameter rendering: a parameter may itself be a translation key (reason sentences the server sends purely by key, e.g.
     * short-liquidation reasons); if it matches the current table its translation is taken by key; otherwise treat it as Chinese original
     * through the zh -> current reverse table; numbers and player names insert verbatim.
     */
    public static String argText(String a) {
        if (a == null || a.isEmpty()) {
            return "";
        }
        if (a.startsWith(MOD_KEY_PREFIX)) {
            String byKey = keyTemplate(a);
            if (byKey != null) {
                return byKey;
            }
        }
        // A string carrying piece boundaries must be split and translated even with no Hanzi inside (pure numeric pieces spliced);
        // otherwise the separator itself gets drawn onto the screen.
        return containsCjk(a)
                || cn.oremarket.common.EffectText.isComposed(a)
                ? tr(a) : a;
    }

    /**
     * Fill parameters into a template: printf (%s/%d/%.2f/%1$s...) and {} placeholders consume the next argument in order,
     * positional arguments (%2$s) index directly; parameters that are Chinese (stock names, event names, buy/sell verbs) go through
     * {@link #tr} one by one; numbers and player names insert verbatim; when arguments run short the placeholder stays as-is
     * (better to show %s than to drop a character), and no exception is ever thrown.
     */
    public static String fillTemplate(String template, List<String> args) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        int n = args == null ? 0 : args.size();
        Matcher m = FORMAT_TOKEN.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 32);
        int cursor = 0;
        int implicit = 0;
        while (m.find()) {
            out.append(template, cursor, m.start());
            cursor = m.end();
            String token = m.group();
            if (token.equals("%%")) {
                out.append('%');
                continue;
            }
            int idx;
            int dollar = token.startsWith("%") ? token.indexOf('$', 1) : -1;
            if (dollar > 1) {
                int parsed = -1;
                try {
                    parsed = Integer.parseInt(token.substring(1, dollar)) - 1;
                } catch (NumberFormatException ignored) {
                    // illegal argument index falls back to the sequential slot
                }
                idx = parsed >= 0 ? parsed : implicit++;
            } else {
                idx = implicit++;
            }
            if (idx >= 0 && idx < n) {
                out.append(argText(args.get(idx)));
            } else {
                out.append(token);       // missing arguments: the placeholder stays verbatim for self-diagnosis
            }
        }
        out.append(template, cursor, template.length());
        return out.toString();
    }

    /**
     * Log entry -> current-language body.
     * Key hit: current-language template + direct parameter fill; key missing / legacy data: fall back to the original's reverse lookup.
     */
    public static String logBody(cn.oremarket.market.log.MarketLogEntry entry) {
        if (entry == null) {
            return "";
        }
        if (entry.hasKey()) {
            String tpl = keyTemplate(entry.key);
            if (tpl != null) {
                return fillTemplate(tpl, entry.args);
            }
        }
        return tr(entry.text);
    }

    /** Log prefix ([trading day X] [type] ): likewise rendered by key, no Chinese fragments concatenated anymore. */
    public static String logPrefix(long day, cn.oremarket.common.LogType type) {
        String label = keyTemplate(type.labelKey);
        String byKey = trKey("oremarket.log.entry_prefix", String.valueOf(day),
                label == null ? type.label : label);
        if (byKey != null) {
            return byKey;
        }
        return tr(cn.oremarket.common.RawText.t("oremarket.text.0088")
                + day
                + cn.oremarket.common.RawText.t("oremarket.text.0345")
                + type.label + "] ");
    }

    /**
     * Opening bracket: Chinese families get the full-width bracket, other
     * languages get half-width " (". Any hand-drawn text joining two translations into name (code) must use this method;
     * hardcoded full-width punctuation is not allowed.
     */
    public static String parenOpen() {
        return " (";
    }

    /** Right parenthesis used in composed interface labels. */
    public static String parenClose() {
        return ")";
    }

    /** Comma separator used in composed lists. */
    public static String listSep() {
        return ", ";
    }

    /** Force-drop mappings and caches (callable directly from resource-reload events; normally rebuilt automatically via instance identity). */
    public static void invalidate() {
        boundLanguage = null;
        cache.clear();
        stockNameTrCache = null;
        RECURSION_WARNED.clear();
    }

    /**
     * Unified money abbreviation entry (integer convention: coin balances, peaks, share counts): Chinese uses wan/yi/wan-yi,
     * English K/M/B/T; one value shows the same amount in both languages (1.0 yi = 100.0M, 1.0 wan = 10.0K).
     * Below the smallest tier print the plain integer.
     */
    public static String money(long v) {
        boolean neg = v < 0L;
        double abs = neg ? -(double) v : (double) v;
        return (neg ? "-" : "") + Num.unit(abs, zh(), 0);
    }

    /** Unified money abbreviation entry (floating-point convention: total assets, position value); below the smallest tier keep one decimal. */
    public static String money(double v) {
        if (v != v) {                                   // NaN
            return "0.0";
        }
        boolean neg = v < 0.0;
        return (neg ? "-" : "") + Num.unit(Math.abs(v), zh(), 1);
    }

    /** Unified money abbreviation + forced sign (total P&L / floating P&L / realised P&L / daily gain etc.). */
    public static String moneySigned(double v) {
        if (v != v) {                                   // NaN, treated as 0 on Num.signed's convention
            return "+0.0";
        }
        boolean neg = v < 0.0;
        return (neg ? "-" : "+") + Num.unit(Math.abs(v), zh(), 1);
    }

    /** Old balance abbreviation entry: now delegates uniformly to {@link #money(long)} so both languages share one numeric convention. */
    public static String ore(long v) {
        return money(v);
    }

    // ------------------------------------------------------------ mapping-table construction

    private static void ensureBound() {
        Language current = Language.getInstance();
        if (current != boundLanguage) {
            rebuild(current);
        }
    }

    private static synchronized void rebuild(Language current) {
        if (current == boundLanguage) {
            return;
        }
        boundLanguage = current;
        cache.clear();
        stockNameTrCache = null;
        RECURSION_WARNED.clear();
        rebuildCount++;
        // Language changed: every wrap cache / header string / name width measurement in the screen layer is void (epoch +1)
        languageGeneration++;
        try {
            Map<String, String> zh = zhBase();
            Map<String, String> target = current.getLanguageData();
            Map<String, String> ex = new HashMap<>(Math.max(64, zh.size() * 2));
            Map<String, String> kt = new HashMap<>(Math.max(64, zh.size() * 2));
            List<String[]> segs = new ArrayList<>(Math.max(64, zh.size() * 2));
            List<FormatTemplate> formats = new ArrayList<>();
            List<String[]> templates = new ArrayList<>();
            for (Map.Entry<String, String> e : zh.entrySet()) {
                String raw = e.getValue();
                String en = target.get(e.getKey());
                if (en != null && !en.isEmpty()) {
                    kt.put(e.getKey(), en);        // forward key -> template table (the log key-value rendering entry)
                }
                if (raw == null || raw.isEmpty() || en == null || en.isEmpty() || raw.equals(en)) {
                    continue;
                }
                ex.putIfAbsent(raw, en);
                if (hasFormatPlaceholder(raw)) {
                    FormatTemplate format = FormatTemplate.compile(raw, en);
                    if (format != null) {
                        formats.add(format);
                    }
                }
                // a single Hanzi is usually a button abbreviation (the Chinese words for price / fee / buy / sell): exact match only,
                // never a global substring, otherwise total-price would shatter into Total + P.
                if (!(raw.length() == 1 && containsCjk(raw))) {
                    segs.add(new String[]{raw, en});
                    // Spaces at the ends of registered entries vanish when the layout wraps (tokenizing on spaces,
                    // trimming tooltip lines), and the space-less half-sentence matches no registered entry any more -- shorter
                    // words bite it apart and Chinese bleeds onto the English screen. Register the de-whitespaced variant here:
                    // - always into the exact table (whole-sentence hit, zero side effects);
                    // - only 4+ character fragments (sentence pieces, low bite risk) also enter the global substring table.
                    String zhPiece = raw.strip();
                    if (zhPiece.length() != raw.length() && containsCjk(zhPiece)
                            && !containsCjk(en.strip())) {
                        ex.putIfAbsent(zhPiece, en.strip());
                        if (zhPiece.length() >= 4) {
                            segs.add(new String[]{zhPiece, en.strip()});
                        }
                    }
                }
                if (raw.contains("{stock}")) {
                    templates.add(new String[]{raw, en});
                }
            }
            segs.sort(Comparator.comparingInt((String[] s) -> -s[0].length()));
            formats.sort(Comparator.comparingInt(FormatTemplate::specificity).reversed());
            templates.sort(Comparator.comparingInt((String[] s) -> -s[0].length()));
            exact = Map.copyOf(ex);
            keyTable = Map.copyOf(kt);
            segments = List.copyOf(segs);
            formatTemplates = List.copyOf(formats);
            buildFormatBuckets(formatTemplates);
            stockTemplates = List.copyOf(templates);
            // Effective display language: if more than half the translations contain CJK Hanzi -> Chinese typography and Chinese units.
            // Traditional files hit fully; when files are missing and it fell back to en_us the translations are English -> display English.
            int cjk = 0;
            for (String v : ex.values()) {
                if (containsCjk(v)) {
                    cjk++;
                }
            }
            displayChinese = ex.isEmpty()
                    ? langCode().startsWith("zh")
                    : cjk * 2 >= ex.size();
            Set<String> runs = new java.util.HashSet<>();
            for (String value : zh.values()) {
                if (value == null) {
                    continue;
                }
                int k = 0;
                while (k < value.length()) {
                    if (isCjk(value.charAt(k))) {
                        int e = k;
                        while (e < value.length() && isCjk(value.charAt(e))) {
                            e++;
                        }
                        runs.add(value.substring(k, e));
                        k = e;
                    } else {
                        k++;
                    }
                }
            }
            registeredRuns = Set.copyOf(runs);
        } catch (RuntimeException anything) {
            // Build failure = show all originals; the screen never crashes for it (all three tables must be zeroed together,
            // or stale stockTemplates from the old language would keep participating in reverse matching)
            exact = Map.of();
            keyTable = Map.of();
            segments = List.of();
            formatTemplates = List.of();
            formatBuckets = Map.of();
            formatNoBucket = List.of();
            stockTemplates = List.of();
            displayChinese = langCode().startsWith("zh");
            LOG.warn("OreMarket language overlay rebuild failed; UI keeps source text", anything);
        }
    }

    /**
     * Bucket format templates by first character (runs once per language rebuild).
     *
     * <p>Order equivalence: each bucket is filled in one pass from the table already in global specificity order; templates without a
     * leading literal (whole matching may start at any offset) are copied into EVERY bucket at the same global position -- so for any
     * input string the per-entry decision order is byte-identical to the unbuckled scan, and neither which template matches nor the
     * translation produced ever changes.</p>
     */
    private static void buildFormatBuckets(List<FormatTemplate> ordered) {
        Set<Character> firsts = new LinkedHashSet<>();
        List<FormatTemplate> headless = new ArrayList<>();
        for (FormatTemplate t : ordered) {
            if (t.lead.isEmpty()) {
                headless.add(t);
            } else {
                firsts.add(t.lead.charAt(0));
            }
        }
        Map<Character, List<FormatTemplate>> buckets = new HashMap<>(Math.max(16, firsts.size() * 2));
        for (Character c : firsts) {
            buckets.put(c, new ArrayList<>(8));
        }
        for (FormatTemplate t : ordered) {
            if (t.lead.isEmpty()) {
                for (List<FormatTemplate> bucket : buckets.values()) {
                    bucket.add(t);
                }
            } else {
                buckets.get(t.lead.charAt(0)).add(t);
            }
        }
        formatBuckets = buckets;
        formatNoBucket = List.copyOf(headless);
    }

    /** Whether it contains CJK Hanzi (basic plane + extension A, covering all common simplified and traditional forms). */
    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x3400 && c <= 0x9fff) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> zhBase() {
        if (zhBase != null) {
            return zhBase;
        }
        Map<String, String> out = new HashMap<>(1024);
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            var res = rm.getResource(ZH_FILE);
            if (res.isPresent()) {
                parseLang(res.get(), out);
            }
        } catch (Exception e) {
            LOG.warn("Failed to load zh_cn.json source table", e);
        }
        zhBase = out;
        return out;
    }

    private static void parseLang(Resource resource, Map<String, String> out) throws Exception {
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            var json = GsonHelper.parse(reader);
            for (var entry : json.entrySet()) {
                if (entry.getValue() != null) {
                    out.put(entry.getKey(), GsonHelper.convertToString(entry.getValue(), entry.getKey()));
                }
            }
        }
    }

    /**
     * Restore numbers carrying Chinese units, then hand them to the unified English formatter, so an 11.0-yi value never becomes 11.0B.
     */
    private static String normalizeChineseUnits(String raw) {
        return cn.oremarket.common.Num
                .normalizeChineseUnitText(raw, false);
    }

    /** Decide whether a language-table value is a format template still needing matching after the server fills values. */
    private static boolean hasFormatPlaceholder(String raw) {
        return FORMAT_TOKEN.matcher(raw).find();
    }

    /**
     * First try translating runtime strings against the full template. The server often fills String.format / {} templates before
     * writing logs; if we waited for fragment replacement, short labels would shatter compound words like total price.
     */
    private static String applyFormatTemplate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;                        // empty strings skip bucket lookup (charAt(0) would overrun)
        }
        // (2) inside the bucket, still run the zero-allocation head/tail literal pre-filter before the anchored regex.
        // Both steps only skip impossible matches; the hit set and decision order stay byte-identical.
        List<FormatTemplate> candidates = formatBuckets.get(raw.charAt(0));
        if (candidates == null) {
            candidates = formatNoBucket;
        }
        for (FormatTemplate template : candidates) {
            if (!template.mayMatch(raw)) {
                continue;
            }
            Matcher matcher = template.pattern.matcher(raw);
            if (!matcher.matches()) {
                continue;
            }
            Map<Integer, String> values = new HashMap<>();
            for (FormatToken token : template.sourceTokens) {
                // never re-trigger a No group <n> overrun); a missing group treats the whole template as unmatched,
                // falling back to the fragment-replacement path instead of throwing.
                String value = cn.oremarket.common.Num
                        .safeGroup(matcher, token.group);
                if (value == null) {
                    return null;
                }
                values.putIfAbsent(token.index, value);
            }
            Matcher targetMatcher = FORMAT_TOKEN.matcher(template.target);
            StringBuffer out = new StringBuffer(template.target.length() + raw.length());
            int implicitTargetIndex = 1;
            while (targetMatcher.find()) {
                String tokenText = targetMatcher.group();
                if (tokenText.equals("%%")) {
                    targetMatcher.appendReplacement(out, "%");
                    continue;
                }
                FormatToken targetToken = FormatToken.from(tokenText, implicitTargetIndex, 0);
                if (!FormatToken.explicit(tokenText)) {
                    implicitTargetIndex++;
                }
                String value = values.get(targetToken.index);
                if (value == null) {
                    return null;
                }
                if (containsCjk(value)) {
                    value = tr(value);
                }
                targetMatcher.appendReplacement(out, Matcher.quoteReplacement(value));
            }
            targetMatcher.appendTail(out);
            return out.toString();
        }
        return null;
    }

    /** Fragment replacement: delegates to common/TemplateMatch (a pure function, unit-testable with attack cases). */
    private static String applySegments(String raw) {
        return TemplateMatch.applySegments(raw, segments);
    }

    /**
     * Stock-name translation table: reverse matching of event templates needs a Chinese-name -> current-language mapping.
     * Translations are computed here once ({@link #translateStockName(String)} never calls back into {@link #applyStockTemplate}),
     * cutting the tr() <-> applyStockTemplate() cycle at its root; the result table is invalidated together with language rebuilds /
     * registry swaps.
     *
     */
    private static List<String[]> stockNameTranslations() {
        List<String[]> snapshot = stockNameTrCache;
        if (snapshot != null) {
            return snapshot;
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        // the 8 built-in Chinese names enter the table UNCONDITIONALLY first: the client registry stays empty until SyncConfigPacket,
        // while the startup self-check and the first-frame event cards run before any packet arrives. With the table empty, whole
        // {stock}-style templates cannot match and fragment replacement shatters them into half-translated strings; the hard gate
        // then warns per leak -- the main source of the I18n leak blocked wall in English logs. Built-in names appearing in templates
        // are exactly the display names of Stock.BUILTIN_TEMPLATES (what the server's EventTemplate fills), so copying them in
        // mirrors real payloads exactly and introduces no extra mapping.
        for (Stock tpl : Stock.BUILTIN_TEMPLATES) {
            String name = tpl.displayName();
            if (name != null && !name.isEmpty()) {
                out.putIfAbsent(name, translateStockName(name));
            }
        }
        for (Stock stock : StockRegistry.client().snapshot()) {
            String name = stock.displayName();
            if (name != null && !name.isEmpty()) {
                out.putIfAbsent(name, translateStockName(name));
            }
        }
        // the multi-ores phrase is the fixed word the server fills when stock == null (EventTemplate).
        out.putIfAbsent(cn.oremarket.common.RawText.t("oremarket.text.0624"), translateStockName(cn.oremarket.common.RawText.t("oremarket.text.0624")));
        Map<String, String> table = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(out));
        // length-descending expanded table: computed once, resident; saves the fixed rebuild + sort cost per string on the
        // fallback path (measured: with 8 stocks this step alone cost 12us per string, and it grows with stock count).
        List<String[]> ordered = new ArrayList<>(table.size());
        for (Map.Entry<String, String> e : table.entrySet()) {
            ordered.add(new String[]{e.getKey(), e.getValue()});
        }
        ordered.sort(Comparator.comparingInt((String[] pair) ->
                pair[0] == null ? 0 : -pair[0].length()));
        snapshot = java.util.Collections.unmodifiableList(ordered);
        stockNameTrCache = snapshot;
        return snapshot;
    }

    /**
     * Shallow stock-name translation: exact whole string -> fragment replacement, the same logic as doTr's success path,
     * but deliberately skipping the format-template and stock-template steps -- those would re-enter tr() and form the other half of the cycle.
     */
    private static String translateStockName(String name) {
        String normalized = zh() ? name : normalizeChineseUnits(name);
        String hit = exact.get(normalized);
        return hit != null ? hit : applySegments(normalized);
    }

    /**
     * The server replaces {stock} with the Chinese stock name in event names/descriptions before sending to the client.
     * All translations are precomputed by the caller outside ({@link #stockNameTranslations()} is already length-descending);
     * the replacement algorithm delegates to common/TemplateMatch (pure function: even stock names containing {stock} or self-referencing
     * templates replace linearly once, never recursing).
     */
    private static String applyStockTemplate(String raw, List<String[]> namesByLengthDesc) {
        return TemplateMatch.applyStockTemplatesOrdered(raw, stockTemplates, namesByLengthDesc);
    }

    /** A placeholder with its argument number and regex capture-group number. */
    private static final class FormatToken {
        final int index;
        final int group;

        private FormatToken(int index, int group) {
            this.index = index;
            this.group = group;
        }

        static FormatToken from(String token, int implicitIndex, int group) {
            if (token.startsWith("%") && token.length() > 2 && token.charAt(1) != '{') {
                int dollar = token.indexOf('$', 1);
                if (dollar > 1) {
                    try {
                        return new FormatToken(Integer.parseInt(token.substring(1, dollar)), group);
                    } catch (NumberFormatException ignored) {
                        // illegal argument numbers fall through to ordinary fragment logic; a language table must never crash the screen.
                    }
                }
            }
            return new FormatToken(implicitIndex, group);
        }

        static boolean explicit(String token) {
            return token.startsWith("%") && token.indexOf('$', 1) > 1;
        }
    }

    /** Compile a template with placeholders into a whole-string matcher. */
    private static final class FormatTemplate {
        final Pattern pattern;
        final String target;
        final List<FormatToken> sourceTokens;
        final int literalLength;
        /**
         * The literal that must appear verbatim **before the first capture group** in the anchored pattern (including the % expanded from %%).
         * Empty string = the template starts with a placeholder and cannot be pre-filtered.
         */
        final String lead;
        /** The literal that must appear verbatim **after the last capture group** in the anchored pattern (including the trailing literal). */
        final String tail;

        private FormatTemplate(Pattern pattern, String target,
                               List<FormatToken> sourceTokens, int literalLength,
                               String lead, String tail) {
            this.pattern = pattern;
            this.target = target;
            this.sourceTokens = sourceTokens;
            this.literalLength = literalLength;
            this.lead = lead;
            this.tail = tail;
        }

        /**
         * Cheap pre-filter: whole-string matchers compile to {@code \A literal...\z}, so strings failing the head/tail literals can never
         * match this template. Runs before the regex; pure boolean checks, zero allocation.
         */
        boolean mayMatch(String s) {
            if (!lead.isEmpty() && !s.startsWith(lead)) {
                return false;
            }
            return tail.isEmpty() || s.endsWith(tail);
        }

        static FormatTemplate compile(String source, String target) {
            Matcher matcher = FORMAT_TOKEN.matcher(source);
            StringBuilder regex = new StringBuilder(source.length() * 2 + 8);
            List<FormatToken> tokens = new ArrayList<>();
            int cursor = 0;
            int group = 0;
            int implicitIndex = 1;
            // Required head/tail literals (for the mayMatch pre-filter): lead = all literals before the first capture group,
            // tail = all literals after the last capture group; %% contributes one literal '%'.
            StringBuilder lead = new StringBuilder();
            StringBuilder after = new StringBuilder();
            boolean seenGroup = false;
            while (matcher.find()) {
                String literal = source.substring(cursor, matcher.start());
                regex.append(Pattern.quote(literal));
                if (seenGroup) {
                    after.append(literal);
                } else {
                    lead.append(literal);
                }
                String tokenText = matcher.group();
                if (tokenText.equals("%%")) {
                    regex.append(Pattern.quote("%"));
                    if (seenGroup) {
                        after.append('%');
                    } else {
                        lead.append('%');
                    }
                    cursor = matcher.end();
                    continue;
                }
                group++;
                FormatToken token = FormatToken.from(tokenText, implicitIndex, group);
                tokens.add(token);
                if (!FormatToken.explicit(tokenText)) {
                    implicitIndex++;
                }
                if (tokenText.equals("{}") || tokenText.endsWith("s")) {
                    regex.append("(.+?)");
                } else if (tokenText.endsWith("d")) {
                    regex.append("([-+]?\\d+)");
                } else {
                    regex.append("([-+]?\\d[\\d,]*(?:\\.\\d+)?)");
                }
                seenGroup = true;
                after.setLength(0);
                cursor = matcher.end();
            }
            if (tokens.isEmpty()) {
                return null;
            }
            String endLiteral = source.substring(cursor);
            regex.append(Pattern.quote(endLiteral));
            after.append(endLiteral);
            try {
                return new FormatTemplate(Pattern.compile("\\A" + regex + "\\z", Pattern.DOTALL),
                        target, List.copyOf(tokens), literalLength(source),
                        lead.toString(), after.toString());
            } catch (RuntimeException invalidPattern) {
                LOG.warn("OreMarket cannot compile language template: {}",
                        cn.oremarket.common.DebugLog.ascii(source),
                        invalidPattern);
                return null;
            }
        }

        private static int literalLength(String source) {
            Matcher matcher = FORMAT_TOKEN.matcher(source);
            int total = source.length();
            while (matcher.find()) {
                total -= matcher.group().length();
            }
            return total;
        }

        int specificity() {
            return literalLength;
        }
    }

    /** After a stock-registry swap, drop composed caches and the stock-name table so dynamically listed stocks join event-name translation promptly. */
    public static void invalidateStockTemplates() {
        cache.clear();
        stockNameTrCache = null;
    }

    /** Diagnostics: how many times the mapping tables were rebuilt. */
    public static int rebuildCount() {
        return rebuildCount;
    }
}
