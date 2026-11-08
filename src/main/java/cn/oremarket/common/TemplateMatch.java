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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The pure-string parsing core for language templates (extracted out of {@code client.L}: no callbacks, no state,
 * directly unit-testable with attack cases).
 *
 * Design constraint: these two methods NEVER call any translation entry - they do one-pass linear replacement only;
 * every needed translation is computed by the caller OUTSIDE the methods and passed in. This is the root-cause cut for
 * the historic {@code tr()} <-> applyStockTemplate() infinite-recursion crash (see RECURSION_ROOT_CAUSE.md):
 * even a self-referential input (a malicious stock name containing "{stock}", or a source==target loop template)
 * degrades into ordinary string replacement and cannot recurse.
 *
 * (1) rebuilding and re-sorting the name table on every call, (2) doing two {@link String#replace} allocations
 * unconditionally for every 'template x stock name' combination, (3) multi-character scans over all 1400 fragment entries.</p>
 */
public final class TemplateMatch {

    private TemplateMatch() {
    }

    /**
     * Character presence-stamp table (one per thread, fixed 256 KB, never growing with stock count / fragment table):
     * {@code stamp[c] == epoch} means character c occurs in the string currently being replaced.
     * A generation number replaces clearing: a new string just bumps epoch + 1, so no per-frame allocation ever happens.
     */
    private static final ThreadLocal<int[]> CHAR_STAMP = ThreadLocal.withInitial(() -> new int[65536]);
    /** The epoch lives in an int[1]: reads and writes never box, so thousands of calls per frame allocate nothing. */
    private static final ThreadLocal<int[]> CHAR_EPOCH = ThreadLocal.withInitial(() -> new int[]{1});

    /**
     * Fragment replacement: segments arrive sorted by source length DESCENDING (longest first);
     * each fragment does exactly one {@link String#replace}, the output length is bounded, and no second matching exists.
     * Bad pairs (null / too short / empty source) are skipped outright: an empty source would make {@code replace} insert
     * the translation between every pair of characters - the classic 'template injection blows up the string' attack surface.
     *
     * <p>Fast path: the current-string character-presence table skips whole fragments whose first character
     * is absent from the string. Equivalence: if a fragment source occurs in the string, its first character must occur too,
     * so this pre-filter only removes useless multi-char scans and can never miss a real replacement.
     * After a replacement actually happens the current string has changed, so the presence table is rebuilt against the new
     * string, and later fragments see exactly the same character set as the verbatim-scan version.</p>
     */
    public static String applySegments(String raw, List<String[]> segments) {
        if (raw == null || segments == null || segments.isEmpty()) {
            return raw;
        }
        String out = raw;
        int[] stamp = CHAR_STAMP.get();
        int[] epochRef = CHAR_EPOCH.get();
        int epoch = epochRef[0];
        boolean tableValid = false;
        for (String[] pair : segments) {
            if (pair == null || pair.length < 2 || pair[0] == null || pair[0].isEmpty()
                    || pair[1] == null) {
                continue;
            }
            if (!tableValid) {
                epoch = markChars(out, stamp, epoch);
                tableValid = true;
            }
            if (stamp[pair[0].charAt(0)] != epoch) {
                continue;                       // First character not in the current string: this fragment can never hit
            }
            if (out.contains(pair[0])) {
                out = out.replace(pair[0], pair[1]);
                tableValid = false;             // String changed: rebuild the presence table for the next fragment against the new string
            }
        }
        epochRef[0] = epoch;
        return out;
    }

    /** Register the characters of {@code s} under a NEW epoch and return it; stale marks from old epochs expire by themselves, no clearing needed. */
    private static int markChars(String s, int[] stamp, int previousEpoch) {
        int epoch = previousEpoch == Integer.MAX_VALUE ? 1 : previousEpoch + 1;
        if (previousEpoch == Integer.MAX_VALUE) {
            java.util.Arrays.fill(stamp, 0);    // Only when the epoch wraps around do we pay for one full-table clear
        }
        for (int i = 0; i < s.length(); i++) {
            stamp[s.charAt(i)] = epoch;
        }
        return epoch;
    }

    /**
     * Stock-template reverse matching (the Map entry, kept so existing APIs and tests still work):
     * names are sorted by length descending here and handed to the fast-path implementation.
     *
     * <p>Hot UI paths should use {@link #applyStockTemplatesOrdered(String, List, List)}:
     * the caller builds and sorts the name table ONCE instead of rebuilding + re-sorting it per call.</p>
     */
    public static String applyStockTemplates(String raw, List<String[]> pairs,
                                             Map<String, String> translatedNames) {
        if (raw == null || raw.isEmpty() || pairs == null || pairs.isEmpty()
                || translatedNames == null || translatedNames.isEmpty()) {
            return null;
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(translatedNames.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, String> e) ->
                e.getKey() == null ? 0 : e.getKey().length()).reversed());
        List<String[]> names = new ArrayList<>(entries.size());
        for (Map.Entry<String, String> e : entries) {
            names.add(new String[]{e.getKey(), e.getValue()});
        }
        return applyStockTemplatesOrdered(raw, pairs, names);
    }

    /**
     * Hot-path implementation ({@code applyStockTemplatesOrdered}): {@code namesByLengthDesc} must map 'Chinese name -> translation'
     * and be SORTED BY NAME LENGTH DESCENDING.
     * If any expansion form matches, it is replaced and the new string returned; if none matches, null is returned (the caller continues with fragment replacement).
     *
     * <p>Names expand longest-first: otherwise the short name for Gold Ingot would eat the tail of the longer name for
     * Netherite Block and English UIs would show a half-translated string ('Nether' + 'Gold Ingot') -
     * the root cause behind the Chain Collapses screenshot.</p>
     *
     * <p>Both pre-filters are strictly equivalent:
     * (1) the expansion source necessarily contains the stock name itself, so 'name absent from the current string' proves
     * 'source absent' - run a cheap contains(name) first and allocate the expansion only when a hit is possible
     * (the old version allocated source and target for every combination before checking);
     * (2) when no name from the whole table is in the current string, return null at once: no replacement can have
     * happened, so no template can hit either - the same outcome the old implementation reached by spinning.</p>
     */
    public static String applyStockTemplatesOrdered(String raw, List<String[]> pairs,
                                                    List<String[]> namesByLengthDesc) {
        if (raw == null || raw.isEmpty() || pairs == null || pairs.isEmpty()
                || namesByLengthDesc == null || namesByLengthDesc.isEmpty()) {
            return null;
        }
        String out = raw;
        boolean anyName = false;
        for (String[] name : namesByLengthDesc) {
            if (name != null && name.length >= 2 && name[0] != null && !name[0].isEmpty()
                    && out.contains(name[0])) {
                anyName = true;
                break;
            }
        }
        if (!anyName) {
            return null;                        // No stock name from the whole table is in the string: reverse matching cannot hit
        }
        boolean changed = false;
        for (String[] pair : pairs) {
            if (pair == null || pair.length < 2 || pair[0] == null || pair[1] == null
                    || !pair[0].contains("{stock}")) {
                continue;
            }
            int at = pair[0].indexOf("{stock}");
            boolean singleSlot = pair[0].indexOf("{stock}", at + 7) < 0;
            String prefix = singleSlot ? pair[0].substring(0, at) : null;
            String suffix = singleSlot ? pair[0].substring(at + 7) : null;
            for (String[] name : namesByLengthDesc) {
                if (name == null || name.length < 2 || name[0] == null || name[1] == null
                        || name[0].isEmpty()) {
                    continue;
                }
                if (!out.contains(name[0])) {
                    continue;                   // None of the names are in the current string; their expansions cannot be in it either
                }
                String source = singleSlot ? prefix + name[0] + suffix
                        : pair[0].replace("{stock}", name[0]);
                if (!source.isEmpty() && out.contains(source)) {
                    out = out.replace(source, pair[1].replace("{stock}", name[1]));
                    changed = true;
                }
            }
        }
        return changed ? out : null;
    }
}
