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

package cn.oremarket.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Collections;
import java.util.List;

/**
 * Bilingual COMMON settings for the ore stock market.
 *
 * This class declares entries only. Validation, clamping, unit conversion and hot-reload snapshots are handled by
 * {@code MarketSettings} and {@code MarketReloadService}; clamped values are never written back to the TOML.
 * Event durations use real seconds and are converted internally with {@code marketTickInterval}.
 */
public final class OreMarketConfig {

    /** Network sync mode: the original three tiers kept; decides snapshot push frequency only. */
    public enum SyncModel {
        /** FULL: every marketTick pushes the whole snapshot to all players. */
        FULL,
        /** INCREMENTAL: only push to players with the UI open (default). */
        INCREMENTAL,
        /** ON_DEMAND: push only when a price moved more than 1%. */
        ON_DEMAND
    }

    public static final ForgeConfigSpec SPEC;

    // ---------------------------------------------------------- category 1: level unlock thresholds
    /** Level 1 unlocks initially (coal, redstone, lapis, copper); its threshold is always 0, no config entry needed. */
    /** Coin peak that unlocks level 2 (iron, gold). Default 200. Range 0~1000000. */
    public static final ForgeConfigSpec.LongValue LEVEL2_THRESHOLD;
    /** Coin peak that unlocks level 3 (diamond). Default 2000. Range 0~10000000. */
    public static final ForgeConfigSpec.LongValue LEVEL3_THRESHOLD;
    /** Coin peak that unlocks level 4 (netherite ingot). Default 8000. Range 0~100000000. */
    public static final ForgeConfigSpec.LongValue LEVEL4_THRESHOLD;

    // ---------------------------------------------------------- category 2: exchange rates
    /** Coal exchange rate: 1 coal = X ore coins. Default 3. */
    public static final ForgeConfigSpec.IntValue EXCHANGE_COAL;
    /** Redstone exchange rate. Default 2. */
    public static final ForgeConfigSpec.IntValue EXCHANGE_REDSTONE;
    /** Lapis lazuli exchange rate. Default 3. */
    public static final ForgeConfigSpec.IntValue EXCHANGE_LAPIS;
    /** Copper exchange rate. Default 4. */
    public static final ForgeConfigSpec.IntValue EXCHANGE_COPPER;
    /** Iron exchange rate. Default 8. */
    public static final ForgeConfigSpec.IntValue EXCHANGE_IRON;
    /** Gold exchange rate. Default 15. */
    public static final ForgeConfigSpec.IntValue EXCHANGE_GOLD;
    /** Diamond exchange rate. Default 40. */
    public static final ForgeConfigSpec.IntValue EXCHANGE_DIAMOND;
    /** Netherite ingot exchange rate. Default 120. */
    public static final ForgeConfigSpec.IntValue EXCHANGE_NETHERITE;

    // ---------------------------------------------------------- category 3: stock base prices
    /** Coal base price. Default 8.0. */
    public static final ForgeConfigSpec.DoubleValue BASE_COAL;
    /** Redstone base price. Default 6.0. */
    public static final ForgeConfigSpec.DoubleValue BASE_REDSTONE;
    /** Lapis lazuli base price. Default 10.0. */
    public static final ForgeConfigSpec.DoubleValue BASE_LAPIS;
    /** Copper base price. Default 12.0. */
    public static final ForgeConfigSpec.DoubleValue BASE_COPPER;
    /** Iron base price. Default 18.0. */
    public static final ForgeConfigSpec.DoubleValue BASE_IRON;
    /** Gold base price. Default 35.0. */
    public static final ForgeConfigSpec.DoubleValue BASE_GOLD;
    /** Diamond base price. Default 70.0. */
    public static final ForgeConfigSpec.DoubleValue BASE_DIAMOND;
    /** Netherite ingot base price. Default 160.0. */
    public static final ForgeConfigSpec.DoubleValue BASE_NETHERITE;

    // ---------------------------------------------------------- category 4: price engine (eight shared entries)
    /** Mean-reversion strength: the fraction of the gap to the quote anchor the current price closes per marketTick; 0.03 closes 3% each step, and ONLY inside the dead zone (reversionDeadZonePercent). Range 0.0~0.5. */
    public static final ForgeConfigSpec.DoubleValue MEAN_REVERSION_FACTOR;
    /** Mean-reversion dead zone (percent of the quote anchor): beyond this distance from the anchor, mean reversion stops entirely, so deep discounts/premiums are real risk, not a guaranteed ladder. 100 = legacy always-reverting. Range 0.0~100.0. */
    public static final ForgeConfigSpec.DoubleValue REVERSION_DEAD_ZONE_PERCENT;
    /** Random volatility: the price jitters randomly within +/- this fraction each marketTick; 0.02 is +/-2%. Range 0.0~0.5. */
    public static final ForgeConfigSpec.DoubleValue RANDOM_FLUCTUATION;
    /** Trend contribution: the fixed step added per tick while the trend is up/down. Range 0.0~0.1. */
    public static final ForgeConfigSpec.DoubleValue TREND_IMPACT;
    /** Trade impact: how one trade's volume relative to total position moves the price (applied once per side). Range 0.0~0.1. */
    public static final ForgeConfigSpec.DoubleValue TRADE_IMPACT_FACTOR;
    /** Whether system trades charge half of their existing price impact as approximate execution slippage. Default true enables approximate slippage. */
    public static final ForgeConfigSpec.BooleanValue TRADE_SLIPPAGE;
    /** Shared market-wide random component weight. Default 0.15 adds a light shared market factor. Range 0.0~0.5. */
    public static final ForgeConfigSpec.DoubleValue INDEX_VOLATILITY_WEIGHT;
    /** Price floor: the current price never falls below this percentage of the base price. Range 1~100. */
    public static final ForgeConfigSpec.DoubleValue PRICE_FLOOR_PERCENT;
    /** Price ceiling: the current price never rises above this percentage of the base price. Range 100~10000. */
    public static final ForgeConfigSpec.DoubleValue PRICE_CEILING_PERCENT;

    // ---------------------------------------------------------- category 5: time parameters
    /** Game ticks per marketTick. Default 60 (= 3 seconds). Range 1~24000. */
    public static final ForgeConfigSpec.IntValue MARKET_TICK_INTERVAL;
    /** Game ticks a trading day lasts. Default 24000 (= 20 minutes). Range 100~240000. */
    public static final ForgeConfigSpec.IntValue TRADING_DAY_TICKS;

    // ---------------------------------------------------------- category 6: loan parameters
    /** Credit line at level 2 (ore coins). Default 200. Range 0~1000000. */
    public static final ForgeConfigSpec.LongValue CREDIT_LIMIT_LEVEL2;
    /** Credit line at level 3 (ore coins). Default 500. Range 0~1000000. */
    public static final ForgeConfigSpec.LongValue CREDIT_LIMIT_LEVEL3;
    /** Credit line at level 4 (ore coins). Default 1200. Range 0~1000000. */
    public static final ForgeConfigSpec.LongValue CREDIT_LIMIT_LEVEL4;
    /** Loan daily rate (percent). Default 0.5, i.e. 0.5% of principal per day as the nominal base. Range 0~100. */
    public static final ForgeConfigSpec.DoubleValue LOAN_DAILY_INTEREST_PERCENT;
    /** Loan term baseline (trading days). Default 5: controls system-loan installments and P2P listing terms. Range 1~30. */
    public static final ForgeConfigSpec.IntValue LOAN_TERM_DAYS;
    /** Bankruptcy cooldown (trading days). Default 3: no new borrowing for 3 full trading days. Range 0~30. */
    public static final ForgeConfigSpec.IntValue BANKRUPTCY_COOLDOWN_DAYS;
    /** Interest cap multiplier: accrued interest never exceeds principal x this factor. Default 2.0. Range 1.0~10.0. */
    public static final ForgeConfigSpec.DoubleValue LOAN_INTEREST_CAP_MULTIPLIER;
    /** Whether repaid loan principal is burned (true = burned outright; false = moved to the system reserve pool for future disbursement). */
    public static final ForgeConfigSpec.BooleanValue LOAN_DESTROY_PRINCIPAL;
    /** Whether repaid loan interest is burned (true = burned outright; false = moved to the system reserve pool). */
    public static final ForgeConfigSpec.BooleanValue LOAN_DESTROY_INTEREST;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.BooleanValue LOAN_AUTO_REPAY;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.IntValue LOAN_GRACE_PERIOD_DAYS;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.DoubleValue LOAN_PENALTY_PERCENT;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.BooleanValue LOAN_CREDIT_SCORE_ENABLED;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.BooleanValue LOAN_P2P_ENABLED;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.DoubleValue LOAN_INTEREST_VARIANCE;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.DoubleValue LOAN_RATE_CAP_PERCENT;
    /** Percentage of the player's current total assets added to the dynamic credit limit; this is not capped by category 22. */
    public static final ForgeConfigSpec.DoubleValue LOAN_LIMIT_ASSET_PERCENT;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.DoubleValue LOAN_COLLATERAL_LIMIT_FACTOR;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.LongValue LOAN_EMERGENCY_CAP;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.DoubleValue LOAN_EMERGENCY_RATE_FACTOR;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.DoubleValue LOAN_MARGIN_RATE_FACTOR;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.DoubleValue LOAN_COLLATERAL_RATE_FACTOR;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.DoubleValue LOAN_P2P_FEE_PERCENT;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.IntValue LOAN_OVERDUE_STRIKE_LIMIT;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.IntValue LOAN_CLAWBACK_DAYS;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.IntValue LOAN_LIMIT_RECOVER_DAYS;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.DoubleValue LOAN_PREPAY_DISCOUNT_PERCENT;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.DoubleValue LOAN_PREPAY_FEE_PERCENT;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.IntValue LOAN_SCORE_PER_ONTIME;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.IntValue LOAN_SCORE_PER_OVERDUE;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.IntValue LOAN_SCORE_PER_BANKRUPT;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.LongValue LOAN_SCORE_ASSET_STEP;
    /** Loan v2 config entry (semantics in the bilingual comment at its definition). */
    public static final ForgeConfigSpec.IntValue LOAN_SCORE_PER_TIER;


    // ---------------------------------------------------------- category 7: trading parameters
    /** Buy/sell fee (percent of the subtotal). Default 0.5. Range 0~10. */
    public static final ForgeConfigSpec.DoubleValue TRADE_FEE_PERCENT;
    /** Daily price limit per marketTick (percent). Default 10. Range 1~50. */
    public static final ForgeConfigSpec.DoubleValue PRICE_LIMIT_PERCENT;
    /** Starting coins for new players (ore coins). Default 100. Range 0~1000000. Set 0 to issue nothing. */
    public static final ForgeConfigSpec.LongValue STARTING_COINS;

    // ---------------------------------------------------------- category 8: event frequency and durations (unit: seconds)
    /** Probability of trying to fire a new event each marketTick (percent). Default 3.0. Range 0~100. */
    public static final ForgeConfigSpec.DoubleValue EVENT_TRIGGER_CHANCE;
    /** Legendary event cooldown (trading days): no legendary within this many days of a previous one. Default 3. Range 0~30. */
    public static final ForgeConfigSpec.IntValue LEGEND_COOLDOWN_DAYS;
    /** Common event minimum duration (seconds). Default 20. Range 0~86400. */
    public static final ForgeConfigSpec.IntValue EVENT_DURATION_COMMON_MIN;
    /** Common event maximum duration (seconds). Default 60. Range 0~86400. */
    public static final ForgeConfigSpec.IntValue EVENT_DURATION_COMMON_MAX;
    /** Rare event minimum duration (seconds). Default 45. Range 0~86400. */
    public static final ForgeConfigSpec.IntValue EVENT_DURATION_RARE_MIN;
    /** Rare event maximum duration (seconds). Default 120. Range 0~86400. */
    public static final ForgeConfigSpec.IntValue EVENT_DURATION_RARE_MAX;
    /** Epic event minimum duration (seconds). Default 90. Range 0~86400. */
    public static final ForgeConfigSpec.IntValue EVENT_DURATION_EPIC_MIN;
    /** Epic event maximum duration (seconds). Default 180. Range 0~86400. */
    public static final ForgeConfigSpec.IntValue EVENT_DURATION_EPIC_MAX;
    /** Legendary event minimum duration (seconds). Default 120. Range 0~86400. */
    public static final ForgeConfigSpec.IntValue EVENT_DURATION_LEGEND_MIN;
    /** Legendary event maximum duration (seconds). Default 240. Range 0~86400. */
    public static final ForgeConfigSpec.IntValue EVENT_DURATION_LEGEND_MAX;
    /** Maximum length of one suspension (seconds). Default 180. Range 1~30000. */
    public static final ForgeConfigSpec.IntValue EVENT_HALT_MAX_SECONDS;
    /** Maximum length of one event price-limit effect (seconds). Default 60. Range 1~30000. */
    public static final ForgeConfigSpec.IntValue EVENT_PRICE_LIMIT_MAX_SECONDS;

    // ---------------------------------------------------------- category 9: event effect value ranges
    /** Event price multiplier lower bound (multiplied into the price per marketTick). Default 0.5. Range 0.0~5.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_PRICE_MULT_MIN;
    /** Event price multiplier upper bound. Default 1.5. Range 0.0~5.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_PRICE_MULT_MAX;
    /** Event volatility multiplier lower bound. Default 0.2. Range 0.0~10.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_VOLATILITY_MIN;
    /** Event volatility multiplier upper bound. Default 3.0. Range 0.0~10.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_VOLATILITY_MAX;
    /** Event reversion strength lower bound. Default 0.0. Range 0.0~1.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_MEAN_REVERSION_MIN;
    /** Event reversion strength upper bound. Default 0.15. Range 0.0~1.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_MEAN_REVERSION_MAX;
    /** Event fee modifier lower bound (fraction of the trade; 0 means fee-free). Default 0.0. Range 0.0~1.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_FEE_MIN;
    /** Event fee modifier upper bound. Default 0.10. Range 0.0~1.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_FEE_MAX;
    /** Event trade-impact multiplier lower bound. Default 0.5. Range 0.0~10.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_TRADE_IMPACT_MIN;
    /** Event trade-impact multiplier upper bound. Default 3.0. Range 0.0~10.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_TRADE_IMPACT_MAX;
    /** Event price-limit override lower bound (percent). Default 5.0. Range 0.1~100.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_PRICE_LIMIT_MIN;
    /** Event price-limit override upper bound (percent). Default 25.0. Range 0.1~100.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_PRICE_LIMIT_MAX;

    // ---------------------------------------------------------- category 18: event system extensions (combo/chain/conflict/preview/fake news/player trigger/animation/frequency/caps/new effects/custom)
    /** Whether event combos are on (10 fixed pairs); turning off disables the whole combo layer; already-active combo events are unaffected. Default true. */
    public static final ForgeConfigSpec.BooleanValue EVENT_COMBINATION_ENABLED;
    /** Whether event chains are on (5 fixed rules: the parent fires its child at the Nth marketTick). Default true. */
    public static final ForgeConfigSpec.BooleanValue EVENT_CHAIN_ENABLED;
    /** Whether conflict annulment is on: opposite price-multiplier directions (>1 vs <1) on the same stock cancel both events and dampen market-wide volatility. Default true. */
    public static final ForgeConfigSpec.BooleanValue EVENT_CONFLICT_ENABLED;
    /** Whether legendary forecast cards are on (a grey preview one marketTick before firing; 20% are misinformation). Default true. */
    public static final ForgeConfigSpec.BooleanValue EVENT_PREVIEW_ENABLED;
    /** Forecast accuracy: 0.80 = 80% of forecasts come true, the rest are misinformation. Range 0.0~1.0, gated by EVENT_PREVIEW_ENABLED. */
    public static final ForgeConfigSpec.DoubleValue EVENT_PREVIEW_ACCURACY;
    /** Whether fake-news events are on (5 market rumour templates, no real effect, 60 seconds long). Default true. */
    public static final ForgeConfigSpec.BooleanValue EVENT_FAKE_NEWS_ENABLED;
    /** Whether player-behaviour-triggered events are on (5 consecutive marketTicks with server-wide buy/sell volume over the threshold fire frenzy/panic). Default true. */
    public static final ForgeConfigSpec.BooleanValue EVENT_PLAYER_TRIGGER_ENABLED;
    /** Player-behaviour trigger threshold (shares): server-wide total buy or sell volume within the 5-marketTick window. Range 100~100000, gated by EVENT_PLAYER_TRIGGER_ENABLED. */
    public static final ForgeConfigSpec.IntValue EVENT_PLAYER_TRIGGER_VOLUME;
    /** Whether event card slide-in/fade-out animations are on (a client render item, shipped via SyncConfigPacket). Default true. */
    public static final ForgeConfigSpec.BooleanValue EVENT_CARD_ANIMATION_ENABLED;
    /** Whether dynamic event-frequency adjustment is on (lower probability when too frequent, higher when too rare). Default true. */
    public static final ForgeConfigSpec.BooleanValue EVENT_FREQUENCY_DYNAMIC_ENABLED;
    /** High-frequency rule: more than this many events fired in the last 10 marketTicks -> trigger probability halved for 20 ticks. Range 1~20. */
    public static final ForgeConfigSpec.IntValue EVENT_FREQUENCY_HIGH_THRESHOLD;
    /** Low-frequency rule: at most this many events fired in the last 20 marketTicks -> trigger probability doubled for 20 ticks. Range 0~5. */
    public static final ForgeConfigSpec.IntValue EVENT_FREQUENCY_LOW_THRESHOLD;
    /** Lower clamp of the multiplied effect of stacked events (only when 2+ price multipliers hit the same stock; single events are unrestricted). Range 0.01~1.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_EFFECT_MULTIPLIER_CAP_MIN;
    /** Upper clamp of the multiplied effect of stacked events. Range 1.0~100.0, paired with the lower bound; an inverted pair auto-takes smaller/larger. */
    public static final ForgeConfigSpec.DoubleValue EVENT_EFFECT_MULTIPLIER_CAP_MAX;
    /** Whether legendary cooldown records are written to the log tab (the event bar no longer renders a cooldown card). Default true. */
    public static final ForgeConfigSpec.BooleanValue EVENT_LEGEND_COOLDOWN_HINT_ENABLED;
    /** RGB of the exchange selected-row background (the client forces an opaque alpha); default 0x4A5A7A. */
    public static final ForgeConfigSpec.IntValue EXCHANGE_ROW_SELECTED_COLOR;
    /** Maximum shares for the forced-buy event (the system buys and pushes the price up). Range 10~100000. */
    public static final ForgeConfigSpec.IntValue EVENT_FORCE_BUY_MAX_SHARES;
    /** Maximum shares for the forced-sell event (the system sells and presses the price down). Range 10~100000. */
    public static final ForgeConfigSpec.IntValue EVENT_FORCE_SELL_MAX_SHARES;
    /** Dividend cap per share (ore coins): after a dividend event the price is force-ex-dividend by the same amount. Range 0.1~1000.0. */
    public static final ForgeConfigSpec.DoubleValue EVENT_DIVIDEND_MAX_PER_SHARE;
    /** Whether stock-split events are on (price /2, position x2, float-preserving). Default true. */
    public static final ForgeConfigSpec.BooleanValue EVENT_STOCK_SPLIT_ENABLED;
    /** Custom event list: name|rarity|stock codes|effect modules|duration seconds|description. Cap 50 entries; parse failures are skipped with a warning; names clashing with built-ins are skipped. */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CUSTOM_EVENTS;

    // ---------------------------------------------------------- category 19: particle effects (server triggers, client playback via S2C packets, protocol 10+)
    /** Particle master switch: false stops the server sending any SpawnParticlePacket. Default true. */
    public static final ForgeConfigSpec.BooleanValue PARTICLE_EFFECT_ENABLED;
    /** Whether trades (fills) play particles. Default true; still gated by the master switch. */
    public static final ForgeConfigSpec.BooleanValue PARTICLE_EFFECT_ON_TRADE;
    /** Whether event triggers play particles. Default true; still gated by the master switch. */
    public static final ForgeConfigSpec.BooleanValue PARTICLE_EFFECT_ON_EVENT;
    /** Whether short openings play particles. Default true; still gated by the master switch. */
    public static final ForgeConfigSpec.BooleanValue PARTICLE_EFFECT_ON_SHORT_OPEN;
    /** Whether short closings play particles. Default true; still gated by the master switch. */
    public static final ForgeConfigSpec.BooleanValue PARTICLE_EFFECT_ON_SHORT_CLOSE;
    /** Whether bankruptcy settlements play particles. Default true; still gated by the master switch. */
    public static final ForgeConfigSpec.BooleanValue PARTICLE_EFFECT_ON_BANKRUPTCY;
    /** Buy particle type (vanilla particle id, rising above the head). Illegal ids degrade to minecraft:smoke with a warning. */
    public static final ForgeConfigSpec.ConfigValue<String> PARTICLE_EFFECT_TYPE_BUY;
    /** Sell particle type (vanilla particle id, at the feet). Illegal ids degrade to minecraft:smoke with a warning. */
    public static final ForgeConfigSpec.ConfigValue<String> PARTICLE_EFFECT_TYPE_SELL;
    /** Event particle type (8 random points inside a 3-block sphere centred on the player). Illegal ids degrade to minecraft:smoke with a warning. */
    public static final ForgeConfigSpec.ConfigValue<String> PARTICLE_EFFECT_TYPE_EVENT;
    /** Short-open particle type (1 block in front of the player). Illegal ids degrade to minecraft:smoke with a warning. */
    public static final ForgeConfigSpec.ConfigValue<String> PARTICLE_EFFECT_TYPE_SHORT_OPEN;
    /** Short-close particle type (1 block behind the player). Illegal ids degrade to minecraft:smoke with a warning. */
    public static final ForgeConfigSpec.ConfigValue<String> PARTICLE_EFFECT_TYPE_SHORT_CLOSE;
    /** Bankruptcy particle type (10 random points inside a 3-block sphere around the player). Illegal ids degrade to minecraft:smoke with a warning. */
    public static final ForgeConfigSpec.ConfigValue<String> PARTICLE_EFFECT_TYPE_BANKRUPTCY;
    /** Particles per play. Range 1~100, shared by all particle kinds. */
    public static final ForgeConfigSpec.IntValue PARTICLE_EFFECT_COUNT;
    /** Particle spread radius (blocks). Range 0.0~5.0; the spherical event/bankruptcy patterns use a fixed radius 3 and ignore this. */
    public static final ForgeConfigSpec.DoubleValue PARTICLE_EFFECT_SPREAD;
    /** Minimum interval between particle spawns to the same player (game ticks): a rate cap; extra triggers within the interval are dropped. Range 1~200. */
    public static final ForgeConfigSpec.IntValue PARTICLE_EFFECT_SPAWN_INTERVAL_TICKS;
    /** Whether particle plays also play the vanilla orb sound. Default true. */
    public static final ForgeConfigSpec.BooleanValue PARTICLE_EFFECT_PLAY_SOUND_ENABLED;

    // ---------------------------------------------------------- category 11: transaction tax (coin burn)
    /** Burn tax rate (percent): coins taken on every buy and sell in addition to the fee, burned outright into no pool. Total rate = fee rate + burn tax - fee rate x burn tax; defaults fee 0.5% + tax 0% = 0.5%. Short openings pay the opening tax (burn tax included); short closings and liquidations are exempt from all tax and fees. Order fill total rate = trade tax + order fee (additive, the order fee split 50/50 between both sides). Limit-order trigger total rate = trade tax + limit fee (additive). Event fee modifiers change the fee part only, never the burn tax. Default 0.0. Range 0~10. */
    public static final ForgeConfigSpec.DoubleValue TAX_DESTROY_PERCENT;

    // ---------------------------------------------------------- category 12: shorting
    /** Short margin ratio: margin = borrowed value x this ratio. Default 0.50. Range 0.10~5.0. */
    public static final ForgeConfigSpec.DoubleValue SHORT_MARGIN_RATIO;
    /** Short liquidation line (condition one): the price rising to open price x this ratio fires it. Default 1.15. Range 1.05~3.0. */
    public static final ForgeConfigSpec.DoubleValue SHORT_LIQUIDATION_LINE;
    /** Short daily interest (accrued on the value borrowed at opening, deducted from the margin at every trading-day close). Default 0.001 = 0.1%. Range 0.0~0.20. */
    public static final ForgeConfigSpec.DoubleValue SHORT_DAILY_INTEREST;
    /** New-position daily-rate multiplier while less than 20% of the stock short pool remains. Existing positions keep their locked rate. Default 2.0. */
    public static final ForgeConfigSpec.DoubleValue SHORT_SCARCITY_MULTIPLIER;
    /** Liquidation condition two threshold: margin below current value x this ratio fires it. Default 0.20, range 0.05~2.00; loading caps it by the equity remaining at the price-line trigger. */
    public static final ForgeConfigSpec.DoubleValue SHORT_LIQUIDATION_THRESHOLD2;
    /** Whether shorts move the market price. Default true: opening applies sell impact, while closing and liquidation apply buy impact. Setting it to false keeps shorts as system contracts that do not move the shared quote. */
    public static final ForgeConfigSpec.BooleanValue SHORT_AFFECT_PRICE;
    /**
     * Buy-side impact decay applied when a short cover (active close or forced buy-back) moves the shared quote.
     * A cover is a passive repayment, so its quote impact is this fraction of a normal buy (default 0.2 = 20%).
     * Settlement itself always prices at the quote shown when the player clicked; the decay only scales the
     * after-the-fact market push. Range 0.0~1.0; 1.0 restores the old full-impact behavior.
     */
    public static final ForgeConfigSpec.DoubleValue SHORT_CLOSE_IMPACT_MULTIPLIER;
    /** Short-close margin release mode. withPnl (default) = release margin by the quantity fraction AND settle the closing P&L (released = total margin x closed qty / borrowed qty before close + P&L, floored at 0); quantity = release by the quantity fraction, ignoring P&L. Only these two values are legal. */
    public static final ForgeConfigSpec.ConfigValue<String> SHORT_RELEASE_MODE;
    /** Short pool base shares (the pool is measured in pure share count, not coin value). Pool cap = this value / the stock's base price, then clamped by the absolute bounds. Default 50000. Range 1~10000000. */
    public static final ForgeConfigSpec.IntValue SHORT_POOL_DEFAULT_SHARES;
    /** Short pool absolute cap (shares): no matter the conversion, borrowable shares of one stock never exceed it. Default 100000. Range 1~10000000. */
    public static final ForgeConfigSpec.IntValue SHORT_POOL_ABSOLUTE_MAX;
    /** Short pool absolute floor (shares): when an expensive stock converts below the floor, the pool still offers this many shares so there is always work to do. Default 100. Range 1~1000000. */
    public static final ForgeConfigSpec.IntValue SHORT_POOL_ABSOLUTE_MIN;

    // ---------------------------------------------------------- category 12.5: debug log
    /** Debug log master switch. Default false -- when off no string building or recording happens at money nodes, zero performance cost; when on the server startup prints a loud yellow warning. */
    public static final ForgeConfigSpec.BooleanValue ENABLE_DEBUG_LOG;

