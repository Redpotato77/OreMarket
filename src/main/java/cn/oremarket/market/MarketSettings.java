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

import cn.oremarket.config.OreMarketConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import cn.oremarket.common.Fees;

/**
 * The **immutable snapshot** of the configuration: reads every tunable of all ten categories of the Forge config
 * (config/oremarket.toml) in one pass, validates and clamps them; afterwards the engine, events, loans and the UI read only
 * this snapshot instead of calling .get() on individual config entries.
 *
 * Why this layer exists:
 * - reading half old values and half new values within one marketTick could compute NaN prices; a snapshot keeps all
 * parameters consistent within the same tick;
 * - out-of-range values are clamped here centrally and the warning is **printed once only**, and the toml file is
 * **never rewritten** (mutating a player's file would be rude);
 * - when a paired min/max is inverted, min takes the smaller and max the larger, warning once;
 * - when the client needs it, the whole snapshot serialises into a CompoundTag carried by SyncConfigPacket;
 * the client stores it in {@link #CLIENT}, and the UI always reads this cache instead of the local config file.
 *
 * Three snapshots: {@link #server()} the server authority (from config), {@link #client()} the client cache (from the sync
 * packet), {@link #current()} picked automatically by calling thread (server thread gets server, client thread gets client).
 */
public final class MarketSettings {
    private static final Logger LOG = LoggerFactory.getLogger("OreMarket/Settings");

    /** The number of built-in stocks is fixed at 8 (custom stock counts are dynamic, carried by StockRegistry; nothing hardcoded here). */
    public static final int BUILTIN_COUNT = Stock.BUILTIN_TEMPLATE_COUNT;

    /** Raw custom-stock config strings (parsed server-side only; the client never reads nor receives them). */
    public final List<String> customStocks;

    // ---------------------------------------------------------- category 1: level thresholds
    public final long level2Threshold;
    public final long level3Threshold;
    public final long level4Threshold;

    // ---------------------------------------------------------- categories 2/3: exchange rates and base prices
    /**
     * Numeric table of the 8 built-ins; index = order of Stock.BUILTIN_TEMPLATES (migration mapping 0..7 uses the same order).
     * Custom stocks do not live here; they go through the customStocks strings + StockRegistry parsing.
     */
    public final long[] exchangeRates;
    public final double[] basePrices;

    // ---------------------------------------------------------- category 4: price engine
    public final double meanReversionFactor;
    /**
     * Mean-reversion dead zone (FRACTION of the anchor): when |quote - anchor| / anchor exceeds this,
     * the reversion term contributes NOTHING. 0.08 (default) = gravity only corrects small deviations;
     * deep discounts/premiums no longer heal on a schedule, so "buy the floor, sit for the climb" is not
     * a risk-free ladder anymore. >= 1.0 disables the gate (legacy always-reverting behavior).
     */
    public final double reversionDeadZoneFraction;
    public final double randomFluctuation;
    public final double trendImpact;
    public final double tradeImpactFactor;
    /** Whether system fills charge half of their existing impact as approximate execution slippage. */
    public final boolean tradeSlippage;
    /** Optional shared market-wide random component weight; zero preserves the old random stream. */
    public final double indexVolatilityWeight;
    public final double priceFloorPercent;
    public final double priceCeilingPercent;

    // ---------------------------------------------------------- category 5: time
    public final int marketTickInterval;
    /** Trading-day tick count, already floored to a multiple of marketTickInterval. */
    public final int tradingDayTicks;

    // ---------------------------------------------------------- category 6: loans
    public final long creditLimitLevel2;
    public final long creditLimitLevel3;
    public final long creditLimitLevel4;
    public final double loanDailyInterestPercent;
    public final int loanTermDays;
    public final int bankruptcyCooldownDays;
    public final double loanInterestCapMultiplier;

    // ------------------------------------------------------ category 6 continued: loan v2
    /** Auto-repay from the wallet balance at every trading-day close. */
    public final boolean loanAutoRepay;
    /** Grace trading days after maturity (no penalty interest, no score drop during the grace period). */
    public final int loanGracePeriodDays;
    /** Overdue penalty daily rate (fraction, already converted from percentage). */
    public final double loanPenaltyPercent;
    /** Whether the credit score system is active (when off, quota and rates follow level alone, as in the old version). */
    public final boolean loanCreditScoreEnabled;
    /** Whether P2P lending between players is open. */
    public final boolean loanP2PEnabled;
    /** Floating-rate amplitude (percentage of the fixed rate; the maximum drift when an event pushes the factor to plus/minus 1). */
    public final double loanInterestVariance;
    /** Hard cap of the daily rate (percentage): base x credit x event x term x product factor is clamped to it (plus an absolute 1.0% ceiling). */
    public final double loanRateCapPercent;
    /** How much of the player's current total assets counts into the credit limit (percentage); no gameplay cap is applied. */
    public final double loanLimitAssetPercent;
    /** Collateral value-to-quota haircut factor (0-2x). */
    public final double loanCollateralLimitFactor;
    /** Emergency loan per-borrowal cap (ore coins). */
    public final long loanEmergencyCap;
    /** Emergency loan rate factor (relative to the fixed rate). */
    public final double loanEmergencyRateFactor;
    /** Short margin loan rate factor. */
    public final double loanMarginRateFactor;
    /** Collateral loan rate factor (<1 = cheaper when collateralised). */
    public final double loanCollateralRateFactor;
    /** P2P platform fee (percentage of principal, charged once into the system reserve pool). */
    public final double loanP2PFeePercent;
    /** Consecutive overdue installments that trigger forced liquidation (earlier for collateral loans). */
    public final int loanOverdueStrikeLimit;
    /** Bankruptcy clawback window (trading days): loans borrowed inside the window before bankruptcy are not forgiven. */
    public final int loanClawbackDays;
    /** Quota-discount recovery rhythm after settlement: per settled loan, the trading days needed before the discount shrinks. */
    public final int loanLimitRecoverDays;
    /** Early-repayment interest discount (percentage). */
    public final double loanPrepayDiscountPercent;
    /** Early-repayment handling fee (percentage of the remaining principal). */
    public final double loanPrepayFeePercent;
    /** Score factor: points added per loan settled on time. */
    public final int loanScorePerOnTime;
    /** Score factor: points deducted per overdue event. */
    public final int loanScorePerOverdue;
    /** Score factor: points deducted per bankruptcy. */
    public final int loanScorePerBankrupt;
    /** Score factor: how many ore coins of total assets add 1 point (capped at +60). */
    public final long loanScoreAssetStep;
    /** Score factor: points added per level. */
    public final int loanScorePerTier;

    // ---------------------------------------------------------- category 7: trading
    public final double tradeFeePercent;
    public final double priceLimitPercent;
    public final long startingCoins;

    // ---------------------------------------------------------- category 8: event frequency and durations (seconds)
    public final double eventTriggerChance;
    public final int legendCooldownDays;
    public final int eventDurationCommonMinSec;
    public final int eventDurationCommonMaxSec;
    public final int eventDurationRareMinSec;
    public final int eventDurationRareMaxSec;
    public final int eventDurationEpicMinSec;
    public final int eventDurationEpicMaxSec;
    public final int eventDurationLegendMinSec;
    public final int eventDurationLegendMaxSec;
    public final int eventHaltMaxSeconds;
    public final int eventPriceLimitMaxSeconds;

    // ---------------------------------------------------------- category 9: event effect ranges
    public final double eventPriceMultMin;
    public final double eventPriceMultMax;
    public final double eventVolatilityMin;
    public final double eventVolatilityMax;
    public final double eventMeanReversionMin;
    public final double eventMeanReversionMax;
    public final double eventFeeMin;
    public final double eventFeeMax;
    public final double eventTradeImpactMin;
    public final double eventTradeImpactMax;
    public final double eventPriceLimitMin;
    public final double eventPriceLimitMax;

    // ---------------------------------------------------------- category 18: event system extensions
    public final boolean eventCombinationEnabled;
    public final boolean eventChainEnabled;
    public final boolean eventConflictEnabled;
    public final boolean eventPreviewEnabled;
    public final double eventPreviewAccuracy;
    public final boolean eventFakeNewsEnabled;
    public final boolean eventPlayerTriggerEnabled;
    public final int eventPlayerTriggerVolume;
    public final boolean eventCardAnimationEnabled;
    public final boolean eventFrequencyDynamicEnabled;
    public final int eventFrequencyHighThreshold;
    public final int eventFrequencyLowThreshold;
    /** Clamping band for the product of multiple event effects (only when 2+ multipliers stack on the same stock). */
    public final double eventEffectCapMin;
    public final double eventEffectCapMax;
    public final boolean eventLegendCooldownHintEnabled;
    /** RGB of the exchange selected-row background (the client adds an opaque alpha; admins can customise it in the config). */
    public final int exchangeRowSelectedColor;
    public final int eventForceBuyMaxShares;
    public final int eventForceSellMaxShares;
    public final double eventDividendMaxPerShare;
    public final boolean eventStockSplitEnabled;
    /** Raw custom-event config strings (parsed server-side only; like customStocks, never shipped to the client). */
    public final List<String> customEvents;

    // ---------------------------------------------------------- category 19: particle effects (server trigger parameters)
    public final boolean particleEnabled;
    public final boolean particleOnTrade;
    public final boolean particleOnEvent;
    public final boolean particleOnShortOpen;
    public final boolean particleOnShortClose;
    public final boolean particleOnBankruptcy;
    public final String particleTypeBuy;
    public final String particleTypeSell;
    public final String particleTypeEvent;
    public final String particleTypeShortOpen;
    public final String particleTypeShortClose;
    public final String particleTypeBankruptcy;
    public final int particleCount;
    public final double particleSpread;
    /** Minimum gap between two particle packets to the same player (game ticks). */
    public final int particleSpawnIntervalTicks;
    public final boolean particlePlaySoundEnabled;

    /** Validation items already warned about (each prints once, never floods). */
    private static final Set<String> WARNED = new HashSet<>();

    // ---------------------------------------------------------- category 11: transaction tax (coin burn)
    /** Burn tax rate (fraction). Total rate = fee rate + burn tax - fee rate x burn tax. */
    public final double taxDestroyRate;

    // ---------------------------------------------------------- category 12: shorting
    public final double shortMarginRatio;        // margin / borrowed value (0.5)
    public final double shortLiquidationRatio;   // liquidation condition one: liquidation price / open price (1.15)
    public final double shortDailyRate;          // borrowed-share daily interest (0.001)
    /** Multiplier for new short entries while the remaining pool is below 20%; existing positions are unchanged. */
    public final double shortScarcityMultiplier;
    /** Liquidation condition two threshold: margin < current value x this value fires it; cap = equity at the price line / value at that line. */
    public final double shortLiquidationThreshold2;
    /** Whether shorts affect the system price (default false: a short is a contract with the system, never in the market). */
    public final boolean shortAffectPrice;
    /** Buy-side quote impact multiplier for short closes and forced buy-backs; default 0.2. */
    public final double shortCloseImpactMultiplier;
    /** Margin release mode: withPnl (default, release proportionally and settle P&L) or quantity (release proportionally only, no P&L settlement). */
    public final String shortReleaseMode;
    /** Short pool base share count: per-stock pool cap = this value / the stock's base price (floored). Default 50000. */
    public final int shortPoolDefaultShares;
    /** Short pool absolute cap (shares): no converted result may exceed it. Default 100000. */
    public final int shortPoolAbsoluteMax;
    /** Short pool absolute floor (shares): even expensive stocks guarantee at least this many borrowable shares. Default 100. */
    public final int shortPoolAbsoluteMin;

    // ---------------------------------------------------------- category 13: orders
    /** Whether order matching moves the system price (default false: matching transfers player assets without rewriting the quote or recording candlestick volume). */
    public final boolean orderAffectPrice;

    // ---------------------------------------------------------- category 6: loan burn switches
    /** true (default) = repaid principal is burned outright; false = it goes to the system reserve pool. */
    public final boolean loanDestroyPrincipal;
    /** true (default) = repaid interest is burned outright; false = it goes to the system reserve pool. */
    public final boolean loanDestroyInterest;

    // ---------------------------------------------------------- category 15: limit-order deviation hint
    /** When the trigger price deviates from the actual fill price by more than this fraction, write a log hint (default 0.05 = 5%). */
    public final double limitOrderDeviationThreshold;

    // ---------------------------------------------------------- category 14: first-day volatility
    public final double firstDayUpChance;
    public final double firstDayDownChance;
    public final double firstDayFlatChance;
    public final double firstDayUpMin;
    public final double firstDayUpMax;
    public final double firstDayDownMin;
    public final double firstDayDownMax;
    public final double firstDayFlatRange;

    // ---------------------------------------------------------- category 17: bankruptcy
    /** Post-bankruptcy short cooldown: counted from the trading day after the bankruptcy day, forbidding new shorts for that many full trading days. */
    public final int bankruptcyShortCooldown;

    // ---------------------------------------------------------- category 13: orders
    public final int orderMatchIntervalSeconds;
    public final int orderMaxKeepDays;
    /** Concurrent player-order cap per player (server-authoritative; default 20). */
    public final int orderMax;
    public final double orderFeeRate;

    // ---------------------------------------------------------- category 14: dynamic listing and delisting
    public final int listIntervalDays;
    public final double delistThresholdRatio;
    public final int delistConsecutiveDays;
    public final int relistIntervalDays;
    public final int listingMaxCount;
    /** Whether random stocks auto-list (false = no more automatic dynamic listings). */
    public final boolean dynamicListingEnabled;
    /** Whether the default 8 built-in stocks join dynamic listing/delisting (false = built-ins are always listed and never auto-delisted). */
    public final boolean builtinDynamicListing;
    /** Default unlock level of dynamic stocks (the candidate pool's row 5th segment can override it). */
    public final int dynamicUnlockLevel;
    /** Dynamic stock base-price factor: base price = exchange points x this factor. */
    public final double dynamicBasePriceMultiplier;
    /** Raw candidate pool strings (CODE|ITEM_ID|DISPLAY_NAME|POINTS[|UNLOCK_LEVEL]; empty = built-in default pool; parsed server-side only). */
    public final List<String> dynamicCandidates;

    // ---------------------------------------------------------- category 15: limit orders
    public final int limitOrderMax;
    public final double limitOrderFeeRate;
    public final int limitOrderMaxKeepDays;
    /** How many consecutive marketTicks a shortfall in funds/shares may retry before auto-cancel (default 3). */
    public final int limitOrderFailRetries;

    // ---------------------------------------------------------- category 16: leaderboards
    public final int leaderboardSize;
    public final boolean leaderboardShowSelf;

