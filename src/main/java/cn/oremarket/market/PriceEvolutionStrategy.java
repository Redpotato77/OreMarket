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

import cn.oremarket.common.StockState;
import cn.oremarket.market.event.MarketModifiers;
import net.minecraft.util.RandomSource;

/**
 * Pure price-evolution rules extracted from {@link MarketEngine}.
 *
 * <p>This class owns the numerical part of one quote update: event-adjusted movement,
 * long-run floor and ceiling handling, one-tick limits, and bounded auction relief.
 * It deliberately has no network, persistence, or player-facing responsibilities.</p>
 */
final class PriceEvolutionStrategy {

    private static final int LIMIT_PATTERN_SEALED = 0;
    private static final int LIMIT_PATTERN_VOLATILE = 1;
    private static final int LIMIT_PATTERN_ALTERNATING = 2;
    private static final int LIMIT_PATTERN_MIXED = 3;
    private static final int LIMIT_PATTERN_COUNT = 4;
    private static final int VOLATILE_RELEASE_STREAK = 3;
    private static final int RELIEF_RELEASE_STREAK = 2;
    private static final double MIXED_RELEASE_CHANCE = 0.40;
    private static final double PRICE_EPSILON = 1.0e-9;
    private static final double MIN_SAFE_LIMIT = 0.001;
    private static final double MAX_SAFE_LIMIT = 0.50;
    private static final double MIN_PRICE = 0.01;
    private static final double MAX_INWARD_BAND = 0.02;
    private static final double MIN_INWARD_BAND = 0.0025;
    private static final double INWARD_BAND_FACTOR = 0.20;
    private static final double INWARD_BASE = 0.55;
    private static final double INWARD_RANGE = 0.45;
    private static final double RELIEF_STEP_FRACTION = 0.0025;
    /** Stable FNV-1a parameters used to derive a per-code, per-direction limit-board pattern. */
    private static final int LIMIT_HASH_OFFSET_BASIS = 0x811C9DC5;
    private static final int LIMIT_HASH_PRIME = 0x01000193;
    private static final int LIMIT_HASH_UP_SALT = 0x13579BDF;
    private static final int LIMIT_HASH_DOWN_SALT = 0x2468ACE0;

    private PriceEvolutionStrategy() {
    }

