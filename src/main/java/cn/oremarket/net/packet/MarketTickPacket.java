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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;
import java.util.Map;

/**
 * Market delta push (S2C): sent to every player with the UI open after each marketTick.
 *
 * Structure (NBT, dynamic length - as long as the registry, no fixed 8-slot array):
 * top-level "stocks" = ListTag, each element a CompoundTag:
 * - "code" StringTag: stock code
 * - "price" DoubleTag: current price
 * - "lastHistory" DoubleTag: the point appended to the trend history this tick
 * - "klineOpen"/"klineHigh"/"klineLow"/"klineClose" DoubleTags: the current candle's four prices (volume fields are sent alongside them)
 * - "klineComplete" ByteTag: 1 = the candle just closed this tick (the client appends it),
 * 0 = still-forming candle (the client replaces the last one)
 *
 * Client merge rules are in MarketClientState.applyMarketTick: codes absent from the cache or already delisted
 * are skipped entirely; no entry is ever created out of thin air.
 */
public final class MarketTickPacket {
    /** Top-level key of the stocks list. */
    public static final String KEY_STOCKS = "stocks";

    public final CompoundTag payload;

    public MarketTickPacket(CompoundTag payload) {
        this.payload = payload == null ? new CompoundTag() : payload;
    }

    public ListTag stocks() {
        return payload.getList(KEY_STOCKS, Tag.TAG_COMPOUND);
    }

    /**
     * Server-side packing: stocks are the currently ACTIVE stocks (delisted ones are absent and get no price updates).
     * closed holds the candles that just completed on a closing tick (code -> double[4]); pass an empty map otherwise.
     */
    public static MarketTickPacket forTick(MarketSavedData data, List<Stock> stocks,
                                           Map<String, double[]> closed,
                                           Map<String, double[]> dailyClosed) {
        ListTag list = new ListTag();
        for (Stock s : stocks) {
            StockState st = data.state(s);
            if (st == null) {
                continue;
            }
            CompoundTag e = new CompoundTag();
            e.putString("code", s.code());
            e.putDouble("price", st.price);
            e.putDouble("lastHistory", st.latest());
            double[] bar = closed.get(s.code());
            boolean complete = bar != null;
            boolean forming = st.liveKlineFilled() > 0;
            if (complete || forming) {
                double[] k = complete ? bar : st.liveKline();
                e.putDouble("klineOpen", k[0]);
                e.putDouble("klineHigh", k[1]);
                e.putDouble("klineLow", k[2]);
                e.putDouble("klineClose", k[3]);
                // A completed bar carries its final volume; a forming bar carries the read-only display amount, so a fresh bar never shows 0 before real fills.
                e.putDouble("klineVolume", complete
                        ? cn.oremarket.common.MarketVolume.of(k)
                        : st.displayVolume());
                e.putDouble("klinePlayerVolume", complete
                        ? (k.length > StockState.PLAYER_VOLUME_SLOT
                        ? cn.oremarket.common.MarketVolume.sanitize(k[StockState.PLAYER_VOLUME_SLOT]) : 0.0)
                        : st.displayPlayerVolume());
                    e.putByte("klineComplete", (byte) (complete ? 1 : 0));
            }
            double[] dailyBar = dailyClosed.get(s.code());
            boolean dailyComplete = dailyBar != null;
            boolean dailyForming = st.liveDailyKlineFilled() > 0;
            if (dailyComplete || dailyForming) {
                double[] d = dailyComplete ? dailyBar : st.liveDailyKline();
                e.putDouble("dailyOpen", d[0]);
                e.putDouble("dailyHigh", d[1]);
                e.putDouble("dailyLow", d[2]);
                e.putDouble("dailyClose", d[3]);
                e.putDouble("dailyVolume", dailyComplete
                        ? cn.oremarket.common.MarketVolume.of(d)
                        : st.displayDailyVolume());
                e.putDouble("dailyPlayerVolume", dailyComplete
                        ? cn.oremarket.common.MarketVolume.sanitize(d[StockState.PLAYER_VOLUME_SLOT])
                        : st.liveDailyPlayerVolume());
                e.putByte("dailyComplete", (byte) (dailyComplete ? 1 : 0));
            }
            // A halted stock can have no active candle. In that case omit K-line fields instead of serialising
            // the zero-filled working buffer; the client still receives its valid price and history.
            list.add(e);
        }
        CompoundTag tag = new CompoundTag();
        tag.put(KEY_STOCKS, list);
        return new MarketTickPacket(tag);
    }

    public static void encode(MarketTickPacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.payload);
    }

    public static MarketTickPacket decode(FriendlyByteBuf buf) {
        return new MarketTickPacket(buf.readNbt());
    }
}
