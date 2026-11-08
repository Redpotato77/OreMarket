package cn.oremarket.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线验证（纯 JDK、编译 src/main 里的**真实源码**：Fees / Num / LoanSchedule / LoanCalculator）：
 *
 *  A. 成交价 12.41 买入 1 股的完整事故复现：
 *     旧口径 日志"总价 12.4 / 手续费 0.0"、界面"实扣 13 / 成本 13.00" 三处打架；
 *     新口径 Fees.buyLedger 必须给出 ["12.41","0.25","0.12","13"]，与结算逐列一致。
 *  B. 费用精确值永不整数化归零（0.2482 → "0.25"，不是 "0.0"）。
 *  C. 语言表四个语言的买卖模板占位符数量与 TradeService 下发参数个数一致（7 / 8）。
 *  D. 一次性还本付息多期排期：3 期 balloon 前 2 期计划还款恒为 0、末期一次扣本息，
 *     firstChargedIndex / openIndex / firstOpenDueDay 与落账推进口径一致。
 *
 * 工具不参与模组打包。运行：见文件尾部注释的 javac/java 命令。
 */
public final class FeeBalloonVerify {
    private static int checks;
    private static int failed;

    private static void ok(boolean cond, String what) {
        checks++;
        if (!cond) {
            failed++;
            System.out.println("  FAIL  " + what);
        } else {
            System.out.println("  PASS  " + what);
        }
    }

    private static void eqStr(String a, String b, String what) {
        ok(a != null && a.equals(b), what + " (期望 " + b + "，实际 " + a + ")");
    }

