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

import java.util.List;

/**
 * Event chain rule table (five hard-coded rules; when the parent event reaches its Nth
 * marketTick the child event fires; children get independent cards and chainDepth=1 never chains further down).
 *
 * Coexists with the old probabilistic chaining: this table is DETERMINISTIC (fires on schedule exactly),
 * while EventTemplate.chain's probability / end-of-event triggers keep their original behavior
 * (e.g. cave-in collapse -> rescue operation).
 * The central-bank rate hike's old "trigger market absorption on end" declaration was removed; it now runs via rule 2.
 */
public final class EventChainTable {

    /** One chain rule: parent template id + the parent event's Nth marketTick + child template id. */
    public static final class ChainRule {
        public final String parentTemplateId;
        public final int afterTicks;
        public final String childTemplateId;

        ChainRule(String parentTemplateId, int afterTicks, String childTemplateId) {
            this.parentTemplateId = parentTemplateId;
            this.afterTicks = afterTicks;
            this.childTemplateId = childTemplateId;
        }
    }

    /** The five fixed chain rules. */
    public static final List<ChainRule> RULES = List.of(
            // 1 Mine Accident at tick 3 -> Rescue Operation (same stock x1.1, 10 ticks)
            new ChainRule("legacy_mine_accident", 3, "chain_rescue_action"),
            // 2 Central Bank Rate Hike at tick 5 -> Market Absorption (market-wide volatility x0.7, 15 ticks)
            new ChainRule("legacy_rate_hike", 5, "chain_rate_digest"),
            // 3 Diamond Craze at tick 10 -> Bubble Warning (diamond x0.9, 10 ticks)
            new ChainRule("legacy_diamond_fever", 10, "chain_bubble_warn"),
            // 4 Market Frenzy at tick 8 -> Profit Taking (market-wide x0.95, 12 ticks)
            new ChainRule("legacy_market_frenzy", 8, "chain_profit_close"),
            // 5 Gold Safe-Haven at tick 6 -> Safe-Haven Capital Inflow (gold x1.15, 12 ticks)
            new ChainRule("gold_haven", 6, "chain_haven_inflow"));

    /** Triggered flags: same principle as the combo table - one fire per parent event instance times rule. */
    static final String CHAIN_FIRED_PREFIX = "chain|";

    private EventChainTable() {
    }

    /**
     * Called once per marketTick (after event timing): scans active parent events and spawns the child
     * exactly when the tick designated by the rule is reached.
     *
     * @return children spawned this tick (usually 0 to 1)
     */
    public static java.util.List<ActiveEvent> tick(MarketSavedData data) {
        MarketSettings ms = MarketSettings.server();
        java.util.List<ActiveEvent> fired = new java.util.ArrayList<>();
        if (!ms.eventChainEnabled) {
            return fired;
        }
        for (ActiveEvent parent : data.events) {
            if (parent.chainDepth != 0 || parent.template.preview) {
                continue;
            }
            for (ChainRule rule : RULES) {
                if (!rule.parentTemplateId.equals(parent.template.id)
                        || parent.elapsed() != rule.afterTicks) {
                    continue;
                }
                String key = CHAIN_FIRED_PREFIX + rule.childTemplateId + "|" + parent.uid;
                if (!data.comboFiredKeys.add(key)) {
                    continue;   // This parent instance already fired this chain rule
                }
                EventTemplate child = EventRegistry.byId(rule.childTemplateId);
                if (child == null) {
                    continue;
                }
                ActiveEvent e = ActiveEvent.spawnFixed(child, null,
                        child.fixedTicks > 0 ? child.fixedTicks : ruleDuration(child),
                        parent.targets, data.nextEventUid(), 1);
                if (e != null) {
                    fired.add(e);
                    data.setDirty();
                }
            }
        }
        return fired;
    }

    private static int ruleDuration(EventTemplate child) {
        return Math.max(1, child.fixedTicks);
    }
}
