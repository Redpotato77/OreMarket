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

import cn.oremarket.market.MarketSettings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * S2C: a complete server configuration snapshot (every tunable in all ten categories) + the resolved stock registry.
 *
 * Serialized as CompoundTag (custom binary formats forbidden); the client wholesale-replaces its local config cache -
 * no appending, no re-parsing strings - and the UI always reads that cache.
 * Received when the market UI opens (RequestConfigPacket) and after a config hot reload.
 * Custom stock entries are sent down in stockRegistry after server-side resolution; the client never parses or validates.
 */
public final class SyncConfigPacket {
    public static final String KEY_PROTOCOL = "protocol";
    public static final String KEY_IS_RESPONSE = "isResponse";

    /** Config snapshot + stockRegistry + protocol version + response flag, all inside this one tag. */
    public final CompoundTag data;

    public SyncConfigPacket(CompoundTag data) {
        this.data = data == null ? new CompoundTag() : data;
    }

    public static SyncConfigPacket of(MarketSettings settings) {
        return of(settings, false);
    }

    /**
     * @param isResponse true = this is the reply to a RequestConfigPacket (the client drops the reply when it lands within
     * 2 s of a proactive server push, so a fresher hot-reload push is never overwritten)
     */
    public static SyncConfigPacket of(MarketSettings settings, boolean isResponse) {
        CompoundTag tag = new CompoundTag();
        settings.writeTo(tag);
        tag.putString(KEY_PROTOCOL, cn.oremarket.net.OreMarketNetwork.PROTOCOL);
        tag.putByte(KEY_IS_RESPONSE, (byte) (isResponse ? 1 : 0));
        return new SyncConfigPacket(tag);
    }

    public String protocol() {
        return data.getString(KEY_PROTOCOL);
    }

    public boolean isResponse() {
        return data.getByte(KEY_IS_RESPONSE) != 0;
    }

    public static void encode(SyncConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.data);
    }

    public static SyncConfigPacket decode(FriendlyByteBuf buf) {
        return new SyncConfigPacket(buf.readNbt());
    }
}
