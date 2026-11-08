package cn.oremarket.common;

import cn.oremarket.market.Stock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockStateVolumeTest {

    @Test
    void formingCandleShowsReadOnlyVirtualVolumeWithoutResettingEconomicVolume() {
        StockState state = StockState.empty(Stock.COAL);
        state.startLiveKline(Stock.COAL.basePrice());

        double projected = VirtualVolume.amount(
                Stock.COAL.code(), 1L, Stock.COAL.basePrice(), Stock.COAL.basePrice(), 2000.0);

        assertEquals(0.0, state.liveVolume(), 0.0,
                "a fresh candle without real fills must keep economic volume at zero");
        assertEquals(projected, state.displayVolume(), 0.0,
                "the UI display amount must use the deterministic virtual forecast for the current candle");
        assertEquals(0.0, state.liveKline()[MarketVolume.VOLUME_SLOT], 0.0,
                "the display forecast must never be written back into the forming candle's economic volume");

        state.addTradedVolume(7L);
        assertEquals(7.0, state.liveVolume(), 0.0,
                "real traded volume must stay independent");
        assertEquals(7.0 + projected, state.displayVolume(), 0.0,
                "the display amount must be real fills plus the read-only forecast");
        assertTrue(state.displayVolume() > state.liveVolume(),
                "below the cap the display amount must clearly include the forecast part");
    }

    @Test
    void playerVolumeStaysSeparateFromSystemAndVirtualVolume() {
        StockState state = StockState.empty(Stock.COAL);
        state.startLiveKline(Stock.COAL.basePrice());
        state.addPlayerTradedVolume(7L);
        state.addTradedVolume(5L);

        assertEquals(12.0, state.liveVolume(), 0.0,
                "the total ledger includes both player and non-player quote-moving fills");
        assertEquals(7.0, state.livePlayerVolume(), 0.0,
                "the player ledger includes only explicitly marked player fills");

        state.pushLiveKline();
        state.addVirtualVolume(100.0);
        double[] closed = state.lastPushedKline();
        assertEquals(112.0, MarketVolume.of(closed), 0.0,
                "virtual volume is added only to the total display ledger");
        assertEquals(7.0, closed[StockState.PLAYER_VOLUME_SLOT], 0.0,
                "virtual volume must never be promoted to real player volume");
    }

    @Test
    void klineRingRetainsEightyNewestCandlesWithoutGrowing() {
        StockState state = StockState.empty(Stock.COAL);
        for (int i = 0; i < StockState.KLINE_LEN + 5; i++) {
            double price = 100.0 + i;
            state.startLiveKline(price);
            state.pushLiveKline();
        }

        assertEquals(80, state.klineCount(), "the server ring keeps the configured eighty-candle history");
        assertEquals(StockState.KLINE_LEN + 5L, state.klinePushes(), "push count remains monotonic after eviction");
        assertEquals(105.0, state.klineAt(0)[0], 0.0, "the five oldest candles are evicted in order");
        assertEquals(184.0, state.klineAt(StockState.KLINE_LEN - 1)[3], 0.0,
                "the newest retained candle stays at the end of the oldest-to-newest view");
        assertEquals(StockState.KLINE_LEN, state.recentKlines().length,
                "serialisation-facing history is bounded and cannot grow with market ticks");
    }

    @Test
    void dailyCandlesUseAnIndependentRingAndKeepPlayerVolumeSeparate() {
        StockState state = StockState.empty(Stock.COAL);
        state.startLiveDailyKline(100.0);
        state.updateLiveDailyKline(110.0);
        state.addPlayerTradedVolume(7L);
        state.pushLiveDailyKline();
        state.startLiveDailyKline(120.0);
        state.pushLiveDailyKline();

        assertEquals(2, state.recentDailyKlines().length,
                "daily history must be independent from short-term history");
        assertEquals(100.0, state.recentDailyKlines()[0][0], 0.0,
                "the daily candle keeps the first quote as its open");
        assertEquals(110.0, state.recentDailyKlines()[0][3], 0.0,
                "the daily candle keeps the last quote before close as its close");
        assertEquals(7.0, state.recentDailyKlines()[0][StockState.PLAYER_VOLUME_SLOT], 0.0,
                "daily player volume is tracked separately from cosmetic total volume");
        assertEquals(0, state.klineCount(),
                "writing a daily candle must not create a short-term candle");
    }

    @Test
    void priceChangesBetweenMarketTicksStayInsideTheCurrentCandle() {
        StockState state = StockState.empty(Stock.COAL);
        state.recordPriceChange(8.0, 10.0);

        double[] live = state.liveKline();
        assertEquals(8.0, live[0], 0.0, "an out-of-band price change starts from the previous quote");
        assertEquals(10.0, live[1], 0.0, "the new quote becomes the candle high");
        assertEquals(8.0, live[2], 0.0, "the previous quote remains the candle low");
        assertEquals(10.0, live[3], 0.0, "the new quote becomes the candle close");
        assertEquals(0.0, state.liveVolume(), 0.0, "a price change must not invent traded volume");

        state.recordPriceChange(10.0, 7.0);
        live = state.liveKline();
        assertEquals(7.0, live[2], 0.0, "a later price change updates the candle low");
        assertEquals(7.0, live[3], 0.0, "the latest quote remains the candle close");
    }
}
