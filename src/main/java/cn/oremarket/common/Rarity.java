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
 * The four event rarity tiers: they only set trigger weight, duration range and affected-stock count range,
 * never the event's logic (the same template at another rarity is just another strength level).
 * Weights 60 / 25 / 12 / 3 sum to 100; draws weight by the tier of each candidate template.
 * Border colors are UI conventions; the server sends the tier ordinal with the event and the client maps ordinal to color.
 *
 * Duration ranges scaled up (in marketTicks; 1 = 3 seconds):
 * Common 20~60 (shown as 1~3 min), Rare 40~100 (2~5 min),
 * Epic 80~160 (4~8 min), Legendary 160~300 (8~15 min).
 */
public enum Rarity {
    COMMON(cn.oremarket.common.RawText.t("oremarket.text.0049"), Defaults.COMMON_WEIGHT,
            Defaults.COMMON_COLOR, Defaults.COMMON_DURATION_MIN, Defaults.COMMON_DURATION_MAX,
            Defaults.COMMON_TARGET_MIN, Defaults.COMMON_TARGET_MAX),
    RARE(cn.oremarket.common.RawText.t("oremarket.text.0064"), Defaults.RARE_WEIGHT,
            Defaults.RARE_COLOR, Defaults.RARE_DURATION_MIN, Defaults.RARE_DURATION_MAX,
            Defaults.RARE_TARGET_MIN, Defaults.RARE_TARGET_MAX),
    EPIC(cn.oremarket.common.RawText.t("oremarket.text.0037"), Defaults.EPIC_WEIGHT,
            Defaults.EPIC_COLOR, Defaults.EPIC_DURATION_MIN, Defaults.EPIC_DURATION_MAX,
            Defaults.EPIC_TARGET_MIN, Defaults.EPIC_TARGET_MAX),
    LEGENDARY(cn.oremarket.common.RawText.t("oremarket.text.0024"), Defaults.LEGENDARY_WEIGHT,
            Defaults.LEGENDARY_COLOR, Defaults.LEGENDARY_DURATION_MIN, Defaults.LEGENDARY_DURATION_MAX,
            Defaults.LEGENDARY_TARGET_MIN, Defaults.LEGENDARY_TARGET_MAX);

    public static final Rarity[] ORDER = values();

    /** Chinese tier label (sent to the UI by the server). */
    public final String label;
    /** Draw weight (60/25/12/3). */
    public volatile int weight;
    /** Event card border color (synced from server config; the client picks it by rarity ordinal). */
    public volatile int borderColor;
    /** Event overall duration range in ticks. */
    public final int durationMin;
    public final int durationMax;
    /** Range of affected stock count. */
    public final int targetMin;
    public final int targetMax;

    Rarity(String label, int weight, int borderColor, int durationMin, int durationMax,
           int targetMin, int targetMax) {
        this.label = label;
        this.weight = weight;
        this.borderColor = borderColor;
        this.durationMin = durationMin;
        this.durationMax = durationMax;
        this.targetMin = targetMin;
        this.targetMax = targetMax;
    }

    public static Rarity byOrdinalSafe(int ordinal) {
        return ordinal >= 0 && ordinal < ORDER.length ? ORDER[ordinal] : COMMON;
    }

    /** Named defaults keep the enum declaration readable and document each value's role. */
    private static final class Defaults {
        private static final int COMMON_WEIGHT = 60;
        private static final int COMMON_COLOR = 0xFF888888;
        private static final int COMMON_DURATION_MIN = 20;
        private static final int COMMON_DURATION_MAX = 60;
        private static final int COMMON_TARGET_MIN = 1;
        private static final int COMMON_TARGET_MAX = 2;

        private static final int RARE_WEIGHT = 25;
        private static final int RARE_COLOR = 0xFF4A7FD0;
        private static final int RARE_DURATION_MIN = 40;
        private static final int RARE_DURATION_MAX = 100;
        private static final int RARE_TARGET_MIN = 2;
        private static final int RARE_TARGET_MAX = 4;

        private static final int EPIC_WEIGHT = 12;
        private static final int EPIC_COLOR = 0xFF9A4AD0;
        private static final int EPIC_DURATION_MIN = 80;
        private static final int EPIC_DURATION_MAX = 160;
        private static final int EPIC_TARGET_MIN = 4;
        private static final int EPIC_TARGET_MAX = 6;

        private static final int LEGENDARY_WEIGHT = 3;
        private static final int LEGENDARY_COLOR = 0xFFD0A040;
        private static final int LEGENDARY_DURATION_MIN = 160;
        private static final int LEGENDARY_DURATION_MAX = 300;
        private static final int LEGENDARY_TARGET_MIN = 6;
        private static final int LEGENDARY_TARGET_MAX = 8;

        private Defaults() {
        }
    }
}
