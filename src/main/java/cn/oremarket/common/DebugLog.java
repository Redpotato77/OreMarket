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

import cn.oremarket.config.OreMarketConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Debug log switch and TraceID.
 *
 * <p>[Purpose] Gathers the forensic logs of low-level money movements - locked margin / short pool / wallet deductions -
 * behind one master switch that is OFF by default: {@code oremarket.toml -> debug -> enableDebugLog = false}.</p>
 *
 * <p><b>Zero-cost contract</b>: while the switch is false {@link #on()} is a single boolean read; every call site is written
 * as
 * <pre>if (DebugLog.on()) { DebugLog.info(key, args...); }</pre>
 * - template lookup, argument boxing and string building all happen INSIDE the if body; the off path performs NO string
 * work at all; {@link #begin()} returns null straight away when off, so not even a TraceID is generated.</p>
 *
 * <p>TraceID: one request chain (a single open / close / repayment / disbursement) generates a 6-hex-digit code at entry
 * (like {@code [T-a1b2c3]}); every log line inside the chain shares it, so concurrent requests never interleave
 * confusingly. Log text takes its templates from the en_us lang table (the console is developer / server-owner output,
 * independent of the player's game language, and must be 100% plain English); the filled text plus the TraceID
 * goes to the server log.</p>
 */
public final class DebugLog {

    private static final Logger LOG = LoggerFactory.getLogger("OreMarket/Debug");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** The TraceID lives in thread context: all server business runs on the main / command thread, one chain per thread. */
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

    private DebugLog() {
    }

    /** Master switch (reads the Forge COMMON config directly, effective instantly on hot reload; when false callers build no string). */
    public static boolean on() {
        return Boolean.TRUE.equals(OreMarketConfig.ENABLE_DEBUG_LOG.get());
    }

    /** Chain entry point: when on, generate [T-xxxxxx] and bind it to the current thread; when off return null, zero allocation. The caller must run {@code if (tr != null) DebugLog.end();} in a finally block. */
    public static String begin() {
        if (!on()) {
            return null;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        char[] buf = new char[10];
        buf[0] = '[';
        buf[1] = 'T';
        buf[2] = '-';
        for (int i = 0; i < 6; i++) {
            buf[3 + i] = HEX[r.nextInt(16)];
        }
        buf[9] = ']';
        String id = new String(buf);
        TRACE.set(id);
        return id;
    }

    /** Chain exit: unbind the TraceID (only call this when begin() returned non-null). */
    public static void end() {
        TRACE.remove();
    }

    /**
     * One debug log line prefixed with the TraceID (call sites must already be wrapped in {@code if (DebugLog.on())}).
     *
     * <p><b>Console log discipline</b>: the text takes templates from the en_us table (the {@code oremarket.debug.log.*} keys
     * are server-owner-visible logs that never enter player UI), fully independent of the player's language - Chinese or
     * English client alike, the console always prints 100% plain English. The template table lazily loads on first use
     * (only when the switch is on and a line really gets logged); if it cannot be read, degrade to 'key + arguments' concatenation.
     */
    public static void info(String key, Object... args) {
        if (!on()) {
            return;   // Double safety: even if a call site forgot the if, not a single line gets logged here
        }
        String t = TRACE.get();
        String body = formatEnglish(key, args);
        LOG.info((t == null ? "" : t + " ") + body);
    }

    /**
     * key -> raw en_us English template (unfilled). For console text like the 'Debug enabled banner';
     * same table and same rules as {@link #info}; a missing key returns the key itself (still an English identifier, Chinese can never leak in).
     */
    public static String en(String key) {
        if (key == null) {
            return "";
        }
        String tpl = englishTemplates().get(key);
        return tpl == null ? key : tpl;
    }

    /** key + args -> pure-English log body (en_us printf template; missing template or fill errors degrade safely). */
    static String formatEnglish(String key, Object... args) {
        String tpl = key == null ? null : englishTemplates().get(key);
        if (tpl == null) {
            StringBuilder sb = new StringBuilder(String.valueOf(key));
            if (args != null) {
                for (Object a : args) {
                    sb.append(' ').append(a instanceof String s ? ascii(s) : a);
                }
            }
            return sb.toString();
        }
        if (args == null || args.length == 0) {
            return tpl;
        }
        // String arguments (player names / intercept keys etc.) are sanitized first: user data such as a Chinese player name
        // must not drag non-ASCII into the console; everything is escaped before filling so each log line stays 100% English.
        Object[] safe = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            safe[i] = args[i] instanceof String s ? ascii(s) : args[i];
        }
        try {
            return String.format(java.util.Locale.ROOT, tpl, safe);
        } catch (RuntimeException badTemplate) {
            return tpl;
        }
    }

    /** The lazily loaded en_us template table for {@code oremarket.debug.*} prefixed keys (console-only text; double-checked locking). */
    private static volatile java.util.Map<String, String> enTemplates;

    private static java.util.Map<String, String> englishTemplates() {
        java.util.Map<String, String> m = enTemplates;
        if (m == null) {
            synchronized (DebugLog.class) {
                m = enTemplates;
                if (m == null) {
                    m = loadEnglishTemplates();
                    enTemplates = m;
                }
            }
        }
        return m;
    }

    /** Read en_us.json from the classpath, keeping only {@code oremarket.debug.} prefixed keys (console-only text). */
    private static java.util.Map<String, String> loadEnglishTemplates() {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        try (java.io.InputStream in = DebugLog.class.getResourceAsStream(
                "/assets/oremarket/lang/en_us.json")) {
            if (in != null) {
                String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                com.google.gson.JsonObject json =
                        com.google.gson.JsonParser.parseString(text).getAsJsonObject();
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : json.entrySet()) {
                    if (e.getKey().startsWith("oremarket.debug.") && e.getValue().isJsonPrimitive()) {
                        out.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        } catch (Throwable ignored) {
            // A failed table read only degrades to key + args concatenation; the logging path must never throw outward.
        }
        return out;
    }

    /** The current chain's TraceID (empty when off / with no chain; only used to compose sandbox command output). */
    public static String currentTrace() {
        String t = TRACE.get();
        return t == null ? "" : t;
    }

    /**
     * Console text sanitizer (the pure-English log discipline): escapes any non-ASCII character in dynamic text
     * (Chinese raw text, full-width punctuation, leaked samples) into unicode escape sequences, and collapses newline / tab
     * into spaces. Every LOG.* argument that might carry player-language text passes through this single function before
     * being written, so latest.log and Windows terminals stay plain English with no more mojibake.
     */
    public static String ascii(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
            } else if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else {
                sb.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", (int) c));
            }
        }
        return sb.toString();
    }
}
