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

/**
 * Lightweight formatting for numbers shown in the UI.
 *
 * Why not {@code String.format}:
 * - every call builds a fresh {@code Formatter} (parsing the format string, creating a regex Matcher,
 * boxing the argument array) - an order of magnitude more expensive than hand-rolled concatenation;
 * - the quote list, K-line chart, leaderboard and detail panel together format dozens of times per frame,
 * which at 60 fps means thousands of short-lived string allocations per second flooding the young
 * generation and showing up as rhythmic frame drops;
 * - a few call sites in this project also omit {@code Locale.ROOT}, so the system language would turn the
 * decimal point into a comma and the display basis drifts.
 */
public final class Num {

    private static final long[] POW10 = {1L, 10L, 100L, 1000L, 10000L};
    /** Scaling safety ceiling: beyond it we saturate - never render a negative from overflow in the UI. */
    private static final double SAFE_MAX = 9.0e17;

    private Num() {
    }

    /**
     * Rounded to decimals places as a fixed-point integer (NaN -> 0, beyond range -> saturate).
     * The input is taken as absolute value. A tiny 1e-9 correction is added in the fixed-point domain:
     * {@code 1.005 * 100} is {@code 100.49999999999999} in binary, so a plain round gives 100, while
     * {@code String.format} with decimal semantics gives 101; the correction keeps display identical to the original.
     * At large magnitudes the correction drops below one ulp and becomes inert - it never lifts a value that should round down.
     */
    private static long scaledAbs(double abs, int decimals) {
        if (abs != abs) {
            return 0;
        }
        if (abs > SAFE_MAX) {
            return Long.MAX_VALUE / 16;
        }
        return Math.round(abs * POW10[decimals] + 1e-9);
    }

    /** Sign test: consistent with String.format, negative zero (-0.0) counts as negative. */
    private static boolean negative(double v) {
        return Double.compare(v, 0.0) < 0;
    }

    /** Fixed-point decimal: {@code fixed(-12.345, 2)} -> {@code "-12.35"}; {@code fixed(7, 1)} -> {@code "7.0"}. */
    public static String fixed(double v, int decimals) {
        String body = plain(scaledAbs(Math.abs(v), decimals), decimals);
        return (negative(v) ? "-" : "") + body;
    }

    /** With sign: equivalent to {@code %+.Nf} (matching vanilla: -0.0 shows "-0.0", +0 shows "+0.0"). */
    public static String signed(double v, int decimals) {
        return (negative(v) ? "-" : "+") + plain(scaledAbs(Math.abs(v), decimals), decimals);
    }

    /** Percentage: {@code pct(chg, 1)} -> {@code "+1.2%"}. */
    public static String pct(double v, int decimals) {
        return signed(v, decimals) + "%";
    }

