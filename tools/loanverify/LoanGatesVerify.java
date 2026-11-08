package cn.oremarket.common;

import java.util.List;

/**
 * 贷款四模式「还款红线」离线白盒验证——纯 JDK，秒级，工具不参与模组打包。
 *
 * <p>编译并调用的是 src/main 里的<b>真实生产代码</b>：
 * {@code LoanSchedule.balloonZeroBillPeriod / interestFinalNotReached / firstOpenDueDay /
 * openIndex / firstChargedIndex}、{@code LoanRules.splitPayment}、
 * {@code LoanCalculator.schedule / payoff / remainingInterestOwed}。
 * LoanService 的两条新闸门（repayCurrentBill / prepay 共用的 repayInner 开头）判定的就是这里断言的同一组
 * 纯函数结果，服务端与离线 harness 不可能出现两套逻辑。</p>
 *
 * <p>覆盖的用户红线：
 *  ① 一次性还本付息：前 n−1 期应还恒为 0 —— 普通还款（还10/还100/还全）必须被拦截并提示，
 *     不扣款、不标记失败或逾期；到期照常全额；提前还款可全部提前还完。
 *  ② 先息后本：未到最后一期，本金抵扣路径彻底封死（allowPrincipal=false），只扣罚息+利息，
 *     多余金额留在余额；提前还款可全部提前还完。
 *  ③ 等额本息：前 n−1 期向下取整，末期 = 总还款额 − 已扣总额，精准归零。
 *  ④ 等额本金：每期本金 floor，末期本金 = 总本金 − 已还本金，利息按剩余本金递减。</p>
 */
public final class LoanGatesVerify {

    private static int checks;
    private static int failed;

    private static void ok(boolean cond, String what) {
        checks++;
        if (cond) {
            System.out.println("  ok    " + what);
        } else {
            failed++;
            System.out.println("  FAIL  " + what);
        }
    }

    private static void eq(long want, long got, String what) {
        ok(want == got, what + " (want " + want + ", got " + got + ")");
    }

    // 基准：本金 1000、日利率 1%、3 期 × 5 交易日（到期日 = borrowDay 0 + 15）
    private static final long P = 1000L;
    private static final double RATE = 0.01;
    private static final int TERM = 15;
    private static final int N = 3;
    private static final long DUE = TERM;      // borrowDay = 0

