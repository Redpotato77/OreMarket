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
 * Exchange request (C2S). The action is validated server-side; amount<=0 means all.
 * The stock identifier moved from an enum ordinal to a **stock
 * code string**; the server uses StockRegistry.getByCode to check that the code is
 * valid and not delisted, and rejects invalid requests outright with an ErrorPacket.
 */
public final class C2SExchangePacket {
    public static final int DEPOSIT_ONE = 0;
    public static final int DEPOSIT_TEN = 1;
    public static final int DEPOSIT_ALL_ROW = 2;
    public static final int WITHDRAW_ONE = 3;
    public static final int WITHDRAW_TEN = 4;
    public static final int WITHDRAW_AMOUNT = 5;
    /** Deposit a custom amount (right column of the exchange). Amount is the desired count, clamped server-side to what the inventory really holds. */
    public static final int DEPOSIT_AMOUNT = 6;
    /**
     * Withdraw everything in one click. The amount is
     * recomputed authoritatively server-side as the smaller of "how many the player
     * can afford with Ore Coins" and "how many Inventory.add actually fits (simulated
     * insertion)", so items are never lost to overflow.
     */
    public static final int WITHDRAW_ALL = 7;

    /** Stock code string (not an index!). */
    public final String stockCode;
    public final int action;
    public final int amount;

    public C2SExchangePacket(String stockCode, int action, int amount) {
        this.stockCode = stockCode == null ? "" : stockCode;
        this.action = action;
        this.amount = amount;
    }

    public static C2SExchangePacket decode(FriendlyByteBuf buf) {
        return new C2SExchangePacket(buf.readUtf(16), buf.readVarInt(), buf.readVarInt());
    }

    public static void encode(C2SExchangePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.stockCode, 16);
        buf.writeVarInt(msg.action);
        buf.writeVarInt(msg.amount);
    }
}
