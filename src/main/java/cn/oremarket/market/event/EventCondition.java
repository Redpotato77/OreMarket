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

import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.MarketSettings;
import cn.oremarket.market.Stock;

import java.util.Map;
import java.util.Locale;

/**
 * Event trigger condition: unmet means the template cannot enter the random pool (requirements 5 and 6).
 * Conditions and rarity weights are computed independently - the engine filters by condition first, then draws from the survivors by weight.
 * A few conditions carry a STOCK dimension (breaking below the base price, excessive holdings); on a hit they hand that stock to the template as the target.
 */
public final class EventCondition {

    public enum Kind {
        /** Whole-server ore-coin supply above the threshold (keeps late-game overheating in check). */
        COIN_TOTAL_ABOVE,
        /** Whole-server ore-coin supply below the threshold (keeps early-game collapse in check). */
        COIN_TOTAL_BELOW,
        /** Some stock trades more than the given ratio ABOVE its base price. */
        PRICE_DEV_ABOVE,
        /** Some stock trades more than the given ratio BELOW its base price (e.g. 'bargain hunters enter' only appears after a 50% breakdown). */
        PRICE_DEV_BELOW,
        /** One stock's whole-server total holdings exceed the given share count (e.g. overweight holdings trigger 'Profit Taking'). */
        HOLDING_ABOVE,
        /** True only within the first N marketTicks of each trading day's start (e.g. opening bell quotes). */
        TRADE_DAY_WINDOW,
        /** Some player reached the given level (e.g. 'Netherite Race' only exists after netherite unlocks at level 4). */
        PLAYER_TIER
    }

    public final Kind kind;
    /** Ratio / threshold parameters (used per the actual semantics). */
    public final double threshold;
    /** Integer parameters such as tick counts / levels / share counts. */
    public final int intValue;
    /** Scoped to one stock code; null = any stock qualifies (comparison is always by code string). */
    public final String stockCode;

    public EventCondition(Kind kind, double threshold, int intValue, String stockCode) {
        this.kind = kind;
        this.threshold = threshold;
        this.intValue = intValue;
        this.stockCode = stockCode;
    }

    public static EventCondition coinAbove(long amount) {
        return new EventCondition(Kind.COIN_TOTAL_ABOVE, amount, 0, null);
    }

    public static EventCondition coinBelow(long amount) {
        return new EventCondition(Kind.COIN_TOTAL_BELOW, amount, 0, null);
    }

    /** Any stock broke below base by ratio (0.5 = lost half its value). */
    public static EventCondition priceDrop(double ratio) {
        return new EventCondition(Kind.PRICE_DEV_BELOW, ratio, 0, null);
    }

    /** Any stock rose above base by ratio. */
    public static EventCondition priceRally(double ratio) {
        return new EventCondition(Kind.PRICE_DEV_ABOVE, ratio, 0, null);
    }

    /** Any stock's whole-server total holdings exceed shares. */
    public static EventCondition holdingAbove(long shares) {
        return new EventCondition(Kind.HOLDING_ABOVE, shares, 0, null);
    }

    /** True within the first window marketTicks of each trading day's start. */
    public static EventCondition tradeDayOpen(int window) {
        return new EventCondition(Kind.TRADE_DAY_WINDOW, 0, window, null);
    }

    /** Some player reached tier. */
    public static EventCondition playerTier(int tier) {
        return new EventCondition(Kind.PLAYER_TIER, 0, tier, null);
    }

    /** Condition hit result: code is meaningless when met=false; code is the stock code string that was hit. */
    public static final class Result {
        public final boolean met;
        public final String code;

        Result(boolean met, String code) {
            this.met = met;
            this.code = code;
        }
    }

    private static final Result FAIL = new Result(false, null);

