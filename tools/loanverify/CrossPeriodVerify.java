package cn.oremarket.market;

import cn.oremarket.common.Fees;
import cn.oremarket.common.LoanCalculator;
import cn.oremarket.common.LoanRules;
import cn.oremarket.common.LoanSchedule;
import cn.oremarket.common.ShortPoolCalculator;

import java.util.List;
import java.util.UUID;

/**
 * 跨期预缴 / 四种还款方式 / 一键做空 / 强平线组合 的离线验证（纯 JDK，秒级）。
 *
 * <p>编译的是 src/main 里的<b>真实</b>源码（LoanSchedule / LoanCalculator / LoanRules /
 * LoanData / ShortPoolCalculator / ShortPosition / Fees），只对 CompoundTag 与
 * MarketSettings 用最小替身，因此这里跑过的每一条断言都对应模组里真正执行的算术。
 *
 * <p>覆盖玩家实测报的四件事 + 定版规则：
 *  1. 先息后本 5 期、借款当天点「还全」→ 只能扣当期 25，绝不把 5 期利息 125 一次收走；
 *  2. 同一天连点「还 10 / 还 100 / 还全」→ 第 2~5 期保持未结，余额留在钱包；
 *  3. 一键做空：钱够就把整个池子（6250 股）填进输入框；
 *  4. AAA 参数组合（保证金比例 0.5 + 强平线 1.15）下开仓当场不被强平；
 *  5. 定版：下一期必须走进<b>自己的计提窗口</b>（第 k 期窗口 = [第 k−1 期到期日,
 *     第 k 期到期日]）才可提前缴——「过 1 个交易日就能还下一期」的漏洞由
 *     {@link LoanSchedule#windowStartDay} 闸门堵死（用例 14 逐字复刻玩家 latest-13.log 数字）。
 */
public final class CrossPeriodVerify {
    private static int checks;
    private static int failed;

    private static void ok(boolean cond, String what) {
        checks++;
        if (!cond) {
            failed++;
            System.out.println("  [FAIL] " + what);
        } else {
            System.out.println("  [pass] " + what);
        }
    }

    private static void eq(long a, long b, String what) {
        ok(a == b, what + " (expect " + b + ", got " + a + ")");
    }

    private static void note(String s) {
        System.out.println("    " + s);
    }

    /** 造一份在贷档案：本金 principal、日利率 rate、n 期、方式 mode，借款日 = borrowDay。 */
    private static LoanData loan(long borrowDay, long principal, double rate, int n, int mode) {
        LoanData l = new LoanData();
        l.product = LoanData.PRODUCT_CREDIT;
        l.mode = mode;
        l.installments = n;
        l.principal = principal;
        l.amount = principal;
        l.lockedDailyRate = rate;
        l.lockedTermDays = LoanSchedule.totalTermDays(n);
        l.lockedCapMultiplier = 2.0;
        l.borrowDay = borrowDay;
        l.dueDay = borrowDay + l.lockedTermDays;
        l.nextInst = 1;
        // 一次性还本付息允许 1~5 期：前 n−1 期应付恒为 0，落账当场就把期号推到第一个有金额的期次
        // （与 LoanService.doBorrow 的 line 397 完全同一入口）
        l.advanceInstallment();
        return l;
    }

    /** 与服务端 LoanService.principalAllowed 同一判据（本体是纯函数 interestFinalNotReached）。 */
    private static boolean principalAllowed(LoanData l, long day, boolean prepay) {
        return !LoanSchedule.interestFinalNotReached(l.mode, day, l.dueDay, l.nextInst, l.installments)
                || prepay;
    }

