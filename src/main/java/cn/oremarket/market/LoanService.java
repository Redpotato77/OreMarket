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

import cn.oremarket.config.OreMarketConfig;
import cn.oremarket.market.event.MarketModifiers;
import cn.oremarket.market.log.LogService;
import cn.oremarket.common.LogType;
import cn.oremarket.net.OreMarketNetwork;
import cn.oremarket.net.packet.S2CFeedbackPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import cn.oremarket.common.DebugLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import cn.oremarket.common.LoanRules;
import cn.oremarket.common.StockState;

/**
 * Loan service (addenda four / five / nine): everything runs server-side only; the client merely
 * displays and sends packets, and every request is re-validated here (level / limit / outstanding
 * loan / already borrowed today / bankruptcy cooldown / amount range).
 *
 * Key rules (all in Trading Days; 1 Trading Day = 400 marketTicks):
 *  - Borrowing: level >= 2, no outstanding loan, nothing borrowed yet this Trading Day; the amount
 *    is clamped into [1, credit limit]; borrowed coins never raise the historical peak
 *    (addBalanceNoPeak); due day = borrow day + 5 Trading Days.
 *  - Interest: simple interest, daily = principal x 5%, refreshed at the close; frozen (no further
 *    accrual) once matured; cumulative cap = principal (principal + interest at most 2x), and once
 *    the cap is hit the UI shows "interest cap reached".
 *  - Early repayment (repay) and maturity Forced Liquidation (settleMatured) are two independent
 *    static methods that never call each other and share no flow helpers: repay performs
 *    only the three-steps-then-bankruptcy below -- it never checks the due day, never reads or
 *    writes liquidating, and never triggers any maturity-liquidation logic; settleMatured fires only
 *    on the first marketTick of the 6th Trading Day (the close hook) and in the deferred retries
 *    after it.
 *  - Both flows run the same order; skipping or reordering steps is forbidden:
 *    (1) deduct from the coin balance (when the balance >= principal + interest, deduct, zero the
 *        loan and reply success, entering no liquidation flow at all);
 *    (2) forced sale of sellable positions -- first check whether any sellable position exists, and
 *        skip the entire step if not (market value ascending, ties by alphabetical stock code,
 *        halted stocks skipped, frozen shares untouched);
 *    (3) reclaim ores from the inventory (at the 50% exchange rate, whole stacks only, overage
 *        refunded to the balance);
 *    (4) only when all three sources combined still fall short does bankruptcy settlement begin.
 *        As soon as any step covers principal + interest the chain stops and the remainder stays.
 *  - Every step of repayment / liquidation writes a server log: the principal + interest
 *    total, the coin balance, the sellable-position market value, the inventory ore converted value
 *    and whether bankruptcy was entered -- handy for ruling out false triggers.
 *  - Maturity: normal repayment still works on the due day itself; the first marketTick of the 6th
 *    Trading Day enters Forced Liquidation. When a halt / freeze keeps recovery incomplete, the
 *    liquidation defers and retries, accruing no extra interest meanwhile, and completes the moment
 *    conditions allow.
 *  - Bankruptcy: coins zeroed, positions (frozen included) zeroed, debts cleared, while peak / level
 *    / credit limit do not drop; cooldown = 3 full Trading Days starting the day after bankruptcy
 *    (borrowing is also forbidden on the bankruptcy day itself; "1 day left" shows the day before
 *    borrowing unlocks again).
 *  - Offline: interest accrual and maturity forced selling still run as usual; inventory
 *    reclamation and the bankruptcy verdict need the entity present, so for an offline player we
 *    first take the balance deduction and close positions; whatever cannot be completed defers to
 *    the moment of login, with the chat notice re-issued then.
 */
public final class LoanService {

    /** Literal inventory reclamation order for the builtin stocks. */
    private static final String[] BUILTIN_RECOVERY_CODES = {
            "COAL", "REDSTONE", "LAPIS", "COPPER", "IRON", "GOLD", "DIAMOND", "NETHERITE"
    };

    /**
     * Inventory reclamation order: the 8 builtin stocks in the literal order above, then custom
     * stocks in registry order. Required: bankruptcy settlement can also reclaim the
     * items bound to custom stocks (at 50% of their exchange points); items of delisted stocks are
     * reclaimed as well (reclamation is debt repayment, not a trade); stocks whose item id is
     * invalid are skipped.
     */
    private static List<Stock> recoveryStocks() {
        List<Stock> out = new ArrayList<>();
        for (String code : BUILTIN_RECOVERY_CODES) {
            Stock s = StockRegistry.server().getByCode(code);
            if (s != null && s.item() != null) {
                out.add(s);
            }
        }
        for (Stock s : StockRegistry.server().getAll()) {
            if (s.item() == null || Stock.BUILTIN_CODES.contains(s.code())) {
                continue;
            }
            out.add(s);
        }
        return out;
    }
    /** Logs for the repayment / maturity-liquidation flows: the money-raising detail of every step is printed. */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("OreMarket/Loan");

    /**
     * Retry cooldown for deferred settlement against offline players: while the
     * player is offline and recovery falls short, every marketTick used to rescan both order books,
     * value the whole market and re-check every live event -- and it <b>never converges</b> (an
     * offline player's balance and positions do not change by themselves; for external changes such
     * as an admin topping up money, probing again after 2 seconds is plenty). Online players are
     * unaffected and still get a try every marketTick; the moment a player logs in, onPlayerLogin
     * unconditionally makes up the work, so the cooldown is practically invisible to its owner.
     */
    private static final Map<UUID, Long> OFFLINE_RETRY_TICK = new java.util.HashMap<>();
    private static final long OFFLINE_RETRY_COOLDOWN_TICKS = 40L;   // 2 seconds

    /** Server stop: clear the offline cooldown table (memory-leak check: static tables must not linger across saves). */
    public static void clearAll() {
        OFFLINE_RETRY_TICK.clear();
    }

    private LoanService() {
    }

    /**
     * Dynamic credit limit: the base limit for the level times the credit-score
     * multiplier plus a current-asset bonus, then cut by overdue haircuts. Old call sites (commands,
     * UI hints, the short-selling quota) keep using this entry point, so the definition stays
     * exactly in line with the borrow checks.
     */
    public static long creditLimit(MarketSavedData data, UUID id) {
        return dynamicLimit(data, id, LoanData.PRODUCT_CREDIT);
    }

    /**
     * Borrowable limit per product:
     *  - Credit Loan: base level limit x score multiplier + asset bonus;
     *  - Collateralized Loan: the credit-loan figure plus sellable-position market value x loanCollateralLimitFactor;
     *  - Emergency Loan: a flat loanEmergencyCap (the comeback channel during the bankruptcy cooldown; ignores the base level limit);
     *  - short Margin Loan: same as the credit loan (the Margin itself is policed by the short module against the limit).
     * Overdue haircuts ({@code limitStrikes}) recover one tier every loanLimitRecoverDays on their
     * own, and settling a loan restores one tier immediately -- satisfying "the limit climbs back
     * after Payoff (full settlement) and shrinks after being Overdue".
     */
    public static long dynamicLimit(MarketSavedData data, UUID id, int product) {
        MarketSettings s = MarketSettings.server();
        LoanData loan = data.loanOrNull(id);
        if (product == LoanData.PRODUCT_EMERGENCY) {
            // Hard cap of 100 on the emergency limit (take whichever is stricter, this or the configured cap).
            return LoanRules.emergencyCap(s.loanEmergencyCap);
        }
        long base = s.creditLimit(data.playerTier(id));
        if (base <= 0) {
            return 0;
        }
        int score = creditScore(data, id);
        boolean collateral = product == LoanData.PRODUCT_COLLATERAL;
        long collateralValue = collateral ? sellableValue(data, id) : 0;
        int strikes = loan == null ? 0 : effectiveStrikes(loan, data.tradeDay(), s);
        // The asset bonus reads the <b>current total assets</b>, so the limit rises and falls with
        // the player's present holdings. There is no gameplay cap on this bonus; only the configured
        // asset percentage, credit score and overdue haircut shape the result.
        long assets = Math.max(0L, data.calculateTotalAssets(id));
        return LoanRules.dynamicLimit(s, base, score, assets, collateralValue, strikes, collateral);
    }

    /**
     * Credit score: five factors -- repayment record / overdue count / bankruptcy
     * history / total assets / level, computed live and never persisted. For a
     * player without a loan profile is scored from <b>the same set of inputs</b> (the calculation
     * passed 0 assets here, so the instant "the first borrow creates the profile" gave the score a
     * phantom +60 -- simply taking out a loan must never change the credit score).
     */
    public static int creditScore(MarketSavedData data, UUID id) {
        LoanData l = data.loanOrNull(id);
        MarketSettings s = MarketSettings.server();
        int onTime = l == null ? 0 : l.onTimeRepays;
        int overdue = l == null ? 0 : l.overdueTotal;
        int bankrupt = l == null ? 0 : l.bankruptcies;
        return LoanRules.creditScore(onTime, overdue, bankrupt, data.peak(id),
                data.playerTier(id), s.loanScorePerOnTime, s.loanScorePerOverdue, s.loanScorePerBankrupt,
                s.loanScoreAssetStep, s.loanScorePerTier);
    }

    /**
     * Overdue haircuts actually in effect: one tier lapses automatically every
     * loanLimitRecoverDays Trading Days, never falling below the portion already repaid (settling a
     * loan proactively drops one more tier; see finishLoan).
     */
    private static int effectiveStrikes(LoanData loan, long day, MarketSettings s) {
        int strikes = Math.max(0, loan.limitStrikes);
        if (strikes <= 0 || loan.limitStrikeDay == Long.MIN_VALUE || s.loanLimitRecoverDays <= 0) {
            return strikes;
        }
        long passed = Math.max(0, day - loan.limitStrikeDay);
        return Math.max(0, (int) (strikes - passed / s.loanLimitRecoverDays));
    }

    /**
     * Whether loan funds are still exchangeable for ores: borrowed money may
     * only be traded on the market, not swapped into ore and then "laundered" into assets through
     * bankruptcy. Exchangeable amount = balance - unspent borrowed funds.
     */
    public static long exchangeableCoins(MarketSavedData data, UUID id) {
        return Math.max(0, data.balance(id) - lockedLoanFunds(data, id));
    }

    /** Coins locked by the loan and not exchangeable for ores (the rejection notice and the UI hint share one definition). */
    public static long lockedLoanFunds(MarketSavedData data, UUID id) {
        LoanData l = data.loanOrNull(id);
        return l == null ? 0 : Math.max(0, l.loanFunds);
    }

    /**
     * Coins that may leave the wallet for an ore withdrawal or a P2P listing: neither the
     * unspent-loan purse nor money already pledged by a resting order is available here.
     */
    public static long withdrawableCoins(MarketSavedData data, UUID id) {
        if (data == null || id == null) {
            return 0L;
        }
        long freeOfLoan = exchangeableCoins(data, id);
        long lockedByOrders = data.lockedCoins(id);
        return lockedByOrders >= freeOfLoan ? 0L : freeOfLoan - lockedByOrders;
    }

    /**
     * While a Margin Loan is outstanding it boosts the
     * short-selling quota by the unpaid principal. The money never enters the wallet, the bonus
     * returns to zero on payoff; short openings are then limited only by wallet margin and the stock's short pool.
     */
    public static long marginLoanBonus(MarketSavedData data, UUID id) {
        LoanData l = data.loanOrNull(id);
        if (l == null || !l.hasLoan() || l.product != LoanData.PRODUCT_MARGIN) {
            return 0L;
        }
        return Math.max(0L, l.amount);
    }

    /** Interest-rate event factor: active events push the funding cost up or down, clamped to [-1, +1]. */
    public static double eventRateFactor(MarketSavedData data) {
        double factor = 0;
        for (cn.oremarket.market.ActiveEvent e : data.events) {
            if (e == null || e.template == null) {
                continue;
            }
            factor += rateWeightOf(e.template.id);
        }
        if (!Double.isFinite(factor)) {
            return 0;
        }
        return Math.max(-1.0, Math.min(1.0, factor));
    }

    /**
     * Event -> interest-rate weight table: tightening events raise the borrowing cost,
     * easing events lower it. Weights are looked up by event id only -- no new field is added to the
     * event system, so the serialized shape of events in NBT stays exactly the same.
     */
    private static double rateWeightOf(String eventId) {
        if (eventId == null) {
            return 0;
        }
        switch (eventId) {
            case "central_bank":              // central bank tightening (rate hike)
                return 0.8;
            case "legacy_rate_hike":          // rate-hike transmission
                return 0.6;
            case "chain_rate_digest":         // rate digestion
                return 0.3;
            case "liquidity_dry":             // liquidity drying up
                return 0.7;
            case "market_crash":
            case "black_swan":
            case "total_halt":
            case "halt_wave":                 // banks ration credit during a crisis
                return 0.9;
            case "combo_haven_fail":
                return 0.4;
            case "official_subsidy":          // fiscal subsidy = easing
                return -0.5;
            case "policy_boost":
                return -0.3;
            case "golden_age":
                return -0.6;
            case "chain_rescue_action":       // market rescue action
                return -0.9;
            case "combo_market_chaos":
                return 0.5;
            default:
                return 0;
        }
    }