    // UI theme colour order matches OreMarketConfig category 20; ARGB alpha normalised to opaque.
    public static final int UI_PANEL_ROOT = 0, UI_PANEL_SOFT = 1, UI_PANEL_ROW = 2, UI_BORDER = 3,
            UI_DARK = 4, UI_HIGHLIGHT = 5, UI_ACCENT = 6, UI_GOLD = 7, UI_MUTED = 8, UI_GRAY = 9,
            UI_DIM = 10, UI_QUOTE_UP = 11, UI_QUOTE_DOWN = 12, UI_QUOTE_FLAT = 13,
            UI_PNL_UP = 14, UI_PNL_DOWN = 15, UI_SELECTED = 16, UI_HOVER = 17, UI_BLUE = 18,
            UI_BUY = 19, UI_TOAST_BG = 20, UI_TOAST_FG = 21, UI_KGRID = 22, UI_CHART_BG = 23,
            UI_LETTERBOX = 24, UI_RARITY_COMMON = 25, UI_RARITY_RARE = 26, UI_RARITY_EPIC = 27,
            UI_RARITY_LEGEND = 28, UI_TEXT_WHITE = 29, UI_TEXT_SOFT = 30, UI_TIP_TEXT = 31,
            UI_BODY_TEXT = 32, UI_WICK_UP = 33, UI_WICK_DOWN = 34, UI_BUTTON_HOVER = 35,
            UI_DEPOSIT_HOVER = 36, UI_WITHDRAW_HOVER = 37, UI_WARNING = 38, UI_HINT_GRAY = 39,
            UI_GOLD_TEXT = 40, UI_CHIP_BLUE = 41, UI_ACCENT_ORANGE = 42, UI_COIN_DARK = 43,
            UI_COIN_LIGHT = 44;

    private static final int[] UI_COLOR_DEFAULTS = {
            0x1A1A1A, 0x2B2B2B, 0x3A3A3A, 0x555555, 0x2A2A2A, 0x4A4A4A,
            0x6A8FD0, 0xFFD75F, 0xAAAAAA, 0x888888, 0x666666, 0xFF6F6F,
            0x6FE06F, 0xBDBDBD, 0xE04A4A, 0x3FBF5F, 0x4A5A7A, 0x404040,
            0x3A3A5A, 0x2E5A3A, 0x0E0E0E, 0xFF5555, 0x333333, 0x151515,
            0x0D0D10, 0x888888, 0x4A7FD0, 0x9A4AD0, 0xD0A040,
            0xFFFFFF, 0xC9C9D9, 0xE8E8E8, 0xCCCCCC, 0xCC3A3A, 0x2F9F4F, 0x4E4E4E,
            0x3F7A50, 0x50507A, 0xFF5F5F, 0x9A9AA5, 0xFFE89A, 0x44506A,
            0xFFB060, 0xB8860B, 0xDAA520
    };
    private final int[] uiColors;
    public final int uiScrollStep;
    public final int uiScrollStepFine;
    public final int uiDragThresholdPx;
    public final boolean uiSmoothScroll;
    public final int uiScrollLerpPercent;
    public final int uiToastDurationMs;
    public final int uiToastFadeMs;
    public final int uiCardAnimMs;
    public final int uiClickDebounceMs;
    public final int maxTradeQuantity;
    public final int logMaxEntries;
    public final int eventMaxActive;
    public final int eventSuspendCooldownTicks;
    public final int eventDividendCooldownDays;
    public final int eventSplitRatio;
    public final int klineTicksPerBar;
    /** Maximum number of candles the client draws; server retention and the synchronized history cap are StockState.KLINE_LEN. */
    public final int klineDisplayCount;
    /** Whether a newly opened quote screen starts in short-term mode; this is display-only. */
    public final boolean klineDefaultShort;
    /** Display-only switch: use only tracked player fills instead of total candle volume. */
    public final boolean klineRealPlayerVolumeOnly;
    /** In order: draw weights for common, rare, epic and legendary events. */
    private final int[] rarityWeights;

    private static volatile MarketSettings SERVER;
    private static volatile MarketSettings CLIENT;

