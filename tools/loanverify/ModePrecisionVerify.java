package cn.oremarket.common;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * （贷款四模式数学基准 + UI 精度统一 + I18n 正则越界防御 + 数字智能降级）
 * 离线白盒验证——纯 JDK，秒级。
 *
 * <p>编译的是 src/main 里的<b>真实源码</b>（LoanCalculator / LoanSchedule / Num），
 * 只对 RawText 用最小替身（返回 zh_cn.json 登记的单位码点）。这里跑过的每一条断言
 * 都对应模组里真正执行的算术与显示口径；控制台输出的 [校验] 行与游戏内
 * SanityCheck 启动自检同源同格式。工具不参与模组打包。</p>
 */
public final class ModePrecisionVerify {

    private static int checks;
    private static int failed;

    private static void ok(boolean cond, String what) {
        checks++;
        if (!cond) {
            failed++;
            System.out.println("  FAIL  " + what);
        } else {
            System.out.println("  ok    " + what);
        }
    }

    private static void eq(String want, String got, String what) {
        ok(want.equals(got), what + " (期望 " + want + "，实际 " + got + ")");
    }

    private static void eq(long want, long got, String what) {
        ok(want == got, what + " (期望 " + want + "，实际 " + got + ")");
    }

    // 基准参数：本金 1000、日利率 1%、3 期 × 每期 5 天（共 15 天）
    private static final long P = 1000L;
    private static final double RATE = 0.01;
    private static final int TERM = 15;
    private static final int N = 3;

