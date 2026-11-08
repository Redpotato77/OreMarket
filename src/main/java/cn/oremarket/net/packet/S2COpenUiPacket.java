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

/** Tell the client to open the stock-market UI (S2C, empty payload, triggered by the /oremarket command). */
public final class S2COpenUiPacket {
    public S2COpenUiPacket() {
    }

    public static S2COpenUiPacket decode(FriendlyByteBuf buf) {
        return new S2COpenUiPacket();
    }

    public static void encode(S2COpenUiPacket msg, FriendlyByteBuf buf) {
    }
}
