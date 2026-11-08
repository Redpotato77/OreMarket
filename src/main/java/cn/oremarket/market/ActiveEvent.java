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

import cn.oremarket.common.EffectSpec;
import cn.oremarket.common.EffectType;
import cn.oremarket.market.event.EventRegistry;
import cn.oremarket.market.event.EventTemplate;
import cn.oremarket.common.Rarity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import cn.oremarket.common.MarketTime;
import java.util.Locale;

/**
 * A live event instance: generated from a template plus the server random source (requirement 3),
 * composed of one or more effect modules, each timed independently (requirement 1).
 * Every figure is clamped into its allowed range at generation; the client only gets display text and finished numbers (requirement 9).
 *
 * Targets moved from EnumSet<Stock> / bit mask to a SET OF STOCK CODE STRINGS (Set<String>),
 * persisted as ListTag of StringTag; on load, an old IntTag bit mask (DataVersion < 2 migration)
 * is converted to codes via the built-in-8 index mapping.
 *
 * The original 12 fixed event cards come in as special templates: names and price effects stay bit-identical with the old version.
 */
public final class ActiveEvent {

    /** One runtime effect module. */
    public static final class EffectModule {
        public final EffectType type;
        /** true = applies to all stocks (e.g. a global fee change). */
        public final boolean global;
        /** true = the parameter is an OVERRIDE (FEE / PRICE_LIMIT / REVERSION); false = increment or multiplier. */
        public final boolean absolute;
        /** Target stock code set (code strings - neither enums nor bit masks). */
        public final Set<String> targets;
        /** The clamped parameter; TREND uses direction instead. */
        public final double param;
        public final int direction;
        public int duration;
        public int ticksLeft;
        /** Actual one-shot result used by the announcement and tooltip; NaN means legacy/unknown. */
        public double realizedValue = Double.NaN;

        EffectModule(EffectType type, boolean global, boolean absolute, Set<String> targets,
                     double param, int direction, int duration) {
            this.type = type;
            this.global = global;
            this.absolute = absolute;
            this.targets = targets == null ? new LinkedHashSet<>() : new LinkedHashSet<>(targets);
            this.param = param;
            this.direction = direction;
            this.duration = Math.max(1, duration);
            this.ticksLeft = this.duration;
        }

        /** The 'override' semantics switch for FEE / PRICE_LIMIT. */
        public boolean absoluteSet() {
            return absolute;
        }

        public boolean affects(String code) {
            return global || (code != null && targets.contains(code));
        }

        public boolean expired() {
            return ticksLeft <= 0;
        }
    }

    public final EventTemplate template;
    public final String displayName;
    public final String description;
    /**
     * Name / description translation keys (e.g. oremarket.text.0569). An empty string means a custom event or user raw text;
     * the client fetches the current-language template by key and fills {stock} - English UIs no longer reverse-look-up Chinese sentences.
     */
    public final String nameKey;
    public final String descKey;
    public final Rarity rarity;
    /** Event targets: a set of stock code strings. */
    public final Set<String> targets;
    public final List<EffectModule> modules;
    public int duration;
    public final int uid;
    /** 0 = normal event, 1 = triggered by a chain (chains no further, preventing nesting explosions). */
    public final int chainDepth;
    public int ticksLeft;
    /** Contributor display names for player-action-triggered events ("Player1, Player2, Player3"); null for all others. */
    public String contributorNames;

    private ActiveEvent(EventTemplate template, String displayName, String description,
                        Set<String> targets, List<EffectModule> modules,
                        int duration, int uid, int chainDepth) {
        this(template, displayName, description,
                keyOf(template == null ? null : template.nameTemplate),
                keyOf(template == null ? null : template.descriptionTemplate),
                targets, modules, duration, uid, chainDepth);
    }

    private ActiveEvent(EventTemplate template, String displayName, String description,
                        String nameKey, String descKey,
                        Set<String> targets, List<EffectModule> modules,
                        int duration, int uid, int chainDepth) {
        this.template = template;
        this.displayName = displayName;
        this.description = description;
        this.nameKey = nameKey == null ? "" : nameKey;
        this.descKey = descKey == null ? "" : descKey;
        this.rarity = template.rarity;
        this.targets = targets == null ? new LinkedHashSet<>() : new LinkedHashSet<>(targets);
        this.modules = modules;
        this.duration = Math.max(1, duration);
        this.ticksLeft = this.duration;
        this.uid = uid;
        this.chainDepth = chainDepth;
    }

