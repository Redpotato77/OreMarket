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

import cn.oremarket.market.ActiveEvent;
import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.MarketEngine;
import cn.oremarket.market.Stock;
import cn.oremarket.market.StockRegistry;
import cn.oremarket.common.StockState;
import cn.oremarket.market.event.EventEngine;
import cn.oremarket.market.event.MarketEventView;
import cn.oremarket.market.event.MarketModifiers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Player data snapshot (S2C): the receiver's own balance, sellable/frozen holdings (T+1), average cost, realized P/L,
 * ore-coin peak, top-bar total P/L and total assets, loan status, currently active events, plus every stock's
 * current price / trend / halt / price-limit state.
 *
 * - everything serializes via CompoundTag / ListTag (fixed 8-slot arrays and custom binaries are banned);
 * the per-stock section is dynamic length: each element carries "code" and the client writes by code into its cache;
 * - quote history (history / klineHistory) is NOT here - that is MarketSnapshotPacket's and MarketTickPacket's job;
 * this packet only guarantees price and personal data stay consistent on the immediate post-trade push back.
 * - event targets became code lists (MarketEventView.targetCodes); the old bit mask is gone.
 *
 * Client rules: the current price always updates into the quote cache; codes absent from the cache are skipped (await the quote snapshot).
 */
public final class S2CSnapshotPacket {
    /** Max event cards packed per packet (a single source with {@code PushFrame.MAX_EVENT_VIEWS}; never drifts). */
    public static final int EV_MAX =
            cn.oremarket.market.PushFrame.MAX_EVENT_VIEWS;
    public static final String KEY_STOCKS = "stocks";
    public static final String KEY_EVENTS = "events";

    /** Raw NBT payload (the client reads key by key; missing fields use defaults). */
    public final CompoundTag data;

    public S2CSnapshotPacket(CompoundTag data) {
        this.data = data == null ? new CompoundTag() : data;
    }

