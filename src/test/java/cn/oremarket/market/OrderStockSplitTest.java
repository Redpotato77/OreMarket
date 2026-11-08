package cn.oremarket.market;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderStockSplitTest {
    private static final long EXPECTED_POST_SPLIT_FEE_RESERVED_COINS = 41L;
    private static final int CANCEL_ORDER_QUANTITY = 1;
    private static final long CANCEL_PENDING_ORDER_ID = 4L;
    private static final double CANCEL_ORDER_PRICE = 4.0;

    @BeforeAll
    static void installDeterministicSettings() throws Exception {
        Field server = MarketSettings.class.getDeclaredField("SERVER");
        server.setAccessible(true);
        server.set(null, MarketSettings.defaults());
    }

    @Test
    void pendingOrderKeepsEconomicSizeAndLockedCoins() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000002");
        PendingOrder buy = new PendingOrder(1L, owner, "Player", "COAL", true, 10, 4.0, 1L, 1L);
        PendingOrder sell = new PendingOrder(2L, owner, "Player", "COAL", false, 10, 4.0, 2L, 1L);

        buy.applyStockSplit(2);
        sell.applyStockSplit(2);

        assertEquals(20, buy.quantity);
        assertEquals(2.0, buy.price, 0.0);
        assertEquals(EXPECTED_POST_SPLIT_FEE_RESERVED_COINS, buy.lockedCoins());
        assertEquals(20, sell.quantity);
        assertEquals(2.0, sell.price, 0.0);
    }

    @Test
    void cancelAllOrdersRemovesPendingAndLimitOrdersTogether() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000004");
        MarketSavedData data = new MarketSavedData();
        data.pendingOrders.add(new PendingOrder(CANCEL_PENDING_ORDER_ID, owner, "Player", "COAL", true,
                CANCEL_ORDER_QUANTITY, CANCEL_ORDER_PRICE, CANCEL_PENDING_ORDER_ID, 1L));
        data.limitOrders.add(new LimitOrder(5L, owner, "Player", "COAL", true,
                CANCEL_ORDER_PRICE, CANCEL_ORDER_QUANTITY, 5L, 1L));

        assertEquals(2, data.cancelAllOrders(null, owner));
        assertEquals(0, data.pendingOrders.size());
        assertEquals(0, data.limitOrders.size());
    }

    @Test
    void limitOrderKeepsTriggerEconomics() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000003");
        LimitOrder order = new LimitOrder(3L, owner, "Player", "COAL", true, 8.0, 5, 3L, 1L);

        order.applyStockSplit(2);

        assertEquals(10, order.quantity);
        assertEquals(4.0, order.triggerPrice, 0.0);
        assertEquals(EXPECTED_POST_SPLIT_FEE_RESERVED_COINS, order.lockedCoins());
    }
}
