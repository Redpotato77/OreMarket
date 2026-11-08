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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Startup self-check: invariants that once crashed or mis-converted are frozen into checks that run at boot;
 * any failure only logs an error and never blocks startup (the UI has degradation paths; a self-check must never crash the game).
 *
 * Called once from the client's FMLClientSetupEvent and once from the server's ServerStartedEvent;
 * when everything passes, the fixed sentence {@value #PASS_MESSAGE} is the last log line.
 *
 * Check items (unit tests in src/test/java cover the same invariants):
 * 1. RecursionGuard: depth cap, leave() restores the pre-entry value, nested counting is correct;
 * 2. Num: Chinese large-number units (1e4/1e8/1e12/1e16) vs English K/M/B/T/Qa conversion,
 * scientific-notation degradation, overlong digit-string shorthand, NaN degradation;
 * 3. Fees: total-fee formula, non-finite clamping (no free trades through NaN);
 * 4. key-set equality of the four lang tables (zh_cn / en_us / zh_tw / zh_hk; a missing key would break the L overlay).
 *
 * <p><b>Console log discipline </b>: the two former groups of pure-string assertions here
 * the loan four-mode baselines and the I18n regex boundary defense (each startup printed Chinese "[check]"
 * evidence lines) - are FULLY DELETED. They were one-off development verifications; rerunning them on every boot
 * of the release build is wasted performance. Their permanent regression locks live in src/test/java
 * (LoanCalculatorTest etc.) and the tools/loanverify offline harness - run at compile/verification time, never in
 * the in-game console. The remaining checks here are silent when all pass and log English errors only on failure;
 * non-ASCII content in failure messages is escaped into unicode escape sequences, so console output is 100% English.</p>
 */
public final class SanityCheck {

    /** The fixed log sentence when everything passes (the acceptance marker). */
    public static final String PASS_MESSAGE = "[OreMarket] sanity checks passed";

    private static final Logger LOG = LoggerFactory.getLogger("OreMarket/Sanity");
    private static final String[] LANG_FILES = {
            "/assets/oremarket/lang/zh_cn.json",
            "/assets/oremarket/lang/en_us.json",
            "/assets/oremarket/lang/zh_tw.json",
            "/assets/oremarket/lang/zh_hk.json"};

    private SanityCheck() {
    }

    /** Run every self-check; returns a list of failure descriptions; an empty list means all passed. */
    public static List<String> failures() {
        List<String> f = new ArrayList<>();
        checkGuard(f);
        checkNumbers(f);
        checkFees(f);
        checkLanguageParity(f);
        return f;
    }

    /** Report results: one PASS line when all pass; failures logged one by one as errors (includes extra items, so callers can add client-side checks). */
    public static boolean report(List<String> failures) {
        if (failures.isEmpty()) {
            LOG.info(PASS_MESSAGE);
            return true;
        }
        for (String message : failures) {
            // Console log 100% English: messages may quote expected lang-table values (Chinese included); sanitize before writing.
            LOG.error("[OreMarket] sanity check failed: {}", DebugLog.ascii(message));
        }
        return false;
    }

    /** Run + report in one step. */
    public static boolean runAndLog() {
        return report(failures());
    }

    // ------------------------------------------------------------ Check items

    private static void checkGuard(List<String> f) {
        RecursionGuard g = new RecursionGuard(2);
        int base = g.depth();
        boolean a = g.tryEnter();
        boolean b = g.tryEnter();
        boolean c = g.tryEnter();
        if (!(a && b && !c)) {
            f.add("RecursionGuard depth cap misbehaved");
        }
        if (g.depth() != base + 2) {
            f.add("RecursionGuard counter drift");
        }
        g.leave(base + 1);
        g.leave(base);
        if (g.depth() != base) {
            f.add("RecursionGuard leave did not restore depth");
        }
    }

    private static void checkNumbers(List<String> f) {
        expect(f, "100.0M", Num.unit(1.0e8, false, 1), "1e8 must render as 100.0M (en)");
        expect(f, "1.0" + String.valueOf((char) 0x4EBF), Num.unit(1.0e8, true, 1), "1e8 zh unit");
        expect(f, "10.0K", Num.unit(1.0e4, false, 1), "1e4 must render as 10.0K (en)");
        expect(f, "1.0" + String.valueOf((char) 0x4E07), Num.unit(1.0e4, true, 1), "1e4 zh unit");
        expect(f, "1.0B", Num.unit(1.0e9, false, 1), "1e9 must render as 1.0B (en)");
        expect(f, "1.0" + String.valueOf((char) 0x5146), Num.unit(1.0e12, true, 1), "1e12 zh unit");
        expect(f, "1.0T", Num.unit(1.0e12, false, 1), "1e12 en unit");
        expect(f, "1.0" + String.valueOf((char) 0x4EAC), Num.unit(1.0e16, true, 1), "1e16 zh unit");
        expect(f, "1.0Qa", Num.unit(1.0e15, false, 1), "1e15 en unit");
        // Even the largest unit overflows, so two-decimal scientific notation; the pure-digit part must stay within 10 characters.
        expect(f, "1.00E18", Num.unit(1.0e18, false, 1), "1e18 en must degrade to scientific");
        expect(f, "1.00E20", Num.unit(1.0e20, true, 1), "1e20 zh must degrade to scientific");
        expect(f, "0.0", Num.unit(Double.NaN, false, 1), "NaN must degrade to 0.0");
        expect(f, "-0.0", Num.signed(-0.0, 1), "negative zero must keep sign");
        String huge = Num.unit(Double.MAX_VALUE, false, 1);
        if (huge == null || huge.isEmpty()) {
            f.add("out-of-range display produced empty string");
        }
        if (huge != null && huge.length() > Num.MAX_NUMBER_LEN) {
            f.add("scientific fallback exceeded " + Num.MAX_NUMBER_LEN + " chars: " + huge);
        }
        // Extreme pure-digit-string shorthand (exact string slicing, unaffected by double precision)
        expect(f, "9.99E18", Num.compact("9999999999999999999"), "compact must truncate mantissa");
        expect(f, "12.43", Num.compact("12.43"), "compact must not touch normal prices");
    }

    private static void checkFees(List<String> f) {
        double combined = Fees.combined(0.02, 0.01);
        if (Math.abs(combined - 0.0298) > 1e-9) {
            f.add("combined fee-rate formula regression failed: " + combined);
        }
        if (Fees.effectiveFeeRate(Double.NaN) != 0.0) {
            f.add("NaN fee rate not clamped to 0");
        }
        if (Fees.buyCost(10.0, 5, Double.NaN) != 50L) {
            f.add("NaN total rate gave bogus buy cost (free-trade exploit)");
        }
        if (Fees.additive(Double.POSITIVE_INFINITY, 1.0) != 1.0) {
            f.add("Infinity listing fee rate not clamped");
        }
        if (Fees.sellGain(10.0, 5, 0.3) != 35L) {
            f.add("sell net proceeds regression failed");
        }
    }


    private static void checkLanguageParity(List<String> f) {        Set<String> base = null;
        for (String path : LANG_FILES) {
            Set<String> keys = loadKeys(path);
            if (keys == null) {
                f.add("language file missing or unreadable: " + path);
                continue;
            }
            if (base == null) {
                base = keys;
            } else if (!base.equals(keys)) {
                f.add("language key set differs from zh_cn: " + path);
            }
        }
    }

    /** Read a lang table's key set from the mod jar classpath (works on both sides; bypasses the resource manager). */
    private static Set<String> loadKeys(String path) {
        try (InputStream in = SanityCheck.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            Set<String> out = new HashSet<>(json.size() * 2);
            for (String key : json.keySet()) {
                out.add(key);
            }
            return out;
        } catch (Exception e) {
            LOG.warn("failed to read language file: {}", path, e);
            return null;
        }
    }

    private static void expect(List<String> f, String want, String got, String why) {
        if (!want.equals(got)) {
            f.add(why + "(expected " + want + ", actual " + got + ")");
        }
    }
}
