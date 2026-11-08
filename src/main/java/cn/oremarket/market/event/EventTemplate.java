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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import cn.oremarket.common.Rarity;
import cn.oremarket.common.EffectSpec;

/**
 * Event template: instead of hand-writing each event card, declare 'rarity + target selection mode + effect modules + description text template',
 * and at runtime ActiveEvent#spawn rolls concrete events from it using the server-side random source (requirement 3).
 * The {stock} placeholder in the description text is replaced with the actual stock name.
 */
public final class EventTemplate {

    /** Which stocks may be selected by this template. */
    public enum TargetScope {
        /** Every currently active stock, including custom and candidate-pool stocks that have already listed. */
        ACTIVE,
        /** Only the eight built-in mineral stocks; custom and dynamic candidate-pool stocks are deliberately excluded. */
        BUILTIN_MINERALS
    }

    /** Target selection mode. */
    public enum TargetMode {
        /** All stocks. */
        ALL,
        /** Randomly pick n~m stocks from the whole pool. */
        RANDOM,
        /** Use the stock hit by the trigger condition (e.g. '50% below base price'). */
        CONDITION_STOCK,
        /** A fixed handful (the original 12 event cards run this path). */
        FIXED,
        /** Inherit the parent event's targets (for chained events). */
        SAME_AS_PARENT
    }

    /** Chain declaration: only one level allowed; a depth=1 child never chains further. */
    public static final class Chain {
        public final String childId;
        /** Per-marketTick trigger probability (while active); the end-of-event trigger also rolls the chance. */
        public final double chance;
        /** true = fires when the parent event ends; false = keeps rolling the probability while active. */
        public final boolean onEnd;
        /** true = the child reuses the parent event's target stocks. */
        public final boolean sameTargets;

        public Chain(String childId, double chance, boolean onEnd, boolean sameTargets) {
            this.childId = childId;
            this.chance = Math.max(0, Math.min(1, chance));
            this.onEnd = onEnd;
            this.sameTargets = sameTargets;
        }
    }

    public final String id;
    public final String nameTemplate;
    public final String descriptionTemplate;
    public final Rarity rarity;
    public final TargetScope targetScope;
    public final TargetMode targetMode;
    public final int targetMin;
    public final int targetMax;
    public final Set<String> fixedTargets;
    public final List<EffectSpec> effects;
    public final EventCondition condition;   // may be null
    public final Chain chain;                // may be null
    /** true = triggered only by chains; never enters the random pool. */
    public final boolean chainOnly;
    /** Fixed duration in seconds (>0 skips the rarity range and converts these seconds directly; used by the 60-second fake news). */
    public final double fixedSeconds;
    /** Fixed duration in marketTicks (>0 takes top priority; used where an exact tick duration matters, e.g. liquidity drain 20 ticks). */
    public final int fixedTicks;
    /** true = the 'Market Rumor' fake-news template (empty effects, gated by eventFakeNewsEnabled). */
    public final boolean fakeNews;
    /** true = a custom template registered dynamically from the config customEvents (replaced wholesale on hot reload). */
    public final boolean custom;
    /** true = legendary preview display card (never participates in effect settlement). */
    public final boolean preview;
    /** The original 12 fixed event cards (uniformly Rare rarity; names and effects preserved verbatim). */
    public final EventCard legacyCard;       // may be null
    public final int durationMin;
    public final int durationMax;

    private EventTemplate(Builder b) {
        this.id = b.id;
        this.nameTemplate = b.name;
        this.descriptionTemplate = b.desc;
        this.rarity = b.rarity;
        this.targetScope = b.targetScope;
        this.targetMode = b.targetMode;
        this.targetMin = b.targetMin;
        this.targetMax = b.targetMax;
        this.fixedTargets = b.fixedTargets;
        this.effects = List.copyOf(b.effects);
        this.condition = b.condition;
        this.chain = b.chain;
        this.chainOnly = b.chainOnly;
        this.fixedSeconds = b.fixedSeconds;
        this.fixedTicks = b.fixedTicks;
        this.fakeNews = b.fakeNews;
        this.custom = b.custom;
        this.preview = b.preview;
        this.legacyCard = b.legacyCard;
        this.durationMin = b.durationMin > 0 ? b.durationMin : b.rarity.durationMin;
        this.durationMax = b.durationMax > 0 ? b.durationMax : b.rarity.durationMax;
    }

    public boolean isLegacy() {
        return legacyCard != null;
    }

    /** Whether this template is allowed to affect a stock code. */
    public boolean allowsCode(String code) {
        if (code == null) {
            return false;
        }
        if (targetScope == TargetScope.ACTIVE) {
            return true;
        }
        return Stock.BUILTIN_CODES.contains(code);
    }

