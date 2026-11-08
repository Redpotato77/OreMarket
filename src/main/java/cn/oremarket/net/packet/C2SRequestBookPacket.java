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

/** C2S request for the Order Book / Leaderboard (sent when the matching tab is opened). */
public final class C2SRequestBookPacket {
    public static final int KIND_ORDER_BOOK = 0;
    public static final int KIND_LEADERBOARD = 1;

    public final int kind;

    /** Server-side protocol whitelist: unknown kinds must not mint a per-player cooldown-gate key. */
    public static boolean isValidKind(int kind) {
        return kind == KIND_ORDER_BOOK || kind == KIND_LEADERBOARD;
    }

    public C2SRequestBookPacket(int kind) {
        this.kind = kind;
    }

    public static void encode(C2SRequestBookPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.kind);
    }

    public static C2SRequestBookPacket decode(FriendlyByteBuf buf) {
        return new C2SRequestBookPacket(buf.readVarInt());
    }
}
