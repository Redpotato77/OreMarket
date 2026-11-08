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

import cn.oremarket.market.MarketSettings;
import cn.oremarket.market.ActiveEvent;
import cn.oremarket.market.MarketEngine;
import cn.oremarket.market.MarketSavedData;
import cn.oremarket.common.MarketTime;
import cn.oremarket.market.Stock;
import cn.oremarket.market.log.LogService;
import cn.oremarket.common.LogType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import cn.oremarket.common.Rarity;
import cn.oremarket.common.EffectType;

/**
 * Event pool engine (server-side only):
 *
 * - Timing: each event and each of its effect modules countdown separately; a module that expires first lapses first (Requirement 1);
 * - Dynamic pool: templates whose trigger conditions do not hold are filtered out first, then drawn by rarity weights 60/25/12/3 (Requirements 5, 6);
 * - Frequency: every marketTick tries to trigger with the configured eventTriggerChance (default 3%);
 * if it hits but no template is available or the pool already holds 3 -> this attempt is void, no retry this tick, wait for the next marketTick.
 * - Dedup: templates held by active events are removed from the pool for their duration; at most maxActive events at a time (default 3);
 * - Conflict: if a new event forces the opposite trend on the same stock as an existing one, or suspends a stock already suspended or still cooling down,
 * the whole new event is void and nothing more triggers this tick (Requirement 6);
 * - Chain: child events may trigger during the parent's lifetime by probability, or when it ends by probability; only one level deep (Requirement 4);
 * - Cooldown: legendary events fire once per configured legendCooldownDays (default 3 trading days);
 * the same stock's two suspensions are at least 200 marketTicks apart;
 * - Randomness: everything uses the overworld's unified random source, so all multiplayer players see the same events.
 */
public final class EventEngine {

    /**
     * Legendary-event cooldown (appendix 3): all legendaries share one cooldown -- any legendary suppresses all legendaries for 3 trading days;
     * common, rare and epic are unaffected. The counter lives in SavedData (lastLegendaryDay).
     */
    /** The legendary cooldown now comes from the legendCooldownDays config; this is only the design-default fallback. */
    public static final int LEGENDARY_COOLDOWN_TRADE_DAYS = 3;
    /** Maximum simultaneously active events (kept at 3; the config does not list this separately). */
    /** Deprecated: the cap on concurrent events now uses the eventMaxActive config entry (category 22). */
    @Deprecated
    public static final int MAX_ACTIVE_EVENTS = 3;
    /** Hard cap of the event pool (memory-leak check item 1): built-in + custom + combo + chain templates total at most 200. */
    public static final int EVENT_POOL_MAX = 200;
    /**
     * Minimum gap between two consecutive suspensions of the same stock: 200 marketTicks (about 10 minutes of real time).
     * Suspension itself is timed in marketTicks (the same unit as event durations);
     * had this been plain game ticks it would be only 10 seconds -- shorter than one suspension -- and the rule would never bite.
     */
    /** Deprecated: the suspension/resumption cooldown now uses the eventSuspendCooldownTicks config entry (category 22). */
    @Deprecated
    public static final int SUSPEND_COOLDOWN_MARKET_TICKS = 200;

    /** A candidate template plus the stock codes matched by its conditions. */
    private static final class Candidate {
        final EventTemplate template;
        final String conditionCode;

        Candidate(EventTemplate template, String conditionCode) {
            this.template = template;
            this.conditionCode = conditionCode;
        }
    }

    private EventEngine() {
    }

    // ------------------------------------------------------------ timing

    /**
     * Decrement durations; expired events are removed, and on removal the trigger-on-end chains are settled.
     * Removing an expired event also logs event ended: name for every online player (appendix 5; stays out of chat).
     */
    public static void advanceTimers(MinecraftServer server, MarketSavedData data,
                                     RandomSource rand, Consumer<ActiveEvent> announcer) {

        List<ChainRequest> endChains = new ArrayList<>();
        List<String> ended = new ArrayList<>();
        Iterator<ActiveEvent> it = data.events.iterator();
        while (it.hasNext()) {
            ActiveEvent e = it.next();
            for (ActiveEvent.EffectModule m : e.modules) {
                m.ticksLeft--;
            }
            e.ticksLeft--;
            if (e.ticksLeft <= 0) {
                it.remove();
                data.setDirty();
                ended.add(e.nameOrKey());
                EventTemplate.Chain c = e.template.chain;
                if (c != null && c.onEnd && e.chainDepth == 0) {
                    endChains.add(new ChainRequest(c, e));
                }
            }
        }
        if (server != null) {
            for (String name : ended) {
                LogService.pushAllKey(server, data, LogType.EVENT,
                        "oremarket.event.log.end", name);
            }
        }
        for (ChainRequest r : endChains) {
            triggerChain(server, data, rand, r.chain, r.parent, announcer);
        }
    }