    private MarketSettings(CompoundTag t) {
        // ---------- category 1 (any missing key falls back to defaults; never throws)
        long level2 = clampL("level2Threshold", has(t, "level2Threshold", 200L), 0L, 1_000_000L, 200L);
        long level3 = clampL("level3Threshold", has(t, "level3Threshold", 2000L), 0L, 10_000_000L, 2000L);
        long level4 = clampL("level4Threshold", has(t, "level4Threshold", 8000L), 0L, 100_000_000L, 8000L);
        if (level3 < level2) {
            warnOnce("levelThresholds.2-3", "level3Threshold=" + level3
                    + " is below level2Threshold=" + level2 + "; raised in memory to level 2 threshold");
            level3 = level2;
        }
        if (level4 < level3) {
            warnOnce("levelThresholds.3-4", "level4Threshold=" + level4
                    + " is below level3Threshold=" + level3 + "; raised in memory to level 3 threshold");
            level4 = level3;
        }
        level2Threshold = level2;
        level3Threshold = level3;
        level4Threshold = level4;

        // ---------- categories 2/3 (the 8 built-ins in fixed template order; index = position in Stock.BUILTIN_TEMPLATES)
        exchangeRates = new long[BUILTIN_COUNT];
        basePrices = new double[BUILTIN_COUNT];
        String rateKey = "exchangeRate.";
        String baseKey = "basePrice.";
        for (int i = 0; i < BUILTIN_COUNT; i++) {
            Stock tpl = Stock.BUILTIN_TEMPLATES.get(i);
            long rateDef = tpl.exchangeRate();
            double baseDef = tpl.basePrice();
            // exchange rates 0 or negative would divide by zero: clamp to the lower bound 1
            exchangeRates[i] = clampL(rateKey + i, has(t, rateKey + i, rateDef), 1L, 10000L, rateDef);
            basePrices[i] = clampD(baseKey + i, has(t, baseKey + i, baseDef), 1.0, 100000.0, baseDef);
        }

        // ---------- category 10: custom stocks (raw strings; parsing and ten-step validation in StockRegistry.parseCustomStocks)
        // only the server writes these into the tag; the client's SyncConfigPacket excludes them -> empty list (the client never reads raw strings).
        List<String> custom = new ArrayList<>();
        Tag customTag = t.get("customStocks");
        if (customTag instanceof ListTag cl) {
            for (int i = 0; i < cl.size(); i++) {
                custom.add(cl.getString(i));
            }
        }
        customStocks = List.copyOf(custom);

        // ---------- category 4
        meanReversionFactor = clampD("meanReversionFactor", has(t, "meanReversionFactor", 0.03), 0.0, 0.5, 0.03);
        reversionDeadZoneFraction = clampD("reversionDeadZoneFraction", has(t, "reversionDeadZoneFraction", 0.08), 0.0, 1.0, 0.08);
        randomFluctuation = clampD("randomFluctuation", has(t, "randomFluctuation", 0.02), 0.0, 0.5, 0.02);
        trendImpact = clampD("trendImpact", has(t, "trendImpact", 0.005), 0.0, 0.1, 0.005);
        tradeImpactFactor = clampD("tradeImpactFactor", has(t, "tradeImpactFactor", 0.004), 0.0, 0.1, 0.004);
        tradeSlippage = hasBoolean(t, "tradeSlippage", true);
        indexVolatilityWeight = cn.oremarket.common.MarketIndexNoise
                .clampWeight(clampD("indexVolatilityWeight", has(t, "indexVolatilityWeight", 0.15), 0.0, 0.5, 0.15));
        priceFloorPercent = clampD("priceFloorPercent", has(t, "priceFloorPercent", 25.0), 1.0, 100.0, 25.0);
        priceCeilingPercent = clampD("priceCeilingPercent", has(t, "priceCeilingPercent", 400.0), 100.0, 10000.0, 400.0);

        // ---------- category 5: the trading day length must be a multiple of marketTickInterval (floor + warn once)
        int interval = clampI("marketTickInterval", has(t, "marketTickInterval", 60), 1, 24000, 60);
        marketTickInterval = interval;
        int rawDay = clampI("tradingDayTicks", has(t, "tradingDayTicks", 24000), 100, 240000, 24000);
        int day = rawDay - Math.floorMod(rawDay, interval);
        if (day < interval) {
            day = interval;
        }
        if (day != rawDay) {
            warnOnce("tradingDayTicksMultiple", "tradingDayTicks=" + rawDay
                    + " is not a multiple of marketTickInterval=" + interval + "; rounded to " + day);
        }
        tradingDayTicks = day;
        // A trading day shorter than 1200 ticks (1 minute) turns every once-per-trading-day log such as
        // setDailyBaseline / Leaderboard into spam every few seconds and drags the server down.
        // The snapshot build warns once; startup / hot-reload entry additionally calls warnIfTradingDayTooShort with a giant banner.
        if (isTradingDayTooShort(day)) {
            warnOnce("tradingDayTicksTooShort." + day, tradingDayTooShortMessage(day, "snapshot"));
        }

        // ---------- category 6
        creditLimitLevel2 = clampL("creditLimitLevel2", has(t, "creditLimitLevel2", 200L), 0L, 1_000_000L, 200L);
        creditLimitLevel3 = clampL("creditLimitLevel3", has(t, "creditLimitLevel3", 500L), 0L, 1_000_000L, 500L);
        creditLimitLevel4 = clampL("creditLimitLevel4", has(t, "creditLimitLevel4", 1200L), 0L, 1_000_000L, 1200L);
        loanDailyInterestPercent = clampD("loanDailyInterestPercent",
                has(t, "loanDailyInterestPercent", 0.5), 0.0, 100.0, 0.5) / 100.0;
        loanTermDays = clampI("loanTermDays", has(t, "loanTermDays", 5), 1, 30, 5);
        bankruptcyCooldownDays = clampI("bankruptcyCooldownDays", has(t, "bankruptcyCooldownDays", 3), 0, 30, 3);
        loanInterestCapMultiplier = clampD("loanInterestCapMultiplier",
                has(t, "loanInterestCapMultiplier", 2.0), 1.0, 10.0, 2.0);
        // ---------- category 6 continued: loan v2 (auto-repayment / overdue penalty / credit scoring / dynamic quota /
        // floating rate / early repayment / four loan products / P2P lending). All defaults; legacy saves missing keys behave as before.
        loanAutoRepay = hasBoolean(t, "loanAutoRepay", true);
        loanGracePeriodDays = clampI("loanGracePeriodDays", has(t, "loanGracePeriodDays", 1), 0, 30, 1);
        loanPenaltyPercent = clampD("loanPenaltyPercent", has(t, "loanPenaltyPercent", 2.0), 0.0, 100.0, 2.0) / 100.0;
        loanCreditScoreEnabled = hasBoolean(t, "loanCreditScoreEnabled", true);
        loanP2PEnabled = hasBoolean(t, "loanP2PEnabled", true);
        loanInterestVariance = clampD("loanInterestVariance", has(t, "loanInterestVariance", 40.0), 0.0, 200.0, 40.0);
        loanRateCapPercent = clampD("loanRateCapPercent", has(t, "loanRateCapPercent", 1.0), 0.0, 1.0, 1.0);
        loanLimitAssetPercent = clampD("loanLimitAssetPercent", has(t, "loanLimitAssetPercent", 20.0), 0.0, 100.0, 20.0);
        loanCollateralLimitFactor = clampD("loanCollateralLimitFactor",
                has(t, "loanCollateralLimitFactor", 0.50), 0.0, 2.0, 0.50);
        loanEmergencyCap = clampL("loanEmergencyCap", has(t, "loanEmergencyCap", 100L), 0L, 100_000L, 100L);
        loanEmergencyRateFactor = clampD("loanEmergencyRateFactor",
                has(t, "loanEmergencyRateFactor", 2.00), 1.0, 5.0, 2.00);
        loanMarginRateFactor = clampD("loanMarginRateFactor", has(t, "loanMarginRateFactor", 1.50), 1.0, 3.0, 1.50);
        loanCollateralRateFactor = clampD("loanCollateralRateFactor",
                has(t, "loanCollateralRateFactor", 0.85), 0.2, 1.0, 0.85);
        loanP2PFeePercent = clampD("loanP2PFeePercent", has(t, "loanP2PFeePercent", 2.0), 0.0, 20.0, 2.0);
        loanOverdueStrikeLimit = clampI("loanOverdueStrikeLimit", has(t, "loanOverdueStrikeLimit", 5), 1, 30, 5);
        loanClawbackDays = clampI("loanClawbackDays", has(t, "loanClawbackDays", 2), 0, 30, 2);
        loanLimitRecoverDays = clampI("loanLimitRecoverDays", has(t, "loanLimitRecoverDays", 5), 1, 30, 5);
        loanPrepayDiscountPercent = clampD("loanPrepayDiscountPercent",
                has(t, "loanPrepayDiscountPercent", 10.0), 0.0, 100.0, 10.0);
        loanPrepayFeePercent = clampD("loanPrepayFeePercent", has(t, "loanPrepayFeePercent", 1.0), 0.0, 20.0, 1.0);
        loanScorePerOnTime = clampI("loanScorePerOnTime", has(t, "loanScorePerOnTime", 12), 0, 100, 12);
        loanScorePerOverdue = clampI("loanScorePerOverdue", has(t, "loanScorePerOverdue", 35), 0, 100, 35);
        loanScorePerBankrupt = clampI("loanScorePerBankrupt", has(t, "loanScorePerBankrupt", 150), 0, 300, 150);
        loanScoreAssetStep = clampL("loanScoreAssetStep", has(t, "loanScoreAssetStep", 2000L), 1L, 1_000_000L, 2000L);
        loanScorePerTier = clampI("loanScorePerTier", has(t, "loanScorePerTier", 15), 0, 50, 15);

        // ---------- category 7
        tradeFeePercent = clampD("tradeFeePercent", has(t, "tradeFeePercent", 0.5), 0.0, 10.0, 0.5) / 100.0;
        priceLimitPercent = clampD("priceLimitPercent", has(t, "priceLimitPercent", 10.0), 1.0, 50.0, 10.0) / 100.0;
        // startingCoins legitimately allows 0 (= no initial coins issued), so 0 must not be treated as unset
        startingCoins = clampL("startingCoins", has(t, "startingCoins", 100L), 0L, 1_000_000L, 100L);

        // ---------- category 11: transaction tax (only affects the burn part of new trades; events modify only the fee component)
        taxDestroyRate = clampD("taxDestroyPercent", has(t, "taxDestroyPercent", 0.0), 0.0, 10.0, 0.0) / 100.0;
        // ---------- category 12: shorting (new convention: all decimal fractions, no percentage keys anymore)
        shortMarginRatio = clampD("shortMarginRatio", has(t, "shortMarginRatio", 0.50), 0.10, 5.0, 0.50);
        shortLiquidationRatio = clampD("shortLiquidationLine", has(t, "shortLiquidationLine", 1.15), 1.05, 3.0, 1.15);
        shortDailyRate = clampD("shortDailyInterest", has(t, "shortDailyInterest", 0.001), 0.0, 0.20, 0.001);
        shortScarcityMultiplier = clampD("shortScarcityMultiplier", has(t, "shortScarcityMultiplier", 2.0), 1.0, 10.0, 2.0);
        // Liquidation condition two has a static range plus a conservative dynamic cap. The cap is
        // derived from the equity that remains at the price-line trigger, not from marginRatio alone:
        // cap = (marginRatio - (liquidationLine - 1)) / liquidationLine. This keeps the maintenance
        // check from firing before condition one on a clean position; daily interest can still lower
        // equity and legitimately make condition two fire earlier. Older generated defaults (0.43 and
        // 1.00) are migrated through the same cap without changing an already safer custom value.
        double configuredT2 = has(t, "shortLiquidationThreshold2", 0.20);
        boolean legacyT2Default = t.contains("shortLiquidationThreshold2")
                && (Math.abs(configuredT2 - 0.43) < 1.0e-9 || Math.abs(configuredT2 - 1.00) < 1.0e-9);
        double rawT2 = clampD("shortLiquidationThreshold2", configuredT2, 0.05, 2.00, 0.20);
        double t2Cap = (shortMarginRatio - (shortLiquidationRatio - 1.0))
                / Math.max(1.0e-6, shortLiquidationRatio);
        // The dynamic cap may be below the config's static 0.05 floor for very leveraged
        // settings; runtime safety wins and condition two becomes 0 rather than firing early.
        t2Cap = Math.max(0.0, t2Cap);
        if (rawT2 > t2Cap) {
            if (!legacyT2Default) {
                warnOnce("shortLiquidationThreshold2.cap", "shortLiquidationThreshold2=" + rawT2
                        + " exceeds price-line safety cap " + String.format(Locale.ROOT, "%.4f", t2Cap)
                        + "; clamped to cap");
            }
            rawT2 = t2Cap;
        }
        shortLiquidationThreshold2 = rawT2;
        shortAffectPrice = hasBoolean(t, "shortAffectPrice", true);
        shortCloseImpactMultiplier = clampD("shortCloseImpactMultiplier", has(t, "shortCloseImpactMultiplier", 0.2), 0.0, 1.0, 0.2);
        String mode = t.contains("shortReleaseMode") ? t.getString("shortReleaseMode") : "withPnl";
        if ("withPnl".equals(mode)) {
            shortReleaseMode = "withPnl";
        } else if ("quantity".equals(mode)) {
            shortReleaseMode = "quantity";
        } else {
            if (!mode.isEmpty()) {
                warnOnce("shortReleaseMode", "shortReleaseMode=\"" + mode
                        + "\" is not quantity / withPnl, falling back to default withPnl (warned once)");
            }
            shortReleaseMode = "withPnl";
        }
        // ---------- pure-share convention. Missing keys (legacy save / old settings packet) = defaults;
        // the three bounds clamp individually; an absurd min > max combination is backstopped by ShortPoolCalculator when sizing the pool.
        shortPoolDefaultShares = clampI("defaultBaseShares", has(t, "defaultBaseShares", 50000), 1, 10_000_000, 50000);
        shortPoolAbsoluteMax = clampI("absoluteMaxShares", has(t, "absoluteMaxShares", 100000), 1, 10_000_000, 100000);
        shortPoolAbsoluteMin = clampI("absoluteMinShares", has(t, "absoluteMinShares", 100), 1, 1_000_000, 100);

        // ---------- category 13: orders
        orderAffectPrice = hasBoolean(t, "orderAffectPrice", true);

        // ---------- category 6: loan burn switches (missing = default true; a key never defaults to false)
        loanDestroyPrincipal = hasBoolean(t, "loanDestroyPrincipal", true);
        loanDestroyInterest = hasBoolean(t, "loanDestroyInterest", true);

        // ---------- category 15: limit-order trigger deviation hint threshold
        limitOrderDeviationThreshold = clampD("limitOrderDeviationThreshold",
                has(t, "limitOrderDeviationThreshold", 0.05), 0.01, 0.20, 0.05);

        // ---------- category 14: first-day volatility (the three probabilities must sum to 1.0;
        // when they do not, normalise proportionally -- the result lives only in this in-memory snapshot, never written back to the toml, re-judged on every load)
        double upChance = clampD("firstDayUpChance", has(t, "firstDayUpChance", 0.30), 0.0, 1.0, 0.30);
        double downChance = clampD("firstDayDownChance", has(t, "firstDayDownChance", 0.30), 0.0, 1.0, 0.30);
        double flatChance = clampD("firstDayFlatChance", has(t, "firstDayFlatChance", 0.40), 0.0, 1.0, 0.40);
        double chanceSum = upChance + downChance + flatChance;
        if (Math.abs(chanceSum - 1.0) > 1.0e-6) {
            if (chanceSum <= 1.0e-9) {
                LOG.warn("First-day chance sum is 0; using defaults 0.30 / 0.30 / 0.40 (not written back to config)");
                upChance = 0.30;
                downChance = 0.30;
                flatChance = 0.40;
            } else {
                LOG.warn("First-day chance sum is {}, not 1.0; values were normalized in memory only (not written back to config)",
                        String.format(Locale.ROOT, "%.4f", chanceSum));
                upChance /= chanceSum;
                downChance /= chanceSum;
                flatChance /= chanceSum;
            }
        }
        firstDayUpChance = upChance;
        firstDayDownChance = downChance;
        firstDayFlatChance = flatChance;
        // first-day surge/plunge min-max: clamp each into the config-declared range first; swap with a warning when min exceeds max
        double[] up = pairSwapWarn("firstDayUp",
                clampD("firstDayUpMin", has(t, "firstDayUpMin", 0.20), 0.0, 1.0, 0.20),
                clampD("firstDayUpMax", has(t, "firstDayUpMax", 0.50), 0.0, 2.0, 0.50));
        firstDayUpMin = up[0];
        firstDayUpMax = up[1];
        double[] down = pairSwapWarn("firstDayDown",
                clampD("firstDayDownMin", has(t, "firstDayDownMin", 0.20), 0.0, 1.0, 0.20),
                clampD("firstDayDownMax", has(t, "firstDayDownMax", 0.50), 0.0, 1.0, 0.50));
        firstDayDownMin = down[0];
        firstDayDownMax = down[1];
        firstDayFlatRange = clampD("firstDayFlatRange", has(t, "firstDayFlatRange", 0.05), 0.0, 0.20, 0.05);

        // ---------- category 17: bankruptcy
        bankruptcyShortCooldown = clampI("bankruptcyShortCooldown", has(t, "bankruptcyShortCooldown", 3), 0, 30, 3);

        // ---------- category 13: player orders
        orderMatchIntervalSeconds = clampI("orderMatchIntervalSeconds", has(t, "orderMatchIntervalSeconds", 60), 10, 600, 60);
        orderMaxKeepDays = clampI("orderMaxKeepDays", has(t, "orderMaxKeepDays", 7), 1, 30, 7);
        orderMax = clampI("orderMax", has(t, "orderMax", 20), 1, 200, 20);
        orderFeeRate = clampD("orderFeePercent", has(t, "orderFeePercent", 0.5), 0.0, 5.0, 0.5) / 100.0;

        // ---------- category 14: dynamic listing and delisting
        listIntervalDays = clampI("listIntervalDays", has(t, "listIntervalDays", 5), 1, 30, 5);
        delistThresholdRatio = clampD("delistThresholdPercent", has(t, "delistThresholdPercent", 30.0), 10.0, 90.0, 30.0) / 100.0;
        delistConsecutiveDays = clampI("delistConsecutiveDays", has(t, "delistConsecutiveDays", 3), 1, 10, 3);
        relistIntervalDays = clampI("relistIntervalDays", has(t, "relistIntervalDays", 10), 1, 60, 10);
        listingMaxCount = clampI("listingMaxCount", has(t, "listingMaxCount", 24), 8, 64, 24);
        dynamicListingEnabled = hasBoolean(t, "dynamicListingEnabled", true);
        builtinDynamicListing = hasBoolean(t, "builtinDynamicListing", false);
        dynamicUnlockLevel = clampI("dynamicUnlockLevel", has(t, "dynamicUnlockLevel", 1), 1, 4, 1);
        dynamicBasePriceMultiplier = clampD("dynamicBasePriceMultiplier",
                has(t, "dynamicBasePriceMultiplier", 5.0), 0.1, 100.0, 5.0);
        List<String> cands = new ArrayList<>();
        Tag candTag = t.get("dynamicCandidates");
        if (candTag instanceof ListTag cl) {
            for (int i = 0; i < cl.size(); i++) {
                cands.add(cl.getString(i));
            }
        }
        dynamicCandidates = List.copyOf(cands);

        // ---------- category 15: limit orders
        limitOrderMax = clampI("limitOrderMax", has(t, "limitOrderMax", 10), 1, 50, 10);
        limitOrderFeeRate = clampD("limitOrderFeePercent", has(t, "limitOrderFeePercent", 1.0), 0.0, 5.0, 1.0) / 100.0;
        limitOrderMaxKeepDays = clampI("limitOrderMaxKeepDays", has(t, "limitOrderMaxKeepDays", 7), 1, 30, 7);
        limitOrderFailRetries = clampI("limitOrderFailRetries", has(t, "limitOrderFailRetries", 3), 1, 100, 3);

        // ---------- category 16: leaderboards
        leaderboardSize = clampI("leaderboardSize", has(t, "leaderboardSize", 10), 5, 50, 10);
        leaderboardShowSelf = t.contains("leaderboardShowSelf") ? t.getBoolean("leaderboardShowSelf") : true;

        // ---------- category 8
        eventTriggerChance = clampD("eventTriggerChance", has(t, "eventTriggerChance", 3.0), 0.0, 100.0, 3.0) / 100.0;
        legendCooldownDays = clampI("legendCooldownDays", has(t, "legendCooldownDays", 3), 0, 30, 3);
        int[] c = pair("eventDurationCommon", has(t, "eventDurationCommonMinSec", 20), 20,
                has(t, "eventDurationCommonMaxSec", 60), 60, 0, 86400);
        eventDurationCommonMinSec = c[0];
        eventDurationCommonMaxSec = c[1];
        int[] r = pair("eventDurationRare", has(t, "eventDurationRareMinSec", 45), 45,
                has(t, "eventDurationRareMaxSec", 120), 120, 0, 86400);
        eventDurationRareMinSec = r[0];
        eventDurationRareMaxSec = r[1];
        int[] e = pair("eventDurationEpic", has(t, "eventDurationEpicMinSec", 90), 90,
                has(t, "eventDurationEpicMaxSec", 180), 180, 0, 86400);
        eventDurationEpicMinSec = e[0];
        eventDurationEpicMaxSec = e[1];
        int[] l = pair("eventDurationLegend", has(t, "eventDurationLegendMinSec", 120), 120,
                has(t, "eventDurationLegendMaxSec", 240), 240, 0, 86400);
        eventDurationLegendMinSec = l[0];
        eventDurationLegendMaxSec = l[1];
        eventHaltMaxSeconds = clampI("eventHaltMaxSeconds", has(t, "eventHaltMaxSeconds", 180), 1, 30000, 180);
        eventPriceLimitMaxSeconds = clampI("eventPriceLimitMaxSeconds",
                has(t, "eventPriceLimitMaxSeconds", 60), 1, 30000, 60);

        // ---------- category 9
        double[] pm = pairD("eventPriceMultiplier", has(t, "eventPriceMultMin", 0.5), 0.5,
                has(t, "eventPriceMultMax", 1.5), 1.5, 0.0, 5.0);
        eventPriceMultMin = pm[0];
        eventPriceMultMax = pm[1];
        double[] vo = pairD("eventVolatility", has(t, "eventVolatilityMin", 0.2), 0.2,
                has(t, "eventVolatilityMax", 3.0), 3.0, 0.0, 10.0);
        eventVolatilityMin = vo[0];
        eventVolatilityMax = vo[1];
        double[] mr = pairD("eventMeanReversion", has(t, "eventMeanReversionMin", 0.0), 0.0,
                has(t, "eventMeanReversionMax", 0.15), 0.15, 0.0, 1.0);
        eventMeanReversionMin = mr[0];
        eventMeanReversionMax = mr[1];
        double[] fe = pairD("eventFee", has(t, "eventFeeMin", 0.0), 0.0,
                has(t, "eventFeeMax", 0.1), 0.10, 0.0, 1.0);
        eventFeeMin = fe[0];
        eventFeeMax = fe[1];
        double[] ti = pairD("eventTradeImpact", has(t, "eventTradeImpactMin", 0.5), 0.5,
                has(t, "eventTradeImpactMax", 3.0), 3.0, 0.0, 10.0);
        eventTradeImpactMin = ti[0];
        eventTradeImpactMax = ti[1];
        double[] pl = pairD("eventPriceLimit", has(t, "eventPriceLimitMin", 5.0), 5.0,
                has(t, "eventPriceLimitMax", 25.0), 25.0, 0.1, 100.0);
        eventPriceLimitMin = pl[0] / 100.0;
        eventPriceLimitMax = pl[1] / 100.0;

        // ---------- category 18: event system extensions
        eventCombinationEnabled = hasBoolean(t, "eventCombinationEnabled", true);
        eventChainEnabled = hasBoolean(t, "eventChainEnabled", true);
        eventConflictEnabled = hasBoolean(t, "eventConflictEnabled", true);
        eventPreviewEnabled = hasBoolean(t, "eventPreviewEnabled", true);
        eventPreviewAccuracy = clampD("eventPreviewAccuracy",
                has(t, "eventPreviewAccuracy", 0.80), 0.0, 1.0, 0.80);
        eventFakeNewsEnabled = hasBoolean(t, "eventFakeNewsEnabled", true);
        eventPlayerTriggerEnabled = hasBoolean(t, "eventPlayerTriggerEnabled", true);
        eventPlayerTriggerVolume = clampI("eventPlayerTriggerVolume",
                has(t, "eventPlayerTriggerVolume", 1000), 100, 100000, 1000);
        eventCardAnimationEnabled = hasBoolean(t, "eventCardAnimationEnabled", true);
        eventFrequencyDynamicEnabled = hasBoolean(t, "eventFrequencyDynamicEnabled", true);
        eventFrequencyHighThreshold = clampI("eventFrequencyHighThreshold",
                has(t, "eventFrequencyHighThreshold", 5), 1, 20, 5);
        eventFrequencyLowThreshold = clampI("eventFrequencyLowThreshold",
                has(t, "eventFrequencyLowThreshold", 0), 0, 5, 0);
        double[] cap = pairD("eventEffectMultiplierCap", has(t, "eventEffectCapMin", 0.5), 0.5,
                has(t, "eventEffectCapMax", 2.0), 2.0, 0.01, 100.0);
        // the lower bound must be at most 1 and the upper bound at least 1 (the clamp band must cover 1.0, otherwise even single-event normal values would be distorted)
        eventEffectCapMin = Math.min(cap[0], 1.0);
        eventEffectCapMax = Math.max(cap[1], 1.0);
        eventLegendCooldownHintEnabled = hasBoolean(t, "eventLegendCooldownHintEnabled", true);
        // selected-row colour: 0 = unconfigured (use built-in default 0xFF4A5A7A); non-zero treated as RGB, alpha clamped and forced opaque
        exchangeRowSelectedColor = has(t, "exchangeRowSelectedColor", 0xFF4A5A7A) | 0xFF000000;
        eventForceBuyMaxShares = clampI("eventForceBuyMaxShares",
                has(t, "eventForceBuyMaxShares", 1000), 10, 100000, 1000);
        eventForceSellMaxShares = clampI("eventForceSellMaxShares",
                has(t, "eventForceSellMaxShares", 1000), 10, 100000, 1000);
        eventDividendMaxPerShare = clampD("eventDividendMaxPerShare",
                has(t, "eventDividendMaxPerShare", 10.0), 0.1, 1000.0, 10.0);
        eventStockSplitEnabled = hasBoolean(t, "eventStockSplitEnabled", true);
        List<String> customEv = new ArrayList<>();
        Tag customEvTag = t.get("customEvents");
        if (customEvTag instanceof ListTag cl) {
            for (int i = 0; i < cl.size(); i++) {
                customEv.add(cl.getString(i));
            }
        }
        customEvents = List.copyOf(customEv);

        // ---------- category 19: particles (server-only reads; SyncConfigPacket never sends them)
        particleEnabled = hasBoolean(t, "particleEffectEnabled", true);
        particleOnTrade = hasBoolean(t, "particleEffectOnTrade", true);
        particleOnEvent = hasBoolean(t, "particleEffectOnEvent", true);
        particleOnShortOpen = hasBoolean(t, "particleEffectOnShortOpen", true);
        particleOnShortClose = hasBoolean(t, "particleEffectOnShortClose", true);
        particleOnBankruptcy = hasBoolean(t, "particleEffectOnBankruptcy", true);
        particleTypeBuy = hasString(t, "particleEffectTypeBuy", "minecraft:happy_villager");
        particleTypeSell = hasString(t, "particleEffectTypeSell", "minecraft:angry_villager");
        particleTypeEvent = hasString(t, "particleEffectTypeEvent", "minecraft:end_rod");
        particleTypeShortOpen = hasString(t, "particleEffectTypeShortOpen", "minecraft:smoke");
        particleTypeShortClose = hasString(t, "particleEffectTypeShortClose", "minecraft:cloud");
        particleTypeBankruptcy = hasString(t, "particleEffectTypeBankruptcy", "minecraft:explosion");
        particleCount = clampI("particleEffectCount", has(t, "particleEffectCount", 10), 1, 100, 10);
        particleSpread = clampD("particleEffectSpread", has(t, "particleEffectSpread", 0.5), 0.0, 5.0, 0.5);
        particleSpawnIntervalTicks = clampI("particleEffectSpawnIntervalTicks",
                has(t, "particleEffectSpawnIntervalTicks", 20), 1, 200, 20);
        particlePlaySoundEnabled = hasBoolean(t, "particleEffectPlaySoundEnabled", true);

        // ---------- category 20: UI theme; 24-bit RGB converted to opaque ARGB
        uiColors = new int[UI_COLOR_DEFAULTS.length];
        for (int i = 0; i < uiColors.length; i++) {
            int rgb = clampI("uiColor." + i, has(t, "uiColor." + i, UI_COLOR_DEFAULTS[i]),
                    0, 0xFFFFFF, UI_COLOR_DEFAULTS[i] & 0xFFFFFF);
            uiColors[i] = 0xFF000000 | rgb;
        }
        // ---------- category 21: screen feel
        uiScrollStep = clampI("uiScrollStep", has(t, "uiScrollStep", 20), 2, 100, 20);
        uiScrollStepFine = clampI("uiScrollStepFine", has(t, "uiScrollStepFine", 10), 2, 50, 10);
        uiDragThresholdPx = clampI("uiDragThresholdPx", has(t, "uiDragThresholdPx", 5), 0, 50, 5);
        uiSmoothScroll = hasBoolean(t, "uiSmoothScroll", true);
        uiScrollLerpPercent = clampI("uiScrollLerpPercent", has(t, "uiScrollLerpPercent", 15), 1, 100, 15);
        uiToastDurationMs = clampI("uiToastDurationMs", has(t, "uiToastDurationMs", 2000), 200, 60000, 2000);
        uiToastFadeMs = clampI("uiToastFadeMs", has(t, "uiToastFadeMs", 200), 0, 5000, 200);
        uiCardAnimMs = clampI("uiCardAnimMs", has(t, "uiCardAnimMs", 200), 0, 5000, 200);
        uiClickDebounceMs = clampI("uiClickDebounceMs", has(t, "uiClickDebounceMs", 200), 0, 5000, 200);
        // ---------- category 22: trade, event and chart display settings
        maxTradeQuantity = clampI("maxTradeQuantity", has(t, "maxTradeQuantity", 1_000_000), 1, 100_000_000, 1_000_000);
        logMaxEntries = clampI("logMaxEntries", has(t, "logMaxEntries", 100), 10, 1000, 100);
        eventMaxActive = clampI("eventMaxActive", has(t, "eventMaxActive", 3), 1, 10, 3);
        eventSuspendCooldownTicks = clampI("eventSuspendCooldownTicks", has(t, "eventSuspendCooldownTicks", 200), 0, 10000, 200);
        eventDividendCooldownDays = clampI("eventDividendCooldownDays", has(t, "eventDividendCooldownDays", 30), 0, 365, 30);
        eventSplitRatio = clampI("eventSplitRatio", has(t, "eventSplitRatio", 2), 2, 10, 2);
        klineTicksPerBar = clampI("klineTicksPerBar", has(t, "klineTicksPerBar", 10), 1, 200, 10);
        klineDisplayCount = clampI("klineDisplayCount", has(t, "klineDisplayCount", OreMarketConfig.DEFAULT_KLINE_DISPLAY_COUNT), 1, cn.oremarket.common.StockState.KLINE_LEN, OreMarketConfig.DEFAULT_KLINE_DISPLAY_COUNT);
        klineDefaultShort = hasBoolean(t, "klineDefaultShort", true);
        klineRealPlayerVolumeOnly = hasBoolean(t, "klineRealPlayerVolumeOnly", false);
        rarityWeights = new int[]{
                clampI("rarityWeightCommon", has(t, "rarityWeightCommon", 60), 0, 1000, 60),
                clampI("rarityWeightRare", has(t, "rarityWeightRare", 25), 0, 1000, 25),
                clampI("rarityWeightEpic", has(t, "rarityWeightEpic", 12), 0, 1000, 12),
                clampI("rarityWeightLegend", has(t, "rarityWeightLegend", 3), 0, 1000, 3)
        };
        // Event templates use the shared enum; both client and server refresh weights and border colours from the authoritative snapshot.
        cn.oremarket.common.Rarity.COMMON.weight = rarityWeights[0];
        cn.oremarket.common.Rarity.RARE.weight = rarityWeights[1];
        cn.oremarket.common.Rarity.EPIC.weight = rarityWeights[2];
        cn.oremarket.common.Rarity.LEGENDARY.weight = rarityWeights[3];
        cn.oremarket.common.Rarity.COMMON.borderColor = uiColors[UI_RARITY_COMMON];
        cn.oremarket.common.Rarity.RARE.borderColor = uiColors[UI_RARITY_RARE];
        cn.oremarket.common.Rarity.EPIC.borderColor = uiColors[UI_RARITY_EPIC];
        cn.oremarket.common.Rarity.LEGENDARY.borderColor = uiColors[UI_RARITY_LEGEND];

        warnExchangeAboveBase();
    }