    // ------------------------------------------------------------ Borrowing

    /** Legacy entry point: defaults to Credit Loan + balloon (principal and interest at maturity) + a single installment, so commands and old call sites keep working. */
    public static boolean borrow(ServerPlayer player, long amount) {
        return borrow(player, amount, LoanData.PRODUCT_CREDIT,
                cn.oremarket.common.LoanSchedule.MODE_BALLOON, 1);
    }

    /**
     * The borrow entry point with hard server validation. The client sends intent +
     * numbers only, and every rule is recomputed here:
     *  1. legal product (dirty values clamped into 0-3); apart from the emergency loan, the level
     *     gate must be passed and a positive limit required;
     *  2. not inside the bankruptcy cooldown (the emergency loan is the sole exception = the
     *     comeback channel after bankruptcy);
     *  3. no un-forgiven bankruptcy debt (clawbackDebt must be paid off first, closing the
     *     "borrow, then default through bankruptcy" hole);
     *  4. nothing borrowed yet this Trading Day;
     *  5. amount within [1, product limit] (the limit already carries the score multiplier / asset
     *     bonus / Collateral conversion / overdue haircut);
     *  6. a Collateralized Loan must have sellable positions as Collateral; a short Margin Loan must
     *     already hold a Short Position.
     * Once these pass, the loan locks in: daily rate (fixed + floating + risk premium - good-debtor
     * discount, clamped by the hard cap), term, interest-cap multiplier, repayment mode and
     * installment count -- later config changes only affect new loans.
     */
    public static boolean borrow(ServerPlayer player, long amount, int product, int mode, int installments) {
        // Debug-chain entry point -- when enabled, mint a [T-xxxxxx] and let every debit/grant log in the chain share the same TraceID.
        final String trace = DebugLog.on() ? DebugLog.begin() : null;
        try {
            // Anti-abuse gate for concurrency -- one player's borrow processing
            // may not be re-entered (mouse-spam packets are already serial on the main thread, but
            // this also slams shut any "came in again while processing" path, guaranteeing that
            // "coins granted + limit occupied" runs at most once per player at any moment).
            if (!BORROW_IN_FLIGHT.add(player.getUUID())) {
                // rejectKey already logged the interception at 100% (reason key + player); no need to repeat it here.
                rejectKey(player, "oremarket.loan.err.busy");
                return false;
            }
            try {
                return doBorrow(player, amount, product, mode, installments);
            } finally {
                BORROW_IN_FLIGHT.remove(player.getUUID());
            }
        } finally {
            if (trace != null) {
                DebugLog.end();
            }
        }
    }

    /** Set of players whose borrow request is being processed (added when borrow is entered, removed when it returns; see the gate above). */
    private static final java.util.Set<java.util.UUID> BORROW_IN_FLIGHT =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static boolean doBorrow(ServerPlayer player, long amount, int product, int mode, int installments) {
        MinecraftServer server = player.server;
        if (server == null) {
            return false;
        }
        MarketSavedData data = MarketSavedData.get(server);
        MarketSettings settings = MarketSettings.server();
        UUID id = player.getUUID();
        LoanData loan = data.loan(id);
        long day = data.tradeDay();
        int prod = LoanData.clampProduct(product);
        int m = cn.oremarket.common.LoanSchedule.clampMode(mode);
        long limit = dynamicLimit(data, id, prod);

        if (prod == LoanData.PRODUCT_EMERGENCY) {
            if (settings.loanEmergencyCap <= 0) {
                rejectKey(player, "oremarket.loan.err.product_off");
                return false;
            }
            // Emergency-loan asset gate -- total assets above 1000 forbid borrowing it (it is a comeback channel, not extra capacity).
            long assets = Math.max(0L, data.calculateTotalAssets(id));
            if (!LoanRules.emergencyAllowed(assets, settings.loanEmergencyCap)) {
                rejectKey(player, "oremarket.loan.err.emergency_rich",
                        String.valueOf(LoanRules.EMERGENCY_ASSET_CAP));
                return false;
            }
        } else if (data.playerTier(id) < LoanRules.requiredTier() || limit <= 0) {
            rejectKey(player, "oremarket.loan.borrow_hint",
                    String.valueOf(MarketSettings.current().levelThreshold(LoanRules.requiredTier())));
            return false;
        }
        // Reject the loan when the credit score is too low (emergency loan excepted).
        int score = creditScore(data, id);
        if (!LoanRules.scoreAllowsBorrow(score, prod)) {
            rejectKey(player, "oremarket.loan.err.score_low",
                    String.valueOf(LoanRules.SCORE_BORROW_MIN), String.valueOf(score));
            return false;
        }
        if (loan.hasLoan()) {
            rejectKey(player, "oremarket.loan.err.has_loan");
            return false;
        }
        if (loan.clawbackDebt > 0) {
            rejectKey(player, "oremarket.loan.err.clawback", String.valueOf(loan.clawbackDebt));
            return false;
        }
        if (loan.shortDebtDue() > 0 && prod != LoanData.PRODUCT_EMERGENCY) {
            rejectKey(player, "oremarket.loan.err.short_debt", String.valueOf(loan.shortDebtDue()));
            return false;
        }
        if (inCooldown(loan.cooldownEndDay, day) && prod != LoanData.PRODUCT_EMERGENCY) {
            rejectKey(player, "oremarket.loan.err.cooldown", String.valueOf(cooldownLeft(loan.cooldownEndDay, day)));
            return false;
        }
        if (loan.borrowedOn(day)) {
            rejectKey(player, "oremarket.loan.err.borrowed_today");
            return false;
        }
        if (prod == LoanData.PRODUCT_COLLATERAL && sellableValue(data, id) <= 0) {
            rejectKey(player, "oremarket.loan.err.no_collateral");
            return false;
        }
        if (prod == LoanData.PRODUCT_MARGIN && data.shortsOf(id).isEmpty()) {
            rejectKey(player, "oremarket.loan.err.no_short");
            return false;
        }
        if (amount < LoanRules.minAmount() || amount > limit) {
            rejectKey(player, "oremarket.loan.err.amount_range", String.valueOf(limit));
            return false;
        }

        // New system loans lock the configured trading-day length per installment. The emergency loan
        // stays at 1 installment, but still uses the same configured base period; existing loans keep
        // their stored lockedTermDays and are never rescheduled by config reload.
        // Balloon repayment (principal and interest at maturity) <b>may</b> pick 1-5 installments:
        // the scheduled payment for the first n-1 installments is always 0, and principal + interest
        // are collected in one go on the last day.
        int n = prod == LoanData.PRODUCT_EMERGENCY
                ? 1 : cn.oremarket.common.LoanSchedule
                .clampInstallments(installments);
        int term = cn.oremarket.common.LoanSchedule
                .totalTermDays(n, settings.loanTermDays);
        double rate = lockedRateFor(settings, data, id, prod, n);

        loan.amount = amount;
        loan.principal = amount;
        loan.interest = 0;
        loan.penalty = 0;
        loan.paidPrincipal = 0;
        loan.paidInterest = 0;
        loan.paidPenalty = 0;
        loan.nextInst = 1;
        loan.overdueStrikes = 0;
        loan.accruedDay = day;
        loan.lockedDailyRate = rate;
        loan.lockedTermDays = term;
        loan.lockedCapMultiplier = settings.loanInterestCapMultiplier;
        loan.product = prod;
        loan.mode = m;
        loan.installments = n;
        loan.dueDay = day + term;
        loan.borrowDay = day;
        // Balloon repayment may schedule 1-5
        // installments with the first n-1 due amounts equal to 0. An installment "due 0" carries no
        // debt and must never serve as the overdue baseline, so right at booking the index advances
        // to the first installment that actually carries an amount. For the other three repayment
        // modes the very first installment has an amount, so they still land on period 1.
        advanceInstallmentIndex(loan, day);
        loan.liquidating = false;
        loan.catchupKind = 0;
        data.setDirty();
        // Borrowed coins never raise the peak: only ore exchanges and stock sales count toward it.
        // The system reserve pool (the part of repayments not destroyed) pays out first, and new
        // coins are minted only for the shortfall -- loans create coins and repayment destroys them
        // by default, keeping the long-run supply under control.
        long fromReserve = Math.min(Math.max(0L, data.loanReserve), amount);
        if (fromReserve > 0) {
            data.loanReserve -= fromReserve;
        }
        // The Margin Loan <b>skips the wallet</b> -- the
        // borrowed amount feeds the short-sellable quota directly (ShortService's quota plus
        // marginLoanBonus), touching neither the balance nor the loan-funds bag.
        // repayment still follows the rule "principal is deducted from coins, and
        // any shortfall turns into shortDebt" through the generic debit path of repay / autoRepay --
        // there is <b>no</b> "margin loans don't touch the wallet" special branch; this product
        // differs from the other three in exactly two ways: (1) disbursement skips the wallet;
        // (2) no loan-funds locking (there are no ore-exchange coins to launder).
        // After the short-selling quota was removed, margin loans needed to
        // "skip the wallet on disbursement and only top up the short-sellable quota", but the short
        // quota has since been deleted outright -- if disbursement still skipped the wallet, a
        // borrower would get neither coins nor quota, a pure-loss "all debt, no money" product.
        // Final ruling: every product disburses into the wallet (the margin product still requires
        // an existing Short Position to qualify, and the blow-up fallback still runs through
        // callMarginLoan unchanged; the product semantics return to "a cash loan topping up the
        // Margin of an existing short book").
        long walletBefore = data.balance(id);
        data.addBalanceNoPeak(id, amount);
        // Anti-abuse: remember "borrowed money not yet spent"; that portion cannot be
        // exchanged for ores (see exchangeableCoins) but may buy stocks, pay fees and repay early
        // -- the money really is with the player.
        loan.loanFunds = Long.MAX_VALUE - loan.loanFunds > amount
                ? loan.loanFunds + amount : Long.MAX_VALUE;
        if (DebugLog.on()) {
            DebugLog.info("oremarket.debug.log.loan_borrow",
                    player.getName().getString(), Long.toString(amount),
                    Long.toString(walletBefore), Long.toString(walletBefore + amount));
        }
        // Planned interest now goes through LoanCalculator (the same entry the client detail / schedule views use)
        long plannedInterest = cn.oremarket.common.LoanCalculator
                .plannedInterest(loan.schedule());
        long finalPayoff = cn.oremarket.common.LoanCalculator
                .plannedPayoff(amount, loan.schedule());
        // Argument order = the order of the %s placeholders in the template: amount / product / repayment mode / installments / due day / total due / daily rate
        LogService.pushKey(player, data, LogType.SYSTEM, "oremarket.loan.log.borrow",
                amount, productKey(prod), modeKey(m), n, loan.dueDay, finalPayoff,
                ratePercentText(rate));
        noticeKeyTicks(player, "oremarket.loan.toast.borrow_ok", true, 50,
                String.valueOf(amount), String.valueOf(n), String.valueOf(loan.dueDay));
        LOG.info("[borrow] player {} product {} mode {} installments {} amount {} rate {} due {} reserve {} minted {}",
                DebugLog.ascii(player.getName().getString()), prod, m, n, amount, rate, loan.dueDay,
                fromReserve, amount - fromReserve);
        MarketEngine.pushAfterAction(server, player);
        return true;
    }

    /** Product factor: the configured multipliers for Emergency / Collateralized / Margin loans; the credit loan is 1. */
    private static double productRateFactor(MarketSettings s, int product) {
        if (product == LoanData.PRODUCT_EMERGENCY) {
            return s.loanEmergencyRateFactor;
        }
        if (product == LoanData.PRODUCT_COLLATERAL) {
            return s.loanCollateralRateFactor;
        }
        if (product == LoanData.PRODUCT_MARGIN) {
            return s.loanMarginRateFactor;
        }
        return 1.0;
    }

    /**
     * Rate locked in at borrow time:
     * the quote (base x credit discount x event) times the product factor times the term
     * multiplier, hard-capped at 1%. Client-side estimates run through {@link #quoteRate} +
     * {@link LoanRules#lockedDailyRate}, the exact same chain.
     */
    private static double lockedRateFor(MarketSettings s, MarketSavedData data, UUID id,
                                        int product, int installments) {
        double quote = quoteRate(data, id);
        return LoanRules.lockedDailyRate(quote, productRateFactor(s, product),
                installments, s.loanRateCapPercent);
    }

    /** Product -> language key (network packets carry keys only; the UI resolves translations from them). */
    public static String productKey(int product) {
        return switch (LoanData.clampProduct(product)) {
            case LoanData.PRODUCT_COLLATERAL -> "oremarket.loan.product.collateral";
            case LoanData.PRODUCT_EMERGENCY -> "oremarket.loan.product.emergency";
            case LoanData.PRODUCT_MARGIN -> "oremarket.loan.product.margin";
            default -> "oremarket.loan.product.credit";
        };
    }

    /** Repayment mode -> language key. */
    public static String modeKey(int mode) {
        return switch (cn.oremarket.common.LoanSchedule.clampMode(mode)) {
            case 1 -> "oremarket.loan.mode.interest_first";
            case 2 -> "oremarket.loan.mode.annuity";
            case 3 -> "oremarket.loan.mode.linear";
            default -> "oremarket.loan.mode.balloon";
        };
    }

