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
import cn.oremarket.common.LoanRules;
import cn.oremarket.common.LoanSchedule;

/**
 * One player's loan + credit dossier, living only in server-side SavedData.
 *
 * From v2 this dossier is kept forever (never deleted even when repaid), because the credit score reads history:
 * - {@link #onTimeRepays} loans settled on time, {@link #overdueTotal} overdue count, {@link #bankruptcies} bankruptcy count
 * are all cumulative, combined with live assets and level to compute the credit score
 * ({@code LoanRules.creditScore}, never persisted but recomputed each time, so a stored score cannot drift with config);
 * - {@link #limitStrikes} is the number of quota discounts still in effect, decaying automatically with repayments and time;
 * - {@link #loanFunds} is borrowed-but-unspent ore coins, used to forbid converting loan money straight into ores;
 * - {@link #clawbackDebt} is debt NOT forgiven at bankruptcy (loans inside the clawback window); it must be repaid before borrowing again.
 *
 * Rules quick-reference (all legacy conventions retained):
 * - borrowDay doubles as the borrowed-today flag and survives repayment;
 * - dueDay = borrow day + term (with installments: the last period's due day);
 * - interest settles only at the TRADING-DAY close, never tick by tick (accruedDay is the idempotency gate);
 * - the three loan parameters (daily rate / term / interest cap multiplier) are locked into this object at borrow time;
 * later config changes only affect new loans; product, repayment mode and installments are locked likewise;
 * - cooldownEndDay = bankruptcy day + 1 + cooldown days;
 * - catchupKind: hint type recorded when a forced liquidation / bankruptcy settlement ran while offline (0 none / 1 liquidation / 2 bankruptcy).
 */
public final class LoanData {
    // ------------------------------------------------------ loan products
    /** Credit loan: unsecured; the quota follows level and credit score. */
    public static final int PRODUCT_CREDIT = 0;
    /** Collateral loan: the quota includes a haircut on position value, carries a lower rate, and the first overdue installment triggers forced liquidation. */
    public static final int PRODUCT_COLLATERAL = 1;
    /** Emergency loan: a small, high-rate, short-term loan that is still available during the bankruptcy cooldown (the comeback channel). */
    public static final int PRODUCT_EMERGENCY = 2;
    /** Short-selling margin loan: the proceeds may only fund short margin; the rate is marked up by a factor. */
    public static final int PRODUCT_MARGIN = 3;
    /** Number of products (for validation and UI cycling). */
    public static final int PRODUCT_COUNT = 4;

    // ------------------------------------------------------ outstanding-loan fields
    /** Outstanding principal (0 = no active loan). */
    public long amount;
    /** Accrued interest (settled per trading day; after overdue, penalty interest is recorded separately in {@link #penalty}). */
    public double interest;
    /** Accrued penalty interest (charged per day after the grace period). */
    public double penalty;
    /** Due trading day = borrow day + term. */
    public long dueDay;
    /** Daily rate locked at borrow time (fraction). 0 = legacy save; falls back to current config. */
    public double lockedDailyRate;
    /** Term (trading days) locked at borrow time. 0 = legacy save; falls back to current config. */
    public int lockedTermDays;
    /** Principal-plus-interest cap multiplier locked at borrow time (principal x this factor). 0 = legacy save; falls back to current config. */
    public double lockedCapMultiplier;
    /** Borrow trading day (kept after repayment as the borrowed-today flag). */
    public long borrowDay = Long.MIN_VALUE;
    /** Trading day the bankruptcy cooldown ends: borrowing is forbidden while the current day is below it. */
    public long cooldownEndDay;
    /** In-progress overdue liquidation flag: parked when a suspension/freeze makes collection impossible; settles as soon as the money is collected. */
    public boolean liquidating;
    /** Offline liquidation outcome, replayed when the player comes online. */
    public int catchupKind;
    /** Principal-plus-interest total carried in the replay message. */
    public long catchupPaid;