    /** Sustained chains that fire during the parent's lifetime (e.g. chain collapse rolls rescue operation at 2% per tick). */
    public static void fireRunningChains(MinecraftServer server, MarketSavedData data, RandomSource rand, Consumer<ActiveEvent> announcer) {
        List<ChainRequest> due = new ArrayList<>();
        for (ActiveEvent e : data.events) {
            EventTemplate.Chain c = e.template.chain;
            if (e.chainDepth != 0 || c == null || c.onEnd) {
                continue;   // Only one level deep: events with depth >= 1 chain no further
            }
            // Templates are written per second (2% per second etc.), while the engine advances one marketTick every 3 seconds,
            // so convert to the probability of at least one hit within those 3 seconds at the same rate: 1 - (1 - p)^3
            if (rand.nextDouble() < perMarketTickChance(c.chance)) {
                due.add(new ChainRequest(c, e));
            }
        }
        for (ChainRequest r : due) {
            triggerChain(server, data, rand, r.chain, r.parent, announcer);
        }
    }

    /** Convert a per-game-second probability into a per-marketTick (= 3 seconds) probability. */
    private static double perMarketTickChance(double perSecond) {
        double p = Math.max(0.0, Math.min(1.0, perSecond));
        return 1.0 - Math.pow(1.0 - p, MarketSettings.server().marketTickInterval / 20.0);
    }

    private static final class ChainRequest {
        final EventTemplate.Chain chain;
        final ActiveEvent parent;

        ChainRequest(EventTemplate.Chain chain, ActiveEvent parent) {
            this.chain = chain;
            this.parent = parent;
        }
    }

    // ------------------------------------------------------------ drawing new events

    /** Dynamic frequency adjustment (Requirement 2 item 13): triggers in the last 10 marketTicks above the high threshold -> halve the probability for 20 ticks; */
    private static final java.util.ArrayDeque<Integer> TRIGGER_BUCKETS = new java.util.ArrayDeque<>();
    private static long bucketTick = Long.MIN_VALUE;
    private static long halfChanceUntilTick = Long.MIN_VALUE;
    private static long doubleChanceUntilTick = Long.MIN_VALUE;
    /** At most one combo per marketTick (Requirement 2 item 3). */
    private static boolean comboFiredThisTick = false;

    /** Called once at the head of the event phase every marketTick: rolls the frequency window. */
    private static void beginTickWindow(long marketTick) {
        // Combo-gate fix: within one marketTick this method is called by both tickScheduled and tryTriggerRandom,
        // so the reset must live in the new-window branch -- the old unconditional reset cleared a combo already
        // fired by tickScheduled, letting a second combo fire in the same marketTick (double event-pool pressure per tick).
        if (bucketTick == marketTick) {
            return;
        }
        comboFiredThisTick = false;
        bucketTick = marketTick;
        TRIGGER_BUCKETS.addLast(0);
        while (TRIGGER_BUCKETS.size() > 20) {
            TRIGGER_BUCKETS.removeFirst();
        }
        MarketSettings em = MarketSettings.server();
        if (em.eventFrequencyDynamicEnabled) {
            int last10 = sumBuckets(10);
            int last20 = sumBuckets(20);
            if (last10 > em.eventFrequencyHighThreshold) {
                halfChanceUntilTick = marketTick + 20;
            }
            if (last20 <= em.eventFrequencyLowThreshold) {
                doubleChanceUntilTick = marketTick + 20;
            }
        }
    }

    private static int sumBuckets(int n) {
        int sum = 0;
        int seen = 0;
        java.util.Iterator<Integer> it = TRIGGER_BUCKETS.descendingIterator();
        while (it.hasNext() && seen++ < n) {
            sum += it.next();
        }
        return sum;
    }

