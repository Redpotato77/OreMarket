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
import net.minecraft.nbt.LongTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * S2C: player level state (SyncPlayerStatePacket).
 *
 * Serialized as a CompoundTag; both keys are LongTags:
 * - "highestLevel": all-time highest level (the sole source for display and unlock rights, monotonically increasing);
 * - "peakOreCoins": all-time peak of the ore-coin balance (excluded from loans and starting coins).
 * The client treats a missing field as 0 (the level then falls back to 1).
 * Push timing: after the recalculation at login, after a peak update raises the level, and after a config hot reload (always after SyncConfigPacket).
 */
public final class SyncPlayerStatePacket {
    public static final String KEY_HIGHEST_LEVEL = "highestLevel";
    public static final String KEY_PEAK_ORE_COINS = "peakOreCoins";

    /** Raw tag (the client reads values by key; missing fields default to 0). */
    public final CompoundTag data;

    public SyncPlayerStatePacket(long highestLevel, long peakOreCoins) {
        CompoundTag tag = new CompoundTag();
        tag.put(KEY_HIGHEST_LEVEL, LongTag.valueOf(highestLevel));
        tag.put(KEY_PEAK_ORE_COINS, LongTag.valueOf(peakOreCoins));
        this.data = tag;
    }

    public SyncPlayerStatePacket(CompoundTag data) {
        this.data = data == null ? new CompoundTag() : data;
    }

    public long highestLevel() {
        return data.getLong(KEY_HIGHEST_LEVEL);
    }

    public long peakOreCoins() {
        return data.getLong(KEY_PEAK_ORE_COINS);
    }

    public static void encode(SyncPlayerStatePacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.data);
    }

    public static SyncPlayerStatePacket decode(FriendlyByteBuf buf) {
        return new SyncPlayerStatePacket(buf.readNbt());
    }
}
