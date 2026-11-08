/*
 * Cosmetic environment volume for the market chart.
 * It never enters balances, positions, prices, shorting or loans.
 */
package cn.oremarket.common;

/** Deterministic, bounded virtual volume generated only when a candle closes. */
public final class VirtualVolume {
    private static final long BASE_MIN = 50L;
    private static final long BASE_MAX = 200L;
    private static final long PULSE_MIN = 500L;
    private static final long PULSE_MAX = 2000L;
    private static final long PULSE_ROLL_BOUND = 10L;

    private VirtualVolume() {
    }

    /**
     * Returns cosmetic shares for one completed candle. The deterministic seed is deliberate:
     * no server RNG state is consumed, so adding chart volume cannot alter market prices/events.
     */
    public static double amount(String code, long candleSequence, double high, double low,
                                 double amplitudeFactor) {
        if (code == null || !Double.isFinite(high) || !Double.isFinite(low)
                || !Double.isFinite(amplitudeFactor) || amplitudeFactor < 0.0) {
            return 0.0;
        }
        double amplitude = high - low;
        if (!Double.isFinite(amplitude) || amplitude < 0.0) {
            // Illegal or inverted high/low values never fake a volume spike; fall back to the base amount.
            amplitude = 0.0;
        }
        long seed = mix(code.hashCode(), candleSequence);
        long base = BASE_MIN + floorMod(seed, BASE_MAX - BASE_MIN + 1L);
        double value = amplitudeFactor > 0.0
                && amplitude > (MarketVolume.MAX_VOLUME - base) / amplitudeFactor
                ? MarketVolume.MAX_VOLUME : base + amplitude * amplitudeFactor;
        if (floorMod(seed >>> 17, PULSE_ROLL_BOUND) == 0L) {
            double pulse = PULSE_MIN + floorMod(seed >>> 29, PULSE_MAX - PULSE_MIN + 1L);
            value = value >= MarketVolume.MAX_VOLUME - pulse ? MarketVolume.MAX_VOLUME : value + pulse;
        }
        return MarketVolume.sanitize(value);
    }

    private static long mix(long a, long b) {
        long z = a * 0x9E3779B97F4A7C15L + b + 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long floorMod(long value, long modulus) {
        return Math.floorMod(value, modulus);
    }
}
