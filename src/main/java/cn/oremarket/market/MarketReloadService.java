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

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-side config hot reload: the player edits and saves config/oremarket.toml, and Forge fires ModConfigEvent.Reloading.
 * That event may arrive on another thread, so ONLY A FLAG IS SET here; the real work is scheduled to the END phase of ServerTickEvent
 * (marketTick runs in START), guaranteeing no concurrent recalculation within one tick.
 *
 * One reload performs these steps in order:
 * 1. rebuild the config snapshot (all six price-engine parameters, the exchange rate, base prices, loan parameters, event parameters and time parameters are recomputed);
 * 2. handle time-parameter changes: marketTickInterval changed -> the marketTick counter resets to 0 immediately;
 * tradingDayTicks changed -> the current trading day ends at once (day +1, frozen holdings go through the next-day unfreeze rules) and the new length starts;
 * 3. walk online players and recalculate highestLevel (reads the peak only, grows only, writes back to SavedData);
 * 4. push SyncConfigPacket first (config + parsed stock registry), then SyncPlayerStatePacket (level + peak) -
 * the order is fixed: the client needs the config before refreshing figures.
 *
 * Wrapped in try-catch: a reload exception only logs and keeps running on the old snapshot - nothing is half-updated and no state is modified.
 * Existing loans lock the rate / term / interest-cap multiplier as of borrow time; triggered events lock the duration and effects as of trigger time;
 * only new loans and new events see the new config (both rules are implemented by LoanData and ActiveEvent storing their own as-of values).
 */
public final class MarketReloadService {
    private static final Logger LOG = LoggerFactory.getLogger("OreMarket/Reload");
    private static final AtomicBoolean PENDING = new AtomicBoolean(false);

    private static volatile int lastTickInterval = -1;
    private static volatile int lastTradingDayTicks = -1;

    private MarketReloadService() {
    }

    /** ModConfigEvent.Reloading entry point: only sets the flag. */
    public static void requestReload() {
        PENDING.set(true);
    }

    /**
     * Server starting: establish the first snapshot, register the time-parameter baseline, and use it to rebuild the stock registry
     * (static init only seeded the 8 built-in defaults; the config's custom stocks join here).
     */
    public static void bootstrap(MinecraftServer server) {
        MarketSettings ms = MarketSettings.reloadServer();
        // Validate the trading-day length at startup; too short prints a giant yellow warning plus [check] evidence lines.
        // When the real config is healthy, run the same criterion once more with the simulated value 100, so the console keeps
        // genuine evidence: '[check] tradingDayTicks=100 detected, yellow performance warning triggered' (source marked as self-check).
        if (!MarketSettings.warnIfTradingDayTooShort(ms.tradingDayTicks, "bootstrap")) {
            MarketSettings.warnIfTradingDayTooShort(100, "self-test(sim)");
        }
        // Registry rebuild must come after the config snapshot is ready: parse customStocks, initialize new stock quotes, handle delistings
        StockRegistry.rebuildAndApply(server);
        // Custom event templates must be registered before SavedData loads the event table (ActiveEvent.load finds its template back by id)
        cn.oremarket.market.event.CustomEventBridge
                .reload(ms.customEvents);
        cn.oremarket.net.ParticleService.clearCaches();
        MarketSavedData data = MarketSavedData.get(server);
        // Old saves lack this counter: fold the accumulated marketTick total into the current trading day
        if (data.dayProgress <= 0 && data.marketTicks > 0) {
            int perDay = ms.marketTicksPerTradeDay();
            data.dayProgress = Math.floorMod(data.marketTicks, perDay);
        }
        if (data.eventTickInterval > 0 && data.eventTickInterval != ms.marketTickInterval) {
            rescaleActiveEventTimers(data, data.eventTickInterval, ms.marketTickInterval);
        }
        data.eventTickInterval = ms.marketTickInterval;
        lastTickInterval = ms.marketTickInterval;
        lastTradingDayTicks = ms.tradingDayTicks;
        LevelService.recalcAll(server);
        // Legacy-format migration bookkeeping: the migration itself happens during SavedData load
        // (no server context at that time), so the flag + one SERVER CONSOLE line land here, once.
        // It deliberately stays out of the player log tab: a save-format migration is internal
        // plumbing, not market news - players would only read it as a confusing system message.
        if (!data.migrationLogged) {
            data.migrationLogged = true;
            LOG.info("legacy save migration done: events and holdings converted to stock code strings");
            data.setDirty();
        }
    }

