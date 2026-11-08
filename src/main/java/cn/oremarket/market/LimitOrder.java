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

import cn.oremarket.common.Fees;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Server-side limit order: executes at the actual market price once its trigger condition is met. */
public final class LimitOrder {
    public final long id;
    public final UUID owner;
    public final String ownerName;
    public final String code;
    public final boolean buy;
    public double triggerPrice;
    public int quantity;
    public final long createdSequence;
    public final long createdDay;
    public int insufficientTicks;

    public LimitOrder(long id, UUID owner, String ownerName, String code, boolean buy, double triggerPrice,
                      int quantity, long createdSequence, long createdDay) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.code = code == null ? "" : code;
        this.buy = buy;
        this.triggerPrice = Math.max(0.0, triggerPrice);
        this.quantity = Math.max(0, quantity);
        this.createdSequence = createdSequence;
        this.createdDay = Math.max(1L, createdDay);
    }

    /** Maximum buyer reservation at the trigger price, including the current limit-order fee. */
    public long lockedCoins() {
        MarketSettings settings = MarketSettings.current();
        return lockedCoins(Fees.additive(settings.taxRate(), settings.limitOrderFeeRate));
    }

    /** Pure reservation calculation used by the live lock ledger and regression tests. */
    public long lockedCoins(double totalRate) {
        if (!buy || quantity <= 0 || !(triggerPrice > 0.0) || !Double.isFinite(triggerPrice)) {
            return 0L;
        }
        long value = Fees.buyCost(triggerPrice, quantity, totalRate);
        return Math.max(0L, value);
    }

    /** Converts a resting trigger to post-split terms without changing its economic size. */
    public void applyStockSplit(int ratio) {
        if (ratio <= 1) {
            return;
        }
        quantity = Math.multiplyExact(quantity, ratio);
        triggerPrice = Math.max(0.01, triggerPrice / ratio);
    }

    /** Sentinel id meaning "exclude no order": the order sequence starts at 1, so 0 never matches a real order. */
    public static final long NO_EXCLUSION = 0L;

    /**
     * Total of one player's buyer-locked ore coins across a batch of limit orders (the core criterion behind
     * audit; every available-balance query in {@code MarketSavedData} delegates here,
     * and the offline verification harness runs the very same code).
     *
     * <p>{@code excludeId} names the one order whose reservation must be "given back": when a limit order matches,
     * its own logical reservation must not count against available balance, or the player would effectively need
     * twice the funds; an all-in limit order would then be judged "insufficient funds" on every trigger and be
     * silently cancelled after limitOrderFailRetries failures.
     * All OTHER resting/limit locks still subtract as before, so excluding only oneself cannot cause overspend.
     */
    public static long lockedCoinsOf(Iterable<LimitOrder> orders, UUID owner, long excludeId) {
        if (orders == null || owner == null) {
            return 0L;
        }
        long sum = 0L;
        for (LimitOrder o : orders) {
            if (o == null || !o.buy || o.id == excludeId || !owner.equals(o.owner)) {
                continue;
            }
            long v = o.lockedCoins();
            if (v > Long.MAX_VALUE - sum) {
                return Long.MAX_VALUE;              // Saturating addition: dirty data must never wrap around into a small value
            }
            sum += v;
        }
        return sum;
    }

    /** Total of one player's seller-locked shares for one code across a batch of limit orders; {@code excludeId} as above (excludes the order itself). */
    public static int lockedSharesOf(Iterable<LimitOrder> orders, UUID owner, String code, long excludeId) {
        if (orders == null || owner == null || code == null) {
            return 0;
        }
        int sum = 0;
        for (LimitOrder o : orders) {
            if (o == null || o.buy || o.id == excludeId || !owner.equals(o.owner)
                    || !code.equals(o.code)) {
                continue;
            }
            int q = Math.max(0, o.quantity);
            if (q > Integer.MAX_VALUE - sum) {
                return Integer.MAX_VALUE;
            }
            sum += q;
        }
        return sum;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        tag.putString("Owner", owner.toString());
        tag.putString("OwnerName", ownerName);
        tag.putString("Code", code);
        tag.putBoolean("Buy", buy);
        tag.putDouble("TriggerPrice", triggerPrice);
        tag.putInt("Quantity", quantity);
        tag.putLong("CreatedSequence", createdSequence);
        tag.putLong("CreatedDay", createdDay);
        tag.putInt("InsufficientTicks", insufficientTicks);
        return tag;
    }

    public static LimitOrder fromTag(CompoundTag tag) {
        UUID owner;
        try {
            owner = UUID.fromString(tag.getString("Owner"));
        } catch (IllegalArgumentException ex) {
            owner = new UUID(0L, 0L);
        }
        LimitOrder order = new LimitOrder(tag.getLong("Id"), owner, tag.getString("OwnerName"),
                tag.getString("Code"), tag.getBoolean("Buy"), tag.getDouble("TriggerPrice"),
                tag.getInt("Quantity"), tag.getLong("CreatedSequence"), tag.getLong("CreatedDay"));
        order.insufficientTicks = Math.max(0, tag.getInt("InsufficientTicks"));
        return order;
    }
}
