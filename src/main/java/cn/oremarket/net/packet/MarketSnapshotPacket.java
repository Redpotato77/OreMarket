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

import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.Stock;
import cn.oremarket.common.StockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

/**
 * Full market snapshot (S2C): sent on UI open / reconnect / explicit request; the client uses it to
 * ATOMICALLY replace the entire market cache.
 *
 * Structure (NBT, dynamic length; while the UI is closed the client ignores it and keeps its cache):
 * top-level "stocks" = ListTag, each element:
 * - "code" StringTag: stock code
 * - "price" DoubleTag: current price
 * - "history" ListTag of DoubleTag: trend history (up to 60 points, oldest first; fewer if available)
 * - "klineHistory" ListTag of CompoundTag: complete candles (each {open,high,low,close,volume,playerVolume} DoubleTags,
 * up to 80, oldest first); the still-forming candle, if any, is included as the LAST element
 * (with no forming candle the list is pure history).
 *
 * Stocks come from the server registry's active list (built-ins first, customs after); delisted stocks are absent,
 * and the cache-cleanup logic keeps their client entries as "insufficient data" instead of showing them.
 */
public final class MarketSnapshotPacket {
    public static final String KEY_STOCKS = "stocks";
    public static final String KEY_RESPONSE = "resp";

    public final CompoundTag payload;

    public MarketSnapshotPacket(CompoundTag payload) {
        this.payload = payload == null ? new CompoundTag() : payload;
    }

    public ListTag stocks() {
        return payload.getList(KEY_STOCKS, Tag.TAG_COMPOUND);
    }

    /**
     * true = this is the reply to a RequestMarketSnapshotPacket; false = a proactive server push
     * (the hot-reload bundle). Client race rule: a reply landing within 2 s of the last proactive push is dropped;
     * proactive pushes refresh the lastSnapshotPushTime stamp.
     */
    public boolean isResponse() {
        return payload.getByte(KEY_RESPONSE) != 0;
    }

    /** Copy plus response flag (called before the server sends a reply; proactive pushes keep the default 0). */
    public MarketSnapshotPacket asResponse() {
        CompoundTag copy = (CompoundTag) payload.copy();
        copy.putByte(KEY_RESPONSE, (byte) 1);
        return new MarketSnapshotPacket(copy);
    }

    /** Server packs the full market state (read-only; no data is modified). */
    public static MarketSnapshotPacket forStocks(MarketSavedData data, List<Stock> stocks) {
        ListTag list = new ListTag();
        for (Stock s : stocks) {
            StockState st = data.state(s);
            if (st == null) {
                continue;
            }
            CompoundTag e = new CompoundTag();
            e.putString("code", s.code());
            e.putDouble("price", st.price);
            ListTag hist = new ListTag();
            for (double v : st.recentHistory()) {
                hist.add(DoubleTag.valueOf(v));
            }
            e.put("history", hist);
            ListTag kl = new ListTag();
            for (double[] bar : st.recentKlines()) {
                kl.add(barCompound(bar));
            }
            if (st.liveKlineFilled() > 0) {
                // The forming short-term candle carries display-only projected volume.
                double[] live = st.liveKline().clone();
                live[cn.oremarket.common.MarketVolume.VOLUME_SLOT] = st.displayVolume();
                live[StockState.PLAYER_VOLUME_SLOT] = st.displayPlayerVolume();
                kl.add(barCompound(live));
            }
            e.put("klineHistory", kl);
            e.putByte("klineLive", (byte) (st.liveKlineFilled() > 0 ? 1 : 0));
            ListTag daily = new ListTag();
            for (double[] bar : st.recentDailyKlines()) {
                daily.add(barCompound(bar));
            }
            if (st.liveDailyKlineFilled() > 0) {
                double[] live = st.liveDailyKline().clone();
                live[cn.oremarket.common.MarketVolume.VOLUME_SLOT] = st.displayDailyVolume();
                live[StockState.PLAYER_VOLUME_SLOT] = st.liveDailyPlayerVolume();
                daily.add(barCompound(live));
            }
            e.put("dailyKlineHistory", daily);
            e.putByte("dailyKlineLive", (byte) (st.liveDailyKlineFilled() > 0 ? 1 : 0));
            list.add(e);
        }
        CompoundTag tag = new CompoundTag();
        tag.put(KEY_STOCKS, list);
        return new MarketSnapshotPacket(tag);
    }

    private static CompoundTag barCompound(double[] bar) {
        CompoundTag c = new CompoundTag();
        c.putDouble("open", bar[0]);
        c.putDouble("high", bar[1]);
        c.putDouble("low", bar[2]);
        c.putDouble("close", bar[3]);
        c.putDouble("volume", bar.length > 4
                ? cn.oremarket.common.MarketVolume.of(bar) : 0.0);
        c.putDouble("playerVolume", bar.length > StockState.PLAYER_VOLUME_SLOT
                ? cn.oremarket.common.MarketVolume.sanitize(bar[StockState.PLAYER_VOLUME_SLOT]) : 0.0);
        return c;
    }

    public static void encode(MarketSnapshotPacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.payload);
    }

    public static MarketSnapshotPacket decode(FriendlyByteBuf buf) {
        return new MarketSnapshotPacket(buf.readNbt());
    }
}
