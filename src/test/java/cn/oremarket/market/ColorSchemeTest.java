package cn.oremarket.market;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import cn.oremarket.market.event.MarketEventView;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the rising/falling colour convention switch.
 *
 * <p>The three directional pairs (quote text, P&amp;L / candle bodies, wicks) must swap at the single choke point
 * {@code MarketSettings.uiColor}, the convention must survive a config sync round trip without being applied twice,
 * and every non-directional slot (flat, warning, error, buy button, muted text) must stay exactly where it is.</p>
 */
class ColorSchemeTest {
    private static final int DEFAULT_RISE_QUOTE = 0xFFFF6F6F;
    private static final int DEFAULT_FALL_QUOTE = 0xFF6FE06F;
    private static final int DEFAULT_RISE_BODY = 0xFFE04A4A;
    private static final int DEFAULT_FALL_BODY = 0xFF3FBF5F;
    private static final int DEFAULT_RISE_WICK = 0xFFCC3A3A;
    private static final int DEFAULT_FALL_WICK = 0xFF2F9F4F;
    private static final int DEFAULT_FLAT_QUOTE = 0xFFBDBDBD;
    private static final int DEFAULT_WARNING_RED = 0xFFFF5F5F;
    private static final int DEFAULT_ERROR_RED = 0xFFFF5555;
    private static final int DEFAULT_BUY_GREEN = 0xFF2E5A3A;
    private static final int DEFAULT_LOG_BUY_GREEN = 0xFF3FBF5F;
    private static final int DEFAULT_LOG_SELL_RED = 0xFFE04A4A;
    private static final int DEFAULT_MUTED_GREY = 0xFFAAAAAA;
    private static final int CUSTOM_RISE_RGB = 0x123456;
    private static final int CUSTOM_FALL_RGB = 0x654321;
    private static final int CUSTOM_RISE_ARGB = 0xFF123456;
    private static final int CUSTOM_FALL_ARGB = 0xFF654321;
    private static final String SCHEME_RED_UP = "RED_UP";
    private static final String SCHEME_GREEN_UP = "GREEN_UP";
    private static final String SCHEME_UNKNOWN = "BLUE_UP";
    private static final String LOG_SCHEME_BUY_GREEN = "BUY_GREEN";
    private static final String LOG_SCHEME_BUY_RED = "BUY_RED";
    private static final String LOG_SCHEME_FOLLOW_MARKET = "FOLLOW_MARKET";
    private static final String LOG_SCHEME_UNKNOWN = "PURPLE_BUY";
    private static final String SCHEME_LOWER_CASE = "green_up";
    private static final double RISING_CHANGE = 1.5;
    private static final double FALLING_CHANGE = -1.5;
    private static final double FLAT_CHANGE = 0.0;
    /** The alpha-free RGB mask: the wire format ships 24-bit values, the snapshot adds an opaque alpha itself. */
    private static final int RGB_ONLY_MASK = 0xFFFFFF;
    private static final int EVENT_RARITY_COMMON = 0;
    private static final int EVENT_TICKS_LEFT = 100;
    private static final int EVENT_TOTAL_TICKS = 200;
    private static final int EVENT_UID = 7;

    @AfterEach
    void clearClientSnapshotCache() {
        MarketSettings.setClient(null);
    }

    @Test
    void redUpIsTheDefaultConvention() {
        MarketSettings redUp = MarketSettings.defaults();
        assertEquals(DEFAULT_RISE_BODY, redUp.riseColor());
        assertEquals(DEFAULT_FALL_BODY, redUp.fallColor());
        assertEquals(DEFAULT_RISE_QUOTE, redUp.uiColor(MarketSettings.UI_QUOTE_UP));
        assertEquals(DEFAULT_FALL_QUOTE, redUp.uiColor(MarketSettings.UI_QUOTE_DOWN));
        assertEquals(DEFAULT_RISE_WICK, redUp.uiColor(MarketSettings.UI_WICK_UP));
        assertEquals(DEFAULT_FALL_WICK, redUp.uiColor(MarketSettings.UI_WICK_DOWN));
    }