    /** Effective trigger probability this tick (base probability x dynamic factor). */
    static double effectiveTriggerChance(MarketSettings em, long marketTick) {
        double p = em.eventTriggerChance;
        if (em.eventFrequencyDynamicEnabled) {
            if (marketTick <= halfChanceUntilTick) {
                p *= 0.5;
            } else if (marketTick <= doubleChanceUntilTick) {
                p *= 2.0;
            }
        }
        return Math.max(0.0, Math.min(1.0, p));
    }

    /**
     * Server shutdown: wipe all static state of the frequency window.
     * These are absolute marketTick counters -- without clearing, marketTicks restart at 0 in a new save,
     * so the old world's half/double verdicts would poison the first few thousand ticks of the new world's
     * trigger probability, and old trigger counts would leak into the rolling window.
     */
    public static void clearAll() {
        TRIGGER_BUCKETS.clear();
        bucketTick = Long.MIN_VALUE;
        halfChanceUntilTick = Long.MIN_VALUE;
        doubleChanceUntilTick = Long.MIN_VALUE;
        comboFiredThisTick = false;
    }

    /**
     * Scheduled tasks (called every marketTick before the random draw):
     * 1) deterministic chains (parent reaches its Nth tick); 2) legendary forecasts mature;
     * 3) player-behaviour triggers (rolling-window totals exceed limits); 4) combo checks and fired-key pruning.
     */
    public static void tickScheduled(MinecraftServer server, MarketSavedData data,
                                     RandomSource rand, Consumer<ActiveEvent> announcer) {
        beginTickWindow(data.marketTicks);
        for (ActiveEvent e : EventChainTable.tick(data)) {
            commit(server, data, e, announcer);
        }
        EventPreviewService.Pending pending = EventPreviewService.pollDue(data.marketTicks);
        if (pending != null) {
            EventTemplate tpl = EventRegistry.byId(pending.templateId);
            if (tpl != null && acceptsRaw(tpl, data)) {
                ActiveEvent e = ActiveEvent.spawn(tpl, rand, MarketSettings.server(), null,
                        pending.conditionCode, data.nextEventUid(), 0);
                if (e != null && accepts(e, data)) {
                    commit(server, data, e, announcer);
                }
            }
        }
        for (ActiveEvent e : PlayerVolumeTracker.evaluate(data)) {
            commit(server, data, e, announcer);
        }
        tryFireCombo(server, data, announcer);
        EventComboTable.pruneFiredKeys(data);
    }

    /** Combo check (at most one per marketTick; the first table row whose condition holds wins). */
    private static void tryFireCombo(MinecraftServer server, MarketSavedData data,
                                     Consumer<ActiveEvent> announcer) {
        if (comboFiredThisTick) {
            return;
        }
        ActiveEvent combo = EventComboTable.fireFirstMatch(data, server);
        if (combo != null) {
            comboFiredThisTick = true;
            commit(server, data, combo, announcer);
        }
    }

    /** Try to trigger one new event by probability; silently skips this tick when conditions fail, the pool is empty or the draw conflicts. */
    public static void tryTriggerRandom(MinecraftServer server, MarketSavedData data,
                                        RandomSource rand, Consumer<ActiveEvent> announcer) {
        MarketSettings em = MarketSettings.server();
        beginTickWindow(data.marketTicks);
        if (data.events.size() >= maxActive(em)
                || rand.nextDouble() >= effectiveTriggerChance(em, data.marketTicks)) {
            return;
        }
        List<Candidate> pool = candidates(data, em);
        if (pool.isEmpty()) {
            return;
        }
        Candidate c = weightedPick(pool, rand);
        if (c == null) {
            return;   // All rarity weights are 0: no trigger this tick
        }
        // Legendary event: show the forecast card first (80% comes true, 20% is misinformation); the body does not trigger this tick
        if (c.template.rarity == Rarity.LEGENDARY && em.eventPreviewEnabled) {
            ActiveEvent preview = EventPreviewService.schedule(data, c.template, c.conditionCode, rand);
            if (preview != null) {
                commit(server, data, preview, announcer);
                return;
            }
        }
        ActiveEvent draft = ActiveEvent.spawn(c.template, rand, em, null, c.conditionCode,
                data.nextEventUid(), 0);
        if (draft == null || !accepts(draft, data)) {
            return;   // Effect conflict: cancel this tick, no redraw
        }
        commit(server, data, draft, announcer);
    }