    /**
     * For log / feedback arguments: when the name template contains no {stock}, ship the translation key and the client looks it up;
     * for names WITH {stock} (e.g. 'Coal Rumor'), ship the already-filled raw text and the overlay translates the whole sentence.
     * Custom events without a key ship their raw text.
     */
    public String nameOrKey() {
        if (nameKey == null || nameKey.isEmpty()) {
            return displayName;
        }
        String tpl = cn.oremarket.common.RawText.t(nameKey);
        if (tpl != null && tpl.contains("{stock}")) {
            return displayName;
        }
        return nameKey;
    }

    // ------------------------------------------------------------ Generation

    /**
     * Generate a concrete event from a template.
     *
     * @param forcedTargets passed when a chained event inherits the parent's targets; null otherwise
     * @param conditionCode the stock code hit by the condition (used by the CONDITION_STOCK target mode); may be null
     */
    public static ActiveEvent spawn(EventTemplate tpl, RandomSource rand, MarketSettings ms,
                                    Set<String> forcedTargets, String conditionCode,
                                    int uid, int chainDepth) {
        // 1) overall event duration: the config gives SECONDS (category 8); converted here to marketTicks via marketTickInterval.
        // Formula: marketTicks = ceil(seconds x 20 / marketTickInterval), minimum 1.
        // The old 12 cards take the same path (names and effects preserved verbatim; durations now drawn from the rarity range).
        // When the template declares fixedSeconds (the 60-second fake news etc.), the fixed seconds take conversion priority.
        int duration;
        if (tpl.fixedTicks > 0) {
            duration = tpl.fixedTicks;
        } else if (tpl.fixedSeconds > 0) {
            duration = ms.secondsToMarketTicks(tpl.fixedSeconds);
        } else {
            int[] secRange = ms.durationSecondsRange(tpl.rarity);
            duration = ms.secondsToMarketTicks(randRange(rand, secRange[0], secRange[1]));
            duration = Math.max(ms.secondsToMarketTicks(secRange[0]),
                    Math.min(ms.secondsToMarketTicks(secRange[1]), duration));
        }

        // 2) target stocks: random draws come from the template's eligible active scope.
        Set<String> targets = pickTargets(tpl, rand, forcedTargets, conditionCode);
        if (targets.isEmpty()) {
            return null;
        }
        String repCode = targets.iterator().next();
        Stock rep = StockRegistry.server().getByCode(repCode);

        // 3) effect modules: per module draw the parameter -> clamp into category 9's configured range -> set its own duration
        List<EffectModule> modules = new ArrayList<>();
        for (EffectSpec spec : tpl.effects) {
            double param;
            int dir = spec.direction;
            double[] range = ms.effectRange(spec.type);
            if (tpl.isLegacy() && spec.type == EffectType.PRICE_MULT) {
                // old formula: cumulative change / period, then the old +-15% single-tick cap
                param = MarketSavedData.clampEventMultiplier(tpl.legacyCard.perTickMultiplier(duration, 1.0));
            } else {
                param = randRange(rand, spec.min, spec.max);
                if (spec.type == EffectType.TREND && dir == 0) {
                    dir = rand.nextBoolean() ? 1 : -1;
                }
            }
            // category 9: every event effect value is clamped into the admin-set range
            param = spec.type.clampParam(param);
            param = Math.max(range[0], Math.min(range[1], param));
            // one module's duration: the template's declared range applies when given (also converted from seconds); otherwise it follows the event's overall duration
            int modDur = spec.durMin > 0
                    ? ms.secondsToMarketTicks(randRange(rand, spec.durMin, spec.durMax))
                    : duration;
            // category 8 gives each timed effect family its own hard cap:
            // suspensions use eventHaltMaxSeconds; price-limit effects use eventPriceLimitMaxSeconds.
            int cap = spec.type == EffectType.SUSPEND
                    ? Math.max(1, ms.haltMaxMarketTicks())
                    : cappedModuleDuration(spec.type, modDur);
            modDur = Math.max(1, Math.min(cap, modDur));
            modules.add(new EffectModule(spec.type, spec.global, spec.absolute, targets, param, dir, modDur));
        }

        String name = tpl.name(rep);
        String desc = tpl.description(rep);
        return new ActiveEvent(tpl, name, desc, targets, modules, duration, uid, chainDepth);
    }