    /** Returns the theme colour; illegal indexes safely fall back to the root panel colour. */
    public int uiColor(int index) {
        return index >= 0 && index < uiColors.length ? uiColors[index] : uiColors[UI_PANEL_ROOT];
    }

    public int rarityWeight(cn.oremarket.common.Rarity rarity) {
        int ordinal = rarity == null ? -1 : rarity.ordinal();
        return ordinal >= 0 && ordinal < rarityWeights.length ? rarityWeights[ordinal] : 0;
    }

    // ============================================================ snapshot accessors

    /** The server authority snapshot: reads and validates oremarket.toml (called on the server thread only). */
    public static MarketSettings server() {
        MarketSettings s = SERVER;
        if (s == null) {
            s = reloadServer();
            SERVER = s;
        }
        return s;
    }

    /** Config hot reload: rebuild the server snapshot (the caller guarantees the server main thread). */
    public static synchronized MarketSettings reloadServer() {
        MarketSettings s = fromConfig();
        SERVER = s;
        return s;
    }

    /** The client cache snapshot (possibly null: SyncConfigPacket not received yet, the UI shows loading). */
    public static MarketSettings client() {
        return CLIENT;
    }

    public static synchronized void setClient(MarketSettings s) {
        CLIENT = s;
    }

    public static boolean hasClient() {
        return CLIENT != null;
    }

    /**
     * Pick the snapshot by calling thread: the server thread gets the authority snapshot, the client thread gets the sync-packet cache.
     * In single-player both share one source and agree numerically. Before any snapshot is ready, the default snapshot is returned (no crash).
     */
    public static MarketSettings current() {
        if (isServerThread()) {
            return server();
        }
        MarketSettings c = CLIENT;
        return c != null ? c : defaults();
    }

    private static volatile Thread serverThread;

    /** Registers the server thread in ServerTickEvent so {@link #current()} can tell threads apart. */
    public static void markServerThread(Thread t) {
        serverThread = t;
    }

    /** On server stop, release the stale thread reference so switching saves never keeps an old server thread in static state. */
    public static void clearServerThread() {
        serverThread = null;
    }

    public static boolean isOnServerThread() {
        return isServerThread();
    }

    private static boolean isServerThread() {
        Thread s = serverThread;
        return s != null && Thread.currentThread() == s;
    }