    /**
     * The "quoted daily rate at this moment" on credit-loan terms: computed server-side
     * from the current event drift + the player's credit and sent with the snapshot for the client
     * to estimate; the actual dealing rate is still re-locked at the moment of borrowing (never
     * taken from the client's value).
     */
    public static double quoteRate(MarketSavedData data, UUID id) {
        MarketSettings s = MarketSettings.server();
        return LoanRules.quoteDailyRate(s.loanDailyInterestPercent * 100.0, creditScore(data, id),
                s.loanInterestVariance, eventRateFactor(data), s.loanRateCapPercent,
                s.loanCreditScoreEnabled);
    }

    /** Daily rate -> percentage string (for logs and packet arguments; carries no localized text at all). */
    public static String ratePercentText(double dailyRate) {
        return cn.oremarket.common.Num.fixed(dailyRate * 100.0, 2);
    }

    // ------------------------------------------------------------ Normal repayment vs. early settlement

    /** The two player-facing repayment intents; no amount value may select between them. */
    private enum RepaymentIntent {
        CURRENT_BILL,
        EARLY_SETTLEMENT
    }

    /**
     * Pays only the currently payable bill. This is the entry used by the normal repayment buttons
     * ("pay 10 / pay 100 / repay all"); "repay all" means all of the current bill, not early
     * settlement of future installments. The server re-trims the amount against the balance, and the
     * deduction order is fixed as "penalty -> interest -> principal"; once principal and interest
     * are both zero the loan is settled (finishLoan awards the credit bonus). This method only
     * repays: it never reads or writes liquidating and never triggers maturity liquidation (same
     * discipline as before).
     */
    public static boolean repayCurrentBill(ServerPlayer player, long amount) {
        return repayWithIntent(player, amount, RepaymentIntent.CURRENT_BILL);
    }

    /** Pays the current payable bill in full, subject to the wallet balance and period gates. */
    public static boolean repayCurrentBill(ServerPlayer player) {
        return repayCurrentBill(player, Long.MAX_VALUE);
    }

    /** Settles the whole outstanding loan through the configured early-repayment rules. */
    public static boolean prepay(ServerPlayer player) {
        return repayWithIntent(player, 0L, RepaymentIntent.EARLY_SETTLEMENT);
    }

    /**
     * Shared tracing wrapper. Keep the intent at this boundary explicit: normal repayment and early
     * settlement must never be selected by a caller's ambiguous amount or by a reused UI action.
     */
    private static boolean repayWithIntent(ServerPlayer player, long amount, RepaymentIntent intent) {
        // Repayment-chain TraceID -- every debitCoins snapshot inside the chain shares one [T-xxxxxx].
        final String trace = DebugLog.on() ? DebugLog.begin() : null;
        try {
            return repayInner(player, amount, intent);
        } finally {
            if (trace != null) {
                DebugLog.end();
            }
        }
    }

    private static boolean repayInner(ServerPlayer player, long amount, RepaymentIntent prepay) {
        MinecraftServer server = player.server;
        if (server == null) {
            return false;
        }
        MarketSavedData data = MarketSavedData.get(server);
        UUID id = player.getUUID();
        LoanData loan = data.loanOrNull(id);
        if (loan == null) {
            rejectKey(player, "oremarket.loan.err.no_loan");
            return false;
        }
        MarketSettings s = MarketSettings.server();
        // With no outstanding principal + interest but still owing bankruptcy debt / the Forced
        // Liquidation shortfall, this entry serves specifically to settle those; otherwise the
        // channel would lock the player out for good (unable to borrow, unable to pay off either).
        if (!loan.hasLoan()) {
            return repayHangover(player, server, data, id, loan, amount);
        }
        final long today = data.tradeDay();
        // RepaymentIntent, not the amount, decides which channel this is:
        // - CURRENT_BILL: "pay 1 / 10 / 100 / all" -- may only touch what is already due.
        // - EARLY_SETTLEMENT: the dedicated "prepay" button -- may close the whole loan early.
        // A huge amount (STEP_ALL -> Long.MAX_VALUE) is just "fill whatever is due", never a
        // request to skip ahead: the bill is still capped by the schedule and by the book debt.
        // Anything beyond the current bill stays in the wallet; it is never swept into a future
        // installment, which is what used to make "repay all" look like it had prepayed.
        // Only EARLY_SETTLEMENT may reach future principal and apply the discount / fee pricing.
        // Shortfalls are always split penalty -> interest -> principal, so a partial debit can
        // never be misattributed to principal it has not yet reached.
        // These gates live at the server boundary on purpose: a client may lie about the amount,
        // but it cannot choose the channel - that is fixed by which button was clicked.
        // Balloon loans have a zero-bill window before the final installment; normal repayment is
        // rejected there without a debit or an overdue mark, and only EARLY_SETTLEMENT bypasses it.
        if (prepay != RepaymentIntent.EARLY_SETTLEMENT && cn.oremarket.common.LoanSchedule
                .balloonZeroBillPeriod(loan.mode, loan.installments,
                        today, loan.dueDay)) {
            rejectKey(player, "oremarket.loan.err.balloon_zero");
            return false;
        }
        long payoff = loan.payoff();
        // Normal repayment is capped at the current payable bill; future periods stay closed until
        // their own window. Early settlement uses a separate path and may include the principal.
        boolean allowPrincipal = principalAllowed(loan, today, prepay == RepaymentIntent.EARLY_SETTLEMENT);
        cn.oremarket.common.LoanSchedule.Payable payable =
                loan.payable(today);
        // The cap itself comes from LoanCalculator.payableBill: the client's "due this period" row calls the very same function
        long bill = prepay == RepaymentIntent.EARLY_SETTLEMENT
                ? payoff : Math.min(payoff, loan.payableBill(today, allowPrincipal));
        if (prepay != RepaymentIntent.EARLY_SETTLEMENT && payable.early && loan.lastAdvanceDay == today) {
            rejectKey(player, "oremarket.loan.err.period_locked",
                    String.valueOf(loan.nextInst), String.valueOf(today + 1));
            return false;
        }
        // Early settlement uses the configured discount / fee; normal payment is capped at the current
        // bill and book debt, so STEP_ALL=Long.MAX_VALUE cannot overcharge or make money disappear.
        long want = prepay == RepaymentIntent.EARLY_SETTLEMENT
                ? Math.min(payoff, LoanRules.prepayAmount(loan.amount, loan.interestDue(),
                        s.loanPrepayDiscountPercent, s.loanPrepayFeePercent))
                : Math.min(bill, Math.max(0, amount));
        if (want <= 0) {
            // The bill for the current period is already settled and the next period's bill window
            // has not opened (ruled: a period becomes payable only once it has entered
            // its own accrual window) -- tell the player exactly which period opens on which
            // Trading Day, so the button does not look broken.
            long openDay = cn.oremarket.common.LoanSchedule
                    .firstOpenDay(loan.schedule(), loan.nextInst, Long.MIN_VALUE);
            if (prepay != RepaymentIntent.EARLY_SETTLEMENT && payable.rows == 0
                    && openDay > Long.MIN_VALUE && openDay > today) {
                rejectKey(player, "oremarket.loan.err.period_not_open",
                        String.valueOf(loan.nextInst), String.valueOf(openDay));
                return false;
            }
            // The current-period bill itself being 0 (interest already settled / empty period) is a different matter from "bad amount"; report them separately.
            rejectKey(player, bill <= 0 ? "oremarket.loan.err.period_settled"
                    : "oremarket.loan.err.amount_range", String.valueOf(payoff));
            return false;
        }
        // Anti-underflow: split per the rules first, then deduct the balance by the
        // amount actually applied -- while Interest First, Principal Later has not reached its final
        // period, any excess stays in the wallet as is; never shove a balance that cannot even cover
        // the interest into principal, and never let money disappear.
        // The split components come from the "payable period" (not the whole book position), so the
        // cumulative principal / interest repaid on the books always matches the repayment
        // schedule's per-row allocation, and the UI never shows two conflicting attributions.
        long interestBill = prepay == RepaymentIntent.EARLY_SETTLEMENT ? loan.interestDue() : payable.interest;
        long principalBill = prepay == RepaymentIntent.EARLY_SETTLEMENT
                ? loan.amount : (allowPrincipal ? payable.principal : 0L);
        long[] split = LoanRules.splitPayment(want, loan.penaltyDue(),
                interestBill, principalBill, allowPrincipal);
        long applied = split[0] + split[1] + split[2];
        if (applied <= 0) {
            rejectKey(player, allowPrincipal
                    ? "oremarket.loan.err.amount_range" : "oremarket.loan.err.interest_only",
                    String.valueOf(payoff));
            return false;
        }
        long pay = debitCoins(data, id, applied);
        if (pay <= 0) {
            rejectKey(player, "oremarket.loan.err.no_coins", String.valueOf(payoff));
            return false;
        }
        if (pay < applied) {
            split = LoanRules.splitPayment(pay, loan.penaltyDue(),
                    interestBill, principalBill, allowPrincipal);
            applied = split[0] + split[1] + split[2];
        }
        applySplit(loan, split);
        advanceInstallmentIndex(loan, today);
        // Paid up to the settlement price: the remaining book interest is waived in one stroke by the discount (otherwise, after the discount, a little bit could never be repaid)
        if (prepay == RepaymentIntent.EARLY_SETTLEMENT && pay >= want) {
            loan.amount = 0;
            loan.interest = 0;
            loan.penalty = 0;
        }
        data.setDirty();
        LOG.info("[repay] player {} paid {} penalty {} interest {} principal {} remain payoff {} period bill {}",
                DebugLog.ascii(player.getName().getString()), pay, split[0], split[1], split[2],
                loan.payoff(), bill);
        if (loan.amount <= 0 && loan.penalty <= 1e-9 && loan.interestDue() <= 0) {
            finishLoan(server, data, id, loan, pay, false,
                    "oremarket.loan.log.repay", pay, Math.max(0, data.balance(id)));
            return true;
        }
        // P2P and this payment did not settle the loan: hand the applied amount to the lender on the spot (the final payoff is delivered via routeLoanRepayment, so neither path double-pays)
        deliverP2PRepayment(server, data, id, loan, split);
        LogService.pushKey(player, data, LogType.SYSTEM, "oremarket.loan.log.repay_part",
                pay, split[2], loan.amount, loan.interestDue());
        noticeKeyTicks(player, "oremarket.loan.toast.repay_ok", true, 50,
                String.valueOf(pay), String.valueOf(loan.amount));
        MarketEngine.pushAfterAction(server, player);
        return true;
    }

    /**
     * While Interest First, Principal Later has not
     * reached its final installment, repayment must never be applied to principal. Payoff (full
     * settlement, the EARLY_SETTLEMENT intent) and the balloon / equal-scheduled modes are not gated here. The predicate
     * itself lives in the pure-calculation class {@code LoanSchedule.interestFinalNotReached}
     * (shared with the offline harness).
     */
    private static boolean principalAllowed(LoanData loan, long day, boolean earlySettlement) {
        return !cn.oremarket.common.LoanSchedule
                .interestFinalNotReached(loan.mode, day, loan.dueDay, loan.nextInst,
                        loan.installments) || earlySettlement;
    }

    /** Applies the split to the books: penalty / interest / principal all non-negative, and their sum always equals the amount actually debited. */
    private static void applySplit(LoanData loan, long[] split) {
        loan.applySplit(split);     // The booking itself lives in LoanData (the offline verification harness runs the very same code)
    }

    /**
     * Anti-underflow: debit only while the balance covers it, returning the amount
     * actually taken (always <= the balance, always >= 0). {@code addBalance(-pay)} must never push
     * a player's balance negative.
     */
    private static long debitCoins(MarketSavedData data, UUID id, long want) {
        long need = Math.max(0L, want);
        if (need <= 0) {
            return 0L;
        }
        long bal = Math.max(0L, data.balance(id));
        long pay = Math.min(need, bal);
        if (pay > 0) {
            data.addBalance(id, -pay);
        }
        // Every debit through this entry (repayment / forced-sell recovery / bankruptcy
        // settlement / shortfall repayment) leaves a 100% trail -- balance before, requested,
        // actually debited, balance after -- to pin down "where the money went" in one step.
        if (DebugLog.on()) {
            DebugLog.info("oremarket.debug.log.loan_debit",
                    Long.toString(bal), Long.toString(need), Long.toString(pay),
                    Long.toString(bal - pay));
        }
        return pay;
    }

    /**
     * Standalone entry for settling the leftover debt: pays off
     * only "un-forgiven bankruptcy debt + Forced Liquidation shortfall".
     *
     * <p>Why {@code repay(player, amount)} cannot be reused: that path targets the <b>outstanding
     * principal + interest</b>; when a player owes both a live loan and the liquidation shortfall
     * (a short blow-up on a price gap does exactly this), the money gets poured into the live loan
     * books while the shortfall stays untouched -- and {@code borrow()} then blocks new ordinary
     * loans on the shortDebt gate, leaving the player "unable to pay the shortfall while the loan
     * is live, back to square one after settling it"; worst case, no channel at all during the
     * cooldown. This entry is fully isolated from the live loan books: it touches neither amount /
     * interest / penalty nor force-sells any position.
     */
    public static boolean repayHangover(ServerPlayer player, long amount) {
        if (player == null) {
            return false;
        }
        MinecraftServer server = player.server;
        MarketSavedData data = MarketSavedData.get(server);
        UUID id = player.getUUID();
        LoanData loan = data.loan(id);
        return repayHangover(player, server, data, id, loan, amount);
    }

