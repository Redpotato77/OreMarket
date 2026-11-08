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

import cn.oremarket.market.EventCard;
import cn.oremarket.market.Stock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import cn.oremarket.common.Rarity;
import cn.oremarket.common.EffectSpec;
import cn.oremarket.common.EffectType;

/**
 * Event template registry.
 *
 * Two parts:
 * 1. the original 12 fixed event cards -> special templates, uniformly Rare rarity, names and effects untouched
 * (price multipliers still use the original 'spread the cumulative change over each marketTick' formula, see ActiveEvent#spawn);
 * 2. 25 new templates covering the Common/Rare/Epic/Legendary tiers, with randomized parameters, randomized targets and combinable effects.
 * Dynamic candidate-pool stocks use the ACTIVE scope for generic events; only templates whose wording is explicitly
 * about the built-in mineral market use BUILTIN_MINERALS, so arbitrary items never receive a mismatched mine/vein description.
 *
 * Requirement 9 asks 'do not hand-write more than 40 fixed event cards', so only templates and random ranges are declared here;
 * concrete events are produced at runtime by the server.
 */
public final class EventRegistry {

    /** id -> template, declaration order kept (for debugging and command display). */
    private static final Map<String, EventTemplate> BY_ID = new LinkedHashMap<>();
    private static final List<EventTemplate> ALL = new ArrayList<>();
    /** Custom templates registered from config (replaced wholesale on hot reload; stored apart from the built-in table). */
    private static final Map<String, EventTemplate> CUSTOM_BY_ID = new LinkedHashMap<>();
    /** Stable id prefix for custom events (saves find templates back by id). */
    public static final String CUSTOM_ID_PREFIX = "custom_";

    /** Legendary preview display-card template id (never in the random pool; produced only by EventPreviewService). */
    public static final String PREVIEW_ID = "legend_preview";
    /** 'Market Watch' template id produced by conflict cancellation. */
    public static final String CONFLICT_WATCH_ID = "conflict_watch";