    /** Build one snapshot from the Forge config. */
    private static MarketSettings fromConfig() {
        CompoundTag t = new CompoundTag();
        t.putLong("level2Threshold", OreMarketConfig.LEVEL2_THRESHOLD.get());
        t.putLong("level3Threshold", OreMarketConfig.LEVEL3_THRESHOLD.get());
        t.putLong("level4Threshold", OreMarketConfig.LEVEL4_THRESHOLD.get());
        t.putLong("exchangeRate.0", OreMarketConfig.EXCHANGE_COAL.get());
        t.putDouble("basePrice.0", OreMarketConfig.BASE_COAL.get());
        t.putLong("exchangeRate.1", OreMarketConfig.EXCHANGE_COPPER.get());
        t.putLong("exchangeRate.2", OreMarketConfig.EXCHANGE_IRON.get());
        t.putLong("exchangeRate.3", OreMarketConfig.EXCHANGE_GOLD.get());
        t.putLong("exchangeRate.4", OreMarketConfig.EXCHANGE_REDSTONE.get());
        t.putLong("exchangeRate.5", OreMarketConfig.EXCHANGE_LAPIS.get());
        t.putLong("exchangeRate.6", OreMarketConfig.EXCHANGE_DIAMOND.get());
        t.putLong("exchangeRate.7", OreMarketConfig.EXCHANGE_NETHERITE.get());
        t.putDouble("basePrice.1", OreMarketConfig.BASE_COPPER.get());
        t.putDouble("basePrice.2", OreMarketConfig.BASE_IRON.get());
        t.putDouble("basePrice.3", OreMarketConfig.BASE_GOLD.get());
        t.putDouble("basePrice.4", OreMarketConfig.BASE_REDSTONE.get());
        t.putDouble("basePrice.5", OreMarketConfig.BASE_LAPIS.get());
        t.putDouble("basePrice.6", OreMarketConfig.BASE_DIAMOND.get());
        t.putDouble("basePrice.7", OreMarketConfig.BASE_NETHERITE.get());
        t.putDouble("meanReversionFactor", OreMarketConfig.MEAN_REVERSION_FACTOR.get());
        t.putDouble("reversionDeadZoneFraction", OreMarketConfig.REVERSION_DEAD_ZONE_PERCENT.get() / 100.0);
        t.putDouble("randomFluctuation", OreMarketConfig.RANDOM_FLUCTUATION.get());
        t.putDouble("trendImpact", OreMarketConfig.TREND_IMPACT.get());
        t.putDouble("tradeImpactFactor", OreMarketConfig.TRADE_IMPACT_FACTOR.get());
        t.putBoolean("tradeSlippage", OreMarketConfig.TRADE_SLIPPAGE.get());
        t.putDouble("indexVolatilityWeight", OreMarketConfig.INDEX_VOLATILITY_WEIGHT.get());
        t.putDouble("priceFloorPercent", OreMarketConfig.PRICE_FLOOR_PERCENT.get());
        t.putDouble("priceCeilingPercent", OreMarketConfig.PRICE_CEILING_PERCENT.get());
        t.putInt("marketTickInterval", OreMarketConfig.MARKET_TICK_INTERVAL.get());
        t.putInt("tradingDayTicks", OreMarketConfig.TRADING_DAY_TICKS.get());
        t.putLong("creditLimitLevel2", OreMarketConfig.CREDIT_LIMIT_LEVEL2.get());
        t.putLong("creditLimitLevel3", OreMarketConfig.CREDIT_LIMIT_LEVEL3.get());
        t.putLong("creditLimitLevel4", OreMarketConfig.CREDIT_LIMIT_LEVEL4.get());
        t.putDouble("loanDailyInterestPercent", OreMarketConfig.LOAN_DAILY_INTEREST_PERCENT.get());
        t.putInt("loanTermDays", OreMarketConfig.LOAN_TERM_DAYS.get());
        t.putInt("bankruptcyCooldownDays", OreMarketConfig.BANKRUPTCY_COOLDOWN_DAYS.get());
        t.putDouble("loanInterestCapMultiplier", OreMarketConfig.LOAN_INTEREST_CAP_MULTIPLIER.get());
        t.putBoolean("loanAutoRepay", OreMarketConfig.LOAN_AUTO_REPAY.get());
        t.putInt("loanGracePeriodDays", OreMarketConfig.LOAN_GRACE_PERIOD_DAYS.get());
        t.putDouble("loanPenaltyPercent", OreMarketConfig.LOAN_PENALTY_PERCENT.get());
        t.putBoolean("loanCreditScoreEnabled", OreMarketConfig.LOAN_CREDIT_SCORE_ENABLED.get());
        t.putBoolean("loanP2PEnabled", OreMarketConfig.LOAN_P2P_ENABLED.get());
        t.putDouble("loanInterestVariance", OreMarketConfig.LOAN_INTEREST_VARIANCE.get());
        t.putDouble("loanRateCapPercent", OreMarketConfig.LOAN_RATE_CAP_PERCENT.get());
        t.putDouble("loanLimitAssetPercent", OreMarketConfig.LOAN_LIMIT_ASSET_PERCENT.get());
        t.putDouble("loanCollateralLimitFactor", OreMarketConfig.LOAN_COLLATERAL_LIMIT_FACTOR.get());
        t.putLong("loanEmergencyCap", OreMarketConfig.LOAN_EMERGENCY_CAP.get());
        t.putDouble("loanEmergencyRateFactor", OreMarketConfig.LOAN_EMERGENCY_RATE_FACTOR.get());
        t.putDouble("loanMarginRateFactor", OreMarketConfig.LOAN_MARGIN_RATE_FACTOR.get());
        t.putDouble("loanCollateralRateFactor", OreMarketConfig.LOAN_COLLATERAL_RATE_FACTOR.get());
        t.putDouble("loanP2PFeePercent", OreMarketConfig.LOAN_P2P_FEE_PERCENT.get());
        t.putInt("loanOverdueStrikeLimit", OreMarketConfig.LOAN_OVERDUE_STRIKE_LIMIT.get());
        t.putInt("loanClawbackDays", OreMarketConfig.LOAN_CLAWBACK_DAYS.get());
        t.putInt("loanLimitRecoverDays", OreMarketConfig.LOAN_LIMIT_RECOVER_DAYS.get());
        t.putDouble("loanPrepayDiscountPercent", OreMarketConfig.LOAN_PREPAY_DISCOUNT_PERCENT.get());
        t.putDouble("loanPrepayFeePercent", OreMarketConfig.LOAN_PREPAY_FEE_PERCENT.get());
        t.putInt("loanScorePerOnTime", OreMarketConfig.LOAN_SCORE_PER_ONTIME.get());
        t.putInt("loanScorePerOverdue", OreMarketConfig.LOAN_SCORE_PER_OVERDUE.get());
        t.putInt("loanScorePerBankrupt", OreMarketConfig.LOAN_SCORE_PER_BANKRUPT.get());
        t.putLong("loanScoreAssetStep", OreMarketConfig.LOAN_SCORE_ASSET_STEP.get());
        t.putInt("loanScorePerTier", OreMarketConfig.LOAN_SCORE_PER_TIER.get());
        t.putBoolean("loanDestroyPrincipal", OreMarketConfig.LOAN_DESTROY_PRINCIPAL.get());
        t.putBoolean("loanDestroyInterest", OreMarketConfig.LOAN_DESTROY_INTEREST.get());
        t.putInt("bankruptcyShortCooldown", OreMarketConfig.BANKRUPTCY_SHORT_COOLDOWN.get());
        t.putDouble("tradeFeePercent", OreMarketConfig.TRADE_FEE_PERCENT.get());
        t.putDouble("priceLimitPercent", OreMarketConfig.PRICE_LIMIT_PERCENT.get());
        t.putLong("startingCoins", OreMarketConfig.STARTING_COINS.get());
        t.putDouble("taxDestroyPercent", OreMarketConfig.TAX_DESTROY_PERCENT.get());
        t.putDouble("shortMarginRatio", OreMarketConfig.SHORT_MARGIN_RATIO.get());
        t.putDouble("shortLiquidationLine", OreMarketConfig.SHORT_LIQUIDATION_LINE.get());
        t.putDouble("shortDailyInterest", OreMarketConfig.SHORT_DAILY_INTEREST.get());
        t.putDouble("shortScarcityMultiplier", OreMarketConfig.SHORT_SCARCITY_MULTIPLIER.get());
        t.putDouble("shortLiquidationThreshold2", OreMarketConfig.SHORT_LIQUIDATION_THRESHOLD2.get());
        t.putBoolean("shortAffectPrice", OreMarketConfig.SHORT_AFFECT_PRICE.get());
        t.putDouble("shortCloseImpactMultiplier", OreMarketConfig.SHORT_CLOSE_IMPACT_MULTIPLIER.get());
        t.putString("shortReleaseMode", OreMarketConfig.SHORT_RELEASE_MODE.get());
        t.putInt("defaultBaseShares", cfg(OreMarketConfig.SHORT_POOL_DEFAULT_SHARES, 50000));
        t.putInt("absoluteMaxShares", cfg(OreMarketConfig.SHORT_POOL_ABSOLUTE_MAX, 100000));
        t.putInt("absoluteMinShares", cfg(OreMarketConfig.SHORT_POOL_ABSOLUTE_MIN, 100));
        t.putInt("orderMatchIntervalSeconds", OreMarketConfig.ORDER_MATCH_INTERVAL_SECONDS.get());
        t.putInt("orderMaxKeepDays", OreMarketConfig.ORDER_MAX_KEEP_DAYS.get());
        t.putInt("orderMax", OreMarketConfig.ORDER_MAX.get());
        t.putDouble("orderFeePercent", OreMarketConfig.ORDER_FEE_PERCENT.get());
        t.putBoolean("orderAffectPrice", OreMarketConfig.ORDER_AFFECT_PRICE.get());
        t.putInt("listIntervalDays", OreMarketConfig.LIST_INTERVAL_DAYS.get());
        t.putDouble("delistThresholdPercent", OreMarketConfig.DELIST_THRESHOLD_PERCENT.get());
        t.putInt("delistConsecutiveDays", OreMarketConfig.DELIST_CONSECUTIVE_DAYS.get());
        t.putInt("relistIntervalDays", OreMarketConfig.RELIST_INTERVAL_DAYS.get());
        t.putInt("listingMaxCount", OreMarketConfig.LISTING_MAX_COUNT.get());
        t.putBoolean("dynamicListingEnabled", OreMarketConfig.DYNAMIC_LISTING_ENABLED.get());
        t.putBoolean("builtinDynamicListing", OreMarketConfig.BUILTIN_DYNAMIC_LISTING.get());
        t.putInt("dynamicUnlockLevel", OreMarketConfig.DYNAMIC_UNLOCK_LEVEL.get());
        t.putDouble("dynamicBasePriceMultiplier", OreMarketConfig.DYNAMIC_BASE_PRICE_MULTIPLIER.get());
        ListTag dynCand = new ListTag();
        for (String line : cfgList(OreMarketConfig.DYNAMIC_CANDIDATES)) {
            dynCand.add(StringTag.valueOf(line));
        }
        t.put("dynamicCandidates", dynCand);
        t.putDouble("firstDayUpChance", OreMarketConfig.FIRST_DAY_UP_CHANCE.get());
        t.putDouble("firstDayDownChance", OreMarketConfig.FIRST_DAY_DOWN_CHANCE.get());
        t.putDouble("firstDayFlatChance", OreMarketConfig.FIRST_DAY_FLAT_CHANCE.get());
        t.putDouble("firstDayUpMin", OreMarketConfig.FIRST_DAY_UP_MIN.get());
        t.putDouble("firstDayUpMax", OreMarketConfig.FIRST_DAY_UP_MAX.get());
        t.putDouble("firstDayDownMin", OreMarketConfig.FIRST_DAY_DOWN_MIN.get());
        t.putDouble("firstDayDownMax", OreMarketConfig.FIRST_DAY_DOWN_MAX.get());
        t.putDouble("firstDayFlatRange", OreMarketConfig.FIRST_DAY_FLAT_RANGE.get());
        t.putInt("limitOrderMax", OreMarketConfig.LIMIT_ORDER_MAX.get());
        t.putDouble("limitOrderFeePercent", OreMarketConfig.LIMIT_ORDER_FEE_PERCENT.get());
        t.putInt("limitOrderMaxKeepDays", OreMarketConfig.LIMIT_ORDER_MAX_KEEP_DAYS.get());
        t.putInt("limitOrderFailRetries", OreMarketConfig.LIMIT_ORDER_FAIL_RETRIES.get());
        t.putDouble("limitOrderDeviationThreshold", OreMarketConfig.LIMIT_ORDER_DEVIATION_THRESHOLD.get());
        t.putInt("leaderboardSize", OreMarketConfig.LEADERBOARD_SIZE.get());
        t.putBoolean("leaderboardShowSelf", OreMarketConfig.LEADERBOARD_SHOW_SELF.get());
        int[] uiCfg = {
                cfg(OreMarketConfig.UI_PANEL_ROOT_RGB, 0x1A1A1A), cfg(OreMarketConfig.UI_PANEL_SOFT_RGB, 0x2B2B2B),
                cfg(OreMarketConfig.UI_PANEL_ROW_RGB, 0x3A3A3A), cfg(OreMarketConfig.UI_BORDER_RGB, 0x555555),
                cfg(OreMarketConfig.UI_DARK_RGB, 0x2A2A2A), cfg(OreMarketConfig.UI_HIGHLIGHT_RGB, 0x4A4A4A),
                cfg(OreMarketConfig.UI_ACCENT_RGB, 0x6A8FD0), cfg(OreMarketConfig.UI_GOLD_RGB, 0xFFD75F),
                cfg(OreMarketConfig.UI_MUTED_RGB, 0xAAAAAA), cfg(OreMarketConfig.UI_GRAY_RGB, 0x888888),
                cfg(OreMarketConfig.UI_DIM_RGB, 0x666666), cfg(OreMarketConfig.UI_QUOTE_UP_RGB, 0xFF6F6F),
                cfg(OreMarketConfig.UI_QUOTE_DOWN_RGB, 0x6FE06F), cfg(OreMarketConfig.UI_QUOTE_FLAT_RGB, 0xBDBDBD),
                cfg(OreMarketConfig.UI_PNL_UP_RGB, 0xE04A4A), cfg(OreMarketConfig.UI_PNL_DOWN_RGB, 0x3FBF5F),
                cfg(OreMarketConfig.UI_SELECTED_RGB, 0x4A5A7A), cfg(OreMarketConfig.UI_HOVER_RGB, 0x404040),
                cfg(OreMarketConfig.UI_BLUE_RGB, 0x3A3A5A), cfg(OreMarketConfig.UI_BUY_RGB, 0x2E5A3A),
                cfg(OreMarketConfig.UI_TOAST_BG_RGB, 0x0E0E0E), cfg(OreMarketConfig.UI_TOAST_FG_RGB, 0xFF5555),
                cfg(OreMarketConfig.UI_KGRID_RGB, 0x333333), cfg(OreMarketConfig.UI_CHART_BG_RGB, 0x151515),
                cfg(OreMarketConfig.UI_LETTERBOX_RGB, 0x0D0D10), cfg(OreMarketConfig.UI_RARITY_COMMON_RGB, 0x888888),
                cfg(OreMarketConfig.UI_RARITY_RARE_RGB, 0x4A7FD0), cfg(OreMarketConfig.UI_RARITY_EPIC_RGB, 0x9A4AD0),
                cfg(OreMarketConfig.UI_RARITY_LEGEND_RGB, 0xD0A040),
                cfg(OreMarketConfig.UI_TEXT_RGB, 0xFFFFFF), cfg(OreMarketConfig.UI_TEXT_SOFT_RGB, 0xC9C9D9),
                cfg(OreMarketConfig.UI_TIP_TEXT_RGB, 0xE8E8E8), cfg(OreMarketConfig.UI_BODY_TEXT_RGB, 0xCCCCCC),
                cfg(OreMarketConfig.UI_WICK_UP_RGB, 0xCC3A3A), cfg(OreMarketConfig.UI_WICK_DOWN_RGB, 0x2F9F4F),
                cfg(OreMarketConfig.UI_BUTTON_HOVER_RGB, 0x4E4E4E), cfg(OreMarketConfig.UI_DEPOSIT_HOVER_RGB, 0x3F7A50),
                cfg(OreMarketConfig.UI_WITHDRAW_HOVER_RGB, 0x50507A), cfg(OreMarketConfig.UI_WARNING_RGB, 0xFF5F5F),
                cfg(OreMarketConfig.UI_HINT_GRAY_RGB, 0x9A9AA5), cfg(OreMarketConfig.UI_GOLD_TEXT_RGB, 0xFFE89A),
                cfg(OreMarketConfig.UI_CHIP_BLUE_RGB, 0x44506A), cfg(OreMarketConfig.UI_ACCENT_ORANGE_RGB, 0xFFB060),
                cfg(OreMarketConfig.UI_COIN_DARK_RGB, 0xB8860B), cfg(OreMarketConfig.UI_COIN_LIGHT_RGB, 0xDAA520)
        };
        for (int i = 0; i < uiCfg.length; i++) t.putInt("uiColor." + i, uiCfg[i]);
        t.putInt("uiScrollStep", cfg(OreMarketConfig.UI_SCROLL_STEP, 20));
        t.putInt("uiScrollStepFine", cfg(OreMarketConfig.UI_SCROLL_STEP_FINE, 10));
        t.putInt("uiDragThresholdPx", cfg(OreMarketConfig.UI_DRAG_THRESHOLD_PX, 5));
        t.putBoolean("uiSmoothScroll", cfg(OreMarketConfig.UI_SMOOTH_SCROLL, true));
        t.putInt("uiScrollLerpPercent", cfg(OreMarketConfig.UI_SCROLL_LERP_PERCENT, 15));
        t.putInt("uiToastDurationMs", cfg(OreMarketConfig.UI_TOAST_DURATION_MS, 2000));
        t.putInt("uiToastFadeMs", cfg(OreMarketConfig.UI_TOAST_FADE_MS, 200));
        t.putInt("uiCardAnimMs", cfg(OreMarketConfig.UI_CARD_ANIM_MS, 200));
        t.putInt("uiClickDebounceMs", cfg(OreMarketConfig.UI_CLICK_DEBOUNCE_MS, 200));
        t.putInt("maxTradeQuantity", cfg(OreMarketConfig.MAX_TRADE_QUANTITY, 1_000_000));
        t.putInt("logMaxEntries", cfg(OreMarketConfig.LOG_MAX_ENTRIES, 100));
        t.putInt("eventMaxActive", cfg(OreMarketConfig.EVENT_MAX_ACTIVE, 3));
        t.putInt("eventSuspendCooldownTicks", cfg(OreMarketConfig.EVENT_SUSPEND_COOLDOWN_TICKS, 200));
        t.putInt("eventDividendCooldownDays", cfg(OreMarketConfig.EVENT_DIVIDEND_COOLDOWN_DAYS, 30));
        t.putInt("eventSplitRatio", cfg(OreMarketConfig.EVENT_SPLIT_RATIO, 2));
        t.putInt("klineTicksPerBar", cfg(OreMarketConfig.KLINE_TICKS_PER_BAR, 10));
        t.putInt("klineDisplayCount", cfg(OreMarketConfig.KLINE_DISPLAY_COUNT, OreMarketConfig.DEFAULT_KLINE_DISPLAY_COUNT));
        t.putBoolean("klineDefaultShort", cfg(OreMarketConfig.KLINE_DEFAULT_SHORT, true));
        t.putBoolean("klineRealPlayerVolumeOnly", cfg(OreMarketConfig.KLINE_REAL_PLAYER_VOLUME_ONLY, false));
        t.putInt("rarityWeightCommon", cfg(OreMarketConfig.RARITY_WEIGHT_COMMON, 60));
        t.putInt("rarityWeightRare", cfg(OreMarketConfig.RARITY_WEIGHT_RARE, 25));
        t.putInt("rarityWeightEpic", cfg(OreMarketConfig.RARITY_WEIGHT_EPIC, 12));
        t.putInt("rarityWeightLegend", cfg(OreMarketConfig.RARITY_WEIGHT_LEGEND, 3));
        t.putDouble("eventTriggerChance", OreMarketConfig.EVENT_TRIGGER_CHANCE.get());
        t.putInt("legendCooldownDays", OreMarketConfig.LEGEND_COOLDOWN_DAYS.get());
        t.putInt("eventDurationCommonMinSec", OreMarketConfig.EVENT_DURATION_COMMON_MIN.get());
        t.putInt("eventDurationCommonMaxSec", OreMarketConfig.EVENT_DURATION_COMMON_MAX.get());
        t.putInt("eventDurationRareMinSec", OreMarketConfig.EVENT_DURATION_RARE_MIN.get());
        t.putInt("eventDurationRareMaxSec", OreMarketConfig.EVENT_DURATION_RARE_MAX.get());
        t.putInt("eventDurationEpicMinSec", OreMarketConfig.EVENT_DURATION_EPIC_MIN.get());
        t.putInt("eventDurationEpicMaxSec", OreMarketConfig.EVENT_DURATION_EPIC_MAX.get());
        t.putInt("eventDurationLegendMinSec", OreMarketConfig.EVENT_DURATION_LEGEND_MIN.get());
        t.putInt("eventDurationLegendMaxSec", OreMarketConfig.EVENT_DURATION_LEGEND_MAX.get());
        t.putInt("eventHaltMaxSeconds", OreMarketConfig.EVENT_HALT_MAX_SECONDS.get());
        t.putInt("eventPriceLimitMaxSeconds", OreMarketConfig.EVENT_PRICE_LIMIT_MAX_SECONDS.get());
        t.putDouble("eventPriceMultMin", OreMarketConfig.EVENT_PRICE_MULT_MIN.get());
        t.putDouble("eventPriceMultMax", OreMarketConfig.EVENT_PRICE_MULT_MAX.get());
        t.putDouble("eventVolatilityMin", OreMarketConfig.EVENT_VOLATILITY_MIN.get());
        t.putDouble("eventVolatilityMax", OreMarketConfig.EVENT_VOLATILITY_MAX.get());
        t.putDouble("eventMeanReversionMin", OreMarketConfig.EVENT_MEAN_REVERSION_MIN.get());
        t.putDouble("eventMeanReversionMax", OreMarketConfig.EVENT_MEAN_REVERSION_MAX.get());
        t.putDouble("eventFeeMin", OreMarketConfig.EVENT_FEE_MIN.get());
        t.putDouble("eventFeeMax", OreMarketConfig.EVENT_FEE_MAX.get());
        t.putDouble("eventTradeImpactMin", OreMarketConfig.EVENT_TRADE_IMPACT_MIN.get());
        t.putDouble("eventTradeImpactMax", OreMarketConfig.EVENT_TRADE_IMPACT_MAX.get());
        t.putDouble("eventPriceLimitMin", OreMarketConfig.EVENT_PRICE_LIMIT_MIN.get());
        t.putDouble("eventPriceLimitMax", OreMarketConfig.EVENT_PRICE_LIMIT_MAX.get());
        // ---------- category 18: event extensions (ConfigValue.get() null checks: null always falls back to safe defaults)
        t.putBoolean("eventCombinationEnabled", cfg(OreMarketConfig.EVENT_COMBINATION_ENABLED, true));
        t.putBoolean("eventChainEnabled", cfg(OreMarketConfig.EVENT_CHAIN_ENABLED, true));
        t.putBoolean("eventConflictEnabled", cfg(OreMarketConfig.EVENT_CONFLICT_ENABLED, true));
        t.putBoolean("eventPreviewEnabled", cfg(OreMarketConfig.EVENT_PREVIEW_ENABLED, true));
        t.putDouble("eventPreviewAccuracy", cfg(OreMarketConfig.EVENT_PREVIEW_ACCURACY, 0.80));
        t.putBoolean("eventFakeNewsEnabled", cfg(OreMarketConfig.EVENT_FAKE_NEWS_ENABLED, true));
        t.putBoolean("eventPlayerTriggerEnabled", cfg(OreMarketConfig.EVENT_PLAYER_TRIGGER_ENABLED, true));
        t.putInt("eventPlayerTriggerVolume", cfg(OreMarketConfig.EVENT_PLAYER_TRIGGER_VOLUME, 1000));
        t.putBoolean("eventCardAnimationEnabled", cfg(OreMarketConfig.EVENT_CARD_ANIMATION_ENABLED, true));
        t.putBoolean("eventFrequencyDynamicEnabled", cfg(OreMarketConfig.EVENT_FREQUENCY_DYNAMIC_ENABLED, true));
        t.putInt("eventFrequencyHighThreshold", cfg(OreMarketConfig.EVENT_FREQUENCY_HIGH_THRESHOLD, 5));
        t.putInt("eventFrequencyLowThreshold", cfg(OreMarketConfig.EVENT_FREQUENCY_LOW_THRESHOLD, 0));
        t.putDouble("eventEffectCapMin", cfg(OreMarketConfig.EVENT_EFFECT_MULTIPLIER_CAP_MIN, 0.5));
        t.putDouble("eventEffectCapMax", cfg(OreMarketConfig.EVENT_EFFECT_MULTIPLIER_CAP_MAX, 2.0));
        t.putBoolean("eventLegendCooldownHintEnabled", cfg(OreMarketConfig.EVENT_LEGEND_COOLDOWN_HINT_ENABLED, true));
        t.putInt("exchangeRowSelectedColor", cfg(OreMarketConfig.EXCHANGE_ROW_SELECTED_COLOR, 0x4A5A7A));
        t.putInt("eventForceBuyMaxShares", cfg(OreMarketConfig.EVENT_FORCE_BUY_MAX_SHARES, 1000));
        t.putInt("eventForceSellMaxShares", cfg(OreMarketConfig.EVENT_FORCE_SELL_MAX_SHARES, 1000));
        t.putDouble("eventDividendMaxPerShare", cfg(OreMarketConfig.EVENT_DIVIDEND_MAX_PER_SHARE, 10.0));
        t.putBoolean("eventStockSplitEnabled", cfg(OreMarketConfig.EVENT_STOCK_SPLIT_ENABLED, true));
        ListTag customEv = new ListTag();
        for (String line : cfgList(OreMarketConfig.CUSTOM_EVENTS)) {
            customEv.add(StringTag.valueOf(line));
        }
        t.put("customEvents", customEv);
        // ---------- category 19: particles (held only by the server snapshot; writeTo never ships them to the client)
        t.putBoolean("particleEffectEnabled", cfg(OreMarketConfig.PARTICLE_EFFECT_ENABLED, true));
        t.putBoolean("particleEffectOnTrade", cfg(OreMarketConfig.PARTICLE_EFFECT_ON_TRADE, true));
        t.putBoolean("particleEffectOnEvent", cfg(OreMarketConfig.PARTICLE_EFFECT_ON_EVENT, true));
        t.putBoolean("particleEffectOnShortOpen", cfg(OreMarketConfig.PARTICLE_EFFECT_ON_SHORT_OPEN, true));
        t.putBoolean("particleEffectOnShortClose", cfg(OreMarketConfig.PARTICLE_EFFECT_ON_SHORT_CLOSE, true));
        t.putBoolean("particleEffectOnBankruptcy", cfg(OreMarketConfig.PARTICLE_EFFECT_ON_BANKRUPTCY, true));
        t.putString("particleEffectTypeBuy", cfg(OreMarketConfig.PARTICLE_EFFECT_TYPE_BUY, "minecraft:happy_villager"));
        t.putString("particleEffectTypeSell", cfg(OreMarketConfig.PARTICLE_EFFECT_TYPE_SELL, "minecraft:angry_villager"));
        t.putString("particleEffectTypeEvent", cfg(OreMarketConfig.PARTICLE_EFFECT_TYPE_EVENT, "minecraft:end_rod"));
        t.putString("particleEffectTypeShortOpen", cfg(OreMarketConfig.PARTICLE_EFFECT_TYPE_SHORT_OPEN, "minecraft:smoke"));
        t.putString("particleEffectTypeShortClose", cfg(OreMarketConfig.PARTICLE_EFFECT_TYPE_SHORT_CLOSE, "minecraft:cloud"));
        t.putString("particleEffectTypeBankruptcy", cfg(OreMarketConfig.PARTICLE_EFFECT_TYPE_BANKRUPTCY, "minecraft:explosion"));
        t.putInt("particleEffectCount", cfg(OreMarketConfig.PARTICLE_EFFECT_COUNT, 10));
        t.putDouble("particleEffectSpread", cfg(OreMarketConfig.PARTICLE_EFFECT_SPREAD, 0.5));
        t.putInt("particleEffectSpawnIntervalTicks", cfg(OreMarketConfig.PARTICLE_EFFECT_SPAWN_INTERVAL_TICKS, 20));
        t.putBoolean("particleEffectPlaySoundEnabled", cfg(OreMarketConfig.PARTICLE_EFFECT_PLAY_SOUND_ENABLED, true));
        // category 10: raw custom-stock strings (held only by the server snapshot; writeTo no longer pushes them to the client)
        ListTag custom = new ListTag();
        for (String line : cfgList(OreMarketConfig.CUSTOM_STOCKS)) {
            custom.add(StringTag.valueOf(line));
        }
        t.put("customStocks", custom);
        return new MarketSettings(t);
    }