    /**
     * Unified unit shorthand (Chinese and English share one numeric basis, so the same number shows the same value in both languages):
     * - Chinese: 1e16+ -> jing, 1e12+ -> zhao, 1e8+ -> yi, 1e4+ -> wan, one decimal kept;
     * - English: 1e15+ -> Qa, 1e12+ -> T, 1e9+ -> B, 1e6+ -> M, 1e3+ -> K, one decimal kept.
     * Correspondence: 1.0 yi = 100.0M (x100), 1.0 wan = 10.0K (x10), 1.0 zhao = 1.0T,
     * 1.0 jing = 10.0Qa. Completes the carry ladder to wan/yi/zhao/jing; when even the largest unit
     * overflows (mantissa above 4 digits / English 1000.0Qa) it force-degrades to two-decimal scientific
     * notation (9.99E18), and if that is still too long it truncates with a '+' so the pure-digit text stays
     * within {@link #MAX_NUMBER_LEN}. The absolute value is rounded to one decimal BEFORE choosing the unit tier,
     * so both languages pick tiers from the same rounded figure and cannot diverge into cases like
     * '99999999 shows 10000.0 yi-wan in Chinese but 99.99M in English'.
     * Below the smallest tier the raw value shows fixed-point with {@code plainDecimals} places.
     * The argument is an absolute value (signs are handled by callers); this is UI display only and changes no server figure.
     */
    public static String unit(double abs, boolean zh, int plainDecimals) {
        if (abs != abs) {                       // NaN -> 0, consistent with the rest of this class
            return fixed(0, plainDecimals);
        }
        double a = abs < 0 ? -abs : abs;
        if (!Double.isFinite(a)) {              // +/-Infinity -> the largest representable value; never print Inf
            a = Double.MAX_VALUE;
        }
        // Pre-round to one decimal: 99,999,999 -> 1.0e8, so Chinese and English promote tiers simultaneously.
        // When a is close to Long.MAX_VALUE, Math.round(a * 10) is off the table: long saturation would shrink the result tenfold.
        double r = a >= Long.MAX_VALUE / 10.0 ? Math.rint(a) : Math.round(a * 10.0) / 10.0;
        // English K/M/B/T/Qa, each step x10^3. A tier promotes only when the mantissa breaks past a full unit (Chinese 10000.0 / English 1000.0).
        double[] factors = zh ? new double[]{1e16, 1e12, 1e8, 1e4}
                : new double[]{1e15, 1e12, 1e9, 1e6, 1e3};
        String[] suffixes = zh ? new String[]{
                        cn.oremarket.common.RawText.t("oremarket.unit.jing"),
                        cn.oremarket.common.RawText.t("oremarket.unit.zhao"),
                        cn.oremarket.common.RawText.t("oremarket.text.0003"),
                        cn.oremarket.common.RawText.t("oremarket.text.0001")}
                : new String[]{"Qa", "T", "B", "M", "K"};
        double bump = zh ? 10000.0 : 1000.0;
        int u = -1;
        for (int i = 0; i < factors.length; i++) {
            if (r >= factors[i]) {
                u = i;
                break;
            }
        }
        if (u < 0) {
            return fixed(r, plainDecimals);
        }
        // 999.95M must show as 1.0B, not 1000.0M: when the mantissa carries past the tier, promote one step (unit[0] is already the largest tier).
        double m = Math.round((r / factors[u]) * 10.0) / 10.0;
        if (m >= bump && u > 0) {
            u--;
            m = Math.round((r / factors[u]) * 10.0) / 10.0;
        }
        if (m >= bump) {
            // Even jing / Qa cannot hold it: force two-decimal scientific notation; if still too long, truncate and append '+'.
            return capNumber(sci(r));
        }
        return capNumber(fixed(m, 1) + suffixes[u]);
    }

    /**
     * Two-decimal scientific notation (the mantissa is TRUNCATED, never rounded up into 10.00E<n>):
     * 9.999999999999999999e18 -> "9.99E18"; 1e19 -> "1.00E19"; 0 -> "0.00E0".
     */
    public static String sci(double abs) {
        double a = Math.abs(abs);
        if (a != a) {
            return "0.00E0";
        }
        if (!Double.isFinite(a)) {
            a = Double.MAX_VALUE;
        }
        if (a == 0.0) {
            return "0.00E0";
        }
        int e = (int) Math.floor(Math.log10(a));
        double m = a / Math.pow(10.0, e);
        if (m >= 10.0) {
            m /= 10.0;
            e++;
        } else if (m < 1.0) {
            m *= 10.0;
            e--;
        }
        long mt = (long) Math.floor(m * 100.0 + 1e-9);   // truncate to 2 decimals
        if (mt >= 1000) {
            mt /= 10;
            e++;
        }
        return Long.toString(mt / 100) + "." + padFrac(mt % 100, 2) + "E" + e;
    }

    /** UI number-length red line: the pure-digit part must never exceed 10 characters. */
    public static final int MAX_NUMBER_LEN = 10;

