package cn.oremarket.market;

import cn.oremarket.common.Fees;
import cn.oremarket.common.LoanRules;
import cn.oremarket.common.LoanSchedule;
import cn.oremarket.common.ShortSettlement;

import java.util.List;
import java.util.UUID;

/**
 * 白盒自查取证打印（不参与模组打包）：把关键分支的真实输入 / 命中判据 / 输出数值打出来，
 * 供自查报告直接引用。编译的是 src/main 里的真实源码，替身只有 CompoundTag 与 MarketSettings。
 */
public final class Trace {

    public static void main(String[] args) {
        traceBalloonOverdue();
        traceSeizeBooking();
        traceLiquidation();
        traceLimitSelfLock();
    }

    private static void line(String s) {
        System.out.println(s);
    }

    /** 缺陷 F1：多期一次性还本付息的逾期基准。 */
    private static void traceBalloonOverdue() {
        line("=== [F1] 一次性还本付息 × 5 期排期（本金 1200，日利率 0.5%，期限 25 个交易日）===");
        long borrowDay = 100;
        List<LoanSchedule.Row> rows =
                LoanSchedule.build(borrowDay, 1200, 0.005, 25, 5, LoanSchedule.MODE_BALLOON);
        for (LoanSchedule.Row r : rows) {
            line(String.format("  计划表 第%d期 dueDay=%d 本金=%d 利息=%d 合计=%d",
                    r.index, r.dueDay, r.principal, r.interest, r.total()));
        }
        line(String.format("  全表计划利息 = %d，合同到期日 = %d",
                rows.get(4).interest, borrowDay + 25));
        long legacy = legacyRef(rows, 1);                    // 修复前的判据
        long fixed = LoanSchedule.firstOpenDueDay(rows, 1, borrowDay + 25);
        line(String.format("  [代码分支] 旧 overdueDays(): 首个 index>=nextInst 的行 → dueRef=%d", legacy));
        line(String.format("  [代码分支] 新 overdueDays(): LoanSchedule.firstOpenDueDay() → dueRef=%d", fixed));
        long grace = 1;   // 服务端默认 loanGracePeriodDays = 1（clampI 默认值，0..30）
        for (long day : new long[]{106, 108, 111, 126, 129}) {
            long o = day - (legacy + grace);
            long n = day - (fixed + grace);
            line(String.format("  [结果] 第%d天：旧口径逾期=%d%s | 新口径逾期=%d%s",
                    day, o, o > 0 ? " ← 收罚息/降信用/抵押贷当天强平" : "",
                    n, n > 0 ? " ← 逾期" : "（未逾期）"));
        }
        line(String.format("  首期就有金额的还款方式对照：先息后本 dueRef=%d，等额本息 dueRef=%d，等额本金 dueRef=%d",
                LoanSchedule.firstOpenDueDay(LoanSchedule.build(borrowDay, 1200, 0.005, 25, 5,
                        LoanSchedule.MODE_INTEREST_FIRST), 1, 125),
                LoanSchedule.firstOpenDueDay(LoanSchedule.build(borrowDay, 1200, 0.005, 25, 5,
                        LoanSchedule.MODE_ANNUITY), 1, 125),
                LoanSchedule.firstOpenDueDay(LoanSchedule.build(borrowDay, 1200, 0.005, 25, 5,
                        LoanSchedule.MODE_LINEAR), 1, 125)));
        line(String.format("  落账初始未结期号：旧=%d（停在空期）→ 新=%d（首个真要有钱的期）",
                1, LoanSchedule.openIndex(rows, 0)));
        line("");
    }

    /** 缺陷 F2：强制清算收缴必须当场落账，挂起重试不能重复扣同一笔钱。 */
    private static void traceSeizeBooking() {
        line("=== [F2] 到期清算收缴落账（应还 1050，钱包只有 400，之后重试时余额 0）===");
        LoanData loan = mkLoan(1000, 0.01, 5, 1);
        long payoff0 = loan.payoff();
        line(String.format("  [输入] payoff=%d（本金 %d + 计划利息 %d）", payoff0, loan.amount,
                loan.interestDue()));
        long[] s1 = LoanRules.splitPayment(400, loan.penaltyDue(), loan.interestDue(), loan.amount, true);
        line(String.format("  [代码分支] 命中 bookSeizedValue → splitPayment(400) = [罚息 %d, 利息 %d, 本金 %d]",
                s1[0], s1[1], s1[2]));
        loan.applySplit(s1);
        loan.advanceInstallment();
        line(String.format("  [结果] 落账后 payoff=%d（旧实现这里仍是 %d，下一轮会再扣一次）",
                loan.payoff(), payoff0));
        line(String.format("  [输入] 第二轮重试：钱包余额=0，need=payoff=%d", loan.payoff()));
        long[] s2 = LoanRules.splitPayment(0, loan.penaltyDue(), loan.interestDue(), loan.amount, true);
        line(String.format("  [代码分支] splitPayment(0) = [%d, %d, %d] → 一分不落账", s2[0], s2[1], s2[2]));
        loan.applySplit(s2);
        line(String.format("  [结果] payoff 仍 = %d（旧实现会重复扣 1050，并把这个全额记进 clawbackDebt）",
                loan.payoff()));
        long[] s3 = LoanRules.splitPayment(650, loan.penaltyDue(), loan.interestDue(), loan.amount, true);
        loan.applySplit(s3);
        line(String.format("  [结果] 缴清 650 后 amount=%d payoff=%d hasLoan=%s 已还合计=%d",
                loan.amount, loan.payoff(), loan.hasLoan(),
                loan.paidPrincipal + loan.paidInterest + loan.paidPenalty));
        LoanData neg = mkLoan(1000, 0.01, 5, 1);
        neg.applySplit(new long[]{-500, -500, -500});
        line(String.format("  [恶意输入] split=[-500,-500,-500] → payoff=%d（一分不少）已还本金=%d",
                neg.payoff(), neg.paidPrincipal));
        LoanData big = mkLoan(1000, 0.01, 5, 1);
        big.applySplit(new long[]{Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE});
        line(String.format("  [恶意输入] split=[MAX,MAX,MAX] → amount=%d 已还本金=%d（饱和，不回绕成负数）",
                big.amount, big.paidPrincipal));
        line("");
    }

