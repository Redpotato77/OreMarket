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

package cn.oremarket.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * I18n guard for OreMarket.
 *
 * <p>Language-table checks are hard gates: the key sets must match, no Simplified Chinese may leak into the English / Traditional tables,
 * and en_us and Java string literals may only contain visible ASCII text. Project i18n convention: every Java comment under
 * src/main/java is written in English -- Chinese inside a comment is a development warning, same tier as Component.literal.
 * The bilingual .comment(...) strings Forge writes into the config file are the intended exception (they are user-facing config
 * documentation, not source comments), and diagnostic logs may still carry Chinese.</p>
 */
public final class I18nGuard {
    private static final Logger LOG = LoggerFactory.getLogger("OreMarket/I18nGuard");
    private static final String MOD_ID = "oremarket";
    private static final String[] LANGS = {"zh_cn", "en_us", "zh_hk", "zh_tw"};
    /** CJK ideograph ranges (basic plane + extension A + compatibility ideographs) as paired start/end code points. */
    private static final int[] CJK_RANGES = {
            0x3400, 0x4DBF, 0x4E00, 0x9FFF, 0xF900, 0xFAFF};
    private static final Pattern CJK = Pattern.compile(charClass(CJK_RANGES));
    private static final Pattern JAVA_STRING = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
    private static final Pattern ASCII_RUN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern PRINTF_TOKEN =
            Pattern.compile("%(?:[0-9]+\\$)?[-+ 0#]*(?:\\.[0-9]+)?[0-9]*[a-zA-Z]|%%");
    /** Recognize only the placeholders the runtime formatter supports, so an ordinary percent sign in 50% of is never mistaken for a format item. */
    private static final Pattern FORMAT_PLACEHOLDER =
            Pattern.compile("%%|%(?:([0-9]+)\\$)?[-+ 0#]*[0-9]*(?:\\.[0-9]+)?([sdfx])");
    private static final Pattern CURLY_PLACEHOLDER = Pattern.compile("\\{[A-Za-z0-9_]*\\}");
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    /**
     * Letter whitelist allowed in the zh_cn table (matches the measured token set of the current zh_cn.json):
     * all of them are technical identifiers -- config key names, class names, placeholders, ore codes, file extensions.
     * Before adding an English word, decide whether it is a technical identifier or player-facing copy that belongs in en_us.
     */
    private static final Set<String> TECHNICAL_WORDS = new HashSet<>(Arrays.asList(
            "Ctrl", "G", "END", "ID", "INCREMENTAL", "ON_DEMAND", "FULL", "K", "X", "N", "M", "B", "T",
            "NaN", "OreMarket", "MarketScreen", "ServerTickEvent", "StockRegistry",
            "config", "oremarket", "toml", "json", "zh_cn", "event",
            "marketTick", "marketTickInterval", "tick", "min", "max", "quantity", "withPnl",
            "rebuild", "render", "savedData", "startingCoins", "highestLevel", "templates",
            "shortMarginRatio", "shortLiquidationLine", "firstDayUpChance",
            "firstDayDownChance", "firstDayFlatChance",
            "COAL", "IRON", "GOLD", "DIAMOND", "EMERALD", "REDSTONE", "LAPIS", "QUARTZ",
            "COPPER", "NETHERITE",
            // Log level names, switch names and their English identifiers -- part of the config/technical-identifier whitelist convention.
            "WARNING", "Debug", "enableDebugLog"));

    private I18nGuard() {
    }

    /** Decide whether a single character falls in the CJK unified ideographs range. */
    public static boolean isChinese(char c) {
        for (int i = 0; i < CJK_RANGES.length; i += 2) {
            if (c >= CJK_RANGES[i] && c <= CJK_RANGES[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /** Paired start/end code points -> a regex character class (unicode escape sequences built from the code points; no non-ASCII literal anywhere in the source). */
    private static String charClass(int... ranges) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            sb.append("\\u").append(hex4(ranges[i]))
                    .append("-\\u").append(hex4(ranges[i + 1]));
        }
        return sb.append(']').toString();
    }

    /** Code point -> 4-digit uppercase hex escape form (BMP only; every range the guard detects sits inside the BMP). */
    private static String hex4(int cp) {
        String hex = Integer.toString(cp & 0xFFFF, 16).toUpperCase(java.util.Locale.ROOT);
        return "0000".substring(hex.length()) + hex;
    }

    /** Decide whether the text contains Chinese characters. */
    public static boolean isChinese(String text) {
        return text != null && CJK.matcher(text).find();
    }

    /** Readable alias synonymous with isChinese(String). */
    public static boolean containsChinese(String text) {
        return isChinese(text);
    }

    /** Strict assertion used by the startup self-check and by tests. */
    public static void assertNoChinese(String locale, String text) {
        if (containsChinese(text)) {
            throw new AssertionError(locale + " contains Chinese characters: " + text);
        }
    }

    /**
     * Scan the resource language tables and return every hard error; an empty list means the language tables pass.
     * root may be the project root or a resource directory directly containing assets/oremarket/lang.
     */
    public static List<String> scanLangFiles(Path root) {
        List<String> failures = new ArrayList<>();
        Path langDir = locateLangDir(root);
        if (langDir == null) {
            failures.add("language directory not found under " + root);
            return failures;
        }
        Map<String, JsonObject> tables = new HashMap<>();
        for (String lang : LANGS) {
            Path file = langDir.resolve(lang + ".json");
            if (!Files.isRegularFile(file)) {
                failures.add("missing language file: " + file);
                continue;
            }
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) {
                    failures.add("language file is not an object: " + file);
                } else {
                    tables.put(lang, parsed.getAsJsonObject());
                }
            } catch (Exception e) {
                failures.add("invalid language JSON: " + file + " (" + e.getMessage() + ")");
            }
        }
        JsonObject base = tables.get("zh_cn");
        if (base != null) {
            Set<String> baseKeys = base.keySet();
            for (String lang : LANGS) {
                JsonObject table = tables.get(lang);
                if (table != null && !baseKeys.equals(table.keySet())) {
                    Set<String> missing = new LinkedHashSet<>(baseKeys);
                    missing.removeAll(table.keySet());
                    Set<String> extra = new LinkedHashSet<>(table.keySet());
                    extra.removeAll(baseKeys);
                    failures.add(lang + " key set differs from zh_cn; missing=" + missing + ", extra=" + extra);
                }
                if (table != null && !"zh_cn".equals(lang)) {
                    checkPlaceholderParity(base, table, lang, failures);
                }
            }
        }
        // en_us: must be pure printable ASCII (0x20~0x7E). Hanzi, Chinese punctuation and full-width characters,
        // and typographic symbols such as arrows / multiplication and division signs / mathematical minus are equally violations -- on the English UI
        // they either display as Chinese-styled symbols or render as tofu boxes.
        checkEnglishPureAscii(tables.get("en_us"), "en_us", failures);
        // zh_hk / zh_tw: no residual Simplified forms allowed. The judged set = Hanzi used by zh_cn but never used by the other Traditional table
        // (the two Traditional tables cross-reference each other: a Simplified-only-to-tw form appearing in hk is a violation, and vice versa).
        Set<Character> simplifiedAbsentInTw = cjkCharsAbsentFrom(tables.get("zh_cn"), tables.get("zh_tw"));
        Set<Character> simplifiedAbsentInHk = cjkCharsAbsentFrom(tables.get("zh_cn"), tables.get("zh_hk"));
        checkSimplified(tables.get("zh_hk"), simplifiedAbsentInTw, "zh_hk", failures);
        checkSimplified(tables.get("zh_tw"), simplifiedAbsentInHk, "zh_tw", failures);
        // zh_cn: letters are only the technical whitelist (printf placeholders and {stock}-style brace placeholders are stripped first).
        JsonObject zh = tables.get("zh_cn");
        if (zh != null) {
            for (Map.Entry<String, JsonElement> entry : zh.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                String cleaned = CURLY_PLACEHOLDER.matcher(
                        PRINTF_TOKEN.matcher(entry.getValue().getAsString()).replaceAll(" "))
                        .replaceAll(" ");
                Matcher m = ASCII_RUN.matcher(cleaned);
                while (m.find()) {
                    String token = m.group();
                    if (!TECHNICAL_WORDS.contains(token)
                            && !(token.length() == 1 && Character.isUpperCase(token.charAt(0)))) {
                        failures.add("zh_cn contains non-whitelisted letters: "
                                + entry.getKey() + " -> " + token);
                    }
                }
            }
        }
        // Extended: every json under src/main/resources outside the lang directory (models, recipes, loot tables,
        // tags etc.) is packaged resource; any Hanzi in it = hardcoded Chinese, build fails.
        failures.addAll(scanResourceJsons(root));
        return failures;
    }