    // ---------------------------------------------------------- category 13: player orders
    /** Matching interval (seconds). Default 60. Range 10~600. */
    public static final ForgeConfigSpec.IntValue ORDER_MATCH_INTERVAL_SECONDS;
    /** Maximum order lifetime (trading days); expired orders auto-cancel. Default 7. Range 1~30. */
    public static final ForgeConfigSpec.IntValue ORDER_MAX_KEEP_DAYS;
    /** Maximum simultaneous orders per player. Default 20. Range 1~200. */
    public static final ForgeConfigSpec.IntValue ORDER_MAX;
    /** Order fee (percent), split half and half between buyer and seller, additive on top of the trade tax. Default 0.5. Range 0~5. */
    public static final ForgeConfigSpec.DoubleValue ORDER_FEE_PERCENT;
    /** Whether orders move the market price. Default true: player-to-player fills transfer coins and shares, rewrite the system quote to the fill price and record the fill volume in the candlestick. */
    public static final ForgeConfigSpec.BooleanValue ORDER_AFFECT_PRICE;

    // ---------------------------------------------------------- category 14: dynamic listing and delisting
    /** Dynamic listing interval (trading days). Default 5. Range 1~30. */
    public static final ForgeConfigSpec.IntValue LIST_INTERVAL_DAYS;
    /** Delist threshold: a price below this percentage of the base price counts as low-confidence. Default 30. Range 10~90. */
    public static final ForgeConfigSpec.DoubleValue DELIST_THRESHOLD_PERCENT;
    /** How many consecutive trading days must breach the delist threshold before delisting. Default 3. Range 1~10. */
    public static final ForgeConfigSpec.IntValue DELIST_CONSECUTIVE_DAYS;
    /** Minimum trading days a delisted stock must wait before relisting. Default 10. Range 1~60. */
    public static final ForgeConfigSpec.IntValue RELIST_INTERVAL_DAYS;
    /** Whether random stocks auto-list. Default true; false stops automatic new dynamic listings (existing dynamic stocks can still delist). */
    public static final ForgeConfigSpec.BooleanValue DYNAMIC_LISTING_ENABLED;
    /** Whether the default 8 built-in stocks join dynamic listing/delisting. Default false: built-ins stay listed forever and never auto-delist. */
    public static final ForgeConfigSpec.BooleanValue BUILTIN_DYNAMIC_LISTING;
    /** Default unlock level for dynamically listed stocks. Default 1. Range 1~4. The candidate pool's row 5th segment can override it. */
    public static final ForgeConfigSpec.IntValue DYNAMIC_UNLOCK_LEVEL;
    /** Dynamic listing base-price factor: base price = exchange points x this factor. Default 5.0. Range 0.1~100.0. */
    public static final ForgeConfigSpec.DoubleValue DYNAMIC_BASE_PRICE_MULTIPLIER;
    /** Random listing candidates, one per line: stock code|item id|display name|exchange points[|unlock level]. Example: "EMERALD|minecraft:emerald|Emerald|25|1". The fifth field is optional: omit it to use the default unlock level, or write a value from 1 to 4; do not type the square brackets. Codes are 2-8 uppercase letters/digits, up to 64 rows; an empty display name uses the item name, and an empty list uses the built-in pool. */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DYNAMIC_CANDIDATES;
    /** Maximum simultaneously listed stocks (built-in + custom + dynamic). Default 24. Range 8~64. */
    public static final ForgeConfigSpec.IntValue LISTING_MAX_COUNT;
    /** Day-one surge probability. Default 0.30, range 0.0~1.0. The three day-one probabilities should sum to 1.0; when they do not, they are normalised proportionally at load with a warning (the normalised result lives in memory only, never written back here). */
    public static final ForgeConfigSpec.DoubleValue FIRST_DAY_UP_CHANCE;
    /** Day-one plunge probability. Default 0.30, range 0.0~1.0. */
    public static final ForgeConfigSpec.DoubleValue FIRST_DAY_DOWN_CHANCE;
    /** Day-one flat probability. Default 0.40, range 0.0~1.0. */
    public static final ForgeConfigSpec.DoubleValue FIRST_DAY_FLAT_CHANCE;
    /** Day-one surge minimum (0.20 = +20%). Default 0.20, range 0.0~1.0. If it exceeds the maximum, they swap at load with a warning. */
    public static final ForgeConfigSpec.DoubleValue FIRST_DAY_UP_MIN;
    /** Day-one surge maximum. Default 0.50, range 0.0~2.0. */
    public static final ForgeConfigSpec.DoubleValue FIRST_DAY_UP_MAX;
    /** Day-one plunge minimum. Default 0.20, range 0.0~1.0. */
    public static final ForgeConfigSpec.DoubleValue FIRST_DAY_DOWN_MIN;
    /** Day-one plunge maximum. Default 0.50, range 0.0~1.0. */
    public static final ForgeConfigSpec.DoubleValue FIRST_DAY_DOWN_MAX;
    /** Day-one flat band (+/- this value). Default 0.05 = +/-5%. Range 0.0~0.20. */
    public static final ForgeConfigSpec.DoubleValue FIRST_DAY_FLAT_RANGE;

    // ---------------------------------------------------------- category 15: limit orders
    /** Maximum simultaneous limit orders per player. Default 10. Range 1~50. */
    public static final ForgeConfigSpec.IntValue LIMIT_ORDER_MAX;
    /** Limit order fee (percent), additive on top of the trade tax. Default 1. Range 0~5. */
    public static final ForgeConfigSpec.DoubleValue LIMIT_ORDER_FEE_PERCENT;
    /** Maximum limit-order lifetime (trading days); expired orders auto-cancel. Default 7. Range 1~30. */
    public static final ForgeConfigSpec.IntValue LIMIT_ORDER_MAX_KEEP_DAYS;
    /** How many consecutive marketTicks a limit order retries when funds or shares fall short before auto-cancelling. Default 3. Range 1~100. */
    public static final ForgeConfigSpec.IntValue LIMIT_ORDER_FAIL_RETRIES;
    /** Limit-order trigger deviation hint threshold. A limit order fills at the real market price; when the deviation between the fill price and the trigger price exceeds this percentage, a log line records it, but the order is never cancelled. Default 0.05 = 5%. Range 0.01~0.20. */
    public static final ForgeConfigSpec.DoubleValue LIMIT_ORDER_DEVIATION_THRESHOLD;

    // ---------------------------------------------------------- category 17: bankruptcy
    /** Post-bankruptcy short cooldown days: counted from the **trading day after** the one containing the bankruptcy moment, spanning that many **full trading days** during which new shorts are forbidden; after the cooldown the short quota is recomputed from the current highestLevel. Default 3. Range 0~30. */
    public static final ForgeConfigSpec.IntValue BANKRUPTCY_SHORT_COOLDOWN;

    // ---------------------------------------------------------- category 16: leaderboards
    /** Ranks shown per leaderboard page. Default 10. Range 5~50. */
    public static final ForgeConfigSpec.IntValue LEADERBOARD_SIZE;
    /** Whether the player's own rank shows at the leaderboard bottom. Default true; false lets players hide it. */
    public static final ForgeConfigSpec.BooleanValue LEADERBOARD_SHOW_SELF;

    // ---------------------------------------------------------- networking
    /** Quote sync mode: FULL = push the whole snapshot to every player each tick; INCREMENTAL = push deltas only to players with the UI open (default); ON_DEMAND = push only when a price moved more than 1%. */
    public static final ForgeConfigSpec.EnumValue<SyncModel> SYNC_MODEL;

    // ---------------------------------------------------------- custom stocks (category 10)
    /**
     * Custom stocks, one per line: STOCK_CODE|ITEM_ID|DISPLAY_NAME|BASE_PRICE|POINTS|UNLOCK_LEVEL.
     * Example: "MYST|minecraft:amethyst_shard|Amethyst|25|10|2".
     * The code must be 2-8 uppercase letters/digits and cannot clash with the 8 built-ins; at most 16 stocks; the display name may be empty (falls back to the item name);
     * base price 1.0~100000.0, points 1~10000, unlock level 1~4 (clamped out of range).
     * After saving, changes apply within 5 seconds: new stocks start at their base price with empty K-lines; removed stocks are only marked delisted, player positions are kept.
     */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CUSTOM_STOCKS;

    // ---------------------------------------------------------- category 20: UI theme (all 24-bit RGB, out of range falls back to default; effective values are always opaque)
    public static final ForgeConfigSpec.IntValue UI_PANEL_ROOT_RGB;
    public static final ForgeConfigSpec.IntValue UI_PANEL_SOFT_RGB;
    public static final ForgeConfigSpec.IntValue UI_PANEL_ROW_RGB;
    public static final ForgeConfigSpec.IntValue UI_BORDER_RGB;
    public static final ForgeConfigSpec.IntValue UI_DARK_RGB;
    public static final ForgeConfigSpec.IntValue UI_HIGHLIGHT_RGB;
    public static final ForgeConfigSpec.IntValue UI_ACCENT_RGB;
    public static final ForgeConfigSpec.IntValue UI_GOLD_RGB;
    public static final ForgeConfigSpec.IntValue UI_MUTED_RGB;
    public static final ForgeConfigSpec.IntValue UI_GRAY_RGB;
    public static final ForgeConfigSpec.IntValue UI_DIM_RGB;
    public static final ForgeConfigSpec.IntValue UI_QUOTE_UP_RGB;
    public static final ForgeConfigSpec.IntValue UI_QUOTE_DOWN_RGB;
    public static final ForgeConfigSpec.IntValue UI_QUOTE_FLAT_RGB;
    public static final ForgeConfigSpec.IntValue UI_PNL_UP_RGB;
    public static final ForgeConfigSpec.IntValue UI_PNL_DOWN_RGB;
    public static final ForgeConfigSpec.IntValue UI_SELECTED_RGB;
    public static final ForgeConfigSpec.IntValue UI_HOVER_RGB;
    public static final ForgeConfigSpec.IntValue UI_BLUE_RGB;
    public static final ForgeConfigSpec.IntValue UI_BUY_RGB;
    public static final ForgeConfigSpec.IntValue UI_TOAST_BG_RGB;
    public static final ForgeConfigSpec.IntValue UI_TOAST_FG_RGB;
    public static final ForgeConfigSpec.IntValue UI_KGRID_RGB;
    public static final ForgeConfigSpec.IntValue UI_CHART_BG_RGB;
    public static final ForgeConfigSpec.IntValue UI_LETTERBOX_RGB;
    public static final ForgeConfigSpec.IntValue UI_RARITY_COMMON_RGB;
    public static final ForgeConfigSpec.IntValue UI_RARITY_RARE_RGB;
    public static final ForgeConfigSpec.IntValue UI_RARITY_EPIC_RGB;
    public static final ForgeConfigSpec.IntValue UI_RARITY_LEGEND_RGB;
    public static final ForgeConfigSpec.IntValue UI_TEXT_RGB;
    public static final ForgeConfigSpec.IntValue UI_TEXT_SOFT_RGB;
    public static final ForgeConfigSpec.IntValue UI_TIP_TEXT_RGB;
    public static final ForgeConfigSpec.IntValue UI_BODY_TEXT_RGB;
    public static final ForgeConfigSpec.IntValue UI_WICK_UP_RGB;
    public static final ForgeConfigSpec.IntValue UI_WICK_DOWN_RGB;
    public static final ForgeConfigSpec.IntValue UI_BUTTON_HOVER_RGB;
    public static final ForgeConfigSpec.IntValue UI_DEPOSIT_HOVER_RGB;
    public static final ForgeConfigSpec.IntValue UI_WITHDRAW_HOVER_RGB;
    public static final ForgeConfigSpec.IntValue UI_WARNING_RGB;
    public static final ForgeConfigSpec.IntValue UI_HINT_GRAY_RGB;
    public static final ForgeConfigSpec.IntValue UI_GOLD_TEXT_RGB;
    public static final ForgeConfigSpec.IntValue UI_CHIP_BLUE_RGB;
    public static final ForgeConfigSpec.IntValue UI_ACCENT_ORANGE_RGB;
    public static final ForgeConfigSpec.IntValue UI_COIN_DARK_RGB;
    public static final ForgeConfigSpec.IntValue UI_COIN_LIGHT_RGB;

    // ---------------------------------------------------------- category 21: screen feel (scrolling / dragging / animation)
    public static final ForgeConfigSpec.IntValue UI_SCROLL_STEP;
    public static final ForgeConfigSpec.IntValue UI_SCROLL_STEP_FINE;
    public static final ForgeConfigSpec.IntValue UI_DRAG_THRESHOLD_PX;
    public static final ForgeConfigSpec.BooleanValue UI_SMOOTH_SCROLL;
    public static final ForgeConfigSpec.IntValue UI_SCROLL_LERP_PERCENT;
    public static final ForgeConfigSpec.IntValue UI_TOAST_DURATION_MS;
    public static final ForgeConfigSpec.IntValue UI_TOAST_FADE_MS;
    public static final ForgeConfigSpec.IntValue UI_CARD_ANIM_MS;
    public static final ForgeConfigSpec.IntValue UI_CLICK_DEBOUNCE_MS;

    // ---------------------------------------------------------- category 22: trade, event and chart display settings
    public static final ForgeConfigSpec.IntValue MAX_TRADE_QUANTITY;
    public static final ForgeConfigSpec.IntValue LOG_MAX_ENTRIES;
    public static final ForgeConfigSpec.IntValue EVENT_MAX_ACTIVE;
    public static final ForgeConfigSpec.IntValue EVENT_SUSPEND_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue EVENT_DIVIDEND_COOLDOWN_DAYS;
    public static final ForgeConfigSpec.IntValue EVENT_SPLIT_RATIO;
    public static final ForgeConfigSpec.IntValue KLINE_TICKS_PER_BAR;
    /** Default number of candles shown on the quote chart; retained history is still larger. */
    public static final int DEFAULT_KLINE_DISPLAY_COUNT = 60;
    public static final ForgeConfigSpec.IntValue KLINE_DISPLAY_COUNT;
    /** Whether a newly opened quote screen starts in short-term mode instead of daily mode. */
    public static final ForgeConfigSpec.BooleanValue KLINE_DEFAULT_SHORT;
    public static final ForgeConfigSpec.BooleanValue KLINE_REAL_PLAYER_VOLUME_ONLY;
    public static final ForgeConfigSpec.IntValue RARITY_WEIGHT_COMMON;
    public static final ForgeConfigSpec.IntValue RARITY_WEIGHT_RARE;
    public static final ForgeConfigSpec.IntValue RARITY_WEIGHT_EPIC;
    public static final ForgeConfigSpec.IntValue RARITY_WEIGHT_LEGEND;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        // ============================ category 1: level unlock thresholds ============================
        b.push("levelUnlock");
        b.comment(
                "\u80a1\u7968\u7b49\u7ea7\u89e3\u9501\u9608\u503c\u3002\u5224\u5b9a\u6807\u51c6\u662f\u73a9\u5bb6\u7684\u201c\u77ff\u5e01\u5386\u53f2\u5cf0\u503c peakOreCoins\u201d\uff08\u5355\u4f4d\uff1a\u77ff\u5e01\uff09\u3002 | Level unlock thresholds for stocks. Judged by each player's peak Ore-coins ever held (unit: coins).",
                "\u7b49\u7ea7 1 \u521d\u59cb\u89e3\u9501\uff08\u7164\u70ad\u3001\u7ea2\u77f3\u3001\u9752\u91d1\u77f3\u3001\u94dc\uff09\uff0c\u6ca1\u6709\u95e8\u69db\uff1b\u7b49\u7ea7 2/3/4 \u7684\u95e8\u69db\u5728\u8fd9\u91cc\u6539\u3002 | Level 1 (Coal, Redstone, Lapis, Copper) is unlocked from the start; edit the gates for levels 2/3/4 here.",
                "\u7b49\u7ea7\u663e\u793a\u4e0e\u89e3\u9501\u6743\u9650\u7528\u5386\u53f2\u6700\u9ad8\u7b49\u7ea7 highestLevel\uff1a\u4e00\u65e6\u89e3\u9501\u6c38\u4e0d\u6536\u56de\u3002 | Level display and unlock rights use the historical highest level: once unlocked, never revoked.",
                "\u53d6\u503c\u8303\u56f4\uff1a0 ~ 1000000\uff08\u7b49\u7ea7 4 \u4e0a\u9650 100000000\uff09\u3002\u6539\u5b8c\u4fdd\u5b58\u4f1a\u5373\u65f6\u91cd\u7b97\u5728\u7ebf\u73a9\u5bb6\u7b49\u7ea7\u3002 | Range: 0 ~ 1000000 (level 4 up to 100000000). Saving recomputes online players' levels immediately.");
        LEVEL2_THRESHOLD = b.comment("\u8fbe\u5230\u8be5\u77ff\u5e01\u5cf0\u503c\u89e3\u9501\u7b49\u7ea7 2\uff08\u94c1\u3001\u91d1\uff09\u3002\u9ed8\u8ba4 200\u3002\u8303\u56f4 0~1000000\u3002 | Peak coins needed to unlock level 2 (Iron, Gold). Default 200. Range 0~1000000.")
                .defineInRange("level2Threshold", 200L, 0L, 1_000_000L);
        LEVEL3_THRESHOLD = b.comment("\u8fbe\u5230\u8be5\u77ff\u5e01\u5cf0\u503c\u89e3\u9501\u7b49\u7ea7 3\uff08\u94bb\u77f3\uff09\u3002\u9ed8\u8ba4 2000\u3002\u8303\u56f4 0~10000000\u3002 | Peak coins needed to unlock level 3 (Diamond). Default 2000. Range 0~10000000.")
                .defineInRange("level3Threshold", 2000L, 0L, 10_000_000L);
        LEVEL4_THRESHOLD = b.comment("\u8fbe\u5230\u8be5\u77ff\u5e01\u5cf0\u503c\u89e3\u9501\u7b49\u7ea7 4\uff08\u4e0b\u754c\u5408\u91d1\u952d\uff09\u3002\u9ed8\u8ba4 8000\u3002\u8303\u56f4 0~100000000\u3002 | Peak coins needed to unlock level 4 (Netherite Ingot). Default 8000. Range 0~100000000.")
                .defineInRange("level4Threshold", 8000L, 0L, 100_000_000L);
        b.pop();

        // ============================ category 2: exchange rates ============================
        b.push("exchange");
        b.comment(
                "\u5151\u6362\u6240\u5217\u8868\u6bcf\u884c\u53f3\u4fa7\u680f\u7684\u56fa\u5b9a\u5151\u6362\u6c47\u7387\uff1a1 \u4e2a\u8be5\u77ff\u7269\u53ef\u5151\u6362\u591a\u5c11\u77ff\u5e01\uff0c\u4e0d\u968f\u884c\u60c5\u6ce2\u52a8\u3002 | Fixed exchange rates shown in the right column of each Exchange row: coins per ore item; never fluctuates with market prices.",
                "\u53d6\u503c\u8303\u56f4\uff1a1 ~ 10000 \u7684\u6574\u6570\u3002\u8bbe\u4e3a 0 \u6216\u8d1f\u6570\u4f1a\u88ab\u94b3\u6210 1\uff08\u907f\u514d\u9664\u96f6\uff09\u3002 | Integer range 1 ~ 10000. Values <= 0 are clamped to 1 (division-by-zero guard).",
                "\u82e5\u67d0\u53ea\u81ea\u5b9a\u4e49\u80a1\u7968\u7684\u5151\u6362\u4ef7\u8d85\u8fc7\u5b83\u57fa\u51c6\u4ef7\u7684 50%\uff0c\u542f\u52a8\u6216\u70ed\u91cd\u8f7d\u65f6\u65e5\u5fd7\u4f1a\u63d0\u793a\u201c\u53ef\u80fd\u67b6\u7a7a\u80a1\u5e02\u201d\uff0c\u4f46\u4e0d\u4f1a\u963b\u6b62\u73a9\u5bb6\u5151\u6362\u3002 | If a custom stock's exchange rate exceeds 50% of its base price, the startup or hot-reload log warns it may hollow out the market, but exchanging is not blocked.")
                ;
        EXCHANGE_COAL = b.comment("\u7164\u70ad\u5151\u6362\u6c47\u7387\uff1a1 \u7164\u70ad = X \u77ff\u5e01\u3002\u9ed8\u8ba4 3\u3002 | Coal exchange rate: 1 Coal = X coins. Default 3.").defineInRange("coal", 3, 1, 10000);
        EXCHANGE_REDSTONE = b.comment("\u7ea2\u77f3\u5151\u6362\u6c47\u7387\u3002\u9ed8\u8ba4 2\u3002 | Redstone exchange rate. Default 2.").defineInRange("redstone", 2, 1, 10000);
        EXCHANGE_LAPIS = b.comment("\u9752\u91d1\u77f3\u5151\u6362\u6c47\u7387\u3002\u9ed8\u8ba4 3\u3002 | Lapis Lazuli exchange rate. Default 3.").defineInRange("lapis", 3, 1, 10000);
        EXCHANGE_COPPER = b.comment("\u94dc\u5151\u6362\u6c47\u7387\u3002\u9ed8\u8ba4 4\u3002 | Copper exchange rate. Default 4.").defineInRange("copper", 4, 1, 10000);
        EXCHANGE_IRON = b.comment("\u94c1\u5151\u6362\u6c47\u7387\u3002\u9ed8\u8ba4 8\u3002 | Iron exchange rate. Default 8.").defineInRange("iron", 8, 1, 10000);
        EXCHANGE_GOLD = b.comment("\u91d1\u5151\u6362\u6c47\u7387\u3002\u9ed8\u8ba4 15\u3002 | Gold exchange rate. Default 15.").defineInRange("gold", 15, 1, 10000);
        EXCHANGE_DIAMOND = b.comment("\u94bb\u77f3\u5151\u6362\u6c47\u7387\u3002\u9ed8\u8ba4 40\u3002 | Diamond exchange rate. Default 40.").defineInRange("diamond", 40, 1, 10000);
        EXCHANGE_NETHERITE = b.comment("\u4e0b\u754c\u5408\u91d1\u952d\u5151\u6362\u6c47\u7387\u3002\u9ed8\u8ba4 120\u3002 | Netherite Ingot exchange rate. Default 120.").defineInRange("netherite", 120, 1, 10000);
        b.pop();

        // ============================ category 3: stock base prices ============================
        b.push("basePrice");
        b.comment("\u80a1\u7968\u57fa\u51c6\u4ef7\uff08\u5355\u4f4d\uff1a\u77ff\u5e01\uff09\u3002\u4ef7\u683c\u5f15\u64ce\u6bcf\u4e2a marketTick \u90fd\u628a\u73b0\u4ef7\u5f80\u57fa\u51c6\u4ef7\u56de\u5f52\uff0c \u6da8\u8dcc\u505c\u533a\u95f4\u4e0e floor/ceiling \u94b3\u5236\u4e5f\u4ee5\u57fa\u51c6\u4ef7\u4e3a\u951a\u3002\u6539\u57fa\u51c6\u4ef7\u4e0d\u4f1a\u7acb\u523b\u6539\u5199\u73b0\u4ef7\uff0c \u53ea\u4f1a\u6539\u53d8\u56de\u5f52\u76ee\u6807\uff1b\u5df2\u6709\u6301\u4ed3\u4e0e\u5e73\u5747\u6210\u672c\u4e0d\u53d7\u5f71\u54cd\u3002\u53d6\u503c\u8303\u56f4\uff1a1.0 ~ 100000.0\u3002 | Stock base price (unit: coins). The price engine reverts the current price toward it every marketTick;  price limits and floor/ceiling clamps are anchored to it too. Changing it does not rewrite the current price  instantly, only the reversion target; existing positions and avg cost are unaffected. Range 1.0 ~ 100000.0.")
                ;
        BASE_COAL = b.comment("\u7164\u70ad\u57fa\u51c6\u4ef7\u3002\u9ed8\u8ba4 8.0\u3002 | Coal base price. Default 8.0.").defineInRange("coal", 8.0, 1.0, 100000.0);
        BASE_REDSTONE = b.comment("\u7ea2\u77f3\u57fa\u51c6\u4ef7\u3002\u9ed8\u8ba4 6.0\u3002 | Redstone base price. Default 6.0.").defineInRange("redstone", 6.0, 1.0, 100000.0);
        BASE_LAPIS = b.comment("\u9752\u91d1\u77f3\u57fa\u51c6\u4ef7\u3002\u9ed8\u8ba4 10.0\u3002 | Lapis Lazuli base price. Default 10.0.").defineInRange("lapis", 10.0, 1.0, 100000.0);
        BASE_COPPER = b.comment("\u94dc\u57fa\u51c6\u4ef7\u3002\u9ed8\u8ba4 12.0\u3002 | Copper base price. Default 12.0.").defineInRange("copper", 12.0, 1.0, 100000.0);
        BASE_IRON = b.comment("\u94c1\u57fa\u51c6\u4ef7\u3002\u9ed8\u8ba4 18.0\u3002 | Iron base price. Default 18.0.").defineInRange("iron", 18.0, 1.0, 100000.0);
        BASE_GOLD = b.comment("\u91d1\u57fa\u51c6\u4ef7\u3002\u9ed8\u8ba4 35.0\u3002 | Gold base price. Default 35.0.").defineInRange("gold", 35.0, 1.0, 100000.0);
        BASE_DIAMOND = b.comment("\u94bb\u77f3\u57fa\u51c6\u4ef7\u3002\u9ed8\u8ba4 70.0\u3002 | Diamond base price. Default 70.0.").defineInRange("diamond", 70.0, 1.0, 100000.0);
        BASE_NETHERITE = b.comment("\u4e0b\u754c\u5408\u91d1\u952d\u57fa\u51c6\u4ef7\u3002\u9ed8\u8ba4 160.0\u3002 | Netherite Ingot base price. Default 160.0.").defineInRange("netherite", 160.0, 1.0, 100000.0);
        b.pop();