    /** 复刻 LoanService.repayCurrentBill / prepay（原 repayInner / repayAllInner）的判定顺序，返回本次真正会扣走的金额（负数 = 被拦并弹对应提示）。 */
    private static long simulateCharge(LoanData l, long day, long requested, boolean prepay) {
        long payoff = l.payoff();
        if (LoanSchedule.balloonZeroBillPeriod(l.mode, l.installments, day, l.dueDay)) {
            return -1;      // -1 = 弹「一次性还本付息未到最后一期」
        }
        LoanSchedule.Payable p = l.payable(day);
        boolean allowPrincipal = principalAllowed(l, day, prepay);
        if (prepay) {
            return Math.min(payoff, Math.max(0L, requested));
        }
        if (p.early && l.lastAdvanceDay == day) {
            return -3;      // -3 = 弹「今日已流转一期」
        }
        long bill = Math.min(payoff, l.payableBill(day, allowPrincipal));
        // 「还全」定版：总应还 > 当期账单时不再整笔拦下（旧版返回 -2），
        // 而是与还 1 / 还 10 / 还 100 走同一条通道、扣满当期账单（requested=MAX → 扣 bill）。
        long want = Math.min(bill, Math.max(0L, requested));
        if (want <= 0) {
            // 定版（玩家反馈「下一期只要过 1 个交易日就能还」）：当期已缴清、下一期
            // 还没走进自己的账单窗口（计提起始日 > 今天）→ period_not_open 拦截
            long open = LoanSchedule.firstOpenDay(l.schedule(), l.nextInst, Long.MIN_VALUE);
            if (p.rows == 0 && open > Long.MIN_VALUE && open > day) {
                return -4;      // -4 = 弹「第 X 期尚未开放还款（第 Y 个交易日起才可还）」
            }
            return bill <= 0 ? -5 : 0;    // -5 = period_settled（已开放却无剩余可缴）
        }
        return want;
    }

    /** 真正落账一次扣款（splitPayment → applySplit → advanceInstallmentIndex），返回拆分明细。 */
    private static long[] pay(LoanData l, long day, long requested, boolean prepay) {
        LoanSchedule.Payable p = l.payable(day);
        boolean allowPrincipal = principalAllowed(l, day, prepay);
        long interestBill = prepay ? l.interestDue() : p.interest;
        long principalBill = prepay ? l.amount : (allowPrincipal ? p.principal : 0L);
        long bill = Math.min(l.payoff(), l.payableBill(day, allowPrincipal));
        long want = prepay ? Math.min(l.payoff(), requested) : Math.min(bill, requested);
        long[] split = LoanRules.splitPayment(want, l.penaltyDue(), interestBill, principalBill, allowPrincipal);
        l.applySplit(split);
        int before = l.nextInst;
        l.advanceInstallment();
        if (l.nextInst > before) {
            l.lastAdvanceDay = day;
        }
        return split;
    }