    /** Resource json scan: no .json under src/main/resources (except the lang directory) may contain Hanzi. */
    public static List<String> scanResourceJsons(Path root) {
        List<String> failures = new ArrayList<>();
        Path res = root.resolve("src/main/resources");
        if (!Files.isDirectory(res)) {
            res = root.resolve("resources");   // Fallback for when Gradle passes projectDir
        }
        if (!Files.isDirectory(res)) {
            return failures;
        }
        Path lang = res.resolve("assets").resolve(MOD_ID).resolve("lang");
        try (java.util.stream.Stream<Path> walk = Files.walk(res)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                if (!file.getFileName().toString().endsWith(".json")) {
                    continue;
                }
                if (lang != null && file.startsWith(lang)) {
                    continue;
                }
                String text;
                try {
                    text = decodeUnicodeEscapes(Files.readString(file, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    failures.add("cannot read resource json: " + file + " (" + e.getMessage() + ")");
                    continue;
                }
                for (int i = 0; i < text.length(); i++) {
                    if (isChinese(text.charAt(i))) {
                        failures.add("resource json contains Chinese: " + res.relativize(file));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            failures.add("cannot walk resource json tree: " + e.getMessage());
        }
        return failures;
    }

    /**
     * Placeholder consistency: printf placeholders in zh_cn templates (%s/%d/%.2f/...; positional arguments normalized by index)
     * and {stock}-style brace placeholders must appear in the same key of every language in the same count.
     * A count mismatch makes the client mis-fill or skip parameters after matching the whole template -- a frequent root of Chinese
     * surviving in English logs -- so this is a language-table hard error.
     */
    private static void checkPlaceholderParity(JsonObject base, JsonObject table,
                                               String lang, List<String> failures) {
        for (Map.Entry<String, JsonElement> entry : table.entrySet()) {
            JsonElement zhValue = base.get(entry.getKey());
            if (zhValue == null || !zhValue.isJsonPrimitive() || !entry.getValue().isJsonPrimitive()) {
                continue;
            }
            List<String> expected = placeholders(zhValue.getAsString());
            List<String> actual = placeholders(entry.getValue().getAsString());
            if (!expected.equals(actual)) {
                failures.add(lang + " placeholder mismatch at " + entry.getKey()
                        + ": zh_cn=" + expected + ", " + lang + "=" + actual);
            }
        }
    }

    /** Extract a comparable placeholder list: positional arguments lose their index, sorted by order of appearance. */
    private static List<String> placeholders(String value) {
        List<String> out = new ArrayList<>();
        Matcher m = FORMAT_PLACEHOLDER.matcher(value);
        while (m.find()) {
            String token = m.group();
            if (m.group(1) != null) {
                token = "%" + m.group(2);
            }
            out.add(token);
        }
        Collections.sort(out);
        out.addAll(CURLY_PLACEHOLDER.matcher(value).results()
                .map(r -> r.group()).sorted().collect(Collectors.toList()));
        return out;
    }

    /** The set of Hanzi used by zh_cn but never used by the reference table (a null reference is treated as the empty set). */
    private static Set<Character> cjkCharsAbsentFrom(JsonObject source, JsonObject reference) {
        Set<Character> out = new HashSet<>();
        if (source == null) {
            return out;
        }
        Set<Character> ref = new HashSet<>();
        if (reference != null) {
            for (Map.Entry<String, JsonElement> entry : reference.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    collectCjk(entry.getValue().getAsString(), ref);
                }
            }
        }
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                for (char c : entry.getValue().getAsString().toCharArray()) {
                    if (isChinese(c) && !ref.contains(c)) {
                        out.add(c);
                    }
                }
            }
        }
        return out;
    }

    private static void collectCjk(String text, Set<Character> sink) {
        for (char c : text.toCharArray()) {
            if (isChinese(c)) {
                sink.add(c);
            }
        }
    }

    /**
     * en_us pure-ASCII validation (0x20~0x7E): Hanzi, Chinese punctuation, full-width characters, plus typographic arrows /
     * multiplication-division signs / mathematical minus are all violations -- on the English UI they either look like Chinese punctuation or become tofu.
     * Same ASCII convention as scanJavaStringLiterals.
     */
    private static void checkEnglishPureAscii(JsonObject table, String lang, List<String> failures) {
        if (table == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : table.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) {
                continue;
            }
            String value = entry.getValue().getAsString();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if ((c < 0x20 && c != '\n' && c != '\r' && c != '\t') || c > 0x7E) {
                    failures.add(lang + " contains non-ASCII char at " + entry.getKey()
                            + " -> U+" + String.format("%04X", (int) c));
                    break;
                }
            }
        }
    }

    /** The table violates as soon as any value hits the forbidden character set. */
    private static void checkSimplified(JsonObject table, Set<Character> forbidden,
                                        String lang, List<String> failures) {
        if (table == null || forbidden.isEmpty()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : table.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) {
                continue;
            }
            for (char c : entry.getValue().getAsString().toCharArray()) {
                if (forbidden.contains(c)) {
                    failures.add(lang + " contains simplified residue at " + entry.getKey()
                            + " -> " + c);
                    break;
                }
            }
        }
    }

    /** LogService.push/pushAll call sites (same convention as PUSH_CALL in scripts/check_i18n.sh; comments stripped before scanning). */
    private static final Pattern PUSH_CALL = Pattern.compile("LogService\\.push\\(|LogService\\.pushAll\\(");
    /** A real translation-key literal: inside quotes, at least two segments after oremarket.; a single segment (oremarket.toml) is a file name, not a key. */
    private static final Pattern KEY_FULL = Pattern.compile("\"(oremarket(?:\\.[A-Za-z0-9_\\-]+){2,})\"");
    /** Key-prefix literals that append a suffix at runtime: quoted content ends with a dot (e.g. "oremarket.event.rarity."). */
    private static final Pattern KEY_PREFIX = Pattern.compile("\"(oremarket(?:\\.[A-Za-z0-9_\\-]+)+\\.)[^\"]*\"");

    /**
     * Log key-value discipline scan (the enforcement that keeps zero Chinese in English-environment logs; exactly one convention
     * with the discipline section of scripts/check_i18n.sh); returns hard errors:
     *
     * <ol>
     * <li>non-client packages (server/common code) must not reference the client translation functions
     * {@code L.tr(...)} / {@code client.L} -- a server calling client classes crashes a dedicated server;</li>
     * <li>{@code LogService.push / pushAll} text parameters must not compose sentences anymore
     * (any {@code RawText.} / {@code LogFormatter.} / {@code +} fragment concatenation violates);
     * player logs all go through {@code pushKey / pushAllKey}: the server sends only translation keys + parameters;</li>
     * <li>every {@code "oremarket.x.y"} translation-key literal in source must be registered in the zh_cn table
     * (the four-tables-same-key-set rule then guarantees presence in all language files); prefix literals ending in a dot
     * pass if any registered key starts with them. The scan strips comments first; the guard's own source legitimately
     * contains these marker literals and is exempted wholesale.</li>
     * </ol>
     */
    public static List<String> scanLogKeyDiscipline(Path root) {
        List<String> failures = new ArrayList<>();
        Path sourceDir = root.resolve("src/main/java");
        if (!Files.isDirectory(sourceDir)) {
            return failures;
        }
        Set<String> baseKeys = loadZhKeys(root);
        try (var stream = Files.walk(sourceDir)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
            for (Path file : files) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                // The guard source legitimately carries "LogService.push(" / "L.tr(" marker literals: exempted wholesale (same as the shell).
                if (rel.endsWith("I18nGuard.java")) {
                    continue;
                }
                String text;
                try {
                    text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    failures.add("cannot read source for i18n discipline: " + rel);
                    continue;
                }
                // 1) server/common code must not reference the client translation function
                if (!rel.contains("/client/")) {
                    String[] lines = text.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        String code = lines[i];
                        if (code.contains("L.tr(") || code.contains("client.L")) {
                            failures.add("server-side code calls client translation helper: "
                                    + rel + ":" + (i + 1));
                        }
                    }
                }
                // 2) push/pushAll text-parameter scan
                Matcher pc = PUSH_CALL.matcher(text);
                while (pc.find()) {
                    String call = balancedCall(text, pc.end() - 1);
                    if (call == null) {
                        failures.add("unbalanced LogService.push call in " + rel);
                        continue;
                    }
                    if (call.contains("RawText.") || call.contains("LogFormatter.")
                            || call.contains("+")) {
                        int line = 1 + countLines(text.substring(0, pc.start()));
                        failures.add("LogService.push carries hand-assembled text (use pushKey with a translation key): "
                                + rel + ":" + line);
                    }
                }
                // 3) key literals must be registered; dot-terminated prefix literals pass if any registered key starts with them
                List<String> prefixes = new ArrayList<>();
                Matcher kp = KEY_PREFIX.matcher(text);
                while (kp.find()) {
                    prefixes.add(kp.group(1));
                }
                if (baseKeys != null) {
                    Matcher m = KEY_FULL.matcher(text);
                    while (m.find()) {
                        String key = m.group(1);
                        if (baseKeys.contains(key)) {
                            continue;
                        }
                        boolean coveredByPrefix = false;
                        for (String p : prefixes) {
                            if (p.startsWith(key + ".")) {
                                coveredByPrefix = true;
                                break;
                            }
                        }
                        if (coveredByPrefix) {
                            continue;
                        }
                        int line = 1 + countLines(text.substring(0, m.start()));
                        failures.add("translation key not registered in zh_cn: " + key + " at " + rel + ":" + line);
                    }
                    for (String p : prefixes) {
                        boolean hits = false;
                        for (String k : baseKeys) {
                            if (k.startsWith(p)) {
                                hits = true;
                                break;
                            }
                        }
                        if (!hits) {
                            failures.add("translation key prefix matches nothing in zh_cn: " + p + " at " + rel);
                        }
                    }
                }
            }
        } catch (IOException e) {
            failures.add("source discipline scan failed: " + e.getMessage());
        }
        return failures;
    }

    /**
     * Strip comments while preserving offsets (same convention as strip_comments in the shell version): comment characters become spaces,
     * newlines are kept, so the line numbers of the scan still match the source. The extreme case of // inside a string gets wrongly
     * stripped -- the shell shares this convention; acceptable.
     */
    private static String stripComments(String src) {
        char[] out = src.toCharArray();
        int i = 0;
        int n = src.length();
        boolean inBlock = false;
        while (i < n) {
            if (inBlock) {
                if (i + 1 < n && out[i] == '*' && out[i + 1] == '/') {
                    out[i] = ' ';
                    out[i + 1] = ' ';
                    i += 2;
                    inBlock = false;
                    continue;
                }
                if (out[i] != '\n') {
                    out[i] = ' ';
                }
                i++;
                continue;
            }
            if (i + 1 < n && out[i] == '/' && out[i + 1] == '/') {
                int j = src.indexOf('\n', i);
                if (j < 0) {
                    j = n;
                }
                for (int k = i; k < j; k++) {
                    out[k] = ' ';
                }
                i = j;
                continue;
            }
            if (i + 1 < n && out[i] == '/' && out[i + 1] == '*') {
                out[i] = ' ';
                out[i + 1] = ' ';
                i += 2;
                inBlock = true;
                continue;
            }
            i++;
        }
        return new String(out);
    }

    /** Take the call text from an opening paren to its matching close paren (at most 4000 characters, so pathological source cannot hang the scan). */
    private static String balancedCall(String text, int openParen) {
        int depth = 0;
        for (int i = openParen; i < text.length() && i - openParen < 4000; i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(openParen + 1, i);
                }
            }
        }
        return null;
    }

    private static int countLines(String prefix) {
        int n = 0;
        for (int i = 0; i < prefix.length(); i++) {
            if (prefix.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** zh_cn key set (the registration baseline of the discipline scan; when unreadable the key-existence check is skipped, other disciplines still run). */
    private static Set<String> loadZhKeys(Path root) {
        Path langDir = locateLangDir(root);
        if (langDir == null) {
            return null;
        }
        Path zh = langDir.resolve("zh_cn.json");
        if (!Files.isRegularFile(zh)) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(zh, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return null;
            }
            return new HashSet<>(parsed.getAsJsonObject().keySet());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Scan Java source and return development warnings. The list never fails the build.
     * It decodes unicode escape sequences in source; config .comment(...) lines are exempted as bilingual config comments.
     * Comments themselves must be English (project i18n convention): Chinese inside a comment reports a Chinese-comment WARN.
     */
    public static List<String> scanSourceFiles(Path root) {
        List<String> warnings = new ArrayList<>();
        Path sourceDir = root.resolve("src/main/java");
        if (!Files.isDirectory(sourceDir)) {
            warnings.add("source directory not found: " + sourceDir);
            return warnings;
        }
        try (var stream = Files.walk(sourceDir)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                // This class IS the detection rule: the Component.literal( / unicode ranges inside it are rule text,
                // not player copy, so the dev-warning scan skips this file itself (the hard gate on string literals still covers it).
                if (file.getFileName().toString().equals("I18nGuard.java")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                // Keep newlines and code offsets while removing comments. This also prevents words in JavaDoc from becoming warnings.
                String codeSource = stripComments(source);
                scanCommentLanguage(source, codeSource, root.relativize(file).toString(), warnings);
                String[] lines = codeSource.split("\\n", -1);
                int[] configCommentDepth = {0};
                for (int i = 0; i < lines.length; i++) {
                    String code = lines[i];
                    // Forge config descriptions may span several lines. Exempt the complete b.comment(...) call,
                    // not just the line that happens to contain the method name.
                    boolean configCommentLine = scanConfigCommentLine(code, configCommentDepth);
                    if (!configCommentLine && isChinese(decodeUnicodeEscapes(code))) {
                        warnings.add("Chinese-in-code WARN: " + root.relativize(file) + ":" + (i + 1));
                    }
                    if (code.contains("Component.literal(")) {
                        warnings.add("Component.literal occurrence: " + root.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        } catch (IOException e) {
            warnings.add("source scan failed: " + e.getMessage());
        }
        return warnings;
    }

    /**
     * Comment-language convention: comments under src/main/java must be English. stripComments keeps offsets and blanks only
     * comment characters, so a Chinese character still present in the raw text but blanked in the stripped copy sits inside a
     * comment. This reports one development warning per such line and never touches literals (other rules own those).
     */
    private static void scanCommentLanguage(String source, String stripped, String rel, List<String> warnings) {
        int n = Math.min(source.length(), stripped.length());
        int line = 1;
        int lastWarned = -1;
        for (int i = 0; i < n; i++) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                continue;
            }
            if (isChinese(c) && stripped.charAt(i) == ' ' && line != lastWarned) {
                warnings.add("Chinese-comment WARN: " + rel + ":" + line);
                lastWarned = line;
            }
        }
    }

    /** Scan one code line and carry an open Forge b.comment(...) call across line boundaries. */
    private static boolean scanConfigCommentLine(String code, int[] depth) {
        boolean configLine = depth[0] > 0;
        for (int i = 0; i < code.length();) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipJavaLiteral(code, i);
                continue;
            }
            if (depth[0] == 0 && code.startsWith(".comment(", i)) {
                configLine = true;
                depth[0] = 1;
                i += ".comment(".length();
                continue;
            }
            if (depth[0] > 0) {
                if (c == '(') {
                    depth[0]++;
                } else if (c == ')') {
                    depth[0]--;
                }
            }
            i++;
        }
        return configLine;
    }

    /** Skip a Java string/character literal while preserving the caller's parenthesis depth. */
    private static int skipJavaLiteral(String code, int start) {
        char quote = code.charAt(start);
        int i = start + 1;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '\\') {
                i += Math.min(2, code.length() - i);
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return code.length();
    }

    /** Check language tables from the classpath at startup; on failure print the AssertionError list without blocking game startup. */
    public static void runStartupChecks() {
        List<String> failures = new ArrayList<>();
        Map<String, JsonObject> tables = new HashMap<>();
        Set<String> base = null;
        for (String lang : LANGS) {
            String resource = "/assets/oremarket/lang/" + lang + ".json";
            try (InputStream in = I18nGuard.class.getResourceAsStream(resource)) {
                if (in == null) {
                    failures.add("AssertionError: missing classpath language " + resource);
                    continue;
                }
                JsonObject table = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                tables.put(lang, table);
                if (base == null) {
                    base = new HashSet<>(table.keySet());
                } else if (!base.equals(table.keySet())) {
                    failures.add("AssertionError: key set differs in " + lang);
                }
            } catch (Exception e) {
                failures.add("AssertionError: cannot inspect " + resource + ": " + e.getMessage());
            }
        }
        // any Hanzi in en_us violates; zh_hk / zh_tw are checked with the Hanzi set used by zh_cn but absent from the other Traditional table;
        // zh_cn letters must all be in the technical whitelist. Same rule set as scanLangFiles.
        JsonObject zhBase = tables.get("zh_cn");
        if (zhBase != null) {
            for (String lang : LANGS) {
                JsonObject table = tables.get(lang);
                if (table != null && !"zh_cn".equals(lang)) {
                    checkPlaceholderParity(zhBase, table, lang, failures);
                }
            }
        }
        checkEnglishPureAscii(tables.get("en_us"), "en_us", failures);
        checkSimplified(tables.get("zh_hk"),
                cjkCharsAbsentFrom(tables.get("zh_cn"), tables.get("zh_tw")), "zh_hk", failures);
        checkSimplified(tables.get("zh_tw"),
                cjkCharsAbsentFrom(tables.get("zh_cn"), tables.get("zh_hk")), "zh_tw", failures);
        JsonObject zh = tables.get("zh_cn");
        if (zh != null) {
            for (Map.Entry<String, JsonElement> entry : zh.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                String cleaned = CURLY_PLACEHOLDER.matcher(
                        PRINTF_TOKEN.matcher(entry.getValue().getAsString()).replaceAll(" "))
                        .replaceAll(" ");
                Matcher m = ASCII_RUN.matcher(cleaned);
                while (m.find()) {
                    String token = m.group();
                    if (!TECHNICAL_WORDS.contains(token)
                            && !(token.length() == 1 && Character.isUpperCase(token.charAt(0)))) {
                        failures.add("AssertionError: zh_cn non-whitelisted letters at "
                                + entry.getKey() + " -> " + token);
                    }
                }
            }
        }
        if (failures.isEmpty()) {
            LOG.info("[OreMarket] I18nGuard startup checks passed");
        } else {
            for (String failure : failures) {
                LOG.error(failure);
            }
        }
    }

    /** Typical values used to fill templates in the self-check: digit slots get numeric samples, string slots get a registered Chinese stock name (source stays ASCII). */
    private static final String SAMPLE_NUMBER = "3";
    private static final String SAMPLE_DECIMAL = "0.85";
    private static final String SAMPLE_TEXT =
            String.valueOf((char) 0x7164) + String.valueOf((char) 0x70AD);

    /**
     * Runtime overlay self-check.
     *
     * <p>Statically scanning language tables only proves the tables themselves are clean; this closes the behaviour AFTER assembly: every
     * zh_cn baseline template is filled with typical parameters into the exact sentence the server would send, fed to the current language's
     * translation entry, and any Hanzi or full-width symbol in the result counts as a leak. The two families the server really joins --
     * effect abbreviations and hover tooltips -- are each additionally joined twice via {@link EffectText#join} and a bare space.</p>
     *
     * <p>The (client) caller feeds keys batch by batch per tick, dozens per batch, so a full-table run never stalls the render thread.</p>
     *
     * @param key the zh_cn baseline key to check
     * @param translate the current language's translation entry (the client passes {@code L::trForScan})
     * @param sink list collecting residue descriptions (cleared and printed by the caller)
     * @param max stop appending after this many entries
     * @param prevSample previously filled sample for the two-piece join check; null skips it
     */
    public static void scanOverlayLeaksOne(String key,
                                           java.util.function.UnaryOperator<String> translate,
                                           List<String> sink, int max, String prevSample) {
        if (key == null || translate == null || sink == null || sink.size() >= max) {
            return;
        }
        String raw = RawText.t(key);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String sample = fillSample(raw);
        reportLeaks(sink, max, key, sample, translate);
        // Layout splits tokens on spaces and trims lines, so leading/trailing whitespace in registered copy is lost on screen.
        // A half-sentence that lost its whitespace is no longer any registered original; only shorter words can bite it into Chinese fragments --
        // so the self-check must run again in the shape the layout actually feeds the translation layer: whole sentence de-whitespaced + first token.
        String stripped = sample.strip();
        if (!stripped.equals(sample) && !stripped.isEmpty()) {
            reportLeaks(sink, max, key + " +trim", stripped, translate);
        }
        int space = stripped.indexOf(' ');
        if (space > 1) {
            reportLeaks(sink, max, key + " +token", stripped.substring(0, space), translate);
        }
        // The join check only covers templates the server really concatenates (effects/tooltips). Forcing unrelated registered copy such as
        // repayment diagnostics and price multipliers into one sentence means the client can only do fragment replacement, and
        // residual symbols would flood the report as leaks -- that is not a path players can ever see.
        if (prevSample != null && !prevSample.isEmpty()
                && isComposableEffectKey(key)) {
            reportLeaks(sink, max, key + " +pieces",
                    EffectText.join(Arrays.asList(prevSample, sample)), translate);
            reportLeaks(sink, max, key + " +joined", prevSample + " " + sample, translate);
        }
    }

    /**
     * The template family the server joins via {@link EffectText#join}: the second line of event cards, effect abbreviations
     * ({@code oremarket.event.effect.*}) and hover tooltips ({@code oremarket.event.tooltip.*}).
     * All other registered copy (command output, repayment diagnostics, config summaries) is a standalone sentence the client never
     * hard-joins with a space before handing to the overlay.
     */
    public static boolean isComposableEffectKey(String key) {
        return key != null && (key.startsWith("oremarket.event.effect.")
                || key.startsWith("oremarket.event.tooltip."));
    }

    /** The sample sentence this key produces when filled with typical parameters (kept by the caller as the next round's two-piece join material). */
    public static String sampleFor(String key) {
        String raw = key == null ? null : RawText.t(key);
        return raw == null || raw.isEmpty() ? null : fillSample(raw);
    }

    private static void reportLeaks(List<String> leaks, int max, String tag, String input,
                                    java.util.function.UnaryOperator<String> translate) {
        if (leaks.size() >= max || input == null || input.isEmpty()) {
            return;
        }
        String out;
        try {
            out = translate.apply(input);
        } catch (RuntimeException crash) {
            leaks.add("overlay threw on " + tag + ": in=" + abbrev(input) + " -> " + crash);
            return;
        }
        if (out == null) {
            leaks.add("overlay returned null for " + tag + ": in=" + abbrev(input));
            return;
        }
        String bad = firstNonAsciiVisible(out);
        if (bad != null) {
            leaks.add("overlay leak in " + tag + ": in=" + abbrev(input)
                    + " out=" + abbrev(out) + " bad=U+" + bad);
        }
    }

    /**
     * The first character in the result that must not appear on non-Chinese UIs.
     * Ellipsis, em dash (both legitimately used in en_us) and the translation key written back by the hard gate are not leaks --
     * keys are ASCII and never reach here; this only stops Hanzi, full-width punctuation and stray multiplication signs / arrows.
     */
    private static String firstNonAsciiVisible(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7E && c != 0x2026 && c != 0x2014) {
                return String.format("%04X", (int) c);
            }
        }
        return null;
    }

    private static String abbrev(String s) {
        String oneLine = s.replace('\n', ' ');
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "...";
    }

    /** Fill typical values by placeholder type: {@code %d} / {@code %.2f} / {@code %s} / {@code {}} each have a stable sample. */
    private static String fillSample(String template) {
        Matcher m = FORMAT_PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder(template.length() + 16);
        int cursor = 0;
        while (m.find()) {
            sb.append(template, cursor, m.start());
            cursor = m.end();
            String token = m.group();
            if (token.equals("%%")) {
                sb.append('%');
                continue;
            }
            char kind = token.charAt(token.length() - 1);
            sb.append(kind == 'd' ? SAMPLE_NUMBER : kind == 'f' ? SAMPLE_DECIMAL : SAMPLE_TEXT);
        }
        sb.append(template, cursor, template.length());
        return CURLY_PLACEHOLDER.matcher(sb).replaceAll(SAMPLE_TEXT);
    }

    /** Java character literals (including escapes): full-width punctuation such as an ideographic comma also leaks into player-visible text. */
    private static final Pattern JAVA_CHAR = Pattern.compile("'(?:\\\\.|[^'\\\\])'");

    /**
     * Scan Java string and character literals: directly written CJK / Chinese punctuation / full-width characters fail the build.
     * Comments are not literals; every other Java string/character is scanned with no exemptions.
     * When Chinese is needed use unicode escape sequences or {@code String.valueOf((char) 0x...)} (the source itself stays ASCII).
     */
    public static List<String> scanJavaStringLiterals(Path root) {
        List<String> failures = new ArrayList<>();
        Path sourceDir = root.resolve("src/main/java");
        if (!Files.isDirectory(sourceDir)) {
            return failures;
        }
        try (var stream = Files.walk(sourceDir)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    failures.add("cannot read Java source: " + rel);
                    continue;
                }
                boolean inBlock = false;
                for (int i = 0; i < lines.size(); i++) {
                    String original = lines.get(i);
                    String line = original;
                    if (inBlock) {
                        int end = line.indexOf("*/");
                        if (end < 0) {
                            continue;
                        }
                        line = line.substring(end + 2);
                        inBlock = false;
                    }
                    while (true) {
                        int start = line.indexOf("/*");
                        if (start < 0) {
                            break;
                        }
                        int end = line.indexOf("*/", start + 2);
                        if (end < 0) {
                            line = line.substring(0, start);
                            inBlock = true;
                            break;
                        }
                        line = line.substring(0, start) + line.substring(end + 2);
                    }
                    int lineComment = line.indexOf("//");
                    if (lineComment >= 0) {
                        line = line.substring(0, lineComment);
                    }
                    Matcher m = JAVA_STRING.matcher(line);
                    while (m.find()) {
                        String inner = m.group();
                        inner = inner.substring(1, inner.length() - 1);
                        if (inner.chars().anyMatch(c -> c < 0x20 || c > 0x7E)) {
                            failures.add("Java string contains non-ASCII at " + rel + ":" + (i + 1)
                                    + " " + inner);
                        }
                    }
                    Matcher cm = JAVA_CHAR.matcher(line);
                    while (cm.find()) {
                        String token = cm.group();
                        String inner = token.substring(1, token.length() - 1);
                        if (inner.chars().anyMatch(c -> c < 0x20 || c > 0x7E)) {
                            failures.add("Java char literal contains non-ASCII at " + rel + ":" + (i + 1)
                                    + " " + token);
                        }
                    }
                }
            }
        } catch (IOException e) {
            failures.add("Java string scan failed: " + e.getMessage());
        }
        return failures;
    }

    // =============================================== multilingual sync gate for all NEW content
    //
    // The three checks below are the mechanical guarantee that every future addition must pass the I18n sync;
    // all hang on gradle checkI18n (a build dependency); violating any one fails compilation:
    //
    // 1. scanKeyReferences     -- every full translation key written in Java must exist in all four language tables;
    // 2. scanComposedSentences -- full sentences composed with + in code must translate wholly in English, not one Hanzi left;
    // 3. scanTrimmedFragments  -- registered copy half-sentences after the layout trims their leading/trailing whitespace
    // must likewise translate wholly in English.
    //
    // The decision follows the client overlay (client/L)'s own resolution order: whole-sentence exact ->
    // parameterised templates -> longest-first substring replacement. This is a pure-JDK replica; it must not import client classes.

    /** Read a language table as key -> value (missing file / parse failure returns null and the caller skips). */
    private static Map<String, String> loadTable(Path root, String lang) {
        Path langDir = locateLangDir(root);
        if (langDir == null) {
            return null;
        }
        Path file = langDir.resolve(lang + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return null;
            }
            Map<String, String> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    out.put(e.getKey(), e.getValue().getAsString());
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** The alternating literal + argument-index sequence a template decomposes into (index -1 means a pure literal piece). */
    private static final class TemplateParts {
        final List<String> literals = new ArrayList<>();
        final List<Integer> args = new ArrayList<>();
    }

    private static final Pattern PLACEHOLDER =
            Pattern.compile("%%|%(?:(\\d+)\\$)?[-+ 0#]*\\d*(?:\\.\\d+)?([sdfx])");

    /** Split a template: literals are cut at placeholders; {@code %%} is a literal '%'; positional arguments record their index. */
    private static TemplateParts parts(String text) {
        TemplateParts p = new TemplateParts();
        Matcher m = PLACEHOLDER.matcher(text);
        int last = 0;
        int seq = 0;
        StringBuilder literal = new StringBuilder();
        while (m.find()) {
            literal.append(text, last, m.start());
            last = m.end();
            if ("%%".equals(m.group())) {
                literal.append('%');
                continue;
            }
            p.literals.add(literal.toString());
            p.args.add(m.group(1) == null ? ++seq : Integer.parseInt(m.group(1)));
            literal.setLength(0);
        }
        literal.append(text.substring(last));
        p.literals.add(literal.toString());
        p.args.add(-1);
        return p;
    }

    /** Refill captured groups into the translated template by argument index (same convention as the client FormatTemplate). */
    private static String fillParts(TemplateParts target, Map<Integer, String> captured) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < target.literals.size(); i++) {
            sb.append(target.literals.get(i));
            int arg = target.args.get(i);
            if (arg > 0) {
                String v = captured.get(arg);
                sb.append(v == null ? "" : v);
            }
        }
        return sb.toString();
    }

    /** Argument count (positional arguments take the max index; a repeated %1$s counts as one slot). */
    private static int argCount(TemplateParts p) {
        int max = 0;
        for (int a : p.args) {
            if (a > max) {
                max = a;
            }
        }
        return max;
    }

    /**
     * The offline replica of the overlay (same registration rules as {@code client/L.rebuild}), shared by the three gates:
     * whole-sentence exact (including the de-whitespaced variant) -> parameterised templates -> longest-first substring replacement.
     */
    private static final class OverlayReplica {
        private final Map<String, String> zh;
        private final Map<String, String> target;
        private final Map<String, String> exact = new HashMap<>();
        private final List<String> templateKeys = new ArrayList<>();
        private final List<String[]> segments = new ArrayList<>();

        OverlayReplica(Map<String, String> zh, Map<String, String> target) {
            this.zh = zh;
            this.target = target;
            for (Map.Entry<String, String> e : zh.entrySet()) {
                String raw = e.getValue();
                String tr = target.get(e.getKey());
                if (raw == null || raw.isEmpty() || tr == null || tr.isEmpty() || raw.equals(tr)) {
                    continue;
                }
                if (raw.indexOf('%') >= 0) {
                    templateKeys.add(e.getKey());
                }
                exact.putIfAbsent(raw, tr);
                if (raw.length() == 1 && containsChinese(raw)) {
                    continue;                       // one-character abbreviations only participate in exact matching, never as global substrings
                }
                segments.add(new String[]{raw, tr});
                // In sync with L.rebuild: leading/trailing spaces in registered copy are lost when the layout wraps; the de-whitespaced variant
                // always enters the exact table; only fragments of 4+ characters (sentence pieces) also enter the global substring table
                String piece = raw.strip();
                if (piece.length() != raw.length() && containsChinese(piece)
                        && !containsChinese(tr.strip())) {
                    exact.putIfAbsent(piece, tr.strip());
                    if (piece.length() >= 4) {
                        segments.add(new String[]{piece, tr.strip()});
                    }
                }
            }
            segments.sort((a, b) -> Integer.compare(b[0].length(), a[0].length()));
        }

        /** Translate one Chinese original (filled with runtime-shaped parameters) into the target language; **no hard-gate character dropping**. */
        String translate(String raw) {
            if (raw == null || raw.isEmpty()) {
                return raw == null ? "" : raw;
            }
            String hit = exact.get(raw);
            if (hit != null) {
                return hit;
            }
            for (String key : templateKeys) {
                String src = zh.get(key);
                String dst = target.get(key);
                if (src == null || dst == null || dst.isEmpty() || src.equals(dst)) {
                    continue;
                }
                TemplateParts from = parts(src);
                TemplateParts to = parts(dst);
                if (argCount(from) == 0 || argCount(from) != argCount(to)
                        || from.literals.size() != to.literals.size()) {
                    continue;
                }
                StringBuilder regex = new StringBuilder();
                List<Integer> groupArg = new ArrayList<>();
                for (int i = 0; i < from.literals.size(); i++) {
                    regex.append(Pattern.quote(from.literals.get(i)));
                    int arg = from.args.get(i);
                    if (arg > 0 && i < from.literals.size() - 1) {
                        regex.append("(.*?)");
                        groupArg.add(arg);
                    }
                }
                Matcher m = Pattern.compile(regex.toString()).matcher(raw);
                if (m.matches()) {
                    Map<Integer, String> captured = new HashMap<>();
                    for (int i = 0; i < groupArg.size() && i < m.groupCount(); i++) {
                        captured.put(groupArg.get(i), m.group(i + 1));
                    }
                    return fillParts(to, captured);
                }
            }
            String out = raw;
            for (String[] seg : segments) {
                if (out.indexOf(seg[0]) >= 0) {
                    out = out.replace(seg[0], seg[1]);
                }
            }
            return out;
        }
    }

    /** Fill placeholders into sample parameters by the runtime shape (same convention as I18nGuard.fillSample; plain text). */
    private static String fillProbe(String raw) {
        Matcher m = PLACEHOLDER.matcher(raw);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(raw, last, m.start());
            last = m.end();
            if ("%%".equals(m.group())) {
                sb.append('%');
                continue;
            }
            char kind = m.group(2) == null ? 's' : m.group(2).charAt(0);
            sb.append(kind == 'd' ? "3" : kind == 'f' ? "0.85" : "1");
        }
        sb.append(raw.substring(last));
        return sb.toString();
    }

    /** The position of the first Hanzi in a translation (used to report fragments). */
    private static String firstChineseRun(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isChinese(s.charAt(i))) {
                int j = i;
                while (j < s.length() && (isChinese(s.charAt(j)) || !asciiVisible(s.charAt(j)))) {
                    j++;
                }
                return s.substring(i, Math.min(j, i + 24));
            }
        }
        return "";
    }

    private static boolean asciiVisible(char c) {
        return c >= 0x20 && c <= 0x7E;
    }

    /** Gate one: every full translation key appearing in Java must exist in all four language tables. */
    public static List<String> scanKeyReferences(Path root) {
        List<String> failures = new ArrayList<>();
        Path sourceDir = root.resolve("src/main/java");
        if (!Files.isDirectory(sourceDir)) {
            return failures;
        }
        Map<String, Map<String, String>> tables = new HashMap<>();
        for (String lang : LANGS) {
            Map<String, String> t = loadTable(root, lang);
            if (t == null) {
                return failures;                       // missing tables are reported by scanLangFiles; not repeated here
            }
            tables.put(lang, t);
        }
        Map<String, String> zh = tables.get("zh_cn");
        Pattern ref = Pattern.compile("\"(oremarket\\.[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)\"");
        try (var stream = Files.walk(sourceDir)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
            for (Path file : files) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                String text;
                try {
                    text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    failures.add("cannot read source for key reference scan: " + rel);
                    continue;
                }
                Matcher m = ref.matcher(text);
                while (m.find()) {
                    String key = m.group(1);
                    // key prefixes assembled at runtime (followed by +) are outside this gate: the full key only exists at runtime
                    int after = m.end();
                    while (after < text.length() && (text.charAt(after) == ' ' || text.charAt(after) == '\n')) {
                        after++;
                    }
                    if (after < text.length() && text.charAt(after) == '+') {
                        continue;
                    }
                    // literal keys whose suffix (.short / .long style) is concatenated by code are likewise skipped; only the literal itself is checked
                    if (!zh.containsKey(key) && !key.endsWith(".short")) {
                        // absent even in zh_cn = mistyped key name / never registered; the four tables then certainly miss it too
                        failures.add("translation key used in code but missing in zh_cn: "
                                + key + " at " + rel);
                        continue;
                    }
                    for (Map.Entry<String, Map<String, String>> t : tables.entrySet()) {
                        if (!t.getValue().containsKey(key)) {
                            failures.add("translation key missing in " + t.getKey() + ": "
                                    + key + " (used at " + rel + ")");
                        }
                    }
                }
            }
        } catch (IOException e) {
            failures.add("key reference scan failed: " + e.getMessage());
        }
        return failures;
    }

    /** A RawText.t chain joined adjacently (the gap may contain only + and whitespace), at most 8 segments. */
    private static final Pattern RAWTEXT_CALL =
            Pattern.compile("RawText\\.t\\(\"([A-Za-z0-9_.]+)\"\\)");

    /** Gate two: full sentences composed with + in code must translate wholly against the English table, not one Hanzi allowed. */
    public static List<String> scanComposedSentences(Path root) {
        List<String> failures = new ArrayList<>();
        Path sourceDir = root.resolve("src/main/java");
        Map<String, String> zh = loadTable(root, "zh_cn");
        Map<String, String> en = loadTable(root, "en_us");
        if (!Files.isDirectory(sourceDir) || zh == null || en == null) {
            return failures;
        }
        final OverlayReplica replica = new OverlayReplica(zh, en);
        try (var stream = Files.walk(sourceDir)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
            for (Path file : files) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (rel.endsWith("I18nGuard.java")) {
                    continue;                          // this class carries its own "RawText.t(" marker literals
                }
                String text;
                try {
                    text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    failures.add("cannot read source for composition scan: " + rel);
                    continue;
                }
                Matcher m = RAWTEXT_CALL.matcher(text);
                List<int[]> spans = new ArrayList<>();
                List<String> keys = new ArrayList<>();
                while (m.find()) {
                    spans.add(new int[]{m.start(), m.end()});
                    keys.add(m.group(1));
                }
                for (int i = 0; i + 1 < spans.size(); i++) {
                    // only accept pure concatenation chains where adjacent segments contain no operator other than + and whitespace
                    String gap = text.substring(spans.get(i)[1], spans.get(i + 1)[0]);
                    if (!gap.replace("+", "").trim().isEmpty()) {
                        continue;
                    }
                    StringBuilder sentence = new StringBuilder();
                    StringBuilder chain = new StringBuilder();
                    int j = i;
                    while (j < spans.size()) {
                        if (j > i) {
                            String g = j - 1 >= 0 && j - 1 < spans.size() - 1
                                    ? text.substring(spans.get(j - 1)[1], spans.get(j)[0]) : "+";
                            if (!g.replace("+", "").trim().isEmpty()) {
                                break;
                            }
                        }
                        String key = keys.get(j);
                        String val = zh.get(key);
                        if (val == null) {
                            break;
                        }
                        sentence.append(val);
                        chain.append(key).append(" + ");
                        // stop when the next segment of the chain is not a pure concatenation
                        if (j + 1 >= spans.size()) {
                            break;
                        }
                        String nextGap = text.substring(spans.get(j)[1], spans.get(j + 1)[0]);
                        if (!nextGap.replace("+", "").trim().isEmpty()) {
                            break;
                        }
                        j++;
                    }
                    if (sentence.length() == 0 || j <= i) {
                        continue;
                    }
                    int line = 1 + countChar(text.subSequence(0, spans.get(i)[0]), '\n');
                    // non-key segments (numbers / variables) are filled with sample parameters: same shape as what runtime shows
                    String probe = fillProbe(sentence.toString());
                    String out = replica.translate(probe);
                    if (containsChinese(out)) {
                        failures.add("composed sentence leaks Chinese in en_us: " + rel + ":" + line
                                + " [" + chain.toString().trim() + "] -> " + firstChineseRun(out));
                    }
                }
            }
        } catch (IOException e) {
            failures.add("composition scan failed: " + e.getMessage());
        }
        return failures;
    }

    /** Gate three: registered copy trimmed of leading/trailing whitespace by the layout must translate wholly in English too. */
    public static List<String> scanTrimmedFragments(Path root) {
        List<String> failures = new ArrayList<>();
        Map<String, String> zh = loadTable(root, "zh_cn");
        Map<String, String> en = loadTable(root, "en_us");
        if (zh == null || en == null) {
            return failures;
        }
        final OverlayReplica replica = new OverlayReplica(zh, en);
        for (Map.Entry<String, String> e : zh.entrySet()) {
            String raw = e.getValue();
            if (raw == null || raw.isEmpty() || raw.equals(raw.strip())) {
                continue;                              // only check registered copy that carries leading/trailing whitespace
            }
            // the shapes the UI really feeds the translation layer: the whole sentence, and the whole sentence DE-whitespaced
            // (wrapping splits tokens on spaces, tooltips trim per line; trailing registration spaces are lost exactly this way).
            // single tokens are not checked: copy is always translated whole first, then wrapped by width,
            // so an English token produced by wrapping can never return to the translation layer.
            for (String shape : new String[]{fillProbe(raw), fillProbe(raw).strip()}) {
                if (shape == null || shape.length() < 2 || !containsChinese(shape)) {
                    continue;
                }
                String out = replica.translate(shape);
                if (containsChinese(out)) {
                    failures.add("registered sentence leaks Chinese in en_us: " + e.getKey()
                            + " shape=[" + shape + "] -> " + firstChineseRun(out));
                }
            }
        }
        return failures;
    }

    private static int countChar(CharSequence s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** Entry point of Gradle checkI18n; any violation in language tables, log discipline, Java literals or the new-content sync gates fails. */
    public static void main(String[] args) {
        Path root = args.length == 0 ? Path.of(".") : Path.of(args[0]);
        List<String> languageFailures = scanLangFiles(root);
        languageFailures.addAll(scanLogKeyDiscipline(root));
        languageFailures.addAll(scanJavaStringLiterals(root));
        languageFailures.addAll(scanKeyReferences(root));
        languageFailures.addAll(scanComposedSentences(root));
        languageFailures.addAll(scanTrimmedFragments(root));
        List<String> sourceWarnings = scanSourceFiles(root);
        for (String warning : sourceWarnings) {
            System.out.println("[I18nGuard] WARN " + warning);
        }
        if (!languageFailures.isEmpty()) {
            for (String failure : languageFailures) {
                System.err.println("[I18nGuard] ERROR " + failure);
            }
            System.exit(1);
        }
        System.out.println("[I18nGuard] language checks passed");
    }

    private static Path locateLangDir(Path root) {
        Path direct = root.resolve("assets").resolve(MOD_ID).resolve("lang");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path project = root.resolve("src/main/resources/assets").resolve(MOD_ID).resolve("lang");
        return Files.isDirectory(project) ? project : null;
    }

    private static String decodeUnicodeEscapes(String text) {
        Matcher matcher = UNICODE_ESCAPE.matcher(text == null ? "" : text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            char decoded = (char) Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(decoded)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