    @Test
    void defaultLogConventionIsGreenBuyAndRedSell() {
        MarketSettings defaults = MarketSettings.defaults();
        assertEquals(DEFAULT_LOG_BUY_GREEN, defaults.logBuyColor());
        assertEquals(DEFAULT_LOG_SELL_RED, defaults.logSellColor());
    }

    @Test
    void logConventionIsIndependentFromMarketConvention() {
        MarketSettings greenUp = withScheme(SCHEME_GREEN_UP);
        assertEquals(DEFAULT_LOG_BUY_GREEN, greenUp.logBuyColor());
        assertEquals(DEFAULT_LOG_SELL_RED, greenUp.logSellColor());
    }

    @Test
    void buyRedSwapsOnlyTheLogPair() {
        MarketSettings buyRed = withLogScheme(LOG_SCHEME_BUY_RED);
        assertEquals(DEFAULT_LOG_SELL_RED, buyRed.logBuyColor());
        assertEquals(DEFAULT_LOG_BUY_GREEN, buyRed.logSellColor());
        assertEquals(DEFAULT_RISE_BODY, buyRed.riseColor());
        assertEquals(DEFAULT_FALL_BODY, buyRed.fallColor());
    }

    @Test
    void followMarketReconnectsTheLogPairToTheMarketConvention() {
        MarketSettings redUp = withLogScheme(LOG_SCHEME_FOLLOW_MARKET);
        assertEquals(DEFAULT_RISE_BODY, redUp.logBuyColor());
        assertEquals(DEFAULT_FALL_BODY, redUp.logSellColor());

        CompoundTag greenTag = new CompoundTag();
        greenTag.putString("uiColorScheme", SCHEME_GREEN_UP);
        greenTag.putString("uiLogColorScheme", LOG_SCHEME_FOLLOW_MARKET);
        MarketSettings greenUp = MarketSettings.readFrom(greenTag);
        assertEquals(DEFAULT_FALL_BODY, greenUp.logBuyColor());
        assertEquals(DEFAULT_RISE_BODY, greenUp.logSellColor());
    }

    @Test
    void unknownLogConventionFallsBackToGreenBuy() {
        MarketSettings fallback = withLogScheme(LOG_SCHEME_UNKNOWN);
        assertEquals(DEFAULT_LOG_BUY_GREEN, fallback.logBuyColor());
        assertEquals(DEFAULT_LOG_SELL_RED, fallback.logSellColor());
    }

    @Test
    void customLogPairStaysIndependentFromMarketConvention() {
        CompoundTag tag = new CompoundTag();
        tag.putString("uiColorScheme", SCHEME_GREEN_UP);
        tag.putInt("uiColor." + MarketSettings.UI_LOG_BUY, CUSTOM_RISE_RGB);
        tag.putInt("uiColor." + MarketSettings.UI_LOG_SELL, CUSTOM_FALL_RGB);
        MarketSettings custom = MarketSettings.readFrom(tag);
        assertEquals(CUSTOM_RISE_ARGB, custom.logBuyColor());
        assertEquals(CUSTOM_FALL_ARGB, custom.logSellColor());
    }

    @Test
    void logConventionAndRawPairSurviveSyncRoundTrip() {
        CompoundTag tag = new CompoundTag();
        tag.putString("uiLogColorScheme", LOG_SCHEME_BUY_RED);
        tag.putInt("uiColor." + MarketSettings.UI_LOG_BUY, CUSTOM_RISE_RGB);
        tag.putInt("uiColor." + MarketSettings.UI_LOG_SELL, CUSTOM_FALL_RGB);
        MarketSettings server = MarketSettings.readFrom(tag);
        CompoundTag wire = server.writeTo(new CompoundTag());
        assertEquals(LOG_SCHEME_BUY_RED, wire.getString("uiLogColorScheme"));
        assertEquals(CUSTOM_RISE_RGB, wire.getInt("uiColor." + MarketSettings.UI_LOG_BUY));
        assertEquals(CUSTOM_FALL_RGB, wire.getInt("uiColor." + MarketSettings.UI_LOG_SELL));
        MarketSettings client = MarketSettings.readFrom(wire);
        assertEquals(server.logBuyColor(), client.logBuyColor());
        assertEquals(server.logSellColor(), client.logSellColor());
    }