    /** Overlong number fallback: cut to {@value #MAX_NUMBER_LEN} - 1 characters and append '+' (e.g. "999999999+"). */
    public static String capNumber(String s) {
        if (s == null || s.isEmpty()) {
            return "0";
        }
        if (s.length() <= MAX_NUMBER_LEN) {
            return s;
        }
        return s.substring(0, MAX_NUMBER_LEN - 1) + "+";
    }

    /**
     * Extreme-value shorthand entry (call before a UI number cell renders):
     * when the digit string (integer part + fraction part) totals more than {@value #MAX_NUMBER_LEN} characters,
     * degrade to two-decimal scientific notation - slicing the STRING keeps exactness regardless of double
     * precision: 9999999999999999999 -> 9.99E18 - and if the result is still too long, truncate with '+'.
     * Normal-length text returns verbatim; ordinary prices like 12.43 are never touched.
     */
    public static String compact(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() <= MAX_NUMBER_LEN) {
            return raw;
        }
        try {
            int i = 0;
            StringBuilder out = new StringBuilder(raw.length());
            while (i < raw.length()) {
                char c = raw.charAt(i);
                boolean digitRun = c >= '0' && c <= '9';
                boolean signedRun = (c == '-' || c == '+') && i + 1 < raw.length()
                        && raw.charAt(i + 1) >= '0' && raw.charAt(i + 1) <= '9';
                if (!digitRun && !signedRun) {
                    out.append(c);
                    i++;
                    continue;
                }
                int start = i;
                if (c == '-' || c == '+') {
                    i++;
                }
                int intStart = i;
                while (i < raw.length() && raw.charAt(i) >= '0' && raw.charAt(i) <= '9') {
                    i++;
                }
                int intEnd = i;
                int fracEnd = i;
                if (i < raw.length() && raw.charAt(i) == '.'
                        && i + 1 < raw.length() && raw.charAt(i + 1) >= '0' && raw.charAt(i + 1) <= '9') {
                    i++;
                    while (i < raw.length() && raw.charAt(i) >= '0' && raw.charAt(i) <= '9') {
                        i++;
                    }
                    fracEnd = i;
                }
                String run = raw.substring(start, i);
                int digits = (intEnd - intStart) + (fracEnd - intEnd);
                if (digits <= MAX_NUMBER_LEN && run.length() <= MAX_NUMBER_LEN) {
                    out.append(run);
                    continue;
                }
                out.append(sciFromDigits(raw.substring(intStart, intEnd),
                        raw.substring(intEnd, fracEnd), start < intStart));
            }
            return capNumber(out.toString());
        } catch (RuntimeException broken) {
            // Any parse surprise just returns the original text (the outer UI still has scale/ellipsis fallbacks); rendering must never throw.
            return raw;
        }
    }

    /** Exact string slicing: integer part + fraction part -> two-decimal-truncated scientific mantissa (the 9.99E18 basis). */
    private static String sciFromDigits(String intPart, String fracPart, boolean negative) {
        String digits = intPart + fracPart;
        int p = 0;
        while (p < digits.length() && digits.charAt(p) == '0') {
            p++;
        }
        if (p >= digits.length()) {
            return "0.00E0";
        }
        // The first non-zero digit in the integer part -> exponent = the integer digits right of it; in the fraction part -> negative exponent.
        int exp = p < intPart.length() ? intPart.length() - 1 - p : -(p - intPart.length() + 1);
        StringBuilder mant = new StringBuilder(3);
        for (int i = 0; mant.length() < 3 && p + i < digits.length(); i++) {
            mant.append(digits.charAt(p + i));
        }
        while (mant.length() < 3) {
            mant.append('0');
        }
        return (negative ? "-" : "") + mant.charAt(0) + "." + mant.substring(1, 3) + "E" + exp;
    }

    /**
     * Defensive capture-group read: too few groups / out of range / unmatched all return null - never throws
     * IndexOutOfBoundsException (I18n crash root cause: calling group(2) on a pattern with a single group).
     */
    public static String safeGroup(java.util.regex.Matcher m, int n) {
        try {
            if (m == null || n < 0 || m.groupCount() < n) {
                return null;
            }
            return m.group(n);
        } catch (RuntimeException outOfRange) {
            return null;
        }
    }

    /**
     * Normalize numeric fragments carrying Chinese units (pure JDK, offline-checkable): restore figures like
     * "9999999.9yi / 1.2wan / 3zhao / 5jing" to their numeric value and re-shrink them in the target language's ladder.
     * on match failure / missing group / parse failure the original fragment is kept and the scan continues - never throws.
     *
     * @param zh target display language (true outputs the Chinese units wan/yi/zhao/jing, false outputs K/M/B/T/Qa)
     */
    public static String normalizeChineseUnitText(String raw, boolean zh) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        try {
            java.util.regex.Matcher m = CHINESE_UNIT_NUMBER.matcher(raw);
            StringBuffer out = new StringBuffer(raw.length());
            while (m.find()) {
                String rep = m.group();
                String num = safeGroup(m, 1);
                String unit = safeGroup(m, 2);
                if (num != null && unit != null) {
                    try {
                        double value = Double.parseDouble(num);
                        double factor = chineseUnitFactor(unit);
                        if (factor > 0) {
                            double amount = value * factor;
                            if (Double.isFinite(amount)) {
                                rep = (value < 0 ? "-" : "") + unit(Math.abs(amount), zh, 1);
                            }
                        }
                    } catch (NumberFormatException ignored) {
                        // Keep the offending fragment verbatim: one bad hint must not break the whole screen render.
                    }
                }
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(rep));
            }
            m.appendTail(out);
            return out.toString();
        } catch (RuntimeException broken) {
            return raw;
        }
    }

    /** Chinese unit -> multiplier; unknown units return -1 (the caller keeps the raw text). Codepoint concatenation keeps the source locale-neutral. */
    private static double chineseUnitFactor(String unit) {
        char c = unit.charAt(0);
        if (c == 0x4E07 || c == 0x842C) {        // wan (1e4; simplified and traditional glyphs)
            return 1e4;
        }
        if (c == 0x4EBF || c == 0x5104) {        // yi (1e8; simplified and traditional glyphs)
            return 1e8;
        }
        if (c == 0x5146) {                       // zhao (1e12)
            return 1e12;
        }
        if (c == 0x4EAC) {                       // jing (1e16)
            return 1e16;
        }
        return -1;
    }

    /** 'digits + Chinese unit' fragment: the unit is capture group 2. */
    private static final java.util.regex.Pattern CHINESE_UNIT_NUMBER =
            java.util.regex.Pattern.compile(
                    "([+-]?\\d+(?:\\.\\d+)?)\\s*("
                            + String.valueOf((char) 0x4E07) + "|" + String.valueOf((char) 0x842C)
                            + "|" + String.valueOf((char) 0x4EBF) + "|" + String.valueOf((char) 0x5104)
                            + "|" + String.valueOf((char) 0x5146) + "|" + String.valueOf((char) 0x4EAC)
                            + ")");

    /** Scaled fixed-point integer -> "integer part.fraction part". */
    private static String plain(long scaledAbs, int decimals) {
        long pow = POW10[decimals];
        long ip = scaledAbs / pow;
        if (decimals == 0) {
            return Long.toString(ip);
        }
        long fp = scaledAbs % pow;
        return Long.toString(ip) + "." + padFrac(fp, decimals);
    }

    /** Pad the fraction part on the left with zeros to decimals places. */
    private static String padFrac(long fp, int decimals) {
        String s = Long.toString(fp);
        if (s.length() >= decimals) {
            return s;
        }
        StringBuilder sb = new StringBuilder(decimals);
        for (int i = s.length(); i < decimals; i++) {
            sb.append('0');
        }
        sb.append(s);
        return sb.toString();
    }

    /** Left-align and pad to width (equivalent to {@code %-Ns}). */
    public static String padLeft(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        StringBuilder sb = new StringBuilder(width);
        sb.append(s);
        for (int i = s.length(); i < width; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
