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

/** Client UI closed; the server stops pushing deltas to it (C2S, empty payload). */
public final class C2SCloseUiPacket {
    public C2SCloseUiPacket() {
    }

    public static C2SCloseUiPacket decode(FriendlyByteBuf buf) {
        return new C2SCloseUiPacket();
    }

    public static void encode(C2SCloseUiPacket msg, FriendlyByteBuf buf) {
    }
}