        // ============================ category 4: price engine parameters (shared by all stocks) ============================
        b.push("priceEngine");
        b.comment(
                "\u4ef7\u683c\u5f15\u64ce\u53c2\u6570\uff1a\u4ee5\u4e0b\u516b\u9879**\u6240\u6709\u80a1\u7968\u5171\u7528\u4e00\u5957**\uff0c\u4e0d\u652f\u6301\u6309\u80a1\u7968\u5355\u72ec\u8bbe\u7f6e\u3002 | Price engine parameters: these eight entries are shared by ALL stocks; per-stock overrides are not supported.",
                "\u4e00\u4e2a marketTick \u7684\u6f14\u5316\u987a\u5e8f\uff1a\u968f\u673a\u6ce2\u52a8 \u2192 \u8d8b\u52bf\u9879 \u2192 \u5747\u503c\u56de\u5f52 \u2192 \u4e8b\u4ef6\u4ef7\u683c\u500d\u7387 \u2192 \u533a\u95f4\u94b3\u5236 \u2192 \u6da8\u8dcc\u505c\u3002 | Per-marketTick evolution order: random fluctuation, trend, mean reversion, event price multiplier, range clamp, price limits.");
        MEAN_REVERSION_FACTOR = b.comment("\u5747\u503c\u56de\u5f52\u5f3a\u5ea6\uff1a\u6bcf marketTick \u73b0\u4ef7\u5411\u57fa\u51c6\u4ef7\u9760\u62e2\u7684\u6bd4\u4f8b\u30020.03 \u8868\u793a\u6bcf\u6b21\u6536\u6389 3% \u7684\u4ef7\u5dee\u3002\u914d\u7f6e\u8303\u56f4 0.0~0.5\uff0c \u4f46\u4e8b\u4ef6\u805a\u5408\u5c42\u628a\u6bcf\u80a1\u751f\u6548\u503c\u94b3\u8fdb 0.0~0.15\uff08MarketModifiers.finish\uff09\uff0c \u56e0\u6b64 0.15 \u4ee5\u4e0a\u7684\u914d\u7f6e\u503c\u4e00\u5f8b\u6309 0.15 \u751f\u6548\u3002 | Mean reversion strength per marketTick: 0.03 pulls 3% of the gap back. Configured range is 0.0~0.5, but the event aggregation layer clamps the effective per-stock value to 0.0~0.15, so anything above 0.15 behaves as 0.15. strength: share of the gap to base price closed per marketTick. 0.03 closes 3% each time. Range 0.0~0.5.").defineInRange("meanReversionFactor", 0.03, 0.0, 0.5);
        REVERSION_DEAD_ZONE_PERCENT = b.comment("\u5747\u503c\u56de\u5f52\u6b7b\u533a\uff08\u767e\u5206\u6bd4\uff09\uff1a\u73b0\u4ef7\u504f\u79bb\u201c\u62a5\u4ef7\u951a\u201d\uff08\u57fa\u51c6\u4ef7\u7ecf\u62c6\u80a1/\u5206\u7ea2\u6362\u7b97\u540e\u7684\u6709\u6548\u57fa\u51c6\uff09\u8d85\u8fc7\u8be5\u5e45\u5ea6\u65f6\uff0c\u5747\u503c\u56de\u5f52\u5b8c\u5168\u505c\u624b\uff0c\u53ea\u7559\u8d8b\u52bf\u4e0e\u6ce2\u52a8\u3002\u9ed8\u8ba4 8\uff1a\u53ea\u6709 \u00b18% \u4ee5\u5185\u7684\u5c0f\u504f\u79bb\u4f1a\u88ab\u81ea\u52a8\u62c9\u56de\uff1b\u6df1\u8dcc\u4e0e\u66b4\u6da8\u4e0d\u518d\u4fdd\u8bc1\u81ea\u52a8\u4fee\u590d\u2014\u2014\u201c\u4e70\u5728\u5730\u677f\u4ef7\u3001\u5750\u7b49\u56de\u6da8\u201d\u4ece\u786e\u5b9a\u6027\u6536\u76ca\u53d8\u6210\u771f\u5b9e\u6301\u4ed3\u98ce\u9669\uff08\u6df1\u8dcc\u4ecd\u8981\u9762\u5bf9\u9000\u5e02\u89c4\u5219\uff09\u3002\u586b 100 \u8868\u793a\u5173\u95ed\u6b7b\u533a\uff1a\u4efb\u610f\u504f\u79bb\u90fd\u56de\u5f52\uff0c\u56de\u5f52\u5f3a\u5ea6\u89c1 meanReversionFactor\u3002\u8303\u56f4 0.0~100.0\u3002 | Mean-reversion dead zone (percent): beyond this distance from the quote anchor (base price rebased by splits/dividends), mean reversion stops entirely and only trend/noise remain. Default 8: pull-back inside +/-8% only; deep discounts and premiums no longer heal by themselves, so 'buy the floor and wait' becomes a real position instead of a guaranteed ladder (the low side still faces the delisting rules). 100 disables the dead zone (revert at any distance; strength = meanReversionFactor). Range 0.0~100.0.")
                .defineInRange("reversionDeadZonePercent", 8.0, 0.0, 100.0);
        RANDOM_FLUCTUATION = b.comment("\u968f\u673a\u6ce2\u52a8\u5e45\u5ea6\uff1a\u6bcf marketTick \u4ef7\u683c\u5728 \u00b1\u8be5\u6bd4\u4f8b\u5185\u968f\u673a\u6296\u52a8\u30020.02 \u5373 \u00b12%\u3002\u8303\u56f4 0.0~0.5\u3002 | Random fluctuation: per-marketTick jitter within +/- this ratio. 0.02 = +/-2%. Range 0.0~0.5.")
                .defineInRange("randomFluctuation", 0.02, 0.0, 0.5);
        TREND_IMPACT = b.comment("\u8d8b\u52bf\u9879\u5f71\u54cd\uff1a\u8d8b\u52bf\u4e3a\u6da8/\u8dcc\u65f6\u6bcf\u4e2a tick \u53e0\u52a0\u7684\u56fa\u5b9a\u6da8\u8dcc\u6bd4\u4f8b\u3002\u8303\u56f4 0.0~0.1\u3002 | Trend impact: fixed per-tick drift added while the trend is up/down. Range 0.0~0.1.")
                .defineInRange("trendImpact", 0.005, 0.0, 0.1);
        TRADE_IMPACT_FACTOR = b.comment("\u4ea4\u6613\u51b2\u51fb\uff1a\u5355\u7b14\u6210\u4ea4\u91cf\u5360\u603b\u6301\u4ed3\u5bf9\u4ef7\u683c\u7684\u63a8\u52a8\u6bd4\u4f8b\uff08\u4e70\u5356\u5404\u7b97\u4e00\u6b21\uff09\u3002\u8303\u56f4 0.0~0.1\u3002 | Trade impact: how much one fill moves the price relative to total holdings (buy and sell each count once). Range 0.0~0.1.")
                .defineInRange("tradeImpactFactor", 0.004, 0.0, 0.1);
        TRADE_SLIPPAGE = b.comment("\u662f\u5426\u5728\u6210\u4ea4\u65f6\u6263\u5165\u4ea4\u6613\u51b2\u51fb\u7684\u4e00\u534a\u4f5c\u4e3a\u8fd1\u4f3c\u6ed1\u70b9\u3002\u9ed8\u8ba4 true \u542f\u7528\u8fd1\u4f3c\u6ed1\u70b9\u3002 | Whether to charge half of existing trade impact at execution as approximate slippage. Default true enables approximate slippage.")
                .define("tradeSlippage", true);
        INDEX_VOLATILITY_WEIGHT = b.comment("\u5927\u76d8\u5171\u4eab\u6ce2\u52a8\u6743\u91cd\u3002\u9ed8\u8ba4 0.15 \u53e0\u52a0\u8f7b\u5ea6\u5927\u76d8\u5171\u9707\uff1b\u8303\u56f4 0.0~0.5\u3002 | Shared market-wide volatility weight. Default 0.15 adds a light shared market factor. Range 0.0~0.5.")
                .defineInRange("indexVolatilityWeight", 0.15, 0.0, 0.5);
        PRICE_FLOOR_PERCENT = b.comment("\u4ef7\u683c\u4e0b\u9650\uff1a\u73b0\u4ef7\u4e0d\u5f97\u4f4e\u4e8e\u57fa\u51c6\u4ef7\u7684\u8fd9\u4e2a\u767e\u5206\u6bd4\u3002\u8303\u56f4 1~100\u3002 | Price floor: current price cannot fall below this percent of base. Range 1~100.")
                .defineInRange("priceFloorPercent", 25.0, 1.0, 100.0);
        PRICE_CEILING_PERCENT = b.comment("\u4ef7\u683c\u4e0a\u9650\uff1a\u73b0\u4ef7\u4e0d\u5f97\u9ad8\u4e8e\u57fa\u51c6\u4ef7\u7684\u8fd9\u4e2a\u767e\u5206\u6bd4\u3002\u8303\u56f4 100~10000\u3002 | Price ceiling: current price cannot exceed this percent of base. Range 100~10000.")
                .defineInRange("priceCeilingPercent", 400.0, 100.0, 10000.0);
        b.pop();

        // ============================ category 5: time parameters ============================
        b.push("time");
        b.comment("\u65f6\u95f4\u53c2\u6570\u3002marketTick \u662f\u672c\u6a21\u7ec4\u81ea\u5df1\u7684\u884c\u60c5\u8282\u62cd\uff1a\u6bcf marketTickInterval \u4e2a\u6e38\u620f tick \u63a8\u8fdb\u4e00\u6b21\u4ef7\u683c\u6f14\u5316\uff08\u6e38\u620f\u91cc 20 tick = 1 \u79d2\uff09\u3002\u4ea4\u6613\u65e5\u957f\u5ea6\u7528\u6e38\u620f tick \u8868\u793a\u3002 \u82e5 tradingDayTicks \u4e0d\u662f marketTickInterval \u7684\u6574\u6570\u500d\uff0c\u4f1a\u5411\u4e0b\u53d6\u6574\u5230\u6700\u8fd1\u7684\u6574\u6570\u500d\u5e76\u6253\u5370\u4e00\u6b21\u8b66\u544a\u3002 \u6539 marketTickInterval \u4f1a\u8ba9 marketTick \u8ba1\u6570\u5668\u7acb\u5373\u6e05\u96f6\uff1b \u6539 tradingDayTicks \u4f1a\u8ba9\u5f53\u524d\u4ea4\u6613\u65e5\u7acb\u5373\u7ed3\u675f\uff08\u51bb\u7ed3\u6301\u4ed3\u6309\u9694\u65e5\u89e3\u51bb\u89c4\u5219\u5904\u7406\uff09\u3002 | Timing. marketTick is this mod's own quote heartbeat: prices evolve every marketTickInterval game ticks  (20 game ticks = 1 s). Trading-day length is expressed in game ticks.  If tradingDayTicks is not a multiple of marketTickInterval it is floored to the nearest multiple with one warning.  Changing marketTickInterval resets the marketTick counter instantly;  changing tradingDayTicks ends the current trading day instantly (frozen positions unfreeze next day).")
                ;
        MARKET_TICK_INTERVAL = b.comment("\u6bcf\u4e2a marketTick \u95f4\u9694\u591a\u5c11\u6e38\u620f tick\u3002\u9ed8\u8ba4 60\uff08=3 \u79d2\uff09\u3002\u8303\u56f4 1~24000\u3002 | Game ticks between marketTicks. Default 60 (=3 s). Range 1~24000.")
                .defineInRange("marketTickInterval", 60, 1, 24000);
        TRADING_DAY_TICKS = b.comment("\u4e00\u4e2a\u4ea4\u6613\u65e5\u6301\u7eed\u591a\u5c11\u6e38\u620f tick\u3002\u9ed8\u8ba4 24000\uff08=20 \u5206\u949f\uff09\u3002\u8303\u56f4 100~240000\u3002 | Game ticks per trading day. Default 24000 (=20 min). Range 100~240000.")
                .defineInRange("tradingDayTicks", 24000, 100, 240000);
        b.pop();

        // ============================ category 6: loan parameters ============================
        b.push("loan");
        b.comment("\u8d37\u6b3e\u53c2\u6570\u3002\u4fe1\u7528\u989d\u5ea6\u6309\u73a9\u5bb6\u7684**\u5386\u53f2\u6700\u9ad8\u7b49\u7ea7** highestLevel \u53d6\u503c\uff1b \u501f\u6b3e\u8d44\u683c\u4e3a highestLevel >= 2\uff08\u7b49\u7ea7 2 \u95e8\u69db\u89c1\u5206\u7c7b\u4e00\uff0c\u4e0d\u786c\u7f16\u7801\uff09\u3002 \u5355\u5229\u8ba1\u606f\uff1a\u6bcf\u5929\u5229\u606f = \u672c\u91d1 \u00d7 loanDailyInterestPercent%\u3002 \u5df2\u6709\u8d37\u6b3e\u9501\u5b9a\u7684\u662f**\u501f\u6b3e\u5f53\u65f6**\u7684\u5229\u7387\u3001\u671f\u9650\u4e0e\u5229\u606f\u4e0a\u9650\u500d\u6570\uff0c\u6539\u914d\u7f6e\u53ea\u5f71\u54cd\u65b0\u8d37\u6b3e\u3002 | Loans. Credit limit is taken from each player's historical highest level; eligibility is highestLevel >= 2  (the level-2 gate comes from category 1, never hardcoded). Simple interest: daily interest = principal x loanDailyInterestPercent%.  Existing loans keep the rate, term and interest-cap multiplier from when they were borrowed; edits affect new loans only.")
                ;
        CREDIT_LIMIT_LEVEL2 = b.comment("\u7b49\u7ea7 2 \u7684\u4fe1\u7528\u989d\u5ea6\uff08\u77ff\u5e01\uff09\u3002\u9ed8\u8ba4 200\u3002\u8303\u56f4 0~1000000\u3002 | Credit limit at level 2 (coins). Default 200. Range 0~1000000.")
                .defineInRange("creditLimitLevel2", 200L, 0L, 1_000_000L);
        CREDIT_LIMIT_LEVEL3 = b.comment("\u7b49\u7ea7 3 \u7684\u4fe1\u7528\u989d\u5ea6\uff08\u77ff\u5e01\uff09\u3002\u9ed8\u8ba4 500\u3002\u8303\u56f4 0~1000000\u3002 | Credit limit at level 3 (coins). Default 500. Range 0~1000000.")
                .defineInRange("creditLimitLevel3", 500L, 0L, 1_000_000L);
        CREDIT_LIMIT_LEVEL4 = b.comment("\u7b49\u7ea7 4 \u7684\u4fe1\u7528\u989d\u5ea6\uff08\u77ff\u5e01\uff09\u3002\u9ed8\u8ba4 1200\u3002\u8303\u56f4 0~1000000\u3002 | Credit limit at level 4 (coins). Default 1200. Range 0~1000000.")
                .defineInRange("creditLimitLevel4", 1200L, 0L, 1_000_000L);
        LOAN_DAILY_INTEREST_PERCENT = b.comment("\u8d37\u6b3e\u65e5\u5229\u7387\uff08\u767e\u5206\u6bd4\uff0c\u540d\u4e49\u57fa\u51c6\uff09\u3002\u9ed8\u8ba4 0.5\uff0c\u5373\u6bcf\u5929\u672c\u91d1 0.5% \u5229\u606f\u57fa\u51c6\u3002\u8303\u56f4 0~100\u3002 \u771f\u5b9e\u751f\u6548\u94fe\uff08\u9010\u9879\u90fd\u5728\u8fd9\u6761\u94fe\u4e0a\u751f\u6548\uff0c\u6ca1\u6709\u6446\u8bbe\uff09\uff1a\u672c\u503c\u5148\u94b3\u8fdb\u8f6f\u4e0a\u9650 0.5 \uff08LoanRules.BASE_SOFT_MAX_PERCENT\uff09\uff0c\u518d\u4e58\u4fe1\u7528\u51cf\u514d\u4e58\u6570\uff080.6~1.4\uff09\u3001 \u4e8b\u4ef6\u6d6e\u52a8\uff08\u00b125%\uff0c\u53d7 loanInterestVariance\uff09\u3001\u671f\u9650\u4e58\u6570\u4e0e\u4ea7\u54c1\u7cfb\u6570\uff0c \u6700\u540e\u5f3a\u5236 min(\u7ed3\u679c, loanRateCapPercent, 1.0%) \u5c01\u9876\u3002 \u8f6f\u4e0a\u9650\u6545\u610f\u53ea\u7ed9\u5230\u786c\u9876\u7684\u4e00\u534a\uff1a\u7559\u51fa\u6446\u52a8\u4f59\u91cf\uff0c\u4fe1\u7528\u6298\u6263\u4e0e\u671f\u9650\u4e58\u6570\u624d\u4e0d\u4f1a\u88ab\u5c01\u9876\u538b\u6210 \u4e00\u4e2a\u5e38\u6570\uff08\u754c\u9762\u65e5\u5229\u7387\u6c38\u8fdc\u663e\u793a\u540c\u4e00\u4e2a\u6570\u7684\u90a3\u4e2a Bug \u5c31\u662f\u8fd9\u4e48\u6765\u7684\uff09\u3002 \u56e0\u6b64\u586b 0.5 \u4ee5\u4e0a\u4e0e\u586b 0.5 \u6548\u679c\u76f8\u540c\uff0c\u5f80\u5c0f\u8c03\u624d\u771f\u6b63\u6539\u53d8\u7ed3\u679c\u3002 | Nominal daily loan interest rate in percent. Default 0.5 (0.5% of principal per day as the base). Range 0~100. Effective chain: the value is first soft-capped at 0.5 (LoanRules.BASE_SOFT_MAX_PERCENT), multiplied by the credit factor (0.6~1.4), the event swing (+-25%, see loanInterestVariance), the term factor and the product factor, then hard-capped by min(result, loanRateCapPercent, 1.0%). The soft cap sits at half the hard cap on purpose: it leaves room for the credit and term factors to move at all, otherwise every rate pins to the ceiling and the UI shows one constant number. Values above 0.5 behave like 0.5; only lowering the value changes the result.")
                .defineInRange("loanDailyInterestPercent", 0.5, 0.0, 100.0);
        LOAN_TERM_DAYS = b.comment("\u8d37\u6b3e\u671f\u9650\u57fa\u51c6\uff08\u4ea4\u6613\u65e5\uff09\u3002\u9ed8\u8ba4 5\u3002\u8303\u56f4 1~30\u3002 \u7cfb\u7edf\u8d37\u6b3e\u6bcf\u671f\u5929\u6570\u548c P2P \u6302\u5355\u9ed8\u8ba4\u671f\u9650\u90fd\u8bfb\u53d6\u672c\u503c\uff1bP2P \u6302\u5355\u671f\u9650\u4e0a\u9650 = \u672c\u503c \u00d7 3\u3002 | Loan term baseline in trading days. Default 5. Range 1~30. New system-loan installments and the default P2P listing term read this value; the P2P maximum is this value x 3.")
                .defineInRange("loanTermDays", 5, 1, 30);
        BANKRUPTCY_COOLDOWN_DAYS = b.comment("\u7834\u4ea7\u51b7\u5374\uff08\u4ea4\u6613\u65e5\uff09\u3002\u9ed8\u8ba4 3\uff1a\u7834\u4ea7\u540e 3 \u4e2a\u5b8c\u6574\u4ea4\u6613\u65e5\u5185\u7981\u6b62\u518d\u501f\u3002\u8303\u56f4 0~30\u3002 | Bankruptcy cooldown (trading days). Default 3: no new loans for 3 full trading days after bankruptcy. Range 0~30.")
                .defineInRange("bankruptcyCooldownDays", 3, 0, 30);
        LOAN_INTEREST_CAP_MULTIPLIER = b.comment("\u672c\u606f\u5408\u8ba1\u4e0a\u9650\u500d\u6570\uff1a\u672a\u8fd8\u7684\u300c\u5229\u606f + \u7f5a\u606f\u300d\u6700\u591a = \u672c\u91d1 \u00d7 (\u8be5\u500d\u6570 \u2212 1)\uff0c \u4e5f\u5c31\u662f\u5e94\u8fd8\u603b\u989d\u6c38\u4e0d\u8d85\u8fc7\u672c\u91d1 \u00d7 \u8be5\u500d\u6570\u3002\u9ed8\u8ba4 2.0\uff08\u6700\u591a\u8fd8\u4e24\u500d\u672c\u91d1\uff09\u3002\u8303\u56f4 1.0~10.0\u3002 \u586b 1.0 \u7b49\u4e8e\u628a\u5229\u606f\u4e0e\u7f5a\u606f\u5168\u90e8\u6e05\u96f6\uff08\u4e0a\u9650 = \u672c\u91d1 \u00d7 0\uff09\uff0c\u53ea\u8fd8\u80fd\u501f\u5230\u7684\u672c\u91d1\u3002 | Cap multiplier on the total payoff: unpaid interest plus penalty never exceeds principal x (this factor - 1), i.e. the amount owed never rises above principal x this factor. Default 2.0 (at most double the principal). 1.0 zeroes out all interest and penalty.")
                .defineInRange("loanInterestCapMultiplier", 2.0, 1.0, 10.0);
        LOAN_DESTROY_PRINCIPAL = b.comment("\u8d37\u6b3e\u672c\u91d1\u662f\u5426\u9500\u6bc1\uff1atrue\uff08\u9ed8\u8ba4\uff09 = \u73a9\u5bb6\u8fd8\u6b3e\u65f6\u672c\u91d1\u4ece\u77ff\u5e01\u4f59\u989d\u6263\u9664\u540e\u76f4\u63a5\u9500\u6bc1\uff0c\u4e0d\u8fdb\u5165\u4efb\u4f55\u6c60\u5b50\uff1b false = \u672c\u91d1\u4e0d\u9500\u6bc1\uff0c\u8f6c\u5165\u7cfb\u7edf\u51c6\u5907\u91d1\u6c60\uff08loanReserve\uff09\u8bb0\u8d26\u3002 \u6ce8\u610f\uff1a\u51c6\u5907\u91d1\u6c60\u662f\u4e00\u672c\u7cfb\u7edf\u53f0\u8d26\uff0c\u653e\u6b3e\u59cb\u7ec8\u6309\u7533\u8bf7\u989d\u5168\u989d\u5230\u8d26\uff0c\u56e0\u6b64\u628a true \u6539\u6210 false  \u4e0d\u4f1a\u6539\u53d8\u6e38\u620f\u91cc\u6d41\u901a\u7684\u77ff\u5e01\u603b\u91cf\uff0c\u53ea\u6539\u53d8\u300c\u8fd9\u7b14\u94b1\u7b97\u9500\u6bc1\u8fd8\u662f\u7b97\u51c6\u5907\u91d1\u300d\u7684\u7edf\u8ba1\u53e3\u5f84\u3002 | Whether repaid loan principal is burned: true (default) = deducted and destroyed, entering no pool;  false = moved into the system reserve ledger (loanReserve). Note: the reserve is a system ledger, lending always credits the full requested amount, so switching this to false changes bookkeeping, not the coins in circulation.")
                .define("loanDestroyPrincipal", true);
        LOAN_DESTROY_INTEREST = b.comment("\u8d37\u6b3e\u5229\u606f\u662f\u5426\u9500\u6bc1\uff1atrue\uff08\u9ed8\u8ba4\uff09 = \u8fd8\u6b3e\u65f6\u5229\u606f\u4e0e\u7f5a\u606f\u90e8\u5206\u76f4\u63a5\u9500\u6bc1\uff1b false = \u8f6c\u5165\u7cfb\u7edf\u51c6\u5907\u91d1\u6c60\uff08loanReserve\uff09\u8bb0\u8d26\u800c\u4e0d\u662f\u9500\u6bc1\u3002 \u5f52\u5c5e\u53e3\u5f84\u89c1\u4e0a\uff1a\u51c6\u5907\u91d1\u662f\u53f0\u8d26\uff0c\u4e0d\u6539\u53d8\u6d41\u901a\u603b\u91cf\u3002 | Whether repaid loan interest is burned: true (default) = destroyed on repayment;  false = moved into the system reserve ledger. Same caveat as above: the reserve is bookkeeping and does not change circulating supply.")
                .define("loanDestroyInterest", true);
        // ---------- loan v2: auto repayment / grace and penalty / credit scoring / dynamic quota / floating rate / early repayment / products / P2P
        LOAN_AUTO_REPAY = b.comment("\u6536\u76d8\u81ea\u52a8\u8fd8\u6b3e\uff1a\u6bcf\u4e2a\u4ea4\u6613\u65e5\u6536\u76d8\u65f6\uff0c\u82e5\u77ff\u5e01\u4f59\u989d\u591f\u4ed8\u5f53\u671f\u5e94\u8fd8\uff0c\u7cfb\u7edf\u81ea\u52a8\u6263\u6b3e\u8fd8\u6b3e\u3002\u9ed8\u8ba4 true\u3002 | Auto repay at every trading day close when the coin balance covers the due amount. Default true.")
                .define("loanAutoRepay", true);
        LOAN_GRACE_PERIOD_DAYS = b.comment("\u903e\u671f\u5bbd\u9650\uff08\u4ea4\u6613\u65e5\uff09\uff1a\u5230\u671f\u540e\u8be5\u5929\u6570\u5185\u4e0d\u8ba1\u7f5a\u606f\u3001\u4e0d\u964d\u4fe1\u7528\u5206\u3002\u9ed8\u8ba4 1\u3002 | Grace period (trading days) after maturity with no penalty interest and no credit hit. Default 1.")
                .defineInRange("loanGracePeriodDays", 1, 0, 30);
        LOAN_PENALTY_PERCENT = b.comment("\u903e\u671f\u7f5a\u606f\u65e5\u5229\u7387\uff08\u767e\u5206\u6bd4\uff09\uff1a\u8fc7\u4e86\u5bbd\u9650\u671f\u540e\uff0c\u903e\u671f\u672a\u8fd8\u90e8\u5206\u6bcf\u5929\u6309\u8be5\u6bd4\u4f8b\u52a0\u6536\u3002\u9ed8\u8ba4 2.0\u3002 \u586b 0 \u6216\u8d1f\u6570\u4e0d\u4f1a\u8ba9\u903e\u671f\u514d\u8d39\uff1a\u4ee3\u7801\u5f3a\u5236\u56de\u843d\u5230\u6bcf\u65e5 5%\uff08LoanRules.DEFAULT_PENALTY_PERCENT\uff09\u3002 \u7f5a\u606f\u4e0e\u5229\u606f\u4e00\u8d77\u53d7 loanInterestCapMultiplier \u7684\u672c\u606f\u5408\u8ba1\u4e0a\u9650\u7ea6\u675f\uff0c\u4e0d\u4f1a\u65e0\u9650\u6eda\u3002 | Daily penalty rate after grace (percent of the overdue amount). Default 2.0. Zero or negative does NOT make overdue free - it falls back to the forced 5% per day. Penalty and interest share the total cap set by loanInterestCapMultiplier.")
                .defineInRange("loanPenaltyPercent", 2.0, 0.0, 100.0);
        LOAN_CREDIT_SCORE_ENABLED = b.comment("\u4fe1\u7528\u8bc4\u5206\u4f53\u7cfb\uff1a\u6309\u8fd8\u6b3e\u8bb0\u5f55/\u903e\u671f\u6b21\u6570/\u7834\u4ea7\u5386\u53f2/\u603b\u8d44\u4ea7/\u7b49\u7ea7\u8ba1\u7b97 300~950 \u5206\uff0c\u5f71\u54cd\u989d\u5ea6\u4e0e\u5229\u7387\u3002\u9ed8\u8ba4 true\uff1b\u5173\u95ed\u540e\u989d\u5ea6\u4e0e\u5229\u7387\u53ea\u6309\u73a9\u5bb6\u7b49\u7ea7\u7b97\uff0c\u4e0d\u518d\u8bfb\u8fd8\u6b3e\u8bb0\u5f55\u3002 | Credit scoring from repay record, overdue count, bankruptcy history, assets and tier. Default true; off restores tier-only behaviour.")
                .define("loanCreditScoreEnabled", true);
        LOAN_P2P_ENABLED = b.comment("\u73a9\u5bb6\u95f4\u501f\u8d37\uff08P2P\uff09\uff1a\u73a9\u5bb6\u53ef\u6302\u51fa\u501f\u6761\uff0c\u5176\u4ed6\u73a9\u5bb6\u6309\u6302\u5355\u5229\u7387\u501f\u6b3e\uff0c\u5e73\u53f0\u6536\u53d6\u4e00\u6b21\u6027\u624b\u7eed\u8d39\u3002\u9ed8\u8ba4 true\uff08\u5173\u95ed\u65f6\u501f\u6b3e\u4e00\u5f8b\u8d70\u7cfb\u7edf\u8d44\u91d1\u6c60\uff09\u3002 | Player-to-player lending with listed offers and a one-off platform fee. Default true.")
                .define("loanP2PEnabled", true);
        LOAN_INTEREST_VARIANCE = b.comment("\u6d6e\u52a8\u5229\u7387\u5e45\u5ea6\uff08\u5360\u56fa\u5b9a\u5229\u7387\u7684\u767e\u5206\u6bd4\uff09\uff1a\u592e\u884c/\u5371\u673a\u4e8b\u4ef6\u628a\u5229\u7387\u56e0\u5b50\u63a8\u5230 +1 \u65f6\u7684\u6700\u5927\u4e0a\u6d6e\u5e45\u5ea6\u3002\u9ed8\u8ba4 40\u3002 \u6446\u52a8\u6bd4\u4f8b\u672c\u8eab\u88ab\u4ee3\u7801\u94b3\u8fdb \u00b125%\uff08LoanRules.EVENT_SWING\uff09\uff0c\u6240\u4ee5 25 \u4ee5\u4e0a\u7684\u914d\u7f6e\u503c\u90fd\u6309 25 \u751f\u6548\uff1b \u4e0a\u6d6e\u540e\u7684\u6700\u7ec8\u5229\u7387\u518d\u53d7 loanRateCapPercent \u4e0e 1.0% \u786c\u9876\u53cc\u91cd\u5c01\u9876\u3002 | Floating amplitude in percent of the fixed rate when an event pushes the rate factor to +1. Default 40. The swing ratio itself is clamped to +-25% in code, so anything above 25 behaves as 25; the resulting rate is then hard-capped by loanRateCapPercent and the 1.0% ceiling.")
                .defineInRange("loanInterestVariance", 40.0, 0.0, 200.0);
        LOAN_RATE_CAP_PERCENT = b.comment("\u65e5\u5229\u7387\u786c\u4e0a\u9650\uff08\u767e\u5206\u6bd4\uff09\uff1a\u56fa\u5b9a + \u6d6e\u52a8 + \u4fe1\u7528 + \u671f\u9650 + \u4ea7\u54c1\u7cfb\u6570\u5168\u90e8\u4e58\u5b8c\u4e4b\u540e\uff0c \u6700\u7ec8\u9501\u5b9a\u7684\u65e5\u5229\u7387\u53d6 min(\u672c\u503c, 1.0)\u3002\u8303\u56f4\u76f4\u63a5\u6309\u771f\u5b9e\u751f\u6548\u5e26\u7ed9\u5230 0~1\uff1a \u4ee3\u7801\u91cc\u53e6\u6709\u4e00\u9053 1.0% \u7684\u7edd\u5bf9\u5929\u82b1\u677f\uff08LoanRules.HARD_RATE_CAP_PERCENT\uff09\uff0c \u60f3\u7a81\u7834 1% \u5fc5\u987b\u5148\u6539\u90a3\u4e2a\u5e38\u91cf\u3002\u672c\u9879\u540c\u65f6\u662f P2P \u6302\u5355\u5229\u7387\u7684\u4e0a\u9650 \uff08LoanRules.effectiveRateCapPercent\uff0c\u4e24\u6761\u8def\u540c\u4e00\u4e2a\u53e3\u5f84\uff09\u3002 | Hard cap of the locked daily rate in percent: after every factor is multiplied in, the locked rate becomes min(this value, 1.0). The range is the real effective band 0~1, because an absolute 1.0% ceiling lives in code. This same effective cap also bounds P2P listing rates (LoanRules.effectiveRateCapPercent), so one key means one thing on both paths.")
                .defineInRange("loanRateCapPercent", 1.0, 0.0, 1.0);
        LOAN_LIMIT_ASSET_PERCENT = b.comment("\u8d44\u4ea7\u8ba1\u5165\u989d\u5ea6\u7684\u6bd4\u4f8b\uff08\u767e\u5206\u6bd4\uff09\uff1a\u73b0\u6709\u603b\u8d44\u4ea7 x \u8be5\u6bd4\u4f8b\u53e0\u52a0\u5230\u7b49\u7ea7\u57fa\u7840\u989d\u5ea6\u4e0a\uff0c\u4e0d\u8bbe\u73a9\u6cd5\u4e0a\u9650\u3002\u9ed8\u8ba4 20\u3002 | Percent of current total assets added to the tier base limit; there is no gameplay cap on this bonus. Default 20.")
                .defineInRange("loanLimitAssetPercent", 20.0, 0.0, 100.0);
        LOAN_COLLATERAL_LIMIT_FACTOR = b.comment("\u62b5\u62bc\u8d37\u989d\u5ea6\u7cfb\u6570\uff1a\u53ef\u501f\u989d\u5ea6\u518d\u52a0 \u6301\u4ed3\u5e02\u503c x \u8be5\u7cfb\u6570\u3002\u9ed8\u8ba4 0.50\u3002 | Collateral limit factor: limit also gains holdings value x this factor. Default 0.50.")
                .defineInRange("loanCollateralLimitFactor", 0.50, 0.0, 2.0);
        LOAN_EMERGENCY_CAP = b.comment("\u5e94\u6025\u8d37\u5355\u7b14\u4e0a\u9650\uff08\u77ff\u5e01\uff09\uff1a\u7834\u4ea7\u51b7\u5374\u671f\u5185\u4ecd\u53ef\u501f\u7684\u5c0f\u989d\u9ad8\u606f\u8d37\u6b3e\u4e0a\u9650\u3002\u9ed8\u8ba4 100\uff0c0 = \u5173\u95ed\u5e94\u6025\u8d37\u3002 \u4ee3\u7801\u53e6\u6709\u4e00\u9053 100 \u77ff\u5e01\u7684\u786c\u4e0a\u9650\uff08LoanRules.emergencyCap \u53d6\u4e24\u8005\u66f4\u4e25\uff09\uff0c \u6240\u4ee5\u586b 100 \u4ee5\u4e0a\u4e0e\u586b 100 \u6548\u679c\u76f8\u540c\u3002\u6b64\u5916\u8fd8\u8981\u6c42\u73a9\u5bb6\u603b\u8d44\u4ea7\u4e0d\u8d85\u8fc7 EMERGENCY_ASSET_CAP\u3002 | Emergency loan cap in coins, available even inside the bankruptcy cooldown. Default 100, 0 disables it. A second hard ceiling of 100 coins lives in code (the stricter of the two wins), so values above 100 behave like 100. Total assets must also stay under the emergency asset gate.")
                .defineInRange("loanEmergencyCap", 100L, 0L, 100000L);
        LOAN_EMERGENCY_RATE_FACTOR = b.comment("\u5e94\u6025\u8d37\u5229\u7387\u7cfb\u6570\uff08\u76f8\u5bf9\u56fa\u5b9a\u5229\u7387\uff09\u3002\u9ed8\u8ba4 2.00\u3002 | Emergency rate factor relative to the fixed rate. Default 2.00.")
                .defineInRange("loanEmergencyRateFactor", 2.00, 1.0, 5.0);
        LOAN_MARGIN_RATE_FACTOR = b.comment("\u505a\u7a7a\u4fdd\u8bc1\u91d1\u8d37\u5229\u7387\u7cfb\u6570\uff08\u76f8\u5bf9\u56fa\u5b9a\u5229\u7387\uff09\u3002\u9ed8\u8ba4 1.50\u3002 | Short margin loan rate factor. Default 1.50.")
                .defineInRange("loanMarginRateFactor", 1.50, 1.0, 3.0);
        LOAN_COLLATERAL_RATE_FACTOR = b.comment("\u62b5\u62bc\u8d37\u5229\u7387\u7cfb\u6570\uff08\u76f8\u5bf9\u56fa\u5b9a\u5229\u7387\uff0c<1 \u5373\u6709\u62b5\u62bc\u66f4\u4fbf\u5b9c\uff09\u3002\u9ed8\u8ba4 0.85\u3002 | Collateral rate factor (<1 = cheaper because secured). Default 0.85.")
                .defineInRange("loanCollateralRateFactor", 0.85, 0.2, 1.0);
        LOAN_P2P_FEE_PERCENT = b.comment("P2P \u5e73\u53f0\u624b\u7eed\u8d39\uff08\u767e\u5206\u6bd4\uff0c\u6309\u672c\u91d1\u4e00\u6b21\u6027\u8ba1\u5165\u7cfb\u7edf\u51c6\u5907\u91d1\u6c60\uff09\u3002\u9ed8\u8ba4 2.0\u3002 | P2P platform fee (percent of principal, into the system reserve). Default 2.0.")
                .defineInRange("loanP2PFeePercent", 2.0, 0.0, 20.0);
        LOAN_OVERDUE_STRIKE_LIMIT = b.comment("\u5f3a\u5236\u6e05\u7b97\u9608\u503c\uff1a\u8fde\u7eed\u903e\u671f\u8fbe\u5230\u8be5\u671f\u6570\u5373\u542f\u52a8\u5f3a\u5236\u6e05\u7b97\uff08\u62b5\u62bc\u8d37\u5728\u7b2c\u4e00\u671f\u903e\u671f\u5373\u5f3a\u5e73\uff09\u3002\u9ed8\u8ba4 5\u3002 | Forced liquidation after this many consecutive overdue installments. Default 5.")
                .defineInRange("loanOverdueStrikeLimit", 5, 1, 30);
        LOAN_CLAWBACK_DAYS = b.comment("\u7834\u4ea7\u64a4\u9500\u7a97\u53e3\uff08\u4ea4\u6613\u65e5\uff09\uff1a\u7834\u4ea7\u524d\u8be5\u7a97\u53e3\u5185\u501f\u5230\u7684\u6b3e\u9879\u4e0d\u4e88\u514d\u9664\uff0c\u8f6c\u4e3a\u7834\u4ea7\u672a\u514d\u9664\u503a\u52a1\u4f18\u5148\u507f\u8fd8\u3002\u9ed8\u8ba4 2 = \u5835\u4f4f\u4e0a\u5348\u501f\u6b3e\u4e0b\u5348\u7834\u4ea7\u8d56\u8d26\u3002 | Clawback window: loans taken inside it before bankruptcy are not discharged. Default 2.")
                .defineInRange("loanClawbackDays", 2, 0, 30);
        LOAN_LIMIT_RECOVER_DAYS = b.comment("\u989d\u5ea6\u6062\u590d\u8282\u594f\uff08\u4ea4\u6613\u65e5\uff09\uff1a\u8fde\u7eed\u903e\u671f\u9020\u6210\u7684\u989d\u5ea6\u6298\u51cf\u6309\u8be5\u8282\u594f\u9010\u65e5\u81ea\u884c\u6062\u590d\u3002\u9ed8\u8ba4 5\u3002 | Overdue limit cut decays by one strike per this many trading days. Default 5.")
                .defineInRange("loanLimitRecoverDays", 5, 1, 30);
        LOAN_PREPAY_DISCOUNT_PERCENT = b.comment("\u63d0\u524d\u7ed3\u6e05\u7684\u5229\u606f\u6298\u6263\uff08\u767e\u5206\u6bd4\uff09\u3002\u9ed8\u8ba4 10\u3002 | Discount on accrued interest when prepaying. Default 10.")
                .defineInRange("loanPrepayDiscountPercent", 10.0, 0.0, 100.0);
        LOAN_PREPAY_FEE_PERCENT = b.comment("\u63d0\u524d\u7ed3\u6e05\u624b\u7eed\u8d39\uff08\u6309\u5269\u4f59\u672c\u91d1\u7684\u767e\u5206\u6bd4\uff09\u3002\u9ed8\u8ba4 1.0\u3002 | Prepayment fee (percent of remaining principal). Default 1.0.")
                .defineInRange("loanPrepayFeePercent", 1.0, 0.0, 20.0);
        LOAN_SCORE_PER_ONTIME = b.comment("\u4fe1\u7528\u8bc4\u5206\uff1a\u6309\u65f6\u7ed3\u6e05\u4e00\u7b14\u52a0\u5206\u3002\u9ed8\u8ba4 12\u3002 | Score points per on-time payoff. Default 12.")
                .defineInRange("loanScorePerOnTime", 12, 0, 100);
        LOAN_SCORE_PER_OVERDUE = b.comment("\u4fe1\u7528\u8bc4\u5206\uff1a\u4e00\u6b21\u903e\u671f\u6263\u5206\u3002\u9ed8\u8ba4 35\u3002 | Score points per overdue event. Default 35.")
                .defineInRange("loanScorePerOverdue", 35, 0, 100);
        LOAN_SCORE_PER_BANKRUPT = b.comment("\u4fe1\u7528\u8bc4\u5206\uff1a\u4e00\u6b21\u7834\u4ea7\u6263\u5206\u3002\u9ed8\u8ba4 150\u3002 | Score points per bankruptcy. Default 150.")
                .defineInRange("loanScorePerBankrupt", 150, 0, 300);
        LOAN_SCORE_ASSET_STEP = b.comment("\u4fe1\u7528\u8bc4\u5206\uff1a\u6bcf\u591a\u5c11\u77ff\u5e01\u603b\u8d44\u4ea7\u52a0 1 \u5206\uff08\u5c01\u9876 +60 \u5206\uff09\u3002\u9ed8\u8ba4 2000\u3002 | Assets per +1 score point (capped at +60). Default 2000.")
                .defineInRange("loanScoreAssetStep", 2000L, 1L, 1000000L);
        LOAN_SCORE_PER_TIER = b.comment("\u4fe1\u7528\u8bc4\u5206\uff1a\u6bcf\u7ea7\u52a0\u5206\u3002\u9ed8\u8ba4 15\u3002 | Score points per player tier. Default 15.")
                .defineInRange("loanScorePerTier", 15, 0, 50);
        b.pop();