    /** The server compresses the active event table into display views (at most EV_MAX cards). */
    public static List<MarketEventView> viewsOf(List<ActiveEvent> events) {
        int n = Math.min(events.size(), EV_MAX);
        List<MarketEventView> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(MarketEventView.of(events.get(i)));
        }
        return out;
    }

    /**
     * Server-side packing (call on the server main thread only): when packing alone, capture this tick's derived-data frame.
     * Scattered entry points like the login backfill and the post-trade push-back use this; for the per-marketTick batch
     * push use the frame-passing entry {@link #forPlayer(MarketSavedData, UUID, cn.oremarket.market.PushFrame)}
     * so one frame is reused for every player.
     */
    public static S2CSnapshotPacket forPlayer(MarketSavedData data, UUID player) {
        return forPlayer(data, player,
                cn.oremarket.market.PushFrame.capture(data));
    }

    /**
     * Server-side packing (call on the server main thread only): walks the registry's active stocks; dynamic length.
     * Halt / price-limit / fee modifiers come from {@code frame} (by code); locked shares come from once-swept tables -
     */
    public static S2CSnapshotPacket forPlayer(MarketSavedData data, UUID player,
                                              cn.oremarket.market.PushFrame frame) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("tick", data.tickCounter);
        tag.putLong("balance", data.balance(player));
        tag.putLong("peak", data.peak(player));

        MarketModifiers mm = frame != null ? frame.modifiers() : MarketModifiers.collect(data.events,
                MarketSettingsBridge.feeRate(), MarketSettingsBridge.meanReversion(),
                MarketEngine.defaultPriceLimit());
        // The receiver's own order facts (locked shares / locked coins / my orders / my limit orders) come from ONE sweep,
        // replacing the old per-stock rescan of two order tables plus per-player full-table filtering.
        MarketSavedData.PlayerOrders orders = data.playerOrders(player);
        ListTag stocks = new ListTag();
        double realizedSum = 0;
        double floatSum = 0;
        for (Stock s : StockRegistry.server().getActiveStocks()) {
            StockState st = data.state(s);
            if (st == null) {
                continue;
            }
            CompoundTag e = new CompoundTag();
            e.putString("code", s.code());
            e.putDouble("price", st.price);
            e.putDouble("base", s.basePrice());
            e.putInt("trend", st.trend);
            e.putInt("suspend", frame != null ? frame.suspendTicks(s.code())
                    : EventEngine.suspendTicksLeft(data, s.code()));
            e.putDouble("limit", mm.get(s.code()).limitPct);
            int avail = data.available(player, s);
            int frz = data.frozenShares(player, s);
            // Cost basis only from SavedData's averageCost; absent shows 0 - never patch the cost with the current price.
            double avg = avail + frz > 0 ? data.costOf(player, s) : 0;
            double realized = data.realizedOf(player, s);
            e.putInt("avail", avail);
            e.putInt("frozen", frz);
            // Sellable shares locked by orders/limit orders (logical lock, derived server-side); sellable = avail - locked
            e.putInt("locked", orders.lockedSharesOf(s.code()));
            e.putDouble("avg", avg);
            e.putDouble("realized", realized);
            // Display volume of the currently forming candle = real fills + read-only virtual forecast; it never takes part in money, position, price or event settlement.
            e.putDouble("volume", st.displayVolume());
            e.putDouble("playerVolume", st.displayPlayerVolume());
            // A per-stock whole-server short-pool share snapshot. A missing pool (theoretically only in extreme timing) = -1;
            // the client treats it as 'uninitialized': clicks pass through as usual, everything defers to the server's critical-section recheck.
            e.putInt("poolMax", data.shortPoolMax(s.code()));
            e.putInt("poolLeft", data.shortPoolMax(s.code()) < 0 ? -1
                    : data.shortPoolLeftNow(s.code()));
            stocks.add(e);
            realizedSum += realized;
            if (avail + frz > 0) {
                floatSum += (avail + frz) * (st.price - avg);
            }
        }
        tag.put(KEY_STOCKS, stocks);

        LoanInfo info = LoanInfo.of(data, player);
        // Top-bar total P/L: ordinary realized + ordinary floating + short settled and still-open floating P/L.
        // Total assets uniformly via calculateTotalAssets; the two figures share the server as their only calculation authority.
        double shortPnl = data.shortTotalPnl(player);
        tag.putDouble("totalPnl", realizedSum + floatSum + shortPnl);
        tag.putDouble("shortPnlTotal", shortPnl);
        tag.putLong("totalAssets", data.calculateTotalAssets(player));
        tag.put("loan", info.toCompound());

        // ---------- New: short positions, my limit orders, locked resources (derived from orders + limit orders)
        ListTag shortsList = new ListTag();
        for (cn.oremarket.market.ShortPosition sp
                : data.shortsOf(player).values()) {
            CompoundTag shortTag = sp.toTag();
            shortTag.putDouble("InitialMargin", sp.lockedMargin);
            StockState shortState = data.state(sp.code);
            double shortPrice = shortState != null && shortState.price > 0.0
                    && Double.isFinite(shortState.price) ? shortState.price : sp.openPrice;
            shortTag.putDouble("CurrentRemainingMargin", sp.currentRemainingMargin(shortPrice));
            shortTag.putDouble("LiquidationThreshold", sp.liquidationThreshold(shortPrice));
            shortTag.putDouble("RealizedShortPnl", data.realizedShortPnlOf(player, sp.code));
            shortsList.add(shortTag);
        }
        tag.put("shorts", shortsList);
        tag.putLong("marginTotal", data.shortMarginTotal(player));
        tag.putDouble("shortFloat", data.shortFloatingPnl(player));
        tag.putDouble("shortRealizedPnl", data.realizedShortPnlTotal(player));

        ListTag limitList = new ListTag();
        for (cn.oremarket.market.LimitOrder lo : orders.limitOrders) {
            limitList.add(lo.toTag());
        }
        tag.put("limitOrders", limitList);

        ListTag orderList = new ListTag();
        for (cn.oremarket.market.PendingOrder po : orders.orders) {
            orderList.add(po.toTag());
        }
        tag.put("myOrders", orderList);
        tag.putLong("lockedCoins", orders.lockedCoins);

        // Event display cards: with a frame, use the one already assembled in it (player-independent); otherwise assemble once inline.
        ListTag events = new ListTag();
        List<MarketEventView> views = frame != null ? frame.eventViews() : viewsOf(data.events);
        for (MarketEventView v : views) {
            events.add(v.toCompound());
        }
        tag.put(KEY_EVENTS, events);
        // Legendary cooldown hint: remaining game days; 0 = hide
        int legendCd = 0;
        cn.oremarket.market.MarketSettings msCfg =
                cn.oremarket.market.MarketSettings.server();
        if (msCfg != null && msCfg.eventLegendCooldownHintEnabled
                && data.lastLegendaryDay > Long.MIN_VALUE / 8) {
            legendCd = (int) Math.max(0, msCfg.legendCooldownDays - (data.tradeDay() - data.lastLegendaryDay));
        }
        tag.putInt("legendCdDays", legendCd);
        return new S2CSnapshotPacket(tag);
    }

    /** Event-list decode (for the client main thread; targetCodes already live in the view object, matched by code). */
    public List<MarketEventView> events() {
        List<MarketEventView> out = new ArrayList<>();
        Tag raw = data.get(KEY_EVENTS);
        if (raw instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof CompoundTag c) {
                    out.add(MarketEventView.fromCompound(c));
                }
            }
        }
        return out;
    }

    public ListTag stocks() {
        return data.getList(KEY_STOCKS, Tag.TAG_COMPOUND);
    }

    public static void encode(S2CSnapshotPacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.data);
    }

    public static S2CSnapshotPacket decode(FriendlyByteBuf buf) {
        return new S2CSnapshotPacket(buf.readNbt());
    }

    /** Small helper: pull the base parameters of the current server/client snapshot (keeps thread assumptions out of the packet class). */
    private static final class MarketSettingsBridge {
        static double feeRate() {
            return cn.oremarket.market.MarketSettings.current().feeRate();
        }

        static double meanReversion() {
            return cn.oremarket.market.MarketSettings.current().meanReversionFactor;
        }
    }
}
