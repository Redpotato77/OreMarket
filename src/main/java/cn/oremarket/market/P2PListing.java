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
 * Peer-to-peer loan listing.
 *
 * <p>The shape is deliberately minimal: who, how much, daily rate, term, listing day, taken or not.
 * When a listing is placed, the lender's coins go <b>immediately</b> into the escrow pool
 * ({@code MarketSavedData.p2pEscrow}), so the listed amount is naturally bounded by the balance and
 * a "phantom listing" backed by no funds is impossible; when the listing is taken, the escrow pool
 * pays the borrower (minus a one-time platform fee), and when it is cancelled, the full amount is
 * refunded to the lender.
 *
 * <p>All persisted fields are read and written by name with defaults: an old save (with no
 * {@code P2PListings} list) reads as "zero listings" and no existing behavior changes.
 */
public final class P2PListing {
    /** Auto-cancel deadline when nobody takes the order (listing day + this many Trading Days). */
    public static final int LISTING_TTL_DAYS = 10;
    /** Cap per listing: must not exceed the lender's own borrowable limit multiplied by this factor (also prevents over-issuance). */
    public static final int MAX_AMOUNT_TIER = 2;

    public long id;
    public UUID lender;
    /** Coins held in escrow (already deducted from the lender's balance when the listing was placed). */
    public long coins;
    /** Daily rate as a decimal (0.0035 = 0.35%/day); clamped server-side by loanRateCapPercent. */
    public double rate;
    /** Term in Trading Days, 1 to 3x loanTermDays. */
    public int termDays;
    /** Trading Day on which the listing was placed. */
    public long listedDay;
    /** Already taken (the record is kept for traceability; no longer takeable and exempt from TTL cancellation). */
    public boolean taken;

    /** Whether the listing can still be taken (untaken and not expired). */
    public boolean open(long day) {
        return !taken && day - listedDay < LISTING_TTL_DAYS;
    }

    /** Days left before the listing expires (at least 0). */
    public long daysLeft(long day) {
        return Math.max(0, LISTING_TTL_DAYS - (day - listedDay));
    }

    public CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.putLong("Id", id);
        if (lender != null) {
            t.putUUID("Lender", lender);
        }
        t.putLong("Coins", coins);
        t.putDouble("Rate", rate);
        t.putInt("Term", termDays);
        t.putLong("ListedDay", listedDay);
        t.putBoolean("Taken", taken);
        return t;
    }

    public static P2PListing fromTag(CompoundTag t) {
        P2PListing p = new P2PListing();
        p.id = Math.max(0, t.getLong("Id"));
        p.lender = t.hasUUID("Lender") ? t.getUUID("Lender") : null;
        p.coins = Math.max(0, t.getLong("Coins"));
        p.rate = Math.max(0, Math.min(1.0, t.getDouble("Rate")));
        p.termDays = Math.max(1, Math.min(240, t.getInt("Term")));
        p.listedDay = t.getLong("ListedDay");
        p.taken = t.getBoolean("Taken");
        return p;
    }
}