    /**
     * Evolves one stock for one market tick without touching settlement or persistence structures.
     * The operation order is intentionally identical to the original market engine path.
     */
    static double evolvePrice(MarketSavedData data, Stock stock, double previousTickPrice,
                              MarketSettings settings, MarketModifiers modifiers, RandomSource random,
                              double sharedNoise) {
        StockState state = data.state(stock);
        if (state == null) {
            return previousTickPrice;
        }
        MarketModifiers.Per priceModifier = modifiers.get(stock.code());
        double randomNoise = (random.nextDouble() * 2 - 1)
                * settings.randomFluctuation * priceModifier.volMult;
        int effectiveTrend = priceModifier.trendForce != 0
                ? priceModifier.trendForce : state.trend;
        double price = state.price * (1.0 + randomNoise + sharedNoise
                + effectiveTrend * settings.trendImpact);
        double anchor = data.anchorBase(stock);
        if (anchor > 0.0 && Double.isFinite(anchor)) {
            double distance = Math.abs(price - anchor) / anchor;
            if (settings.reversionDeadZoneFraction >= 1.0
                    || distance <= settings.reversionDeadZoneFraction) {
                price += (anchor - price) * priceModifier.reversion;
            }
        } else {
            price += (stock.basePrice() - price) * priceModifier.reversion;
        }
        price *= priceModifier.priceMult;

        double rawPrice = price;
        double bandBase = anchor > 0.0 && Double.isFinite(anchor) ? anchor : stock.basePrice();
        double floor = settings.priceFloor(bandBase);
        double ceiling = settings.priceCeiling(bandBase);
        double desired = data.clampPrice(stock, rawPrice);
        double upperLimit = previousTickPrice * (1.0 + priceModifier.limitPct);
        double lowerLimit = previousTickPrice * (1.0 - priceModifier.limitPct);
        int pressureDirection = rawPrice > upperLimit * (1.0 + PRICE_EPSILON) ? 1
                : rawPrice < lowerLimit * (1.0 - PRICE_EPSILON) ? -1 : 0;
        int pattern = limitPattern(stock.code(), pressureDirection);
        desired = boundaryQuote(desired, rawPrice, previousTickPrice, floor, ceiling,
                priceModifier.limitPct, random.nextDouble());
        double limitedPrice = MarketSavedData.applyPriceLimit(desired, previousTickPrice,
                priceModifier.limitPct);

        int pressureDirectionForStreak = pressureDirection;
        int pressureStreak = state.noteLimitHit(pressureDirectionForStreak);
        if (pressureDirectionForStreak == 0) {
            // noteLimitHit(0) clears the transient episode.
        } else if (pattern == LIMIT_PATTERN_SEALED) {
            limitedPrice = sealedLimitQuote(limitedPrice, previousTickPrice,
                    priceModifier.limitPct, pressureDirectionForStreak, floor, ceiling);
        } else if (pattern == LIMIT_PATTERN_VOLATILE && pressureStreak >= VOLATILE_RELEASE_STREAK) {
            limitedPrice = auctionRelief(limitedPrice, previousTickPrice,
                    priceModifier.limitPct, pressureDirectionForStreak);
            state.clearLimitHitStreak();
        } else if (pattern == LIMIT_PATTERN_ALTERNATING
                && pressureStreak >= RELIEF_RELEASE_STREAK && (pressureStreak & 1) == 0) {
            limitedPrice = auctionRelief(limitedPrice, previousTickPrice,
                    priceModifier.limitPct, pressureDirectionForStreak);
            state.clearLimitHitStreak();
        } else if (pattern == LIMIT_PATTERN_MIXED
                && pressureStreak >= RELIEF_RELEASE_STREAK
                && random.nextDouble() < MIXED_RELEASE_CHANCE) {
            limitedPrice = auctionRelief(limitedPrice, previousTickPrice,
                    priceModifier.limitPct, pressureDirectionForStreak);
            state.clearLimitHitStreak();
        }
        return Math.max(MIN_PRICE, limitedPrice);
    }

    /** Maps a stock and pressure direction to a stable limit-episode pattern. */
    static int limitPattern(String code, int direction) {
        if (direction == 0) {
            return LIMIT_PATTERN_MIXED;
        }
        int hash = LIMIT_HASH_OFFSET_BASIS;
        String safeCode = code == null ? "" : code;
        for (int characterIndex = 0; characterIndex < safeCode.length(); characterIndex++) {
            hash ^= safeCode.charAt(characterIndex);
            hash *= LIMIT_HASH_PRIME;
        }
        hash ^= direction > 0 ? LIMIT_HASH_UP_SALT : LIMIT_HASH_DOWN_SALT;
        return Math.floorMod(hash, LIMIT_PATTERN_COUNT);
    }

    /** Keeps a sealed board at the legal edge without crossing either configured price band. */
    static double sealedLimitQuote(double limitedPrice, double previousPrice, double limit,
                                   int direction, double floor, double ceiling) {
        if (!Double.isFinite(previousPrice) || previousPrice <= 0.0 || !Double.isFinite(limit)
                || !Double.isFinite(floor) || !Double.isFinite(ceiling) || ceiling < floor) {
            return limitedPrice;
        }
        double safeLimit = safeLimit(limit);
        double edge = direction > 0 ? previousPrice * (1.0 + safeLimit)
                : previousPrice * (1.0 - safeLimit);
        return Math.max(floor, Math.min(ceiling, edge));
    }