        // ============================ category 17: bankruptcy ============================
        b.push("bankruptcy");
        b.comment("\u7834\u4ea7\u53c2\u6570\u3002\u7834\u4ea7\u6e05\u7b97\u4f1a\u628a\u77ff\u5e01\u3001\u6301\u4ed3\u3001\u505a\u7a7a\u4ed3\u4f4d\u3001\u503a\u52a1\u5168\u90e8\u6e05\u96f6\uff1b\u51b7\u5374\u671f\u5185\u7981\u6b62\u5f00\u65b0\u505a\u7a7a\uff0c\u51b7\u5374\u7ed3\u675f\u540e\u5f00\u4ed3\u53ea\u518d\u53d7\u94b1\u5305\u4fdd\u8bc1\u91d1\u4e0e\u80a1\u7968\u505a\u7a7a\u6c60\u5269\u4f59\u91cf\u9650\u5236\uff08\u539f\u6309\u4fe1\u7528\u989d\u5ea6\u8ba1\u7b97\u7684\u505a\u7a7a\u989d\u5ea6\u5df2\u5220\u9664\uff09\u3002 | Bankruptcy. Liquidation zeroes coins, positions, short positions and debts;  new shorts are blocked during the cooldown; afterwards openings are limited by wallet margin and the stock's remaining short pool.")
                ;
        BANKRUPTCY_SHORT_COOLDOWN = b.comment("\u7834\u4ea7\u540e\u505a\u7a7a\u51b7\u5374\u5929\u6570\uff1a\u4ece\u7834\u4ea7\u65f6\u523b\u6240\u5728\u4ea4\u6613\u65e5\u7684**\u4e0b\u4e00\u4e2a\u4ea4\u6613\u65e5**\u8d77\uff0c \u6301\u7eed\u8fd9\u4e48\u591a**\u5b8c\u6574\u4ea4\u6613\u65e5**\uff0c\u671f\u95f4\u7981\u6b62\u5f00\u65b0\u505a\u7a7a\uff1b\u51b7\u5374\u7ed3\u675f\u540e\u5f00\u4ed3\u53ea\u518d\u53d7\u94b1\u5305\u4fdd\u8bc1\u91d1\u4e0e\u80a1\u7968\u505a\u7a7a\u6c60\u5269\u4f59\u91cf\u9650\u5236\uff0c\u4e0d\u518d\u6309\u4fe1\u7528\u989d\u5ea6\u5355\u72ec\u9650\u989d\u3002\u9ed8\u8ba4 3\u3002\u8303\u56f4 0~30\u3002 | Post-bankruptcy short cooldown: starting from the trading day AFTER the bankruptcy day, this many full  trading days block new short entries; afterwards openings are limited by wallet margin and the stock's remaining short pool. Default 3. Range 0~30.")
                .defineInRange("bankruptcyShortCooldown", 3, 0, 30);

        b.pop();


        // ============================ category 7: trading parameters ============================
        b.push("trade");
        b.comment("\u4ea4\u6613\u53c2\u6570\u3002\u624b\u7eed\u8d39\u6309\u6210\u4ea4\u989d\u767e\u5206\u6bd4\u6536\u53d6\uff1b\u6da8\u8dcc\u505c\u9650\u5236\u5355\u4e2a marketTick \u7684\u6700\u5927\u6da8\u8dcc\u5e45\uff1b \u521d\u59cb\u77ff\u5e01\u53ea\u5728\u73a9\u5bb6\u7b2c\u4e00\u6b21\u8fdb\u5165\u670d\u52a1\u5668\u65f6\u53d1\u653e\u4e00\u6b21\uff0c\u4e14\u4e0d\u8bb0\u5165\u77ff\u5e01\u5cf0\u503c\u3002 | Trading. Fee is a percent of turnover; price limits cap one marketTick's move;  starting coins are granted once on a player's first join and never count toward the peak.")
                ;
        TRADE_FEE_PERCENT = b.comment("\u4e70\u5356\u624b\u7eed\u8d39\uff08\u6210\u4ea4\u989d\u7684\u767e\u5206\u6bd4\uff09\u3002\u9ed8\u8ba4 0.5\u3002\u8303\u56f4 0~10\u3002 \u4e8b\u4ef6\uff08\u5206\u7c7b\u4e5d\uff09\u4f1a\u5728\u6210\u4ea4\u5f53\u65f6\u628a\u624b\u7eed\u8d39\u7387\u4e0a\u4e0b\u6d6e\u52a8\uff0c\u6d6e\u52a8\u7ed3\u679c\u540c\u6837\u94b3\u8fdb 0~10\uff0c \u8d85\u8fc7 10 \u7684\u914d\u7f6e\u503c\u4f1a\u88ab\u538b\u56de 10\u3002 | Trade fee in percent of turnover. Default 0.5. Range 0~10. Event modifiers (category 9) swing it at fill time and the result is clamped to 0~10 as well.")
                .defineInRange("tradeFeePercent", 0.5, 0.0, 10.0);
        PRICE_LIMIT_PERCENT = b.comment("\u5355 marketTick \u6da8\u8dcc\u505c\u5e45\u5ea6\uff08\u767e\u5206\u6bd4\uff09\u3002\u9ed8\u8ba4 10\u3002\u914d\u7f6e\u8303\u56f4 1~50\uff0c \u4f46\u4e8b\u4ef6\u805a\u5408\u5c42\u628a\u6bcf\u80a1\u751f\u6548\u503c\u94b3\u8fdb 5~25\uff08MarketModifiers.finish \u7684 0.05~0.25\uff09\uff0c \u56e0\u6b64\u5c0f\u4e8e 5 \u6309 5 \u751f\u6548\u3001\u5927\u4e8e 25 \u6309 25 \u751f\u6548\uff1b\u4e8b\u4ef6\u7684\u6b63\u8d1f delta \u4e5f\u5728\u8fd9\u9053\u94b3\u5236\u4e4b\u5185\u3002 | Price-limit width per marketTick, in percent. Configured range 1~50, but the effective per-stock value is clamped to 5~25 by the event aggregation layer, so below 5 acts as 5 and above 25 acts as 25; event deltas are inside the same clamp.")
                .defineInRange("priceLimitPercent", 10.0, 1.0, 50.0);
        STARTING_COINS = b.comment("\u65b0\u73a9\u5bb6\u521d\u59cb\u77ff\u5e01\uff08\u77ff\u5e01\uff09\u3002\u9ed8\u8ba4 100\u3002\u8303\u56f4 0~1000000\u3002\u8bbe\u4e3a 0 \u5219\u4e0d\u53d1\u653e\u521d\u59cb\u77ff\u5e01\u3002 | Starting coins for new players. Default 100. Range 0~1000000. Set to 0 to disable the grant.")
                .defineInRange("startingCoins", 100L, 0L, 1_000_000L);
        b.pop();

        // ============================ category 8: event frequency and durations (unit: seconds) ============================
        b.push("event");
        b.push("timing");
        b.comment("\u4e8b\u4ef6\u9891\u7387\u4e0e\u65f6\u957f\u3002**\u6240\u6709\u65f6\u957f\u5355\u4f4d\u90fd\u662f\u79d2**\uff08\u771f\u5b9e\u65f6\u95f4\uff09\uff0c \u5f15\u64ce\u5185\u90e8\u6309 marketTickInterval \u6362\u7b97\uff1amarketTick = ceil(\u79d2 \u00d7 20 / marketTickInterval)\uff0c\u6700\u5c0f 1\u3002 \u56e0\u6b64\u6539 marketTickInterval \u4e0d\u4f1a\u6539\u53d8\u4e8b\u4ef6\u7684\u771f\u5b9e\u6301\u7eed\u65f6\u957f\u3002 | Event frequency and durations. **All durations are in real seconds**; the engine converts internally:  marketTicks = ceil(seconds x 20 / marketTickInterval), minimum 1, so changing marketTickInterval does not change event length.")
                ;
        EVENT_TRIGGER_CHANCE = b.comment("\u6bcf\u4e2a marketTick \u5c1d\u8bd5\u89e6\u53d1\u65b0\u4e8b\u4ef6\u7684\u6982\u7387\uff08\u767e\u5206\u6bd4\uff09\u3002\u9ed8\u8ba4 3.0\u3002\u8303\u56f4 0~100\u3002 | Chance to roll a new event each marketTick (percent). Default 3.0. Range 0~100.")
                .defineInRange("eventTriggerChance", 3.0, 0.0, 100.0);
        LEGEND_COOLDOWN_DAYS = b.comment("\u4f20\u5947\u4e8b\u4ef6\u51b7\u5374\uff08\u4ea4\u6613\u65e5\uff09\uff1a\u89e6\u53d1\u540e\u8fd9\u4e48\u591a\u4ea4\u6613\u65e5\u5185\u4e0d\u518d\u51fa\u4f20\u5947\u4e8b\u4ef6\u3002\u9ed8\u8ba4 3\u3002\u8303\u56f4 0~30\u3002 | Legendary-event cooldown (trading days): no other legendary event within this many days after one fires. Default 3. Range 0~30.")
                .defineInRange("legendCooldownDays", 3, 0, 30);
        EVENT_DURATION_COMMON_MIN = b.comment("\u666e\u901a\u4e8b\u4ef6\u6700\u77ed\u6301\u7eed\uff08\u79d2\uff09\u3002\u9ed8\u8ba4 60\u3002\u8303\u56f4 0~86400\u3002 | Common event minimum duration (seconds). Default 20. Range 0~86400.")
                .defineInRange("eventDurationCommonMinSeconds", 20, 0, 86400);
        EVENT_DURATION_COMMON_MAX = b.comment("\u666e\u901a\u4e8b\u4ef6\u6700\u957f\u6301\u7eed\uff08\u79d2\uff09\u3002\u9ed8\u8ba4 60\u3002\u8303\u56f4 0~86400\u3002 | Common event maximum duration (seconds). Default 60. Range 0~86400.")
                .defineInRange("eventDurationCommonMaxSeconds", 60, 0, 86400);
        EVENT_DURATION_RARE_MIN = b.comment("\u7f55\u89c1\u4e8b\u4ef6\u6700\u77ed\u6301\u7eed\uff08\u79d2\uff09\u3002\u9ed8\u8ba4 45\u3002\u8303\u56f4 0~86400\u3002 | Rare event minimum duration (seconds). Default 45. Range 0~86400.")
                .defineInRange("eventDurationRareMinSeconds", 45, 0, 86400);
        EVENT_DURATION_RARE_MAX = b.comment("\u7f55\u89c1\u4e8b\u4ef6\u6700\u957f\u6301\u7eed\uff08\u79d2\uff09\u3002\u9ed8\u8ba4 120\u3002\u8303\u56f4 0~86400\u3002 | Rare event maximum duration (seconds). Default 120. Range 0~86400.")
                .defineInRange("eventDurationRareMaxSeconds", 120, 0, 86400);
        EVENT_DURATION_EPIC_MIN = b.comment("\u53f2\u8bd7\u4e8b\u4ef6\u6700\u77ed\u6301\u7eed\uff08\u79d2\uff09\u3002\u9ed8\u8ba4 90\u3002\u8303\u56f4 0~86400\u3002 | Epic event minimum duration (seconds). Default 90. Range 0~86400.")
                .defineInRange("eventDurationEpicMinSeconds", 90, 0, 86400);
        EVENT_DURATION_EPIC_MAX = b.comment("\u53f2\u8bd7\u4e8b\u4ef6\u6700\u957f\u6301\u7eed\uff08\u79d2\uff09\u3002\u9ed8\u8ba4 180\u3002\u8303\u56f4 0~86400\u3002 | Epic event maximum duration (seconds). Default 180. Range 0~86400.")
                .defineInRange("eventDurationEpicMaxSeconds", 180, 0, 86400);
        EVENT_DURATION_LEGEND_MIN = b.comment("\u4f20\u5947\u4e8b\u4ef6\u6700\u77ed\u6301\u7eed\uff08\u79d2\uff09\u3002\u9ed8\u8ba4 120\u3002\u8303\u56f4 0~86400\u3002 | Legendary event minimum duration (seconds). Default 120. Range 0~86400.")
                .defineInRange("eventDurationLegendMinSeconds", 120, 0, 86400);
        EVENT_DURATION_LEGEND_MAX = b.comment("\u4f20\u5947\u4e8b\u4ef6\u6700\u957f\u6301\u7eed\uff08\u79d2\uff09\u3002\u9ed8\u8ba4 240\u3002\u8303\u56f4 0~86400\u3002 | Legendary event maximum duration (seconds). Default 240. Range 0~86400.")
                .defineInRange("eventDurationLegendMaxSeconds", 240, 0, 86400);
        EVENT_HALT_MAX_SECONDS = b.comment("\u5355\u6b21\u505c\u724c\u6700\u957f\u65f6\u957f\uff08\u79d2\uff09\u3002\u9ed8\u8ba4 180\u3002\u8303\u56f4 1~30000\u3002 | Maximum length of one trading halt (seconds). Default 180. Range 1~30000.")
                .defineInRange("eventHaltMaxSeconds", 180, 1, 30000);
        EVENT_PRICE_LIMIT_MAX_SECONDS = b.comment("\u4e8b\u4ef6\u6da8\u8dcc\u505c\u6548\u679c\u6700\u957f\u6301\u7eed\uff08\u79d2\uff09\u3002\u9ed8\u8ba4 60\u3002\u8303\u56f4 1~30000\u3002 | Maximum length of one event price-limit effect (seconds). Default 60. Range 1~30000.")
                .defineInRange("eventPriceLimitMaxSeconds", 60, 1, 30000);
        b.pop();
        b.pop();