    @Test
    void greenUpSwapsAllThreeDirectionalPairs() {
        MarketSettings greenUp = withScheme(SCHEME_GREEN_UP);
        assertEquals(DEFAULT_FALL_BODY, greenUp.riseColor(), "a rising quote must use the green slot");
        assertEquals(DEFAULT_RISE_BODY, greenUp.fallColor(), "a falling quote must use the red slot");
        assertEquals(DEFAULT_FALL_QUOTE, greenUp.uiColor(MarketSettings.UI_QUOTE_UP));
        assertEquals(DEFAULT_RISE_QUOTE, greenUp.uiColor(MarketSettings.UI_QUOTE_DOWN));
        assertEquals(DEFAULT_FALL_WICK, greenUp.uiColor(MarketSettings.UI_WICK_UP));
        assertEquals(DEFAULT_RISE_WICK, greenUp.uiColor(MarketSettings.UI_WICK_DOWN));
    }

    @Test
    void lowerCaseConventionNameStillSelectsGreenUp() {
        assertEquals(DEFAULT_FALL_BODY, withScheme(SCHEME_LOWER_CASE).riseColor());
    }

    @Test
    void unknownConventionFallsBackToRedUp() {
        MarketSettings fallback = withScheme(SCHEME_UNKNOWN);
        assertEquals(DEFAULT_RISE_BODY, fallback.riseColor());
        assertEquals(DEFAULT_FALL_BODY, fallback.fallColor());
        assertEquals(DEFAULT_RISE_QUOTE, fallback.uiColor(MarketSettings.UI_QUOTE_UP));
    }

    @Test
    void nonDirectionalSlotsNeverMove() {
        MarketSettings greenUp = withScheme(SCHEME_GREEN_UP);
        assertEquals(DEFAULT_FLAT_QUOTE, greenUp.uiColor(MarketSettings.UI_QUOTE_FLAT));
        assertEquals(DEFAULT_WARNING_RED, greenUp.uiColor(MarketSettings.UI_WARNING));
        assertEquals(DEFAULT_ERROR_RED, greenUp.uiColor(MarketSettings.UI_TOAST_FG));
        assertEquals(DEFAULT_BUY_GREEN, greenUp.uiColor(MarketSettings.UI_BUY));
        assertEquals(DEFAULT_MUTED_GREY, greenUp.uiColor(MarketSettings.UI_MUTED));
    }

    @Test
    void customPairColoursFollowTheConventionInsteadOfBeingOverwritten() {
        CompoundTag tag = new CompoundTag();
        tag.putString("uiColorScheme", SCHEME_GREEN_UP);
        tag.putInt("uiColor." + MarketSettings.UI_PNL_UP, CUSTOM_RISE_RGB);
        tag.putInt("uiColor." + MarketSettings.UI_PNL_DOWN, CUSTOM_FALL_RGB);
        MarketSettings greenUp = MarketSettings.readFrom(tag);
        assertEquals(CUSTOM_FALL_ARGB, greenUp.riseColor(), "green-up paints profit with the configured falling colour");
        assertEquals(CUSTOM_RISE_ARGB, greenUp.fallColor(), "green-up paints loss with the configured rising colour");
    }

