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
import cn.oremarket.common.Rarity;
import cn.oremarket.market.ActiveEvent;
import cn.oremarket.market.MarketSettings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Event view shipped to the client: pure display data (name, rarity, effect shorthand, remaining ticks, affected scope).
 * Real event effects are computed server-side only; what the client holds is ready-made text and numbers.
 *
 * - affected scope moved from a bit mask to a LIST OF STOCK CODE STRINGS;
 * - effect module details support the multi-line tooltip (type + parameter + remaining/total duration, up to 8);
 * - added the bullish/bearish tone (progress-bar color), the legendary-preview flag, and contributor names for player-triggered events.
 */
public final class MarketEventView {
    /** Length cap on each text field; defends against absurdly long strings. */
    private static final int MAX_TEXT = 96;
    /** Maximum encoded length of one stock code. */
    private static final int MAX_CODE_TEXT = 16;
    /** Cap on the number of event targets (defends against abnormal data; normally never above the total active stock count). */
    private static final int MAX_TARGETS = 64;
    /** Cap on the effect-module detail entries shipped (defensive). */
    public static final int MAX_MODULES = 8;
    /** Defensive bound for a realized value received from a client-side snapshot. */
    private static final double MAX_REALIZED_VALUE = 1.0e18;
    /** Border color for a preview card without a real event effect. */
    private static final int PREVIEW_BORDER_COLOR = 0xFF666666;
    /** Neutral / mixed tone stays grey and never flips. */
    private static final int NEUTRAL_TONE_COLOR = 0xFFAAAAAA;

    /** Progress-bar / tone: bullish (the scheme's rising colour) / bearish (its falling colour) / mixed or neutral (gray). */
    public static final int TONE_NEUTRAL = 0;
    public static final int TONE_BULL = 1;
    public static final int TONE_BEAR = 2;

    /** Display data for one effect module (rendered item by item on line 5 of the multi-line tooltip). */
    public static final class ModuleView {
        /** EffectType.ordinal. */
        public final int type;
        public final double param;
        public final int direction;
        public final int ticksLeft;
        public final int duration;
        /** Actual one-shot effect value, or NaN for timed/legacy modules. */
        public final double realizedValue;

        public ModuleView(int type, double param, int direction, int ticksLeft, int duration) {
            this(type, param, direction, ticksLeft, duration, Double.NaN);
        }

        public ModuleView(int type, double param, int direction, int ticksLeft, int duration,
                          double realizedValue) {
            this.type = type;
            this.param = param;
            this.direction = direction;
            this.ticksLeft = ticksLeft;
            this.duration = duration;
            this.realizedValue = realizedValue;
        }
    }

    public final String name;
    public final String description;
    public final String effectText;
    /**
     * Name / description translation keys (e.g. oremarket.text.0569). An empty string means a custom event or a legacy packet;
     * the client fetches the current-language template by key and fills {stock} - English UIs no longer reverse-look-up Chinese sentences.
     */
    public final String nameKey;
    public final String descKey;
    /** Target display-name string ("Coal/Iron Ingot" or "All market"); pure display. */
    public final String targets;
    /** List of target stock codes (affected scope is judged by code, not a bit mask). */
    public final List<String> targetCodes;
    public final int rarity;
    public final int ticksLeft;
    public final int duration;
    /** Whether the event contains a halt module (extra hint outside the UI border). */
    public final boolean suspendEvent;
    public final int uid;
    /** Effect module details (line 5 of the tooltip). */
    public final List<ModuleView> modules;
    /** Bullish/bearish tone (progress-bar color). */
    public final int tone;
    /** Legendary preview display card (gray 0xFF666666, no real effect). */
    public final boolean preview;
    /** Contributor display names of a player-action-triggered event ("Player1, Player2, Player3"); empty string otherwise. */
    public final String contributors;

    public MarketEventView(String name, String description, String effectText, String targets,
                           List<String> targetCodes, int rarity, int ticksLeft, int duration,
                           boolean suspendEvent, int uid, List<ModuleView> modules, int tone,
                           boolean preview, String contributors) {
        this(name, description, effectText, "", "", targets, targetCodes, rarity, ticksLeft, duration,
                suspendEvent, uid, modules, tone, preview, contributors);
    }

    public MarketEventView(String name, String description, String effectText,
                           String nameKey, String descKey, String targets,
                           List<String> targetCodes, int rarity, int ticksLeft, int duration,
                           boolean suspendEvent, int uid, List<ModuleView> modules, int tone,
                           boolean preview, String contributors) {
        this.name = name;
        this.description = description;
        this.effectText = effectText;
        this.nameKey = nameKey == null ? "" : nameKey;
        this.descKey = descKey == null ? "" : descKey;
        this.targets = targets;
        this.targetCodes = targetCodes == null ? List.of() : List.copyOf(targetCodes);
        this.rarity = rarity;
        this.ticksLeft = ticksLeft;
        this.duration = duration;
        this.suspendEvent = suspendEvent;
        this.uid = uid;
        this.modules = modules == null ? List.of() : List.copyOf(modules);
        this.tone = tone;
        this.preview = preview;
        this.contributors = contributors == null ? "" : contributors;
    }