        // ============================ category 9: event effect value ranges ============================
        b.push("event");
        b.push("effect");
        b.comment("\u4e8b\u4ef6\u6548\u679c\u53c2\u6570\u5141\u8bb8\u968f\u673a\u53d6\u503c\u7684\u533a\u95f4\u3002\u89e6\u53d1\u65f6\u62bd\u5230\u7684\u6548\u679c\u53c2\u6570\u4f1a\u88ab\u94b3\u8fdb\u8fd9\u91cc\u8bbe\u5b9a\u7684\u533a\u95f4\uff1b \u6210\u5bf9\u53c2\u6570\uff08min/max\uff09\u82e5\u586b\u53cd\u4e86\uff0c\u4f1a\u81ea\u52a8\u53d6 min=\u8f83\u5c0f\u503c\u3001max=\u8f83\u5927\u503c\uff0c\u53ea\u8b66\u544a\u4e00\u6b21\u3001\u4e0d\u4ea4\u6362\u3001\u4e0d\u6539\u5199\u672c\u6587\u4ef6\u3002 \u5df2\u7ecf\u89e6\u53d1\u7684\u4e8b\u4ef6\u9501\u5b9a**\u89e6\u53d1\u5f53\u65f6**\u7684\u65f6\u957f\u4e0e\u6548\u679c\uff0c\u6539\u914d\u7f6e\u53ea\u5f71\u54cd\u4e4b\u540e\u65b0\u89e6\u53d1\u7684\u4e8b\u4ef6\u3002 | Ranges that event effect parameters may randomly draw from; rolled values are clamped into them.  If a min/max pair is written backwards, min takes the smaller and max the larger value with one warning - nothing is swapped or rewritten in this file.  Already-triggered events keep the duration and effects from the moment they fired; edits only affect future events.")
                ;
        EVENT_PRICE_MULT_MIN = b.comment("\u4e8b\u4ef6\u4ef7\u683c\u500d\u7387\u4e0b\u9650\uff08\u6bcf marketTick \u4e58\u5230\u4ef7\u683c\u4e0a\uff09\u3002\u9ed8\u8ba4 0.5\u3002\u8303\u56f4 0.0~5.0\u3002 | Event price multiplier minimum (per marketTick). Default 0.5. Range 0.0~5.0.")
                .defineInRange("eventPriceMultiplierMin", 0.5, 0.0, 5.0);
        EVENT_PRICE_MULT_MAX = b.comment("\u4e8b\u4ef6\u4ef7\u683c\u500d\u7387\u4e0a\u9650\u3002\u9ed8\u8ba4 1.5\u3002\u8303\u56f4 0.0~5.0\u3002 | Event price multiplier maximum. Default 1.5. Range 0.0~5.0.")
                .defineInRange("eventPriceMultiplierMax", 1.5, 0.0, 5.0);
        EVENT_VOLATILITY_MIN = b.comment("\u4e8b\u4ef6\u6ce2\u52a8\u7387\u500d\u7387\u4e0b\u9650\u3002\u9ed8\u8ba4 0.2\u3002\u8303\u56f4 0.0~10.0\u3002 | Event volatility multiplier minimum. Default 0.2. Range 0.0~10.0.")
                .defineInRange("eventVolatilityMin", 0.2, 0.0, 10.0);
        EVENT_VOLATILITY_MAX = b.comment("\u4e8b\u4ef6\u6ce2\u52a8\u7387\u500d\u7387\u4e0a\u9650\u3002\u9ed8\u8ba4 3.0\u3002\u8303\u56f4 0.0~10.0\u3002 | Event volatility multiplier maximum. Default 3.0. Range 0.0~10.0.")
                .defineInRange("eventVolatilityMax", 3.0, 0.0, 10.0);
        EVENT_MEAN_REVERSION_MIN = b.comment("\u4e8b\u4ef6\u56de\u5f52\u5f3a\u5ea6\u4e0b\u9650\u3002\u9ed8\u8ba4 0.0\u3002\u8303\u56f4 0.0~1.0\u3002 | Event mean-reversion strength minimum. Default 0.0. Range 0.0~1.0.")
                .defineInRange("eventMeanReversionMin", 0.0, 0.0, 1.0);
        EVENT_MEAN_REVERSION_MAX = b.comment("\u4e8b\u4ef6\u56de\u5f52\u5f3a\u5ea6\u4e0a\u9650\u3002\u9ed8\u8ba4 0.15\u3002\u8303\u56f4 0.0~1.0\u3002 | Event mean-reversion strength maximum. Default 0.15. Range 0.0~1.0.")
                .defineInRange("eventMeanReversionMax", 0.15, 0.0, 1.0);
        EVENT_FEE_MIN = b.comment("\u4e8b\u4ef6\u624b\u7eed\u8d39\u4fee\u6b63\u4e0b\u9650\uff08\u6210\u4ea4\u6bd4\u4f8b\uff0c\u53ef\u4e3a 0 \u8868\u793a\u514d\u624b\u7eed\u8d39\uff09\u3002\u9ed8\u8ba4 0.0\u3002\u8303\u56f4 0.0~1.0\u3002 | Event fee-adjustment minimum (share of turnover; 0 means fee-free). Default 0.0. Range 0.0~1.0.")
                .defineInRange("eventFeeMin", 0.0, 0.0, 1.0);
        EVENT_FEE_MAX = b.comment("\u4e8b\u4ef6\u624b\u7eed\u8d39\u4fee\u6b63\u4e0a\u9650\u3002\u9ed8\u8ba4 0.10\u3002\u8303\u56f4 0.0~1.0\u3002 | Event fee-adjustment maximum. Default 0.10. Range 0.0~1.0.")
                .defineInRange("eventFeeMax", 0.10, 0.0, 1.0);
        EVENT_TRADE_IMPACT_MIN = b.comment("\u4e8b\u4ef6\u4ea4\u6613\u51b2\u51fb\u500d\u7387\u4e0b\u9650\u3002\u9ed8\u8ba4 0.5\u3002\u8303\u56f4 0.0~10.0\u3002 | Event trade-impact multiplier minimum. Default 0.5. Range 0.0~10.0.")
                .defineInRange("eventTradeImpactMin", 0.5, 0.0, 10.0);
        EVENT_TRADE_IMPACT_MAX = b.comment("\u4e8b\u4ef6\u4ea4\u6613\u51b2\u51fb\u500d\u7387\u4e0a\u9650\u3002\u9ed8\u8ba4 3.0\u3002\u8303\u56f4 0.0~10.0\u3002 | Event trade-impact multiplier maximum. Default 3.0. Range 0.0~10.0.")
                .defineInRange("eventTradeImpactMax", 3.0, 0.0, 10.0);
        EVENT_PRICE_LIMIT_MIN = b.comment("\u4e8b\u4ef6\u6da8\u8dcc\u505c\u5e45\u5ea6\u8986\u76d6\u4e0b\u9650\uff08\u767e\u5206\u6bd4\uff09\u3002\u9ed8\u8ba4 5.0\u3002\u8303\u56f4 0.1~100.0\u3002 | Event price-limit override minimum (percent). Default 5.0. Range 0.1~100.0.")
                .defineInRange("eventPriceLimitMin", 5.0, 0.1, 100.0);
        EVENT_PRICE_LIMIT_MAX = b.comment("\u4e8b\u4ef6\u6da8\u8dcc\u505c\u5e45\u5ea6\u8986\u76d6\u4e0a\u9650\uff08\u767e\u5206\u6bd4\uff09\u3002\u9ed8\u8ba4 25.0\u3002\u8303\u56f4 0.1~100.0\u3002 | Event price-limit override maximum (percent). Default 25.0. Range 0.1~100.0.")
                .defineInRange("eventPriceLimitMax", 25.0, 0.1, 100.0);
        b.pop();
        b.pop();

        // ============================ category 18: event system extensions ============================
        b.push("event");
        b.push("system");
        b.comment("\u4e8b\u4ef6\u7cfb\u7edf\u6269\u5c55\u5f00\u5173\uff1a\u7ec4\u5408\u3001\u8fde\u9501\u3001\u51b2\u7a81\u62b5\u6d88\u3001\u4f20\u5947\u9884\u544a\u3001\u5047\u6d88\u606f\u3001\u73a9\u5bb6\u884c\u4e3a\u89e6\u53d1\u3001\u52a8\u753b\u3001\u9891\u7387\u52a8\u6001\u8c03\u6574\u3001\u53e0\u52a0\u4e0a\u9650\u4e0e\u65b0\u6548\u679c\u7c7b\u578b\u3002 | Event system extensions: combinations, chains, conflict cancellation, legendary previews, fake news, player-action triggers, card animation, dynamic frequency, stacking caps and new effect types.",
                "\u5df2\u751f\u6548\u4e8b\u4ef6\u7684\u65f6\u957f\u4e0e\u6548\u679c\u9501\u5b9a\u89e6\u53d1\u5f53\u65f6\u7684\u53c2\u6570\uff0c\u70ed\u91cd\u8f7d\u53ea\u5f71\u54cd\u4e4b\u540e\u7684\u65b0\u4e8b\u4ef6\u3002 | Triggered events keep the parameters from the moment they fired; hot reload only affects future events.")
                ;
        EVENT_COMBINATION_ENABLED = b.comment("\u662f\u5426\u542f\u7528\u4e8b\u4ef6\u7ec4\u5408 | Enable event combinations").define("eventCombinationEnabled", true);
        EVENT_CHAIN_ENABLED = b.comment("\u662f\u5426\u542f\u7528\u4e8b\u4ef6\u8fde\u9501 | Enable event chains").define("eventChainEnabled", true);
        EVENT_CONFLICT_ENABLED = b.comment("\u662f\u5426\u542f\u7528\u4e8b\u4ef6\u51b2\u7a81\u62b5\u6d88 | Enable event conflict cancellation").define("eventConflictEnabled", true);
        EVENT_PREVIEW_ENABLED = b.comment("\u662f\u5426\u542f\u7528\u4f20\u5947\u4e8b\u4ef6\u9884\u544a | Enable legendary event preview").define("eventPreviewEnabled", true);
        EVENT_PREVIEW_ACCURACY = b.comment("\u9884\u544a\u51c6\u786e\u7387 | Preview accuracy").defineInRange("eventPreviewAccuracy", 0.80, 0.0, 1.0);
        EVENT_FAKE_NEWS_ENABLED = b.comment("\u662f\u5426\u542f\u7528\u5047\u6d88\u606f\u4e8b\u4ef6 | Enable fake news events").define("eventFakeNewsEnabled", true);
        EVENT_PLAYER_TRIGGER_ENABLED = b.comment("\u662f\u5426\u542f\u7528\u73a9\u5bb6\u884c\u4e3a\u89e6\u53d1\u4e8b\u4ef6 | Enable player-action triggered events").define("eventPlayerTriggerEnabled", true);
        EVENT_PLAYER_TRIGGER_VOLUME = b.comment("\u73a9\u5bb6\u884c\u4e3a\u89e6\u53d1\u9608\u503c\u80a1\u6570 | Player action trigger volume").defineInRange("eventPlayerTriggerVolume", 1000, 100, 100000);
        EVENT_CARD_ANIMATION_ENABLED = b.comment("\u662f\u5426\u542f\u7528\u4e8b\u4ef6\u5361\u52a8\u753b | Enable event card animations").define("eventCardAnimationEnabled", true);
        EVENT_FREQUENCY_DYNAMIC_ENABLED = b.comment("\u662f\u5426\u542f\u7528\u4e8b\u4ef6\u9891\u7387\u52a8\u6001\u8c03\u6574 | Enable dynamic event frequency").define("eventFrequencyDynamicEnabled", true);
        EVENT_FREQUENCY_HIGH_THRESHOLD = b.comment("\u9ad8\u9891\u68c0\u6d4b\u7a97\u53e3\u5185\u4e8b\u4ef6\u6570\u4e0a\u9650 | High frequency threshold").defineInRange("eventFrequencyHighThreshold", 5, 1, 20);
        EVENT_FREQUENCY_LOW_THRESHOLD = b.comment("\u4f4e\u9891\u68c0\u6d4b\u7a97\u53e3\u5185\u4e8b\u4ef6\u6570\u4e0b\u9650 | Low frequency threshold").defineInRange("eventFrequencyLowThreshold", 0, 0, 5);
        EVENT_EFFECT_MULTIPLIER_CAP_MIN = b.comment("\u591a\u4e8b\u4ef6\u6548\u679c\u4e58\u79ef\u4e0b\u9650 | Multi-event multiplier cap minimum").defineInRange("eventEffectMultiplierCapMin", 0.5, 0.01, 1.0);
        EVENT_EFFECT_MULTIPLIER_CAP_MAX = b.comment("\u591a\u4e8b\u4ef6\u6548\u679c\u4e58\u79ef\u4e0a\u9650 | Multi-event multiplier cap maximum").defineInRange("eventEffectMultiplierCapMax", 2.0, 1.0, 100.0);
        EVENT_LEGEND_COOLDOWN_HINT_ENABLED = b.comment("\u662f\u5426\u5728\u65e5\u5fd7\u6807\u7b7e\u9875\u5199\u5165\u4f20\u5947\u51b7\u5374\u8bb0\u5f55\uff08\u4e8b\u4ef6\u680f\u4e0d\u518d\u6e32\u67d3\u51b7\u5374\u5361\uff09 | Write legendary cooldown records to the log tab (no event-bar card)").define("eventLegendCooldownHintEnabled", true);
        EXCHANGE_ROW_SELECTED_COLOR = b.comment("\u4ea4\u6613\u6240\u9009\u4e2d\u884c\u5e95\u8272 RGB\uff08\u5ba2\u6237\u7aef\u5f3a\u5236\u52a0\u4e0d\u900f\u660e alpha\uff09 | Exchange selected-row background RGB (alpha forced opaque)").defineInRange("exchangeRowSelectedColor", 0x4A5A7A, 0, 0xFFFFFF);
        EVENT_FORCE_BUY_MAX_SHARES = b.comment("\u5f3a\u5236\u4e70\u5165\u6700\u5927\u80a1\u6570 | Force buy max shares").defineInRange("eventForceBuyMaxShares", 1000, 10, 100000);
        EVENT_FORCE_SELL_MAX_SHARES = b.comment("\u5f3a\u5236\u5356\u51fa\u6700\u5927\u80a1\u6570 | Force sell max shares").defineInRange("eventForceSellMaxShares", 1000, 10, 100000);
        EVENT_DIVIDEND_MAX_PER_SHARE = b.comment("\u6bcf\u80a1\u5206\u7ea2\u4e0a\u9650\u77ff\u5e01 | Dividend max per share").defineInRange("eventDividendMaxPerShare", 10.0, 0.1, 1000.0);
        EVENT_STOCK_SPLIT_ENABLED = b.comment("\u662f\u5426\u542f\u7528\u62c6\u80a1\u4e8b\u4ef6 | Enable stock split events").define("eventStockSplitEnabled", true);
        CUSTOM_EVENTS = b.comment("\u81ea\u5b9a\u4e49\u4e8b\u4ef6\u5217\u8868\uff0c\u683c\u5f0f\uff1a\u4e8b\u4ef6\u540d|\u7a00\u6709\u5ea6|\u80a1\u7968\u4ee3\u7801\u5217\u8868|\u6548\u679c\u6a21\u5757\u5217\u8868|\u6301\u7eed\u79d2\u6570|\u63cf\u8ff0 | Custom events, format: name|rarity|stocks|effects|duration|description",
                        "\u793a\u4f8b\uff1a\"MY_EVENT|rare|COAL,IRON|price:1.2,volatility:1.5|60|\u67d0\u77ff\u533a\u53d1\u751f\u4e8b\u6545\"\u3002\u4e0a\u9650 50 \u6761\uff1b\u975e\u6cd5\u6761\u76ee\u8df3\u8fc7\u5e76\u8b66\u544a\u3002 | Example line above; max 50 entries; invalid lines are skipped with a warning.")
                .defineList("customEvents", Collections.emptyList(),
                        o -> o instanceof String s && !s.isBlank());
        b.pop();
        b.pop();

        // ============================ category 19: particle effects ============================
        b.push("particle");
        b.comment("\u7c92\u5b50\u6548\u679c\uff1a\u670d\u52a1\u7aef\u89e6\u53d1\u3001\u7ecf SpawnParticlePacket\uff08\u534f\u8bae 10 \u8d77\uff09\u53d1\u7ed9\u5ba2\u6237\u7aef\u64ad\u653e\uff1b\u5bf9\u4ea4\u6613\u903b\u8f91\u4e0e\u4ef7\u683c\u5f15\u64ce\u96f6\u5f71\u54cd\u3002 | Particle effects: triggered on the server, played on the client via SpawnParticlePacket (protocol 10+); they never touch trade logic or the price engine.",
                "\u7c92\u5b50\u7c7b\u578b\u586b\u539f\u7248\u7c92\u5b50 ID\uff08\u5982 minecraft:happy_villager\uff09\uff1b\u89e3\u6790\u5931\u8d25\u6216\u6ce8\u518c\u8868\u4e0d\u5b58\u5728\u65f6\u964d\u7ea7\u4e3a minecraft:smoke \u5e76\u6253\u5370\u8b66\u544a\u3002 | Particle types are vanilla particle IDs; unparseable or unregistered values degrade to minecraft:smoke with a warning.")
                ;
        PARTICLE_EFFECT_ENABLED = b.comment("\u662f\u5426\u542f\u7528\u7c92\u5b50\u6548\u679c | Enable particle effects").define("particleEffectEnabled", true);
        PARTICLE_EFFECT_ON_TRADE = b.comment("\u4ea4\u6613\u6210\u529f\u65f6\u64ad\u653e\u7c92\u5b50 | Play particles on successful trade").define("particleEffectOnTrade", true);
        PARTICLE_EFFECT_ON_EVENT = b.comment("\u4e8b\u4ef6\u89e6\u53d1\u65f6\u64ad\u653e\u7c92\u5b50 | Play particles on event trigger").define("particleEffectOnEvent", true);
        PARTICLE_EFFECT_ON_SHORT_OPEN = b.comment("\u505a\u7a7a\u5f00\u4ed3\u65f6\u64ad\u653e\u7c92\u5b50 | Play particles on short open").define("particleEffectOnShortOpen", true);
        PARTICLE_EFFECT_ON_SHORT_CLOSE = b.comment("\u505a\u7a7a\u5e73\u4ed3\u65f6\u64ad\u653e\u7c92\u5b50 | Play particles on short close").define("particleEffectOnShortClose", true);
        PARTICLE_EFFECT_ON_BANKRUPTCY = b.comment("\u7834\u4ea7\u6e05\u7b97\u65f6\u64ad\u653e\u7c92\u5b50 | Play particles on bankruptcy").define("particleEffectOnBankruptcy", true);
        PARTICLE_EFFECT_TYPE_BUY = b.comment("\u4e70\u5165\u7c92\u5b50\u7c7b\u578b\uff0c\u586b\u5199\u539f\u7248\u7c92\u5b50 ID | Buy particle type, vanilla particle ID").define("particleEffectTypeBuy", "minecraft:happy_villager");
        PARTICLE_EFFECT_TYPE_SELL = b.comment("\u5356\u51fa\u7c92\u5b50\u7c7b\u578b\uff0c\u586b\u5199\u539f\u7248\u7c92\u5b50 ID | Sell particle type, vanilla particle ID").define("particleEffectTypeSell", "minecraft:angry_villager");
        PARTICLE_EFFECT_TYPE_EVENT = b.comment("\u4e8b\u4ef6\u89e6\u53d1\u7c92\u5b50\u7c7b\u578b\uff0c\u586b\u5199\u539f\u7248\u7c92\u5b50 ID | Event particle type, vanilla particle ID").define("particleEffectTypeEvent", "minecraft:end_rod");
        PARTICLE_EFFECT_TYPE_SHORT_OPEN = b.comment("\u505a\u7a7a\u5f00\u4ed3\u7c92\u5b50\u7c7b\u578b | Short open particle type").define("particleEffectTypeShortOpen", "minecraft:smoke");
        PARTICLE_EFFECT_TYPE_SHORT_CLOSE = b.comment("\u505a\u7a7a\u5e73\u4ed3\u7c92\u5b50\u7c7b\u578b | Short close particle type").define("particleEffectTypeShortClose", "minecraft:cloud");
        PARTICLE_EFFECT_TYPE_BANKRUPTCY = b.comment("\u7834\u4ea7\u7c92\u5b50\u7c7b\u578b | Bankruptcy particle type").define("particleEffectTypeBankruptcy", "minecraft:explosion");
        PARTICLE_EFFECT_COUNT = b.comment("\u6bcf\u6b21\u64ad\u653e\u7684\u7c92\u5b50\u6570\u91cf | Particle count per play").defineInRange("particleEffectCount", 10, 1, 100);
        PARTICLE_EFFECT_SPREAD = b.comment("\u7c92\u5b50\u6269\u6563\u534a\u5f84 | Particle spread radius").defineInRange("particleEffectSpread", 0.5, 0.0, 5.0);
        PARTICLE_EFFECT_SPAWN_INTERVAL_TICKS = b.comment("\u7c92\u5b50\u751f\u6210\u95f4\u9694 tick \u6570 | Particle spawn interval in ticks").defineInRange("particleEffectSpawnIntervalTicks", 20, 1, 200);
        PARTICLE_EFFECT_PLAY_SOUND_ENABLED = b.comment("\u7c92\u5b50\u64ad\u653e\u65f6\u662f\u5426\u540c\u65f6\u64ad\u653e\u97f3\u6548 | Play sound along with particles").define("particleEffectPlaySoundEnabled", true);
        b.pop();

        // ============================ category 11: transaction tax (coin burn) ============================
        b.push("tax");
        TAX_DESTROY_PERCENT = b.comment("\u9500\u6bc1\u7a0e\u7387\uff08\u767e\u5206\u6bd4\uff09\uff1a\u6240\u6709\u4e70\u5356\u5728\u624b\u7eed\u8d39\u4e4b\u5916\u989d\u5916\u6536\u53d6\u7684\u77ff\u5e01\uff0c\u76f4\u63a5\u9500\u6bc1\u4e0d\u8fdb\u5165\u4efb\u4f55\u6c60\u5b50\u3002 \u603b\u8d39\u7387 = \u624b\u7eed\u8d39\u7387 + \u9500\u6bc1\u7a0e\u7387 - \u624b\u7eed\u8d39\u7387\u00d7\u9500\u6bc1\u7a0e\u7387\u3002\u9ed8\u8ba4\u624b\u7eed\u8d39 0.5% + \u9500\u6bc1\u7a0e 0% = 0.5%\u3002 \u505a\u7a7a\u5f00\u4ed3\u6536\u5f00\u4ed3\u7a0e\uff08\u542b\u9500\u6bc1\u7a0e\uff09\uff1b\u505a\u7a7a\u5e73\u4ed3\u4e0e\u5f3a\u5e73\u514d\u4e00\u5207\u7a0e\u4e0e\u624b\u7eed\u8d39\u3002 \u6302\u5355\u6210\u4ea4\u603b\u8d39\u7387 = \u4ea4\u6613\u7a0e + \u6302\u5355\u624b\u7eed\u8d39\uff08\u52a0\u6cd5\u53e0\u52a0\uff0c\u4e70\u5356\u53cc\u65b9\u5404\u627f\u62c5\u4e00\u534a\u6302\u5355\u624b\u7eed\u8d39\uff09\u3002 \u9650\u4ef7\u5355\u89e6\u53d1\u603b\u8d39\u7387 = \u4ea4\u6613\u7a0e + \u9650\u4ef7\u5355\u624b\u7eed\u8d39\uff08\u52a0\u6cd5\u53e0\u52a0\uff09\u3002 \u4e8b\u4ef6\u624b\u7eed\u8d39\u4fee\u6b63\u53ea\u5f71\u54cd\u624b\u7eed\u8d39\u90e8\u5206\uff0c\u4e0d\u5f71\u54cd\u9500\u6bc1\u7a0e\u90e8\u5206\u3002\u9ed8\u8ba4 0.0\u3002\u8303\u56f4 0~10\u3002 | Burn tax rate (percent): collected on every trade on top of the fee and destroyed outright.  Total rate = fee + burn - fee x burn; defaults 0.5% + 0% = 0.5%.  Short entries pay the entry tax (burn included); short closes and forced liquidations pay no tax or fee at all.  Order-book fills charge trade tax + order fee (added; each side bears half the order fee).  Limit-order fills charge trade tax + limit fee (added).  Event fee adjustments only touch the fee part, never the burn tax. Default 0.0. Range 0~10.")
                .defineInRange("taxDestroyPercent", 0.0, 0.0, 10.0);
        b.pop();