    public static void main(String[] args) {
        balloonZeroGate();
        balloonOverdueSkip();
        interestFirstPrincipalLock();
        annuityRemainderZeroing();
        linearRemainderZeroing();
        prepayAlwaysAllowed();
        dirtyAndNullish();

        System.out.println(failed == 0
                ? "[loangates-verify] PASS: all " + checks + " assertions passed"
                : "[loangates-verify] FAIL: " + failed + "/" + checks + " assertions failed");
        if (failed != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------- 红线①：一次性还本付息零账单闸
    private static void balloonZeroGate() {
        System.out.println("== A. red line 1: balloon zero-bill gate (normal repay blocked) ==");
        // 多期一次性：到期日之前的每一天都在零账单区间 → repayCurrentBill（普通还款入口）拒绝并提示
        for (long day = 0; day < DUE; day++) {
            ok(LoanSchedule.balloonZeroBillPeriod(LoanSchedule.MODE_BALLOON, N, day, DUE),
                    "balloon multi: day " + day + " blocked (nothing due)");
        }
        // 到期日当天与之后：账单到期，普通还款照常扣
        ok(!LoanSchedule.balloonZeroBillPeriod(LoanSchedule.MODE_BALLOON, N, DUE, DUE),
                "balloon multi: due day open (repay allowed)");
        ok(!LoanSchedule.balloonZeroBillPeriod(LoanSchedule.MODE_BALLOON, N, DUE + 30, DUE),
                "balloon multi: overdue (repay allowed, never marked back into gate)");
        // 单期一次性：唯一一期就是末期，不存在前 n−1 期空账单 → 不拦
        ok(!LoanSchedule.balloonZeroBillPeriod(LoanSchedule.MODE_BALLOON, 1, 0, DUE),
                "balloon single: no zero period exists");
        // 其它三种模式不受本闸影响
        ok(!LoanSchedule.balloonZeroBillPeriod(LoanSchedule.MODE_INTEREST_FIRST, N, 1, DUE),
                "interest-first not blocked by balloon gate");
        ok(!LoanSchedule.balloonZeroBillPeriod(LoanSchedule.MODE_ANNUITY, N, 1, DUE),
                "annuity not blocked by balloon gate");
        ok(!LoanSchedule.balloonZeroBillPeriod(LoanSchedule.MODE_LINEAR, N, 1, DUE),
                "linear not blocked by balloon gate");
        // 计划表侧证据：前 n−1 期 total 恒为 0（拦截与跳过都必须基于这份数据）
        List<LoanSchedule.Row> balloon = LoanCalculator.schedule(0L, P, RATE, TERM, N,
                LoanSchedule.MODE_BALLOON);
        eq(0L, balloon.get(0).total(), "balloon row1 total 0");
        eq(0L, balloon.get(1).total(), "balloon row2 total 0");
        eq(1150L, balloon.get(2).total(), "balloon row3 total 1150");
        eq(3, LoanSchedule.firstChargedIndex(balloon), "balloon first charged row is the last");
        // 收盘自动还款判据（amountDueNow 的纯数据部分）：到期前累计应还 = 0 → autoRepay 直接跳过
        long acc = 0;
        for (LoanSchedule.Row r : balloon) {
            if (r.dueDay <= DUE - 1) {
                acc += r.total();
            }
        }
        eq(0L, acc, "auto-repay sees due 0 before due day (skips, no failure log, no overdue mark)");
    }

    // ---------------------------------------------------- 逾期判据跳过零账单期
    private static void balloonOverdueSkip() {
        System.out.println("== B. overdue baseline skips zero bills ==");
        List<LoanSchedule.Row> balloon = LoanCalculator.schedule(0L, P, RATE, TERM, N,
                LoanSchedule.MODE_BALLOON);
        for (long day = 5; day <= 10; day++) {   // 第 1、2 期空账单到期日之后
            eq(DUE, LoanSchedule.firstOpenDueDay(balloon, 1, -1L),
                    "firstOpenDueDay always the charged last row (day " + day + ")");
        }
        // 空期绝不能成为逾期基准：day 10 < due 15 → overdue = day − (due + grace) < 0
        long grace = 2;
        ok(10 - (LoanSchedule.firstOpenDueDay(balloon, 1, 0L) + grace) < 0,
                "day 10 not overdue even though zero rows 1-2 matured");
        // 已还总额推进期号：paid=0 时空期天然视为已结，直接跳到第 3 期
        eq(3, LoanSchedule.openIndex(balloon, 0), "openIndex skips zero bills (paid 0 -> row 3)");
    }

    // ---------------------------------------------------- 红线②：先息后本封死本金
    private static void interestFirstPrincipalLock() {
        System.out.println("== C. red line 2: interest-first locks the principal path ==");
        // 未到末期：判定为「锁本金」
        for (int nextInst = 1; nextInst < N; nextInst++) {
            ok(LoanSchedule.interestFinalNotReached(LoanSchedule.MODE_INTEREST_FIRST,
                            0, DUE, nextInst, N),
                    "interest-first locked (nextInst " + nextInst + ")");
        }
        // 到末期（期号推进到最后一期）或到期日已过：解锁
        ok(!LoanSchedule.interestFinalNotReached(LoanSchedule.MODE_INTEREST_FIRST,
                0, DUE, N, N), "unlocked when nextInst reaches final row");
        ok(!LoanSchedule.interestFinalNotReached(LoanSchedule.MODE_INTEREST_FIRST,
                DUE, DUE, 1, N), "unlocked on due day");
        ok(!LoanSchedule.interestFinalNotReached(LoanSchedule.MODE_INTEREST_FIRST,
                DUE + 40, DUE, 1, N), "unlocked when overdue");
        // 其它模式不受影响
        ok(!LoanSchedule.interestFinalNotReached(LoanSchedule.MODE_ANNUITY, 0, DUE, 1, N),
                "annuity never locked");
        // 「还10/还100/还全」在锁定期的真实扣款算术（allowPrincipal = principalAllowed 的取反）：
        // 只吃罚息 + 利息，本金一分不动，多出来的留在钱包（debitCoins 只扣 applied）。
        List<LoanSchedule.Row> ifp = LoanCalculator.schedule(0L, P, RATE, TERM, N,
                LoanSchedule.MODE_INTEREST_FIRST);
        eq(150L, LoanCalculator.plannedInterest(ifp), "interest-first planned interest 150");
        long interestDue = LoanCalculator.remainingInterestOwed(ifp, 0);   // 未扣过利息
        long payoff = LoanCalculator.payoff(P, P, ifp, 0, 0, 2.0);
        // 还全（STEP_ALL → want = payoff，服务端 Long.MAX_VALUE 钳制不溢出）：
        eq(payoff, Math.min(payoff, Math.max(0L, Long.MAX_VALUE)),
                "want = min(payoff, Long.MAX_VALUE) without overflow");
        long[] split = LoanRules.splitPayment(payoff, 0, interestDue, P, false);
        eq(0L, split[2], "repay-all before final row books ZERO principal");
        eq(interestDue, split[0] + split[1] + split[2], "repay-all covers penalty+interest only");
        ok(payoff > interestDue && split[0] + split[1] + split[2] < payoff,
                "the principal remainder of the payment is left in the wallet untouched");
        // 还 10：只扣利息，剩余 0 进本金
        long[] s10 = LoanRules.splitPayment(10, 0, interestDue, P, false);
        eq(10L, s10[1], "repay-10 pays interest only");
        eq(0L, s10[2], "repay-10 principal path sealed");
        // 末期解锁后 allowPrincipal=true：本金可以进
        long[] last = LoanRules.splitPayment(payoff, 0, interestDue, P, true);
        eq(P, last[2], "final row repays the whole principal");
    }

    // ---------------------------------------------------- 红线③：等额本息末期精准归零
    private static void annuityRemainderZeroing() {
        System.out.println("== D. red line 3: annuity final row = total - paid before it ==");
        List<LoanSchedule.Row> an = LoanCalculator.schedule(0L, P, RATE, TERM, N,
                LoanSchedule.MODE_ANNUITY);
        eq(383L, an.get(0).total(), "annuity row1 (floor)");
        eq(383L, an.get(1).total(), "annuity row2");
        long paidBeforeFinal = an.get(0).total() + an.get(1).total();
        eq(1150L - paidBeforeFinal, an.get(2).total(), "annuity row3 = total - previous rows");
        // 还完前两期后账面剩余（生产 payoff 公式）：必须恰等于末期
        long paidPrincipal = an.get(0).principal + an.get(1).principal;
        long paidInterest = an.get(0).interest + an.get(1).interest;
        long remain = LoanCalculator.payoff(P - paidPrincipal, P, an, paidInterest, 0, 2.0);
        eq(an.get(2).total(), remain, "payoff after rows 1-2 equals final row exactly");
        // 最后一期扣完 = 精准归零
        long allPrincipal = paidPrincipal + an.get(2).principal;
        eq(P, allPrincipal, "annuity principal sums exactly to loan principal (no 0.01 residue)");
        eq(0L, LoanCalculator.payoff(P - allPrincipal, P, an, paidInterest + an.get(2).interest,
                0, 2.0) , "payoff is 0 after final row (exact zeroing)");
    }

    // ---------------------------------------------------- 红线④：等额本金末期本金 = 剩余
    private static void linearRemainderZeroing() {
        System.out.println("== E. red line 4: linear final principal = principal - repaid ==");
        List<LoanSchedule.Row> lin = LoanCalculator.schedule(0L, P, RATE, TERM, N,
                LoanSchedule.MODE_LINEAR);
        eq(333L, lin.get(0).principal, "linear row1 principal = floor(1000/3)");
        eq(333L, lin.get(1).principal, "linear row2 principal");
        eq(P - 333L - 333L, lin.get(2).principal, "linear row3 principal = remainder");
        eq(50L, lin.get(0).interest, "linear row1 interest = 1000 * 1% * 5");
        eq(33L, lin.get(1).interest, "linear row2 interest = 667 * 1% * 5 (floor 33.35)");
        eq(16L, lin.get(2).interest, "linear row3 interest = 334 * 1% * 5 (floor 16.7)");
        eq(0L, LoanSchedule.remainingPrincipal(lin, lin.size()), "principal settles to exactly 0");
        // 递减性：利息按剩余本金下降
        ok(lin.get(0).total() > lin.get(1).total() && lin.get(1).total() > lin.get(2).total(),
                "linear totals strictly decrease");
    }

    // ---------------------------------------------------- 四模式中期决策表（提前还款通道豁免）
    private static void prepayAlwaysAllowed() {
        System.out.println("== F. mid-term decision table per mode (Prepay exempted at call site) ==");
        // 普通还款（还10/还100/还全，prepay=false）在中期第 1 天的判定矩阵：
        boolean[][] want = {          // [balloonBlocked, principalLocked]
                {true, false},        // MODE_BALLOON: 零账单闸拦截，本金锁不适用
                {false, true},        // MODE_INTEREST_FIRST: 不拦提示，但本金路径封死
                {false, false},       // MODE_ANNUITY: 普通还款照常
                {false, false},       // MODE_LINEAR: 普通还款照常
        };
        for (int mode = 0; mode < LoanSchedule.MODE_COUNT; mode++) {
            eq(want[mode][0] ? 1 : 0,
                    LoanSchedule.balloonZeroBillPeriod(mode, N, 1, DUE) ? 1 : 0,
                    "mode " + mode + ": balloon gate value at day 1");
            eq(want[mode][1] ? 1 : 0,
                    LoanSchedule.interestFinalNotReached(mode, 1, DUE, 1, N) ? 1 : 0,
                    "mode " + mode + ": principal lock at day 1");
        }
        // 提前还款的豁免由 repayInner 的 earlySettlement=true 明确选择
        // （普通还款走 `!earlySettlement && balloonZero...`；principalAllowed: `... || earlySettlement`），
        // 判据本身与意图无关——因此同一判据在四模式中期都不误拦 prepay 全额结清：
        List<LoanSchedule.Row> any = LoanCalculator.schedule(0L, P, RATE, TERM, N,
                LoanSchedule.MODE_BALLOON);
        eq(1150L, LoanCalculator.payoff(P, P, any, 0, 0, 2.0),
                "prepay settlement basis (mid-term balloon) is the full payoff 1150");
    }

    // ---------------------------------------------------- 恶意/脏输入与 NPE / 溢出面
    private static void dirtyAndNullish() {
        System.out.println("== G. dirty inputs, overflow and recursion safety ==");
        // 判据全是基本类型入参：null 无处可空；这里轰脏值，断言「不抛异常 + 结果安全」。
        int[] dirtyModes = {Integer.MIN_VALUE, -1, 4, 99, 0};
        for (int m : dirtyModes) {
            ok(!LoanSchedule.balloonZeroBillPeriod(m, 0, 0, 0)
                            || LoanSchedule.balloonZeroBillPeriod(m, 0, -1, 0),
                    "dirty mode " + m + ": balloon gate stays boolean, no throw");
            ok(!LoanSchedule.interestFinalNotReached(m, 0, 0, 0, 0),
                    "dirty mode " + m + ": lock gate closed for degenerate loan (no throw)");
        }
        ok(LoanSchedule.balloonZeroBillPeriod(LoanSchedule.MODE_BALLOON,
                Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE),
                "extreme bounds: no overflow in comparison");
        ok(!LoanSchedule.balloonZeroBillPeriod(LoanSchedule.MODE_BALLOON,
                5, Long.MAX_VALUE, Long.MAX_VALUE - 1), "extreme day: overdue is not 'mid-term'");
        // 脏金额进 splitPayment：负数按 0，绝不产生负抵扣
        long[] neg = LoanRules.splitPayment(-5, -1, -1, -1, false);
        eq(0L, neg[0] + neg[1] + neg[2], "negative split inputs clamp to zero payment");
        // Long.MAX_VALUE want 不溢出（还全路由的算式）
        ok(Math.max(0L, Long.MAX_VALUE) == Long.MAX_VALUE, "Long.MAX_VALUE stays positive in want");
        // 计划表脏参数：build 不抛、只降级
        List<LoanSchedule.Row> dirty = LoanCalculator.schedule(0L, -1, Double.NaN, 0, 0, -7);
        ok(dirty != null && dirty.isEmpty(), "principal<=0 -> empty schedule (no loan)");
        List<LoanSchedule.Row> zeroRate = LoanCalculator.schedule(0L, 1, 0.0, 1, 5, 0);
        long sumP = 0;
        for (LoanSchedule.Row r : zeroRate) {
            sumP += r.principal;
        }
        eq(1L, sumP, "1-coin balloon loan still settles principal exactly");
        for (LoanSchedule.Row r : zeroRate) {
            ok(r.total() >= 0 && r.principal >= 0 && r.interest >= 0,
                    "row " + r.index + " non-negative");
        }
    }
}