    /**
     * Settles "un-forgiven bankruptcy debt + Forced Liquidation shortfall" (the dedicated entry for
     * when there is no outstanding principal + interest). Pays down clawbackDebt first, then
     * shortDebt; never underflows.
     */
    private static boolean repayHangover(ServerPlayer player, MinecraftServer server,
                                         MarketSavedData data, UUID id, LoanData loan, long amount) {
        long owe = LoanRules.saturatingAdd(loan.clawbackDebt, loan.shortDebtDue());
        if (owe <= 0) {
            rejectKey(player, "oremarket.loan.err.no_loan");
            return false;
        }
        long want = Math.max(0, amount > 0 ? amount : owe);
        long payBack = debitCoins(data, id, Math.min(want, owe));
        if (payBack <= 0) {
            rejectKey(player, "oremarket.loan.err.no_coins", String.valueOf(owe));
            return false;
        }
        long left = payBack;
        long clawTake = Math.min(left, Math.max(0L, loan.clawbackDebt));
        loan.clawbackDebt = Math.max(0L, loan.clawbackDebt - clawTake);
        left -= clawTake;
        long shortTake = Math.min(left, loan.shortDebtDue());
        loan.shortDebt = Math.max(0L, loan.shortDebt - shortTake);
        loan.lastSettledDay = data.tradeDay();
        data.setDirty();
        String key = clawTake > 0 ? "oremarket.loan.log.clawback_paid" : "oremarket.loan.log.short_debt_paid";
        long remain = LoanRules.saturatingAdd(loan.clawbackDebt, loan.shortDebtDue());
        LogService.pushKey(player, data, LogType.SYSTEM, key, payBack, remain);
        noticeKey(player, key, true, String.valueOf(payBack), String.valueOf(remain));
        LOG.info("[hangover] player {} paid {} claw {} short {} remain {}",
                DebugLog.ascii(player.getName().getString()), payBack, loan.clawbackDebt, loan.shortDebt, remain);
        MarketEngine.pushAfterAction(server, player);
        return true;
    }

    /**
     * Immediate delivery for a P2P loan: call <b>only when this
     * repayment did not settle the loan</b>, handing the actually applied amount to the lender on
     * the spot -- otherwise the money vanishes and the lender never gets paid; the final payoff is
     * delivered by routeLoanRepayment, so each path runs exactly once with no double payment.
     * {@code p2pDelivered} is bookkeeping only.
     */
    private static void deliverP2PRepayment(MinecraftServer server, MarketSavedData data, UUID borrower,
                                            LoanData loan, long[] split) {
        if (loan.p2pLender == null) {
            return;
        }
        long applied = split[0] + split[1] + split[2];
        if (applied <= 0) {
            return;
        }
        data.addBalance(loan.p2pLender, applied);
        loan.p2pDelivered = Math.max(0, loan.p2pDelivered + applied);
        notifyLenderIncoming(server, data, loan.p2pLender, borrower, applied);
    }

    /**
     * Coins arriving on a P2P listing are the lender's money moving through someone else's
     * repayment: online lenders get a toast + log line, offline lenders the persisted login
     * notice - incoming money never lands without a word.
     */
    private static void notifyLenderIncoming(MinecraftServer server, MarketSavedData data,
                                             UUID lender, UUID borrower, long paid) {
        if (lender == null || paid <= 0 || server == null) {
            return;
        }
        String name = data.nameOf(borrower);
        ServerPlayer lp = server.getPlayerList().getPlayer(lender);
        if (lp != null) {
            LogService.pushKey(server, data, lender, LogType.SYSTEM,
                    "oremarket.toast.p2p.incoming", paid, name);
        }
        OrderNoticeService.notice(server, data, lender,
                "oremarket.toast.p2p.incoming", true, paid, name);
    }

    /**
     * Advances the installment index by the total repaid: the repayment schedule marks the leading
     * rows "settled" with it (same algorithm server-side and client-side).
     *
     * <p>When the index <b>really moves forward</b>, the Trading Day of the move is recorded
     * ({@code loan.lastAdvanceDay}): the sole basis for the gate "a not-yet-due installment can
     * advance at most once per Trading Day". Without it, spamming "pay 100" would push all 5
     * installments to settled on the very borrow day.
     */
    private static void advanceInstallmentIndex(LoanData loan, long day) {
        int before = loan.nextInst;
        loan.advanceInstallment();      // The predicate itself lives in the pure-calculation classes (LoanData -> LoanSchedule.openIndex)
        if (loan.nextInst > before) {
            loan.lastAdvanceDay = day;
        }
    }

    /**
     * Book "coins already taken from the wallet /
     * assets already sold against the debt" to the loan ledger <b>on the spot</b>. The old
     * implementation only zeroed the books at the final finishLoan, so any deferral in between
     * (halt / freeze / offline) or the bankruptcy branch would:
     *  (1) leave payoff unchanged, making the next retry debit the very same coins <b>again</b>;
     *  (2) book the forgiven bankruptcy debt at the full un-deducted amount, counting what the
     *      player already paid as debt <b>twice</b>.
     * Every seizure now books immediately in the "penalty -> interest -> principal" order, so the
     * remaining books and the money the player actually paid line up one-to-one.
     *
     * @param fromCoins true = debited from the wallet and booked; false = sale proceeds went
     *                  straight against the debt (bypassing the wallet), booked only
     * @return the amount actually booked
     */
    private static long bookSeizedValue(MarketSavedData data, UUID id, LoanData loan,
                                        long want, boolean fromCoins) {
        if (!loan.hasLoan()) {
            return 0L;
        }
        long need = Math.min(Math.max(0L, want), loan.payoff());
        if (need <= 0L) {
            return 0L;
        }
        // Forced Liquidation / full repayment is an "entire-loan settlement", so principal may be poured in (not subject to the Interest First, Principal Later period gate)
        long[] split = LoanRules.splitPayment(need, loan.penaltyDue(),
                loan.interestDue(), loan.amount, true);
        if (fromCoins) {
            long applied = split[0] + split[1] + split[2];
            long actual = debitCoins(data, id, applied);
            if (actual <= 0L) {
                return 0L;
            }
            if (actual < applied) {
                split = LoanRules.splitPayment(actual, loan.penaltyDue(),
                        loan.interestDue(), loan.amount, true);
            }
        }
        applySplit(loan, split);
        advanceInstallmentIndex(loan, data.tradeDay());
        data.setDirty();
        long booked = split[0] + split[1] + split[2];
        LOG.info("[seize] player {} booked {} (penalty {} interest {} principal {}) remain payoff {}",
                id, booked, split[0], split[1], split[2], loan.payoff());
        return booked;
    }

    // ------------------------------------------------------------ Repayment (standalone current-bill entry)

    /** Text of the repayment-success log. */
    /** Total market value of sellable positions: only unfrozen (available) shares count, halted stocks are skipped, frozen ones are untouched. Used by the step-2 skip check and the logs. */
    private static long sellableValue(MarketSavedData data, UUID id) {
        MarketModifiers mm = MarketModifiers.collect(data.events, MarketSettings.server().feeRate(),
                MarketSettings.server().meanReversionFactor, MarketEngine.defaultPriceLimit());
        long value = 0;
        // Count <b>active</b> stocks only: holdings of delisted stocks are kept but not tradable, and forced selling skips them.
        for (Stock s : StockRegistry.getActiveStocks()) {
            StockState st = data.state(s);
            if (st == null) {
                continue;
            }
            int available = data.availableUnlocked(id, s);
            if (available <= 0 || mm.suspended(s.code())) {
                continue;
            }
            value += (long) Math.floor(st.price * available);
        }
        return value;
    }

    /** Total converted value of ores in the inventory (50% exchange rate, whole-stack basis): used by the step-3 log and the "is there still a way out" check. */
    private static long backpackValue(ServerPlayer player) {
        long value = 0;
        for (Stock s : recoveryStocks()) {
            int have = countItem(player, s.item());
            if (have > 0) {
                value += Math.round(s.exchangeRate() * 0.5) * have;
            }
        }
        return value;
    }

    /**
     * Forced selling: ascending market value, ties by alphabetical stock code, selling positions in
     * whole lots until the need is covered (the overage stays in the balance).
     * With overdue = true (maturity liquidation) each stock logs one "loan Overdue, forced sale
     * of ..." entry; sales during early repayment (overdue = false) are not logged separately --
     * a unified "repayment succeeded" entry is logged once the amount is covered.
     */
    private static long forceSell(MinecraftServer server, MarketSavedData data, UUID id, long need,
                                  boolean overdue) {
        MarketModifiers mm = MarketModifiers.collect(data.events, MarketSettings.server().feeRate(),
                MarketSettings.server().meanReversionFactor, MarketEngine.defaultPriceLimit());
        List<Stock> sellable = new ArrayList<>();
        // Forced selling walks <b>active</b> stocks only: holdings of delisted stocks are kept but unsellable 
        for (Stock s : StockRegistry.getActiveStocks()) {
            int available = data.availableUnlocked(id, s);
            if (available <= 0) {
                continue;                       // Sell availableShares only; frozen shares wait for unfreezing
            }
            if (mm.suspended(s.code())) {
                continue;                       // Skip halted stocks; liquidate again once trading resumes
            }
            sellable.add(s);
        }
        sellable.sort(Comparator
                .comparingLong((Stock s) -> {
                    StockState st = data.state(s);
                    return (long) Math.floor(data.available(id, s) * (st == null ? 0 : st.price));
                })
                .thenComparing(Stock::code));

        long raised = 0;
        for (Stock s : sellable) {
            if (raised >= need) {
                break;
            }
            int qty = data.available(id, s);
            if (qty <= 0) {
                continue;
            }
            StockState st = data.state(s);
            if (st == null) {
                continue;
            }
            // Forced Liquidation requires a positive,
            // finite price. With a missing quote or a dirty save writing the price as 0 or NaN, the
            // old code still ran addShares(-qty) and destroyed the whole lot while
            // gain = floor(0 x qty) = 0 -- the player's position gone, the debt not a cent lower
            // (positions swallowed from thin air). Now the stock is skipped and the liquidation
            // waits for quotes to recover (sellableValue likewise treats an invalid price as 0; the
            // two definitions stay consistent).
            if (!(st.price > 0) || !Double.isFinite(st.price)) {
                LOG.warn("[force sell] skip {}: invalid price {}", s.code(), st.price);
                continue;
            }
            double fee = mm.get(s.code()).fee;
            long gain = (long) Math.floor(st.price * qty * (1.0 - fee));
            double avg = data.costOr(id, s, st.price);
            data.addShares(id, s, -qty);
            data.addRealized(id, s, gain - qty * avg);
            // Positions sold under Forced Liquidation may still be locked by stale sell orders: trim the sell orders of this code in sync to prevent ghost orders
            data.clampSellOrders(id, s.code());
            if (data.totalShares(id, s) <= 0) {
                data.clearPositionCost(id, s, "clear");
            }
            // Same price impact as a player-initiated sale
            double shock = MarketSettings.server().tradeImpactFactor * mm.get(s.code()).shockMult;
            double previousPrice = st.price;
            st.price = data.clampPrice(s, st.price * (1.0 - shock * Math.sqrt(qty)));
            st.recordPriceChange(previousPrice, st.price);
            st.appendHistory(st.price);
            st.addTradedVolume(qty);
            data.setDirty();
            // Proceeds go straight against the debt; anything above the debt stays in the coin balance (sale proceeds still count toward the peak)
            long use = Math.min(gain, need - raised);
            raised += use;
            long left = gain - use;
            if (left > 0) {
                data.addBalance(id, left);
            }
            if (overdue) {
                LogService.pushKey(server, data, id, LogType.SYSTEM,
                        "oremarket.loan.log.force_sell", s.displayName(), qty, 0, use);
            }
        }
        return raised;
    }

    /**
     * Inventory ore reclamation: whole stacks converted at the 50% exchange rate, in the order
     * needed, until the debt is covered or the inventory runs dry; the overage is refunded to the
     * balance. Returns [coins applied, items reclaimed]. With overdue = true (maturity
     * liquidation) it logs "loan Overdue, reclaimed X ores, applied X coins"; reclamation during
     * early repayment is not logged separately.
     */
    private static long[] recoverInventory(MarketSavedData data, ServerPlayer player, long need,
                                           boolean overdue) {
        long applied = 0;
        int items = 0;
        for (Stock s : recoveryStocks()) {
            if (applied >= need) {
                break;
            }
            long value = Math.round(s.exchangeRate() * 0.5);
            int have = countItem(player, s.item());
            while (have > 0 && applied < need) {
                long gap = need - applied;
                if (value > gap) {
                    // Last stack: apply it to the remaining debt, refund the overage converted into the balance
                    removeItem(player, s.item(), 1);
                    applied += gap;
                    items++;
                    data.addBalanceNoPeak(player.getUUID(), value - gap);
                    break;
                }
                removeItem(player, s.item(), 1);
                applied += value;
                items++;
                have--;
            }
        }
        if (applied > 0 && overdue) {
            LogService.pushKey(player.server, data, player.getUUID(), LogType.SYSTEM,
                    "oremarket.loan.log.recover", items, applied);
        }
        return new long[]{applied, items};
    }

