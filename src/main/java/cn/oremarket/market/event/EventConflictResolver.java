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

import cn.oremarket.common.EffectType;
import cn.oremarket.market.ActiveEvent;
import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.MarketSettings;
import cn.oremarket.market.log.LogService;
import cn.oremarket.common.LogType;

import java.util.List;

/**
 * Event conflict cancellation (requirement 2.5 / part 4.6).
 *
 * Strict definition of a "direct contradiction" (the corrected criterion; pinned
 * here so it cannot drift into scattered business branches):
 *  two modules act on the same stock and their price-multiplier directions are
 *  opposite - one param above 1.0, the other below 1.0.
 * When that holds: both Events are cancelled (the new one never enters the pool,
 * the old one is removed at once), a "market wait-and-see" Event is spawned
 * (whole-market volatility x0.5, lasting 10 marketTicks), and the log line
 * "Event conflict: X and Y cancelled out, market entering wait-and-see" is written.
 *
 * Mind the difference from the legacy TREND conflict: the old accepts() tests for
 * opposite forced trends (rising vs falling), while this class tests for opposite
 * price-multiplier directions (&gt;1 vs &lt;1). The two never overlap.
 */
public final class EventConflictResolver {

    /** Duration of the wait-and-see Event in marketTicks (value from the design sheet). */
    public static final int WATCH_TICKS = 10;
    /** Whole-market volatility multiplier of the wait-and-see Event. */
    public static final double WATCH_VOLATILITY = 0.5;

    private EventConflictResolver() {
    }

    /** Whether the two modules form a "direct contradiction" on the same stock. */
    static boolean contradicts(ActiveEvent.EffectModule a, ActiveEvent.EffectModule b) {
        if (a.type != EffectType.PRICE_MULT || b.type != EffectType.PRICE_MULT) {
            return false;
        }
        boolean aUp = a.param > 1.0;
        boolean aDown = a.param < 1.0;
        boolean bUp = b.param > 1.0;
        boolean bDown = b.param < 1.0;
        if (!((aUp && bDown) || (aDown && bUp))) {
            return false;
        }
        // do the scopes overlap? (a global module overlaps with any target)
        if (a.global || b.global) {
            return true;
        }
        for (String code : a.targets) {
            if (b.targets.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Conflict ruling before a new Event enters the pool. Returns true when a
     * **cancellation actually happened**: the caller must then not add the draft to
     * the Event pool; this method takes care of removing the old Event and spawning
     * the wait-and-see Event plus its log line.
     */
    public static boolean resolveAgainst(net.minecraft.server.MinecraftServer server,
                                         MarketSavedData data, ActiveEvent draft,
                                         java.util.function.Consumer<ActiveEvent> watcherSpawner) {
        if (!MarketSettings.server().eventConflictEnabled) {
            return false;
        }
        for (ActiveEvent existing : List.copyOf(data.events)) {
            for (ActiveEvent.EffectModule a : draft.modules) {
                if (a.expired()) {
                    continue;
                }
                for (ActiveEvent.EffectModule b : existing.modules) {
                    if (b.expired()) {
                        continue;
                    }
                    if (contradicts(a, b)) {
                        data.events.remove(existing);
                        EventTemplate watch = EventRegistry.byId(EventRegistry.CONFLICT_WATCH_ID);
                        if (watch != null && watcherSpawner != null) {
                            ActiveEvent e = ActiveEvent.spawnFixed(watch, ActiveEvent.activeCodes(),
                                    WATCH_TICKS, null, data.nextEventUid(), 0);
                            if (e != null) {
                                watcherSpawner.accept(e);
                            }
                        }
                        data.setDirty();
                        if (server != null) {
                            LogService.pushAllKey(server, data, LogType.EVENT,
                                    "oremarket.event.log.conflict",
                                    existing.nameOrKey(), draft.nameOrKey());
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
