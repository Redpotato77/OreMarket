package cn.oremarket.market;

import cn.oremarket.common.LoanRules;
import cn.oremarket.common.ShortSettlement;
import cn.oremarket.common.LoanSchedule;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.UUID;

/**
 * 贷款 v2 离线验证（纯 JDK，秒级）。
 *
 * <p>编译的是 src/main 里的**真实源码**（LoanSchedule / LoanRules / LoanData / P2PListing），
 * 只对 CompoundTag 与 MarketSettings 用最小替身，因此这里跑过的每一条断言
 * 都对应模组里真正执行的算术与存档格式。工具不参与模组打包。
 */
public final class Verify {
    private static int checks;
    private static int failed;

    private static void ok(boolean cond, String what) {
        checks++;
        if (!cond) {
            failed++;
            System.out.println("  FAIL  " + what);
        }
    }

    private static void eq(long a, long b, String what) {
        ok(a == b, what + " (期望 " + b + "，实际 " + a + ")");
    }

    private static void near(double a, double b, double eps, String what) {
        ok(Math.abs(a - b) <= eps, what + " (期望 " + b + "±" + eps + "，实际 " + a + ")");
    }

    public static void main(String[] args) {
        scheduleBasics();
        scheduleBoundary();
        rulesRate();
        rulesPrepayAndSplit();
        rulesScoreAndLimit();
        loanDataRoundTrip();
        loanDataLegacyArchive();
        listingRoundTrip();
        lumpSumMultiPeriod();
        seizedValueBooking();
        shortLiquidationParity();
        limitOrderSelfLock();

        System.out.println(failed == 0
                ? "[loanverify] PASS: " + checks + " 条断言全部通过"
                : "[loanverify] FAIL: " + failed + "/" + checks + " 条断言未通过");
        if (failed != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 摊销表

    private static void scheduleBasics() {
        System.out.println("[1] 四种还款方式与日拆分");
        // 期限 7 天分 3 期：前 7%3=1 期各多 1 天 → 3 + 2 + 2
        List<LoanSchedule.Row> split = LoanSchedule.build(1, 1000, 0.001, 7, 3, LoanSchedule.MODE_ANNUITY);
        eq(split.size(), 3, "期数");
        eq(split.get(0).days, 3, "第 1 期天数");
        eq(split.get(1).days, 2, "第 2 期天数");
        eq(split.get(2).days + split.get(0).days + split.get(1).days, 7, "天数合计=期限");
        eq(split.get(0).dueDay, 1 + 3, "第 1 期到期日");
        eq(split.get(2).dueDay, 1 + 7, "最后一期到期日=借款日+期限");

        // 一次性还本付息：只有一期，本金全额落在末期
        List<LoanSchedule.Row> balloon = LoanSchedule.build(10, 1000, 0.002, 5, 1, LoanSchedule.MODE_BALLOON);
        eq(balloon.size(), 1, "到期一次还本付息只有一期");
        eq(balloon.get(0).principal, 1000, "本金全额");
        eq(balloon.get(0).interest, 10, "利息 = 1000×0.002×5");
        eq(balloon.get(0).total(), 1010, "本期合计");

        // 等额本金：每期本金均分，余数摊给前面几期
        List<LoanSchedule.Row> linear = LoanSchedule.build(1, 1000, 0.001, 9, 4, LoanSchedule.MODE_LINEAR);
        eq(LoanSchedule.totalPrincipal(linear), 1000, "等额本金本金合计=借款额");
        eq(linear.get(0).principal + linear.get(1).principal + linear.get(2).principal + linear.get(3).principal,
                1000, "四期本金相加");
        ok(linear.get(0).principal >= linear.get(3).principal, "等额本金各期金额相近");
        // 余数摊给**末期**（实现口径）：1000 分 3 期 → 333 + 333 + 334
        List<LoanSchedule.Row> rem = LoanSchedule.build(1, 1000, 0.0, 9, 3, LoanSchedule.MODE_LINEAR);
        eq(rem.get(0).principal, 333, "等额本金首期");
        eq(rem.get(2).principal, 334, "余数进末期");
        eq(LoanSchedule.totalPrincipal(rem), 1000, "余数不丢不增");
        // 一次性还本付息允许多期排期：前 n-1 期本息为 0，全部落在末期
        List<LoanSchedule.Row> balloon6 = LoanSchedule.build(1, 1000, 0.001, 12, 6, LoanSchedule.MODE_BALLOON);
        eq(balloon6.size(), 6, "到期一次还本付息可按期数排期");
        eq(balloon6.get(5).dueDay, 13, "末期到期日 = 借款日 + 期限");
        eq(balloon6.get(5).interest, 12, "利息按整个期限计（1000×0.001×12）");
        eq(balloon6.get(0).total(), 0, "前几期扣款为 0");

        // 等额本息：每期合计相同（±1 取整误差）
        List<LoanSchedule.Row> annuity = LoanSchedule.build(1, 1200, 0.002, 12, 4, LoanSchedule.MODE_ANNUITY);
        eq(LoanSchedule.totalPrincipal(annuity), 1200, "等额本息本金合计");
        long min = Long.MAX_VALUE;
        long max = 0;
        for (LoanSchedule.Row r : annuity) {
            min = Math.min(min, r.total());
            max = Math.max(max, r.total());
        }
        ok(max - min <= 2, "等额本息各期合计相等（取整误差 " + (max - min) + "）");

        // 先息后本：中间各期只还利息，末期还全部本金
        List<LoanSchedule.Row> first = LoanSchedule.build(1, 1000, 0.001, 9, 3, LoanSchedule.MODE_INTEREST_FIRST);
        eq(first.get(0).principal, 0, "先息后本首期不还本金");
        eq(first.get(1).principal, 0, "先息后本次期不还本金");
        eq(first.get(2).principal, 1000, "先息后本末期还清本金");
        eq(LoanSchedule.totalPrincipal(first), 1000, "先息后本本金合计");
    }

    private static void scheduleBoundary() {
        System.out.println("[2] 边界：0 / 负数 / 溢出 / 除零 / 脏浮点");
        List<LoanSchedule.Row> zero = LoanSchedule.build(1, 0, 0.001, 5, 1, LoanSchedule.MODE_BALLOON);
        eq(zero.size(), 0, "本金 0 → 不出计划行");
        List<LoanSchedule.Row> neg = LoanSchedule.build(1, -500, 0.001, 5, 1, LoanSchedule.MODE_BALLOON);
        eq(neg.size(), 0, "本金为负 → 不出计划行");
        List<LoanSchedule.Row> negRate = LoanSchedule.build(1, 1000, -0.5, 5, 1, LoanSchedule.MODE_BALLOON);
        eq(LoanSchedule.totalInterest(negRate), 0, "负利率按 0 计");
        List<LoanSchedule.Row> nan = LoanSchedule.build(1, 1000, Double.NaN, 5, 3, LoanSchedule.MODE_LINEAR);
        eq(LoanSchedule.totalPrincipal(nan), 1000, "NaN 利率不毁掉本金");
        for (LoanSchedule.Row r : nan) {
            ok(r.interest >= 0 && r.total() >= 0, "NaN 利率下各期仍为非负");
        }
        List<LoanSchedule.Row> inf = LoanSchedule.build(1, 1000, Double.POSITIVE_INFINITY, 5, 1,
                LoanSchedule.MODE_BALLOON);
        ok(LoanSchedule.totalPayoff(inf) <= LoanSchedule.SAFE_MAX_LIMIT, "无穷利率被钳到安全上限");

        // 0 期限 / 负期限都不能除零
        List<LoanSchedule.Row> t0 = LoanSchedule.build(1, 1000, 0.001, 0, 3, LoanSchedule.MODE_ANNUITY);
        ok(t0.size() >= 1, "期限 0 仍至少一期（不除零）");
        List<LoanSchedule.Row> tn = LoanSchedule.build(1, 1000, 0.001, -7, 3, LoanSchedule.MODE_ANNUITY);
        ok(tn.size() >= 1, "期限为负仍不崩（内部钳到 ≥1 天）");

        // 溢出：Long.MAX_VALUE 不允许变负
        List<LoanSchedule.Row> huge = LoanSchedule.build(1, Long.MAX_VALUE, 0.05, 5, 1, LoanSchedule.MODE_BALLOON);
        ok(LoanSchedule.totalPayoff(huge) > 0, "超大本金结果仍为正（无溢出翻负）");
        ok(LoanSchedule.totalPayoff(huge) <= LoanSchedule.SAFE_MAX_LIMIT * 2, "超大本金被钳住");
        eq(LoanSchedule.clampL(-9), 0, "clampL 负数→0");
        eq(LoanSchedule.clampL(Double.NaN), 0, "clampL NaN→0");

        // 期数 / 方式钳位
        eq(LoanSchedule.clampInstallments(0), 1, "期数 0→1");
        eq(LoanSchedule.clampInstallments(-3), 1, "期数负数→1");
        eq(LoanSchedule.clampInstallments(999), LoanSchedule.MAX_INSTALLMENTS, "期数超上限→5");
        eq(LoanSchedule.totalTermDays(1), 5, "1 期 = 5 天");
        eq(LoanSchedule.totalTermDays(5), 25, "5 期 = 25 天");
        eq(LoanSchedule.clampMode(-1), LoanSchedule.MODE_BALLOON, "方式 -1→默认");
        eq(LoanSchedule.clampMode(99), LoanSchedule.MODE_BALLOON, "方式越界→默认");
        eq(LoanSchedule.totalPrincipal(List.of()), 0, "空表合计为 0");
        eq(LoanSchedule.remainingPrincipal(List.of(), 0), 0, "空表剩余本金为 0");
    }

    // ---------------------------------------------------------------- 利率 / 结清 / 抵扣

    private static void rulesRate() {
        System.out.println("[3] 基础×信用减免×期限乘数 + 1% 硬封顶");
        MarketSettings s = MarketSettings.current();
        double mid = LoanRules.quoteDailyRate(0.35, 625, 0, 0, 12, true);
        near(mid, 0.0035, 1e-9, "中性评分 625 + 无事件 = 基础日利率");
        double hike = LoanRules.quoteDailyRate(0.35, 625, 40, 1.0, 12, true);
        double ease = LoanRules.quoteDailyRate(0.35, 625, 40, -1.0, 12, true);
        ok(hike > mid && mid > ease, "加息 > 中性 > 宽松");

        double bad = LoanRules.quoteDailyRate(0.35, LoanRules.SCORE_MIN, 0, 0, 12, true);
        double great = LoanRules.quoteDailyRate(0.35, LoanRules.SCORE_MAX, 0, 0, 12, true);
        ok(bad > mid, "低评分信用乘数 > 1");
        ok(great < mid, "高评分信用乘数 < 1");

        double worst = LoanRules.quoteDailyRate(0.35, LoanRules.SCORE_MIN, 40, 1.0, 12, true);
        ok(worst <= 0.01 + 1e-12, "利率不超过 1% 硬上限");
        double capped = LoanRules.quoteDailyRate(50, 300, 400, 1.0, 12, true);
        ok(capped <= 0.01 + 1e-12, "脏参数下仍被 1% 钳住");

        ok(LoanRules.quoteDailyRate(Double.NaN, 650, Double.NaN, Double.NaN, Double.NaN, true) >= 0.0,
                "全 NaN 输入不抛异常且非负");
        ok(LoanRules.quoteDailyRate(-10, 0, -10, -5, -1, true) == 0.0, "负上限→0");
        double off = LoanRules.quoteDailyRate(0.35, LoanRules.SCORE_MIN, 40, 1.0, 12, false);
        near(off, LoanRules.quoteDailyRate(0.35, 625, 40, 1.0, 12, false), 1e-12,
                "关闭信用评分时信用乘数固定 1");

        near(LoanRules.lockedDailyRate(0.005, 1.0, 5, 12), 0.006, 1e-9, "5 期期限乘数 1.20");
        near(LoanRules.penaltyRate(s), 0.02, 1e-12, "罚息日利率按配置百分数换算");
    }

    private static void rulesPrepayAndSplit() {
        System.out.println("[4] 提前结清（折扣/手续费）与部分还款抵扣顺序");
        // 本金 1000、账面利息 100：利息打 9 折 + 1% 手续费
        eq(LoanRules.prepayAmount(1000, 100, 10, 1), 1000 + 90 + 10, "折扣只作用于利息，手续费按本金");
        eq(LoanRules.prepayAmount(1000, 100, 0, 0), 1100, "无折扣无手续费=本息合计");
        eq(LoanRules.prepayAmount(1000, 100, 100, 0), 1000, "利息全免也不会低于本金");
        eq(LoanRules.prepayAmount(0, 0, 10, 1), 0, "0 本金 0 利息");
        eq(LoanRules.prepayAmount(-500, -500, -10, -10), 0, "脏输入全部钳到 0");
        // 折扣钳到 100%（利息全免）、手续费钳到 100%（按本金再收一倍）：脏参数也不能算出负数
        eq(LoanRules.prepayAmount(1000, 100, 500, 1), 1010, "折扣越界钳到 100%，手续费照常");
        eq(LoanRules.prepayAmount(1000, 100, 500, 500), 2000, "折扣与手续费双双越界仍按钳位值计算");
        eq(LoanRules.prepayAmount(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0),
                LoanSchedule.SAFE_MAX_LIMIT, "巨额结清额被钳到安全上限（不溢出翻负）");

        // 抵扣顺序：罚息 → 利息 → 本金
        long[] a = LoanRules.splitPayment(100, 30, 40, 500);
        eq(a[0], 30, "先冲罚息");
        eq(a[1], 40, "再冲利息");
        eq(a[2], 30, "余下冲本金");
        long[] b = LoanRules.splitPayment(-10, 30, 40, 500);
        eq(b[0] + b[1] + b[2], 0, "负付款额按 0 处理");
        long[] c = LoanRules.splitPayment(1000, 0, 0, 0);
        eq(c[0] + c[1] + c[2], 0, "无欠款时不凭空抵扣");
        long[] d = LoanRules.splitPayment(500, -5, -5, 600);
        eq(d[0], 0, "负欠款不被抵扣");
        eq(d[1] + d[2], 500, "脏欠款下仍按剩余抵扣本金");

        // 关键不变式：付款额 ≤ 账面应还时，抵扣合计必须恰好等于付款额（钱不能凭空消失）
        for (long pay : new long[]{1, 7, 99, 100, 101, 999, 1000}) {
            long penalty = 30;
            long interest = 40;
            long principal = 500;
            long payoff = penalty + interest + principal;
            long[] r = LoanRules.splitPayment(Math.min(pay, payoff), penalty, interest, principal);
            eq(r[0] + r[1] + r[2], Math.min(pay, payoff), "付款 " + pay + " 时抵扣合计=实付");
        }
    }

    // ---------------------------------------------------------------- 信用分 / 动态额度

    private static void rulesScoreAndLimit() {
        System.out.println("[5] 信用评分与动态额度");
        MarketSettings s = MarketSettings.current();
        // 600 + min(120, 按时×12) − 逾期×35 − 破产×150 + min(60, 资产/2000) + 等级×15
        int fresh = LoanRules.creditScore(0, 0, 0, 1000, 2, 12, 35, 150, 2000, 15);
        eq(fresh, 630, "干净档案（资产不足一档 + 2 级×15）");
        int good = LoanRules.creditScore(5, 0, 0, 1000, 2, 12, 35, 150, 2000, 15);
        eq(good - fresh, 60, "5 笔按时结清 = +60 分");
        int bad = LoanRules.creditScore(0, 3, 0, 1000, 2, 12, 35, 150, 2000, 15);
        eq(fresh - bad, 105, "3 次逾期 = −105 分");
        int broke = LoanRules.creditScore(0, 0, 1, 1000, 2, 12, 35, 150, 2000, 15);
        eq(fresh - broke, 150, "1 次破产 = −150 分");
        int rich = LoanRules.creditScore(0, 0, 0, 100_000, 2, 12, 35, 150, 2000, 15);
        eq(rich - fresh, 50, "资产每 2000 矿币 +1 分");
        ok(LoanRules.creditScore(0, 0, 0, Long.MAX_VALUE, 2, 12, 35, 150, 1, 15) - fresh <= 61,
                "资产加分封顶 +60");
        eq(LoanRules.creditScore(999, 0, 0, 0, 50, 12, 35, 150, 2000, 15), LoanRules.SCORE_MAX,
                "评分封顶 950");
        eq(LoanRules.creditScore(0, 999, 0, 0, 0, 12, 35, 150, 2000, 15), LoanRules.SCORE_MIN,
                "评分封底 300");
        ok(LoanRules.creditScore(-5, -5, -5, -100, -3, 12, 35, 150, 2000, 15) >= LoanRules.SCORE_MIN,
                "负数入参不越界（按 0 处理）");
        eq(LoanRules.creditScore(0, 0, 0, 1000, 2, 12, 35, 150, 0, 15), fresh, "assetsStep=0 不除零");
        boolean saved = s.loanCreditScoreEnabled;
        s.loanCreditScoreEnabled = false;
        eq(LoanRules.creditScore(5, 0, 0, 1000, 2), LoanRules.SCORE_BASE, "关闭信用评分时恒为基准分");
        s.loanCreditScoreEnabled = saved;
        ok(LoanRules.creditScore(5, 0, 0, 1000, 2) > LoanRules.SCORE_BASE, "开启后按记录加分");

        eq(LoanRules.scoreBand(LoanRules.SCORE_MIN), 0, "最低档");
        eq(LoanRules.scoreBand(LoanRules.SCORE_MAX), 4, "最高档");
        ok(LoanRules.scoreBand(400) < LoanRules.scoreBand(900), "档位随评分单调上升");
        near(LoanRules.scoreLimitMultiplier(600), 1.00, 1e-12, "普通档（550~649）额度倍率 1.00");
        near(LoanRules.scoreLimitMultiplier(650), 1.25, 1e-12, "良好档（650~749）额度倍率 1.25");
        near(LoanRules.scoreLimitMultiplier(760), 1.50, 1e-12, "极优档（750+）额度倍率 1.50");
        near(LoanRules.scoreLimitMultiplier(300), 0.50, 1e-12, "最差档额度倍率 0.50");
        ok(LoanRules.scoreLimitMultiplier(600) < LoanRules.scoreLimitMultiplier(700), "额度倍率随评分单调上升");
        ok(LoanRules.scoreBandKey(650).startsWith("oremarket.loan.score."), "档位返回语言键（不是中文原文）");

        long clean = LoanRules.dynamicLimit(1000, 600, 20_000, 0, 0, false, 20, 50, true);
        eq(clean, 1000 + 4000, "普通档基础额度 + 峰值资产 20%");
        long high = LoanRules.dynamicLimit(1000, 760, 20_000, 0, 0, false, 20, 50, true);
        eq(high, 1500 + 4000, "极优档额度倍率 1.5");
        long low = LoanRules.dynamicLimit(1000, 400, 20_000, 0, 3, false, 20, 50, true);
        ok(low < clean, "低分 + 连续逾期 → 额度下降");
        long one = LoanRules.dynamicLimit(1000, 600, 20_000, 0, 1, false, 20, 50, true);
        long two = LoanRules.dynamicLimit(1000, 600, 20_000, 0, 2, false, 20, 50, true);
        long nine = LoanRules.dynamicLimit(1000, 600, 20_000, 0, 9, false, 20, 50, true);
        ok(one > two && two > nine, "同一评分下逾期越多额度越低");
        eq(nine, (long) Math.floor((1000 + 4000) * 0.4), "折减有 40% 下限（逾期再多也不会归零）");
        long coll = LoanRules.dynamicLimit(1000, 600, 20_000, 8_000, 0, true, 20, 50, true);
        eq(coll - clean, 4000, "抵押贷按抵押物 50% 追加额度");
        long off = LoanRules.dynamicLimit(1000, 300, 0, 0, 0, false, 20, 50, false);
        eq(off, 1000, "关闭信用评分时只看等级基础额度");
        eq(LoanRules.dynamicLimit(0, 950, 1_000_000, 1_000_000, 0, true, 20, 50, true), 0,
                "等级额度为 0（未到借款等级）→ 动态额度也是 0");
        long dirty = LoanRules.dynamicLimit(-1, 0, Long.MAX_VALUE, Long.MAX_VALUE, -5, true, 20, 50, true);
        eq(dirty, 0, "脏输入不产生负额度");
        long huge = LoanRules.dynamicLimit(1000, 950, Long.MAX_VALUE, Long.MAX_VALUE, 0, true, 20, 50, true);
        ok(huge > 0 && huge <= LoanSchedule.SAFE_MAX_LIMIT, "溢出风险被钳到安全上限");

        near(LoanRules.penaltyRate(s), 0.02, 1e-12, "罚息日利率按配置百分数换算");
    }

    // ---------------------------------------------------------------- 存档格式

    private static void loanDataRoundTrip() {
        System.out.println("[6] LoanData v2 存档读写（字段名 + 默认值）");
        LoanData l = new LoanData();
        UUID lender = UUID.randomUUID();
        l.amount = 1234;
        l.interest = 12.5;
        l.penalty = 3.25;
        l.dueDay = 77;
        l.borrowDay = 70;
        l.cooldownEndDay = 80;
        l.liquidating = true;
        l.product = LoanData.PRODUCT_MARGIN;
        l.mode = LoanSchedule.MODE_ANNUITY;
        l.installments = 5;
        l.principal = 1234;
        l.paidPrincipal = 100;
        l.paidInterest = 20;
        l.paidPenalty = 5;
        l.nextInst = 3;
        l.accruedDay = 75;
        l.overdueStrikes = 2;
        l.limitStrikes = 1;
        l.limitStrikeDay = 74;
        l.onTimeRepays = 9;
        l.overdueTotal = 4;
        l.bankruptcies = 1;
        l.lastSettledDay = 60;
        l.lockedDailyRate = 0.001;
        l.lockedTermDays = 7;
        l.lockedCapMultiplier = 2.0;
        l.loanFunds = 500;
        l.clawbackDebt = 42;
        l.p2pLender = lender;
        l.p2pListingId = 8;
        l.p2pDelivered = 11;

        CompoundTag t = l.toTag();
        LoanData back = LoanData.fromTag(t);
        eq(back.amount, 1234, "amount");
        near(back.interest, 12.5, 1e-9, "interest");
        near(back.penalty, 3.25, 1e-9, "penalty");
        eq(back.dueDay, 77, "dueDay");
        eq(back.borrowDay, 70, "borrowDay");
        eq(back.cooldownEndDay, 80, "cooldownEndDay");
        ok(back.liquidating, "liquidating");
        eq(back.product, LoanData.PRODUCT_MARGIN, "product");
        eq(back.mode, LoanSchedule.MODE_ANNUITY, "mode");
        eq(back.installments, 5, "installments");
        eq(back.principal, 1234, "principal");
        eq(back.paidPrincipal, 100, "paidPrincipal");
        eq(back.paidInterest, 20, "paidInterest");
        eq(back.paidPenalty, 5, "paidPenalty");
        eq(back.nextInst, 3, "nextInst");
        eq(back.accruedDay, 75, "accruedDay");
        eq(back.overdueStrikes, 2, "overdueStrikes");
        eq(back.limitStrikes, 1, "limitStrikes");
        eq(back.limitStrikeDay, 74, "limitStrikeDay");
        eq(back.onTimeRepays, 9, "onTimeRepays");
        eq(back.overdueTotal, 4, "overdueTotal");
        eq(back.bankruptcies, 1, "bankruptcies");
        eq(back.lastSettledDay, 60, "lastSettledDay");
        eq(back.loanFunds, 500, "loanFunds");
        eq(back.clawbackDebt, 42, "clawbackDebt");
        eq(back.p2pListingId, 8, "p2pListingId");
        eq(back.p2pDelivered, 11, "p2pDelivered");
        ok(lender.equals(back.p2pLender), "p2pLender");
        // 负债同源定版：payoff = 未还本金 + min(计划利息 − 已还利息 + 罚息, 本金×(倍数−1))。
        // 本金 1234、锁定利率 0.001、期限 7 天 → 计划利息 floor(1234×0.001×7)=8；
        // paidInterest=20 已覆盖计划利息 → 未结利息 0；罚息 ceil(3.25)=4 → 应还 1234 + 4 = 1238。
        eq(back.payoff(), 1238, "payoff = 未还本金 + 未结利息(0) + 罚息(4)");
        eq(back.interestDue(), 0, "interestDue = max(0, 计划利息 8 − 已还利息 20)");
        ok(back.payoff() > 0, "payoff 为正");
        eq(back.termDays(), 7, "termDays 读借款时锁定的期限");
        near(back.dailyRate(), 0.001, 1e-12, "日利率读锁定值（不受改配置影响）");
        ok(back.dailyRate() < 1.0, "单位回归守卫：日利率必须是**小数**（0.001），不是百分数");
        ok(LoanRules.dailyRate() < 1.0, "单位回归守卫：配置口径同样是小数日利率");
        near(back.capRate(), 1.0, 1e-12, "利息上限倍数 2.0 → 上限为本金的 100%");
        ok(!back.interestCapped(), "账面利息未达上限");
        eq(back.daysLeft(70), 7, "剩余天数");
        eq(back.daysLeft(100), -23, "逾期天数为负（调用方按 0 钳）");
        ok(owesAnythingCheck(back), "欠款判定（有贷款即欠款）");
        eq(back.schedule().size(), back.installments, "等额本息按计划参数重建（5 期）");
        back.mode = LoanSchedule.MODE_BALLOON;
        // 定版：一次性还本付息**允许多期排期**（前 n−1 期扣 0，末期一次结清本息）。
        java.util.List<LoanSchedule.Row> balloon = back.schedule();
        eq(balloon.size(), 5, "同一条贷款换成一次性还本付息 → 仍按 5 期排期");
        for (int i = 0; i < 4; i++) {
            eq(balloon.get(i).total(), 0, "一次性第 " + (i + 1) + " 期扣款为 0");
        }
        eq(balloon.get(4).total(), 1234 + 8, "末期一次结清本息（本金 + 计划利息 8）");
        back.mode = LoanSchedule.MODE_ANNUITY;
        eq(LoanSchedule.totalPrincipal(back.schedule()), back.basePrincipal(), "计划表本金=本金快照");
        // 计划表是“展示与分期口径”：每期金额向下取整（对借款人有利），所以合计利息只会**少于**
        // 单利总额（1234×0.001×7 ≈ 8.6），永远不会低于本金本身；真正扣款按逐日计提的账面利息走。
        long plan = LoanSchedule.totalPayoff(back.schedule());
        long simple = 1234 + 9;
        ok(plan >= 1234, "计划表合计不低于本金（不会因为取整少收本金）: " + plan);
        ok(plan <= simple, "计划表合计不超过本金+单利（取整只向下，不凭空加息）: " + plan);
        eq(LoanSchedule.totalPrincipal(back.schedule()), 1234, "各期本金相加恒等于借款本金");
        eq(back.basePrincipal(), 1234, "本金快照读得出");

        // 结清重置：债务清零、信用历史保留
        back.resetLoan();
        eq(back.amount, 0, "resetLoan 清本金");
        eq(back.product, LoanData.PRODUCT_CREDIT, "resetLoan 复位产品");
        eq(back.nextInst, 1, "resetLoan 复位期号");
        ok(!back.liquidating, "resetLoan 复位清算标记");
        eq(back.onTimeRepays, 9, "resetLoan 保留按时结清次数");
        eq(back.overdueTotal, 4, "resetLoan 保留逾期总数");
        eq(back.bankruptcies, 1, "resetLoan 保留破产次数");
        eq(back.clawbackDebt, 42, "resetLoan 保留未免除欠款（不能被结清抹掉）");
        ok(back.p2pLender == null, "resetLoan 清空出借人");

        // 产品越界钳位
        eq(LoanData.clampProduct(99), LoanData.PRODUCT_CREDIT, "非法产品编号回落默认");
        eq(LoanData.clampProduct(-1), LoanData.PRODUCT_CREDIT, "负产品编号回落默认");
    }

    private static boolean owesAnythingCheck(LoanData l) {
        return l.owesAnything() && !new LoanData().owesAnything();
    }

    private static void loanDataLegacyArchive() {
        System.out.println("[7] 旧存档（v3 及更早，没有 v2 字段）读入");
        CompoundTag t = new CompoundTag();
        t.putLong("Amount", 300);
        t.putDouble("Interest", 1.0);
        t.putLong("DueDay", 12);
        t.putLong("BorrowDay", 7);
        t.putLong("CooldownEndDay", 0);
        t.putBoolean("Liquidating", false);
        LoanData old = LoanData.fromTag(t);
        eq(old.amount, 300, "老字段照常读出");
        eq(old.principal, 300, "缺本金快照时用在贷余额补上");
        eq(old.product, LoanData.PRODUCT_CREDIT, "缺产品默认信用贷");
        eq(old.mode, LoanSchedule.MODE_BALLOON, "缺还款方式默认到期一次");
        eq(old.installments, 1, "缺分期默认 1 期");
        near(old.penalty, 0, 1e-12, "缺罚息默认 0");
        eq(old.overdueStrikes, 0, "缺逾期连击默认 0");
        eq(old.onTimeRepays, 0, "缺信用记录默认 0");
        eq(old.loanFunds, 0, "缺资金袋默认 0");
        eq(old.clawbackDebt, 0, "缺未免除欠款默认 0");
        ok(old.p2pLender == null, "缺出借人默认 null");
        eq(old.accruedDay, Long.MIN_VALUE, "缺计提日用哨兵值（首日记息一天）");
        ok(old.hasLoan(), "旧存档读出来仍在贷");
        eq(old.termDays(), 5, "缺锁定时期限回退到当前配置（5 天）");
        ok(old.dailyRate() > 0 && old.dailyRate() < 1.0, "缺锁定利率时回退值也是小数口径");
        eq(old.schedule().size(), 1, "旧存档计划表 1 期");
        ok(old.payoff() > old.amount, "旧存档应还含利息");

        // 空标签（全新玩家）
        LoanData blank = LoanData.fromTag(new CompoundTag());
        ok(!blank.hasLoan(), "空档案 = 无贷款");
        eq(blank.payoff(), 0, "空档案应还为 0");
        eq(blank.schedule().size(), 0, "空档案无计划行");
    }

    private static void listingRoundTrip() {
        System.out.println("[8] P2P 挂单存档与 TTL");
        P2PListing p = new P2PListing();
        UUID who = UUID.randomUUID();
        p.id = 17;
        p.lender = who;
        p.coins = 5000;
        p.rate = 0.0035;
        p.termDays = 9;
        p.listedDay = 100;
        p.taken = false;

        P2PListing back = P2PListing.fromTag(p.toTag());
        eq(back.id, 17, "id");
        ok(who.equals(back.lender), "lender");
        eq(back.coins, 5000, "coins");
        near(back.rate, 0.0035, 1e-12, "rate");
        eq(back.termDays, 9, "termDays");
        eq(back.listedDay, 100, "listedDay");
        ok(!back.taken, "taken");
        ok(back.open(109), "挂单第 9 天仍可接（TTL 内）");
        ok(!back.open(100 + P2PListing.LISTING_TTL_DAYS), "超过 TTL 自动失效");
        eq(back.daysLeft(100), P2PListing.LISTING_TTL_DAYS, "剩余天数");
        eq(back.daysLeft(100 + P2PListing.LISTING_TTL_DAYS + 50), 0, "过期后剩余天数不为负");
        back.taken = true;
        ok(!back.open(101), "已成交的挂单不再可接");

        P2PListing empty = P2PListing.fromTag(new CompoundTag());
        eq(empty.coins, 0, "脏标签回落 0");
        ok(empty.lender == null, "脏标签 lender=null（调用方会丢弃）");
    }

    // ================================================================ 白盒自查

    /**
     * 缺陷 F1：一次性还本付息允许多期排期时，前 n−1 期应付恒为 0，
     * 这些「空期」绝不能被当成逾期基准（否则 25 个交易日的贷款第 6 天就被判逾期）。
     * 断言对象是生产判据本体：LoanSchedule.firstOpenDueDay / openIndex / firstChargedIndex。
     */
    private static void lumpSumMultiPeriod() {
        System.out.println("[9] 多期一次性还本付息：空期不作逾期基准（缺陷 F1）");
        long borrowDay = 100;
        java.util.List<LoanSchedule.Row> balloon =
                LoanSchedule.build(borrowDay, 1200, 0.005, 25, 5, LoanSchedule.MODE_BALLOON);
        eq(balloon.size(), 5, "5 期计划表行数");
        eq(balloon.get(0).dueDay, 105, "第 1 期到期日");
        eq(balloon.get(4).dueDay, 125, "第 5 期到期日 = 合同到期日");
        for (int i = 0; i < 4; i++) {
            eq(balloon.get(i).total(), 0, "第 " + (i + 1) + " 期应付（空期）");
        }
        eq(balloon.get(4).principal, 1200, "末期一次性还本金");
        eq(balloon.get(4).interest, 150, "末期一次性收全期利息 1200×0.5%×25");
        eq(LoanSchedule.firstChargedIndex(balloon), 5, "首笔有金额的期号 = 5");
        eq(LoanSchedule.openIndex(balloon, 0), 5, "落账时未结期号推到 5（不停在空期 1）");
        eq(LoanSchedule.firstOpenDueDay(balloon, 1, 125), 125, "脏 nextInst=1 时逾期基准仍是第 25 天");
        eq(LoanSchedule.firstOpenDueDay(balloon, 5, 125), 125, "正常 nextInst=5 时逾期基准 = 第 25 天");
        // 旧判据（原 overdueDays 的循环条件，去掉 total()>0）逐行复刻，只为量化差异
        long legacyRef = 125;
        for (LoanSchedule.Row r : balloon) {
            if (r.index >= 1) {
                legacyRef = r.dueDay;
                break;
            }
        }
        eq(legacyRef, 105, "旧判据基准 = 第 5 期那天（比真实到期早 20 个交易日）");
        eq(LoanSchedule.firstOpenDueDay(balloon, 1, 125) - legacyRef, 20, "修复后基准后移 20 天");

        // 其余三种还款方式：首期就有金额，基准不变（证明修复没有把正常分期也推迟）
        int[] modes = {LoanSchedule.MODE_INTEREST_FIRST, LoanSchedule.MODE_ANNUITY, LoanSchedule.MODE_LINEAR};
        String[] names = {"先息后本", "等额本息", "等额本金"};
        for (int i = 0; i < modes.length; i++) {
            java.util.List<LoanSchedule.Row> rows =
                    LoanSchedule.build(borrowDay, 1200, 0.005, 25, 5, modes[i]);
            ok(rows.get(0).total() > 0, names[i] + " 首期就有应付金额");
            eq(LoanSchedule.firstOpenDueDay(rows, 1, 125), 105, names[i] + " 逾期基准 = 首期到期日");
            eq(LoanSchedule.openIndex(rows, 0), 1, names[i] + " 落账期号仍为 1");
        }

        // 空表 / 全部结清时回落调用方给的到期日（不会返回 0 造成"今天就逾期"）
        eq(LoanSchedule.firstOpenDueDay(java.util.Collections.emptyList(), 1, 125), 125, "空表回落合同到期日");
        eq(LoanSchedule.firstOpenDueDay(balloon, 6, 125), 125, "期号越界回落合同到期日");

        // 旧存档（legacy 24 期）一次性还本付息：仍然只认最后一期
        java.util.List<LoanSchedule.Row> legacy24 =
                LoanSchedule.build(0, 1000, 0.0035, 24, 24, LoanSchedule.MODE_BALLOON);
        eq(legacy24.size(), 24, "旧存档 24 期保留");
        eq(LoanSchedule.firstChargedIndex(legacy24), 24, "旧存档空期同样跳过");
        eq(LoanSchedule.firstOpenDueDay(legacy24, 1, 24), 24, "旧存档逾期基准 = 第 24 天");
    }

    /**
     * 缺陷 F2：强制清算 / 破产里每一笔收缴必须当场落账。
     * 这里用真实的 LoanRules.splitPayment + LoanData.applySplit/advanceInstallment/payoff，
     * 证明「扣 400 → 账面只剩 650」，重试时不会把同一笔钱再扣一次。
     */
    private static void seizedValueBooking() {
        System.out.println("[10] 清算收缴当场落账（缺陷 F2：不重复扣款、不重复记负债）");
        LoanData loan = new LoanData();
        loan.borrowDay = 100;
        loan.dueDay = 105;
        loan.amount = 1000;
        loan.principal = 1000;
        loan.lockedDailyRate = 0.01;
        loan.lockedTermDays = 5;
        loan.lockedCapMultiplier = 2.0;
        loan.installments = 1;
        loan.mode = LoanSchedule.MODE_BALLOON;
        loan.advanceInstallment();
        eq(loan.payoff(), 1050, "初始应还 = 本金 1000 + 计划利息 50");

        // 第 1 轮：只有 400 矿币 → 罚息 0 → 利息 50 → 本金 350
        long[] s1 = LoanRules.splitPayment(400, loan.penaltyDue(), loan.interestDue(), loan.amount, true);
        eq(s1[0], 0, "第 1 轮抵扣罚息");
        eq(s1[1], 50, "第 1 轮抵扣利息");
        eq(s1[2], 350, "第 1 轮抵扣本金");
        loan.applySplit(s1);
        loan.advanceInstallment();
        eq(loan.payoff(), 650, "落账后账面剩余 = 1050 − 400（重试不会重复扣这 400）");

        // 第 2 轮：钱包已被扣空 → 一分钱都落不上，账面保持不变
        long[] s2 = LoanRules.splitPayment(0, loan.penaltyDue(), loan.interestDue(), loan.amount, true);
        eq(s2[0] + s2[1] + s2[2], 0, "第 2 轮无钱可扣");
        loan.applySplit(s2);
        eq(loan.payoff(), 650, "第 2 轮账面未变（旧实现会再按 1050 扣一次）");

        // 第 3 轮：凑齐 650 → 结清
        long[] s3 = LoanRules.splitPayment(650, loan.penaltyDue(), loan.interestDue(), loan.amount, true);
        loan.applySplit(s3);
        loan.advanceInstallment();
        eq(loan.amount, 0, "剩余本金归零");
        eq(loan.payoff(), 0, "应还归零");
        ok(!loan.hasLoan(), "hasLoan=false");
        eq(loan.paidPrincipal + loan.paidInterest, 1050, "已还累计恰等于实扣总额");

        // 恶意 / 脏输入：负数拆分不得让账面倒退，巨额拆分不得回绕成负数
        LoanData d = new LoanData();
        d.borrowDay = 0;
        d.dueDay = 5;
        d.amount = 1000;
        d.principal = 1000;
        d.lockedDailyRate = 0.01;
        d.lockedTermDays = 5;
        d.lockedCapMultiplier = 2.0;
        d.installments = 1;
        long before = d.payoff();
        d.applySplit(new long[]{-500, -500, -500});
        eq(d.payoff(), before, "脏负数 split：账面一分不少");
        ok(d.paidPrincipal >= 0 && d.paidInterest >= 0 && d.paidPenalty >= 0, "已还累计不为负");
        d.applySplit(new long[]{Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE});
        eq(d.amount, 0, "巨额脏 split：本金钳到 0");
        ok(d.paidPrincipal > 0, "巨额脏 split：已还累计饱和而非回绕成负数");
    }

    /**
     * 缺陷 F3：强制平仓与玩家主动平仓必须同一套结算公式。
     * 断言对象是生产纯计算类 ShortSettlement（closeInternal 与 settleForced 都调它）。
     */
    private static void shortLiquidationParity() {
        System.out.println("[11] 强平与主动平仓同一口径（缺陷 F3）");
        long margin = 1500L;             // 开仓价 100 × 10 股 × 1.5 保证金比例
        double open = 100.0;
        double px = 130.0;               // 默认强平线 130%
        int qty = 10;
        eq(ShortSettlement.pnl(open, px, qty), -300, "价格涨 30% 的空头盈亏");
        eq(ShortSettlement.payoutFullClose(margin, open, px, qty), 1200, "强平应退 = 保证金 + 盈亏");
        eq(ShortSettlement.payoutWithFloor(margin, open, px, qty), 1200, "主动平仓全额退款（同一口径）");
        // 旧强平公式逐字复刻：refund = margin − ceil(现价 × 数量)
        long legacy = margin - (long) Math.ceil(px * qty);
        eq(legacy, 200, "旧强平只退 200");
        eq(1200 - legacy, (long) (open * qty), "旧公式系统性多收 = 开仓价 × 数量 = 1000");
        eq(ShortSettlement.buybackCost(px, qty), 1300, "日志口径的买回成本仍然算得出来");

        // 价格脏：绝不按 0 价买回，也绝不吞保证金
        ok(!ShortSettlement.validPrice(0.0), "0 价无效");
        ok(!ShortSettlement.validPrice(Double.NaN), "NaN 价无效");
        ok(!ShortSettlement.validPrice(Double.POSITIVE_INFINITY), "Inf 价无效");
        eq(ShortSettlement.pnl(open, 0.0, qty), 0, "脏价盈亏按 0");
        eq(ShortSettlement.payoutFullClose(margin, open, Double.NaN, qty), margin, "脏价只退保证金");
        eq(ShortSettlement.buybackCost(Double.NaN, qty), 0, "脏价买回成本 0");

        // 亏损超过保证金：强平要追缴差额，主动平仓最多亏光保证金
        eq(ShortSettlement.payoutFullClose(500, 100, 200, 10), -500, "爆仓差额 −500（须追缴）");
        eq(ShortSettlement.payoutWithFloor(500, 100, 200, 10), 0, "主动平仓不倒扣玩家");

        // 部分平仓按数量比例释放
        eq(ShortSettlement.releasedMargin(margin, 4, 10), 600, "平 4/10 股释放 60% 保证金");
        eq(ShortSettlement.releasedMargin(margin, 10, 10), 1500, "全额平仓释放全部保证金");
        eq(ShortSettlement.releasedMargin(margin, 5, 0), 0, "除数保护：平仓前数量为 0");
        eq(ShortSettlement.releasedMargin(margin, 0, 10), 0, "数量 0 不释放");
        eq(ShortSettlement.payoutPartial(margin, 4, 10, -300, true), 300, "withPnl 模式：释放额轧盈亏");
        eq(ShortSettlement.payoutPartial(margin, 4, 10, -300, false), 600, "quantity 模式：只释放保证金");
        // 溢出防护
        eq(ShortSettlement.payoutFullClose(Long.MAX_VALUE, 100, 1, 1), Long.MAX_VALUE, "巨额相加饱和不回绕");
    }

    /**
     * 缺陷 F5：限价单撮合时不能把"本单自己的逻辑预留"算作可用额扣减项。
     * 断言对象是生产判据本体 LimitOrder.lockedCoinsOf / lockedSharesOf。
     */
    private static void limitOrderSelfLock() {
        System.out.println("[12] 限价单成交排除本单自身锁定（缺陷 F5）");
        UUID who = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        LimitOrder self = new LimitOrder(1, who, "P", "FE", true, 100.0, 10, 1, 1);
        LimitOrder twin = new LimitOrder(2, who, "P", "FE", true, 50.0, 10, 2, 1);
        LimitOrder foreign = new LimitOrder(3, other, "Q", "FE", true, 70.0, 10, 3, 1);
        java.util.List<LimitOrder> orders = new java.util.ArrayList<>();
        orders.add(self);
        orders.add(twin);
        orders.add(foreign);
        eq(LimitOrder.lockedCoinsOf(orders, who, LimitOrder.NO_EXCLUSION), 1500, "本人两张买单锁定合计");
        eq(LimitOrder.lockedCoinsOf(orders, who, self.id), 500, "撮合本单时让出自己的 1000 预留");
        eq(LimitOrder.lockedCoinsOf(orders, who, twin.id), 1000, "另一张单的预留照旧扣减（不超支）");
        eq(LimitOrder.lockedCoinsOf(orders, other, foreign.id), 0, "排除别人的单不影响本人合计");
        // 真实场景：玩家「按全部可用额」提交限价买单。提交时服务端就要求
        // 可用额 ≥ 触发价 × 数量 ×(1+费率)，所以下面这个 1020 的余额是**足额**的。
        double rate = cn.oremarket.common.Fees
                .additive(0.02, 0.0);                     // 与 execute() 同一口径
        long balance = cn.oremarket.common.Fees
                .buyCost(100.0, 10, rate);               // = 1020
        long costAtFill = cn.oremarket.common.Fees
                .buyCost(100.0, 10, rate);               // 撮合市价 100 → 成本 1020
        eq(costAtFill, 1020, "撮合成本（真实 Fees.buyCost）");
        eq(self.lockedCoins(), 1000, "本单自身逻辑锁定 = ceil(触发价×数量)");
        java.util.List<LimitOrder> solo = java.util.Collections.singletonList(self);
        eq(balance - LimitOrder.lockedCoinsOf(solo, who, LimitOrder.NO_EXCLUSION), 20,
                "旧口径 availableCoins：把本单自己的 1000 也扣掉，只剩 20");
        ok(20 < costAtFill, "旧口径必然判「资金不足」→ 连错 limitOrderFailRetries 次后被静默撤销");
        ok(balance - LimitOrder.lockedCoinsOf(solo, who, self.id) >= costAtFill,
                "新口径可用额 1020 ≥ 成本 1020 → 正常成交");
        // 两张自己的买单：只许让出本单，另一张的预留照旧扣减（不会因此超支）
        eq(balance - LimitOrder.lockedCoinsOf(solo, who, twin.id), 20,
                "排除别的单不会顺手把自己的另一张单也放出来");
        long richBalance = 2040L;                        // 1020(self) + 500(twin 锁定) + 富余
        eq(richBalance - LimitOrder.lockedCoinsOf(orders, who, LimitOrder.NO_EXCLUSION), 540,
                "两张单时旧口径剩 540 < 1020（同样永远成交不了）");
        ok(richBalance - LimitOrder.lockedCoinsOf(orders, who, self.id) >= costAtFill,
                "新口径剩 1540 ≥ 1020 → 成交；成交后余额 1020，另一张单锁定 500，未超支");
        // 卖单：同样排除自身锁定
        LimitOrder sellSelf = new LimitOrder(4, who, "P", "FE", false, 200.0, 7, 4, 1);
        LimitOrder sellTwin = new LimitOrder(5, who, "P", "FE", false, 210.0, 3, 5, 1);
        java.util.List<LimitOrder> sells = new java.util.ArrayList<>();
        sells.add(sellSelf);
        sells.add(sellTwin);
        eq(LimitOrder.lockedSharesOf(sells, who, "FE", LimitOrder.NO_EXCLUSION), 10, "两张限价卖单锁定 10 股");
        eq(LimitOrder.lockedSharesOf(sells, who, "FE", sellSelf.id), 3, "撮合本单时让出自己的 7 股");
        eq(LimitOrder.lockedSharesOf(sells, other, "FE", LimitOrder.NO_EXCLUSION), 0, "他人持仓不被别人的单锁定");
        // 脏数据：触发价非正 / 数量为 0 → 不产生锁定
        LimitOrder dirty = new LimitOrder(6, who, "P", "FE", true, 0.0, 10, 6, 1);
        eq(dirty.lockedCoins(), 0, "脏触发价锁定 0");
        eq(LimitOrder.lockedCoinsOf(java.util.Collections.singletonList(dirty), who, LimitOrder.NO_EXCLUSION), 0,
                "脏单不占用可用额");
        eq(LimitOrder.lockedCoinsOf(orders, null, LimitOrder.NO_EXCLUSION), 0, "空 owner 防护");
        eq(LimitOrder.lockedCoinsOf(null, who, LimitOrder.NO_EXCLUSION), 0, "空集合防护");
        LimitOrder huge = new LimitOrder(7, who, "P", "FE", true, 1.0e18, Integer.MAX_VALUE, 7, 1);
        ok(huge.lockedCoins() == Long.MAX_VALUE, "巨额锁定饱和到 Long.MAX_VALUE");
        ok(LimitOrder.lockedCoinsOf(java.util.Collections.singletonList(huge), who, LimitOrder.NO_EXCLUSION)
                == Long.MAX_VALUE, "求和同样饱和，不回绕成小值");
    }
}
