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
import net.minecraft.network.FriendlyByteBuf;

/**
 * C2S: the client requests a full market snapshot when the market UI opens (answered S2C by MarketSnapshotPacket).
 * Payload is serialized as a CompoundTag; the "protocol" key carries the client protocol version,
 * and the server ignores the request and replies with an error packet when the versions differ.
 */
public final class RequestMarketSnapshotPacket {
    public static final String KEY_PROTOCOL = "protocol";

    public final CompoundTag payload;

    public RequestMarketSnapshotPacket(String protocol) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_PROTOCOL, protocol == null ? "" : protocol);
        this.payload = tag;
    }

    public RequestMarketSnapshotPacket(CompoundTag payload) {
        this.payload = payload == null ? new CompoundTag() : payload;
    }

    public String protocol() {
        return payload.getString(KEY_PROTOCOL);
    }

    public static void encode(RequestMarketSnapshotPacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.payload);
    }

    public static RequestMarketSnapshotPacket decode(FriendlyByteBuf buf) {
        return new RequestMarketSnapshotPacket(buf.readNbt());
    }
}