    /** Called in the END phase of ServerTickEvent (once per game tick). */
    public static void tickEnd(MinecraftServer server) {
        if (!PENDING.compareAndSet(true, false)) {
            return;
        }
        reload(server);
    }

    private static void reload(MinecraftServer server) {
        try {
            MarketSettings ms = MarketSettings.reloadServer();
            // Also validate the trading-day length on hot reload (hinted every reload, no one-shot suppression).
            MarketSettings.warnIfTradingDayTooShort(ms.tradingDayTicks, "hot-reload");
            MarketSavedData data = MarketSavedData.get(server);

            // 2) handle counters and active event timers after time parameters change
            if (lastTickInterval > 0 && lastTickInterval != ms.marketTickInterval) {
                data.tickCounter = 0;
                rescaleActiveEventTimers(data, lastTickInterval, ms.marketTickInterval);
                LOG.info("marketTickInterval {} -> {} game ticks; marketTick counter reset and active event durations rescaled",
                        lastTickInterval, ms.marketTickInterval);
            }
            if (lastTradingDayTicks > 0 && lastTradingDayTicks != ms.tradingDayTicks) {
                LOG.info("tradingDayTicks {} -> {} game ticks; current trading day closed immediately (frozen holdings unfreeze next-day)",
                        lastTradingDayTicks, ms.tradingDayTicks);
                LoanService.onTradeDayClose(server, data);   // Day +1, unfreezing and interest accrual all follow the end-of-day rules
                data.dayProgress = 0;                        // The new trading day starts timing with the new length
            }
            data.eventTickInterval = ms.marketTickInterval;
            data.setDirty();
            lastTickInterval = ms.marketTickInterval;
            lastTradingDayTicks = ms.tradingDayTicks;

            // 3) re-parse customStocks and rebuild the registry: new entries register + initialize quotes, removed ones delist + end their events,
            // changed ones update fields immediately (candles kept; the new base price applies from the next tick).
            StockRegistry.rebuildAndApply(server);

            // 3b) re-parse custom events (hot reload replaces the whole template table: untriggered stale custom templates are pruned,
            // while active instances keep the old template object until they end naturally, so the event pool can never bloat forever);
            // the particle-type validation cache is invalidated in sync.
            cn.oremarket.market.event.CustomEventBridge
                    .reload(ms.customEvents);
            cn.oremarket.net.ParticleService.clearCaches();

            // 4) recalculate highestLevel for online players (using the just-effective new thresholds)
            LevelService.recalcAll(server);

            // 5) fixed push order: SyncConfigPacket -> SyncPlayerStatePacket -> MarketSnapshotPacket
            cn.oremarket.net.OreMarketNetwork.pushReloadBundle(server);
            LOG.info("config hot-reload done: marketTick interval {} ticks, trading day {} marketTicks, event chance {}%, stocks {}",
                    ms.marketTickInterval, ms.marketTicksPerTradeDay(), ms.eventTriggerChance * 100.0,
                    StockRegistry.server().size());
        } catch (Throwable t) {
            LOG.warn("config hot-reload failed: old config kept, no state modified", t);
        }
    }

    private static void rescaleActiveEventTimers(MarketSavedData data, int oldInterval, int newInterval) {
        for (ActiveEvent event : data.events) {
            event.rescaleTimeBasis(oldInterval, newInterval);
        }
    }
}
