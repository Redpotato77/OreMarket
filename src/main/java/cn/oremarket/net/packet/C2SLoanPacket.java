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

import net.minecraft.network.FriendlyByteBuf;

/**
 * Loan request: the client sends only 'intent + numbers'.
 * Every condition (level / dynamic credit limit / active loan / borrowed today / bankruptcy cooldown /
 * unwaived debt / collateral / short positions / amount range / product and installment legality)
 * is recomputed by the server's LoanService.
 *
 * <p>The packet carries no copy text, no rate and no schedule: those are computed server-side from the current
 * config and the player's credit profile, then pushed back via {@link LoanInfo}; the client only displays numbers.
 */
public final class C2SLoanPacket {
    /** Borrow (the amount field may use STEP_* values, or be cropped to the credit limit server-side). */
    public static final int ACTION_BORROW = 0;
    /** Full payoff (the pre-existing entry point; behavior unchanged from the old version). */
    public static final int ACTION_REPAY = 1;
    /** Repay a fixed amount (pay 1 / pay 10 / custom). */
    public static final int ACTION_REPAY_AMOUNT = 2;
    /** Early settlement (discount and fee rules decided by server config). */
    public static final int ACTION_PREPAY = 3;
    /** P2P: list a loan note (amount = coins lent, ratePercentTimes100 = daily rate, termDays = term). */
    public static final int ACTION_P2P_LIST = 4;
    /** P2P: cancel your own listing (listingId <= 0 = cancel the single currently valid listing). */
    public static final int ACTION_P2P_CANCEL = 5;
    /** P2P: take one listed note (listingId <= 0 = take the best one on the market). */
    public static final int ACTION_P2P_TAKE = 6;
    /**
     * Settle the 'unwaived bankruptcy debt + forced-liquidation shortfall'.
     *
     * <p>What separates it from {@link #ACTION_REPAY_AMOUNT} is the TARGET ACCOUNT, not the amount:
     * this intent only touches the {@code clawbackDebt} / {@code shortDebt} shortfall liabilities,
     * usable even while the player still has an active loan (under the old rule ACTION_REPAY_AMOUNT threw money
     * into the active loan's principal-and-interest first, leaving players with 'active loan + liquidation shortfall'
     * no repayment channel at all). The actual deduction is recomputed by the server from the balance and debt at that
     * moment; the number sent by the client is only an intent.
     */
    public static final int ACTION_REPAY_HANGOVER = 7;

    /** UI 'borrow 1 / pay 1'. */
    public static final long STEP_ONE = 1;
    /** UI 'borrow 10 / pay 10'. */
    public static final long STEP_TEN = 10;
    /** UI 'borrow all / pay all': the amount is decided server-side from the credit limit or the sum due. */
    public static final long STEP_ALL = -1;

    public final int action;
    /** Borrow / repayment amount; STEP_ALL(-1) means 'take the full amount by the server's rule'. */
    public final long amount;
    /** Loan product ({@code LoanData.PRODUCT_*}; dirty values clamped server-side). */
    public final int product;
    /** Repayment mode ({@code LoanSchedule.MODE_*}). */
    public final int mode;
    /** Installment count (1~24; the server clamps again against the term). */
    public final int installments;
    /** P2P listing daily rate (percent x 100, i.e. 0.35% stored as 35); dirty values clamped to the cap server-side. */
    public final int ratePercentTimes100;
    /** P2P listing term (trading days). */
    public final int termDays;
    /** P2P listing id (<= 0 = 'let the server pick one'). */
    public final long listingId;

    public C2SLoanPacket(int action, long amount) {
        this(action, amount, 0, 0, 1, 0, 0, 0);
    }

    public C2SLoanPacket(int action, long amount, int product, int mode, int installments) {
        this(action, amount, product, mode, installments, 0, 0, 0);
    }

    /** P2P-only constructor (static factory, avoids confusion with the five-arg 'product/mode/installments' constructor). */
    public static C2SLoanPacket p2pList(long coins, int ratePercentTimes100, int termDays) {
        return new C2SLoanPacket(ACTION_P2P_LIST, coins, 0, 0, 1, ratePercentTimes100, termDays, 0);
    }

    public static C2SLoanPacket p2pCancel(long listingId) {
        return new C2SLoanPacket(ACTION_P2P_CANCEL, 0, 0, 0, 1, 0, 0, listingId);
    }

    /** listingId <= 0 = ask the server to take 'the best note on the market'. */
    public static C2SLoanPacket p2pTake(long listingId) {
        return new C2SLoanPacket(ACTION_P2P_TAKE, 0, 0, 0, 1, 0, 0, listingId);
    }

    private C2SLoanPacket(int action, long amount, int product, int mode, int installments,
                          int ratePercentTimes100, int termDays, long listingId) {
        this.action = action;
        this.amount = amount;
        this.product = product;
        this.mode = mode;
        this.installments = installments;
        this.ratePercentTimes100 = ratePercentTimes100;
        this.termDays = termDays;
        this.listingId = listingId;
    }

    public static C2SLoanPacket decode(FriendlyByteBuf buf) {
        return new C2SLoanPacket(buf.readVarInt(), buf.readLong(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readLong());
    }

    public static void encode(C2SLoanPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.action);
        buf.writeLong(msg.amount);
        buf.writeVarInt(msg.product);
        buf.writeVarInt(msg.mode);
        buf.writeVarInt(msg.installments);
        buf.writeVarInt(msg.ratePercentTimes100);
        buf.writeVarInt(msg.termDays);
        buf.writeLong(msg.listingId);
    }
}