    private static Set<String> pickTargets(EventTemplate tpl, RandomSource rand,
                                           Set<String> forcedTargets, String conditionCode) {
        switch (tpl.targetMode) {
            case ALL:
                return activeCodes(tpl.targetScope);
            case FIXED:
                // A fixed template only retains currently active, scope-compatible targets; an empty result is rejected before commit.
                if (tpl.fixedTargets.isEmpty()) {
                    return activeCodes(tpl.targetScope);
                }
                Set<String> fixed = new LinkedHashSet<>();
                for (String code : tpl.fixedTargets) {
                    Stock stock = StockRegistry.server().getByCode(code);
                    if (tpl.allowsCode(code) && stock != null && !stock.retired()) {
                        fixed.add(code);
                    }
                }
                return fixed;
            case SAME_AS_PARENT:
                if (forcedTargets != null && !forcedTargets.isEmpty()) {
                    Set<String> inherited = new LinkedHashSet<>();
                    for (String code : forcedTargets) {
                        Stock stock = StockRegistry.server().getByCode(code);
                        if (tpl.allowsCode(code) && stock != null && !stock.retired()) {
                            inherited.add(code);
                        }
                    }
                    if (!inherited.isEmpty()) {
                        return inherited;
                    }
                }
                return randomPick(tpl.targetScope, rand, 1, 2);
            case CONDITION_STOCK:
                Stock conditionStock = conditionCode == null ? null : StockRegistry.server().getByCode(conditionCode);
                if (conditionCode != null && tpl.allowsCode(conditionCode)
                        && conditionStock != null && !conditionStock.retired()) {
                    Set<String> one = new LinkedHashSet<>();
                    one.add(conditionCode);
                    return one;
                }
                return randomPick(tpl.targetScope, rand, 1, 1);
            case RANDOM:
            default:
                return randomPick(tpl.targetScope, rand, tpl.targetMin, tpl.targetMax);
        }
    }

    /** Active stock codes of the current server registry (built-ins first, customs after; delisted excluded). */
    public static Set<String> activeCodes() {
        return activeCodes(EventTemplate.TargetScope.ACTIVE);
    }

    /** Active codes allowed by a template scope. */
    public static Set<String> activeCodes(EventTemplate.TargetScope scope) {
        Set<String> codes = new LinkedHashSet<>();
        for (Stock s : StockRegistry.getActiveStocks()) {
            if (scope == null || scope == EventTemplate.TargetScope.ACTIVE
                    || Stock.BUILTIN_CODES.contains(s.code())) {
                codes.add(s.code());
            }
        }
        return codes;
    }

    /** Fisher-Yates draws n distinct stocks from the eligible active list (via the unified server random source). */
    private static Set<String> randomPick(EventTemplate.TargetScope scope, RandomSource rand, int lo, int hi) {
        int span = Math.max(0, hi - lo);
        int n = lo + (span == 0 ? 0 : rand.nextInt(span + 1));
        List<String> pool = new ArrayList<>(activeCodes(scope));
        if (pool.isEmpty()) {
            return new LinkedHashSet<>();
        }
        n = Math.max(1, Math.min(pool.size(), n));
        for (int i = pool.size() - 1; i > 0; i--) {
            int j = rand.nextInt(i + 1);
            String tmp = pool.get(i);
            pool.set(i, pool.get(j));
            pool.set(j, tmp);
        }
        return new LinkedHashSet<>(pool.subList(0, n));
    }

    private static double randRange(RandomSource rand, double lo, double hi) {
        if (hi <= lo) {
            return lo;
        }
        return lo + rand.nextDouble() * (hi - lo);
    }

    private static int cappedModuleDuration(EffectType type, int requested) {
        int safeRequested = Math.max(1, requested);
        if (type == EffectType.PRICE_LIMIT) {
            return Math.min(safeRequested,
                    Math.max(1, MarketSettings.server().priceLimitMaxMarketTicks()));
        }
        return safeRequested;
    }

