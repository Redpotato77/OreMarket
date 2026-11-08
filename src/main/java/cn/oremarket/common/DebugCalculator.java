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

import java.util.List;
import java.util.Locale;

/**
 * Pure in-memory sandbox calculator for self-tests.
 *
 * <p>Purpose: give {@code /oremarket testshort} and {@code /oremarket testloan} a ZERO-POLLUTION arithmetic
 * rehearsal: fixed fake data runs through the exact SAME formulas as production
 * (shorts via {@link ShortPoolCalculator} / {@link ShortSettlement}, loans via the real {@link LoanSchedule} table)
 * and printable text comes back. This class:
 * <ul>
 * <li>must never reference ShortService / LoanService / MarketSavedData - a real player's money cannot be touched;</li>
 * <li>holds no save handles - setDirty() can never be called;</li>
 * <li>leaves no state: local variables die with the stack frame once printed.</li>
 * </ul>
 */
public final class DebugCalculator {

    /** One simulated result: text + whether it passed the self-check (negative values / fractions / broken identities all fail). */
    public static final class SimResult {
        public final List<String> lines;
        public final boolean passed;

        public SimResult(List<String> lines, boolean passed) {
            this.lines = lines;
            this.passed = passed;
        }
    }

    private DebugCalculator() {
    }

    /**
     * Simulate one short-sell open position (ALL-AVAILABLE-BALANCE one-click sizing + dual caps), fully in memory.
     *
     * <p>Worked example: balance 1000, price 10, ratio 1.5, combined fee+tax 3%:
     * per-share cost 15.3, fundsMax = floor(1000 / 15.3) = 65; pool has 5000, final 65 shares;
     * margin = ceil(65 x 10 x 1.5) = 975; fee/tax = ceil(650 x 0.03) = 20; locked = 995;
     * pool 5000 becomes 4935, wallet 1000 becomes 5 (stop when the money runs out, never overdraft).
     * With only 30 left in the pool, final 30 shares (the whole pool gets swept - exactly what one-click short should do).
     */
    public static SimResult simulateShortOpen(double balance, double price, double marginRatio,
                                              double combinedFeeTaxRate, int poolLeft, int maxPerOrder) {
        List<String> out = new java.util.ArrayList<>();
        boolean pass = true;
        int shares = ShortPoolCalculator.oneClickShares(balance, price, marginRatio,
                combinedFeeTaxRate, poolLeft, maxPerOrder);
        out.add(String.format(Locale.ROOT,
                "[TEST] simulate short: available balance %.2f, price %.2f, ratio %.2f,"
                            + " fee+tax %.4f/share, pool left %d -> shares %d",
                balance, price, marginRatio, price * combinedFeeTaxRate,
                poolLeft, shares));
        if (shares <= 0) {
            out.add("[TEST] shares <= 0: local intercept fires, NO packet is sent to the server.");
            return new SimResult(out, true);
        }
        double raw = price * shares;
        long margin = (long) Math.ceil(raw * marginRatio);
        long tax = (long) Math.ceil(raw * combinedFeeTaxRate);
        double locked = (double) (margin + tax);
        int poolAfter = poolLeft - shares;
        out.add(String.format(Locale.ROOT,
                "[TEST] margin %d + tax %d = lockedMargin %.0f; pool %d -> %d; wallet %.2f -> %.2f",
                margin, tax, locked, poolLeft, poolAfter,
                balance, balance - locked));
        // Self-check: results must be non-negative integers; the pool may not go negative; the wallet may not be drained below zero
        if (margin < 0 || tax < 0 || !Double.isFinite(locked) || locked != Math.rint(locked)
                || poolAfter < 0 || balance - locked < 0.0 || shares <= 0) {
            pass = false;
            out.add("[TEST FAILED] invariant broken: negative / fractional / overdraft detected!");
        }
        // sweep the whole pool); the old half-position invariant is void, replaced by the two real constraints below.
        if (margin + tax > balance + 1e-9) {
            pass = false;
            out.add("[TEST FAILED] affordability rule broken: deduction exceeds available balance!");
        }
        if (shares > poolLeft) {
            pass = false;
            out.add("[TEST FAILED] pool rule broken: one-click exceeds the shares left in this stock pool!");
        }
        if (maxPerOrder > 0 && shares > maxPerOrder) {
            pass = false;
            out.add("[TEST FAILED] per-order cap broken: shares above maxTradeQuantity!");
        }
        return new SimResult(out, pass);
    }

    /**
     * Simulate loan installment splitting (indivisible cases) through the REAL production {@link LoanSchedule}.
     * Worked example: principal 1000 over 3 installments (Equal Principal, rate 0):
     * first two rows 333 each, last row 334 (remainder to the final installment), sum equals 1000, balance zero.
     */
    public static SimResult simulateLoanSplit(long principal, int installments) {
        List<String> out = new java.util.ArrayList<>();
        boolean pass = true;
        int n = Math.max(1, Math.min(installments, LoanSchedule.MAX_INSTALLMENTS));
        List<LoanSchedule.Row> rows = LoanSchedule.build(1L, principal, 0.0,
                n * LoanSchedule.DAYS_PER_INSTALLMENT, n, LoanSchedule.MODE_LINEAR);
        long sum = 0;
        StringBuilder detail = new StringBuilder();
        for (LoanSchedule.Row r : rows) {
            sum += r.principal;
            detail.append("inst").append(r.index).append('=').append(r.principal).append(' ');
        }
        out.add(String.format(Locale.ROOT, "[TEST] split %d into %d installments (linear): %s",
                principal, n, detail.toString().trim()));
        long last = rows.isEmpty() ? 0L : rows.get(rows.size() - 1).principal;
        long first = rows.isEmpty() ? 0L : rows.get(0).principal;
        out.add(String.format(Locale.ROOT,
                "[TEST] first rows %d each, last row absorbs the remainder %d, total %d (zero left %b)",
                first, last, sum, sum == principal));
        if (sum != principal || rows.size() != n) {
            pass = false;
            out.add("[TEST FAILED] sum of installments != principal - ledger would leak coins!");
        }
        for (LoanSchedule.Row r : rows) {
            if (r.principal < 0) {
                pass = false;
                out.add("[TEST FAILED] negative installment principal!");
            }
        }
        return new SimResult(out, pass);
    }
}
