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

import cn.oremarket.market.log.MarketLogEntry;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Push of one log entry: whenever a
 * buy / sell / exchange / loan / repayment / bankruptcy / Event trigger / Event end /
 * level unlock / expiry reminder / forced sell-off and reclaim happens, the server
 * pushes this entry to the player concerned; the client inserts it at the head of an
 * in-memory queue capped at 100 entries.
 * Pushed only to the player involved (Event logs go out one entry per player and
 * mention nothing but the Event and market data).
 */
public final class S2CLogPacket {
    public final MarketLogEntry entry;

    public S2CLogPacket(MarketLogEntry entry) {
        this.entry = entry;
    }

    public static S2CLogPacket decode(FriendlyByteBuf buf) {
        return new S2CLogPacket(MarketLogEntry.read(buf));
    }

    public static void encode(S2CLogPacket msg, FriendlyByteBuf buf) {
        msg.entry.write(buf);
    }
}
