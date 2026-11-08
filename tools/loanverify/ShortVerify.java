package cn.oremarket.market;

import cn.oremarket.common.DebugCalculator;
import cn.oremarket.common.ShortPoolCalculator;
import cn.oremarket.common.ShortSettlement;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * （锁定保证金 / 做空池 / 部分平仓 / 沙盒算术）离线白盒验证——纯 JDK，秒级。
 *
 * <p>编译的是 src/main 里的<b>真实源码</b>（ShortPosition / ShortPoolCalculator /
 * ShortSettlement / DebugCalculator / LoanSchedule），只对 CompoundTag 与 MarketSettings
 * 用最小替身。这里跑过的每一条断言都对应模组里真正执行的算术与存档格式。
 */
public final class ShortVerify {
    private static int checks;
    private static int failed;
    private static final UUID P = new UUID(1L, 2L);

    private static void ok(boolean cond, String what) {
        checks++;
        if (!cond) {
            failed++;
            System.out.println("  FAIL  " + what);
        } else {
            System.out.println("  ok    " + what);
        }
    }

    private static void eq(long a, long b, String what) {
        ok(a == b, what + " (期望 " + b + "，实际 " + a + ")");
    }

    public static void main(String[] args) {
        System.out.println("== A. lockedMargin 冻结不变量（需求 4 验收场景） ==");
        lockedInvariant();
        System.out.println("== B. 部分平仓 / 末期兜底 分毫不差（补丁2） ==");
        partialAndFinal();
        System.out.println("== C. 3 股平 50% → 1 股剩 2 股（用户例子） ==");
        threeShareHalf();
        System.out.println("== D. 池子上限纯股数公式与钳制（需求 3） ==");
        poolFormula();
        System.out.println("== E. 每日回血 floor(max*10%) 钳顶 ==");
        refill();
        System.out.println("== F. 一键做空双重上限 + 取整安全（需求 5 / 补丁1） ==");
        oneClick();
        System.out.println("== G. 老存档 NBT 缺键兜底（防 NPE） ==");
        legacyBackfill();
        System.out.println("== H. 恶意 / 脏值矩阵 ==");
        malicious();
        System.out.println("== I. 沙盒指令算术（testshort / testloan 恒等式） ==");
        sandbox();

        System.out.println();
        System.out.println(failed == 0
                ? "[SHORT VERIFY] 全部通过：" + checks + " 条断言，0 失败"
                : "[SHORT VERIFY] 失败 " + failed + " / " + checks + " 条断言！");
        if (failed != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- A

    private static void lockedInvariant() {
        // 开仓价 10 × 100 股 × 1.5 = 1500 保证金 + 15 税费 → 锁定 1515
        ShortPosition p = new ShortPosition(P, "COAL", 100, 10.0, 1500L, 15.0,
                1.5, 1.3, 1.0, 0.03, 1L);
        eq(p.margin, 1515L, "抵押物池 = 保证金 + 税费");
        eq((long) p.lockedMargin, 1515L, "开仓瞬间 lockedMargin = 1515");
        eq(p.originalQuantity, 100L, "分母 originalQuantity = 开仓股数");
        CompoundTag t = p.toTag();
        // 现价翻倍：仓位对象重存档再取出（模拟跨天 / 重启），lockedMargin 不许动
        p.floatingPnl(20.0);                       // 现价翻倍只影响浮盈亏展示
        ShortPosition x1 = ShortPosition.fromTag(t, P);
        eq((long) x1.lockedMargin, 1515L, "现价翻倍后 lockedMargin 仍 1515");
        x1.floatingPnl(5.0);                       // 现价腰斩
        ShortPosition x2 = ShortPosition.fromTag(x1.toTag(), P);
        eq((long) x2.lockedMargin, 1515L, "现价腰斩后 lockedMargin 仍 1515");
        // 日息只侵蚀可退余额，不碰冻结锚点
        x2.chargeInterest(45L);
        eq(x2.margin, 1470L, "日息 45 后池余额 1470");
        eq((long) x2.lockedMargin, 1515L, "日息后 lockedMargin 依然 1515（UI 列读的它）");
    }

    // ---------------------------------------------------------------- B

    private static void partialAndFinal() {
        ShortPosition p = new ShortPosition(P, "GOLD", 100, 10.0, 1500L, 15.0,
                1.5, 1.3, 1.0, 0.03, 1L);
        p.chargeInterest(15L);                     // 池余额 1500，锚点仍 1515
        long r1 = ShortSettlement.releasedLocked(p.lockedMargin, p.margin, 50, p.originalQuantity);
        eq(r1, 757L, "平 50 股退 floor(1515×50÷100)=757");
        p.consumeMargin(r1);
        p.releaseLocked(r1);
        long r2 = ShortSettlement.releasedLocked(p.lockedMargin, p.margin, 30, p.originalQuantity);
        eq(r2, 454L, "再平 30 股退 floor(1515×30÷100)=454");
        p.consumeMargin(r2);
        p.releaseLocked(r2);
        // 末期兜底：remaining==0 → 一次退清当场余额（吸收历次取整零头）
        long r3 = p.margin;                        // closeInternal full 分支就是这个值
        eq(r3, 289L, "末期退款 = 当场余额 1500−757−454 = 289（零头全归末期）");
        p.consumeMargin(r3);
        eq(p.margin, 0L, "末期后池清空");
        eq(r1 + r2 + r3 + 15L, 1515L, "恒等式 Σ退款 + Σ日息 == lockedMargin，分毫不差");
        // 取整零头绝不为负 / 绝不 999.99 形态：全部 long 相加闭合
        ok(Double.isFinite(p.lockedMargin) && p.lockedReleased == Math.rint(p.lockedReleased),
                "审计计数器保持整数值（无浮点泄漏）");
    }

    // ---------------------------------------------------------------- C

    private static void threeShareHalf() {
        // 3 股、现价 10：保证金 ceil(30×1.5)=45、税 ceil(30×0.03)=1 → 锁定 46
        ShortPosition p = new ShortPosition(P, "DIAMOND", 3, 10.0, 45L, 1.0,
                1.5, 1.3, 1.0, 0.03, 1L);
        eq((long) p.lockedMargin, 46L, "3 股开仓锁定 46");
        int half = Math.floorDiv(p.quantity, 2);   // 客户端「平一半」按钮同款取整
        eq(half, 1L, "3 股 ×50% → floor = 1 股（向下取整）");
        long released = ShortSettlement.releasedLocked(p.lockedMargin, p.margin, half, p.originalQuantity);
        eq(released, 15L, "退 floor(46×1÷3) = 15（整币，无 15.33）");
        p.consumeMargin(released);
        p.releaseLocked(released);
        p.quantity -= half;
        eq(p.quantity, 2L, "剩 2 股");
        // 再平到只剩 0：末期兜底退全部余额
        eq(p.margin, 31L, "末期可退 = 当场余额 31");
        eq(released + p.margin + 0L, 46L, "3 股生命周期 Σ退款 == lockedMargin");
    }

    // ---------------------------------------------------------------- D

    private static void poolFormula() {
        eq(ShortPoolCalculator.maxSharesFor(10.0, 50000, 100000, 100), 5000L, "基准价 10 → 5000 股");
        eq(ShortPoolCalculator.maxSharesFor(500.0, 50000, 100000, 100), 100L, "基准价 500 → 100 股");
        eq(ShortPoolCalculator.maxSharesFor(1000.0, 50000, 100000, 100), 100L, "基准价 1000 → 保底 100 股");
        eq(ShortPoolCalculator.maxSharesFor(0.1, 50000, 100000, 100), 100000L, "基准价 0.1 → 硬顶 100000");
        eq(ShortPoolCalculator.maxSharesFor(0.0, 50000, 100000, 100), 50000L, "基准价 0 → 按 1.0 防除零");
        eq(ShortPoolCalculator.maxSharesFor(Double.NaN, 50000, 100000, 100), 50000L, "NaN 基准价 → 按 1.0");
        eq(ShortPoolCalculator.maxSharesFor(-7.0, 50000, 100000, 100), 50000L, "负基准价 → 按 1.0");
        eq(ShortPoolCalculator.maxSharesFor(3.0, 50000, 100, 100000), 100L, "min>max 荒谬配置 → 自净化钳到 100");
        eq(ShortPoolCalculator.maxSharesFor(1.0 / 3.0, 50000, 100000, 100), 100000L, "超低价仍受硬顶");
    }

    // ---------------------------------------------------------------- E

    private static void refill() {
        eq(ShortPoolCalculator.refill(0, 1000), 100L, "max 1000 空池回 100");
        eq(ShortPoolCalculator.refill(995, 1000), 1000L, "接近满池钳顶不溢出");
        eq(ShortPoolCalculator.refill(0, 105), 10L, "max 105 → floor(10.5) = 10 股");
        eq(ShortPoolCalculator.refill(105, 105), 105L, "满池保持满");
        eq(ShortPoolCalculator.refill(-9, 100), 10L, "脏负剩余 → 拉回 0 再回血");
        eq(ShortPoolCalculator.refill(500, 0), 0L, "max 0（已下架）恒 0");
    }

    // ---------------------------------------------------------------- F

    private static void oneClick() {
        // 一键做空口径（玩家实测反馈）：基数是**全额可用余额**，不再是半仓。
        // 余额 1000、现价 10、比例 1.5、费税 3% → 成本 15.3/股 → floor(1000÷15.3)=65
        eq(ShortPoolCalculator.oneClickShares(1000, 10, 1.5, 0.03, 5000, 1_000_000), 65L,
                "1000 全额 ÷ 15.3 → 65 股（旧半仓口径只给 32，玩家报的就是这个）");
        eq(ShortPoolCalculator.oneClickShares(40000, 10, 0.5, 0.005, 6250, 1_000_000), 6250L,
                "余额够 + 池剩 6250 → 整个池子一次端走（用户例子：煤炭 6250）");
        eq(ShortPoolCalculator.oneClickShares(1000, 10, 1.5, 0.03, 20, 1_000_000), 20L,
                "池剩 20 → min(65,20) = 20");
        eq(ShortPoolCalculator.oneClickShares(1000, 10, 1.5, 0.03, 0, 1_000_000), 0L, "池空 → 0（本地拦截）");
        eq(ShortPoolCalculator.oneClickShares(0, 10, 1.5, 0.03, 5000, 1_000_000), 0L, "零余额 → 0");
        eq(ShortPoolCalculator.oneClickShares(Double.NaN, 10, 1.5, 0.03, 5000, 1_000_000), 0L, "NaN 余额 → 0");
        eq(ShortPoolCalculator.oneClickShares(1000, -3, 1.5, 0.03, 5000, 1_000_000), 0L, "脏价 → 0");
        eq(ShortPoolCalculator.oneClickShares(1000, 10, 1.5, 0.03, 5000, 10), 10L, "单笔上限 10 股钳制");
        // ceil 反超防御：price 1 ratio 0.9 fee 0.05 → 朴素 floor(5÷0.95)=5，
        // 但服务端 ceil 口径要 ceil(4.5)+ceil(0.25)=5+1=6 > 5 → 逐股退让到 4（ceil(3.6)+ceil(0.2)=4+1=5）
        eq(ShortPoolCalculator.oneClickShares(5, 1, 0.9, 0.05, 5000, 1_000_000), 4L,
                "ceil 取整反超余额 → 逐股退让，一键必然开仓成功");
        ok(ShortPoolCalculator.exactOpenCost(1, 4, 0.9, 0.05) <= 5 + 1e-6,
                "退让后的实扣成本必然 ≤ 可用余额");
    }

    // ---------------------------------------------------------------- G

    private static void legacyBackfill() {
        CompoundTag old = new CompoundTag();
        old.putString("Owner", P.toString());
        old.putString("Code", "IRON");
        old.putInt("Quantity", 40);
        old.putDouble("OpenPrice", 8.0);
        old.putLong("Margin", 480);
        old.putDouble("MarginRatio", 1.5);
        old.putDouble("LiquidationRatio", 1.3);
        old.putDouble("DailyInterestRate", 0.03);
        old.putLong("OpenDay", 9);
        // 老存档：没有 Id / LockedMargin / LockedReleased / OpenTax / OrigQty / LiquidationThreshold2
        ShortPosition p = ShortPosition.fromTag(old, P);
        eq(p.id, 0L, "老仓位缺 Id → 0（载入阶段统一补发）");
        eq((long) p.lockedMargin, 480L, "缺 LockedMargin → 以现存 margin 兜底 480");
        eq((long) p.lockedReleased, 0L, "缺 LockedReleased → 0");
        eq(p.originalQuantity, 40L, "缺 OrigQty → 以现有数量兜底");
        ok(p.liquidationThreshold2 == 1.00, "缺 Threshold2 → 当前口径兜底 1.00");
        // 部分退过的老仓：LockedReleased 键存在时不得超过锚点
        CompoundTag dirty = p.toTag();
        dirty.putDouble("LockedReleased", 99999.0);
        ShortPosition d = ShortPosition.fromTag(dirty, P);
        eq((long) d.lockedReleased, 480L, "脏 Released 钳回锚点上限");
        // 锚点被写成低于余额：抬回，保证退款不超实际抵押物
        CompoundTag low = p.toTag();
        low.putDouble("LockedMargin", 1.0);
        ShortPosition l = ShortPosition.fromTag(low, P);
        ok(l.lockedMargin >= (double) l.margin, "脏 LockedMargin 低于余额 → 抬回余额");
    }

    // ---------------------------------------------------------------- H

    private static void malicious() {
        // 负数量 / NaN 价格 / 无穷税费：构造器全部钳进合法域，绝不抛异常也绝不产 NaN
        ShortPosition p = new ShortPosition(P, "COAL", -5, Double.NaN, -100L,
                Double.POSITIVE_INFINITY, Double.NaN, -3.0, Double.NaN, -1.0, -9L);
        eq(p.quantity, 0L, "负股数 → 0");
        ok(p.openPrice == 0.0, "NaN 开仓价 → 0");
        eq(p.margin, 0L, "负保证金 → 0");
        ok(!Double.isFinite(p.lockedMargin) == false && p.lockedMargin == 0.0,
                "inf 税费 + 0 保证金 → 锁定 0（无 NaN / 无 inf）");
        ok(p.liquidationPrice() == 0.0, "脏仓位强平价不炸 NaN");
        // releasedLocked 的除零与负数域
        eq(ShortSettlement.releasedLocked(1000, 0, 1, 0), 0L, "分母 0 → 0");
        eq(ShortSettlement.releasedLocked(Double.NaN, 100, 1, 10), 0L, "NaN 锚点 → 0");
        eq(ShortSettlement.releasedLocked(1000, -5, 2, 10), 0L, "负余额上限 → 0");
        eq(ShortSettlement.releasedLocked(1000, 40, 10, 10), 40L, "伪“超量”平仓 → 直接全退余额封顶");
        // 池子钳顶：恶意多加回不能超过上限
        // （shortPoolReturn 的 min 逻辑等价物：refill(500,100) 域）
        eq(ShortPoolCalculator.refill(1500, 1000), 1000L, "脏剩余超上限 → 钳顶");
        // NaN 进余额防护：toTag→fromTag 往返后 lockedMargin 仍有限
        CompoundTag t = p.toTag();
        ShortPosition q = ShortPosition.fromTag(t, P);
        ok(Double.isFinite(q.lockedMargin) && q.lockedMargin >= 0, "脏仓位存档往返仍无 NaN");
        ok(q.lockedRemainingCoins() >= 0L, "可退余额永不为负");
    }

    // ---------------------------------------------------------------- I

    private static void sandbox() {
        DebugCalculator.SimResult s1 =
                DebugCalculator.simulateShortOpen(1000, 10, 1.5, 0.03, 5000, 1_000_000);
        ok(s1.passed, "testshort(1000/10) 自检通过");
        DebugCalculator.SimResult s2 =
                DebugCalculator.simulateShortOpen(10, 10, 1.5, 0.03, 5000, 1_000_000);
        ok(s2.passed, "testshort(10/10) 资金不足路径也安全");
        DebugCalculator.SimResult l1 = DebugCalculator.simulateLoanSplit(1000, 3);
        ok(l1.passed, "testloan 1000÷3 恒等式 Σ==本金");
        for (int n = 1; n <= 5; n++) {
            for (long principal : new long[]{1, 7, 333, 1000, 999_999}) {
                ok(DebugCalculator.simulateLoanSplit(principal, n).passed,
                        "simulateLoanSplit(" + principal + "," + n + ") 通过");
            }
        }
    }
}
