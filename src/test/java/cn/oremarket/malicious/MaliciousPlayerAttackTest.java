package cn.oremarket.malicious;

import cn.oremarket.common.CustomEventParser;
import cn.oremarket.common.Fees;
import cn.oremarket.common.LoanRules;
import cn.oremarket.common.Num;
import cn.oremarket.common.ShortSettlement;
import cn.oremarket.market.LoanData;
import cn.oremarket.market.LoanService;
import cn.oremarket.market.LimitOrder;
import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.MarketSettings;
import cn.oremarket.market.PendingOrder;
import cn.oremarket.market.ShortPosition;
import cn.oremarket.net.packet.C2SExchangePacket;
import cn.oremarket.net.packet.C2SLimitPacket;
import cn.oremarket.net.packet.C2SLoanPacket;
import cn.oremarket.net.packet.C2SOrderPacket;
import cn.oremarket.net.packet.C2SRequestBookPacket;
import cn.oremarket.net.packet.C2SShortPacket;
import cn.oremarket.net.packet.C2STradePacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adversarial tests for forged packets, dirty persistence data, and economic boundary values. */
final class MaliciousPlayerAttackTest {
    private static final String STOCK_CODE = "COAL";
    private static final String SECOND_STOCK_CODE = "IRON";
    private static final long ONE_THOUSAND = 1_000L;
    private static final long NINE_HUNDRED = 900L;
    private static final long SIXTY = 60L;
    private static final long SHORT_MARGIN = 10L;
    private static final int TEST_ORDER_QUANTITY = 60;
    private static final long TEST_ORDER_RESERVATION = 602L;
    private static final long TEST_WITHDRAWABLE = 398L;
    private static final long DEFAULT_DAY = 1L;
    private static final double TEST_PRICE = 10.0;
    private static final double TEST_MARGIN_RATIO = 1.15;
    private static final double TEST_LIQUIDATION_RATIO = 0.43;
    private static final double TEST_INTEREST_RATE = 0.0;
    private static final int UNKNOWN_ACTION = 99;
    private static final long EXTREME_CURRENT_PRICE = 1_000_000_000_000L;
    private static final int EXTREME_SHARE_COUNT = 10_000_000;
    private static final int MAX_EVENT_FUZZ_BATCH = 20;

    @BeforeAll
    static void installDesignSettingsForLedgerPaths() throws Exception {
        Field server = MarketSettings.class.getDeclaredField("SERVER");
        server.setAccessible(true);
        server.set(null, MarketSettings.defaults());
    }

    @Test
    void invalidBookKindsAreRejectedBeforeTheCooldownMap() {
        assertFalse(C2SRequestBookPacket.isValidKind(-1));
        assertFalse(C2SRequestBookPacket.isValidKind(2));
        assertFalse(C2SRequestBookPacket.isValidKind(Integer.MAX_VALUE));
        assertTrue(C2SRequestBookPacket.isValidKind(C2SRequestBookPacket.KIND_ORDER_BOOK));
        assertTrue(C2SRequestBookPacket.isValidKind(C2SRequestBookPacket.KIND_LEADERBOARD));
    }