    // ------------------------------------------------------ v2: installments and repayment modes
    /** Loan product ({@code PRODUCT_*}). */
    public int product = PRODUCT_CREDIT;
    /** Repayment mode ({@code LoanSchedule.MODE_*}). */
    public int mode = LoanSchedule.MODE_BALLOON;
    /** Installment count (1 = settle everything at maturity). */
    public int installments = 1;
    /** Loan principal (the full amount disbursed at borrow time; {@link #amount} is how much is still unpaid). */
    public long principal;
    /** Total principal repaid. */
    public long paidPrincipal;
    /** Total interest repaid (normal interest, excluding penalty interest). */
    public long paidInterest;
    /** Total penalty interest repaid. */
    public long paidPenalty;
    /** Next installment number (1-based): the schedule marks settled/unsettled rows by it. */
    public int nextInst = 1;
    /** Trading day up to which interest has been settled daily: guarantees one settlement per day, never per tick. */
    public long accruedDay = Long.MIN_VALUE;
    /** Consecutive overdue installments (reaching loanOverdueStrikeLimit triggers forced liquidation). */
    public int overdueStrikes;
    /** Quota discounts still in effect (one decays per settled loan, or every loanLimitRecoverDays days). */
    public int limitStrikes;
    /** Trading day of the latest quota discount (the decay timing baseline). */
    public long limitStrikeDay;
    /**
     * The trading day of the most recent installment rollover (the gate against cross-period prepayment).
     *
     * <p>The player's second requirement -- keep the surplus, do not deduct across periods, later installments must stay
     * unsettled, <b>and only the next trading day may unlock another installment</b>. Capping each payment at the current
     * bill alone cannot stop rapid clicking: once installment 1 is paid the index jumps to 2 immediately, and a second
     * click the same day would serve up installment 2's bill. So we record on which trading day the last rollover happened:
     * a not-yet-due installment may roll at most once PER TRADING DAY. Already-due arrears are exempt (real debt, not prepayment).
     */
    public long lastAdvanceDay = Long.MIN_VALUE;

    // ------------------------------------------------------ v2: credit dossier (kept forever)
    /** Number of loans settled on time. */
    public int onTimeRepays;
    /** Cumulative overdue count (history; never decays). */
    public int overdueTotal;
    /** Cumulative bankruptcy count. */
    public int bankruptcies;
    /** Trading day of the most recent settlement (or bankruptcy). */
    public long lastSettledDay = Long.MIN_VALUE;

    // ------------------------------------------------------ v2: anti-arbitrage and P2P
    /** Loan proceeds received but not yet spent: these coins may NOT be exchanged into ores. */
    public long loanFunds;
    /** Debt borrowed inside the bankruptcy clawback window and therefore not forgiven (must be repaid before a new loan). */
    public long clawbackDebt;
    /**
     * The shortfall left after liquidation proceeds and all ore coins
     * have been applied, when a collateral loan goes under water or a margin loan blows up. Counts into total debt, is
     * auto-collected at each close, and is peer to {@link #clawbackDebt}: {@link #resetLoan()} does not zero it -- it disappears only when repaid.
     */
    public long shortDebt;
    /** Lender of a P2P loan note (null = system liquidity pool). */
    public java.util.UUID p2pLender;
    /** P2P loan note id (0 = not a P2P loan). */
    public long p2pListingId;
    /**
     * Cumulative amount actually transferred to the lender (bookkeeping and idempotency only:
     * while unsettled each payment is delivered on the spot, and the settling payment is delivered by routeLoanRepayment; the two never double-pay).
     */
    public long p2pDelivered;

    public boolean hasLoan() {
        return amount > 0;
    }

    /** Whether this player borrowed today (including loans already repaid). */
    public boolean borrowedOn(long day) {
        return borrowDay == day;
    }

    public boolean inCooldown(long day) {
        return day < cooldownEndDay;
    }

    /** Locked daily rate (falls back to the current config when nothing was locked). Part 2: the read path enforces the cap, so even a legacy 4.573% save is pressed down to 1%. */
    public double dailyRate() {
        double raw = lockedDailyRate > 0 ? lockedDailyRate : LoanRules.dailyRate();
        return LoanRules.clampDailyRate(raw);
    }

    /** Locked interest cap multiplier (principal-plus-interest cap = principal x this factor). */
    public double capMultiplier() {
        return lockedCapMultiplier > 0 ? lockedCapMultiplier : LoanRules.capMultiplier();
    }

    /** Locked interest cap (as a ratio of principal). */
    public double capRate() {
        return Math.max(0.0, capMultiplier() - 1.0);
    }

    /** Locked / legacy-save term (trading days). */
    public int termDays() {
        return lockedTermDays > 0 ? lockedTermDays : LoanRules.termDays();
    }

    /** Outstanding principal (degrades to the balance when a legacy save has no principal snapshot). */
    public long basePrincipal() {
        return principal > 0 ? principal : amount;
    }