    /** 缺陷 F3：强制平仓 vs 主动平仓。 */
    private static void traceLiquidation() {
        line("=== [F3] 做空强平结算口径（开仓价 100，10 股，保证金比例 1.5 → 保证金 1500）===");
        for (double px : new double[]{130.0, 100.0, 90.0, 0.0, Double.NaN}) {
            long legacy = 1500 - (px > 0 && Double.isFinite(px) ? (long) Math.ceil(px * 10) : 0);
            long fixed = ShortSettlement.validPrice(px)
                    ? ShortSettlement.payoutFullClose(1500, 100, px, 10) : 1500;
            long self = ShortSettlement.validPrice(px)
                    ? ShortSettlement.payoutWithFloor(1500, 100, px, 10) : 1500;
            line(String.format("  [输入] 现价=%s → 旧强平退 %d | 新强平退 %d | 玩家主动平仓退 %d（差 %d）",
                    px == 0 ? "0(脏)" : (Double.isNaN(px) ? "NaN(脏)" : String.valueOf(px)),
                    legacy, fixed, self, fixed - legacy));
        }
        line(String.format("  [代码分支] 爆仓（现价 200）：payoutFullClose(500,100,200,10)=%d → 追缴 %d，转入 shortDebt",
                ShortSettlement.payoutFullClose(500, 100, 200, 10),
                -ShortSettlement.payoutFullClose(500, 100, 200, 10)));
        line(String.format("  [代码分支] 主动平仓同样行情：payoutWithFloor=%d（最多亏光保证金，不倒扣）",
                ShortSettlement.payoutWithFloor(500, 100, 200, 10)));
        line(String.format("  部分平仓 4/10 股：释放保证金=%d，轧盈亏后(withPnl)退款=%d，不轧(quantity)退款=%d",
                ShortSettlement.releasedMargin(1500, 4, 10),
                ShortSettlement.payoutPartial(1500, 4, 10, ShortSettlement.pnl(100, 130, 4), true),
                ShortSettlement.payoutPartial(1500, 4, 10, ShortSettlement.pnl(100, 130, 4), false)));
        line("");
    }

    /** 缺陷 F5：限价单撮合的可用额必须排除本单自身。 */
    private static void traceLimitSelfLock() {
        line("=== [F5] 限价买单撮合可用额（触发价 100×10 股，费率 2%）===");
        UUID who = UUID.randomUUID();
        LimitOrder self = new LimitOrder(1, who, "P", "FE", true, 100.0, 10, 1, 1);
        LimitOrder twin = new LimitOrder(2, who, "P", "FE", true, 50.0, 10, 2, 1);
        List<LimitOrder> both = java.util.Arrays.asList(self, twin);
        double rate = Fees.additive(0.02, 0.0);
        long cost = Fees.buyCost(100.0, 10, rate);
        long balance = cost;                       // 玩家正好按“全部可用额”提交
        line(String.format("  [输入] 余额=%d 本单自身锁定=%d 撮合成本=%d", balance, self.lockedCoins(), cost));
        line(String.format("  [代码分支] 旧 availableCoins() → %d < %d → 判「资金不足」，"
                        + "连错 limitOrderFailRetries 次后 cancelOrder(「撤单」文案) → 订单凭空消失",
                Math.max(0, balance - LimitOrder.lockedCoinsOf(both, who, LimitOrder.NO_EXCLUSION)), cost));
        line(String.format("  [代码分支] 新 availableCoinsExceptOrder(本单) → 两张单时 %d，单张时 %d",
                Math.max(0, balance - LimitOrder.lockedCoinsOf(both, who, self.id)),
                Math.max(0, balance - LimitOrder.lockedCoinsOf(
                        java.util.Collections.singletonList(self), who, self.id))));
        line(String.format("  [结果] 单张足额限价买单：可用额 %d ≥ 成本 %d → 正常成交；"
                + "同时另一张单的 500 预留仍被扣减（排除自己 ≠ 超支）",
                Math.max(0, balance - LimitOrder.lockedCoinsOf(
                        java.util.Collections.singletonList(self), who, self.id)), cost));
    }

    // ------------------------------------------------------------------ 替身工具

    private static LoanData mkLoan(long principal, double rate, int termDays, int installments) {
        LoanData l = new LoanData();
        l.borrowDay = 100;
        l.dueDay = 100 + termDays;
        l.amount = principal;
        l.principal = principal;
        l.lockedDailyRate = rate;
        l.lockedTermDays = termDays;
        l.lockedCapMultiplier = 2.0;
        l.installments = installments;
        l.mode = LoanSchedule.MODE_BALLOON;
        return l;
    }

    /** 修复前 overdueDays 的判据（逐字复刻，只为量化差异）。 */
    private static long legacyRef(List<LoanSchedule.Row> rows, int nextInst) {
        for (LoanSchedule.Row r : rows) {
            if (r.index >= nextInst) {
                return r.dueDay;
            }
        }
        return rows.isEmpty() ? Long.MIN_VALUE : rows.get(rows.size() - 1).dueDay;
    }
}