    @Test
    void forgedActionValuesAreNotKnownBusinessActions() {
        C2SExchangePacket exchange = new C2SExchangePacket(STOCK_CODE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        assertFalse(exchange.action >= C2SExchangePacket.DEPOSIT_ONE
                && exchange.action <= C2SExchangePacket.WITHDRAW_ALL);

        C2SLoanPacket loan = new C2SLoanPacket(Integer.MAX_VALUE, Long.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertTrue(loan.action > C2SLoanPacket.ACTION_REPAY_HANGOVER);
        assertEquals(Long.MIN_VALUE, loan.amount);

        assertEquals(C2SOrderPacket.ACTION_CANCEL, 1);
        assertEquals(C2SOrderPacket.ACTION_TAKE, 2);
        assertEquals(C2SShortPacket.ACTION_CLOSE, 1);
        assertEquals(C2SLimitPacket.ACTION_CANCEL, 1);
    }

    @Test
    void extremeMoneyAndShareValuesSaturateWithoutWrapping() {
        MarketSavedData data = new MarketSavedData();
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000a01");
        data.addBalance(player, Long.MAX_VALUE);
        data.addBalance(player, 1L);
        assertEquals(Long.MAX_VALUE, data.balance(player));

        data.addShares(player, cn.oremarket.market.Stock.COAL, Integer.MAX_VALUE);
        data.addFrozen(player, cn.oremarket.market.Stock.COAL, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, data.totalShares(player, cn.oremarket.market.Stock.COAL));
        assertTrue(data.totalShares(player, cn.oremarket.market.Stock.COAL) > 0);

        assertEquals(0L, Fees.buyCost(Double.POSITIVE_INFINITY, Integer.MAX_VALUE, 1.0));
        assertEquals(Long.MIN_VALUE, ShortSettlement.payoutFullClose(0L, 1.0,
                EXTREME_CURRENT_PRICE, EXTREME_SHARE_COUNT));
        assertEquals(0L, ShortSettlement.payoutWithFloor(0L, 1.0,
                EXTREME_CURRENT_PRICE, EXTREME_SHARE_COUNT));
    }

    @Test
    void dirtyOrderNBTCannotCreateAUsableNegativeReservation() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000a02");
        CompoundTag orderTag = orderTag(owner, STOCK_CODE, true);
        orderTag.putLong("Id", Long.MIN_VALUE);
        orderTag.putInt("Quantity", Integer.MIN_VALUE);
        PendingOrder pending = PendingOrder.fromTag(orderTag);

        CompoundTag limitTag = orderTag(owner, STOCK_CODE, true);
        limitTag.putLong("Id", Long.MIN_VALUE);
        limitTag.putInt("Quantity", Integer.MIN_VALUE);
        limitTag.putDouble("TriggerPrice", TEST_PRICE);
        LimitOrder limit = LimitOrder.fromTag(limitTag);

        assertEquals(0, pending.quantity);
        assertEquals(0, limit.quantity);
        assertEquals(0L, pending.lockedCoins());
        assertEquals(0L, limit.lockedCoins());
    }

    @Test
    void forgedShortIdsCannotAuthorizeAClose() {
        MarketSavedData data = new MarketSavedData();
        data.nextShortId = Long.MAX_VALUE;
        UUID firstOwner = UUID.fromString("00000000-0000-0000-0000-000000000a03");
        UUID secondOwner = UUID.fromString("00000000-0000-0000-0000-000000000a04");
        ShortPosition first = new ShortPosition(firstOwner, STOCK_CODE, 1, TEST_PRICE, SHORT_MARGIN,
                1.0, TEST_MARGIN_RATIO, TEST_LIQUIDATION_RATIO, TEST_INTEREST_RATE, DEFAULT_DAY);
        ShortPosition second = new ShortPosition(secondOwner, SECOND_STOCK_CODE, 1, TEST_PRICE, SHORT_MARGIN,
                1.0, TEST_MARGIN_RATIO, TEST_LIQUIDATION_RATIO, TEST_INTEREST_RATE, DEFAULT_DAY);

        assertTrue(data.openShort(first));
        assertTrue(data.openShort(second));
        assertTrue(ShortPosition.validCloseId(first.id));
        assertTrue(ShortPosition.validCloseId(second.id));
        assertTrue(first.id != second.id);
        assertFalse(ShortPosition.validCloseId(0L));
        assertFalse(ShortPosition.validCloseId(Long.MIN_VALUE));
    }

    @Test
    void loanFundsStayLockedAcrossAStockRoundTrip() {
        MarketSavedData data = new MarketSavedData();
        UUID borrower = UUID.fromString("00000000-0000-0000-0000-000000000a05");
        data.addBalanceNoPeak(borrower, ONE_THOUSAND);
        data.loan(borrower).loanFunds = ONE_THOUSAND;
        data.spendOwnCoinsIfEnough(borrower, ONE_THOUSAND);
        assertEquals(ONE_THOUSAND, cn.oremarket.market.LoanService.lockedLoanFunds(data, borrower));
        assertEquals(0L, cn.oremarket.market.LoanService.exchangeableCoins(data, borrower));

        // Borrowed coins buy stock first: the purse is consumed, but a later sale must restore it.
        data.addBalanceNoPeak(borrower, ONE_THOUSAND);
        data.loan(borrower).amount = ONE_THOUSAND;
        data.loan(borrower).loanFunds = ONE_THOUSAND;
        data.addBalance(borrower, -ONE_THOUSAND);
        assertEquals(0L, data.loan(borrower).loanFunds);
        data.creditSaleProceeds(borrower, NINE_HUNDRED);
        assertEquals(NINE_HUNDRED, cn.oremarket.market.LoanService.lockedLoanFunds(data, borrower));
        assertEquals(0L, cn.oremarket.market.LoanService.exchangeableCoins(data, borrower));
    }

    @Test
    void loanAwareCreditKeepsReturnedShortMarginLocked() {
        MarketSavedData data = new MarketSavedData();
        UUID borrower = UUID.fromString("00000000-0000-0000-0000-000000000a08");
        data.addBalanceNoPeak(borrower, ONE_THOUSAND);
        LoanData loan = data.loan(borrower);
        loan.amount = ONE_THOUSAND;
        loan.loanFunds = ONE_THOUSAND;

        // Opening a short spends the borrowed margin; closing it returns the same coins.
        data.addBalance(borrower, -ONE_THOUSAND);
        data.creditSaleProceeds(borrower, ONE_THOUSAND);

        assertEquals(ONE_THOUSAND, LoanService.lockedLoanFunds(data, borrower));
        assertEquals(0L, LoanService.exchangeableCoins(data, borrower));
    }

    @Test
    void withdrawableCoinsRespectRestingOrderReservations() {
        MarketSavedData data = new MarketSavedData();
        UUID trader = UUID.fromString("00000000-0000-0000-0000-000000000a09");
        data.addBalance(trader, ONE_THOUSAND);
        data.pendingOrders.add(new PendingOrder(1L, trader, "attacker", STOCK_CODE,
                true, TEST_ORDER_QUANTITY, TEST_PRICE, 1L, DEFAULT_DAY));

        assertEquals(ONE_THOUSAND, data.balance(trader));
        assertEquals(TEST_ORDER_RESERVATION, data.lockedCoins(trader));
        assertEquals(TEST_WITHDRAWABLE, LoanService.withdrawableCoins(data, trader));
    }

    @Test
    void deferredP2pCollectionRoutesTheCollectedAmountToTheLender() throws Exception {
        MarketSavedData data = new MarketSavedData();
        UUID lender = UUID.fromString("00000000-0000-0000-0000-000000000a06");
        UUID borrower = UUID.fromString("00000000-0000-0000-0000-000000000a07");
        LoanData loan = data.loan(borrower);
        loan.p2pLender = lender;

        Method route = cn.oremarket.market.LoanService.class.getDeclaredMethod(
                "routeMaturedP2PCollection", net.minecraft.server.MinecraftServer.class,
                MarketSavedData.class, UUID.class, LoanData.class, long.class);
        route.setAccessible(true);
        route.invoke(null, null, data, borrower, loan, SIXTY);

        assertEquals(SIXTY, data.balance(lender));
        assertEquals(SIXTY, loan.p2pDelivered);
        assertEquals(0L, data.balance(borrower));
    }

    @Test
    void forgedPacketFieldsRemainExtremeUntilServerValidation() throws Exception {
        Constructor<C2SLoanPacket> loanConstructor = C2SLoanPacket.class.getDeclaredConstructor(
                int.class, long.class, int.class, int.class, int.class, int.class, int.class, long.class);
        loanConstructor.setAccessible(true);
        C2SLoanPacket loan = loanConstructor.newInstance(Integer.MAX_VALUE, Long.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
                Integer.MIN_VALUE, Long.MIN_VALUE);
        assertEquals(Integer.MAX_VALUE, loan.action);
        assertEquals(Long.MIN_VALUE, loan.amount);
        assertEquals(Long.MIN_VALUE, loan.listingId);

        C2SOrderPacket order = new C2SOrderPacket(UNKNOWN_ACTION, STOCK_CODE, true,
                Integer.MIN_VALUE, Double.NaN, Long.MIN_VALUE);
        assertEquals(UNKNOWN_ACTION, order.action);
        assertEquals(Integer.MIN_VALUE, order.quantity);
        assertTrue(Double.isNaN(order.price));

        C2SShortPacket shortPacket = new C2SShortPacket(UNKNOWN_ACTION, SECOND_STOCK_CODE,
                Integer.MIN_VALUE, Long.MIN_VALUE);
        assertEquals(UNKNOWN_ACTION, shortPacket.action);
        assertEquals(Integer.MIN_VALUE, shortPacket.quantity);
        assertEquals(Integer.MIN_VALUE, new C2STradePacket(STOCK_CODE, Integer.MIN_VALUE, true).qty);
    }

    @Test
    void corruptedSavedDataIsIgnoredInsteadOfCrashingTheServer() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", Integer.MAX_VALUE);
        tag.putLong("TickCounter", Long.MIN_VALUE);
        tag.putLong("MarketTicks", Long.MIN_VALUE);
        tag.putLong("TradeDay", Long.MIN_VALUE);
        tag.putLong("P2PNextId", Long.MIN_VALUE);
        tag.putLong("NextShortId", Long.MIN_VALUE);

        ListTag balances = new ListTag();
        CompoundTag brokenBalance = new CompoundTag();
        brokenBalance.putString("Id", "not-a-uuid");
        brokenBalance.putLong("Amount", Long.MAX_VALUE);
        balances.add(brokenBalance);
        tag.put("Balances", balances);

        ListTag orders = new ListTag();
        CompoundTag brokenOrder = new CompoundTag();
        brokenOrder.putString("Owner", "still-not-a-uuid");
        brokenOrder.putInt("Quantity", Integer.MIN_VALUE);
        orders.add(brokenOrder);
        tag.put("PendingOrders", orders);

        MarketSavedData loaded = assertDoesNotThrow(() -> MarketSavedData.load(tag));
        assertTrue(loaded.pendingOrders.isEmpty());
        assertTrue(loaded.p2pListings.isEmpty());
        assertTrue(loaded.loans.isEmpty());
    }

