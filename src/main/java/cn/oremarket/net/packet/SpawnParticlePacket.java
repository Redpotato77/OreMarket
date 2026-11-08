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
 * S2C particle packet (protocol 10): triggered server-side, played client-side.
 *
 * Payload: particle type string + xyz coordinates + count + spread radius (optionally a sound flag).
 * The server never sends this to clients below protocol version 10 (the Forge handshake already gates on PROTOCOL;
 * the sender checks once more as a double lock). Client-side playback failures (null level etc.) are skipped silently.
 */
public final class SpawnParticlePacket {
    /** Per-packet particle count cap (defends against abnormal data). */
    public static final int MAX_COUNT = 100;
    /** Max length of one particle-type id. */
    public static final int MAX_TYPE_LEN = 64;

    public final String type;
    public final double x;
    public final double y;
    public final double z;
    public final int count;
    public final double spread;
    /** 1 = also play the vanilla experience-orb sound (gated by particleEffectPlaySoundEnabled). */
    public final int playSound;

    public SpawnParticlePacket(String type, double x, double y, double z,
                               int count, double spread, boolean playSound) {
        this.type = type == null ? "minecraft:smoke" : type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.count = Math.max(1, Math.min(MAX_COUNT, count));
        this.spread = Math.max(0.0, Math.min(5.0, spread));
        this.playSound = playSound ? 1 : 0;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(type, MAX_TYPE_LEN);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeVarInt(count);
        buf.writeDouble(spread);
        buf.writeVarInt(playSound);
    }

    /** Read (network packet hardening: any out-of-range / illegal field is clamped to a safe value in the constructor). */
    public static SpawnParticlePacket read(FriendlyByteBuf buf) {
        String type = buf.readUtf(MAX_TYPE_LEN);
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        int count = buf.readVarInt();
        double spread = buf.readDouble();
        int sound = buf.readVarInt();
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return new SpawnParticlePacket("minecraft:smoke", 0, 0, 0, 1, 0, false);
        }
        return new SpawnParticlePacket(type, x, y, z, count, spread, sound != 0);
    }
}