    // ------------------------------------------------------------ Bankruptcy

    private static void bankrupt(ServerPlayer player, MinecraftServer server, MarketSavedData data,
                                 UUID id, LoanData loan, int recoveredItems, long recoveredValue) {
        long day = data.tradeDay();
        MarketSettings s = MarketSettings.server();
        // Anti-abuse: the outstanding amount at the very moment of bankruptcy is
        // <b>not forgiven</b> when the borrow happened inside the clawback window; it turns into
        // clawbackDebt hanging on the profile, and no new loan can be taken until it is paid off
        // (sealing the "borrow, then bankrupt immediately to default" hole).
        long unpaid = loan.payoff();
        // Captured BEFORE resetLoan (it clears the lender link): the lender must be told that
        // this bankruptcy writes off the unpaid remainder of their listing.
        java.util.UUID p2pLenderOfDebt = loan.p2pLender;
        boolean recent = day - loan.borrowDay < Math.max(0, s.loanClawbackDays);
        // Bankruptcy order: first cancel all listings to return logically locked resources, then
        // force-close all shorts; the forced short closing uses a dedicated flow that never
        // re-triggers the bankruptcy verdict, and only then settle ordinary positions and balance.
        data.cancelAllOrders(server, id);
        ShortService.forceCloseAll(server, data, id, "oremarket.short.reason.pre_bankrupt");
        // P2P listings under the bankrupt player that were <b>never
        // taken are confiscated together with their escrow</b>. Otherwise, after bankruptcy zeroes
        // the balance he could still "cancel" the listing and walk off with the escrowed coins,
        // making the bankruptcy penalty hollow.
        forfeitListings(data, id);
        data.setBalance(id, 0);
        data.clearPositions(id);
        loan.resetLoan();
        loan.clawbackDebt = recent ? Math.max(0, unpaid) : 0;
        loan.bankruptcies++;
        loan.lastSettledDay = day;
        // A P2P lender just lost the unpaid remainder of their listing to this bankruptcy:
        // tell them straight - a silently evaporating loan would look like lost coins.
        if (p2pLenderOfDebt != null && unpaid > 0) {
            OrderNoticeService.notice(server, data, p2pLenderOfDebt,
                    "oremarket.toast.p2p.lost", false, data.nameOf(id), unpaid);
        }
        // Bankruptcy's blow to credit: two tiers of limit haircut (the credit score is computed live from the history counts)
        loan.limitStrikes += 2;
        loan.limitStrikeDay = day;
        // Borrow cooldown = bankruptcyCooldownDays full Trading Days starting the day after bankruptcy (no borrowing on the bankruptcy day itself)
        loan.cooldownEndDay = LoanRules.cooldownEndDay(day);
        // New: the short-selling side resets independently after bankruptcy -- positions
        // were already force-closed above and the locked Margin zeroed; the cooldown starts on the
        // <b>next Trading Day</b> after the one containing the bankruptcy moment and lasts
        // bankruptcyShortCooldown full Trading Days, forbidding new Short Selling meanwhile; once it
        // expires, openings are again limited only by wallet margin and the stock's short pool (the credit limit itself
        // is unaffected).
        data.setShortsCooldownEnd(id, day + 1L + Math.max(0, MarketSettings.server().bankruptcyShortCooldown));
        data.setDirty();
        if (player != null) {
            // Bankruptcy settlement writes the log; nothing enters the chat, red text at the bottom of the UI for 3 seconds (60 client ticks)
            LogService.pushKey(server, data, id, LogType.SYSTEM,
                    "oremarket.loan.log.bankrupt", recoveredItems, recoveredValue,
                    loan.clawbackDebt, Math.max(0, loan.cooldownEndDay - day));
            OreMarketNetwork.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new S2CFeedbackPacket("oremarket.loan.toast.bankrupt", java.util.List.of(), false, 60));
            // Config-driven bankruptcy particles (10 points scattered inside a sphere of radius 3 blocks around the player)
            cn.oremarket.net.ParticleService
                    .playBankruptcy(server, player);
        } else {
            loan.catchupKind = 2;
        }
        broadcastSnapshot(server, data);
    }

    /**
     * Settlement: zero the debt but keep borrowDay (borrowed today, can borrow again tomorrow).
     * Log entries now carry a translation key + arguments, and the chat
     * notice fetches the zh baseline sentence from the same key.
     */
    private static void finishLoan(MinecraftServer server, MarketSavedData data, UUID id,
                                   LoanData loan, long paid, boolean offlineCatchup,
                                   String logKey, Object... logArgs) {
        routeLoanRepayment(server, data, id, loan, paid);
        // : settlement writes the credit history. Only a payoff without any overdue earns
        // the "on-time settlement" bonus; one tier of the overdue limit haircut is given back at the
        // same time ("the limit climbs back after payoff"), and the booking day is recorded.
        boolean clean = loan.overdueStrikes == 0;
        long day = data.tradeDay();
        loan.resetLoan();
        if (clean) {
            loan.onTimeRepays++;
        }
        loan.limitStrikes = Math.max(0, loan.limitStrikes - 1);
        loan.limitStrikeDay = day;
        loan.lastSettledDay = day;
        data.setDirty();
        if (clean) {
            LOG.info("[credit] player {} settled on time: onTimeRepays {} limitStrikes {} score {}",
                    id, loan.onTimeRepays, loan.limitStrikes, creditScore(data, id));
        }
        if (offlineCatchup) {
            loan.catchupKind = 1;
            loan.catchupPaid = paid;
        } else {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                LogService.pushKey(server, data, id, LogType.SYSTEM, logKey, logArgs);
                noticeKey(p, logKey, true, logArgs);
                MarketEngine.pushAfterAction(server, p);
            } else {
                // Settled while the borrower was offline: queue the same sentence for the next
                // login instead of letting the money / debt change without a word.
                data.noteOfflineNotice(id, logKey, logArgs);
            }
        }
    }

    /**
     * Destination of repaid money: both principal and interest are deducted from the coin balance at
     * repayment; with {@code loanDestroyPrincipal / loanDestroyInterest = true} (default) the
     * deducted coins are <b>destroyed outright</b>, entering no pool and returning to no player;
     * set to false and the corresponding part moves into the system reserve pool (loanReserve).
     *
     * <p><b>Repayment attribution</b>: the calculation must not split by "how much principal was
     * left on the books at settlement" -- the {@code repayInner / autoRepay} paths had already run
     * {@code applySplit} to zero amount before calling here, so {@code principal = 0} and the whole
     * payment got attributed to interest; meanwhile the full-settlement path debits first and
     * settles after, so the very same money was attributed two different ways on the two paths.
     * Attribution now always follows the profile's <b>cumulative repaid</b> figures: principal
     * bucket = cumulative principal repaid, interest bucket = cumulative interest + penalty repaid.
     * Mid-loan partial repayments also enter the attribution stats for the first time (the old
     * basis only counted the final payment; earlier repayments left neither a destruction record nor
     * a reserve record).
     *
     * <p><b>A boundary worth spelling out</b>: {@code loanReserve} is an internal accounting pool,
     * not any player's wallet. Disbursement always credits the full requested amount (see the final
     * ruling comment at the end of {@code doBorrow}), so turning destruction into reserve
     * <b>never changes</b> the quantity of coins in circulation in-game -- it only changes the
     * books on "was this money destroyed or reserved".
     */
    private static void routeLoanRepayment(MinecraftServer server, MarketSavedData data, UUID borrower,
                                           LoanData loan, long paid) {
        // P2P: for a taken listing, repayment travels back to the lender along the same road
        // (interest included), not destroyed by default and not entering the reserve pool.
        // Partial repayments and the close-time auto repayment already delivered part of it on the
        // spot (p2pDelivered); this only tops up the undelivered difference.
        if (loan.p2pLender != null) {
            if (paid > 0) {
                data.addBalance(loan.p2pLender, paid);
                loan.p2pDelivered = Math.max(0, loan.p2pDelivered + paid);
                notifyLenderIncoming(server, data, loan.p2pLender, borrower, paid);
            }
            return;
        }
        MarketSettings settings = MarketSettings.server();
        if (settings.loanDestroyPrincipal && settings.loanDestroyInterest) {
            return;     // Default basis: everything is destroyed and the reserve stays untouched
        }
        long principal = Math.max(0L, loan.paidPrincipal);
        long interest = Math.max(0L, loan.paidInterest + loan.paidPenalty);
        if (!settings.loanDestroyPrincipal) {
            data.loanReserve += principal;
        }
        if (!settings.loanDestroyInterest) {
            data.loanReserve += interest;
        }
        LOG.info("[repay destination] principal {} ({}), interest {} ({}), reserve now {}",
                principal, settings.loanDestroyPrincipal ? "burned" : "to reserve",
                interest, settings.loanDestroyInterest ? "burned" : "to reserve", data.loanReserve);
        data.setDirty();
    }

    // ------------------------------------------------------------ Close / Maturity

    /**
     *   tradeDay+1 -> T+1 unfreeze -> (1) daily accrual (idempotent) -> (2) pre-maturity reminder
     *   -> (3) close-time auto repayment -> (4) overdue verdict (grace / penalty / credit down /
     *   limit down) -> (5) Forced Liquidation once the threshold is hit.
     * Maturity Forced Liquidation (settleMatured) still has only three entry points: here, the
     * deferred retries in tickLiquidation, and the login catch-up; repayment packet handling never
     * enters this method .
     */
    public static void onTradeDayClose(MinecraftServer server, MarketSavedData data) {
        data.tradeDay++;
        data.unfreezeAll();
        long day = data.tradeDay();
        MarketSettings s = MarketSettings.server();
        List<UUID> liquidate = new ArrayList<>();
        data.loans.forEach((id, loan) -> {
            if (!loan.hasLoan()) {
                return;
            }
            // (1) Daily accrual: accruedDay guarantees "at most once per day", so multi-day offline gaps never double-add
            accrueDaily(loan, day);
            data.setDirty();
            // (2) Reminder one Trading Day before maturity (toast online, login queue offline)
            if (loan.dueDay - day == 1) {
                LogService.pushKey(server, data, id, LogType.SYSTEM,
                        "oremarket.loan.log.due_warning", loan.dueDay - day, loan.payoff());
                OrderNoticeService.notice(server, data, id,
                        "oremarket.toast.loan.due_soon", false, day + 1, loan.payoff());
            }
            // (3) Close-time auto repayment: deduct this period's bill (penalty included) from the balance
            long due = amountDueNow(loan, day);
            if (s.loanAutoRepay && due > 0) {
                autoRepay(server, data, id, loan, due);
                due = amountDueNow(loan, day);
            }
            // (4) Overdue verdict: book only after the grace period without settlement; the first overdue strike cuts credit and limit, later days add penalty
            long overdueDays = overdueDays(loan, day, s);
            if (overdueDays > 0) {
                if (overdueDays == 1) {
                    loan.overdueTotal++;
                    loan.overdueStrikes = Math.max(1, loan.overdueStrikes);
                    loan.limitStrikes++;
                    loan.limitStrikeDay = day;
                    LogService.pushKey(server, data, id, LogType.SYSTEM,
                            "oremarket.loan.log.overdue", loan.dueDay, loan.payoff(),
                            ratePercentText(LoanRules.penaltyRate(s)));
                    ServerPlayer overduePlayer = server.getPlayerList().getPlayer(id);
                    if (overduePlayer != null) {
                        noticeKeyTicks(overduePlayer, "oremarket.loan.toast.overdue", false, 80,
                                String.valueOf(loan.dueDay), String.valueOf(loan.payoff()));
                    }
                    LOG.info("[overdue] player {} first overdue: dueDay {} payoff {} limitStrikes {}",
                            id, loan.dueDay, loan.payoff(), loan.limitStrikes);
                }
                // Penalty: charged daily on the overdue "principal + interest" (the base excludes
                // accrued penalty so it never compounds); together with ordinary interest it still
                // obeys the cap "principal + interest multiple = principal x loanInterestCapMultiplier".
                double cap = loan.basePrincipal() * loan.capRate();
                double overdueBase = Math.max(0.0, loan.amount + loan.interest);
                double add = overdueBase * LoanRules.penaltyRate(s);
                double want = loan.penalty + add;
                double room = Math.max(0.0, cap - loan.interest);
                loan.penalty = Math.min(want, room);
                data.setDirty();
            } else {
                loan.overdueStrikes = 0;
            }
            // (5) Forced Liquidation threshold: <b>only sufficiently Overdue loans get liquidated</b>
            // (the due day and the following day no longer enter liquidation directly but
            // goes through the grace period and penalty first, otherwise loanGracePeriodDays and
            // the penalty would be toothless). A Collateralized Loan liquidates on the first
            // overdue day (its Collateral is the risk control); the others wait until the streak
            // of consecutive Overdue days reaches the threshold.
            int strikeLimit = Math.max(1, s.loanOverdueStrikeLimit);
            boolean trigger = loan.product == LoanData.PRODUCT_COLLATERAL
                    ? overdueDays >= 1 : overdueDays >= strikeLimit;
            // The collateral liquidation predicate must not require the position value to cover the principal:
            // "sellable-position market value < principal x 80%" was mathematically incompatible
            // with the limit formula (base limit + asset bonus + positions x 50%, never requiring
            // positions to cover principal at borrow time), so any Collateralized Loan with
            // "principal > positions / 0.8" got wrongly liquidated at the next close (measured in
            // play: borrowed 14252, holdings worth 913, 100k coins in the wallet, paying on time,
            // and the next day it still hit "collection Forced Liquidation"). The new predicate
            // lives in the pure-calculation class LoanRules.marginCallNumbers: it only looks at
            // "repayable assets" = coins + sellable positions + inventory reclamation value <
            // remaining principal x 80% -- early liquidation only when the borrower is genuinely
            // insolvent. Offline players skip this check (their inventory value cannot be measured
            // and nothing is collected on them; the "Collateralized Loan liquidates on the first
            // Overdue installment" channel is unaffected).
            if (!trigger && loan.product == LoanData.PRODUCT_COLLATERAL && loan.hasLoan()) {
                ServerPlayer online = server.getPlayerList().getPlayer(id);
                if (online != null) {
                    long[] mc = LoanRules.marginCallNumbers(Math.max(0L, data.balance(id)),
                            sellableValue(data, id), backpackValue(online), loan.amount);
                    if (mc[0] < mc[1]) {
                        trigger = true;
                        LogService.pushKey(server, data, id, LogType.SYSTEM,
                                "oremarket.loan.log.margin_call", mc[0], loan.amount, loan.payoff());
                        LOG.info("[margin-call] player {} coverage {} < 80% of principal {} -> liquidate",
                                id, mc[0], loan.amount);
                    }
                }
            }
            if (trigger) {
                loan.liquidating = true;
                liquidate.add(id);
            }
        });
        for (UUID id : liquidate) {
            settleMatured(server, data, id, data.loan(id));
        }
        // P2P: escrow of expired untaken listings is refunded at the close (once a day, never inside the tick)
        p2pExpire(server, data, day);
    }

    /**
     * Daily accrual (idempotent). Called only at the close: interest = outstanding principal x
     * locked daily rate, added day by day, capped at "principal + interest <= principal x
     * loanInterestCapMultiplier"; once Overdue (day &gt; dueDay) ordinary interest freezes and stops
     * accruing (as before), with the penalty line continuing instead.
     */
    private static void accrueDaily(LoanData loan, long day) {
        if (loan.accruedDay != Long.MIN_VALUE && day <= loan.accruedDay) {
            return;                                  // Repeated calls on the same day are skipped outright
        }
        // Accrual-step cap of 60: on the normal path each Trading Day accrues exactly once at the
        // close (steps is always 1); only "reopening after a long shutdown" back-fills several days
        // at once. The configured maximum loan term is 30 Trading Days, and ordinary interest
        // freezes outright after maturity (the day > dueDay return below), so 60 steps are always
        // enough -- while this also blocks long loops when a dirty save pushes day to a huge value.
        long steps = loan.accruedDay == Long.MIN_VALUE ? 1
                : Math.max(1, Math.min(60, day - loan.accruedDay));
        loan.accruedDay = day;
        if (day > loan.dueDay) {
            return;                                  // Ordinary interest freezes after maturity
        }
        double cap = loan.basePrincipal() * loan.capRate();
        // The accrual rate must go through the capped lockedDailyRate (never the configured 5% baseline).
        double oneDay = loan.amount * LoanRules.clampDailyRate(loan.dailyRate());
        if (loan.interest < oneDay) {
            loan.interest = oneDay;                  // Held less than a day still accrues one full day (as before)
        } else {
            loan.interest = Math.min(cap, loan.interest + oneDay * steps);
        }
        if (loan.interest > cap) {
            loan.interest = cap;
        }
    }

    /**
     * Amount due for the Current Period/Installment (shared by close-time auto repayment and the
     * UI "due" row):
     *  - before maturity: the scheduled principal + interest of the current installment
     *    (overdue-period amounts accumulate);
     *  - on or after the due day: the full principal + interest plus penalty.
     */
    public static long amountDueNow(LoanData loan, long day) {
        if (!loan.hasLoan()) {
            return 0;
        }
        if (day >= loan.dueDay) {
            return loan.payoff();
        }
        long acc = 0;
        for (cn.oremarket.common.LoanSchedule.Row r : loan.schedule()) {
            if (r.dueDay <= day) {
                acc += r.total();
            } else {
                break;
            }
        }
        long paid = loan.paidPrincipal + loan.paidInterest + loan.paidPenalty;
        long due = cn.oremarket.common.LoanSchedule
                .clampL((double) acc - paid + loan.penalty);
        return Math.min(due, loan.payoff());
    }

    /**
     * Days Overdue (grace period included): &lt;= 0 means not overdue yet. For installment loans
     * (penalty + credit down + same-day Forced Liquidation for collateral). Even in a dirty save
     * whose nextInst sits on an empty period, this still computes from the first installment that
     * actually carries an amount, without relying on booking-time initialization.
     */
    private static long overdueDays(LoanData loan, long day, MarketSettings s) {
        // The predicate itself lives in the pure-calculation class LoanSchedule.firstOpenDueDay (the offline verification harness runs the same code).
        long dueRef = cn.oremarket.common.LoanSchedule
                .firstOpenDueDay(loan.schedule(), loan.nextInst, loan.dueDay);
        long grace = Math.max(0, s.loanGracePeriodDays);
        return day - (dueRef + grace);
    }

    /**
     * Close-time auto repayment. Parts 3 and 5:
     *  - the deduction order is enforced as "penalty -> interest -> principal" ({@link LoanRules#splitPayment});
     *  - borrowed, locked funds <b>may</b> be used to repay (the money really is with the player, it just cannot buy ores);
     *  - on a shortfall it still debits whatever the balance covers, and the remaining debt goes to the overdue verdict -- never "take part of it, stop, and call it not Overdue";
     *  - the log prints the principal + interest + penalty triple, and the debit amount shares its source with the live UI debt ({@link LoanData#payoff}).
     *
     * <p><b>Cross-period prepayment red line </b>: automatic close-time debits and manual
     * repayments must use the <b>same</b> payable-bill definition ({@link LoanData#payable}).
     * Not-yet-due periods contribute nothing to the interest / principal components; otherwise one
     * close could collect the scheduled interest of several future periods in a lump, making the
     * manual repayment gate dead weight.
     */
    private static void autoRepay(MinecraftServer server, MarketSavedData data, UUID id,
                                  LoanData loan, long due) {
        if (due <= 0) {
            return;
        }
        final long day = data.tradeDay();
        boolean allowPrincipal = principalAllowed(loan, day, false);
        long interestBill;
        long principalBill;
        if (day >= loan.dueDay) {
            // Contract matured: all principal + interest on the books is owed, there is no "prepaying future periods" question left, so split against the full book amount
            interestBill = loan.interestDue();
            principalBill = loan.amount;
        } else {
            cn.oremarket.common.LoanSchedule.Payable payable =
                    loan.payable(day);
            interestBill = payable.interest;
            principalBill = allowPrincipal ? payable.principal : 0L;
        }
        long bill = loan.penaltyDue() + interestBill + principalBill;
        long bal = Math.max(0, data.balance(id));
        long pay = Math.min(Math.min(bal, due), bill);
        if (pay <= 0) {
            // Not a single coin could be debited: still log "unpaid" so the overdue flow takes over (never silently let it pass).
            LogService.pushKey(server, data, id, LogType.SYSTEM,
                    "oremarket.loan.log.auto_short", due, loan.amount, loan.interestDue(),
                    loan.penaltyDue());
            return;
        }
        long[] split = LoanRules.splitPayment(pay, loan.penaltyDue(),
                interestBill, principalBill, allowPrincipal);
        long applied = split[0] + split[1] + split[2];
        if (applied <= 0) {
            LogService.pushKey(server, data, id, LogType.SYSTEM,
                    "oremarket.loan.log.auto_short", due, loan.amount, loan.interestDue(),
                    loan.penaltyDue());
            return;
        }
        long actual = debitCoins(data, id, applied);
        if (actual <= 0) {
            LogService.pushKey(server, data, id, LogType.SYSTEM,
                    "oremarket.loan.log.auto_short", due, loan.amount, loan.interestDue(),
                    loan.penaltyDue());
            return;
        }
        if (actual < applied) {
            split = LoanRules.splitPayment(actual, loan.penaltyDue(),
                    interestBill, principalBill, allowPrincipal);
        }
        applySplit(loan, split);
        advanceInstallmentIndex(loan, day);
        data.setDirty();
        LOG.info("[auto repay] player {} paid {} (penalty {} interest {} principal {}) remain {}",
                id, actual, split[0], split[1], split[2], loan.payoff());
        if (loan.amount <= 0 && loan.penaltyDue() <= 0 && loan.interestDue() <= 0) {
            // Fully repaid: the remaining principal must display 0; never let a bare %s leak.
            finishLoan(server, data, id, loan, actual, false,
                    "oremarket.loan.log.auto_repaid_detail", actual, split[2], split[1], split[0], 0L);
        } else {
            deliverP2PRepayment(server, data, id, loan, split);   // Not settled: delivered to the lender on the spot
            LogService.pushKey(server, data, id, LogType.SYSTEM,
                    "oremarket.loan.log.auto_repaid_detail",
                    actual, split[2], split[1], split[0], Math.max(0, loan.amount));
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                noticeKeyTicks(p, "oremarket.loan.toast.repay_ok", true, 40,
                        String.valueOf(actual), String.valueOf(Math.max(0, loan.amount)));
            }
        }
    }

    /** Called every marketTick: retry liquidations deferred by halts / freezes / offline players. */
    public static void tickLiquidation(MinecraftServer server, MarketSavedData data) {
        if (data.loans.isEmpty()) {
            return;
        }
        List<UUID> pending = new ArrayList<>();
        data.loans.forEach((id, loan) -> {
            if (loan.hasLoan() && loan.liquidating) {
                pending.add(id);
            }
        });
        for (UUID id : pending) {
            if (server.getPlayerList().getPlayer(id) == null) {
                // Offline players retry on the 2-second cooldown (see the OFFLINE_RETRY_TICK comment); online players still get a try every tick
                long now = server.getTickCount();
                Long next = OFFLINE_RETRY_TICK.get(id);
                if (next != null && now < next) {
                    continue;
                }
                OFFLINE_RETRY_TICK.put(id, now + OFFLINE_RETRY_COOLDOWN_TICKS);
            }
            settleMatured(server, data, id, data.loan(id));
        }
        // Players no longer in the liquidation-retry queue are evicted from the cooldown table at once (even small static tables must not linger across sessions)
        OFFLINE_RETRY_TICK.keySet().retainAll(pending);
    }

    /**
     * Maturity Forced Liquidation is independent from early repayment: it never calls the early
     * repayment path and shares no flow helper with it, firing only on the first marketTick of the
     * 6th Trading Day (onTradeDayClose) and in the deferred retries afterwards (tickLiquidation /
     * login catch-up). The step order matches repayment (balance -> sell positions -> reclaim
     * inventory -> bankruptcy); the difference is that it serves already-Overdue loans: when the
     * amount cannot be covered and the loan is still waiting for trading resumption / unfreezing /
     * login, it defers rather than declaring bankruptcy early.
     */
    private static void settleMatured(MinecraftServer server, MarketSavedData data, UUID id, LoanData loan) {
        if (!loan.hasLoan()) {
            loan.liquidating = false;
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        final long payoffOriginal = loan.payoff();   // Total due when this pass began (for logs and the covered-check only)
        long payoff = payoffOriginal;                 // Re-taken as the remainder after every booking (refreshed repeatedly below)

        // Step 1: the coin balance (self-check: every debit books on the spot,
        // so neither deferred retries nor the bankruptcy branch double-debits the same money or
        // re-books the forgiven debt at the full original amount).
        long bal = Math.max(0, data.balance(id));
        long collected = bookSeizedValue(data, id, loan, bal, true);

        // First two steps of the bankruptcy order: cancel all listings to return locked resources -> force-close shorts (no re-triggered bankruptcy verdict).
        data.cancelAllOrders(server, id);
        ShortService.forceCloseAll(server, data, id, "oremarket.short.reason.maturity_bankrupt");
        // The Margin returned by the short closing is in the wallet right now; debit and book it on the spot too (not debiting it would erase debt while the money stays with the player).
        collected += bookSeizedValue(data, id, loan, Math.max(0, data.balance(id)), true);

        // Step 3: sellable positions (skipped when there are none; stocks locked by open orders never enter forced selling)
        long holdValue = 0;
        if (loan.payoff() > 0) {
            holdValue = sellableValue(data, id);
            if (holdValue > 0) {
                collected += bookSeizedValue(data, id, loan,
                        forceSell(server, data, id, loan.payoff(), true), false);
            }
        }

        // Step 3: inventory ores (need the player entity present; deferred to the login catch-up when offline)
        long invValue = 0;
        long recoveredItems = 0;
        long recoveredValue = 0;
        if (loan.payoff() > 0 && player != null) {
            invValue = backpackValue(player);
            long[] rec = recoverInventory(data, player, loan.payoff(), true);
            recoveredValue = rec[0];
            recoveredItems = rec[1];
            collected += bookSeizedValue(data, id, loan, recoveredValue, false);
        }
        payoff = loan.payoff();         // Whatever remains is the part genuinely not repaid
        collected = Math.min(collected, payoffOriginal);
        LOG.info("[maturity] player {}: payoff {}, coins {}, holdings {}, inventory {}, collected {}, remain {}",
                player != null ? DebugLog.ascii(player.getName().getString()) : "(offline " + id + ")",
                payoffOriginal, bal, holdValue, invValue, collected, payoff);

        if (collected >= payoffOriginal || payoff <= 0) {
            finishLoan(server, data, id, loan, collected, player == null,
                    "oremarket.loan.log.settled", collected);
            return;
        }
        if (awaitingCondition(data, id)) {
            routeMaturedP2PCollection(server, data, id, loan, collected);
            return;     // Trading not resumed / freeze not lifted: defer and wait, adding no interest meanwhile (already-booked deductions are never lost)
        }
        if (player == null) {
            routeMaturedP2PCollection(server, data, id, loan, collected);
            return;     // Offline player, amount short: keep it for the login moment to finish inventory reclamation and the bankruptcy verdict
        }
        // A Collateralized Loan that is under water -- when force-selling the
        // Collateral still cannot cover principal, the difference turns into shortDebt and counts
        // toward total debt; no bankruptcy (bankruptcy would liquidate everything plus a cooldown,
        // while the collateral product's risk control has already been done by its Collateral).
        if (loan.product == LoanData.PRODUCT_COLLATERAL) {
            long gap = Math.max(0L, payoff);
            convertToShortDebt(server, data, id, loan, gap, collected, "oremarket.loan.log.shortfall_collateral");
            return;
        }
        // Step 4: all three sources tapped and still short -> bankruptcy settlement.
        // Route this pass's seized value first; otherwise the borrower is debited while the P2P lender receives nothing.
        routeMaturedP2PCollection(server, data, id, loan, collected);
        LOG.info("[maturity] player {} still short after all three sources -> bankruptcy", DebugLog.ascii(player.getName().getString()));
        bankrupt(player, server, data, id, loan, (int) recoveredItems, recoveredValue);
    }

    /**
     * Deliver the value seized by one deferred maturity-liquidation pass to its P2P lender.
     * System loans intentionally return here; their destination is resolved when the loan finishes.
     */
    private static void routeMaturedP2PCollection(MinecraftServer server, MarketSavedData data, UUID borrower,
                                                  LoanData loan, long collected) {
        if (loan.p2pLender != null && collected > 0) {
            routeLoanRepayment(server, data, borrower, loan, collected);
        }
    }

    /**
     * Hang the unpaid difference after Forced Liquidation as
     * {@code shortDebt}. The loan fields are zeroed (occupying no limit and accruing no interest
     * any more); the difference ships as part of total debt and is worked off through the
     * repayment entry point.
     */
    private static void convertToShortDebt(MinecraftServer server, MarketSavedData data, UUID id,
                                           LoanData loan, long gap, long collected, String logKey) {
        long add = Math.max(0L, gap);
        loan.shortDebt = cn.oremarket.common.LoanSchedule
                .clampL((double) loan.shortDebtDue() + add);
        routeLoanRepayment(server, data, id, loan, collected);
        boolean clean = loan.overdueStrikes == 0;
        long day = data.tradeDay();
        loan.resetLoan();
        if (clean) {
            loan.onTimeRepays++;
        }
        loan.limitStrikes = Math.max(0, loan.limitStrikes);
        loan.limitStrikeDay = day;
        loan.lastSettledDay = day;
        data.setDirty();
        LOG.info("[short-debt] player {} gap {} shortDebt now {}", id, add, loan.shortDebt);
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) {
            LogService.pushKey(server, data, id, LogType.SYSTEM, logKey, collected, add, loan.shortDebt);
            noticeKeyTicks(p, "oremarket.loan.toast.shortfall", false, 80,
                    String.valueOf(add), String.valueOf(loan.shortDebt));
            MarketEngine.pushAfterAction(server, p);
        } else {
            loan.catchupKind = 3;
            loan.catchupPaid = add;
        }
    }

    /** Whether any position could still bring money in "after a wait": frozen positions, positions in halted stocks. */
    private static boolean awaitingCondition(MarketSavedData data, UUID id) {
        if (!data.frozen.getOrDefault(id, Map.of()).isEmpty()) {
            return true;
        }
        MarketModifiers mm = MarketModifiers.collect(data.events, MarketSettings.server().feeRate(),
                MarketSettings.server().meanReversionFactor, MarketEngine.defaultPriceLimit());
        for (Stock s : StockRegistry.getActiveStocks()) {
            if (mm.suspended(s.code())) {
                if (data.available(id, s) > 0 || data.frozenShares(id, s) > 0) {
                    return true;     // Position (sellable or frozen) in a halted stock: sell after trading resumes and the freeze lifts
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------ Login catch-up

    /** Player login: re-issue the forced-close / bankruptcy notices from the offline window, and resume deferred liquidations. */
    public static void onPlayerLogin(ServerPlayer player) {
        MinecraftServer server = player.server;
        if (server == null) {
            return;
        }
        MarketSavedData data = MarketSavedData.get(server);
        UUID id = player.getUUID();
        LoanData loan = data.loanOrNull(id);
        if (loan == null) {
            return;
        }
        if (loan.catchupKind == 1) {
            // Cross-system anomaly warning: results settled during the offline window get a chat line + log entry at login
            warnChatKey(player, "oremarket.loan.log.settled", loan.catchupPaid);
            LogService.pushKey(player.server, data, id, LogType.SYSTEM,
                    "oremarket.loan.log.settled", loan.catchupPaid);
            loan.catchupKind = 0;
            data.setDirty();
        } else if (loan.catchupKind == 2) {
            // Offline-bankruptcy catch-up: the reclaimed value was settled as 0 at the time (the in-kind
            // repayment detail is not kept across sessions), but the template needs all four
            // placeholders filled, otherwise a bare "%s" leaks into chat.
            int cooldownLeft = (int) Math.max(0L, loan.cooldownEndDay - data.tradeDay());
            warnChatKey(player, "oremarket.loan.log.bankrupt", 0, 0,
                    loan.clawbackDebt, cooldownLeft);
            LogService.pushKey(player.server, data, id, LogType.SYSTEM,
                    "oremarket.loan.log.bankrupt", 0, 0, loan.clawbackDebt, cooldownLeft);
            loan.catchupKind = 0;
            data.setDirty();
        } else if (loan.catchupKind == 3) {
            warnChatKey(player, "oremarket.loan.toast.shortfall", loan.catchupPaid, loan.shortDebtDue());
            LogService.pushKey(player.server, data, id, LogType.SYSTEM,
                    "oremarket.loan.log.shortfall_collateral", 0, loan.catchupPaid, loan.shortDebtDue());
            loan.catchupKind = 0;
            data.setDirty();
        }
        // If the loan is already Overdue at login, pop a red notice urging repayment (the log entry stays regardless).
        if (loan.hasLoan() && data.tradeDay() > loan.dueDay) {
            noticeKeyTicks(player, "oremarket.loan.toast.overdue", false, 80,
                    String.valueOf(loan.dueDay), String.valueOf(loan.payoff()));
        }
        if (loan.hasLoan() && loan.liquidating) {
            settleMatured(server, data, id, loan);
        }
    }

    // ------------------------------------------------------------ Display helpers (one definition shared server-side and client-side)

    /** Whether inside the bankruptcy cooldown (the bankruptcy day plus 3 full Trading Days after it). */
    public static boolean inCooldown(long cooldownEndDay, long day) {
        return day < cooldownEndDay;
    }

    /**
     * Days left in the bankruptcy cooldown (player-facing "N days left"):
     * cooldownEndDay = the earliest day a loan may be taken; the countdown is days until then,
     * always showing at least 1.
     */
    public static long cooldownLeft(long cooldownEndDay, long day) {
        return Math.max(1, cooldownEndDay - day);
    }

    // ============================================================ Peer-to-peer lending

    /**
     * The lender's listing: coins move from the balance into the escrow pool
     * ({@code p2pEscrow}) on the spot, so every listing necessarily corresponds to real money and
     * the escrow is refunded only on cancellation or expiry. Validation (all recomputed
     * server-side): the switch, level, no self-borrowing, amount, rate cap, term, one listing per
     * player.
     */
    public static boolean p2pList(ServerPlayer player, long coins, double ratePercent, int termDays) {
        MinecraftServer server = player == null ? null : player.server;
        if (server == null) {
            return false;
        }
        MarketSavedData data = MarketSavedData.get(server);
        MarketSettings s = MarketSettings.server();
        UUID id = player.getUUID();
        if (!s.loanP2PEnabled) {
            rejectKey(player, "oremarket.p2p.err.disabled");
            return false;
        }
        if (data.playerTier(id) < LoanRules.requiredTier()) {
            rejectKey(player, "oremarket.loan.borrow_hint",
                    String.valueOf(s.levelThreshold(LoanRules.requiredTier())));
            return false;
        }
        // A listing may only use <b>your own money</b> that is not already pledged elsewhere (borrowed money
        // and coins reserved by resting orders cannot be listed, otherwise cancellation could release
        // funds that another market obligation still expects).
        // Clean expired escrow before checking the one-active-listing rule, so a hot-reload or
        // a very short trading day cannot leave an expired listing blocking or confusing a new one.
        p2pExpire(server, data, data.tradeDay());
        long own = withdrawableCoins(data, id);
        if (coins < LoanRules.minAmount() || own < coins) {
            rejectKey(player, "oremarket.p2p.err.coins", String.valueOf(Math.max(0, own)));
            return false;
        }
        long ownLimit = creditLimit(data, id);
        if (ownLimit > 0 && coins > ownLimit * P2PListing.MAX_AMOUNT_TIER) {
            rejectKey(player, "oremarket.p2p.err.too_big", String.valueOf(ownLimit * P2PListing.MAX_AMOUNT_TIER));
            return false;
        }
        double rate = ratePercent / 100.0;
        // The listing rate cap and the system-loan quote share one entry (min(loanRateCapPercent, 1.0%)):
        // the old code read the raw config directly, so the same config key meant two different things
        // on the two paths -- system loans held to 1%/day while player listings could post 12%/day.
        // Now loanRateCapPercent means exactly one thing from here on.
        double capPercent = cn.oremarket.common.LoanRules
                .effectiveRateCapPercent(s.loanRateCapPercent);
        if (!Double.isFinite(rate) || rate <= 0 || rate > capPercent / 100.0) {
            rejectKey(player, "oremarket.p2p.err.rate",
                    cn.oremarket.common.Num.fixed(capPercent, 2));
            return false;
        }
        int term = Math.max(1, Math.min(Math.max(1, s.loanTermDays * 3), termDays <= 0 ? s.loanTermDays : termDays));
        long day = data.tradeDay();
        P2PListing existing = myOpenListing(data, id, day);
        if (existing != null) {
            rejectKey(player, "oremarket.p2p.err.dup", String.valueOf(existing.id));
            return false;
        }
        data.lockOwnCoins(id, coins);
        data.p2pEscrow = Math.max(0, data.p2pEscrow + coins);
        P2PListing p = new P2PListing();
        p.id = data.p2pNextId++;
        p.lender = id;
        p.coins = coins;
        p.rate = rate;
        p.termDays = term;
        p.listedDay = day;
        data.p2pListings.put(p.id, p);
        data.setDirty();
        LogService.pushKey(player, data, LogType.SYSTEM, "oremarket.p2p.log.list",
                coins, cn.oremarket.common.Num.fixed(ratePercent, 3), term);
        noticeKey(player, "oremarket.p2p.log.list", true, String.valueOf(coins),
                cn.oremarket.common.Num.fixed(ratePercent, 3), String.valueOf(term));
        LOG.info("[p2p] player {} listed {} at rate {} for {} days (id {}, escrow {})",
                DebugLog.ascii(player.getName().getString()), coins, rate, term, p.id, data.p2pEscrow);
        MarketEngine.pushAfterAction(server, player);
        return true;
    }

    /** Cancel your own listing: the escrowed coins travel back to the balance along the same road (listing funds are not "income", so they never raise the peak). */
    public static boolean p2pCancel(ServerPlayer player, long listingId) {
        MinecraftServer server = player == null ? null : player.server;
        if (server == null) {
            return false;
        }
        MarketSavedData data = MarketSavedData.get(server);
        UUID id = player.getUUID();
        P2PListing p = listingId > 0 ? data.p2pListings.get(listingId) : myOpenListing(data, id, data.tradeDay());
        if (p == null || !id.equals(p.lender) || p.taken) {
            rejectKey(player, "oremarket.p2p.err.gone");
            return false;
        }
        data.p2pListings.remove(p.id);
        data.p2pEscrow = Math.max(0, data.p2pEscrow - p.coins);
        data.addBalanceNoPeak(id, p.coins);
        data.setDirty();
        LogService.pushKey(player, data, LogType.SYSTEM, "oremarket.p2p.log.cancel", p.id, p.coins);
        noticeKey(player, "oremarket.p2p.log.cancel", true, String.valueOf(p.id), String.valueOf(p.coins));
        MarketEngine.pushAfterAction(server, player);
        return true;
    }

    /**
     * Take a listing (listingId &lt;= 0 = take whichever listing carries the lowest rate with
     * enough coins). Shares the exact LoanData lifecycle with system loans: the rate / term switch
     * to the listing's figures, the disbursement comes from the escrow pool (no new coins minted),
     * the platform takes a one-off fee into the reserve pool per loanP2PFeePercent, the borrower
     * receives principal - fee, while everything due is computed on the principal.
     */
    public static boolean p2pTake(ServerPlayer player, long listingId) {
        MinecraftServer server = player == null ? null : player.server;
        if (server == null) {
            return false;
        }
        MarketSavedData data = MarketSavedData.get(server);
        MarketSettings s = MarketSettings.server();
        UUID id = player.getUUID();
        long day = data.tradeDay();
        if (!s.loanP2PEnabled) {
            rejectKey(player, "oremarket.p2p.err.disabled");
            return false;
        }
        LoanData loan = data.loan(id);
        if (loan.hasLoan()) {
            rejectKey(player, "oremarket.loan.err.has_loan");
            return false;
        }
        if (loan.clawbackDebt > 0) {
            rejectKey(player, "oremarket.loan.err.clawback", String.valueOf(loan.clawbackDebt));
            return false;
        }
        if (loan.shortDebtDue() > 0) {
            rejectKey(player, "oremarket.loan.err.short_debt", String.valueOf(loan.shortDebtDue()));
            return false;
        }
        if (inCooldown(loan.cooldownEndDay, day)) {
            rejectKey(player, "oremarket.loan.err.cooldown", String.valueOf(cooldownLeft(loan.cooldownEndDay, day)));
            return false;
        }
        if (loan.borrowedOn(day)) {
            rejectKey(player, "oremarket.loan.err.borrowed_today");
            return false;
        }
        P2PListing p = listingId > 0 ? findOpen(data, listingId, day) : bestListing(data, id, day);
        if (p == null) {
            rejectKey(player, "oremarket.p2p.err.none");
            return false;
        }
        if (id.equals(p.lender)) {
            rejectKey(player, "oremarket.p2p.err.self");
            return false;
        }
        long limit = creditLimit(data, id);
        if (limit <= 0 || p.coins > limit) {
            rejectKey(player, "oremarket.loan.err.over_limit", String.valueOf(Math.max(0, limit)));
            return false;
        }
        long fee = Math.min(p.coins - 1, (long) Math.floor(p.coins * s.loanP2PFeePercent / 100.0));
        fee = Math.max(0, fee);
        long handed = p.coins - fee;
        p.taken = true;
        data.p2pEscrow = Math.max(0, data.p2pEscrow - p.coins);
        data.loanReserve = Math.max(0, data.loanReserve + fee);   // The platform fee goes into the reserve pool
        data.p2pListings.remove(p.id);                            // Filled = delisted at once (the taken flag lives on in the logs)
        // Loan booking: principal per the listed amount (the fee never reduces the debt); rate / term locked to the listing's figures
        loan.amount = p.coins;
        loan.principal = p.coins;
        loan.interest = 0;
        loan.penalty = 0;
        loan.paidPrincipal = 0;
        loan.paidInterest = 0;
        loan.paidPenalty = 0;
        loan.nextInst = 1;
        loan.overdueStrikes = 0;
        loan.accruedDay = day;
        loan.lockedDailyRate = p.rate;
        loan.lockedTermDays = p.termDays;
        loan.lockedCapMultiplier = s.loanInterestCapMultiplier;
        loan.product = LoanData.PRODUCT_CREDIT;
        loan.mode = cn.oremarket.common.LoanSchedule.MODE_BALLOON;
        loan.installments = 1;
        loan.dueDay = day + p.termDays;
        loan.borrowDay = day;
        loan.liquidating = false;
        loan.catchupKind = 0;
        loan.p2pLender = p.lender;
        loan.p2pListingId = p.id;
        data.addBalanceNoPeak(id, handed);
        loan.loanFunds = Long.MAX_VALUE - loan.loanFunds > handed ? loan.loanFunds + handed : Long.MAX_VALUE;
        data.setDirty();
        long plannedInterest = cn.oremarket.common.LoanSchedule
                .clampL(p.coins * p.rate * p.termDays);
        LogService.pushKey(player, data, LogType.SYSTEM, "oremarket.p2p.log.take",
                p.id, p.coins, fee, handed, day + p.termDays,
                cn.oremarket.common.Num.fixed(p.rate * 100.0, 3));
        noticeKey(player, "oremarket.p2p.log.take", true, String.valueOf(p.id),
                String.valueOf(p.coins), String.valueOf(fee), String.valueOf(handed),
                String.valueOf(day + p.termDays),
                cn.oremarket.common.Num.fixed(p.rate * 100.0, 3));
        // The lender's escrow just became someone else's loan: they must know their listing was
        // taken (toast online, login notice offline), otherwise the listing just "disappears".
        if (p.lender != null && !p.lender.equals(id)) {
            LogService.pushKey(server, data, p.lender, LogType.SYSTEM,
                    "oremarket.toast.p2p.taken", player.getName().getString(), p.coins);
            OrderNoticeService.notice(server, data, p.lender,
                    "oremarket.toast.p2p.taken", true, player.getName().getString(), p.coins);
        }
        LOG.info("[p2p] player {} took listing {} from {} coins {} fee {} handed {} interest {}",
                DebugLog.ascii(player.getName().getString()), p.id, p.lender, p.coins, fee, handed, plannedInterest);
        MarketEngine.pushAfterAction(server, player);
        return true;
    }

    /** Confiscate every outstanding listing of a player (escrow included); used only by bankruptcy settlement. */
    private static void forfeitListings(MarketSavedData data, UUID id) {
        if (data.p2pListings.isEmpty()) {
            return;
        }
        java.util.Iterator<P2PListing> it = data.p2pListings.values().iterator();
        long forfeited = 0;
        while (it.hasNext()) {
            P2PListing p = it.next();
            if (id.equals(p.lender) && !p.taken) {
                forfeited += p.coins;
                data.p2pEscrow = Math.max(0, data.p2pEscrow - p.coins);
                it.remove();
            }
        }
        if (forfeited > 0) {
            LOG.info("[p2p] player {} bankrupt: {} escrowed coins forfeited", id, forfeited);
        }
    }

    /** The player's only active listing (null when there is none). */
    public static P2PListing myOpenListing(MarketSavedData data, UUID id, long day) {
        for (P2PListing p : data.p2pListings.values()) {
            if (p.open(day) && id.equals(p.lender)) {
                return p;
            }
        }
        return null;
    }

    private static P2PListing findOpen(MarketSavedData data, long listingId, long day) {
        P2PListing p = data.p2pListings.get(listingId);
        return p != null && p.open(day) ? p : null;
    }

    /** The single most favorable listing on the market for "me": lowest daily rate, ties broken by the longer term. */
    public static P2PListing bestListing(MarketSavedData data, UUID borrower, long day) {
        P2PListing best = null;
        for (P2PListing p : data.p2pListings.values()) {
            if (!p.open(day) || borrower.equals(p.lender)) {
                continue;
            }
            if (best == null || p.rate < best.rate || (p.rate == best.rate && p.termDays > best.termDays)) {
                best = p;
            }
        }
        return best;
    }

    /** Number of active listings (excluding my own; used by the "N listings borrowable" UI line). */
    public static int openListingCount(MarketSavedData data, UUID viewer, long day) {
        int n = 0;
        for (P2PListing p : data.p2pListings.values()) {
            if (p.open(day) && !java.util.Objects.equals(p.lender, viewer)) {
                n++;
            }
        }
        return n;
    }

    /** Lowest daily rate on the market (0 when there is no listing). */
    public static double bestOpenRate(MarketSavedData data, UUID viewer, long day) {
        P2PListing p = bestListing(data, viewer, day);
        return p == null ? 0 : p.rate;
    }

    /**
     * Close-time sweep (once per Trading Day, called by onTradeDayClose): expired untaken listings
     * are cancelled automatically and their escrow is refunded to the lender's balance along the
     * same road. Offline lenders are refunded as well (the balance lives in the save and shows up
     * at login).
     */
    private static void p2pExpire(MinecraftServer server, MarketSavedData data, long day) {
        if (data.p2pListings.isEmpty()) {
            return;
        }
        java.util.Iterator<P2PListing> it = data.p2pListings.values().iterator();
        while (it.hasNext()) {
            P2PListing p = it.next();
            if (!p.taken && day - p.listedDay >= P2PListing.LISTING_TTL_DAYS) {
                data.p2pEscrow = Math.max(0, data.p2pEscrow - p.coins);
                if (p.lender != null) {
                    data.addBalanceNoPeak(p.lender, p.coins);
                    LogService.pushKey(server, data, p.lender, LogType.SYSTEM,
                            "oremarket.p2p.log.expired", p.id, p.coins);
                    // A listing quietly vanishing from the board would read like the escrow was
                    // lost: tell the lender the escrow is back in the wallet (login notice offline).
                    OrderNoticeService.notice(server, data, p.lender,
                            "oremarket.toast.p2p.expired", true, p.coins);
                }
                it.remove();
                data.setDirty();
                LOG.info("[p2p] listing {} expired, {} returned to {}", p.id, p.coins, p.lender);
            }
        }
    }

    // ------------------------------------------------------------ Messaging

    /**
     * Notice: success and failure notices for every trading
     * operation -- borrowing / repayment / bankruptcy and so on -- no longer enter the chat:
     * success writes the log tab (each call site runs its own LogService.push), failure shows red
     * text at the bottom of the UI for 2 seconds; this only drives the sound plus the red-text
     * packet.
     */
    private static void noticeKey(ServerPlayer player, String key, boolean success, Object... args) {
        noticeKeyTicks(player, key, success, 40, args);
    }

    /**
     * Unified notices: success / warning / forced events all ride the existing
     * {@link S2CFeedbackPacket}; the client color-codes by success + key prefix, and ticks control
     * the bar's duration.
     */
    private static void noticeKeyTicks(ServerPlayer player, String key, boolean success, int ticks, Object... args) {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (Object arg : args) {
            values.add(String.valueOf(arg));
        }
        OreMarketNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new S2CFeedbackPacket(key, values, success, ticks));
    }

    /** Cross-system anomaly warning (re-issue of an offline settlement): chat and toast both carry key + arguments only. */
    private static void warnChatKey(ServerPlayer player, String key, Object... args) {
        Component inner = Component.translatable(key, args);
        Component msg = Component.translatable("oremarket.loan.warning", inner)
                .withStyle(ChatFormatting.RED);
        player.displayClientMessage(msg, false);
        java.util.List<String> values = new java.util.ArrayList<>();
        for (Object arg : args) {
            values.add(String.valueOf(arg));
        }
        OreMarketNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new S2CFeedbackPacket(key, values, false, 60));
    }

    private static void rejectKey(ServerPlayer player, String key, Object... args) {
        // Every loan interception (limit exhausted / cooldown / in-flight lock / amount
        // out of range...) is logged 100%, not one missed; when the switch is off it is just one
        // extra read of a false, zero string work.
        if (DebugLog.on()) {
            DebugLog.info("oremarket.debug.log.loan_intercept",
                    key, player == null ? "?" : player.getName().getString());
        }
        noticeKey(player, key, false, args);
    }

    private static void broadcastSnapshot(MinecraftServer server, MarketSavedData data) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            MarketEngine.pushAfterAction(server, p);
        }
    }

    // ------------------------------------------------------------ Inventory helpers (same definitions as TradeService)

    private static int countItem(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        var inv = player.getInventory();
        // Vanilla Inventory.getContainerSize
        // == 41 already includes the 4 armor slots and the offhand, and the code below added the
        // offhand once more -> offhand items were counted twice. Now only the real storage slots
        // (the 36-slot main inventory) are summed, with the offhand added exactly once, fully
        // matching the simulated-insert / actual-payout definitions.
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(item)) {
                count += st.getCount();
            }
        }
        ItemStack off = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (off.is(item)) {
            count += off.getCount();
        }
        return count;
    }

    private static void removeItem(ServerPlayer player, net.minecraft.world.item.Item item, int want) {
        int removed = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.items.size() && removed < want; i++) {   // Scan only the 36 main-inventory slots
            ItemStack st = inv.getItem(i);
            if (st.is(item)) {
                int take = Math.min(st.getCount(), want - removed);
                st.shrink(take);
                removed += take;
                if (st.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        ItemStack off = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (removed < want && off.is(item)) {
            int take = Math.min(off.getCount(), want - removed);
            off.shrink(take);
            if (off.isEmpty()) {
                player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            }
        }
    }
}
