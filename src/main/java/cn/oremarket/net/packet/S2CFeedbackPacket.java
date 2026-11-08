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

import java.util.ArrayList;
import java.util.List;

/**
 * Operation feedback (S2C). This packet carries only a translation key and bounded arguments;
 * localized text is always rendered by the receiving client.
 */
public final class S2CFeedbackPacket {
    public static final int MAX_ARGS = 10;
    public static final int MAX_ARG = 96;

    public final String key;
    public final List<String> args;
    public final boolean success;
    public final int toastTicks;

    public S2CFeedbackPacket(String key, List<String> args, boolean success, int toastTicks) {
        this.key = key == null ? "" : key;
        if (args == null || args.isEmpty()) {
            this.args = List.of();
        } else {
            List<String> out = new ArrayList<>(Math.min(args.size(), MAX_ARGS));
            for (int i = 0; i < args.size() && i < MAX_ARGS; i++) {
                String arg = args.get(i);
                if (arg == null) {
                    arg = "";
                }
                if (arg.length() > MAX_ARG) {
                    arg = arg.substring(0, MAX_ARG);
                }
                out.add(arg);
            }
            this.args = List.copyOf(out);
        }
        this.success = success;
        this.toastTicks = Math.max(0, Math.min(2400, toastTicks));
    }

    public boolean hasKey() {
        return !key.isEmpty();
    }

    public static S2CFeedbackPacket decode(FriendlyByteBuf buf) {
        int ticks = Math.max(0, Math.min(2400, buf.readVarInt()));
        boolean success = buf.readBoolean();
        String key = buf.readUtf(96);
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_ARGS));
        List<String> args = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            args.add(buf.readUtf(MAX_ARG));
        }
        return new S2CFeedbackPacket(key, args, success, ticks);
    }

    public static void encode(S2CFeedbackPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.toastTicks);
        buf.writeBoolean(msg.success);
        buf.writeUtf(msg.key, 96);
        buf.writeVarInt(msg.args.size());
        for (String arg : msg.args) {
            buf.writeUtf(arg, MAX_ARG);
        }
    }
}
