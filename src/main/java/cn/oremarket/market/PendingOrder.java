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

/** One buy or sell entry in the player-to-player order book; locked resources are derived dynamically from quantity/price. */
public final class PendingOrder {
    /** Order-book fees are shared by maker and taker: each side bears one half (the divisor behind "orderFeeRate / 2"). */
    private static final double ORDER_FEE_SIDE_DIVISOR = 2.0;
    public final long id;
    public final UUID owner;
    public final String ownerName;
    public final String code;
    public final boolean buy;
    public int quantity;
    public double price;
    public final long createdSequence;
    public final long createdDay;
    public PendingOrder(long id, UUID owner, String ownerName, String code, boolean buy, int quantity,
                        double price, long createdSequence, long createdDay) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.code = code == null ? "" : code;
        this.buy = buy;
        this.quantity = Math.max(0, quantity);
        this.price = Math.max(0.0, price);
        this.createdSequence = createdSequence;
        this.createdDay = Math.max(1L, createdDay);
    }

    /**
     * Maximum coin reservation for a resting buy order.
     *
     * <p>The order is filled at the midpoint of the crossing bid and ask, which can never exceed
     * this bid price, while the buyer pays the trade tax plus half the order fee on top of the
     * execution value. Locking only the nominal value therefore let several buy orders reserve
     * the same coins and one execution drive the balance short. The reservation is the worst-case
     * actual charge of this one order: {@code buyCost(bid, quantity, tax + orderFee/2)}.
     */
    public long lockedCoins() {
        MarketSettings settings = MarketSettings.current();
        return lockedCoins(Fees.additive(settings.taxRate(),
                settings.orderFeeRate / ORDER_FEE_SIDE_DIVISOR));
    }

    /** Pure reservation calculation used by the live lock ledger and regression tests. */
    public long lockedCoins(double totalRate) {
        if (!buy || quantity <= 0 || !(price > 0.0) || !Double.isFinite(price)) {
            return 0L;
        }
        long value = Fees.buyCost(price, quantity, totalRate);
        return Math.max(0L, value);
    }

    /**
     * Converts a resting order to post-split terms. Quantity doubles while price divides by the same
     * ratio, preserving both the economic exposure and the coin reservation as closely as integer
     * share accounting allows. The caller validates every order before applying any change.
     */
    public void applyStockSplit(int ratio) {
        if (ratio <= 1) {
            return;
        }
        quantity = Math.multiplyExact(quantity, ratio);
        price = Math.max(0.01, price / ratio);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        tag.putString("Owner", owner.toString());
        tag.putString("OwnerName", ownerName);
        tag.putString("Code", code);
        tag.putBoolean("Buy", buy);
        tag.putInt("Quantity", quantity);
        tag.putDouble("Price", price);
        tag.putLong("CreatedSequence", createdSequence);
        tag.putLong("CreatedDay", createdDay);
        return tag;
    }

    public static PendingOrder fromTag(CompoundTag tag) {
        UUID owner;
        try {
            owner = UUID.fromString(tag.getString("Owner"));
        } catch (IllegalArgumentException ex) {
            owner = new UUID(0L, 0L);
        }
        return new PendingOrder(tag.getLong("Id"), owner, tag.getString("OwnerName"),
                tag.getString("Code"), tag.getBoolean("Buy"), tag.getInt("Quantity"),
                tag.getDouble("Price"), tag.getLong("CreatedSequence"), tag.getLong("CreatedDay"));
    }
}