    /** Command-forced trigger: skips probability and pool filtering, but conflicts and cooldowns still apply. */
    public static ActiveEvent forceTrigger(MinecraftServer server, MarketSavedData data,
                                           EventTemplate tpl, RandomSource rand,
                                           Consumer<ActiveEvent> announcer) {
        MarketSettings em = MarketSettings.server();
        String condCode = null;
        if (tpl.condition != null) {
            EventCondition.Result r = tpl.condition.test(data);
            condCode = r.code;
        }
        ActiveEvent draft = ActiveEvent.spawn(tpl, rand, em, null, condCode, data.nextEventUid(), 0);
        if (draft == null || !accepts(draft, data)) {
            return null;
        }
        commit(server, data, draft, announcer);
        return draft;
    }

    private static void triggerChain(MinecraftServer server, MarketSavedData data, RandomSource rand,
                                     EventTemplate.Chain chain,
                                     ActiveEvent parent, Consumer<ActiveEvent> announcer) {
        MarketSettings em = MarketSettings.server();
        EventTemplate child = EventRegistry.byId(chain.childId);
        if (child == null || data.events.size() >= maxActive(em)) {
            return;
        }
        if (isTemplateActive(data, child.id)) {
            return;
        }
        if (child.rarity == Rarity.LEGENDARY && !legendaryReady(data)) {
            return;
        }
        String condCode = null;
        if (child.condition != null) {
            EventCondition.Result r = child.condition.test(data);
            if (!r.met) {
                return;
            }
            condCode = r.code;
        }
        java.util.Set<String> inherit = chain.sameTargets ? parent.targets : null;
        ActiveEvent draft = ActiveEvent.spawn(child, rand, em, inherit, condCode,
                data.nextEventUid(), parent.chainDepth + 1);
        if (draft == null || !accepts(draft, data)) {
            return;
        }
        if (server != null) {
            LogService.pushAllKey(server, data, LogType.EVENT,
                    "oremarket.event.log.chain", parent.nameOrKey(), draft.nameOrKey());
        }
        commit(server, data, draft, announcer);
    }

    // ------------------------------------------------------------ pool filtering

    private static List<Candidate> candidates(MarketSavedData data, MarketSettings em) {
        List<Candidate> out = new ArrayList<>();
        boolean legendaryReady = legendaryReady(data);
        Set<String> activeIds = new HashSet<>();
        for (ActiveEvent e : data.events) {
            activeIds.add(e.template.id);
        }
        for (EventTemplate t : EventRegistry.all()) {
            if (t.chainOnly) {
                continue;                       // Chain-only templates never enter the random pool
            }
            if (t.fakeNews && !em.eventFakeNewsEnabled) {
                continue;                       // Market rumour misinformation is gated by its config switch
            }
            if (activeIds.contains(t.id)) {
                continue;                       // Templates of an event type currently active are removed from the pool
            }
            if (t.rarity == Rarity.LEGENDARY && !legendaryReady) {
                continue;                       // 3-trading-day legendary cooldown
            }
            if (!t.hasActiveTarget()) {
                continue;                       // Fixed/scope-limited templates must not create empty events
            }
            if (t.condition != null) {
                EventCondition.Result r = t.condition.test(data);
                if (!r.met) {
                    continue;                   // Templates whose conditions do not hold must never enter the pool
                }
                out.add(new Candidate(t, r.code));
            } else {
                out.add(new Candidate(t, null));
            }
        }
        return out;
    }

    /** Weighted draw of event rarities; these weights come from category 22 and do not affect loan limits. Weight 0 = that rarity never participates. */
    private static Candidate weightedPick(List<Candidate> pool, RandomSource rand) {
        int total = 0;
        for (Candidate c : pool) {
            total += Math.max(0, c.template.rarity.weight);
        }
        if (total <= 0) {
            return null;    // Every rarity weight is 0: no trigger this tick
        }
        int roll = rand.nextInt(total);
        for (Candidate c : pool) {
            roll -= Math.max(0, c.template.rarity.weight);
            if (roll < 0) {
                return c;
            }
        }
        return pool.get(pool.size() - 1);
    }

    // ------------------------------------------------------------ conflicts and commit