    static {
        // ---------------------------------------------------------- 1) the original 12 cards (Rare)
        for (EventCard card : EventCard.ORDER) {
            EventTemplate.Builder b = EventTemplate.of("legacy_" + card.id(), card.cardName(), Rarity.RARE)
                    .desc(card.description())
                    .fixedCodes(card.affectedCodes().toArray(new String[0]))
                    .fixedTicks(card.durationTicks())
                    .effect(EffectSpec.price(1.0, 1.0))
                    .legacy(card);
            if (!card.isAllMarket()) {
                b.builtinMineralsOnly();
            }
            // The two named examples from requirements 5 and 4: level gating and end-of-event chaining; neither changes a card's name or price effect
            if (card == EventCard.NETHERITE_ARMS) {
                b.condition(EventCondition.playerTier(4));
            }
            // Central Bank Rate Hike's 'Market Absorption' successor is now triggered via EventChainTable (requirement 2, item 4 deterministic chaining);
            // the old per-tick probability chain was removed so one successor event is never double-triggered by two mechanisms.
            register(b.build());
        }

        // ---------------------------------------------------------- 2) Common (weight 60, 1~2 stocks, 20~60 marketTicks = 1~3 min)
        register(EventTemplate.of("local_quote", cn.oremarket.common.RawText.t("oremarket.text.0552"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0557"))
                .randomTargets(1, 2)
                .duration(25, 60)
                .effect(EffectSpec.price(0.9, 1.1))
                .build());

        register(EventTemplate.of("rumor", cn.oremarket.common.RawText.t("oremarket.text.0551"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0568"))
                .randomTargets(1, 1)
                .duration(20, 45)
                .effect(EffectSpec.trend(0))
                .build());

        register(EventTemplate.of("quiet_mine", cn.oremarket.common.RawText.t("oremarket.text.0184"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0565"))
                .randomTargets(1, 2)
                .duration(25, 60)
                .effect(EffectSpec.volatility(0.2, 0.7))
                .builtinMineralsOnly()
                .build());

        register(EventTemplate.of("fee_promo", cn.oremarket.common.RawText.t("oremarket.text.0244"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0493"))
                .allTargets()
                .duration(25, 55)
                .effect(EffectSpec.feeAdd(-0.008, -0.004))
                .build());

        register(EventTemplate.of("thin_book", cn.oremarket.common.RawText.t("oremarket.text.0170"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0559"))
                .randomTargets(1, 2)
                .duration(25, 60)
                .effect(EffectSpec.shock(1.4, 2.2))
                .build());

        register(EventTemplate.of("wide_band", cn.oremarket.common.RawText.t("oremarket.text.0175"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0488"))
                .allTargets()
                .duration(25, 60)
                .effect(EffectSpec.limit(0.14, 0.18))
                .build());

        register(EventTemplate.of("open_bell", cn.oremarket.common.RawText.t("oremarket.text.0164"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0561"))
                .randomTargets(1, 2)
                .duration(20, 50)
                .condition(EventCondition.tradeDayOpen(2))
                .effect(EffectSpec.price(0.96, 1.06))
                .effect(EffectSpec.volatility(1.2, 1.8))
                .build());

        register(EventTemplate.of("panic_dump", cn.oremarket.common.RawText.t("oremarket.text.0664"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0665"))
                .randomTargets(1, 2)
                .duration(20, 45)
                .effect(EffectSpec.price(0.85, 0.85))
                .effect(EffectSpec.trend(-1))
                .effect(EffectSpec.limit(0.10, 0.10))
                .build());

        register(EventTemplate.of("local_halt", cn.oremarket.common.RawText.t("oremarket.text.0553"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0556"))
                .randomTargets(1, 1)
                .duration(45, 60)
                .effect(EffectSpec.suspend(20, 40))
                .build());

        // ---------------------------------------------------------- 3) Rare (weight 25, 2~4 stocks, 40~100 = 2~5 min)
        register(EventTemplate.of("mine_collapse", cn.oremarket.common.RawText.t("oremarket.text.0195"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0569"))
                .randomTargets(2, 3)
                .duration(40, 80)
                .effect(EffectSpec.price(1.1, 1.3))
                .effect(EffectSpec.volatility(1.5, 1.5))
                .chain(new EventTemplate.Chain("rescue_op", 0.02, false, true))
                .builtinMineralsOnly()
                .build());

        register(EventTemplate.of("rescue_op", cn.oremarket.common.RawText.t("oremarket.text.0177"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0576"))
                .sameAsParent()
                .duration(40, 70)
                .chainOnly()
                .effect(EffectSpec.price(1.05, 1.2))
                .builtinMineralsOnly()
                .build());

        register(EventTemplate.of("policy_boost", cn.oremarket.common.RawText.t("oremarket.text.0176"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0560"))
                .randomTargets(2, 4)
                .duration(50, 90)
                .effect(EffectSpec.trend(1))
                .effect(EffectSpec.reversion(0.01, 0.01))
                .build());

        register(EventTemplate.of("bottom_fishing", cn.oremarket.common.RawText.t("oremarket.text.0293"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0558"))
                .conditionTarget()
                .duration(40, 80)
                .condition(EventCondition.priceDrop(0.5))
                .effect(EffectSpec.price(1.05, 1.15))
                .build());

        register(EventTemplate.of("profit_taking", cn.oremarket.common.RawText.t("oremarket.text.0191"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0563"))
                .conditionTarget()
                .duration(40, 80)
                .condition(EventCondition.holdingAbove(600))
                .effect(EffectSpec.price(0.85, 0.95))
                .effect(EffectSpec.volatility(1.3, 1.3))
                .build());

        register(EventTemplate.of("liquidity_dry", cn.oremarket.common.RawText.t("oremarket.text.0254"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0567"))
                .randomTargets(2, 3)
                .duration(40, 90)
                .effect(EffectSpec.volatility(2.0, 3.0))
                .effect(EffectSpec.shock(2.0, 2.0))
                .build());

        register(EventTemplate.of("official_subsidy", cn.oremarket.common.RawText.t("oremarket.text.0158"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0500"))
                .allTargets()
                .duration(40, 90)
                .condition(EventCondition.coinBelow(20000))
                .effect(EffectSpec.feeSet(0.005))
                .build());

        // ---------------------------------------------------------- 4) Epic (weight 12, 4~6 stocks, 80~160 = 4~8 min)
        register(EventTemplate.of("central_bank", cn.oremarket.common.RawText.t("oremarket.text.0157"), Rarity.EPIC)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0498"))
                .allTargets()
                .duration(80, 140)
                .effect(EffectSpec.feeAdd(-0.012, -0.008))
                .effect(EffectSpec.volatility(0.5, 0.5))
                .build());

        register(EventTemplate.of("vein_depletion", cn.oremarket.common.RawText.t("oremarket.text.0187"), Rarity.EPIC)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0575"))
                .randomTargets(4, 6)
                .duration(80, 140)
                .effect(EffectSpec.trend(-1))
                .effect(EffectSpec.reversion(0.1, 0.1))
                .builtinMineralsOnly()
                .build());

        register(EventTemplate.of("halt_wave", cn.oremarket.common.RawText.t("oremarket.text.0094"), Rarity.EPIC)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0571"))
                .randomTargets(2, 4)
                .duration(100, 160)
                .effect(EffectSpec.suspend(20, 40))
                .effect(EffectSpec.volatility(1.4, 1.4))
                .build());

        register(EventTemplate.of("reversion_lock", cn.oremarket.common.RawText.t("oremarket.text.0116"), Rarity.EPIC)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0564"))
                .randomTargets(4, 6)
                .duration(80, 140)
                .effect(EffectSpec.reversion(0.12, 0.15))
                .effect(EffectSpec.volatility(0.3, 0.3))
                .build());

        // ---------------------------------------------------------- 5) Legendary (weight 3, all or most, 160~300 = 8~15 min)
        register(EventTemplate.of("market_crash", cn.oremarket.common.RawText.t("oremarket.text.0160"), Rarity.LEGENDARY)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0512"))
                .allTargets()
                .duration(160, 240)
                .condition(EventCondition.coinAbove(3000))
                .effect(EffectSpec.price(0.85, 0.85))
                .effect(EffectSpec.volatility(2.0, 2.0))
                .effect(EffectSpec.limit(0.20, 0.20))
                .build());

        register(EventTemplate.of("golden_age", cn.oremarket.common.RawText.t("oremarket.text.0197"), Rarity.LEGENDARY)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0505"))
                .allTargets()
                .duration(160, 240)
                .effect(EffectSpec.price(1.15, 1.15))
                .effect(EffectSpec.trend(1))
                .effect(EffectSpec.feeAdd(-0.018, -0.012))
                .build());

        register(EventTemplate.of("total_halt", cn.oremarket.common.RawText.t("oremarket.text.0153"), Rarity.LEGENDARY)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0501"))
                .allTargets()
                .duration(160, 200)
                .condition(EventCondition.coinAbove(8000))
                .effect(EffectSpec.suspend(30, 50))
                .effect(EffectSpec.limit(0.05, 0.05))
                .build());

        register(EventTemplate.of("mega_merge", cn.oremarket.common.RawText.t("oremarket.text.0193"), Rarity.LEGENDARY)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0506"))
                .allTargets()
                .duration(160, 200)
                .effect(EffectSpec.shock(0.5, 0.5))
                .effect(EffectSpec.volatility(0.2, 0.2))
                .effect(EffectSpec.reversion(0.005, 0.005))
                .build());

        register(EventTemplate.of("black_swan", cn.oremarket.common.RawText.t("oremarket.text.0198"), Rarity.LEGENDARY)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0570"))
                .randomTargets(6, 8)
                .duration(240, 300)
                .effect(EffectSpec.price(0.92, 0.92))
                .effect(EffectSpec.volatility(3.0, 3.0))
                .effect(EffectSpec.trend(0))
                .chain(new EventTemplate.Chain("market_digest", 0.01, false, false))
                .build());

        // ---------------------------------------------------------- 6) chain-triggered only (never in the random pool)
        register(EventTemplate.of("market_digest", cn.oremarket.common.RawText.t("oremarket.text.0161"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.text.0509"))
                .allTargets()
                .duration(40, 90)
                .chainOnly()
                .effect(EffectSpec.volatility(0.6, 0.9))
                .build());

        // ---------------------------------------------------------- 7) two new base events referenced by combos/chains (enter the random pool)
        register(EventTemplate.of("gold_haven", cn.oremarket.common.RawText.t("oremarket.name.event.20"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.gold_hedge"))
                .fixedCodes("GOLD")
                .duration(40, 90)
                .effect(EffectSpec.price(1.05, 1.15))
                .effect(EffectSpec.volatility(0.6, 0.8))
                .builtinMineralsOnly()
                .build());
        register(EventTemplate.of("industry_reboot", cn.oremarket.common.RawText.t("oremarket.name.event.06"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.infra_orders"))
                .fixedCodes("IRON", "COPPER", "COAL")
                .duration(40, 90)
                .effect(EffectSpec.price(1.05, 1.12))
                .builtinMineralsOnly()
                .build());

        // ---------------------------------------------------------- 8) combo children (10 groups, fixed parameters, triggered only by EventComboTable)
        register(EventTemplate.of("combo_market_chaos", cn.oremarket.common.RawText.t("oremarket.name.event.08"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.embargo_discovery"))
                .allTargets().chainOnly().fixedTicks(20)
                .effect(EffectSpec.volatility(1.5, 1.5).glob())
                .build());
        register(EventTemplate.of("combo_bubble_burst", cn.oremarket.common.RawText.t("oremarket.name.event.13"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.hike_burst"))
                .fixedCodes("DIAMOND").chainOnly().fixedTicks(15)
                .effect(EffectSpec.price(0.8, 0.8))
                .build());
        register(EventTemplate.of("combo_precious_frenzy", cn.oremarket.common.RawText.t("oremarket.name.event.17"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.haven_mania"))
                .fixedCodes("GOLD", "DIAMOND", "NETHERITE").chainOnly().fixedTicks(20)
                .effect(EffectSpec.price(1.2, 1.2))
                .build());
        register(EventTemplate.of("combo_tech_bull", cn.oremarket.common.RawText.t("oremarket.name.event.15"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.tech_recovery"))
                .fixedCodes("REDSTONE", "IRON", "COPPER").chainOnly().fixedTicks(25)
                .effect(EffectSpec.price(1.15, 1.15))
                .build());
        register(EventTemplate.of("combo_supply_crisis", cn.oremarket.common.RawText.t("oremarket.name.event.01"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.crash_mine"))
                .fixedCodes("COPPER", "IRON", "GOLD").chainOnly().fixedTicks(18)
                .effect(EffectSpec.price(1.3, 1.3))
                .build());
        register(EventTemplate.of("combo_luxury_split", cn.oremarket.common.RawText.t("oremarket.name.event.05"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.luxury_split"))
                .fixedCodes("LAPIS", "DIAMOND").chainOnly().fixedTicks(20)
                .build());   // modules are given exactly by EventComboTable's ModulePlan (the two stocks get different multipliers)
        register(EventTemplate.of("combo_energy_squeeze", cn.oremarket.common.RawText.t("oremarket.name.event.16"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.embargo_hike"))
                .fixedCodes("COAL").chainOnly().fixedTicks(22)
                .effect(EffectSpec.price(1.4, 1.4))
                .effect(EffectSpec.volatility(1.3, 1.3).glob())
                .build());
        register(EventTemplate.of("combo_arms_race", cn.oremarket.common.RawText.t("oremarket.name.event.03"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.arms_mania"))
                .fixedCodes("NETHERITE").chainOnly().fixedTicks(25)
                .effect(EffectSpec.price(1.35, 1.35))
                .build());
        register(EventTemplate.of("combo_oversupply", cn.oremarket.common.RawText.t("oremarket.name.event.02"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.discovery_late"))
                .fixedCodes("IRON", "COPPER").chainOnly().fixedTicks(20)
                .effect(EffectSpec.price(0.75, 0.75))
                .build());
        register(EventTemplate.of("combo_haven_fail", cn.oremarket.common.RawText.t("oremarket.name.event.18"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.hike_haven_fail"))
                .fixedCodes("GOLD").chainOnly().fixedTicks(15)
                .effect(EffectSpec.price(0.85, 0.85))
                .build());

        // ---------------------------------------------------------- 9) chain children (5 rules, fixed parameters, triggered only by EventChainTable)
        register(EventTemplate.of("chain_rescue_action", cn.oremarket.common.RawText.t("oremarket.text.0177"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.rescue"))
                .sameAsParent().chainOnly().fixedTicks(10)
                .effect(EffectSpec.price(1.1, 1.1))
                .build());
        register(EventTemplate.of("chain_rate_digest", cn.oremarket.common.RawText.t("oremarket.text.0161"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.hike_calm"))
                .allTargets().chainOnly().fixedTicks(15)
                .effect(EffectSpec.volatility(0.7, 0.7).glob())
                .build());
        register(EventTemplate.of("chain_bubble_warn", cn.oremarket.common.RawText.t("oremarket.name.event.14"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.diamond_ebb"))
                .fixedCodes("DIAMOND").chainOnly().fixedTicks(10)
                .effect(EffectSpec.price(0.9, 0.9))
                .build());
        register(EventTemplate.of("chain_profit_close", cn.oremarket.common.RawText.t("oremarket.text.0191"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.mania_cashout"))
                .allTargets().chainOnly().fixedTicks(12)
                .effect(EffectSpec.price(0.95, 0.95))
                .build());
        register(EventTemplate.of("chain_haven_inflow", cn.oremarket.common.RawText.t("oremarket.name.event.19"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.haven_add"))
                .fixedCodes("GOLD").chainOnly().fixedTicks(12)
                .effect(EffectSpec.price(1.15, 1.15))
                .build());

        // ---------------------------------------------------------- 10) conflict watch (after cancellation: market-wide volatility x0.5 for 10 ticks)
        register(EventTemplate.of(CONFLICT_WATCH_ID, cn.oremarket.common.RawText.t("oremarket.name.event.21"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.conflict_watch"))
                .allTargets().chainOnly().fixedTicks(10)
                .effect(EffectSpec.volatility(0.5, 0.5).glob())
                .build());

        // ---------------------------------------------------------- 11) legendary preview card (no effect, 1 tick)
        register(EventTemplate.of(PREVIEW_ID, cn.oremarket.common.RawText.t("oremarket.name.event.22"), Rarity.LEGENDARY)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.legend_preview"))
                .allTargets().chainOnly().fixedTicks(1).preview()
                .build());

        // ---------------------------------------------------------- 12) player-action triggers (triggered only by PlayerVolumeTracker)
        register(EventTemplate.of("player_frenzy", cn.oremarket.common.RawText.t("oremarket.text.0635"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.player_frenzy"))
                .conditionTarget().chainOnly().fixedTicks(20)
                .effect(EffectSpec.price(1.15, 1.15))
                .build());
        register(EventTemplate.of("player_panic", cn.oremarket.common.RawText.t("oremarket.name.event.09"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.player_panic"))
                .conditionTarget().chainOnly().fixedTicks(20)
                .effect(EffectSpec.price(0.85, 0.85))
                .build());

        // ---------------------------------------------------------- 13) fake news 'Market Rumor' (5 fixed templates, no effect, 60 seconds)
        register(EventTemplate.of("rumor_news_coal", cn.oremarket.common.RawText.t("oremarket.name.event.07"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.rumor1")).fixedCodes("COAL").fakeNews().fixedSeconds(60)
                .build());
        register(EventTemplate.of("rumor_news_diamond", cn.oremarket.common.RawText.t("oremarket.name.event.07"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.rumor2")).fixedCodes("DIAMOND").fakeNews().fixedSeconds(60)
                .build());
        register(EventTemplate.of("rumor_news_rate", cn.oremarket.common.RawText.t("oremarket.name.event.07"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.rumor3")).allTargets().fakeNews().fixedSeconds(60)
                .build());
        register(EventTemplate.of("rumor_news_redstone", cn.oremarket.common.RawText.t("oremarket.name.event.07"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.rumor4")).fixedCodes("REDSTONE").fakeNews().fixedSeconds(60)
                .build());
        register(EventTemplate.of("rumor_news_netherite", cn.oremarket.common.RawText.t("oremarket.name.event.07"), Rarity.COMMON)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.rumor5")).fixedCodes("NETHERITE").fakeNews().fixedSeconds(60)
                .build());

        // ---------------------------------------------------------- 14) trigger-once effect events (forced buy / forced sell / matching pause / dividend / split)
        register(EventTemplate.of("inst_force_buy", cn.oremarket.common.RawText.t("oremarket.name.event.12"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.inst_buy"))
                .randomTargets(1, 1).fixedTicks(1)
                .effect(EffectSpec.instant(EffectType.FORCE_BUY, 10, 1000))
                .build());
        register(EventTemplate.of("inst_force_sell", cn.oremarket.common.RawText.t("oremarket.name.event.11"), Rarity.RARE)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.inst_sell"))
                .randomTargets(1, 1).fixedTicks(1)
                .effect(EffectSpec.instant(EffectType.FORCE_SELL, 10, 1000))
                .build());
        register(EventTemplate.of("inst_liquidity_dry", cn.oremarket.common.RawText.t("oremarket.text.0254"), Rarity.EPIC)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.liquidity_dry"))
                .randomTargets(1, 2).fixedTicks(20)
                .effect(EffectSpec.instant(EffectType.LIQUIDITY_DRY, 20, 20))
                .build());
        register(EventTemplate.of("inst_dividend", cn.oremarket.common.RawText.t("oremarket.name.event.04"), Rarity.EPIC)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.dividend"))
                .randomTargets(1, 1).fixedTicks(1)
                .effect(EffectSpec.instant(EffectType.DIVIDEND, 0.1, 10))
                .build());
        register(EventTemplate.of("inst_stock_split", cn.oremarket.common.RawText.t("oremarket.name.event.10"), Rarity.EPIC)
                .desc(cn.oremarket.common.RawText.t("oremarket.event.desc.split"))
                .randomTargets(1, 1).fixedTicks(1)
                .effect(EffectSpec.instant(EffectType.SPLIT, 2, 2))
                .build());
    }

    private EventRegistry() {
    }

    private static void register(EventTemplate t) {
        if (BY_ID.put(t.id, t) == null) {
            ALL.add(t);
        }
    }

    public static EventTemplate byId(String id) {
        if (id == null) {
            return null;
        }
        EventTemplate t = BY_ID.get(id);
        return t != null ? t : CUSTOM_BY_ID.get(id);
    }

    /** All templates, built-in + custom (for the random pool and display). */
    public static List<EventTemplate> all() {
        List<EventTemplate> out = new ArrayList<>(ALL.size() + CUSTOM_BY_ID.size());
        out.addAll(ALL);
        out.addAll(CUSTOM_BY_ID.values());
        return List.copyOf(out);
    }

    /** Special templates backing the original 12 cards. */
    public static EventTemplate legacy(EventCard card) {
        return BY_ID.get("legacy_" + card.id());
    }

    // ------------------------------------------------------------ Custom events (config-registered, replaced wholesale on hot reload)

    /** Config entry -> stable template id (hashed from the event name, so a loaded save finds the same template back). */
    public static String customId(String name) {
        return CUSTOM_ID_PREFIX + Integer.toHexString(name == null ? 0 : name.hashCode());
    }

    /**
     * Replace the custom template table wholesale on hot reload.
     * Active custom event instances keep the old template object until they end ('already running ones last until done');
     * untriggered old templates are pruned here, so the registry itself never bloats.
     */
    public static synchronized void replaceCustoms(List<EventTemplate> templates) {
        CUSTOM_BY_ID.clear();
        if (templates == null) {
            return;
        }
        for (EventTemplate t : templates) {
            if (t != null && t.custom) {
                EventTemplate prev = CUSTOM_BY_ID.putIfAbsent(t.id, t);
                // ids are hashed from event names, so theoretically distinct names can collide (about 1/2^32).
                // On collision the first registration wins with an explicit warning - never silently overwrite (otherwise the later custom
                // event would never fire and loaded saves would fetch a mismatched template). The id hashing scheme stays, so existing saves still resolve.
                if (prev != null && prev != t) {
                    org.slf4j.LoggerFactory.getLogger("OreMarket/Event")
                            .warn("custom event id collision: templates '{}' and '{}' map to id {}; latter skipped (rename one event)",
                                    prev.nameTemplate, t.nameTemplate, t.id);
                }
            }
        }
    }

    public static synchronized boolean isCustomId(String id) {
        return id != null && CUSTOM_BY_ID.containsKey(id);
    }

    /** Set of built-in event names (the 12 fixed card names + all built-in template names): used by the custom-event duplicate-name check. */
    public static synchronized Set<String> builtinNames() {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (EventCard card : EventCard.ORDER) {
            out.add(card.cardName());
        }
        for (EventTemplate t : ALL) {
            out.add(t.nameTemplate);
        }
        return out;
    }
}