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
 * S2C my Limit Orders: top-level "limitOrders" = ListTag, each element produced by LimitOrder.toTag().
 */
public final class S2CLimitPacket {
    public static final String KEY_LIMIT_ORDERS = "limitOrders";

    public final CompoundTag payload;

    public S2CLimitPacket(CompoundTag payload) {
        this.payload = payload == null ? new CompoundTag() : payload;
    }

    public ListTag limitOrders() {
        return payload.getList(KEY_LIMIT_ORDERS, Tag.TAG_COMPOUND);
    }

    public static void encode(S2CLimitPacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.payload);
    }

    public static S2CLimitPacket decode(FriendlyByteBuf buf) {
        return new S2CLimitPacket(buf.readNbt());
    }
}
