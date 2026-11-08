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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Event combo table (10 hard-coded groups; convention: named constants, never scattered through business branches).
 *
 * Matching rules:
 * - set matching: the combo fires when A and B are both in the active event pool, regardless of order;
 * - at most one combo fires per marketTick, the first match in table order; after a hit this tick stops checking;
 * - each combo fires only once per pair of parent event instances (fired key = comboId|parentUidA|parentUidB, stored in SavedData);
 * - the combo card's rarity takes the higher of the two parents: all 10 groups here have Rare-tier parents,
 * so child templates are declared RARE uniformly; to introduce higher-tier parents, extend ComboRule#childRarity.
 */
public final class EventComboTable {

    /** One combo rule: two parent template ids + child template id + child duration in marketTicks. */
    public static final class ComboRule {
        public final String childId;
        public final String parentA;
        public final String parentB;
        public final int durationTicks;

        ComboRule(String childId, String parentA, String parentB, int durationTicks) {
            this.childId = childId;
            this.parentA = parentA;
            this.parentB = parentB;
            this.durationTicks = durationTicks;
        }

        /** Fired dedup key (a pair of parent instance uids, so the same pair combos exactly once). */
        public String firedKey(int uidA, int uidB) {
            return childId + "|" + Math.min(uidA, uidB) + "|" + Math.max(uidA, uidB);
        }
    }

    /** The 10 fixed combos (table order is priority order). */
    public static final List<ComboRule> RULES = List.of(
            // 1 Coal Embargo + Major Ore Discovery -> Market Chaos (market-wide volatility x1.5, 20 ticks)
            new ComboRule("combo_market_chaos", "legacy_coal_embargo", "legacy_great_vein", 20),
            // 2 Diamond Craze + Central Bank Rate Hike -> Bubble Burst (diamond x0.8, 15 ticks)
            new ComboRule("combo_bubble_burst", "legacy_diamond_fever", "legacy_rate_hike", 15),
            // 3 Gold Safe-Haven + Market Frenzy -> Precious Metals Boom (gold/diamond/netherite x1.2, 20 ticks)
            new ComboRule("combo_precious_frenzy", "gold_haven", "legacy_market_frenzy", 20),
            // 4 Redstone Revolution + Industrial Recovery -> Tech Bull Run (redstone/iron/copper x1.15, 25 ticks)
            new ComboRule("combo_tech_bull", "legacy_redstone_revolution", "industry_reboot", 25),
            // 5 Copper Price Crash + Mine Accident -> Supply Crisis (copper/iron/gold x1.3, 18 ticks)
            new ComboRule("combo_supply_crisis", "legacy_copper_crash", "legacy_mine_accident", 18),
            // 6 Lapis Lazuli Slump + Diamond Craze -> Luxury Divergence (lapis x0.7, diamond x1.25, 20 ticks)
            new ComboRule("combo_luxury_split", "legacy_lapis_surplus", "legacy_diamond_fever", 20),
            // 7 Coal Embargo + Central Bank Rate Hike -> Energy Squeeze (coal x1.4 + market-wide volatility x1.3, 22 ticks)
            new ComboRule("combo_energy_squeeze", "legacy_coal_embargo", "legacy_rate_hike", 22),
            // 8 Netherite Arms Buildup + Market Frenzy -> Arms Race (netherite x1.35, 25 ticks)
            new ComboRule("combo_arms_race", "legacy_netherite_arms", "legacy_market_frenzy", 25),
            // 9 Major Ore Discovery + Industrial Recovery -> Oversupply (iron/copper x0.75, 20 ticks)
            new ComboRule("combo_oversupply", "legacy_great_vein", "industry_reboot", 20),
            // 10 Gold Safe-Haven + Central Bank Rate Hike -> Haven Rush Fails (gold x0.85, 15 ticks)
            new ComboRule("combo_haven_fail", "gold_haven", "legacy_rate_hike", 15));

    /** 'Luxury Divergence' gives its two stocks different multipliers, so the template carries no effect module - the exact module plan is supplied here. */
    private static final String LUXURY_SPLIT_ID = "combo_luxury_split";

    private EventComboTable() {
    }

    /**
     * Check and fire the first combo whose condition holds (called once per marketTick; requirement: check only when events fire,
     * never per frame - EventEngine drives this method at commit and on new ticks).
     *
     * @return the combo event fired; null when no combo's condition holds
     */
    public static ActiveEvent fireFirstMatch(MarketSavedData data) {
        return fireFirstMatch(data, null);
    }

