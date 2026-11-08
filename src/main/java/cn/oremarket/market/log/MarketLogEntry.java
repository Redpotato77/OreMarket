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

package cn.oremarket.market.log;

import net.minecraft.network.FriendlyByteBuf;
import cn.oremarket.common.LogType;

import java.util.ArrayList;
import java.util.List;

/**
 * One log record: [Trading Day X] [type] body.
 * Lives only in the server's and the client's in-memory ring queues; never written to SavedData;
 * a pure data class shared by both sides, free of any sided logic.
 *
 * Log bodies no longer carry only a Chinese sentence assembled
 * server-side. Each entry now holds a TRANSLATION KEY plus an argument table; at render time the client takes the
 * template from the player's current-language file and fills it (see {@code L.logBody}). The text field remains the
 * zh_cn baseline sentence, kept purely as a fallback (unregistered key / legacy data); the normal path no longer
 * relies on string reverse lookup.
 */
public final class MarketLogEntry {
    /** Body length cap; defends against absurdly long strings. */
    public static final int MAX_TEXT = 200;
    /** Caps on argument count and single-argument length (network hardening). */
    public static final int MAX_ARGS = 10;
    public static final int MAX_ARG = 96;

    public final long day;
    public final int type;
    /** zh_cn baseline sentence (fallback rendering and debugging only). */
    public final String text;
    /** Translation key (e.g. oremarket.trade.log.buy); empty string = plain-text log, falling back to text reverse lookup. */
    public final String key;
    /** Stringified template arguments (Chinese arguments such as stock names are translated one by one by the client while filling). */
    public final List<String> args;

    public MarketLogEntry(long day, LogType type, String text) {
        this(day, type.ordinal(), cut(text, MAX_TEXT), "", List.of());
    }

    public MarketLogEntry(long day, int type, String text) {
        this(day, type, cut(text, MAX_TEXT), "", List.of());
    }

    /** key + args constructor: text carries the server's zh_cn-baseline filled sentence (fallback only). */
    public MarketLogEntry(long day, LogType type, String text, String key, List<String> args) {
        this(day, type.ordinal(), text, key, args);
    }

    private MarketLogEntry(long day, int type, String text, String key, List<String> args) {
        this.day = day;
        this.type = type;
        this.text = cut(text, MAX_TEXT);
        this.key = key == null ? "" : key;
        if (args == null || args.isEmpty()) {
            this.args = List.of();
        } else {
            List<String> out = new ArrayList<>(Math.min(args.size(), MAX_ARGS));
            for (int i = 0; i < args.size() && i < MAX_ARGS; i++) {
                out.add(cut(args.get(i), MAX_ARG));
            }
            this.args = List.copyOf(out);
        }
    }

    public LogType logType() {
        return LogType.byOrdinalSafe(type);
    }

    /** Whether this is a key-based log (the client renders directly from key + args). */
    public boolean hasKey() {
        return !key.isEmpty();
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        // Arguments that carry piece boundaries (server-built effect shorthands etc.) may only be cut at a boundary: half a
        // template never matches a whole sentence, and English UIs would fall back to fragment lookup and leak Chinese.
        if (cn.oremarket.common.EffectText.isComposed(s)) {
            return cn.oremarket.common.EffectText.cut(s, max);
        }
        return s.substring(0, max);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarLong(Math.max(0, day));
        buf.writeVarInt(type);
        buf.writeUtf(text, MAX_TEXT);
        buf.writeUtf(key, 96);
        buf.writeVarInt(args.size());
        for (String a : args) {
            buf.writeUtf(a, MAX_ARG);
        }
    }

    public static MarketLogEntry read(FriendlyByteBuf buf) {
        long d = buf.readVarLong();
        int t = buf.readVarInt();
        String text = buf.readUtf(MAX_TEXT);
        String key = buf.readUtf(96);
        int n = buf.readVarInt();
        List<String> args = new ArrayList<>(Math.max(0, Math.min(n, MAX_ARGS)));
        for (int i = 0; i < n && i < MAX_ARGS; i++) {
            args.add(buf.readUtf(MAX_ARG));
        }
        return new MarketLogEntry(d, t, text, key, args);
    }
}
