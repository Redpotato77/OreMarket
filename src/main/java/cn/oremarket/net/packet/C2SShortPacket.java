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
 * C2S Short Selling: open or close a position (qty == 0 means close the entire position; negative close quantities are rejected).
 * Closing now carries a unique position ID — the server uses it to verify
 * that the position really exists with a matching quantity, and rejects the request with an error
 * if it has been forcibly liquidated or changed (the client then refreshes its UI). Open requests
 * and a zero ID is rejected by the server; zero is retained only for old saved data
 * before a position ID has been assigned.
 */
public final class C2SShortPacket {
    public static final int ACTION_OPEN = 0;
    public static final int ACTION_CLOSE = 1;

    public final int action;
    public final String stockCode;
    public final int quantity;
    /** Unique ID of the Short Position (used when closing; 0 = unspecified, server only checks code + quantity). */
    public final long positionId;

    public C2SShortPacket(int action, String stockCode, int quantity) {
        this(action, stockCode, quantity, 0L);
    }

    public C2SShortPacket(int action, String stockCode, int quantity, long positionId) {
        this.action = action;
        this.stockCode = stockCode == null ? "" : stockCode;
        this.quantity = quantity;
        this.positionId = positionId;
    }

    public static void encode(C2SShortPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.action);
        buf.writeUtf(msg.stockCode, 16);
        buf.writeVarInt(msg.quantity);
        buf.writeLong(msg.positionId);
    }

    public static C2SShortPacket decode(FriendlyByteBuf buf) {
        return new C2SShortPacket(buf.readVarInt(), buf.readUtf(16), buf.readVarInt(), buf.readLong());
    }
}