    /**
     * Immediate event generation with fixed parameters (combos / chains / conflict watch / legendary preview / player triggers all use it):
     * effect parameters are taken straight from the template's declared values (min == max) - NO random draw and NO category-9 range clamping -
     * and the duration is the marketTick count given by the caller, so the design table's figures land bit-exactly.
     *
     * @param targets       target code set; for SAME_AS_PARENT templates null/empty falls back to forcedTargets, then to the whole market
     * @param forcedTargets the parent event's targets (chain inheritance), may be null
     */
    public static ActiveEvent spawnFixed(EventTemplate tpl, Set<String> targets, int durationTicks,
                                         Set<String> forcedTargets, int uid, int chainDepth) {
        Set<String> eff = resolveFixedTargets(tpl, targets, forcedTargets);
        if (eff.isEmpty()) {
            return null;
        }
        int duration = Math.max(1, durationTicks);
        List<EffectModule> modules = new ArrayList<>();
        for (EffectSpec spec : tpl.effects) {
            int moduleDuration = cappedModuleDuration(spec.type, duration);
            double param = spec.type == EffectType.SUSPEND ? moduleDuration : spec.min;
            modules.add(new EffectModule(spec.type, spec.global, spec.absolute, eff,
                    param, spec.direction, moduleDuration));
        }
        return new ActiveEvent(tpl, tpl.name(representative(eff)), tpl.description(representative(eff)),
                eff, modules, duration, uid, chainDepth);
    }

    /** Fixed generation with a module plan and a name override (for combo events where different modules hit different stocks, e.g. 'Luxury Divergence'). */
    public static ActiveEvent spawnFixed(EventTemplate tpl, Set<String> eventTargets, int durationTicks,
                                         List<ModulePlan> plans, int uid, int chainDepth,
                                         String nameOverride) {
        Set<String> eff = resolveFixedTargets(tpl, eventTargets, null);
        if (eff.isEmpty() && !tpl.preview) {
            return null;
        }
        int duration = Math.max(1, durationTicks);
        List<EffectModule> modules = new ArrayList<>();
        // preview cards and other effect-less callers pass a null module plan: treat as zero modules (fixes the guaranteed legendary-preview NPE)
        if (plans != null) {
            for (ModulePlan plan : plans) {
                Set<String> targets = plan.targets == null || plan.targets.isEmpty() ? eff : plan.targets;
                modules.add(new EffectModule(plan.type, plan.global, plan.absolute, targets,
                        plan.param, plan.direction, cappedModuleDuration(plan.type, duration)));
            }
        }
        String name;
        String nKey;
        if (nameOverride != null && !nameOverride.isEmpty()) {
            name = nameOverride;
            nKey = keyOf(nameOverride);
        } else {
            name = tpl.name(representative(eff));
            nKey = keyOf(tpl.nameTemplate);
        }
        return new ActiveEvent(tpl, name, tpl.description(representative(eff)),
                nKey, keyOf(tpl.descriptionTemplate),
                eff, modules, duration, uid, chainDepth);
    }

    private static Set<String> resolveFixedTargets(EventTemplate tpl, Set<String> targets,
                                                   Set<String> forcedTargets) {
        Set<String> source;
        if (targets != null && !targets.isEmpty()) {
            source = targets;
        } else if (forcedTargets != null && !forcedTargets.isEmpty()) {
            source = forcedTargets;
        } else {
            source = activeCodes(tpl.targetScope);
        }
        Set<String> out = new LinkedHashSet<>();
        for (String code : source) {
            Stock stock = StockRegistry.server().getByCode(code);
            if (tpl.allowsCode(code) && stock != null && !stock.retired()) {
                out.add(code);
            }
        }
        return out;
    }

    private static Stock representative(Set<String> codes) {
        return codes == null || codes.isEmpty()
                ? null : StockRegistry.server().getByCode(codes.iterator().next());
    }

    /** One module plan for fixed generation (its target may differ from the event's overall targets). */
    public static final class ModulePlan {
        public final EffectType type;
        public final boolean global;
        public final boolean absolute;
        public final Set<String> targets;
        public final double param;
        public final int direction;

        public ModulePlan(EffectType type, boolean global, boolean absolute, Set<String> targets,
                          double param, int direction) {
            if (type == null || targets == null) {
                throw new IllegalArgumentException("module plan needs type & targets");
            }
            this.type = type;
            this.global = global;
            this.absolute = absolute;
            this.targets = targets;
            this.param = param;
            this.direction = direction;
        }

