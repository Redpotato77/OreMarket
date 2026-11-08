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

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * S2C leaderboard: three boards. Top-level keys assets / daily / positions, each a ListTag:
 * {uuid, name, value, online}. On the daily-gain board, value=Long.MIN_VALUE marks an offline "--" entry.
 */
public final class S2CLeaderboardPacket {
    public static final String KEY_ASSETS = "assets";
    public static final String KEY_DAILY = "daily";
    public static final String KEY_POSITIONS = "positions";

    public final CompoundTag payload;

    public S2CLeaderboardPacket(CompoundTag payload) {
        this.payload = payload == null ? new CompoundTag() : payload;
    }

    public ListTag board(String key) {
        return payload.getList(key, Tag.TAG_COMPOUND);
    }

    public static void encode(S2CLeaderboardPacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.payload);
    }

    public static S2CLeaderboardPacket decode(FriendlyByteBuf buf) {
        return new S2CLeaderboardPacket(buf.readNbt());
    }
}
