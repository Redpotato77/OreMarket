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
 * C2S order: submit (code, buy, qty, price), cancel (orderId),
 * or take someone else's resting order directly (orderId + quantity; trade price = the resting price).
 * The client only sends intent; price / quantity / balance / holdings are all recomputed and validated by the server.
 */
public final class C2SOrderPacket {
    public static final int ACTION_PLACE = 0;
    public static final int ACTION_CANCEL = 1;
    /** Taking an order: trade against the resting order identified by orderId; quantity {@code <= 0} means take its entire remaining size. */
    public static final int ACTION_TAKE = 2;

    public final int action;
    public final String stockCode;
    public final boolean buy;
    public final int quantity;
    public final double price;
    public final long orderId;

    public C2SOrderPacket(int action, String stockCode, boolean buy, int quantity, double price, long orderId) {
        this.action = action;
        this.stockCode = stockCode == null ? "" : stockCode;
        this.buy = buy;
        this.quantity = quantity;
        this.price = price;
        this.orderId = orderId;
    }

    public static void encode(C2SOrderPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.action);
        buf.writeUtf(msg.stockCode, 16);
        buf.writeBoolean(msg.buy);
        buf.writeVarInt(msg.quantity);
        buf.writeDouble(msg.price);
        buf.writeLong(msg.orderId);
    }

    public static C2SOrderPacket decode(FriendlyByteBuf buf) {
        return new C2SOrderPacket(buf.readVarInt(), buf.readUtf(16), buf.readBoolean(),
                buf.readVarInt(), buf.readDouble(), buf.readLong());
    }
}
