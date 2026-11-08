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

import java.util.Locale;

/**
 * Single entry point for log text (multilingual enforcement, item 13).
 *
 * When writing player logs the server must no longer hand-assemble Chinese or English sentences: always pass a
 * language key + arguments; this class takes the template from the built-in zh_cn baseline table and fills it
 * printf-style. The generated "raw text" matches the lang table verbatim, so the client {@code L} overlay hits the
 * whole sentence in the player's current language - English logs therefore keep zero Chinese residue.
 *
 * Division of labor with {@link RawText#f(String, Object...)}: RawText provides the generic "fetch template + fill"
 * capability; this class is the semantic entry for player logs (log / broadcast / toast text all go through it).
 * Any new log text must register a key and call this class - direct string assembly is not allowed.
 */
public final class LogFormatter {

    private LogFormatter() {
    }

    /** key -> raw template in the registered baseline language (zh_cn); arguments not filled. */
    public static String text(String key) {
        return RawText.t(key);
    }

    /** key + args -> finished log sentence (degrades to the bare template on placeholder/arg mismatch; never throws). */
    public static String format(String key, Object... args) {
        String tpl = RawText.t(key);
        if (args == null || args.length == 0 || key == null) {
            return tpl;
        }
        try {
            return String.format(Locale.ROOT, tpl, flattenArgs(args));
        } catch (RuntimeException badTemplate) {
            org.slf4j.LoggerFactory.getLogger("OreMarket/LogFormatter")
                    .warn("bad placeholder usage in log key {}", key, badTemplate);
            return tpl;
        }
    }

    /**
     * Flatten piece boundaries before filling a whole sentence: once a multi-piece text (event-effect shorthand) is
     * embedded in a larger sentence, its inner boundaries must not be split again by the outer layer - whole-sentence
     * reverse lookup is the correct behavior for this fallback path. The argument list actually shipped to the client
     * lives in {@code MarketLogEntry.args}, where the encoded form is preserved as-is.
     */
    private static Object[] flattenArgs(Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = args[i] instanceof String s ? EffectText.flatten(s) : args[i];
        }
        return out;
    }

    /** Placeholder scan identical to the client's L.fillTemplate (printf style + {} logging style). */
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile(
            "%%|%(\\d+\\$)?[-+ 0#]*\\d*(?:\\.\\d+)?[sdfx]|\\{\\}");

    /**
     * Lenient filling: replace %s/%d/%.2f/{} style
     * placeholders in order of appearance with String.valueOf(arg); positional args (%2$s) are taken by index; %% becomes %.
     * No printf type checking - args may already be formatted strings, and the conversion must match the client renderer,
     * so one sentence can never appear in two spellings on the two sides.
     */
    public static String formatLoose(String key, Object... args) {
        String tpl = RawText.t(key);
        if (tpl == null || tpl.isEmpty()) {
            return "";
        }
        int n = args == null ? 0 : args.length;
        java.util.regex.Matcher m = TOKEN.matcher(tpl);
        StringBuilder out = new StringBuilder(tpl.length() + 32);
        int cursor = 0;
        int implicit = 0;
        while (m.find()) {
            out.append(tpl, cursor, m.start());
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
                    parsed = -1;
                }
                idx = parsed >= 0 ? parsed : implicit++;
            } else {
                idx = implicit++;
            }
            if (idx >= 0 && idx < n) {
                out.append(args[idx] instanceof String s ? EffectText.flatten(s) : args[idx]);
            } else {
                out.append(token);
            }
        }
        out.append(tpl, cursor, tpl.length());
        return out.toString();
    }
}