    /** Fallback default snapshot (for when config was not read yet or the client has no sync packet; all design values). */
    public static MarketSettings defaults() {
        return SERVER != null ? SERVER : CLIENT != null ? CLIENT : FALLBACK;
    }

    private static final MarketSettings FALLBACK = new MarketSettings(defaultTag());

    /** The all-defaults CompoundTag (the client sync path takes defaults from it when fields are missing). */
    private static CompoundTag defaultTag() {
        CompoundTag t = defaultTagRaw();
        return t;
    }

    // ============================================================ serialisation (CompoundTag for network packets)

    /** Whole snapshot -> CompoundTag (SyncConfigPacket writes it into the packet directly). */
    public CompoundTag writeTo(CompoundTag t) {
        t.putLong("level2Threshold", level2Threshold);
        t.putLong("level3Threshold", level3Threshold);
        t.putLong("level4Threshold", level4Threshold);
        for (int i = 0; i < BUILTIN_COUNT; i++) {
            t.put("exchangeRate." + i, LongTag.valueOf(exchangeRates[i]));
            t.put("basePrice." + i, DoubleTag.valueOf(basePrices[i]));
        }
        t.putDouble("meanReversionFactor", meanReversionFactor);
        t.putDouble("reversionDeadZoneFraction", reversionDeadZoneFraction);
        t.putDouble("randomFluctuation", randomFluctuation);
        t.putDouble("trendImpact", trendImpact);
        t.putDouble("tradeImpactFactor", tradeImpactFactor);
        t.putBoolean("tradeSlippage", tradeSlippage);
        t.putDouble("indexVolatilityWeight", indexVolatilityWeight);
        t.putDouble("priceFloorPercent", priceFloorPercent);
        t.putDouble("priceCeilingPercent", priceCeilingPercent);
        t.putInt("marketTickInterval", marketTickInterval);
        t.putInt("tradingDayTicks", tradingDayTicks);
        t.putLong("creditLimitLevel2", creditLimitLevel2);
        t.putLong("creditLimitLevel3", creditLimitLevel3);
        t.putLong("creditLimitLevel4", creditLimitLevel4);
        t.putDouble("loanDailyInterestPercent", loanDailyInterestPercent * 100.0);
        t.putInt("loanTermDays", loanTermDays);
        t.putInt("bankruptcyCooldownDays", bankruptcyCooldownDays);
        t.putDouble("loanInterestCapMultiplier", loanInterestCapMultiplier);
        t.putBoolean("loanAutoRepay", loanAutoRepay);
        t.putInt("loanGracePeriodDays", loanGracePeriodDays);
        t.putDouble("loanPenaltyPercent", loanPenaltyPercent * 100.0);
        t.putBoolean("loanCreditScoreEnabled", loanCreditScoreEnabled);
        t.putBoolean("loanP2PEnabled", loanP2PEnabled);
        t.putDouble("loanInterestVariance", loanInterestVariance);
        t.putDouble("loanRateCapPercent", loanRateCapPercent);
        t.putDouble("loanLimitAssetPercent", loanLimitAssetPercent);
        t.putDouble("loanCollateralLimitFactor", loanCollateralLimitFactor);
        t.putLong("loanEmergencyCap", loanEmergencyCap);
        t.putDouble("loanEmergencyRateFactor", loanEmergencyRateFactor);
        t.putDouble("loanMarginRateFactor", loanMarginRateFactor);
        t.putDouble("loanCollateralRateFactor", loanCollateralRateFactor);
        t.putDouble("loanP2PFeePercent", loanP2PFeePercent);
        t.putInt("loanOverdueStrikeLimit", loanOverdueStrikeLimit);
        t.putInt("loanClawbackDays", loanClawbackDays);
        t.putInt("loanLimitRecoverDays", loanLimitRecoverDays);
        t.putDouble("loanPrepayDiscountPercent", loanPrepayDiscountPercent);
        t.putDouble("loanPrepayFeePercent", loanPrepayFeePercent);
        t.putInt("loanScorePerOnTime", loanScorePerOnTime);
        t.putInt("loanScorePerOverdue", loanScorePerOverdue);
        t.putInt("loanScorePerBankrupt", loanScorePerBankrupt);
        t.putLong("loanScoreAssetStep", loanScoreAssetStep);
        t.putInt("loanScorePerTier", loanScorePerTier);
        t.putDouble("tradeFeePercent", tradeFeePercent * 100.0);
        t.putDouble("priceLimitPercent", priceLimitPercent * 100.0);
        t.putLong("startingCoins", startingCoins);
        t.putDouble("taxDestroyPercent", taxDestroyRate * 100.0);
        t.putDouble("shortMarginRatio", shortMarginRatio);
        t.putDouble("shortLiquidationLine", shortLiquidationRatio);
        t.putDouble("shortDailyInterest", shortDailyRate);
        t.putDouble("shortScarcityMultiplier", shortScarcityMultiplier);
        t.putDouble("shortLiquidationThreshold2", shortLiquidationThreshold2);
        t.putBoolean("shortAffectPrice", shortAffectPrice);
        t.putDouble("shortCloseImpactMultiplier", shortCloseImpactMultiplier);
        t.putString("shortReleaseMode", shortReleaseMode);
        t.putInt("defaultBaseShares", shortPoolDefaultShares);
        t.putInt("absoluteMaxShares", shortPoolAbsoluteMax);
        t.putInt("absoluteMinShares", shortPoolAbsoluteMin);
        t.putInt("orderMatchIntervalSeconds", orderMatchIntervalSeconds);
        t.putInt("orderMaxKeepDays", orderMaxKeepDays);
        t.putInt("orderMax", orderMax);
        t.putDouble("orderFeePercent", orderFeeRate * 100.0);
        t.putBoolean("orderAffectPrice", orderAffectPrice);
        t.putInt("listIntervalDays", listIntervalDays);
        t.putDouble("delistThresholdPercent", delistThresholdRatio * 100.0);
        t.putInt("delistConsecutiveDays", delistConsecutiveDays);
        t.putInt("relistIntervalDays", relistIntervalDays);
        t.putInt("listingMaxCount", listingMaxCount);
        t.putBoolean("dynamicListingEnabled", dynamicListingEnabled);
        t.putBoolean("builtinDynamicListing", builtinDynamicListing);
        t.putInt("dynamicUnlockLevel", dynamicUnlockLevel);
        t.putDouble("dynamicBasePriceMultiplier", dynamicBasePriceMultiplier);
        ListTag dynCandOut = new ListTag();
        for (String line : dynamicCandidates) {
            dynCandOut.add(StringTag.valueOf(line));
        }
        t.put("dynamicCandidates", dynCandOut);
        t.putDouble("firstDayUpChance", firstDayUpChance);
        t.putDouble("firstDayDownChance", firstDayDownChance);
        t.putDouble("firstDayFlatChance", firstDayFlatChance);
        t.putDouble("firstDayUpMin", firstDayUpMin);
        t.putDouble("firstDayUpMax", firstDayUpMax);
        t.putDouble("firstDayDownMin", firstDayDownMin);
        t.putDouble("firstDayDownMax", firstDayDownMax);
        t.putDouble("firstDayFlatRange", firstDayFlatRange);
        t.putInt("limitOrderMax", limitOrderMax);
        t.putDouble("limitOrderFeePercent", limitOrderFeeRate * 100.0);
        t.putInt("limitOrderMaxKeepDays", limitOrderMaxKeepDays);
        t.putInt("limitOrderFailRetries", limitOrderFailRetries);
        t.putDouble("limitOrderDeviationThreshold", limitOrderDeviationThreshold);
        t.putBoolean("loanDestroyPrincipal", loanDestroyPrincipal);
        t.putBoolean("loanDestroyInterest", loanDestroyInterest);
        t.putInt("bankruptcyShortCooldown", bankruptcyShortCooldown);
        t.putInt("leaderboardSize", leaderboardSize);
        t.putBoolean("leaderboardShowSelf", leaderboardShowSelf);
        for (int i = 0; i < uiColors.length; i++) t.putInt("uiColor." + i, uiColors[i] & 0xFFFFFF);
        t.putInt("uiScrollStep", uiScrollStep);
        t.putInt("uiScrollStepFine", uiScrollStepFine);
        t.putInt("uiDragThresholdPx", uiDragThresholdPx);
        t.putBoolean("uiSmoothScroll", uiSmoothScroll);
        t.putInt("uiScrollLerpPercent", uiScrollLerpPercent);
        t.putInt("uiToastDurationMs", uiToastDurationMs);
        t.putInt("uiToastFadeMs", uiToastFadeMs);
        t.putInt("uiCardAnimMs", uiCardAnimMs);
        t.putInt("uiClickDebounceMs", uiClickDebounceMs);
        t.putInt("maxTradeQuantity", maxTradeQuantity);
        t.putInt("logMaxEntries", logMaxEntries);
        t.putInt("eventMaxActive", eventMaxActive);
        t.putInt("eventSuspendCooldownTicks", eventSuspendCooldownTicks);
        t.putInt("eventDividendCooldownDays", eventDividendCooldownDays);
        t.putInt("eventSplitRatio", eventSplitRatio);
        t.putInt("klineTicksPerBar", klineTicksPerBar);
        t.putInt("klineDisplayCount", klineDisplayCount);
        t.putBoolean("klineDefaultShort", klineDefaultShort);
        t.putBoolean("klineRealPlayerVolumeOnly", klineRealPlayerVolumeOnly);
        t.putInt("rarityWeightCommon", rarityWeights[0]);
        t.putInt("rarityWeightRare", rarityWeights[1]);
        t.putInt("rarityWeightEpic", rarityWeights[2]);
        t.putInt("rarityWeightLegend", rarityWeights[3]);
        t.putDouble("eventTriggerChance", eventTriggerChance * 100.0);
        t.putInt("legendCooldownDays", legendCooldownDays);
        t.putInt("eventDurationCommonMinSec", eventDurationCommonMinSec);
        t.putInt("eventDurationCommonMaxSec", eventDurationCommonMaxSec);
        t.putInt("eventDurationRareMinSec", eventDurationRareMinSec);
        t.putInt("eventDurationRareMaxSec", eventDurationRareMaxSec);
        t.putInt("eventDurationEpicMinSec", eventDurationEpicMinSec);
        t.putInt("eventDurationEpicMaxSec", eventDurationEpicMaxSec);
        t.putInt("eventDurationLegendMinSec", eventDurationLegendMinSec);
        t.putInt("eventDurationLegendMaxSec", eventDurationLegendMaxSec);
        t.putInt("eventHaltMaxSeconds", eventHaltMaxSeconds);
        t.putInt("eventPriceLimitMaxSeconds", eventPriceLimitMaxSeconds);
        t.putDouble("eventPriceMultMin", eventPriceMultMin);
        t.putDouble("eventPriceMultMax", eventPriceMultMax);
        t.putDouble("eventVolatilityMin", eventVolatilityMin);
        t.putDouble("eventVolatilityMax", eventVolatilityMax);
        t.putDouble("eventMeanReversionMin", eventMeanReversionMin);
        t.putDouble("eventMeanReversionMax", eventMeanReversionMax);
        t.putDouble("eventFeeMin", eventFeeMin);
        t.putDouble("eventFeeMax", eventFeeMax);
        t.putDouble("eventTradeImpactMin", eventTradeImpactMin);
        t.putDouble("eventTradeImpactMax", eventTradeImpactMax);
        t.putDouble("eventPriceLimitMin", eventPriceLimitMin * 100.0);
        t.putDouble("eventPriceLimitMax", eventPriceLimitMax * 100.0);
        // ---------- category 18 (push only the switches the client UI needs; raw customEvents strings and particle parameters stay server-side)
        t.putBoolean("eventCardAnimationEnabled", eventCardAnimationEnabled);
        t.putBoolean("eventLegendCooldownHintEnabled", eventLegendCooldownHintEnabled);
        t.putInt("exchangeRowSelectedColor", exchangeRowSelectedColor);
        t.putDouble("eventEffectCapMin", eventEffectCapMin);
        t.putDouble("eventEffectCapMax", eventEffectCapMax);
        // The parsed stock registry (server authority snapshot, custom stocks included): the client builds its local list straight from it,
        // reparsing no strings; the ListTag is dynamically sized, never a fixed 8-element array.
        t.put("stockRegistry", StockRegistry.toTag());
        // SyncConfigPacket never pushes the customStocks raw strings (the client neither needs nor may read them)
        return t;
    }