    /** Replaces a hard long-run boundary quote with a bounded randomized auction approach. */
    static double boundaryQuote(double desired, double raw, double previous, double floor,
                                double ceiling, double limit, double noise) {
        if (!Double.isFinite(desired) || !Double.isFinite(raw) || !Double.isFinite(previous)
                || !Double.isFinite(floor) || !Double.isFinite(ceiling) || !Double.isFinite(limit)
                || previous <= 0.0 || floor <= 0.0 || ceiling < floor) {
            return desired;
        }
        double safeLimit = safeLimit(limit);
        double normalizedNoise = Math.max(0.0, Math.min(1.0, noise));
        double inwardBand = Math.min(MAX_INWARD_BAND,
                Math.max(MIN_INWARD_BAND, safeLimit * INWARD_BAND_FACTOR));
        double inward = inwardBand * (INWARD_BASE + INWARD_RANGE * normalizedNoise);
        if (raw > ceiling * (1.0 + PRICE_EPSILON)) {
            if (previous * (1.0 + safeLimit) >= ceiling * (1.0 - PRICE_EPSILON)) {
                return ceiling;
            }
            double target = ceiling * (1.0 - inward);
            return Math.min(target, previous * (1.0 + safeLimit));
        }
        if (raw < floor * (1.0 - PRICE_EPSILON)) {
            if (previous * (1.0 - safeLimit) <= floor * (1.0 + PRICE_EPSILON)) {
                return floor;
            }
            double target = floor * (1.0 + inward);
            return Math.max(target, previous * (1.0 - safeLimit));
        }
        return desired;
    }

    /** Creates a small inward step only when the previous quote was already pinned to an edge. */
    static double boundaryAuctionRelief(double limitedPrice, double previousPrice, double floor,
                                        double ceiling, double limit, int direction) {
        if (!Double.isFinite(limitedPrice) || !Double.isFinite(previousPrice)
                || !Double.isFinite(floor) || !Double.isFinite(ceiling) || !Double.isFinite(limit)
                || previousPrice <= 0.0 || floor <= 0.0 || ceiling < floor || direction == 0) {
            return limitedPrice;
        }
        double safeLimit = safeLimit(limit);
        double boundary = direction > 0 ? ceiling : floor;
        double step = Math.min(boundary * RELIEF_STEP_FRACTION, previousPrice * safeLimit);
        if (direction > 0) {
            if (previousPrice < ceiling * (1.0 - PRICE_EPSILON)
                    || limitedPrice < ceiling * (1.0 - PRICE_EPSILON)) {
                return limitedPrice;
            }
            return Math.max(floor, Math.min(ceiling, ceiling - step));
        }
        if (previousPrice > floor * (1.0 + PRICE_EPSILON)
                || limitedPrice > floor * (1.0 + PRICE_EPSILON)) {
            return limitedPrice;
        }
        return Math.min(ceiling, Math.max(floor, floor + step));
    }

    /** Pulls a repeatedly clipped quote back by a bounded microstructure step. */
    static double auctionRelief(double limitedPrice, double previousPrice, double limit, int direction) {
        if (!(previousPrice > 0.0) || !Double.isFinite(previousPrice)
                || !Double.isFinite(limitedPrice)) {
            return limitedPrice;
        }
        double safeLimit = safeLimit(limit);
        double edge = direction > 0 ? previousPrice * (1.0 + safeLimit)
                : previousPrice * (1.0 - safeLimit);
        double step = Math.min(previousPrice * RELIEF_STEP_FRACTION, previousPrice * safeLimit);
        if (direction > 0) {
            return Math.max(previousPrice, Math.min(edge, limitedPrice - step));
        }
        return Math.min(previousPrice, Math.max(edge, limitedPrice + step));
    }

    private static double safeLimit(double limit) {
        return Math.max(MIN_SAFE_LIMIT, Math.min(MAX_SAFE_LIMIT, limit));
    }
}
