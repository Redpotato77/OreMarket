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

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/** Plain payload encode/decode helpers: avoid repeating boilerplate in every packet class. */
public final class PacketUtils {

    private PacketUtils() {
    }

    public static FriendlyByteBuf empty() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    public static void writeDoubles(FriendlyByteBuf buf, double[] arr) {
        for (double v : arr) {
            buf.writeDouble(v);
        }
    }

    public static double[] readDoubles(FriendlyByteBuf buf, int len) {
        double[] arr = new double[len];
        for (int i = 0; i < len; i++) {
            arr[i] = buf.readDouble();
        }
        return arr;
    }

    public static void writeInts(FriendlyByteBuf buf, int[] arr) {
        for (int v : arr) {
            buf.writeVarInt(v);
        }
    }

    public static int[] readInts(FriendlyByteBuf buf, int len) {
        int[] arr = new int[len];
        for (int i = 0; i < len; i++) {
            arr[i] = buf.readVarInt();
        }
        return arr;
    }
}
