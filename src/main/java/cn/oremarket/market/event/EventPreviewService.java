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

import cn.oremarket.common.Rarity;
import cn.oremarket.market.ActiveEvent;
import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.MarketSettings;
import net.minecraft.util.RandomSource;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Legendary-event preview (requirement 2, item 6):
 * one marketTick before a legendary event fires, a gray preview card (0xFF666666, rendered via the preview flag)
 * shows "Incoming: <event>, affecting <stock>, expected in N seconds" and lasts exactly one marketTick.
 * The preview is accurate with probability eventPreviewAccuracy (default 80%); the inaccurate 20% are hoaxes:
 * nothing happens on the next tick. Real and fake cards are visually identical and cannot be told apart.
 *
 * Implementation: when EventEngine draws a legendary template it does not fire directly - the preview and the
 * pending trigger are registered here and consumed at the start of the next marketTick. Previews and pending
 * triggers are memory-only: a server restart may lose one preview, which is acceptable; never persisted
 * (no cross-version NBT compatibility burden).
 */
public final class EventPreviewService {

    /** One pending legendary-event trigger. */
    public static final class Pending {
        public final String templateId;
        public final String conditionCode;
        public final long fireAtMarketTick;

        Pending(String templateId, String conditionCode, long fireAtMarketTick) {
            this.templateId = templateId;
            this.conditionCode = conditionCode;
            this.fireAtMarketTick = fireAtMarketTick;
        }
    }

    private static final Deque<Pending> PENDING = new ArrayDeque<>();

    private EventPreviewService() {
    }

    /**
     * Register a legendary preview and return the preview card event (shown for 1 tick).
     * The accuracy roll happens here: a hoax produces only the card, no pending trigger.
     */
    public static ActiveEvent schedule(MarketSavedData data, EventTemplate target,
                                       String conditionCode, RandomSource rand) {
        MarketSettings ms = MarketSettings.server();
        if (!ms.eventPreviewEnabled || target.rarity != Rarity.LEGENDARY) {
            return null;
        }
        boolean accurate = rand.nextDouble() < ms.eventPreviewAccuracy;
        if (accurate) {
            PENDING.addLast(new Pending(target.id, conditionCode, data.marketTicks + 1));
        }
        EventTemplate preview = EventRegistry.byId(EventRegistry.PREVIEW_ID);
        if (preview == null) {
            return null;
        }
        // Do not put target.nameTemplate on the preview card: that was an insider-information leak
        // (the exact legendary name exposed direction and magnitude one marketTick early). The preview
        // remains a generic, accurate-or-hoax rumour; the real event announces its own name only when it lands.
        return ActiveEvent.spawnFixed(preview, ActiveEvent.activeCodes(), 1,
                java.util.List.of(), data.nextEventUid(), 0, null);
    }

    /** Called at the start of the next marketTick: pulls out legendary triggers whose time has come. */
    public static Pending pollDue(long marketTick) {
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (p.fireAtMarketTick <= marketTick) {
                it.remove();
                return p;
            }
        }
        return null;
    }

    /** Server stopping: clear pending triggers (preview cards live in the event pool and vanish naturally with memory/save). */
    public static void clearAll() {
        PENDING.clear();
    }
}