    public static void main(String[] args) {
        loanBenchmark();
        loanRedLines();
        pricePrecision();
        unitLadder();
        scientificFallback();
        i18nRegexDefense();
        dirtyInputs();

        System.out.println(failed == 0
                ? "[modeprecision-verify] PASS: " + checks + " 条断言全部通过"
                : "[modeprecision-verify] FAIL: " + failed + "/" + checks + " 条断言未通过");
        if (failed != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 贷款四模式基准
    private static List<LoanSchedule.Row> rows(int mode) {
        return LoanCalculator.schedule(0L, P, RATE, TERM, N, mode);
    }

    private static long[] totals(List<LoanSchedule.Row> r) {
        long[] t = new long[r.size()];
        for (int i = 0; i < r.size(); i++) {
            t[i] = r.get(i).total();
        }
        return t;
    }

    private static void loanBenchmark() {
        System.out.println("== A. 贷款四模式基准（本金 1000 / 日利率 1% / 3 期 × 5 天） ==");
        long[][] want = {
                {0, 0, 1150},
                {50, 50, 1050},
                {383, 383, 384},
                {383, 366, 350},
        };
        String[] names = {"一次性还本付息", "先息后本", "等额本息", "等额本金"};
        for (int mode = 0; mode < LoanSchedule.MODE_COUNT; mode++) {
            List<LoanSchedule.Row> r = rows(mode);
            eq(N, r.size(), names[mode] + " 期数");
            long[] t = totals(r);
            ok(Arrays.equals(want[mode], t), names[mode] + " 逐期扣款 " + Arrays.toString(t)
                    + " 必须等于 " + Arrays.toString(want[mode]));
            long sumP = 0;
            for (LoanSchedule.Row row : r) {
                sumP += row.principal;
                ok(row.principal >= 0 && row.interest >= 0 && row.total() >= 0,
                        names[mode] + " 第" + row.index + "期 无负数");
            }
            eq(P, sumP, names[mode] + " 各期本金之和精确归零尾款");
            // 控制台 [校验] 证据行（与游戏内 SanityCheck 同格式）
            StringBuilder line = new StringBuilder("[校验] ");
            if (mode == 0) {
                line.append("贷款 ").append(P).append(" / ").append(N).append("期。");
            }
            line.append(names[mode]).append("：");
            for (int i = 0; i < t.length; i++) {
                if (i > 0) {
                    line.append(", ");
                }
                line.append(t[i]);
            }
            line.append("。");
            System.out.println(line);
        }
        eq(1150L, LoanCalculator.plannedPayoff(P, rows(0)), "总还款（一次性）= 本金+总利息 1150");
        eq(150L, LoanCalculator.plannedInterest(rows(0)), "总利息（一次性）= 150");
        eq(99L, LoanSchedule.totalInterest(rows(3)), "等额本金总利息 99（最小）");
    }

    private static void loanRedLines() {
        System.out.println("== B. 四条红线 ==");
        // 红线①：一次性还本付息前 n−1 期 0 元账单 → 结算/逾期判据必须跳过
        List<LoanSchedule.Row> balloon = LoanCalculator.schedule(100L, P, RATE, TERM, N,
                LoanSchedule.MODE_BALLOON);
        eq(0L, balloon.get(0).total(), "一次性 第1期账单 0 元");
        eq(0L, balloon.get(1).total(), "一次性 第2期账单 0 元");
        eq(balloon.get(2).dueDay, LoanSchedule.firstOpenDueDay(balloon, 1, -1L),
                "一次性 逾期基准跳过空期（落在第3期到期日）");
        eq(3, LoanSchedule.openIndex(balloon, 0), "一次性 未结期号直接落在第3期");
        eq(3, LoanSchedule.firstChargedIndex(balloon), "一次性 首笔应付期号 = 3");
        // 红线②：先息后本未到最后一期不得抵扣本金
        List<LoanSchedule.Row> ifp = LoanCalculator.schedule(100L, P, RATE, TERM, N,
                LoanSchedule.MODE_INTEREST_FIRST);
        eq(0L, ifp.get(0).principal, "先息后本 第1期计划本金 0");
        eq(0L, ifp.get(1).principal, "先息后本 第2期计划本金 0");
        eq(P, ifp.get(2).principal, "先息后本 末期一次性还本");
        // 服务端扣款拆分同判据：allowPrincipal=false 时多还的钱一分都不进本金
        long[] split = LoanRules.splitPayment(100L, 0L, 50L, P, false);
        eq(0L, split[2], "先息后本 未到末期点「还100」→ 抵扣本金 0（只吃当期利息 50）");
        eq(50L, split[1], "先息后本 未到末期点「还100」→ 利息 50");
        // 红线③：等额本息余数全部落在最后一期，Σ = 1150
        List<LoanSchedule.Row> an = LoanCalculator.schedule(100L, P, RATE, TERM, N,
                LoanSchedule.MODE_ANNUITY);
        eq(an.get(0).total(), an.get(1).total(), "等额本息 前两期总额相同");
        ok(an.get(2).total() >= an.get(0).total(), "等额本息 余数进末期");
        eq(1150L, LoanSchedule.totalPayoff(an), "等额本息 三期合计精确 1150");
        // 红线④：等额本金末期精确扣完，负债归零
        List<LoanSchedule.Row> lin = LoanCalculator.schedule(100L, P, RATE, TERM, N,
                LoanSchedule.MODE_LINEAR);
        eq(0L, LoanSchedule.remainingPrincipal(lin, lin.size()), "等额本金 末期后剩余本金 = 0");
        ok(lin.get(0).total() > lin.get(1).total() && lin.get(1).total() > lin.get(2).total(),
                "等额本金 总扣款逐期递减");
        eq(1099L, LoanSchedule.totalPayoff(lin), "等额本金 合计 1099");
    }

    // ---------------------------------------------------------------- UI 精度统一
    private static void pricePrecision() {
        System.out.println("== C. 价格显示统一两位小数 ==");
        eq("12.43", Num.fixed(12.43, 2), "现价 12.43");
        eq("12.40", Num.fixed(12.4, 2), "现价 12.4 → 12.40（列表不再打 12.4）");
        eq("13.00", Num.fixed(13.0, 2), "成本价 13 → 13.00");
        eq("-0.00", Num.fixed(-0.001, 2), "负零保号（与 String.format 同口径；价格列调用点已先判 >0）");
        eq("142.00", Num.fixed(141.999, 2), "四舍五入到分");
    }

    // ---------------------------------------------------------------- 单位阶梯
    private static void unitLadder() {
        System.out.println("== D. 智能单位进位（万/亿/兆/京 + K/M/B/T/Qa） ==");
        eq("1.0万", Num.unit(1.0e4, true, 1), "1e4 zh");
        eq("1.0亿", Num.unit(1.0e8, true, 1), "1e8 zh");
        eq("1.0兆", Num.unit(1.0e12, true, 1), "1e12 zh（旧「万亿」口径升级为兆）");
        eq("1.0京", Num.unit(1.0e16, true, 1), "1e16 zh");
        eq("1000.0兆", Num.unit(9.99999999e14, true, 1), "9999999.9亿 zh 缩写");
        eq("1.0Qa", Num.unit(1.0e15, false, 1), "1e15 en");
        eq("1.0T", Num.unit(1.0e12, false, 1), "1e12 en");
        eq("100.0M", Num.unit(1.0e8, false, 1), "1e8 en");
        eq("1.0B", Num.unit(1.0e9, false, 1), "1e9 en");
        // 进位不出现 1000.0M 这类破档显示
        eq("1.0B", Num.unit(9.9996e8, false, 1), "999.96M 进位成 1.0B");
    }

    private static void scientificFallback() {
        System.out.println("== E. 科学计数法降级 + 10 字符红线 ==");
        eq("1.00E18", Num.unit(1.0e18, false, 1), "1e18 en 超 Qa 档 → 科学计数法");
        eq("1.00E20", Num.unit(1.0e20, true, 1), "1e20 zh 超京档 → 科学计数法");
        String max = Num.unit(Double.MAX_VALUE, false, 1);
        ok(max.length() <= Num.MAX_NUMBER_LEN, "Double.MAX_VALUE 缩写 ≤10 字符：" + max);
        eq("9.99E18", Num.compact("9999999999999999999"), "纯数字串按字符串精确取位（截断不进位）");
        eq("12.43", Num.compact("12.43"), "常规价不动");
        eq("1234567890", Num.compact("1234567890"), "10 字符恰好不动");
        ok(Num.compact("9".repeat(300)).length() <= Num.MAX_NUMBER_LEN, "300 位 9 → ≤10 字符");
        eq("-9.99E18", Num.compact("-9999999999999999999"), "负号保留");
        ok(Num.compact("矿币 9999999999999999999").startsWith("矿币 "), "非数字前缀原样保留");
    }

    // ---------------------------------------------------------------- I18n 正则越界防御
    private static void i18nRegexDefense() {
        System.out.println("== F. I18n 正则越界防御（latest.log 崩溃现场复现） ==");
        String probe = "矿币 9999999.9亿";
        // ① 旧崩溃现场：单位是非捕获组 → 只有 1 个捕获组，group(2) 必须返回 null 而不是抛异常
        Matcher broken = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:亿)").matcher(probe);
        boolean found = broken.find();
        ok(found, "坏模式仍能匹配整段");
        String unit = null;
        boolean threw = false;
        try {
            unit = Num.safeGroup(broken, 2);
        } catch (IndexOutOfBoundsException crash) {
            threw = true;
        }
        ok(!threw && unit == null, "safeGroup 对缺失的第 2 捕获组返回 null，不抛异常");
        System.out.println("[校验] 输入 \"" + probe + "\"，正则匹配失败，已安全降级，"
                + "未抛出 IndexOutOfBoundsException。");
        // ② 直接调 group(2) 的旧写法确实会炸（证明防御判空是必要修复，不是摆设）
        boolean legacyThrows = false;
        try {
            broken.reset();
            if (broken.find()) {
                broken.group(2);
            }
        } catch (IndexOutOfBoundsException expected) {
            legacyThrows = true;
        }
        ok(legacyThrows, "旧写法 matcher.group(2) 复现 No group 2（修复必要性证据）");
        // ③ 生产路径：修复后的模式（单位 = 第 2 捕获组）两种语言都安全缩写
        String zh = Num.normalizeChineseUnitText(probe, true);
        String en = Num.normalizeChineseUnitText(probe, false);
        eq("矿币 1000.0兆", zh, "zh 缩写");
        eq("矿币 1.0Qa", en, "en 缩写");
        System.out.println("[校验] 输入 \"" + probe + "\"，正则匹配成功，安全缩写为 \""
                + zh + "\" / \"" + en + "\"，未抛出异常。");
        System.out.println("[校验] 输入 \"9999999999999999999\"，安全降级为 \"9.99E18\"，UI 未溢出。");
        // ④ 繁体 / 兆 / 京 单位与多片段混排
        eq("1.0亿", Num.normalizeChineseUnitText("1.0\u5104", true), "繁体億");
        eq("1.0兆", Num.normalizeChineseUnitText("10000.0亿", true), "亿片段自动升档到兆");
        eq("买 1.0万 卖 2.0万", Num.normalizeChineseUnitText("买 1.0万 卖 2.0万", true),
                "已达标片段不误伤");
    }

    // ---------------------------------------------------------------- 恶意输入轰炸
    private static void dirtyInputs() {
        System.out.println("== G. 恶意 / 脏输入轰炸（任何一条都不许抛） ==");
        String[] dirty = {null, "", " ", "亿", "-亿", "1e999999亿", "++1.2.3万", "矿币 0亿",
                "9".repeat(400), "-" + "8".repeat(60) + ".9兆", "NaN", "Infinity",
                "0.0.0.0亿", "99999999999999999999.999999999999999999999999亿"};
        for (String d : dirty) {
            try {
                String a = Num.normalizeChineseUnitText(d, false);
                String b = Num.normalizeChineseUnitText(d, true);
                String c = Num.compact(d);
                // 契约：null 进 null 出；非 null 进必非 null 出（调用方无需再判空指针炸点）
                ok((a == null) == (d == null) && (b == null) == (d == null),
                        "脏输入按契约返回: " + abbrev(d));
                ok(c == null || c.length() <= Math.max(Num.MAX_NUMBER_LEN + 32,
                        (d == null ? 0 : d.length())), "compact 不放大文本: " + abbrev(d));
            } catch (RuntimeException crash) {
                ok(false, "脏输入抛异常: " + abbrev(d) + " -> " + crash);
            }
        }
        // 贷款脏参数轰炸：NaN / 负数 / 超长参数 → 空表或钳制，绝不抛
        try {
            ok(LoanCalculator.schedule(0, -5, 0.01, 15, 3, 0).isEmpty(), "负本金 → 空表");
            ok(LoanCalculator.schedule(0, 1000, Double.NaN, 15, 3, 0).size() == 3, "NaN 利率钳 0");
            ok(LoanCalculator.schedule(0, 1000, 0.01, -9, -4, 99).size() == 1, "脏期限/期数/模式钳回");
            long sum = 0;
            for (LoanSchedule.Row r : LoanCalculator.schedule(0, Long.MAX_VALUE, 1e308, 25, 5, 2)) {
                sum += r.total();
                ok(r.total() >= 0, "极端参数单期非负");
            }
            ok(sum >= 0, "极端参数合计不溢出为负");
        } catch (RuntimeException crash) {
            ok(false, "贷款脏参数抛异常 -> " + crash);
        }
    }

    private static String abbrev(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 24 ? s : s.substring(0, 24) + "...";
    }
}
