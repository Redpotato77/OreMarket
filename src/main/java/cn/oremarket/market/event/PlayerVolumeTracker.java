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

package cn.oremarket.market.event;

import cn.oremarket.market.ActiveEvent;
import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.MarketSettings;
import cn.oremarket.market.StockRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Player-behaviour-triggered Event: once the server-wide buy volume of
 * one stock exceeds the threshold for {@link #WINDOW_TICKS} consecutive marketTicks,
 * "Market Frenzy" fires; the sell volume crossing it fires "Panic Selling".
 *
 * Anti-abuse: recorded volume **excludes wash trades - buyer and seller
 * sharing the same UUID**; order matching already forbids self-trading, and this is a
 * second, independent line of defence (it covers Limit Orders and any future entry point).
 *
 * Structure: one sliding window (ArrayDeque) of at most 5 cells per stock, advanced along
 * with the marketTick; each cell stores the total buy/sell volume plus the per-player
 * contribution (the "Triggered by:" line under the event card takes the top 3 by
 * contribution). Memory is bounded at stocks x 5 cells; when a player logs out, his
 * contribution records disappear as the window slides.
 */
public final class PlayerVolumeTracker {

    /** Trigger window length (marketTicks). */
    public static final int WINDOW_TICKS = 5;
    /** Cooldown for same-direction triggers on one stock (marketTicks); stops repeated event farming. */
    private static final int TRIGGER_COOLDOWN_TICKS = 200;

    /** A stock's traded volume within one marketTick. */
    private static final class TickStat {
        long buyTotal;
        long sellTotal;
        final Map<UUID, Long> buyBy = new HashMap<>();
        final Map<UUID, Long> sellBy = new HashMap<>();
    }

    private static final class Window {
        final Deque<TickStat> ticks = new ArrayDeque<>();
        long lastFrenzyTick = Long.MIN_VALUE;
        long lastPanicTick = Long.MIN_VALUE;
        long lastAdvancedTick = Long.MIN_VALUE;

        void advanceTo(long marketTick) {
            if (lastAdvancedTick == marketTick) {
                return;
            }
            if (lastAdvancedTick < 0) {
                lastAdvancedTick = marketTick;
            }
            long gap = Math.min(WINDOW_TICKS, marketTick - lastAdvancedTick);
            for (long i = 0; i < gap; i++) {
                ticks.addLast(new TickStat());
                while (ticks.size() > WINDOW_TICKS) {
                    ticks.removeFirst();
                }
            }
            lastAdvancedTick = marketTick;
        }
    }

    private static final Map<String, Window> WINDOWS = new LinkedHashMap<>();
    /** "Volume already recorded this tick" latch, reset every tick: records arriving before the advance land in the current (last) cell. */
    private static long currentTick = Long.MIN_VALUE;

    private PlayerVolumeTracker() {
    }

    /** Called at the top of the market loop: advance every sliding window to this marketTick. */
    public static synchronized void beginMarketTick(long marketTick) {
        if (currentTick == marketTick) {
            return;
        }
        currentTick = marketTick;
        for (Window w : WINDOWS.values()) {
            w.advanceTo(marketTick);
        }
    }

    /**
     * Records the buy side of a fill.
     *
     * @param selfTrade buyer and seller share one UUID (a wash trade): dropped outright,
     *                  counted nowhere at all (the core of the anti-abuse guard)
     */
    public static synchronized void recordBuy(String code, UUID buyer, int qty, boolean selfTrade) {
        if (selfTrade || buyer == null || code == null || qty <= 0) {
            return;
        }
        Window w = window(code);
        TickStat stat = currentStat(w);
        stat.buyTotal += qty;
        stat.buyBy.merge(buyer, (long) qty, Long::sum);
    }

    /** Records the sell side of a fill (selfTrade behaves exactly as in {@link #recordBuy}). */
    public static synchronized void recordSell(String code, UUID seller, int qty, boolean selfTrade) {
        if (selfTrade || seller == null || code == null || qty <= 0) {
            return;
        }
        Window w = window(code);
        TickStat stat = currentStat(w);
        stat.sellTotal += qty;
        stat.sellBy.merge(seller, (long) qty, Long::sum);
    }

    private static Window window(String code) {
        return WINDOWS.computeIfAbsent(code, k -> {
            Window w = new Window();
            w.advanceTo(currentTick == Long.MIN_VALUE ? 0 : currentTick);
            return w;
        });
    }

    private static TickStat currentStat(Window w) {
        TickStat last = w.ticks.peekLast();
        if (last == null) {
            last = new TickStat();
            w.ticks.addLast(last);
        }
        return last;
    }

    /** Player logout: drop his contribution records from every window. */
    public static synchronized void onPlayerLogout(UUID id) {
        if (id == null) {
            return;
        }
        for (Window w : WINDOWS.values()) {
            for (TickStat stat : w.ticks) {
                stat.buyBy.remove(id);
                stat.sellBy.remove(id);
            }
        }
    }

    /** Stock delisted: throw away its window. */
    public static synchronized void onStockRetired(String code) {
        WINDOWS.remove(code);
    }

    /** Wipe everything (on server shutdown). */
    public static synchronized void clearAll() {
        WINDOWS.clear();
        currentTick = Long.MIN_VALUE;
    }

    /**
     * Call once per marketTick after the Event draw: check whether any stock's window
     * total has crossed the threshold. Crossing it spawns the matching Event (fixed
     * templates x1.15 / x0.85, lasting 20 ticks), and the "Triggered by" line under the
     * card lists the top 3 contributors within the window.
     */
    public static synchronized List<ActiveEvent> evaluate(MarketSavedData data) {
        List<ActiveEvent> fired = new ArrayList<>();
        MarketSettings ms = MarketSettings.server();
        if (!ms.eventPlayerTriggerEnabled) {
            return fired;
        }
        long tick = data.marketTicks;
        Iterator<Map.Entry<String, Window>> it = WINDOWS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Window> entry = it.next();
            String code = entry.getKey();
            Window w = entry.getValue();
            if (StockRegistry.getByCode(code) == null) {
                it.remove();       // retired stock: drop its window on the spot so the Map cannot leak
                continue;
            }
            long buy = 0;
            long sell = 0;
            for (TickStat s : w.ticks) {
                buy += s.buyTotal;
                sell += s.sellTotal;
            }
            if (buy >= ms.eventPlayerTriggerVolume
                    && broadEnough(w, true, buy)
                    && tick - w.lastFrenzyTick > TRIGGER_COOLDOWN_TICKS) {
                w.lastFrenzyTick = tick;
                ActiveEvent e = spawnTrigger("player_frenzy", code, w, true, data);
                if (e != null) {
                    fired.add(e);
                }
            }
            if (sell >= ms.eventPlayerTriggerVolume
                    && broadEnough(w, false, sell)
                    && tick - w.lastPanicTick > TRIGGER_COOLDOWN_TICKS) {
                w.lastPanicTick = tick;
                ActiveEvent e = spawnTrigger("player_panic", code, w, false, data);
                if (e != null) {
                    fired.add(e);
                }
            }
        }
        return fired;
    }

    /**
     * A player-volume event is market sentiment, not a private button. Require at least two independent
     * contributors and reject a window where one UUID supplied more than half of the side's volume;
     * this blocks one player from self-generating a bullish/bearish event with system trades. Two accounts
     * can still coordinate, but they now pay the real two-sided trading cost and cannot farm repeated
     * triggers because of the 200-tick cooldown.
     */
    private static boolean broadEnough(Window w, boolean buySide, long total) {
        if (w == null || total <= 0L) {
            return false;
        }
        Map<UUID, Long> merged = new HashMap<>();
        for (TickStat s : w.ticks) {
            Map<UUID, Long> src = buySide ? s.buyBy : s.sellBy;
            for (Map.Entry<UUID, Long> e : src.entrySet()) {
                merged.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
        if (merged.size() < 2) {
            return false;
        }
        long top = 0L;
        for (long amount : merged.values()) {
            top = Math.max(top, amount);
        }
        return top <= total / 2L;
    }

    private static ActiveEvent spawnTrigger(String templateId, String code, Window w,
                                            boolean buySide, MarketSavedData data) {
        EventTemplate tpl = EventRegistry.byId(templateId);
        if (tpl == null || EventComboTable.findActive(data, templateId) != null) {
            return null;
        }
        ActiveEvent e = ActiveEvent.spawnFixed(tpl, new java.util.LinkedHashSet<>(List.of(code)),
                tpl.fixedTicks > 0 ? tpl.fixedTicks : 20, null, data.nextEventUid(), 0);
        if (e != null) {
            e.contributorNames = topContributors(w, buySide, data);
        }
        return e;
    }

    /** Display names of the top 3 contributors (by buy, or sell, volume) inside the window. */
    private static String topContributors(Window w, boolean buySide, MarketSavedData data) {
        Map<UUID, Long> merged = new HashMap<>();
        for (TickStat s : w.ticks) {
            Map<UUID, Long> src = buySide ? s.buyBy : s.sellBy;
            for (Map.Entry<UUID, Long> e : src.entrySet()) {
                merged.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
        List<Map.Entry<UUID, Long>> list = new ArrayList<>(merged.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, list.size()); i++) {
            if (i > 0) {
                sb.append(',').append(' ');
            }
            sb.append(data.nameOf(list.get(i).getKey()));
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
