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

/**
 * Log types. The server sends only the ordinal; the client paints the prefix from this table:
 * exchange blue 0xFF4A7FD0, event purple 0xFF9A4AD0 and system gray 0xFF888888 are non-directional and fixed.
 * BUY / SELL use the theme's own trade-log pair (logColorScheme, green buy / red sell by default), so the log page
 * does not flip with the quote convention unless an operator reconnects it with FOLLOW_MARKET.
 * The two literals below are the documented fallback hues for a consumer without a config snapshot and match the
 * default pair.
 *
 * Each type name also registers a translation key {@link #labelKey};
 * the client looks its prefix up by key in the current language instead of reverse-matching the Chinese label.
 */
public enum LogType {
    BUY(cn.oremarket.common.RawText.t("oremarket.text.0019"), "oremarket.text.0019", 0xFF3FBF5F),
    SELL(cn.oremarket.common.RawText.t("oremarket.text.0032"), "oremarket.text.0032", 0xFFE04A4A),
    EXCHANGE(cn.oremarket.common.RawText.t("oremarket.text.0027"), "oremarket.text.0027", 0xFF4A7FD0),
    EVENT(cn.oremarket.common.RawText.t("oremarket.text.0021"), "oremarket.text.0021", 0xFF9A4AD0),
    SYSTEM(cn.oremarket.common.RawText.t("oremarket.text.0062"), "oremarket.text.0062", 0xFF888888);

    /** zh_cn baseline name (for debugging / the server console; never rendered in the UI). */
    public final String label;
    /** Translation key of the type name (entry point for the client's prefix rendering). */
    public final String labelKey;
    public final int color;

    LogType(String label, String labelKey, int color) {
        this.label = label;
        this.labelKey = labelKey;
        this.color = color;
    }

    public static LogType byOrdinalSafe(int ordinal) {
        return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : SYSTEM;
    }
}
