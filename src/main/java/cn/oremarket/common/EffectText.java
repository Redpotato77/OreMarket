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

import java.util.ArrayList;
import java.util.List;

/**
 * Piece encoding for multi-piece text.
 *
 * <p>Background: the mod's internal raw text is Chinese, and the client {@code L} overlay reverse-looks it up.
 * While whole sentences hit, everything is fine; but once the server joins two already-filled templates with a
 * space (event-effect shorthands do exactly that), the client receives a stitched string and can only do fragment
 * replacement. Fragment replacement is a global {@code replace}, so a short fragment bites a long word in half:
 * in the price-limit text "limit up/down 10%", the "limit down" part got replaced by {@code Limit-down} and the
 * leftover Chinese leaked into English UIs (the root cause behind the user's screenshot).</p>
 *
 * <p>Fix: whoever stitches the sentence knows the boundaries. When the server joins multi-piece text it connects
 * pieces with {@link #PIECE}, an invisible separator; on seeing it the client translates EACH piece independently
 * and rejoins them with a single space. Each piece is one fully filled template, going through the whole-sentence
 * hit / whole-format-template match path already audited to zero residue - never bitten apart by short fragments again.</p>
 *
 * <p>The separator is U+0001: absent from every lang table, mineral name and player-written text; UTF-8 encoding
 * and NBT strings round-trip it unchanged; and if some render path bypasses the translation layer, vanilla fonts
 * draw it as nothing rather than a Chinese glyph.</p>
 */
public final class EffectText {

    /** Piece separator (invisible control char; never appears in normal text). */
    public static final char PIECE = '\u0001';

    private EffectText() {
    }

    /** Whether a string is piece-encoded. */
    public static boolean isComposed(String s) {
        return s != null && s.indexOf(PIECE) >= 0;
    }

    /** Server: list of filled pieces -> encoded string (null and empty pieces are skipped outright). */
    public static String join(List<String> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(64);
        for (String p : pieces) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(PIECE);
            }
            sb.append(nest(p));
        }
        return sb.toString();
    }

    /** Server: list of filled pieces -> plain space-joined string (for display fields that get length-truncated). */
    public static String joinPlain(List<String> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(64);
        for (String p : pieces) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(nest(p));
        }
        return sb.toString();
    }

    /** Client: encoded string -> piece list (unencoded strings return a single-element list; callers may always iterate). */
    public static List<String> split(String s) {
        if (s == null || s.isEmpty()) {
            return List.of();
        }
        if (s.indexOf(PIECE) < 0) {
            return List.of(s);
        }
        List<String> out = new ArrayList<>(4);
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == PIECE) {
                if (i > start) {
                    out.add(s.substring(start, i));
                }
                start = i + 1;
            }
        }
        if (start < s.length()) {
            out.add(s.substring(start));
        }
        return out;
    }

    /** A piece being embedded in larger text: flatten its own boundaries first, so the outer split cannot bisect a sentence and translate both halves. */
    private static String nest(String p) {
        return p.indexOf(PIECE) >= 0 ? flatten(p) : p;
    }

    /**
     * Flatten every separator in a string into a plain space (nested pieces / foreign data must not fake boundaries).
     *
     * <p>If an argument embedded in a bigger sentence carries its own boundary, the outer split would slice the
     * sentence in half and translate each half separately, scrambling order and wording; flattened to a space it is
     * just an ordinary stitched string going through fragment lookup plus the {@code L.strictGate} fallback, so no Chinese leaks.</p>
     */
    public static String flatten(String s) {
        if (s == null || s.indexOf(PIECE) < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == PIECE || c == ' ') {
                space = sb.length() > 0;         // Collapse consecutive whitespace to one and leave no trailing space
                continue;
            }
            if (space) {
                sb.append(' ');
                space = false;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Length truncation: cut only at piece boundaries; never leave a half piece (a half template can never translate). */
    public static String cut(String s, int max) {
        if (s == null || s.isEmpty() || max <= 0) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        StringBuilder sb = new StringBuilder(max);
        for (String p : split(s)) {
            int need = sb.length() == 0 ? p.length() : p.length() + 1;
            if (sb.length() + need > max) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(PIECE);
            }
            sb.append(p);
        }
        if (sb.length() == 0 && !s.isEmpty()) {
            // Even the first piece does not fit: hard-cut it to keep at least readable information (residue is caught by L.strictGate)
            return s.substring(0, Math.min(max, s.length()));
        }
        return sb.toString();
    }
}