    /**
     * Entry point with a server-wide broadcast: when server is non-null, a combo hit pushes one
     * 'Event combo: A + B triggered C' log line to all online players (text generated from a language key, so non-Chinese locales read English).
     */
    public static ActiveEvent fireFirstMatch(MarketSavedData data,
                                             net.minecraft.server.MinecraftServer server) {
        if (!cn.oremarket.market.MarketSettings.server()
                .eventCombinationEnabled) {
            return null;
        }
        for (ComboRule rule : RULES) {
            ActiveEvent a = findActive(data, rule.parentA);
            ActiveEvent b = findActive(data, rule.parentB);
            if (a == null || b == null || a == b) {
                continue;
            }
            String key = rule.firedKey(a.uid, b.uid);
            if (data.comboFiredKeys.contains(key)) {
                continue;
            }
            if (isTemplateActive(data, rule.childId)) {
                continue;   // The previous combo is still active: no second card stacks on top
            }
            EventTemplate child = EventRegistry.byId(rule.childId);
            if (child == null) {
                continue;
            }
            data.comboFiredKeys.add(key);
            data.setDirty();
            ActiveEvent combo;
            if (LUXURY_SPLIT_ID.equals(rule.childId)) {
                combo = ActiveEvent.spawnFixed(child, codes(child), rule.durationTicks,
                        luxuryPlans(), data.nextEventUid(), 0, null);
            } else {
                combo = ActiveEvent.spawnFixed(child, codes(child), rule.durationTicks,
                        null, data.nextEventUid(), 0);
            }
            if (server != null) {
                cn.oremarket.market.log.LogService.pushAllKey(
                        server, data,
                        cn.oremarket.common.LogType.EVENT,
                        "oremarket.event.log.combo", a.nameOrKey(), b.nameOrKey(),
                        combo.nameOrKey());
            }
            return combo;
        }
        return null;
    }

    /** Cleanup at combo firing: fired keys whose parent events left the pool are dropped, so the set never grows unbounded. */
    public static void pruneFiredKeys(MarketSavedData data) {
        if (data.comboFiredKeys.isEmpty()) {
            return;
        }
        Set<String> liveParentUids = new LinkedHashSet<>();
        for (ActiveEvent e : data.events) {
            liveParentUids.add(String.valueOf(e.uid));
        }
        data.comboFiredKeys.removeIf(key -> {
            // Two kinds of keys share one set, so liveness must be judged separately:
            // combo key = childId|uidA|uidB - parts[1] and parts[2] are both parent event uids;
            // chain key = chain|childId|parentUid - only parts[2] is the parent uid (parts[1] is a template name).
            // The old code applied the combo reading (parts[1]) to chain keys too, and a template name never equals a uid,
            // so chain dedup keys were wiped every tick and the second dedup line of defense was pure decoration.
            String[] parts = key.split("\\|");
            if (key.startsWith(EventChainTable.CHAIN_FIRED_PREFIX)) {
                // Chain key chain|childId|parentUid: only the last segment is the parent uid; parts[1] is a template name
                return parts.length != 3 || !liveParentUids.contains(parts[2]);
            }
            return parts.length != 3
                    || !liveParentUids.contains(parts[1])
                    || !liveParentUids.contains(parts[2]);
        });
    }

    /** Whether a combo child just fired this tick (only one per tick allowed; EventEngine uses this as the gate). */
    public static ActiveEvent findActive(MarketSavedData data, String templateId) {
        for (ActiveEvent e : data.events) {
            if (e.template.id.equals(templateId)) {
                return e;
            }
        }
        return null;
    }

    private static boolean isTemplateActive(MarketSavedData data, String templateId) {
        return findActive(data, templateId) != null;
    }

    private static Set<String> codes(EventTemplate child) {
        return child.fixedTargets.isEmpty() ? ActiveEvent.activeCodes()
                : new LinkedHashSet<>(child.fixedTargets);
    }

    private static List<ActiveEvent.ModulePlan> luxuryPlans() {
        return List.of(
                ActiveEvent.ModulePlan.ofCodes(EffectType.PRICE_MULT,
                        new LinkedHashSet<>(List.of("LAPIS")), 0.7),
                ActiveEvent.ModulePlan.ofCodes(EffectType.PRICE_MULT,
                        new LinkedHashSet<>(List.of("DIAMOND")), 1.25));
    }
}
