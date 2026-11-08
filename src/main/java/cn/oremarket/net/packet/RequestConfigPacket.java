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
import net.minecraft.nbt.CompoundTag;

/**
 * C2S: the client requests the full configuration and stock registry when the market UI opens (answered S2C by SyncConfigPacket).
 * Payload is serialized as a CompoundTag (custom binary formats forbidden); currently an empty tag kept as an extension slot.
 */
public final class RequestConfigPacket {
    public static final String KEY_PROTOCOL = "protocol";

    public final CompoundTag payload;

    /** Sent by the client: carries the local protocol version; the server ignores the request and replies with an error when it mismatches. */
    public RequestConfigPacket(String protocol) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_PROTOCOL, protocol == null ? "" : protocol);
        this.payload = tag;
    }

    public RequestConfigPacket(CompoundTag payload) {
        this.payload = payload == null ? new CompoundTag() : payload;
    }

    public String protocol() {
        return payload.getString(KEY_PROTOCOL);
    }

    public static void encode(RequestConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.payload);
    }

    public static RequestConfigPacket decode(FriendlyByteBuf buf) {
        return new RequestConfigPacket(buf.readNbt());
    }
}