    public static void main(String[] args) {
        System.out.println("=== 1. 先息后本 5 期：借款当天点「还全」（玩家报的跨期预缴 Bug） ===");
        // 本金 1000、日利率 0.5%、5 期（每期 5 个交易日，总期限 25 天），借款日 = 第 1 交易日
        LoanData a = loan(1L, 1000L, 0.005, 5, LoanSchedule.MODE_INTEREST_FIRST);
        List<LoanSchedule.Row> rows = a.schedule();
        note("计划表：" + rows.size() + " 期，每期利息/本金 = "
                + rows.get(0).interest + "/" + rows.get(0).principal + " …"
                + " 末期 " + rows.get(4).interest + "/" + rows.get(4).principal);
        eq(LoanSchedule.totalInterest(rows), 125L, "计划利息合计 = 1000 × 0.5% × 25 天");
        eq(LoanSchedule.totalPrincipal(rows), 1000L, "计划本金合计 = 1000");
        eq(a.payoff(), 1125L, "第 1 天账面应还（本金 + 全部未结利息）");
        LoanSchedule.Payable p1 = a.payable(1L);
        eq(p1.interest, 25L, "第 1 天「可缴」利息只有当期 25（不是 125）");
        eq(p1.principal, 0L, "先息后本未到末期：可缴本金 0");
        eq(p1.dueDay, 6L, "当期到期日 = 借款日 + 5");
        ok(p1.early, "当期尚未到期（early = true）");
        eq(a.payableBill(1L, principalAllowed(a, 1L, false)), 25L, "第 1 天扣款上限 = 25");
        eq(simulateCharge(a, 1L, Long.MAX_VALUE, false), 25L, "「还全」一键把当期还完：只扣当期 25（绝不收走 5 期利息 125）");
        ok(simulateCharge(a, 1L, 100L, true) > 0, "同一时刻【提前还款】通道畅通（连本金一起扣并销户）");

        System.out.println("=== 2. 合法地按期还：只扣当期，第 2~5 期保持未结 ===");
        long bal = 5000L;                        // 钱包余额（模拟）
        long[] s = pay(a, 1L, 100L, false);      // 玩家点「还 100」
        note("拆分 [罚息, 利息, 本金] = " + s[0] + "/" + s[1] + "/" + s[2]);
        eq(s[0] + s[1] + s[2], 25L, "还 100 实际只扣走当期账单 25");
        bal -= (s[0] + s[1] + s[2]);
        eq(bal, 4975L, "多给的 75 留在钱包（不倒扣、不消失）");
        eq(a.nextInst, 2L, "期号只流转到第 2 期");
        eq(a.lastAdvanceDay, 1L, "记下流转日 = 第 1 交易日");
        eq(a.paidInterest, 25L, "已还利息 25（不是 125）");
        eq(a.amount, 1000L, "本金一分未动");
        eq(a.payoff(), 1100L, "剩余应还 = 1000 本金 + 100 未结利息");
        eq(LoanSchedule.openIndex(a.schedule(), a.paidPrincipal + a.paidInterest + a.paidPenalty), 2L,
                "计划表判定「已结」的期数 = 1 期（第 2~5 期仍是未结）");
        eq(simulateCharge(a, 1L, Long.MAX_VALUE, false), -4L, "同一天再点「还全」→ 第 2 期窗口未开放（第 6 个交易日起才可还），拦住");
        eq(simulateCharge(a, 1L, 100L, false), -4L, "同一天再点「还 100」→ 同样拦住");
        eq(a.payoff(), 1100L, "两次拦截都没有产生任何扣款");

        System.out.println("=== 3. 到第 2 期账单窗口开放日（第 6 交易日 = 第 1 期到期日）才继续解锁 ===");
        eq(a.payableBill(6L, principalAllowed(a, 6L, false)), 25L,
                "第 6 天可缴仍是 25（只放当期，绝不预缴后面 3 期的 75）");
        long[] s2 = pay(a, 6L, 1000L, false);
        eq(s2[0] + s2[1] + s2[2], 25L, "第 6 天点「还全」→ 只扣 25");
        eq(a.nextInst, 3L, "期号推进到第 3 期");
        eq(a.payoff(), 1075L, "剩余应还 = 1000 + 75");

        System.out.println("=== 4. 欠账可以一次补缴（第 2、3 期已到期，第 4 期未到） ===");
        LoanData b = loan(1L, 1000L, 0.005, 5, LoanSchedule.MODE_INTEREST_FIRST);
        b.nextInst = 2;
        b.paidInterest = 25L;
        LoanSchedule.Payable catchUp = b.payable(16L);
        eq(catchUp.interest, 50L, "第 16 天：已到期未结的第 2、3 期（到期日 11、16）利息 50 一次补缴");
        ok(!catchUp.early, "这些期次都已到期，不属于跨期预缴");
        eq(catchUp.rows, 2L, "一次结转 2 个已到期期次（第 4 期到期日 21 > 16，绝不提前收）");
        eq(b.payableBill(16L, false), 50L, "补缴日的扣款上限 = 50（不是利息欠额 100，更不是应还 1100）");
        eq(b.payoff(), 1100L, "账面应还 = 1000 + 100（第 5 期未到期不计入欠账）");

        System.out.println("=== 5. 等额本息（ANNUITY）公式 ===");
        LoanData c = loan(1L, 1000L, 0.005, 5, LoanSchedule.MODE_ANNUITY);
        List<LoanSchedule.Row> cr = c.schedule();
        long sumP = LoanSchedule.totalPrincipal(cr), sumI = LoanSchedule.totalInterest(cr);
        StringBuilder sb = new StringBuilder();
        for (LoanSchedule.Row r : cr) {
            sb.append("[期").append(r.index).append(" 本金").append(r.principal)
              .append(" 利息").append(r.interest).append(" 合计").append(r.total()).append("] ");
        }
        note(sb.toString());
        eq(sumP, 1000L, "Σ本金 = 1000（分毫不差）");
        eq(sumI, 125L, "Σ利息 = 1000 × 0.5% × 25 = 125");
        for (int i = 0; i < 5; i++) {
            eq(cr.get(i).interest, 25L, "第 " + (i + 1) + " 期利息 = 1000 × 0.5% × 5 = 25（单利全额，每期相同）");
            eq(cr.get(i).principal, 200L, "第 " + (i + 1) + " 期本金 = 225 − 25 = 200（末期不再出现利息跳变）");
        }
        ok(cr.get(0).total() == cr.get(1).total() && cr.get(1).total() == cr.get(2).total()
                && cr.get(2).total() == cr.get(3).total(), "前 4 期每期还款额完全相同（等额）");
        eq((1000L + 125L) / 5L, cr.get(0).total(), "每期还款额 = (本金 + 计划利息) ÷ 期数 = 225");
        eq(cr.get(4).total(), 225L, "末期同样 225（1125 能被 5 整除，零头归零）");
        eq(c.payoff(), 1125L, "第 1 天账面应还 = 1125");
        eq(c.payableBill(1L, true), 225L, "第 1 天扣款上限 = 当期 225（不是 1125）");
        eq(simulateCharge(c, 1L, Long.MAX_VALUE, false), 225L, "等额本息点「还全」= 一键缴满当期 225（不是拦下，也不是收走 1125）");
        long[] s3 = pay(c, 1L, 9999L, false);
        eq(s3[0] + s3[1] + s3[2], 225L, "点「还 100」以上最多扣 225");
        eq(c.nextInst, 2L, "只推进一期");

        System.out.println("=== 6. 等额本金（LINEAR）公式 ===");
        LoanData d = loan(1L, 1000L, 0.005, 5, LoanSchedule.MODE_LINEAR);
        List<LoanSchedule.Row> dr = d.schedule();
        StringBuilder sb2 = new StringBuilder();
        for (LoanSchedule.Row r : dr) {
            sb2.append("[期").append(r.index).append(" 本金").append(r.principal)
               .append(" 利息").append(r.interest).append(" 合计").append(r.total()).append("] ");
        }
        note(sb2.toString());
        eq(LoanSchedule.totalPrincipal(dr), 1000L, "Σ本金 = 1000");
        for (int i = 0; i < 5; i++) {
            eq(dr.get(i).principal, 200L, "第 " + (i + 1) + " 期本金 = 1000 ÷ 5 = 200");
        }
        eq(dr.get(0).interest, 25L, "第 1 期利息 = 1000 × 0.5% × 5 = 25");
        eq(dr.get(4).interest, 5L, "第 5 期利息 = 200 × 0.5% × 5 = 5（剩余本金递减）");
        eq(LoanSchedule.totalInterest(dr), 75L, "Σ利息 = 75（等额本金比等额本息的 125 少，符合银行口径）");
        ok(dr.get(0).total() > dr.get(4).total(), "每期还款额递减（等额本金的定义）");

        System.out.println("=== 7. 除不尽的分配（本金 1000 分 3 期） ===");
        List<LoanSchedule.Row> r3 = LoanCalculator.schedule(1L, 1000L, 0.0, 15, 3, LoanSchedule.MODE_LINEAR);
        eq(r3.get(0).principal, 333L, "第 1 期本金 = floor(1000/3) = 333");
        eq(r3.get(2).principal, 334L, "末期吸收零头 = 334");
        eq(LoanSchedule.totalPrincipal(r3), 1000L, "Σ本金 = 1000（不丢币、不出负数）");

        System.out.println("=== 8. 一次性还本付息 5 期（前 4 期应付 0） ===");
        LoanData e = loan(1L, 1000L, 0.005, 5, LoanSchedule.MODE_BALLOON);
        eq(e.nextInst, 5L, "落账当期就把期号推到第一笔有金额的期次（第 5 期）");
        ok(LoanSchedule.balloonZeroBillPeriod(e.mode, e.installments, 1L, e.dueDay),
                "第 1 天处于「本期应还为 0」区间");
        eq(simulateCharge(e, 1L, Long.MAX_VALUE, false), -1L, "此期间点「还全」直接提示，不扣款不跳期");
        eq(e.payoff(), 1125L, "账面应还 = 1125");
        ok(!LoanSchedule.balloonZeroBillPeriod(e.mode, e.installments, 26L, e.dueDay),
                "到期日（第 26 交易日）区间结束");
        eq(simulateCharge(e, 26L, Long.MAX_VALUE, false), 1125L, "到期日点「还全」一次结清 1125");

        System.out.println("=== 9. 罚息与利息上限：payoff 恒等式 ===");
        LoanData f = loan(1L, 1000L, 0.005, 5, LoanSchedule.MODE_INTEREST_FIRST);
        f.penalty = 30.0;
        eq(f.penaltyDue(), 30L, "罚息向上取整 = 30");
        eq(f.payoff(), 1155L, "应还 = 1000 + min(125 + 30, 1000 × (2.0 − 1)) = 1155");
        f.penalty = 5000.0;
        eq(f.payoff(), 2000L, "罚息滚到 5000 时仍被本息合计上限钳到 2000（本金 × 上限倍数）");
        long dirtyBill = Math.min(f.payoff(), f.payableBill(16L, false));
        eq(dirtyBill, 2000L, "第 16 天可缴 = min(3 期利息 75 + 罚息 5000, 账面应还 2000) = 2000（收不出不存在的钱）");

        System.out.println("=== 10. 一键做空：钱够就把整个池子端走（煤炭 6250 股） ===");
        double price = 10.0, ratio = 0.5, feeTax = Fees.combined(0.005, 0.0);
        note("单股成本 = 10 × 0.5 + 10 × 0.005 = " + (price * ratio + price * feeTax));
        eq(ShortPoolCalculator.oneClickShares(40000.0, price, ratio, feeTax, 6250, 100000), 6250L,
                "余额 40000 + 池剩 6250 → 一键填满整个池子 6250 股（旧写法只给 3125）");
        eq(ShortPoolCalculator.oneClickShares(10000.0, price, ratio, feeTax, 6250, 100000), 1980L,
                "余额 10000 → floor(10000 ÷ 5.05) = 1980 股（钱尽即停）");
        eq(ShortPoolCalculator.oneClickShares(10000.0, price, ratio, feeTax, 6250, 1000), 1000L,
                "单笔上限 1000 时按上限给");
        eq(ShortPoolCalculator.oneClickShares(10000.0, price, ratio, feeTax, 500, 100000), 500L,
                "池子只剩 500 时按池子给");
        eq(ShortPoolCalculator.oneClickShares(3.0, price, ratio, feeTax, 6250, 100000), 0L,
                "3 币连 1 股都开不出 → 返回 0，客户端本地拦截不发空包");
        // 旧口径对照：半仓基数会把 6250 的池子砍成 3125 以下
        note("旧半仓口径同样条件下只能给：" + (long) Math.floor(40000 * 0.5 / (price * ratio + price * feeTax))
                + " 股（受池子限制前），玩家报的正是这个");

        System.out.println("=== 11. AAA 参数组合下的强平线（0.5 / 1.15 / 日息 0.001） ===");
        double t2Static = 1.00;
        double t2Cap = ratio / 1.15;                                  // 动态上限 = 0.43478…
        double t2 = Math.min(t2Static, t2Cap);
        note("动态上限 shortMarginRatio ÷ shortLiquidationLine = 0.5 ÷ 1.15 = " + t2);
        ok(t2 < 1.0, "AAA 组合的阈值二必然小于 1（这正是旧下限 1.00 会顶死它的组合）");
        ShortPosition sp = newShort("COAL", 100, 10.0, 500L, 5L, ratio, 1.15, t2, 0.001);
        eq(sp.margin, 505L, "抵押物池 = 保证金 500 + 开仓税 5 = 505");
        eq((long) sp.lockedMargin, 505L, "锁定保证金与池子同源（开仓当场冻结）");
        ok(Math.abs(sp.liquidationPrice() - 11.5) < 1e-9, "条件一强平价 = 10 × 1.15 = 11.5");
        ok(!(10.0 >= sp.liquidationPrice()), "开仓价 10 < 11.5：条件一不误触发");
        ok(!(sp.margin < 10.0 * sp.quantity * sp.liquidationThreshold2),
                "开仓价 10 时条件二：505 < 434.78 不成立 → 开仓当场不被强平（旧下限 1.00 会立刻强平）");
        ok(!(sp.margin < 11.5 * sp.quantity * sp.liquidationThreshold2),
                "11.5 时条件二仍未触发（505 < 500.0 不成立）→ 条件一先到，两线不打架");
        ok(sp.margin < 11.62 * sp.quantity * sp.liquidationThreshold2,
                "11.62 时条件二才成立（505 < 505.5）→ 条件一是唯一首触发线");
        ok(sp.margin < 10.0 * sp.quantity * 1.00,
                "对照旧 Bug：阈值二被 1.00 下限时 505 < 10×100×1.00 成立 → 开仓当场就强平（现已放开到 0.05）");
        long daily = (long) Math.ceil(sp.openPrice * sp.quantity * sp.dailyInterestRate);
        eq(daily, 1L, "日息 = ceil(10 × 100 × 0.001) = 1 币/天（旧 3% 是 30 币/天，第 18 天自己吃掉保证金）");
        note("505 币池子按 1 币/天可以撑 505 个交易日；按 30 币/天只能撑 17 个");

        System.out.println("=== 12. 玩家实测复现：14252 抵押贷 / 等额本金 / 5 期，连点 11 次还 100 再点「还全」 ===");
        LoanData u = loan(33L, 14252L, 0.0041, 5, LoanSchedule.MODE_LINEAR);
        List<LoanSchedule.Row> ur = u.schedule();
        note("第 1 期：本金 " + ur.get(0).principal + " 利息 " + ur.get(0).interest
                + " 合计 " + ur.get(0).total() + "；末期 " + ur.get(4).principal + "/" + ur.get(4).interest);
        eq(ur.get(0).principal, 2850L, "第 1 期本金 = floor(14252 ÷ 5) = 2850");
        eq(ur.get(0).interest, 292L, "第 1 期利息 = floor(14252 × 0.41% × 5) = 292");
        eq(LoanSchedule.totalInterest(ur), 874L, "计划利息合计 = 292+233+175+116+58 = 874（游戏实测 863 是锁定利率 0.4036% 的同一公式，界面四舍五入显示 0.41%）");
        eq(u.payableBill(33L, true), 3142L, "借款当天的「本期应还」= 292 + 2850 = 3142");
        long paidI = 0;
        long paidP = 0;
        for (int i = 0; i < 11; i++) {
            long[] st12 = pay(u, 33L, 100L, false);   // 连点 11 次「还 100」（玩家截图里的操作）
            paidI += st12[1];
            paidP += st12[2];
        }
        eq(paidI, 292L, "11 × 100 = 1100 里进利息的最多 292（= 当期计划利息；旧口径灌到 800+，把第 2~5 期利息提前缴走）");
        eq(paidP, 808L, "剩下的 808 全部抵当期本金（旧口径同期只抵 226，874 的整笔计划利息全被提前收光）");
        eq(u.paidInterest, 292L, "已还利息停在第 1 期的 292：后面 4 期的 571 利息一分没收（跨期预缴红线从利息缝里漏出去的洞已堵死）");
        eq(u.amount, 13444L, "剩余本金 = 14252 − 808 = 13444");
        eq(u.nextInst, 1L, "第 1 期没缴满（1100 < 3142）：期号仍是第 1 期");
        eq(u.payableBill(33L, true), 2042L, "「本期应还」随每一笔同步缩小：3142 − 1100 = 2042（旧口径会卡在 3142 不动）");
        eq(simulateCharge(u, 33L, Long.MAX_VALUE, false), 2042L, "点「还全」= 一次缴满当期剩余 2042（旧版直接拦死，只能继续连点还 100）");
        long[] fin = pay(u, 33L, 2042L, false);
        eq(fin[0] + fin[1] + fin[2], 2042L, "「还全」实扣 2042");
        eq(u.nextInst, 2L, "第 1 期结清，期号推进到第 2 期");
        eq(u.lastAdvanceDay, 33L, "流转日记为第 33 交易日");
        eq(simulateCharge(u, 33L, Long.MAX_VALUE, false), -4L, "同一天再点「还全」→「第 2 期尚未开放还款（第 38 个交易日起才可还）」拦下（旧口径过 1 个交易日就能缴第 2 期）");
        eq(u.payoff(), 11984L, "当期还完后账面应还 = 剩余本金 11402 + 未结利息 582 = 11984（钱没有凭空消失，也没有多收）");
        eq(simulateCharge(u, 37L, Long.MAX_VALUE, false), -4L, "第 37 交易日（窗口开放前 1 天）点「还全」→ 依旧拦下，不扣一分");
        eq(u.payableBill(38L, true), 3083L, "第 38 交易日 = 第 2 期计提窗口开放：本期应还 = 本金 2850 + 利息 233 = 3083");

        System.out.println("=== 13. 抵押贷催收（Margin Call）：旧判据次日误强平 vs 新判据 ===");
        // 玩家实测：借 14252（抵押贷 5 期），持仓市值只有 913，钱包 10 万+ 矿币，
        // 第 34 交易日收盘旧判据 913 < 80% × 13915 → 误强平（「下一个交易日后就贷款到期强制清偿」）。
        long[] m1 = LoanRules.marginCallNumbers(103000L, 913L, 0L, 13915L);
        note("玩家实测数字：矿币 103000 + 持仓 913 + 背包 0，本金 13915 → 可清偿 " + m1[0] + "，阈值 " + m1[1]);
        ok(m1[0] >= m1[1], "新判据：可清偿 103913 ≥ 11132 → 不强平（旧判据 913 < 11132 必误强平）");
        long[] m2 = LoanRules.marginCallNumbers(14252L, 913L, 0L, 14252L);
        ok(m2[0] >= m2[1], "放款当天钱还躺在钱包里：15165 ≥ 11401 → 判据天然不会在借款当日误触发");
        long[] m3 = LoanRules.marginCallNumbers(500L, 300L, 0L, 14252L);
        ok(m3[0] < m3[1], "真资不抵债（矿币 500 + 持仓 300，本金 14252）：800 < 11401 → 催收强平仍然生效（双保险没拆）");
        long[] m4 = LoanRules.marginCallNumbers(500L, 300L, 11000L, 14252L);
        ok(m4[0] >= m4[1], "背包矿物是强平收缴来源之一，计入可清偿：11800 ≥ 11401 → 不误收");
        long[] m5 = LoanRules.marginCallNumbers(0L, 0L, 0L, 0L);
        eq(m5[1], 1L, "脏本金 0 时阈值保底 1（不除零、不空触发；调用方另有 hasLoan() 前置闸）");
        ok(!LoanRules.marginCallTriggered(103000L, 913L, 0L, 13915L), "触发判据与数字判据同源：同一组数字 triggered=false");
        ok(LoanRules.marginCallTriggered(500L, 300L, 0L, 14252L), "同一组塌方数字 triggered=true");

        System.out.println("=== 14. 期窗口闸门：5 期 25 交易日，下一期必须走进自己的计提窗口才能还 ===");
        // 逐字复刻玩家 latest-13.log：借款日 228、等额本金 5 期、本金 13825、日利率 0.0037504615384615376。
        // 日志里旧 Bug 的表现：20:30:42 缴第 1 期 → 20:30:44 就能缴第 2 期 → 连点 4 次 + 结清，
        // 一笔 25 个交易日的贷款在几个交易日内被逐日「流完」5 期。
        LoanData w = loan(228L, 13825L, 0.0037504615384615376, 5, LoanSchedule.MODE_LINEAR);
        List<LoanSchedule.Row> wr = w.schedule();
        eq(wr.get(0).total(), 3024L, "第 1 期账单 = 2765 本金 + 259 利息 = 3024（游戏日志 [repay-all] bill 3024）");
        eq(wr.get(1).total(), 2972L, "第 2 期账单 = 2765 + 207 = 2972（日志 bill 2972）");
        eq(wr.get(2).total(), 2920L, "第 3 期账单 = 2920（日志 bill 2920）");
        eq(wr.get(3).total(), 2868L, "第 4 期账单 = 2868（日志 bill 2868）");
        eq(wr.get(4).total(), 2816L, "第 5 期账单 = 2816（日志 payoff 2816）");
        eq(LoanSchedule.windowStartDay(wr.get(0)), 228L, "第 1 期窗口起点 = 借款日 228");
        eq(LoanSchedule.windowStartDay(wr.get(1)), 233L, "第 2 期窗口起点 = 第 1 期到期日 233");
        eq(LoanSchedule.windowStartDay(wr.get(4)), 248L, "第 5 期窗口起点 = 248（末期 253 到期）");
        eq(simulateCharge(w, 228L, Long.MAX_VALUE, false), 3024L, "借款当天点「还全」→ 缴满第 1 期 3024");
        pay(w, 228L, 3024L, false);
        eq(w.nextInst, 2L, "期号推进到第 2 期");
        for (long day = 229; day <= 232; day++) {
            eq(simulateCharge(w, day, Long.MAX_VALUE, false), -4L,
                    "第 " + day + " 交易日（旧 Bug 在这里就能缴第 2 期）点「还全」→ 拦下：第 2 期第 233 个交易日起才开放");
            eq(w.payableBill(day, true), 0L, "客户端「本期应还」同源 = 0.00");
        }
        eq(w.payoff(), 14600L - 3024L, "连点 4 天一分钱没被扣走（账面仍是后 4 期 11576）");
        eq(simulateCharge(w, 233L, Long.MAX_VALUE, false), 2972L, "第 233 交易日（第 1 期到期日 = 第 2 期计提窗口开放）→ 第 2 期可缴 2972");
        pay(w, 233L, 2972L, false);
        eq(simulateCharge(w, 233L, Long.MAX_VALUE, false), -4L, "第 233 天再点 → 第 3 期窗口（238）未开放，拦下");
        eq(simulateCharge(w, 237L, Long.MAX_VALUE, false), -4L, "第 237 交易日点「还全」→ 依旧拦下");
        pay(w, 238L, simulateCharge(w, 238L, Long.MAX_VALUE, false), false);
        eq(simulateCharge(w, 238L, Long.MAX_VALUE, false), -4L, "第 3 期缴完后第 4 期也要等窗口（243）");
        eq(w.nextInst, 4L, "第 4 期之前保持按期流转：期号 = 4");
        pay(w, 243L, 2868L, false);
        eq(w.payoff(), 2816L, "第 4 期（243 开放）缴清后只剩末期 2816");
        eq(simulateCharge(w, 247L, Long.MAX_VALUE, false), -4L, "第 247 交易日末期未开放 → 拦下（旧 Bug：这天早就能结清）");
        eq(simulateCharge(w, 248L, Long.MAX_VALUE, false), 2816L, "末期计提窗口 248 开放 → 点「还全」一次结清 2816");
        pay(w, 248L, 2816L, false);
        eq(w.amount, 0L, "末期结清：本金归零（最早结清日 = 第 248 交易日 = 借款后 20 个交易日；旧 Bug 实测只用 4 个）");
        eq(w.payoff(), 0L, "结清后账面归零");
        // 「每个交易日最多流转一期」闸门仍在：补缴日第 233 天先缴清已到期的第 1 期，同日还想再流第 2 期 → -3
        LoanData v = loan(228L, 13825L, 0.0037504615384615376, 5, LoanSchedule.MODE_LINEAR);
        eq(simulateCharge(v, 233L, Long.MAX_VALUE, false), 3024L, "漏缴到到期日才补：第 233 天可缴 = 第 1 期 3024（第 2 期没进账单）");
        pay(v, 233L, 3024L, false);
        eq(v.nextInst, 2L, "补缴后期号推到第 2 期");
        eq(simulateCharge(v, 233L, Long.MAX_VALUE, false), -3L, "同一交易日里第 2 期已在窗口内、但今天已流转一期 →「今日已流转一期」拦下");
        eq(simulateCharge(v, 234L, Long.MAX_VALUE, false), 2972L, "隔天（234，窗口 233~238 内）第 2 期照常可缴");

        System.out.println();
        System.out.println(failed == 0
                ? "ALL CHECKS PASSED (" + checks + " assertions)"
                : "FAILED " + failed + " / " + checks + " assertions");
        if (failed != 0) {
            System.exit(1);
        }
    }

    /** 用真实构造器建空仓（10 参构造器带 openTax，与 ShortService.open 同一入口）。 */
    private static ShortPosition newShort(String code, int qty, double openPrice, long margin, long openTax,
                                          double ratio, double line, double t2, double daily) {
        return new ShortPosition(UUID.randomUUID(), code, qty, openPrice, margin, openTax,
                ratio, line, t2, daily, 1L);
    }
}
