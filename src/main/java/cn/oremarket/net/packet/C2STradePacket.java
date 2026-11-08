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
 * Buy/sell stocks (C2S). The client only sends intent; balance / holdings / cost / quantity are all recomputed and validated server-side.
 * Negative qty values are markers: QTY_ALL_IN = buy-as-much-as-affordable (all the ore coins can buy),
 * QTY_ALL_AVAILABLE = sell-all-available (frozen shares are left untouched).
 * Stock identity moved from enum ordinal to the stock CODE STRING, validated via StockRegistry.getByCode.
 */
public final class C2STradePacket {
    /** Marker for buy-as-much-as-affordable. */
    public static final int QTY_ALL_IN = -1;
    /** Marker for sell-all-available: sells the entire availableShares. */
    public static final int QTY_ALL_AVAILABLE = -2;

    /** Stock code string (never an index!). */
    public final String stockCode;
    public final int qty;
    public final boolean buy;

    public C2STradePacket(String stockCode, int qty, boolean buy) {
        this.stockCode = stockCode == null ? "" : stockCode;
        this.qty = qty;
        this.buy = buy;
    }

    public static C2STradePacket decode(FriendlyByteBuf buf) {
        return new C2STradePacket(buf.readUtf(16), buf.readInt(), buf.readBoolean());
    }

    public static void encode(C2STradePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.stockCode, 16);
        buf.writeInt(msg.qty);
        buf.writeBoolean(msg.buy);
    }
}
