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

package cn.oremarket.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Custom event parser (common layer: JDK-only, unit-testable outside the game).
 *
 * Config entry format: name|rarity|target stock codes|effect modules|duration seconds|description
 * Example: MY_EVENT|rare|COAL,IRON|price:1.2,volatility:1.5|60|An accident hit a mine
 *
 * Validation rules (any failure -> the entry is skipped with a reason for the caller's WARN):
 * - empty after trim: silently skipped (SKIPPED_BLANK);
 * - pipe-split fields must be exactly 6;
 * - event name 2~16 characters and must not duplicate a built-in name (DUPLICATE_NAME);
 * - rarity is one of {common, rare, epic, legend};
 * - all target stock codes must exist in the given registry (validCodes passed in by the caller; the parser touches no MC classes);
 * - effect module types and parameter ranges: price 0.5~1.5, volatility 0.2~3.0, trend up|down,
 * meanReversion 0.0~0.15, fee 0.0~0.10, tradeImpact 0.5~3.0, priceLimit 5~25, halt takes no parameter;
 * - duration 10~600 seconds; descriptions longer than 100 characters are truncated;
 * - total cap {@link #MAX_CUSTOM_EVENTS}; entries beyond it are skipped (CAP_REACHED).
 */
public final class CustomEventParser {

    /** Cap on the number of custom events (excess entries are skipped with a warning, so the event pool cannot bloat forever). */
    public static final int MAX_CUSTOM_EVENTS = 50;

    // Named constants for field indices (convention: bare parts[1] usage is banned)
    public static final int FIELD_NAME = 0;
    public static final int FIELD_RARITY = 1;
    public static final int FIELD_TARGETS = 2;
    public static final int FIELD_EFFECTS = 3;
    public static final int FIELD_DURATION = 4;
    public static final int FIELD_DESC = 5;
    public static final int FIELD_COUNT = 6;

    /** Max description length (longer is truncated). */
    public static final int DESC_MAX_CHARS = 100;
    /** Event name length lower / upper bound. */
    public static final int NAME_MIN_CHARS = 2;
    public static final int NAME_MAX_CHARS = 16;

    /** Effect module type names (the spelling used in the config, one-to-one with EffectType). */
    public static final String EFFECT_PRICE = "price";
    public static final String EFFECT_VOLATILITY = "volatility";
    public static final String EFFECT_TREND = "trend";
    public static final String EFFECT_MEAN_REVERSION = "meanReversion";
    public static final String EFFECT_FEE = "fee";
    public static final String EFFECT_TRADE_IMPACT = "tradeImpact";
    public static final String EFFECT_PRICE_LIMIT = "priceLimit";
    public static final String EFFECT_HALT = "halt";

    /** Parser result status. */
    public enum Status {
        /** Parse succeeded. */
        OK,
        /** Empty after trim: silently skipped. */
        SKIPPED_BLANK,
        /** Field count is not 6. */
        BAD_FIELD_COUNT,
        /** Event name length out of bounds. */
        BAD_NAME,
        /** Duplicates a built-in event name. */
        DUPLICATE_NAME,
        /** Invalid rarity. */
        BAD_RARITY,
        /** A target stock code is missing from the registry / the list is empty. */
        BAD_TARGET,
        /** Invalid effect module type or parameter. */
        BAD_EFFECT,
        /** Duration seconds out of range. */
        BAD_DURATION,
        /** Custom event count cap already reached. */
        CAP_REACHED
    }

    /** One parse result: event is null when status != OK; reason is for the log. */
    public static final class Result {
        public final Status status;
        public final ParsedEvent event;
        public final String reason;

        Result(Status status, ParsedEvent event, String reason) {
            this.status = status;
            this.event = event;
            this.reason = reason;
        }
    }

    /** The successfully parsed custom event (plain data; conversion to EventTemplate goes through the market-layer bridge). */
    public static final class ParsedEvent {
        public final String name;
        public final String rarityKey;
        public final Rarity rarity;
        public final List<String> codes;
        public final List<EffectSpec> effects;
        public final int durationSeconds;
        public final String description;

        ParsedEvent(String name, String rarityKey, Rarity rarity, List<String> codes,
                    List<EffectSpec> effects, int durationSeconds, String description) {
            this.name = name;
            this.rarityKey = rarityKey;
            this.rarity = rarity;
            this.codes = List.copyOf(codes);
            this.effects = List.copyOf(effects);
            this.durationSeconds = durationSeconds;
            this.description = description;
        }
    }

    private CustomEventParser() {
    }

    /** Rarity config value -> enum; null when invalid. */
    public static Rarity rarityOf(String key) {
        if (key == null) {
            return null;
        }
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "common" -> Rarity.COMMON;
            case "rare" -> Rarity.RARE;
            case "epic" -> Rarity.EPIC;
            case "legend" -> Rarity.LEGENDARY;
            default -> null;
        };
    }

    /**
     * Parse the whole config list.
     *
     * @param lines        raw config entries (null / blank lines are silently skipped)
     * @param validCodes   active stock codes of the current registry
     * @param reservedNames built-in event names (duplicates are not allowed)
     */
    public static List<Result> parseAll(List<String> lines, Set<String> validCodes,
                                        Set<String> reservedNames) {
        List<Result> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        int accepted = 0;
        Set<String> seenNames = new LinkedHashSet<>();
        for (String line : lines) {
            Result r = parse(line, validCodes, reservedNames);
            if (r.status == Status.SKIPPED_BLANK) {
                continue;
            }
            if (r.status == Status.OK && !seenNames.add(r.event.name)) {
                // Duplicate names within one batch: customId is hashed from the name, so two identical names would overwrite each other's template entry - rejected outright
                out.add(new Result(Status.DUPLICATE_NAME, null,
                        "duplicate custom event name within config: " + r.event.name));
                continue;
            }
            if (r.status == Status.OK) {
                if (accepted >= MAX_CUSTOM_EVENTS) {
                    out.add(new Result(Status.CAP_REACHED, null,
                            "custom event cap " + MAX_CUSTOM_EVENTS + " reached, skipped: " + line));
                    continue;
                }
                accepted++;
            }
            out.add(r);
        }
        return out;
    }

    /** Parse one config entry. */
    public static Result parse(String line, Set<String> validCodes, Set<String> reservedNames) {
        if (line == null || line.trim().isEmpty()) {
            return new Result(Status.SKIPPED_BLANK, null, "blank entry");
        }
        String[] parts = line.split("\\|", -1);
        if (parts.length != FIELD_COUNT) {
            return fail(Status.BAD_FIELD_COUNT, "expected " + FIELD_COUNT
                    + " pipe-separated fields, got " + parts.length + ": " + line);
        }
        String name = parts[FIELD_NAME].trim();
        if (name.length() < NAME_MIN_CHARS || name.length() > NAME_MAX_CHARS) {
            return fail(Status.BAD_NAME, "event name must be " + NAME_MIN_CHARS + "~"
                    + NAME_MAX_CHARS + " chars: " + name);
        }
        if (reservedNames != null && reservedNames.contains(name)) {
            return fail(Status.DUPLICATE_NAME, "custom event name clashes with a built-in event: " + name);
        }
        String rarityKey = parts[FIELD_RARITY].trim().toLowerCase(Locale.ROOT);
        Rarity rarity = rarityOf(rarityKey);
        if (rarity == null) {
            return fail(Status.BAD_RARITY, "rarity must be common/rare/epic/legend, got: "
                    + parts[FIELD_RARITY].trim());
        }
        List<String> codes = new ArrayList<>();
        for (String code : parts[FIELD_TARGETS].split(",")) {
            String c = code.trim().toUpperCase(Locale.ROOT);
            if (c.isEmpty()) {
                continue;
            }
            if (validCodes == null || !validCodes.contains(c)) {
                return fail(Status.BAD_TARGET, "unknown stock code: " + c);
            }
            codes.add(c);
        }
        if (codes.isEmpty()) {
            return fail(Status.BAD_TARGET, "no valid stock target in: " + parts[FIELD_TARGETS]);
        }
        List<EffectSpec> effects = new ArrayList<>();
        for (String raw : parts[FIELD_EFFECTS].split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            EffectSpec spec = parseEffect(token);
            if (spec == null) {
                return fail(Status.BAD_EFFECT, "invalid effect module: " + token);
            }
            effects.add(spec);
        }
        int duration;
        try {
            duration = Integer.parseInt(parts[FIELD_DURATION].trim());
        } catch (NumberFormatException e) {
            return fail(Status.BAD_DURATION, "duration must be an integer seconds value: "
                    + parts[FIELD_DURATION].trim());
        }
        if (duration < 10 || duration > 600) {
            return fail(Status.BAD_DURATION, "duration seconds out of range 10~600: " + duration);
        }
        String desc = parts[FIELD_DESC].trim();
        if (desc.length() > DESC_MAX_CHARS) {
            desc = desc.substring(0, DESC_MAX_CHARS);
        }
        return new Result(Status.OK,
                new ParsedEvent(name, rarityKey, rarity, codes, effects, duration, desc), null);
    }

    /** One effect module token ("type:param" or "halt") -> EffectSpec; null when invalid. */
    static EffectSpec parseEffect(String token) {
        int colon = token.indexOf(':');
        String type = (colon < 0 ? token : token.substring(0, colon)).trim();
        String value = colon < 0 ? "" : token.substring(colon + 1).trim();
        switch (type) {
            case EFFECT_HALT:
                if (!value.isEmpty()) {
                    return null;
                }
                return EffectSpec.suspend(10, 60);
            case EFFECT_PRICE:
                return inRange(value, 0.5, 1.5, v -> EffectSpec.price(v, v));
            case EFFECT_VOLATILITY:
                return inRange(value, 0.2, 3.0, v -> EffectSpec.volatility(v, v));
            case EFFECT_TREND:
                if ("up".equalsIgnoreCase(value)) {
                    return EffectSpec.trend(1);
                }
                if ("down".equalsIgnoreCase(value)) {
                    return EffectSpec.trend(-1);
                }
                return null;
            case EFFECT_MEAN_REVERSION:
                return inRange(value, 0.0, 0.15, v -> EffectSpec.reversion(v, v));
            case EFFECT_FEE:
                return inRange(value, 0.0, 0.10, v -> EffectSpec.feeSet(v));
            case EFFECT_TRADE_IMPACT:
                return inRange(value, 0.5, 3.0, v -> EffectSpec.shock(v, v));
            case EFFECT_PRICE_LIMIT:
                // The config spells priceLimit as a percentage (5~25); it is stored internally as a decimal
                return inRange(value, 5.0, 25.0, v -> EffectSpec.limit(v / 100.0, v / 100.0));
            default:
                return null;
        }
    }

    private interface SpecFactory {
        EffectSpec make(double v);
    }

    private static EffectSpec inRange(String value, double lo, double hi, SpecFactory factory) {
        double v;
        try {
            v = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
        if (Double.isNaN(v) || Double.isInfinite(v) || v < lo || v > hi) {
            return null;
        }
        return factory.make(v);
    }

    private static Result fail(Status status, String reason) {
        return new Result(status, null, reason);
    }

    /** Code set used for event-pool deduplication (order-preserving dedup). */
    public static Set<String> dedupe(List<String> codes) {
        return new LinkedHashSet<>(codes);
    }
}