    @Test
    void syncRoundTripAppliesTheConventionExactlyOnce() {
        MarketSettings server = withScheme(SCHEME_GREEN_UP);
        CompoundTag wire = server.writeTo(new CompoundTag());
        assertEquals(SCHEME_GREEN_UP, wire.getString("uiColorScheme"), "the convention travels with the palette");
        assertEquals(DEFAULT_RISE_BODY & RGB_ONLY_MASK, wire.getInt("uiColor." + MarketSettings.UI_PNL_UP),
                "the raw slot on the wire must stay as configured, not pre-swapped");
        MarketSettings client = MarketSettings.readFrom(wire);
        assertEquals(server.riseColor(), client.riseColor(), "a double swap would silently restore red-up on clients");
        assertEquals(server.fallColor(), client.fallColor());
        assertEquals(server.uiColor(MarketSettings.UI_QUOTE_UP), client.uiColor(MarketSettings.UI_QUOTE_UP));
        assertEquals(server.uiColor(MarketSettings.UI_WICK_UP), client.uiColor(MarketSettings.UI_WICK_UP));
        assertEquals(server.uiColor(MarketSettings.UI_WARNING), client.uiColor(MarketSettings.UI_WARNING));
    }

    @Test
    void redUpConventionSurvivesTheSameRoundTrip() {
        MarketSettings server = withScheme(SCHEME_RED_UP);
        MarketSettings client = MarketSettings.readFrom(server.writeTo(new CompoundTag()));
        assertEquals(DEFAULT_RISE_BODY, client.riseColor());
        assertEquals(DEFAULT_FALL_BODY, client.fallColor());
    }

    @Test
    void chatQuoteStyleFollowsTheConvention() {
        assertEquals(ChatFormatting.RED, MarketSettings.defaults().changeStyle(RISING_CHANGE));
        assertEquals(ChatFormatting.GREEN, MarketSettings.defaults().changeStyle(FALLING_CHANGE));
        assertEquals(ChatFormatting.RED, MarketSettings.defaults().changeStyle(FLAT_CHANGE),
                "an unchanged quote keeps the historic rising colour instead of flipping");
        assertEquals(ChatFormatting.GREEN, withScheme(SCHEME_GREEN_UP).changeStyle(RISING_CHANGE));
        assertEquals(ChatFormatting.RED, withScheme(SCHEME_GREEN_UP).changeStyle(FALLING_CHANGE));
        assertEquals(ChatFormatting.GRAY, withScheme(SCHEME_GREEN_UP).changeStyle(Double.NaN),
                "a non-finite change is reported in grey, never guessed");
    }

    @Test
    void eventTonePaintsWithTheClientSnapshotConvention() {
        MarketEventView bullish = eventView(MarketEventView.TONE_BULL);
        MarketEventView bearish = eventView(MarketEventView.TONE_BEAR);
        MarketEventView neutral = eventView(MarketEventView.TONE_NEUTRAL);
        assertEquals(DEFAULT_RISE_BODY, bullish.toneColor());
        assertEquals(DEFAULT_FALL_BODY, bearish.toneColor());
        assertEquals(DEFAULT_MUTED_GREY, neutral.toneColor());
        MarketSettings.setClient(withScheme(SCHEME_GREEN_UP));
        assertEquals(DEFAULT_FALL_BODY, bullish.toneColor(), "a bullish event must go green under green-up");
        assertEquals(DEFAULT_RISE_BODY, bearish.toneColor(), "a bearish event must go red under green-up");
        assertEquals(DEFAULT_MUTED_GREY, neutral.toneColor(), "a neutral event never changes hue");
    }

    private static MarketSettings withScheme(String schemeName) {
        CompoundTag tag = new CompoundTag();
        tag.putString("uiColorScheme", schemeName);
        return MarketSettings.readFrom(tag);
    }

    private static MarketSettings withLogScheme(String schemeName) {
        CompoundTag tag = new CompoundTag();
        tag.putString("uiLogColorScheme", schemeName);
        return MarketSettings.readFrom(tag);
    }

    private static MarketEventView eventView(int tone) {
        return new MarketEventView("test event", "test description", "effect", "COAL",
                List.of("COAL"), EVENT_RARITY_COMMON, EVENT_TICKS_LEFT, EVENT_TOTAL_TICKS,
                false, EVENT_UID, List.of(), tone, false, "");
    }
}