    /** Whether at least one currently active stock can be selected by this template. */
    public boolean hasActiveTarget() {
        for (Stock stock : cn.oremarket.market.StockRegistry.getActiveStocks()) {
            if (allowsCode(stock.code()) && (targetMode != TargetMode.FIXED || fixedTargets.isEmpty()
                    || fixedTargets.contains(stock.code()))) {
                return true;
            }
        }
        return false;
    }

    /** Description text: replace {stock} with the actual stock name. */
    public String name(Stock stock) {
        return fill(nameTemplate, stock);
    }

    public String description(Stock stock) {
        return fill(descriptionTemplate, stock);
    }

    private static String fill(String tpl, Stock stock) {
        if (tpl == null) {
            return "";
        }
        String s = stock == null ? cn.oremarket.common.RawText.t("oremarket.text.0624") : stock.displayName();
        return tpl.replace("{stock}", s);
    }

    public static Builder of(String id, String name, Rarity rarity) {
        return new Builder(id, name, rarity);
    }

    /** Fluent constructor so the 30+ templates in EventRegistry stay readable. */
    public static final class Builder {
        private final String id;
        private final String name;
        private final Rarity rarity;
        private String desc = "";
        private TargetScope targetScope = TargetScope.ACTIVE;
        private TargetMode targetMode = TargetMode.RANDOM;
        private int targetMin = 1;
        private int targetMax = 2;
        private Set<String> fixedTargets = new LinkedHashSet<>();
        private final List<EffectSpec> effects = new ArrayList<>();
        private EventCondition condition;
        private Chain chain;
        private boolean chainOnly;
        private double fixedSeconds;
        private int fixedTicks;
        private boolean fakeNews;
        private boolean custom;
        private boolean preview;
        private EventCard legacyCard;
        private int durationMin = -1;
        private int durationMax = -1;

        private Builder(String id, String name, Rarity rarity) {
            this.id = id;
            this.name = name;
            this.rarity = rarity;
        }

        public Builder desc(String text) {
            this.desc = text;
            return this;
        }

        /** Restrict target selection to the built-in mineral stocks. */
        public Builder builtinMineralsOnly() {
            this.targetScope = TargetScope.BUILTIN_MINERALS;
            return this;
        }

        public Builder allTargets() {
            this.targetMode = TargetMode.ALL;
            // ALL mode lets ActiveEvent.pickTargets take the current active codes directly; min/max do not participate in the draw
            this.targetMin = 0;
            this.targetMax = 0;
            return this;
        }

        public Builder randomTargets(int lo, int hi) {
            this.targetMode = TargetMode.RANDOM;
            this.targetMin = lo;
            this.targetMax = hi;
            return this;
        }

        public Builder conditionTarget() {
            this.targetMode = TargetMode.CONDITION_STOCK;
            this.targetMin = 1;
            this.targetMax = 1;
            return this;
        }

        public Builder sameAsParent() {
            this.targetMode = TargetMode.SAME_AS_PARENT;
            return this;
        }

        public Builder fixedCodes(String... codes) {
            this.targetMode = TargetMode.FIXED;
            Set<String> set = new LinkedHashSet<>();
            for (String c : codes) {
                set.add(c);
            }
            this.fixedTargets = set;
            return this;
        }

        public Builder duration(int lo, int hi) {
            this.durationMin = lo;
            this.durationMax = hi;
            return this;
        }

        public Builder effect(EffectSpec spec) {
            this.effects.add(spec);
            return this;
        }

        public Builder condition(EventCondition cond) {
            this.condition = cond;
            return this;
        }

        public Builder chain(Chain c) {
            this.chain = c;
            return this;
        }

        public Builder chainOnly() {
            this.chainOnly = true;
            return this;
        }

        /** Fixed duration in seconds: spawn skips the rarity range and converts these seconds into marketTicks. */
        public Builder fixedSeconds(double seconds) {
            this.fixedSeconds = Math.max(0, seconds);
            return this;
        }

        /** Fixed duration in marketTicks: higher priority than fixedSeconds (for rules needing an exact tick basis). */
        public Builder fixedTicks(int ticks) {
            this.fixedTicks = Math.max(0, ticks);
            return this;
        }

        /** Marks this as the 'Market Rumor' fake-news template. */
        public Builder fakeNews() {
            this.fakeNews = true;
            return this;
        }

        /** Marks this as a config-registered custom template. */
        public Builder custom() {
            this.custom = true;
            return this;
        }

        /** Marks this as the legendary preview display card. */
        public Builder preview() {
            this.preview = true;
            return this;
        }

        public Builder legacy(EventCard card) {
            this.legacyCard = card;
            return this;
        }

        public EventTemplate build() {
            return new EventTemplate(this);
        }
    }
}