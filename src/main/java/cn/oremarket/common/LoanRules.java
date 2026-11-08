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
import cn.oremarket.market.MarketSettings;

/**
 * The entry point for loan rule values: every number comes from the config snapshot (category 6 + category 1); no quota or rate is hard-coded here.
 *
 * v2 adds four blocks, all pure functions of 'raw numbers in -> result out',
 * so the boundary unit tests under src/test cover them directly (0 / negative / overflow / divide-by-zero / over-limit / prepayment / partial repayment):
 * - credit score {@link #creditScore}: five factors - repayment record, overdue count, bankruptcy count, total assets, level;
 * - dynamic limit {@link #dynamicLimit}: tier base x score multiplier + asset bonus + collateral bonus,
 * then discounted by consecutive overdue, zeroed during the bankruptcy cooldown;
 * - rates {@link #quoteDailyRate} / {@link #lockedDailyRate}:
 * base rate x credit discount x term multiplier x product factor, force-capped at min(result, 1.0%);
 * - prepayment {@link #prepayAmount}: remaining-interest discount and fee, either or both.
 *
 * The legacy single-value entries (tier limit / daily rate / term / cap multiplier / bankruptcy cooldown) stay unchanged:
 * - credit limit: level 1 = 0 (no borrowing), levels 2 / 3 / 4 = creditLimitLevel2 / 3 / 4;
 * - eligibility: all-time highestLevel >= 2 (level 2's peak threshold reads the live config);
 * - daily rate: loanDailyInterestPercent% (default 0.5%; clamped into the 0.5 soft cap before entering the multiplication);
 * - term: system loan = installments x configured loanTermDays (5 trading days per installment by default, so 5 installments = 25 days);
 * - interest cap multiplier: loanInterestCapMultiplier (default 2.0);
 * - bankruptcy cooldown: bankruptcyCooldownDays full trading days (default 3).
 *
 * Note: EXISTING LOANS never consult these methods' return values; each loan locks the five fields written
 * into LoanData at borrow time - dailyRate / termDays / capMultiplier / mode / installments; config changes only affect later new loans.
 */
public final class LoanRules {
    /** Design defaults (fallback when the config is missing). */
    public static final double DEFAULT_DAILY_RATE = 0.005;   // consistent with the loanDailyInterestPercent default of 0.5%
    public static final int DEFAULT_TERM_DAYS = 5;
    public static final double DEFAULT_CAP_MULTIPLIER = 2.0;
    public static final int DEFAULT_BANKRUPT_COOLDOWN_DAYS = 3;
    /**
     * The mandatory default for overdue penalty interest (percent basis): when the config is <= 0 (unset / set to 0),
     * charge 5% per day, closing the 'overdue funds are free' loophole.
     */
    public static final double DEFAULT_PENALTY_PERCENT = 5.0;
    /**
     * The minimum credit score for borrowing (below it, borrowing is refused outright; emergency loans are exempt
     * they are the comeback lane after bankruptcy, with a tiny limit and a very high rate, so no extra entry gate applies to them).
     */
    public static final int SCORE_BORROW_MIN = 450;

    /** Score baseline and floor/ceiling (below the floor the player loses borrowing eligibility entirely). */
    public static final int SCORE_BASE = 600;
    public static final int SCORE_MIN = 300;
    public static final int SCORE_MAX = 950;
    /** Score band boundaries (Very Poor / Fair / Good / Great / Excellent), one-to-one with {@link #scoreBand}. */
    private static final int[] SCORE_BAND_FLOOR = {0, 450, 550, 650, 750};
    /** Limit multiplier per band: the higher the band, the larger the limit. */
    private static final double[] SCORE_BAND_MULT = {0.50, 0.75, 1.00, 1.25, 1.50};
    /** The score's neutral line (limit banding / historical compatibility). */
    private static final int SCORE_RISK_NEUTRAL = 650;
    /**
     * The absolute hard cap on the daily rate, in percentage points.
     * Server deductions, client estimates and log displays all route through {@link #clampDailyRate},
     * ending the 'UI shows 1%, server charges 4.573%' two-side split.
     */
    public static final double HARD_RATE_CAP_PERCENT = 1.0;
    private LoanRules() {
    }