    private static void eq(long a, long b, String what) {
        ok(a == b, what + " (期望 " + b + "，实际 " + a + ")");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== A. 12.41 × 1 股事故复现（费 2% + 销毁税 1%） ==");
        accidentRepro();
        System.out.println("== B. 费用精确值与边界防护 ==");
        feeExactGuards();
        System.out.println("== C. 语言表模板占位符与下发参数一致性 ==");
        langTemplateParity();
        System.out.println("== D. 一次性还本付息多期排期 ==");
        balloonMultiInstallment();
        System.out.println();
        System.out.println(failed == 0
                ? "ALL PASS: " + checks + " assertions"
                : "FAILED: " + failed + " / " + checks + " assertions");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** A：结算与日志四列逐一对账（与 TradeService.trade 完全相同的调用序列）。 */
    private static void accidentRepro() {
        double price = 12.41;
        int qty = 1;
        double fee = 0.02;
        double tax = 0.01;
        double totalRate = Fees.combined(fee, tax);
        ok(Math.abs(totalRate - 0.0298) < 1e-12, "总费率 = 0.02+0.01-0.0002 = 0.0298，实际 " + totalRate);

        long cost = Fees.buyCost(price, qty, totalRate);
        eq(cost, 13L, "实扣 = ceil(12.41×1.0298) = ceil(12.7798) = 13（界面「花费 13 矿币」同源）");
        eqStr(Num.fixed((double) cost / qty, 2), "13.00", "成本价 = 实扣÷股数 = 13.00（界面「成本价 13.00」同源）");

        String[] led = Fees.buyLedger(price, qty, fee, tax);
        eqStr(led[0], "12.41", "日志小计列（旧版打成 12.4 的费前成交额，现在两位小数）");
        eqStr(led[1], "0.25", "日志手续费列（旧版 long 化显示 0.0 的 Bug 列）");
        eqStr(led[2], "0.12", "日志销毁税列（旧版日志里根本不存在的列）");
        eqStr(led[3], "13", "日志实扣列 == 结算 cost");
        ok(!led[1].equals("0.00") && !led[1].equals("0.0"), "手续费不再显示为 0");
        // 对账不变式：小计+费+税 ≥ 计费基数（差为费×税乘积项），实扣 = ceil(基数)
        double base = price * qty * (1.0 + totalRate);
        double shown = 12.41 + 0.2482 + 0.1241;
        ok(shown >= base - 1e-9, "展示三列之和 12.7823 ≥ 计费基数 " + base);
        ok(cost == (long) Math.ceil(base), "实扣恒等于 ceil(计费基数)");

        // 卖出对称口径
        long gain = Fees.sellGain(price, qty, totalRate);
        eq(gain, 12L, "实得 = floor(12.41×0.9702) = 12");
        String[] sl = Fees.sellLedger(price, qty, fee, tax);
        eqStr(sl[0], "12.41", "卖日志小计");
        eqStr(sl[1], "0.25", "卖日志手续费");
        eqStr(sl[2], "0.12", "卖日志销毁税");
        eqStr(sl[3], "12", "卖日志实得 == 结算 gain");

        // 批量交易：1441.3 × 100 股
        long bigCost = Fees.buyCost(1441.3, 100, totalRate);
        String[] bl = Fees.buyLedger(1441.3, 100, fee, tax);
        eqStr(bl[0], "144130.00", "大额小计");
        eqStr(bl[1], "2882.60", "大额手续费 144130×2%");
        eqStr(bl[2], "1441.30", "大额销毁税 144130×1%");
        eqStr(bl[3], String.valueOf(bigCost), "大额实扣列与结算同值");
        eq(bigCost, 148426L, "大额实扣 = ceil(144130×1.0298) = ceil(148425.074) = 148426");

        // 一致性扫描：任意价量下 ledger 结算列必须等于 buyCost/sellGain（同输入同公式）
        double[] prices = {0.01, 1.0, 12.41, 999.999, 1441.3};
        int[] qtys = {1, 7, 100, 99999};
        for (double p : prices) {
            for (int q : qtys) {
                String[] b = Fees.buyLedger(p, q, fee, tax);
                eq(Long.parseLong(b[3]), Fees.buyCost(p, q, totalRate),
                        "扫描 p=" + p + " q=" + q + " 买入结算列一致");
                String[] s = Fees.sellLedger(p, q, fee, tax);
                eq(Long.parseLong(s[3]), Fees.sellGain(p, q, totalRate),
                        "扫描 p=" + p + " q=" + q + " 卖出结算列一致");
            }
        }
    }

    /** B：非有限值 / 越界费率 / 零价量的防护，全部按 0 计且不产生 NaN 文案。 */
    private static void feeExactGuards() {
        eq(Fees.feeAmountExact(Double.NaN, 5, 0.02) == 0 ? 1 : 0, 1, "NaN 价格 → 手续费 0");
        eq(Fees.feeAmountExact(100, 1, Double.POSITIVE_INFINITY) == 0 ? 1 : 0, 1, "Inf 费率 → 手续费 0");
        eq(Fees.taxAmountExact(100, 1, -0.01) == 0 ? 1 : 0, 1, "负税率 → 税 0");
        eq(Fees.feeAmountExact(100, 1, 0.9) == 20 ? 1 : 0, 1, "费率钳到上限 20%：100×0.9 → 20");
        eqStr(Num.fixed(Fees.feeAmountExact(12.41, 0, 0.02), 2), "0.00", "0 股 → 费 0.00（qty 上游已拒，纯防御）");
        eqStr(Num.fixed(0.2482, 2), "0.25", "显示口径 0.2482 → 0.25");
        eqStr(Num.signed(-1.04, 1), "-1.0", "盈亏口径 -1.04 → -1.0");
        eqStr(Num.fixed(12.7798, 2), "12.78", "基数 12.7798 → 12.78");
    }

    /** C：直接读四个语言文件，核对买卖模板的位置占位符集合与参数个数。 */
    private static void langTemplateParity() throws Exception {
        Pattern tok = Pattern.compile("%(\\d+)\\$s");
        String[] langs = {"zh_cn", "zh_tw", "zh_hk", "en_us"};
        for (String lang : langs) {
            Path file = Path.of("src/main/resources/assets/oremarket/lang", lang + ".json");
            String json = Files.readString(file, StandardCharsets.UTF_8);
            String buy = extract(json, "oremarket.trade.log.buy");
            String sell = extract(json, "oremarket.trade.log.sell");
            ok(buy != null && sell != null, lang + " 模板存在");
            eq(placeholderCount(tok, buy), 7, lang + " 买入模板 7 个占位符（TradeService 下发 7 参）");
            eq(placeholderCount(tok, sell), 8, lang + " 卖出模板 8 个占位符（TradeService 下发 8 参）");
            ok(coversIndexes(tok, buy, 7), lang + " 买入占位符覆盖 %1$s..%7$s");
            ok(coversIndexes(tok, sell, 8), lang + " 卖出占位符覆盖 %1$s..%8$s");
        }
    }

    private static String extract(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\": \"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static int placeholderCount(Pattern tok, String tpl) {
        if (tpl == null) {
            return -1;
        }
        Matcher m = tok.matcher(tpl);
        int c = 0;
        while (m.find()) {
            c++;
        }
        return c;
    }

    private static boolean coversIndexes(Pattern tok, String tpl, int n) {
        if (tpl == null) {
            return false;
        }
        boolean[] seen = new boolean[n + 1];
        Matcher m = tok.matcher(tpl);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            if (idx < 1 || idx > n) {
                return false;
            }
            seen[idx] = true;
        }
        for (int i = 1; i <= n; i++) {
            if (!seen[i]) {
                return false;
            }
        }
        return true;
    }