    /**
     * Current principal-plus-interest payable (ore coins). Part 1: the single global entry
     * {@link cn.oremarket.common.LoanCalculator#payoff},
     * formula = outstanding principal + min(planned unsettled interest + penalty, principal x (cap multiplier - 1)).
     * The client's total-debt row, the schedule total, the auto-repayment deduction at close and the log's principal+interest all share this source.
     */
    public long payoff() {
        if (amount <= 0) {
            return 0L;
        }
        return cn.oremarket.common.LoanCalculator
                .payoff(amount, basePrincipal(), schedule(), paidInterest,
                        (long) Math.ceil(Math.max(0.0, penalty)), capMultiplier());
    }

    /**
     * Unsettled interest (excluding penalty). Part 1: schedule interest total minus repaid interest,
     * always equal to the detail interest row and to the sum of the schedule's unsettled rows.
     */
    public long interestDue() {
        if (amount <= 0) {
            return 0L;
        }
        return cn.oremarket.common.LoanCalculator
                .remainingInterestOwed(schedule(), paidInterest);
    }

    /** Outstanding penalty interest (ceil'd integer convention, same source as the packet field and the split deduction). */
    public long penaltyDue() {
        return (long) Math.ceil(Math.max(0.0, penalty));
    }

    /**
     * The bill composition actually payable by this REGULAR repayment (pay 1 / 10 / 100 / pay off).
     *
     * <p>The criterion itself lives in the pure calculation class
     * {@link LoanSchedule#payable(java.util.List, int, long, long)} (the offline verification harness runs the same bytecode);
     * this method does exactly two things: (1) compute the remaining components of the payable installments from the
     * schedule + current installment + today + the TOTAL REPAID (whatever was already paid into this period shrinks the bill);
     * (2) clamp those components into the book's real debt -- interest never exceeds planned minus repaid interest, principal
     * never exceeds the outstanding principal, so even a dirty save cannot collect money that does not exist.
     *
     * <p>The principal component is further gated by the interest-first rule ({@code LoanService.principalAllowed});
     * that cut is made by the caller -- this method reports the planned values faithfully.
     */
    public cn.oremarket.common.LoanSchedule.Payable payable(long day) {
        cn.oremarket.common.LoanSchedule.Payable p =
                cn.oremarket.common.LoanSchedule
                        .payable(schedule(), nextInst, day, paidTotal());
        long interestCap = interestDue();
        if (p.interest > interestCap) {
            p.interest = interestCap;
        }
        long principalCap = Math.max(0L, amount);
        if (p.principal > principalCap) {
            p.principal = principalCap;
        }
        return p;
    }

    /**
     * Upper bound of the bill actually payable by this REGULAR repayment (pay 1 / 10 / 100 / pay off) -- the single source,
     * the same function as the client's current-payment figure
     * {@link cn.oremarket.common.LoanCalculator#payableBill}.
     *
     * @param day current trading day
     * @param allowPrincipal whether the principal path is open (interest-first before the last period = false)
     */
    public long payableBill(long day, boolean allowPrincipal) {
        return cn.oremarket.common.LoanCalculator.payableBill(
                schedule(), nextInst, day, penaltyDue(), interestDue(), amount, allowPrincipal,
                paidTotal());
    }

    /** Whether the interest cap has been reached (principal-plus-interest cap = principal x locked multiplier). Judged on the planned convention, same source as payoff. */
    public boolean interestCapped() {
        long cap = cn.oremarket.common.LoanCalculator
                .interestCap(basePrincipal(), capMultiplier());
        return cap > 0 && interestDue() + penaltyDue() >= cap;
    }

    /** Repayment schedule: generated from the locked parameters and shared with the client display by one algorithm. */
    public java.util.List<LoanSchedule.Row> schedule() {
        return cn.oremarket.common.LoanCalculator
                .schedule(borrowDay, basePrincipal(), dailyRate(), termDays(), installments, mode);
    }

    /** Clamp a product id into the valid range (dirty values from the client must not bypass validation). */
    public static int clampProduct(int p) {
        return p >= 0 && p < PRODUCT_COUNT ? p : PRODUCT_CREDIT;
    }

    /** Whether ANY debt remains (outstanding loan + unforgiven bankruptcy debt + forced-liquidation shortfall). */
    public boolean owesAnything() {
        return amount > 0 || clawbackDebt > 0 || shortDebt > 0;
    }

    /** Forced-liquidation shortfall (dirty negatives treated as 0). Counts into total debt. */
    public long shortDebtDue() {
        return Math.max(0L, shortDebt);
    }

    /** UI days remaining: due day minus today (may be negative = overdue). */
    public long daysLeft(long day) {
        return dueDay - day;
    }

