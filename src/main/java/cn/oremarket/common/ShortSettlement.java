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
 * The single authoritative formulas for settling a Short Position close (a pure
 * calculation class added for 's exhaustive audit).
 *
 * <p>Why a dedicated class: the player's <b>manual Close Position</b>
 * ({@code ShortService.closeInternal}) and the server's <b>Forced Liquidation</b>
 * ({@code ShortService.settleForced}) each used to carry their own formula - a manual
 * close refunded "Margin +/- P&L", while a forced liquidation settled on "Margin -
 * current price x quantity". Opening a position only ever charged the player the Margin
 * and the opening tax (the market value of the Borrowing never went through his
 * wallet), so liquidated players systematically paid {@code open price x quantity} more
 * than those who closed by hand, and the overpaid part was turned into {@code shortDebt}
 * that pinned the player down. Now both paths can only call the same formulas from here,
 * so a fork at the formula level is no longer possible.
 *
 * <p>This class depends only on the JDK: server-side Settlement and the offline
 * verification harness run the very same bytecode. Every method guards against dirty
 * values - NaN / Infinity / negative numbers are all treated as 0, and NaN is never
 * written into an Ore Coin balance.
 */
public final class ShortSettlement {

    private ShortSettlement() {
    }

    /** An effective price: positive and finite. 0 / NaN / Infinity / negative are all invalid (market data not loaded, or a corrupted save). */
    public static boolean validPrice(double price) {
        return price > 0.0 && Double.isFinite(price);
    }

    /**
     * P&L of this Close Position tranche = {@code round((open price - current price) x quantity)}
     * (Short Selling: you only earn when the price falls). Returns 0 when either price is
     * invalid or quantity <= 0 - better to settle no P&L than to produce a NaN.
     */
    public static long pnl(double openPrice, double price, int quantity) {
        if (!validPrice(price) || !validPrice(openPrice) || quantity <= 0) {
            return 0L;
        }
        double v = (openPrice - price) * (double) quantity;
        if (!Double.isFinite(v)) {
            return 0L;
        }
        return clampToLong(Math.round(v));
    }

    /**
     * Effective event shock multiplier for a short cover. A cover's quote impact is intentionally
     * scaled down from a normal buy, and dirty values degrade to no additional shock.
     */
    public static double coverShockMultiplier(double eventShockMultiplier, double closeImpactMultiplier) {
        if (!Double.isFinite(eventShockMultiplier) || eventShockMultiplier <= 0.0
                || !Double.isFinite(closeImpactMultiplier) || closeImpactMultiplier <= 0.0) {
            return 0.0;
        }
        return eventShockMultiplier * Math.min(1.0, closeImpactMultiplier);
    }

    /**
     * Amount due on a <b>full</b> Close Position = Margin + P&L.
     *
     * <p>The non-negative clamp is <b>deliberately omitted</b> here: a negative result
     * means the loss exceeded the Margin, and its absolute value is exactly the shortfall
     * that must be recovered from the player (deduct Ore Coins first, roll the rest into
     * shortDebt). Manual closes go through {@link #payoutWithFloor} (worst case: the
     * Margin is wiped out), forced liquidations use this method (over-Margin losses are
     * recovered) - the only difference is "whether to claw back the excess"; the P&L
     * formula itself is identical on both sides.
     */
    public static long payoutFullClose(long margin, double openPrice, double price, int quantity) {
        return saturatingAdd(Math.max(0L, margin), pnl(openPrice, price, quantity));
    }

    /** Full-close refund with "the worst case wipes out the Margin": negative results clamp to 0 (used for manual Close Position by the player). */
    public static long payoutWithFloor(long margin, double openPrice, double price, int quantity) {
        long payout = payoutFullClose(margin, openPrice, price, quantity);
        return payout < 0L ? 0L : payout;
    }

    /**
     * Margin released pro rata to quantity = {@code min(total margin, floor(total margin
     * x closed quantity / quantity before the close))}. On a full Close Position
     * (quantity == before) this is the whole Margin; dirty quantities (<=0) release 0.
     */
    public static long releasedMargin(long margin, int quantity, int before) {
        long m = Math.max(0L, margin);
        if (m <= 0L || quantity <= 0 || before <= 0) {
            return 0L;
        }
        if (quantity >= before) {
            return m;
        }
        double v = (double) m * (double) quantity / (double) before;
        if (!Double.isFinite(v) || v <= 0) {
            return 0L;
        }
        long released = v >= (double) m ? m : (long) Math.floor(v);
        return Math.min(m, released);
    }

    /** Partial-close refund = the released Margin; the caller adds the tranche's P&L when the config asks to "settle P&L together with the Close Position". */
    public static long payoutPartial(long margin, int quantity, int before, long pnl, boolean withPnl) {
        long released = releasedMargin(margin, quantity, before);
        if (!withPnl) {
            return released;
        }
        long payout = saturatingAdd(released, pnl);
        return payout < 0L ? 0L : payout;
    }

    /**
     * Release on a <b>partial Close Position</b> =
     * the locked Margin prorated by the number of shares closed.
     *
     * <p>[Business rule] the Margin is collateral frozen at the very instant the position
     * is opened; whatever fraction of the <b>opened shares</b> the player closes, that
     * same fraction of the <b>locked Margin</b> is released - never re-multiplied by the
     * live current price. The final close (when the remaining share count hits zero) does
     * not go through this method: the caller simply refunds the position's on-the-spot
     * balance (a fallback exact to the last coin).
     * <p>[Formula example] opened 100 shares with lockedMargin = 1500:
     * closing 50 shares -> floor(1500 x 50 / 100) = 750; closing 1 share -> floor(1500 x 1 / 100) = 15.
     * Once daily interest has eroded the refundable balance down to 700, closing 50
     * shares releases the smaller of the locked share (750) and the proportional
     * current balance (350). The remaining 350 stays with the remaining shares;
     * no partial close can accidentally empty the collateral of the live remainder.
     *
     * @param lockedMargin      the locked Margin frozen at the moment of opening (read-only, never changes)
     * @param refundableBalance the position's current refundable collateral balance (= {@code position.margin})
     * @param closedQty         shares closed by this call (>0 and below the total opened)
     * @param originalQty       total shares opened (the fixed denominator; does not drift with partial closes)
     */
    public static long releasedLocked(double lockedMargin, long refundableBalance,
                                      int closedQty, int originalQty) {
        long cap = Math.max(0L, refundableBalance);
        if (cap <= 0L || closedQty <= 0 || originalQty <= 0
                || !Double.isFinite(lockedMargin) || lockedMargin <= 0.0) {
            return 0L;
        }
        if (closedQty >= originalQty) {
            return cap;   // full close: refund the on-the-spot balance to the last coin (the caller holds the fallback; same criterion here)
        }
        double v = lockedMargin * (double) closedQty / (double) originalQty;
        if (!Double.isFinite(v) || v <= 0.0) {
            return 0L;
        }
        long released = (long) Math.floor(v);
        if (released <= 0L) {
            return 0L;    // the pro-rata share is under 1 coin: release nothing this time; the balance stays in the position and is paid out by later closes / the fallback
        }
        return Math.min(cap, released);
    }

    /**
     * Release the current collateral balance proportionally for a non-final close.
     * The denominator is the quantity held immediately before this close, not the
     * original quantity: this prevents a low post-interest balance from being
     * released in full while shares remain open.
     */
    public static long releasedRemaining(long refundableBalance, int closedQty, int quantityBefore) {
        long cap = Math.max(0L, refundableBalance);
        if (cap <= 0L || closedQty <= 0 || quantityBefore <= 0) {
            return 0L;
        }
        if (closedQty >= quantityBefore) {
            return cap;
        }
        double v = (double) cap * (double) closedQty / (double) quantityBefore;
        if (!Double.isFinite(v) || v <= 0.0) {
            return 0L;
        }
        return Math.min(cap, (long) Math.floor(v));
    }

    /** The "buyback cost" shown in Forced Liquidation logs = {@code ceil(current price x quantity)}; an invalid price counts as 0 (wording only, it never affects the Settlement). */
    public static long buybackCost(double price, int quantity) {
        if (!validPrice(price) || quantity <= 0) {
            return 0L;
        }
        double v = price * (double) quantity;
        return !Double.isFinite(v) ? Long.MAX_VALUE : clampToLong(Math.ceil(v));
    }

    // ------------------------------------------------------------------ dirty-value guards

    private static long clampToLong(double v) {
        if (!Double.isFinite(v)) {
            return 0L;
        }
        if (v >= (double) Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        if (v <= (double) Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        return (long) v;
    }

    private static long saturatingAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        if (b < 0 && a < Long.MIN_VALUE - b) {
            return Long.MIN_VALUE;
        }
        return a + b;
    }
}
