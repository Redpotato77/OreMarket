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

package cn.oremarket.market;

import cn.oremarket.market.event.EventEngine;
import cn.oremarket.market.event.MarketEventView;
import cn.oremarket.market.event.MarketModifiers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's snapshot frame of derived data for the current marketTick: the part of the
 * computation that depends only on the <b>events + config + registry</b> and not on any player is
 * done once per marketTick and shared by every player's snapshot packet.
 *
 * Why it is needed:
 *  - {@code S2CSnapshotPacket.forPlayer} used to re-run {@link MarketModifiers#collect} for
 *    <b>every receiving player</b>, each run allocating a fresh parameter object and a hash table
 *    per active stock;
 *  - every stock also called {@link EventEngine#suspendTicksLeft} on its own, and that method does
 *    a full scan of "all events x all effect modules" — players x stocks x events x modules, the
 *    single biggest burn of wasted CPU in a marketTick;
 *  - short Forced Liquidation, Limit Order checks and listing Order Matching each re-scanned the
 *    event table separately.
 * All of these now read from the frame produced by {@link #capture}.
 *
 * Semantics unchanged: the same events, the same config and the same functions are computed, just
 * no longer repeatedly. The frame is only passed around within one marketTick and never cached
 * across ticks, so there is no window for reading a stale value; codes absent from the frame (e.g.
 * delisted) fall back to computing on demand with the original functions, with results identical
 * to before.
 */
public final class PushFrame {

    /** Max event cards packed into a snapshot (EV_MAX in S2CSnapshotPacket references this, so the two are always in sync). */
    public static final int MAX_EVENT_VIEWS = 12;

    private final MarketSavedData data;
    private final MarketModifiers modifiers;
    /** Active stock code -> marketTicks left of the trading halt (0 = not halted). */
    private final Map<String, Integer> suspendTicks;
    /** Display cards of the active events (previously the strings were rebuilt for every receiving player). */
    private final List<MarketEventView> eventViews;

    private PushFrame(MarketSavedData data, MarketModifiers modifiers, Map<String, Integer> suspendTicks,
                      List<MarketEventView> eventViews) {
        this.data = data;
        this.modifiers = modifiers;
        this.suspendTicks = suspendTicks;
        this.eventViews = eventViews;
    }

    /**
     * Captures a frame of the current state (called on the server main thread): the event modifier
     * table is aggregated by code once, the remaining halt ticks are computed once per
     * <b>active</b> stock, and the event display cards are built only once.
     */
    public static PushFrame capture(MarketSavedData data) {
        return capture(data, true);
    }

    /**
     * Captures a check-only frame; event-card building can be turned off since Short Selling,
     * Limit Orders and Order Matching only need the modifier table and the halt table.
     */
    public static PushFrame capture(MarketSavedData data, boolean includeEventViews) {
        MarketSettings ms = MarketSettings.current();
        MarketModifiers mm = MarketModifiers.collect(data.events, ms.feeRate(),
                ms.meanReversionFactor, ms.priceLimitFraction());
        Map<String, Integer> suspend = new HashMap<>();
        for (Stock s : StockRegistry.getActiveStocks()) {
            suspend.put(s.code(), EventEngine.suspendTicksLeft(data, s.code()));
        }
        if (!includeEventViews || data.events.isEmpty()) {
            return new PushFrame(data, mm, suspend, List.of());
        }
        int n = Math.min(data.events.size(), MAX_EVENT_VIEWS);
        List<MarketEventView> views = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            views.add(MarketEventView.of(data.events.get(i)));
        }
        return new PushFrame(data, mm, suspend, views);
    }

    /** Display cards of the active events (already truncated to {@link #MAX_EVENT_VIEWS}). */
    public List<MarketEventView> eventViews() {
        return eventViews;
    }

    /** Event modifier table (same definition as {@link MarketModifiers#collect}, just precomputed here). */
    public MarketModifiers modifiers() {
        return modifiers;
    }

    /** Remaining halt marketTicks for the stock; codes not registered in the frame are computed on demand by the original function. */
    public int suspendTicks(String code) {
        Integer v = suspendTicks.get(code);
        return v != null ? v : EventEngine.suspendTicksLeft(data, code);
    }

    /** Whether the stock is currently halted. */
    public boolean suspended(String code) {
        return suspendTicks(code) > 0;
    }

    /** Whether the stock is currently halted (Stock-typed entry point). */
    public boolean suspended(Stock s) {
        return s != null && suspended(s.code());
    }
}