    @Test
    void customEventConfigFuzzingIsBoundedAndRejectsExtremeValues() {
        Set<String> codes = Set.of(STOCK_CODE);
        String base = "FUZZ|rare|COAL|%s|60|desc";
        List<String> lines = new ArrayList<>();
        lines.add("x");
        lines.add("|||||||");
        lines.add(String.format(base, "price:NaN"));
        lines.add(String.format(base, "price:Infinity"));
        lines.add(String.format(base, "price:1e308"));
        lines.add(String.format(base, "volatility:-Infinity"));
        lines.add(String.format(base, "fee:999999999"));
        lines.add(String.format(base, "tradeImpact:0"));
        lines.add("FUZZ2|rare|UNKNOWN|price:1.2|60|desc");
        lines.add("FUZZ3|rare|COAL|price:1.2|999999999|desc");
        for (int eventIndex = 0; eventIndex < CustomEventParser.MAX_CUSTOM_EVENTS + MAX_EVENT_FUZZ_BATCH; eventIndex++) {
            lines.add("EV" + eventIndex + "|rare|COAL|price:1.2|60|desc");
        }

        List<CustomEventParser.Result> results = CustomEventParser.parseAll(lines, codes, Set.of());
        long accepted = results.stream().filter(result -> result.status == CustomEventParser.Status.OK).count();
        assertTrue(accepted <= CustomEventParser.MAX_CUSTOM_EVENTS);
        assertTrue(results.stream().anyMatch(result -> result.status == CustomEventParser.Status.BAD_EFFECT));
        assertTrue(results.stream().anyMatch(result -> result.status == CustomEventParser.Status.BAD_TARGET));
        assertTrue(results.stream().anyMatch(result -> result.status == CustomEventParser.Status.BAD_DURATION));
        assertTrue(results.stream().anyMatch(result -> result.status == CustomEventParser.Status.CAP_REACHED));
    }

    @Test
    void numericRenderingSurvivesMaliciousExtremes() {
        assertFalse(Num.unit(Double.NaN, true, 1).isEmpty());
        assertFalse(Num.unit(Double.POSITIVE_INFINITY, false, 2).isEmpty());
        assertFalse(Num.signed(Double.NEGATIVE_INFINITY, 2).isEmpty());
        assertEquals(Long.MAX_VALUE, LoanRules.saturatingAdd(Long.MAX_VALUE, 1L));
    }

    private static CompoundTag orderTag(UUID owner, String code, boolean buy) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Owner", owner.toString());
        tag.putString("Code", code);
        tag.putBoolean("Buy", buy);
        tag.putInt("Quantity", 1);
        tag.putDouble("Price", TEST_PRICE);
        tag.putLong("CreatedSequence", 1L);
        tag.putLong("CreatedDay", DEFAULT_DAY);
        return tag;
    }
}