    /** Add two non-negative debt amounts without allowing dirty saved data to wrap into a negative value. */
    public static long saturatingAdd(long a, long b) {
        long left = Math.max(0L, a);
        long right = Math.max(0L, b);
        return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    // ------------------------------------------------------------ Legacy entries (unchanged)

    /** Credit limit for a level (level 1 = 0, no borrowing). */
    public static long creditLimit(int tier) {
        return MarketSettings.current().creditLimit(tier);
    }

    /** The configured daily rate right now (decimal). */
    public static double dailyRate() {
        return MarketSettings.current().loanDailyInterestPercent;
    }

    /** The configured loan term right now (trading days). */
    public static int termDays() {
        return MarketSettings.current().loanTermDays;
    }

    /** The configured interest cap multiplier right now (principal + interest total = principal x this multiplier). */
    public static double capMultiplier() {
        return MarketSettings.current().loanInterestCapMultiplier;
    }

    /** The configured interest cap right now (relative to principal = multiplier - 1). */
    public static double interestCapRate() {
        return Math.max(0.0, capMultiplier() - 1.0);
    }

    /** The configured bankruptcy cooldown in trading days. */
    public static int bankruptCooldownDays() {
        return MarketSettings.current().bankruptcyCooldownDays;
    }

    /** Eligibility level gate (fixed at 2, but level 2's peak threshold reads the live config). */
    public static int requiredTier() {
        return 2;
    }

    /** Eligibility text: when the level is short, quote the live threshold; 200 is never hard-coded. */
    public static String borrowHint() {
        long need = MarketSettings.current().levelThreshold(requiredTier());
        return cn.oremarket.common.LogFormatter.format("oremarket.hint.borrow_peak", need);
    }

    /** Lower bound of the legal borrow-amount range. */
    public static long minAmount() {
        return 1;
    }

    /** Clamp an amount into [1, credit limit]. */
    public static long clampAmount(long want, long limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.max(1, Math.min(want, limit));
    }

    /** Cooldown end day = bankruptcy day + 1 + cooldown days (the bankruptcy day itself also bans borrowing). */
    public static long cooldownEndDay(long bankruptDay) {
        return bankruptDay + 1L + bankruptCooldownDays();
    }

    /** Remaining trading days of the cooldown. */
    public static long cooldownLeft(long cooldownEndDay, long currentDay) {
        return Math.max(0, cooldownEndDay - currentDay);
    }

    /** Legacy compatibility: the old default-rate constant became a method; the name stays, pointing at the config. */
    public static double defaultDailyRate() {
        return dailyRate();
    }

    // ------------------------------------------------------------ v2: credit score

    /**
     * Credit score (pure function, 300~950). The five factors follow the documented formula:
     * - Repayment record: +{@code perOnTime} per on-time payoff (default 12, cumulative cap +120);
     * - Overdue: -{@code perOverdue} each time (default 35);
     * - Bankruptcy history: -{@code perBankrupt} each time (default 150);
     * - Total assets: +1 per {@code assetsStep} coins, capped at +60;
     * - Level: +{@code perTier} per level (default 15).
     * Any negative input counts as 0; the result always lies within [SCORE_MIN, SCORE_MAX].
     */
    public static int creditScore(int onTimeRepays, int overdues, int bankruptcies,
                                  long totalAssets, int tier,
                                  int perOnTime, int perOverdue, int perBankrupt,
                                  long assetsStep, int perTier) {
        double score = SCORE_BASE
                + Math.min(120, Math.max(0, onTimeRepays) * (double) Math.max(0, perOnTime))
                - Math.max(0, overdues) * (double) Math.max(0, perOverdue)
                - Math.max(0, bankruptcies) * (double) Math.max(0, perBankrupt)
                + Math.min(60, assetsStep > 0 ? totalAssets / assetsStep : 0)
                + Math.max(0, tier) * (double) Math.max(0, perTier);
        if (!Double.isFinite(score)) {
            return SCORE_BASE;
        }
        return (int) Math.max(SCORE_MIN, Math.min(SCORE_MAX, Math.round(score)));
    }

    /** Score under the current config (while scoring is disabled in config the baseline score always returns, and limits/rates fall back to the old 'level only' behavior). */
    public static int creditScore(int onTimeRepays, int overdues, int bankruptcies,
                                  long totalAssets, int tier) {
        MarketSettings s = MarketSettings.current();
        if (!s.loanCreditScoreEnabled) {
            return SCORE_BASE;
        }
        return creditScore(onTimeRepays, overdues, bankruptcies, totalAssets, tier,
                s.loanScorePerOnTime, s.loanScorePerOverdue, s.loanScorePerBankrupt,
                s.loanScoreAssetStep, s.loanScorePerTier);
    }

    /** Score band 0~4 (lower = worse); below the floor counts as ineligible. */
    public static int scoreBand(int score) {
        int clamped = Math.max(SCORE_MIN, Math.min(SCORE_MAX, score));
        int band = 0;
        for (int i = SCORE_BAND_FLOOR.length - 1; i >= 0; i--) {
            if (clamped >= SCORE_BAND_FLOOR[i]) {
                band = i;
                break;
            }
        }
        return band;
    }

    /** Limit multiplier of a band (0.5 ~ 1.5). */
    public static double scoreLimitMultiplier(int score) {
        return SCORE_BAND_MULT[scoreBand(score)];
    }

    /** Band name (0 Very Poor / 1 Fair / 2 Good / 3 Great / 4 Excellent): returns a language key, never raw Chinese text. */
    public static String scoreBandKey(int score) {
        return switch (scoreBand(score)) {
            case 0 -> "oremarket.loan.score.bad";
            case 1 -> "oremarket.loan.score.fair";
            case 3 -> "oremarket.loan.score.good";
            case 4 -> "oremarket.loan.score.excellent";
            default -> "oremarket.loan.score.normal";
        };
    }

    // ------------------------------------------------------------ v2: dynamic limit

    /**
     * Dynamic credit limit (pure function). Composition:
     * <pre>
     * tier base limit x score multiplier
     * + current total assets x loanLimitAssetPercent%
     * + collateral value x loanCollateralPercent%         (counted only for collateral loans)
     * then discounted by consecutive overdue: -20% per overdue, down to a 40% floor
     * </pre>
     * The asset bonus reads CURRENT total assets (not the all-time peak), so the available limit follows the player's
     * current holdings. There is no gameplay cap on this asset bonus; the configured percentage and the score multiplier
     * are the balancing controls. A separate very large numeric safety guard only prevents overflow in dirty data/configs.
     * The bankruptcy cooldown and an unsettled bankruptcy debt are refused by the caller (LoanService) at borrow time,
     * not baked into this function, so it yields one and the same figure for UI display and settlement checks.
     *
     * @param currentAssets the player's CURRENT total assets (<= 0 counts as 0; no negative bonus)
     * @return a limit >= 0 (always 0 when the tier base is 0, ending 'players without a level can borrow')
     */
    public static long dynamicLimit(long tierBase, int score, long currentAssets, long collateralValue,
                                    int consecutiveOverdue, boolean collateralProduct,
                                    double assetPercent, double collateralPercent,
                                    boolean creditScoreEnabled) {
        if (tierBase <= 0) {
            return 0;
        }
        double mult = creditScoreEnabled ? scoreLimitMultiplier(score) : 1.0;
        double limit = tierBase * mult;
        if (currentAssets > 0 && assetPercent > 0) {
            double bonus = currentAssets * finite(assetPercent) / 100.0;
            limit += bonus;
        }
        if (collateralProduct && collateralValue > 0 && collateralPercent > 0) {
            limit += collateralValue * collateralPercent / 100.0;
        }
        int strikes = Math.max(0, consecutiveOverdue);
        if (strikes > 0) {
            limit *= Math.max(0.4, 1.0 - 0.2 * strikes);
        }
        if (!Double.isFinite(limit) || limit < 0) {
            return 0;
        }
        return limit > LoanSchedule.SAFE_MAX_LIMIT ? LoanSchedule.SAFE_MAX_LIMIT : (long) Math.floor(limit);
    }

    /** Dynamic limit under the current config (assets = the player's current total assets; see the comment above). */
    public static long dynamicLimit(MarketSettings s, long tierBase, int score, long assets,
                                    long collateralValue, int consecutiveOverdue, boolean collateralProduct) {
        return dynamicLimit(tierBase, score, assets, collateralValue, consecutiveOverdue,
                collateralProduct, s.loanLimitAssetPercent, s.loanCollateralLimitFactor * 100.0,
                s.loanCreditScoreEnabled);
    }

    // ------------------------------------------------------- Margin-call criteria for collateral loans

    /**
     * The single margin-call criterion for collateral loans.
     *
     * <p><b>Why the old rule was inevitably wrong</b>: at day close it compared 'sellable holdings value < principal x 80%',
     * while the collateral loan's limit formula is 'base + asset bonus + holdings x 50%' - at borrow time it NEVER
     * required holdings to cover the principal (the only gate was sellableValue > 0). The two rules are mathematically
     * incompatible: any collateral loan with principal > holdings / 0.8 was falsely liquidated at the very next close.
     * Real player case: borrowed 14252, holdings worth 913, 100k coins still in the wallet, the current bill already
     * prepaid, yet force-liquidated the next trading day as a 'margin call' - the '5 installments, liquidated the next day' bug the player reported.</p>
     *
     * <p><b>New criterion</b>: look only at LIQUIDATABLE ASSETS = wallet coins + sellable holdings value + backpack mineral
     * recovery value - exactly the three sources the forced-liquidation three-step seizes (deduct coins -> sell holdings ->
     * collect backpack). Only when their total falls below remaining principal x 80% is the borrower truly insolvent and
     * the loan settled early against the collateral. Right after disbursement the wallet contains the borrowed money itself,
     * so the criterion can never misfire on borrow day; converting holdings into coins leaves the total unchanged.
     * This keeps the intended 'double insurance' (collect early when the borrower collapses) while removing its hits on healthy loans.</p>
     *
     * @param coins              wallet coins (dirty negatives count as 0)
     * @param collateralValue    sellable holdings market value (dirty negatives count as 0)
     * @param backpackValue      backpack mineral recovery value (pass 0 while the player is offline; the caller skips the check)
     * @param remainingPrincipal outstanding principal
     * @return [0] = total liquidatable assets (saturating add), [1] = the 80% principal threshold (at least 1); [0] < [1] triggers
     */
    public static long[] marginCallNumbers(long coins, long collateralValue, long backpackValue,
                                           long remainingPrincipal) {
        long c = Math.max(0L, coins);
        long v = Math.max(0L, collateralValue);
        long b = Math.max(0L, backpackValue);
        long coverage = c > Long.MAX_VALUE - v ? Long.MAX_VALUE : c + v;
        coverage = coverage > Long.MAX_VALUE - b ? Long.MAX_VALUE : coverage + b;
        long need = LoanSchedule.clampL(Math.max(0.0, (double) Math.max(0L, remainingPrincipal) * 0.80));
        return new long[]{coverage, Math.max(1L, need)};
    }

    /** See {@link #marginCallNumbers}: whether this close triggers the collateral margin call. */
    public static boolean marginCallTriggered(long coins, long collateralValue, long backpackValue,
                                              long remainingPrincipal) {
        long[] n = marginCallNumbers(coins, collateralValue, backpackValue, remainingPrincipal);
        return n[0] < n[1];
    }

    // ------------------------------------------------------------ v2: interest rates

    /**
     * Soft cap of the base rate (percentage points): when the configured loanDailyInterestPercent exceeds it, the base rate entering the multiplication is taken at the cap.
     * The old config (5% baseline), fed in verbatim, would have every discount flattened into a constant 1.000% by the hard cap (root cause of the 'fixed daily rate' UI bug);
     * clamping the baseline within 0.5% gives the credit discount and term multiplier real swing room, and the 1% hard cap degrades into a safety net.
     */
    public static final double BASE_SOFT_MAX_PERCENT = 0.5;
    /** Max swing of the event fluctuation on the rate (plus or minus 25%). */
    public static final double EVENT_SWING = 0.25;

    /**
     * Credit discount multiplier (first factor of the finalized 'base rate x credit discount x term multiplier' chain):
     * linear in the score: 950 -> 0.60 (best band, 40% off), 625 -> 1.00 (neutral), 300 -> 1.40 (worst band, 1.4x).
     * Dirty scores clamp into [300, 950]; the return always lies in [0.6, 1.4].
     */
    public static double creditMultiplier(int score) {
        int clamped = Math.max(SCORE_MIN, Math.min(SCORE_MAX, score));
        double t = (clamped - 625) / 325.0;                       // [-1, +1]
        return 1.0 - 0.4 * t;                                     // [0.6, 1.4]
    }

    /**
     * Term multiplier (second factor): five trading days per installment; 1 installment = 1.00, +5% per extra installment,
     * 5 installments (25 days) = 1.20. Dirty installment counts clamp into [1, {@link LoanSchedule#MAX_INSTALLMENTS}].
     */
    public static double termMultiplier(int installments) {
        return 1.0 + (LoanSchedule.clampInstallments(installments) - 1) * 0.05;
    }

    /** Event multiplier: 1 + variance% x event factor (factor clamped to [-1, +1]); swing capped at plus or minus {@link #EVENT_SWING}. */
    public static double eventMultiplier(double variancePercent, double eventFactor) {
        double v = finite(variancePercent) / 100.0
                * Math.max(-1.0, Math.min(1.0, finite(eventFactor)));
        return 1.0 + Math.max(-EVENT_SWING, Math.min(EVENT_SWING, v));
    }

    /**
     * Quoted daily rate (decimal, credit-loan basis, 1 installment):
     * <pre>
     * rate = min(base rate, {@link #BASE_SOFT_MAX_PERCENT}) x credit discount multiplier x event multiplier
     * then forced min(result, min(loanRateCapPercent, {@link #HARD_RATE_CAP_PERCENT}))
     * </pre>
     * The server ships it in the snapshot for the client's estimate; the locked borrow rate = this result x product factor x term
     * multiplier ({@link #lockedDailyRate}) - both sides run one and the same multiplication chain, so the figures must agree.
     */
    public static double quoteDailyRate(double basePercent, int score, double variancePercent,
                                        double eventFactor, double capPercent,
                                        boolean creditScoreEnabled) {
        double base = Math.min(Math.max(0.0, finite(basePercent)), BASE_SOFT_MAX_PERCENT);
        double credit = creditScoreEnabled ? creditMultiplier(score) : 1.0;
        double event = eventMultiplier(variancePercent, eventFactor);
        double pct = base * credit * event;
        double cap = Math.max(0.0, finite(capPercent));
        return clampDailyRate(pct / 100.0, cap / 100.0);
    }

    /**
     * Locked daily borrow rate (decimal): quote x product factor x term multiplier, finally force-capped at
     * {@code Math.min(result, 1.0%)} (done inside {@link #clampDailyRate}).
     * The client estimate runs the same function (reading the server-shipped quoteRate); two formula sets are never allowed.
     */
    public static double lockedDailyRate(double quoteRate, double productFactor,
                                         int installments, double capPercent) {
        double q = Math.max(0.0, finite(quoteRate));
        double pf = finite(productFactor) > 0 ? productFactor : 1.0;
        double cap = Math.max(0.0, finite(capPercent));
        return clampDailyRate(q * pf * termMultiplier(installments), cap / 100.0);
    }

    /**
     * Force-clamp any daily rate (decimal) into [0, HARD_RATE_CAP_PERCENT / 100].
     * An extra capFraction (decimal) tightens the ceiling further; dirty values / negatives count as the hard cap.
     */
    public static double clampDailyRate(double dailyRate, double capFraction) {
        double hard = HARD_RATE_CAP_PERCENT * 0.01;
        double cap = Double.isFinite(capFraction) && capFraction >= 0
                ? Math.min(capFraction, hard)
                : hard;
        double r = Double.isFinite(dailyRate) ? dailyRate : 0.0;
        if (r < 0) {
            return 0.0;
        }
        return Math.min(r, cap);
    }

    /** Clamp with the hard cap alone (fallback while no config has been pushed down). */
    public static double clampDailyRate(double dailyRate) {
        return clampDailyRate(dailyRate, HARD_RATE_CAP_PERCENT / 100.0);
    }

    /**
     * The EFFECTIVE daily-rate ceiling (percent): {@code min(loanRateCapPercent, 1.0)}.
     *
     * <p>Why this entry exists: previously only the borrow-quote chain ran {@link #clampDailyRate},
     * while P2P listing validation read the raw {@code loanRateCapPercent} directly, so one config key meant two
     * things on two paths - system loans stayed at 1%/day while player listings could post 12%/day. Everywhere a
     * 'rate ceiling' is needed now reads this method; the config key has exactly one meaning from here on.</p>
     */
    public static double effectiveRateCapPercent(double configuredCapPercent) {
        double cap = Math.max(0.0, finite(configuredCapPercent));
        return Math.min(cap, HARD_RATE_CAP_PERCENT);
    }

    /**
     * Overdue penalty daily rate (decimal):
     * - the config stores a decimal (MarketSettings already divided by 100), so NO SECOND division by 100 here
     * (the old implementation divided twice: 2% became 0.0002 and the UI showed 0.00% - root cause of the zero-penalty hole);
     * - when the config is <= 0 / unset, the mandatory default {@link #DEFAULT_PENALTY_PERCENT}% (5% per day) applies;
     * free overdue funding is never allowed.
     */
    public static double penaltyRate(MarketSettings s) {
        if (s == null) {
            return DEFAULT_PENALTY_PERCENT / 100.0;
        }
        double raw = finite(s.loanPenaltyPercent);
        if (raw <= 0) {
            return DEFAULT_PENALTY_PERCENT * 0.01;
        }
        // Compat: if an upstream ever skipped the decimal switch (values above 1 read as percent), normalize to decimal; a configured positive value is respected.
        return raw > 1.0 ? raw * 0.01 : raw;
    }

    /**
     * Whether the credit score suffices for this product. Emergency loans, as the comeback lane, ignore the score;
     * every other product below {@link #SCORE_BORROW_MIN} is refused outright.
     */
    public static boolean scoreAllowsBorrow(int score, int product) {
        if (product == 2) {   // LoanData.PRODUCT_EMERGENCY (this class keeps zero Minecraft dependency, hence the literal 2)
            return true;
        }
        return score >= SCORE_BORROW_MIN;
    }

    /**
     * Emergency-loan entry gate - forbidden once the player's total assets exceed 1000
     * (the emergency loan is the 'dead broke' comeback lane; with assets to spare, take the credit / collateral product instead).
     */
    public static final long EMERGENCY_ASSET_CAP = 1000L;

    public static boolean emergencyAllowed(long totalAssets, long emergencyCap) {
        if (emergencyCap <= 0) {
            return false;
        }
        return Math.max(0, totalAssets) <= EMERGENCY_ASSET_CAP;
    }

    /** Hard cap of one emergency draw (the stricter of this figure and the configured cap). */
    public static long emergencyCap(long configuredCap) {
        long cap = Math.max(0, configuredCap);
        return Math.min(cap, 100L);   // Per-draw hard limit: 100
    }

    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }

