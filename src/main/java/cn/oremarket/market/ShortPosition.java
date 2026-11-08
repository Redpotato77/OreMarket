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

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * One player's short position in one stock; the ratio, daily interest and the LOCKED MARGIN are all frozen at the instant of opening.
 *
 * <ul>
 * <li>{@link #margin} (pre-existing field, semantics corrected): the REFUNDABLE COLLATERAL BALANCE.
 * At open it = margin + tax/fee (every cent taken from the player's wallet); daily interest deducts from it,
 * close refunds come out of it, and forced-liquidation condition 2 compares against it.</li>
 * <li>{@link #lockedMargin} [Purpose] the locked-margin anchor frozen at the instant of opening:
 * from then on IT NEVER CHANGES regardless of price moves; the UI 'locked margin' column and quota figures
 * read it only - multiplying the live price again into it is strictly forbidden.<br>
 * [Worked example] open price 10 x 100 shares x ratio 1.5 = 1500, tax 15 ->
 * lockedMargin = 1515; after the price doubles (20) or halves (5) it is still 1515.</li>
 * <li>{@link #lockedReleased} cumulative amount released from the refundable collateral balance
 * (for audit / refundable-balance display; daily interest is not a refund).<br>
 * Identity while active: margin current balance == lockedMargin - cumulative daily interest - lockedReleased.</li>
 * <li>{@link #originalQuantity} total shares at open: retained for save compatibility and
 * historical display; partial closes now prorate the current balance over the remaining quantity.</li>
 * </ul>
 *
 * <p>The final close (remaining quantity hits zero) refunds the CURRENT BALANCE of {@code margin} -
 * all historical rounding remainders are absorbed by the last installment, so the refund total matches to the cent
 * and never lands on 999.99 / 1000.01.
 */
public final class ShortPosition {
    public final UUID owner;
    public final String code;
    /**
     * The position's unique ID. Close requests carry it and the server verifies by ID that
     * 'this holding really exists and has not been liquidated / changed', eliminating 'close by code blindly' and mis-clicks after the list churns.
     * 0 = not yet assigned (old saves / old packets); {@code MarketSavedData} issues it at load and open time.
     */
    public long id;
    public int quantity;

    /** Close requests must carry a server-issued positive ID; zero is reserved for unassigned legacy data. */
    public static boolean validCloseId(long id) {
        return id > 0L;
    }
    /** Total shares at open (the fixed denominator of the locked-margin release ratio; falls back to the current quantity when loading old saves). */
    public int originalQuantity;
    public double openPrice;
    /** Refundable collateral balance: at open = margin + tax/fee; daily interest deducts it, close refunds come from it, forced-liquidation condition 2 compares it. */
    public long margin;
    /** The locked margin (margin + tax/fee) frozen at the instant of opening; no logic may rewrite it after open. */
    public double lockedMargin;
    /** Cumulative amount refunded to the player under the locked accounting (refund audit counter). */
    public double lockedReleased;
    /** Open tax/fee (a component of the locked margin, kept separately for reconciliation and logs). */
    public double openTax;
    public double marginRatio;
    public double liquidationRatio;
    /** Forced-liquidation condition-2 ratio (current remaining margin < current market value x this ratio -> liquidate); the ratio is locked at open. */
    public double liquidationThreshold2;
    public double dailyInterestRate;
    public long openDay;
    public double interestPaid;

    public ShortPosition(UUID owner, String code, int quantity, double openPrice, long margin,
                          double marginRatio, double liquidationRatio, double dailyInterestRate,
                          long openDay) {
        this(owner, code, quantity, openPrice, margin, 0.0, marginRatio, liquidationRatio, 1.00,
                dailyInterestRate, openDay);
    }

    /**
     * Compatibility entry for old callers (event split rebuilding a position etc.): no separate tax item (openTax = 0);
     * the margin passed in IS the whole refundable collateral balance; lockedMargin freezes to it.
     * [Worked example] a split doubles borrowed shares and halves the margin, then rebuilds: lockedMargin = new margin,
     * the release denominator originalQuantity = new quantity - the ratio basis still matches to the cent.
     */
    public ShortPosition(UUID owner, String code, int quantity, double openPrice, long margin,
                          double marginRatio, double liquidationRatio, double liquidationThreshold2,
                          double dailyInterestRate, long openDay) {
        this(owner, code, quantity, openPrice, margin, 0.0, marginRatio, liquidationRatio,
                liquidationThreshold2, dailyInterestRate, openDay);
    }

    /**
     * The single open entry point.
     * [Purpose] at the instant the open succeeds, compute and freeze the collateral pool in one shot:
     * margin, tax/fee, locked total, open shares; afterwards no code may recompute it from live prices.
     * [Worked example] open price 10, 100 shares, ratio 1.5, tax 15:
     * margin = 1500 + 15 = 1515 (pool balance), lockedMargin = 1515 (frozen anchor),
     * lockedReleased = 0, originalQuantity = 100.
     *
     * @param margin   the pure margin part (= ceil(borrowed market value x ratio), tax excluded)
     * @param openTax  open tax/fee (destroy tax included; pooled with the margin, refunded per the locked accounting on close)
     */
    public ShortPosition(UUID owner, String code, int quantity, double openPrice, long margin,
                          double openTax, double marginRatio, double liquidationRatio,
                          double liquidationThreshold2, double dailyInterestRate, long openDay) {
        this.owner = owner;
        this.code = code == null ? "" : code;
        this.quantity = Math.max(0, quantity);
        this.originalQuantity = this.quantity;
        this.openPrice = safeD(openPrice, 0.0, 0.0);
        double tax = Double.isFinite(openTax) && openTax > 0.0 ? openTax : 0.0;
        this.openTax = tax;
        long m = Math.max(0L, margin);
        // Collateral pool = margin + tax/fee = every cent taken from the wallet (two integers added, zero floating-point error).
        this.margin = saturatedAdd(m, (long) tax);
        // margin and tax are both integer coins; lockedMargin is an integral-valued double (exact within 2^53),
        // and this is the arithmetic precondition for 'to the cent, never 999.99'.
        this.lockedMargin = (double) this.margin;
        this.lockedReleased = 0.0;
        // Keep the same legal ranges as MarketSettings. The old 1.00 floors silently turned a 0.50
        // margin setting into full collateral, which changed both risk and the displayed accounting.
        this.marginRatio = safeD(marginRatio, 0.10, 1.0);
        this.liquidationRatio = safeD(liquidationRatio, 1.05, 1.15);
        // Never let condition two be stricter than the price-line condition for a clean position.
        // The cap uses the equity left at the line: (marginRatio - (liquidationRatio - 1)) / liquidationRatio.
        this.liquidationThreshold2 = cappedThreshold2(liquidationThreshold2,
                this.marginRatio, this.liquidationRatio, 0.20);
        this.dailyInterestRate = safeD(dailyInterestRate, 0.0, 0.0);
        this.openDay = Math.max(1L, openDay);
    }

    /**
     * Apply the price-line safety cap to a condition-two ratio. The lower bound is kept at zero
     * here because a very leveraged configuration can mathematically leave no safe maintenance
     * threshold below the line; zero disables only condition two, never condition one.
     */
    private static double cappedThreshold2(double requested, double marginRatio,
                                           double liquidationRatio, double fallback) {
        double value = Double.isFinite(requested) && requested >= 0.0 ? requested : fallback;
        double margin = Double.isFinite(marginRatio) && marginRatio >= 0.0 ? marginRatio : 1.0;
        double line = Double.isFinite(liquidationRatio) && liquidationRatio > 0.0 ? liquidationRatio : 1.15;
        double cap = Math.max(0.0, (margin - (line - 1.0)) / line);
        return Math.min(value, cap);
    }

    /** Dirty-value clamping: NaN / +/-Infinity / below the floor -> fall back to the default (NaN must never enter a settlement formula). */
    private static double safeD(double v, double floor, double dflt) {
        if (!Double.isFinite(v) || v < floor) {
            return dflt < floor ? floor : dflt;
        }
        return v;
    }

    /** Bare-field entry for save restoration (fromTag only; every field falls back individually, old saves missing keys never throw). */
    private ShortPosition(UUID owner, String code) {
        this.owner = owner;
        this.code = code == null ? "" : code;
        this.openPrice = 0.0;
        this.marginRatio = 1.0;
        this.liquidationRatio = 1.0;
        this.liquidationThreshold2 = 1.0;
        this.dailyInterestRate = 0.0;
        this.openDay = 1L;
    }

    private static long saturatedAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        return a + b;
    }

    /** Refundable balance under the locked accounting (= lockedMargin - lockedReleased; read-only display / audit, NaN clamps to 0). */
    public long lockedRemainingCoins() {
        double r = lockedMargin - lockedReleased;
        if (!Double.isFinite(r) || r <= 0.0) {
            return 0L;
        }
        if (r >= (double) Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (long) Math.rint(r);
    }

    /** Refund counter: records exactly what was refunded (clamped into [0, lockedMargin], never out of bounds). */
    public void releaseLocked(double amount) {
        double v = Double.isFinite(amount) && amount > 0.0 ? amount : 0.0;
        lockedReleased = Math.min(lockedMargin, lockedReleased + v);
    }

    /**
     * When the position dies, mark only the refundable balance as released.
     * Daily interest has already consumed the difference from lockedMargin and
     * must not be reported as money refunded to the player.
     */
    public void releaseLockedAll() {
        double consumed = Double.isFinite(interestPaid) && interestPaid > 0.0 ? interestPaid : 0.0;
        double refundable = Math.max(0.0, lockedMargin - consumed);
        lockedReleased = Math.min(lockedMargin, refundable);
    }

    /**
     * Charge one daily-interest installment.
     * [Purpose] interest is a real cost: deducted straight from the refundable collateral pool (what the player finally gets shrinks truthfully),
     * while the comparison base of liquidation condition 2 tightens too. The lockedMargin anchor STAYS put (it is the frozen open value).
     * [Worked example] open market value 1000, daily rate 3% -> charge 30 that day: margin 1515 -> 1485,
     * interestPaid +30, lockedMargin still displays 1515.
     */
    public void chargeInterest(long interest) {
        long i = Math.max(0L, interest);
        if (i <= 0L) {
            return;
        }
        interestPaid += (double) i;
        margin = Math.max(0L, margin - i);
    }

    /** Refund occupancy against the refundable collateral balance. */
    public void consumeMargin(long amount) {
        margin = Math.max(0L, margin - Math.max(0L, amount));
    }

    public double openMarketValue() {
        return openPrice * quantity;
    }

    public double floatingPnl(double currentPrice) {
        if (!(currentPrice > 0.0) || !Double.isFinite(currentPrice)
                || !(openPrice > 0.0) || !Double.isFinite(openPrice) || quantity <= 0) {
            return 0.0;
        }
        double pnl = (openPrice - currentPrice) * (double) quantity;
        return Double.isFinite(pnl) ? pnl : 0.0;
    }

    /**
     * Current remaining margin/equity shown to the player: the refundable collateral
     * after interest and partial-close releases, plus the floating P&L of the shares
     * that are still open. This is the same balance consumed by a real forced close.
     */
    public double currentRemainingMargin(double currentPrice) {
        double value = (double) Math.max(0L, margin) + floatingPnl(currentPrice);
        if (!Double.isFinite(value)) {
            return value > 0.0 ? Double.MAX_VALUE : -Double.MAX_VALUE;
        }
        return value;
    }

    /** Maintenance margin threshold for condition two at the current quote. */
    public double liquidationThreshold(double currentPrice) {
        if (!(currentPrice > 0.0) || !Double.isFinite(currentPrice)
                || quantity <= 0 || !Double.isFinite(liquidationThreshold2)
                || liquidationThreshold2 < 0.0) {
            return 0.0;
        }
        double threshold = currentPrice * (double) quantity * liquidationThreshold2;
        return Double.isFinite(threshold) ? Math.max(0.0, threshold) : Double.MAX_VALUE;
    }

    /** Condition one: the current quote has reached the price line frozen at opening. */
    public boolean priceLineReached(double currentPrice) {
        double line = liquidationPrice();
        return currentPrice > 0.0 && Double.isFinite(currentPrice)
                && line > 0.0 && Double.isFinite(line)
                && currentPrice >= line;
    }

    /** Condition two: current equity is below the maintenance threshold at the same quote. */
    public boolean marginBelowThreshold(double currentPrice) {
        double threshold = liquidationThreshold(currentPrice);
        return threshold > 0.0
                && currentRemainingMargin(currentPrice) < threshold;
    }

    /**
     * One authoritative trigger order for every settlement path.
     * The price-line reason wins when both conditions are true at the same quote;
     * this prevents daily settlement from misreporting a price-line liquidation as a margin liquidation.
     */
    public String liquidationReason(double currentPrice) {
        if (priceLineReached(currentPrice)) {
            return "price_line";
        }
        return marginBelowThreshold(currentPrice) ? "margin_below" : null;
    }

    /**
     * Converts the borrowed share contract after a stock split while preserving every
     * coin-denominated accounting field. The market value stays constant because shares
     * multiply and both open and liquidation prices divide by the same ratio.
     */
    public void applyStockSplit(int ratio) {
        if (ratio <= 1) {
            return;
        }
        quantity = Math.multiplyExact(quantity, ratio);
        originalQuantity = Math.multiplyExact(Math.max(0, originalQuantity), ratio);
        openPrice = safeD(openPrice / ratio, 0.0, 0.0);
    }

    public double liquidationPrice() {
        return openPrice * liquidationRatio;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Owner", owner.toString());
        tag.putString("Code", code);
        tag.putLong("Id", id);
        tag.putInt("Quantity", quantity);
        tag.putInt("OrigQty", originalQuantity);
        tag.putDouble("OpenPrice", openPrice);
        tag.putLong("Margin", margin);
        tag.putDouble("LockedMargin", lockedMargin);
        tag.putDouble("LockedReleased", lockedReleased);
        tag.putDouble("OpenTax", openTax);
        tag.putDouble("MarginRatio", marginRatio);
        tag.putDouble("LiquidationRatio", liquidationRatio);
        tag.putDouble("LiquidationThreshold2", liquidationThreshold2);
        tag.putDouble("DailyInterestRate", dailyInterestRate);
        tag.putLong("OpenDay", openDay);
        tag.putDouble("InterestPaid", interestPaid);
        return tag;
    }

    public static ShortPosition fromTag(CompoundTag tag, UUID fallbackOwner) {
        UUID owner = fallbackOwner;
        try {
            if (tag.contains("Owner")) {
                owner = UUID.fromString(tag.getString("Owner"));
            }
        } catch (IllegalArgumentException ignored) {
        }
        // Old saves lack the condition-two ratio: use the current safe default, then apply the
        // same price-line cap below. Existing saved ratios are also capped so a legacy 0.43/1.00
        // value cannot reintroduce the pre-fix early-margin liquidation after a restart.
        cn.oremarket.market.MarketSettings currentSettings =
                cn.oremarket.market.MarketSettings.current();
        double configuredT2 = currentSettings == null ? 0.20 : currentSettings.shortLiquidationThreshold2;
        double t2 = tag.contains("LiquidationThreshold2")
                ? tag.getDouble("LiquidationThreshold2") : configuredT2;
        ShortPosition p = new ShortPosition(owner, tag.getString("Code"));
        p.quantity = Math.max(0, tag.getInt("Quantity"));
        p.openPrice = safeD(tag.getDouble("OpenPrice"), 0.0, 0.0);
        p.margin = Math.max(0L, tag.getLong("Margin"));
        p.marginRatio = safeD(tag.getDouble("MarginRatio"), 0.10, 1.0);
        p.liquidationRatio = safeD(tag.getDouble("LiquidationRatio"), 1.05, 1.15);
        p.liquidationThreshold2 = cappedThreshold2(t2, p.marginRatio, p.liquidationRatio, 0.20);
        p.dailyInterestRate = safeD(tag.getDouble("DailyInterestRate"), 0.0, 0.0);
        p.openDay = Math.max(1L, tag.getLong("OpenDay"));
        p.interestPaid = Math.max(0.0, tag.getDouble("InterestPaid"));
        // Old saves lack the Id key -> read 0; MarketSavedData's load stage issues the unique ID
        p.id = tag.getLong("Id");
        // ---------- all three new keys fall back safely; old saves can never NPE / produce NaN
        if (tag.contains("LockedMargin")) {
            double lm = tag.getDouble("LockedMargin");
            p.lockedMargin = Double.isFinite(lm) && lm >= 0.0 ? lm : (double) p.margin;
        } else {
            // Positions opened before this fix: the tax was consumed then and never entered the collateral pool - bottom up a locked value from the existing margin,
            // so old positions still close and refund, the UI instantly has a solid figure, and refunds never exceed what was actually charged.
            p.lockedMargin = (double) p.margin;
        }
        if (tag.contains("LockedReleased")) {
            double lr = tag.getDouble("LockedReleased");
            p.lockedReleased = Double.isFinite(lr) && lr >= 0.0 ? Math.min(lr, p.lockedMargin) : 0.0;
        }
        if (tag.contains("OpenTax")) {
            double ot = tag.getDouble("OpenTax");
            p.openTax = Double.isFinite(ot) && ot >= 0.0 ? ot : 0.0;
        }
        if (tag.contains("OrigQty")) {
            int oq = tag.getInt("OrigQty");
            // Denominator safety: the release-ratio denominator is never below the current remaining quantity (protects old rows against a dirty divide-by-zero)
            p.originalQuantity = Math.max(p.quantity, oq > 0 ? oq : p.quantity);
        } else {
            p.originalQuantity = p.quantity;
        }
        // If a dirty save wrote the locked anchor below the existing balance: raise it back to the balance so the identity keeps the right direction
        if (p.lockedMargin < (double) p.margin) {
            p.lockedMargin = (double) p.margin;
        }
        return p;
    }
}