    /**
     * Apply one repayment's split to the book. This shared method keeps the formula in one place instead of
     * {@code LoanService} so the offline verification harness can exercise PRODUCTION CODE ITSELF
     * (instead of equivalent logic rewritten inside tests).
     *
     * <p>Invariants: all three fields stay non-negative, and the book's outstanding debt shrinks by exactly the amount
     * actually applied -- every collection in forced liquidation / bankruptcy flows through here, so a parked retry or a
     * bankruptcy branch can neither charge the same money twice nor re-book an already-paid amount as revoked debt.
     *
     * @param split {@code [0]=penalty [1]=interest [2]=principal} (produced by {@link cn.oremarket.common.LoanRules#splitPayment})
     */
    public void applySplit(long[] split) {
        if (split == null || split.length < 3) {
            return;
        }
        long onPenalty = Math.max(0L, split[0]);
        long onInterest = Math.max(0L, split[1]);
        long onPrincipal = Math.max(0L, split[2]);
        penalty = Math.max(0.0, penalty - onPenalty);
        paidPenalty = saturatingAdd(paidPenalty, onPenalty);
        interest = Math.max(0.0, interest - onInterest);
        paidInterest = saturatingAdd(paidInterest, onInterest);
        amount = Math.max(0L, amount - onPrincipal);
        paidPrincipal = saturatingAdd(paidPrincipal, onPrincipal);
    }