    /** D：balloon 多期排期语义（服务端落账 / 客户端显示共用 LoanSchedule 真身）。 */
    private static void balloonMultiInstallment() {
        // 3169 本金、日利率 0.5%、3 期 × 5 天 = 15 个交易日
        List<LoanSchedule.Row> rows = LoanSchedule.build(100, 3169, 0.005, 15, 3, LoanSchedule.MODE_BALLOON);
        eq(rows.size(), 3, "balloon 3 期 → 计划表 3 行");
        eq(rows.get(0).total(), 0, "第 1 期计划还款 0");
        eq(rows.get(1).total(), 0, "第 2 期计划还款 0");
        eq(rows.get(2).principal, 3169, "末期一次性还本金 3169");
        eq(rows.get(2).interest, 237, "末期利息 = floor(3169×0.005×15) = floor(237.675) = 237");
        eq(LoanSchedule.firstChargedIndex(rows), 3, "首笔有金额的期号 = 3（落账时 nextInst 直接推到 3）");
        eq(LoanSchedule.openIndex(rows, 0), 3, "已还 0 时未结期号 = 3（空期天然跳过，不误判逾期）");
        eq(LoanSchedule.firstOpenDueDay(rows, 3, -1), 115, "首个真实到期日 = 100 + 15 = 115（第 6 天不再误判逾期）");
        eq(LoanSchedule.firstOpenDueDay(rows, 1, -1), 115, "nextInst=1 时逾期基准同样落在末期 115");
        eq(LoanCalculator.plannedInterest(rows), 237, "计划利息合计 = 237");
        eq(LoanCalculator.plannedPayoff(3169, rows), 3406, "计划总还款 = 3169 + 237 = 3406");
        eq(LoanSchedule.totalTermDays(3), 15, "3 期 = 15 个交易日");
        eq(LoanSchedule.totalTermDays(5), 25, "5 期 = 25 个交易日（期+ 上限）");
        eq(LoanSchedule.clampInstallments(0), 1, "脏值 0 → 1 期");
        eq(LoanSchedule.clampInstallments(99), 5, "脏值 99 → 5 期");
        eq(LoanSchedule.clampMode(77), LoanSchedule.MODE_BALLOON, "脏模式 → 一次性还本付息");

        // balloon 单期：与多期末期同公式，行为不变
        List<LoanSchedule.Row> one = LoanSchedule.build(100, 1000, 0.005, 5, 1, LoanSchedule.MODE_BALLOON);
        eq(one.size(), 1, "balloon 1 期 → 1 行");
        eq(one.get(0).total(), 1025, "单期本息 = 1000 + floor(1000×0.005×5) = 1025");
        eq(LoanSchedule.firstChargedIndex(one), 1, "单期首笔即有金额");

        // 5 期 balloon：前 4 期全 0，末期收全部
        List<LoanSchedule.Row> five = LoanSchedule.build(0, 1000, 0.005, 25, 5, LoanSchedule.MODE_BALLOON);
        eq(five.get(3).total(), 0, "5 期时第 4 期还款 0");
        eq(five.get(4).total(), 1125, "5 期末期 = 1000 + floor(1000×0.005×25) = 1125");
        eq(LoanSchedule.firstChargedIndex(five), 5, "5 期首笔有金额期号 = 5");

        // 其余三种模式在 3 期下每期都有金额（对照：期+ 放开只影响 balloon 的空期语义）
        List<LoanSchedule.Row> ann = LoanSchedule.build(100, 1000, 0.005, 15, 3, LoanSchedule.MODE_ANNUITY);
        ok(ann.get(0).total() > 0 && ann.get(1).total() > 0, "等额本息每期都有金额");
        eq(LoanSchedule.totalPrincipal(ann), 1000, "等额本息各期本金之和 = 总本金");
        eq(LoanSchedule.totalInterest(ann), 75, "等额本息利息合计 = 计划利息 floor(1000×0.005×15) = 75");
    }
}

/*
 * 运行（在工程根目录）：
 *   javac -encoding UTF-8 -d tools/loanverify/out3 \
 *     src/main/java/cn/oremarket/common/{Fees,Num,LoanSchedule,LoanCalculator}.java \
 *     tools/loanverify/FeeBalloonVerify.java
 *   java -cp tools/loanverify/out3 cn.oremarket.common.FeeBalloonVerify
 */