    /** Server -> view (snapshot building always runs on the server main thread, so it is safe). */
    public static MarketEventView of(ActiveEvent event) {
        List<ModuleView> modules = new ArrayList<>();
        for (ActiveEvent.EffectModule effectModule : event.modules) {
            if (modules.size() >= MAX_MODULES) {
                break;
            }
            modules.add(new ModuleView(effectModule.type.ordinal(), effectModule.param, effectModule.direction,
                    Math.max(0, effectModule.ticksLeft), effectModule.duration, effectModule.realizedValue));
        }
        return new MarketEventView(
                cut(event.displayName),
                cut(event.description),
                cut(event.effectSummaryPlain()),
                cut(event.nameKey),
                cut(event.descKey),
                cut(event.targetNames()),
                event.targetCodes(),
                event.rarity.ordinal(),
                Math.max(0, event.ticksLeft),
                event.duration,
                event.hasEffect(EffectType.SUSPEND),
                event.uid,
                modules,
                toneOf(event),
                event.template.preview,
                event.contributorNames);
    }

    /** Bullish/bearish tone: looks only at price-multiplier module directions; all >1 bullish, all <1 bearish, anything else mixed. */
    private static int toneOf(ActiveEvent event) {
        boolean sawUp = false;
        boolean sawDown = false;
        for (ActiveEvent.EffectModule effectModule : event.modules) {
            if (effectModule.type != EffectType.PRICE_MULT || effectModule.expired()) {
                continue;
            }
            if (effectModule.param > 1.0) {
                sawUp = true;
            } else if (effectModule.param < 1.0) {
                sawDown = true;
            }
        }
        if (sawUp && !sawDown) {
            return TONE_BULL;
        }
        if (sawDown && !sawUp) {
            return TONE_BEAR;
        }
        return TONE_NEUTRAL;
    }

    /** Progress-bar color: bullish / bearish follow the theme's rising and falling slots (mixed or neutral is grey). */
    public int toneColor() {
        MarketSettings ms = MarketSettings.current();
        return switch (tone) {
            case TONE_BULL -> ms.riseColor();
            case TONE_BEAR -> ms.fallColor();
            default -> NEUTRAL_TONE_COLOR;
        };
    }

    /** Whether the event affects the given stock code (used by the client's quote-list highlight). */
    public boolean affects(String code) {
        return code != null && targetCodes.contains(code);
    }