    /** CompoundTag -> snapshot (missing fields always take defaults, never throw). */
    public static MarketSettings readFrom(CompoundTag in) {
        CompoundTag t = in == null ? defaultTagRaw() : in;
        // when a field is missing the Tag read returns 0; the constructor clamps 0 back to the default
        return new MarketSettings(t);
    }

    /** Read the stockRegistry entries into a list (client-side; only the raw tag is returned here, parsing happens in ClientConfigCache). */
    public static List<CompoundTag> readRegistry(CompoundTag in) {
        List<CompoundTag> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        Tag raw = in.get("stockRegistry");
        if (!(raw instanceof ListTag list)) {
            return out;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof CompoundTag c) {
                out.add(c);
            }
        }
        return out;
    }

    // ============================================================ derived values

    /** Level thresholds: level 1 is always 0; levels 2/3/4 read the live config values. */
    public long levelThreshold(int level) {
        return switch (level) {
            case 2 -> level2Threshold;
            case 3 -> level3Threshold;
            case 4 -> level4Threshold;
            default -> 0L;
        };
    }

    /** Realtime level: compares the coin peak against current thresholds (for decisions only, never display). */
    public int levelForPeak(long peak) {
        int level = 1;
        if (peak >= level4Threshold) {
            level = 4;
        } else if (peak >= level3Threshold) {
            level = 3;
        } else if (peak >= level2Threshold) {
            level = 2;
        }
        return level;
    }

    /** Credit line: level 1 gets 0 (no borrowing); levels 2/3/4 read config. */
    public long creditLimit(int level) {
        return switch (level) {
            case 2 -> creditLimitLevel2;
            case 3 -> creditLimitLevel3;
            case 4 -> creditLimitLevel4;
            default -> 0L;
        };
    }

    public long exchangeRate(Stock s) {
        // registry instances (built-ins rebuilt from config, customs parsed from strings) already carry final values; read fields directly
        return s == null ? 0L : s.exchangeRate();
    }

    public double basePrice(Stock s) {
        return s == null ? 0.0 : s.basePrice();
    }

    /** Exchange points of a built-in stock in category 2/3 config (codes outside the 8 built-ins return 1). */
    public long builtinExchangeRate(String code) {
        int i = Stock.BUILTIN_CODES.indexOf(code);
        return i >= 0 ? exchangeRates[i] : 1L;
    }

    /** Base price of a built-in stock in category 3 config (codes outside the 8 built-ins return 1.0). */
    public double builtinBasePrice(String code) {
        int i = Stock.BUILTIN_CODES.indexOf(code);
        return i >= 0 ? basePrices[i] : 1.0;
    }

    /** Trade fee rate (fraction of the trade subtotal). */
    public double feeRate() {
        return tradeFeePercent;
    }

    /** Burn tax rate (fraction): transaction tax is burned outright, never entering any pool. */
    public double taxRate() {
        return taxDestroyRate;
    }

    /** true = close releases by quantity fraction + settles P&L (withPnl); false = releases by quantity fraction only (quantity). */
    public boolean shortReleaseWithPnl() {
        return "withPnl".equals(shortReleaseMode);
    }

    /**
     * System trade total rate: fee rate + burn tax minus their product.
     * Default fee 2% + burn tax 1% -> 0.0298. Event modifiers change only the fee component.
     */
    public double totalRate(double effectiveFeeRate) {
        return Fees.combined(effectiveFeeRate, taxRate());
    }

    /** Coins actually charged on a buy = ceil(price x n x (1 + total rate)). */
    public long buyCost(double price, int qty, double effectiveFeeRate) {
        return Fees.buyCost(price, qty, totalRate(effectiveFeeRate));
    }

    /** Coins actually received on a sell = floor(price x n x (1 - total rate)). */
    public long sellGain(double price, int qty, double effectiveFeeRate) {
        return Fees.sellGain(price, qty, totalRate(effectiveFeeRate));
    }

    /** Daily price limit fraction (decimal). */
    public double priceLimitFraction() {
        return priceLimitPercent;
    }

    /** Order matching interval (seconds -> marketTick count, ceil, at least 1). */
    public int orderMatchIntervalTicks() {
        return Math.max(1, (int) Math.ceil(orderMatchIntervalSeconds * 20.0 / marketTickInterval));
    }

    /** Price floor = base price x floor%. */
    public double priceFloor(double base) {
        return base * priceFloorPercent / 100.0;
    }

    /** Price ceiling = base price x ceiling%. */
    public double priceCeiling(double base) {
        return base * priceCeilingPercent / 100.0;
    }

    /** How many marketTicks a trading day holds (floor, at least 1). */
    public int marketTicksPerTradeDay() {
        return Math.max(1, tradingDayTicks / marketTickInterval);
    }

    /** Seconds -> marketTick: ceil(seconds x 20 / marketTickInterval), minimum 1. */
    public int secondsToMarketTicks(double seconds) {
        double ticks = Math.ceil(Math.max(0.0, seconds) * 20.0 / marketTickInterval);
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, ticks));
    }

    /** marketTick -> seconds (for display). */
    public double marketTicksToSeconds(long ticks) {
        return (double) ticks * marketTickInterval / 20.0;
    }

    /** Duration-seconds band per rarity. */
    public int[] durationSecondsRange(cn.oremarket.common.Rarity r) {
        return switch (r) {
            case COMMON -> new int[]{eventDurationCommonMinSec, eventDurationCommonMaxSec};
            case RARE -> new int[]{eventDurationRareMinSec, eventDurationRareMaxSec};
            case EPIC -> new int[]{eventDurationEpicMinSec, eventDurationEpicMaxSec};
            case LEGENDARY -> new int[]{eventDurationLegendMinSec, eventDurationLegendMaxSec};
        };
    }

    /**
     * Category 9: the value band of one effect family (drawn parameters are clamped into it).
     * SHOCK uses the trade-impact-multiplier band, SUSPEND the duration band (ticks), TREND carries only a direction.
     */
    public double[] effectRange(cn.oremarket.common.EffectType type) {
        return switch (type) {
            case PRICE_MULT -> new double[]{eventPriceMultMin, eventPriceMultMax};
            case VOLATILITY -> new double[]{eventVolatilityMin, eventVolatilityMax};
            case SHOCK -> new double[]{eventTradeImpactMin, eventTradeImpactMax};
            case REVERSION -> new double[]{eventMeanReversionMin, eventMeanReversionMax};
            case FEE -> new double[]{eventFeeMin, eventFeeMax};
            case PRICE_LIMIT -> new double[]{eventPriceLimitMin, eventPriceLimitMax};
            case SUSPEND -> new double[]{1, haltMaxMarketTicks()};
            case TREND -> new double[]{-1, 1};
            // trigger-type effects: parameters are share counts / coins per share; bands come from the dedicated category-18 config entries
            case FORCE_BUY -> new double[]{10, eventForceBuyMaxShares};
            case FORCE_SELL -> new double[]{10, eventForceSellMaxShares};
            case LIQUIDITY_DRY -> new double[]{1, 20};
            case DIVIDEND -> new double[]{0.1, eventDividendMaxPerShare};
            case SPLIT -> new double[]{2, 2};
        };
    }

    /** Suspension seconds cap -> marketTick cap. */
    public int haltMaxMarketTicks() {
        return secondsToMarketTicks(eventHaltMaxSeconds);
    }

    /** Event price-limit seconds cap -> marketTick cap. */
    public int priceLimitMaxMarketTicks() {
        return secondsToMarketTicks(eventPriceLimitMaxSeconds);
    }

    // ============================================================ validation helpers

    /** Read the field when present, default only when absent (0 is a legal value and must not count as unset). */
    private static long has(CompoundTag t, String key, long dflt) {
        return t.contains(key) ? t.getLong(key) : dflt;
    }

    private static double has(CompoundTag t, String key, double dflt) {
        return t.contains(key) ? t.getDouble(key) : dflt;
    }

    private static int has(CompoundTag t, String key, int dflt) {
        return t.contains(key) ? t.getInt(key) : dflt;
    }

    /** Boolean fields: missing takes the given default (CompoundTag getBoolean on a missing key returns false and cannot be used directly). */
    private static boolean hasBoolean(CompoundTag t, String key, boolean dflt) {
        return t.contains(key) ? t.getBoolean(key) : dflt;
    }

    // ------------------------------------------------------------ null-safe config reads
    // ConfigValue.get() may theoretically return null (config not fully loaded, corrupted file); always fall back to a safe default and warn once.

    /** Null-safe boolean config read. */
    private static boolean cfg(net.minecraftforge.common.ForgeConfigSpec.BooleanValue v, boolean dflt) {
        Boolean got = v == null ? null : v.get();
        if (got == null) {
            warnOnce("cfgNull:" + dflt, "Boolean config read returned null; using default " + dflt + " (warned once)");
            return dflt;
        }
        return got;
    }

    /** Null-safe int config read. */
    private static int cfg(net.minecraftforge.common.ForgeConfigSpec.IntValue v, int dflt) {
        Integer got = v == null ? null : v.get();
        return got == null ? dflt : got;
    }

    /** Null-safe long config read. */
    private static long cfg(net.minecraftforge.common.ForgeConfigSpec.LongValue v, long dflt) {
        Long got = v == null ? null : v.get();
        return got == null ? dflt : got;
    }

    /** Null-safe double config read. */
    private static double cfg(net.minecraftforge.common.ForgeConfigSpec.DoubleValue v, double dflt) {
        Double got = v == null ? null : v.get();
        return got == null ? dflt : got;
    }

    /** Null-safe string config read (null / blank falls back to the default). */
    private static String cfg(net.minecraftforge.common.ForgeConfigSpec.ConfigValue<String> v, String dflt) {
        String got = v == null ? null : v.get();
        return got == null || got.trim().isEmpty() ? dflt : got.trim();
    }

    /** Null-safe string-list config read (null falls back to an empty list; entries stay verbatim for the parser to validate). */
    private static List<String> cfgList(net.minecraftforge.common.ForgeConfigSpec.ConfigValue<? extends List<? extends String>> v) {
        List<? extends String> got = v == null ? null : v.get();
        if (got == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(got.size());
        for (String s : got) {
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** String fields: missing or empty always takes the default (null-safe config requirement: null never enters a snapshot). */
    private static String hasString(CompoundTag t, String key, String dflt) {
        if (!t.contains(key)) {
            return dflt;
        }
        String s = t.getString(key);
        return s == null || s.trim().isEmpty() ? dflt : s.trim();
    }

    /** Paired band (min/max): when min exceeds max, swap the two and warn once (never written back to the config file). */
    private static double[] pairSwapWarn(String key, double minV, double maxV) {
        if (minV > maxV) {
            warnOnce(key + ".swap", key + "Min=" + minV + " exceeds " + key + "Max=" + maxV + "; swapped (warned once)");
            return new double[]{maxV, minV};
        }
        return new double[]{minV, maxV};
    }

    private static long clampL(String key, long v, long min, long max, long dflt) {
        if (v < min) {
            warnOnce(key, key + "=" + v + " is below minimum " + min + "; clamped to " + min);
            return min;
        }
        if (v > max) {
            warnOnce(key, key + "=" + v + " is above maximum " + max + "; clamped to " + max);
            return max;
        }
        return v;
    }

    private static int clampI(String key, int v, int min, int max, int dflt) {
        if (v < min) {
            warnOnce(key, key + "=" + v + " is below minimum " + min + "; clamped to " + min);
            return min;
        }
        if (v > max) {
            warnOnce(key, key + "=" + v + " is above maximum " + max + "; clamped to " + max);
            return max;
        }
        return v;
    }

    private static double clampD(String key, double v, double min, double max, double dflt) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            warnOnce(key, key + " is not finite; using default " + dflt);
            return dflt;
        }
        if (v < min) {
            warnOnce(key, key + "=" + v + " is below minimum " + min + "; clamped to " + min);
            return min;
        }
        if (v > max) {
            warnOnce(key, key + "=" + v + " is above maximum " + max + "; clamped to " + max);
            return max;
        }
        return v;
    }

    /** Paired parameters: clamp out-of-range to the bounds; when inverted take min = smaller / max = larger; warn once; no swap and no write-back. */
    private static int[] pair(String key, int a, int dfltA, int b, int dfltB, int min, int max) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        if (lo != a || hi != b) {
            warnOnce(key, key + " range endpoints were reversed: " + a + " / " + b + "; normalized to " + lo + " / " + hi);
        }
        if (hi < min) {
            warnOnce(key + ".range", key + " range is below minimum " + min + "; using defaults " + dfltA + " / " + dfltB);
            return new int[]{clampLow(dfltA, min), clampHigh(dfltB, max)};
        }
        return new int[]{Math.max(min, lo), Math.min(max, hi)};
    }

    private static double[] pairD(String key, double a, double dfltA, double b, double dfltB, double min, double max) {
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        if (lo != a || hi != b) {
            warnOnce(key, key + " range endpoints were reversed: " + a + " / " + b + "; normalized to " + lo + " / " + hi);
        }
        if (hi < min) {
            warnOnce(key + ".range", key + " range is below minimum " + min + "; using defaults " + dfltA + " / " + dfltB);
            lo = Math.max(min, Math.min(dfltA, dfltB));
            hi = Math.min(max, Math.max(dfltA, dfltB));
            return new double[]{lo, hi};
        }
        return new double[]{Math.max(min, lo), Math.min(max, hi)};
    }

    private static int clampLow(int v, int min) {
        return Math.max(min, v);
    }

    private static int clampHigh(int v, int max) {
        return Math.min(max, v);
    }

    private static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            LOG.warn(message);
        }
    }

    // ------------------------------------------------ too-short trading day defence
    // Testing once set tradingDayTicks to 100 (a trading day every 5 seconds), and the once-per-trading-day INFO logs
    // setDailyBaseline / Leaderboard became instant spam (a fresh group in latest.log every 5 seconds),
    // and recomputing the daily baseline also slowed the server. Startup / hot reload share one entry here:
    // below 1200 ticks (1 minute) print a giant yellow banner and silence the two per-trading-day logs.

    /** The no-spam lower bound for a trading day: 1200 game ticks (= 60 seconds). Below it: warn + silence the logs only; the config value is never mutated. */
    public static final int MIN_COMFORT_TRADING_DAY_TICKS = 1200;

    /** Pure predicate (the self-check harness may call it directly; it touches no config reading). */
    public static boolean isTradingDayTooShort(int dayTicks) {
        return dayTicks > 0 && dayTicks < MIN_COMFORT_TRADING_DAY_TICKS;
    }

    /**
     * Warning text (console log discipline: server-owner-facing logs are 100% English regardless of the player's game language;
     * the source simultaneously keeps the Java-literals-pure-ASCII build discipline).
     */
    public static String tradingDayTooShortMessage(int dayTicks, String source) {
        return "\n"
                + "==================================================================\n"
                + "[WARNING] OreMarket trading day is too short (<" + MIN_COMFORT_TRADING_DAY_TICKS
                + " ticks)! Daily logs will flood the console and server performance"
                + " will drop badly (current=" + dayTicks + ", source=" + source + ")\n"
                + "[WARNING] A trading day shorter than the comfort threshold may"
                + " produce massive logs and hurt server performance!\n"
                + "==================================================================";
    }

    /**
     * Unified entry for startup / hot reload / self-check: when too short, print the giant yellow warning (English, the only
     * console line, no Chinese [check] evidence lines attached); for normal lengths, do nothing. Returns whether it fired.
     */
    public static boolean warnIfTradingDayTooShort(int dayTicks, String source) {
        if (!isTradingDayTooShort(dayTicks)) {
            return false;
        }
        LOG.warn(tradingDayTooShortMessage(dayTicks, source));
        return true;
    }

    /** Whether the once-per-trading-day INFO logs should be silenced (a too-short trading day = guaranteed spam). */
    public static boolean quietDailyLogs(MarketSettings s) {
        return s != null && isTradingDayTooShort(s.tradingDayTicks);
    }

    /**
     * Exchange-price vs base-price sanity check: when exchange points (coins) exceed 50% of the base price, hint that the
     * stock market could be arbitraged around, but players are never blocked.
     */
    private void warnExchangeAboveBase() {
        for (int i = 0; i < BUILTIN_COUNT; i++) {
            Stock tpl = Stock.BUILTIN_TEMPLATES.get(i);
            long rate = exchangeRates[i];
            double base = basePrices[i];
            // convert the exchange rate to double before comparing with the base price
            if (base > 0 && (double) rate > base * 0.5) {
                warnOnce("exchangeAboveBase." + tpl.code(), "Exchange points " + rate + " for stock " + tpl.code()
                        + " exceed 50% of base price " + String.format(Locale.ROOT, "%.1f", base) + " (warning only)");
            }
        }
    }

    /** The all-defaults tag (for the fallback snapshot; field names match writeTo). */
    private static CompoundTag defaultTagRaw() {
        CompoundTag t = new CompoundTag();
        t.putLong("level2Threshold", 200L);
        t.putLong("level3Threshold", 2000L);
        t.putLong("level4Threshold", 8000L);
        for (int i = 0; i < BUILTIN_COUNT; i++) {
            t.putLong("exchangeRate." + i, Stock.BUILTIN_TEMPLATES.get(i).exchangeRate());
            t.putDouble("basePrice." + i, Stock.BUILTIN_TEMPLATES.get(i).basePrice());
        }
        t.putDouble("meanReversionFactor", 0.03);
        t.putDouble("reversionDeadZoneFraction", 0.08);
        t.putDouble("randomFluctuation", 0.02);
        t.putDouble("trendImpact", 0.005);
        t.putDouble("tradeImpactFactor", 0.004);
        t.putDouble("priceFloorPercent", 25.0);
        t.putDouble("priceCeilingPercent", 400.0);
        t.putInt("marketTickInterval", 60);
        t.putInt("tradingDayTicks", 24000);
        t.putLong("creditLimitLevel2", 200L);
        t.putLong("creditLimitLevel3", 500L);
        t.putLong("creditLimitLevel4", 1200L);
        t.putDouble("loanDailyInterestPercent", 0.5);
        t.putInt("loanTermDays", 5);
        t.putInt("bankruptcyCooldownDays", 3);
        t.putDouble("loanInterestCapMultiplier", 2.0);
        t.putBoolean("loanAutoRepay", true);
        t.putInt("loanGracePeriodDays", 1);
        t.putDouble("loanPenaltyPercent", 2.0);
        t.putBoolean("loanCreditScoreEnabled", true);
        t.putBoolean("loanP2PEnabled", true);
        t.putDouble("loanInterestVariance", 40.0);
        t.putDouble("loanRateCapPercent", 1.0);
        t.putDouble("loanLimitAssetPercent", 20.0);
        t.putDouble("loanCollateralLimitFactor", 0.50);
        t.putLong("loanEmergencyCap", 100);
        t.putDouble("loanEmergencyRateFactor", 2.00);
        t.putDouble("loanMarginRateFactor", 1.50);
        t.putDouble("loanCollateralRateFactor", 0.85);
        t.putDouble("loanP2PFeePercent", 2.0);
        t.putInt("loanOverdueStrikeLimit", 5);
        t.putInt("loanClawbackDays", 2);
        t.putInt("loanLimitRecoverDays", 5);
        t.putDouble("loanPrepayDiscountPercent", 10.0);
        t.putDouble("loanPrepayFeePercent", 1.0);
        t.putInt("loanScorePerOnTime", 12);
        t.putInt("loanScorePerOverdue", 35);
        t.putInt("loanScorePerBankrupt", 150);
        t.putLong("loanScoreAssetStep", 2000);
        t.putInt("loanScorePerTier", 15);
        t.putDouble("tradeFeePercent", 0.5);
        t.putDouble("tradeImpactFactor", 0.004);
        t.putBoolean("tradeSlippage", true);
        t.putDouble("indexVolatilityWeight", 0.15);
        t.putDouble("priceLimitPercent", 10.0);
        t.putLong("startingCoins", 100L);
        t.putDouble("taxDestroyPercent", 0.0);
        t.putDouble("shortMarginRatio", 0.50);
        t.putDouble("shortLiquidationLine", 1.15);
        t.putDouble("shortDailyInterest", 0.001);
        t.putDouble("shortScarcityMultiplier", 2.0);
        t.putDouble("shortLiquidationThreshold2", 0.20);
        t.putBoolean("shortAffectPrice", true);
        t.putDouble("shortCloseImpactMultiplier", 0.2);
        t.putString("shortReleaseMode", "withPnl");
        t.putInt("defaultBaseShares", 50000);
        t.putInt("absoluteMaxShares", 100000);
        t.putInt("absoluteMinShares", 100);
        t.putInt("orderMatchIntervalSeconds", 60);
        t.putInt("orderMaxKeepDays", 7);
        t.putInt("orderMax", 20);
        t.putDouble("orderFeePercent", 0.5);
        t.putBoolean("orderAffectPrice", true);
        t.putInt("listIntervalDays", 5);
        t.putDouble("delistThresholdPercent", 30.0);
        t.putInt("delistConsecutiveDays", 3);
        t.putInt("relistIntervalDays", 10);
        t.putInt("listingMaxCount", 24);
        t.putBoolean("dynamicListingEnabled", true);
        t.putBoolean("builtinDynamicListing", false);
        t.putInt("dynamicUnlockLevel", 1);
        t.putDouble("dynamicBasePriceMultiplier", 5.0);
        t.put("dynamicCandidates", new ListTag());
        t.putDouble("firstDayUpChance", 0.30);
        t.putDouble("firstDayDownChance", 0.30);
        t.putDouble("firstDayFlatChance", 0.40);
        t.putDouble("firstDayUpMin", 0.20);
        t.putDouble("firstDayUpMax", 0.50);
        t.putDouble("firstDayDownMin", 0.20);
        t.putDouble("firstDayDownMax", 0.50);
        t.putDouble("firstDayFlatRange", 0.05);
        t.putInt("limitOrderMax", 10);
        t.putDouble("limitOrderFeePercent", 1.0);
        t.putInt("limitOrderMaxKeepDays", 7);
        t.putInt("limitOrderFailRetries", 3);
        t.putDouble("limitOrderDeviationThreshold", 0.05);
        t.putBoolean("loanDestroyPrincipal", true);
        t.putBoolean("loanDestroyInterest", true);
        t.putInt("bankruptcyShortCooldown", 3);
        t.putInt("leaderboardSize", 10);
        t.putBoolean("leaderboardShowSelf", true);
        for (int i = 0; i < UI_COLOR_DEFAULTS.length; i++) t.putInt("uiColor." + i, UI_COLOR_DEFAULTS[i]);
        t.putInt("uiScrollStep", 20);
        t.putInt("uiScrollStepFine", 10);
        t.putInt("uiDragThresholdPx", 5);
        t.putBoolean("uiSmoothScroll", true);
        t.putInt("uiScrollLerpPercent", 15);
        t.putInt("uiToastDurationMs", 2000);
        t.putInt("uiToastFadeMs", 200);
        t.putInt("uiCardAnimMs", 200);
        t.putInt("uiClickDebounceMs", 200);
        t.putInt("maxTradeQuantity", 1_000_000);
        t.putInt("logMaxEntries", 100);
        t.putInt("eventMaxActive", 3);
        t.putInt("eventSuspendCooldownTicks", 200);
        t.putInt("eventDividendCooldownDays", 30);
        t.putInt("eventSplitRatio", 2);
        t.putInt("klineTicksPerBar", 10);
        t.putInt("klineDisplayCount", OreMarketConfig.DEFAULT_KLINE_DISPLAY_COUNT);
        t.putBoolean("klineDefaultShort", true);
        t.putBoolean("klineRealPlayerVolumeOnly", false);
        t.putInt("rarityWeightCommon", 60);
        t.putInt("rarityWeightRare", 25);
        t.putInt("rarityWeightEpic", 12);
        t.putInt("rarityWeightLegend", 3);
        t.putDouble("eventTriggerChance", 3.0);
        t.putInt("legendCooldownDays", 3);
        t.putInt("eventDurationCommonMinSec", 20);
        t.putInt("eventDurationCommonMaxSec", 60);
        t.putInt("eventDurationRareMinSec", 45);
        t.putInt("eventDurationRareMaxSec", 120);
        t.putInt("eventDurationEpicMinSec", 90);
        t.putInt("eventDurationEpicMaxSec", 180);
        t.putInt("eventDurationLegendMinSec", 120);
        t.putInt("eventDurationLegendMaxSec", 240);
        t.putInt("eventHaltMaxSeconds", 180);
        t.putInt("eventPriceLimitMaxSeconds", 60);
        t.putDouble("eventPriceMultMin", 0.5);
        t.putDouble("eventPriceMultMax", 1.5);
        t.putDouble("eventVolatilityMin", 0.2);
        t.putDouble("eventVolatilityMax", 3.0);
        t.putDouble("eventMeanReversionMin", 0.0);
        t.putDouble("eventMeanReversionMax", 0.15);
        t.putDouble("eventFeeMin", 0.0);
        t.putDouble("eventFeeMax", 0.10);
        t.putDouble("eventTradeImpactMin", 0.5);
        t.putDouble("eventTradeImpactMax", 3.0);
        t.putDouble("eventPriceLimitMin", 5.0);
        t.putDouble("eventPriceLimitMax", 25.0);
        return t;
    }
}