        /** A multiplier module scoped to the given stocks only. */
        public static ModulePlan ofCodes(EffectType type, Set<String> targets, double param) {
            return new ModulePlan(type, false, false, targets, param, 0);
        }

        /** A market-wide module (volatility etc.). */
        public static ModulePlan global(EffectType type, double param) {
            return new ModulePlan(type, true, false, null, param, 0);
        }
    }

    /**
     * Converts this active event and every effect module to a new market-tick time basis.
     * Stored durations are marketTicks, while their real duration is marketTicks * interval / 20 seconds.
     */
    public void rescaleTimeBasis(int oldInterval, int newInterval) {
        if (oldInterval <= 0 || newInterval <= 0 || oldInterval == newInterval) {
            return;
        }
        duration = scaleTicks(duration, oldInterval, newInterval);
        ticksLeft = Math.max(0, Math.min(duration, scaleTicks(ticksLeft, oldInterval, newInterval)));
        for (EffectModule module : modules) {
            module.duration = scaleTicks(module.duration, oldInterval, newInterval);
            module.ticksLeft = Math.max(0, Math.min(module.duration,
                    scaleTicks(module.ticksLeft, oldInterval, newInterval)));
        }
    }

    private static int scaleTicks(int ticks, int oldInterval, int newInterval) {
        if (ticks <= 0) {
            return 0;
        }
        double scaled = Math.ceil((double) ticks * oldInterval / newInterval);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, scaled));
    }

    /** marketTicks the event has been running (chain rules judge 'at its N-th tick' from this). */
    public int elapsed() {
        return Math.max(0, duration - ticksLeft);
    }

    /** Whether it contains trigger-once effect modules (forced buy/sell / dividend / split: executed once at commit). */
    public boolean hasInstantEffects() {
        for (EffectModule m : modules) {
            if (m.type == EffectType.FORCE_BUY || m.type == EffectType.FORCE_SELL
                    || m.type == EffectType.DIVIDEND || m.type == EffectType.SPLIT) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ Queries

    /** Whether the event (as a whole or via some global module) affects the given stock code. */
    public boolean affects(String code) {
        if (code != null && targets.contains(code)) {
            return true;
        }
        for (EffectModule m : modules) {
            if (m.global && !m.expired()) {
                return true;
            }
        }
        return false;
    }

    /** Judged from a Stock object (internally still via the code string). */
    public boolean affects(Stock s) {
        return s != null && affects(s.code());
    }

    public boolean hasEffect(EffectType type) {
        for (EffectModule m : modules) {
            if (m.type == type && !m.expired()) {
                return true;
            }
        }
        return false;
    }

    /** Target stock code list (for the protocol payload and the UI's affected-scope display). */
    public List<String> targetCodes() {
        return new ArrayList<>(targets);
    }

    /**
     * The effect shorthand for UI line 2 and the 'event triggered' log (everything via translation keys; hard-coded single-character abbreviations for price/vol/impact are banned).
     * The server fills each segment against the zh_cn baseline template and ships the segments joined by {@code EffectText.PIECE};
     * the client hits whole-sentence translations per segment and rejoins them (see {@code L.tr}) - no effect math on the client (requirement 9).
     *
     * <p>Why this changed (zero Chinese residue in English UIs): the segments were previously joined with PLAIN SPACES, so
     * the client could only hand the whole string to fragment replacement, which is a global {@code replace} - inside the price-limit
     * fragment the 'limit down' piece got replaced by {@code Limit-down} and the leftover Chinese word leaked into the English
     * interface (the user's screenshot). The boundary-coded string lets each segment take the whole-sentence path, structurally immune
     * to being bitten apart by short fragments.</p>
     *
     * <p>An empty effect list shows 'no effect'. No fixed-length truncation here: truncating would bisect a translated template.</p>
     */
    public String effectSummary() {
        return cn.oremarket.common.EffectText.join(effectPieces());
    }

    /** Same as {@link #effectSummary()} but joined by plain spaces: for length-fixed display fields. */
    public String effectSummaryPlain() {
        return cn.oremarket.common.EffectText.joinPlain(effectPieces());
    }

    /** Per-segment effect text: every segment is one fully filled translation template; sentences are never stitched across segments. */
    private List<String> effectPieces() {
        List<String> pieces = new ArrayList<>();
        for (EffectModule module : modules) {
            if (module.expired()) {
                continue;
            }
            String text = switch (module.type) {
                case PRICE_MULT -> priceShockText(module);
                case VOLATILITY -> cn.oremarket.common.LogFormatter.format(
                        "oremarket.event.effect.volatility", fmt2(module.param));
                case SHOCK -> cn.oremarket.common.LogFormatter.format(
                        "oremarket.event.effect.trade_impact", fmt2(module.param));
                case TREND -> cn.oremarket.common.LogFormatter.text(
                        module.direction >= 0 ? "oremarket.event.effect.trend_up" : "oremarket.event.effect.trend_down");
                case REVERSION -> cn.oremarket.common.LogFormatter.format(
                        "oremarket.event.effect.mean_reversion", fmt2(module.param));
                case FEE -> cn.oremarket.common.LogFormatter.format(
                        "oremarket.event.effect.fee",
                        (module.param >= 0 ? "+" : "") + String.format(Locale.ROOT, "%.1f%%", module.param * 100));
                case PRICE_LIMIT -> cn.oremarket.common.LogFormatter.format(
                        "oremarket.event.effect.price_limit", String.format(Locale.ROOT, "%.0f", module.param * 100));
                case SUSPEND -> cn.oremarket.common.LogFormatter.format(
                        "oremarket.event.effect.halt_for", MarketTime.human(module.ticksLeft));
                case FORCE_BUY -> cn.oremarket.common.LogFormatter.format(
                        "oremarket.event.tooltip.force_buy", (int) module.param);
                case FORCE_SELL -> cn.oremarket.common.LogFormatter.format(
                        "oremarket.event.tooltip.force_sell", (int) module.param);
                case LIQUIDITY_DRY -> cn.oremarket.common.LogFormatter.format(
                        "oremarket.event.tooltip.liquidity_dry",
                        Math.round(MarketSettings.current().marketTicksToSeconds(module.ticksLeft)));
                case DIVIDEND -> cn.oremarket.common.LogFormatter.format(
                        "oremarket.event.tooltip.dividend", fmt2(realizedOrNominal(module)));
                case SPLIT -> cn.oremarket.common.LogFormatter.format(
                        "oremarket.event.tooltip.split", (int) Math.round(realizedOrNominal(module)));
            };
            pieces.add(text);
        }
        if (pieces.isEmpty()) {
            pieces.add(cn.oremarket.common.LogFormatter.text(
                    "oremarket.event.effect.none"));
        }
        return pieces;
    }

    /**
     * One PRICE_MULT module as player-facing text: the exact cumulative percentage while it is
     * readable, otherwise the overflow wording. A shock measured from a near-floor price can
     * legitimately multiply the quote many times over, so the fallback states that plainly instead
     * of printing a sentinel like "+999.9%".
     */
    private static String priceShockText(EffectModule module) {
        String percent = signedPercent(module);
        if (percent.isEmpty()) {
            return cn.oremarket.common.LogFormatter.text(
                    "oremarket.event.effect.price_mult_overflow");
        }
        return cn.oremarket.common.LogFormatter.format(
                "oremarket.event.effect.price_mult", percent);
    }

    /** Display names of the target stocks (UI line 2's affected scope); shows 'All market' when covering every active stock. */
    public String targetNames() {
        List<Stock> act = StockRegistry.getActiveStocks();
        if (!act.isEmpty() && targets.size() >= act.size()) {
            return cn.oremarket.common.LogFormatter.text(
                    "oremarket.event.target.market_wide");
        }
        StringBuilder sb = new StringBuilder();
        for (String code : targets) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            Stock s = StockRegistry.getByCode(code);
            sb.append(s == null ? code : s.displayName());
        }
        // the name list goes through a translation key too (the zh value is just "%s"; stock names inside it are swapped word by word by the L overlay)
        return cn.oremarket.common.LogFormatter.format(
                "oremarket.event.target.stock_list", sb.toString());
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * Cumulative price movement: the realized band-capped value when known, otherwise the nominal
     * compounding. An empty string means the move is too large for a percentage (see
     * {@link PriceShockMath}) and the caller must use the overflow wording instead.
     */
    private static String signedPercent(EffectModule module) {
        if (Double.isFinite(module.realizedValue)) {
            return cn.oremarket.common.PriceShockMath.percentText(module.realizedValue);
        }
        return cn.oremarket.common.PriceShockMath.cumulativePercentText(
                MarketSavedData.clampEventMultiplier(module.param), Math.max(1, module.duration));
    }

    /** One-shot effects announce their actual result; skipped or timed effects keep the nominal card value. */
    private static double realizedOrNominal(EffectModule module) {
        double realized = module.realizedValue;
        if (!Double.isFinite(realized) || (realized == 0.0 && module.type != EffectType.PRICE_MULT)) {
            return module.param;
        }
        return realized;
    }

    /**
     * Raw template -> translation key. Custom event names/descriptions are not in the lang table, so return an empty string and the client runs them through the overlay as raw text.
     * Key strings themselves (oremarket.xxx) pass through untouched.
     */
    private static String keyOf(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (raw.startsWith("oremarket.")) {
            return raw;
        }
        String k = cn.oremarket.common.RawText.keyOf(raw);
        return k == null ? "" : k;
    }

    /** Copy the instance (avoid shared mutable state across save / push). */
    public ActiveEvent copy() {
        List<EffectModule> copy = new ArrayList<>();
        for (EffectModule m : modules) {
            EffectModule n = new EffectModule(m.type, m.global, m.absolute, m.targets, m.param, m.direction, m.duration);
            n.ticksLeft = m.ticksLeft;
            n.realizedValue = m.realizedValue;
            copy.add(n);
        }
        ActiveEvent e = new ActiveEvent(template, displayName, description, nameKey, descKey,
                new LinkedHashSet<>(targets), copy, duration, uid, chainDepth);
        e.ticksLeft = ticksLeft;
        e.contributorNames = contributorNames;
        return e;
    }

    // ------------------------------------------------------------ NBT

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Tpl", template.id);
        tag.putInt("Uid", uid);
        tag.putInt("Dur", duration);
        tag.putInt("Left", ticksLeft);
        tag.putInt("Depth", chainDepth);
        tag.put("Targets", codesToTag(targets));
        tag.putString("Name", displayName);
        tag.putString("Desc", description);
        if (nameKey != null && !nameKey.isEmpty()) {
            tag.putString("NameKey", nameKey);
        }
        if (descKey != null && !descKey.isEmpty()) {
            tag.putString("DescKey", descKey);
        }
        if (contributorNames != null && !contributorNames.isEmpty()) {
            tag.putString("Contrib", contributorNames);
        }
        ListTag list = new ListTag();
        for (EffectModule m : modules) {
            CompoundTag mt = new CompoundTag();
            mt.putInt("T", m.type.ordinal());
            mt.putDouble("P", m.param);
            mt.putInt("D", m.direction);
            mt.putInt("Left", m.ticksLeft);
            mt.putInt("Dur", m.duration);
            mt.putBoolean("G", m.global);
            mt.putBoolean("A", m.absolute);
            if (Double.isFinite(m.realizedValue)) {
                mt.putDouble("Real", m.realizedValue);
            }
            mt.put("Targets", codesToTag(m.targets));
            list.add(mt);
        }
        tag.put("Modules", list);
        return tag;
    }

    private static ListTag codesToTag(Set<String> codes) {
        ListTag list = new ListTag();
        for (String c : codes) {
            list.add(StringTag.valueOf(c));
        }
        return list;
    }

    /** Load from a save; returns null when the template no longer exists (version change) so callers drop it. */
    public static ActiveEvent load(CompoundTag tag, boolean migrate) {
        EventTemplate tpl = EventRegistry.byId(tag.getString("Tpl"));
        if (tpl == null) {
            return null;
        }
        Set<String> targets = readTargets(tag, migrate);
        if (targets.isEmpty()) {
            targets = activeCodes();
        }
        List<EffectModule> modules = new ArrayList<>();
        ListTag list = tag.getList("Modules", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag mt = list.getCompound(i);
            EffectType type = EffectType.byOrdinal(mt.getInt("T"));
            if (type == null) {
                continue;
            }
            Set<String> modTargets = mt.contains("Targets") || mt.contains("Mask")
                    ? readTargets(mt, migrate) : new LinkedHashSet<>();
            int storedDuration = cappedModuleDuration(type, mt.getInt("Dur"));
            EffectModule m = new EffectModule(type, mt.getBoolean("G"), mt.getBoolean("A"),
                    modTargets, type.clampParam(mt.getDouble("P")),
                    mt.getInt("D"), storedDuration);
            m.ticksLeft = Math.max(0, Math.min(storedDuration, mt.getInt("Left")));
            if (mt.contains("Real")) {
                double realized = mt.getDouble("Real");
                if (Double.isFinite(realized)) {
                    m.realizedValue = realized;
                }
            }
            modules.add(m);
        }
        String name = tag.getString("Name");
        String desc = tag.getString("Desc");
        String nKey = tag.getString("NameKey");
        String dKey = tag.getString("DescKey");
        if (nKey.isEmpty()) {
            nKey = keyOf(name.isEmpty() ? tpl.nameTemplate : name);
            if (nKey.isEmpty()) {
                nKey = keyOf(tpl.nameTemplate);
            }
        }
        if (dKey.isEmpty()) {
            dKey = keyOf(desc.isEmpty() ? tpl.descriptionTemplate : desc);
            if (dKey.isEmpty()) {
                dKey = keyOf(tpl.descriptionTemplate);
            }
        }
        ActiveEvent e = new ActiveEvent(tpl,
                name.isEmpty() ? tpl.name(null) : name,
                desc.isEmpty() ? tpl.description(null) : desc,
                nKey, dKey,
                targets, modules, Math.max(1, tag.getInt("Dur")), tag.getInt("Uid"), tag.getInt("Depth"));
        e.ticksLeft = Math.max(1, tag.getInt("Left"));
        String contrib = tag.getString("Contrib");
        if (!contrib.isEmpty()) {
            e.contributorNames = contrib;
        }
        return e;
    }

    /**
     * Read the target set from a CompoundTag:
     * new format Targets = ListTag of StringTag;
     * old format Mask = IntTag bit mask (DataVersion < 2 migration) -> mapped to codes via the built-in-8 index table.
     */
    private static Set<String> readTargets(CompoundTag tag, boolean migrate) {
        Set<String> out = new LinkedHashSet<>();
        Tag t = tag.get("Targets");
        if (t instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                String code = list.getString(i).trim().toUpperCase(java.util.Locale.ROOT);
                if (!code.isEmpty()) {
                    out.add(code);
                }
            }
            return out;
        }
        if (tag.contains("Mask", Tag.TAG_INT)) {
            int mask = tag.getInt("Mask");
            for (int bit = 0; bit < 8 && mask != 0; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    String code = MarketSavedData.builtinCodeByLegacyIndex(bit);
                    if (code != null) {
                        out.add(code);
                    }
                }
            }
            if (migrate && !out.isEmpty()) {
                org.slf4j.LoggerFactory.getLogger("OreMarket/Event")
                        .info("[migration] event target bitmask {} -> code {}", mask, out);
            }
        }
        return out;
    }

    /** Legacy saves (only the Card field) convert into the new Rare special-template events, keeping each card's old price behavior bit-for-bit. */
    public static ActiveEvent loadLegacy(CompoundTag tag) {
        EventCard card = EventCard.byId(tag.getString("Card"));
        if (card == null) {
            return null;
        }
        EventTemplate tpl = EventRegistry.legacy(card);
        if (tpl == null) {
            return null;
        }
        int duration = Math.max(1, tag.getInt("Duration"));
        Set<String> targets = new LinkedHashSet<>();
        for (Stock s : StockRegistry.getActiveStocks()) {
            if (card.affects(s.code())) {
                targets.add(s.code());
            }
        }
        double mult = MarketSavedData.clampEventMultiplier(
                card.perTickMultiplier(duration, tag.getDouble("Intensity")));
        List<EffectModule> modules = new ArrayList<>();
        modules.add(new EffectModule(EffectType.PRICE_MULT, false, false, targets, mult, 0, duration));
        ActiveEvent e = new ActiveEvent(tpl, card.cardName(), card.description(), targets, modules,
                duration, 0, 0);
        e.ticksLeft = Math.max(1, tag.getInt("TicksLeft"));
        return e;
    }
}