        // ============================ category 12: shorting ============================
        b.push("short");
        b.comment("\u505a\u7a7a\uff08\u501f\u80a1\u5356\u7a7a\uff09\u53c2\u6570\u3002\u5f00\u4ed3\u65f6\u5411\u7cfb\u7edf\u501f\u80a1\uff0c\u4fdd\u8bc1\u91d1 = \u501f\u80a1\u5e02\u503c \u00d7 shortMarginRatio\uff0c \u4e00\u6b21\u6027\u4ece\u77ff\u5e01\u4f59\u989d\u6263\u9664\u5e76\u9501\u5b9a\uff0c\u4e0d\u968f\u4ef7\u683c\u53d8\u5316\uff1b\u5e73\u4ed3\u514d\u624b\u7eed\u8d39\u4e0e\u5f00\u4ed3\u7a0e\u3002\u505a\u7a7a\u662f\u4e0e\u7cfb\u7edf\u7684\u5408\u7ea6\uff0c\u4e0d\u8fdb\u5165\u5e02\u573a\uff1a\u9ed8\u8ba4 shortAffectPrice=true\uff1a\u5f00\u4ed3\u6309\u5356\u65b9\u51b2\u51fb\u538b\u4f4e\u4ef7\u683c\uff0c\u5e73\u4ed3\u548c\u5f3a\u5e73\u6309\u4e70\u65b9\u51b2\u51fb\u62c9\u9ad8\u4ef7\u683c\uff1b\u8bbe\u4e3a false \u65f6\u5f00\u4ed3/\u5e73\u4ed3/\u5f3a\u5e73\u90fd\u4e0d\u5f71\u54cd\u7cfb\u7edf\u4ef7\u683c\u3002 \u5df2\u6709\u505a\u7a7a\u4ed3\u4f4d\u9501\u5b9a**\u5f00\u4ed3\u5f53\u65f6**\u7684\u6bd4\u4f8b\u3001\u5f3a\u5e73\u7ebf\u3001\u65e5\u606f\uff0c\u70ed\u91cd\u8f7d\u4e0d\u5f71\u54cd\u5b58\u91cf\u4ed3\u4f4d\u3002 | Shorting (borrowed shares). Entry borrows from the system; margin = borrowed value x shortMarginRatio,  deducted from the balance once and locked (it does not drift with price). Closing pays no fee or entry tax. Shorts affect the shared market by default: opening applies sell-side impact, closing and liquidation apply buy-side impact. Setting shortAffectPrice=false keeps shorting as a system contract that does not move the quote. Open positions keep the ratios, liquidation line and daily rate from the moment of opening; hot reload does not touch them.")
                ;
        SHORT_MARGIN_RATIO = b.comment("\u505a\u7a7a\u4fdd\u8bc1\u91d1\u6bd4\u4f8b\uff1a\u4fdd\u8bc1\u91d1 = \u501f\u80a1\u5e02\u503c \u00d7 \u8be5\u6bd4\u4f8b\u3002\u9ed8\u8ba4 0.50\uff08\u534a\u4fdd\u8bc1\u91d1\uff09\u3002\u8303\u56f4 0.10~5.0\u3002 1.0 \u8868\u793a\u8db3\u989d\u4fdd\u8bc1\u91d1\uff1b\u4f4e\u4e8e 1.0 \u662f\u6760\u6746\u505a\u7a7a\uff0c\u7206\u4ed3\u7ebf\u968f\u4e4b\u4e00\u8d77\u4e0b\u79fb\uff08\u89c1 shortLiquidationLine \u4e0e shortLiquidationThreshold2\uff0c \u4e24\u8005\u7684\u52a8\u6001\u4e0a\u9650\u5c31\u662f\u672c\u503c \u00f7 \u5f3a\u5e73\u7ebf\uff09\u3002 | Short margin ratio: margin = borrowed value x this ratio. Default 0.50. Range 0.10~5.0. 1.0 means fully collateralised; below 1.0 is leveraged short and the liquidation lines move down with it (the dynamic cap of the two liquidation thresholds is this value / the liquidation line).")
                .defineInRange("shortMarginRatio", 0.50, 0.10, 5.0);
        SHORT_LIQUIDATION_LINE = b.comment("\u505a\u7a7a\u5f3a\u5e73\u7ebf\uff08\u6761\u4ef6\u4e00\uff09\uff1a\u4ef7\u683c\u6da8\u5230\u5f00\u4ed3\u4ef7 \u00d7 \u8be5\u6bd4\u4f8b\u5373\u89e6\u53d1\u5f3a\u5e73\u3002\u9ed8\u8ba4 1.15\u3002\u8303\u56f4 1.05~3.0\u3002 \u4e0b\u9650\u523b\u610f\u8bbe\u4e3a 1.05 \u800c\u4e0d\u662f 1.0\uff1a\u586b 1.0 \u610f\u5473\u7740\u4ef7\u683c\u539f\u5730\u4e0d\u52a8\u4e5f\u7b97\u7206\u4ed3\uff0c \u5f00\u4ed3\u5f53\u573a\u5c31\u4f1a\u88ab\u5f3a\u5e73\uff0c\u6240\u4ee5 1.0~1.05 \u7684\u914d\u7f6e\u503c\u4f1a\u88ab\u94b3\u5230 1.05 \u5e76\u6253\u5370\u8b66\u544a\u3002 | Short liquidation line (condition 1): price reaching entry price x this ratio forces liquidation. Default 1.15. Range 1.05~3.0. The floor is 1.05, not 1.0: a flat price would otherwise count as a blow-up and liquidate on the spot, so anything below 1.05 is clamped up with a warning.")
                .defineInRange("shortLiquidationLine", 1.15, 1.05, 3.0);
        SHORT_DAILY_INTEREST = b.comment("\u505a\u7a7a\u65e5\u606f\uff08\u6309\u5f00\u4ed3\u65f6\u501f\u80a1\u5e02\u503c\u8ba1\u606f\uff0c\u6bcf\u4e2a\u4ea4\u6613\u65e5\u7ed3\u675f\u4ece\u4fdd\u8bc1\u91d1\u6263\u9664\uff09\u3002\u9ed8\u8ba4 0.001\uff0c\u5373 0.1%\u3002\u8303\u56f4 0.0~0.20\u3002 \u8ba1\u606f\u57fa\u6570\u5728\u5f00\u4ed3\u5f53\u65f6\u9501\u6b7b\uff08lockedMargin \u540c\u6e90\uff09\uff0c\u5e73\u4ed3\u4e0e\u5f3a\u5e73\u90fd\u4e0d\u518d\u91cd\u65b0\u8ba1\u606f\u3002 | Short daily interest (on the borrowed value frozen at entry, deducted from margin at each trading day end). Default 0.001 = 0.1%. Range 0.0~0.20. The base is frozen when the position opens; closes and liquidations do not re-base it.")
                .defineInRange("shortDailyInterest", 0.001, 0.0, 0.20);
        SHORT_SCARCITY_MULTIPLIER = b.comment("\u505a\u7a7a\u6c60\u5269\u4f59\u4f4e\u4e8e 20% \u65f6\u65b0\u5f00\u4ed3\u65e5\u606f\u500d\u6570\uff1b\u5df2\u6709\u4ed3\u4f4d\u4fdd\u6301\u5f00\u4ed3\u65f6\u5229\u7387\u3002\u9ed8\u8ba4 2.0\u3002 | New-position daily-rate multiplier when less than 20% of the short pool remains. Existing positions keep their entry rate. Default 2.0.")
                .defineInRange("shortScarcityMultiplier", 2.0, 1.0, 10.0);
        SHORT_LIQUIDATION_THRESHOLD2 = b.comment("\u5f3a\u5e73\u6761\u4ef6\u4e8c\u9608\u503c\uff1a\u4fdd\u8bc1\u91d1\uff08\u542b\u5f00\u4ed3\u7a0e\uff09\u4f4e\u4e8e\u5f53\u524d\u5e02\u503c \u00d7 \u8be5\u6bd4\u4f8b\u5373\u5f3a\u5e73\uff08\u4ef7\u683c\u77ac\u95f4\u8df3\u6da8\u65f6\u7684\u5197\u4f59\u4fdd\u9669\uff09\u3002\u9ed8\u8ba4 0.20\uff0c\u8303\u56f4 0.05~2.00\u3002 \u52a0\u8f7d\u65f6\u6709\u52a8\u6001\u4e0a\u9650\u7ea6\u675f\uff1a\u4e0d\u5f97\u8d85\u8fc7\uff08shortMarginRatio - \uff08shortLiquidationLine - 1\uff09\uff09 \u00f7 shortLiquidationLine\uff08\u9ed8\u8ba4 \uff080.50 - \uff080.15\uff09\uff09\u00f71.15\u22480.30\uff09\uff0c\u8d85\u8fc7\u4f1a\u88ab\u94b3\u5230\u52a8\u6001\u4e0a\u9650\u5e76\u6253\u5370\u8b66\u544a\u3002 \u4e24\u9053\u6761\u4ef6\u53d6\u5148\u5230\u8005\uff1a\u6761\u4ef6\u4e00\u6309\u5f00\u4ed3\u4ef7\u3001\u6761\u4ef6\u4e8c\u6309\u4fdd\u8bc1\u91d1\u4f59\u91cf\uff0c\u56e0\u6b64\u65e5\u606f\u5403\u6389\u4fdd\u8bc1\u91d1\u540e\u4e5f\u4f1a\u8d70\u6761\u4ef6\u4e8c\u3002 | Liquidation condition 2: margin (entry tax included) below current value x this ratio forces liquidation. Default 0.20. Range 0.05~2.00. At load it is capped by (shortMarginRatio - (shortLiquidationLine - 1)) / shortLiquidationLine (default about 0.30) so this check cannot fire before the price line on a clean position. Both conditions are checked and the first to trigger wins.")
                .defineInRange("shortLiquidationThreshold2", 0.20, 0.05, 2.00);
        SHORT_AFFECT_PRICE = b.comment("\u505a\u7a7a\u662f\u5426\u5f71\u54cd\u5e02\u573a\u4ef7\u683c\u3002\u9ed8\u8ba4 true\uff1a\u5f00\u4ed3\u6309\u5356\u65b9\u51b2\u51fb\u538b\u4f4e\u4ef7\u683c\uff0c\u5e73\u4ed3\u6309\u4e70\u65b9\u51b2\u51fb\u62c9\u9ad8\u4ef7\u683c\uff0c\u5927\u91cf\u505a\u7a7a\u4f1a\u660e\u663e\u6539\u53d8\u5168\u670d K \u7ebf\u3002\u8bbe\u4e3a false \u65f6\uff0c\u505a\u7a7a\u53ea\u662f\u4e0e\u7cfb\u7edf\u7684\u5408\u7ea6\uff0c\u5f00\u4ed3\u3001\u5e73\u4ed3\u548c\u5f3a\u5e73\u90fd\u4e0d\u6539\u53d8\u7cfb\u7edf\u4ef7\u683c\u3002 | Whether shorts move the market price. Default true: opening applies sell-side impact and closing or liquidation applies buy-side impact; heavy shorting visibly moves the shared quote and candlesticks. When false, shorting is a system contract and does not change the quote.")
                .define("shortAffectPrice", true);
        SHORT_CLOSE_IMPACT_MULTIPLIER = b.comment(
                "\u505a\u7a7a\u5e73\u4ed3\u4e0e\u5f3a\u5e73\u4e70\u56de\u7684\u51b2\u51fb\u8870\u51cf\u500d\u7387\uff1a\u5e73\u4ed3\u662f\u88ab\u52a8\u8fd8\u503a\uff0c" +
                "\u5176\u5bf9\u4ef7\u683c\u7684\u4e70\u65b9\u51b2\u51fb\u53ea\u6709\u666e\u901a\u4e70\u5165\u7684\u8be5\u6bd4\u4f8b\u3002" +
                "\u9ed8\u8ba4 0.2\uff08\u5373 20%\uff09\uff0c\u8303\u56f4 0.0~1.0\uff1b\u8bbe 1.0 \u6062\u590d\u65e7\u884c\u4e3a\u3002" +
                "\u7ed3\u7b97\u4ef7\u59cb\u7ec8\u6309\u70b9\u51fb\u5e73\u4ed3\u65f6\u7684\u73b0\u4ef7\u9501\u5b9a\uff0c\u4e0d\u53d7\u672c\u6b21\u51b2\u51fb\u5f71\u54cd\u3002" +
                " | Short-close and forced buy-back impact decay: a cover is a passive repayment," +
                " so its buy-side quote push is this fraction of a normal buy." +
                " Default 0.2 (20%). Range 0.0~1.0; 1.0 restores the old full-impact behavior." +
                " Settlement always prices at the quote shown when the close was clicked.")
                .defineInRange("shortCloseImpactMultiplier", 0.2, 0.0, 1.0);
        SHORT_RELEASE_MODE = b.comment("\u505a\u7a7a\u5e73\u4ed3\u4fdd\u8bc1\u91d1\u91ca\u653e\u6a21\u5f0f\u3002withPnl\uff08\u9ed8\u8ba4\uff09= \u6309\u6570\u91cf\u6bd4\u4f8b\u91ca\u653e\u4fdd\u8bc1\u91d1\u5e76\u7ed3\u7b97\u5e73\u4ed3\u76c8\u4e8f\uff08\u91ca\u653e\u91d1\u989d = \u603b\u4fdd\u8bc1\u91d1\u00d7\u5e73\u4ed3\u6570\u91cf\u00f7\u5e73\u4ed3\u524d\u603b\u501f\u80a1\u6570\u91cf + \u5e73\u4ed3\u76c8\u4e8f\uff0c\u5c0f\u4e8e 0 \u94b3\u4e3a 0\uff09\uff1b quantity = \u53ea\u6309\u6570\u91cf\u6bd4\u4f8b\u91ca\u653e\u3001\u5b8c\u5168\u4e0d\u7ed3\u7b97\u76c8\u4e8f\u3002 \u53ea\u5141\u8bb8\u8fd9\u4e24\u4e2a\u53d6\u503c\uff0c\u5199\u9519\u7684\u503c\u4f1a\u56de\u843d\u6210 withPnl \u5e76\u6253\u5370\u8b66\u544a\u3002 | Short close margin-release mode. withPnl (default) = release margin proportionally AND settle P&L (released = total margin x closed qty / borrowed qty before close + close P&L, floored at 0);  quantity = proportional release only, P&L ignored. Anything else falls back to withPnl with a warning.")
                .define("shortReleaseMode", "withPnl",
                        o -> o instanceof String s && (s.equals("quantity") || s.equals("withPnl")));
        SHORT_POOL_DEFAULT_SHARES = b.comment("\u505a\u7a7a\u6c60\u57fa\u51c6\u80a1\u6570\uff08\u6c60\u5b50\u6309\u7eaf\u80a1\u6570\u8ba1\u91cf\uff0c\u4e0d\u662f\u77ff\u5e01\u91d1\u989d\uff09\u3002 \u5355\u53ea\u80a1\u7968\u7684\u6c60\u5b50\u4e0a\u9650 = \u672c\u503c \u00f7 \u8be5\u80a1\u57fa\u51c6\u4ef7\uff08\u5411\u4e0b\u53d6\u6574\uff09\uff0c\u518d\u88ab\u4e0b\u9762\u7684\u7edd\u5bf9\u4e0a\u4e0b\u9650\u94b3\u5236\u3002\u4f8b\uff1a\u57fa\u51c6\u4ef7 10 \u2192 5000\uff1b\u57fa\u51c6\u4ef7 500 \u2192 100\uff1b\u57fa\u51c6\u4ef7 0.5 \u2192 \u94b3\u5230\u7edd\u5bf9\u4e0a\u9650\u3002\u9ed8\u8ba4 50000\u3002\u8303\u56f4 1~10000000\u3002 | Short pool base shares (measured purely in shares, not coin value): per-stock pool cap = this / stock base price (floored), then clamped by the absolute bounds below. e.g. base 10 -> 5000; base 500 -> 100; base 0.5 -> clamped to absolute max. Default 50000. Range 1~10000000.")
                .defineInRange("defaultBaseShares", 50000, 1, 10000000);
        SHORT_POOL_ABSOLUTE_MAX = b.comment("\u505a\u7a7a\u6c60\u7edd\u5bf9\u4e0a\u9650\uff08\u80a1\uff09\u3002\u65e0\u8bba\u6298\u7b97\u7ed3\u679c\u591a\u5927\uff0c\u5355\u53ea\u80a1\u7968\u53ef\u505a\u7a7a\u80a1\u6570\u4e0d\u8d85\u8fc7\u5b83\u3002\u9ed8\u8ba4 100000\u3002\u8303\u56f4 1~10000000\u3002 | Absolute short pool cap in shares per stock. Default 100000. Range 1~10000000.")
                .defineInRange("absoluteMaxShares", 100000, 1, 10000000);
        SHORT_POOL_ABSOLUTE_MIN = b.comment("\u505a\u7a7a\u6c60\u7edd\u5bf9\u4e0b\u9650\uff08\u80a1\uff09\u3002\u9ad8\u4ef7\u80a1\u7968\u6298\u7b97\u4e0d\u8db3\u4e0b\u9650\u65f6\u6309\u4e0b\u9650\u7ed9\u6c60\uff0c\u4fdd\u8bc1\u6c60\u5b50\u6c38\u8fdc\u6709\u4e8b\u53ef\u505a\u3002\u9ed8\u8ba4 100\u3002\u8303\u56f4 1~1000000\u3002 | Absolute short pool floor in shares: expensive stocks still get at least this many borrowable shares. Default 100. Range 1~1000000.")
                .defineInRange("absoluteMinShares", 100, 1, 1000000);
b.pop();

        // ============================ category 13: player orders ============================
        b.push("order");
        b.comment("\u73a9\u5bb6\u95f4\u6302\u5355\uff1a\u4e70\u5356\u53cc\u65b9\u5404\u6302\u9650\u4ef7\u5355\uff0c\u64ae\u5408\u5468\u671f\u5185\u4e70\u5355\u4ef7 \u2265 \u5356\u5355\u4ef7\u5373\u6210\u4ea4\uff0c\u6210\u4ea4\u4ef7\u53d6\u4e2d\u95f4\u4ef7\u3002 \u9501\u5b9a\u4e3a\u903b\u8f91\u6807\u8bb0\uff0c\u8d44\u6e90\u4ecd\u5728\u73a9\u5bb6\u8d26\u6237\u91cc\uff0c\u7531\u6302\u5355\u6570\u636e\u63a8\u5bfc\uff1b\u91cd\u542f\u540e\u4ece\u5b58\u6863\u81ea\u52a8\u6062\u590d\u3002 \u6302\u5355\u6210\u4ea4\u4e0d\u5f71\u54cd\u7cfb\u7edf\u4ef7\u683c\u3001\u4e0d\u8bb0\u5f55 K \u7ebf\u6210\u4ea4\u91cf\uff1b\u4e70\u5bb6\u6240\u5f97\u80a1\u7968\u53d7 T+1\uff0c\u5356\u5bb6\u5356\u51fa\u7684\u662f\u5df2\u6709\u6301\u4ed3\u4e0d\u53d7 T+1\u3002 | Player order books: each side places a limit order; within a matching cycle a fill happens whenever bid >= ask, at the midpoint.  Locking is a logical flag - the items stay in the player's account and are derived from order data; orders survive restarts via saved data.  Order fills never move the system price and add no candle volume; shares the buyer receives follow T+1, while the seller's already-held shares do not.")
                ;
        ORDER_MATCH_INTERVAL_SECONDS = b.comment("\u64ae\u5408\u95f4\u9694\uff08\u79d2\uff09\u3002\u9ed8\u8ba4 60\u3002\u8303\u56f4 10~600\u3002 | Matching interval (seconds). Default 60. Range 10~600.")
                .defineInRange("orderMatchIntervalSeconds", 60, 10, 600);
        ORDER_MAX_KEEP_DAYS = b.comment("\u6302\u5355\u6700\u957f\u4fdd\u7559\uff08\u4ea4\u6613\u65e5\uff09\uff0c\u8fc7\u671f\u81ea\u52a8\u64a4\u9500\u3002\u9ed8\u8ba4 7\u3002\u8303\u56f4 1~30\u3002 | Maximum order lifetime (trading days); expired orders are cancelled automatically. Default 7. Range 1~30.")
                .defineInRange("orderMaxKeepDays", 7, 1, 30);
        ORDER_MAX = b.comment("\u6bcf\u4e2a\u73a9\u5bb6\u540c\u65f6\u6700\u591a\u6302\u51e0\u5f20\u5355\u3002\u9ed8\u8ba4 20\u3002\u8303\u56f4 1~200\u3002 | Concurrent player orders per player. Default 20. Range 1~200.")
                .defineInRange("orderMax", 20, 1, 200);
        ORDER_FEE_PERCENT = b.comment("\u6302\u5355\u624b\u7eed\u8d39\uff08\u767e\u5206\u6bd4\uff09\uff0c\u4e70\u5356\u53cc\u65b9\u5404\u627f\u62c5\u4e00\u534a\uff0c\u4e0e\u4ea4\u6613\u7a0e\u52a0\u6cd5\u53e0\u52a0\u3002\u9ed8\u8ba4 0.5\u3002\u8303\u56f4 0~5\u3002 | Order fee (percent), split evenly between both sides and added to the trade tax. Default 0.5. Range 0~5.")
                .defineInRange("orderFeePercent", 0.5, 0.0, 5.0);
        ORDER_AFFECT_PRICE = b.comment("\u6302\u5355\u662f\u5426\u5f71\u54cd\u5e02\u573a\u4ef7\u683c\u3002\u9ed8\u8ba4 true\uff1a\u64ae\u5408\u4ef7\u4f1a\u628a\u7cfb\u7edf\u4ef7\u62c9\u5411\u6210\u4ea4\u4ef7\u5e76\u589e\u52a0 K \u7ebf\u6210\u4ea4\u91cf\uff1b\u73a9\u5bb6\u8f83\u5c11\u65f6\uff0c\u884c\u60c5\u4f1a\u66f4\u9891\u7e41\u5730\u8df3\u5411\u73a9\u5bb6\u6302\u5355\u4ef7\u3002\u8bbe\u4e3a false \u65f6\uff0c\u6302\u5355\u53ea\u5728\u73a9\u5bb6\u4e4b\u95f4\u6210\u4ea4\uff0c\u64ae\u5408\u4e0d\u6539\u5199\u7cfb\u7edf\u884c\u60c5\u4e5f\u4e0d\u8bb0\u5f55\u5168\u670d K \u7ebf\u6210\u4ea4\u91cf\u3002 | Whether order fills move the market price. Default true: matching moves the quote toward the fill price and adds that volume to the candlestick; with few players online this makes the market jump toward order-book trades more often. Setting it to false keeps player-to-player matching outside the system quote and candle volume.")
                .define("orderAffectPrice", true);
        b.pop();

