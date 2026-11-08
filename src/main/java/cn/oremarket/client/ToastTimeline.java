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

package cn.oremarket.client;

/**
 * Opacity timeline for a notice's visible lifetime.
 *
 * <p>Entry and revival still use a continuous ramp. Departure is deliberately
 * different: it is an atomic transition to fully hidden, so no partially transparent
 * frame can be rendered and make the text blink independently from its box.</p>
 *
 * <p>The class stores primitives only: it holds no Minecraft types, so the curve can
 * be exercised by the automated tests without a running client.</p>
 */
final class ToastTimeline {

    /** Curve used when a value climbs: quick at first, settling at the end. */
    static final int CURVE_EASE_OUT = 0;
    /** Kept for compatibility with existing timeline tests and callers. */
    static final int CURVE_EASE_IN_OUT = 1;

    /** Longest ramp a single segment may run; a mis-set configuration cannot freeze a ramp open. */
    private static final long MAX_RAMP_MS = 60_000L;

    private float from;
    private float to;
    private long startMs;
    private long rampMs;
    private int curve;
    private boolean fadingOut;

    /** Starts a fully transparent notice that becomes visible over {@code rampMs}. */
    void beginEnter(long now, long rampMs) {
        from = 0f;
        to = 1f;
        startMs = now;
        this.rampMs = boundRamp(rampMs);
        curve = CURVE_EASE_OUT;
        fadingOut = false;
    }

    /**
     * Starts an atomic departure. The notice becomes fully hidden at {@code now};
     * the caller removes it before the next draw, so no fade-out frame exists.
     */
    void beginFadeOut(long now, long rampMs) {
        if (fadingOut) {
            return;
        }
        from = alphaAt(now);
        to = 0f;
        startMs = now;
        this.rampMs = 0L;
        curve = CURVE_EASE_IN_OUT;
        fadingOut = true;
    }

    /**
     * Cancels a running departure: the notice stays on screen and climbs back to full
     * opacity from wherever the fade had reached.
     *
     * @return {@code true} when a fade-out was actually running (the caller uses this to
     *         tell a revive apart from the ordinary case where the notice was already steady)
     */
    boolean cancelFadeOut(long now, long rampMs) {
        if (!fadingOut) {
            return false;
        }
        from = alphaAt(now);
        to = 1f;
        startMs = now;
        this.rampMs = boundRamp(rampMs);
        curve = CURVE_EASE_OUT;
        fadingOut = false;
        return true;
    }

    /** Opacity at {@code now}; always inside {@code [0, 1]}. */
    float alphaAt(long now) {
        return valueAt(from, to, startMs, rampMs, curve, now);
    }

    /** Whether a departure is in progress (the notice keeps its layout slot while it is). */
    boolean isFadingOut() {
        return fadingOut;
    }

    /** Whether the running departure has run out of time and the notice may be destroyed. */
    boolean fadeOutFinished(long now) {
        return fadingOut && now - startMs >= rampMs;
    }

    /** Value of a segment at {@code now}; exposed for the automated tests. */
    static float valueAt(float from, float to, long startMs, long rampMs, int curve, long now) {
        float a = clamp01(from);
        float b = clamp01(to);
        if (now < startMs) {
            return a;
        }
        if (rampMs <= 0L) {
            return b;
        }
        float t = clamp01((now - startMs) / (float) rampMs);
        float eased = curve == CURVE_EASE_IN_OUT ? easeInOut(t) : easeOut(t);
        return a + (b - a) * eased;
    }

    /** Clamped cubic-free ease-out: {@code 1 - (1 - t)^2}. */
    static float easeOut(float t) {
        float x = clamp01(t);
        return 1f - (1f - x) * (1f - x);
    }

    /** Symmetric ease-in-out used by the departure ramp. */
    static float easeInOut(float t) {
        float x = clamp01(t);
        return x < 0.5f ? 2f * x * x : 1f - (float) Math.pow(-2f * x + 2f, 2) / 2f;
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static long boundRamp(long rampMs) {
        if (rampMs <= 0L) {
            return 0L;
        }
        return Math.min(MAX_RAMP_MS, rampMs);
    }
}