    // ------------------------------------------------------------ v2: early repayment

    /**
     * Amount due for a full early settlement (pure function):
     * <pre>
     * due = remaining principal + booked interest x (1 - discount%) + remaining principal x fee%
     * </pre>
     * The discount applies to INTEREST ONLY (principal gets no discount); the fee charges on the remaining principal.
     * Any dirty input (negatives, NaN, over 100%) clamps into the legal range, and the result stays at least the remaining principal.
     */
    public static long prepayAmount(long remainingPrincipal, long accruedInterest,
                                    double discountPercent, double feePercent) {
        long principal = Math.max(0, remainingPrincipal);
        long interest = Math.max(0, accruedInterest);
        double discount = Math.max(0.0, Math.min(100.0, finite(discountPercent))) / 100.0;
        double fee = Math.max(0.0, Math.min(100.0, finite(feePercent))) / 100.0;
        long netInterest = (long) Math.floor(interest * (1.0 - discount));
        long feeAmount = (long) Math.ceil(principal * fee);
        return LoanSchedule.clampL((double) principal + netInterest + feeAmount);
    }

    /** Allocation order of a partial repayment: penalty first, then interest, principal last (all three non-negative, and their sum is identically the payment). */
    public static long[] splitPayment(long payment, long penaltyOwed, long interestOwed, long principalOwed) {
        return splitPayment(payment, penaltyOwed, interestOwed, principalOwed, true);
    }

    /**
     * When {@code allowPrincipal = false}, payments must NEVER
     * be pushed into principal - whatever penalty and interest cannot absorb is refunded verbatim (the caller books the actual deduction).
     * Before the final installment under Interest First, Principal Later, both the manual path and the day-close auto-debit pass through this gate.
     */
    public static long[] splitPayment(long payment, long penaltyOwed, long interestOwed,
                                      long principalOwed, boolean allowPrincipal) {
        long pay = Math.max(0, payment);
        long[] out = new long[3];
        long toPenalty = Math.min(pay, Math.max(0, penaltyOwed));
        pay -= toPenalty;
        long toInterest = Math.min(pay, Math.max(0, interestOwed));
        pay -= toInterest;
        long toPrincipal = allowPrincipal ? Math.min(pay, Math.max(0, principalOwed)) : 0;
        out[0] = toPenalty;
        out[1] = toInterest;
        out[2] = toPrincipal;
        return out;
    }
}