    /** Saturating add: dirty data (a forged huge split) must never wrap the repaid totals back to negative. */
    private static long saturatingAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        return a + b;
    }

    /**
     * Total repaid (penalty + interest + principal): {@link #advanceInstallment()} uses this SAME number to advance
     * the installment index and {@link #payableBill(long, boolean)} uses it to shrink the current bill --
     * wherever the index has reached is exactly how far the bill has shrunk; the two always reconcile.
     */
    public long paidTotal() {
        long paid = paidPrincipal >= Long.MAX_VALUE - paidInterest
                ? Long.MAX_VALUE : paidPrincipal + paidInterest;
        return paid >= Long.MAX_VALUE - paidPenalty ? Long.MAX_VALUE : paid + paidPenalty;
    }

    /**
     * Advance the unsettled installment index by the total repaid (criterion in {@link LoanSchedule#openIndex}, the same source as the server's overdue baseline).
     * Loan posting, every repayment and every forced-liquidation collection go through here; the client's installment N / M reads the same field.
     */
    public void advanceInstallment() {
        nextInst = LoanSchedule.openIndex(schedule(), paidTotal());
    }

    CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.putLong("Amount", amount);
        t.putDouble("Interest", interest);
        t.putLong("DueDay", dueDay);
        t.putLong("BorrowDay", borrowDay);
        t.putLong("CooldownEndDay", cooldownEndDay);
        t.putDouble("DailyRate", lockedDailyRate);
        t.putInt("TermDays", lockedTermDays);
        t.putDouble("CapMultiplier", lockedCapMultiplier);
        t.putBoolean("Liquidating", liquidating);
        t.putInt("CatchupKind", catchupKind);
        t.putLong("CatchupPaid", catchupPaid);
        // ---- v2 (read/write by name: keys missing in legacy saves fall back to defaults, semantics identical to the old version, so no one-shot migration script is needed)
        t.putDouble("Penalty", penalty);
        t.putInt("Product", product);
        t.putInt("Mode", mode);
        t.putInt("Installments", installments);
        t.putLong("Principal", principal);
        t.putLong("PaidPrincipal", paidPrincipal);
        t.putLong("PaidInterest", paidInterest);
        t.putLong("PaidPenalty", paidPenalty);
        t.putInt("NextInst", nextInst);
        t.putLong("AccruedDay", accruedDay);
        t.putInt("OverdueStrikes", overdueStrikes);
        t.putInt("LimitStrikes", limitStrikes);
        t.putLong("LimitStrikeDay", limitStrikeDay);
        t.putLong("LastAdvanceDay", lastAdvanceDay);
        t.putInt("OnTimeRepays", onTimeRepays);
        t.putInt("OverdueTotal", overdueTotal);
        t.putInt("Bankruptcies", bankruptcies);
        t.putLong("LastSettledDay", lastSettledDay);
        t.putLong("LoanFunds", loanFunds);
        t.putLong("ClawbackDebt", clawbackDebt);
        t.putLong("ShortDebt", shortDebt);
        t.putLong("P2PListing", p2pListingId);
        t.putLong("P2PDelivered", p2pDelivered);
        if (p2pLender != null) {
            t.putUUID("P2PLender", p2pLender);
        }
        return t;
    }

    static LoanData fromTag(CompoundTag t) {
        LoanData l = new LoanData();
        l.amount = t.getLong("Amount");
        l.interest = t.getDouble("Interest");
        l.dueDay = t.getLong("DueDay");
        l.borrowDay = t.getLong("BorrowDay");
        l.cooldownEndDay = t.getLong("CooldownEndDay");
        l.lockedDailyRate = t.getDouble("DailyRate");
        l.lockedTermDays = t.getInt("TermDays");
        l.lockedCapMultiplier = t.getDouble("CapMultiplier");
        l.liquidating = t.getBoolean("Liquidating");
        l.catchupKind = t.getInt("CatchupKind");
        l.catchupPaid = t.getLong("CatchupPaid");
        l.penalty = t.contains("Penalty") ? Math.max(0.0, t.getDouble("Penalty")) : 0.0;
        l.product = t.contains("Product") ? clampProduct(t.getInt("Product")) : PRODUCT_CREDIT;
        l.mode = t.contains("Mode") ? LoanSchedule.clampMode(t.getInt("Mode")) : LoanSchedule.MODE_BALLOON;
        l.installments = t.contains("Installments")
                ? LoanSchedule.clampInstallmentsLegacy(t.getInt("Installments")) : 1;
        l.principal = Math.max(0, t.getLong("Principal"));
        l.paidPrincipal = Math.max(0, t.getLong("PaidPrincipal"));
        l.paidInterest = Math.max(0, t.getLong("PaidInterest"));
        l.paidPenalty = Math.max(0, t.getLong("PaidPenalty"));
        l.nextInst = t.contains("NextInst") ? Math.max(1, t.getInt("NextInst")) : 1;
        l.accruedDay = t.contains("AccruedDay") ? t.getLong("AccruedDay") : Long.MIN_VALUE;
        l.overdueStrikes = Math.max(0, t.getInt("OverdueStrikes"));
        l.limitStrikes = Math.max(0, t.getInt("LimitStrikes"));
        l.limitStrikeDay = t.getLong("LimitStrikeDay");
        l.lastAdvanceDay = t.contains("LastAdvanceDay") ? t.getLong("LastAdvanceDay") : Long.MIN_VALUE;
        l.onTimeRepays = Math.max(0, t.getInt("OnTimeRepays"));
        l.overdueTotal = Math.max(0, t.getInt("OverdueTotal"));
        l.bankruptcies = Math.max(0, t.getInt("Bankruptcies"));
        l.lastSettledDay = t.contains("LastSettledDay") ? t.getLong("LastSettledDay") : Long.MIN_VALUE;
        l.loanFunds = Math.max(0, t.getLong("LoanFunds"));
        l.clawbackDebt = Math.max(0, t.getLong("ClawbackDebt"));
        l.shortDebt = Math.max(0, t.getLong("ShortDebt"));
        l.p2pListingId = Math.max(0, t.getLong("P2PListing"));
        l.p2pLender = t.hasUUID("P2PLender") ? t.getUUID("P2PLender") : null;
        l.p2pDelivered = Math.max(0, t.getLong("P2PDelivered"));
        if (l.principal <= 0 && l.amount > 0) {
            l.principal = l.amount;      // Legacy save without a principal snapshot: backfill it from the outstanding balance
        }
        return l;
    }

    /**
     * Reset loan fields after settlement or bankruptcy: interest/penalty go to zero, but borrowDay, cooldownEndDay
     * and the whole credit history are PRESERVED (the score must read history; requirement: the quota
     * recovers after repayment and bankruptcy leaves a dossier behind).
     */
    public void resetLoan() {
        amount = 0;
        interest = 0;
        penalty = 0;
        dueDay = 0;
        principal = 0;
        paidPrincipal = 0;
        paidInterest = 0;
        paidPenalty = 0;
        nextInst = 1;
        lastAdvanceDay = Long.MIN_VALUE;
        accruedDay = Long.MIN_VALUE;
        overdueStrikes = 0;
        liquidating = false;
        product = PRODUCT_CREDIT;
        mode = LoanSchedule.MODE_BALLOON;
        installments = 1;
        lockedDailyRate = 0;
        lockedTermDays = 0;
        lockedCapMultiplier = 0;
        loanFunds = 0;
        p2pDelivered = 0;
        p2pLender = null;
        p2pListingId = 0;
    }
}