    /**
     * Template-level coarse check (no-draft case: before a forecast matures, judge whether it could even enter the pool).
     * The forecast card claims 80% accuracy and the full accepts() runs only at maturity; here only the three hard gates:
     * 1) same-template dedup (candidates() already applies it to the random pool; chains and forecasts follow suit);
     * 2) legendary cooldown (LEGENDARY before its release day);
     * 3) event pool full (the 200-entry hard cap).
     */
    static boolean acceptsRaw(EventTemplate tpl, MarketSavedData data) {
        if (tpl == null || data == null) {
            return false;
        }
        if (data.events.size() >= EVENT_POOL_MAX) {
            return false;
        }
        if (tpl.rarity == Rarity.LEGENDARY && !legendaryReady(data)) {
            return false;
        }
        for (ActiveEvent existing : data.events) {
            if (existing.template.id.equals(tpl.id)) {
                return false;
            }
        }
        return true;
    }

    /** Conflicts with existing events: opposite forced trend, duplicate suspension (already suspended or cooling down), legendary cooldown. */
    private static boolean accepts(ActiveEvent draft, MarketSavedData data) {
        if (draft.rarity == Rarity.LEGENDARY && !legendaryReady(data)) {
            return false;
        }
        for (ActiveEvent existing : data.events) {
            for (ActiveEvent.EffectModule a : draft.modules) {
                if (a.expired()) {
                    continue;
                }
                for (ActiveEvent.EffectModule b : existing.modules) {
                    if (b.expired()) {
                        continue;
                    }
                    if (a.type == EffectType.TREND && b.type == EffectType.TREND && a.direction != b.direction) {
                        if (overlaps(a, b)) {
                            return false;       // The same stock forced up and down at once
                        }
                    }
                    if (a.type == EffectType.SUSPEND && b.type == EffectType.SUSPEND && overlaps(a, b)) {
                        return false;           // A stock already suspended is not suspended again
                    }
                }
            }
        }
        // Suspension cooldown: if any target is still inside its 200-tick cooldown, void the whole event
        for (ActiveEvent.EffectModule m : draft.modules) {
            if (m.type == EffectType.SUSPEND) {
                for (String code : draft.targets) {
                    if (suspendCooling(data, code)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean overlaps(ActiveEvent.EffectModule a, ActiveEvent.EffectModule b) {
        if (a.global && b.global) {
            return true;
        }
        // Coverage overlap: compared stock code by stock code (the registry grows dynamically; there is no fixed 8-bit mask)
        for (String code : ActiveEvent.activeCodes()) {
            if (a.affects(code) && b.affects(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Commit the event into the pool:
     * 1) conflict-annulment ruling (Part 4 item 6: direct contradiction -> both cancelled + wait-and-see + log);
     * 2) event pool hard cap 200 (memory-leak check item 1: beyond it, drop the oldest);
     * 3) register suspension cooldowns and stamp the legendary cooldown day;
     * 4) settle trigger-type effects (forced buy / forced sell / dividend / split);
     * 5) broadcast (log + event particles; particles ride an S2C packet, the server no longer calls sendParticles directly);
     * 6) combo check (checked only on trigger, at most one per tick).
     */
    private static void commit(MinecraftServer server, MarketSavedData data, ActiveEvent event,
                               Consumer<ActiveEvent> announcer) {
        if (!event.template.preview && !event.template.fakeNews
                && EventConflictResolver.resolveAgainst(server, data, event,
                        w -> commitRaw(server, data, w, announcer))) {
            return;   // Direct contradiction: cancel both old and new; the draft never enters the pool
        }
        commitRaw(server, data, event, announcer);
    }

    private static void commitRaw(MinecraftServer server, MarketSavedData data, ActiveEvent event,
                                  Consumer<ActiveEvent> announcer) {
        while (data.events.size() >= EVENT_POOL_MAX) {
            ActiveEvent oldest = data.events.remove(0);   // Pool full: drop the oldest entry
        }
        data.events.add(event);
        if (!TRIGGER_BUCKETS.isEmpty()) {
            TRIGGER_BUCKETS.addLast(TRIGGER_BUCKETS.removeLast() + 1);
        }
        if (event.rarity == Rarity.LEGENDARY && !event.template.preview) {
            data.lastLegendaryDay = data.tradeDay();
            // The legendary-cooldown notice left the event bar; it is now a single system log at the triggering instant (written once only;
            // nothing more during or after the cooldown, and the event bar no longer spends height on it).
            if (MarketSettings.server().eventLegendCooldownHintEnabled) {
                LogService.pushAllKey(server, data, LogType.SYSTEM,
                        "oremarket.event.log.legend_cooldown",
                        String.valueOf(MarketSettings.server().legendCooldownDays));
            }
        }
        for (ActiveEvent.EffectModule m : event.modules) {
            if (m.type == EffectType.SUSPEND) {
                for (String code : event.targets) {
                    // Suspension cooldown counted in marketTicks, keyed by stock code strings
                    data.markSuspended(code, data.marketTicks);
                }
            }
        }
        data.setDirty();
        if (!event.template.preview) {
            EventEffectsService.executeInstant(server, data, event);
        }
        if (announcer != null) {
            announcer.accept(event);
        }
        tryFireCombo(server, data, announcer);
    }

    private static boolean isTemplateActive(MarketSavedData data, String templateId) {
        for (ActiveEvent e : data.events) {
            if (e.template.id.equals(templateId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean legendaryReady(MarketSavedData data) {
        return data.tradeDay() - data.lastLegendaryDay >= MarketSettings.server().legendCooldownDays;
    }

    private static boolean suspendCooling(MarketSavedData data, String code) {
        long last = data.lastSuspendTick(code);
        return last >= 0 && data.marketTicks - last < MarketSettings.server().eventSuspendCooldownTicks;
    }

    private static int maxActive(MarketSettings em) {
        return em.eventMaxActive;
    }

    /** Currently suspended? (for trade validation): TradeService queries the live suspension modules directly by stock code. */
    public static int suspendTicksLeft(MarketSavedData data, String code) {
        int left = 0;
        for (ActiveEvent e : data.events) {
            for (ActiveEvent.EffectModule m : e.modules) {
                if (m.type == EffectType.SUSPEND && !m.expired() && m.affects(code)) {
                    left = Math.max(left, m.ticksLeft);
                }
            }
        }
        return left;
    }

    /** Remaining suspension queried by Stock object (internally keyed by the code string). */
    public static int suspendTicksLeft(MarketSavedData data, Stock s) {
        return s == null ? 0 : suspendTicksLeft(data, s.code());
    }

    /**
     * When a stock is delisted, immediately end every event affecting it: remove the code from every
     * event's and module's targets; an event left with no targets and no global module ends entirely. Returns how many ended.
     */
    public static int endEventsAffecting(MarketSavedData data, String code) {
        PlayerVolumeTracker.onStockRetired(code);
        int ended = 0;
        java.util.Iterator<ActiveEvent> it = data.events.iterator();
        while (it.hasNext()) {
            ActiveEvent e = it.next();
            boolean touched = e.targets.remove(code);
            for (ActiveEvent.EffectModule m : e.modules) {
                touched |= m.targets.remove(code);
            }
            if (touched) {
                boolean stillEffective = e.targets.isEmpty();
                if (stillEffective) {
                    for (ActiveEvent.EffectModule m : e.modules) {
                        if (m.global && !m.expired()) {
                            stillEffective = false;
                            break;
                        }
                    }
                }
                if (stillEffective) {
                    it.remove();
                    ended++;
                }
                data.setDirty();
            }
        }
        return ended;
    }

    // ------------------------------------------------------------ broadcast

    /**
     * Event-trigger broadcast (appendix 5/7): nothing goes to chat anymore; each online player gets a purple event-type log
     * instead: event triggered with name, affected stocks, effect abbreviation and duration X minutes.
     * Above-head particle effects stay (purely visual, not a chat message).
     */
    public static void announce(MinecraftServer server, MarketSavedData data, ActiveEvent event) {
        if (event == null) {
            return;   // Null-pointer check item 2: any entry receiving a null event silently skips
        }
        if (event.template != null && event.template.preview) {
            // The legendary forecast card never uses the event-triggered broadcast (it has no real effect): atmosphere log only, no particles
            LogService.pushAllKey(server, data, LogType.EVENT,
                    "oremarket.event.log.atmosphere");
            return;
        }
        LogService.pushAllKey(server, data, LogType.EVENT,
                "oremarket.event.log.trigger",
                event.nameOrKey(), event.targetNames(), event.effectSummary(),
                MarketTime.human(event.duration));
        // Particles now ride the config-driven S2C packet (Part 3 item 5): type, count and interval all configurable.
        cn.oremarket.net.ParticleService.playEventAll(server, data);
    }
}