        // ============================ category 14: dynamic listing and delisting ============================
        b.push("listing");
        b.comment("\u52a8\u6001\u4e0a\u5e02\u9000\u5e02\uff1a\u6bcf\u9694 N \u4e2a\u4ea4\u6613\u65e5\u968f\u673a\u8ba9\u4e00\u53ea\u5019\u9009\u80a1\u7968\u4e0a\u5e02\uff1b\u57fa\u51c6\u4ef7 = \u5151\u6362\u70b9\u6570 \u00d7 basePriceMultiplier\uff08\u9ed8\u8ba4 5\uff09\uff0c\u9996\u65e5\u5927\u5e45\u6ce2\u52a8\u3002 \u8fde\u7eed N \u4e2a\u4ea4\u6613\u65e5\u4ef7\u683c\u4f4e\u4e8e\u57fa\u51c6\u4ef7 delistThresholdPercent% \u7684\u80a1\u7968\u9000\u5e02\uff0c\u6301\u4ed3\u51bb\u7ed3\u4e0d\u6e05\u7b97\uff0cN \u4e2a\u4ea4\u6613\u65e5\u540e\u53ef\u91cd\u65b0\u4e0a\u5e02\u3002 \u81ea\u5b9a\u4e49\u80a1\u7968\u4f18\u5148\u4e8e\u52a8\u6001\u4e0a\u5e02\uff0c\u4e24\u8005\u5171\u5b58\u4e14\u5408\u8ba1\u4e0d\u8d85\u8fc7\u6570\u91cf\u4e0a\u9650\u3002 enabled=false \u53ef\u6574\u4f53\u5173\u95ed\u968f\u673a\u81ea\u52a8\u4e0a\u5e02\u3002 \u9000\u5e02\u8303\u56f4\uff1a\u9ed8\u8ba4\u53ea\u6709\u5019\u9009\u6c60\u52a8\u6001\u80a1\u7968\u4f1a\u81ea\u52a8\u9000\u5e02\uff1b\u5185\u7f6e 8 \u53ea\u53d7 builtinDynamicListing \u63a7\u5236\uff1b\u81ea\u5b9a\u4e49\u80a1\u7968\u59cb\u7ec8\u4ee5\u914d\u7f6e\u6587\u4ef6\u4e3a\u51c6\u3002 | Dynamic listing & delisting: every N trading days one candidate stock lists; base price = exchange points x basePriceMultiplier (default 5) with a big first-day move.  A stock trading below delistThresholdPercent% of base for N consecutive trading days delists; positions are frozen, not liquidated, and it may relist after N more days.  Custom stocks take priority over dynamic listings; both may coexist within the total listing cap.  Set enabled=false to turn automatic random listings off entirely.  Delisting scope: by default only candidate-pool dynamic stocks auto-delist; the 8 built-ins obey builtinDynamicListing; custom stocks always follow this config file.")
                ;
        DYNAMIC_LISTING_ENABLED = b.comment("\u662f\u5426\u542f\u7528\u968f\u673a\u80a1\u7968\u81ea\u52a8\u4e0a\u5e02\u3002\u9ed8\u8ba4 true\uff1b\u8bbe\u4e3a false \u540e\u4e0d\u518d\u81ea\u52a8\u65b0\u589e\u52a8\u6001\u80a1\u7968\uff08\u5df2\u6709\u52a8\u6001\u80a1\u7968\u4ecd\u53ef\u9000\u5e02\uff09\u3002 | Enable automatic random stock listings. Default true; false stops new dynamic listings (existing ones may still delist).")
                .define("enabled", true);
        BUILTIN_DYNAMIC_LISTING = b.comment("\u9ed8\u8ba4 8 \u53ea\u5185\u7f6e\u80a1\u7968\uff08\u7164\u70ad\u2026\u4e0b\u754c\u5408\u91d1\uff09\u662f\u5426\u53c2\u4e0e\u52a8\u6001\u4e0a\u5e02\u9000\u5e02\u3002\u9ed8\u8ba4 false\uff1a\u5185\u7f6e\u80a1\u7968\u6c38\u8fdc\u4e0a\u5e02\u3001\u4e0d\u4f1a\u88ab\u81ea\u52a8\u9000\u5e02\u3002\u8bbe\u4e3a true\uff1a\u5185\u7f6e\u80a1\u7968\u8fde\u7eed delistConsecutiveDays \u4e2a\u4ea4\u6613\u65e5\u4ef7\u683c\u4f4e\u4e8e\u57fa\u51c6\u4ef7 delistThresholdPercent% \u65f6\u9000\u5e02\uff08\u6301\u4ed3\u51bb\u7ed3\u4e0d\u6e05\u7b97\u3001\u64a4\u9500\u5176\u6302\u5355\u3001\u5f3a\u5e73\u5176\u7a7a\u4ed3\u3001\u7ed3\u675f\u5176\u4e8b\u4ef6\uff09\uff0c\u95f4\u9694 relistIntervalDays \u4e2a\u4ea4\u6613\u65e5\u540e\u6309\u914d\u7f6e\u57fa\u51c6\u4ef7\u91cd\u65b0\u4e0a\u5e02\u5e76\u91cd\u8dd1\u9996\u65e5\u6ce2\u52a8\u3002\u5f00\u5173\u5173\u95ed\u65f6\u5df2\u9000\u5e02\u7684\u5185\u7f6e\u80a1\u7968\u4f1a\u81ea\u52a8\u6062\u590d\u4e0a\u5e02\u3002\u914d\u7f6e\u5206\u7c7b\u5341\u7684\u81ea\u5b9a\u4e49\u80a1\u7968\u4e0d\u53d7\u672c\u5f00\u5173\u5f71\u54cd\uff1a\u5b83\u4eec\u59cb\u7ec8\u4ee5\u914d\u7f6e\u6587\u4ef6\u4e3a\u51c6\uff0c\u4e0d\u4f1a\u88ab\u81ea\u52a8\u9000\u5e02\u3002 | Whether the 8 built-in default stocks (coal..netherite) take part in dynamic listing/delisting. Default false: built-ins always stay listed and never auto-delist. When true: a built-in closing below delistThresholdPercent% of its base price for delistConsecutiveDays consecutive trading days delists (holdings frozen, not liquidated; its orders cancelled, shorts force-closed, events ended), then relists at its configured base price after relistIntervalDays trading days with a fresh first-day move. Turning this off restores any delisted built-in to listing immediately. Custom stocks from category ten are unaffected: they always follow the config file and never auto-delist.")
                .define("builtinDynamicListing", false);
        DYNAMIC_UNLOCK_LEVEL = b.comment("\u52a8\u6001\u4e0a\u5e02\u80a1\u7968\u7684\u9ed8\u8ba4\u89e3\u9501\u7b49\u7ea7\u3002\u9ed8\u8ba4 1\u3002\u8303\u56f4 1~4\u3002\u5019\u9009\u6c60\u5355\u884c\u7b2c 5 \u6bb5\u53ef\u8986\u76d6\u9ed8\u8ba4\u503c\u3002 | Default unlock level for dynamically listed stocks. Default 1. Range 1~4. The 5th field of a candidate line may override it.")
                .defineInRange("unlockLevel", 1, 1, 4);
        DYNAMIC_BASE_PRICE_MULTIPLIER = b.comment("\u52a8\u6001\u4e0a\u5e02\u57fa\u51c6\u4ef7\u500d\u6570\uff1a\u57fa\u51c6\u4ef7 = \u5151\u6362\u70b9\u6570 \u00d7 \u8be5\u500d\u6570\u3002\u9ed8\u8ba4 5.0\u3002\u8303\u56f4 0.1~100.0\u3002 | Dynamic listing base-price multiplier: base = exchange points x this factor. Default 5.0. Range 0.1~100.0.")
                .defineInRange("basePriceMultiplier", 5.0, 0.1, 100.0);
        DYNAMIC_CANDIDATES = b.comment("\u968f\u673a\u4e0a\u5e02\u5019\u9009\u6c60\uff0c\u6bcf\u884c\u4e00\u53ea\u80a1\u7968\uff0c\u683c\u5f0f\uff1a\u4ee3\u7801|\u7269\u54c1ID|\u663e\u793a\u540d\u79f0|\u5151\u6362\u70b9\u6570[|\u89e3\u9501\u7b49\u7ea7]\u3002\uFF1B\u793a\u4f8b\uff1a\"EMERALD|minecraft:emerald|\u7eff\u5b9d\u77f3|25|1\"\u3002\uFF1B\u7b2c 5 \u6bb5\u89e3\u9501\u7b49\u7ea7\u53ef\u4ee5\u7701\u7565\uff1a\u7701\u7565\u65f6\u4f7f\u7528 dynamicUnlockLevel\uff1b\u586b\u5199\u65f6\u8303\u56f4 1~4\u3002\uFF1B\u65b9\u62ec\u53f7\u53ea\u8868\u793a\u53ef\u9009\u5185\u5bb9\uff0c\u5b9e\u9645\u914d\u7f6e\u4e0d\u8981\u8f93\u5165\u65b9\u62ec\u53f7\u3002\uFF1B\u4ee3\u7801\u9700\u4e3a 2~8 \u4f4d\u5927\u5199\u5b57\u6bcd\u6216\u6570\u5b57\uff1b\u6700\u591a 64 \u6761\uff1b\u663e\u793a\u540d\u79f0\u7559\u7a7a\u65f6\u4f7f\u7528\u7269\u54c1\u540d\u79f0\uff1b\u5217\u8868\u7559\u7a7a\u65f6\u4f7f\u7528\u6a21\u7ec4\u5185\u7f6e\u5019\u9009\u6c60\u3002 | Random-listing pool: one stock per line, format code|item id|display name|exchange points[|unlock level]. Example: \"EMERALD|minecraft:emerald|Emerald|25|1\".\uFF1BThe fifth field is optional: omit it to use dynamicUnlockLevel, or enter 1 to 4.\uFF1BSquare brackets only mark optional content; do not type the square brackets in the config.\uFF1BCodes are 2-8 uppercase letters/digits; up to 64 rows; an empty display name uses the item name; an empty list uses the built-in pool. Candidate-pool stocks are general market stocks: they receive generic events after listing, but not mineral-only mine or vein events.")
                .defineList("candidates", Collections.emptyList(),
                        o -> o instanceof String s && !s.isBlank());
        LIST_INTERVAL_DAYS = b.comment("\u52a8\u6001\u4e0a\u5e02\u95f4\u9694\uff08\u4ea4\u6613\u65e5\uff09\u3002\u9ed8\u8ba4 5\u3002\u8303\u56f4 1~30\u3002 | Interval between dynamic listings (trading days). Default 5. Range 1~30.")
                .defineInRange("listIntervalDays", 5, 1, 30);
        DELIST_THRESHOLD_PERCENT = b.comment("\u9000\u5e02\u9608\u503c\uff1a\u4ef7\u683c\u4f4e\u4e8e\u57fa\u51c6\u4ef7\u7684\u8fd9\u4e2a\u767e\u5206\u6bd4\u8bb0\u4e3a\u4f4e\u7f6e\u4fe1\u4ef7\u3002\u9ed8\u8ba4 30\u3002\u8303\u56f4 10~90\u3002 | Delisting threshold: price below this percent of base counts as a low-confidence close. Default 30. Range 10~90.")
                .defineInRange("delistThresholdPercent", 30.0, 10.0, 90.0);
        DELIST_CONSECUTIVE_DAYS = b.comment("\u8fde\u7eed\u591a\u5c11\u4e2a\u4ea4\u6613\u65e5\u6ee1\u8db3\u9000\u5e02\u9608\u503c\u624d\u9000\u5e02\u3002\u9ed8\u8ba4 3\u3002\u8303\u56f4 1~10\u3002 | Consecutive trading days below the threshold required to delist. Default 3. Range 1~10.")
                .defineInRange("delistConsecutiveDays", 3, 1, 10);
        RELIST_INTERVAL_DAYS = b.comment("\u9000\u5e02\u80a1\u7968\u6700\u5c11\u95f4\u9694\u591a\u5c11\u4e2a\u4ea4\u6613\u65e5\u624d\u80fd\u91cd\u65b0\u4e0a\u5e02\u3002\u9ed8\u8ba4 10\u3002\u8303\u56f4 1~60\u3002 | Minimum trading days a delisted stock must wait before relisting. Default 10. Range 1~60.")
                .defineInRange("relistIntervalDays", 10, 1, 60);
        LISTING_MAX_COUNT = b.comment("\u540c\u65f6\u4e0a\u5e02\u80a1\u7968\u603b\u6570\u4e0a\u9650\uff08\u542b\u5185\u7f6e\u3001\u81ea\u5b9a\u4e49\u3001\u52a8\u6001\uff09\u3002\u9ed8\u8ba4 24\u3002\u8303\u56f4 8~64\u3002 | Maximum number of concurrently listed stocks (built-in + custom + dynamic). Default 24. Range 8~64.")
                .defineInRange("listingMaxCount", 24, 8, 64);
        FIRST_DAY_UP_CHANCE = b.comment("\u9996\u65e5\u5927\u6da8\u6982\u7387\u3002\u9ed8\u8ba4 0.30\uff0c\u8303\u56f4 0.0~1.0\u3002\u4e09\u4e2a\u9996\u65e5\u6982\u7387\u4e4b\u548c\u5e94\u4e3a 1.0\uff0c \u52a0\u8f7d\u65f6\u82e5\u603b\u548c\u4e0d\u7b49\u4e8e 1.0 \u4f1a\u6309\u6bd4\u4f8b\u5f52\u4e00\u5316\u5e76\u6253\u5370\u8b66\u544a\uff08\u5f52\u4e00\u5316\u53ea\u4fdd\u5b58\u5728\u5185\u5b58\uff0c\u4e0d\u5199\u56de\u672c\u6587\u4ef6\uff09\u3002 | First-day surge probability. Default 0.30. Range 0.0~1.0. The three first-day chances should sum to 1.0;  if not, they are proportionally normalized at load with a warning (normalization lives in memory only, never written back).")
                .defineInRange("firstDayUpChance", 0.30, 0.0, 1.0);
        FIRST_DAY_DOWN_CHANCE = b.comment("\u9996\u65e5\u5927\u8dcc\u6982\u7387\u3002\u9ed8\u8ba4 0.30\uff0c\u8303\u56f4 0.0~1.0\u3002 | First-day crash probability. Default 0.30. Range 0.0~1.0.")
                .defineInRange("firstDayDownChance", 0.30, 0.0, 1.0);
        FIRST_DAY_FLAT_CHANCE = b.comment("\u9996\u65e5\u5e73\u7a33\u6982\u7387\u3002\u9ed8\u8ba4 0.40\uff0c\u8303\u56f4 0.0~1.0\u3002 | First-day flat probability. Default 0.40. Range 0.0~1.0.")
                .defineInRange("firstDayFlatChance", 0.40, 0.0, 1.0);
        FIRST_DAY_UP_MIN = b.comment("\u9996\u65e5\u5927\u6da8\u6700\u5c0f\u6da8\u5e45\uff080.20 = +20%\uff09\u3002\u9ed8\u8ba4 0.20\uff0c\u8303\u56f4 0.0~1.0\u3002\u82e5\u5927\u4e8e\u6700\u5927\u503c\uff0c\u52a0\u8f7d\u65f6\u81ea\u52a8\u4ea4\u6362\u5e76\u8b66\u544a\u3002 | First-day surge minimum gain (0.20 = +20%). Default 0.20. Range 0.0~1.0. If it exceeds the max, load swaps them with a warning.")
                .defineInRange("firstDayUpMin", 0.20, 0.0, 1.0);
        FIRST_DAY_UP_MAX = b.comment("\u9996\u65e5\u5927\u6da8\u6700\u5927\u6da8\u5e45\u3002\u9ed8\u8ba4 0.50\uff0c\u8303\u56f4 0.0~2.0\u3002 | First-day surge maximum gain. Default 0.50. Range 0.0~2.0.")
                .defineInRange("firstDayUpMax", 0.50, 0.0, 2.0);
        FIRST_DAY_DOWN_MIN = b.comment("\u9996\u65e5\u5927\u8dcc\u6700\u5c0f\u8dcc\u5e45\u3002\u9ed8\u8ba4 0.20\uff0c\u8303\u56f4 0.0~1.0\u3002 | First-day crash minimum loss. Default 0.20. Range 0.0~1.0.")
                .defineInRange("firstDayDownMin", 0.20, 0.0, 1.0);
        FIRST_DAY_DOWN_MAX = b.comment("\u9996\u65e5\u5927\u8dcc\u6700\u5927\u8dcc\u5e45\u3002\u9ed8\u8ba4 0.50\uff0c\u8303\u56f4 0.0~1.0\u3002 | First-day crash maximum loss. Default 0.50. Range 0.0~1.0.")
                .defineInRange("firstDayDownMax", 0.50, 0.0, 1.0);
        FIRST_DAY_FLAT_RANGE = b.comment("\u9996\u65e5\u5e73\u7a33\u5e45\u5ea6\uff08\u00b1\u8be5\u503c\uff09\u3002\u9ed8\u8ba4 0.05 = \u00b15%\u3002\u8303\u56f4 0.0~0.20\u3002 | First-day flat band (+/- this value). Default 0.05 = +/-5%. Range 0.0~0.20.")
                .defineInRange("firstDayFlatRange", 0.05, 0.0, 0.20);
        b.pop();

        // ============================ category 15: limit orders ============================
        b.push("limitOrder");
        b.comment("\u9650\u4ef7\u5355\uff1a\u4ef7\u683c\u8dcc\u5230 X \u81ea\u52a8\u4e70\u5165 / \u6da8\u5230 X \u81ea\u52a8\u5356\u51fa\uff0c\u6bcf\u4e2a marketTick \u68c0\u67e5\u4e00\u6b21\uff0c \u89e6\u53d1\u65f6\u6309\u7cfb\u7edf\u4ea4\u6613\u5904\u7406\uff08\u5f71\u54cd\u7cfb\u7edf\u4ef7\u683c\u3001\u8bb0\u5f55 K \u7ebf\u6210\u4ea4\u91cf\uff09\uff0c\u989d\u5916\u6536\u53d6\u9650\u4ef7\u5355\u624b\u7eed\u8d39\uff08\u4e0e\u4ea4\u6613\u7a0e\u52a0\u6cd5\u53e0\u52a0\uff09\u3002 \u4f59\u989d/\u6301\u4ed3\u4e0d\u8db3\u65f6\u5ef6\u8fdf\u5230\u4e0b\u4e00\u4e2a marketTick \u91cd\u8bd5\uff0c\u8fde\u7eed\u8fbe\u5230 failRetries \u6b21\u4e0d\u8db3\u81ea\u52a8\u53d6\u6d88\uff08\u9ed8\u8ba4 3\uff09\u3002 | Limit orders: auto-buy when the price falls to X / auto-sell when it rises to X, checked every marketTick.  Fills go through the system trade path (they move the system price and add candle volume) and pay the limit fee on top of the trade tax.  When balance or holdings fall short, execution retries on the next marketTick; after failRetries consecutive misses the order is auto-cancelled (default 3).")
                ;
        LIMIT_ORDER_MAX = b.comment("\u6bcf\u4e2a\u73a9\u5bb6\u540c\u65f6\u6700\u591a\u6302\u51e0\u6761\u9650\u4ef7\u5355\u3002\u9ed8\u8ba4 10\u3002\u8303\u56f4 1~50\u3002 | Concurrent limit orders per player. Default 10. Range 1~50.")
                .defineInRange("limitOrderMax", 10, 1, 50);
        LIMIT_ORDER_FEE_PERCENT = b.comment("\u9650\u4ef7\u5355\u624b\u7eed\u8d39\uff08\u767e\u5206\u6bd4\uff09\uff0c\u4e0e\u4ea4\u6613\u7a0e\u52a0\u6cd5\u53e0\u52a0\u3002\u9ed8\u8ba4 1\u3002\u8303\u56f4 0~5\u3002 | Limit-order fee (percent), added on top of the trade tax. Default 1. Range 0~5.")
                .defineInRange("limitOrderFeePercent", 1.0, 0.0, 5.0);
        LIMIT_ORDER_MAX_KEEP_DAYS = b.comment("\u9650\u4ef7\u5355\u6700\u957f\u4fdd\u7559\uff08\u4ea4\u6613\u65e5\uff09\uff0c\u8fc7\u671f\u81ea\u52a8\u53d6\u6d88\u3002\u9ed8\u8ba4 7\u3002\u8303\u56f4 1~30\u3002 | Maximum limit-order lifetime (trading days); expired orders are auto-cancelled. Default 7. Range 1~30.")
                .defineInRange("limitOrderMaxKeepDays", 7, 1, 30);
        LIMIT_ORDER_FAIL_RETRIES = b.comment("\u9650\u4ef7\u5355\u8d44\u91d1\u6216\u6301\u4ed3\u4e0d\u8db3\u65f6\u6700\u591a\u8fde\u7eed\u91cd\u8bd5\u51e0\u4e2a marketTick\uff0c\u8d85\u8fc7\u540e\u81ea\u52a8\u53d6\u6d88\u3002\u9ed8\u8ba4 3\u3002\u8303\u56f4 1~100\u3002 | Consecutive marketTick retries allowed when funds or holdings are short before auto-cancel. Default 3. Range 1~100.")
                .defineInRange("limitOrderFailRetries", 3, 1, 100);
        LIMIT_ORDER_DEVIATION_THRESHOLD = b.comment("\u9650\u4ef7\u5355\u89e6\u53d1\u504f\u5dee\u63d0\u793a\u9608\u503c\u3002\u9650\u4ef7\u5355\u6309\u5b9e\u9645\u5e02\u4ef7\u6210\u4ea4\uff0c\u5b9e\u9645\u4ef7\u4e0e\u89e6\u53d1\u4ef7\u504f\u5dee\u767e\u5206\u6bd4\u8d85\u8fc7\u8be5\u503c\u65f6 \u5199\u5165\u65e5\u5fd7\u201c\u9650\u4ef7\u5355\u4ee5 X \u6210\u4ea4\uff0c\u89e6\u53d1\u4ef7 Y\uff0c\u504f\u5dee Z%\u201d\uff0c\u4f46\u4e0d\u53d6\u6d88\u8be5\u9650\u4ef7\u5355\u3002\u9ed8\u8ba4 0.05 = 5%\u3002\u8303\u56f4 0.01~0.20\u3002 | Limit-order deviation notice threshold. Limit orders fill at the real market price; when the gap to the trigger price  exceeds this percent the log records \"limit filled at X, trigger Y, deviation Z%\", but the order stays alive. Default 0.05 = 5%. Range 0.01~0.20.")
                .defineInRange("limitOrderDeviationThreshold", 0.05, 0.01, 0.20);
        b.pop();

        // ============================ category 16: leaderboards ============================
        b.push("leaderboard");
        LEADERBOARD_SIZE = b.comment("\u6392\u884c\u699c\u6bcf\u9875\u663e\u793a\u540d\u6b21\u6570\u91cf\u3002\u9ed8\u8ba4 10\u3002\u8303\u56f4 5~50\u3002 | Ranks per leaderboard page. Default 10. Range 5~50.")
                .defineInRange("leaderboardSize", 10, 5, 50);
        LEADERBOARD_SHOW_SELF = b.comment("\u6392\u884c\u699c\u5e95\u90e8\u662f\u5426\u663e\u793a\u81ea\u5df1\u7684\u6392\u540d\u3002\u9ed8\u8ba4 true\uff0c\u8bbe\u4e3a false \u5219\u73a9\u5bb6\u53ef\u5173\u95ed\u3002 | Show your own rank at the leaderboard's bottom. Default true; false hides it.")
                .define("leaderboardShowSelf", true);
        b.pop();

        // ============================ networking ============================
        b.push("network");
        SYNC_MODEL = b.comment("\u884c\u60c5\u540c\u6b65\u6a21\u5f0f\uff1aFULL=\u6bcf tick \u5411\u6240\u6709\u73a9\u5bb6\u63a8\u5168\u91cf\u5feb\u7167\uff1bINCREMENTAL=\u53ea\u5411\u6253\u5f00 UI \u7684\u73a9\u5bb6\u63a8\u589e\u91cf\uff08\u9ed8\u8ba4\uff09\uff1bON_DEMAND=\u4ef7\u683c\u53d8\u52a8\u8d85 1% \u624d\u63a8\u3002 | Quote sync mode: FULL pushes every player a complete snapshot each tick; INCREMENTAL pushes deltas only to players with the UI open (default); ON_DEMAND pushes only when a price moves over 1%.")
                .defineEnum("syncModel", SyncModel.INCREMENTAL);
        b.pop();

        // ============================ category 10: custom stocks ============================
        b.push("customStocks");
        CUSTOM_STOCKS = b.comment("\u81ea\u5b9a\u4e49\u80a1\u7968\uff0c\u6bcf\u884c\u4e00\u6761\uff0c\u683c\u5f0f\uff1a\u80a1\u7968\u4ee3\u7801|\u7269\u54c1ID|\u663e\u793a\u540d|\u57fa\u51c6\u4ef7|\u5151\u6362\u70b9\u6570|\u89e3\u9501\u7b49\u7ea7\u3002\uFF1B\u4f8b\uff1a\"MYST|minecraft:amethyst_shard|\u7d2b\u6c34\u6676|25|10|2\"\u3002\uFF1B\u4ee3\u7801\u53ea\u5141\u8bb8 2~8 \u4f4d\u5927\u5199\u5b57\u6bcd\u4e0e\u6570\u5b57\u3001\u4e0d\u80fd\u4e0e\u5185\u7f6e 8 \u53ea\u91cd\u540d\uff1b\u6700\u591a 16 \u53ea\uff1b\u663e\u793a\u540d\u53ef\u7559\u7a7a\uff08\u81ea\u52a8\u7528\u7269\u54c1\u540d\uff09\uff1b\uFF1B\u57fa\u51c6\u4ef7 1.0~100000.0\uff0c\u5151\u6362\u70b9\u6570 1~10000\uff0c\u89e3\u9501\u7b49\u7ea7 1~4\uff08\u8d8a\u754c\u94b3\u5236\uff09\u3002\uFF1B\u6539\u5b8c\u4fdd\u5b58\u540e 5 \u79d2\u5185\u751f\u6548\uff1a\u65b0\u589e\u80a1\u7968\u4ef7\u683c\u521d\u59cb\u5316\u4e3a\u57fa\u51c6\u4ef7\u3001K \u7ebf\u4ece\u7a7a\u5f00\u59cb\uff1b\u79fb\u9664\u7684\u80a1\u7968\u53ea\u6807\u8bb0\u4e0b\u67b6\u3001\u73a9\u5bb6\u6301\u4ed3\u4e0d\u5220\u9664\u3002 | Custom stocks, one per line: CODE|item id|display name|base price|exchange points|unlock level.\uFF1BExample: \"MYST|minecraft:amethyst_shard|Amethyst|25|10|2\".\uFF1BCodes are 2-8 uppercase letters/digits and must not clash with the 8 built-ins; max 16 stocks; display name may stay empty (item name used);\uFF1Bbase price 1.0~100000.0, exchange points 1~10000, unlock level 1~4 (clamped).\uFF1BSaved edits apply within 5 seconds: new stocks start at base price with an empty candle history; removed stocks are only marked retired, player positions survive.")
                .defineList("list", Collections.emptyList(),
                        o -> o instanceof String s && !s.isBlank());
        b.pop();