    /** Evaluate. When a stock-dimension condition hits, the matched stock comes back for the template's target choice. */
    public Result test(MarketSavedData data) {
        switch (kind) {
            case COIN_TOTAL_ABOVE: {
                long total = totalCoins(data);
                return total > (long) threshold ? new Result(true, null) : FAIL;
            }
            case COIN_TOTAL_BELOW: {
                long total = totalCoins(data);
                return total < (long) threshold ? new Result(true, null) : FAIL;
            }
            case PRICE_DEV_BELOW: {
                if (stockCode != null) {
                    return deviated(data, stockCode, true) ? new Result(true, stockCode) : FAIL;
                }
                for (Stock s : cn.oremarket.market.StockRegistry.getActiveStocks()) {
                    if (deviated(data, s.code(), true)) {
                        return new Result(true, s.code());
                    }
                }
                return FAIL;
            }
            case PRICE_DEV_ABOVE: {
                if (stockCode != null) {
                    return deviated(data, stockCode, false) ? new Result(true, stockCode) : FAIL;
                }
                for (Stock s : cn.oremarket.market.StockRegistry.getActiveStocks()) {
                    if (deviated(data, s.code(), false)) {
                        return new Result(true, s.code());
                    }
                }
                return FAIL;
            }
            case HOLDING_ABOVE: {
                if (stockCode != null) {
                    return serverShares(data, stockCode) > (long) threshold
                            ? new Result(true, stockCode) : FAIL;
                }
                for (Stock s : cn.oremarket.market.StockRegistry.getActiveStocks()) {
                    if (serverShares(data, s.code()) > (long) threshold) {
                        return new Result(true, s.code());
                    }
                }
                return FAIL;
            }
            case TRADE_DAY_WINDOW: {
                long perDay = Math.max(1, MarketSettings.current().marketTicksPerTradeDay());
                long day = Math.floorMod(data.marketTicks - 1, perDay);
                return day < Math.max(1, intValue) ? new Result(true, null) : FAIL;
            }
            case PLAYER_TIER: {
                return maxServerTier(data) >= Math.max(1, intValue) ? new Result(true, null) : FAIL;
            }
            default:
                return FAIL;
        }
    }

    private boolean deviated(MarketSavedData data, String code, boolean below) {
        Stock s = cn.oremarket.market.StockRegistry.getByCode(code);
        cn.oremarket.common.StockState st = data.state(code);
        if (s == null || st == null) {
            return false;                       // Delisted stocks and stocks without data never participate in condition evaluation
        }
        double base = s.basePrice();
        if (base <= 0) {
            return false;
        }
        double dev = st.price / base - 1.0;
        return below ? dev < -Math.abs(threshold) : dev > Math.abs(threshold);
    }

    private static long totalCoins(MarketSavedData data) {
        long total = 0;
        for (long v : data.balances.values()) {
            total += v;
        }
        return total;
    }

    /** Whole-server total holdings = every player's sellable + frozen. */
    private static long serverShares(MarketSavedData data, String code) {
        long total = 0;
        for (Map<String, Integer> map : data.holdings.values()) {
            total += map.getOrDefault(code, 0);
        }
        for (Map<String, Integer> map : data.frozen.values()) {
            total += map.getOrDefault(code, 0);
        }
        return total;
    }

    /**
     * Whole-server highest level: taken from the ALL-TIME highestLevel (offline players included);
     * the realtime level is for judgement only, not for this permission figure.
     */
    private static int maxServerTier(MarketSavedData data) {
        int tier = 1;
        for (java.util.UUID id : data.peakBalances.keySet()) {
            tier = Math.max(tier, data.playerTier(id));
        }
        for (java.util.UUID id : data.highestLevels.keySet()) {
            tier = Math.max(tier, data.playerTier(id));
        }
        return tier;
    }

    /** Condition description shown to UI / commands (the whole-sentence template goes through a language key so translations hit whole-sentence). */
    public String describe() {
        return switch (kind) {
            case COIN_TOTAL_ABOVE -> cn.oremarket.common.RawText.f("oremarket.event.cond.coin_above", (long) threshold);
            case COIN_TOTAL_BELOW -> cn.oremarket.common.RawText.f("oremarket.event.cond.coin_below", (long) threshold);
            case PRICE_DEV_ABOVE -> cn.oremarket.common.RawText.f("oremarket.event.cond.price_above", pct(threshold));
            case PRICE_DEV_BELOW -> cn.oremarket.common.RawText.f("oremarket.event.cond.price_below", pct(threshold));
            case HOLDING_ABOVE -> cn.oremarket.common.RawText.f("oremarket.event.cond.holding_above", (long) threshold);
            case TRADE_DAY_WINDOW -> cn.oremarket.common.RawText.f("oremarket.event.cond.tick_before", intValue);
            case PLAYER_TIER -> cn.oremarket.common.RawText.f("oremarket.event.cond.player_tier", intValue);
        };
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.0f%%", Math.abs(v) * 100);
    }
}