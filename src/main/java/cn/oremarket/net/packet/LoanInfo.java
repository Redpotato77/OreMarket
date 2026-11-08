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

package cn.oremarket.net.packet;

import cn.oremarket.common.LoanRules;
import cn.oremarket.common.LoanSchedule;
import cn.oremarket.market.LoanData;
import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.LoanService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Loan and credit fields shared by the snapshot and incremental packets.
 *
 * <p>Protocol discipline: the packet carries <b>only numbers and enum
 * ordinals</b> - no wording of any kind, and not the repayment schedule itself. Given principal /
 * daily rate / term / installment count / repayment mode / borrow day, the client rebuilds the very
 * same schedule with the pure-JDK {@link LoanSchedule} (both sides use integer division, so the
 * results necessarily match); rate, limit and score wording is translated client-side from the
 * {@code oremarket.loan.*} language keys.
 *
 * <p>Display derivations (all computed server-side; the client no longer guesses):
 *  - available credit = {@link #creditLimit} - {@link #loanAmount} (the UI clamps it to >= 0 once more);
 *  - total debt = {@link #loanPayoff} + {@link #clawbackDebt} + {@link #shortDebt};
 *  - days remaining = {@link #loanDueDay} - {@link #tradeDay};
 *  - has a loan = {@code loanAmount > 0 || principal > 0}.
 */
public final class LoanInfo {
    public final long tradeDay;
    /** Outstanding principal (0 = no loan at the moment). */
    public final long loanAmount;
    /** Total principal + interest due now (penalty interest included; already reflects the "at least 1 day" rule and the interest cap). */
    public final long loanPayoff;
    public final long loanDueDay;
    public final boolean borrowedToday;
    public final long cooldownEndDay;
    public final boolean liquidating;
    /** Whether the interest has reached its cap (principal x loanInterestCapMultiplier). */
    public final boolean capped;

    // ---------- v2: credit and limits
    /** Dynamic limit of the current product (base level limit x score multiplier + asset bonus - overdue haircut). */
    public final long creditLimit;
    /** Credit score 300-950 (the 600 baseline when the scoring system is turned off). */
    public final int creditScore;
    /** Un-forgiven bankruptcy debt (it must be paid off before any new loan can be taken). */
    public final long clawbackDebt;
    /** Forced Liquidation shortfall (a Collateralized Loan under water or a Margin loan blown up); counted into total debt. */
    public final long shortDebt;

    // ---------- v2: product and installments (the client rebuilds the repayment schedule from these)
    public final int product;
    public final int mode;
    public final int installments;
    /** Number of the next installment (1-based); the schedule marks rows as settled / unsettled with it. */
    public final int nextInst;
    /** Loan principal (the full amount at disbursement). */
    public final long principal;
    /** Borrow Trading Day (the scheduling baseline of the plan table). */
    public final long borrowDay;
    /** Term in Trading Days. */
    public final int termDays;
    /** Locked daily rate as a decimal (0.0035 = 0.35%/day). */
    public final double dailyRate;
    /** Interest paid in total (excluding penalty interest). */
    public final long paidInterest;
    /** Penalty interest paid in total. */
    public final long paidPenalty;
    /** Accumulated penalty interest still outstanding. */
    public final long penalty;
    /** Borrowed coins not yet spent (they may not be exchanged for ores). */
    public final long loanFunds;
    /** Current streak of Overdue days. */
    public final int overdueStrikes;
    /** Credit history: on-time payoffs / cumulative overdue strikes / cumulative bankruptcies. */
    public final int onTimeRepays;
    public final int overdueTotal;
    public final int bankruptcies;
    /** Amount of my active listing (0 = no listing). */
    public final long myListingCoins;
    /** Daily rate of my active listing (decimal). */
    public final double myListingRate;
    /** Days left on my listing. */
    public final long myListingDaysLeft;
    /** How many listings on the market I am able to take. */
    public final int openListings;
    /** Best daily rate on the market (decimal, 0 = no listings). */
    public final double bestListingRate;
    /** The "quoted daily rate at this moment" on credit-loan terms (event drift + credit premium computed server-side; the client uses it for estimates only). */
    public final double quoteRate;

    public LoanInfo(long tradeDay, long loanAmount, long loanPayoff, long loanDueDay,
                    boolean borrowedToday, long cooldownEndDay, boolean liquidating, boolean capped,
                    long creditLimit, int creditScore, long clawbackDebt, long shortDebt,
                    int product, int mode, int installments, int nextInst,
                    long principal, long borrowDay, int termDays, double dailyRate,
                    long paidInterest, long paidPenalty, long penalty, long loanFunds,
                    int overdueStrikes, int onTimeRepays, int overdueTotal, int bankruptcies,
                    double quoteRate, long myListingCoins, double myListingRate, long myListingDaysLeft,
                    int openListings, double bestListingRate) {
        this.tradeDay = tradeDay;
        this.loanAmount = loanAmount;
        this.loanPayoff = loanPayoff;
        this.loanDueDay = loanDueDay;
        this.borrowedToday = borrowedToday;
        this.cooldownEndDay = cooldownEndDay;
        this.liquidating = liquidating;
        this.capped = capped;
        this.creditLimit = creditLimit;
        this.creditScore = creditScore;
        this.clawbackDebt = clawbackDebt;
        this.shortDebt = Math.max(0L, shortDebt);
        this.product = product;
        this.mode = mode;
        this.installments = installments;
        this.nextInst = nextInst;
        this.principal = principal;
        this.borrowDay = borrowDay;
        this.termDays = termDays;
        this.dailyRate = dailyRate;
        this.paidInterest = paidInterest;
        this.paidPenalty = paidPenalty;
        this.penalty = penalty;
        this.loanFunds = loanFunds;
        this.overdueStrikes = overdueStrikes;
        this.onTimeRepays = onTimeRepays;
        this.overdueTotal = overdueTotal;
        this.bankruptcies = bankruptcies;
        this.quoteRate = quoteRate;
        this.myListingCoins = myListingCoins;
        this.myListingRate = myListingRate;
        this.myListingDaysLeft = myListingDaysLeft;
        this.openListings = openListings;
        this.bestListingRate = bestListingRate;
    }

    /** Client side: the empty object used before any player state arrives (all numbers 0, empty schedule). */
    public static LoanInfo empty() {
        return new LoanInfo(1, 0, 0, 0, false, 0, false, false,
                0, LoanRules.SCORE_BASE, 0, 0,
                LoanData.PRODUCT_CREDIT, LoanSchedule.MODE_BALLOON, 1, 1,
                0, Long.MIN_VALUE, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0);
    }

    /** Server side: packs the player's current profile (dynamic limit on credit-loan terms). */
    public static LoanInfo of(MarketSavedData data, UUID id) {
        LoanData l = data.loanOrNull(id);
        long day = data.tradeDay();
        long limit = LoanService.creditLimit(data, id);
        int score = LoanService.creditScore(data, id);
        cn.oremarket.market.P2PListing mine =
                cn.oremarket.market.LoanService.myOpenListing(data, id, day);
        long myCoins = mine == null ? 0 : mine.coins;
        double myRate = mine == null ? 0 : mine.rate;
        long myLeft = mine == null ? 0 : mine.daysLeft(day);
        int openCount = cn.oremarket.market.LoanService.openListingCount(data, id, day);
        double bestRate = cn.oremarket.market.LoanService.bestOpenRate(data, id, day);
        if (l == null) {
            // Even with no loan profile, the quoted rate and the listing info
            // must ride along (the old code zeroed the whole block, so a player opening the loan page
            // for the first time saw "estimated daily rate 0.000% / estimated total = principal",
            // which contradicted the numbers once a fill actually happened).
            return new LoanInfo(day, 0, 0, 0, false, 0, false, false,
                    limit, score, 0, 0,
                    LoanData.PRODUCT_CREDIT, LoanSchedule.MODE_BALLOON, 1, 1,
                    0, Long.MIN_VALUE, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    LoanService.quoteRate(data, id), myCoins, myRate, myLeft, openCount, bestRate);
        }
        return new LoanInfo(day, l.amount, l.hasLoan() ? l.payoff() : 0, l.dueDay,
                l.borrowedOn(day), l.cooldownEndDay, l.liquidating, l.hasLoan() && l.interestCapped(),
                limit, score, l.clawbackDebt, l.shortDebtDue(),
                l.product, l.mode, l.installments, l.nextInst,
                l.principal, l.borrowDay, l.termDays(), l.dailyRate(),
                l.paidInterest, l.paidPenalty, (long) Math.ceil(Math.max(0, l.penalty)),
                l.loanFunds, l.overdueStrikes, l.onTimeRepays, l.overdueTotal, l.bankruptcies,
                LoanService.quoteRate(data, id),
                myCoins, myRate, myLeft, openCount, bestRate);
    }

    /** Repayment schedule: rebuilt on the client with exactly the same pure-integer algorithm as the server (no wording, no float table on the wire). */
    public java.util.List<LoanSchedule.Row> schedule() {
        if (principal <= 0 || termDays <= 0) {
            return java.util.List.of();
        }
        return cn.oremarket.common.LoanCalculator
                .schedule(borrowDay, principal, dailyRate, termDays, installments, mode);
    }

    /** Credit-band language key (the UI resolves it to a translation; the packet still carries only numbers). */
    public String scoreKey() {
        return LoanRules.scoreBandKey(creditScore);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt((int) Math.min(Integer.MAX_VALUE, tradeDay));
        buf.writeLong(loanAmount);
        buf.writeLong(loanPayoff);
        buf.writeLong(loanDueDay);
        int flags = (borrowedToday ? 1 : 0) | (liquidating ? 2 : 0) | (capped ? 4 : 0);
        buf.writeByte(flags);
        buf.writeLong(cooldownEndDay);
        buf.writeLong(creditLimit);
        buf.writeVarInt(creditScore);
        buf.writeLong(clawbackDebt);
        buf.writeLong(shortDebt);
        buf.writeVarInt(product);
        buf.writeVarInt(mode);
        buf.writeVarInt(installments);
        buf.writeVarInt(nextInst);
        buf.writeLong(principal);
        buf.writeLong(borrowDay);
        buf.writeVarInt(termDays);
        buf.writeDouble(dailyRate);
        buf.writeLong(paidInterest);
        buf.writeLong(paidPenalty);
        buf.writeLong(penalty);
        buf.writeLong(loanFunds);
        buf.writeVarInt(overdueStrikes);
        buf.writeVarInt(onTimeRepays);
        buf.writeVarInt(overdueTotal);
        buf.writeVarInt(bankruptcies);
        buf.writeDouble(quoteRate);
        buf.writeLong(myListingCoins);
        buf.writeDouble(myListingRate);
        buf.writeLong(myListingDaysLeft);
        buf.writeVarInt(openListings);
        buf.writeDouble(bestListingRate);
    }

    /** NBT form (used by the variable-length player snapshot packet). */
    public CompoundTag toCompound() {
        CompoundTag t = new CompoundTag();
        t.putLong("TradeDay", tradeDay);
        t.putLong("Amount", loanAmount);
        t.putLong("Payoff", loanPayoff);
        t.putLong("DueDay", loanDueDay);
        t.putBoolean("BorrowedToday", borrowedToday);
        t.putLong("CooldownEndDay", cooldownEndDay);
        t.putBoolean("Liquidating", liquidating);
        t.putBoolean("Capped", capped);
        t.putLong("CreditLimit", creditLimit);
        t.putInt("CreditScore", creditScore);
        t.putLong("ClawbackDebt", clawbackDebt);
        t.putLong("ShortDebt", shortDebt);
        t.putInt("Product", product);
        t.putInt("Mode", mode);
        t.putInt("Installments", installments);
        t.putInt("NextInst", nextInst);
        t.putLong("Principal", principal);
        t.putLong("BorrowDay", borrowDay);
        t.putInt("TermDays", termDays);
        t.putDouble("DailyRate", dailyRate);
        t.putLong("PaidInterest", paidInterest);
        t.putLong("PaidPenalty", paidPenalty);
        t.putLong("Penalty", penalty);
        t.putLong("LoanFunds", loanFunds);
        t.putInt("OverdueStrikes", overdueStrikes);
        t.putInt("OnTimeRepays", onTimeRepays);
        t.putInt("OverdueTotal", overdueTotal);
        t.putInt("Bankruptcies", bankruptcies);
        t.putDouble("QuoteRate", quoteRate);
        t.putLong("MyListingCoins", myListingCoins);
        t.putDouble("MyListingRate", myListingRate);
        t.putLong("MyListingDaysLeft", myListingDaysLeft);
        t.putInt("OpenListings", openListings);
        t.putDouble("BestListingRate", bestListingRate);
        return t;
    }

    public static LoanInfo fromCompound(CompoundTag t) {
        return new LoanInfo(t.getLong("TradeDay"), t.getLong("Amount"), t.getLong("Payoff"),
                t.getLong("DueDay"), t.getBoolean("BorrowedToday"), t.getLong("CooldownEndDay"),
                t.getBoolean("Liquidating"), t.getBoolean("Capped"),
                t.getLong("CreditLimit"), t.getInt("CreditScore"), t.getLong("ClawbackDebt"),
                t.getLong("ShortDebt"),
                t.getInt("Product"), t.getInt("Mode"), t.getInt("Installments"), t.getInt("NextInst"),
                t.getLong("Principal"), t.getLong("BorrowDay"), t.getInt("TermDays"),
                t.getDouble("DailyRate"), t.getLong("PaidInterest"), t.getLong("PaidPenalty"),
                t.getLong("Penalty"), t.getLong("LoanFunds"), t.getInt("OverdueStrikes"),
                t.getInt("OnTimeRepays"), t.getInt("OverdueTotal"), t.getInt("Bankruptcies"),
                t.getDouble("QuoteRate"), t.getLong("MyListingCoins"), t.getDouble("MyListingRate"),
                t.getLong("MyListingDaysLeft"), t.getInt("OpenListings"), t.getDouble("BestListingRate"));
    }

    public static LoanInfo read(FriendlyByteBuf buf) {
        long day = buf.readVarInt();
        long amount = buf.readLong();
        long payoff = buf.readLong();
        long due = buf.readLong();
        int flags = buf.readByte();
        long cooldown = buf.readLong();
        long limit = buf.readLong();
        int score = buf.readVarInt();
        long clawback = buf.readLong();
        long shortDebt = buf.readLong();
        int product = buf.readVarInt();
        int mode = buf.readVarInt();
        int installments = buf.readVarInt();
        int nextInst = buf.readVarInt();
        long principal = buf.readLong();
        long borrowDay = buf.readLong();
        int termDays = buf.readVarInt();
        double rate = buf.readDouble();
        long paidInterest = buf.readLong();
        long paidPenalty = buf.readLong();
        long penalty = buf.readLong();
        long loanFunds = buf.readLong();
        int overdueStrikes = buf.readVarInt();
        int onTimeRepays = buf.readVarInt();
        int overdueTotal = buf.readVarInt();
        int bankruptcies = buf.readVarInt();
        double quoteRate = buf.readDouble();
        long myCoins = buf.readLong();
        double myRate = buf.readDouble();
        long myLeft = buf.readLong();
        int openCount = buf.readVarInt();
        double bestRate = buf.readDouble();
        return new LoanInfo(day, amount, payoff, due, (flags & 1) != 0, cooldown,
                (flags & 2) != 0, (flags & 4) != 0, limit, score, clawback, shortDebt,
                product, mode, installments, nextInst, principal, borrowDay, termDays, rate,
                paidInterest, paidPenalty, penalty, loanFunds, overdueStrikes,
                onTimeRepays, overdueTotal, bankruptcies, quoteRate,
                myCoins, myRate, myLeft, openCount, bestRate);
    }
}
