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

package cn.oremarket.market;

import java.util.Map;

/**
 * Pure accounting helpers for the short-side ledger.
 *
 * <p>The ledger records economics only. It never participates in balance settlement,
 * margin refunds, short-pool movements or liquidation eligibility.</p>
 */
public final class ShortPnlCalculator {
    private ShortPnlCalculator() {
    }

    /** Sum of closed short price P&L. Null or non-finite ledger entries are skipped defensively. */
    public static double realizedTotal(Map<String, Double> ledger) {
        if (ledger == null || ledger.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Map.Entry<String, Double> entry : ledger.entrySet()) {
            if (entry.getValue() != null && Double.isFinite(entry.getValue())) {
                sum += entry.getValue();
            }
        }
        return sum;
    }

    /**
     * Adds one already-clamped P&L delta and removes a code once its cumulative amount is exactly zero.
     * This keeps old saves free of synthetic keys while leaving a non-zero economic record recoverable.
     */
    public static void add(Map<String, Double> ledger, String code, double delta) {
        if (ledger == null || code == null || code.isEmpty() || !Double.isFinite(delta) || delta == 0.0) {
            return;
        }
        Double old = ledger.get(code);
        double next = (old == null ? 0.0 : old) + delta;
        if (!Double.isFinite(next)) {
            return;
        }
        if (next == 0.0) {
            ledger.remove(code);
        } else {
            ledger.put(code, next);
        }
    }
}