        // ============================ category 20: UI theme (24-bit RGB integers) ============================
        // Every key is a 0xRRGGBB integer (decimal or 0x hex both accepted); an opaque alpha is added automatically at use.
        // The server value ships with the config snapshot so all client screens share one palette; saving applies it live (next frame).
        b.push("uiTheme");
        b.comment("\u754c\u9762\u4e3b\u9898\u914d\u8272\uff08\u5206\u7c7b\u4e8c\u5341\uff09\uff1a24 \u4f4d RGB \u6574\u6570\uff08\u5982 0xFF6F6F \u6216 16741231\uff09\uff0c\u81ea\u52a8\u4e0d\u900f\u660e\u3002",
                "UI theme colors (category 20): 24-bit RGB integers (e.g. 0xFF6F6F or 16741231), forced opaque.");
        UI_PANEL_ROOT_RGB = b.comment("\u6839\u9762\u677f\u4e0e K \u7ebf\u63cf\u8fb9\u5e95\u8272\uff08\u9ed8\u8ba4 0x1A1A1A\uff09\u3002 | Root panel & candle-stick stroke base (default 0x1A1A1A).").defineInRange("panelRoot", 0x1A1A1A, 0, 0xFFFFFF);
        UI_PANEL_SOFT_RGB = b.comment("\u5206\u533a\u9762\u677f/\u4e8b\u4ef6\u680f/\u5361\u7247\u5e95\u8272\uff08\u9ed8\u8ba4 0x2B2B2B\uff09\u3002 | Section panel / event bar / card background (default 0x2B2B2B).").defineInRange("panelSoft", 0x2B2B2B, 0, 0xFFFFFF);
        UI_PANEL_ROW_RGB = b.comment("\u884c\u5206\u9694\u7ebf\u4e0e\u901a\u7528\u6309\u94ae\u5e95\u8272\uff08\u9ed8\u8ba4 0x3A3A3A\uff09\u3002 | Row separators & generic button background (default 0x3A3A3A).").defineInRange("panelRow", 0x3A3A3A, 0, 0xFFFFFF);
        UI_BORDER_RGB = b.comment("\u9762\u677f 1px \u8fb9\u6846\u8272\uff08\u9ed8\u8ba4 0x555555\uff09\u3002 | Panel 1px border (default 0x555555).").defineInRange("border", 0x555555, 0, 0xFFFFFF);
        UI_DARK_RGB = b.comment("\u6df1\u5e95\uff1a\u6807\u7b7e\u672a\u9009\u4e2d\u5e95\u3001\u6eda\u52a8\u6761\u8f68\u9053\u3001\u7981\u7528\u6309\u94ae\u5e95\uff08\u9ed8\u8ba4 0x2A2A2A\uff09\u3002 | Dark fill: idle tabs, scrollbar tracks, disabled buttons (default 0x2A2A2A).").defineInRange("dark", 0x2A2A2A, 0, 0xFFFFFF);
        UI_HIGHLIGHT_RGB = b.comment("\u9762\u677f\u5185\u4fa7 1px \u9ad8\u5149\uff08\u9ed8\u8ba4 0x4A4A4A\uff09\u3002 | Inset 1px highlight (default 0x4A4A4A).").defineInRange("highlight", 0x4A4A4A, 0, 0xFFFFFF);
        UI_ACCENT_RGB = b.comment("\u5f3a\u8c03\u8272\uff1a\u9009\u4e2d\u7ad6\u6761\u3001\u952e\u76d8\u7126\u70b9\u73af\u3001\u89e3\u9501\u5706\u70b9\uff08\u9ed8\u8ba4 0x6A8FD0\uff09\u3002 | Accent: selection bar, focus ring, unlocked dot (default 0x6A8FD0).").defineInRange("accent", 0x6A8FD0, 0, 0xFFFFFF);
        UI_GOLD_RGB = b.comment("\u91d1\u8272\u70b9\u7f00\uff1a\u5206\u533a\u6807\u9898\u3001\u81ea\u5df1\u6392\u540d\u884c\uff08\u9ed8\u8ba4 0xFFD75F\uff09\u3002 | Gold accents: section titles, own leaderboard row (default 0xFFD75F).").defineInRange("gold", 0xFFD75F, 0, 0xFFFFFF);
        UI_MUTED_RGB = b.comment("\u6b21\u8981\u6587\u5b57\u4e0e\u6a2a\u7ebf\u3001\u6301\u5e73\u8721\u70db\u5b9e\u4f53\uff08\u9ed8\u8ba4 0xAAAAAA\uff09\u3002 | Muted text, separators, flat candles (default 0xAAAAAA).").defineInRange("muted", 0xAAAAAA, 0, 0xFFFFFF);
        UI_GRAY_RGB = b.comment("\u7070\u5b57\u3001\u672a\u89e3\u9501\u5706\u70b9\u3001\u6301\u5e73\u5f71\u7ebf\uff08\u9ed8\u8ba4 0x888888\uff09\u3002 | Grey text, locked dots, flat wicks (default 0x888888).").defineInRange("gray", 0x888888, 0, 0xFFFFFF);
        UI_DIM_RGB = b.comment("\u6df1\u7070\u5b57\u4e0e\u6eda\u52a8\u6761\u6ed1\u5757\uff08\u9ed8\u8ba4 0x666666\uff09\u3002 | Dim text and scrollbar thumbs (default 0x666666).").defineInRange("dim", 0x666666, 0, 0xFFFFFF);
        UI_QUOTE_UP_RGB = b.comment("\u884c\u60c5\u4e0a\u6da8\u6587\u5b57\u8272\uff08\u9ed8\u8ba4 0xFF6F6F\uff0c\u7ea2\u6da8\uff09\u3002 | Rising quote text (default 0xFF6F6F, red-up).").defineInRange("quoteUp", 0xFF6F6F, 0, 0xFFFFFF);
        UI_QUOTE_DOWN_RGB = b.comment("\u884c\u60c5\u4e0b\u8dcc\u6587\u5b57\u8272\uff08\u9ed8\u8ba4 0x6FE06F\uff0c\u7eff\u8dcc\uff09\u3002 | Falling quote text (default 0x6FE06F, green-down).").defineInRange("quoteDown", 0x6FE06F, 0, 0xFFFFFF);
        UI_QUOTE_FLAT_RGB = b.comment("\u884c\u60c5\u5e73\u76d8\u6587\u5b57\u8272\uff08\u9ed8\u8ba4 0xBDBDBD\uff09\u3002 | Flat quote text (default 0xBDBDBD).").defineInRange("quoteFlat", 0xBDBDBD, 0, 0xFFFFFF);
        UI_PNL_UP_RGB = b.comment("\u76c8\u5229\u4e0e\u9633\u7ebf\u5b9e\u4f53\u8272\uff08\u9ed8\u8ba4 0xE04A4A\uff09\u3002 | Profit & bull candle body (default 0xE04A4A).").defineInRange("pnlUp", 0xE04A4A, 0, 0xFFFFFF);
        UI_PNL_DOWN_RGB = b.comment("\u4e8f\u635f\u4e0e\u9634\u7ebf\u5b9e\u4f53\u8272\uff08\u9ed8\u8ba4 0x3FBF5F\uff09\u3002 | Loss & bear candle body (default 0x3FBF5F).").defineInRange("pnlDown", 0x3FBF5F, 0, 0xFFFFFF);
        UI_SELECTED_RGB = b.comment("\u5217\u8868\u9009\u4e2d\u884c\u9ed8\u8ba4\u5e95\u8272\uff08\u9ed8\u8ba4 0x4A5A7A\uff1b\u4ea4\u6613\u6240\u884c\u4ecd\u4f18\u5148\u7528 exchangeRowSelectedColor\uff09\u3002 | Default selected row background (0x4A5A7A; exchange rows prefer exchangeRowSelectedColor).").defineInRange("selected", 0x4A5A7A, 0, 0xFFFFFF);
        UI_HOVER_RGB = b.comment("\u60ac\u505c\u5e95\u8272\uff08\u9ed8\u8ba4 0x404040\uff09\u3002 | Hover background (default 0x404040).").defineInRange("hover", 0x404040, 0, 0xFFFFFF);
        UI_BLUE_RGB = b.comment("\u9009\u4e2d\u6807\u7b7e\u4e0e\u53d6\u56de\u6309\u94ae\u5e95\u8272\uff08\u9ed8\u8ba4 0x3A3A5A\uff09\u3002 | Selected tab & withdraw button (default 0x3A3A5A).").defineInRange("blue", 0x3A3A5A, 0, 0xFFFFFF);
        UI_BUY_RGB = b.comment("\u5b58\u5165\u6309\u94ae\u5e95\u8272\uff08\u9ed8\u8ba4 0x2E5A3A\uff09\u3002 | Deposit button background (default 0x2E5A3A).").defineInRange("buy", 0x2E5A3A, 0, 0xFFFFFF);
        UI_TOAST_BG_RGB = b.comment("\u9519\u8bef\u63d0\u793a\u4e0e\u4e8b\u4ef6\u5361\u8be6\u60c5\u9762\u677f\u8fd1\u9ed1\u5e95\u8272\uff08\u9ed8\u8ba4 0x0E0E0E\uff09\u3002 | Near-black background of error toast & event detail panel (default 0x0E0E0E).").defineInRange("toastBg", 0x0E0E0E, 0, 0xFFFFFF);
        UI_TOAST_FG_RGB = b.comment("\u9519\u8bef\u63d0\u793a\u6587\u5b57\u4e0e\u8fdb\u5ea6\u6761\u989c\u8272\uff08\u9ed8\u8ba4 0xFF5555\uff09\u3002 | Error toast text & progress bar (default 0xFF5555).").defineInRange("toastFg", 0xFF5555, 0, 0xFFFFFF);
        UI_KGRID_RGB = b.comment("K \u7ebf\u7f51\u683c\u7ebf\u989c\u8272\uff08\u9ed8\u8ba4 0x333333\uff09\u3002 | Candle chart grid lines (default 0x333333).").defineInRange("kGrid", 0x333333, 0, 0xFFFFFF);
        UI_CHART_BG_RGB = b.comment("K \u7ebf\u56fe\u5185\u90e8\u80cc\u666f\uff08\u9ed8\u8ba4 0x151515\uff09\u3002 | Candle chart inner background (default 0x151515).").defineInRange("chartBg", 0x151515, 0, 0xFFFFFF);
        UI_LETTERBOX_RGB = b.comment("\u753b\u5e03\u5916\u4fe1\u7bb1\u533a\u5e95\u8272\uff08\u9ed8\u8ba4 0x0D0D10\uff09\u3002 | Letterbox area outside the canvas (default 0x0D0D10).").defineInRange("letterbox", 0x0D0D10, 0, 0xFFFFFF);
        UI_RARITY_COMMON_RGB = b.comment("\u666e\u901a\u4e8b\u4ef6\u5361\u8fb9\u6846\u8272\uff08\u9ed8\u8ba4 0x888888\uff09\u3002 | Common event card border (default 0x888888).").defineInRange("rarityCommon", 0x888888, 0, 0xFFFFFF);
        UI_RARITY_RARE_RGB = b.comment("\u7f55\u89c1\u4e8b\u4ef6\u5361\u8fb9\u6846\u8272\uff08\u9ed8\u8ba4 0x4A7FD0\uff09\u3002 | Rare event card border (default 0x4A7FD0).").defineInRange("rarityRare", 0x4A7FD0, 0, 0xFFFFFF);
        UI_RARITY_EPIC_RGB = b.comment("\u53f2\u8bd7\u4e8b\u4ef6\u5361\u8fb9\u6846\u8272\uff08\u9ed8\u8ba4 0x9A4AD0\uff09\u3002 | Epic event card border (default 0x9A4AD0).").defineInRange("rarityEpic", 0x9A4AD0, 0, 0xFFFFFF);
        UI_RARITY_LEGEND_RGB = b.comment("\u4f20\u5947\u4e8b\u4ef6\u5361\u8fb9\u6846\u8272\uff08\u9ed8\u8ba4 0xD0A040\uff09\u3002 | Legendary event card border (default 0xD0A040).").defineInRange("rarityLegend", 0xD0A040, 0, 0xFFFFFF);
        UI_TEXT_RGB = b.comment("\u6b63\u6587\u767d\u5b57\u989c\u8272\uff08\u9ed8\u8ba4 0xFFFFFF\uff09\u3002 | Main body white text (default 0xFFFFFF).").defineInRange("textWhite", 0xFFFFFF, 0, 0xFFFFFF);
        UI_TEXT_SOFT_RGB = b.comment("\u672a\u9009\u5b50\u6807\u7b7e\u6587\u5b57\u8272\uff08\u9ed8\u8ba4 0xC9C9D9\uff09\u3002 | Inactive sub-tab text (default 0xC9C9D9).").defineInRange("textSoft", 0xC9C9D9, 0, 0xFFFFFF);
        UI_TIP_TEXT_RGB = b.comment("\u4e8b\u4ef6\u8be6\u60c5\u6b63\u6587\u4e0e\u56fe\u8868\u6570\u503c\u8272\uff08\u9ed8\u8ba4 0xE8E8E8\uff09\u3002 | Event detail & chart info text (default 0xE8E8E8).").defineInRange("tipText", 0xE8E8E8, 0, 0xFFFFFF);
        UI_BODY_TEXT_RGB = b.comment("\u65e5\u5fd7\u4e0e\u4e8b\u4ef6\u8bf4\u660e\u6587\u5b57\u8272\uff08\u9ed8\u8ba4 0xCCCCCC\uff09\u3002 | Log & event description text (default 0xCCCCCC).").defineInRange("bodyText", 0xCCCCCC, 0, 0xFFFFFF);
        UI_WICK_UP_RGB = b.comment("\u9633\u7ebf\u5f71\u7ebf\u8272\uff08\u9ed8\u8ba4 0xCC3A3A\uff0c\u6bd4\u5b9e\u4f53\u6df1\u4e00\u6863\uff09\u3002 | Bull candle wick (default 0xCC3A3A).").defineInRange("wickUp", 0xCC3A3A, 0, 0xFFFFFF);
        UI_WICK_DOWN_RGB = b.comment("\u9634\u7ebf\u5f71\u7ebf\u8272\uff08\u9ed8\u8ba4 0x2F9F4F\uff09\u3002 | Bear candle wick (default 0x2F9F4F).").defineInRange("wickDown", 0x2F9F4F, 0, 0xFFFFFF);
        UI_BUTTON_HOVER_RGB = b.comment("\u901a\u7528\u6309\u94ae\u60ac\u505c\u5e95\u8272\uff08\u9ed8\u8ba4 0x4E4E4E\uff09\u3002 | Generic button hover background (default 0x4E4E4E).").defineInRange("buttonHover", 0x4E4E4E, 0, 0xFFFFFF);
        UI_DEPOSIT_HOVER_RGB = b.comment("\u5b58\u5165\u6309\u94ae\u60ac\u505c\u5e95\u8272\uff08\u9ed8\u8ba4 0x3F7A50\uff09\u3002 | Deposit button hover background (default 0x3F7A50).").defineInRange("depositHover", 0x3F7A50, 0, 0xFFFFFF);
        UI_WITHDRAW_HOVER_RGB = b.comment("\u53d6\u56de\u6309\u94ae\u60ac\u505c\u5e95\u8272\uff08\u9ed8\u8ba4 0x50507A\uff09\u3002 | Withdraw button hover background (default 0x50507A).").defineInRange("withdrawHover", 0x50507A, 0, 0xFFFFFF);
        UI_WARNING_RGB = b.comment("\u5e95\u90e8\u7ea2\u8272\u8b66\u544a\u6587\u5b57\u8272\uff08\u9ed8\u8ba4 0xFF5F5F\uff09\u3002 | Bottom red warning text (default 0xFF5F5F).").defineInRange("warning", 0xFF5F5F, 0, 0xFFFFFF);
        UI_HINT_GRAY_RGB = b.comment("\u5f31\u63d0\u793a\u7070\u7d2b\u6587\u5b57\u8272\uff08\u9ed8\u8ba4 0x9A9AA5\uff09\u3002 | Muted blue-grey hint text (default 0x9A9AA5).").defineInRange("hintGray", 0x9A9AA5, 0, 0xFFFFFF);
        UI_GOLD_TEXT_RGB = b.comment("\u6309\u94ae\u60ac\u505c\u91d1\u8272\u6587\u5b57\u8272\uff08\u9ed8\u8ba4 0xFFE89A\uff09\u3002 | Hover gold button text (default 0xFFE89A).").defineInRange("goldText", 0xFFE89A, 0, 0xFFFFFF);
        UI_CHIP_BLUE_RGB = b.comment("\u9650\u4ef7\u5355\u65b9\u5411\u6807\u7b7e\u5e95\u8272\uff08\u9ed8\u8ba4 0x44506A\uff09\u3002 | Limit-order direction chip background (default 0x44506A).").defineInRange("chipBlue", 0x44506A, 0, 0xFFFFFF);
        UI_ACCENT_ORANGE_RGB = b.comment("\u7d27\u6025\u63d0\u9192\u6a59\u8272\u6587\u5b57\u8272\uff08\u9ed8\u8ba4 0xFFB060\uff09\u3002 | Urgent notice orange text (default 0xFFB060).").defineInRange("accentOrange", 0xFFB060, 0, 0xFFFFFF);
        UI_COIN_DARK_RGB = b.comment("\u77ff\u5e01\u56fe\u6807\u6697\u90e8\u8272\uff08\u9ed8\u8ba4 0xB8860B\uff09\u3002 | Coin icon dark tone (default 0xB8860B).").defineInRange("coinDark", 0xB8860B, 0, 0xFFFFFF);
        UI_COIN_LIGHT_RGB = b.comment("\u77ff\u5e01\u56fe\u6807\u4eae\u90e8\u8272\uff08\u9ed8\u8ba4 0xDAA520\uff09\u3002 | Coin icon light tone (default 0xDAA520).").defineInRange("coinLight", 0xDAA520, 0, 0xFFFFFF);
        b.pop();

        // ============================ category 21: screen feel ============================
        b.push("uiBehavior");
        b.comment("\u754c\u9762\u6eda\u52a8/\u62d6\u52a8/\u52a8\u753b\u624b\u611f\uff08\u5206\u7c7b\u4e8c\u5341\u4e00\uff09\uff0c\u5168\u90e8\u5ba2\u6237\u7aef\u5373\u65f6\u751f\u6548\u3002",
                "UI scroll / drag / animation feel (category 21), applied client-side instantly.");
        UI_SCROLL_STEP = b.comment("\u6eda\u8f6e\u4e00\u683c\u6eda\u52a8\u50cf\u7d20\uff08\u4e3b\u5217\u8868\uff0c\u9ed8\u8ba4 20\uff09\u3002\u8303\u56f4 2~100\u3002 | Wheel scroll step in px for main lists (default 20). Range 2~100.").defineInRange("scrollStep", 20, 2, 100);
        UI_SCROLL_STEP_FINE = b.comment("\u6eda\u8f6e\u4e00\u683c\u6eda\u52a8\u50cf\u7d20\uff08\u4e8b\u4ef6\u5361\u8be6\u60c5/\u9519\u8bef\u63d0\u793a/\u60ac\u6d6e\u5c0f\u7a97\uff0c\u9ed8\u8ba4 10\uff09\u3002\u8303\u56f4 2~50\u3002 | Fine wheel step (event detail, toast, tooltip; default 10). Range 2~50.").defineInRange("scrollStepFine", 10, 2, 50);
        UI_DRAG_THRESHOLD_PX = b.comment("\u6309\u4f4f\u62d6\u52a8\u5224\u5b9a\u9608\u503c\uff08\u50cf\u7d20\uff0c\u9ed8\u8ba4 5\uff09\uff1a\u6309\u4e0b\u540e\u4f4d\u79fb\u8d85\u8fc7\u8be5\u503c\u89c6\u4e3a\u62d6\u52a8\u3001\u4e0d\u89e6\u53d1\u70b9\u51fb\u3002\u8303\u56f4 0~50\u3002 | Press-drag threshold in px (default 5): moving beyond it drags instead of clicking. Range 0~50.").defineInRange("dragThresholdPx", 5, 0, 50);
        UI_SMOOTH_SCROLL = b.comment("\u53cc\u5411\u8054\u52a8\u662f\u5426\u7528\u5e73\u6ed1\u6eda\u52a8\u52a8\u753b\uff08\u9ed8\u8ba4 true\uff1bfalse = \u77ac\u95f4\u5b9a\u4f4d\uff09\u3002 | Use smooth linked-scroll animation (default true; false snaps instantly).").define("smoothScroll", true);
        UI_SCROLL_LERP_PERCENT = b.comment("\u5e73\u6ed1\u6eda\u52a8\u52a8\u753b\u6bcf\u5e27\u6536\u655b\u767e\u5206\u6bd4\uff08\u9ed8\u8ba4 15 = \u6bcf\u5e27\u79fb\u52a8\u5269\u4f59\u91cf\u7684 15%\uff09\u3002\u8303\u56f4 1~100\u3002 | Linked-scroll lerp percent per frame (default 15). Range 1~100.").defineInRange("scrollLerpPercent", 15, 1, 100);
        UI_TOAST_DURATION_MS = b.comment("\u9519\u8bef\u63d0\u793a\u505c\u7559\u65f6\u957f\uff08\u6beb\u79d2\uff0c\u9ed8\u8ba4 2000\uff09\u3002\u8303\u56f4 200~60000\u3002 | Error toast visible time in ms (default 2000). Range 200~60000.").defineInRange("toastDurationMs", 2000, 200, 60000);
        UI_TOAST_FADE_MS = b.comment("\u9519\u8bef\u63d0\u793a\u6de1\u51fa\u65f6\u957f\u4fdd\u7559\u4e3a\u914d\u7f6e\u517c\u5bb9\uff0c\u63d0\u793a\u6846\u5230\u65f6\u76f4\u63a5\u6d88\u5931\uff0c\u4e0d\u518d\u6267\u884c\u6de1\u51fa\u3002 | Retained for config compatibility; toast departure is always immediate and no fade-out is rendered.").defineInRange("toastFadeMs", 200, 0, 5000);
        UI_CARD_ANIM_MS = b.comment("\u4e8b\u4ef6\u5361\u6ed1\u5165/\u6de1\u51fa\u52a8\u753b\u65f6\u957f\uff08\u6beb\u79d2\uff0c\u9ed8\u8ba4 200\uff09\u3002\u8303\u56f4 0~5000\u3002 | Event card slide/fade animation in ms (default 200). Range 0~5000.").defineInRange("cardAnimMs", 200, 0, 5000);
        UI_CLICK_DEBOUNCE_MS = b.comment("\u6309\u94ae\u70b9\u51fb\u9632\u6296\u7a97\u53e3\uff08\u6beb\u79d2\uff0c\u9ed8\u8ba4 200\uff09\u3002\u8303\u56f4 0~5000\u3002 | Button click debounce window in ms (default 200). Range 0~5000.").defineInRange("clickDebounceMs", 200, 0, 5000);
        b.pop();

        // ============================ category 22: trade, event and chart safety settings ============================
        b.push("gameplayLimits");
        b.comment("\u4ea4\u6613\u3001\u4e8b\u4ef6\u4e0e K \u7ebf\u663e\u793a\u8bbe\u7f6e\uff08\u4e0d\u542b\u8d37\u6b3e\u989d\u5ea6\uff09\uff0c\u670d\u52a1\u7aef\u6743\u5a01\u5e76\u540c\u6b65\u7ed9\u5ba2\u6237\u7aef\u6821\u9a8c\u3002",
                "Trade, event and chart safety settings (category 22); these caps do not limit loan credit lines; values are server-authoritative and synced for client checks.");
        MAX_TRADE_QUANTITY = b.comment("\u5355\u7b14\u5e02\u4ef7/\u6302\u5355/\u9650\u4ef7/\u505a\u7a7a\u7684\u6700\u5927\u6570\u91cf\uff08\u9ed8\u8ba4 1000000\uff09\u3002\u8303\u56f4 1~100000000\u3002 | Max quantity per market/limit/short order (default 1000000). Range 1~100000000.").defineInRange("maxTradeQuantity", 1000000, 1, 100000000);
        LOG_MAX_ENTRIES = b.comment("\u6bcf\u73a9\u5bb6\u65e5\u5fd7\u6761\u6570\u4e0a\u9650\uff08\u9ed8\u8ba4 100\uff09\u3002\u8303\u56f4 10~1000\u3002 | Log entries kept per player (default 100). Range 10~1000.").defineInRange("logMaxEntries", 100, 10, 1000);
        EVENT_MAX_ACTIVE = b.comment("\u540c\u4e00\u65f6\u95f4\u6700\u591a\u751f\u6548\u4e8b\u4ef6\u6570\uff08\u9ed8\u8ba4 3\uff09\u3002\u8303\u56f4 1~10\u3002 | Max concurrently active events (default 3). Range 1~10.").defineInRange("eventMaxActive", 3, 1, 10);
        EVENT_SUSPEND_COOLDOWN_TICKS = b.comment("\u540c\u4e00\u80a1\u7968\u4e24\u6b21\u505c\u724c\u7684\u6700\u5c0f\u95f4\u9694\uff08marketTick \u6570\uff0c\u9ed8\u8ba4 200\uff09\u3002\u8303\u56f4 0~10000\u3002 | Minimum gap between suspensions of the same stock in marketTicks (default 200). Range 0~10000.").defineInRange("eventSuspendCooldownTicks", 200, 0, 10000);
        EVENT_DIVIDEND_COOLDOWN_DAYS = b.comment("\u5206\u7ea2\u4e8b\u4ef6\u5168\u5c40\u51b7\u5374\uff08\u4ea4\u6613\u65e5\uff0c\u9ed8\u8ba4 30\uff09\u3002\u8303\u56f4 0~365\u3002 | Global dividend-event cooldown in trading days (default 30). Range 0~365.").defineInRange("eventDividendCooldownDays", 30, 0, 365);
        EVENT_SPLIT_RATIO = b.comment("\u62c6\u80a1\u4e8b\u4ef6\u6bd4\u4f8b\uff08\u9ed8\u8ba4 2\uff1a\u80a1\u4ef7\u51cf\u534a\u3001\u6301\u4ed3\u7ffb\u500d\uff1b\u5fc5\u987b\u4e3a\u6574\u6570\uff09\u3002\u8303\u56f4 2~10\u3002 | Stock-split ratio (default 2: price halved, holdings doubled; integer only). Range 2~10.").defineInRange("eventSplitRatio", 2, 2, 10);
        KLINE_TICKS_PER_BAR = b.comment("\u4e00\u6839 K \u7ebf\u805a\u5408\u7684 marketTick \u6570\uff08\u9ed8\u8ba4 10\uff09\u3002\u8303\u56f4 1~200\u3002 | marketTicks aggregated into one candle (default 10). Range 1~200.").defineInRange("klineTicksPerBar", 10, 1, 200);
        KLINE_DISPLAY_COUNT = b.comment("\u884c\u60c5\u9875 K \u7ebf\u6700\u591a\u663e\u793a\u591a\u5c11\u6839\uff08\u9ed8\u8ba4 60\uff09\u3002\u53ea\u5f71\u54cd\u754c\u9762\u663e\u793a\uff0c\u4e0d\u6539\u53d8\u5b58\u6863\u4e0e\u4ea4\u6613\u903b\u8f91\u3002\u8303\u56f4 1~80\u3002 | Maximum candles shown on the quote chart (default 60). UI-only; does not change saved data or trading logic. Range 1~80.").defineInRange("klineDisplayCount", DEFAULT_KLINE_DISPLAY_COUNT, 1, 80);
        KLINE_DEFAULT_SHORT = b.comment("\u884c\u60c5\u9875\u6253\u5f00\u65f6\u662f\u5426\u9ed8\u8ba4\u663e\u793a\u77ed\u7ebf K \u7ebf\uff08\u9ed8\u8ba4 true\uff09\u3002true = \u77ed\u7ebf\uff0cfalse = \u65e5\u7ebf\uff1b\u53ea\u5f71\u54cd\u754c\u9762\u9996\u6b21\u521d\u59cb\u6a21\u5f0f\uff0c\u4e0d\u6539\u53d8\u884c\u60c5\u3001\u6210\u4ea4\u4e0e\u5b58\u6863\u6570\u636e\u3002 | Whether a newly opened quote screen starts with short-term candles (default true). true = short-term, false = daily; only affects the initial UI mode and never changes quotes, fills or saved data.").define("klineDefaultShort", true);
        KLINE_REAL_PLAYER_VOLUME_ONLY = b.comment("K \u7ebf\u6210\u4ea4\u91cf\u662f\u5426\u53ea\u663e\u793a\u771f\u5b9e\u73a9\u5bb6\u6210\u4ea4\uff08\u9ed8\u8ba4 false\uff09\u3002true = K \u7ebf\u6210\u4ea4\u91cf\u67f1\u3001\u60ac\u505c\u63d0\u793a\u4e0e\u660e\u7ec6\u6210\u4ea4\u91cf\u53ea\u7edf\u8ba1\u73a9\u5bb6\u4e3b\u52a8\u6210\u4ea4\uff08\u4e70\u5165\u5356\u51fa\u3001\u9650\u4ef7\u5355\u6210\u4ea4\u3001\u6302\u5355\u64ae\u5408\u6210\u4ea4\u3001\u505a\u7a7a\u5f00\u4ed3\u4e0e\u4e3b\u52a8\u5e73\u4ed3\uff09\uff1b\u4e8b\u4ef6\u5f3a\u5236\u4e70\u5356\u3001\u8d37\u6b3e\u5f3a\u5236\u5356\u51fa\u3001\u4ee5\u53ca\u7528\u4e8e\u8425\u9020\u5e02\u573a\u6c1b\u56f4\u7684\u9884\u6d4b\u6210\u4ea4\u91cf\u90fd\u4e0d\u8ba1\u5165\u663e\u793a\u3002\u4ef7\u683c\u3001\u4f59\u989d\u3001\u6301\u4ed3\u3001\u4e8b\u4ef6\u5224\u5b9a\u3001\u64ae\u5408\u4e0e\u5b58\u6863\u8bb0\u5f55\u5747\u4e0d\u53d7\u5f71\u54cd\u3002\u65e7\u5b58\u6863\u6ca1\u6709\u73a9\u5bb6\u6210\u4ea4\u660e\u7ec6\uff0c\u5f00\u542f\u540e\u663e\u793a 0\uff0c\u4e0d\u4f1a\u628a\u672a\u77e5\u6210\u4ea4\u91cf\u731c\u6d4b\u4e3a\u73a9\u5bb6\u6210\u4ea4\u3002 | Whether candlestick volume shows only real player trades (default false). When true, the volume bars, hover tooltip and detail volume count only player-initiated fills (market buys/sells, limit-order fills, order-book fills, short open and active close); event forced trades, forced loan liquidations and ambient predicted volume stay out of the display. Prices, balances, positions, event triggers, matching and saved records are untouched. Legacy candles have no player-volume detail and show 0 instead of a guessed value.").define("klineRealPlayerVolumeOnly", false);
        RARITY_WEIGHT_COMMON = b.comment("\u666e\u901a\u4e8b\u4ef6\u62bd\u53d6\u6743\u91cd\uff08\u9ed8\u8ba4 60\uff09\u3002\u8303\u56f4 0~1000\u3002 | Common event draw weight (default 60). Range 0~1000.").defineInRange("rarityWeightCommon", 60, 0, 1000);
        RARITY_WEIGHT_RARE = b.comment("\u7f55\u89c1\u4e8b\u4ef6\u62bd\u53d6\u6743\u91cd\uff08\u9ed8\u8ba4 25\uff09\u3002\u8303\u56f4 0~1000\u3002 | Rare event draw weight (default 25). Range 0~1000.").defineInRange("rarityWeightRare", 25, 0, 1000);
        RARITY_WEIGHT_EPIC = b.comment("\u53f2\u8bd7\u4e8b\u4ef6\u62bd\u53d6\u6743\u91cd\uff08\u9ed8\u8ba4 12\uff09\u3002\u8303\u56f4 0~1000\u3002 | Epic event draw weight (default 12). Range 0~1000.").defineInRange("rarityWeightEpic", 12, 0, 1000);
        RARITY_WEIGHT_LEGEND = b.comment("\u4f20\u5947\u4e8b\u4ef6\u62bd\u53d6\u6743\u91cd\uff08\u9ed8\u8ba4 3\uff09\u3002\u8303\u56f4 0~1000\u3002 | Legendary event draw weight (default 3). Range 0~1000.").defineInRange("rarityWeightLegend", 3, 0, 1000);
        b.pop();

        // ============================ category 12.5: debug log ============================
        b.push("debug");
        b.comment("Debug \u65e5\u5fd7\uff1a\u8d44\u91d1\u6d41\u6c34\u5feb\u7167\u4e0e\u5e76\u53d1\u62e6\u622a\u5ba1\u8ba1\u3002\u9ed8\u8ba4\u5168\u90e8\u5173\u95ed\uff0c\u5173\u95ed\u65f6\u96f6\u6027\u80fd\u6d88\u8017\uff08\u4e0d\u6267\u884c\u4efb\u4f55\u5b57\u7b26\u4e32\u62fc\u63a5\uff09\u3002 | Debug logging: fund-flow snapshots and concurrency intercept audit. Off by default; when off nothing is built or logged at all (zero-cost).");
        ENABLE_DEBUG_LOG = b.comment("Debug \u65e5\u5fd7\u603b\u5f00\u5173\u3002true = \u501f\u6b3e / \u505a\u7a7a / \u5f3a\u5e73\u6bcf\u4e2a\u8d44\u91d1\u53d8\u52a8\u8282\u70b9\u8bb0\u5f55\u524d\u540e\u4f59\u989d\u5feb\u7167\uff08\u65e5\u5fd7\u4e2d\u4f1a\u5e26 [T-xxxxxx] \u8ffd\u8e2a\u7f16\u53f7\uff0c\u8fd9\u4e2a\u65b9\u62ec\u53f7\u53ea\u662f\u8f93\u51fa\u6807\u8bb0\uff0c\u4e0d\u662f\u914d\u7f6e\u683c\u5f0f\uff09\uff1b \u4f59\u989d\u4e0d\u8db3\u3001\u6c60\u5b50\u5df2\u7a7a\u3001\u5e76\u53d1\u9501\u62d2\u7edd 100% \u8bb0\u5f55\u3002false\uff08\u9ed8\u8ba4\uff09= \u5b8c\u5168\u5173\u95ed\uff0c\u96f6\u6027\u80fd\u6d88\u8017\u3002\u5f00\u542f\u540e\u670d\u52a1\u7aef\u542f\u52a8\u4f1a\u6253\u5370\u9192\u76ee\u7684\u9ec4\u8272\u8b66\u544a\u3002 | Master switch. true = log before/after balance snapshots at every loan/short/liquidation node with a [T-xxxxxx] trace identifier; these brackets belong only to log output, not to the config format; insufficient-balance, empty-pool and lock rejections are logged 100%. false (default) = fully off, zero cost. Turning it on prints a loud yellow warning at server start.")
                .define("enableDebugLog", false);
        b.pop();

        SPEC = b.build();
    }

    private OreMarketConfig() {
    }
}
