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
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** Server rejection notice carrying only an i18n key and bounded string arguments. */
public final class ErrorPacket {
    public static final String KEY_I18N = "key";
    public static final String KEY_ARGS = "args";
    public static final int MAX_KEY = 96;
    public static final int MAX_ARGS = 10;
    public static final int MAX_ARG = 96;

    public final CompoundTag data;

    public ErrorPacket(String key, List<String> args) {
        CompoundTag tag = new CompoundTag();
        String safeKey = key == null ? "oremarket.error.generic" : key;
        if (safeKey.length() > MAX_KEY) {
            safeKey = safeKey.substring(0, MAX_KEY);
        }
        tag.putString(KEY_I18N, safeKey);
        ListTag list = new ListTag();
        if (args != null) {
            for (int i = 0; i < args.size() && i < MAX_ARGS; i++) {
                String arg = args.get(i);
                if (arg == null) {
                    arg = "";
                }
                if (arg.length() > MAX_ARG) {
                    arg = arg.substring(0, MAX_ARG);
                }
                list.add(StringTag.valueOf(arg));
            }
        }
        tag.put(KEY_ARGS, list);
        this.data = tag;
    }

    public ErrorPacket(CompoundTag data) {
        this.data = data == null ? new CompoundTag() : data;
    }

    public String key() {
        String key = data.getString(KEY_I18N);
        return key.isEmpty() ? "oremarket.error.generic" : key;
    }

    public List<String> args() {
        List<String> out = new ArrayList<>();
        if (data.get(KEY_ARGS) instanceof ListTag list) {
            for (int i = 0; i < list.size() && i < MAX_ARGS; i++) {
                Tag tag = list.get(i);
                String arg = tag.getAsString();
                out.add(arg.length() > MAX_ARG ? arg.substring(0, MAX_ARG) : arg);
            }
        }
        return out;
    }

    public static void encode(ErrorPacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.data);
    }

    public static ErrorPacket decode(FriendlyByteBuf buf) {
        return new ErrorPacket(buf.readNbt());
    }
}