    private static String cut(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    /** Keep an untrusted packet value finite and within a display-safe range. */
    private static double readRealized(double value) {
        if (!Double.isFinite(value)) {
            return Double.NaN;
        }
        return Math.max(-MAX_REALIZED_VALUE, Math.min(MAX_REALIZED_VALUE, value));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(name, MAX_TEXT);
        buf.writeUtf(effectText, MAX_TEXT);
        buf.writeUtf(targets, MAX_TEXT);
        buf.writeUtf(description, MAX_TEXT);
        buf.writeVarInt(rarity);
        buf.writeVarInt(Math.max(0, ticksLeft));
        buf.writeVarInt(Math.max(0, duration));
        int targetCount = Math.min(MAX_TARGETS, targetCodes.size());
        buf.writeVarInt(targetCount);
        for (int targetIndex = 0; targetIndex < targetCount; targetIndex++) {
            buf.writeUtf(targetCodes.get(targetIndex), MAX_CODE_TEXT);
        }
        buf.writeVarInt(suspendEvent ? 1 : 0);
        buf.writeVarInt(uid);
        writeExtras(buf);
    }

    private void writeExtras(FriendlyByteBuf buf) {
        buf.writeVarInt(modules.size());
        for (ModuleView m : modules) {
            buf.writeVarInt(m.type);
            buf.writeDouble(m.param);
            buf.writeVarInt(m.direction);
            buf.writeVarInt(m.ticksLeft);
            buf.writeVarInt(m.duration);
            buf.writeDouble(Double.isFinite(m.realizedValue) ? m.realizedValue : Double.NaN);
        }
        buf.writeVarInt(tone);
        buf.writeVarInt(preview ? 1 : 0);
        buf.writeUtf(contributors, MAX_TEXT);
        buf.writeUtf(nameKey, MAX_TEXT);
        buf.writeUtf(descKey, MAX_TEXT);
    }

    public static MarketEventView read(FriendlyByteBuf buf) {
        String name = buf.readUtf(MAX_TEXT);
        String effect = buf.readUtf(MAX_TEXT);
        String targets = buf.readUtf(MAX_TEXT);
        String desc = buf.readUtf(MAX_TEXT);
        int rarity = buf.readVarInt();
        int ticksLeft = buf.readVarInt();
        int duration = buf.readVarInt();
        int targetCount = Math.min(MAX_TARGETS, buf.readVarInt());
        List<String> codes = new ArrayList<>(Math.max(0, targetCount));
        for (int targetIndex = 0; targetIndex < targetCount; targetIndex++) {
            codes.add(buf.readUtf(MAX_CODE_TEXT));
        }
        boolean susp = buf.readVarInt() != 0;
        int uid = buf.readVarInt();
        int moduleCount = Math.min(MAX_MODULES, buf.readVarInt());
        List<ModuleView> mods = new ArrayList<>(Math.max(0, moduleCount));
        for (int moduleIndex = 0; moduleIndex < moduleCount; moduleIndex++) {
            mods.add(new ModuleView(buf.readVarInt(), buf.readDouble(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), readRealized(buf.readDouble())));
        }
        int tone = buf.readVarInt();
        boolean preview = buf.readVarInt() != 0;
        String contrib = buf.readUtf(MAX_TEXT);
        String nKey = buf.readUtf(MAX_TEXT);
        String dKey = buf.readUtf(MAX_TEXT);
        return new MarketEventView(name, desc, effect, nKey, dKey, targets, codes, rarity, ticksLeft, duration,
                susp, uid, mods, tone, preview, contrib);
    }

    // ------------------------------------------------------------ NBT (for the dynamic-length snapshot packet)

    public CompoundTag toCompound() {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name);
        t.putString("Effect", effectText);
        t.putString("Targets", targets);
        t.putString("Desc", description);
        t.putString("NameKey", nameKey);
        t.putString("DescKey", descKey);
        t.putInt("Rarity", rarity);
        t.putInt("Left", ticksLeft);
        t.putInt("Dur", duration);
        ListTag codes = new ListTag();
        for (String c : targetCodes) {
            codes.add(StringTag.valueOf(c));
        }
        t.put("Codes", codes);
        t.putByte("Susp", (byte) (suspendEvent ? 1 : 0));
        t.putInt("Uid", uid);
        ListTag mods = new ListTag();
        for (ModuleView m : modules) {
            CompoundTag mc = new CompoundTag();
            mc.putInt("T", m.type);
            mc.putDouble("P", m.param);
            mc.putInt("D", m.direction);
            mc.putInt("L", m.ticksLeft);
            mc.putInt("U", m.duration);
            if (Double.isFinite(m.realizedValue)) {
                mc.putDouble("Real", m.realizedValue);
            }
            mods.add(mc);
        }
        t.put("Modules", mods);
        t.putByte("Tone", (byte) tone);
        t.putByte("Preview", (byte) (preview ? 1 : 0));
        t.putString("Contrib", contributors);
        return t;
    }

    public static MarketEventView fromCompound(CompoundTag t) {
        java.util.List<String> codes = new java.util.ArrayList<>();
        Tag raw = t.get("Codes");
        if (raw instanceof ListTag list) {
            for (int codeIndex = 0; codeIndex < list.size(); codeIndex++) {
                codes.add(list.getString(codeIndex));
            }
        }
        List<ModuleView> mods = new ArrayList<>();
        if (t.get("Modules") instanceof ListTag ml) {
            for (int moduleIndex = 0; moduleIndex < ml.size() && moduleIndex < MAX_MODULES; moduleIndex++) {
                if (ml.get(moduleIndex) instanceof CompoundTag mc) {
                    mods.add(new ModuleView(mc.getInt("T"), mc.getDouble("P"), mc.getInt("D"),
                            mc.getInt("L"), mc.getInt("U"),
                            mc.contains("Real") ? readRealized(mc.getDouble("Real")) : Double.NaN));
                }
            }
        }
        return new MarketEventView(
                t.getString("Name"), t.getString("Desc"), t.getString("Effect"),
                t.getString("NameKey"), t.getString("DescKey"), t.getString("Targets"),
                codes, t.getInt("Rarity"), t.getInt("Left"), t.getInt("Dur"),
                t.getByte("Susp") != 0, t.getInt("Uid"),
                mods, t.getByte("Tone"), t.getByte("Preview") != 0, t.getString("Contrib"));
    }

    /** Border color: set by rarity (Common gray 0xFF888888 / Rare blue / Epic purple / Legendary gold); preview cards are uniformly dark gray. */
    public int borderColor() {
        if (preview) {
            return PREVIEW_BORDER_COLOR;
        }
        return Rarity.byOrdinalSafe(rarity).borderColor;
    }

    public String rarityLabel() {
        return Rarity.byOrdinalSafe(rarity).label;
    }

    /** Rarity ordinal (used by the client for sorting and card-size scaling). */
    public int rarityOrdinal() {
        return rarity;
    }
}
