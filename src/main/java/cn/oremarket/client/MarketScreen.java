/*
 * oremarket - Minecraft Forge Mod
 * Copyright (C) 2026 Redpotato77
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package cn.oremarket.client;

import cn.oremarket.common.Fees;
import cn.oremarket.common.MarketTime;
import cn.oremarket.common.PriceShockMath;
import cn.oremarket.market.MarketSettings;
import cn.oremarket.market.Stock;
import cn.oremarket.common.StockState;
import cn.oremarket.market.event.MarketEventView;
import cn.oremarket.common.LogType;
import cn.oremarket.market.log.MarketLogEntry;
import cn.oremarket.net.OreMarketNetwork;
import cn.oremarket.net.packet.C2SCloseUiPacket;
import cn.oremarket.net.packet.C2SExchangePacket;
import cn.oremarket.net.packet.C2SLoanPacket;
import cn.oremarket.net.packet.C2SLimitPacket;
import cn.oremarket.net.packet.C2SOrderPacket;
import cn.oremarket.net.packet.C2SRequestBookPacket;
import cn.oremarket.net.packet.C2SShortPacket;
import cn.oremarket.net.packet.C2SRequestSnapshotPacket;
import cn.oremarket.net.packet.C2STradePacket;
import cn.oremarket.net.packet.RequestMarketSnapshotPacket;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import cn.oremarket.common.Num;

/**
 * Main screen of the ore stock market. Server-authoritative UI: this class only renders the
 * client snapshot ({@link MarketClientState}) and sends C2S packets; it never computes market,
 * loan or ledger state locally.
 *
 * <p>Layout: top bar (balance / tier / P&L / loan status), middle area with the Exchange and
 * Market containers, and a pinned bottom event bar. The Market container switches between the
 * Quotes, Trade (short / orders / limits sub-pages), Exchange, Loans, Rank and Log pages; each
 * page keeps its own scroll offsets and is clipped to the content area.</p>
 *
 * <p>Hard rendering invariants (breaking any of them caused player-visible bugs before; the full
 * historical rationale lives in {@code docs/market-screen-history.md}):
 * <ul>
 *   <li>Every container pushes {@link #scissor} before drawing and pops it in {@code finally};
 *       nested clip rects intersect outer-to-inner, so painting and hit-testing share one rect.</li>
 *   <li>List rows draw whenever they share a single pixel with the viewport (half rows included);
 *       whole-row-visible tests, hidden rows and ellipses are banned.</li>
 *   <li>Displayed money / margin / P&L values come only from the matching server snapshot;
 *       the client never re-derives them from independently arriving quotes.</li>
 *   <li>Exactly one container reacts to a scroll wheel at a time.</li>
 * </ul></p>
 */
public final class MarketScreen extends Screen {
    // ---------------- Constants ----------------
    private static final int TOAST_H = 12;
    private static final int TAB_H = 20;
    /** Two rows of Buy/Sell buttons on the Quotes tab plus the fee hint line below them. */
    private static final int TRADE_BTNS_H = 56;
    /** Height of one row of Buy/Sell buttons on the Quotes tab. */
    private static final int TRADE_BTN_H = 20;
    /**
     * Vertical gap between the Buy row and the Sell row. Must be >= 4: the keyboard focus
     * outline of MarketButton (FOCUS_RING) is drawn 2 px outside the button rectangle, so its
     * bottom edge lands on y+21. With a row gap of only 2, the top line of the Sell row
     * outline drawn later (y2-1 = y+21) would cover the lower half of the Buy row focus ring.
     */
    private static final int TRADE_ROW_GAP = 4;
    /** Step between vertically stacked buttons = button height + row gap. */
    private static final int TRADE_BTN_STRIDE = TRADE_BTN_H + TRADE_ROW_GAP;
    /** Space reserved for the fee hint line under the trading button area. */
    private static final int TRADE_HINT_LINE_H = 12;
    /** Candlestick chart height: preferred 100 fixed, minimum 40 (no longer adjusted per toggle since the technical indicators were removed). */
    private static final int CHART_MIN = 40;
    /** Base height of the three columns under the Quotes page (list / holdings / button group) - the chart takes all the remaining vertical space. */
    private static final int THREE_COL_H = 96;
    /** Height of the information line at the top of the candlestick chart. */
    private static final int CHART_INFO_H = 14;
    private static final int SPLIT_GAP = 4;
    private static final int LIST_ROW = 16;
    /** Fixed stock list height of 128 (8 rows x 16). */
    private static final int STOCK_LIST_H = 128;
    /** Natural height of the list (dynamic = number of active stocks x row height). */
    private int listFullH() {
        return n() * LIST_ROW;
    }
    private static final int DETAIL_ROW = 16;
    private static final int DETAIL_FULL_H = 7 * DETAIL_ROW;
    /** The position overview block is gone, OVERVIEW_MAX_ROWS / OVH_* went with it; LOG_ROW is now the height of one log entry. */
    private static final int LOG_ROW = 12;
    /** Minimum height of the log area: if content height - 8 is below 48 the whole page shows "Not enough space". */
    private static final int LOG_MIN_H = 48;
    private static final int LOAN_PANEL_H = 92;
    /**
     * Natural height of the Exchange. With 8 stocks it is 470 (title
     * area 14 + inner block 8x54+7x1 = 439 + bottom toolbar 28 - 11 px of overlap); as the
     * number of stocks changes it scales with exRowsH() instead of staying hard-coded at 470.
     */
    private static final int EX_BASE_NONROWS_H = 470 - (8 * 54 + 7 * 1);   // = 31; the row constants moved below cannot be forward-referenced

    /** Natural height of the stock-market container: at least tabs 20 + chart minimum 40 + gap 4 + fixed list 128 + gap 4 + button area 54 + bottom 4. */
    private int mkNaturalH() {
        return Math.max(MK_NAT_H, TAB_H + CHART_MIN + 4 + STOCK_LIST_H + 4 + TRADE_BTNS_H + 4);
    }

    private int exNaturalH() {
        return EX_BASE_NONROWS_H + exRowsH();
    }
    private static final int MK_NAT_H = 240;
    private static final int STACK_GAP = 4;
    private static final int SIDE_GAP = 6;
    private static final int BAR_MAX = 100;
    private static final int EMPTY_BAR_H = 13;
    private static final int CARD_H = 28;
    private static final int CARD_LINE = 9;
    private static final int CARD_GAP = 3;
    private static final int CARD_VGAP = 3;
    private static final int CARD_MIN_W = 104;
    private static final int CARD_PAD = 2;
    /** Active stock count = length of the client registry's derived view (dynamic; hardcoding 8 is forbidden). */
    private int n() {
        return MarketClientState.INSTANCE.viewSize();
    }
    private static final double LIMIT_EPS = 0.005;
    /** Scrollbar width reserved on the list's right (used in column-width math). */
    private static final int COL_SB_W = 6;

    // ================== UI theme palette (configurable in oremarket.toml, category twenty) ==================
    // The config stores 24-bit RGB; FF alpha is added when writing the snapshot so the values become opaque ARGB.
    // refreshTheme() syncs every role colour below from the client config snapshot before each frame (saving the config takes effect immediately).
    private static int THEME_PANEL_ROOT = 0xFF1A1A1A;   // root panel background / pressed button edge / candlestick outline
    private static int THEME_PANEL_SOFT = 0xFF2B2B2B;   // section plates / event bar / card background
    private static int THEME_PANEL_ROW = 0xFF3A3A3A;    // row separators / generic button background
    private static int THEME_BORDER = 0xFF555555;       // 1 px panel border
    private static int THEME_DARK = 0xFF2A2A2A;         // dark background: unselected tab / scrollbar track / disabled button
    private static int THEME_HIGHLIGHT = 0xFF4A4A4A;    // inner highlight / suspended-trading row background
    private static int THEME_ACCENT = 0xFF6A8FD0;       // accent: selection bar / focus ring / unlocked dot
    private static int THEME_GOLD = 0xFFFFD75F;         // gold accents
    private static int THEME_MUTED = 0xFFAAAAAA;        // secondary text / unchanged price
    private static int THEME_GRAY = 0xFF888888;         // grey text / locked elements
    private static int THEME_DIM = 0xFF666666;          // dark grey text / scrollbar thumb
    private static int THEME_QUOTE_UP = 0xFFFF6F6F;     // rising quote text
    private static int THEME_QUOTE_DOWN = 0xFF6FE06F;   // falling quote text
    private static int THEME_QUOTE_FLAT = 0xFFBDBDBD;   // unchanged quote text
    private static int THEME_PNL_UP = 0xFFE04A4A;       // profit / bullish candle body
    private static int THEME_PNL_DOWN = 0xFF3FBF5F;     // loss / bearish candle body
    private static int THEME_SELECTED = 0xFF4A5A7A;     // selected row in a list
    private static int THEME_HOVER = 0xFF404040;        // hover
    private static int THEME_BLUE = 0xFF3A3A5A;         // selected tab / take-back button
    private static int THEME_BUY = 0xFF2E5A3A;          // store button
    private static int THEME_TOAST_BG = 0xFF0E0E0E;     // error hint / event detail background
    private static int THEME_TOAST_FG = 0xFFFF5555;     // error hint text
    private static int THEME_KGRID = 0xFF333333;        // candlestick grid
    private static int THEME_CHART_BG = 0xFF151515;     // chart plot background
    private static int THEME_LETTERBOX = 0xFF0D0D10;    // letterbox area background
    private static int TEXT_WHITE = 0xFFFFFFFF;
    private static int TEXT_SOFT = 0xFFC9C9D9;
    private static int TIP_TEXT = 0xFFE8E8E8;
    private static int BODY_TEXT = 0xFFCCCCCC;
    private static int GOLD_TEXT = 0xFFFFE89A;
    private static int HINT_GRAY = 0xFF9A9AA5;
    private static int CHIP_BLUE = 0xFF44506A;
    private static int ACCENT_ORANGE = 0xFFFFB060;
    private static int COIN_DARK = 0xFFB8860B;
    private static int COIN_LIGHT = 0xFFDAA520;
    // Derived role colours: the defaults are the original design colours; a theme refresh only rewrites their values, never their names.
    private static int PANEL_ROOT = THEME_PANEL_ROOT;
    private static int PANEL_SOFT = THEME_PANEL_SOFT;
    private static int PANEL_ROW = THEME_PANEL_ROW;
    private static int BORDER_MAIN = THEME_BORDER;
    private static int DARK_BG = THEME_DARK;
    private static int HIGHLIGHT_IN = THEME_HIGHLIGHT;
    private static int ACCENT_BLUE = THEME_ACCENT;
    private static int TEXT_MUTED = THEME_MUTED;
    private static int DIM_TX = THEME_DIM;
    private static int TOAST_BG = THEME_TOAST_BG;
    private static int TOAST_TEXT = THEME_TOAST_FG;
    private static int CHART_BG = THEME_CHART_BG;
    private static int UP = THEME_QUOTE_UP;
    private static int DOWN = THEME_QUOTE_DOWN;
    private static int FLAT = THEME_QUOTE_FLAT;
    private static int GOLD = THEME_GOLD;
    private static int DOT_UNLOCKED = THEME_ACCENT;
    private static int DOT_LOCKED = THEME_GRAY;
    private static int LOCKED_TX = THEME_DIM;
    private static int TOAST_RED = 0xFFFF5F5F;
    private static int SUSPEND_BG = THEME_HIGHLIGHT;
    private static int SUSPEND_TX = THEME_MUTED;
    private static int TAB_SELECTED_BG = THEME_BLUE;
    private static int TAB_NORMAL_BG = THEME_DARK;
    private static int TAB_HOVER_BG = THEME_HOVER;
    private static int LIST_SEL_BG = THEME_SELECTED;
    private static int LIST_HOVER_BG = THEME_HOVER;
    private static int PNL_UP = THEME_PNL_UP;
    private static int PNL_DOWN = THEME_PNL_DOWN;
    private static int PNL_FLAT = THEME_MUTED;
    private static int K_BODY_UP = THEME_PNL_UP;
    private static int K_BODY_DOWN = THEME_PNL_DOWN;
    /** Unchanged candles use a white body so a sealed one-price board is visibly a horizontal white line. */
    private static int K_BODY_FLAT = TEXT_WHITE;
    private static int K_WICK_UP = 0xFFCC3A3A;
    private static int K_WICK_DOWN = 0xFF2F9F4F;
    private static int K_WICK_FLAT = THEME_GRAY;
    private static int K_STROKE = THEME_PANEL_ROOT;
    private static int K_GRID = THEME_KGRID;
    private static int K_LABEL = THEME_GRAY;
    // UI feel (category twenty-one): three wheel-step tiers + smoothing of the linked animations
    private static int SCROLL_STEP = 20;
    private static int SCROLL_STEP_SIDE = 12;
    private static int SCROLL_STEP_TIP = 10;
    private static final int K_Y_AXIS_W = 40;
    /** Chart inner padding is down to 2 px so the candles fill their rectangle (the old 6 px plus a 12 px empty X axis at the bottom was pure waste). */
    private static final int K_PAD_X = 2;
    private static final int K_PAD_Y = 2;
    private static final int K_XAXIS_H = 2;
    /** Compact volume strip inside the K-line chart; hidden automatically when the price area would become cramped. */
    private static final int K_VOLUME_H = 22;
    private static final int K_VOLUME_MIN_PRICE_H = 34;
    private static final int K_BODY_MIN = 3;
    /** Candle body width caps at 20 px (it must not stretch without limit when few candles are shown) and shrinks with the slot pitch when there are many. */
    private static final int K_BODY_MAX = 20;
    /** Largest pitch per candle slot - with fewer than 20 candles the row is filled to the right and the blank space on the left never exceeds this pitch. */
    private static final int K_SLOT_MAX_PITCH = 44;
    private static final int K_VISIBLE_MAX = StockState.KLINE_LEN;
    /** Log body text colour: white 0xFFCCCCCC. */
    private static int LOG_TEXT = 0xFFCCCCCC;
    /** Stock list scrollbar: 4 px wide, shown only when the content overflows, thumb at least 12 px tall. */
    private static final int LIST_SB_W = 4;
    private static int LIST_SB_TRACK = DARK_BG;
    private static int LIST_SB_THUMB = DIM_TX;
    private static final int LIST_SB_MIN_H = 12;
    private static int GRAY88 = THEME_GRAY;
    private static int ICON_PLACEHOLDER = DIM_TX;
    /** Vertical padding of the Exchange content area. */
    private static final int EX_BOTTOM_PAD = 6;
    /** Preferred candlestick chart height of 100 (no longer adjusted per toggle since the technical indicators were removed). */
    private static final int CHART_DESIRED = 100;
    /** Line height of a wrapped log entry and the gap between two entries. */
    private static final int LOG_LINE_H = 9;
    private static final int LOG_ENTRY_GAP = 2;
    /** Debounce window for trading button clicks, in milliseconds: it only looks at the system clock and never waits for a server reply. */
    private static long CLICK_DEBOUNCE_MS = 200L;

    // ---------------- Tabs ----------------
    /** Tab order = Quotes, Exchange, Trade, Loans, Ranking, Log; the Exchange that used to sit permanently on the left now lives in the "Exchange" tab. */
    private static final int TAB_MARKET = 0;
    private static final int TAB_EXCH = 1;
    private static final int TAB_TRADE = 2;
    private static final int TAB_LOAN = 3;
    private static final int TAB_RANK = 4;
    private static final int TAB_LOG = 5;
    /** The tab bar holds 6 tabs laid out edge to edge without padding; the usable width of the root container is mkW - 12. */
    private static final int TAB_COUNT = 6;
    /** Three sub-pages under the Trade tab: Short Selling / Limit Order / Price Limit; three under the Ranking tab: total assets / daily gain / position size. */
    private static final int SUB_TAB_H = 16;
    private static final String[] TRADE_SUB_NAMES = {cn.oremarket.common.RawText.t("oremarket.text.0025"), cn.oremarket.common.RawText.t("oremarket.text.0042"), cn.oremarket.common.RawText.t("oremarket.text.0075")};
    private static final String[] RANK_SUB_NAMES = {cn.oremarket.common.RawText.t("oremarket.text.0109"), cn.oremarket.common.RawText.t("oremarket.text.0154"), cn.oremarket.common.RawText.t("oremarket.text.0111"), cn.oremarket.common.RawText.t("oremarket.rank.tab.debt"), cn.oremarket.common.RawText.t("oremarket.rank.tab.credit")};
    private static final String[] TAB_NAMES = {cn.oremarket.common.RawText.t("oremarket.text.0066"), cn.oremarket.common.RawText.t("oremarket.tab.exchange"), cn.oremarket.common.RawText.t("oremarket.text.0022"), cn.oremarket.common.RawText.t("oremarket.text.0067"), cn.oremarket.common.RawText.t("oremarket.text.0043"), cn.oremarket.common.RawText.t("oremarket.text.0048")};

    // ---------------- Button ids ----------------
    private static final int M_CLOSE = 1;
    /** Quotes page button range: Buy 200..203, Sell 204..207, clear all positions 208, locate my position 209, clear-confirmation dialog Confirm 210 / Cancel 211. */
    private static final int M_TRADE = 200;        // 200..208
    private static final int M_LOCATE = 209;
    /** Real widget for the Quotes chart's short-term / daily mode switch. */
    private static final int M_CHART_MODE = 212;
    /** The two buttons of the clear-all confirmation dialog (reusing the loan Modal machinery). */
    private static final int M_CLEAR_CONFIRM = 210;
    private static final int M_CLEAR_CANCEL = 211;
    private static final int M_LOAN = 400;         // 400..431 (loan page v2)
    /** Trade sub-page buttons: Short Selling 900..902, Limit Order 910..914, Price Limit 920..923, stock dropdown trigger 930. */
    private static final int M_SHORT_OPEN = 900;
    private static final int M_SHORT_CLOSE_ONE = 901;
    private static final int M_SHORT_CLOSE_ALL = 902;
    /** Short All - takes the smaller of "shares affordable with the balance" and "shares left in the pool" and puts it into the quantity box. */
    private static final int M_SHORT_ONECLICK = 903;
    /** Close Half - sends floor(position shares / 2), so 3 shares -> 1 share. */
    private static final int M_SHORT_CLOSE_HALF = 904;
    /** Yellow warning colour when the short pool has less than 20% left (an empty pool uses the theme red PNL_UP, a full one uses white). */
    private static final int SHORT_POOL_YELLOW = 0xFFFFE066;
    private static final int M_ORDER_BUY = 910;
    private static final int M_ORDER_SELL = 911;
    private static final int M_ORDER_SUBMIT = 912;
    private static final int M_ORDER_CANCEL = 913;
    /** Matches the selected order of another player directly at the price that player posted. */
    private static final int M_ORDER_TAKE = 914;
    private static final int M_LIMIT_BUY = 920;
    private static final int M_LIMIT_SELL = 921;
    private static final int M_LIMIT_SUBMIT = 922;
    private static final int M_LIMIT_CANCEL = 923;
    /** Trigger button of the stock dropdown at the top of the Trade page (custom-drawn expanding list, the vanilla CycleButton is banned). */
    private static final int M_PICKER = 930;
    /** Action buttons in the right column of the Exchange: store 941 / take out 942 / store all 943 / take out all 944. */
    private static final int M_EX_DEPOSIT = 941;
    private static final int M_EX_WITHDRAW = 942;
    private static final int M_EX_DEPOSIT_ALL = 943;
    private static final int M_EX_WITHDRAW_ALL = 944;
    /** The Modal mask became translucent (0x80000000, roughly 50% black) so the text underneath is dimmed but still visible; the dialog plate itself stays opaque. */
    private static final int MODAL_MASK = 0x80000000;
    private static final int MODAL_BG = 0xFF2B2B2B;
    /**
     * One shared viewport padding for every scrollable list
     * (enableScissor subtracts padding=4) and the 4 px gap left under the last row when
     * scrolled to the bottom.
     */
    private static final int LIST_PAD = 4;
    private static final int LIST_BOTTOM_GAP = 4;
    /** Red warning outline around the Clear All Positions button. */
    private static final int DANGER_RING = 0xFFFF5555;

    // ---------------- Row geometry of the Exchange left column
    /** Height of one left-column row (16 px icon + 2 px of breathing room) and the row gap. */
    private static final int EX_ROW_H = 20;
    private static final int EX_ROW_GAP = 2;
    /** Column widths inside a row: icon column 18, inventory count column 30; the name column takes whatever is left and the rate column is right-aligned in 86. */
    private static final int EX_ICON_W = 18;
    private static final int EX_COUNT_W = 30;
    private static final int EX_RATE_W = 86;
    private static final int EX_COL_GAP = 3;
    /** Colour set of the right-column action buttons (keeps the store/take theme that oremarket.toml can configure). */
    private static int EX_DEP_BG = 0xFF2E5A3A;
    private static int EX_DEP_HOVER = 0xFF3F7A50;
    private static int EX_WDR_BG = 0xFF3A3A5A;
    private static int EX_WDR_HOVER = 0xFF50507A;
    private static int EX_SEARCH_BG = 0xFF3A3A3A;
    private static int EX_SEARCH_HOVER = 0xFF4E4E4E;
    private static int EX_BTN_DISABLED_BG = 0xFF2A2A2A;
    private static int EX_BTN_DISABLED_TX = 0xFF666666;
    /** Total height of the Exchange row area: m rows of fixed height + (m-1) * 2 of gaps; an O(1) formula that is only recomputed and cached when the stock count changes. */
    private int exRowsHCache = -1;
    private int exRowsHCacheN = -1;
    private int exRowsH() {
        int m = n();
        if (m != exRowsHCacheN) {
            exRowsHCacheN = m;
            exRowsHCache = m <= 0 ? 0 : m * EX_ROW_H + (m - 1) * EX_ROW_GAP;
        }
        return exRowsHCache;
    }
    private static int EX_SEP_COLOR = PANEL_ROW;
    private static int EX_ROW_HOVER_BG = LIST_HOVER_BG;
    private static int EX_LOCKED_TX = DIM_TX;
    /** Rate text: light grey 0xFFAAAAAA, no hit-area. */
    private static int EX_RATE_TX = TEXT_MUTED;

    /** Debug aid: when true, mouseClicked logs the cursor position and the hitRect of every button. */
    private static final boolean DEBUG_CLICK = false;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("OreMarket/UI");

    // ================= Uniform scaling for any GUI Scale =================
    // The window size at GUI Scale=4 is the layout baseline; everything is then scaled to the current GUI size.
    // That way switching GUI Scale inside one window never changes the visual size of buttons, lists or text.
    //
    // Key point (the final stacking fix): the baseline canvas is **constant**,
    // BASE_W x BASE_H, and no longer grows with the window or the GUI Scale. The previous version
    // only set a floor of 480x270 while the canvas still scaled as physical size / 4, so the layout
    // still changed with the window: at 480x270 the chart was squeezed to 40, only 4 list rows were
    // left, and the 114 px tall position details overflowed the list area straight onto the
    // Buy/Sell buttons.
    // 640x360 is the real canvas of r37 at 1080p (GUI Scale 3), the layout the user agreed is the
    // best looking one; every window size now reproduces it pixel for pixel and the window only
    // provides the uniform scale factor plus centring.
    /** Baseline width: a constant 640 (the r37 1080p canvas width). */
    private int baseWidth = 0;
    /** Baseline height: a constant 360 (the r37 1080p canvas height). */
    private int baseHeight = 0;
    /** Layout canvas width fixed at 640: gw = min(640, width - 8) = 632, so the narrow-screen stacking branch is never reached. */
    private static final int BASE_W = 640;
    /** Layout canvas height fixed at 360: gh = 352, enough for the Quotes page chart 69 + list 128 + buttons 54. */
    private static final int BASE_H = 360;
    /** Global scale factor = current GUI size / baseline canvas size (below 1 when the window is smaller than the canvas). */
    private double uiScale = 1.0D;
    /** x of the top-left corner of the scaled canvas once centred horizontally (screen GUI coordinates). */
    private double offsetX = 0.0D;
    /** y of the top-left corner of the scaled canvas once centred vertically (screen GUI coordinates). */
    private double offsetY = 0.0D;
    /** Width and height of the current window in GUI coordinates (needed to fill the letterbox outside the canvas). */
    private int screenWidth = 0;
    private int screenHeight = 0;
    /** Letterbox colour: the borders the scaled canvas does not cover get a near-black fill so no leftovers from the previous frame show through. */
    private static int LETTERBOX = 0xFF0D0D10;

    // ---------------- Interaction state (kept per tab, never cleared on switch) ----------------
    private int tab = TAB_MARKET;
    /** Sub-page of the Trade tab (0 Short Selling / 1 Limit Order / 2 Price Limit), only shown inside the Trade tab. */
    private int tradeSub = 0;
    /** Sub-page of the Ranking tab (0 total assets / 1 daily gain / 2 position size). */
    private int rankSub = 0;
    /** Form state of the Limit Order / Price Limit sub-pages: side, price text, quantity text. */
    private boolean orderSideBuy = true;
    private boolean limitSideBuy = true;
    private String orderPriceText = "";
    private String orderQtyText = "10";
    private String limitPriceText = "";
    private String limitQtyText = "10";
    /** Quantity text borrowed by the Short Selling sub-page. */
    private String shortQtyText = "1";
    /** Selection inside the custom-drawn lists (cancel order / take order / short to close). */
    private long selectedOrderId = -1;
    private long selectedLimitId = -1;
    private long selectedShortPositionId = -1;
    /**
     * Raw label of the "Take selected order" button right now, which follows the side of the
     * selected row (the other side's sell order -> "Buy this order", their buy order ->
     * "Sell this order", nothing selected -> the generic label).
     * Only the raw text is stored; the translation is still resolved by {@link L} at render
     * time, so rebuilding the widgets on a language switch re-translates from this raw text.
     */
    private String orderTakeRaw = "";
    /** Scroll offsets of the Limit Order / Price Limit / Short Selling lists. */
    private int shortScroll = 0;
    private int bookScroll = 0;
    private int limitScroll = 0;
    /** Part four: two-line stock column for the Limit Order / Price Limit lists - switched on for the whole table as soon as one row fails to fit at 0.65x. */
    private boolean bookTwoLine;
    private boolean limitTwoLine;
    private int bookRowH() {
        return bookTwoLine ? 32 : LIST_ROW;
    }
    private int limitRowH() {
        return limitTwoLine ? 32 : LIST_ROW;
    }
    /** -1 means no stock is selected (the right column then shows "Select a stock"); derived from selectedCode and never assigned on its own. */
    private int selected = -1;
    /**
     * The **code** of the selected stock. Only
     * initialised once the client stock registry is non-empty; revalidated after every
     * SyncConfig, market snapshot and registry replacement, falling back to the first stock
     * in the list when it is no longer valid.
     */
    private String selectedCode = null;
    /** Redraw coalescing: the last time the arrays were really rebuilt, in milliseconds (reset to 0 when the screen opens; two packets closer than 50 ms trigger only one redraw). */
    private long lastRedrawTime = 0L;
    /** "Click to retry" cooldown, a timestamp in milliseconds (reset to 0 when the screen opens; for 2000 ms after a click the button is inert and sends nothing). */
    private long pendingRetryCooldown = 0L;
    private long loanSelAmount = 1;
    /**
     * The "Borrow max" intent. While true, confirming the loan sends
     * {@code STEP_ALL} and the server disburses the full amount of the credit available at
     * that moment; pressing plus/minus, Borrow 1 or Borrow 10 clears the intent.
     */
    private boolean loanBorrowAll = false;
    // ---------- Loan page v2
    /** Line height of plain text (the same as the other lists in this mod). */
    private static final int LOAN_LINE_H = 12;
    /** Height of a section title row. */
    private static final int LOAN_HEAD_H = 14;
    /** Height of a small button row (the existing 16 is kept). */
    private static final int LOAN_BTN_H = 16;
    /** Height of a primary button (the existing 20 is kept). */
    private static final int LOAN_BIG_BTN_H = 20;

    /**
     * Section slots of the loan page: computeLoanLayout recomputes the relative y of every
     * section each frame from the actual content, so the page height follows the content
     * (scrolling rule five: content height is always derived from the number
     * of rows, never from a hard-coded panel height).
     */
    private static final int LS_CREDIT = 0, LS_INFO = 1, LS_PHEAD = 2, LS_PROD = 3, LS_MHEAD = 4,
            LS_MODE = 5, LS_THEAD = 6, LS_TERM = 7, LS_TERMNOTE = 8, LS_AHEAD = 9, LS_AMT = 10,
            LS_EHEAD = 11, LS_EST = 12, LS_CONFIRM = 13, LS_PLANHEAD = 14, LS_PLAN = 15,
            LS_REPAY = 16, LS_PREPAY = 17, LS_HINT = 18, LS_P2PHEAD = 19, LS_P2P = 20,
            LS_P2PBTN = 21, LS_CLAW = 22, LS_SLOTS = 23;
    private final int[] loanY = new int[LS_SLOTS];
    /** Total content height of the loan page in pixels; the scroll overflow is this minus the visible content height. */
    /** Render-exception log de-duplication: printed only when the kind of exception changes, the rest is counted as suppressed frames. */
    private String lastRenderException;
    private int renderExceptionSuppressed;

    /**
     * De-duplicates render-exception logging (performance hygiene): an exception that stays
     * present used to log one WARN per frame (60 per second); now a single line is printed when
     * the kind of exception changes, carrying the number of frames suppressed before it. Frame
     * skipping and the reset behaviour are exactly the same as before.
     */
    private void logRenderException(Throwable t) {
        String kind = t.getClass().getName() + ":" + t.getMessage();
        if (!kind.equals(lastRenderException)) {
            LOG.warn("MarketScreen render exception (skip this frame, keep UI state)"
                    + " [{}; {} identical frame(s) suppressed before this]", kind,
                    renderExceptionSuppressed, t);
            lastRenderException = kind;
            renderExceptionSuppressed = 0;
        } else {
            renderExceptionSuppressed++;
        }
    }

    private int loanPageH = LOAN_PANEL_H;
    /** The loan intent currently selected in the UI (only packet parameters; the server's recomputation is what counts). */
    private int loanProductSel = 0;
    private int loanModeSel = 0;
    private int loanInstallmentsSel = 1;
    /** Loan confirmation dialog: the borrow packet is only sent after an explicit second confirmation. */
    private boolean loanConfirmOpen = false;
    /**
     * Deadline of the borrow-request busy state, a timestamp in milliseconds
     * (0 means no request in flight). Set to now + LOAN_BUSY_MS right after ACTION_BORROW is
     * sent; while the window is open "Confirm loan" is greyed out and reads "Working...", and
     * it ends early as soon as the server reply flips the state to an active loan (a change of
     * loanStateCode). It recovers purely on a timer and can never stick.
     */
    private long loanBusyUntilMs = 0L;
    /** Length of the busy-state window (covers a normal server round trip plus slack; after a timeout the button becomes clickable again). */
    private static final long LOAN_BUSY_MS = 1500L;

    /** Whether a borrow request is in flight (inside the time window and the active-loan state has not arrived yet). */
    private boolean loanBusy(MarketClientState st) {
        if (loanBusyUntilMs == 0L) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now >= loanBusyUntilMs || st.hasLoan()) {
            loanBusyUntilMs = 0L;
            return false;
        }
        return true;
    }

    private boolean firstLoanTab = true;
    private int eventScroll = 0;
    private int mkScroll = 0;      // scroll of the stock list in the left column of the Quotes page
    private int detailScroll = 0;  // scroll of the position details in the right column of the Quotes page
    private int loanScroll = 0;    // scroll of the loan page
    private int logScroll = 0;     // scroll of the log area on the Log tab
    private int exScroll = 0;
    private int midScroll = 0;
    // ---------------- Smooth, two-way linked scrolling between the Exchange and the stock list
    /** Target offset of the linked animation: -1 means inactive; recomputed whenever the selection changes so the target row is fully inside the viewport. */
    private int exScrollTarget = -1;
    private int mkScrollTarget = -1;
    /** Current animation position (a float lerped towards the target each frame; -1 means it is in sync with the integer offset and no animation is running). */
    private double exAnimOffset = -1.0D;
    private double mkAnimOffset = -1.0D;
    /** Re-entrancy lock: the link is triggered only by a selection change in one direction, and anything happening inside the lock is ignored, which rules out infinite recursion. */
    private boolean isSyncingScroll;
    /** Lerp factor: currentOffset += (targetOffset - currentOffset) * 0.15F. */
    private static float SCROLL_LERP = 0.15F;
    private EditBox shortQtyBox;
    private EditBox orderPriceBox;
    private EditBox orderQtyBox;
    private EditBox limitPriceBox;
    private EditBox limitQtyBox;


    // ---------------- Log wrap cache and render snapshot
    /** The wrapped result of one log entry: its text lines plus the rendered height (max(12, number of lines * 9)). */
    private static final class LogWrap {
        final List<String> lines = new ArrayList<>();
        int height;
        /** The prefix already translated into the current language at cache time, together with its width (nothing is re-translated per frame while drawing). */
        String prefix = "";
        int prefixW;
    }

    /** Render snapshot: rendering only reads this shallow copy, network packets only write the main list (any change of logVersion counts as stale). */
    private final List<MarketLogEntry> logSnapshot = new ArrayList<>();
    private int logSnapshotVersion = -1;
    /** Number of consecutive frames skipped because the snapshot and the main list disagree in length; the fourth frame force-rebuilds the snapshot from the current main list and draws it. */
    private int logSkipFrames = 0;
    /** Wrap cache with one item per snapshot entry; logWrapDirty is set by whoever writes to the log (main thread) and the rebuild only runs on a render frame. */
    private final List<LogWrap> logWrapCache = new ArrayList<>();
    private boolean logWrapDirty = true;
    private String langAtLastRender = "";
    private int logWrapCachedWidth = -1;
    /** Total log content height computed from the cache (entry gaps included), used for scroll clamping and the scrollbar. */
    private int logContentHeight = 0;
    /** Usable text width W of the log area in the previous frame: the inner width of the log area (mkW - 4) minus 4 px of padding on each side minus 4 px for the scrollbar = mkW - 16;
     *  the same formula is used whether or not the scrollbar is shown, so the layout stays stable (the wrap width must never depend on the scrollbar appearing). */
    private int logTextWidth() {
        return Math.max(8, mkW - 16);
    }

    // ---------------- Button debounce ----------------
    /** Disable timestamps of the custom-drawn action buttons: rows are rebuilt often, so the timestamps live here keyed by button and are written back during layout. */
    private final Map<Integer, Long> exDebounceAt = new HashMap<>();

    // ---------------- Added: liquidation dialog / Trade page stock dropdown / Exchange right column
    /** Whether the clear-all-positions confirmation dialog is open (the same Modal mask machinery as the loan dialog). */
    private boolean liquidateConfirmOpen = false;
    /**
     * The dialog's own "Confirm / Cancel" buttons are
     * skipped in the widget layer (super.render) and drawn later, in the renderTopOverlays
     * top-most channel right after the mask and the plate - the same channel already proven to
     * cover all page content (dropdown, tooltips). It is true only between super.render and
     * drawWidgetRings inside renderFrame; clicks, hover and focus still go through the vanilla
     * widget dispatch (visible is never changed).
     */
    private boolean modalButtonsDeferred = false;
    /** Whether the stock dropdown of the Trade page is expanded (all three sub-pages share one open state and one selected stock). */
    private boolean pickerOpen = false;
    /** Inner scroll offset, in pixels, of the expanded dropdown list. */
    private int pickerScroll = 0;
    /** Rectangle currently used to draw the expanded dropdown panel (shared by rendering and hit testing; [0]=x1 [1]=y1 [2]=x2 [3]=y2). */
    private final int[] pickerPanelRect = new int[4];
    private boolean pickerPanelValid;
    /**
     * Scroll offset of the content inside a dialog. When the loan
     * confirmation content is taller than the visible part of the panel, the wheel scrolls the
     * dialog content itself while it is over the panel and is swallowed outside it, so it can
     * never leak through to the lists underneath.
     */
    private int modalScroll = 0;
    /** Mouse position in screen coordinates as of the last frame (so hover underneath is re-triggered immediately once a dialog closes). */
    private double lastScreenMouseX = -99999.0D;
    private double lastScreenMouseY = -99999.0D;
    /** Cursor of the "locate my position" cycle (the stock code located last time; reset when there is no position or the data changed). */
    private String locateCursorCode = null;
    /** Right column of the Exchange - the custom quantity text (the source used to rebuild the EditBox, kept across tab switches;
     *  part four: the search box and its keyword were deleted together with the search feature). */
    private String exQtyText = "0";
    private EditBox exQtyBox;

    // ---------------- Layout results ----------------
    private int gw, gh, gx, gy;
    private boolean stacked;
    private int midY, midH;
    private int exX, exW, exH, exTitleY, exRowTop, exRowH, exFooterY;
    /** Geometry of the two Exchange columns (x relative to the container): left list [exListL, exListR], right action column [exOpL, exOpR]. */
    private int exListL, exListR, exOpL, exOpR;
    /** Relative y of each row in the right column (part four: the search row is gone - detail block / quantity row / two button rows), shared by layout and rendering. */
    private int exDetailY, exQtyRowY, exOpBtnY, exOpInnerW, exOpBtnW;
    /** Whether the right column has enough space for the complete action area (when it does not, no input box is created and every action button is disabled). */
    private boolean exOpEnough;
    private int mkX, mkW, mkH;
    private int chartTop, chartH;
    /** Chart mode: false = short-term candles, true = server-saved trading-day candles. */
    private boolean dailyChart;
    /** Applies the server-authoritative initial mode exactly once; manual button changes must survive later syncs. */
    private boolean chartModeInitialized;
    private final int[] chartModeRect = new int[4];
    private boolean chartModeRectValid;
    private int splitTop, splitH, leftColW, rightColW, rightColOff;
    /** Offset and width of the right column of the Quotes page (the Buy/Sell button group), shared by the button widgets and the custom-drawn hints. */
    private int btnsColOff, btnsColW;
    private int loanOverflow;
    // Log tab
    private int logMaxScroll;
    /** The log content height minus the 8 px of vertical padding is below LOG_MIN_H (48): the whole tab only shows "Not enough space". */
    private boolean logTooSmall;
    private int btnsTop;
    /** Actual total height of the trading button area on the Quotes page (including the fee hint line at the bottom), computed by layoutMarket together with btnsTop. */
    private int btnsAreaH = TRADE_BTNS_H;
    private int barY, barH, barContentH, perRow, cardW;
    private int toastY;

    // ---------------- Drag and wheel coexisting
    /** Id of a scrollable region. Every region keeps its own scroll offset and drag state; sharing them is forbidden. */
    private static final int REGION_EX = 0;
    private static final int REGION_MK = 1;
    private static final int REGION_SHORT = 2;
    private static final int REGION_ORDER = 3;
    private static final int REGION_LIMIT = 4;
    private static final int REGION_LOG = 5;
    private static final int REGION_RANK = 6;
    private static final int REGION_COUNT = 7;
    /** Dragging covers every wheel-scrollable container (event bar / error hint / position details / loan / event overlay / stacked outer layer / Trade page outer layer). */
    private static final int REGION_EVENT = 7;
    private static final int REGION_TOAST = 8;
    private static final int REGION_DETAIL = 9;
    private static final int REGION_LOAN = 10;
    private static final int REGION_TIP = 11;
    private static final int REGION_MID = 12;
    private static final int REGION_SHORT_OUTER = 13;
    private static final int REGION_ORDER_OUTER = 14;
    private static final int REGION_LIMIT_OUTER = 15;
    /** The expanded stock dropdown panel of the Trade page - it reuses the same scrolling, dragging and mis-click guard logic as the lists. */
    private static final int REGION_PICKER = 16;
    private final DragState[] dragStates = new DragState[17];
    {
        for (int i = 0; i < dragStates.length; i++) {
            dragStates[i] = new DragState();
        }
    }
    /** Drag trigger threshold in px: the pointer has to move more than 3 px before it counts as a drag; within the threshold it is only "armed", which neither scrolls nor swallows clicks. */
    private static int DRAG_THRESHOLD_PX = 3;

    private static final class DragState {
        /** Armed state: mouseClicked (the press) only records the start point and leaves isDragging false; the drag decision happens in mouseDragged. */
        boolean armed;
        boolean isDragging;
        int dragStartY;
        int dragStartX;
        int dragStartOffset;
        /** Set once a drag ends: swallows the click that follows (the replay path) so a row or button is never selected by accident. */
        boolean suppressNextClick;
        /** Rectangle of the scrollable area for this frame, in baseline coordinates: [top, bottom]; -1 means it cannot be dragged this frame. */
        int rectTop = -99999;
        int rectBottom = -99999;
        /** Hit-areas inside the region where dragging is not allowed (buttons, clickable rows); mousePressed on one of them abandons the drag. */
        final java.util.List<int[]> hotZones = new ArrayList<>();
    }

    /** Whether anything is being dragged this frame (the render layer then suppresses hover highlighting and tooltips). */
    private boolean dragAny() {
        for (DragState d : dragStates) {
            if (d.isDragging) {
                return true;
            }
        }
        return false;
    }

    /** The region currently being dragged, or -1 for none. Hover highlighting and tooltips are blocked per region. */
    private int draggingRegion() {
        for (int i = 0; i < dragStates.length; i++) {
            if (dragStates[i].isDragging) {
                return i;
            }
        }
        return -1;
    }

    /** The region in either the "armed" or the "dragging" state - drag events are routed with this, which also covers movement inside the threshold. */
    private int armedRegion() {
        for (int i = 0; i < dragStates.length; i++) {
            if (dragStates[i].armed || dragStates[i].isDragging) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Clears every "armed / dragging" state. A press that started on
     * an underlying list before a dialog opened must not stay in effect, otherwise the drag
     * would scroll that list through the dialog mask.
     */
    private void cancelDragStates() {
        for (DragState d : dragStates) {
            d.armed = false;
            d.isDragging = false;
        }
    }

    /** Whether the given region is dragging (any global drag first has to clear the state of the other regions so they never interfere). */
    private boolean dragRegion(int region) {
        return region >= 0 && region < dragStates.length && dragStates[region].isDragging;
    }

    /** Reads and writes the scroll offset of a region (every region maps to its own dedicated field). */
    private int dragOffset(int region) {
        return switch (region) {
            case REGION_EX -> exScroll;
            case REGION_MK -> mkScroll;
            case REGION_SHORT -> shortsInnerScroll;
            case REGION_ORDER -> bookScroll;
            case REGION_LIMIT -> limitScroll;
            case REGION_LOG -> logScroll;
            case REGION_RANK -> rankScroll;
            case REGION_EVENT -> eventScroll;
            case REGION_TOAST -> errorToast.dragOffset();
            case REGION_DETAIL -> detailScroll;
            case REGION_LOAN -> loanScroll;
            case REGION_TIP -> eventTipScroll;
            case REGION_MID -> midScroll;
            case REGION_SHORT_OUTER -> shortScroll;
            case REGION_ORDER_OUTER -> orderOuterScroll;
            case REGION_LIMIT_OUTER -> limitOuterScroll;
            case REGION_PICKER -> pickerScroll;
            default -> 0;
        };
    }

    private void dragOffsetSet(int region, int v) {
        switch (region) {
            case REGION_EX -> {
                exScroll = v;
                cancelLinkedExAnim();       // The drag takes over, the linked animation steps aside
            }
            case REGION_MK -> {
                mkScroll = v;
                cancelLinkedMkAnim();
            }
            case REGION_SHORT -> shortsInnerScroll = v;
            case REGION_ORDER -> bookScroll = v;
            case REGION_LIMIT -> limitScroll = v;
            case REGION_LOG -> logScroll = v;
            case REGION_RANK -> rankScroll = v;
            case REGION_EVENT -> eventScroll = v;
            case REGION_TOAST -> errorToast.dragOffsetSet(v);
            case REGION_DETAIL -> detailScroll = v;
            case REGION_LOAN -> loanScroll = v;
            case REGION_TIP -> eventTipScroll = v;
            case REGION_MID -> midScroll = v;
            case REGION_SHORT_OUTER -> shortScroll = v;
            case REGION_ORDER_OUTER -> orderOuterScroll = v;
            case REGION_LIMIT_OUTER -> limitOuterScroll = v;
            case REGION_PICKER -> pickerScroll = v;
            default -> { }
        }
    }

    /** A single scroll upper bound -- scrolling is only allowed when content exceeds the viewport, and at the bottom there still remains a {@link #LIST_BOTTOM_GAP}px gap under the last row (content + 4 - view). */
    private static int scrollMax(int contentH, int viewH) {
        return contentH > viewH ? Math.max(0, contentH + LIST_BOTTOM_GAP - viewH) : 0;
    }

    /** Upper scroll bound of a region (= that region's max scroll, kept in sync with what its scrollbar renders). */
    private int dragMax(int region) {
        return switch (region) {
            case REGION_EX -> maxExScroll();
            case REGION_MK -> listMaxScroll();
            case REGION_SHORT -> shortsInnerMax(MarketClientState.INSTANCE);
            case REGION_ORDER -> tradeNestedInnerMax(orderLayout, TO_LIST, MarketClientState.INSTANCE.orderBook.size(), bookRowH());
            case REGION_LIMIT -> tradeNestedInnerMax(limitLayout, TO_LIST, MarketClientState.INSTANCE.limitOrders.size(), limitRowH());
            case REGION_LOG -> logMaxScroll;
            case REGION_RANK -> rankLayout.maxScrollOffset();
            case REGION_EVENT -> eventMaxScroll();
            case REGION_TOAST -> errorToast.dragMax();
            case REGION_DETAIL -> detailMaxScroll();
            case REGION_LOAN -> loanOverflow;
            case REGION_TIP -> Math.max(0, eventTipContentHeight - Math.max(1, eventTipPanelH - 12));
            case REGION_MID -> maxMidScroll();
            case REGION_SHORT_OUTER -> {
                layoutShortPage(MarketClientState.INSTANCE);
                yield shortLayout.maxScrollOffset();
            }
            case REGION_ORDER_OUTER -> {
                layoutOrderPage(MarketClientState.INSTANCE);
                yield pageLayout(false).maxScrollOffset();
            }
            case REGION_LIMIT_OUTER -> {
                layoutLimitPage(MarketClientState.INSTANCE);
                yield pageLayout(true).maxScrollOffset();
            }
            case REGION_PICKER -> pickerMaxScroll();
            default -> 0;
        };
    }

    /** Part five: the scroll ceiling of the dropdown panel - same rules as the lists (a region with no overflow never scrolls). */
    private int pickerMaxScroll() {
        if (!pickerOpen || !pickerPanelValid) {
            return 0;
        }
        int visH = Math.max(1, pickerPanelRect[3] - pickerPanelRect[1] - 2 * LIST_PAD);
        return scrollMax(n() * LIST_ROW, visH);
    }

    /**
     * MouseClicked (the press) only records the
     * start coordinates and arms the region (isDragging stays false, so it neither scrolls nor is
     * treated as a click yet); the real drag is decided in mouseDragged, and isDragging=true plus
     * scrolling only happen once the pointer has moved more than {@link #DRAG_THRESHOLD_PX}px.
     * A region that hits a hot-area (a button) or has no overflowing content is never armed, and
     * its clicks are dispatched exactly as before.
     */
    private boolean dragPress(int region, double mx, double my, int button, double[] rect, List<int[]> hot) {
        DragState d = dragStates[region];
        d.armed = false;
        d.isDragging = false;
        d.suppressNextClick = false;
        if (button != 0 || rect == null || rect.length < 4) {
            return false;
        }
        if (mx < rect[0] || mx >= rect[2] || my < rect[1] || my >= rect[3]) {
            return false;
        }
        if (dragMax(region) <= 0) {
            return false;   // the region has no overflowing content: no dragging starts and no click is swallowed
        }
        if (hot != null) {
            for (int[] z : hot) {
                if (z != null && z.length >= 4 && mx >= z[0] && mx < z[2] && my >= z[1] && my < z[3]) {
                    return false;   // buttons and clickable rows come first, never steal a click
                }
            }
        }
        // record the start point and enter the armed state: this press is consumed, click dispatch is deferred to mouseReleased
        // (in LWJGL3 mouseClicked *is* the press; "no accidental row selection after a drag" is handled by the suppress flag on release)
        d.armed = true;
        d.dragStartX = (int) Math.floor(mx);
        d.dragStartY = (int) Math.floor(my);
        d.dragStartOffset = dragOffset(region);
        return true;
    }

    /**
     * mouseDragged: starting from the armed state, the pointer has to travel past the threshold
     * (3 px) before it is promoted to a real drag that scrolls; inside the threshold the region
     * stays armed and the event is only consumed. A drag returns true throughout, so nothing
     * passes through to the widgets underneath.
     */
    private boolean dragMove(int region, double my) {
        DragState d = dragStates[region];
        if (!d.armed) {
            return false;
        }
        if (!d.isDragging && Math.abs((int) Math.floor(my) - d.dragStartY) <= DRAG_THRESHOLD_PX) {
            return true;        // small movement while armed: no scrolling, no pass-through; wait until it is promoted to a drag or released as a click
        }
        d.isDragging = true;
        int max = Math.max(0, dragMax(region));
        dragOffsetSet(region, clamp(d.dragStartOffset + (d.dragStartY - (int) Math.floor(my)), 0, max));
        return true;
    }

    /** mouseReleased: if a drag took place, set suppressNextClick (swallowing the replayed click) and reset isDragging. */
    private void dragRelease(int region, double my) {
        DragState d = dragStates[region];
        if (d.isDragging) {
            d.suppressNextClick = true;
        }
        d.armed = false;
        d.isDragging = false;
        d.dragStartY = 0;
        d.dragStartX = 0;
        d.dragStartOffset = 0;
    }

    /**
     * At the top of mouseClicked: as soon as any region still holds suppressNextClick, this click
     * is swallowed and the flag cleared. (1.20.1 has no synthesised click on release, so the flag
     * doubles as a cross-frame safety net: if a drag ended in the previous frame without clearing
     * it, the press is intercepted outright and no row gets selected by mistake.)
     */
    private boolean consumeDragClicks() {
        boolean consumed = false;
        for (DragState d : dragStates) {
            if (d.suppressNextClick) {
                d.suppressNextClick = false;
                consumed = true;
            }
        }
        return consumed;
    }
    /**
     * Before every render pass and at the start of every client tick, sync the theme colours and
     * the UI-feel values held in the config snapshot into the static constants above.
     * Single player = the local oremarket.toml; on a server = the copy the server pushes down
     * (SyncConfigPacket carries the whole palette).
     */
    private static void refreshTheme() {
        MarketSettings ms = MarketSettings.current();
        THEME_PANEL_ROOT = ms.uiColor(MarketSettings.UI_PANEL_ROOT);
        THEME_PANEL_SOFT = ms.uiColor(MarketSettings.UI_PANEL_SOFT);
        THEME_PANEL_ROW = ms.uiColor(MarketSettings.UI_PANEL_ROW);
        THEME_BORDER = ms.uiColor(MarketSettings.UI_BORDER);
        THEME_DARK = ms.uiColor(MarketSettings.UI_DARK);
        THEME_HIGHLIGHT = ms.uiColor(MarketSettings.UI_HIGHLIGHT);
        THEME_ACCENT = ms.uiColor(MarketSettings.UI_ACCENT);
        THEME_GOLD = ms.uiColor(MarketSettings.UI_GOLD);
        THEME_MUTED = ms.uiColor(MarketSettings.UI_MUTED);
        THEME_GRAY = ms.uiColor(MarketSettings.UI_GRAY);
        THEME_DIM = ms.uiColor(MarketSettings.UI_DIM);
        THEME_QUOTE_UP = ms.uiColor(MarketSettings.UI_QUOTE_UP);
        THEME_QUOTE_DOWN = ms.uiColor(MarketSettings.UI_QUOTE_DOWN);
        THEME_QUOTE_FLAT = ms.uiColor(MarketSettings.UI_QUOTE_FLAT);
        THEME_PNL_UP = ms.uiColor(MarketSettings.UI_PNL_UP);
        THEME_PNL_DOWN = ms.uiColor(MarketSettings.UI_PNL_DOWN);
        THEME_SELECTED = ms.uiColor(MarketSettings.UI_SELECTED);
        THEME_HOVER = ms.uiColor(MarketSettings.UI_HOVER);
        THEME_BLUE = ms.uiColor(MarketSettings.UI_BLUE);
        THEME_BUY = ms.uiColor(MarketSettings.UI_BUY);
        THEME_TOAST_BG = ms.uiColor(MarketSettings.UI_TOAST_BG);
        THEME_TOAST_FG = ms.uiColor(MarketSettings.UI_TOAST_FG);
        THEME_KGRID = ms.uiColor(MarketSettings.UI_KGRID);
        THEME_CHART_BG = ms.uiColor(MarketSettings.UI_CHART_BG);
        THEME_LETTERBOX = ms.uiColor(MarketSettings.UI_LETTERBOX);
        PANEL_ROOT = THEME_PANEL_ROOT;
        PANEL_SOFT = THEME_PANEL_SOFT;
        PANEL_ROW = THEME_PANEL_ROW;
        BORDER_MAIN = THEME_BORDER;
        DARK_BG = THEME_DARK;
        HIGHLIGHT_IN = THEME_HIGHLIGHT;
        ACCENT_BLUE = THEME_ACCENT;
        TEXT_MUTED = THEME_MUTED;
        DIM_TX = THEME_DIM;
        TOAST_BG = THEME_TOAST_BG;
        TOAST_TEXT = THEME_TOAST_FG;
        CHART_BG = THEME_CHART_BG;
        UP = THEME_QUOTE_UP;
        DOWN = THEME_QUOTE_DOWN;
        FLAT = THEME_QUOTE_FLAT;
        GOLD = THEME_GOLD;
        DOT_UNLOCKED = THEME_ACCENT;
        DOT_LOCKED = THEME_GRAY;
        LOCKED_TX = THEME_DIM;
        SUSPEND_BG = THEME_HIGHLIGHT;
        SUSPEND_TX = THEME_MUTED;
        TAB_SELECTED_BG = THEME_BLUE;
        TAB_NORMAL_BG = THEME_DARK;
        TAB_HOVER_BG = THEME_HOVER;
        LIST_SEL_BG = THEME_SELECTED;
        LIST_HOVER_BG = THEME_HOVER;
        PNL_UP = THEME_PNL_UP;
        PNL_DOWN = THEME_PNL_DOWN;
        PNL_FLAT = THEME_MUTED;
        K_BODY_UP = PNL_UP;
        K_BODY_DOWN = PNL_DOWN;
        K_BODY_FLAT = TEXT_WHITE;
        K_WICK_FLAT = THEME_GRAY;
        K_STROKE = PANEL_ROOT;
        K_GRID = THEME_KGRID;
        K_LABEL = THEME_GRAY;
        LIST_SB_TRACK = DARK_BG;
        LIST_SB_THUMB = DIM_TX;
        GRAY88 = THEME_GRAY;
        ICON_PLACEHOLDER = DIM_TX;
        EX_DEP_BG = THEME_BUY;
        EX_WDR_BG = TAB_SELECTED_BG;
        EX_SEP_COLOR = PANEL_ROW;
        EX_ROW_HOVER_BG = LIST_HOVER_BG;
        EX_LOCKED_TX = DIM_TX;
        EX_RATE_TX = TEXT_MUTED;
        BTN_BASE_BG = PANEL_ROW;
        BTN_DISABLED_BG = DARK_BG;
        BTN_DISABLED_TX = GRAY88;
        FOCUS_RING = ACCENT_BLUE;
        LETTERBOX = THEME_LETTERBOX;
        TEXT_WHITE = ms.uiColor(MarketSettings.UI_TEXT_WHITE);
        TEXT_SOFT = ms.uiColor(MarketSettings.UI_TEXT_SOFT);
        TIP_TEXT = ms.uiColor(MarketSettings.UI_TIP_TEXT);
        BODY_TEXT = ms.uiColor(MarketSettings.UI_BODY_TEXT);
        LOG_TEXT = BODY_TEXT;
        GOLD_TEXT = ms.uiColor(MarketSettings.UI_GOLD_TEXT);
        HINT_GRAY = ms.uiColor(MarketSettings.UI_HINT_GRAY);
        CHIP_BLUE = ms.uiColor(MarketSettings.UI_CHIP_BLUE);
        ACCENT_ORANGE = ms.uiColor(MarketSettings.UI_ACCENT_ORANGE);
        COIN_DARK = ms.uiColor(MarketSettings.UI_COIN_DARK);
        COIN_LIGHT = ms.uiColor(MarketSettings.UI_COIN_LIGHT);
        TOAST_RED = ms.uiColor(MarketSettings.UI_WARNING);
        K_WICK_UP = ms.uiColor(MarketSettings.UI_WICK_UP);
        K_WICK_DOWN = ms.uiColor(MarketSettings.UI_WICK_DOWN);
        BTN_HOVER_BG = ms.uiColor(MarketSettings.UI_BUTTON_HOVER);
        EX_DEP_HOVER = ms.uiColor(MarketSettings.UI_DEPOSIT_HOVER);
        EX_WDR_HOVER = ms.uiColor(MarketSettings.UI_WITHDRAW_HOVER);
        SCROLL_STEP = ms.uiScrollStep;
        SCROLL_STEP_SIDE = Math.max(2, ms.uiScrollStep * 3 / 5);
        SCROLL_STEP_TIP = ms.uiScrollStepFine;
        DRAG_THRESHOLD_PX = ms.uiDragThresholdPx;
        SCROLL_LERP = ms.uiSmoothScroll ? Math.max(0.01F, ms.uiScrollLerpPercent / 100.0F) : 1.0F;
        CLICK_DEBOUNCE_MS = Math.max(0L, ms.uiClickDebounceMs);
        CARD_ANIM_MS = Math.max(1L, ms.uiCardAnimMs);
        ToastNotification.applyTheme(ms);
    }

    /** Every Exchange row, including the drawRect/hitRect of its six buttons and the rate line, computed once during layout. */
    private final List<ExchangeRow> exRows = new ArrayList<>();
    private final List<CardGeom> cards = new ArrayList<>();
    private Component pendingTooltip;

    /** Item eight: the custom-drawn tooltip panel for event cards, which stops the vanilla tooltip from running out of bounds on long English sentences. */
    private static final class EventTipLine {
        final String text;
        final int color;
        EventTipLine(String text, int color) {
            this.text = text == null ? "" : text;
            this.color = color;
        }
    }

    private MarketEventView eventTipView;
    private boolean eventTipOnCard;
    private int eventTipScroll;
    private String eventTipSignature = "";
    private int eventTipWrapWidth = -1;
    private List<EventTipLine> eventTipLines = List.of();
    private int eventTipContentHeight;
    private int eventTipPanelX;
    private int eventTipPanelY;
    private int eventTipPanelW;
    private int eventTipPanelH;

    /** The small adaptive box below the centre that every interactive button reports errors through (one at a time, a newer hint replaces the older). */
    private final ToastNotification errorToast = new ToastNotification();
    /** Serial number of the hint shown last (ClientEvents.toastSerial); a change means a new hint, so the progress bar restarts and the text is wrapped again. */
    private int lastToastSerial = -1;
    private String builtKey = "";
    /** Row index of the last hovered line in the left column of the Quotes page (-1 for none), used by the locked-ore tooltip. */
    private int hoverListRow = -1;

    // ---------------- Unified vertical layout ----------------
    private final VerticalLayout shortLayout = new VerticalLayout();
    private final VerticalLayout orderLayout = new VerticalLayout();
    private final VerticalLayout limitLayout = new VerticalLayout();
    private final VerticalLayout rankLayout = new VerticalLayout();
    private final VerticalLayout logLayout = new VerticalLayout();
    /** The action buttons of the Exchange right column (search / store / take), computed once during layout. */
    private final List<ExOpButton> exOps = new ArrayList<>();
    /**
     * De-duplication signature for the average-cost display log ("stockCode=4-decimal value"): the
     * data source is always the value synchronised from the server's SavedData, so nothing is
     * printed while the signature stays the same and the log cannot flood every frame.
     * See {@link #logCostDisplay}.
     */
    private String costDisplaySignature = "";

    public MarketScreen() {
        super(Component.literal(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0186"))));
        // Every time the UI opens, the session cache is cleared and RequestConfigPacket and
        // RequestMarketSnapshotPacket are **sent again** (together with the watcher-registered player packet); reusing the cache of the previous opening is forbidden.
        MarketClientState st = MarketClientState.INSTANCE;
        st.clearMarketCache();
        st.hasPlayerState = false;
        st.hasData = false;
        st.redrawPending = false;
        lastRedrawTime = 0L;
        pendingRetryCooldown = 0L;
        selectedCode = null;
        selected = -1;
        st.snapshotRequestedTick = ClientEvents.clientTick();
        st.snapshotStatus = MarketClientState.SnapshotStatus.LOADING;
        ClientConfigCache.request();
        OreMarketNetwork.CHANNEL.sendToServer(new C2SRequestSnapshotPacket());
        OreMarketNetwork.CHANNEL.sendToServer(new RequestMarketSnapshotPacket(OreMarketNetwork.PROTOCOL));
    }

    /** How an Exchange row button obtains the code it sends (row index -> code of the current view). */
    private static String st0code(int idx) {
        return MarketClientState.INSTANCE.codeAt(idx);
    }

    /** Selected row index -> code, or null when out of range (the server re-validates by code before matching anyway). */
    private String selectedCodeSafe() {
        return selectedCode != null && selected >= 0 ? selectedCode : null;
    }

    /** The selected Stock object (derived from selectedCode); null when nothing is selected. */
    private Stock selectedStock() {
        MarketClientState st = MarketClientState.INSTANCE;
        return selected >= 0 && selected < st.stocks.length ? st.stocks[selected] : null;
    }

    /** Set the selection and play the selection sound (clicks on a list row or an Exchange row go through here); code and index are maintained together. */
    private void setSelected(int idx) {
        setSelected(idx, true);
    }

    /**
     * Set the selection, optionally playing the selection sound.
     * "locate my position" has to pass playSound=false - the button's own click
     * sound is emitted by the vanilla AbstractWidget.mouseClicked -> playDownSound, and playing the
     * selection sound on top of it (the very same UI_BUTTON_CLICK, only a different pitch) would
     * stack two sounds; locating should ring the button sound alone.
     */
    private void setSelected(int idx, boolean playSound) {
        MarketClientState st = MarketClientState.INSTANCE;
        if (idx < 0 || idx >= st.viewSize()) {
            return;
        }
        boolean changed = idx != selected;
        selectedCode = st.codeAt(idx);
        selected = idx;
        // Clicking a row to change the selection plays one short UI sound (only when the
        // selection really changed; clicking the same row again stays silent, and the init / revalidation paths never reach this code).
        if (changed) {
            if (playSound) {
                playSelectSound();
            }
            // The selection changed -> the list on the other side automatically moves to the same ore / stock (smooth scroll animation).
            syncLinkedScroll();
        }
    }

    // ------------------------------------------------------------ two-way linked smooth scrolling

    /**
     * The selection changed -> work out, for each of the two lists, the scroll offset that brings
     * the target row fully into the viewport. The isSyncingScroll lock makes this method write
     * animation targets only and never change the selection, and any re-entry inside the lock is
     * ignored outright, which kills the "link -> selection -> link" recursion at its source.
     */
    private void syncLinkedScroll() {
        if (isSyncingScroll) {
            return;
        }
        isSyncingScroll = true;
        try {
            int exTarget = targetExScroll(selected);
            if (exTarget >= 0 && exTarget != exScroll) {
                exScrollTarget = exTarget;
                exAnimOffset = exScroll;             // animate from the current position
            } else {
                exScrollTarget = -1;                 // already fully visible: no animation, and the user's own scrolling is not hijacked
            }
            int mkTarget = tab == TAB_MARKET ? targetMkScroll(selected) : -1;
            if (mkTarget >= 0 && mkTarget != mkScroll) {
                mkScrollTarget = mkTarget;
                mkAnimOffset = mkScroll;
            } else {
                mkScrollTarget = -1;
            }
        } finally {
            isSyncingScroll = false;
        }
    }

    /** Target offset for the left Exchange column: row i fully inside the content window, top-aligned when the row is taller than the window; clamped to 0..max. */
    private int targetExScroll(int i) {
        if (i < 0 || i >= n()) {
            return -1;
        }
        int d = i * (EX_ROW_H + EX_ROW_GAP);
        int h = EX_ROW_H;
        int vh = Math.max(1, exVisibleH());
        int cur = exScroll;
        int target = cur;
        if (d < cur) {
            target = d;
        } else if (d + h > cur + vh) {
            target = d + h - vh;
        }
        return clamp(target, 0, Math.max(0, maxExScroll()));
    }

    /** Target offset for the stock list column: uniform 16 px rows, row i fully inside the list window; clamped to 0..max. */
    private int targetMkScroll(int i) {
        if (tab != TAB_MARKET || i < 0 || i >= n()) {
            return -1;
        }
        int vh = Math.max(1, stockAvailHeight());
        int d = i * LIST_ROW;
        int cur = mkScroll;
        int target = cur;
        if (d < cur) {
            target = d;
        } else if (d + LIST_ROW > cur + vh) {
            target = d + LIST_ROW - vh;
        }
        return clamp(target, 0, Math.max(0, listMaxScroll()));
    }

    /**
     * The render loop advances the linked animation once per frame, converting the lerp factor into
     * the real frame time and snapping into place once less than 0.6 px is left. It has to run
     * before computeLayout, since the row and button rectangles use the newest offset.
     * The factor is calibrated for 60 FPS: a high refresh rate does not scroll too fast and a low
     * frame rate does not turn it into slow motion.
     */
    private void tickLinkedScroll() {
        float alpha = scrollLerpAlpha();
        if (exScrollTarget >= 0) {
            if (exAnimOffset < 0.0D) {
                exAnimOffset = exScroll;
            }
            exAnimOffset += (exScrollTarget - exAnimOffset) * alpha;
            boolean done = Math.abs(exScrollTarget - exAnimOffset) <= 0.6D;
            if (done) {
                exAnimOffset = exScrollTarget;
            }
            exScroll = clamp((int) Math.round(exAnimOffset), 0, Math.max(0, maxExScroll()));
            if (done || exScroll == exScrollTarget) {
                exScrollTarget = -1;
                exAnimOffset = -1.0D;
            }
        } else {
            exAnimOffset = -1.0D;
        }
        if (mkScrollTarget >= 0 && tab == TAB_MARKET) {
            if (mkAnimOffset < 0.0D) {
                mkAnimOffset = mkScroll;
            }
            mkAnimOffset += (mkScrollTarget - mkAnimOffset) * alpha;
            boolean done = Math.abs(mkScrollTarget - mkAnimOffset) <= 0.6D;
            if (done) {
                mkAnimOffset = mkScrollTarget;
            }
            mkScroll = clamp((int) Math.round(mkAnimOffset), 0, Math.max(0, listMaxScroll()));
            if (done || mkScroll == mkScrollTarget) {
                mkScrollTarget = -1;
                mkAnimOffset = -1.0D;
            }
        } else {
            mkScrollTarget = -1;
            mkAnimOffset = -1.0D;
        }
    }

    /** Turns "15% per frame" into a factor of the real frame time, catching up by at most 250 ms when a frame stalls. */
    private float scrollLerpAlpha() {
        float lerp = SCROLL_LERP;
        if (lerp >= 0.999F) {
            return 1.0F;
        }
        double seconds = 1.0 / 60.0;
        if (this.minecraft != null) {
            long ns = this.minecraft.getFrameTimeNs();
            if (ns > 0L) {
                seconds = Math.min(0.25D, ns / 1_000_000_000.0D);
            }
        }
        return (float) (1.0D - Math.pow(1.0D - lerp, seconds * 60.0D));
    }

    /** A manual scroll (wheel or drag) takes over at once: an in-flight linked animation is cancelled so the user never fights the scrollbar. */
    private void cancelLinkedExAnim() {
        exScrollTarget = -1;
        exAnimOffset = -1.0D;
    }

    private void cancelLinkedMkAnim() {
        mkScrollTarget = -1;
        mkAnimOffset = -1.0D;
    }

    /** Feedback sound for a selection change - the vanilla button click sound, quiet and a little higher in pitch, played once. */
    private void playSelectSound() {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.2F));
        }
    }

    /** The background of the selected Exchange row comes from the config (admins may customise it); with no value configured it falls back to 0xFF4A5A7A. */
    private int exchangeSelBg() {
        MarketSettings ms = MarketSettings.client();
        return ms == null ? LIST_SEL_BG : ms.exchangeRowSelectedColor;
    }

    /**
     * Revalidate selectedCode: call it whenever the registry or the
     * quotes changed. Invalid or uninitialised -> fall back to the first stock in the list;
     * empty list -> -1, which displays "Select a stock".
     */
    private void syncSelected() {
        MarketClientState st = MarketClientState.INSTANCE;
        int before = selected;
        int idx = st.indexOfCode(selectedCode);
        if (idx >= 0) {
            selected = idx;
        } else if (st.viewSize() > 0) {
            selectedCode = st.codes[0];
            selected = 0;
        } else {
            selectedCode = null;
            selected = -1;
        }
        // When a registry swap shifts the row indices, both lists relocate (same state lock, so no recursion).
        if (selected != before) {
            syncLinkedScroll();
        }
    }

    /** Values that depend on the config may only be rendered once all three caches - config, stock registry and player state - have arrived. */
    private boolean configReady() {
        MarketClientState st = MarketClientState.INSTANCE;
        return st.hasData
                && st.hasPlayerState
                && ClientConfigCache.ready()
                && ClientConfigCache.registryReady();
    }

    /** Whether the quote (price / candlestick) cache snapshot has arrived. */
    private boolean marketReady() {
        return MarketClientState.INSTANCE.snapshotStatus == MarketClientState.SnapshotStatus.READY;
    }

    /** Placeholder text while not ready: a failed config or a failed quote shows its own "Click to retry", anything else shows "Loading". */
    private String loadingText() {
        if (ClientConfigCache.status() == ClientConfigCache.Status.FAILED) {
            return cn.oremarket.common.RawText.t("oremarket.text.0451");
        }
        if (MarketClientState.INSTANCE.snapshotStatus == MarketClientState.SnapshotStatus.FAILED) {
            return cn.oremarket.common.RawText.t("oremarket.text.0450");
        }
        return cn.oremarket.common.RawText.t("oremarket.text.0096");
    }

    /**
     * The single "Click to retry" entry point after a timeout: a click less than 2
     * seconds after the previous one is ignored outright (pendingRetryCooldown); when it does take
     * effect, RequestConfigPacket and RequestMarketSnapshotPacket are both resent and the two
     * states go back to LOADING.
     */
    private void retryLoads() {
        long now = System.currentTimeMillis();
        if (pendingRetryCooldown > 0 && now < pendingRetryCooldown) {
            return;                       // cooling down: do not resend the packets
        }
        pendingRetryCooldown = now + 2000L;
        MarketClientState st = MarketClientState.INSTANCE;
        st.snapshotStatus = MarketClientState.SnapshotStatus.LOADING;
        st.snapshotRequestedTick = ClientEvents.clientTick();
        ClientConfigCache.retry();
        OreMarketNetwork.CHANNEL.sendToServer(new RequestMarketSnapshotPacket(OreMarketNetwork.PROTOCOL));
    }

    // ================================================================ Lifecycle

    @Override
    protected void init() {
        // Guard one: while the font is not ready this frame builds no widgets and does no layout (layout depends on font.width); render retries on the next frame
        if (!fontReady()) {
            builtKey = "";
            return;
        }
        MarketClientState st = MarketClientState.INSTANCE;
        applyConfiguredChartMode();
        updateUiTransform();
        computeLayout(st);
        buildWidgets(st);
        builtKey = layoutKey();
        // As soon as the screen opens, pop the forced events that were parked while the player was offline.
        ClientEvents.flushOfflineNotes(errorToast, font, Math.max(60, gw - 40));
    }

    /**
     * Applies the synced display preference only after the client has a complete config snapshot.
     * It is intentionally one-shot: a hot-reload packet or a later server push must not undo a
     * player's explicit short/daily button choice during the same screen session.
     */
    private void applyConfiguredChartMode() {
        if (chartModeInitialized) {
            return;
        }
        MarketSettings settings = MarketSettings.client();
        if (settings == null) {
            return;
        }
        dailyChart = !settings.klineDefaultShort;
        chartModeInitialized = true;
    }

    @Override
    public void resize(Minecraft mc, int newWidth, int newHeight) {
        super.resize(mc, newWidth, newHeight);
        rebuildWidgets();
    }

    @Override
    public void onClose() {
        // This method is also called at the instant of a disconnect or of leaving a world, when the network layer
        // may already be gone: failing to send the close notification to the server costs no correctness
        // (the server clears WATCHERS on the logout event too), but it must never throw and wreck the disconnect flow - swallow it and carry on with the local cache cleanup.
        try {
            OreMarketNetwork.CHANNEL.sendToServer(new C2SCloseUiPacket());
        } catch (RuntimeException ignored) {
            // connection already down: stay silent
        }
            // event bar animation cache: every piece of cross-screen state is cleared (memory-leak check, item five)
        clearEventAnimCaches();
        // Closing the screen clears the config cache, the player state cache and the quote cache and resets the redraw timer / retry cooldown /
        // selectedCode / race timestamps - the next opening has to run the request-reply cycle again, and reusing data from an earlier opening is forbidden.
        MarketClientState st = MarketClientState.INSTANCE;
        st.clearOnClose();
        ClientConfigCache.clear();
        // neither the icon cache nor the event card layout cache survives a reopen (the stock list may have been swapped wholesale)
        ItemIconCache.clear();
        // quote payloads parked while the registry was not ready are not kept across openings either
        ClientPlay.clearPendingMarketSnapshot();
        selectedCode = null;
        selected = -1;
        lastRedrawTime = 0L;
        pendingRetryCooldown = 0L;
        // Closing the screen must destroy every Modal and every dropdown, so that a leftover mask cannot make the next opening unclickable.
        loanConfirmOpen = false;
        liquidateConfirmOpen = false;
        modalScroll = 0;
        pickerOpen = false;
        pickerPanelValid = false;
        locateCursorCode = null;
        exQtyText = "0";
        // the hint box is not kept across openings (the next opening runs the request-reply cycle again and never shows an old error)
        errorToast.clear();
        lastToastSerial = -1;
        ClientEvents.dropPendingToasts();
        clearEventTooltip();
        // Part one, item six (memory-leak check): closing the screen releases every drag state and every name layout cache,
        // so a "half dragging" state cannot survive an reopen (a stuck isDragging / armed would permanently block hover and clicks).
        for (DragState d : dragStates) {
            d.isDragging = false;
            d.armed = false;
            d.dragStartY = 0;
            d.dragStartX = 0;
            d.dragStartOffset = 0;
            d.suppressNextClick = false;
        }
        exOps.clear();
        headerSig = "";
        super.onClose();
    }

    /**
     * Item seven: called every tick by ClientTickEvent (ClientEvents). It walks all buttons and
     * clears a disable timestamp, making the button usable again, once now - timestamp >= 200 ms.
     * No sleeping, no blocking, no waiting for a server reply - however quick the reply is, the
     * recovery follows the fixed window.
     */
    /**
     * The merged redraw: ClientEvents calls this every tick. Packet handling only
     * sets redrawPending; the derived arrays are rebuilt and selectedCode revalidated when that
     * flag is set and at least 50 ms have passed since the last redraw. lastRedrawTime is an
     * instance field of the screen, 0 when the screen opens, so the first packet redraws at once.
     */
    public void onClientTickPulse() {
        refreshTheme();
        tickDebounce();
        MarketClientState st = MarketClientState.INSTANCE;
        if (!st.redrawPending) {
            return;
        }
        long now = System.currentTimeMillis();
        // Requirement two, item nineteen: the merge window went from 250ms to 50ms, because the event card animation needs a snappier redraw;
        // a redraw only rebuilds derived arrays, so at a 50ms window it happens at most 20 times a second, which measurement shows is no frame pressure.
        if (lastRedrawTime != 0L && now - lastRedrawTime < 50L) {
            return;                       // merge window: only record the flag, do not rebuild the arrays
        }
        st.redrawPending = false;
        lastRedrawTime = now;
        st.rebuildView();
        syncSelected();
        syncShortSelection(st);
        // the order book was just refreshed: the selected entry may already have been filled or cancelled, so drop the selection
        syncOrderSelection(st);
    }

    public void tickDebounce() {
        long now = System.currentTimeMillis();
        for (var w : this.renderables) {
            if (w instanceof MarketButton mb && mb.disabledAtMs > 0
                    && now - mb.disabledAtMs >= CLICK_DEBOUNCE_MS) {
                mb.disabledAtMs = 0;
            }
        }
        exDebounceAt.values().removeIf(t -> t != null && now - t >= CLICK_DEBOUNCE_MS);
        for (ExOpButton b : exOps) {
            if (b.disabledAtMs > 0 && now - b.disabledAtMs >= CLICK_DEBOUNCE_MS) {
                b.disabledAtMs = 0;
            }
        }
    }

    // ================================================================ Layout

    /**
     * Invalidate every text cache that was computed from raw source text when the language changes
     *
     * <p>The wrap caches, the note-block lines, the log line heights and the top-bar signature all
     * store strings that have <b>already been translated</b>. If on some frame the overlay mapping
     * table has not been built yet (first frame, resource reload, language switch), the raw Chinese
     * of that moment is cached as if it were the translation; from then on only the <b>raw source
     * signature</b> is compared, so half a Chinese sentence is fed back into the translation layer
     * and one registered copy string ends up split in two - I18n leaks such as "shares bought today
     * can be sold the next day" arrive exactly along this path. Whenever the translation layer
     * rebuilds its mapping table it bumps a generation counter ({@link L#languageGeneration}), so
     * comparing it here and wiping every text cache on a change lets the next frame recompute with
     * the real translation.</p>
     */
    private int seenLanguageGeneration = -1;

    private void syncLanguageCaches() {
        int gen = L.languageGeneration();
        if (gen == seenLanguageGeneration) {
            return;
        }
        boolean first = seenLanguageGeneration < 0;
        seenLanguageGeneration = gen;
        if (first) {
            return;
        }
        orderNoteWrap.invalidate();
        limitBuyWrap.invalidate();
        limitSellWrap.invalidate();
        limitHintWrap.invalidate();
        // The wrap cache added by the loan page is invalidated on a language switch too
        for (WrapCache wc : loanEstWraps) {
            wc.invalidate();
        }
        loanNoteWrap.invalidate();
        loanWarnWrap.invalidate();
        // The wrap cache of the liquidation confirmation dialog is invalidated on a language switch too
        liquidateBodyWrap.invalidate();
        liquidateWarnWrap.invalidate();
        introCacheW = -1;
        introCacheCfg = null;
        introCache.clear();
        logWrapDirty = true;
        headerSig = "";
        costDisplaySignature = "";
    }

    /**
     * A line may enter the wrap cache only once its translation has really been
     * resolved. Lines computed while the overlay mapping table was not ready are never cached and
     * are recomputed on the next frame, rather than freezing half a Chinese sentence into the UI
     * (where it would then be handed back to the translation layer as if it were copy of its own).
     */
    private boolean cacheableTranslation(String translated) {
        return L.overlayActive() || !L.hasChinese(translated);
    }

    private void computeLayout(MarketClientState st) {
        gx = 4;
        gy = 4;
        gw = Math.max(240, Math.min(640, width - 8));
        gh = height - 8;

        // The top bar first decides between one and two lines (both the event bar and the middle area height depend on headerH)
        layoutHeader(st);
        int headerHNow = fontReady() ? headerH : HEADER_H_1;

        // event bar
        layoutEventBar(st, gw);
        int midMin = 150;
        int maxBar = Math.max(EMPTY_BAR_H, gh - headerHNow - TOAST_H - midMin - 6);
        if (barH > maxBar) {
            barH = Math.min(maxBar, Math.max(EMPTY_BAR_H, barContentH));
        }
        barY = gh - barH;
        toastY = barY - TOAST_H - 1;
        midY = headerHNow + 2;
        // The container bottom sits right against the event bar (the standard 4 px gap) - the hint box is an overlay
        // (drawn last by renderTopOverlays) and is no longer reserved out of the available height, which is what lets the three columns stretch to the bottom.
        midH = Math.max(120, barY - 4 - midY);

        // The permanently docked Exchange on the left is removed - it now lives in the "Exchange" tab.
        // the market container takes the whole width of the middle area; the geometry of the exchange panel is the content area below the container tab bar
        // (exAbsY() returns its absolute top edge), and the row rectangles / clipping / hit areas / wheel keep reading that same geometry.
        stacked = false;
        mkX = 0;
        mkW = gw;
        mkH = midH;
        exX = mkX + 2;
        exW = mkW - 4;
        exH = Math.max(48, mkH - TAB_H);
        midScroll = 0;

        layoutExchange();
        layoutMarket(st);
        updateChartModeRect(st);
        // Every sub-page content area is recomputed each frame by VerticalLayout: the event bar height changes -> containerH changes
        // -> the total content height / maxScrollOffset / scrollOffset clamp all update along with it.
        layoutShortPage(st);
        layoutOrderPage(st);
        layoutLimitPage(st);
        layoutRankPage(st);
        layoutLogPage(st);

        exScroll = clamp(exScroll, 0, maxExScroll());
        // A tab's scroll amount is clamped only while that tab is showing: switching to another tab never zeroes it and never forces it back,
        // so switching back keeps the old position (Quotes = left column / right column, Loans = the loan page, Log = the log area).
        if (tab == TAB_MARKET) {
            mkScroll = clamp(mkScroll, 0, listMaxScroll());
            detailScroll = clamp(detailScroll, 0, detailMaxScroll());
        } else if (tab == TAB_LOAN) {
            loanScroll = clamp(loanScroll, 0, loanOverflow);
        } else {
            logScroll = clamp(logScroll, 0, logMaxScroll);
        }
        // Part one, item seven: every region's scroll offset is re-clamped each frame, so a content height change (a new limit order, more log lines,
        // a name that wraps and changes the row height) takes effect at once and an offset can never stay out of bounds.
        int exMax = Math.max(0, maxExScroll());
        if (exScroll > exMax) {
            exScroll = exMax;
        }
        mkScroll = clamp(mkScroll, 0, Math.max(0, listMaxScroll()));
        shortsInnerScroll = clamp(shortsInnerScroll, 0, Math.max(0, shortsInnerMax(st)));
        bookScroll = clamp(bookScroll, 0, Math.max(0,
                tradeNestedInnerMax(orderLayout, TO_LIST, st.orderBook.size(), bookRowH())));
        limitScroll = clamp(limitScroll, 0, Math.max(0,
                tradeNestedInnerMax(limitLayout, TO_LIST, st.limitOrders.size(), limitRowH())));
        rankScroll = clamp(rankScroll, 0, Math.max(0, rankLayout.maxScrollOffset() + LIST_PAD));
        shortScroll = clamp(shortScroll, 0, Math.max(0, shortLayout.maxScrollOffset()));
        orderOuterScroll = clamp(orderOuterScroll, 0, Math.max(0, orderLayout.maxScrollOffset()));
        limitOuterScroll = clamp(limitOuterScroll, 0, Math.max(0, limitLayout.maxScrollOffset()));
        logScroll = clamp(logScroll, 0, Math.max(0, logMaxScroll));
        // The drawRect / hitRect of rows and buttons are computed once during layout (with the current scroll offset),
        // and rendering and the mouseClicked hit test read the very same rectangles - recomputing coordinates at click time is forbidden.
        layoutExchangeRows(st);
    }

    /**
     * The Exchange was rebuilt from scratch as a left and a right column.
     * Left column = the scrollable list (ore icon / name / inventory count / exchange rate, a fixed
     *   20 px row with a 2 px gap, pixel-level scissor plus rowIntersects, so a half row is drawn and
     *   then clipped away);
     * right column = the fixed action area (search box plus search button on top, the details of the
     *   selected ore in the middle, and the quantity input plus store / take at the bottom).
     * The row height no longer depends on name wrapping: a name is always a single line, scaled down
     *   in steps (no ellipsis and no hiding), which turns the total content height into an O(1)
     *   formula cached per stock count (requirement five: recomputing it every frame is forbidden).
     */
    private void layoutExchange() {
        exTitleY = 2;
        exFooterY = exH - EX_BOTTOM_PAD;
        exRowTop = exTitleY + 12;
        exRowH = EX_ROW_H;
        // two-column split: 4 px of padding left and right, 4 px between the columns; the right column baseline is 230, a narrow container shrinks it to 38% with a floor of 150.
        int innerW = Math.max(0, exW - 8);
        int opW = Math.min(230, Math.max(150, innerW * 38 / 100));
        if (innerW < 320) {
            opW = Math.max(0, innerW - 130);                  // extremely narrow: compress the right column, keep a floor of 130 for the left one
        }
        int listW = Math.max(60, innerW - opW - 4);
        exListL = 4;
        exListR = exListL + listW;
        exOpL = exListR + 4;
        exOpR = Math.min(exW - 4, exOpL + opW);
        int opInner = Math.max(0, exOpR - exOpL - 8);
        exOpInnerW = opInner;
        int ox = exOpL + 4;                                    // left edge of the right column content (relative to the container)
        // row y (relative to the container top) - part four: the search row and the search button were removed,
        // and the space went to the detail panel (56 -> 82); what follows is detail block -> quantity row -> store/take row -> store-all/take-all row.
        exDetailY = exRowTop + 2;
        exQtyRowY = exDetailY + 82 + 8;
        exOpBtnY = exQtyRowY + 20 + 6;
        // the two action buttons share the width evenly; the second row (store all / take all) is as wide as the first, with a 4 px gap between them.
        exOpBtnW = Math.max(40, (opInner - 4) / 2);
        exOpEnough = opInner >= 96 && exFooterY - exRowTop >= 82 + 8 + 20 + 6 + 20 + 4 + 20 + 6;
        // the absolute baseline coordinates of the right column widgets are read from this same group of fields by both drawExchange and buildExchangeWidgets.
    }

    /** Absolute rectangle of the quantity input in the right column: it takes the inner width of the action column minus two input slots (the space on the right is left for the note and carries no hit-area). */
    private int[] exQtyBoxRect() {
        return new int[]{gx + exX + exOpL + 4, exAbsY() + exQtyRowY, exOpInnerW, 18};
    }

    /** Visible height of the Exchange content area = content bottom - content top (the content area = container height - title band - 6 px of bottom padding). */
    private int exVisibleH() {
        return Math.max(0, exFooterY - exRowTop - 2 * LIST_PAD);   // Container height - padTop - padBottom
    }

    /**
     * Upper scroll bound of the left list = max(total content height - visible
     * height, 0). The content height comes from exRowsH(), an O(1) formula over fixed-height rows
     * cached per stock count (requirement five, item four: recompute only when the data source
     * changes; a render frame merely reads the variable).
     */
    private int maxExScroll() {
        int visH = exVisibleH();
        // One shared scrollMax (the last row keeps a 4 px gap when scrolled to the bottom)
        return scrollMax(exRowsH(), visH);
    }

    /**
     * Row layout of the Exchange - one 20 px row in the left column plus the three
     * action buttons of the right column (search / store / take).
     * Every rectangle is computed once during layout, and rendering and click hit testing read the
     * same copy (hit-area == visual rectangle).
     * Item seven: when the row objects are rebuilt every frame, the 200 ms debounce timestamp is
     * written back for each button key.
     */
    private void layoutExchangeRows(MarketClientState st) {
        exRows.clear();
        exOps.clear();
        if (tab != TAB_EXCH) {
            return;                                             // other tabs: no rows are built and no hit-area is claimed
        }
        int x0 = gx + exX;
        int rowLeft = x0 + exListL + 2;
        int rowRight = x0 + exListR - 2;                        // the overlay scrollbar takes no width from the content
        int nameX = rowLeft + EX_ICON_W + 2;                    // 2 px left for the icon and the name
        int rateRight = rowRight;                               // the rate column hugs the right edge of the row
        int countRight = rateRight - EX_RATE_W - EX_COL_GAP;    // right edge of the inventory count column
        int nameW = Math.max(0, (countRight - EX_COUNT_W - EX_COL_GAP) - nameX);
        long nowMs = System.currentTimeMillis();
        int topAbs = exViewTop();   // The row origin = viewport top with the padding already deducted, the same basis as the clipping
        int m = n();
        for (int i = 0; i < m; i++) {
            Stock s = i < st.stocks.length ? st.stocks[i] : null;
            if (s == null) {
                continue;
            }
            ExchangeRow row = new ExchangeRow();
            row.index = i;
            row.top = topAbs + i * (EX_ROW_H + EX_ROW_GAP) - exScroll;
            row.height = EX_ROW_H;
            row.left = rowLeft;
            row.right = rowRight;
            row.iconX = rowLeft;
            row.nameX = nameX;
            row.nameW = nameW;
            row.countRight = countRight;
            row.rateRight = rateRight;
            row.rateText = cn.oremarket.common.RawText.t("oremarket.text.0281") + s.exchangeRate() + cn.oremarket.common.RawText.t("oremarket.text.0082");
            row.stockName = s.displayName();
            row.stockCode = st.codeAt(i);
            exRows.add(row);
        }
        // ---- four custom-drawn action buttons in the right column: store / take (below the
        //      quantity row) plus store all / take all (second row, 4 px apart; hit-areas are only built when there is room) ----
        if (!exOpEnough || !configReady() || !playerReady()) {
            return;
        }
        int opx = x0 + exOpL + 4;
        int btnRowAbs = exAbsY() + exOpBtnY;
        int halfW = Math.max(40, (exOpInnerW - 4) / 2);
        ExOpButton dep = new ExOpButton(ExOpButton.DEPOSIT, opx, btnRowAbs, halfW, 20);
        ExOpButton wdr = new ExOpButton(ExOpButton.WITHDRAW, opx + halfW + 4, btnRowAbs, halfW, 20);
        ExOpButton depAll = new ExOpButton(ExOpButton.DEPOSIT_ALL, opx, btnRowAbs + 24, halfW, 20);
        ExOpButton wdrAll = new ExOpButton(ExOpButton.WITHDRAW_ALL, opx + halfW + 4, btnRowAbs + 24, halfW, 20);
        ExOpButton[] ops = {dep, wdr, depAll, wdrAll};
        boolean picked = selected >= 0 && selected < m;
        String pickWhy = trKey("oremarket.exch.err.pick");
        for (ExOpButton b : ops) {
            if (!picked) {
                b.enabled = false;
                b.disabledReason = pickWhy;
            } else if (!st.unlocked(selected)) {
                b.enabled = false;
                b.disabledReason = st.unlockHint(selected);
            }
        }
        // item seven: disabled inside the 200ms debounce window (the window ends when ClientTickEvent clears the timestamp, automatically and without waiting for a reply)
        for (ExOpButton b : ops) {
            Long sentAt = exDebounceAt.get(b.kind);
            b.disabledAtMs = sentAt == null ? 0L : sentAt;
            if (b.disabledAtMs > 0 && nowMs - b.disabledAtMs < CLICK_DEBOUNCE_MS) {
                b.enabled = false;
            }
            exOps.add(b);
        }
    }

    /** Key of the debounce timestamp table for the custom-drawn action buttons: the button kind (search / store / take). */
    private static int exDebounceKey(int kind) {
        return 900 + kind;
    }

    /**
     * Layout of the market container. Three blocks strictly from top to bottom: the price chart
     * (candlesticks), the stock list and the trading button area.
     * The 20 px tab bar and the 44 px button area have a fixed height and are never compressed, and
     * the button area never shifts to make room for the list; the usable height of the stock list =
     * container height - tab bar 20 - chart minimum 40 - button area 44 - the two gaps 8, clamped to
     * 0 when it goes negative (in which case no row is rendered at all); it takes all the height the
     * middle area has left.
     * Log tab = a single log area (the position overview block was deleted, so no
     * overview height is reserved at the top any more; minimum 48).
     */
    private void layoutMarket(MarketClientState st) {
        // Trade / Ranking tabs: the content height also deducts the 16 px sub-tab bar
        int contentH = mkH - TAB_H - (hasSubTabs() ? SUB_TAB_H : 0);
        chartH = 0;
        chartTop = 0;
        chartModeRectValid = false;
        splitTop = 0;
        splitH = 0;
        loanOverflow = 0;
        logMaxScroll = 0;
        logTooSmall = false;
        if (tab == TAB_LOAN) {
            computeLoanLayout(st);
            // Usable height of the loan page = container - tab bar - the fixed row of bottom buttons (gap of at least 4 px included).
            // One shared scrollMax (the last row keeps a 4 px gap when scrolled to the bottom).
            loanOverflow = scrollMax(loanPageH, listViewportH(contentH - loanPinnedH(st)));
            btnsTop = 0;
            btnsAreaH = TRADE_BTNS_H;
            return;
        }
        if (tab == TAB_TRADE || tab == TAB_RANK) {
            // Trade / Ranking pages: everything below the 16 px sub-tab bar is the list content area
            splitTop = TAB_H + SUB_TAB_H;
            splitH = Math.max(0, mkH - splitTop);
            leftColW = mkW - 4;
            rightColOff = 2 + leftColW + SPLIT_GAP;
            rightColW = 0;
            btnsColOff = 0;
            btnsColW = 0;
            btnsTop = 0;
            btnsAreaH = TRADE_BTNS_H;
            return;
        }
        if (tab == TAB_LOG || tab == TAB_EXCH) {
            btnsTop = 0;
            btnsAreaH = TRADE_BTNS_H;
            return;
        }
        // Quotes page: the candlestick chart above takes every bit of vertical space except the base height of the three columns below;
        // the three columns = left (stock list) / middle (holdings details) / right (Buy/Sell button group), sharing one top and one height, each scrolling inside itself.
        // The three columns take their height from the content baseline only and never squeeze the chart in return; at the chart minimum of 40 the columns give way first.
        int gap = SPLIT_GAP;
        int listWant = (mkW - 4 - 2 * gap) * 34 / 100;
        leftColW = Math.max(96, listWant);
        rightColOff = 2 + leftColW + gap;
        int detailWant = (mkW - 4 - 2 * gap) * 33 / 100;
        rightColW = Math.max(96, detailWant);
        btnsColOff = rightColOff + rightColW + gap;
        btnsColW = mkW - 2 - btnsColOff;
        if (btnsColW < 96) {
            // the button column gets at least 96 px, taken from the details column (the details already scroll, so the list keeps priority for a readable row count)
            int take = 96 - btnsColW;
            rightColW = Math.max(80, rightColW - take);
            btnsColOff = rightColOff + rightColW + gap;
            btnsColW = mkW - 2 - btnsColOff;
        }
        int btnsH = tradeBtnsAreaH();
        // Base height of the three columns = max(Buy/Sell button area height, 6 list rows = 96). When the list or details
        // content is taller than the column it scrolls inside it (an existing capability); the columns never use their natural height to squeeze the chart.
        int threeBase = Math.max(btnsH, THREE_COL_H);
        // Columns height = usable page height - chart height - the standard 4 px gap.
        // the chart never takes more than 48% of the content area (and never less than CHART_MIN, so the base height of the columns is preserved),
        // and all the remaining vertical space goes to the columns, which stretch adaptively - splitH runs straight to the container bottom edge, against the event bar.
        int chartCap = Math.max(CHART_MIN, contentH * 12 / 25);
        int chartFloor = Math.max(CHART_MIN, contentH - threeBase - 4);
        chartH = Math.max(CHART_MIN, Math.min(chartCap, chartFloor));
        chartH = Math.min(chartH, Math.max(0, contentH - 4));
        chartTop = TAB_H;
        splitTop = chartTop + chartH + 4;
        splitH = Math.max(0, mkH - splitTop);
        btnsTop = splitTop;
        btnsAreaH = splitH;
    }

    /**
     * Computes the chart mode chip before widgets are built. The same cached rectangle is used by
     * drawing, the real widget and the defensive click path, so a resize, language change or quote
     * refresh cannot leave a stale clickable area behind.
     */
    private void updateChartModeRect(MarketClientState st) {
        chartModeRectValid = false;
        if (tab != TAB_MARKET || st == null || chartH < 24 || selected < 0 || selected >= st.viewSize()
                || !st.unlocked(selected) || !st.quoteAt(selected)) {
            return;
        }
        int x0 = gx + mkX + 2;
        int y0 = mkAbsY() + chartTop;
        int w = mkW - 4;
        if (w < 40) {
            return;
        }
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < st.viewSize(); i++) {
            if (!st.quoteAt(i)) {
                continue;
            }
            double v = st.changePercent(i);
            if (Double.isFinite(v)) {
                sum += v;
                count++;
            }
        }
        String marketText = count <= 0 ? null
                : trKey("oremarket.market.index.value", Num.pct(sum / count, 2));
        int marketW = marketText == null ? 0 : font.width(marketText);
        String modeText = trKey(dailyChart ? "oremarket.market.chart.mode.daily" : "oremarket.market.chart.mode.short");
        int modeW = Math.max(24, font.width(modeText) + 8);
        int marketRightEdge = x0 + w - 14;
        boolean drawMarket = marketW > 0
                && marketRightEdge - marketW - 6 - modeW > x0 + K_PAD_X + 60;
        int modeRight = drawMarket ? marketRightEdge - marketW - 6 : marketRightEdge;
        int modeLeft = modeRight - modeW;
        chartModeRect[0] = modeLeft;
        chartModeRect[1] = y0 + 1;
        chartModeRect[2] = modeRight;
        chartModeRect[3] = y0 + CHART_INFO_H;
        chartModeRectValid = modeLeft >= x0 + 4 && chartModeRect[2] > chartModeRect[0];
    }

    /** Adds the actual clickable chart mode widget only when the chart has a real quote to display. */
    private void buildChartModeWidget(MarketClientState st) {
        if (!chartModeRectValid) {
            return;
        }
        ChartModeButton button = new ChartModeButton(chartModeRect[0], chartModeRect[1],
                chartModeRect[2] - chartModeRect[0], chartModeRect[3] - chartModeRect[1]);
        addRenderableWidget(button);
    }

    /** Keep the registered widget aligned with the frame's latest chart geometry before event dispatch. */
    private void syncChartModeWidget() {
        for (var child : this.children()) {
            if (!(child instanceof ChartModeButton button)) {
                continue;
            }
            button.visible = tab == TAB_MARKET && chartModeRectValid;
            if (button.visible) {
                button.setX(chartModeRect[0]);
                button.setY(chartModeRect[1]);
                button.setWidth(chartModeRect[2] - chartModeRect[0]);
                button.setHeight(chartModeRect[3] - chartModeRect[1]);
            }
        }
    }

    /** Four: content height of the stock list = total number of stocks x 16 (computing it from the position size is forbidden). */
    private int stockContentHeight() {
        return n() * LIST_ROW;
    }

    /** Usable height of the stock list = the real height the current layout leaves for the list. */
    private int stockAvailHeight() {
        return listViewportH(splitH);                     // The viewport height always has the padding deducted
    }

    /** Rectangle in which the stock list is drawn right now (outer midScroll included); scroll ownership is decided on drawn coordinates, not on the raw ones. */
    private int[] stockListDrawRect() {
        int my0 = mkAbsY();
        return new int[]{gx + mkX + 2, my0 + splitTop, gx + mkX + 2 + leftColW, my0 + splitTop + splitH};
    }


    /** Five: thumb height of the stock list scrollbar = clamp(available^2 / content height, min(12, available), available); no division when the denominator is 0. */

    private int listMaxScroll() {
        // The bound goes through the shared scrollMax - scrolling only when the content is taller, and the last row keeps a 4 px gap at the bottom
        return tab == TAB_MARKET ? scrollMax(stockContentHeight(), stockAvailHeight()) : 0;
    }

    /**
     * After the holdings information bar lost its stock-name title, six field rows are
     * left, with 4 px of padding. Total content height = 6 x 16, recomputed only when the number of
     * fields changes (requirement five, item four: no traversal every frame).
     */
    private static final int DETAIL_FIELD_COUNT = 7;
    private static final int DETAIL_PAD = 4;
    private static final int DETAIL_CONTENT_H = DETAIL_FIELD_COUNT * DETAIL_ROW;

    private int detailMaxScroll() {
        return tab == TAB_MARKET
                ? scrollMax(DETAIL_CONTENT_H, Math.max(0, splitH - 2 * DETAIL_PAD)) : 0;
    }

    /** The criterion for rebuilding widgets: rebuild as soon as the geometry or the UI state changes.
     *  List scrolling (Exchange / stock / log / ranking and the like) goes through the custom-drawn path and is not part of this key;
     *  only scrolling that genuinely moves buttons or input boxes (the outer mid / loan / short outer / order and limit outer) triggers a rebuild,
     *  so linked smooth scrolling and list dragging cannot rebuild every widget each frame and drop frames. */
    private String layoutKey() {
        MarketClientState st = MarketClientState.INSTANCE;
        // the language code is part of the criterion: switching the vanilla language rebuilds the Language instance, so widget labels must run through L.tr again.
        return gw + "x" + gh + "|" + (stacked ? "V" : "H") + "|" + (headerTwoRows ? "2R" : "1R") + "|" + barH + "|" + exH + "|" + mkH + "|"
                + tab + "/" + tradeSub + "/" + rankSub + "/" + selected + "|"
                + (orderSideBuy ? "B" : "S") + (limitSideBuy ? "B" : "S") + "|"
                + L.langCode() + "|"
                + loanStateCode(st) + "|" + (st != null && st.showBankruptBanner() ? "BANNER" : "")
                + "|" + loanSelAmount
                + "|" + loanProductSel + "/" + loanModeSel + "/" + loanInstallmentsSel
                // The dialog open state and the loading state joined the rebuild criterion - once Confirm / Cancel is pressed the widgets are rebuilt in the same frame,
                // which puts an end to "dialog buttons disappear late / stay clickable after the dialog closed".
                + "|" + (loanConfirmOpen ? "conf" : "") + (liquidateConfirmOpen ? "liq" : "")
                + (pickerOpen ? "pick" : "") + (loanBusy(st) ? "busy" : "")
                 + "|chart=" + (dailyChart ? "daily" : "short")
                + "|" + midScroll + "/" + loanScroll + "/" + shortScroll
                + "/" + orderOuterScroll + "/" + limitOuterScroll;
    }

    private int loanStateCode(MarketClientState st) {
        if (st.hasLoan()) {
            return 2;
        }
        if (st.inBankruptCooldown() && !st.showBankruptBanner()) {
            return 4;
        }
        long limit = st.hasData ? Math.max(0, st.loan.creditLimit) : Math.max(0, st.creditLimit());
        if (limit <= 0) {
            return 5;
        }
        return 1;
    }

    // ================================================================ Widgets

    /**
     * Changed from a static nested class into an inner class, because clipped drawing
     * has to reuse the outer {@link MarketScreen#scissor} (baseline coordinates -> screen GUI
     * coordinates); apart from that the behaviour is exactly the same as in the previous version.
     */
    private class MarketButton extends Button {
        final int mark;
        /** Seven: the moment a C2S packet was sent, as a millisecond timestamp; 0 = no debounce. Recovery is timed purely by the clock and never waits for a reply. */
        long disabledAtMs;
        /** Current selection state of the Buy / Sell direction buttons on the Trade sub-page: an extra white outline that claims no additional space in the row. */
        boolean highlighted;
        /**
         * The strong selected state (used by the product and repayment-method button
         * groups on the loan page) - the background brightens by one accent step, a 1 px gold outline is
         * drawn and the text turns gold; an unselected button keeps its ordinary dark look.
         * It defaults to false, so every other page sees no change at all.
         */
        boolean loud;
        /**
         * The pixel-level clipping box, in baseline canvas coordinates. Only widgets in the
         * scroll areas of the Trade sub-pages (Short Selling / Limit Order / Price Limit) and of the loan
         * page ever get one; clipX2 <= clipX1 means no clipping (every other page is untouched).
         * With clipping enabled the rendering is wrapped in enableScissor (the very same viewport
         * rectangle the text lines use): a button that pokes half out of the viewport is still drawn and
         * the pixels beyond the border are cut away instead of hiding whole rows by decision; the
         * hit-area likewise only accepts "button rectangle intersect clip box", so the clipped-away half
         * can neither be hovered nor clicked.
         */
        int clipX1 = -1;
        int clipY1 = -1;
        int clipX2 = -1;
        int clipY2 = -1;

        private MarketButton(Builder builder, int mark) {
            super(builder);
            this.mark = mark;
        }

        void setClip(int x1, int y1, int x2, int y2) {
            this.clipX1 = x1;
            this.clipY1 = y1;
            this.clipX2 = x2;
            this.clipY2 = y2;
        }

        /** Whether a usable clipping box is configured (a degenerate rectangle counts as "no clipping", so a button is never clipped away entirely). */
        boolean hasClip() {
            return clipX2 > clipX1 && clipY2 > clipY1;
        }

        /** Whether a point falls inside the clipping box (baseline coordinates). */
        boolean inClip(double mx, double my) {
            return mx >= clipX1 && mx < clipX2 && my >= clipY1 && my < clipY2;
        }

        /**
         * Only a button that really sticks out of the clipping box opens a scissor - one
         * fully inside the box costs no state switch and adds no extra batch submission; only the ones
         * crossing the border get wrapped in a single enableScissor / disableScissor pair.
         */
        private boolean clipActiveNow() {
            return hasClip() && (getX() < clipX1 || getY() < clipY1
                    || getX() + getWidth() > clipX2 || getY() + getHeight() > clipY2);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            // While a dialog is open its own Confirm / Cancel buttons are not drawn in
            // the widget layer, which sits under the mask and the plate - drawing them there would be half covered by the
            // dialog; they are drawn later in the top-most channel of renderTopOverlays (click and hover tests are unaffected, visible and the hit-areas stay exactly as they were).
            if (modalButtonsDeferred && isModalOwnButton(this)) {
                return;
            }
            if (!clipActiveNow()) {
                super.render(g, mouseX, mouseY, partialTick);
                return;
            }
            scissor(g, clipX1, clipY1, clipX2, clipY2);
            try {
                // the mouse is over the clipped-away half: feed it dead coordinates, so hover highlighting also only affects the visible part.
                boolean in = inClip(mouseX, mouseY);
                super.render(g, in ? mouseX : -99999, in ? mouseY : -99999, partialTick);
            } finally {
                g.disableScissor();
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            // While a dialog is open the buttons underneath it (still visible = true) must swallow the click at the hit-area layer,
            // otherwise the vanilla AbstractWidget.mouseClicked plays the click sound and fires the callback -
            // the interception at the top of the page's mouseClicked cannot stop events already dispatched to child widgets.
            if (anyModalOpen() && !isModalOwnButton(this)) {
                return false;
            }
            // The hit-area follows the clipping - the clipped-away half responds to no click (so it cannot emit a button click sound either).
            if (hasClip() && !inClip(mouseX, mouseY)) {
                return false;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        /**
         * Force this button's hover state off while a dialog exists - the vanilla
         * isHovered is only refreshed inside render, and a hidden widget (visible = false) never reaches
         * render, so the hover value would freeze at whatever it was; that is exactly where a lingering
         * tooltip or a stuck highlight the moment a dialog closes comes from.
         */
        void forceUnhover() {
            this.isHovered = false;
        }

        /** Recompute the hover state immediately from the current mouse position once a dialog closes (the equivalent of updateHover). */
        void syncHover(double mx, double my) {
            this.isHovered = this.visible
                    && (!hasClip() || inClip(mx, my))          // The clipped-away half does not count as hovered
                    && mx >= this.getX() && mx < this.getX() + this.getWidth()
                    && my >= this.getY() && my < this.getY() + this.getHeight();
        }

        @Override
        public void renderString(GuiGraphics g, Font font, int color) {
            int textW = font.width(getMessage());
            int inner = getWidth() - 8;    // the normal padding is 4 px on each side
            if (textW <= inner || textW <= 0) {
                super.renderString(g, font, color);
                return;
            }
            // Item seven: when even a standard or short label is too wide, fall back to uniform downscaling at the very end (never an ellipsis, never truncation).
            // This fallback covers very narrow windows and the direction buttons missing from the short-label table, and guarantees the text stays inside the button rectangle.
            float scale = Math.max((float) FONT_FLOOR_SCALE, (getWidth() - 4) / (float) textW);
            PoseStack pose = g.pose();
            pose.pushPose();
            pose.translate(getX() + getWidth() / 2.0F,
                    getY() + getHeight() / 2.0F - 4.0F, 0.0F);
            pose.scale(scale, scale, 1.0F);
            g.drawCenteredString(font, getMessage(), 0, 0, color);
            pose.popPose();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            // Part seven, item three: the vanilla texture is dropped for three custom-drawn background layers plus a 1 px dark outline; while
            // pressed (including the 5 tick highlight) the outline darkens to pure black, and on hover the background gains 10% brightness.
            // Fixed: the blue keyboard focus ring (2 px outside) and the white selection ring are **not drawn here**.
            // Widgets are drawn one after another in the order they were added, so a ring sticking out of its own
            // rectangle gets its lower half covered by the neighbouring widget drawn later, by the panel background and by the section borders (the reported "blue highlight is cut off").
            // They are now redrawn as a complete ring in one pass by {@link #drawWidgetRings}, after every widget has been drawn.
            boolean pressed = MarketClientState.INSTANCE.isPress(mark, ClientEvents.clientTick());
            int base = !active ? BTN_DISABLED_BG : (loud ? BTN_LOUD_BG : BTN_BASE_BG);
            int hoverCol = loud ? BTN_LOUD_HOVER : BTN_HOVER_BG;
            int bg = (isHovered() && active) || pressed ? hoverCol : base;
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
            g.renderOutline(getX() - 1, getY() - 1, getWidth() + 2, getHeight() + 2,
                    pressed ? 0xFF000000 : PANEL_ROOT);
            // In the strong (loud) selected state the text is always accent gold; unselected keeps the existing white / hovered gold text ladder
            renderString(g, Minecraft.getInstance().font,
                    !active ? BTN_DISABLED_TX
                            : (loud && active) ? GOLD_TEXT
                                    : (isHovered() && active ? GOLD_TEXT : TEXT_WHITE));
        }
    }

    /**
     * The chart mode is a real Screen widget, not only a hand-written hit test. Its painted rectangle is
     * the same rectangle registered with the widget tree, and it deliberately has no outline of its own.
     */
    private final class ChartModeButton extends MarketButton {
        private ChartModeButton(int x, int y, int w, int h) {
            super(Button.builder(Component.empty(), btn -> toggleChartMode()).bounds(x, y, w, h), M_CHART_MODE);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int bg = isHovered() ? TAB_HOVER_BG : TAB_NORMAL_BG;
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
            String label = trKey(dailyChart ? "oremarket.market.chart.mode.daily" : "oremarket.market.chart.mode.short");
            drawFitCentered(g, label, getX() + 2, getX() + getWidth() - 2,
                    getY() + 2, dailyChart ? GOLD_TEXT : TEXT_WHITE);
        }
    }

    /** Toggle only the chart data source; prices, trades, balances and server state are untouched. */
    private void toggleChartMode() {
        dailyChart = !dailyChart;
    }

    /**
     * An input box with the same pixel-level clipping as MarketButton - the quantity and
     * price boxes inside a Trade sub-page scroll area share one clip box with the buttons in the same
     * row: a widget that overlaps the viewport by even 1 px is kept (no more hiding a whole row by
     * decision) and whatever sticks out is cut by the enableScissor inside render; the clipped-away
     * half accepts no click, takes no focus and plays no click sound. Keyboard, focus and Responder
     * behaviour all stay on the vanilla EditBox, and no framework code was touched.
     */
    private final class MarketEditBox extends EditBox {
        int clipX1 = -1;
        int clipY1 = -1;
        int clipX2 = -1;
        int clipY2 = -1;

        MarketEditBox(Font fontIn, int x, int y, int width, int height, Component message) {
            super(fontIn, x, y, width, height, message);
        }

        void setClip(int x1, int y1, int x2, int y2) {
            this.clipX1 = x1;
            this.clipY1 = y1;
            this.clipX2 = x2;
            this.clipY2 = y2;
        }

        boolean hasClip() {
            return clipX2 > clipX1 && clipY2 > clipY1;
        }

        private boolean inClip(double mx, double my) {
            return mx >= clipX1 && mx < clipX2 && my >= clipY1 && my < clipY2;
        }

        private boolean clipActiveNow() {
            return hasClip() && (getX() < clipX1 || getY() < clipY1
                    || getX() + getWidth() > clipX2 || getY() + getHeight() > clipY2);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            if (!clipActiveNow()) {
                super.render(g, mouseX, mouseY, partialTick);
                return;
            }
            scissor(g, clipX1, clipY1, clipX2, clipY2);
            try {
                boolean in = inClip(mouseX, mouseY);
                super.render(g, in ? mouseX : -99999, in ? mouseY : -99999, partialTick);
            } finally {
                g.disableScissor();
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            // The hit-area follows the clipping - the clipped-away half takes no focus and never enters edit mode.
            if (hasClip() && !inClip(mouseX, mouseY)) {
                return false;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }

    /**
     * The unified late-drawing pass after every widget has been rendered (fixes button
     * highlight rings being clipped or covered).
     *
     * <p>Both the white selection ring and the blue keyboard focus ring are drawn <b>outside</b> the
     * button rectangle (0 and 2 pixels beyond it respectively). Drawing them inside
     * {@code renderWidget} lets widgets rendered later, the section backgrounds and the 1 px container
     * border squash half of the ring - most visibly the lower half on the last button of a row. Here
     * they are drawn again once {@code super.render} is over, from the current highlight state, so a
     * ring is always on the top-most layer and always complete.</p>
     *
     * <p>Nothing is drawn beyond the screen: a ring is just the widget's own rectangle grown outwards,
     * the widgets are placed by the screen layout, and no clipping is applied here, so even the 2 px of
     * overflow stays fully visible.</p>
     *
     */
    private void drawWidgetRings(GuiGraphics g) {
        for (net.minecraft.client.gui.components.Renderable r : this.renderables) {
            if (!(r instanceof MarketButton b) || !b.visible) {
                continue;
            }
            // A dialog button deferred to the top-most channel has its highlight ring redrawn there too.
            if (modalButtonsDeferred && isModalOwnButton(b)) {
                continue;
            }
            if (b.clipActiveNow()) {
                scissor(g, b.clipX1, b.clipY1, b.clipX2, b.clipY2);
                try {
                    drawRingsFor(g, b);
                } finally {
                    g.disableScissor();
                }
            } else {
                drawRingsFor(g, b);
            }
        }
    }

    /** The highlight ring of a single button (split out of drawWidgetRings so the clipped and unclipped paths can share it). */
    private void drawRingsFor(GuiGraphics g, MarketButton b) {
        if (b.mark == M_CHART_MODE) {
            return;
        }
        if (b.highlighted) {
            g.renderOutline(b.getX(), b.getY(), b.getWidth(), b.getHeight(), TEXT_WHITE);
        }
        // The selected button on the loan page gets an extra 1 px gold outline (always on the top-most layer, never clipped by a neighbour),
        // which, together with the brightened background and the gold text, makes a selection that is obvious at a glance.
        if (b.loud && b.active) {
            g.renderOutline(b.getX() - 1, b.getY() - 1, b.getWidth() + 2, b.getHeight() + 2, GOLD);
        }
        // Red warning outline of the Clear All Positions button (always on the top-most layer).
        if (b.mark == M_TRADE + 8) {
            g.renderOutline(b.getX() - 1, b.getY() - 1, b.getWidth() + 2, b.getHeight() + 2, DANGER_RING);
        }
        // The small triangle drawn by hand to the right of the stock dropdown trigger (assembled with fill, no non-ASCII glyphs allowed).
        if (b.mark == M_PICKER) {
            int ax = b.getX() + b.getWidth() - 12;
            int ay = b.getY() + b.getHeight() / 2;
            if (pickerOpen) {
                g.fill(ax, ay - 2, ax + 5, ay - 1, TEXT_WHITE);
                g.fill(ax + 1, ay - 1, ax + 4, ay, TEXT_WHITE);
                g.fill(ax + 2, ay, ax + 3, ay + 1, TEXT_WHITE);
            } else {
                g.fill(ax + 2, ay - 2, ax + 3, ay - 1, TEXT_WHITE);
                g.fill(ax + 1, ay - 1, ax + 4, ay, TEXT_WHITE);
                g.fill(ax, ay, ax + 5, ay + 1, TEXT_WHITE);
            }
        }
        if (b.isFocused() && !b.isHovered()) {
            g.renderOutline(b.getX() - 2, b.getY() - 2, b.getWidth() + 4, b.getHeight() + 4,
                    FOCUS_RING);
        }
    }

    /** The three background layers of a button (the button tier of the colour scheme in part seven, item one). */
    private static int BTN_BASE_BG = PANEL_ROW;
    private static int BTN_HOVER_BG = 0xFF4E4E4E;
    private static int BTN_DISABLED_BG = DARK_BG;
    private static int BTN_DISABLED_TX = GRAY88;
    private static int FOCUS_RING = ACCENT_BLUE;
    /** Background of the strong selected state (one step brighter than usual, following the "selected" role of the theme). */
    private static int BTN_LOUD_BG = 0xFF4A5A7A;
    private static int BTN_LOUD_HOVER = 0xFF5F75A6;

    /**
     * Seven: right after a C2S packet is sent the button is disabled and System.currentTimeMillis() is
     * recorded; ClientTickEvent clears the timestamp once now - timestamp >= 200 ms and the button is
     * usable again. No sleeping, no blocking, no waiting for a reply: however fast the answer comes,
     * recovery follows the fixed window.
     */
    private static void markSent(Button btn) {
        if (btn instanceof MarketButton mb) {
            mb.disabledAtMs = System.currentTimeMillis();
            mb.active = false;
        }
    }

    private Button addBtn(Component label, int x, int y, int w, int h, int mark, Button.OnPress action) {
        MarketButton b = new MarketButton(Button.builder(label, btn -> {
            MarketClientState.INSTANCE.press(mark, ClientEvents.clientTick());
            action.onPress(btn);
        }).bounds(x, y, Math.max(8, w), h), mark);
        addRenderableWidget(b);
        return b;
    }

    private void buildWidgets(MarketClientState st) {
        int closeY = headerTwoRows ? gy + 8 : gy + (HEADER_H_1 - 12) / 2;
        addBtn(Component.literal(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0029"))), gx + gw - 34, closeY, 28, 12, M_CLOSE, btn -> onClose());
        if (tab == TAB_LOAN) {
            buildLoanWidgets(st);
        } else if (tab == TAB_MARKET) {
            buildTradeWidgets(st);
            buildChartModeWidget(st);
        } else if (tab == TAB_TRADE) {
            buildTradeTabWidgets(st);
        } else if (tab == TAB_EXCH) {
            buildExchangeWidgets(st);
        }
        // Buttons of the liquidation confirmation dialog (the same Modal machinery as the loan dialog; they must be built whenever the dialog is open, under any tab).
        if (liquidateConfirmOpen) {
            buildLiquidateConfirmButtons();
        }
        // the Log and Ranking pages have no vanilla widgets: the content is entirely custom-drawn plus wheel scrolling.
    }

    /**
     * The shared measuring entry point behind the decision of which button
     * text to render:
     *  (1) the width of the standard translation fits inside the usable button width (button width - 4 px
     *      of padding on each side) -> use the standard value;
     *  (2) it does not fit -> use the .short label value, and when even that does not fit the caller
     *      degrades (bigger button / smaller padding / vertical stacking).
     * Truncating button text with an ellipsis is forbidden: no path may trimToWidth and then squeeze the
     * result into the button.
     */
    private String btnLabel(String stdKey, int buttonW) {
        return btnLabel(stdKey, buttonW, 4);
    }

    /** Same as above with a configurable padding (the second degradation step takes the side padding from 4 down to 2). */
    private String btnLabel(String stdKey, int buttonW, int pad) {
        int avail = buttonW - pad * 2;
        String std = trKey(stdKey);
        if (font == null || font.width(std) <= avail) {
            return std;
        }
        return trKey(stdKey + ".short");
    }

    /**
     * Buttons whose text changes with the selected direction, such as "Take selected order": measure
     * against the dynamic text in the current language first, and fall back to that button's .short
     * label when it does not fit (truncation and ellipses are forbidden there too).
     */
    private String btnLabelDynamic(String rawText, String shortKey, int buttonW) {
        String t = L.tr(rawText);
        if (font == null || font.width(t) <= buttonW - 8) {
            return t;
        }
        return trKey(shortKey + ".short");
    }

    /** Which label tier a button row should use at the current width (0 standard / 1 short label / 2 needs degradation). */
    private int btnRowFit(String[] stdKeys, int buttonW, int gap, int pad) {
        int avail = buttonW - pad * 2;
        boolean stdOk = true;
        boolean shortOk = true;
        for (String key : stdKeys) {
            if (font == null) {
                continue;
            }
            if (font.width(trKey(key)) > avail) {
                stdOk = false;
            }
            if (font.width(trKey(key + ".short")) > avail) {
                shortOk = false;
            }
        }
        if (stdOk) {
            return 0;
        }
        return shortOk ? 1 : 2;
    }

    /**
     * Evaluate the tier of the trading button area (layout and widget construction share
     * one criterion, so the two can never drift apart). It returns the total height of the button area:
     * normally TRADE_BTNS_H (two button rows + a 4 px row gap + the fee hint line); once a row enters
     * the "vertical stacking" tier it becomes button count x TRADE_BTN_H + gaps x TRADE_ROW_GAP + the
     * hint line, and the list / chart above shrinks accordingly (the list supports scrolling anyway).
     */
    private int tradeBtnsAreaH() {
        if (font == null || mkW <= 0) {
            return TRADE_BTNS_H;
        }
        // The button group lives in the right column of the Quotes page, so the tier evaluation uses the column width instead of the container width.
        int inner = Math.max(24, (btnsColW > 0 ? btnsColW : mkW) - 8);
        int w4 = (inner - 3 * 3) / 4;
        String[] buyKeys = {"oremarket.button.buy1", "oremarket.button.buy10",
                "oremarket.button.buy100", "oremarket.button.buy_all"};
        String[] sellKeys = {"oremarket.button.sell1", "oremarket.button.sell10",
                "oremarket.button.sell100", "oremarket.button.sell_all"};
        int buyFit = btnRowFit(buyKeys, w4, 3, 4);
        int sellFit = btnRowFit(sellKeys, w4, 3, 4);
        if (buyFit == 2) {
            buyFit = btnRowFit(buyKeys, (inner - 3 * 2) / 4, 2, 2);
        }
        if (sellFit == 2) {
            sellFit = btnRowFit(sellKeys, (inner - 3 * 2) / 4, 2, 2);
        }
        int buyH = buyFit == 2 ? 4 * TRADE_BTN_H + 3 * TRADE_ROW_GAP : TRADE_BTN_H;
        int sellH = sellFit == 2 ? 4 * TRADE_BTN_H + 3 * TRADE_ROW_GAP : TRADE_BTN_H;
        // Buy row + Sell row + Clear All Positions row + current selection line (12) + locate-position row + fee hint line;
        // the row gaps line up section by section with the real arrangement in buildTradeWidgets (4 px of padding at the top, 4 px between the locate button and the fee line).
        int extraH = TRADE_BTN_H + 12 + TRADE_ROW_GAP + TRADE_BTN_H + TRADE_HINT_LINE_H + 4;
        return Math.max(TRADE_BTNS_H,
                buyH + TRADE_ROW_GAP + sellH + TRADE_ROW_GAP + extraH);
    }

    /**
     * Rearranged grid of the button group in the right column of the Quotes page.
     *  Buy row: Buy 1 / Buy 10 / Buy 100 / Buy All At Once;
     *  Sell row: Sell 1 / Sell 10 / Sell 100 / Sell All At Once (only the unfrozen shares of the stock
     *    that is selected right now);
     *  global clean-up row: a single "Clear All Positions" button with the red warning outline, which
     *    opens the same Modal second confirmation as the loan page;
     *  selection line: the text "Currently selected stock: XXX";
     *  locate row: one "Locate my position" button, greyed out while there is no position.
     * Every button keeps at least 4 px from the container edge; hit-area = visual rectangle
     * (addBtn.bounds is exactly the rectangle that is drawn).
     */
    private void buildTradeWidgets(MarketClientState st) {
        int y = mkAbsY() + btnsTop + 4;                        // margin of 4: never overlapping the container edge
        int x0 = gx + mkX + btnsColOff + 4;
        int inner = Math.max(24, btnsColW - 8);
        String[] buyKeys = {"oremarket.button.buy1", "oremarket.button.buy10",
                "oremarket.button.buy100", "oremarket.button.buy_all"};
        String[] sellKeys = {"oremarket.button.sell1", "oremarket.button.sell10",
                "oremarket.button.sell100", "oremarket.button.sell_all"};
        int w4 = (inner - 3 * 3) / 4;
        int buyFit = btnRowFit(buyKeys, w4, 3, 4);
        int sellFit = btnRowFit(sellKeys, w4, 3, 4);
        int gap = 3, pad = 4;
        if (buyFit == 2) {
            w4 = (inner - 3 * 2) / 4;
            buyFit = btnRowFit(buyKeys, w4, 2, 2);
            gap = 2;
            pad = 2;
        }
        if (sellFit == 2) {
            int wAlt = (inner - 3 * 2) / 4;
            sellFit = btnRowFit(sellKeys, wAlt, 2, 2);
            if (sellFit != 2) {
                w4 = wAlt;
                gap = 2;
                pad = 2;
            }
        }
        boolean buyStack = buyFit == 2;
        boolean sellStack = sellFit == 2;
        int fullW = inner;
        String[] buyLabel = new String[4];
        String[] sellLabel = new String[4];
        for (int i = 0; i < 4; i++) {
            buyLabel[i] = btnLabel(buyKeys[i], buyStack ? fullW : w4, buyStack ? 4 : pad);
            sellLabel[i] = btnLabel(sellKeys[i], sellStack ? fullW : w4, sellStack ? 4 : pad);
        }
        int by1 = y;
        if (buyStack) {
            for (int i = 0; i < 4; i++) {
                final int qty = i == 0 ? 1 : i == 1 ? 10 : i == 2 ? 100 : C2STradePacket.QTY_ALL_IN;
                addBtn(Component.literal(buyLabel[i]), x0, by1 + i * TRADE_BTN_STRIDE,
                        fullW, TRADE_BTN_H, M_TRADE + i,
                        btn -> sendTrade(btn, qty, true));
            }
        } else {
            addBtn(Component.literal(buyLabel[0]), x0, y, w4, TRADE_BTN_H, M_TRADE,
                    btn -> sendTrade(btn, 1, true));
            addBtn(Component.literal(buyLabel[1]), x0 + (w4 + gap), y, w4, TRADE_BTN_H, M_TRADE + 1,
                    btn -> sendTrade(btn, 10, true));
            addBtn(Component.literal(buyLabel[2]), x0 + 2 * (w4 + gap), y, w4, TRADE_BTN_H, M_TRADE + 2,
                    btn -> sendTrade(btn, 100, true));
            addBtn(Component.literal(buyLabel[3]), x0 + 3 * (w4 + gap), y,
                    inner - 3 * (w4 + gap), TRADE_BTN_H, M_TRADE + 3,
                    btn -> sendTrade(btn, C2STradePacket.QTY_ALL_IN, true));
        }
        int y2 = buyStack
                ? by1 + 3 * TRADE_BTN_STRIDE + TRADE_BTN_H + TRADE_ROW_GAP
                : y + TRADE_BTN_H + TRADE_ROW_GAP;
        if (sellStack) {
            for (int i = 0; i < 4; i++) {
                final int qty = i == 0 ? 1 : i == 1 ? 10 : i == 2 ? 100 : C2STradePacket.QTY_ALL_AVAILABLE;
                addBtn(Component.literal(sellLabel[i]), x0, y2 + i * TRADE_BTN_STRIDE,
                        fullW, TRADE_BTN_H, M_TRADE + 4 + i,
                        btn -> sendTrade(btn, qty, false));
            }
        } else {
            addBtn(Component.literal(sellLabel[0]), x0, y2, w4, TRADE_BTN_H, M_TRADE + 4,
                    btn -> sendTrade(btn, 1, false));
            addBtn(Component.literal(sellLabel[1]), x0 + (w4 + gap), y2, w4, TRADE_BTN_H, M_TRADE + 5,
                    btn -> sendTrade(btn, 10, false));
            addBtn(Component.literal(sellLabel[2]), x0 + 2 * (w4 + gap), y2, w4, TRADE_BTN_H, M_TRADE + 6,
                    btn -> sendTrade(btn, 100, false));
            addBtn(Component.literal(sellLabel[3]), x0 + 3 * (w4 + gap), y2,
                    inner - 3 * (w4 + gap), TRADE_BTN_H, M_TRADE + 7,
                    btn -> sendTrade(btn, C2STradePacket.QTY_ALL_AVAILABLE, false));
        }
        // Global clear row: a standalone Clear All button with a red warning ring (drawn by drawWidgetRings).
        int y3 = sellStack
                ? y2 + 3 * TRADE_BTN_STRIDE + TRADE_BTN_H + TRADE_ROW_GAP
                : y2 + TRADE_BTN_H + TRADE_ROW_GAP;
        addBtn(Component.literal(btnLabel("oremarket.button.sell_everything", inner)),
                x0, y3, inner, TRADE_BTN_H, M_TRADE + 8,
                btn -> openLiquidateConfirm());
        // Locate-position button: below the current selected row's text (self-drawn, height 12); greyed out by updateButtonStates when there are no positions.
        int y4 = y3 + TRADE_BTN_H + 12 + 4;                   // 12 = the height of the current selected-text row.
        addBtn(Component.literal(btnLabel("oremarket.button.locate_holding", inner)),
                x0, y4, inner, TRADE_BTN_H, M_LOCATE,
                btn -> locateNextHolding());
    }

    /** Trading sub-page controls: every control uses the same unified row height, margins and wrap decisions as the painting code. */
    private void buildTradeTabWidgets(MarketClientState st) {
        int left = tradeContentLeft();
        int right = tradeContentRight();
        if (tradeSub == 0) {
            // Controls and painting share the VerticalLayout result; every y is layout y minus the outer scrollOffset.
            layoutShortPage(st);
            int row = SL_QTY;
            int labelW = font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0237")));
            shortQtyBox = numericBox(left + labelW + 6, shortElementY(row) + 1, 60, 18,
                    shortQtyText, 8, v -> shortQtyText = v);
            int buttonRow = SL_BTN;
            int gap = 4;
            int by0 = shortElementY(buttonRow);
            boolean shortStacked = shortButtonsStacked();
            int fullW = Math.max(8, right - left);
            // Five-button grid -- open / one-click short / close half / close selected / close all;
            // wide screens put all five in one equal-width row (the last absorbs the remainder to the right edge); narrow screens stack them vertically (step 22, same convention as the old three-grid).
            int bw = shortStacked ? fullW : Math.max(45, (right - left - gap * 4) / 5);
            int lastW = shortStacked ? fullW : Math.max(bw, right - (left + (bw + gap) * 4));
            java.util.function.IntUnaryOperator bx = k -> shortStacked ? left : left + k * (bw + gap);
            java.util.function.IntUnaryOperator byF = k -> shortStacked ? by0 + k * 22 : by0;
            Button openBtn = addBtn(Component.literal(btnLabel("oremarket.button.short_open", bw)), bx.applyAsInt(0), byF.applyAsInt(0), bw, 20, M_SHORT_OPEN, btn -> {
                String code = selectedCodeForForm(st);
                if (code == null) {
                    ClientEvents.showToast(trKey("oremarket.error.no_selection"));
                    return;
                }
                String freeze = selectedTradeBlockKey(st);
                if (freeze != null) {
                    ClientEvents.showToast(trKey(freeze));
                    return;
                }
                // The borrow-quantity field is strictly validated: empty / decimal / non-positive / unparseable -> pop the error toast window (same animation as other error toasts);
                // it no longer silently falls back to 1 and sends the order.
                int qty = parseShortQty();
                if (qty <= 0) {
                    ClientEvents.showToast(trKey("oremarket.error.short_quantity_invalid"));
                    return;
                }
                // A C2S packet is sent only after all four local checks pass; the server still re-validates on receipt,
                // and validation failures come back as feedback shown in the same error toast window.
                if (!shortOpenLocalChecksPass(st, code, qty)) {
                    return;
                }
                OreMarketNetwork.CHANNEL.sendToServer(new C2SShortPacket(C2SShortPacket.ACTION_OPEN,
                        code, qty));
                markSent(btn);
            });
            Button closeOne = addBtn(Component.literal(btnLabel("oremarket.button.short_close_selected", bw)), bx.applyAsInt(3), byF.applyAsInt(3), bw, 20, M_SHORT_CLOSE_ONE, btn -> {
                MarketClientState.ShortInfo target = selectedShortInfo(st);
                if (target == null) {
                    // When selectedIndex == -1 (no short position selected)
                    // a closing request must never be issued -- show an ErrorToast and return immediately.
                    ClientEvents.showToast(trKey("oremarket.short.err.no_selection"));
                    return;
                }
                int si = st.indexOfCode(target.code);
                if (si < 0 || !st.quoteAt(si) || !(st.price[si] > 0) || !Double.isFinite(st.price[si])) {
                    ClientEvents.showToast(trKey("oremarket.error.quote_unavailable"));
                    return;
                }
                // The close packet carries the unique position id + expected quantity; the server re-validates against the id that
                // the position really exists with a matching quantity; if it was liquidated or mutated, an error toast shows and the latest snapshot is pushed to refresh the UI.
                OreMarketNetwork.CHANNEL.sendToServer(new C2SShortPacket(C2SShortPacket.ACTION_CLOSE,
                        target.code, target.quantity, target.id));
                markSent(btn);
            });
            Button closeAll = addBtn(Component.literal(btnLabel("oremarket.button.short_close_all",
                    lastW)), bx.applyAsInt(4), byF.applyAsInt(4), lastW, 20, M_SHORT_CLOSE_ALL, btn -> {
                        boolean sent = false;
                        for (MarketClientState.ShortInfo target : st.shorts) {
                            int si = st.indexOfCode(target.code);
                            if (si < 0 || !st.quoteAt(si) || !(st.price[si] > 0)
                                    || !Double.isFinite(st.price[si])) {
                                continue;   // Positions without a valid current price are skipped and no close packet is sent
                            }
                            // Close-all likewise carries each position's unique id for server-side validation
                            OreMarketNetwork.CHANNEL.sendToServer(new C2SShortPacket(
                                    C2SShortPacket.ACTION_CLOSE, target.code, 0, target.id));
                            sent = true;
                        }
                        if (sent) {
                            markSent(btn);
                        } else if (!st.shorts.isEmpty()) {
                            ClientEvents.showToast(trKey("oremarket.error.quote_unavailable"));
                        }
                    });
            // Operates only on the CURRENTLY SELECTED stock; a pure local fill button that changes no quotes -
            // quantity = min(shares affordable with half the available balance, pool remaining), floored; results <= 0 always toast locally and
            // **never send an empty packet**; success only puts the number into the input field -- the final open still needs the player to click Open and pass server re-validation.
            Button oneClickBtn = addBtn(Component.literal(btnLabel("oremarket.button.short_oneclick", bw)),
                    bx.applyAsInt(1), byF.applyAsInt(1), bw, 20, M_SHORT_ONECLICK, btn -> {
                String code = selectedCodeForForm(st);
                if (code == null) {
                    ClientEvents.showToast(trKey("oremarket.error.no_selection"));
                    return;
                }
                int oi = st.indexOfCode(code);
                if (oi < 0 || !st.quoteAt(oi) || !(st.price[oi] > 0) || !Double.isFinite(st.price[oi])) {
                    ClientEvents.showToast(trKey("oremarket.error.quote_unavailable"));
                    return;
                }
                MarketSettings msO = MarketSettings.client();
                // When the snapshot is missing, do not guess: fall back to the all-defaults object (same source as config defaults),
                // so no hardcoded 1.5 hides in the UI and drifts when the config default changes.
                double ratioO = msO == null
                        ? cn.oremarket.market.MarketSettings
                        .defaults().shortMarginRatio
                        : msO.shortMarginRatio;
                double feeTaxO = msO == null ? 0.0 : Fees.combined(
                        Fees.effectiveFeeRate(msO.feeRate()), msO.taxRate());
                int maxOrderO = msO == null ? Integer.MAX_VALUE : Math.max(1, msO.maxTradeQuantity);
                // When the pool was never sent (-1), impose no local limit and let the server critical section decide; once sent, strictly take the remainder.
                int poolO = st.poolLeft[oi] < 0 ? Integer.MAX_VALUE : Math.max(0, st.poolLeft[oi]);
                int shares = cn.oremarket.common.ShortPoolCalculator
                        .oneClickShares(st.freeCoins(), st.price[oi], ratioO, feeTaxO, poolO, maxOrderO);
                if (shares <= 0) {
                    // Failures only inform: insufficient balance vs pool empty get distinct texts; no packet sent, the field is not cleared.
                    if (poolO <= 0) {
                        ClientEvents.showToast(trKey("oremarket.short.err.pool_empty"));
                    } else {
                        ClientEvents.showToast(trKey("oremarket.short.err.oneclick_no_funds"));
                    }
                    return;
                }
                String v = String.valueOf(shares);
                shortQtyText = v;
                if (shortQtyBox != null) {
                    shortQtyBox.setValue(v);   // the consumer writes shortQtyText back synchronously so the two views always agree
                }
                markSent(btn);
            });
            // Send floor(position shares / 2) -- 3 shares -> 1; fewer than 2 computes 0,
            // which only toasts locally and NEVER sends an empty packet. The server refunds locked margin by the share fraction and returns integer shares to the pool.
            Button closeHalfBtn = addBtn(Component.literal(btnLabel("oremarket.button.short_close_half", bw)),
                    bx.applyAsInt(2), byF.applyAsInt(2), bw, 20, M_SHORT_CLOSE_HALF, btn -> {
                MarketClientState.ShortInfo target = selectedShortInfo(st);
                if (target == null) {
                    ClientEvents.showToast(trKey("oremarket.short.err.no_selection"));
                    return;
                }
                int si = st.indexOfCode(target.code);
                if (si < 0 || !st.quoteAt(si) || !(st.price[si] > 0) || !Double.isFinite(st.price[si])) {
                    ClientEvents.showToast(trKey("oremarket.error.quote_unavailable"));
                    return;
                }
                int half = Math.floorDiv(target.quantity, 2);
                if (half <= 0) {
                    ClientEvents.showToast(trKey("oremarket.short.err.half_zero"));
                    return;
                }
                OreMarketNetwork.CHANNEL.sendToServer(new C2SShortPacket(C2SShortPacket.ACTION_CLOSE,
                        target.code, half, target.id));
                markSent(btn);
            });
            // Controls must not paint over the event bar either -- switched to pixel-level clipping: any 1px overlap with the content area
            // (top = sub-tab bar + 4, bottom = event bar upper edge - 4) keeps the control, and the half-past-the-bound part is cut by scissor;
            // only fully scrolled-out blocks hide, and taller event bars / page scrolls recompute at the next rebuildWidgets.
            clampWidgetToContent(shortQtyBox);
            clampWidgetToContent(openBtn);
            clampWidgetToContent(oneClickBtn);
            clampWidgetToContent(closeHalfBtn);
            clampWidgetToContent(closeOne);
            clampWidgetToContent(closeAll);
        } else {
            boolean limit = tradeSub == 2;
            if (limit) {
                layoutLimitPage(st);
            } else {
                layoutOrderPage(st);
            }
            VerticalLayout page = limit ? limitLayout : orderLayout;
            TradeFormGeom geom = tradeFormGeom(limit);
            int scroll = pageScroll(limit);
            int dirY = page.y(TO_DIR) - scroll;
            int inputY = (geom.inputOnSecondRow() ? page.y(TO_INPUT) : page.y(TO_DIR)) - scroll;
            int submitY = (geom.buttonsOnFirstRow() ? page.y(TO_DIR) : page.y(TO_SUBMIT)) - scroll;
            if (!limit) {
                addSideButtons(left, dirY, M_ORDER_BUY, orderSideBuy, b -> orderSideBuy = b);
                orderPriceBox = numericBox(geom.priceBoxX, inputY + 1, geom.inputW, 18,
                        orderPriceText, 12, v -> orderPriceText = v);
                orderQtyBox = numericBox(geom.qtyBoxX, inputY + 1, geom.inputW, 18,
                        orderQtyText, 8, v -> orderQtyText = v);
                addOrderButtons(st, geom, submitY);
                clampWidgetToContent(orderPriceBox);
                clampWidgetToContent(orderQtyBox);
            } else {
                addSideButtons(left, dirY, M_LIMIT_BUY, limitSideBuy, b -> limitSideBuy = b);
                limitPriceBox = numericBox(geom.priceBoxX, inputY + 1, geom.inputW, 18,
                        limitPriceText, 12, v -> limitPriceText = v);
                limitQtyBox = numericBox(geom.qtyBoxX, inputY + 1, geom.inputW, 18,
                        limitQtyText, 8, v -> limitQtyText = v);
                addLimitButtons(st, geom, submitY);
                clampWidgetToContent(limitPriceBox);
                clampWidgetToContent(limitQtyBox);
            }
        }
        // The three sub-pages share one stock-picker dropdown trigger at the top (self-drawn expanded list; the vanilla CycleButton is banned).
        // shared state: read selected / selectedCode (MarketClientState derived fields) directly; creating new singletons is forbidden.
        buildStockPickerTrigger();
    }

    /**
     * The trading page's top stock-dropdown trigger button.
     * The expanded list is self-drawn by drawStockPickerOverlay at the highest z-order (never covered by inputs or lists);
     * when the list is empty the button greys out with a placeholder hint, and list.get(0) overruns are strictly prevented.
     */
    private void buildStockPickerTrigger() {
        int[] r = pickerTriggerRect();
        int m = n();
        int textAvail = Math.max(24, r[2] - 30);                 // right side leaves room for the self-drawn triangle
        String label;
        if (m <= 0) {
            label = trKey("oremarket.picker.empty");
        } else if (selected >= 0 && selected < m) {
            Stock s = MarketClientState.INSTANCE.stocks[selected];
            String name = s == null ? selectedCodeSafe() : L.tr(s.displayName());
            label = trKey("oremarket.picker.current", clipToWidth(name == null ? "" : name, textAvail));
        } else {
            label = trKey("oremarket.picker.none");
        }
        Button b = addBtn(Component.literal(label), r[0], r[1], r[2], r[3], M_PICKER, btn -> {
            if (n() <= 0) {
                return;                                         // empty list: do not expand, avoiding an overrun
            }
            pickerOpen = !pickerOpen;
            if (pickerOpen) {
                pickerScroll = 0;
            }
        });
        b.active = m > 0;
        b.visible = true;                                       // the trigger row sits above the content area and is not clamped by it
    }

    /** Trigger button rectangle of the dropdown: [x, y, w, h], between the sub-tab bar and the content area (its own row, outside the VerticalLayout). */
    private int[] pickerTriggerRect() {
        int left = tradeContentLeft();
        int w = Math.max(80, tradeContentRight() - left);
        int y = mkAbsY() + TAB_H + SUB_TAB_H + 1;
        return new int[]{left, y, w, PICKER_ROW_H - 2};
    }

    /**
     * The dropdown's expanded panel (self-drawn at the top layer; vanilla CycleButton banned).
     * The panel paints at a z-order above inputs / lists, covering what lies beneath;
     * one enableScissor for the whole panel + disableScissor in finally, with per-row half-row clipping by rowIntersects only;
     * row = icon + name + current price; the panel never expands on an empty list (the trigger callback blocks it) and list.get(0) is strictly banned;
     * the panel rectangle is cached in pickerPanelRect so click / wheel hit-testing and painting share one geometry.
     */
    private void drawStockPickerOverlay(GuiGraphics g, int mouseX, int mouseY) {
        pickerPanelValid = false;
        if (!pickerOpen) {
            return;
        }
        int m = n();
        MarketClientState st = MarketClientState.INSTANCE;
        if (m <= 0) {
            pickerOpen = false;
            return;
        }
        int[] trig = pickerTriggerRect();
        int x1 = trig[0];
        int x2 = trig[2];
        int y1 = trig[1] + trig[3] + 2;
        int maxBottom = Math.min(gy + barY - 4, gy + gh);
        if (y1 >= maxBottom) {
            pickerOpen = false;
            return;
        }
        int viewH = Math.min(m * LIST_ROW + 2 * LIST_PAD, 110);
        int y2 = Math.min(y1 + viewH, maxBottom);
        // The panel's content viewport = panel rect minus padding 4 -- scissor,
        // row origin and scroll upper bound all use that one definition; at the bottom there is always a 4px gap under the last row.
        int viewTop = y1 + LIST_PAD;
        int viewBot = Math.max(viewTop + 1, y2 - LIST_PAD);
        int visH = viewBot - viewTop;
        int maxScroll = scrollMax(m * LIST_ROW, visH);
        if (pickerScroll > maxScroll) {
            pickerScroll = maxScroll;                                  // delisting shortened the list mid-frame: clamp on the spot
        }
        pickerPanelRect[0] = x1;
        pickerPanelRect[1] = y1;
        pickerPanelRect[2] = x2;
        pickerPanelRect[3] = y2;
        pickerPanelValid = true;
        g.fill(x1, y1, x2, y2, MODAL_BG);
        g.renderOutline(x1, y1, x2 - x1, y2 - y1, BORDER_MAIN);
        try {
            scissor(g, x1 + LIST_PAD, viewTop, x2 - LIST_PAD, viewBot);
            for (int i = 0; i < m; i++) {
                if (i >= st.stocks.length) {
                    continue;
                }
                Stock s = st.stocks[i];
                if (s == null) {
                    continue;
                }
                int top = viewTop + i * LIST_ROW - pickerScroll;
                if (!rowIntersects(top, top + LIST_ROW, viewTop, viewBot)) {
                    continue;
                }
                boolean sel = i == selected;
                boolean hover = mouseX >= x1 + 2 && mouseX < x2 - 2
                        && mouseY >= top && mouseY < top + LIST_ROW;
                if (sel) {
                    g.fill(x1 + 2, top, x2 - 2, top + LIST_ROW, exchangeSelBg());
                    g.fill(x1 + 2, top, x1 + 4, top + LIST_ROW, DOT_UNLOCKED);
                } else if (hover) {
                    g.fill(x1 + 2, top, x2 - 2, top + LIST_ROW, EX_ROW_HOVER_BG);
                }
                int tx = st.unlocked(i) ? TEXT_WHITE : EX_LOCKED_TX;
                int ty = top + (LIST_ROW - 9) / 2;
                drawItemIcon(g, itemIcon(s), x1 + 6, top + (LIST_ROW - 16) / 2);
                String name = L.tr(s.displayName());
                int priceW = 56;
                drawFitLeft(g, name, x1 + 26, ty, Math.max(10, x2 - x1 - 26 - priceW - 8), tx);
                if (st.suspended(i)) {
                    // A halted stock remains visible and selectable in this picker, so the player can see why its form is disabled.
                    drawFitRight(g, trKey("oremarket.text.0385"), x2 - 6, ty, priceW, SUSPEND_TX);
                } else if (st.unlocked(i) && st.quoteAt(i) && st.price[i] > 0 && Double.isFinite(st.price[i])) {
                    drawFitNumber(g, formatPrice(st.price[i]), x2 - 6, ty, priceW, TEXT_WHITE);
                } else {
                    // Missing quotes / locked / non-positive price -> show the frozen-for-trading notice,
                    // never misusing the loan-overdue recovery text (that is a loan log key, not a quote state).
                    int freezeCol = pickerRowBlocked(st, i) ? PNL_UP : GRAY88;
                    drawFitRight(g, trKey("oremarket.error.stock_frozen"),
                            x2 - 6, ty, priceW, freezeCol);
                }
            }
        } finally {
            g.disableScissor();
        }
        // overlay-style scrollbar: drawn only when content overflows the panel, taking no content width
        if (maxScroll > 0) {
            int trackH = Math.max(1, visH);
            int sbH = Math.max(8, trackH * trackH / (m * LIST_ROW));
            int sbY = viewTop + (trackH - sbH) * pickerScroll / maxScroll;
            g.fill(x2 - 5, sbY, x2 - 2, sbY + sbH, LIST_SB_THUMB);
        }
    }

    /** Form row geometry of the order / limit sub-pages. */
    private static final class TradeFormGeom {
        /**
         * FORM_ALL: direction, buy/sell buttons, price input, quantity input, submit and cancel all on row one,
         * inputs compressed proportionally; FORM_INPUT_ROW: inputs move to row two while submit/cancel stay rightmost on row one;
         * FORM_SEPARATE: on extreme narrowness submit/cancel take a third row alone. In every mode control rects stay inside the content area and never overlap.
         */
        static final int FORM_ALL = 0;
        static final int FORM_INPUT_ROW = 1;
        static final int FORM_SEPARATE = 2;

        final int mode;
        final int inputW;
        final int priceLabelX;
        final int priceBoxX;
        final int qtyLabelX;
        final int qtyBoxX;
        final int submitX;
        final int cancelX;
        /**
         * // x of the third button on the order sub-page (match the selected order);
         * the limit sub-page has no such button, it is -1 there and callers build no control.
         */
        final int takeX;
        /** Unified width for the right-side buttons: base 80, compressed proportionally when the content area cannot fit it, guaranteeing no overrun and no overlap. */
        final int buttonW;
        /**
         * When button text still does not fit after standard -> short label -> reduced padding/spacing, enter the stacked tier:
         * each button takes a full row (spanning the content width), row height stays 20 with 2px spacing, the button area grows accordingly,
         * and the form/list above yields automatically via VerticalLayout and becomes scrollable.
         */
        final boolean stacked;
        final int stackCount;

        TradeFormGeom(int mode, int inputW, int priceLabelX, int priceBoxX,
                      int qtyLabelX, int qtyBoxX, int submitX, int cancelX,
                      int takeX, int buttonW, boolean stacked, int stackCount) {
            this.mode = mode;
            this.inputW = inputW;
            this.priceLabelX = priceLabelX;
            this.priceBoxX = priceBoxX;
            this.qtyLabelX = qtyLabelX;
            this.qtyBoxX = qtyBoxX;
            this.submitX = submitX;
            this.cancelX = cancelX;
            this.takeX = takeX;
            this.buttonW = buttonW;
            this.stacked = stacked;
            this.stackCount = stackCount;
        }

        /** Whether the inputs take row two alone (the TO_INPUT height decision for the layout stack). */
        boolean inputOnSecondRow() {
            return mode != FORM_ALL;
        }

        /** Whether submit/cancel share the direction button's row (the TO_SUBMIT height decision for the layout stack). */
        boolean buttonsOnFirstRow() {
            return mode != FORM_SEPARATE && !stacked;
        }

        /** Total button-area height in the stacked tier; unstacked it remains one row of 20. */
        int buttonsAreaHeight() {
            return stacked ? stackCount * 20 + (stackCount - 1) * 2 : 20;
        }
    }

    /** Form row geometry: prefer one row fitting everything (inputs from 60 compressed proportionally to >= 24); then fall back to two rows; finally three. */
    private TradeFormGeom tradeFormGeom(boolean limit) {
        int left = gx + mkX + 6;
        int right = gx + mkX + mkW - 6;
        int avail = Math.max(0, right - left);
        int dirW = font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0113")));
        int priceW = font.width(L.tr(limit ? cn.oremarket.common.RawText.t("oremarket.text.0192") : cn.oremarket.common.RawText.t("oremarket.text.0092")));
        int qtyW = font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0112")));
        int gap = 4;
        // the order sub-page carries one more button than the limit page (match the selected order); base width 80, compressed proportionally when it will not fit (never beyond the content area)
        int btnCount = limit ? 2 : 3;
        int btnW = Math.max(36, Math.min(80, (avail - (btnCount - 1) * gap) / btnCount));
        int rightFixed = btnCount * btnW + (btnCount - 1) * gap;
        int submitX = right - rightFixed;
        int cancelX = submitX + btnW + gap;
        int takeX = limit ? -1 : right - btnW;
        // First test the stacked tier when standard -> short label -> reduced padding/spacing all fail. Stacked, each button
        // takes its own row at the full content width (text always complete) and the right side stops participating in row one/two layout.
        String[] formKeys = limit
                ? new String[]{"oremarket.button.limit_submit", "oremarket.button.limit_cancel_selected"}
                : new String[]{"oremarket.button.order_submit", "oremarket.button.order_cancel_selected",
                        "oremarket.button.order_trade_selected"};
        boolean stack = btnRowFit(formKeys, btnW, gap, 4) == 2 && btnRowFit(formKeys, btnW, 2, 2) == 2;
        if (stack) {
            btnW = avail;
            rightFixed = 0;
            submitX = left;
            cancelX = left;
            takeX = left;
        }
        // one-row mode: fixed overhead of the left chain direction + buy 40 + sell 40 + price label + input + qty label + input
        int leftFixed = dirW + 4 + 40 + 4 + 40 + 4 + priceW + 6 + 4 + qtyW + 6;
        int inputRoom = avail - leftFixed - rightFixed - gap;
        if (inputRoom >= 2 * 24) {
            int w = Math.min(60, inputRoom / 2);
            int priceLabelX = left + dirW + 4 + 40 + 4 + 40 + 4;
            int priceBoxX = priceLabelX + priceW + 6;
            int qtyLabelX = priceBoxX + w + 4;
            int qtyBoxX = qtyLabelX + qtyW + 6;
            return new TradeFormGeom(stack ? TradeFormGeom.FORM_SEPARATE : TradeFormGeom.FORM_ALL,
                    w, priceLabelX, priceBoxX,
                    qtyLabelX, qtyBoxX, submitX, cancelX, takeX, btnW, stack, btnCount);
        }
        // two-row mode: row two holds the price/qty inputs; row one holds direction + buy/sell + right buttons (merged only when they fit)
        for (int w : new int[]{60, 50}) {
            int priceLabelX = left;
            int priceBoxX = priceLabelX + priceW + 6;
            int qtyLabelX = priceBoxX + w + 4;
            int qtyBoxX = qtyLabelX + qtyW + 6;
            boolean inputsFit = qtyBoxX + w <= right;
            boolean buttonsFitFirstRow = !stack && dirW + 4 + 40 + 4 + 40 + rightFixed + gap * 2 <= avail;
            if (inputsFit && buttonsFitFirstRow) {
                return new TradeFormGeom(TradeFormGeom.FORM_INPUT_ROW, w,
                        priceLabelX, priceBoxX, qtyLabelX, qtyBoxX, submitX, cancelX, takeX,
                        btnW, false, btnCount);
            }
        }
        // three-row fallback: right buttons take row three alone (in the stacked tier every button takes a row); inputs fixed at 50
        int w = 50;
        int priceLabelX = left;
        int priceBoxX = priceLabelX + priceW + 6;
        int qtyLabelX = priceBoxX + w + 4;
        int qtyBoxX = qtyLabelX + qtyW + 6;
        return new TradeFormGeom(TradeFormGeom.FORM_SEPARATE, w,
                priceLabelX, priceBoxX, qtyLabelX, qtyBoxX, submitX, cancelX, takeX, btnW, stack, btnCount);
    }

    /** Row height of the trading page's stock dropdown trigger (between the sub-tab bar and the content area, shared by the three sub-pages). */
    private static final int PICKER_ROW_H = 18;

    /**
     * Outer top edge of the content area = below the sub-tab bar (shared by the three sub-pages; the 4px top padding is added by VerticalLayout).
     * On the trading page subtract the dropdown trigger row as well, so explanation blocks / forms never overlap the dropdown.
     */
    private int tradeBoundsTop() {
        int extra = tab == TAB_TRADE ? PICKER_ROW_H : 0;
        return mkAbsY() + TAB_H + SUB_TAB_H + extra;
    }

    /**
     * Outer bottom edge of the content area = the event bar's upper edge, further clamped by the container bottom:
     * when the event bar wraps taller this edge shrinks on the spot; the 4px bottom padding is taken by VerticalLayout.
     */
    private int tradeBoundsBottom() {
        return Math.max(tradeBoundsTop(), Math.min(gy + barY, mkAbsY() + mkH));
    }

    private int tradePageContainerH() {
        return Math.max(0, tradeBoundsBottom() - tradeBoundsTop());
    }

    /** Top y of the content area's first element = outer top + 4. */
    private int tradeContentTop() {
        return tradeBoundsTop() + 4;
    }

    /** Bottom line y of the content area = outer bottom - 4 (bottom padding). */
    private int tradeContentBottom() {
        return tradeBoundsBottom() - 4;
    }

    /** Usable height of the content area = outer height minus 4 top and 4 bottom padding (kept consistent by the layout engine). */
    private int tradeContentHeight() {
        return Math.max(0, tradePageContainerH() - 8);
    }

    /** Left/right bounds of the content area: 6px margins on each side (the scrollbar hugs the content area's right side, width 6). */
    private int tradeContentLeft() {
        return gx + mkX + 6;
    }

    private int tradeContentRight() {
        return gx + mkX + mkW - 6;
    }

    // ---------------- order / limit sub-page layout stack ----------------

    /**
     * Element indexes shared by the two sub-pages (the limit page gained two semantic explanation rows on top while the order page keeps zero-height placeholders at the same positions,
     * so form/list indexes stay identical across both sub-pages):
     * limit explanation row 1, row 2, direction, wrapped input row, submit button, live current-vs-trigger price hint row,
     * nested list, (order page) hint row.
     */
    private static final int TO_INTRO_BUY = 0;
    private static final int TO_INTRO_SELL = 1;
    private static final int TO_DIR = 2;
    private static final int TO_INPUT = 3;
    private static final int TO_SUBMIT = 4;
    private static final int TO_HINT = 5;
    private static final int TO_LIST = 6;
    private static final int TO_NOTE = 7;

    /** Row heights of the limit page's two top explanation rows and the live hint row. */
    /** Single-line base height for the limit page's two top explanation rows and the live hint row. */
    private static final int LIMIT_INTRO_H = 14;
    /** Wrap cache for explanation text, avoiding per-frame measuring and word-splitting. */
    private static final class WrapCache {
        private String source = "";
        private int width = -1;
        private List<String> lines = List.of();
        private void invalidate() {
            source = "";
            width = -1;
            lines = List.of();
        }
    }

    private final WrapCache orderNoteWrap = new WrapCache();
    private final WrapCache orderSummaryWrap = new WrapCache();
    private final WrapCache limitBuyWrap = new WrapCache();
    private final WrapCache limitSellWrap = new WrapCache();
    private final WrapCache limitHintWrap = new WrapCache();
    /** Wrap cache for the loan page's four estimate rows + the confirm dialog's explanation/consequence rows (recomputed automatically when language/width/numbers change). */
    private final WrapCache[] loanEstWraps = {new WrapCache(), new WrapCache(), new WrapCache(), new WrapCache(), new WrapCache()};
    private final WrapCache loanNoteWrap = new WrapCache();
    private final WrapCache loanWarnWrap = new WrapCache();
    /** Wrap cache for the clear-all confirmation dialog's body / warning rows (invalidated on language or width change). */
    private final WrapCache liquidateBodyWrap = new WrapCache();
    private final WrapCache liquidateWarnWrap = new WrapCache();

    /**
     * Return the cached wrap result; re-split only when text, available width or language changes; row height fixed at 9.
     *
     * (2) when the translation still contains Chinese (the overlay not ready yet), nothing is cached and the next frame recomputes -- feeding half-cut Chinese back to the translation layer is exactly the I18n leak line on English screens.</p>
     */
    private List<String> wrapCached(WrapCache cache, String text, int availW) {
        String translated = L.tr(text);
        boolean cacheable = cacheableTranslation(translated);
        if (!cacheable || cache.width != availW || !cache.source.equals(translated)) {
            List<String> out = new ArrayList<>();
            wrapAllNoEllipsis(translated, Math.max(1, availW), out);
            if (cacheable) {
                cache.source = translated;
                cache.width = availW;
                cache.lines = List.copyOf(out);
                return cache.lines;
            }
            return out;
        }
        return cache.lines;
    }

    /** Block height of the text after wrapping; keeps at least the old height 14 so single lines do not jump against the existing layout. */
    private int wrappedBlockHeight(List<String> lines) {
        return Math.max(LIMIT_INTRO_H, lines.size() * 9 + 4);
    }

    private String orderSummaryRawText(MarketClientState st) {
        String code = selected >= 0 && selected < st.viewSize() ? st.codeAt(selected) : null;
        double bid = 0.0;
        double ask = Double.POSITIVE_INFINITY;
        int count = 0;
        if (code != null) {
            for (MarketClientState.BookInfo b : st.orderBook) {
                if (b == null || b.quantity <= 0 || !code.equals(b.code)) continue;
                count++;
                if (b.buy) bid = Math.max(bid, b.price);
                else if (b.price > 0.0 && b.price < ask) ask = b.price;
            }
        }
        String name = code == null ? "--" : L.tr(st.stocks[selected].displayName());
        String bidText = bid > 0.0 ? formatPrice(bid) : "--";
        String askText = Double.isFinite(ask) ? formatPrice(ask) : "--";
        String spread = bid > 0.0 && Double.isFinite(ask) ? formatPrice(Math.max(0.0, ask - bid)) : "--";
        return trKey("oremarket.market.order.summary", name, bidText, askText, spread, String.valueOf(count));
    }

    private String orderNoteRawText() {
        cn.oremarket.market.MarketSettings noteCfg =
                cn.oremarket.market.MarketSettings.client();
        String noteKey = noteCfg != null && noteCfg.orderAffectPrice
                ? "oremarket.market.order.note.affect_price"
                : "oremarket.market.order.note.no_price";
        return trKey(noteKey);
    }

    /** Outer scroll offset of the order page (independent of the inner bookScroll; preserved across sub-page switches). */
    private int orderOuterScroll = 0;
    /** Outer scroll offset of the limit page (independent of the inner limitScroll; preserved across sub-page switches). */
    private int limitOuterScroll = 0;

    private int pageScroll(boolean limit) {
        return limit ? limitOuterScroll : orderOuterScroll;
    }

    private VerticalLayout pageLayout(boolean limit) {
        return limit ? limitLayout : orderLayout;
    }

    private void layoutOrderPage(MarketClientState st) {
        bookTwoLine = listColTooNarrow(st, tradeContentLeft(), tradeContentRight(), false);
        TradeFormGeom geom = tradeFormGeom(false);
        orderLayout.clear()
                .add(0)   // limit explanation row 1: zero-height placeholder on the order page (zero-height elements take no space and no row gap)
                .add(0)   // limit explanation row 2: same
                .add(20)
                .add(() -> geom.inputOnSecondRow() ? 20 : 0)
                .add(() -> geom.buttonsOnFirstRow() ? 0 : geom.buttonsAreaHeight())
                .add(0)   // live hint row: zero-height placeholder on the order page
                .add(() -> tradeNestedListHeight(st.orderBook.size(), bookRowH()))
                .add(() -> wrappedBlockHeight(wrapCached(orderSummaryWrap, orderSummaryRawText(st),
                        tradeContentRight() - tradeContentLeft()))
                        + wrappedBlockHeight(wrapCached(orderNoteWrap, orderNoteRawText(),
                        tradeContentRight() - tradeContentLeft())));
        orderLayout.layout(tradeContentLeft(), tradeBoundsTop(),
                tradeContentRight() - tradeContentLeft(), tradePageContainerH());
        orderOuterScroll = orderLayout.clampScroll(orderOuterScroll);
        bookScroll = clamp(bookScroll, 0, tradeNestedInnerMax(orderLayout, TO_LIST, st.orderBook.size(), bookRowH()));
    }

    private void layoutLimitPage(MarketClientState st) {
        limitTwoLine = listColTooNarrow(st, tradeContentLeft(), tradeContentRight(), true);
        TradeFormGeom geom = tradeFormGeom(true);
        limitLayout.clear()
                .add(() -> wrappedBlockHeight(wrapCached(limitBuyWrap,
                        trKey(cn.oremarket.common.RawText.t("oremarket.text.0502")),
                        tradeContentRight() - tradeContentLeft())))
                .add(() -> wrappedBlockHeight(wrapCached(limitSellWrap,
                        trKey(cn.oremarket.common.RawText.t("oremarket.text.0503")),
                        tradeContentRight() - tradeContentLeft())))
                .add(20)
                .add(() -> geom.inputOnSecondRow() ? 20 : 0)
                .add(() -> geom.buttonsOnFirstRow() ? 0 : geom.buttonsAreaHeight())
                .add(() -> {   // live current-vs-trigger price hint: zero height when not applicable; when applicable, height follows the wrapped translation
                    String hint = limitHintText(st);
                    return hint == null ? 0
                            : wrappedBlockHeight(wrapCached(limitHintWrap, L.tr(hint),
                                    tradeContentRight() - tradeContentLeft()));
                })
                .add(() -> tradeNestedListHeight(st.limitOrders.size(), limitRowH()));
        limitLayout.layout(tradeContentLeft(), tradeBoundsTop(),
                tradeContentRight() - tradeContentLeft(), tradePageContainerH());
        limitOuterScroll = limitLayout.clampScroll(limitOuterScroll);
        limitScroll = clamp(limitScroll, 0, tradeNestedInnerMax(limitLayout, TO_LIST, st.limitOrders.size(), limitRowH()));
    }

    /** Height of the order / limit nested lists: row count x current row pitch (32 in two-row mode) + header 14. */
    private int tradeNestedListHeight(int count, int rowH) {
        return Math.max(30, Math.min(count * rowH + 14, 200));
    }

    /** Inner maximum scroll of the nested list = total data-row height minus (list height - header 14). */
    private int tradeNestedInnerMax(VerticalLayout layout, int listIndex, int count, int rowH) {
        int viewH = Math.max(0, layout.height(listIndex) - 14 - LIST_PAD);   // Part 2: the inner viewport subtracts the bottom padding
        // Part 2: unified scrollMax (4px gap under the last row at full scroll)
        return scrollMax(count * rowH, viewH);
    }

    /** Part 4: whether the stock column needs the two-row mode -- if ANY code/stock text in the list exceeds the column width even at 0.65 font scale, the whole table switches to two rows (row one: stock/direction/quantity(or trigger price); row two: price/publisher (orders) or quantity/status/cancel (limits)). Ellipses and hiding are forbidden. */
    private boolean listColTooNarrow(MarketClientState st, int left, int right, boolean limit) {
        if (font == null) {
            return false;
        }
        int[] c = limit ? limitColumns(left, right, limitHeaderLabel(left, right)) : orderColumns(left, right);
        int avail = c[1] - c[0] - 2;
        if (avail <= 0) {
            return true;
        }
        if (!limit) {
            for (MarketClientState.BookInfo b : st.orderBook) {
                if (b != null && fitScale(L.tr(b.code), avail) <= 0) {
                    return true;
                }
            }
        } else {
            for (MarketClientState.LimitInfo o : st.limitOrders) {
                if (o != null && fitScale(L.tr(o.code), avail) <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Current drawing rect of the order book nested list (includes the outer scroll offset; bottom clamped to the content area bottom, same rule as painting). */
    /**
     * Inner viewport bottom of a nested list = list box bottom - padding(4).
     * Painting clip, hover highlight and click hit-testing MUST share this one function (invisible pixels never respond).
     */
    private int nestedInnerBottom(int dataTop, int boxBottom) {
        return Math.max(dataTop + 1, boxBottom - LIST_PAD);
    }

    private int[] orderListRect(boolean limit) {
        VerticalLayout layout = pageLayout(limit);
        int top = layout.y(TO_LIST) - pageScroll(limit);
        return new int[]{tradeContentLeft(), top, tradeContentRight(),
                Math.min(top + layout.height(TO_LIST), tradeContentBottom())};
    }

    private void addSideButtons(int left, int y, int mark, boolean currentBuy,
                                java.util.function.Consumer<Boolean> sink) {
        int labelW = font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0113")));
        Button buy = addBtn(Component.literal(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0019"))), left + labelW + 4, y, 40, 20, mark, btn -> sink.accept(true));
        Button sell = addBtn(Component.literal(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0032"))), left + labelW + 4 + 44, y, 40, 20, mark + 1,
                btn -> sink.accept(false));
        if (buy instanceof MarketButton b) b.highlighted = currentBuy;
        if (sell instanceof MarketButton b) b.highlighted = !currentBuy;
        clampWidgetToContent(buy);
        clampWidgetToContent(sell);
    }

    // ================================================== Part 6: five local checks before submit
    // The client uses the same formulas as the server's OrderBookService / LimitOrderService (Fees.additive +
    // buyCost), purely for instant toasts; the server still re-validates and answers with an ErrorPacket shown in the same toast window.

    /** Parse the quantity input without fallback: empty / illegal / non-positive all become 0 (maps to the quantity_must_be_positive hint). */
    private int parseQtyStrict(String text) {
        try {
            long v = Long.parseLong(text.trim());
            if (v <= 0) {
                return 0;
            }
            return (int) Math.min(v, 6400L);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Strict read of the short borrow-quantity field: empty, decimal point, non-positive and unparseable all return 0
     * and the caller pops the short_quantity_invalid hint; a legal value is capped at maxTradeQuantity on the server's exact convention
     * (ShortService.open applies the same min clamp). The EditBox itself only lets digits and a decimal point through; this decimal check catches inputs like 1.5.
     */
    private int parseShortQty() {
        String text = shortQtyText == null ? "" : shortQtyText.trim();
        if (text.isEmpty() || text.indexOf('.') >= 0) {
            return 0;
        }
        long v;
        try {
            v = Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return 0;
        }
        if (v <= 0) {
            return 0;
        }
        MarketSettings ms = MarketSettings.client();
        long cap = ms == null || ms.maxTradeQuantity <= 0 ? 1_000_000L : ms.maxTradeQuantity;
        return (int) Math.min(v, cap);
    }

    /** Order page six checks: price > 0, quantity > 0, buy-side balance, sell-side sellable position, duplicate order, own open orders already at the orderMax cap. null = all pass. */
    private String validateOrderSubmit(MarketClientState st, String code, boolean buy, double price, int qty) {
        String freeze = selectedTradeBlockKey(st);
        if (freeze != null) {
            return freeze;
        }
        if (price <= 0) {
            return "oremarket.error.price_must_be_positive";
        }
        if (qty <= 0) {
            return "oremarket.error.quantity_must_be_positive";
        }
        MarketSettings ms = MarketSettings.client();
        if (ms == null || !st.hasData || !st.hasPlayerState) {
            return null;   // while snapshots are still arriving, do not misjudge balances; the server validation is the backstop
        }
        int idx = st.indexOfCode(code);
        if (buy) {
            long need = Fees.buyCost(price, qty, Fees.additive(ms.taxRate(), ms.orderFeeRate / 2.0));
            if (st.freeCoins() < need) {
                return "oremarket.error.insufficient_credits_for_order";
            }
        } else {
            if (idx < 0 || st.sellable(idx) < qty) {
                return "oremarket.error.insufficient_shares_for_order";
            }
        }
        // Count my open orders in the same pass -- the orderMax gate on the server's exact convention;
        // over-limit is toasted before submission (the server still refuses independently; this is only for feel).
        int mineCount = 0;
        for (MarketClientState.BookInfo b : st.orderBook) {
            if (b != null && b.mine()) {
                mineCount++;
                if (b.buy == buy && code.equals(b.code) && Math.abs(b.price - price) < 1e-9) {
                    return "oremarket.error.duplicate_order";
                }
            }
        }
        if (mineCount >= Math.max(1, ms.orderMax)) {
            return "oremarket.error.order_limit_reached";
        }
        return null;
    }

    /** Limit page five checks: trigger price > 0, quantity > 0, buy-side balance, sell-side sellable position, limit orders already at limitOrderMax. */
    private String validateLimitSubmit(MarketClientState st, String code, boolean buy, double trigger, int qty) {
        String freeze = selectedTradeBlockKey(st);
        if (freeze != null) {
            return freeze;
        }
        if (trigger <= 0) {
            return "oremarket.error.trigger_price_must_be_positive";
        }
        if (qty <= 0) {
            return "oremarket.error.quantity_must_be_positive";
        }
        MarketSettings ms = MarketSettings.client();
        if (st.hasData) {
            int cap = ms == null ? 10 : ms.limitOrderMax;
            if (st.limitOrders.size() >= cap) {
                return "oremarket.error.limit_order_limit_reached";
            }
        }
        if (ms == null || !st.hasData || !st.hasPlayerState) {
            return null;
        }
        int idx = st.indexOfCode(code);
        if (buy) {
            long need = Fees.buyCost(trigger, qty, Fees.additive(ms.taxRate(), ms.limitOrderFeeRate));
            if (st.freeCoins() < need) {
                return "oremarket.error.insufficient_credits_for_limit";
            }
        } else {
            if (idx < 0 || st.sellable(idx) < qty) {
                return "oremarket.error.insufficient_shares_for_order";
            }
        }
        return null;
    }

    /**
     * Submit/cancel/take buttons: x always comes from geometry's submitX/cancelX/takeX (rightmost in the row, chained with the inputs, never overlapping).
     * Labels run standard/short-label adaptive measurement; no language may overflow the button rect.
     */
    private void addOrderButtons(MarketClientState st, TradeFormGeom geom, int y) {
        int w = geom.buttonW;
        Button place = addBtn(Component.literal(btnLabel("oremarket.button.order_submit", w)), geom.submitX, y, w, 20, M_ORDER_SUBMIT, btn -> {
            MarketClientState now = MarketClientState.INSTANCE;
            String code = selectedCodeForForm(now);
            if (code == null) {
                ClientEvents.showToast(trKey("oremarket.error.no_selection"));
                return;
            }
            int qty = parseQtyStrict(orderQtyText);
            double price = parsePrice(orderPriceText);
            String err = validateOrderSubmit(now, code, orderSideBuy, price, qty);
            if (err != null) {
                ClientEvents.showToast(trKey(err));   // Part 6: the local five checks pop the same toast window instantly
                return;
            }
            OreMarketNetwork.CHANNEL.sendToServer(new C2SOrderPacket(C2SOrderPacket.ACTION_PLACE,
                    code, orderSideBuy, qty, price, -1));
            markSent(btn);
        });
        Button cancel = addBtn(Component.literal(btnLabel("oremarket.button.order_cancel_selected", w)),
                geom.cancelX, geom.stacked ? y + 22 : y, w, 20, M_ORDER_CANCEL, btn -> {
            MarketClientState.BookInfo b = MarketClientState.INSTANCE.bookOrder(selectedOrderId);
            // cancel with nothing selected -> the nothing-selected hint; only my own orders can be cancelled:
            // with someone else's order selected the button is greyed (see updateButtonStates for state)
            if (b == null) {
                ClientEvents.showToast(trKey("oremarket.error.no_selection"));
                return;
            }
            if (b.mine()) {
                OreMarketNetwork.CHANNEL.sendToServer(new C2SOrderPacket(C2SOrderPacket.ACTION_CANCEL,
                        "", true, 0, 0, b.id));
                markSent(btn);
            }
        });
        // New: match directly against an order somebody else placed. Only legal selections (exists + not mine + tradable stock + resources enough) can send the packet.
        Button take = addBtn(Component.literal(btnLabelDynamic(
                        orderTakeRaw.isEmpty() ? cn.oremarket.common.RawText.t("oremarket.text.0591") : orderTakeRaw,
                        "oremarket.button.order_trade_selected", w)),
                geom.takeX, geom.stacked ? y + 44 : y, w, 20, M_ORDER_TAKE, btn -> {
                    MarketClientState now = MarketClientState.INSTANCE;
                    MarketClientState.BookInfo b = now.bookOrder(selectedOrderId);
                    if (b == null || b.mine() || orderTakeBlockReason(now, b) != null) {
                        return;
                    }
                    // quantity carries this order's remaining size: how much fits is partially filled server-side against my balance / position
                    OreMarketNetwork.CHANNEL.sendToServer(new C2SOrderPacket(C2SOrderPacket.ACTION_TAKE,
                            b.code, !b.buy, b.quantity, b.price, b.id));
                    markSent(btn);
                });
        clampWidgetToContent(place);
        clampWidgetToContent(cancel);
        clampWidgetToContent(take);
    }

    private void addLimitButtons(MarketClientState st, TradeFormGeom geom, int y) {
        int w = geom.buttonW;
        Button place = addBtn(Component.literal(btnLabel("oremarket.button.limit_submit", w)), geom.submitX, y, w, 20, M_LIMIT_SUBMIT, btn -> {
            MarketClientState now = MarketClientState.INSTANCE;
            String code = selectedCodeForForm(now);
            if (code == null) {
                ClientEvents.showToast(trKey("oremarket.error.no_selection"));
                return;
            }
            int qty = parseQtyStrict(limitQtyText);
            double trigger = parsePrice(limitPriceText);
            String err = validateLimitSubmit(now, code, limitSideBuy, trigger, qty);
            if (err != null) {
                ClientEvents.showToast(trKey(err));
                return;
            }
            OreMarketNetwork.CHANNEL.sendToServer(new C2SLimitPacket(C2SLimitPacket.ACTION_PLACE,
                    code, limitSideBuy, qty, trigger, -1));
            markSent(btn);
        });
        Button cancel = addBtn(Component.literal(btnLabel("oremarket.button.limit_cancel_selected", w)),
                geom.cancelX, geom.stacked ? y + 22 : y, w, 20, M_LIMIT_CANCEL, btn -> {
            MarketClientState.LimitInfo sel = null;
            for (MarketClientState.LimitInfo o : MarketClientState.INSTANCE.limitOrders) {
                if (o != null && o.id == selectedLimitId) {
                    sel = o;
                    break;
                }
            }
            if (sel == null) {
                ClientEvents.showToast(trKey("oremarket.error.no_selection"));
                return;
            }
            OreMarketNetwork.CHANNEL.sendToServer(new C2SLimitPacket(C2SLimitPacket.ACTION_CANCEL,
                    "", true, 0, 0, selectedLimitId));
            markSent(btn);
        });
        clampWidgetToContent(place);
        clampWidgetToContent(cancel);
    }

    private EditBox numericBox(int x, int y, int w, int h, String value, int max, java.util.function.Consumer<String> sink) {
        // Inputs now use the clipped edit box -- the quantity / price fields on scrolling trading sub-pages
        // follow the same rule as buttons on the same row: pixel-level intersection keeps them, the past-the-bound half is scissored, no whole-row hide.
        MarketEditBox box = new MarketEditBox(font, x, y, w, h, Component.literal(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0045"))));
        box.setMaxLength(max);
        box.setValue(value == null ? "" : value);
        box.setBordered(true);
        box.setResponder(text -> {
            String clean = text.replaceAll("[^0-9.]", "");
            int dot = clean.indexOf('.');
            if (dot >= 0) {
                clean = clean.substring(0, dot + 1) + clean.substring(dot + 1).replace(".", "");
            }
            if (clean.length() > max) clean = clean.substring(0, max);
            if (!clean.equals(text)) box.setValue(clean);
            sink.accept(clean);
        });
        addRenderableWidget(box);
        return box;
    }

    /**
     * Trading page control visibility clamp.
     * A control stays visible and interactive while it has at least a 1px intersection with the content area
     * (top = sub-tab bar lower edge + 4, bottom = event bar upper edge - 4); the past-the-bound half is cut by the enableScissor inside render --
     * the clip rect is the same one drawShortPage / drawOrderPage / drawLimitPage use for text rows.
     * Only zero intersection (fully scrolled out) sets visible=false. When the event bar wraps taller or the page scrolls,
     * rebuildWidgets recomputes clip rects and visibility from the new geometry.
     */
    private void clampWidgetToContent(AbstractWidget w) {
        if (w == null) {
            return;
        }
        int top = tradeContentTop();
        int bottom = tradeContentBottom();
        int left = tradeContentLeft();
        int right = tradeContentRight();
        applyPixelClip(w, left, top, right, bottom);
        w.visible = rowIntersects(w.getY(), w.getY() + w.getHeight(), top, bottom)
                && rowIntersects(w.getX(), w.getX() + w.getWidth(), left, right);
    }

    /** Write the pixel clip rect into clip-capable controls (buttons / numeric edit boxes); other controls are untouched. */
    private void applyPixelClip(AbstractWidget w, int x1, int y1, int x2, int y2) {
        if (w instanceof MarketButton mb) {
            mb.setClip(x1, y1, x2, y2);
        } else if (w instanceof MarketEditBox eb) {
            eb.setClip(x1, y1, x2, y2);
        }
    }

    private String selectedCodeForForm(MarketClientState st) {
        return selected >= 0 && selected < st.viewSize() ? st.codeAt(selected) : null;
    }

    /**
     * On the trading pages (short / orders / limits) is the currently selected stock tradable.
     * Returns {@code null} = tradable; a returned language key = the interception reason (button greyed + red warning).
     * (1) nothing selected; (2) locked tier; (3) suspended; (4) quote cache missing / non-positive current price (the -- cases).
     * The client only provides fool-proofing; the server re-validates hard.
     */
    private String selectedTradeBlockKey(MarketClientState st) {
        if (st == null || selected < 0 || selected >= st.viewSize() || selectedCodeForForm(st) == null) {
            return "oremarket.text.0427";
        }
        if (!st.unlocked(selected)) {
            return null;    // locked tiers keep the existing unlockHint and are not overwritten by the frozen text
        }
        if (st.suspended(selected)) {
            return "oremarket.text.0385";
        }
        if (!st.quoteAt(selected) || !(st.price[selected] > 0) || !Double.isFinite(st.price[selected])) {
            return "oremarket.error.stock_frozen";
        }
        return null;
    }

    /** Whether the stock at index i is forbidden to select in the trading dropdown (frozen / no quotes / locked / suspended). */
    private boolean pickerRowBlocked(MarketClientState st, int i) {
        if (st == null || i < 0 || i >= st.viewSize()) {
            return true;
        }
        if (!st.unlocked(i) || st.suspended(i)) {
            return true;
        }
        return !st.quoteAt(i) || !(st.price[i] > 0) || !Double.isFinite(st.price[i]);
    }

    /**
     * Whether the take-this-order button may fire: {@code null} = legal selection, clickable;
     * a returned text = button disabled and shown as the hover reason.
     * The rules match the server's {@code OrderBookService.take} (the server re-validates against packet tampering anyway;
     * here we only make the button clickable exactly when the selection is legal).
     */
    private String orderTakeBlockReason(MarketClientState st, MarketClientState.BookInfo b) {
        if (b == null) {
            return cn.oremarket.common.RawText.t("oremarket.text.0594");
        }
        if (b.mine()) {
            return cn.oremarket.common.RawText.t("oremarket.text.0595");
        }
        if (b.quantity <= 0 || !(b.price > 0)) {
            return cn.oremarket.common.RawText.t("oremarket.text.0596");
        }
        int idx = st.indexOfCode(b.code);
        if (idx < 0) {
            return cn.oremarket.common.RawText.t("oremarket.text.0597");
        }
        if (!st.unlocked(idx)) {
            return st.unlockHint(idx);
        }
        if (st.suspended(idx)) {
            return cn.oremarket.common.RawText.t("oremarket.text.0385");
        }
        if (b.buy) {
            // their side is a buy -> I sell: only the position not locked by my own orders counts
            if (st.sellable(idx) <= 0) {
                return st.frozen[idx] > 0
                        // The whole line goes through one translation key + parameters instead of splicing two Chinese originals.
                        // a spliced Chinese half-sentence is in no translation table and cannot survive the layout's wrapping;
                        // once torn apart it becomes the I18n leak on English screens (T+1 / next-day selling hints).
                        ? trKey("oremarket.trade.hint.frozen_t1", L.money(st.frozen[idx]))
                        : cn.oremarket.common.RawText.t("oremarket.text.0295");
            }
        } else {
            // their side is a sell -> I buy: disable only when even 1 share is unaffordable (partial fills allowed whenever anything can be paid)
            double unit = b.price * (1.0 + orderTakeRate());
            if (!(unit > 0) || st.freeCoins() < Math.ceil(unit)) {
                return cn.oremarket.common.RawText.t("oremarket.text.0605") + (long) Math.ceil(unit) + cn.oremarket.common.RawText.t("oremarket.text.0082");
            }
        }
        return null;
    }

    /** Fee convention for order taking (burn tax + half the order fee), exactly the server's settlement rule; 0 while config is not ready. */
    private static double orderTakeRate() {
        MarketSettings cs = MarketSettings.client();
        return cs == null ? 0.0 : Fees.additive(cs.taxRate(), cs.orderFeeRate / 2.0);
    }

    /** Fill details shown on hover when the selection is legal (computed at their order price; the server may still partially fill against balance / position). */
    private String orderTakeFillInfo(MarketClientState st, MarketClientState.BookInfo b) {
        if (b == null) {
            return null;
        }
        int idx = st.indexOfCode(b.code);
        String name = idx >= 0 ? st.stocks[idx].displayName() : b.code;
        double rate = orderTakeRate();
        if (b.buy) {
            int can = Math.min(b.quantity, idx >= 0 ? st.sellable(idx) : 0);
            long gain = (long) Math.floor(b.price * can * (1.0 - rate));
            return cn.oremarket.common.RawText.t("oremarket.text.0098") + name + " " + can + cn.oremarket.common.RawText.t("oremarket.text.0608") + formatPrice(b.price)
                    + cn.oremarket.common.RawText.t("oremarket.text.0609") + gain + cn.oremarket.common.RawText.t("oremarket.text.0082");
        }
        double unit = b.price * (1.0 + rate);
        int can = unit <= 0 ? 0
                : (int) Math.min((double) b.quantity, Math.floor((double) st.freeCoins() / unit));
        long cost = (long) Math.ceil(b.price * can * (1.0 + rate));
        return cn.oremarket.common.RawText.t("oremarket.text.0090") + name + " " + can + cn.oremarket.common.RawText.t("oremarket.text.0608") + formatPrice(b.price)
                + cn.oremarket.common.RawText.t("oremarket.text.0134") + cost + cn.oremarket.common.RawText.t("oremarket.text.0082")
                + (can < b.quantity ? cn.oremarket.common.RawText.t("oremarket.text.0603") : "");
    }

    /**
     * After an order-book refresh, re-validate the selection: if the selected entry was just filled/cancelled, clear the selection on the spot,
     * so the highlight and button states both return to nothing-selected and no packet is ever sent for a nonexistent order id.
     */
    private void syncOrderSelection(MarketClientState st) {
        if (selectedOrderId >= 0 && st.bookOrder(selectedOrderId) == null) {
            selectedOrderId = -1;
        }
    }

    /** The button label follows the selected row's direction (their sell -> buy this order; their buy -> sell this order). */
    private void syncOrderTakeLabel(MarketButton mb, MarketClientState st) {
        MarketClientState.BookInfo b = st.bookOrder(selectedOrderId);
        String raw = b == null || b.mine() ? cn.oremarket.common.RawText.t("oremarket.text.0591") : (b.buy ? cn.oremarket.common.RawText.t("oremarket.text.0593") : cn.oremarket.common.RawText.t("oremarket.text.0592"));
        if (!raw.equals(orderTakeRaw)) {
            orderTakeRaw = raw;
            // (item 7): dynamic labels also fall back to short labels by button width; overflowing the button rect is forbidden
            mb.setMessage(Component.literal(btnLabelDynamic(raw, "oremarket.button.order_trade_selected",
                    mb.getWidth())));
        }
    }

    /** Clear a stale short selection after a snapshot; row order is not a stable identity. */
    private void syncShortSelection(MarketClientState st) {
        if (selectedShortPositionId >= 0 && selectedShortInfo(st) == null) {
            selectedShortPositionId = -1;
        }
    }

    private MarketClientState.ShortInfo selectedShortInfo(MarketClientState st) {
        // Selection is a server-issued position ID, not a list row number. A refresh or forced liquidation
        // may reorder/remove rows; never let the same row number silently select a different position.
        if (selectedShortPositionId < 0) {
            return null;
        }
        for (MarketClientState.ShortInfo info : st.shorts) {
            if (info.id == selectedShortPositionId) {
                return info;
            }
        }
        return null;
    }

    /**
     * Short opening: four local checks (Requirement 3 item 2). Only when all four pass does the C2S packet go out;
     * any failure immediately pops the matching error toast and returns false.
     * (1) coin balance >= estimated margin + opening tax;
     * (2) remaining short quota >= estimated margin (with level under 2 the credit line is 0: skip this check and let (3) supply the exact text);
     * (3) level >= 2 (criterion = the server-synced creditLimit <= 0);
     * (4) not in the bankruptcy cooldown.
     * The estimates only pre-intercept; the server re-checks with event-corrected rates, and failures come back into the same toast.
     */
    private boolean shortOpenLocalChecksPass(MarketClientState st, String code, int qty) {
        int idx = st.indexOfCode(code);
        //: with quotes missing or a non-positive current price, block locally and send no open packet (the server would refuse again anyway).
        if (idx < 0 || !st.quoteAt(idx) || !(st.price[idx] > 0) || !Double.isFinite(st.price[idx])) {
            ClientEvents.showToast(trKey("oremarket.error.stock_frozen"));
            return false;
        }
        MarketSettings cfg = MarketSettings.client();
        if (cfg == null || !configReady()) {
            return true;   // config snapshot not ready: no local estimate possible, leave it to the server's validation
        }
        double price = st.price[idx];
        long margin = 0L;
        long openTax = 0L;
        if (price > 0.0 && qty > 0) {
            double raw = price * qty;
            margin = (long) Math.ceil(raw * cfg.shortMarginRatio);
            openTax = (long) Math.ceil(raw * Fees.combined(Fees.effectiveFeeRate(cfg.feeRate()), cfg.taxRate()));
            if (st.freeCoins() < margin + openTax) {
                ClientEvents.showToast(trKey("oremarket.error.short_insufficient_credits"));
                return false;
            }
        }
        // The four-way credit / short quota / used / remaining validation went away WITH the whole quota system.
        // Locally only two things matter: can the wallet pay margin + fees, and does the pool still hold that many shares --
        // the server critical section double-checks concurrently; this only screens obvious bad clicks outside the toast window.
        if (st.poolMax[idx] >= 0) {
            if (st.poolLeft[idx] <= 0) {
                ClientEvents.showToast(trKey("oremarket.short.err.pool_empty"));
                return false;
            }
            if (qty > st.poolLeft[idx]) {
                ClientEvents.showToast(trKey("oremarket.short.err.pool_insufficient",
                        String.valueOf(st.poolLeft[idx]), String.valueOf(qty)));
                return false;
            }
        }
        if (st.inBankruptCooldown()) {
            ClientEvents.showToast(trKey("oremarket.error.short_bankruptcy_cooldown",
                    String.valueOf(st.cooldownDaysLeft())));
            return false;
        }
        return true;
    }

    private double parsePrice(String text) {
        try {
            double v = Double.parseDouble(text == null ? "" : text);
            return Double.isFinite(v) && v > 0 ? v : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * Settle-shortfall button: one shared entry for bankruptcy-unforgiven debt + forced-liquidation shortfall.
     * The sent amount is intent only; the server's {@code repayHangover} splits it itself
     * bankruptcy-debt-first, then liquidation-shortfall, and never exceeds the current balance (never negative).
     * When both debts exist the label says settle bankruptcy debt (the one players care about first).
     */
    private void addHangoverPayButton(MarketClientState st, int x0, int y, int innerW) {
        long hangover = Math.max(0L, st.loan.clawbackDebt) + Math.max(0L, st.loan.shortDebt);
        if (hangover <= 0) {
            return;
        }
        addBtn(Component.literal(btnLabel(st.loan.clawbackDebt > 0
                        ? "oremarket.loan.button.pay_clawback"
                        : "oremarket.loan.button.pay_hangover", innerW)),
                x0, y, innerW, LOAN_BTN_H, M_LOAN + 26,
                // a dedicated intent (ACTION_REPAY_HANGOVER): this money goes only to the shortfall debt,
                // never eaten by outstanding loan principal/interest -- the server's repayHangover trims it against the balance.
                btn -> sendLoan(C2SLoanPacket.ACTION_REPAY_HANGOVER, hangover, st, btn));
    }

    /**
     * Loan page controls: product four-way pick, repayment mode four-way pick, installment stepper, amount stepper,
     * quick borrow (borrow 1 / borrow 10 / borrow all), repayment (repay 1 / repay 10 / repay all), prepayment,
     * plus the borrow-confirm dialog's two buttons. During the bankruptcy cooldown only the emergency loan remains as the comeback channel.
     * Every button sends intent + numbers only; the server recomputes quota and legality (the client does no math).
     */
    private void buildLoanWidgets(MarketClientState st) {
        computeLoanLayout(st);
        // (bankruptcy screen): while the settlement banner shows, the page keeps only that single red line and builds no loan-form control at all.
        // The old code merely returned early in drawLoanPanel yet still addRenderableWidget'd the controls,
        // so the credit-loan / period minus / -100 / borrow-max buttons floated above the bankruptcy settlement, all positions closed banner
        // (that is exactly the screenshot players reported). The banner lasts 3 seconds; when it ends the layoutKey change rebuilds automatically.
        if (st.showBankruptBanner()) {
            for (var child : this.children()) {
                if (child instanceof MarketButton mb && mb.mark >= M_LOAN && mb.mark < M_LOAN + 32) {
                    mb.visible = false;
                }
            }
            return;
        }
        // Part 2: control origin and loan viewport origin share one rule (viewport top already minus padding 4)
        int top = mkAbsY() + TAB_H + LIST_PAD - loanScroll;
        int x0 = gx + mkX + 4;
        int innerW = mkW - 8;
        int code = loanStateCode(st);
        int colW = (innerW - 3 * 2) / 4;
        if (code == 1) {
            for (int i = 0; i < 4; i++) {
                final int pick = i;
                addBtn(Component.literal(btnLabel(productBtnKey(i), colW)),
                        x0 + i * (colW + 2), top + loanY[LS_PROD], colW, LOAN_BTN_H,
                        M_LOAN + 10 + i, btn -> {
                            loanProductSel = pick;
                            if (pick == 2) {
                                loanInstallmentsSel = 1;   // emergency loans are forced to 1 installment (5 days)
                            }
                            loanConfirmOpen = false;
                        });
            }
            for (int i = 0; i < 4; i++) {
                final int pick = i;
                addBtn(Component.literal(btnLabel(modeBtnKey(i), colW)),
                        x0 + i * (colW + 2), top + loanY[LS_MODE], colW, LOAN_BTN_H,
                        M_LOAN + 14 + i, btn -> {
                            loanModeSel = pick;
                            loanConfirmOpen = false;
                        });
            }
            // Installment stepper: two buttons flanking the installment N of M text; greyed out at the 1 / 5 boundaries.
            int ith = (innerW - 2 * 2) / 3;
            Button less = addBtn(Component.literal(btnLabel("oremarket.loan.button.inst_less", ith)),
                    x0, top + loanY[LS_TERM], ith, LOAN_BTN_H, M_LOAN + 18,
                    btn -> {
                        loanInstallmentsSel = Math.max(1, loanInstallmentsSel - 1);
                        loanConfirmOpen = false;
                    });
            less.active = loanInstallmentsSel > 1;
            Button more = addBtn(Component.literal(btnLabel("oremarket.loan.button.inst_more", ith)),
                    x0 + 2 * (ith + 2), top + loanY[LS_TERM], innerW - 2 * (ith + 2), LOAN_BTN_H, M_LOAN + 19,
                    btn -> {
                        loanInstallmentsSel = Math.min(loanTermLimit(), Math.max(1, loanInstallmentsSel + 1));
                        loanConfirmOpen = false;
                    });
            more.active = loanInstallmentsSel < loanTermLimit();
            // Amount stepper: -100 / -10 / +10 / +100 / borrow-max in one equal-width row at the same level, never squeezed.
            int gap = 2;
            int cell = (innerW - 4 * gap) / 5;
            int[] marks = {M_LOAN, M_LOAN + 1, M_LOAN + 2, M_LOAN + 3, M_LOAN + 9};
            String[] labels = {"-100", "-10", "+10", "+100",
                    btnLabel("oremarket.loan.button.borrowall", cell)};
            int[] deltas = {-100, -10, 10, 100, 0};
            for (int i = 0; i < 5; i++) {
                final int d = deltas[i];
                int bx = x0 + i * (cell + gap);
                addBtn(Component.literal(i == 4 ? labels[i] : L.tr(labels[i])),
                        bx, top + loanY[LS_AMT], cell, LOAN_BTN_H, marks[i],
                        btn -> {
                            if (d == 0) {
                                loanBorrowAll = true;
                                loanSelAmount = currentBorrowCap(st);
                            } else {
                                loanBorrowAll = false;
                                loanSelAmount = clampAmt(loanSelAmount + d, currentBorrowCap(st));
                            }
                        });
            }
            // Confirm borrow / cancel (moved out of the scroll flow, pinned below the content viewport and above the event bar,
            // row bottom at least 6px from the event bar, row top at least 4px from the scrolled content, never overlapping; sizes and callbacks unchanged)
            int w2 = innerW / 2;
            final boolean busy = loanBusy(st);
            String confirmLabel = busy ? trKey("oremarket.loan.ui.processing")
                    : btnLabel("oremarket.button.loan_confirm", w2);
            addBtn(Component.literal(confirmLabel),
                    x0, loanPinnedTop(), w2, LOAN_BIG_BTN_H, M_LOAN + 4, btn -> {
                        if (!canBorrowNow(st)) {
                            String why = borrowBlockReason(st);
                            if (why != null) {
                                ClientEvents.showToast(why);
                            }
                            return;
                        }
                        loanConfirmOpen = true;   //: borrowing requires first reading the total repayment and the overdue consequences
                        dismissFloatingPanels();  // Part 1: the moment the dialog opens, the dropdown / event detail panel collapse on the spot
                        modalScroll = 0;          // Part 1: every dialog open starts the reading position from the top
                    });
            addBtn(Component.literal(btnLabel("oremarket.button.loan_cancel", innerW - w2 - 2)),
                    x0 + w2 + 2, loanPinnedTop(), innerW - w2 - 2, LOAN_BIG_BTN_H, M_LOAN + 5,
                    // Cancel: close the dialog and reset the chosen amount to 1; no tab switch, existing loans untouched, screen stays open
                    btn -> {
                        loanConfirmOpen = false;
                        loanSelAmount = 1;
                    });
            if (st.loan.clawbackDebt > 0 || st.loan.shortDebt > 0) {
                // Offer an entry whenever any shortfall debt (forced liquidation / bankruptcy-unforgiven) exists.
                // The settle entry covers both clawbackDebt and shortDebt:
                // normal borrowing remains blocked while shortDebt > 0, and the emergency loan demands total assets <= 1000,
                // so the account needs this entry to repay. The button sends the sum of both debts and
                // the server's repayHangover allocates it itself, bankruptcy-unforgiven first, then the liquidation shortfall.
                addHangoverPayButton(st, x0, top + loanY[LS_CLAW], innerW);
            }
            if (p2pEnabled()) {
                int pw = (innerW - 2 * 2) / 3;
                addBtn(Component.literal(btnLabel("oremarket.p2p.button.list", pw)),
                        x0, top + loanY[LS_P2PBTN], pw, LOAN_BTN_H, M_LOAN + 23, btn -> {
                            cn.oremarket.market.MarketSettings cs =
                                    cn.oremarket.market.MarketSettings.client();
                            int rateHundredths = (int) Math.round(st.loan.quoteRate * 10000.0);
                            OreMarketNetwork.CHANNEL.sendToServer(C2SLoanPacket.p2pList(
                                    loanSelAmount, rateHundredths, cs == null ? 0 : cs.loanTermDays));
                            markSent(btn);
                        });
                addBtn(Component.literal(btnLabel("oremarket.p2p.button.cancel", pw)),
                        x0 + pw + 2, top + loanY[LS_P2PBTN], pw, LOAN_BTN_H, M_LOAN + 24,
                        btn -> {
                            OreMarketNetwork.CHANNEL.sendToServer(C2SLoanPacket.p2pCancel(0));
                            markSent(btn);
                        });
                addBtn(Component.literal(btnLabel("oremarket.p2p.button.take", pw)),
                        x0 + 2 * (pw + 2), top + loanY[LS_P2PBTN], innerW - 2 * (pw + 2), LOAN_BTN_H, M_LOAN + 25,
                        btn -> {
                            OreMarketNetwork.CHANNEL.sendToServer(C2SLoanPacket.p2pTake(0));
                            markSent(btn);
                        });
            }
        } else if (code == 2) {
            int third = (innerW - 2 * 2) / 3;
            addBtn(Component.literal(btnLabel("oremarket.loan.button.repay10", third)),
                    x0, top + loanY[LS_REPAY], third, LOAN_BTN_H, M_LOAN + 20, btn -> {
                        if (st.loan.liquidating) {
                            ClientEvents.showToast(trKey("oremarket.loan.ui.liquidating"));
                            return;
                        }
                        sendLoan(C2SLoanPacket.ACTION_REPAY_AMOUNT, 10, st, btn);
                    });
            addBtn(Component.literal(btnLabel("oremarket.loan.button.repay100", third)),
                    x0 + third + 2, top + loanY[LS_REPAY], third, LOAN_BTN_H, M_LOAN + 21, btn -> {
                        if (st.loan.liquidating) {
                            ClientEvents.showToast(trKey("oremarket.loan.ui.liquidating"));
                            return;
                        }
                        sendLoan(C2SLoanPacket.ACTION_REPAY_AMOUNT, 100, st, btn);
                    });
            addBtn(Component.literal(btnLabel("oremarket.loan.button.repayall", third)),
                    x0 + 2 * (third + 2), top + loanY[LS_REPAY], innerW - 2 * (third + 2), LOAN_BTN_H, M_LOAN + 6, btn -> {
                        if (st.loan.liquidating) {
                            ClientEvents.showToast(trKey("oremarket.loan.ui.liquidating"));
                            return;
                        }
                        sendLoan(C2SLoanPacket.ACTION_REPAY, 0, st, btn);
                    });
            addBtn(Component.literal(btnLabel("oremarket.loan.button.prepay", innerW)),
                    x0, top + loanY[LS_PREPAY], innerW, LOAN_BIG_BTN_H, M_LOAN + 22, btn -> {
                        if (st.loan.liquidating) {
                            ClientEvents.showToast(trKey("oremarket.loan.ui.liquidating"));
                            return;
                        }
                        sendLoan(C2SLoanPacket.ACTION_PREPAY, 0, st, btn);
                    });
            // An active loan can carry a forced-liquidation shortfall (a short gap-blowup does exactly this),
            // and normal repayment only flows to the loan's principal/interest; the shortfall needs its own entry or that line of debt could only ever be system-collected.
            addHangoverPayButton(st, x0, top + loanY[LS_CLAW], innerW);
        } else if (code == 4) {
            // The only comeback channel during the bankruptcy cooldown: the emergency loan (quota and rate given by the server from config)
            addBtn(Component.literal(btnLabel("oremarket.loan.button.emergency", innerW)),
                    x0, top + loanY[LS_AMT], innerW, LOAN_BTN_H, M_LOAN + 9, btn -> {
                        loanProductSel = 2;
                        loanSelAmount = Math.max(1, emergencyLimit(st));
                        loanConfirmOpen = true;
                        dismissFloatingPanels();  // Part 1: the dialog takes priority; overlays must never paint above the scrim
                        modalScroll = 0;          // Part 1: the emergency dialog likewise resets its inner scroll
                    });
            // Cooldown periods also need the settle-shortfall entry -- the bankruptcy's
            // clawbackDebt / shortDebt keep blocking every later normal loan until repaid.
            addHangoverPayButton(st, x0, top + loanY[LS_CLAW], innerW);
        }
        if (loanConfirmOpen) {
            int[] r = loanConfirmRect();
            int w = r[2] - r[0];
            int h = r[3] - r[1];
            int half = (w - 20) / 2;
            addBtn(Component.literal(btnLabel("oremarket.button.loan_confirm", half)),
                    r[0] + 8, r[1] + h - LOAN_BTN_H - 8, half, LOAN_BTN_H, M_LOAN + 30,
                    btn -> {
                        //: pressing closes the dialog immediately (the layoutKey contains the dialog state so controls rebuild next frame;
                        // dialog buttons can no longer linger delayed); then the packet goes out and a loading state begins;
                        // during the window the confirm button is greyed, ruling out duplicate clicks.
                        closeAllModals();         // Part 1: close the dialog + reset scroll + recompute the underlying hover right away
                        // Part 2: borrow-all sends STEP_ALL and the server disburses the full dynamic quota at that moment;
                        // other amounts send concrete numbers and the server trims them against the quota (client figures are never trusted).
                        long sendAmt = loanBorrowAll ? C2SLoanPacket.STEP_ALL : loanSelAmount;
                        sendLoan(C2SLoanPacket.ACTION_BORROW, sendAmt, st, btn);
                        loanBusyUntilMs = System.currentTimeMillis() + LOAN_BUSY_MS;
                    });
            addBtn(Component.literal(btnLabel("oremarket.button.loan_cancel", half)),
                    r[0] + 10 + half, r[1] + h - LOAN_BTN_H - 8, half, LOAN_BTN_H, M_LOAN + 31,
                    btn -> closeAllModals());      // Part 1: cancel also goes through the unified close path
        }
        //: loan controls upgraded to pixel-level clipping + scissor (replacing draw-only-when-fully-visible):
        // a control stays interactive while it has any 1px intersection with the viewport; the half past the border is cut by
        // enableScissor inside render (the clip rect is the very same loanViewport drawLoanPanel uses for text rows),
        // and hit areas follow the clip. The fixed button row and dialog buttons are exempt by design and carry no clip rect.
        int[] loanView = loanViewport();
        int loanPinTop = loanPinnedTop();
        int loanPinH = loanPinnedH(st);
        for (var child : this.children()) {
            if (!(child instanceof MarketButton mb) || mb.mark < M_LOAN || mb.mark >= M_LOAN + 32) {
                continue;
            }
            if (mb.mark == M_LOAN + 30 || mb.mark == M_LOAN + 31) {
                mb.visible = true;
            } else if (mb.mark == M_LOAN + 4 || mb.mark == M_LOAN + 5) {
                mb.visible = loanPinH > 0
                        && loanPinTop + LOAN_BIG_BTN_H <= Math.min(mkAbsY() + mkH, gy + barY - 2) + 2;
            } else {
                mb.setClip(loanView[0], loanView[1], loanView[2], loanView[3]);
                mb.visible = rowIntersects(mb.getY(), mb.getY() + mb.getHeight(),
                        loanView[1], loanView[3]);
            }
        }
    }

    /** Send a loan request: product / repayment mode / installment count ride along (the server recomputes everything). */
    private void sendLoan(int action, long amount, MarketClientState st, Button btn) {
        //: null / out-of-range / amount clamping before sending; with no product or installment chosen, no packet and no crash
        if (st == null) {
            return;
        }
        int product = Math.max(0, Math.min(3, loanStateCode(st) == 4 ? 2 : loanProductSel));
        int mode = Math.max(0, Math.min(3, loanModeSel));
        // the client only clamps into [1, product cap] and never sends dirty values; the server's LoanSchedule.clampInstallments agrees.
        int nInst = Math.max(1, Math.min(loanTermLimit(), Math.max(1, loanInstallmentsSel)));
        // STEP_ALL(-1) must go out verbatim so the server disburses the full live quota; only concrete numbers get floored at 0.
        long amt = amount == C2SLoanPacket.STEP_ALL ? C2SLoanPacket.STEP_ALL : Math.max(0, amount);
        OreMarketNetwork.CHANNEL.sendToServer(new C2SLoanPacket(action, amt, product, mode, nInst));
        if (btn != null) {
            markSent(btn);     // existing convention: sending the C2S packet immediately starts the 200ms debounce window
        }
    }

    /** // P2P availability (the server's config switch ships with the snapshot; when off, no lending-notice UI appears at all). */
    private boolean p2pEnabled() {
        cn.oremarket.market.MarketSettings cs =
                cn.oremarket.market.MarketSettings.client();
        return cs != null && cs.loanP2PEnabled;
    }

    /** Maximum selectable installments: emergency loans are forced to 1, other products allow up to 5. */
    private int loanTermLimit() {
        return loanProductSel == 2 ? 1
                : cn.oremarket.common.LoanSchedule.MAX_INSTALLMENTS;
    }

    /** Client-side display fallback for the configured system-loan period; the server remains authoritative. */
    private int configuredLoanTermDays() {
        cn.oremarket.market.MarketSettings cs =
                cn.oremarket.market.MarketSettings.client();
        return cs == null ? cn.oremarket.common.LoanRules.DEFAULT_TERM_DAYS
                : Math.max(1, Math.min(30, cs.loanTermDays));
    }

    /** // Borrowable quota: always the server's dynamic quota (credit-loan figure); the client never derives it locally. */
    private long borrowLimit(MarketClientState st) {
        return Math.max(0, st.loan.creditLimit);
    }

    /**
     * The selected product's amount cap (borrow-all / stepper / per-frame clamping share one definition).
     * The emergency loan reads the live emergency cap from config; every other product uses the server-pushed dynamic quota --
     * never the level's base line again (that was the root cause of borrow-all stuck at 1200).
     */
    private long currentBorrowCap(MarketClientState st) {
        if (st == null || st.loan == null) {
            return 1;
        }
        long cap = loanProductSel == 2 ? emergencyLimit(st) : borrowLimit(st);
        return Math.max(1, cap);
    }

    /** Emergency loan cap (config value pushed live; 0 means an admin disabled it). */
    private long emergencyLimit(MarketClientState st) {
        cn.oremarket.market.MarketSettings cs =
                cn.oremarket.market.MarketSettings.client();
        // The server's dynamicLimit runs LoanRules.emergencyCap(config)
        // -- the stricter of the config value and the per-call hard cap of 100. The client used to display the raw config value,
        // so with config >100 the screen could show 500 while confirmation disbursed only 100.
        return cs == null ? 0
                : cn.oremarket.common.LoanRules
                        .emergencyCap(cs.loanEmergencyCap);
    }

    private String productBtnKey(int i) {
        return switch (i) {
            case 1 -> "oremarket.loan.product.collateral";
            case 2 -> "oremarket.loan.product.emergency";
            case 3 -> "oremarket.loan.product.margin";
            default -> "oremarket.loan.product.credit";
        };
    }

    private String modeBtnKey(int i) {
        return switch (i) {
            case 1 -> "oremarket.loan.mode.interest_first";
            case 2 -> "oremarket.loan.mode.annuity";
            case 3 -> "oremarket.loan.mode.linear";
            default -> "oremarket.loan.mode.balloon";
        };
    }

    /**
     * Full parameter tooltip for loan product buttons (visible on hover: collateral / term / rate factor,
     * numbers taken live from server-pushed config, the same figures as the loanQuote estimate).
     */
    private String loanProductTip(int i, MarketClientState st) {
        cn.oremarket.market.MarketSettings cs =
                cn.oremarket.market.MarketSettings.client();
        double factor = cs == null ? 1.0 : i == 2 ? cs.loanEmergencyRateFactor
                : i == 1 ? cs.loanCollateralRateFactor
                : i == 3 ? cs.loanMarginRateFactor : 1.0;
        if (i == 0) {
            return trKey("oremarket.loan.tip.p0");
        }
        if (i == 2) {
            return trKey("oremarket.loan.tip.p2", L.money(Math.max(0, emergencyLimit(st))),
                    cn.oremarket.common.Num.fixed(factor, 2));
        }
        // Keys are written out as literals (the i18n guard statically checks every registered key); no string concatenation.
        return trKey(i == 1 ? "oremarket.loan.tip.p1" : "oremarket.loan.tip.p3",
                cn.oremarket.common.Num.fixed(factor, 2));
    }

    private long clampAmt(long v, long limit) {
        return Math.max(1, Math.min(v, Math.max(1, limit)));
    }

    // ================================================================ rendering

    @Override
    public void render(GuiGraphics graphics, int screenMouseX, int screenMouseY, float partialTick) {
        refreshTheme();
        // Single entry for proportional scaling: refresh the global transform first (it follows window/scale changes automatically),
        // then map screen mouse coordinates back into canvas space; the whole UI (vanilla controls included) draws inside this transform,
        // layout computed at the GUI Scale=4 baseline; window changes are absorbed by the outer uniform scale only.
        updateUiTransform();
        String langNow = L.langCode();
        if (!langNow.equals(langAtLastRender)) {
            langAtLastRender = langNow;
            logWrapDirty = true;
            // On language change: explanation-block wrap caches and event card line-count caches all invalidate (they are measured on the
            // translation's width); otherwise English would reuse Chinese wraps and get insufficient heights with overlapping text.
            introCacheW = -1;
            orderNoteWrap.invalidate();
            limitBuyWrap.invalidate();
            limitSellWrap.invalidate();
            limitHintWrap.invalidate();
            rebuildWidgets();
        }
        final GuiGraphics g = graphics;
        final int mouseX = toBaseX(screenMouseX);
        final int mouseY = toBaseY(screenMouseY);
        // On small or non-16:9 windows the scaled canvas leaves letterbox margins: first fill a background in SCREEN GUI coordinates,
        // then paint inside the transform, so the edges are clean dark instead of last-frame residue.
        if (offsetX > 0.5D || offsetY > 0.5D) {
            g.fill(0, 0, screenWidth, screenHeight, LETTERBOX);
        }
        PoseStack poseStack = g.pose();
        poseStack.pushPose();
        poseStack.translate(offsetX, offsetY, 0.0D);
        poseStack.scale((float) uiScale, (float) uiScale, 1.0F);
        // Guard five: the whole render is wrapped in try-catch; any unexpected exception logs and continues the next frame,
        // the catch block force-disables scissor to reset clipping state and never clears any UI state.
        try {
            renderFrame(g, mouseX, mouseY, partialTick);
        } catch (Throwable t) {
            logRenderException(t);
            try {
                RenderSystem.disableScissor();
            } catch (Throwable ignored) {
                // a scissor reset failure must not let the exception propagate upward
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Computes the global scale factor and centering offset against the fixed r37 canvas of 640x360.
     * Actual width/height always come from Window, never this.width: rebuildWidgets overwrites the latter with the baseline size.
     * Window changes only scale the whole canvas; no control's relative position, size or hit area changes;
     * hit areas still inverse-map by uiScale, so clicks work identically on small and large windows.
     */
    private void updateUiTransform() {
        if (this.minecraft == null) {
            return;
        }
        Window window = this.minecraft.getWindow();
        int realWidth = Math.max(1, window.getGuiScaledWidth());
        int realHeight = Math.max(1, window.getGuiScaledHeight());
        this.screenWidth = realWidth;
        this.screenHeight = realHeight;
        // the canvas is fixed at r37's 640x360; window changes only alter the overall scale, never any layout metric.
        this.baseWidth = BASE_W;
        this.baseHeight = BASE_H;
        this.uiScale = Math.min(realWidth / (double) this.baseWidth,
                realHeight / (double) this.baseHeight);
        this.offsetX = (realWidth - this.baseWidth * this.uiScale) / 2.0D;
        this.offsetY = (realHeight - this.baseHeight * this.uiScale) / 2.0D;
        // all layout code computes in the baseline canvas coordinate space; the window only applies the uniform scale.
        this.width = this.baseWidth;
        this.height = this.baseHeight;
    }

    /** Screen GUI coordinates -> baseline coordinates: subtract the centering offset, then divide by the scale factor. */
    private int toBaseX(double screenX) {
        return (int) Math.floor((screenX - offsetX) / uiScale);
    }

    private int toBaseY(double screenY) {
        return (int) Math.floor((screenY - offsetY) / uiScale);
    }

    /**
     * GuiGraphics.enableScissor clips in device space: it only multiplies the given GUI coordinates by the window guiScale
     * and never sees our pushed pose scaling. So every clip rect must be converted here into screen GUI coordinates
     * (baseline x uiScale + centering offset) or, at large scales, the clip would be too small and cut content away.
     * disableScissor pops GuiGraphics' own stack and needs no conversion.
     */
    private void scissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
        g.enableScissor(
                (int) Math.floor(x1 * uiScale + offsetX),
                (int) Math.floor(y1 * uiScale + offsetY),
                (int) Math.ceil(x2 * uiScale + offsetX),
                (int) Math.ceil(y2 * uiScale + offsetY));
    }

    /**
     * Part 2 (one convention for every list): from the container rect (x, y, w, h) and padding, the clip rect is
     * {x1, y1, x2, y2} = {x+pad, y+pad, x+w-pad, y+h-pad}.
     * Every scrollable list's enableScissor must go through here -- clipping straight to the container edge used to press
     * the last row's glyph (its 1px descender) onto the panel border (the screenshot-41 symptom).
     * The paired helper is viewportHeight = max(0, h - padTop - padBottom) (see listViewportH).
     */
    private int[] listClip(int x, int y, int w, int h, int pad) {
        // null / degenerate guard: when the container is smaller than 2 x padding, yield down to the usable size (at most to 2px),
        // and never return a zero-area clip rect -- that would make the whole list vanish in tiny windows.
        int px = Math.max(0, Math.min(pad, (w - 2) / 2));
        int py = Math.max(0, Math.min(pad, (h - 2) / 2));
        return new int[]{x + px, y + py, x + w - px, y + h - py};
    }

    /** Part 2: clip rect straight from the container rect (padding defaults to LIST_PAD=4). */
    private int[] listClip(int x1, int y1, int x2, int y2) {
        return listClip(x1, y1, x2 - x1, y2 - y1, LIST_PAD);
    }

    /** Part 2: list visible height = max(0, container height - padTop - padBottom). */
    /**
     * Part 2: event bar scroll upper bound = content height - visible height.
     * BarContentH already includes the 4px bottom padding (matching the clip's bottom inset),
     * so no extra +LIST_PAD here -- otherwise even a single-line event could scroll 4px and its card top would be cut;
     * at full scroll the last card's bottom edge exactly touches the clip line, fully visible.
     */
    private int eventMaxScroll() {
        return Math.max(0, barContentH - barH);
    }

    /** Part 2: stock list clip rect (padding top and bottom subtracted, row origin uses clip[1], left/right hug the container's inner edges). */
    private int[] stockListClip(int colX, int colY, int colW, int colH) {
        int y1 = colY + LIST_PAD;
        return new int[]{colX, y1, colX + colW, Math.max(y1 + 1, colY + colH - LIST_PAD)};
    }

    private int listViewportH(int containerH) {
        return Math.max(0, containerH - 2 * LIST_PAD);
    }

    private void renderFrame(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        // Guard one: when the font is null, skip all text rendering this frame and retry next frame
        if (!fontReady()) {
            return;
        }
        lastScreenMouseX = mouseX;
        lastScreenMouseY = mouseY;
        MarketClientState st = MarketClientState.INSTANCE;
        // The config reply can arrive after init(); apply the initial mode at the first frame that has it,
        // while the one-shot guard keeps later hot-reload packets from overriding a manual toggle.
        applyConfiguredChartMode();
        //: on any language change, every text cache computed from the ORIGINAL invalidates and recomputes (zero Chinese on English screens)
        syncLanguageCaches();
        // Advance the exchange <-> stock-list linking animation first, then compute layout (row/button rects use the latest offsets).
        tickLinkedScroll();
        // Clamp the amount to the selected product's dynamic quota (the old code misused the level's base line of 1200,
        // bouncing borrow-max back to 1200 on the next frame: the screen said 200k while only 1200 was disbursed).
        if (loanInstallmentsSel > loanTermLimit()) {
            loanInstallmentsSel = loanTermLimit();
        }
        if (loanInstallmentsSel < 1) {
            loanInstallmentsSel = 1;
        }
        loanSelAmount = clampAmt(loanSelAmount, currentBorrowCap(st));
        hoverListRow = -1;
        computeLayout(st);
        String key = layoutKey();
        if (!key.equals(builtKey)) {
            rebuildWidgets();
            builtKey = key;
        }
        syncChartModeWidget();
        updateButtonStates(st, mouseX, mouseY);
        // While a dialog exists, the underlying self-drawn layers receive dead coordinates only -- row hover highlights / tooltips /
        // event card hover all extinguish (the painting-side rule for forcing the underlying hovered state empty).
        final int hx = anyModalOpen() ? -99999 : mouseX;
        final int hy = anyModalOpen() ? -99999 : mouseY;

        // Part 7 item 1: outermost 0xFF1A1A1A (fully opaque) + 1px border 0xFF555555
        g.fill(gx - 2, gy - 2, gx + gw + 2, gy + gh + 2, PANEL_ROOT);
        g.renderOutline(gx - 2, gy - 2, gw + 4, gh + 4, BORDER_MAIN);

        pendingTooltip = null;
        eventTipOnCard = false;
        // global clipping hardening: the top bar is an independent parent container; no matter how wide the text, content is only clipped, never painted past the panel.
        // While the dialog is open the underlying page renders FULLY as usual (text / item
        // icons / K-lines / lists nothing skipped); readability comes from the translucent scrim dimming it, not from skipping the drawing at the source.
        try {
            scissor(g, gx + 2, gy, gx + gw - 2, gy + headerH);
            drawHeader(g, st);
        } finally {
            g.disableScissor();
        }
        // Guard three: both the exchange and the market container depend on the player's own data (inventory counts, own positions, own logs);
        // when the player is null / already offline, skip those two components while the top bar and event bar carry on normally.
        //: the exchange is no longer a standalone left container; its content moved into the Exchange tab (drawMarketContainer routes it).
        if (st.hasData && playerReady()) {
            drawMarketContainer(g, st, hx, hy);
        } else if (!st.hasData) {
            pageCentered(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0395")), gx + gw / 2, gy + gh / 2, TEXT_WHITE);
        }
        drawEventBar(g, st, hx, hy);

        // While a dialog is open, underlying controls NEVER get visible=false -
        // page content, lists, buttons and text render unconditionally every frame; the widget layer draws as usual but receives dead coordinates
        // (-99999), so hover highlights and tooltips extinguish naturally (the painting-side rule for forcing hovered empty).
        // After all underlying content is painted, the ScreenEvent.Render.Post hook submits the page,
        // then draws the 0x80000000 translucent scrim and the opaque dialog in separate top-channel batches.
        modalButtonsDeferred = anyModalOpen();
        super.render(g, hx, hy, partialTick);
        //: control outer rings (white selection / blue keyboard-focus frames) are unified above the widget layer,
        // no longer sliced in half by neighbouring controls or section backgrounds.
        drawWidgetRings(g);
        modalButtonsDeferred = false;
        // The modal scrim is deliberately not laid here. GuiGraphics may batch rectangles and text separately,
        // which can leave page text bright above a scrim drawn in this channel. renderTopOverlays submits the
        // completed page first, then draws the scrim as its own batch before the opaque dialog panel.

        // Part 1: while a dialog is open, underlying tooltips never pop (mouse coordinates are already dead-fed; this is the second belt).
        if (pendingTooltip != null && !anyModalOpen()) {
            g.renderTooltip(font, pendingTooltip, mouseX, mouseY);
        }
        // overlays are not drawn here; ScreenEvent.Render.Post paints them last, centrally.
    }

    /** Collapse every non-dialog overlay (dropdown, event detail panel) so the dialog is the sole top layer. */
    private void dismissFloatingPanels() {
        pickerOpen = false;
        clearEventTooltip();
    }

    /**
     * Draw the last top overlays. Use the event's own GuiGraphics and first submit all pending screen content;
     * then place overlays on Forge's highest GUI Z layer so buttons, text and vanilla tooltips cannot cover them.
     * The coordinate system remains the 640x360 baseline canvas (centering offset + uniform scale).
     */
    public void renderTopOverlays(GuiGraphics top, int screenMouseX, int screenMouseY) {
        if (!fontReady()) {
            return;
        }
        // (dialogs-on-top, finalised): while a dialog is open, the event detail panel and the stock dropdown list are not drawn at all -
        // the dialog layer now paints at the top of this channel (same layer as toasts; dialogs always cover all page content and controls).
        final boolean modalBlocking = anyModalOpen();
        if (modalBlocking) {
            if (pickerOpen) {
                pickerOpen = false;
            }
            clearEventTooltip();
        }
        final int bmx = toBaseX(screenMouseX);
        final int bmy = toBaseY(screenMouseY);
        boolean panelVisible = eventTipView != null
                && (eventTipOnCard || eventTooltipContains(bmx, bmy));
        if (eventTipView != null && !panelVisible) {
            clearEventTooltip();   // the mouse has left both card and panel: clear the state exactly like the old drawing path did
        }
        if (modalBlocking) {
            // The dialog layer paints in this very channel, so it must not return early even with no tooltip -
            // the scrim, panels and dialog buttons must appear on the topmost layer every frame.
        } else if (!errorToast.active() && ClientEvents.toast() == null && !panelVisible && !pickerOpen) {
            return;
        }
        PoseStack ps = top.pose();
        boolean pushed = false;
        try {
            // submit the screen body and vanilla tooltip first, then float the overlays above the vanilla tooltip's Z=400.
            top.flush();
            ps.pushPose();
            pushed = true;
            ps.translate(offsetX, offsetY, 5000.0D);
            ps.scale((float) uiScale, (float) uiScale, 1.0F);
            // The mask is submitted in its own top-channel batch. This is intentional: Forge's GUI batching
            // can submit rectangle and text render types in different orders, leaving background text bright
            // when the mask is laid in the main channel. The page is flushed, then the mask, then the dialog.
            if (modalBlocking) {
                drawModalMask(top);
                top.flush();
                drawModalLayer(top, MarketClientState.INSTANCE);
                drawModalButtonsOverlay(top, bmx, bmy);
            } else {
                drawEventTooltipPanel(top, bmx, bmy);
                //: the expanded stock dropdown list always covers inputs and lists (highest z-order).
                drawStockPickerOverlay(top, bmx, bmy);
            }
            // Hard layer order -- the **ErrorToast sits at the very top, above the confirmation dialog**.
            // Root cause: vanilla control render applies its own +100 Z translation (dialog buttons paint at Z=5100); the GUI channel compares depth,
            // so a toast sharing layer 5000 was covered by the button panel -- most visible when three toasts stack into the dialog's area.
            // Now the dialog panel and buttons are flushed as one batch first, then toasts draw alone on their dedicated Z=6000 sub-layer:
            // under any overlap the ErrorToast rides above the confirmation dialog and can never be hidden again.
            top.flush();
            ps.pushPose();
            try {
                ps.translate(0.0D, 0.0D, 1000.0D);
                drawToast(top);
            } finally {
                ps.popPose();
            }
        } catch (Throwable t) {
            logRenderException(t);
            try {
                RenderSystem.disableScissor();
            } catch (Throwable ignored) {
                // a clip-reset failure must not let the exception propagate into the render main loop
            }
        } finally {
            try {
                top.flush();
            } catch (Throwable ignored) {
                // a final flush failure must not propagate into the render main loop either
            }
            if (pushed) {
                ps.popPose();
            }
        }
    }

    /** Guard one: null checks for Minecraft and the font; every text render and font-dependent layout entry passes this gate first. */
    private boolean fontReady() {
        return this.minecraft != null && this.minecraft.font != null && this.font != null;
    }

    /** Guard three: the current player exists and still occupies the local-player slot (false during disconnect / world switch). */
    private boolean playerReady() {
        return this.minecraft != null && this.minecraft.player != null;
    }

    /**
     * Guard two: the single item-icon entry. With a null or EMPTY ItemStack a 16x16 grey placeholder (0xFF666666) draws;
     * ItemRenderer (the guts of g.renderItem) is never called on an empty stack.
     */
    private void drawItemIcon(GuiGraphics g, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) {
            g.fill(x, y, x + 16, y + 16, ICON_PLACEHOLDER);
            return;
        }
        g.renderItem(stack, x, y);
    }

    /** Single page-text entry (with a dialog open the background still draws normally; readability is the translucent scrim's job). */
    private void pageText(GuiGraphics g, String text, int x, int y, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        g.drawString(font, text, x, y, color);
    }

    /** Single page-centered-text entry (same: never dropped because a dialog opened). */
    private void pageCentered(GuiGraphics g, String text, int cx, int y, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        g.drawCenteredString(font, text, cx, y, color);
    }

    /** The exchange no longer docks permanently on the left; its panel top = the market container's content-area top (below the 20px tab bar). */
    private int exAbsY() {
        return gy + midY + TAB_H - midScroll;
    }

    // ---------------- exchange content viewport (one rule for clicks / hover / clipping) ----------------
    //
    // Whatever the scissor cuts off during painting is invisible to players, but hit areas historically were not clipped along with it,
    // so clicking outside the frame (e.g. the bottom event bar) could still hit the deposit/withdraw button of a half-clipped row
    // and even pop a disabled-reason tooltip. The methods below intersect every hit area with the viewport:
    // invisible pixels never respond.
    private int exViewTop() {
        return exAbsY() + exRowTop + LIST_PAD;            // Part 2: the viewport subtracts padding=4
    }

    private int exViewBottom() {
        return exAbsY() + exFooterY - LIST_PAD;           // Part 2: at full scroll the last row keeps >= 4px from the bottom border
    }

    private int exViewLeft() {
        return gx + exX + 1;
    }

    private int exViewRight() {
        return gx + exX + exW - 1;
    }

    /** Whether the exchange content viewport is valid (returns false while the panel is un-laid out or too short; all hit areas die with it). */
    private boolean exViewportValid() {
        // Part 2: with the viewport redefined as minus-padding, hit-area validity judges on the shrunk size too
        return exVisibleH() > 0 && exW - 2 * LIST_PAD > 4 && exH > 4;
    }

    /** Whether a point hits hit-area INTERSECT exchange viewport: exactly drawExchange's scissor rule. */
    private boolean exHit(double mx, double my, double x1, double y1, double x2, double y2) {
        if (!exViewportValid()) {
            return false;
        }
        if (mx < x1 || mx >= x2 || my < y1 || my >= y2) {
            return false;
        }
        double vx1 = Math.max(x1, exViewLeft());
        double vy1 = Math.max(y1, exViewTop());
        double vx2 = Math.min(x2, exViewRight());
        double vy2 = Math.min(y2, exViewBottom());
        return mx >= vx1 && mx < vx2 && my >= vy1 && my < vy2;
    }

    private int mkAbsY() {
        return gy + midY + (stacked ? exH + STACK_GAP : 0) - midScroll;
    }

    // ---------------- top bar

    /** Single-row top bar height 28; two-row top bar height 52 (the middle area automatically loses 24). */
    private static final int HEADER_H_1 = 28;
    private static final int HEADER_H_2 = 52;
    private static final int HEADER_GAP = 6;
    private static final int HEADER_ICON_W = 10;   // coin icon 8 + gap 2 to the text
    private static final int HEADER_CLOSE_W = 42;  // reserve room for the close button on the right (28 wide + margins)
    private boolean headerTwoRows;
    private int headerH = HEADER_H_1;
    private double headerScaleOne = 1.0;
    private double headerScaleRow1 = 1.0;
    private double headerScaleRow2 = 1.0;
    private String headerSig = "";
    // top-bar texts in the current language (recomposed each frame; scale / two-row decisions rerun only when the whole signature changes -- never per-frame recomputes)
    private String hTitle = "", hDay = "", hHint = "", hBal = "", hTier = "", hPeak = "",
            hLoan = "", hAssets = "", hPnl = "", hName = "";

    /** Width ladder: returns the smallest step fitting availW; 0 when even 0.65 overflows (the caller switches to two rows). */
    private double ladderOrZero(int baseW, int availW) {
        if (baseW <= availW) {
            return 1.0;
        }
        for (double sc = 0.95; sc >= FONT_MIN_SCALE - 1e-9; sc -= 0.05) {
            if (baseW * sc <= availW + 0.5) {
                return Math.round(sc * 100.0) / 100.0;
            }
        }
        return 0.0;
    }

    /** Top-bar coin icon: an 8x8 gold square with a dark outline (PNG textures banned; pure pixel color-mixing). */
    private void drawCoinIcon(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 8, y + 8, PANEL_ROOT);
        g.fill(x + 1, y + 1, x + 7, y + 7, COIN_DARK);
        g.fill(x + 2, y + 2, x + 6, y + 4, GOLD);
        g.fill(x + 2, y + 5, x + 4, y + 6, COIN_LIGHT);
    }

    /** Draw a sequence of top-bar texts in order (6px gaps); returns the ending x; empty strings are skipped. */
    private int drawHeaderSeq(GuiGraphics g, int x, int y, double sc,
                              String[] texts, int[] colors) {
        for (int i = 0; i < texts.length; i++) {
            String t = texts[i];
            if (t == null || t.isEmpty()) {
                continue;
            }
            x += drawScaled(g, t, x, y, sc, colors[i]) + HEADER_GAP;
        }
        return x;
    }

    private int sumW(String... texts) {
        int w = 0;
        for (String t : texts) {
            if (t != null && !t.isEmpty()) {
                w += font.width(t) + HEADER_GAP;
            }
        }
        return Math.max(0, w - HEADER_GAP);
    }

    /** Compose every top-bar element's text (missing server data shows the -- placeholder; no element is ever hidden). */
    private void computeHeaderStrings(MarketClientState st) {
        hTitle = L.tr(cn.oremarket.common.RawText.t("oremarket.text.0186"));
        long tradeDay = st.loan == null ? 0L : st.loan.tradeDay;
        hDay = L.tr(cn.oremarket.common.RawText.t("oremarket.text.0058") + tradeDay
                + cn.oremarket.common.RawText.t("oremarket.text.0221"));
        hHint = L.tr(MarketKeys.hotkeyHint());
        hBal = L.tr(cn.oremarket.common.RawText.t("oremarket.text.0115") + formatOre(st.balance));
        hTier = L.tr(cn.oremarket.common.RawText.t("oremarket.text.0117") + st.tier() + "/" + Stock.MAX_TIER);
        hPeak = L.tr(cn.oremarket.common.RawText.t("oremarket.text.0104") + formatOre(st.peak));
        if (st.inBankruptCooldown()) {
            hLoan = trKey("oremarket.header.loan.bankrupt", st.cooldownDaysLeft());
        } else if (st.hasLoan()) {
            hLoan = trKey("oremarket.header.loan.active", st.loan == null ? 0 : st.loan.loanAmount);
        } else {
            hLoan = trKey("oremarket.header.loan.none");
        }
        boolean missing = st.positionCacheMissing();
        hPnl = L.tr(missing ? cn.oremarket.common.RawText.t("oremarket.text.0290")
                : cn.oremarket.common.RawText.t("oremarket.text.0166") + L.moneySigned(st.totalPnl));
        hAssets = L.tr(missing ? cn.oremarket.common.RawText.t("oremarket.text.0291")
                : cn.oremarket.common.RawText.t("oremarket.text.0167") + roundNum(st.totalAssets));
        Stock sel = selectedStock();
        hName = sel == null ? "" : L.tr(sel.displayName());
    }

    /**
     * Top-bar layout decision (only a changed string signature reruns it per frame):
     * 1) measure the total width at 1.0 (elements + 6px gaps); fits -> single row;
     * 2) otherwise walk the 1.0 -> 0.65 ladder remeasuring;
     * 3) still overflowing at 0.65 -> two rows 's mandated left-to-right order
     * trading day -> selected ore -> total assets -> total P&L -> peak -> level -> coins -> loan,
     * laid out as: row one icon/title/trading day/selected ore/total assets/total P&L (close button at the right),
     * row two peak/level/coins/loan/hotkey hint, each row running its own ladder;
     * 4) when a row still overflows at 0.65, keep shrinking past the floor step -- hiding, ellipses and overflowing the root container are all banned.
     */
    private void layoutHeader(MarketClientState st) {
        if (!fontReady()) {
            return;
        }
        computeHeaderStrings(st);
        String sig = gw + "|" + L.langCode() + "|" + hBal + "|" + hTier + "|" + hDay + "|" + hHint
                + "|" + hPeak + "|" + hLoan + "|" + hAssets + "|" + hPnl + "|" + hName + "|" + (st.hasData ? "d" : "n");
        if (sig.equals(headerSig)) {
            return;
        }
        headerSig = sig;
        int availOne = gw - 8 - HEADER_CLOSE_W;
        int baseOne = HEADER_ICON_W + sumW(hTitle, hDay, hName, hAssets, hPnl, hPeak, hTier, hBal, hLoan)
                + 9 * HEADER_GAP;
        double s = ladderOrZero(baseOne, availOne);
        if (s > 0) {
            headerTwoRows = false;
            headerScaleOne = s;
            headerH = HEADER_H_1;
            return;
        }
        headerTwoRows = true;
        headerH = HEADER_H_2;
        int baseR1 = HEADER_ICON_W + sumW(hTitle, hDay, hName, hAssets, hPnl) + 5 * HEADER_GAP;
        int availR1 = gw - 8 - HEADER_CLOSE_W;
        double r1 = ladderOrZero(baseR1, availR1);
        headerScaleRow1 = r1 > 0 ? r1 : fitScaleW(baseR1, availR1);
        int baseR2 = sumW(hPeak, hTier, hBal, hLoan, hHint) + 4 * HEADER_GAP;
        int availR2 = gw - 12;
        double r2 = ladderOrZero(baseR2, availR2);
        headerScaleRow2 = r2 > 0 ? r2 : fitScaleW(baseR2, availR2);
    }

    private void drawHeader(GuiGraphics g, MarketClientState st) {
        int pnlCol = st.totalPnl > 0 ? PNL_UP : st.totalPnl < 0 ? PNL_DOWN : PNL_FLAT;
        int loanCol = st.inBankruptCooldown() ? TOAST_RED : FLAT;
        if (headerTwoRows) {
            int y1 = gy + 8;
            int y2 = gy + 8 + 22;
            int x = gx + 6;
            drawCoinIcon(g, x, y1);
            x += HEADER_ICON_W;
            // row one: icon, title, trading day, selected ore, total assets, total P&L; close button on the right (permanent widget)
            x = drawHeaderSeq(g, x, y1, headerScaleRow1,
                    new String[]{hTitle, hDay, hName, hAssets, hPnl},
                    new int[]{GOLD, FLAT, TEXT_WHITE, TEXT_WHITE, pnlCol});
            // row two: peak, level, coins, loan state, hotkey hint (the same left-to-right order continued)
            drawHeaderSeq(g, gx + 6, y2, headerScaleRow2,
                    new String[]{hPeak, hTier, hBal, hLoan, hHint},
                    new int[]{GOLD, GOLD, TEXT_WHITE, loanCol, HINT_GRAY});
            return;
        }
        // single-row mode: mandated left-to-right order = title icon -> trading day -> selected ore -> total assets
        // -> total P&L -> peak -> level -> coins -> loan state
        int y = gy + (HEADER_H_1 - 9) / 2;
        int x = gx + 6;
        drawCoinIcon(g, x, y - 1);
        x += HEADER_ICON_W;
        drawHeaderSeq(g, x, y, headerScaleOne,
                new String[]{hTitle, hDay, hName, hAssets, hPnl, hPeak, hTier, hBal, hLoan},
                new int[]{GOLD, FLAT, TEXT_WHITE, TEXT_WHITE, pnlCol,
                        GOLD, GOLD, TEXT_WHITE, loanCol});
    }

    // ---------------- exchange container ----------------

    private void drawExchange(GuiGraphics g, MarketClientState st, int mouseX, int mouseY) {
        int x = gx + exX;
        int y = exAbsY();
        // The exchange content plate uses the same dark PANEL_ROOT (0xFF1A1A1A) as the loan page,
        // dropping the light PANEL_SOFT (0xFF2B2B2B) plate; layout and button positions unchanged.
        g.fill(x, y, x + exW, y + exH, PANEL_ROOT);
        g.renderOutline(x, y, exW, exH, BORDER_MAIN);
        if (exW > 8 && exH > 8) {
            g.renderOutline(x + 1, y + 1, exW - 2, exH - 2, HIGHLIGHT_IN);
        }
        drawSectionTitle(g, cn.oremarket.common.RawText.t("oremarket.text.0413"), x + 4, y + exTitleY + 2, exW - 10, GOLD);
        if (!configReady()) {
            drawCenteredHint(g, x, y, exW, exH, loadingText());
            return;
        }
        if (!playerReady()) {
            return;
        }
        int now = ClientEvents.clientTick();
        int viewTop = exViewTop();
        int viewBot = exViewBottom();
        int visH = viewBot - viewTop;
        if (visH < EX_ROW_H) {
            drawFitCentered(g, cn.oremarket.common.RawText.t("oremarket.text.0190"),
                    x + 4, x + exW - 4, y + (exH - 9) / 2, GRAY88);
            return;
        }
        int listL = x + exListL;
        int listR = x + exListR;
        int opL = x + exOpL;
        int opR = x + exOpR;
        // vertical divider of the two columns
        g.fill(listR + 1, viewTop, listR + 2, viewBot, EX_SEP_COLOR);
        // ---- left column: pixel-level scissor + half-row painting ----
        try {
            scissor(g, listL, viewTop, listR, viewBot);
            int m = n();
            for (ExchangeRow row : exRows) {
                if (!rowIntersects(row.top, row.top + row.height, viewTop, viewBot)) {
                    continue;
                }
                if (row.index < 0 || row.index >= st.stocks.length) {
                    continue;
                }
                Stock s = st.stocks[row.index];
                boolean unlocked = st.unlocked(row.index);
                boolean hover = !dragAny() && exHit(mouseX, mouseY, row.left - 2, row.top,
                        row.right + 2, row.top + row.height);
                // Row background / selection stripe / dividers are all clamped into the clip viewport,
                // pixels inside the padding are no longer shaved to half a line (the old code lost 1-2px on a selected row's left edge)
                final int fx1 = Math.max(row.left - 2, listL);
                final int fx2 = Math.min(row.right + 2, listR);
                if (row.index == selected) {
                    g.fill(fx1, row.top, fx2, row.top + row.height, exchangeSelBg());
                    g.fill(fx1, row.top, fx1 + 2, row.top + row.height, DOT_UNLOCKED);
                } else if (hover) {
                    g.fill(fx1, row.top, fx2, row.top + row.height, EX_ROW_HOVER_BG);
                }
                if (row.index < m - 1) {
                    g.fill(fx1, row.top + row.height - 1, fx2, row.top + row.height, EX_SEP_COLOR);
                }
                int tx = unlocked ? TEXT_WHITE : EX_LOCKED_TX;
                int ty = row.top + (EX_ROW_H - 9) / 2;
                drawItemIcon(g, itemIcon(s), row.iconX, row.top + (EX_ROW_H - 16) / 2);
                if (row.nameW > 2) {
                    drawFitLeft(g, row.stockName, row.nameX, ty, row.nameW, tx);
                }
                int have = countLocal(s);
                String haveStr = have > 9999 ? "9999+" : String.valueOf(have);
                int haveW = font.width(haveStr);
                pageText(g, haveStr, row.countRight - haveW, ty, tx);
                drawFitRight(g, row.rateText, row.rateRight, ty, EX_RATE_W, EX_RATE_TX);
            }
            int exMax = maxExScroll();
            if (exMax > 0 && exScroll < exMax) {
                int lineL = listL + 6;
                int lineR = listR - 6;
                if (lineR > lineL) {
                    g.fill(lineL, viewBot - 5, lineR, viewBot - 4, DARK_BG);
                }
            }
        } finally {
            g.disableScissor();
        }
        // ---- right column
        if (exOpEnough) {
            int ox = opL + 4;
            int inner = exOpInnerW;
            // middle: selected ore details (icon, name, current rate) -- grown to 82px now the search row is gone
            int dy = y + exDetailY;
            g.renderOutline(ox - 1, dy - 1, inner + 2, 82 + 2, BORDER_MAIN);
            if (selected >= 0 && selected < st.stocks.length) {
                Stock s = st.stocks[selected];
                drawItemIcon(g, itemIcon(s), ox + 4, dy + 16);
                drawFitLeft(g, s.displayName(), ox + 24, dy + 18, inner - 28, TEXT_WHITE);
                String rate = cn.oremarket.common.RawText.t("oremarket.text.0281")
                        + s.exchangeRate()
                        + cn.oremarket.common.RawText.t("oremarket.text.0082");
                drawFitLeft(g, rate, ox + 4, dy + 48, inner - 8, EX_RATE_TX);
            } else {
                drawFitCentered(g, trKey("oremarket.exch.err.pick"), ox, ox + inner, dy + 36, GRAY88);
            }
            for (ExOpButton b : exOps) {
                drawExOpButton(g, b, mouseX, mouseY, now);
            }
        }
        if (pendingTooltip == null) {
            for (ExOpButton b : exOps) {
                if (!b.enabled && !b.inDebounce()
                        && b.contains(mouseX, mouseY)
                        && b.disabledReason != null) {
                    pendingTooltip = Component.literal(L.tr(b.disabledReason)).withStyle(ChatFormatting.GRAY);
                    return;
                }
            }
        }
    }

    /** Exchange inner vertical scrollbar: overlay style, width 4; track 0xFF2A2A2A, thumb 0xFF666666, hover 0xFF888888. */
    /** Whether a click lands inside the exchange or market container rect (used by the not-ready click-to-retry check). */
    private boolean clicksInContainers(double mx, double my) {
        int ex = gx + exX;
        int mk = gx + mkX;
        return (mx >= ex && mx < ex + exW && my >= exAbsY() && my < exAbsY() + exH)
                || (mx >= mk && mx < mk + mkW && my >= mkAbsY() && my < mkAbsY() + mkH);
    }



    /** Unlock status dot: solid blue (unlocked) / hollow grey (locked); suspension keeps its original colour and style. */
    private void drawStatusDot(GuiGraphics g, int x, int y, boolean unlocked, boolean suspended) {
        if (suspended) {
            g.fill(x, y, x + 6, y + 6, SUSPEND_TX);
            return;
        }
        if (unlocked) {
            g.fill(x, y, x + 6, y + 6, DOT_UNLOCKED);
            return;
        }
        // hollow: just a 1px outline ring, nothing filled inside
        g.fill(x, y, x + 6, y + 1, DOT_LOCKED);
        g.fill(x, y + 5, x + 6, y + 6, DOT_LOCKED);
        g.fill(x, y, x + 1, y + 6, DOT_LOCKED);
        g.fill(x + 5, y, x + 6, y + 6, DOT_LOCKED);
    }

    /** Draw one grey hint line at the container's centre (loading / load failed, click to retry / not enough space etc.). */
    private void drawCenteredHint(GuiGraphics g, int x, int y, int w, int h, String text) {
        if (w <= 4 || h <= 4 || text == null || text.isEmpty()) {
            return;
        }
        drawFitCentered(g, text, x + 4, x + w - 4, y + (h - 9) / 2, GRAY88);
    }


    /**
     * The ItemStack behind an item icon (guard-two fallback: a null item returns the empty stack and drawItemIcon paints the placeholder).
     */
    private static ItemStack itemIcon(Stock s) {
        return ItemIconCache.of(s);
    }

    /** Paint a right-column action button: background colours are grouped per kind, hit area = the visual rect, what you see is what you click. */
    private void drawExOpButton(GuiGraphics g, ExOpButton b, int mouseX, int mouseY, int now) {
        boolean hover = b.contains(mouseX, mouseY);
        // Four buttons in two colour families -- deposit / deposit all share green, withdraw / withdraw all share red
        // (the EX_SEARCH_* constants are now unreferenced and kept harmlessly).
        int base = (b.kind == ExOpButton.DEPOSIT || b.kind == ExOpButton.DEPOSIT_ALL) ? EX_DEP_BG
                : EX_WDR_BG;
        int hoverCol = (b.kind == ExOpButton.DEPOSIT || b.kind == ExOpButton.DEPOSIT_ALL) ? EX_DEP_HOVER
                : EX_WDR_HOVER;
        boolean pressed = now < b.pressUntil;
        int bg = !b.enabled ? EX_BTN_DISABLED_BG : (hover || pressed ? hoverCol : base);
        g.fill(b.drawX, b.drawY, b.drawX + b.drawW, b.drawY + b.drawH, bg);
        g.renderOutline(b.drawX, b.drawY, b.drawW, b.drawH, pressed ? 0xAAFFFFFF : 0x66FFFFFF);
        String label = btnLabel(b.stdKey(), b.drawW);
        int tw = font.width(label);
        int color = b.enabled ? TEXT_WHITE : EX_BTN_DISABLED_TX;
        int ty = b.drawY + (b.drawH - 9) / 2;
        int maxTextW = b.drawW - 4;
        if (tw > maxTextW && tw > 0) {
            float s = Math.max(0.5f, maxTextW / (float) tw);
            PoseStack pose = g.pose();
            pose.pushPose();
            pose.translate(b.drawX + b.drawW / 2.0F, ty + 4.5F, 0.0F);
            pose.scale(s, s, 1.0F);
            pageText(g, label, -tw / 2, -4, color);
            pose.popPose();
        } else {
            pageText(g, label, b.drawX + (b.drawW - tw) / 2, ty, color);
        }
    }

    // ---------------- market container (three tabs) ----------------

    private void drawMarketContainer(GuiGraphics g, MarketClientState st, int mouseX, int mouseY) {
        int x = gx + mkX;
        int y = mkAbsY();
        if (!configReady()) {
            // every number on the quotes / loan / log pages comes from the server config cache; until it is ready, only a hint shows
            drawCenteredHint(g, x, y + TAB_H, mkW, Math.max(20, mkH - TAB_H), loadingText());
            return;
        }
        // Tab bar: 5 tabs with no inner padding, flush side by side, each floor((container width - 12) / 5) wide,
        // the last tab absorbs the remainder; 6px margins left and right. Height 20 is never compressed; the whole rect is the click area.
        for (int t = 0; t < TAB_COUNT; t++) {
            int tx = x + tabLeft(t);
            int tw = tabWidth(t);
            boolean active = t == tab;
            boolean hover = mouseX >= tx && mouseX < tx + tw && mouseY >= y && mouseY < y + TAB_H;
            int bg = active ? TAB_SELECTED_BG : (hover ? TAB_HOVER_BG : TAB_NORMAL_BG);
            g.fill(tx, y, tx + tw, y + TAB_H, bg);
            pageCentered(g,L.tr(TAB_NAMES[t]), tx + tw / 2, y + (TAB_H - 9) / 2,
                    active ? TEXT_WHITE : TEXT_SOFT);
        }
        int contentTop = y + TAB_H;
        if (st.snapshotStatus == MarketClientState.SnapshotStatus.FAILED) {
            // quote snapshot timeout: all five tabs show quotes failed, click to retry
            drawCenteredHint(g, x, y + TAB_H, mkW, Math.max(20, mkH - TAB_H),
                    cn.oremarket.common.RawText.t("oremarket.text.0450"));
            return;
        }
        // trading / leaderboard tabs: a 16px sub-tab bar hugs the main tab bar (content area height shrinks accordingly)
        if (hasSubTabs()) {
            drawSubTabBar(g, x, contentTop, mouseX, mouseY);
        }
        // global clipping hardening: the market container is the shared parent of sub-pages and nested lists;
        // its clip rect = [container content top, min(container bottom, event bar top - 4)], exactly the sub-page content-area rule;
        // the whole draw is wrapped in try/finally so any exception still hits disableScissor (GuiGraphics pops its own stack).
        int containerBottom = Math.min(y + mkH, gy + barY - 4);
        try {
            scissor(g, x + 1, contentTop, x + mkW - 1, containerBottom);
            switch (tab) {
                case TAB_EXCH -> drawExchange(g, st, mouseX, mouseY);
                case TAB_TRADE -> drawTradePage(g, st, x, contentTop, mouseX, mouseY);
                case TAB_LOAN -> drawLoanPanel(g, st, x, contentTop);
                case TAB_RANK -> drawRankPage(g, st, x, contentTop, mouseY);
                case TAB_LOG -> drawLogPage(g, st, x, contentTop);
                default -> drawMarketPage(g, st, x, contentTop, mouseX, mouseY);
            }
        } finally {
            g.disableScissor();
        }
        g.renderOutline(x, y, mkW, mkH, BORDER_MAIN);
        if (mkW > 8 && mkH > 8) {
            g.renderOutline(x + 1, y + 1, mkW - 2, mkH - 2, HIGHLIGHT_IN);
        }
        if (tab == TAB_MARKET) {
            hoverSuspendCheck(st, mouseX, mouseY);
        }
        // Clear-all / loan dialogs moved to renderTopOverlays' top channel
        // (all background drawn -> widget layer -> top flush -> full scrim -> opaque panels -> dialog text ->
        // dialog buttons -> toasts); the main channel no longer draws any dialog content.
    }

    // ---------------- tab bar geometry

    /** Main tab usable width = container width - 6 per side; the first four are floor(usable/5), the last absorbs the remainder. */
    private int tabBaseWidth() {
        return Math.max(8, (mkW - 12) / TAB_COUNT);
    }

    private int tabWidth(int t) {
        int base = tabBaseWidth();
        int used = base * (TAB_COUNT - 1);
        return t == TAB_COUNT - 1 ? Math.max(base, mkW - 12 - used) : base;
    }

    private int tabLeft(int t) {
        int left = 6;
        for (int i = 0; i < t; i++) {
            left += tabWidth(i);
        }
        return left;
    }

    /** Click hit-test: returns the tab index, -1 when outside the tab bar. */
    private int tabAt(int mouseX, int x, int y, int mouseY) {
        if (mouseY < y || mouseY >= y + TAB_H || mouseX < x + 6 || mouseX >= x + mkW - 6) {
            return -1;
        }
        for (int t = 0; t < TAB_COUNT; t++) {
            int left = x + tabLeft(t);
            if (mouseX < left + tabWidth(t)) {
                return t;
            }
        }
        return -1;
    }

    private boolean hasSubTabs() {
        return tab == TAB_TRADE || tab == TAB_RANK;
    }

    private String[] subNames() {
        return tab == TAB_TRADE ? TRADE_SUB_NAMES : RANK_SUB_NAMES;
    }

    private int subIndex() {
        return tab == TAB_TRADE ? tradeSub : rankSub;
    }

    private void setSubIndex(int i) {
        if (tab == TAB_TRADE) {
            tradeSub = i;
        } else {
            rankSub = i;
        }
    }

    /** Sub-tab bar: height 16, hugging the main tab bar, container width split evenly (no inner padding). */
    private void drawSubTabBar(GuiGraphics g, int x, int top, int mouseX, int mouseY) {
        String[] names = subNames();
        int w = (mkW - 4) / names.length;
        g.fill(x + 2, top, x + mkW - 2, top + SUB_TAB_H, PANEL_ROOT);
        for (int i = 0; i < names.length; i++) {
            int sx = x + 2 + i * w;
            int sw = i == names.length - 1 ? mkW - 4 - i * w : w;
            boolean active = i == subIndex();
            boolean hover = mouseX >= sx && mouseX < sx + sw && mouseY >= top && mouseY < top + SUB_TAB_H;
            int bg = active ? TAB_SELECTED_BG : (hover ? TAB_HOVER_BG : TAB_NORMAL_BG);
            g.fill(sx, top, sx + sw, top + SUB_TAB_H, bg);
            pageCentered(g,L.tr(names[i]), sx + sw / 2, top + (SUB_TAB_H - 9) / 2,
                    active ? TEXT_WHITE : TEXT_SOFT);
        }
    }

    /** Sub-tab click hit-test: returns the index or -1. */
    private int subTabAt(int mouseX, int x, int contentTop) {
        String[] names = subNames();
        int w = (mkW - 4) / names.length;
        for (int i = 0; i < names.length; i++) {
            int sx = x + 2 + i * w;
            int sw = i == names.length - 1 ? mkW - 4 - i * w : w;
            if (mouseX >= sx && mouseX < sx + sw) {
                return i;
            }
        }
        return -1;
    }

    /** The trading tab's three sub-pages: content top = sub-tab bar lower edge + 4, bottom = event bar upper edge - 4, recomputed every frame. */
    private void drawTradePage(GuiGraphics g, MarketClientState st, int x, int contentTop,
                               int mouseX, int mouseY) {
        int top = tradeContentTop();
        int left = x + 6;
        int right = x + mkW - 6;
        int bodyH = tradeContentHeight();
        // (UI unification): the trading sub-tab pages (short/orders/limits) use the same dark
        // PANEL_ROOT as the loan page, filling the content area without covering the sub-tab bar above or the event bar below.
        g.fill(left - 2, top, right + 2, top + bodyH, PANEL_ROOT);
        if (tradeSub == 0) drawShortPage(g, st, left, top, right, bodyH, mouseY);
        else if (tradeSub == 1) drawOrderPage(g, st, left, top, right, bodyH, mouseY);
        else drawLimitPage(g, st, left, top, right, bodyH, mouseY);
        //: when the selected stock is frozen / quotes are missing, overlay a red warning line at the content top
        // (below the dropdown, inside the content area; the layout stack and frame stay untouched).
        drawTradeFrozenBanner(g, st, left, top, right);
    }

    /**
     * The trading page's red warning bar. Shown when the selected stock is frozen / quotes missing / current price non-positive
     * (this stock is frozen and cannot be traded); locked tiers / suspensions keep the existing hints and do not stack this bar.
     */
    private void drawTradeFrozenBanner(GuiGraphics g, MarketClientState st, int left, int top, int right) {
        if (st == null || selected < 0 || selected >= st.viewSize()) {
            return;
        }
        if (!st.unlocked(selected) || st.suspended(selected)) {
            return;
        }
        if (st.quoteAt(selected) && st.price[selected] > 0 && Double.isFinite(st.price[selected])) {
            return;
        }
        String warn = trKey("oremarket.error.stock_frozen");
        int y = top + 1;
        if (y + 9 > tradeContentBottom()) {
            return;
        }
        drawFitLeft(g, warn, left, y, Math.max(10, right - left), PNL_UP);
    }

    /**
     * Pixel-level visibility test.
     *
     * <p>A row must draw whenever it shares even 1 pixel with the viewport; the part past the viewport is removed by
     * {@link #scissor}. Several lists used to say draw only fully visible rows
     * ({@code rowTop >= viewTop && rowBottom <= viewBottom}), so mid-scroll the edge rows vanished outright and players
     * saw rows appear only once scrolled to fit a whole row. Introducing any whole-row-visible test, row-index rounding,
     * hidden rows or ellipses is banned.</p>
     */
    private static boolean rowIntersects(int rowTop, int rowBottom, int viewTop, int viewBottom) {
        // Any 1px intersection draws and hit-tests; zero intersection (exactly touching) is not visible.
        // Whole-row-visible rules are not allowed.
        return rowTop < viewBottom && rowBottom > viewTop;
    }

    /** The clickable part of a row within the visible span (half rows are clickable too; hit area and painting share one rule). */
    private static int visibleTop(int rowTop, int rowBottom, int viewTop, int viewBottom) {
        return Math.max(rowTop, viewTop);
    }

    private static int visibleBottom(int rowTop, int rowBottom, int viewTop, int viewBottom) {
        return Math.min(rowBottom, viewBottom);
    }

    private int rowTextY(int rowTop) { return rowTop + 5; }

    /** Cell text: no longer truncated from -- column fitting is done solely by drawAligned / drawCenteredCell's scaling ladder. */
    private String cellText(String text, int l, int r) {
        if (r - l <= 0 || text == null) {
            return "";
        }
        return text;
    }

    /** Short page scroll offset: outer scroll of the whole page; the inner position list keeps its offset in a separate field. */
    private int shortsInnerScroll = 0;

    /**
     * Short page element indexes. The explanation, stock, pool, quantity, ratio, fee, button and
     * position-list elements are laid out in this order.
     * Each row declares only its height; every concrete y comes from VerticalLayout. The first
     * element starts at content top + 4, and each later element follows the previous row plus a 4px gap.
     * The pool element displays only the server-provided remaining / maximum share counts.
     */
    private static final int SL_INTRO = 0;
    private static final int SL_STOCK = 1;
    private static final int SL_POOL = 2;     // short pool remaining: X / Y shares
    private static final int SL_QTY = 3;
    private static final int SL_RATIO = 4;
    private static final int SL_TAX = 5;
    private static final int SL_BTN = 6;
    private static final int SL_LIST = 7;

    /** Adaptive tier for the short page's action buttons, shared by layout and widget construction. */
    private boolean shortButtonsStacked() {
        int left = tradeContentLeft();
        int right = tradeContentRight();
        int inner = Math.max(0, right - left);
        int gap = 4;
        int bw = Math.max(45, (inner - gap * 4) / 5);
        String[] keys = {"oremarket.button.short_open", "oremarket.button.short_oneclick",
                "oremarket.button.short_close_half", "oremarket.button.short_close_selected",
                "oremarket.button.short_close_all"};
        if (font == null) {
            return false;
        }
        if (btnRowFit(keys, bw, gap, 4) != 2) {
            return false;
        }
        int narrowW = Math.max(8, (inner - 4 * 2) / 5);
        return btnRowFit(keys, narrowW, 2, 2) == 2;
    }

    private int shortButtonAreaHeight() {
        return shortButtonsStacked() ? 5 * 20 + 4 * 2 : 20;
    }

    /** Rebuilds the short page layout every frame; containerH comes from the trading page's outer bounds (event-bar height changes feed in automatically). */
    private void layoutShortPage(MarketClientState st) {
        int left = tradeContentLeft();
        int width = tradeContentRight() - left;
        shortLayout.clear()
                .add(() -> Math.max(14, shortIntroLines(introAvailWidth()).size() * SHORT_INTRO_LINE_H))
                .add(20)   // stock row
                .add(20)   // short pool row
                .add(20)   // borrow-quantity row (the input row)
                .add(20)   // margin-ratio row
                .add(20)   // opening-fee row
                .add(shortButtonAreaHeight())   // action-button row: five buttons stack vertically when the width is too narrow
                .add(() -> shortsNestedHeight(st.shorts.size()));
        shortLayout.layout(left, tradeBoundsTop(), width, tradePageContainerH());
        shortScroll = shortLayout.clampScroll(shortScroll);
        clampShortsInnerScroll(st);
    }

    /** Fixed height of the short-position nested list. */
    private int shortsNestedHeight(int count) {
        return Math.max(30, Math.min(count * LIST_ROW + 14, 200));
    }

    /** Inner list's visible height is the part below the 14px header and bottom padding. */
    private int shortsInnerViewH() {
        return Math.max(0, shortLayout.height(SL_LIST) - 14 - LIST_PAD);
    }

    private int shortsInnerMax(MarketClientState st) {
        // Keep a 4px gap under the last row at full scroll.
        return scrollMax(st.shorts.size() * LIST_ROW, shortsInnerViewH());
    }

    private void clampShortsInnerScroll(MarketClientState st) {
        shortsInnerScroll = clamp(shortsInnerScroll, 0, shortsInnerMax(st));
    }

    /**
     * Current drawing rect of the short page's nested list; scrolling and click checks share the absolute coordinates after drawing.
     * The rect's bottom is always clamped to the content area's bottom (event bar upper edge - 4): where it paints is where clicks count,
     * and it may never reach into the event bar.
     */
    private int[] shortListRect() {
        int top = shortLayout.y(SL_LIST) - shortScroll;
        int naturalBottom = top + shortLayout.height(SL_LIST);
        return new int[]{tradeContentLeft(), top, tradeContentRight(),
                Math.min(naturalBottom, tradeContentBottom())};
    }

    /** Form/button row drawing coordinates: layout y minus the outer scrollOffset. */
    private int shortElementY(int index) {
        return shortLayout.y(index) - shortScroll;
    }

    private void drawShortPage(GuiGraphics g, MarketClientState st, int left, int top, int right, int bodyH, int mouseY) {
        int bottom = tradeContentBottom();
        if (bottom <= top) {
            return;
        }
        clampShortsInnerScroll(st);
        // The outer clip is hard-clamped to the content area [sub-tab bar lower edge + 4, event bar upper edge - 4]:
        // the bottom edge must sit 4px above the event bar, so at full scroll the last row never loses half a row.
        // Nested lists then clip again outer-to-inner (GuiGraphics keeps its own scissor stack and intersects pushed rects with the parent).
        try {
            scissor(g, left, top, right, bottom);
            int introLineY = shortElementY(SL_INTRO);
            for (String line : shortIntroLines(introAvailWidth())) {
                // Rows are already the outcome of wrapping whole translated sentences, so they are NEVER fed back to the translation layer line by line -
                // re-translating a translated half-sentence is precisely how I18n leaks happen
                pageText(g, line, left, introLineY, TEXT_MUTED);
                introLineY += SHORT_INTRO_LINE_H;
            }
            // Only one quota line paints here: short pool remaining X / Y shares (pure share count).
            // Numbers read only the server snapshot values (st.poolMax / st.poolLeft); the client recomputes nothing;
            // Colours: 0 left = red (open / one-click buttons grey out in sync), >0 and <20% = yellow, else white; a missing pool (-1) shows - / -.
            {
                int poolIndex = selected >= 0 && selected < st.viewSize() ? selected : 0;
                int poolMaximum = st.viewSize() > 0 ? st.poolMax[poolIndex] : -1;
                int poolRemaining = st.viewSize() > 0 ? st.poolLeft[poolIndex] : -1;
                String poolLine = poolMaximum < 0
                        ? trKey("oremarket.short.pool.label", "-", "-")
                        : trKey("oremarket.short.pool.label",
                                String.valueOf(Math.max(0, poolRemaining)), String.valueOf(Math.max(0, poolMaximum)));
                int poolColor = TEXT_WHITE;
                if (poolMaximum >= 0) {
                    if (poolRemaining <= 0) {
                        poolColor = PNL_UP;                       // 0 shares: red
                    } else if ((long) poolRemaining * 5L < (long) Math.max(1, poolMaximum)) {
                        poolColor = SHORT_POOL_YELLOW;            // remaining < 20%: yellow
                    }
                }
                pageText(g, poolLine, left, rowTextY(shortElementY(SL_POOL)), poolColor);
            }
            if (st.viewSize() > 0) {
                int idx = selected >= 0 && selected < st.viewSize() ? selected : 0;
                String code = st.codeAt(idx);
                String name = st.stocks[idx].displayName();
                drawSectionTitle(g, cn.oremarket.common.RawText.t("oremarket.text.0120") + name + L.parenOpen() + code + cn.oremarket.common.RawText.t("oremarket.text.0200") + formatPrice(st.price[idx]),
                        left, rowTextY(shortElementY(SL_STOCK)), right - left, TEXT_WHITE);
                // The margin estimate and the open button share one source: with an empty / illegal / 0 quantity it shows 0 (margin 0),
                // never falling back to 1 and displaying a margin that could never fill.
                long qty = parseShortQty();
                MarketSettings msShort = MarketSettings.client();
                double ratio = msShort == null ? 1.5 : msShort.shortMarginRatio;
                // The estimate equals the lockedMargin frozen at opening (margin + fees combined),
                // the same formula as the server's open(); after opening, the UI's locked column shows exactly this.
                long margin = (long) Math.ceil(st.price[idx] * qty * ratio);
                long lockedEst = margin + (qty > 0 && msShort != null && st.price[idx] > 0
                        ? (long) Math.ceil(st.price[idx] * qty * Fees.combined(
                                Fees.effectiveFeeRate(msShort.feeRate()), msShort.taxRate()))
                        : 0L);
                int y1 = shortElementY(SL_QTY);
                // Translate the label before measuring its width. English "Shares borrowed:" is about 3x the Chinese width;
                // positioning by the Chinese text would press the input box and the right-side margin text under the label.
                String qtyLabel = L.tr(cn.oremarket.common.RawText.t("oremarket.text.0237"));
                int boxRight = left + font.width(qtyLabel) + 6 + 60;
                drawMarginAmount(g, lockedEst, boxRight, right, rowTextY(y1));
                pageText(g, qtyLabel, left, rowTextY(y1), TEXT_WHITE);
                pageText(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0285") + Math.round(ratio * 100) + "%"),
                        left, rowTextY(shortElementY(SL_RATIO)), TEXT_WHITE);
                pageText(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0514")),
                        left, rowTextY(shortElementY(SL_TAX)), TEXT_MUTED);
            }
            // the button row is drawn by widgets; here only the nested list and its header paint.
            int listTop = shortLayout.y(SL_LIST) - shortScroll;
            int listH = shortLayout.height(SL_LIST);
            // List rect bottom = min(list natural bottom, content bottom). List height = max(30, min(14 + count x 16, 200));
            // the 14px header is never squashed; when the rect would pass the content area it is clamped on the spot and both painting and clipping use the clamped value.
            int listBottom = Math.min(listTop + listH, bottom);
            int headerY = listTop;
            int mode = shortColumnMode(right - left);
            int[] columnEdges = shortColumns(left, right, mode);
            for (int col = 0; col < SHORT_COL_COUNT; col++) {
                if (shortColumnHidden(mode, col)) {
                    continue;
                }
                drawAligned(g, cellText(SHORT_HEADERS[col], columnEdges[col], columnEdges[col + 1]),
                        columnEdges[col], columnEdges[col + 1], col != 0, headerY + 2, GRAY88);
            }
            int dataTop = headerY + 14;
            final int innerBot = nestedInnerBottom(dataTop, listBottom);
            try {
                scissor(g, left, dataTop, right, innerBot);
                if (st.shorts.isEmpty()) {
                    pageCentered(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0361")), (left + right) / 2,
                            dataTop + (LIST_ROW - 9) / 2, GRAY88);
                }
                // One page-level buffer pair is reused per row (filled inside this method only, never kept
                // across frames), so the per-frame drawing path allocates nothing extra.
                String[] cells = new String[SHORT_COL_COUNT];
                int[] colors = new int[SHORT_COL_COUNT];
                for (int i = 0; i < st.shorts.size(); i++) {
                    MarketClientState.ShortInfo info = st.shorts.get(i);
                    int rowY = dataTop + i * LIST_ROW - shortsInnerScroll;
                    // pixel-level visibility: draw whenever the viewport shares pixels
                    if (!rowIntersects(rowY, rowY + LIST_ROW, dataTop, innerBot)) {
                        continue;
                    }
                    int stockIndex = st.indexOfCode(info.code);
                    //: with quotes missing / a non-positive price, the current price and floating P&L show -- and never compute a fake profit at price 0.
                    boolean quoteOk = stockIndex >= 0 && st.quoteAt(stockIndex)
                            && st.price[stockIndex] > 0 && Double.isFinite(st.price[stockIndex]);
                    double current = quoteOk ? st.price[stockIndex] : 0;
                    double pnl = quoteOk ? (info.openPrice - current) * info.quantity : 0;
                    // Margin risk values are a matched server snapshot. Do not rebuild either one from
                    // the independently arriving client quote: a quote packet can belong to another tick
                    // and briefly make a safe position look like a margin breach (or hide a real one).
                    double currentMargin = info.currentRemainingMargin;
                    double threshold = info.liquidationThreshold;
                    if (!Double.isFinite(currentMargin)) {
                        currentMargin = 0.0;
                    }
                    if (!Double.isFinite(threshold) || threshold < 0.0) {
                        threshold = 0.0;
                    }
                    {
                        // Row chrome: 1px row dividers, selected-row background + 2px left stripe, hover background
                        if (i < st.shorts.size() - 1) {
                            g.fill(left, rowY + LIST_ROW - 1, right, rowY + LIST_ROW, EX_SEP_COLOR);
                        }
                        if (selectedShortPositionId == info.id) {
                            g.fill(left, rowY, right, rowY + LIST_ROW, LIST_SEL_BG);
                            g.fill(left, rowY, left + 2, rowY + LIST_ROW, DOT_UNLOCKED);
                        } else if (!dragAny() && mouseY >= visibleTop(rowY, rowY + LIST_ROW, dataTop, innerBot)
                                && mouseY < visibleBottom(rowY, rowY + LIST_ROW, dataTop, innerBot)) {
                            g.fill(left, rowY, right, rowY + LIST_ROW, LIST_HOVER_BG);
                        }
                    }
                    cells[0] = info.code; colors[0] = TEXT_WHITE;
                    cells[1] = L.money(info.quantity); colors[1] = TEXT_WHITE;
                    cells[2] = formatPrice(info.openPrice); colors[2] = TEXT_WHITE;
                    cells[3] = L.money(info.initialMargin); colors[3] = TEXT_WHITE;
                    cells[4] = L.money(currentMargin);
                    colors[4] = shortMarginRiskColor(currentMargin, threshold);
                    cells[5] = quoteOk ? formatPrice(current) : "--";
                    colors[5] = quoteOk ? TEXT_WHITE : GRAY88;
                    cells[6] = quoteOk ? L.moneySigned(pnl) : "--";
                    colors[6] = !quoteOk ? GRAY88
                            : (pnl > 0 ? PNL_UP : pnl < 0 ? PNL_DOWN : PNL_FLAT);
                    cells[7] = formatPrice(info.liquidationPrice); colors[7] = TEXT_WHITE;
                    for (int col = 0; col < SHORT_COL_COUNT; col++) {
                        if (shortColumnHidden(mode, col)) {
                            continue;
                        }
                        drawAligned(g, cellText(cells[col], columnEdges[col], columnEdges[col + 1]),
                                columnEdges[col], columnEdges[col + 1], col != 0, rowY + 3, colors[col]);
                    }
                }
            } finally {
                g.disableScissor();
            }
        } finally {
            g.disableScissor();
        }
        drawBottomLine(g, shortLayout, shortScroll, left, right);
    }

    /**
     * Current-margin warning colour: compare the distance above the maintenance threshold,
     * not the absolute margin amount. At or below the threshold is red; less than 10%
     * headroom is red, less than 20% is yellow, and all safer positions stay white.
     */
    private static int shortMarginRiskColor(double currentMargin, double threshold) {
        if (!Double.isFinite(currentMargin) || !Double.isFinite(threshold) || threshold <= 0.0) {
            return TEXT_WHITE;
        }
        if (currentMargin <= threshold) {
            return TOAST_RED;
        }
        double headroom = (currentMargin - threshold) / threshold;
        if (!Double.isFinite(headroom) || headroom < 0.10) {
            return TOAST_RED;
        }
        return headroom < 0.20 ? SHORT_POOL_YELLOW : TEXT_WHITE;
    }

    /** Faint hairline at the bottom: only in the outermost content area, never inside nested lists. */
    private void drawBottomLine(GuiGraphics g, VerticalLayout layout, int scrollOffset,
                                 int areaLeft, int areaRight) {
        if (!layout.shouldDrawBottomLine(scrollOffset)) {
            return;
        }
        int y = layout.boundsTop() + tradePageContainerH() - 4;
        g.fill(areaLeft + 6, y, areaRight - 6, y + 1, DARK_BG);
    }

    /**
     * Translation-key rendering (Requirement 1: the short and order pages fully switched to Component.translatable + config-driven text;
     * hardcoding Chinese originals into the UI is banned). An unregistered key makes getString return the key verbatim -- and equality is the fallback:
     * first the three-level L overlay, then the key itself; never throws, never shows null.
     */
    private String trKey(String key, Object... args) {
        if (key == null) {
            return "";
        }
        String out;
        try {
            out = Component.translatable(key, args).getString();
        } catch (RuntimeException e) {
            out = key;
        }
        if (out != null && !out.isEmpty() && !out.equals(key)) {
            return out;
        }
        String fb = L.tr(key);
        return fb == null || fb.isEmpty() ? key : fb;
    }

    /** Config ratio -> integer percent string (1.50 -> "150"; rounded, hot-reloaded values apply at once). */
    private static String pct(double ratio) {
        return String.valueOf(Math.round(ratio * 100.0));
    }

    /** Daily-rate display: always keeps two decimal places so 0.001 is shown as 0.10%, not the ambiguous 0%. */
    private static String dailyPct(double ratio) {
        if (!Double.isFinite(ratio)) {
            return "0.00";
        }
        return Num.fixed(Math.max(0.0, ratio) * 100.0, 2);
    }

    /**
     * Short page's top explanation: six dynamically joined segments (fixed entry text + shortAffectPrice decides the second sentence +
     * margin ratio / liquidation line / daily interest / quota ratio, four values read live from the config snapshot synced to the client).
     * Config hot reload -> SyncConfigPacket -> MarketSettings.client() swaps the instance -> this method recomputes next frame;
     * the screen refreshes live without reopening.
     */
    private String shortIntroText(cn.oremarket.market.MarketSettings cfg) {
        MarketClientState st = MarketClientState.INSTANCE;
        StringBuilder sb = new StringBuilder();
        sb.append(trKey("oremarket.market.short.intro.entry"));
        sb.append(' ').append(trKey(cfg.shortAffectPrice
                ? "oremarket.market.short.intro.affect_price"
                : "oremarket.market.short.intro.no_price"));
        sb.append(' ').append(trKey("oremarket.market.short.intro.margin_ratio", pct(cfg.shortMarginRatio)));
        sb.append(' ').append(trKey("oremarket.market.short.intro.liq_line", pct(cfg.shortLiquidationRatio)));
        int poolIndex = selected >= 0 && selected < st.viewSize() ? selected : 0;
        int poolMaxNow = st.viewSize() > 0 ? st.poolMax[poolIndex] : -1;
        int poolLeftNow = st.viewSize() > 0 ? st.poolLeft[poolIndex] : -1;
        double effectiveRate = cn.oremarket.common.ShortPoolCalculator
                .scarceDailyRate(cfg.shortDailyRate, poolLeftNow, poolMaxNow, cfg.shortScarcityMultiplier);
        boolean scarce = effectiveRate > cfg.shortDailyRate + 1.0e-12;
        sb.append(' ').append(trKey(scarce
                ? "oremarket.market.short.intro.daily_interest_scarce"
                : "oremarket.market.short.intro.daily_interest",
                dailyPct(effectiveRate), dailyPct(cfg.shortDailyRate)));
        sb.append(' ').append(trKey("oremarket.market.short.intro.pool"));
        return sb.toString();
    }

    /**
     * Margin amount row: right-aligned baseline = content right edge - 8, text width cap 280.
     * Past the cap, drop the amount/coin words first, degrading to margin: X; still too wide, show only the number; finally fall back to remaining-room truncation.
     * Under no circumstances does it cross the content area's right edge or overlap the input box on its left.
     */
    private void drawMarginAmount(GuiGraphics g, long margin, int boxRight, int right, int y) {
        int rightBase = right - 8;
        // Every step of the three-stage shortening measures the CURRENT language's translation width (it used to measure the Chinese original,
        // and an English translation wider than the 280 cap would run straight over the left-side input box).
        String text = trKey("oremarket.market.short.preview_margin", L.money(margin));
        if (font.width(text) > MARGIN_TEXT_MAX_W) {
            text = trKey("oremarket.market.short.margin_short", L.money(margin));
        }
        if (font.width(text) > MARGIN_TEXT_MAX_W) {
            text = L.money(margin);
        }
        int room = Math.max(0, Math.min(MARGIN_TEXT_MAX_W, rightBase - (boxRight + 6)));
        if (room <= 0) {
            return;
        }
        // The last step no longer truncates -- it runs the scaling ladder against the room (ellipses are globally banned).
        drawFitRight(g, text, rightBase, y, room, TEXT_WHITE);
    }

    /** // explanation block usable width: content width minus another 8px of right-hand room */
    private int introAvailWidth() {
        return Math.max(30, tradeContentRight() - tradeContentLeft() - SHORT_SB_W - 2);
    }

    /**
     * Short page's top explanation text (the whole paragraph; lines longer than one row wrap per character with row height 9,
     * the first row starting at content top + 4, never hugging the sub-tab bar).
     */
    /** Old static text (kept only as reference after Requirement 1 moved to six dynamic segments; it never enters the render path). */
    @Deprecated
    private static final String SHORT_INTRO =
            cn.oremarket.common.RawText.t("oremarket.text.0547");
    private static final int SHORT_INTRO_LINE_H = 9;
    /** Margin text width cap. */
    private static final int MARGIN_TEXT_MAX_W = 280;
    /** // short page's right scrollbar is 6 wide (inside the content area's right edge) */
    private static final int SHORT_SB_W = 6;
    /** Wrap cache of the explanation block: the last usable width and result (no per-frame measuring). */
    private int introCacheW = -1;
    private List<String> introCache = new ArrayList<>();

    /**
     * Wrap result of the explanation block. Key fix: translate the whole paragraph into the current language FIRST, then wrap on the translation -
     * the old code wrapped the Chinese original character by character and translated each fragment separately, giving English
     * over-long pseudo-lines plus frozen Chinese scraps whose height overlapped the quota area below.
     * Wrapping follows the log page's exact rule (words first, over-long tokens by character, no ellipses); under Chinese it is equivalent to per-character wrapping.
     */
    private List<String> shortIntroLines(int availW) {
        cn.oremarket.market.MarketSettings cfg =
                cn.oremarket.market.MarketSettings.client();
        if (availW != introCacheW || introCache.isEmpty() || cfg != introCacheCfg) {
            List<String> out = new ArrayList<>();
            // config snapshot not ready (first response not arrived / cleared on last close): show placeholder text and NEVER dereference null
            String intro = cfg == null
                    ? L.tr(cn.oremarket.common.RawText.t("oremarket.market.short.loading"))
                    : shortIntroText(cfg);
            wrapAllNoEllipsis(intro, Math.max(6, availW), out);
            // The explanation rows are translations wrapped after translating the whole paragraph; while the overlay is not ready the result
            // is still the Chinese original, and such results are not cached (recompute next frame) or half a Chinese sentence would be translated twice
            if (cacheableTranslation(intro)) {
                introCacheW = availW;
                introCacheCfg = cfg;
                introCache = List.copyOf(out);
            }
            return out;
        }
        return introCache;
    }
    private cn.oremarket.market.MarketSettings introCacheCfg;

    /**
     * Eight columns of the short-position list: stock, quantity, entry price, initial margin,
     * current remaining margin, current price, floating P&L and liquidation price.
     * The current margin is server-calculated from the post-interest collateral plus floating P&L;
     * the table never substitutes the old locked-margin value for it.
     */
    private static final String[] SHORT_HEADERS = {cn.oremarket.common.RawText.t("oremarket.text.0065"), cn.oremarket.common.RawText.t("oremarket.text.0046"), cn.oremarket.common.RawText.t("oremarket.text.0106"), cn.oremarket.common.RawText.t("oremarket.short.header.initial_margin"), cn.oremarket.common.RawText.t("oremarket.short.header.current_margin"), cn.oremarket.common.RawText.t("oremarket.text.0108"), cn.oremarket.common.RawText.t("oremarket.text.0183"), cn.oremarket.common.RawText.t("oremarket.text.0107")};
    private static final int SHORT_COL_COUNT = 8;
    /** Widths of the seven non-stock columns (indexes 1..7, Chinese baseline). */
    private static final int[] SHORT_FIXED_W = {0, 40, 60, 72, 78, 60, 70, 78};
    /** Stock column minimum 60; fixed columns plus a 2px safety margin; 12 reserved on the right. */
    private static final int SHORT_STOCK_MIN_W = 60;
    private static final int SHORT_RIGHT_RESERVE = 12;
    /** Minimum compression ratio of the fixed columns (narrower degrades to hiding columns; at the floor the stock column keeps yielding, see shortColumns). */
    private static final double SHORT_COL_MIN_SCALE = 0.55;

    /**
     * A column's actual width under the current language = max(Chinese baseline, translated header width + 8).
     * English headers are wider than Chinese; laying them out on fixed Chinese baselines presses text across columns;
     * headers and data share this width, the stock column yields automatically and the total stays clamped inside the content area.
     */
    private int shortFixedW(int col) {
        int base = SHORT_FIXED_W[col];
        return col == 0 ? base : Math.max(base, font.width(L.tr(SHORT_HEADERS[col])) + 8);
    }

    /** Baseline total of the seven non-stock columns in the current language (including the 2px safety-margin convention). */
    private int shortFixedTotal(int mode) {
        int total = 2;
        for (int col = 1; col < SHORT_COL_COUNT; col++) {
            if (!shortColumnHidden(mode, col)) {
                total += shortFixedW(col);
            }
        }
        return total;
    }

    /** // mode: 0 = all eight columns; 1 = hide quantity; 2 = hide quantity + entry price. */
    private static boolean shortColumnHidden(int mode, int col) {
        return mode >= 1 && col == 1 || mode >= 2 && col == 2;
    }

    private int shortColumnMode(int avail) {
        for (int mode = 0; mode <= 2; mode++) {
            int need = SHORT_STOCK_MIN_W + (int) Math.ceil(shortFixedTotal(mode) * SHORT_COL_MIN_SCALE)
                    + SHORT_RIGHT_RESERVE;
            if (avail >= need) {
                return mode;
            }
        }
        return 2;
    }

    /** Column boundary array (9 boundaries, 8 columns); hidden columns take width 0 and are skipped by header and data alike, and the last boundary clamps to the content area's right edge. */
    private int[] shortColumns(int left, int right, int mode) {
        int avail = Math.max(0, right - left);
        int fixed = shortFixedTotal(mode);
        int stock = Math.max(SHORT_STOCK_MIN_W, avail - fixed - SHORT_RIGHT_RESERVE);
        int room = Math.max(0, avail - stock - SHORT_RIGHT_RESERVE);
        double scale = Math.min(1.0, Math.max(SHORT_COL_MIN_SCALE, room / (double) fixed));
        int fixedW = 0;
        for (int col = 1; col < SHORT_COL_COUNT; col++) {
            if (!shortColumnHidden(mode, col)) {
                fixedW += Math.max(1, (int) Math.round(shortFixedW(col) * scale));
            }
        }
        // in extremely narrow containers (fixed columns already at their floor) the stock column yields further (min 24) so the seven columns never pass the content right edge
        if (stock + fixedW + SHORT_RIGHT_RESERVE > avail) {
            stock = Math.max(24, avail - fixedW - SHORT_RIGHT_RESERVE);
        }
        int[] edges = new int[SHORT_COL_COUNT + 1];
        edges[0] = left;
        for (int col = 0; col < SHORT_COL_COUNT; col++) {
            int w = shortColumnHidden(mode, col) ? 0
                    : col == 0 ? stock : Math.max(1, (int) Math.round(shortFixedW(col) * scale));
            edges[col + 1] = Math.min(right - 2, edges[col] + w);
        }
        return edges;
    }

    private void drawOrderPage(GuiGraphics g, MarketClientState st, int left, int top, int right, int bodyH, int mouseY) {
        layoutOrderPage(st);
        TradeFormGeom form = tradeFormGeom(false);
        int scroll = orderOuterScroll;
        int listTop = orderLayout.y(TO_LIST) - scroll;
        int listH = orderLayout.height(TO_LIST);
        int bottom = tradeContentBottom();
        // The nested list rect's bottom clamps to the content area bottom, one rule for drawing and clipping
        int listBottom = Math.min(listTop + listH, bottom);
        int[] columnEdges = orderColumns(left, right);
        try {
            scissor(g, left, top, right, bottom);
            // Form labels also draw inside the page clip; rows scrolled out of the content area never paint onto the sub-tab bar
            drawOrderFormLabels(g, left, top, form, cn.oremarket.common.RawText.t("oremarket.text.0092"), orderLayout);
            drawFitLeft(g, cn.oremarket.common.RawText.t("oremarket.text.0239"), left, listTop + 2, orderLabelColW() - 4, GRAY88);
            drawAligned(g, cn.oremarket.common.RawText.t("oremarket.text.0065"), columnEdges[0], columnEdges[1], false, listTop + 2, GRAY88);
            drawCenteredCell(g, cn.oremarket.common.RawText.t("oremarket.text.0047"), columnEdges[1], columnEdges[2], listTop + 2, GRAY88);
            drawAligned(g, cn.oremarket.common.RawText.t("oremarket.text.0046"), columnEdges[2], columnEdges[3], true, listTop + 2, GRAY88);
            drawAligned(g, cn.oremarket.common.RawText.t("oremarket.text.0023"), columnEdges[3], columnEdges[4] - 8, true, listTop + 2, GRAY88);
            drawAligned(g, cn.oremarket.common.RawText.t("oremarket.text.0099"), columnEdges[4], columnEdges[5], false, listTop + 2, GRAY88);
            int dataTop = listTop + 14;
            final int innerBot = nestedInnerBottom(dataTop, listBottom);
            try {
                scissor(g, left, dataTop, right, innerBot);
                if (st.orderBook.isEmpty()) {
                    pageCentered(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0179")), (left + right) / 2, dataTop + 3, GRAY88);
                }
                int bookH = bookRowH();
                for (int i = 0; i < st.orderBook.size(); i++) {
                    MarketClientState.BookInfo bookRow = st.orderBook.get(i);
                    int rowY = dataTop + i * bookH - bookScroll;
                    // pixel-level intersection: half rows still draw, the overage cut by the scissor above
                    if (!rowIntersects(rowY, rowY + bookH, dataTop, innerBot)) continue;
                    String dirTx = bookRow.buy ? cn.oremarket.common.RawText.t("oremarket.text.0002") : cn.oremarket.common.RawText.t("oremarket.text.0007");
                    String ownerTx = bookRow.mine() ? bookRow.ownerName + cn.oremarket.common.RawText.t("oremarket.text.0604") : bookRow.ownerName;
                    if (selectedOrderId == bookRow.id) {
                        g.fill(left, rowY, right, rowY + bookH, exchangeSelBg());
                        g.fill(left, rowY, left + 2, rowY + bookH, DOT_UNLOCKED);
                    } else if (!dragAny() && mouseY >= rowY && mouseY < rowY + bookH) {
                        g.fill(left, rowY, right, rowY + bookH, LIST_HOVER_BG);
                    }
                    // 1px divider at the bottom of each list row
                    g.fill(left, rowY + bookH - 1, right, rowY + bookH, EX_SEP_COLOR);
                    if (!bookTwoLine) {
                        drawAligned(g, bookRow.code, columnEdges[0], columnEdges[1], false, rowY + 3, TEXT_WHITE);
                        drawCenteredCell(g, dirTx, columnEdges[1], columnEdges[2], rowY + 3, bookRow.buy ? PNL_UP : PNL_DOWN);
                        drawAligned(g, L.money(bookRow.quantity), columnEdges[2], columnEdges[3], true, rowY + 3, TEXT_WHITE);
                        drawAligned(g, formatPrice(bookRow.price), columnEdges[3], columnEdges[4] - 8, true, rowY + 3, TEXT_WHITE);
                        drawAligned(g, ownerTx, columnEdges[4], columnEdges[5], false, rowY + 3, bookRow.mine() ? GOLD : TEXT_WHITE);
                    } else {
                        // two-row mode: row one stock + direction + quantity; row two price + publisher (16 tall each)
                        drawAligned(g, bookRow.code, columnEdges[0], columnEdges[1], false, rowY + 3, TEXT_WHITE);
                        drawCenteredCell(g, dirTx, columnEdges[1], columnEdges[2], rowY + 3, bookRow.buy ? PNL_UP : PNL_DOWN);
                        drawAligned(g, L.money(bookRow.quantity), columnEdges[2], columnEdges[5], true, rowY + 3, TEXT_WHITE);
                        drawAligned(g, formatPrice(bookRow.price), columnEdges[2], columnEdges[4] - 8, true, rowY + 16 + 3, TEXT_WHITE);
                        drawAligned(g, ownerTx, columnEdges[4], columnEdges[5], false, rowY + 16 + 3, bookRow.mine() ? GOLD : TEXT_WHITE);
                    }
                }
            } finally { g.disableScissor(); }
            // Order page's bottom note follows the orderAffectPrice config; both languages ship via translation keys;
            // while the config snapshot is not ready the default applies (false -> does not affect quotes); no naked null dereference.
            // Draw row by row on the fully wrapped translation (row height 9); the element height and layout share the cached result.
            int noteY = orderLayout.y(TO_NOTE) - scroll + 2;
            for (String line : wrapCached(orderSummaryWrap, orderSummaryRawText(st), right - left)) {
                pageText(g, line, left, noteY, TEXT_MUTED);
                noteY += 9;
            }
            for (String line : wrapCached(orderNoteWrap, orderNoteRawText(), right - left)) {
                pageText(g, line, left, noteY, TEXT_MUTED);
                noteY += 9;
            }
        } finally { g.disableScissor(); }
        drawBottomLine(g, orderLayout, orderOuterScroll, left, right);
    }

    private void drawLimitPage(GuiGraphics g, MarketClientState st, int left, int top, int right, int bodyH, int mouseY) {
        layoutLimitPage(st);
        TradeFormGeom form = tradeFormGeom(true);
        int scroll = limitOuterScroll;
        int listTop = limitLayout.y(TO_LIST) - scroll;
        int listH = limitLayout.height(TO_LIST);
        int bottom = tradeContentBottom();
        // The nested list rect's bottom clamps to the content area bottom, one rule for drawing and clipping
        int listBottom = Math.min(listTop + listH, bottom);
        boolean label = limitHeaderLabel(left, right);
        int[] columnEdges = limitColumns(left, right, label);
        try {
            scissor(g, left, top, right, bottom);
            // Form labels also draw inside the page clip; rows scrolled out of the content area never paint onto the sub-tab bar
            drawOrderFormLabels(g, left, top, form, cn.oremarket.common.RawText.t("oremarket.text.0192"), limitLayout);
            // The top two limit-order semantic lines draw on the fully wrapped translation (row height 9, no truncation);
            // layoutLimitPage reserves element heights from the same wrap result
            int introY = limitLayout.y(TO_INTRO_BUY) - scroll + 2;
            for (String line : wrapCached(limitBuyWrap,
                    trKey(cn.oremarket.common.RawText.t("oremarket.text.0502")),
                    right - left)) {
                pageText(g, line, left, introY, TEXT_MUTED);
                introY += 9;
            }
            int sellIntroY = limitLayout.y(TO_INTRO_SELL) - scroll + 2;
            for (String line : wrapCached(limitSellWrap,
                    trKey(cn.oremarket.common.RawText.t("oremarket.text.0503")),
                    right - left)) {
                pageText(g, line, left, sellIntroY, TEXT_MUTED);
                sellIntroY += 9;
            }
            //: the live current-vs-trigger hint under the trigger-price input (takes no clicks and no input space)
            drawLimitPriceHint(g, st, left, right, scroll);
            if (label) pageText(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0292")), left, listTop + 2, GRAY88);
            drawAligned(g, cn.oremarket.common.RawText.t("oremarket.text.0065"), columnEdges[0], columnEdges[1], false, listTop + 2, GRAY88);
            drawCenteredCell(g, cn.oremarket.common.RawText.t("oremarket.text.0047"), columnEdges[1], columnEdges[2], listTop + 2, GRAY88);
            drawAligned(g, cn.oremarket.common.RawText.t("oremarket.text.0122"), columnEdges[2], columnEdges[3], true, listTop + 2, GRAY88);
            drawAligned(g, cn.oremarket.common.RawText.t("oremarket.text.0046"), columnEdges[3], columnEdges[4], true, listTop + 2, GRAY88);
            drawCenteredCell(g, cn.oremarket.common.RawText.t("oremarket.text.0053"), columnEdges[4], columnEdges[5], listTop + 2, GRAY88);
            drawCenteredCell(g, cn.oremarket.common.RawText.t("oremarket.text.0044"), columnEdges[5], columnEdges[6], listTop + 2, GRAY88);
            int dataTop = listTop + 14;
            final int innerBot = nestedInnerBottom(dataTop, listBottom);
            try {
                scissor(g, left, dataTop, right, innerBot);
                if (st.limitOrders.isEmpty()) {
                    pageCentered(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0251")), (left + right) / 2, dataTop + 3, GRAY88);
                }
                int limH = limitRowH();
                for (int i = 0; i < st.limitOrders.size(); i++) {
                    MarketClientState.LimitInfo limitRow = st.limitOrders.get(i);
                    int rowY = dataTop + i * limH - limitScroll;
                    // pixel-level intersection: half rows still draw, the overage cut by the scissor above
                    if (!rowIntersects(rowY, rowY + limH, dataTop, innerBot)) continue;
                    String dirTx = limitRow.buy ? cn.oremarket.common.RawText.t("oremarket.text.0002") : cn.oremarket.common.RawText.t("oremarket.text.0007");
                    int chipY = limitTwoLine ? rowY + 16 : rowY;
                    int chipH = limitTwoLine ? limH - 16 : limH;
                    if (selectedLimitId == limitRow.id) {
                        g.fill(left, rowY, right, rowY + limH, exchangeSelBg());
                        g.fill(left, rowY, left + 2, rowY + limH, DOT_UNLOCKED);
                    } else if (!dragAny() && mouseY >= rowY && mouseY < rowY + limH) {
                        g.fill(left, rowY, right, rowY + limH, LIST_HOVER_BG);
                    }
                    g.fill(left, rowY + limH - 1, right, rowY + limH, EX_SEP_COLOR);
                    if (!limitTwoLine) {
                        drawAligned(g, limitRow.code, columnEdges[0], columnEdges[1], false, rowY + 3, TEXT_WHITE);
                        drawCenteredCell(g, dirTx, columnEdges[1], columnEdges[2], rowY + 3, limitRow.buy ? PNL_UP : PNL_DOWN);
                        drawAligned(g, formatPrice(limitRow.triggerPrice), columnEdges[2], columnEdges[3], true, rowY + 3, TEXT_WHITE);
                        drawAligned(g, L.money(limitRow.quantity), columnEdges[3], columnEdges[4], true, rowY + 3, TEXT_WHITE);
                        drawCenteredCell(g, cn.oremarket.common.RawText.t("oremarket.text.0123"), columnEdges[4], columnEdges[5], rowY + 3, TEXT_WHITE);
                    } else {
                        // two-row mode: row one stock + direction + trigger price; row two quantity + status + cancel button
                        drawAligned(g, limitRow.code, columnEdges[0], columnEdges[1], false, rowY + 3, TEXT_WHITE);
                        drawCenteredCell(g, dirTx, columnEdges[1], columnEdges[2], rowY + 3, limitRow.buy ? PNL_UP : PNL_DOWN);
                        drawAligned(g, formatPrice(limitRow.triggerPrice), columnEdges[2], columnEdges[3], true, rowY + 3, TEXT_WHITE);
                        drawAligned(g, L.money(limitRow.quantity), columnEdges[2], columnEdges[4], true, rowY + 16 + 3, TEXT_WHITE);
                        drawCenteredCell(g, cn.oremarket.common.RawText.t("oremarket.text.0123"), columnEdges[4], columnEdges[5], rowY + 16 + 3, TEXT_WHITE);
                    }
                    g.fill(columnEdges[5] + 5, chipY, columnEdges[5] + 65, chipY + chipH, CHIP_BLUE);
                    drawFitCentered(g, cn.oremarket.common.RawText.t("oremarket.text.0036"),
                            columnEdges[5] + 5, columnEdges[5] + 65, chipY + (chipH - 9) / 2, TEXT_WHITE);
                }
            } finally { g.disableScissor(); }
        } finally { g.disableScissor(); }
        drawBottomLine(g, limitLayout, limitOuterScroll, left, right);
    }

    private void drawOrderFormLabels(GuiGraphics g, int left, int top, TradeFormGeom form,
                                     String priceLabel, VerticalLayout layout) {
        int scroll = layout == limitLayout ? limitOuterScroll : orderOuterScroll;
        int dirY = rowTextY(layout.y(TO_DIR) - scroll);
        int inputY = rowTextY((form.inputOnSecondRow() ? layout.y(TO_INPUT) : layout.y(TO_DIR)) - scroll);
        pageText(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0113")), left, dirY, TEXT_WHITE);
        pageText(g,L.tr(priceLabel), form.priceLabelX, inputY, TEXT_WHITE);
        pageText(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0112")), form.qtyLabelX, inputY, TEXT_WHITE);
    }

    /**
     * The limit order's live current-vs-trigger hint row:
     * buy: current price <= trigger -> blue text already below trigger price, fills right after submit; else grey waiting for the price to fall.
     * sell: current price >= trigger -> blue text already above trigger price, fills right after submit; else grey waiting for the price to rise.
     * The row takes no clicks and occupies no input space; it hides while no stock is selected or no legal trigger price is typed.
     */
    private void drawLimitPriceHint(GuiGraphics g, MarketClientState st, int left, int right, int scroll) {
        String text = limitHintText(st);
        if (text == null) {
            return;
        }
        int idx = st.indexOfCode(selectedCodeForForm(st));
        double trigger = parsePrice(limitPriceText);
        boolean immediate = limitSideBuy ? st.price[idx] <= trigger : st.price[idx] >= trigger;
        int y = limitLayout.y(TO_HINT) - scroll + 2;
        // Translate first, wrap on the translation, draw row by row (height 9, never truncated);
        // the layout's TO_HINT element height reserves from the same wrap result, so long English lines no longer lose their tail.
        for (String line : wrapCached(limitHintWrap, L.tr(text), right - left)) {
            pageText(g, line, left, y, immediate ? ACCENT_BLUE : TEXT_MUTED);
            y += 9;
        }
    }

    /** The limit page's current-vs-trigger hint text (null when not applicable; layout and drawing share one text source). */
    private String limitHintText(MarketClientState st) {
        String code = selectedCodeForForm(st);
        if (code == null) {
            return null;
        }
        int idx = st.indexOfCode(code);
        if (idx < 0) {
            return null;
        }
        double trigger = parsePrice(limitPriceText);
        if (trigger <= 0) {
            return null;
        }
        double now = st.price[idx];
        if (limitSideBuy) {
            return now <= trigger
                    ? cn.oremarket.common.RawText.t("oremarket.text.0165") + formatPrice(now) + cn.oremarket.common.RawText.t("oremarket.text.0352") + formatPrice(trigger) + cn.oremarket.common.RawText.t("oremarket.text.0380")
                    : cn.oremarket.common.RawText.t("oremarket.text.0165") + formatPrice(now) + cn.oremarket.common.RawText.t("oremarket.text.0317") + formatPrice(trigger) + cn.oremarket.common.RawText.t("oremarket.text.0343");
        }
        return now >= trigger
                ? cn.oremarket.common.RawText.t("oremarket.text.0165") + formatPrice(now) + cn.oremarket.common.RawText.t("oremarket.text.0353") + formatPrice(trigger) + cn.oremarket.common.RawText.t("oremarket.text.0380")
                : cn.oremarket.common.RawText.t("oremarket.text.0165") + formatPrice(now) + cn.oremarket.common.RawText.t("oremarket.text.0312") + formatPrice(trigger) + cn.oremarket.common.RawText.t("oremarket.text.0342");
    }

    private void drawAligned(GuiGraphics g, String text, int l, int r, boolean rightAlign, int y, int color) {
        if (r <= l || text == null) return;
        // When it does not fit, run the scaling ladder (0.65 floor, 0.45 extreme fallback); ellipses banned, crossing column bounds banned.
        // translate before measuring -- position on the width of the translation actually drawn.
        if (rightAlign) {
            drawFitRight(g, text, r - 2, y, r - l - 2, color);
        } else {
            drawFitLeft(g, text, l, y, r - l - 2, color);
        }
    }

    private void drawCenteredCell(GuiGraphics g, String text, int l, int r, int y, int color) {
        if (r <= l) return;
        drawFitCentered(g, text, l, r, y, color);
    }

    /** Column width of the all-server orders: label. */
    private int orderLabelColW() {
        return Math.max(48, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0239"))) + 8);
    }

    /**
     * Five column boundaries of the order book: stock 100 left / direction 40 centre / quantity 40 right / price 80 right / publisher 100 left,
     * baseline total 360; the all-server orders label is a standalone first column of the header row (width 80) deducted from the usable width and excluded from the five-column split.
     * When the usable width (after the label) exceeds the baseline, the surplus splits 1:1 to the stock and publisher columns;
     * below it, all five compress proportionally to a 70% floor per column; even narrower and the boundary clamps to the container's right edge with zero overflow.
     * Headers and data share this function; at least 8px stay between the price and publisher columns (drawing uses c[4]-8 as the price's right edge).
     * Each column's baseline is max(Chinese baseline, current-language header width + 8) -- English headers are wider,
     * and fixed Chinese baselines would run Publisher / Quantity across the neighbouring columns.
     */
    private int[] orderColumns(int left, int right) {
        final int stockBase = Math.max(100, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0065"))) + 8);
        final int dirBase = Math.max(40, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0047"))) + 8);
        final int qtyBase = Math.max(40, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0046"))) + 8);
        final int priceBase = Math.max(80, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0023"))) + 8);
        final int ownerBase = Math.max(100, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0099"))) + 8);
        final int total = stockBase + dirBase + qtyBase + priceBase + ownerBase;
        int a = left + orderLabelColW();           // the standalone all-server orders label column
        int avail = Math.max(0, right - a);
        int stockW, dirW, qtyW, priceW, ownerW;
        if (avail >= total) {
            int extra = avail - total;
            int es = extra / 2;                  // surplus goes 1:1 to the stock and publisher columns
            stockW = stockBase + extra - es;
            dirW = dirBase;
            qtyW = qtyBase;
            priceW = priceBase;
            ownerW = ownerBase + es;
        } else {
            double scale = Math.max(0.7, avail / (double) total);
            stockW = (int) Math.round(stockBase * scale);
            dirW = (int) Math.round(dirBase * scale);
            qtyW = (int) Math.round(qtyBase * scale);
            priceW = (int) Math.round(priceBase * scale);
            ownerW = (int) Math.round(ownerBase * scale);
        }
        int[] c = new int[6];
        c[0] = a;
        c[1] = Math.min(right, c[0] + stockW);
        c[2] = Math.min(right, c[1] + dirW);
        c[3] = Math.min(right, c[2] + qtyW);
        c[4] = Math.min(right, c[3] + priceW);
        c[5] = Math.min(right, c[4] + ownerW);
        return c;
    }

    /** The right six-column block's width is computed live from the current language's header translations (Chinese baseline 270). */
    private int limitFixedColsW() {
        int wDir = Math.max(40, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0047"))) + 8);
        int wTrig = Math.max(60, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0122"))) + 8);
        int wQty = Math.max(40, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0046"))) + 8);
        int wStatus = Math.max(60, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0053"))) + 8);
        int wAct = 65;                                 // the in-row cancel hit area is a fixed 65
        return wDir + wTrig + wQty + wStatus + wAct;
    }

    private boolean limitHeaderLabel(int left, int right) {
        return right - left - (font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0292"))) + 6) - limitFixedColsW() >= 48;
    }

    private int[] limitColumns(int left, int right, boolean label) {
        int off = label ? font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0292"))) + 6 : 0;
        int a = left + off;
        int fixed = limitFixedColsW();
        int wDir = Math.max(40, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0047"))) + 8);
        int wTrig = Math.max(60, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0122"))) + 8);
        int wQty = Math.max(40, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0046"))) + 8);
        int wStatus = Math.max(60, font.width(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0053"))) + 8);
        int b = Math.max(a + 48, right - fixed);       // in extreme narrowness the stock column bottoms out at 48 and the right columns clamp inside the content area
        return new int[]{a, b, b + wDir, b + wDir + wTrig, b + wDir + wTrig + wQty,
                b + wDir + wTrig + wQty + wStatus, right};
    }

    private String formatPrice(double p) {
        return Num.fixed(p, 2);
    }

    /** Leaderboard page outer scroll offset (kept across sub-page / tab switches; resets to 0 with the fresh screen instance after closing the UI). */
    private int rankScroll = 0;

    private List<MarketClientState.RankInfo> rankBoard(MarketClientState st) {
        return switch (rankSub) {
            case 1 -> st.dailyBoard;
            case 2 -> st.positionsBoard;
            case 3 -> st.debtBoard;
            case 4 -> st.creditBoard;
            default -> st.assetsBoard;
        };
    }

    /** Leaderboard layout rebuilds every frame: title, header, up to ten rows and the own-rank row only declare heights. */
    private void layoutRankPage(MarketClientState st) {
        List<MarketClientState.RankInfo> board = rankBoard(st);
        int rows = Math.min(10, board.size());
        rankLayout.clear().add(20).add(20);
        if (rows <= 0) {
            rankLayout.add(20);
        }
        for (int i = 0; i < rows; i++) {
            rankLayout.add(LIST_ROW);
        }
        rankLayout.add(20);
        rankLayout.layout(tradeContentLeft(), tradeBoundsTop(),
                tradeContentRight() - tradeContentLeft(), tradePageContainerH());
        rankScroll = rankLayout.clampScroll(rankScroll);
    }

    private void drawRankPage(GuiGraphics g, MarketClientState st, int x, int contentTop, int mouseY) {
        layoutRankPage(st);
        int left = tradeContentLeft();
        int right = tradeContentRight();
        int top = tradeBoundsTop() + 4;
        int bottom = tradeContentBottom();
        List<MarketClientState.RankInfo> rankingEntries = rankBoard(st);
        String title = rankSub == 0 ? cn.oremarket.common.RawText.t("oremarket.text.0537")
                : rankSub == 1 ? cn.oremarket.common.RawText.t("oremarket.text.0535")
                : rankSub == 3 ? cn.oremarket.common.RawText.t("oremarket.rank.title.debt")
                : rankSub == 4 ? cn.oremarket.common.RawText.t("oremarket.rank.title.credit")
                : cn.oremarket.common.RawText.t("oremarket.text.0481");
        final int[] rankClip = {left, top, right, Math.max(top + 1, bottom - LIST_PAD)};
        try {
            scissor(g, rankClip[0], rankClip[1], rankClip[2], rankClip[3]);
            drawSectionTitle(g, title, left, rankLayout.y(0) - rankScroll + 5, right - left, GOLD);
            pageText(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0539")), left,
                    rankLayout.y(1) - rankScroll + 5, TEXT_MUTED);
            int rowCount = Math.min(10, rankingEntries.size());
            int firstRow = 2;
            if (rowCount <= 0) {
                pageCentered(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0180")), (left + right) / 2,
                        rankLayout.y(firstRow) - rankScroll + 3, GRAY88);
            }
            for (int rankIndex = 0; rankIndex < rowCount; rankIndex++) {
                MarketClientState.RankInfo entry = rankingEntries.get(rankIndex);
                boolean dailyRanking = rankSub == 1;
                boolean creditRanking = rankSub == 4;      // the credit leaderboard holds small integers and is not abbreviated in coin units
                boolean valueMissing = dailyRanking && entry.value == Long.MIN_VALUE;
                String displayValue = valueMissing ? "--"
                        : dailyRanking ? L.moneySigned((double) entry.value)
                        : creditRanking ? Long.toString(entry.value)
                        : formatOre(entry.value);
                int valueColor = !dailyRanking ? (rankSub == 3 ? TOAST_RED : TEXT_WHITE) : pnlColor(valueMissing ? 0 : entry.value);
                // rows 10-20 of the leaderboard were tens of Formatter allocations per frame; now direct concatenation with padded spaces.
                String rowText = Num.padLeft(Integer.toString(rankIndex + 1), 6) + " "
                        + Num.padLeft(entry.name, 28) + " " + displayValue;
                drawFitLeft(g, rowText, left, rankLayout.y(firstRow + rankIndex) - rankScroll + 3, right - left, valueColor);
                if (rankIndex < rowCount - 1) {
                    int rowY = rankLayout.y(firstRow + rankIndex) - rankScroll;
                    g.fill(left, rowY + LIST_ROW - 1, right, rowY + LIST_ROW, EX_SEP_COLOR);
                }
            }
            if (MarketSettings.client() == null || MarketSettings.client().leaderboardShowSelf) {
                int ownRank = switch (rankSub) {
                    case 1 -> st.selfRankDaily;
                    case 2 -> st.selfRankPositions;
                    case 3 -> st.selfRankDebt;
                    case 4 -> st.selfRankCredit;
                    default -> st.selfRankAssets;
                };
                pageText(g,L.tr(ownRank > 0 ? cn.oremarket.common.RawText.t("oremarket.text.0327") + ownRank + cn.oremarket.common.RawText.t("oremarket.text.0013") : cn.oremarket.common.RawText.t("oremarket.text.0391")),
                        left, rankLayout.y(rankLayout.size() - 1) - rankScroll + 5, GOLD);
            }
        } finally {
            g.disableScissor();
        }
        drawBottomLine(g, rankLayout, rankScroll, left, right);
    }

    /**
     * Quotes tab: K-line chart (full width) -> left/right split -> trading button area (vanilla widgets, bottom-hugging).
     * The left column takes 60% for the stock list, the right 40% for the position detail panel, 4px apart, 2px inner padding each,
     * equal height, each scrolling vertically on its own, and the two columns never cover each other.
     */
    private void drawMarketPage(GuiGraphics g, MarketClientState st, int x, int contentTop,
                                int mouseX, int mouseY) {
        drawChart(g, st, x + 2, contentTop + chartTop - TAB_H, mkW - 4, chartH, mouseX, mouseY);
        int sy = contentTop + splitTop - TAB_H;
        drawStockColumn(g, st, x + 2, sy, leftColW, splitH, mouseX, mouseY);
        drawDetailColumn(g, st, x + rightColOff, sy, rightColW, splitH);
        //: the right column (buy/sell button group) shares the top edge, height and border style with the left and middle columns, so three columns read as one set;
        // the buttons themselves draw in the vanilla widget layer, so only the frame is completed here and no interaction is intercepted.
        g.renderOutline(x + btnsColOff - 1, sy - 1, btnsColW + 2, splitH + 2, BORDER_MAIN);
        MarketSettings cs = MarketSettings.client();
        double taxPct = cs == null ? 1.0 : cs.taxDestroyRate * 100.0;
        String taxText = L.tr(cn.oremarket.common.RawText.t("oremarket.text.0038") + Num.fixed(taxPct, 1) + cn.oremarket.common.RawText.t("oremarket.text.0226"));
        // The fee hint line sits at the button column's bottom (inside the right column), right-aligned to the column edge, whole-row scaled down when too wide.
        int hintY = mkAbsY() + btnsTop + btnsAreaH - TRADE_HINT_LINE_H + 2;
        int hintRight = gx + mkX + btnsColOff + btnsColW - 4;
        drawFitRight(g, taxText, hintRight, hintY, btnsColW - 8, ACCENT_ORANGE);
        //: under the buy/sell button group show currently selected stock: XXX, just above the locate-position button.
        int locY = -1;
        for (var w : this.renderables) {
            if (w instanceof MarketButton mb && mb.mark == M_LOCATE && mb.visible) {
                locY = mb.getY();
                break;
            }
        }
        if (locY > 0) {
            String name;
            if (selected >= 0 && selected < st.viewSize()) {
                Stock s = st.stocks[selected];
                name = s == null ? "--" : L.tr(s.displayName());
            } else {
                name = trKey("oremarket.picker.none");
            }
            String line = trKey("oremarket.market.selected", name);
            int tx = gx + mkX + btnsColOff + 4;
            int tw = btnsColW - 8;
            drawFitLeft(g, line, tx, locY - 12, tw, TEXT_WHITE);
        }
    }

    /**
     * Left column's stock list: every row is 16 tall = level dot, item icon, stock name, current price, change percentage.
     * The whole render is wrapped in enableScissor/disableScissor's try-finally: the clip's top-left corner = the list's left bound,
     * bottom-right = the list's right bound + the 4px scrollbar and the list's bottom bound, all in GUI-scaled coordinates (matching the drawing),
     * screen physical coordinates are banned; between enable and disable only list content and the scrollbar draw,
     * and disableScissor runs no matter what is thrown.
     * The content width always equals the container width: the scrollbar is an overlay, takes no content width,
     * appears only when content outgrows the viewport, drawn 4px inside the list's right bound and inside the clip.
     * Only rows fully inside the clip draw; a half-exposed bottom row is skipped; the 16px row height is never compressed.
     * Narrow-width hiding order: name first, icon second, level dot last;
     * price and change are right-aligned, never hidden, always 4px apart. Locked rows render 0xFF666666 with ?? price and change
     * and the hover hint reach a peak of X coins to unlock.
     * (close-marker removed by guard)
     */
    private void drawStockColumn(GuiGraphics g, MarketClientState st, int colX, int colY,
                                 int colW, int colH, int mouseX, int mouseY) {
        // Part 2: clip rule = padding 4 top and bottom, left/right hugging the container's inner edges
        // (row content already carries 2px side padding; another 4px horizontal shrink would cut the right-aligned price / change digits).
        if (colH <= 0) {
            return;         // rule four: usable height <= 0 -- skip the entire stock list render (no text, no scrollbar)
        }
        g.renderOutline(colX - 1, colY - 1, colW + 2, colH + 2, BORDER_MAIN);
        hoverListRow = -1;
        if (colW < 40) {
            return;
        }
        // Every clip subtracts padding=4 (listClip is the single rule), paired closed via try/finally
        final int[] clip = stockListClip(colX, colY, colW, colH);
        // rule four: 0 < usable height < 16 -- render no rows; paint a grey not enough space at the list centre (over-wide text trims characters from the end)
        if (colH < LIST_ROW) {
            try {
                scissor(g, clip[0], clip[1], clip[2], clip[3]);
                drawFitCentered(g, cn.oremarket.common.RawText.t("oremarket.text.0190"),
                        colX, colX + colW, colY + Math.max(0, (colH - 9) / 2), GRAY88);
            } finally {
                g.disableScissor();
            }
            return;
        }
        // Overlay-style scrollbar: no content width is given up; list content width = container width; rows keep 2px side padding
        int rowLeft = colX + 2;
        int rowRight = colX + colW - 2;
        int chgRight = rowRight;                   // right edge of the change column (width 50)
        int priceRight = chgRight - 50 - 4;        // right edge of the price column (width 60), always 4px clear of the change column
        int dotX = rowLeft + 2;                    // column one: level dot (width 8)
        int iconX = rowLeft + 12;                  // column two: item icon (width 18)
        int nameX = rowLeft + 32;                  // column three: stock name (dynamic column, left-aligned)
        int nameColW = priceRight - 4 - nameX;
        // hiding order: name first, icon next, dot last (price and change are never hidden)
        boolean showName = nameColW >= 24;
        boolean showIcon = colW >= 34;
        boolean showDot = colW >= 20;
        try {
            // Five: clip = the viewport of the container minus padding=4; the row origin and the clip's top edge use one rule (the first row no longer touches the border)
            scissor(g, clip[0], clip[1], clip[2], clip[3]);
            for (int i = 0; i < n(); i++) {
                Stock s = st.stocks[i];
                // Five: row y = viewport top + index x 16 - stockScrollOffset (the scroll offset is pixels, not row numbers)
                int rowTop = clip[1] + i * LIST_ROW - mkScroll;
                // and the overage is left to the scissor. The old code demanded whole-row visibility (rowTop >= colY and
                // rowBottom <= colY + colH), so during scrolling the edge rows vanished entirely --
                // they appeared only when a whole row finally fit (the user-reported symptom).
                if (!rowIntersects(rowTop, rowTop + LIST_ROW, clip[1], clip[3])) {
                    continue;
                }
                boolean unlocked = st.unlocked(i);
                boolean susp = st.suspended(i);
                // Hover area = row rect INTERSECT viewport, so a half row under the cursor still highlights (the old code skipped drawing invisible rows
                // and the hover died with them)
                boolean hover = mouseX >= rowLeft - 2 && mouseX < rowRight + 2
                        && mouseY >= visibleTop(rowTop, rowTop + LIST_ROW, clip[1], clip[3])
                        && mouseY < visibleBottom(rowTop, rowTop + LIST_ROW, clip[1], clip[3]);
                if (hover) {
                    hoverListRow = i;
                }
                // Part 7 item 4: row divider + 2px left stripe on the selected row (0xFF6A8FD0) + hover 0xFF404040
                // The row background rect shares the clip viewport's bounds (pixels outside the padding used to be clipped away,
                // and the selected row's 2px left stripe was exactly what got shaved off)
                if (i < n() - 1) {
                    g.fill(clip[0], rowTop + LIST_ROW - 1, clip[2], rowTop + LIST_ROW, EX_SEP_COLOR);
                }
                if (i == selected) {
                    g.fill(clip[0], rowTop, clip[2], rowTop + LIST_ROW, LIST_SEL_BG);
                    g.fill(clip[0], rowTop, clip[0] + 2, rowTop + LIST_ROW, DOT_UNLOCKED);
                } else if (hover) {
                    g.fill(clip[0], rowTop, clip[2], rowTop + LIST_ROW, LIST_HOVER_BG);
                } else if (susp) {
                    g.fill(clip[0], rowTop, clip[2], rowTop + LIST_ROW, SUSPEND_BG);
                }
                int ty = rowTop + (LIST_ROW - 9) / 2;
                int color = susp ? SUSPEND_TX : (unlocked ? TEXT_WHITE : LOCKED_TX);
                if (showDot) {
                    // unlock status dot: unlocked = solid 0xFF6A8FD0; locked = hollow 0xFF888888
                    int dy = rowTop + (LIST_ROW - 6) / 2;
                    drawStatusDot(g, dotX, dy, unlocked, susp);
                }
                if (showIcon) {
                    drawItemIcon(g, itemIcon(s), iconX + 1, rowTop);   // guard two: an empty stack paints the placeholder square
                }
                // Halt badge: exactly ONE label, right-aligned at the row's right edge (inside the change column).
                // The old code drew the same label twice (price column + change column); the 54px column pitch is
                // narrower than the English label, so the two copies collided. The label resolves by KEY (trKey),
                // not by the raw-text reverse lookup, so every language gets its own short value deterministically
                // (one short localized HALT label per language). The name column yields width only when a translation would reach past
                // the price column, so name and badge can never overlap in any language.
                String suspBadge = null;
                double suspBadgeScale = 1.0;
                int suspBadgeW = 0;
                if (susp && font != null) {
                    suspBadge = trKey("oremarket.text.0026");
                    suspBadgeScale = hardFitScale(suspBadge, Math.max(24, chgRight - nameX - 4));
                    suspBadgeW = scaledW(suspBadge, suspBadgeScale);
                }
                if (showName) {
                    int nameRight = priceRight - 4;
                    if (susp) {
                        nameRight = Math.min(nameRight, chgRight - suspBadgeW - 4);
                    }
                    drawFitLeftFull(g, s.displayName(), nameX, ty, Math.max(0, nameRight - nameX), color);
                }
                if (susp) {
                    drawScaled(g, suspBadge, chgRight - suspBadgeW, ty, suspBadgeScale, SUSPEND_TX);
                    continue;
                }
                if (!unlocked) {
                    // locked rows: price and change both render as two question marks
                    drawRightAligned(g, "??", priceRight, ty, LOCKED_TX);
                    drawRightAligned(g, "??", chgRight, ty, LOCKED_TX);
                    continue;
                }
                if (!st.quoteAt(i)) {
                    //: the quote cache for this code is missing -> price and change show -- (not 0, not ??)
                    drawRightAligned(g, "--", priceRight, ty, PNL_FLAT);
                    drawRightAligned(g, "--", chgRight, ty, PNL_FLAT);
                    continue;
                }
                double chg = st.changePercent(i);
                String flag = limitFlag(chg, st.limitPct(i));
                String chgText = flag != null ? flag : Num.pct(chg, 1);
                int chgColor = flag != null ? (chg >= 0 ? PNL_UP : PNL_DOWN)
                        : (chg > 0.001 ? PNL_UP : chg < -0.001 ? PNL_DOWN : PNL_FLAT);
                drawRightAligned(g, chgText, chgRight, ty, chgColor);
                // precision unification: the current price keeps two decimals like the K-line title and the logs;
                // extremely long numbers go through the numeric-specific fallback (shrink font -> 0.65 -> ellipses allowed for digits).
                drawFitNumber(g, formatPrice(st.price[i]), priceRight, ty, 60, TEXT_WHITE);
            }
            // the scrollbar draws inside the clip region without its own clip; hidden while content fits
        } finally {
            g.disableScissor();
        }
        // hover tooltip on locked rows
        if (pendingTooltip == null && hoverListRow >= 0 && !st.unlocked(hoverListRow)
                && !st.suspended(hoverListRow)) {
            pendingTooltip = Component.literal(L.tr(st.unlockHint(hoverListRow))).withStyle(ChatFormatting.GRAY);
        }
    }


    /**
     * Right column's position detail panel (every figure is computed and synced by the server: averageCost / realizedPnl /
     * sellable-frozen / current price); the client only displays, computes nothing locally and never shows another player's data.
     * The stock-name title at the top is removed and six fields show directly (position / cost price / current price /
     * floating P&L / position value / realized P&L); the container keeps padding=4 so text never hugs the border.
     * Without positions the first line says no position held, remaining fields show --, and realized P&L still shows its real value;
     * with no stock selected it says please select a stock.
     */
    private void drawDetailColumn(GuiGraphics g, MarketClientState st, int colX, int colY,
                                  int colW, int colH) {
        g.renderOutline(colX - 1, colY - 1, colW + 2, colH + 2, BORDER_MAIN);
        int x0 = colX + DETAIL_PAD;
        int right = colX + colW - DETAIL_PAD - (detailMaxScroll() > 0 ? COL_SB_W : 0);
        if (right - x0 < 30) {
            return;
        }
        if (selected < 0) {
            pageCentered(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0257")), colX + colW / 2, colY + colH / 2 - 4, PNL_FLAT);
            return;
        }
        int i = selected;
        if (i < 0 || i >= st.stocks.length) {
            return;
        }
        boolean unlocked = st.unlocked(i);
        int shares = st.totalShares(i);
        // detail column's own clip: when the event bar or a small window shrinks the usable height, rows may only scroll or be cut,
        // and must never cross the detail frame to cover the buy/sell buttons below. Pixel-level scissor: half rows still draw.
        final int[] dclip = listClip(colX, colY, colW, colH, DETAIL_PAD);
        try {
            scissor(g, dclip[0], dclip[1], dclip[2], dclip[3]);
            int y = colY + DETAIL_PAD - detailScroll;
            if (shares <= 0) {
                // no position: first line says no position held, every later field shows -- (no numbers)
                drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.text.0041"), cn.oremarket.common.RawText.t("oremarket.text.0242"), x0, y, right, PNL_FLAT);
                y += DETAIL_ROW;
                drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.text.0110"), "--", x0, y, right, GRAY88);
                y += DETAIL_ROW;
                drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.text.0054"), "--", x0, y, right, GRAY88);
                y += DETAIL_ROW;
                drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.text.0183"), "--", x0, y, right, GRAY88);
                y += DETAIL_ROW;
                drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.text.0169"), "--", x0, y, right, GRAY88);
                y += DETAIL_ROW;
            } else {
                // The position share count also runs the unified unit abbreviation (Chinese uses wan/yi suffixes, English K/M/B);
                // 999993200 no longer bursts the detail row as ten bare digits
                drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.text.0041"), L.money(shares) + cn.oremarket.common.RawText.t("oremarket.text.0272") + L.money(st.available[i])
                        + cn.oremarket.common.RawText.t("oremarket.text.0263") + L.money(st.frozen[i]) + L.parenClose(), x0, y, right, TEXT_WHITE);
                y += DETAIL_ROW;
                drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.text.0110"), formatPrice(st.avgCost[i]), x0, y, right, TEXT_WHITE);
                logCostDisplay(st, i, st.avgCost[i]);
                y += DETAIL_ROW;
                drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.text.0054"), unlocked && st.quoteAt(i) ? formatPrice(st.price[i]) : "--",
                        x0, y, right, TEXT_WHITE);
                y += DETAIL_ROW;
                // Floating P&L = (current price - averageCost) x shares (both come from server sync);
                // With the quote cache missing, floating P&L and value show --; both fields go through the unified unit conversion
                // realized P&L: per-share record from the server, shown at its real value even with no position (unified unit conversion)
                if (st.quoteAt(i)) {
                    double fp = st.floatPnl(i);
                    double base = st.avgCost[i] * shares;
                    String fpText = base > 0
                            ? L.moneySigned(fp) + " (" + Num.pct(fp / base * 100.0, 1) + ")"
                            : L.moneySigned(fp);
                    drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.text.0183"), fpText, x0, y, right, pnlColor(fp));
                    y += DETAIL_ROW;
                    drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.text.0169"), L.money(Math.round(st.price[i] * shares)),
                            x0, y, right, TEXT_WHITE);
                } else {
                    drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.text.0183"), "--", x0, y, right, GRAY88);
                    y += DETAIL_ROW;
                    drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.text.0169"), "--", x0, y, right, GRAY88);
                }
                y += DETAIL_ROW;
            }
            // Cost-price display tracing (performance fix): the detail panel repaints every frame, and the old log only the first 20 times
            // still spammed 20 INFO lines per UI opening with no rate-limiting semantics. Now:
            // - log one line only when the selected stock or the cost value itself changes (deduplicated by the code=value signature);
            // - level dropped to DEBUG: fully silent at default levels, switch OreMarket/UI to debug when investigating.
            // Floats compare at printed precision (4 decimals) so last-digit jitter cannot spam the log.
            double rp = st.realizedByStock[i];
            drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.text.0241"), L.moneySigned(rp), x0, y, right, pnlColor(rp));
            y += DETAIL_ROW;
            long volume = cn.oremarket.common.MarketVolume.roundShares(
                    i < st.currentVolume.length ? st.currentVolume[i] : 0.0);
            drawDetailRow(g, cn.oremarket.common.RawText.t("oremarket.detail.volume"),
                    L.money(volume) + cn.oremarket.common.RawText.t("oremarket.text.0017"),
                    x0, y, right, TEXT_WHITE);
        } finally {
            g.disableScissor();
        }
    }

    /**
     * Detail row: label left-aligned, value right-aligned; when it does not fit, walk the 1.0 -> 0.65 scaling ladder,
     * and past 0.65 keep shrinking through the floor step (this panel's rows are fixed height and may not wrap onto neighbours);
     * no step truncates, adds ellipses, or crosses the detail column's bound.
     */
    private void logCostDisplay(MarketClientState st, int index, double value) {
        if (index < 0 || index >= st.viewSize() || !LOG.isDebugEnabled()) {
            return;
        }
        String signature = st.codeAt(index) + "=" + Num.fixed(value, 4);
        if (signature.equals(costDisplaySignature)) {
            return;
        }
        costDisplaySignature = signature;
        String player = minecraft != null && minecraft.player != null
                ? minecraft.player.getGameProfile().getName() : "unknown";
        LOG.debug("Cost display: player {}, stock {}, value {}, source savedData",
                cn.oremarket.common.DebugLog.ascii(player),
                st.codeAt(index), Num.fixed(value, 4));
    }

    /**
     * Loan page layout: every section's y accumulates from the rows of content above it,
     * the total page height loanPageH is fully computed from content (scroll rule five: hardcoded panel heights are banned),
     * and scroll overflow plus offset clamping are recomputed on the spot in layoutMarket / setScroll from the new geometry.
     */
    private void drawDetailRow(GuiGraphics g, String label, String value, int x0, int y, int right, int color) {
        int ty = y + (DETAIL_ROW - 9) / 2;
        String lt = L.tr(label);
        String vt = value == null ? null : L.tr(value);
        int rowW = Math.max(0, right - x0);
        if (lt.isEmpty() || rowW <= 0) {
            return;
        }
        int labelRoom = Math.max(16, rowW * 4 / 9);
        double ls = cellFitScale(lt, labelRoom);
        int lw = drawScaled(g, lt, x0, ty, ls, HINT_GRAY);
        if (vt == null || vt.isEmpty() || rowW <= lw + 4) {
            return;
        }
        double vs = cellFitScale(vt, rowW - lw - 4);
        drawScaled(g, vt, right - scaledW(vt, vs), ty, vs, color);
    }

    /**
     * Loan page layout: each section's y accumulates from the rows of content above it,
     * the total page height loanPageH is fully computed from content (scroll rule five: hardcoded panel heights are banned),
     * and scroll overflow plus offset clamping recompute on the spot in layoutMarket / setScroll from the new geometry.
     */
    private void computeLoanLayout(MarketClientState st) {
        int code = loanStateCode(st);
        int y = 2;
        for (int i = 0; i < LS_SLOTS; i++) {
            loanY[i] = y;
        }
        loanY[LS_CREDIT] = y;
        y += LOAN_HEAD_H;
        int infoLines = loanInfoLines(st, code);
        loanY[LS_INFO] = y;
        y += infoLines * LOAN_LINE_H + 3;
        if (code == 1) {
            loanY[LS_PHEAD] = y; y += LOAN_HEAD_H;
            loanY[LS_PROD] = y; y += LOAN_BTN_H + 3;
            loanY[LS_MHEAD] = y; y += LOAN_HEAD_H;
            loanY[LS_MODE] = y; y += LOAN_BTN_H + 3;
            loanY[LS_THEAD] = y; y += LOAN_HEAD_H;
            loanY[LS_TERM] = y; y += LOAN_BTN_H + 2;
            loanY[LS_TERMNOTE] = y; y += LOAN_LINE_H + 3;   // total term: X trading days
            loanY[LS_AHEAD] = y; y += LOAN_HEAD_H;
            loanY[LS_AMT] = y; y += LOAN_BTN_H + 5;         // amount stepper + borrow-max share one row
            loanY[LS_EHEAD] = y; y += LOAN_HEAD_H;
            // The estimate block's height = the true summed line counts of four text rows wrapped at column width,
            // so no language (long en_us texts included) escapes the border; the confirm/cancel row moves out of the scroll flow
            // and pins to the viewport bottom (loanPinnedH was already deducted from the usable height, so it never overlaps the event bar).
            loanY[LS_EST] = y; y += loanEstBlockH(st);
            loanY[LS_CONFIRM] = y;
            // v2, P2P: player-to-player lending notices (with the switch off the block takes no height and no button appears)
            if (p2pEnabled()) {
                loanY[LS_P2PHEAD] = y; y += LOAN_HEAD_H;
                loanY[LS_P2P] = y; y += 2 * LOAN_LINE_H + 2;
                loanY[LS_P2PBTN] = y; y += LOAN_BTN_H + 2;
            }
            if (st.loan.clawbackDebt > 0 || st.loan.shortDebt > 0) {
                // bankruptcy-unforgiven debt / forced-liquidation shortfall: offer an active settle entry (deducts balance only, never force-sells positions).
                // or the button would be painted outside its reserved row, onto the next section's title.
                loanY[LS_CLAW] = y; y += LOAN_BTN_H + 2;
            }
        } else if (code == 2) {
            loanY[LS_PLANHEAD] = y; y += LOAN_HEAD_H;
            int rows = Math.max(1, st.loanSchedule().size());
            loanY[LS_PLAN] = y; y += (1 + rows) * LOAN_LINE_H + 5;
            loanY[LS_REPAY] = y; y += LOAN_BTN_H + 3;
            loanY[LS_PREPAY] = y; y += LOAN_BIG_BTN_H + 2;
            if (st.loan.clawbackDebt > 0 || st.loan.shortDebt > 0) {
                // this row keeps the settle-shortfall entry for in-loan states too (repays the shortfall only, leaves the loan untouched).
                loanY[LS_CLAW] = y; y += LOAN_BTN_H + 2;
            }
        } else if (code == 4) {
            loanY[LS_HINT] = y; y += 2 * LOAN_LINE_H + 3;
            loanY[LS_AMT] = y; y += LOAN_BTN_H + 2;
            if (st.loan.clawbackDebt > 0 || st.loan.shortDebt > 0) {
                // Cooldown periods likewise reserve the settle-shortfall button row (same terms as buildLoanWidgets)
                loanY[LS_CLAW] = y; y += LOAN_BTN_H + 2;
            }
        } else {
            loanY[LS_HINT] = y; y += LOAN_LINE_H + 2;
        }
        loanPageH = y;
    }

    /**
     * Information-area row count: must match drawLoanInfo's painting order one-for-one:
     * the values must account for every row painted by an active loan so the next section's title remains visible.
     * Any change to drawLoanInfo must update this count in step.
     */
    private int loanInfoLines(MarketClientState st, int code) {
        int n = 0;
        if (code == 2) {
            n += 10;  // product / repayment mode / installment / principal / interest / penalty / this-period due / maturity date / days left / daily rate
        }
        n += 4;       // available quota / used quota / total debt / credit score
        n += 1;       // code==2 is the loan's money bag; the others use the base quota
        n += 1;       // credit history
        if (st.loan.clawbackDebt > 0) {
            n += 1;   // bankruptcy unforgiven debt
        }
        if (st.loan.shortDebt > 0) {
            n += 1;   // forced liquidation shortfall
        }
        return n;
    }

    /**
     * Reserved height of the loan page's fixed bottom button row (only a borrowable state has confirm/cancel).
     * = button height + 4px from the scrolled content + 6px from the event bar, all three deducted from the visible height,
     * so usable height = container height - tab bar - bottom button row - event bar band, and the row never overlaps the event bar.
     */
    private int loanPinnedH(MarketClientState st) {
        return loanStateCode(st) == 1 && !st.showBankruptBanner() ? LOAN_BIG_BTN_H + 10 : 0;
    }

    /** Loan page's visible content area (one rect shared by scroll clipping and hit tests; its bottom already excludes the fixed button row). */
    private int[] loanViewport() {
        // Part 2: 4px padding top and bottom; scrolled content and the fixed button row both judge against this viewport
        int top = mkAbsY() + TAB_H + LIST_PAD;
        int bottom = Math.min(mkAbsY() + mkH, gy + barY - 2)
                - loanPinnedH(MarketClientState.INSTANCE) - LIST_PAD;
        return new int[]{gx + mkX + 2, top, gx + mkX + mkW - 2, Math.max(top + 1, bottom)};
    }

    /** Top edge of the fixed button row (content viewport bottom + 4px gap); its bottom hugs the container's usable bottom (at least 4px from the event bar). */
    private int loanPinnedTop() {
        int[] vp = loanViewport();
        return vp[3] + 4;
    }

    /** // section title (drawn only when pixel-visible) */
    private void loanHeader(GuiGraphics g, int y, int x, int w, int[] view, String title) {
        if (!rowIntersects(y, y + LOAN_HEAD_H, view[1], view[3])) {
            return;
        }
        pageText(g, title, x, y + 2, TEXT_MUTED);
        g.fill(x, y + LOAN_HEAD_H - 2, x + w, y + LOAN_HEAD_H - 1, 0xFF2A2A2A);
    }

    /**
     * One label: value row (scroll rule three: judge by pixel intersection only,
     * half rows still draw; never use the first-visible-row-plus-fixed-count scheme).
     */
    private void loanRow(GuiGraphics g, int y, int x, int w, int[] view, String key, int color, Object... args) {
        if (!rowIntersects(y, y + LOAN_LINE_H, view[1], view[3])) {
            return;
        }
        drawFitLeft(g, trKey(key, args), x, y, w, color);
    }

    /** Same as above but fed pre-composed text (for dynamic numbers). */
    private void loanText(GuiGraphics g, int y, int x, int w, int[] view, String text, int color) {
        if (!rowIntersects(y, y + LOAN_LINE_H, view[1], view[3])) {
            return;
        }
        drawFitLeft(g, text, x, y, w, color);
    }

    /** Loan page information area (shared by all states: quota / debt / credit score / credit history). */
    private void drawLoanInfo(GuiGraphics g, MarketClientState st, int code, int base,
                              int cx, int innerW, int[] view) {
        int y = base + loanY[LS_INFO];
        if (code == 2) {
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.product", TEXT_WHITE, trKey(st.loanProductKey()));
            y += LOAN_LINE_H;
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.mode", TEXT_WHITE, trKey(st.loanModeKey()));
            y += LOAN_LINE_H;
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.inst", TEXT_WHITE,
                    String.valueOf(st.loan.nextInst), String.valueOf(Math.max(1, st.loan.installments)));
            y += LOAN_LINE_H;
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.principal", TEXT_WHITE, L.money(st.loan.loanAmount));
            y += LOAN_LINE_H;
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.interest", GOLD, L.money(st.loanInterest()));
            y += LOAN_LINE_H;
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.penalty",
                    st.loan.penalty > 0 ? TOAST_RED : TEXT_WHITE, L.money(st.loan.penalty));
            y += LOAN_LINE_H;
            // This-period due = at most what one repayment click may deduct.
            // the number comes from LoanCalculator.payableBill, the very function the server's LoanData.payableBill calls;
            // the cap shown on screen is the cap actually charged, so never again five periods of interest displayed while only one is due.
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.period_due", GOLD,
                    L.money(loanPeriodBill(st)));
            y += LOAN_LINE_H;
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.due", TEXT_WHITE, String.valueOf(st.loan.loanDueDay));
            y += LOAN_LINE_H;
            long left = st.loanDaysLeft();
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.left",
                    left <= 0 || st.loan.liquidating ? TOAST_RED : TEXT_WHITE,
                    left > 0 ? String.valueOf(left) : left == 0 ? trKey("oremarket.loan.plan.due_today")
                            : trKey("oremarket.loan.ui.overdue"));
            y += LOAN_LINE_H;
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.rate", TEXT_WHITE,
                    cn.oremarket.common.Num.fixed(st.loan.dailyRate * 100.0, 3));
            y += LOAN_LINE_H;
        }
        loanRow(g, y, cx, innerW, view, "oremarket.loan.row.available", TEXT_WHITE, L.money(st.availableQuota()));
        y += LOAN_LINE_H;
        loanRow(g, y, cx, innerW, view, "oremarket.loan.row.used", TEXT_WHITE, L.money(st.usedQuota()));
        y += LOAN_LINE_H;
        loanRow(g, y, cx, innerW, view, "oremarket.loan.row.debt",
                st.hasDebt() ? GOLD : TEXT_WHITE, L.money(st.totalDebt()));
        y += LOAN_LINE_H;
        loanRow(g, y, cx, innerW, view, "oremarket.loan.row.score", TEXT_WHITE,
                String.valueOf(st.loan.creditScore), trKey(st.loanScoreKey()));
        y += LOAN_LINE_H;
        if (code == 2) {
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.loanfunds",
                    st.loan.loanFunds > 0 ? TOAST_RED : TEXT_WHITE, L.money(st.loan.loanFunds));
            y += LOAN_LINE_H;
        } else {
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.limitbase", TEXT_MUTED,
                    L.money(MarketSettings.client() == null ? 0 : MarketSettings.client().creditLimit(st.tier())));
            y += LOAN_LINE_H;
        }
        loanRow(g, y, cx, innerW, view, "oremarket.loan.row.history", TEXT_MUTED,
                String.valueOf(st.loan.onTimeRepays), String.valueOf(st.loan.overdueTotal),
                String.valueOf(st.loan.bankruptcies));
        y += LOAN_LINE_H;
        if (st.loan.clawbackDebt > 0) {
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.clawback", TOAST_RED, L.money(st.loan.clawbackDebt));
            y += LOAN_LINE_H;
        }
        if (st.loan.shortDebt > 0) {
            loanRow(g, y, cx, innerW, view, "oremarket.loan.row.short_debt", TOAST_RED, L.money(st.loan.shortDebt));
        }
    }

    /**
     * The estimated daily rate (decimal) under the current selection, carrying the same cap as the server's lockedRateFor.
     * The dialog, the cost estimate and the plan interest all read it here; showing the uncapped quoteRate directly is strictly banned.
     */
    private double quotedDailyRate(MarketClientState st) {
        cn.oremarket.market.MarketSettings cs =
                cn.oremarket.market.MarketSettings.client();
        if (st == null || st.loan == null || cs == null) {
            return 0.0;
        }
        double quote = st.loan.quoteRate;
        if (!Double.isFinite(quote) || quote < 0) {
            quote = 0;
        }
        int product = Math.max(0, Math.min(3, loanProductSel));
        double factor = product == 2 ? cs.loanEmergencyRateFactor
                : product == 1 ? cs.loanCollateralRateFactor
                : product == 3 ? cs.loanMarginRateFactor : 1.0;
        int n = Math.max(1, Math.min(loanTermLimit(), Math.max(1, loanInstallmentsSel)));
        return cn.oremarket.common.LoanRules
                .lockedDailyRate(quote, factor, n, cs.loanRateCapPercent);
    }

    /** Estimate under the current selection (the client runs the same pure-integer math, display only; the server's settlement is authoritative). */
    private long[] loanQuote(MarketClientState st) {
        cn.oremarket.market.MarketSettings cs =
                cn.oremarket.market.MarketSettings.client();
        if (st == null || st.loan == null || cs == null || loanSelAmount <= 0) {
            return new long[]{0, 0, 0};
        }
        double rate = quotedDailyRate(st);
        int mode = Math.max(0, Math.min(3, loanModeSel));
        int n = Math.max(1, Math.min(loanTermLimit(), Math.max(1, loanInstallmentsSel)));
        int term = cn.oremarket.common.LoanSchedule.totalTermDays(n, configuredLoanTermDays());
        long interest = cn.oremarket.common.LoanCalculator
                .cappedPlannedInterest(loanSelAmount, rate, term, n, mode, cs.loanInterestCapMultiplier);
        return new long[]{loanSelAmount, interest, Math.min(Long.MAX_VALUE - interest, loanSelAmount + interest)};
    }

    /**
     * This-period due (client display): at most what the server deducts when repay 1 / 10 / 100 / all is clicked.
     * The criterion itself is the pure function LoanCalculator.payableBill and the server's LoanData.payableBill calls the same method,
     * so the screen figure and the real cap always agree: periods not yet due never count (the cross-period prepayment red line).
     *
     * <p>The schedule is rebuilt locally from the shipped parameters (borrow day / principal / locked rate / term / installments / mode / current period) --
     * the same data source as the repayment schedule table, with no extra network field.
     */
    private long loanPeriodBill(MarketClientState st) {
        cn.oremarket.net.packet.LoanInfo info =
                st == null ? null : st.loan;
        if (info == null || info.loanAmount <= 0) {
            return 0L;
        }
        long principal = info.principal > 0 ? info.principal : info.loanAmount;
        java.util.List<cn.oremarket.common.LoanSchedule.Row> rows =
                cn.oremarket.common.LoanCalculator.schedule(
                        info.borrowDay, principal, info.dailyRate, info.termDays,
                        info.installments, info.mode);
        long remainingInterest = cn.oremarket.common.LoanCalculator
                .remainingInterestOwed(rows, info.paidInterest);
        // Total repaid (penalty + interest + principal): on the server's LoanData.paidTotal() exact terms --
        // repaid principal = the principal snapshot minus outstanding principal (applySplit moves both in lockstep, an identity; dirty values clamp to 0),
        // so the screen's this-period due shrinks with every payment (fix: the old rule did not subtract what was already paid,
        // and repeated repay-100 clicks refilled the full-period interest component each time, filling periods 2-5 early).
        long paidPrincipal = Math.max(0L, principal - Math.max(0L, info.loanAmount));
        long paidTotal = paidPrincipal > Long.MAX_VALUE - info.paidInterest
                ? Long.MAX_VALUE : paidPrincipal + Math.max(0L, info.paidInterest);
        paidTotal = paidTotal > Long.MAX_VALUE - info.paidPenalty
                ? Long.MAX_VALUE : paidTotal + Math.max(0L, info.paidPenalty);
        // Interest-first mode before the final period keeps the principal path closed: the same criterion as the server's LoanService.principalAllowed
        boolean allowPrincipal = !(info.mode == cn.oremarket.common
                .LoanSchedule.MODE_INTEREST_FIRST
                && info.tradeDay < info.loanDueDay
                && info.nextInst < Math.max(1, info.installments));
        return cn.oremarket.common.LoanCalculator.payableBill(
                rows, info.nextInst, info.tradeDay, info.penalty, remainingInterest,
                info.loanAmount, allowPrincipal, paidTotal);
    }

    /** The raw text of the four cost-estimate rows (the layout's measuring and the painting share it, so the two can never drift). */
    private String[] loanEstTexts(MarketClientState st) {        long[] q = loanQuote(st);
        int n = Math.max(1, Math.min(loanTermLimit(), Math.max(1, loanInstallmentsSel)));
        int term = cn.oremarket.common.LoanSchedule.totalTermDays(n, configuredLoanTermDays());
        return new String[]{
                trKey("oremarket.loan.row.principal", L.money(q[0])),
                trKey("oremarket.loan.estimate.total", L.money(q[2])),
                trKey("oremarket.loan.estimate.rate",
                        cn.oremarket.common.Num.fixed(quotedDailyRate(st) * 100.0, 3)),
                trKey("oremarket.loan.estimate.term", String.valueOf(term), String.valueOf(n)),
                trKey("oremarket.loan.row.mode", trKey(modeBtnKey(loanModeSel))),
        };
    }

    /** The estimate block's true height = sum of (rows per wrapped line x row height) + bottom gap. */
    private int loanEstBlockH(MarketClientState st) {
        String[] texts = loanEstTexts(st);
        int w = Math.max(24, mkW - 8);
        int h = 0;
        for (int i = 0; i < texts.length && i < loanEstWraps.length; i++) {
            h += wrapCached(loanEstWraps[i], texts[i], w).size() * LOAN_LINE_H;
        }
        return h + 5;
    }

    /**
     * The confirmation dialog's content rows (the dialog's measuring and painting share this source). In [k][..] row 0 is the text row;
     * the note and warn rows wrap at the dialog's inner width -- fully readable in any language, never past the frame.
     */
    private List<String>[] loanConfirmLines(MarketClientState st, int innerW) {
        cn.oremarket.market.MarketSettings cs =
                cn.oremarket.market.MarketSettings.client();
        long[] q = loanQuote(st);
        int nSel = Math.max(1, Math.min(loanTermLimit(), Math.max(1, loanInstallmentsSel)));
        int term = cn.oremarket.common.LoanSchedule.totalTermDays(nSel, configuredLoanTermDays());
        String note = trKey("oremarket.loan.confirm.note");
        // When the penalty rate is unconfigured or <= 0 it displays as 5% per day (same source as the server's LoanRules.penaltyRate).
        double penPct = cs == null ? 5.0
                : (cs.loanPenaltyPercent <= 0 ? 5.0
                : (cs.loanPenaltyPercent > 1.0 ? cs.loanPenaltyPercent : cs.loanPenaltyPercent * 100.0));
        String warn = trKey("oremarket.loan.confirm.warn",
                String.valueOf(cs == null ? 0 : cs.loanGracePeriodDays),
                cn.oremarket.common.Num.fixed(penPct, 2),
                String.valueOf(cs == null ? 0 : cs.loanOverdueStrikeLimit));
        String[] flat = {
                trKey("oremarket.loan.confirm.total", L.money(q[2])),
                trKey("oremarket.loan.confirm.rate",
                        cn.oremarket.common.Num.fixed(quotedDailyRate(st) * 100.0, 3)),
                trKey("oremarket.loan.confirm.term", String.valueOf(term),
                        String.valueOf(st.loan.tradeDay + term)),
                // The dialog's repayment mode field must carry the repayment enum, never the product name (the old code wrote credit loan, 1 installment).
                // lump-sum principal-and-interest likewise shows the chosen installment count (1~5, matching the server's scheduling rule).
                trKey("oremarket.loan.confirm.plan", trKey(modeBtnKey(loanModeSel)),
                        String.valueOf(nSel)),
        };
        @SuppressWarnings("unchecked")
        List<String>[] out = new List[6];
        for (int i = 0; i < flat.length; i++) {
            out[i] = List.of(flat[i]);
        }
        out[4] = wrapCached(loanNoteWrap, note, innerW);
        out[5] = wrapCached(loanWarnWrap, warn, innerW);
        return out;
    }

    /**
     * Decide whether a button belongs to the dialog itself (confirm / cancel).
     * Dialog buttons are repainted by the top channel above the scrim and panel; the remaining background widgets stay visible throughout,
     * drawing normally in the widget layer on dead coordinates, dimmed uniformly by the main-channel scrim (dim but still visible).
     */
    private static boolean isModalOwnButton(MarketButton mb) {
        return mb.mark == M_LOAN + 30 || mb.mark == M_LOAN + 31
                || mb.mark == M_CLEAR_CONFIRM || mb.mark == M_CLEAR_CANCEL;
    }

    /**
     * Draw the modal scrim in the top channel after the completed page has been flushed.
     * Keeping it in its own batch makes the dimming apply uniformly to background text, widgets, icons and chart pixels.
     */
    private void drawModalMask(GuiGraphics g) {
        double inv = 1.0D / Math.max(1.0E-6D, uiScale);
        int mw = this.minecraft != null ? this.minecraft.getWindow().getGuiScaledWidth() : gw;
        int mh = this.minecraft != null ? this.minecraft.getWindow().getGuiScaledHeight() : gh;
        int sx1 = (int) Math.floor(-offsetX * inv) - 2;
        int sy1 = (int) Math.floor(-offsetY * inv) - 2;
        int sx2 = (int) Math.ceil((mw - offsetX) * inv) + 2;
        int sy2 = (int) Math.ceil((mh - offsetY) * inv) + 2;
        g.fill(sx1, sy1, sx2, sy2, MODAL_MASK);     // full-screen translucent scrim 0x80000000
    }

    /**
     * Draw the opaque modal panel and its content after the top-channel scrim.
     * Panels are 0xFF2B2B2B fully opaque; dialog text and buttons always ride above the dimmed page.
     */
    private void drawModalLayer(GuiGraphics g, MarketClientState st) {
        g.flush();
        if (liquidateConfirmOpen) {
            drawLiquidateConfirm(g, st);
        } else {
            drawLoanConfirm(g, st);
        }
        g.flush();                                  // dialog panels and text are submitted; the dialog buttons follow, repainted on the top layer
    }

    /**
     * The dialog's own confirm / cancel buttons repaint on the top channel -
     * while the widget layer ran (super.render) modalButtonsDeferred=true had them skip drawing; here the flag is reset and they render normally
     * on real canvas coordinates (hover highlights light up as usual; clicks are still dispatched by the vanilla widgets, hit areas unchanged),
     * and their rings (focus blue frame etc.) follow in the same convention. The buttons always ride above the scrim and panels, below the toasts.
     */
    private void drawModalButtonsOverlay(GuiGraphics g, int mouseX, int mouseY) {
        modalButtonsDeferred = false;               // normal drawing allowed inside the top channel
        for (var r : this.renderables) {
            if (r instanceof MarketButton mb && mb.visible && isModalOwnButton(mb)) {
                mb.render(g, mouseX, mouseY, 0.0F);
            }
        }
        for (var r : this.renderables) {
            if (r instanceof MarketButton mb && mb.visible && isModalOwnButton(mb)) {
                drawRingsFor(g, mb);
            }
        }
    }

    /**
     * Close every modal and immediately recompute the underlying widget hover from the current mouse position (prevents a stuck hovered).
     * The layoutKey contains the dialog state so rebuildWidgets restores the background widgets next frame; what happens here is clearing the frozen state on the spot.
     */
    private void closeAllModals() {
        if (!loanConfirmOpen && !liquidateConfirmOpen) {
            return;
        }
        loanConfirmOpen = false;
        liquidateConfirmOpen = false;
        modalScroll = 0;
        restoreUnderlyingHover();
    }

    /** Retrigger the underlying widgets' hover check using the last frame's mouse position (equivalent to one updateHover). */
    private void restoreUnderlyingHover() {
        updateUiTransform();
        double bx = (lastScreenMouseX - offsetX) / uiScale;
        double by = (lastScreenMouseY - offsetY) / uiScale;
        for (var w : this.children()) {
            if (w instanceof MarketButton mb) {
                mb.syncHover(bx, by);
            }
        }
        try {
            super.mouseMoved(bx, by);               // the remaining self-drawn listeners sync their hover once too
        } catch (RuntimeException ignored) {
            // hover restoration is a nicety: no exception may ever disturb the close path itself
        }
    }

    /** The loan dialog's content overflow height (its inner scroll is enabled only when the panel's visible content area cannot hold all rows). */
    private int loanConfirmOverflow(MarketClientState st) {
        int[] r = loanConfirmRect();
        int w = r[2] - r[0];
        int innerW = w - 16;
        int lines = 0;
        for (List<String> ls : loanConfirmLines(st, innerW)) {
            lines += ls.size();
        }
        int innerH = (r[3] - LOAN_BTN_H - 9) - (r[1] + 6 + LOAN_HEAD_H);
        return Math.max(0, lines * LOAN_LINE_H - innerH);
    }

    /** Borrow-confirmation dialog rect. */
    private int[] loanConfirmRect() {
        int w = Math.min(mkW - 24, 230);
        int innerW = w - 16;
        int lines = 0;
        List<String>[] content = loanConfirmLines(MarketClientState.INSTANCE, innerW);
        for (List<String> ls : content) {
            lines += ls.size();
        }
        int h = 6 + LOAN_HEAD_H + lines * LOAN_LINE_H + LOAN_BTN_H + 12;
        int availTop = mkAbsY() + TAB_H + 4;
        int availBot = Math.min(mkAbsY() + mkH, gy + barY - 4);
        if (h > Math.max(60, availBot - availTop)) {
            h = Math.max(60, availBot - availTop);   // extreme small container: clamp to the usable height; rows fall back on clipping
        }
        int x1 = gx + mkX + (mkW - w) / 2;
        int y1 = availTop + Math.max(0, (availBot - availTop - h) / 2);
        return new int[]{x1, y1, x1 + w, y1 + h};
    }

    /**
     * Borrow-confirmation dialog -
     * the scrim is now fully opaque (0xFF000000) covering the container's content area (behind the fixed button row included);
     * the panel sits on opaque PANEL_ROOT + a 1px border; content rows wrap at the dialog's inner width and note/warn stay fully readable;
     * widgets beneath the dialog are set invisible wholesale in updateButtonStates (vanilla widgets ignore scissor),
     * and clicks are swallowed on identical terms in handleClick / mouseScrolled / dragRegionRect,
     * while the dialog's own confirm / cancel widgets respond as usual.
     */
    private void drawLoanConfirm(GuiGraphics g, MarketClientState st) {
        int[] r = loanConfirmRect();
        int w = r[2] - r[0];
        int innerW = w - 16;
        // Part 1: the full-screen scrim and the g.flush batch ordering are centralised in drawModalLayer;
        // here only the fully opaque panel (0xFF2B2B2B) + border + content draw; individual dialogs no longer add their own scrim.
        g.fill(r[0], r[1], r[2], r[3], MODAL_BG);
        g.renderOutline(r[0], r[1], w, r[3] - r[1], BORDER_MAIN);
        int cx = r[0] + 8;
        int y = r[1] + 6;
        pageText(g, trKey("oremarket.loan.confirm.title"), cx, y, GOLD);
        y += LOAN_HEAD_H;
        List<String>[] content = loanConfirmLines(st, innerW);
        // When content outgrows the panel, the wheel scrolls the dialog body itself within the panel (modalScroll, clamped back at the bounds)
        int overflow = loanConfirmOverflow(st);
        modalScroll = clamp(modalScroll, 0, overflow);
        int bodyTop = y;
        int bodyBottom = r[3] - LOAN_BTN_H - 9;
        try {
            // Content-area scissor (4px inner padding on the panel); rows are only clipped, never overflow (scissor and row rects share one rule)
            scissor(g, r[0] + LIST_PAD, bodyTop, r[2] - LIST_PAD, bodyBottom);
            int[] colors = {GOLD, TEXT_WHITE, TEXT_WHITE, TEXT_WHITE, TEXT_MUTED, TOAST_RED};
            y = bodyTop - modalScroll;
            for (int i = 0; i < content.length; i++) {
                for (String line : content[i]) {
                    if (rowIntersects(y, y + LOAN_LINE_H, bodyTop, bodyBottom)) {
                        drawFitLeft(g, line, cx, y, innerW, colors[i]);
                    }
                    y += LOAN_LINE_H;
                }
            }
        } finally {
            g.disableScissor();
        }
    }

    /**
     * The clear-all secondary confirmation dialog -- the same Modal machinery as the loan dialog:
     * a 0x99000000 scrim covers the whole page, the 0xFF2B2B2B panel is fully opaque;
     * while it is open the layer beneath hides wholesale in updateButtonStates and clicks/wheels are swallowed
     * on identical terms in handleClick / mouseScrolled / dragRegionRect;
     * tab switches, ESC and closing the screen all clear it -- residue is forbidden.
     */
    private void drawLiquidateConfirm(GuiGraphics g, MarketClientState st) {
        int[] r = modalPanelRect();
        int w = r[2] - r[0];
        int innerW = w - 16;
        // Part 1: the scrim and batch ordering live in drawModalLayer; here only the opaque panel and content draw.
        g.fill(r[0], r[1], r[2], r[3], MODAL_BG);
        g.renderOutline(r[0], r[1], w, r[3] - r[1], BORDER_MAIN);
        int cx = r[0] + 8;
        int y = r[1] + 6;
        pageText(g, trKey("oremarket.liquidate.title"), cx, y, GOLD);
        y += LOAN_HEAD_H;
        int holds = 0;
        double value = 0.0;
        int m = Math.min(n(), st.available == null ? 0 : st.available.length);
        for (int i = 0; i < m; i++) {
            if (st.available[i] > 0 && st.unlocked(i) && !st.suspended(i)) {
                holds++;
                value += st.price[i] * st.available[i];
            }
        }
        String body = trKey("oremarket.liquidate.body", String.valueOf(holds), L.money(Math.round(value)));
        String warn = trKey("oremarket.liquidate.warn");
        try {
            scissor(g, r[0] + LIST_PAD, r[1] + LIST_PAD, r[2] - LIST_PAD, r[3] - LOAN_BTN_H - 9);
            for (String line : wrapCached(liquidateBodyWrap, body, innerW)) {
                drawFitLeft(g, line, cx, y, innerW, TEXT_WHITE);
                y += LOAN_LINE_H;
            }
            for (String line : wrapCached(liquidateWarnWrap, warn, innerW)) {
                drawFitLeft(g, line, cx, y, innerW, TOAST_RED);
                y += LOAN_LINE_H;
            }
        } finally {
            g.disableScissor();
        }
    }

    /**
     * Loan page: quota and credit, loan products, repayment mode, installment settings, borrow amount,
     * cost estimate, repayment schedule, and the repayment / prepayment buttons. The whole page is one pixel-scroll region:
     * - enableScissor first (clip rect = the content viewport), disableScissor in finally;
     * - every row judged by rowIntersects(row top, row bottom, viewport top, viewport bottom); half rows still draw;
     * - the page's total height comes from computeLoanLayout by content rows, and the scroll offset is clamped by layoutMarket against the new geometry.
     */
    private void drawLoanPanel(GuiGraphics g, MarketClientState st, int x, int contentTop) {
        if (st.showBankruptBanner()) {
            drawFitCentered(g, trKey("oremarket.text.0461"), x + 4, x + mkW - 4,
                    contentTop + (mkH - TAB_H) / 2 - 4, TOAST_RED);
            return;
        }
        computeLoanLayout(st);
        int[] view = loanViewport();
        int cx = x + 4;
        int innerW = mkW - 8;
        int base = contentTop + LIST_PAD - loanScroll;   // Part 2: drawing origin = viewport top (minus padding)
        int code = loanStateCode(st);
        try {
            scissor(g, view[0], view[1], view[2], view[3]);
            loanHeader(g, base + loanY[LS_CREDIT], cx, innerW, view, trKey("oremarket.loan.section.credit"));
            drawLoanInfo(g, st, code, base, cx, innerW, view);
            if (code == 1) {
                loanHeader(g, base + loanY[LS_PHEAD], cx, innerW, view, trKey("oremarket.loan.section.product"));
                int colW = (innerW - 3 * 2) / 4;
                int ux = cx + loanProductSel * (colW + 2);
                if (rowIntersects(base + loanY[LS_PROD], base + loanY[LS_PROD] + LOAN_BTN_H, view[1], view[3])) {
                    g.fill(ux, base + loanY[LS_PROD] + LOAN_BTN_H - 1, ux + colW, base + loanY[LS_PROD] + LOAN_BTN_H, GOLD);
                }
                loanHeader(g, base + loanY[LS_MHEAD], cx, innerW, view, trKey("oremarket.loan.section.mode"));
                int mx = cx + loanModeSel * (colW + 2);
                if (rowIntersects(base + loanY[LS_MODE], base + loanY[LS_MODE] + LOAN_BTN_H, view[1], view[3])) {
                    g.fill(mx, base + loanY[LS_MODE] + LOAN_BTN_H - 1, mx + colW, base + loanY[LS_MODE] + LOAN_BTN_H, GOLD);
                }
                loanHeader(g, base + loanY[LS_THEAD], cx, innerW, view, trKey("oremarket.loan.section.term"));
                int third = (innerW - 2 * 2) / 3;
                if (rowIntersects(base + loanY[LS_TERM], base + loanY[LS_TERM] + LOAN_BTN_H, view[1], view[3])) {
                    pageCentered(g, trKey("oremarket.loan.row.inst.count",
                            String.valueOf(Math.max(1, loanInstallmentsSel)), String.valueOf(loanTermLimit())),
                            cx + third + 2 + (innerW - 2 * (third + 2)) / 2, base + loanY[LS_TERM] + 4, TEXT_WHITE);
                }
                int nSel = Math.max(1, Math.min(loanTermLimit(), Math.max(1, loanInstallmentsSel)));
                int termDays = cn.oremarket.common.LoanSchedule.totalTermDays(nSel, configuredLoanTermDays());
                loanRow(g, base + loanY[LS_TERMNOTE], cx, innerW, view,
                        "oremarket.loan.row.total_term", TEXT_WHITE, String.valueOf(termDays));
                loanHeader(g, base + loanY[LS_AHEAD], cx, innerW, view, trKey("oremarket.loan.section.amount"));
                loanHeader(g, base + loanY[LS_EHEAD], cx, innerW, view, trKey("oremarket.loan.section.estimate"));
                if (p2pEnabled()) {
                    loanHeader(g, base + loanY[LS_P2PHEAD], cx, innerW, view, trKey("oremarket.p2p.section"));
                    int py2 = base + loanY[LS_P2P];
                    if (st.loan.myListingCoins > 0) {
                        loanRow(g, py2, cx, innerW, view, "oremarket.p2p.row.my", TEXT_WHITE,
                                L.money(st.loan.myListingCoins),
                                cn.oremarket.common.Num.fixed(st.loan.myListingRate * 100.0, 3),
                                String.valueOf(st.loan.myListingDaysLeft));
                    } else {
                        loanRow(g, py2, cx, innerW, view, "oremarket.p2p.row.my.none", TEXT_WHITE,
                                L.money(0));
                    }
                    py2 += LOAN_LINE_H;
                    loanRow(g, py2, cx, innerW, view, "oremarket.p2p.row.market", TEXT_MUTED,
                            String.valueOf(st.loan.openListings),
                            cn.oremarket.common.Num.fixed(st.loan.bestListingRate * 100.0, 3));
                }
                // The cost estimate draws line by line on its true wrapped row count (the same function computeLoanLayout uses to measure,
                // and the same column width), so principal / total repayment / daily rate / term / repayment mode stay fully readable and never leave the frame.
                String[] estTexts = loanEstTexts(st);
                int estW = Math.max(24, innerW);
                int[] estColors = {TEXT_WHITE, GOLD, TEXT_WHITE, TEXT_WHITE, TEXT_MUTED};
                int ey = base + loanY[LS_EST];
                for (int ei = 0; ei < estTexts.length && ei < loanEstWraps.length; ei++) {
                    for (String line : wrapCached(loanEstWraps[ei], estTexts[ei], estW)) {
                        if (rowIntersects(ey, ey + LOAN_LINE_H, view[1], view[3])) {
                            drawFitLeft(g, line, cx, ey, estW, estColors[ei]);
                        }
                        ey += LOAN_LINE_H;
                    }
                }
            } else if (code == 2) {
                loanHeader(g, base + loanY[LS_PLANHEAD], cx, innerW, view, trKey("oremarket.loan.section.plan"));
                int py = base + loanY[LS_PLAN];
                loanText(g, py, cx, innerW, view, trKey("oremarket.loan.plan.header"), TEXT_MUTED);
                py += LOAN_LINE_H;
                java.util.List<cn.oremarket.common.LoanSchedule.Row> rows =
                        st.loanSchedule();
                if (rows.isEmpty()) {
                    loanText(g, py, cx, innerW, view, trKey("oremarket.header.loan.none"), TEXT_MUTED);
                }
                for (cn.oremarket.common.LoanSchedule.Row r : rows) {
                    boolean settled = r.index < st.loan.nextInst;
                    loanText(g, py, cx, innerW, view, trKey("oremarket.loan.plan.row",
                            String.valueOf(r.index), String.valueOf(r.dueDay), L.money(r.principal),
                            L.money(r.interest), L.money(r.total()),
                            trKey(settled ? "oremarket.loan.plan.settled" : "oremarket.loan.plan.unsettled")),
                            settled ? TEXT_MUTED : TEXT_WHITE);
                    py += LOAN_LINE_H;
                }
            } else if (code == 4) {
                int hy = base + loanY[LS_HINT];
                loanRow(g, hy, cx, innerW, view, "oremarket.loan.cooldown.left", TOAST_RED,
                        String.valueOf(st.cooldownDaysLeft()));
                hy += LOAN_LINE_H;
                loanRow(g, hy, cx, innerW, view, "oremarket.loan.emergency.note",
                        TEXT_MUTED, L.money(emergencyLimit(st)));
            } else {
                int hy = base + loanY[LS_HINT];
                loanText(g, hy, cx, innerW, view, st.borrowLevelHint(), FLAT);
            }
        } finally {
            g.disableScissor();
        }
        // The fixed button row's top divider (clearly separating the confirm borrow / cancel row from the scrolled content;
        // at least 4px of row spacing and loanPinnedH reserves room from the event bar, so overlap is impossible).
        int pinH = loanPinnedH(st);
        if (pinH > 0) {
            g.fill(cx - 2, view[3] + 1, view[2] - 4, view[3] + 2, 0xFF2A2A2A);
        }
        // (dialogs-on-top, finalised): loan dialogs now draw via drawModalLayer in renderTopOverlays' top channel.
        // not scrolled to the end: a 1px faint line 4px above the bottom edge (same rule as the log page; vanishes at full scroll or when everything fits)
        if (loanOverflow > 0 && loanScroll < loanOverflow) {
            g.fill(cx - 2, view[3] - 4, view[2] - 4, view[3] - 3, 0xFF2A2A2A);
        }
    }

    /**
     * The third tab (log).: the position-overview block (header stock/position/cost/price/P&L, data rows,
     * dividers, the no-position hint) is deleted outright and no overview height is kept on top; stock and position information
     * is seen only in the quotes tab's right column. The log page keeps a single log block filling the entire content height:
     * top y = content top + 4; bottom y = content bottom - 4; height = bottom - top (a single VerticalLayout element).
     * Each log entry is 12 tall (9 per wrapped line; multi-line entries count rows x 9), newest on top, at most 100 entries;
     * over-wide text wraps (words first, over-long tokens by character, no ellipses) with 2px entry gaps;
     * total content height = sum of each entry's real height + (count - 1) x 2; when it exceeds the area the wheel engages (step 20),
     * the offset clamps to 0 ~ max(content height - log height, 0); wheel events count only inside the log rect.
     * Rendering pairs enableScissor/disableScissor in try/finally, the clip's top and bottom are the log area's own;
     * no scrollbar, badge or arrows are drawn; while not scrolled to the end a 1px faint line 0xFF2A2A2A sits 4px above the log area's bottom
     * (left = area + 6, right = area - 6) and disappears at full scroll or when content fits.
     */
    /** Log page outer scroll offset. */
    private int logPageScroll = 0;

    /** Log page layout: one log block filling the content area (4px padding top and bottom, applied by VerticalLayout). */
    private void layoutLogPage(MarketClientState st) {
        int top = mkAbsY() + TAB_H;
        int bottom = Math.min(mkAbsY() + mkH, gy + barY - 2);
        int h = Math.max(0, bottom - top);
        int avail = Math.max(0, h - 8);
        logTooSmall = avail < LOG_MIN_H;
        logLayout.clear().add(avail);
        logLayout.layout(gx + mkX + 4, top, mkW - 8, h);
        logPageScroll = logLayout.clampScroll(logPageScroll);
        logMaxScroll = scrollMax(logContentHeight, avail);
        logScroll = clamp(logScroll, 0, logMaxScroll);
    }

    /** The log block's current drawing rect. */
    private int[] logListRect() {
        int topAbs = logLayout.y(0) - logPageScroll;
        return new int[]{gx + mkX + 4, topAbs, gx + mkX + mkW - 4, topAbs + logLayout.height(0)};
    }

    private void drawLogPage(GuiGraphics g, MarketClientState st, int x, int contentTop) {
        layoutLogPage(st);
        int top = mkAbsY() + TAB_H + 4;
        int bottom = Math.min(mkAbsY() + mkH, gy + barY - 2) - 4;
        if (logTooSmall || bottom <= top) {
            drawFitCentered(g, cn.oremarket.common.RawText.t("oremarket.text.0190"),
                    x + 4, x + mkW - 4,
                    top + Math.max(2, (bottom - top - 9) / 2), GRAY88);
            return;
        }
        int rowLeft = x + 4;
        int rowRight = x + mkW - 4;
        // ---- single block: the log area (the overview block is deleted, so the first log sits right at the top;
        // auto-wrap + snapshot + cache logic unchanged; no scrollbar, no badge, no arrows) ----
        int ly = logLayout.y(0) - logPageScroll;
        int visH = logLayout.height(0);
        if (visH <= 0) {
            return;
        }
        int W = logTextWidth();
        g.renderOutline(x + 1, ly - 1, mkW - 2, visH + 2, BORDER_MAIN);
        if (logSkipFrames >= 3) {
            rebuildLogSnapshot(st);
        } else if (logSnapshotVersion != st.logVersion) {
            rebuildLogSnapshot(st);
        }
        if (logSnapshot.size() != st.logs.size()) {
            if (logSkipFrames < 3) {
                logSkipFrames++;
                return;
            }
        } else {
            logSkipFrames = 0;
        }
        if (logWrapDirty || logWrapCache.size() != logSnapshot.size()
                || logWrapCachedWidth != W || logWrapCache.isEmpty() && !logSnapshot.isEmpty()) {
            if (!fontReady()) {
                return;
            }
            rebuildLogWrap(W);
        }
        boolean emptyList = logSnapshot.isEmpty();
        logMaxScroll = scrollMax(logContentHeight, Math.max(0, visH - LIST_PAD));
        logScroll = clamp(logScroll, 0, logMaxScroll);
        final int logClipBot = Math.max(ly + 1, ly + visH - LIST_PAD);
        try {
            scissor(g, x + 2, ly, x + mkW - 2, logClipBot);
            if (emptyList) {
                if (visH >= LOG_LINE_H) {
                    pageCentered(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0181")), x + mkW / 2, ly + (visH - 9) / 2, GRAY88);
                }
            } else if (visH >= LOG_LINE_H) {
                int textX = x + 4;
                int cum = 0;
                int entries = logSnapshot.size();
                for (int k = 0; k < entries; k++) {
                    MarketLogEntry e = logSnapshot.get(k);
                    LogWrap wrap = logWrapCache.get(k);
                    int entryTop = ly + cum - logScroll;
                    cum += wrap.height + LOG_ENTRY_GAP;
                    // 100 log entries used to mean 100 prefix strings built and measured every frame, mostly off-screen wasted math.
                    if (!rowIntersects(entryTop, entryTop + wrap.height, ly, logClipBot)) {
                        continue;
                    }
                    LogType type = e.logType();
                    // The prefix and the translated body are both
                    // translated once in the rebuildLogWrap stage and the paint reads only the cache -- the old code fed
                    // the whole Chinese prefix + body string into L.tr every frame, where the prefix blocked the format template's full-sentence match
                    // force fragment replacement, and words like closing/margin leaked through in English.
                    String prefix = wrap.prefix;
                    int pw = wrap.prefixW;
                    // Single entry base height 12, wrapped line height 9: one-line entries centre vertically inside the 12 cell (offset 1); multi-line entries do not offset
                    int pad = (wrap.height - wrap.lines.size() * LOG_LINE_H) / 2;
                    for (int j = 0; j < wrap.lines.size(); j++) {
                        int lineY = entryTop + pad + j * LOG_LINE_H;
                        if (!rowIntersects(lineY, lineY + 9, ly, logClipBot)) {
                            continue;
                        }
                        String line = wrap.lines.get(j);
                        if (j == 0 && pw <= W && line.startsWith(prefix)) {
                            pageText(g, prefix, textX, lineY, type.color);
                            pageText(g, line.substring(prefix.length()), textX + pw, lineY, LOG_TEXT);
                        } else {
                            pageText(g, line, textX, lineY, LOG_TEXT);
                        }
                    }
                    // Part 7 item 4: a 1px divider under each log entry (not the last one)
                    if (k < entries - 1) {
                        g.fill(textX, entryTop + wrap.height, rowRight, entryTop + wrap.height + 1, EX_SEP_COLOR);
                    }
                }
            }
        } finally {
            g.disableScissor();
        }
        // While not scrolled to the end, a 1px faint line 4px above the log area's bottom; gone at full scroll or when content fits.
        if (logContentHeight > visH && logScroll < logMaxScroll) {
            int lineY = ly + visH - 4;
            g.fill(rowLeft + 6, lineY, rowRight - 6, lineY + 1, DARK_BG);
        }
    }

    /** Six: a shallow-copy snapshot of the main log list; rendering reads only the snapshot while network packets write only the main list. */
    private void rebuildLogSnapshot(MarketClientState st) {
        logSnapshot.clear();
        logSnapshot.addAll(st.logs);
        logSnapshotVersion = st.logVersion;
        logWrapDirty = true;
    }

    /**
     * Six: rebuild the wrap cache. Entry by entry, split rows word first (over-long tokens by character) with each row width <= W,
     * no ellipses; row height 9, entry gap 2; after the rebuild logContentHeight applies immediately for clamping.
     * Called only on render frames; touching it from a network thread is forbidden.
     *
     * Translate the prefix and the body separately, then join. The old code
     * fed the whole prefix + Chinese body string into L.tr, and the body's format templates (such as
     * the short-close line with stock, shares, price, P&L and released margin) lost their full-sentence match to the prefix,
     * falling back to fragment replacement, so unregistered words like closing / margin / released margin surfaced raw in English.
     * Translating the body alone hits the whole-sentence template, stock names and other arguments translate recursively inside it,
     * and English logs keep no Chinese residue at all.
     */
    private void rebuildLogWrap(int w) {
        logWrapCache.clear();
        int total = 0;
        int n = logSnapshot.size();
        for (int k = 0; k < n; k++) {
            MarketLogEntry e = logSnapshot.get(k);
            // Prefix and body are read straight from the current language file as translation key + arguments,
            // so no whole Chinese sentence is reverse-looked-up through L.tr anymore -- English logs can no longer retain any Chinese.
            String prefix = L.logPrefix(e.day, e.logType());
            String body = L.logBody(e);
            LogWrap wrap = new LogWrap();
            wrap.prefix = prefix;
            wrap.prefixW = font == null ? 0 : font.width(prefix);
            wrapAllNoEllipsis(prefix + body, w, wrap.lines);
            wrap.height = Math.max(LOG_ROW, wrap.lines.size() * LOG_LINE_H);
            total += wrap.height;
            logWrapCache.add(wrap);
        }
        logContentHeight = n == 0 ? 0 : total + (n - 1) * LOG_ENTRY_GAP;
        logWrapCachedWidth = w;
        logWrapDirty = false;
    }

    /**
     * Pixel width of a single character (in the vanilla font every character width is an integer, so per-character sums equal whole-string measurement).
     * The font is fixed over the screen's lifetime, so the table caches per instance; characters beyond ASCII are measured live (not tabled).
     */
    private final int[] charWidths = new int[128];
    private final boolean[] charWidthKnown = new boolean[128];

    private int charWidth(char c) {
        if (c >= 128) {
            return this.font == null ? 0 : this.font.width(String.valueOf(c));
        }
        if (!this.charWidthKnown[c]) {
            this.charWidths[c] = this.font == null ? 0 : this.font.width(String.valueOf(c));
            this.charWidthKnown[c] = true;
        }
        return this.charWidths[c];
    }

    /** Wrapping by walking font.width per character (single-line overflow and ellipses are both banned): break at words first, break over-long words by character. */
    private void wrapAllNoEllipsis(String text, int w, List<String> out) {
        text = L.tr(text);
        if (text == null || text.isEmpty()) {
            out.add("");
            return;
        }
        if (w <= 0) {
            out.add(text);
            return;
        }
        StringBuilder cur = new StringBuilder();
        for (String token : text.split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            String trial = cur.length() == 0 ? token : cur + " " + token;
            if (font.width(trial) <= w) {
                cur.setLength(0);
                cur.append(trial);
                continue;
            }
            if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (font.width(token) > w) {
                // over-long tokens (prefixes or digit runs): cut them into character chunks; content is never dropped by truncation.
                StringBuilder piece = new StringBuilder();
                int pieceW = 0;
                for (int i = 0; i < token.length(); i++) {
                    char c = token.charAt(i);
                    int cw = charWidth(c);
                    if (piece.length() > 0 && pieceW + cw > w) {
                        out.add(piece.toString());
                        piece.setLength(0);
                        pieceW = 0;
                    }
                    piece.append(c);
                    pieceW += cw;
                }
                if (piece.length() > 0) {
                    cur.append(piece);
                }
            } else {
                cur.append(token);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        if (out.isEmpty()) {
            out.add("");
        }
    }








    // ---------------- event bar (bottom-hugging; rarity-sorted cards / multi-line hover / slide-in fade-out / progress) ----------------

    /** Card size [width, height] per rarity (index = rarity: 0 common / 1 rare / 2 epic / 3 legendary). */
    private static final int[][] CARD_SIZE_BY_RARITY = {{120, 28}, {140, 32}, {160, 36}, {180, 40}};
    /** // slide-in / fade-out animation duration (milliseconds). */
    private static long CARD_ANIM_MS = 200L;
    /** Event uids whose entrance animation already played (shared across screen instances: one card never replays; capacity capped against leaks). */
    private static final java.util.Set<Integer> ANIMATED_UIDS = new java.util.HashSet<>();
    // The legendary cooldown no longer hangs a hint card in the event bar (the hint became a system log line written by the server at trigger moment),
    // and the old HINT_CARD_UID sentinel plus hintCard geometry are removed; the event bar's height derives from real event cards only.

    /** uid -> entrance animation start in milliseconds (read/written on render frames; cleared when the card dies). */
    private final java.util.Map<Integer, Long> cardSlideStart = new java.util.HashMap<>();
    /** Fade-out ghosts of vanished events (uid -> last-frame geometry; removed when the animation ends). */
    private final java.util.Map<Integer, GhostCard> fadingGhosts = new java.util.HashMap<>();

    private static final class GhostCard {
        final MarketEventView view;
        final int x, y, w, h;
        final long startMs;

        GhostCard(MarketEventView v, int x, int y, int w, int h, long startMs) {
            this.view = v;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.startMs = startMs;
        }
    }

    /** Event animation caches cleared at layout time (called when the screen closes, preventing cross-instance buildup). */
    private void clearEventAnimCaches() {
        cardSlideStart.clear();
        fadingGhosts.clear();
        if (ANIMATED_UIDS.size() > 512) {
            ANIMATED_UIDS.clear();   // static sets are capacity-capped: real uids auto-increment on the server and old values never recycle
        }
    }

    /** Rarity descending (legendary leftmost); equal rarities sort stably by uid so nothing jitters per frame. */
    private List<Integer> sortEventOrder(MarketClientState st, int n) {
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (st.eventAt(i) != null) {
                order.add(i);
            }
        }
        order.sort((a, b) -> {
            int ra = st.eventAt(a).rarityOrdinal();
            int rb = st.eventAt(b).rarityOrdinal();
            if (ra != rb) {
                return Integer.compare(rb, ra);   // higher rarity ranks first
            }
            return Integer.compare(st.eventAt(a).uid, st.eventAt(b).uid);
        });
        return order;
    }

    private void layoutEventBar(MarketClientState st, int panelW) {
        int n = st.eventCount();
        // fade-out ghost registration: cards drawn last frame and gone this frame (including pool-evicted ones)
        java.util.Map<Integer, CardGeom> prevByUid = new java.util.HashMap<>();
        for (CardGeom c : cards) {
            prevByUid.put(c.uid, c);
        }
        long nowMs = System.currentTimeMillis();
        cards.clear();
        int usable = panelW - 2 * CARD_PAD;
        int x = CARD_PAD;
        int y = CARD_PAD;
        int rowMaxH = 0;
        List<Integer> order = sortEventOrder(st, n);
        for (int idx : order) {
            MarketEventView v = st.eventAt(idx);
            int[] sz = CARD_SIZE_BY_RARITY[Math.max(0, Math.min(3, v.rarityOrdinal()))];
            int w = Math.min(sz[0], Math.max(40, usable));
            int h = sz[1];
            if (x > CARD_PAD && x + w > usable) {   // wraps automatically when a line does not fit
                y += rowMaxH + CARD_VGAP;
                x = CARD_PAD;
                rowMaxH = 0;
            }
            CardGeom cg = new CardGeom();
            cg.index = idx;
            cg.uid = v.uid;
            cg.x = x;
            cg.y = y;
            cg.w = w;
            cg.h = h;
            cards.add(cg);
            x += w + CARD_GAP;
            rowMaxH = Math.max(rowMaxH, h);
        }
        // The legendary cooldown hint card is deleted -- the event bar neither lays it out nor spends height on it;
        // cooldown info is written to the log tab by the server (system type) at the legendary trigger moment.
        // ghost registration (keeps the earliest start time; a card already fading is not registered twice)
        for (java.util.Map.Entry<Integer, CardGeom> e : prevByUid.entrySet()) {
            if (fadingGhosts.containsKey(e.getKey())) {
                continue;
            }
            boolean still = false;
            for (CardGeom c : cards) {
                if (c.uid == e.getKey()) {
                    still = true;
                    break;
                }
            }
            if (!still) {
                MarketEventView v = viewOfUid(st, e.getKey());
                CardGeom pc = e.getValue();
                if (v != null) {
                    fadingGhosts.put(e.getKey(), new GhostCard(v, pc.x, pc.y, pc.w, pc.h, nowMs));
                }
            }
        }
        // entrance animation start registration
        for (CardGeom c : cards) {
            if (!ANIMATED_UIDS.contains(c.uid) && !cardSlideStart.containsKey(c.uid)) {
                cardSlideStart.put(c.uid, nowMs);
                if (ANIMATED_UIDS.size() > 512) {
                    ANIMATED_UIDS.clear();   // set full = clear wholesale: otherwise new cards could never enter and would replay the entrance animation every frame
                }
                ANIMATED_UIDS.add(c.uid);
            }
        }
        // Container total height = upper row padding(2) + card height + lower row padding(4).
        // The old code left only CARD_PAD=2 below while the bottom clip inset by LIST_PAD=4 -- the final 2px sliced off
        // the event card's bottom border together with its progress bar (the user's screenshot). Now the bottom padding and the
        // clip inset agree (both LIST_PAD=4): the last card row is fully visible with a real 4px from the container bottom.
        barContentH = Math.max(EMPTY_BAR_H, y + rowMaxH + LIST_PAD);
        barH = Math.min(BAR_MAX, barContentH);
        if (eventScroll > eventMaxScroll()) {
            eventScroll = eventMaxScroll();                  // clamp the scroll back when cards disappear
        }
    }

    private MarketEventView viewOfUid(MarketClientState st, int uid) {
        for (int i = 0; i < st.eventCount(); i++) {
            MarketEventView v = st.eventAt(i);
            if (v != null && v.uid == uid) {
                return v;
            }
        }
        return null;
    }

    private void drawEventBar(GuiGraphics g, MarketClientState st, int mouseX, int mouseY) {
        int by = gy + barY;
        // Part 7 item 1: the event bar plate is opaque (middle layer 0xFF2B2B2B + 1px border 0xFF555555)
        g.fill(gx + 2, by, gx + gw - 2, by + barH, PANEL_SOFT);
        g.renderOutline(gx + 2, by, gw - 4, barH, BORDER_MAIN);
        long nowMs = System.currentTimeMillis();
        if (st.eventCount() == 0 && fadingGhosts.isEmpty()) {
            drawFitLeft(g, cn.oremarket.common.RawText.t("oremarket.text.0534"),
                    gx + 6, by + 3, gw - 10, FLAT);
            return;
        }
        // while the config snapshot is not ready the default applies (animations default on); no naked null dereference
        cn.oremarket.market.MarketSettings animCfg =
                cn.oremarket.market.MarketSettings.client();
        boolean animOn = animCfg == null || animCfg.eventCardAnimationEnabled;
        try {
            final int barClipBot = Math.max(by + 1, by + barH - LIST_PAD);
            scissor(g, gx + 2, by, gx + gw - 2, barClipBot);
            for (CardGeom c : cards) {
                MarketEventView v = st.eventAt(c.index);
                if (v == null) {
                    continue;
                }
                int slide = 0;
                if (animOn) {
                    Long start = cardSlideStart.get(c.uid);
                    if (start != null) {
                        float p = (nowMs - start) / (float) CARD_ANIM_MS;
                        if (p >= 1f) {
                            cardSlideStart.remove(c.uid);
                        } else {
                            slide = (int) ((1f - Math.max(0f, p)) * (c.w + 6));    // Part 7 item 6: slide in from the right over 200ms
                        }
                    }
                }
                int cx = gx + c.x + slide;
                int cy = by + c.y - eventScroll;
                // Part 2: pixel-level intersection test (the old rule was whole-row-only), surplus pixels are scissored away
                if (!rowIntersects(cy, cy + c.h, by, barClipBot)) {
                    continue;
                }
                drawEventCard(g, st, v, c, cx, cy, mouseX, mouseY, 1f);
            }
            //: the legendary cooldown hint card's rendering is removed (moved to the log tab), so no hint card paints here anymore.
            // fade-out ghost: alpha 1 -> 0 over 200ms, removed when done (never lingers)
            fadingGhosts.entrySet().removeIf(e -> {
                GhostCard gc = e.getValue();
                float p = (nowMs - gc.startMs) / (float) CARD_ANIM_MS;
                if (p >= 1f) {
                    return true;
                }
                if (!animOn) {
                    return true;   // animations switched off in config: ghosts are cleared outright
                }
                int cx = gx + gc.x;
                int cy = by + gc.y - eventScroll;
                if (rowIntersects(cy, cy + gc.h, by, barClipBot)) {
                    CardGeom c = new CardGeom();
                    c.uid = gc.view.uid;
                    c.index = -1;
                    c.w = gc.w;
                    c.h = gc.h;
                    drawEventCard(g, st, gc.view, c, cx, cy, -1, -1, 1f - p);
                }
                return false;
            });
            if (barContentH > barH) {
                int trackH = Math.max(6, barH * barH / barContentH);
                int max = Math.max(1, barContentH - barH);
                int ty = by + 1 + (barH - trackH - 2) * eventScroll / max;
                g.fill(gx + gw - 4, Math.max(by + 1, ty), gx + gw - 3,
                        Math.min(by + barH - 1, Math.max(by + 1, ty) + trackH), DIM_TX);
            }
        } finally {
            g.disableScissor();
        }
    }

    /** Alpha blending (slide-in / fade-out: multiplies alpha only, hue untouched). */
    private static int mixAlpha(int argb, float a) {
        int alpha = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private void drawEventCard(GuiGraphics g, MarketClientState st, MarketEventView v, CardGeom c,
                               int cx, int cy, int mouseX, int mouseY, float alpha) {
        // event card background 0xFF2B2B2B fully opaque (except during fade-out)
        g.fill(cx, cy, cx + c.w, cy + c.h, mixAlpha(PANEL_SOFT, alpha));
        int frame = mixAlpha(v.borderColor(), alpha);
        g.renderOutline(cx, cy, c.w, c.h, frame);
        int innerW = c.w - 6;
        // row one: event name; row two: remaining time (same remainingMarketTicks source and conversion as the hover tooltip).
        //: event names never truncate -- the 0.65 ladder plus the floor step; ellipses banned
        String cardName = L.eventPhrase(v.nameKey, v.name, v.targetCodes);
        double nameScale = cellFitScale(cardName, innerW);
        drawScaled(g, cardName, cx + 3, cy + 3, nameScale, mixAlpha(v.preview ? DIM_TX : v.borderColor(), alpha));
        if (c.h >= 24 && v.duration > 0) {
            String rem = TimeFormatter.formatRemainingTime(Math.min(v.ticksLeft, v.duration),
                    TimeFormatter.interval());
            pageText(g, rem, cx + 3, cy + 14, mixAlpha(FLAT, alpha));
        }
        // Remaining-duration progress bar (Requirement 2 item 12): width cardW-8, height 2, 4px above the bottom; bullish red / bearish green / mixed grey
        if (c.h >= 20 && v.duration > 0) {
            float p = Math.max(0f, Math.min(1f, v.ticksLeft / (float) v.duration));
            int bx = cx + 4;
            int bw = Math.max(0, c.w - 8);
            g.fill(bx, cy + c.h - 4, bx + bw, cy + c.h - 2, mixAlpha(0x33000000, alpha));
            g.fill(bx, cy + c.h - 4, bx + (int) (bw * p), cy + c.h - 2, mixAlpha(v.toneColor(), alpha));
        }
        if (mouseX >= cx && mouseX <= cx + c.w && mouseY >= cy && mouseY <= cy + c.h && alpha > 0.95f) {
            if (eventTipView == null || eventTipView.uid != v.uid) {
                eventTipScroll = 0;
                eventTipSignature = "";
            }
            eventTipView = v;
            eventTipOnCard = true;
        }
    }

    /** The event card tooltip's unwrapped paragraphs; each keeps its colour and is later split by the translation's width. */
    private List<EventTipLine> eventTooltipSegments(MarketEventView v) {
        List<EventTipLine> out = new ArrayList<>();
        String titleSep = L.parenOpen();
        String titleEnd = L.parenClose();
        out.add(new EventTipLine(
                L.eventPhrase(v.nameKey, v.name, v.targetCodes)
                        + titleSep + trKey("oremarket.event.rarity." + rarityKey(v.rarityOrdinal())) + titleEnd,
                v.preview ? DIM_TX : v.borderColor()));
        out.add(new EventTipLine(L.eventPhrase(v.descKey, v.description, v.targetCodes), BODY_TEXT));
        out.add(new EventTipLine("", TIP_TEXT));
        out.add(new EventTipLine(trKey("oremarket.event.tooltip.affected",
                L.eventTargets(v.targetCodes, v.targets)), TEXT_MUTED));
        boolean any = false;
        for (MarketEventView.ModuleView m : v.modules) {
            String line = moduleTooltipLine(m);
            if (line != null) {
                any = true;
                out.add(new EventTipLine(line, TIP_TEXT));
            }
        }
        if (!any) {
            out.add(new EventTipLine(trKey("oremarket.event.tooltip.no_effect"), TIP_TEXT));
        }
        out.add(new EventTipLine("", TEXT_MUTED));
        long remainingSecs = TimeFormatter.remainingSeconds(Math.min(v.ticksLeft, v.duration),
                TimeFormatter.interval());
        long totalMins = TimeFormatter.totalMinutes(v.duration, TimeFormatter.interval());
        out.add(new EventTipLine(trKey("oremarket.event.tooltip.remaining",
                String.valueOf(remainingSecs), String.valueOf(totalMins)), TEXT_MUTED));
        if (!v.contributors.isEmpty()) {
            out.add(new EventTipLine(trKey("oremarket.event.contributors", v.contributors), GOLD));
        }
        return out;
    }

    private String eventTooltipSignature(MarketEventView v) {
        StringBuilder b = new StringBuilder();
        b.append(v.uid).append('|').append(v.nameKey).append('|').append(v.descKey)
                .append('|').append(v.name).append('|').append(v.description)
                .append('|').append(v.targets).append('|').append(v.ticksLeft).append('|').append(v.duration)
                .append('|').append(v.contributors);
        for (MarketEventView.ModuleView m : v.modules) {
            b.append('|').append(m.type).append(':').append(m.direction).append(':').append(m.param)
                    .append(':').append(m.duration);
        }
        return b.toString();
    }

    /** // Item 8: cache the event card tooltip wraps by translation width; no per-frame re-splitting. */
    private void ensureEventTooltipLines(MarketEventView v, int maxW) {
        int wrapW = Math.max(1, maxW - 12);
        String signature = eventTooltipSignature(v);
        if (eventTipWrapWidth == wrapW && signature.equals(eventTipSignature) && !eventTipLines.isEmpty()) {
            return;
        }
        List<EventTipLine> wrapped = new ArrayList<>();
        for (EventTipLine segment : eventTooltipSegments(v)) {
            List<String> lines = new ArrayList<>();
            ToastNotification.wrap(font, segment.text, wrapW, lines);
            for (String line : lines) {
                wrapped.add(new EventTipLine(line, segment.color));
            }
        }
        if (wrapped.isEmpty()) {
            wrapped.add(new EventTipLine("", TIP_TEXT));
        }
        eventTipSignature = signature;
        eventTipWrapWidth = wrapW;
        eventTipLines = List.copyOf(wrapped);
        eventTipContentHeight = eventTipLines.size() * 9;
    }

    private boolean eventTooltipContains(double x, double y) {
        return eventTipPanelW > 0 && x >= eventTipPanelX && x < eventTipPanelX + eventTipPanelW
                && y >= eventTipPanelY && y < eventTipPanelY + eventTipPanelH;
    }

    private void clearEventTooltip() {
        eventTipView = null;
        eventTipScroll = 0;
        eventTipPanelW = 0;
        eventTipPanelH = 0;
        eventTipSignature = "";
        eventTipWrapWidth = -1;
        eventTipLines = List.of();
    }

    /** Item 8: draw the custom tooltip panel, flipping / clamping its position in current GUI coordinates. */
    private void drawEventTooltipPanel(GuiGraphics g, int mouseX, int mouseY) {
        if (eventTipView == null) {
            return;
        }
        if (!eventTipOnCard && !eventTooltipContains(mouseX, mouseY)) {
            clearEventTooltip();
            return;
        }
        // the panel bound converts the on-screen visible area back into the baseline canvas: screen GUI coords divided by uiScale,
        // the same coordinate space as the whole layout; native pixel hardcoding is banned. Position is computed against the canvas (screen-centred):
        // beyond the canvas lies the letterbox band, and when the mouse hugs the canvas edge the panel may cross into that band,
        // but it may never leave the screen's visible area in any circumstance.
        double inv = 1.0 / Math.max(1.0E-4, uiScale);
        int viewW = (int) Math.floor(screenWidth * inv);
        int viewH = (int) Math.floor(screenHeight * inv);
        int maxW = Math.max(80, viewW - 40);
        ensureEventTooltipLines(eventTipView, maxW);
        int maxH = Math.max(28, viewH - 40);
        int width = Math.max(80, Math.min(maxW, eventTipLines.stream()
                .mapToInt(line -> font.width(line.text)).max().orElse(0) + 12));
        int naturalH = eventTipContentHeight + 12;
        int height = Math.min(maxH, naturalH);
        int innerH = Math.max(1, height - 12);
        int maxScroll = Math.max(0, eventTipContentHeight - innerH);
        eventTipScroll = clamp(eventTipScroll, 0, maxScroll);

        // Letterbox offset converted to baseline coordinates: screen top-left = (-offsetX x inv, -offsetY x inv)
        int screenLeftBase = (int) Math.floor(-offsetX * inv);
        int screenTopBase = (int) Math.floor(-offsetY * inv);
        int screenRightBase = screenLeftBase + viewW;
        int screenBottomBase = screenTopBase + viewH;

        int x = mouseX + 8;
        if (x + width > screenRightBase - 8) {
            x = mouseX - 8 - width;
        }
        x = Math.max(screenLeftBase + 8, Math.min(screenRightBase - width - 8, x));
        int y = mouseY + 8;
        if (y + height > screenBottomBase - 8) {
            y = mouseY - 8 - height;
        }
        y = Math.max(screenTopBase + 8, Math.min(screenBottomBase - height - 8, y));
        // Avoiding the error toast: the toast always sits on top at a fixed spot (root bottom-centre), while the detail panel
        // follows the mouse and habitually opens near the event bar, so the two easily intersect. When they do, the panel gives way wholesale --
        // it prefers moving above the toast with a 4px gap, else below with 4px; only if neither fits does it stay put
        // (the toast paints on the topmost layer then, so its text is never covered). The toast rect comes from last frame's draw;
        // a fresh toast may still intersect for one frame -- an imperceptible lag, deliberately not pre-laid-out.
        double[] toastRect = errorToast.dragRect();
        if (toastRect != null
                && x < toastRect[2] && x + width > toastRect[0]
                && y < toastRect[3] && y + height > toastRect[1]) {
            int above = (int) toastRect[1] - 4 - height;
            int below = (int) toastRect[3] + 4;
            if (above >= screenTopBase + 8) {
                y = above;
            } else if (below + height <= screenBottomBase - 8) {
                y = below;
            }
        }
        eventTipPanelX = x;
        eventTipPanelY = y;
        eventTipPanelW = width;
        eventTipPanelH = height;

        //: the panel background is a near-black solid 0xFF0E0E0E, clearly darker than every content panel
        // (root 1A1A1A, event bar 2B2B2B -- matching shades is what made it look hollow), plus a 1px pure-black rim;
        // every pixel is alpha 0xFF; nothing is translucent.
        g.renderOutline(x - 1, y - 1, width + 2, height + 2, 0xFF000000);
        g.fill(x, y, x + width, y + height, TOAST_BG);
        g.renderOutline(x, y, width, height, DIM_TX);
        if (width > 6 && height > 6) {
            g.renderOutline(x + 1, y + 1, width - 2, height - 2, HIGHLIGHT_IN);
        }
        // Without the flush below, the batched outline draw is issued only after the clip opens: the border ring then gets clipped
        // into the text strip and the panel reads as hollow. Force a full batch submit before enabling the clip; the background
        // always paints on the complete rect.
        g.flush();
        try {
            if (maxScroll > 0) {
                // the clip rect must be converted to screen GUI coordinates (the scissor helper centralises it) or the clip lands askew after scaling
                scissor(g, x + 1, y + 1, x + width - 1, y + height - 1);
            }
            int lineY = y + 6 - eventTipScroll;
            for (EventTipLine line : eventTipLines) {
                if (lineY + 9 > y && lineY < y + height) {
                    pageText(g, line.text, x + 6, lineY, line.color | 0xFF000000);
                }
                lineY += 9;
            }
        } finally {
            if (maxScroll > 0) {
                g.disableScissor();
            }
        }
        if (maxScroll > 0 && eventTipScroll < maxScroll) {
            g.fill(x + 2, y + height - 4, x + width - 2, y + height - 3, DARK_BG);
        }
    }

    /**
     * The old single-Component-concatenation tooltip is deleted: event card details go through the self-drawn panel uniformly (item 8),
     * with segmented text supplied by eventTooltipSegments(...); vanilla renderTooltip is no longer used for event cards.
     */

    private static String rarityKey(int ordinal) {
        return switch (Math.max(0, Math.min(3, ordinal))) {
            case 1 -> "rare";
            case 2 -> "epic";
            case 3 -> "legend";
            default -> "common";
        };
    }

    /**
     * One effect module -> one hover line; modules without a display convention return null.
     * Fixed-decimal values always go through Num.fixed (no Formatter): hover rebuilds these lines every frame,
     * and String.format allocates a fresh Formatter per call -- a per-frame allocation the rules ban.
     */
    private String moduleTooltipLine(MarketEventView.ModuleView m) {
        return switch (m.type) {
            case 0 -> {
                String percent = priceShockPercentText(m);
                yield percent.isEmpty()
                        ? trKey("oremarket.event.tooltip.price_mult_overflow")
                        : trKey("oremarket.event.tooltip.price_mult", percent);
            }
            case 1 -> trKey("oremarket.event.tooltip.volatility", Num.fixed(m.param, 2));
            case 2 -> trKey(m.direction >= 0
                    ? "oremarket.event.tooltip.trend_up" : "oremarket.event.tooltip.trend_down");
            case 3 -> trKey("oremarket.event.tooltip.mean_reversion", Num.fixed(m.param, 2));
            case 4 -> trKey("oremarket.event.tooltip.fee", Num.fixed(m.param, 2));
            case 5 -> trKey("oremarket.event.tooltip.trade_impact", Num.fixed(m.param, 2));
            case 6 -> trKey("oremarket.event.tooltip.price_limit", String.valueOf(Math.round(m.param * 100)));
            case 7 -> trKey("oremarket.event.tooltip.halt");
            case 8 -> trKey("oremarket.event.tooltip.force_buy", String.valueOf(Math.round(m.param)));
            case 9 -> trKey("oremarket.event.tooltip.force_sell", String.valueOf(Math.round(m.param)));
            case 10 -> trKey("oremarket.event.tooltip.liquidity_dry",
                    String.valueOf(TimeFormatter.remainingSeconds(m.duration, TimeFormatter.interval())));
            case 11 -> trKey("oremarket.event.tooltip.dividend",
                    Num.fixed(realizedOrNominal(m, m.param), 2));
            case 12 -> trKey("oremarket.event.tooltip.split",
                    String.valueOf((int) Math.round(realizedOrNominal(m, m.param))));
            default -> null;
        };
    }

    /**
     * Price-shock tooltip value: use the realized fraction when the server sent one; otherwise
     * compound the nominal per-tick multiplier over the module duration. An empty result means
     * the percentage is too large to display and the overflow translation must be used.
     */
    private static String priceShockPercentText(MarketEventView.ModuleView module) {
        if (Double.isFinite(module.realizedValue)) {
            return PriceShockMath.percentText(module.realizedValue);
        }
        return PriceShockMath.cumulativePercentText(module.param, Math.max(1, module.duration));
    }

    /** A skipped one-shot effect carries realized 0 and must keep its nominal card value. */
    private static double realizedOrNominal(MarketEventView.ModuleView module, double nominal) {
        if (!Double.isFinite(module.realizedValue) || module.realizedValue == 0.0) {
            return nominal;
        }
        return module.realizedValue;
    }

    private static net.minecraft.network.chat.MutableComponent colored(String text, int rgb) {
        return Component.literal(text == null ? "" : text)
                .withStyle(s -> s.withColor(net.minecraft.network.chat.TextColor.fromRgb(rgb)));
    }

    private static final class CardGeom {
        int index, uid, x, y, w, h;
    }

    /**
     * The exchange's right-column self-drawn action buttons (search / deposit / withdraw).
     * Each holds its drawing rect and click rect -- written once from the same coordinates, so the hit area always equals the visual rect,
     * and hover and click tests share that single source; enabled state and disable reason are computed in the layout phase.
     * Seven: the 200ms debounce window is keyed per kind in exDebounceAt and back-filled while rows rebuild each frame.
     */
    private static final class ExOpButton {
        // Part 4: the search button left with the search box (kind 0 keeps its placeholder; the debounce table no longer uses it).
        static final int DEPOSIT = 1;
        static final int WITHDRAW = 2;
        static final int DEPOSIT_ALL = 3;
        static final int WITHDRAW_ALL = 4;

        final int kind;
        int drawX, drawY, drawW, drawH;
        boolean enabled = true;
        String disabledReason;
        long pressUntil;
        long disabledAtMs;

        ExOpButton(int kind, int x, int y, int w, int h) {
            this.kind = kind;
            this.drawX = x;
            this.drawY = y;
            this.drawW = w;
            this.drawH = h;
        }

        boolean inDebounce() {
            return disabledAtMs > 0 && System.currentTimeMillis() - disabledAtMs < CLICK_DEBOUNCE_MS;
        }

        boolean contains(double mx, double my) {
            return mx >= drawX && mx < drawX + drawW && my >= drawY && my < drawY + drawH;
        }

        /** // standard / short-label translation keys (btnLabel's two adaptive tiers; truncation banned). */
        String stdKey() {
            return switch (kind) {
                case WITHDRAW -> "oremarket.button.exchange_withdraw";
                case DEPOSIT_ALL -> "oremarket.button.exchange_deposit_all";
                case WITHDRAW_ALL -> "oremarket.button.exchange_withdraw_all";
                default -> "oremarket.button.exchange_deposit";
            };
        }
    }

    /**
     * One row of the exchange's left column (fixed single-row height 20): ore icon, name, inventory count, exchange rate.
     * All column positions and the row rect are computed once in the layout phase; rendering and click tests read that same source;
     * rows embed no buttons (deposit / withdraw moved to the right action column); clicking a row = selecting that ore.
     */
    private static final class ExchangeRow {
        int index;
        int top, height, left, right;
        int iconX, nameX, nameW, countRight, rateRight;
        /** // exchange-rate display text (pure display, no hit area). */
        String rateText = "";
        /** // row data snapshot (rendering reads it directly, never indexing the registry again). */
        String stockName = "";
        String stockCode = "";
    }

    // ---------------- K-line chart (top info row / main plot / X axis; technical indicators deleted) ----------------

    /**
     * The K-line container splits top to bottom into three regions:
     * - the top info row is 14 tall: at its left, stock name, latest price, change amount and change percentage (red up / green down);
     * when space runs short, hide the change amount first and the change percentage second; name and latest price are never hidden;
     * - the main plot: candles only (moving averages / volume / RSI are deleted), height = container height - info row 14 - X axis 12;
     * - the bottom X-axis band is 12 tall, kept as margin.
     * The four Y-axis ticks, the horizontal/vertical grid and the price mapping logic are fully preserved.
     */
    private void drawChart(GuiGraphics g, MarketClientState st, int x0, int y0, int w, int h,
                           int mouseX, int mouseY) {
        if (w < 40 || h < 24) {
            return;
        }
        // chart inner background 0xFF151515 with a 1px 0xFF555555 outer frame (opaque).
        g.fill(x0, y0, x0 + w, y0 + h, CHART_BG);
        g.renderOutline(x0, y0, w, h, BORDER_MAIN);
        // top info row (height 14): the indicator icons are deleted; only the info's truncation bound remains on the right.
        int mainH = h - CHART_INFO_H - K_XAXIS_H;
        int mainTop = y0 + CHART_INFO_H;
        int mainBottom = mainTop + mainH;
        if (mainH < 8) {
            pageCentered(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0287")), x0 + w / 2,
                    y0 + CHART_INFO_H + Math.max(0, (h - CHART_INFO_H - 9) / 2), GRAY88);
            return;
        }
        if (selected < 0) {
            pageCentered(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0257")), x0 + w / 2, mainTop + (mainH - 9) / 2, PNL_FLAT);
            return;
        }
        int si = selected;
        if (!st.unlocked(si)) {
            pageCentered(g,L.tr(st.unlockHint(si)), x0 + w / 2, mainTop + (mainH - 9) / 2, LOCKED_TX);
            return;
        }
        if (!st.quoteAt(si)) {
            pageCentered(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0394")), x0 + w / 2, mainTop + (mainH - 9) / 2, K_BODY_FLAT);
            return;
        }
        double cur = st.price[si];
        double chgAmt = st.changeAmount(si);
        double chg = st.changePercent(si);
        int chgColor = chg > 0.001 ? PNL_UP : chg < -0.001 ? PNL_DOWN : PNL_FLAT;
        // top info row's left side: name + latest price + change amount + change percentage.
        // (Part 1): no segment may hide -- when it does not fit, the whole row runs the uniform scaling ladder.
        int infoY = y0 + (CHART_INFO_H - 9) / 2;
        double marketSum = 0.0;
        int marketCount = 0;
        for (int i = 0; i < st.viewSize(); i++) {
            if (!st.quoteAt(i)) continue;
            double v = st.changePercent(i);
            if (Double.isFinite(v)) {
                marketSum += v;
                marketCount++;
            }
        }
        String marketText = marketCount <= 0 ? null
                : trKey("oremarket.market.index.value", Num.pct(marketSum / marketCount, 2));
        int marketW = marketText == null ? 0 : font.width(marketText);
        int marketRightEdge = x0 + w - 14;
        boolean drawMarket = marketW > 0 && chartModeRectValid && chartModeRect[2] < marketRightEdge;
        int infoLimit = chartModeRectValid
                ? chartModeRect[0] - 2
                : marketRightEdge - (marketW > 0 ? marketW + 8 : 0);
        String nT = L.tr(st.stocks[si].displayName());
        String pT = L.tr(Num.fixed(cur, 2));
        String aT = L.tr(Num.signed(chgAmt, 2));
        String cT = "(" + Num.pct(chg, 2) + ")";
        int infoGap = 4;
        int infoBase = font.width(nT) + font.width(pT) + font.width(aT) + font.width(cT) + 3 * infoGap;
        double infoS = fitScaleW(infoBase, Math.max(24, infoLimit - (x0 + K_PAD_X)));
        int hx = x0 + K_PAD_X;
        hx += drawScaled(g, nT, hx, infoY, infoS, TIP_TEXT) + infoGap;
        hx += drawScaled(g, pT, hx, infoY, infoS, TEXT_WHITE) + infoGap;
        hx += drawScaled(g, aT, hx, infoY, infoS, chgColor) + infoGap;
        drawScaled(g, cT, hx, infoY, infoS, chgColor);
        if (drawMarket) {
            double avg = marketSum / Math.max(1, marketCount);
            int marketColor = avg > 0.001 ? PNL_UP : avg < -0.001 ? PNL_DOWN : PNL_FLAT;
            drawFitRight(g, marketText, marketRightEdge, infoY, marketW, marketColor);
        }
        int plotLeft = x0 + K_PAD_X + K_Y_AXIS_W;
        int plotRight = x0 + w - K_PAD_X;
        int plotW = plotRight - plotLeft;
        boolean volumeVisible = mainH >= K_VOLUME_H + K_VOLUME_MIN_PRICE_H + 1;
        int volumeTop = volumeVisible ? mainBottom - K_VOLUME_H : mainBottom;
        int separatorY = volumeVisible ? volumeTop - 1 : mainBottom;
        int topY = mainTop;
        int bottomY = volumeVisible ? separatorY : mainBottom;
        int height = bottomY - topY;
        if (plotW < 8 || height < 8) {
            // precision unification: the fallback text that does not fit the K-line area keeps two decimals too (same rule as title/hover/list)
            String txt = Num.fixed(cur, 2) + "  " + Num.pct(chg, 1);
            drawFitCentered(g, txt, x0 + 4, x0 + w - 4, topY + (height - 9) / 2, chgColor);
            return;
        }
        double[][] done = dailyChart ? st.dailyKlines[si] : st.klines[si];
        int doneCount = Math.min(done == null ? 0 : done.length, StockState.KLINE_LEN);
        int total = doneCount;
        if (total == 0) {
            pageCentered(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0394")), x0 + w / 2, topY + (height - 9) / 2, K_BODY_FLAT);
            return;
        }
        // ---- (K-line maths, finalised): visible candle count and slot width
        // The newest candle always hugs the plot's right edge and history extends left; slot width = plot width / visible count,
        // capped at K_SLOT_MAX_PITCH: with few candles they cluster right and bodies top out at 20px, never stretching infinitely,
        // while many candles (up to the retained 80-bar cap) compress slots and bodies together; bodies floor at 1px and wicks stay exactly 1px.
        int configuredVisible = MarketSettings.client() == null
                ? cn.oremarket.config.OreMarketConfig.DEFAULT_KLINE_DISPLAY_COUNT
                : MarketSettings.client().klineDisplayCount;
        int slots = Math.max(1, Math.min(K_VISIBLE_MAX, configuredVisible));
        while (slots > 1 && plotW * 0.6 / slots < K_BODY_MIN) {
            slots--;
        }
        int showN = Math.min(total, slots);
        if (showN <= 0) {
            pageCentered(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0394")), x0 + w / 2, topY + (height - 9) / 2, K_BODY_FLAT);
            return;
        }
        int start = total - showN;
        double slotW = Math.min(plotW / (double) showN, K_SLOT_MAX_PITCH);
        // ---- Y axis: hardcoding a range is strictly banned -- scan every candle visible on screen (including the still-forming last one
        // and the current price); Y_max = visible high x 1.05, Y_min = visible low x 0.95;
        // when high equals low the same formula naturally grants a +/-5% price buffer, so the picture never squashes flat. ----
        double maxHigh = -Double.MAX_VALUE;
        double minLow = Double.MAX_VALUE;
        boolean any = false;
        for (int j = 0; j < showN; j++) {
            double[] bar = klineBarAt(st, si, done, doneCount, start + j);
            if (bar == null || bar.length < 4) {
                continue;
            }
            double hi = bar[1];
            double lo = bar[2];
            if (!Double.isFinite(hi) || !Double.isFinite(lo)) {
                continue;
            }
            any = true;
            if (hi > maxHigh) {
                maxHigh = hi;
            }
            if (lo < minLow) {
                minLow = lo;
            }
        }
        if (Double.isFinite(cur)) {
            any = true;
            if (cur > maxHigh) {
                maxHigh = cur;
            }
            if (cur < minLow) {
                minLow = cur;
            }
        }
        if (!any || maxHigh < minLow) {
            // Defense: with all data points empty / corrupt, paint a placeholder hint and never run the (price-yMin)/(yMax-yMin) division
            pageCentered(g,L.tr(cn.oremarket.common.RawText.t("oremarket.text.0394")), x0 + w / 2, topY + (height - 9) / 2, K_BODY_FLAT);
            return;
        }
        double yMax = maxHigh * 1.05;
        double yMin = minLow * 0.95;
        if (yMax <= yMin || !Double.isFinite(yMax) || !Double.isFinite(yMin)) {
            double p = Math.max(Math.max(maxHigh, cur), 0.001);
            yMax = p * 1.05;
            yMin = p * 0.95;
        }
        if (yMax <= yMin) {
            yMax = yMin + 1.0;                    // divide-by-zero fallback (theoretically unreachable)
        }
        // ---- Grid: horizontal lines follow the dynamic Y axis in 5 even steps (Y_max ... Y_min, bottom line included);
        // when the price labels lack vertical room, skip the steps that would collide (existing convention). ----
        int lastLabelBottom = Integer.MIN_VALUE;
        for (int k = 0; k <= 4; k++) {
            double gv = yMax - (yMax - yMin) * 0.25 * k;
            int gy = chartMapY(gv, yMin, yMax, topY, height);
            gy = Math.max(topY, Math.min(bottomY - 1, gy));
            g.fill(plotLeft, gy, plotRight, gy + 1, K_GRID);
            String label = Num.fixed(gv, 2);
            int ly = Math.max(topY, Math.min(gy - 4, bottomY - 9));
            if (!label.isEmpty() && ly >= lastLabelBottom + 2) {
                //: tick labels never truncate -- they run the scaling ladder against the Y-axis column width
                drawFitRight(g, label, plotLeft - 4, ly, K_Y_AXIS_W - 4, K_LABEL);
                lastLabelBottom = ly + 9;
            }
        }
        g.fill(plotLeft + 2, topY, plotLeft + 3, bottomY, K_GRID);
        int centerX = plotLeft + plotW / 2;
        g.fill(centerX, topY, centerX + 1, bottomY, K_GRID);
        g.fill(plotRight - 2, topY, plotRight - 1, bottomY, K_GRID);
        // ---- Per-candle drawing: body / upper wick / lower wick all draw, wicks 1px, coloured by direction;
        // the 1px dark outer stroke on the body (K_STROKE) is kept so thin bodies stay readable on the dark background. ----
        for (int j = 0; j < showN; j++) {
            int t = start + j;
            double[] bar = klineBarAt(st, si, done, doneCount, t);
            if (bar == null || bar.length < 4) {
                continue;
            }
            double o = bar[0];
            double hi = bar[1];
            double lo = bar[2];
            double c = bar[3];
            if (!Double.isFinite(o) || !Double.isFinite(hi) || !Double.isFinite(lo) || !Double.isFinite(c)) {
                continue;
            }
            // slot right-alignment: j = showN-1 (newest) has its right edge on plotRight; older candles sit further left
            double rx = plotRight - (showN - 1 - j) * slotW;
            double lfd = rx - slotW;
            int slotL = Math.max(plotLeft, (int) Math.floor(lfd));
            int slotR2 = Math.min(plotRight, (int) Math.ceil(rx));
            int slotPix = Math.max(1, slotR2 - slotL);
            // Body width = slot x 0.6 rounded; capped at 20px (no infinite stretch), floored at 1px,
            // and never exceeding the visible slot. Keep it odd so a 1px wick has an unambiguous
            // centre pixel. The centre itself comes from the original fractional slot edges, not from
            // slotPix: rounding the slot first was the source of the one-pixel visual drift.
            int bodyW = (int) Math.round(slotW * 0.6);
            bodyW = Math.max(1, Math.min(Math.min(K_BODY_MAX, slotPix), bodyW));
            if ((bodyW & 1) == 0) {
                bodyW--;
            }
            int wickX = (int) Math.round((lfd + rx) * 0.5 - 0.5);
            wickX = Math.max(plotLeft, Math.min(plotRight - 1, wickX));
            int bx = wickX - (bodyW - 1) / 2;
            if (bx < plotLeft) {
                bx = plotLeft;
            }
            if (bx + bodyW > plotRight) {
                bx = Math.max(plotLeft, plotRight - bodyW);
            }
            // Re-derive the wick from the clamped body so edge candles keep the same centre as their body.
            wickX = bx + (bodyW - 1) / 2;
            boolean up = c > o;
            boolean down = c < o;
            int bodyCol = up ? K_BODY_UP : down ? K_BODY_DOWN : K_BODY_FLAT;
            int wickCol = up ? K_WICK_UP : down ? K_WICK_DOWN : K_WICK_FLAT;
            // chartMapY may legitimately return bottomY for the exact lower axis value. That coordinate is
            // outside the drawable pixel range [topY, bottomY), so a 1px flat body there would leave only
            // its dark outer stroke visible and look like a black limit-down line. Clamp both body edges to
            // the last drawable row before calculating the height. This applies equally to daily and short
            // charts because both modes use this same renderer.
            int yTop = Math.max(topY, Math.min(bottomY - 1,
                    chartMapY(Math.max(o, c), yMin, yMax, topY, height)));
            int yBot = Math.max(topY, Math.min(bottomY - 1,
                    chartMapY(Math.min(o, c), yMin, yMax, topY, height)));
            if (yBot < yTop) {
                yBot = yTop;
            }
            int bodyH = Math.max(1, yBot - yTop);
            // GuiGraphics fills [x1, x2), so the body's actual pixel columns are bx..bx+bodyW-1;
            // wickX is the shared centre pixel derived above. The body coordinates are now guaranteed
            // to contain at least one visible pixel, including a sealed lower-limit flat candle.
            if (hi > Math.max(o, c)) {
                int hy = chartMapY(hi, yMin, yMax, topY, height);
                if (hy < yTop) {
                    g.fill(wickX, Math.max(topY, hy), wickX + 1, yTop, wickCol);
                }
            }
            if (lo < Math.min(o, c)) {
                int ly = chartMapY(lo, yMin, yMax, topY, height);
                if (ly >= yTop + bodyH) {
                    g.fill(wickX, yTop + bodyH, wickX + 1, Math.min(bottomY, ly + 1), wickCol);
                }
            }
            g.fill(bx - 1, Math.max(topY, yTop - 1), bx + bodyW + 1, Math.min(bottomY, yTop + bodyH + 1), K_STROKE);
            g.fill(bx, yTop, bx + bodyW, yTop + bodyH, bodyCol);
        }
        // ---- the current-price through-line -- the gold 1px dashed line stays as is;
        // the left live-price tag drops its solid background block (the old PANEL_ROOT pad smeared over the
        // history candles and even spilled past the left bound); only the gold text remains and candles show through the glyph gaps;
        // the text clamps vertically inside the plot [topY, bottomY-9] -- price lines hugging top or bottom no longer
        // overlap the container's outer frame, and the horizontal start plotLeft+3 plus a tagW width check keep it fully inside. ----
        if (Double.isFinite(cur)) {
            int py = chartMapY(cur, yMin, yMax, topY, height);
            py = Math.max(topY, Math.min(bottomY - 1, py));
            for (int dx = plotLeft; dx < plotRight - 1; dx += 4) {
                g.fill(dx, py, Math.min(plotRight, dx + 2), py + 1, GOLD);
            }
            String curTx = Num.fixed(cur, 2);
            int tagW = Math.max(font.width(curTx) + 4, 22);
            if (plotLeft + tagW + 2 < plotRight) {
                // The live-price text shifts 5px down from the dashed line (the old py-1 nearly pressed the digits
                // onto the yellow dashes); it clamps into [topY, bottomY-9] so the glyphs land fully inside the plot and the frame,
                // never cut by the scissor; the dashed line itself did not move one pixel.
                int textY = Math.max(topY, Math.min(bottomY - 9, py + 5));
                pageText(g, curTx, plotLeft + 3, textY, GOLD);
            }
        }
        if (volumeVisible) {
            g.fill(plotLeft, separatorY, plotRight, separatorY + 1, K_GRID);
            double hoveredVolume = -1.0;
            double maxVolume = 0.0;
            for (int j = 0; j < showN; j++) {
                double[] bar = klineBarAt(st, si, done, doneCount, start + j);
                maxVolume = Math.max(maxVolume,
                        bar == null ? 0.0 : st.volumeForDisplay(bar));
            }
            if (maxVolume > 0.0 && Double.isFinite(maxVolume)) {
                int volumeBottom = mainBottom - 1;
                for (int j = 0; j < showN; j++) {
                    double[] bar = klineBarAt(st, si, done, doneCount, start + j);
                    if (bar == null || bar.length < 4) {
                        continue;
                    }
                    double v = st.volumeForDisplay(bar);
                    if (v <= 0.0) {
                        continue;
                    }
                    double rx = plotRight - (showN - 1 - j) * slotW;
                    int slotL = Math.max(plotLeft, (int) Math.floor(rx - slotW));
                    int slotR = Math.min(plotRight, (int) Math.ceil(rx));
                    int slotPix = Math.max(1, slotR - slotL);
                    if (mouseX >= slotL && mouseX < slotR && mouseY >= volumeTop && mouseY < mainBottom) {
                        hoveredVolume = v;
                    }
                    int bodyW = Math.max(1, Math.min(Math.min(K_BODY_MAX, slotPix), (int) Math.round(slotW * 0.6)));
                    int bx = Math.max(plotLeft, Math.min(plotRight - bodyW, slotL + (slotPix - bodyW) / 2));
                    int vh = Math.max(1, Math.min(K_VOLUME_H - 2,
                            (int) Math.round(v / maxVolume * (K_VOLUME_H - 2))));
                    int col = bar[3] > bar[0] ? ((K_BODY_UP & 0x00FFFFFF) | 0x99000000)
                            : bar[3] < bar[0] ? ((K_BODY_DOWN & 0x00FFFFFF) | 0x99000000)
                            : ((K_BODY_FLAT & 0x00FFFFFF) | 0x99000000);
                    g.fill(bx, volumeBottom - vh, bx + bodyW, volumeBottom, col);
                }
            }
            if (hoveredVolume >= 0.0) {
                String tip = trKey("oremarket.market.chart.volume",
                        String.valueOf(cn.oremarket.common.MarketVolume.roundShares(hoveredVolume)));
                int tw = font.width(tip) + 8;
                int tx = Math.max(x0 + 2, Math.min(mouseX + 6, x0 + w - tw - 2));
                int ty = Math.max(mainTop, Math.min(mouseY - 14, mainBottom - 12));
                g.fill(tx, ty, tx + tw, ty + 11, PANEL_ROOT);
                g.renderOutline(tx, ty, tw, 11, BORDER_MAIN);
                pageText(g, tip, tx + 4, ty + 2, TIP_TEXT);
            }
        }
        // the bottom X-axis band (height 2) is a pure safety margin; no main-plot element may enter it.
    }

    /** Candle t (0 = oldest, total-1 = newest): t below the complete count takes a finished candle, otherwise the in-progress one. */
    private double[] klineBarAt(MarketClientState st, int si, double[][] done, int doneCount, int t) {
        if (done == null || t < 0 || t >= doneCount || t >= done.length) {
            return null;
        }
        double[] bar = done[t];
        return bar == null || bar.length < 4 ? null : bar;
    }

    /**
     * Price -> screen Y: screenY = topY + height - (price - yMin)/(yMax - yMin) x height (rule nine).
     * YMax==yMin / non-finite prices / negative heights all fall onto the plot's mid line, banning divide-by-zero and NaN pixels.
     */
    private int chartMapY(double price, double yMin, double yMax, int topY, int height) {
        if (height <= 0) {
            return topY;
        }
        double range = yMax - yMin;
        if (!Double.isFinite(price) || !Double.isFinite(range) || Math.abs(range) < 1.0e-12) {
            return topY + height / 2;
        }
        int y = topY + height - (int) Math.round((price - yMin) / range * height);
        return Math.max(topY, Math.min(topY + height, y));
    }

    // ================================================================ button states + tooltip

    private void updateButtonStates(MarketClientState st, int mouseX, int mouseY) {
        // After a registry swap the derived arrays may shrink while selected waits for the 50ms repaint-merge window before syncSelected
        // clamps it -- in the gap every frame reads the arrays with a stale index. Clamp defensively in place first, so an out-of-range exception
        // swallowed by the outer catch cannot cause skipped frames plus warning spam (if the selection is still valid, the next syncSelected restores it).
        if (selected >= st.stocks.length) {
            selected = -1;
        }
        // While a dialog is open the background widgets stay visible -
        // hide-the-whole-layer-then-repaint is never allowed again; hover dies naturally in render via dead coordinates and
        // forceUnhover backs it up each frame so hovered can never freeze (restoreUnderlyingHover recomputes after closing).
        for (var w : this.renderables) {
            if (!(w instanceof MarketButton mb)) {
                continue;
            }
            boolean modalBtn = isModalOwnButton(mb);
            if (anyModalOpen() && !modalBtn) {
                mb.forceUnhover();
                continue;
            }
            if (anyModalOpen()) {
                // the dialog's own buttons keep clean hover checks (the top channel refreshes them on real coordinates each frame)
                mb.visible = true;
            }
            if (mb.mark == M_CLEAR_CONFIRM || mb.mark == M_CLEAR_CANCEL) {
                mb.visible = liquidateConfirmOpen;
            }
            // The loan selection state (loud) back-fills from the current selection each frame -- switching picks relights instantly with no widget rebuild.
            mb.loud = false;
            if (tab == TAB_LOAN && mb.mark >= M_LOAN + 10 && mb.mark <= M_LOAN + 17) {
                int idx = mb.mark - M_LOAN - 10;
                mb.loud = idx < 4 ? idx == loanProductSel : idx - 4 == loanModeSel;
            }
            // Seven: within the 200ms debounce window the control is force-disabled and clicks are ignored;
            // recovery judges solely on System.currentTimeMillis() (ClientTickEvent clears the stamp), never depending on a response packet.
            if (mb.disabledAtMs > 0) {
                if (System.currentTimeMillis() - mb.disabledAtMs < CLICK_DEBOUNCE_MS) {
                    mb.active = false;
                    continue;
                }
                mb.disabledAtMs = 0;
            }
            ButtonState buttonState = buttonStateForMark(mb, st);
            boolean active = buttonState.active();
            String why = buttonState.blockReason();
            String info = buttonState.hoverInfo();
            mb.active = active;
            // vanilla disabled buttons show no tooltip and isHovered() is always false: do the hit test by hand
            // while disabled explain why the click fails; while enabled (only the take-order button) explain what a click would fill
            String tip = active ? info : why;
            if (tip != null && mouseX >= mb.getX() && mouseX < mb.getX() + mb.getWidth()
                    && mouseY >= mb.getY() && mouseY < mb.getY() + mb.getHeight()
                    && pendingTooltip == null) {
                pendingTooltip = Component.literal(L.tr(tip)).withStyle(ChatFormatting.GRAY);
            }
        }
    }

    /**
     * Computes the enabled state and tooltip content for non-modal buttons.
     * Keeping this policy separate from widget iteration makes the state rules reviewable without
     * mixing them with hover repair, debounce handling, or tooltip hit testing.
     */
    private ButtonState buttonStateForMark(MarketButton button, MarketClientState state) {
        if (!state.hasData) {
            return new ButtonState(false, null, null);
        }
        if (button.mark == M_LOCATE) {
            return new ButtonState(holdingIndices().length > 0,
                    trKey("oremarket.market.no_holdings"), null);
        }
        if (button.mark == M_CLEAR_CONFIRM || button.mark == M_CLEAR_CANCEL) {
            return new ButtonState(liquidateConfirmOpen, null, null);
        }
        if (button.mark == M_PICKER) {
            return new ButtonState(n() > 0, trKey("oremarket.picker.empty"), null);
        }
        if (button.mark >= M_TRADE && button.mark < M_TRADE + 9) {
            boolean buy = button.mark < M_TRADE + 4;
            if (button.mark == M_TRADE + 8) {
                return new ButtonState(hasAnySellable(),
                        cn.oremarket.common.RawText.t("oremarket.text.0295"), null);
            }
            if (selected < 0) {
                return new ButtonState(false,
                        cn.oremarket.common.RawText.t("oremarket.text.0406"), null);
            }
            if (!state.quoteAt(selected)) {
                return new ButtonState(false,
                        cn.oremarket.common.RawText.t("oremarket.text.0256"), null);
            }
            int selectedIndex = selected;
            if (!state.unlocked(selectedIndex)) {
                return new ButtonState(false, state.unlockHint(selectedIndex), null);
            }
            if (state.suspended(selectedIndex)) {
                String reason = state.stocks[selectedIndex].displayName()
                        + cn.oremarket.common.RawText.t("oremarket.text.0349")
                        + MarketTime.human(state.suspendTicks[selectedIndex])
                        + cn.oremarket.common.RawText.t("oremarket.text.0168");
                return new ButtonState(false, reason, null);
            }
            if (!buy && state.available[selectedIndex] <= 0) {
                String reason = state.frozen[selectedIndex] > 0
                        ? trKey("oremarket.trade.hint.frozen_t1", L.money(state.frozen[selectedIndex]))
                        : cn.oremarket.common.RawText.t("oremarket.text.0295");
                return new ButtonState(false, reason, null);
            }
        }
        if (button.mark >= M_LOAN && button.mark < M_LOAN + 32) {
            return loanButtonState(button, state);
        }
        if (button.mark >= M_SHORT_OPEN && button.mark <= M_LIMIT_CANCEL) {
            return shortButtonState(button, state);
        }
        return new ButtonState(true, null, null);
    }

    /** Applies the loan page's enablement rules without touching widget layout. */
    private ButtonState loanButtonState(MarketButton button, MarketClientState state) {
        int mark = button.mark;
        if (loanConfirmOpen && mark != M_LOAN + 30 && mark != M_LOAN + 31) {
            return new ButtonState(false, null, null);
        }
        if (mark == M_LOAN + 30 || mark == M_LOAN + 31) {
            return new ButtonState(true, null, null);
        }
        if (mark >= M_LOAN + 10 && mark <= M_LOAN + 13) {
            return new ButtonState(true, null, loanProductTip(mark - M_LOAN - 10, state));
        }
        if (mark >= M_LOAN + 14 && mark <= M_LOAN + 17) {
            String tipKey = switch (mark - M_LOAN - 14) {
                case 1 -> "oremarket.loan.tip.mode1";
                case 2 -> "oremarket.loan.tip.mode2";
                case 3 -> "oremarket.loan.tip.mode3";
                default -> "oremarket.loan.tip.mode0";
            };
            return new ButtonState(true, null, trKey(tipKey));
        }
        if (mark == M_LOAN + 18 || mark == M_LOAN + 19) {
            int installmentCount = Math.max(1, Math.min(loanTermLimit(), Math.max(1, loanInstallmentsSel)));
            boolean decrement = mark == M_LOAN + 18;
            boolean active = decrement ? installmentCount > 1 : installmentCount < loanTermLimit();
            String countTip = trKey("oremarket.loan.row.inst.count",
                    String.valueOf(installmentCount), String.valueOf(loanTermLimit()));
            return active ? new ButtonState(true, null, countTip)
                    : new ButtonState(false, countTip, null);
        }
        if (mark == M_LOAN + 23) {
            boolean active = state.loan.myListingCoins <= 0 && loanSelAmount <= Math.max(0, state.balance);
            String reason = state.loan.myListingCoins > 0
                    ? trKey("oremarket.p2p.err.dup", String.valueOf(state.loan.myListingCoins))
                    : trKey("oremarket.p2p.err.coins", L.money(state.balance));
            return new ButtonState(active, reason, null);
        }
        if (mark == M_LOAN + 24) {
            return new ButtonState(state.loan.myListingCoins > 0, trKey("oremarket.p2p.err.gone"), null);
        }
        if (mark == M_LOAN + 25) {
            return new ButtonState(state.loan.openListings > 0, trKey("oremarket.p2p.err.none"), null);
        }
        if (mark == M_LOAN + 26) {
            long hangover = Math.max(0L, state.loan.clawbackDebt) + Math.max(0L, state.loan.shortDebt);
            String reason = hangover <= 0
                    ? trKey("oremarket.loan.err.no_loan")
                    : trKey("oremarket.loan.err.no_coins", L.money(hangover));
            return new ButtonState(hangover > 0 && state.balance > 0, reason, null);
        }
        if (mark == M_LOAN + 4) {
            if (loanBusy(state)) {
                return new ButtonState(false, trKey("oremarket.loan.ui.processing"), null);
            }
            boolean active = canBorrowNow(state);
            String reason = borrowBlockReason(state);
            String info = active ? trKey("oremarket.loan.estimate.total", L.money(loanQuote(state)[2])) : null;
            return new ButtonState(active, reason, info);
        }
        if (mark == M_LOAN + 5) {
            return new ButtonState(true, null, null);
        }
        if (mark == M_LOAN + 6 || mark == M_LOAN + 20 || mark == M_LOAN + 21 || mark == M_LOAN + 22) {
            return new ButtonState(!state.loan.liquidating, trKey("oremarket.loan.ui.liquidating"), null);
        }
        if (mark == M_LOAN + 9 && loanStateCode(state) == 4) {
            return new ButtonState(emergencyLimit(state) > 0,
                    trKey("oremarket.loan.err.product_off"), null);
        }
        long limit = borrowLimit(state);
        long amount = loanSelAmount;
        boolean active = switch (mark - M_LOAN) {
            case 0, 1 -> amount > 1;
            case 2, 3, 7, 8, 9 -> amount < limit;
            default -> true;
        };
        String reason = active ? null
                : (mark == M_LOAN || mark == M_LOAN + 1
                ? trKey("oremarket.loan.err.amount_range", "1")
                : trKey("oremarket.loan.err.over_limit", L.money(limit)));
        return new ButtonState(active, reason, null);
    }

    /** Applies short-selling, order-book, and limit-order button rules. */
    private ButtonState shortButtonState(MarketButton button, MarketClientState state) {
        int mark = button.mark;
        switch (mark) {
            case M_SHORT_OPEN, M_SHORT_ONECLICK, M_ORDER_SUBMIT, M_LIMIT_SUBMIT -> {
                if (selected < 0 || selectedCodeForForm(state) == null) {
                    return new ButtonState(false, cn.oremarket.common.RawText.t("oremarket.text.0427"), null);
                }
                if (!state.unlocked(selected)) {
                    return new ButtonState(false, state.unlockHint(selected), null);
                }
                if (state.suspended(selected)) {
                    return new ButtonState(false, cn.oremarket.common.RawText.t("oremarket.text.0385"), null);
                }
                if (!state.quoteAt(selected) || !(state.price[selected] > 0)
                        || !Double.isFinite(state.price[selected])) {
                    return new ButtonState(false, trKey("oremarket.error.stock_frozen"), null);
                }
                if ((mark == M_SHORT_OPEN || mark == M_SHORT_ONECLICK)
                        && state.viewSize() > 0 && selected >= 0
                        && state.poolMax[selected] >= 0 && state.poolLeft[selected] <= 0) {
                    return new ButtonState(false, trKey("oremarket.short.err.pool_empty"), null);
                }
                return new ButtonState(true, null, null);
            }
            case M_SHORT_CLOSE_ONE, M_SHORT_CLOSE_HALF, M_SHORT_CLOSE_ALL -> {
                if (state.shorts.isEmpty()) {
                    return new ButtonState(false, cn.oremarket.common.RawText.t("oremarket.text.0361"), null);
                }
                if (mark == M_SHORT_CLOSE_ONE || mark == M_SHORT_CLOSE_HALF) {
                    MarketClientState.ShortInfo target = selectedShortInfo(state);
                    if (target == null) {
                        return new ButtonState(true, trKey("oremarket.short.err.no_selection"), null);
                    }
                    int stockIndex = state.indexOfCode(target.code);
                    boolean quoteAvailable = stockIndex >= 0 && state.quoteAt(stockIndex)
                            && state.price[stockIndex] > 0 && Double.isFinite(state.price[stockIndex]);
                    String reason = quoteAvailable ? cn.oremarket.common.RawText.t("oremarket.text.0361")
                            : trKey("oremarket.error.quote_unavailable");
                    return new ButtonState(quoteAvailable, reason, null);
                }
                boolean anyQuote = false;
                for (MarketClientState.ShortInfo shortPosition : state.shorts) {
                    int stockIndex = state.indexOfCode(shortPosition.code);
                    if (stockIndex >= 0 && state.quoteAt(stockIndex) && state.price[stockIndex] > 0
                            && Double.isFinite(state.price[stockIndex])) {
                        anyQuote = true;
                        break;
                    }
                }
                String reason = anyQuote ? cn.oremarket.common.RawText.t("oremarket.text.0361")
                        : trKey("oremarket.error.quote_unavailable");
                return new ButtonState(anyQuote, reason, null);
            }
            case M_ORDER_CANCEL -> {
                MarketClientState.BookInfo order = state.bookOrder(selectedOrderId);
                String reason = order == null ? cn.oremarket.common.RawText.t("oremarket.text.0407")
                        : cn.oremarket.common.RawText.t("oremarket.text.0598");
                return new ButtonState(order != null && order.mine(), reason, null);
            }
            case M_ORDER_TAKE -> {
                MarketClientState.BookInfo order = state.bookOrder(selectedOrderId);
                String reason = orderTakeBlockReason(state, order);
                boolean active = reason == null;
                String info = active ? orderTakeFillInfo(state, order) : null;
                syncOrderTakeLabel(button, state);
                return new ButtonState(active, reason, info);
            }
            case M_LIMIT_CANCEL -> {
                return new ButtonState(selectedLimitId >= 0,
                        cn.oremarket.common.RawText.t("oremarket.text.0334"), null);
            }
            default -> {
                return new ButtonState(true, null, null);
            }
        }
    }

    /** The three values needed after a button's business-state policy has been evaluated. */
    private record ButtonState(boolean active, String blockReason, String hoverInfo) {
    }

    private boolean canBorrowNow(MarketClientState st) {
        if (st == null || st.loan == null) {
            return false;
        }
        int product = Math.max(0, Math.min(3, loanProductSel));
        if (product == 2 && emergencyLimit(st) <= 0) {
            return false;
        }
        if (product == 1 && !st.hasPosition()) {
            return false;
        }
        if (product == 3 && (st.shorts == null || st.shorts.isEmpty())) {
            return false;
        }
        return loanStateCode(st) == 1 && !st.borrowedToday() && !st.hasLoan()
                && borrowLimit(st) > 0 && !st.loan.liquidating && st.loan.clawbackDebt == 0
                // word-for-word the server borrow()'s gate, so players are refused before clicking, not after.
                && (st.loan.shortDebt <= 0 || loanProductSel == 2);
    }

    private String borrowBlockReason(MarketClientState st) {
        // Hints always go through single-key templates (numbers come from server config and the snapshot); no more fragment splicing
        if (!st.canBorrowByLevel() || borrowLimit(st) <= 0) {
            return st.borrowLevelHint();
        }
        // so players are not surprised after clicking (true legality is still decided by the server's recomputation).
        if (loanProductSel == 2 && emergencyLimit(st) <= 0) {
            return trKey("oremarket.loan.err.product_off");
        }
        if (loanProductSel == 1 && !st.hasPosition()) {
            return trKey("oremarket.loan.err.no_collateral");
        }
        if (loanProductSel == 3 && st.shorts.isEmpty()) {
            return trKey("oremarket.loan.err.no_short");
        }
        if (st.hasLoan()) {
            return trKey("oremarket.loan.err.has_loan");
        }
        if (st.loan.clawbackDebt > 0) {
            return trKey("oremarket.loan.err.clawback", L.money(st.loan.clawbackDebt));
        }
        // consistency check: the liquidation shortfall ranks with the bankruptcy debt; every product except the emergency loan must gate on it.
        if (st.loan.shortDebt > 0 && loanProductSel != 2) {
            return trKey("oremarket.loan.err.short_debt", L.money(st.loan.shortDebt));
        }
        if (st.inBankruptCooldown()) {
            return trKey("oremarket.loan.cooldown.left", String.valueOf(st.cooldownDaysLeft()));
        }
        if (st.borrowedToday()) {
            return trKey("oremarket.loan.err.borrowed_today");
        }
        if (st.availableQuota() <= 0) {
            return trKey("oremarket.loan.err.over_limit", L.money(st.loan.creditLimit));
        }
        return null;
    }

    // ================================================================ input

    /** // trading page list and form hit-testing; coordinates and drawing share one row/column geometry (explanation-block offsets and the dynamic content height included). */
    private boolean handleTradePageClick(MarketClientState st, double mouseX, double mouseY) {
        int left = tradeContentLeft();
        int right = tradeContentRight();
        int base = tradeContentTop();
        int bottom = tradeContentBottom();
        if (mouseX < left || mouseX >= right || mouseY < base || mouseY >= bottom) return false;
        if (tradeSub == 0) {
            // short positions are a nested list: hit tests use the current drawing rect (outer scroll offset included) + the inner offset.
            int[] rect = shortListRect();
            int dataTop = rect[1] + 14;
            int innerBot = nestedInnerBottom(dataTop, rect[3]);
            for (int i = 0; i < st.shorts.size(); i++) {
                int yy = dataTop + i * LIST_ROW - shortsInnerScroll;
                // Part 2: half rows hit too (intersection test), and only pixels inside the clip viewport count
                if (!rowIntersects(yy, yy + LIST_ROW, dataTop, innerBot)) continue;
                if (mouseY >= visibleTop(yy, yy + LIST_ROW, dataTop, innerBot)
                        && mouseY < visibleBottom(yy, yy + LIST_ROW, dataTop, innerBot)) {
                    selectedShortPositionId = st.shorts.get(i).id;
                    selectedOrderId = -1;
                    selectedLimitId = -1;
                    return true;
                }
            }
            return false;
        }
        boolean limit = tradeSub == 2;
        int[] rect = orderListRect(limit);
        int dataTop = rect[1] + 14;
        // Part 2: the hit's bottom edge = the clip viewport's bottom (list box bottom - padding)
        int rectBot = nestedInnerBottom(dataTop, rect[3]);
        if (mouseY < dataTop || mouseY >= rectBot) return false;
        int inner = limit ? limitScroll : bookScroll;
        // Hit testing uses the same row height as drawing (32 in two-row mode); the old code always judged 16,
        // so in narrow two-row mode a click on the second row hit the row above -- drawing and hits must share one pixel geometry.
        int pitch = limit ? limitRowH() : bookRowH();
        if (!limit) {
            // so others' orders could never highlight and therefore never be matched).
            // whether an order is mine is now the button states' business: mine can only be cancelled, others' can be taken.
            for (int i = 0; i < st.orderBook.size(); i++) {
                int yy = dataTop + i * pitch - inner;
                // half rows are clickable: hit area = row rect INTERSECT viewport (the very clip region used for drawing)
                if (!rowIntersects(yy, yy + pitch, dataTop, rectBot)) continue;
                if (mouseY >= visibleTop(yy, yy + pitch, dataTop, rectBot)
                        && mouseY < visibleBottom(yy, yy + pitch, dataTop, rectBot)) {
                    selectedOrderId = st.orderBook.get(i).id;
                    selectedShortPositionId = -1;
                    selectedLimitId = -1;
                    return true;
                }
            }
        } else {
            boolean label = limitHeaderLabel(left, right);
            int[] c = limitColumns(left, right, label);
            for (int i = 0; i < st.limitOrders.size(); i++) {
                int yy = dataTop + i * pitch - inner;
                if (!rowIntersects(yy, yy + pitch, dataTop, rectBot)
                        || mouseY < visibleTop(yy, yy + pitch, dataTop, rectBot)
                        || mouseY >= visibleBottom(yy, yy + pitch, dataTop, rectBot)) {
                    continue;
                }
                if (mouseX >= c[5] + 5 && mouseX < c[5] + 65) {
                    // the in-row cancel button: cancels this order directly with no prior selection needed
                    long id = st.limitOrders.get(i).id;
                    selectedLimitId = id;
                    selectedShortPositionId = -1;
                    selectedOrderId = -1;
                    OreMarketNetwork.CHANNEL.sendToServer(
                            new C2SLimitPacket(C2SLimitPacket.ACTION_CANCEL, "", true, 0, 0, id));
                    return true;
                }
                selectedLimitId = st.limitOrders.get(i).id;
                selectedShortPositionId = -1;
                selectedOrderId = -1;
                return true;
            }
        }
        return false;
    }

    /** Wheel ownership test: is the mouse inside the current drawing rect (the outer offset is already baked into it)? */
    private static boolean inRect(int[] rect, double mouseX, double mouseY) {
        return mouseX >= rect[0] && mouseX < rect[2] && mouseY >= rect[1] && mouseY < rect[3];
    }

    /**
     * Wheel bubbling rule: the inner list scrolls first; once it is at its top/bottom and the same direction continues, the surplus bubbles to the outer
     * list until the outer also bottoms out. Returns {new inner offset, new outer offset}.
     */
    private static int[] bubbleScroll(int inner, int innerMax, int outer, int outerMax, int amountPx) {
        int want = inner + amountPx;
        int in = clamp(want, 0, Math.max(0, innerMax));
        int carry = want - in;
        int out = outerMax > 0 ? clamp(outer + carry, 0, outerMax) : clamp(outer, 0, Math.max(0, outerMax));
        return new int[]{in, out};
    }

    /** // trading sub-page wheel: outer and inner share step 20; inner first, overflow bubbles outward. */
    private boolean handleTradePageScroll(double mouseX, double mouseY, double amount) {
        MarketClientState st = MarketClientState.INSTANCE;
        int base = tradeContentTop();
        int left = tradeContentLeft();
        int right = tradeContentRight();
        int bottom = tradeContentBottom();
        if (mouseX < left || mouseX >= right || mouseY < base || mouseY >= bottom) return false;
        int amountPx = -(int) Math.round(amount * SCROLL_STEP);
        if (tradeSub == 0) {
            layoutShortPage(st);
            if (inRect(shortListRect(), mouseX, mouseY)) {
                int[] r = bubbleScroll(shortsInnerScroll, shortsInnerMax(st),
                        shortScroll, shortLayout.maxScrollOffset(), amountPx);
                shortsInnerScroll = r[0];
                shortScroll = r[1];
            } else {
                shortScroll = clamp(shortScroll + amountPx, 0, shortLayout.maxScrollOffset());
            }
            return true;
        }
        boolean limit = tradeSub == 2;
        VerticalLayout page = pageLayout(limit);
        if (limit) {
            layoutLimitPage(st);
        } else {
            layoutOrderPage(st);
        }
        int inner = limit ? limitScroll : bookScroll;
        int innerMax = tradeNestedInnerMax(page, TO_LIST,
                limit ? st.limitOrders.size() : st.orderBook.size(),
                limit ? limitRowH() : bookRowH());
        int outer = pageScroll(limit);
        int outerMax = page.maxScrollOffset();
        if (inRect(orderListRect(limit), mouseX, mouseY)) {
            int[] r = bubbleScroll(inner, innerMax, outer, outerMax, amountPx);
            inner = r[0];
            outer = r[1];
        } else {
            outer = clamp(outer + amountPx, 0, outerMax);
        }
        if (limit) {
            limitScroll = inner;
            limitOuterScroll = outer;
        } else {
            bookScroll = inner;
            orderOuterScroll = outer;
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double screenMouseX, double screenMouseY, int button) {
        // Safety: events can land between frames (the first click after a resize) -- refresh the transform before converting
        // so hit tests and the latest drawing agree on one uiScale/offset.
        updateUiTransform();
        // Screen coordinates -> canvas coordinates (minus the centering offset, divided by uiScale), the same rule as the layout's hitRect;
        // sub-components and super.mouseClicked all receive canvas coordinates too, aligning the vanilla widgets' hit areas.
        double mouseX = (screenMouseX - offsetX) / uiScale;
        double mouseY = (screenMouseY - offsetY) / uiScale;
        lastScreenMouseX = screenMouseX;
        lastScreenMouseY = screenMouseY;
        // While a dialog exists, mouseClicked intercepts as its first act -
        // clicks inside the dialog panel rect (its buttons included) pass only to the widget layer (the background widgets are hidden at that moment);
        // clicks outside the panel (background page / event bar / tab bar) always return true and never click through.
        if (anyModalOpen()) {
            int[] modal = liquidateConfirmOpen ? modalPanelRect() : loanConfirmRect();
            boolean inModal = mouseX >= modal[0] && mouseX < modal[2]
                    && mouseY >= modal[1] && mouseY < modal[3];
            if (!inModal) {
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // Part 2: suppressNextClick left by last frame's drag end is swallowed and reset here (guards against accidental row selects).
        if (consumeDragClicks()) {
            return true;
        }
        // MouseClicked means press. A press only records the start coordinates and
        // arms the ready state (isDragging=false): landing in a scrollable area outside button hot zones consumes the press,
        // and click dispatch defers to mouseReleased replay (movement <= 3px is an ordinary click; beyond 3px it upgrades to a drag,
        // whose release is swallowed by suppressNextClick -- exactly the spec's no-accidental-row-select after dragging).
        if (button == 0 && !overAnyWidget(mouseX, mouseY)) {
            int reg = dragHitRegion(mouseX, mouseY);
            if (reg >= 0 && dragPress(reg, mouseX, mouseY, button, dragRegionRect(reg), dragHotZones(reg))) {
                return true;
            }
        }
        return handleClick(mouseX, mouseY, button);
    }

    /** // click business dispatch in canvas coordinates (one entry shared by mouseClicked's conversion and the drag replay). */
    private boolean handleClick(double mouseX, double mouseY, int button) {
        // debug consistency check: to hunt a dead button, flip DEBUG_CLICK true and
        // this prints the mouse's GUI coordinates next to every button's hitRect for direct comparison.
        if (DEBUG_CLICK && button == 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("mouseClicked mouseX=").append(mouseX).append(" mouseY=").append(mouseY)
                    .append(" shift=").append(hasShiftDown()).append(" ctrl=").append(hasControlDown());
            for (ExOpButton b : exOps) {
                sb.append(" | ").append(b.stdKey()).append("[").append(b.drawX).append(",")
                        .append(b.drawY).append(",").append(b.drawX + b.drawW).append(",")
                        .append(b.drawY + b.drawH).append("]").append(b.enabled ? "on" : "off");
            }
            LOG.info(sb.toString());
        }
        MarketClientState st = MarketClientState.INSTANCE;
        // While any modal is open, left clicks outside the dialog rect and the top tab bar are swallowed wholesale -
        // nothing under the scrim (page buttons / event cards / quote interactions) may click through; dialog buttons still go through the widget layer.
        // clicking another tab or closing the screen clears the dialogs instantly (see clickedTab / onClose / keyPressed below).
        if (button == 0 && anyModalOpen()) {
            int[] modal = liquidateConfirmOpen ? modalPanelRect() : loanConfirmRect();
            boolean inModal = mouseX >= modal[0] && mouseX < modal[2]
                    && mouseY >= modal[1] && mouseY < modal[3];
            int cx0 = gx + mkX;
            boolean inTabs = mouseY >= mkAbsY() && mouseY < mkAbsY() + TAB_H
                    && mouseX >= cx0 && mouseX < cx0 + mkW;
            if (inTabs) {
                // clicking the tab bar: close dialogs first, then let the tab switch continue through the clickedTab branch below
                loanConfirmOpen = false;
                liquidateConfirmOpen = false;
            } else if (!inModal) {
                return true;                                    // the scrim layer swallows the click; background buttons cannot receive it
            }
        }
        // While the stock dropdown is expanded its panel covers all self-drawn rows and inputs -- it intercepts first:
        // inside the panel a click selects that row; outside (including the covered list rows it may graze) the click only closes the panel and never reaches the layers beneath.
        // must run before super.mouseClicked or the edit boxes below steal it.
        if (button == 0 && pickerOpen && handlePickerClick(mouseX, mouseY)) {
            return true;
        }
        // With config or quotes in FAILED state, clicking the container = retry;
        // clicks inside the retry button's 2-second cooldown are swallowed outright with no duplicate packet (retryLoads judges internally)
        if (button == 0 && clicksInContainers(mouseX, mouseY)
                && (!configReady()
                        || st.snapshotStatus == MarketClientState.SnapshotStatus.FAILED)) {
            retryLoads();
            return true;
        }
        // Every scrollbar widget is deleted; scrolling runs on the mouse wheel only (inner first, overflowing outward),
        // so no drag logic remains here.
        // While a modal is open every self-drawn hot zone (list rows / exchange buttons / dropdown panel) yields -
        // rows behind the scrim must not react anymore; only the dialog's own buttons (vanilla widget layer) are live.
        if (button == 0 && !anyModalOpen() && st.hasData && playerReady()) {
            // (1) exchange row buttons are checked first:
            // walk every row, then every button within it, and on a hit use that button's
            // hitRect (the one the layout phase computed; clicks never recompute coordinates), letting no other area cut in first.
            // Seven: buttons inside their 200ms debounce window consume the click without sending a packet.
            // Hit areas intersect the drawing clip -- half buttons shaved by the panel, invisible to the player, no longer answer clicks.
            if (tab == TAB_EXCH) {
                // right-column action buttons first (hit area = the visual rect)
                for (ExOpButton b : exOps) {
                    if (b.contains(mouseX, mouseY)) {
                        if (!b.inDebounce()) {
                            onExchangeOp(b);
                        }
                        return true;
                    }
                }
            }
            int x = gx + mkX;
            int y = mkAbsY();
            // (2) the main tab bar
            int clickedTab = tabAt((int) mouseX, x, y, (int) mouseY);
            if (clickedTab >= 0) {
                if (clickedTab != tab) {
                    tab = clickedTab;
                    //: switching tabs destroys every modal and dropdown immediately, and background buttons regain clickability next frame.
                    loanConfirmOpen = false;
                    liquidateConfirmOpen = false;
                    pickerOpen = false;
                    if (tab == TAB_LOAN && firstLoanTab) {
                        loanSelAmount = 1;
                        firstLoanTab = false;
                    }
                    // entering the trading page pulls the server-wide order book; entering the leaderboard pulls the fresh ranking (both server-authoritative data)
                    if (tab == TAB_TRADE) {
                        OreMarketNetwork.CHANNEL.sendToServer(
                                new C2SRequestBookPacket(C2SRequestBookPacket.KIND_ORDER_BOOK));
                    } else if (tab == TAB_RANK) {
                        OreMarketNetwork.CHANNEL.sendToServer(
                                new C2SRequestBookPacket(C2SRequestBookPacket.KIND_LEADERBOARD));
                    }
                    rebuildWidgets();
                    builtKey = layoutKey();
                }
                return true;
            }
            // (2b) sub-tab bar (trading / leaderboard): height 16, hugging the main tab bar below it
            if (hasSubTabs() && mouseY >= y + TAB_H && mouseY < y + TAB_H + SUB_TAB_H
                    && mouseX >= x && mouseX < x + mkW) {
                int sub = subTabAt((int) mouseX, x, y + TAB_H);
                if (sub >= 0 && sub != subIndex()) {
                    setSubIndex(sub);
                    rebuildWidgets();
                    builtKey = layoutKey();
                }
                return true;
            }
            // (2c) trading sub-page list rows: short selection / order selection / limit-order cancel
            if (tab == TAB_TRADE && handleTradePageClick(st, mouseX, mouseY)) {
                return true;
            }
            // (3) selecting a stock row in the quotes page's left column (the right column's detail panel flips instantly with the selection).
            // The hit area matches the drawing (row y = list top + index x 16 - scroll offset) and only full rows answered.
            if (tab == TAB_MARKET) {
                int colLeft = gx + mkX + 2;
                int[] vclip = stockListClip(gx + mkX + 2, mkAbsY() + splitTop, leftColW, splitH);
                int viewTop = vclip[1];
                int viewBot = vclip[3];
                for (int i = 0; i < n(); i++) {
                    int rowTop = viewTop + i * LIST_ROW - mkScroll;
                    //: half rows accept clicks too (hit area = row rect INTERSECT viewport), same rule as the drawing clip
                    if (!rowIntersects(rowTop, rowTop + LIST_ROW, viewTop, viewBot)) {
                        continue;
                    }
                    if (mouseX >= vclip[0] && mouseX <= vclip[2]
                            && mouseY >= visibleTop(rowTop, rowTop + LIST_ROW, viewTop, viewBot)
                            && mouseY < visibleBottom(rowTop, rowTop + LIST_ROW, viewTop, viewBot)) {
                        if (!st.suspended(i)) {
                            setSelected(i);
                        }
                        return true;
                    }
                }
            }
            // (4) clicking the blank first two lines of an exchange row = select that stock (buttons were handled in step 1; the rate line has no hit area)
            // It likewise recognises only the part inside the viewport: below the panel's bottom edge (toward the event bar) nothing gets selected.
            for (ExchangeRow r : exRows) {
                if (exHit(mouseX, mouseY, r.left - 2, r.top, r.right + 2, r.top + r.height)) {
                    setSelected(r.index);
                    return true;
                }
            }
        }
        // (5) the remaining vanilla widgets (close, buy/sell button area, loan buttons, input boxes)
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Clicks inside the expanded dropdown list. An empty list just closes it; clicking a row calls setSelected (syncing the quotes page highlight).
     * Returns true = this click was consumed (including closing the list by clicking outside the panel).
     */
    private boolean handlePickerClick(double mouseX, double mouseY) {
        if (!pickerPanelValid) {
            pickerOpen = false;
            return true;
        }
        int x1 = pickerPanelRect[0], y1 = pickerPanelRect[1];
        int x2 = pickerPanelRect[2], y2 = pickerPanelRect[3];
        if (mouseX < x1 || mouseX >= x2 || mouseY < y1 || mouseY >= y2) {
            pickerOpen = false;
            return true;
        }
        int m = n();
        if (m <= 0) {
            pickerOpen = false;
            return true;
        }
        int row = ((int) mouseY - y1 - LIST_PAD + pickerScroll) / LIST_ROW;
        if (row < 0 || row >= m) {
            return true;                                        // clicking the panel's blank area: consumed, panel stays open
        }
        // Frozen / no-quote / locked rows refuse selection; halted rows remain selectable so the active form can show the halt reason.
        MarketClientState st = MarketClientState.INSTANCE;
        if (pickerRowBlocked(st, row) && !st.suspended(row)) {
            ClientEvents.showToast(trKey("oremarket.error.stock_frozen"));
            return true;
        }
        setSelected(row);
        pickerOpen = false;
        return true;
    }

    // ---------------- scrollbar dragging: press detection (all three sets share the pattern) ----------------






    /** Wheel ownership: only one container answers at a time; the quotes page's left and right columns scroll independently. */
    @Override
    public boolean mouseScrolled(double screenMouseX, double screenMouseY, double delta) {
        // wheel coordinates are also inverse-mapped into canvas space first before the ownership test (refreshing the transform first, same safety as above).
        updateUiTransform();
        double mouseX = (screenMouseX - offsetX) / uiScale;
        double mouseY = (screenMouseY - offsetY) / uiScale;
        // While a dialog exists the wheel belongs to the dialog alone -
        // cursor inside the dialog panel's visible area with overflowing content -> the dialog body itself scrolls;
        // anywhere else (even over the background lists / event bar / toasts) -> swallowed, return true.
        if (anyModalOpen()) {
            int[] modal = liquidateConfirmOpen ? modalPanelRect() : loanConfirmRect();
            boolean inPanel = mouseX >= modal[0] && mouseX < modal[2]
                    && mouseY >= modal[1] && mouseY < modal[3];
            if (inPanel && !liquidateConfirmOpen) {
                int max = loanConfirmOverflow(MarketClientState.INSTANCE);
                if (max > 0) {
                    modalScroll = clamp(modalScroll - (int) Math.round(delta * SCROLL_STEP), 0, max);
                }
            }
            return true;
        }
        // Wheel ownership follows the paint layering: the error toast sits topmost and takes the wheel first (inside its
        // rect with overflowing content, step 10); the event detail panel is next; only then does surplus bubble to the lists below.
        if (errorToast.contains(mouseX, mouseY) && errorToast.canScroll()) {
            errorToast.scroll(delta);
            return true;
        }
        if (eventTipPanelW > 0 && eventTooltipContains(mouseX, mouseY)) {
            int tipMax = Math.max(0, eventTipContentHeight - Math.max(1, eventTipPanelH - 12));
            if (tipMax > 0) {
                eventTipScroll = clamp((int) Math.round(eventTipScroll - delta * SCROLL_STEP_TIP), 0, tipMax);
                return true;
            }
        }
        // wheel ownership: scroll whichever scrollable area the mouse sits in; once the inner one bottoms out, same-direction surplus bubbles to the outer.
        int evY = gy + barY;
        if (mouseY >= evY && mouseY <= evY + barH) {
            if (barContentH > barH) {
                eventScroll = clamp((int) Math.round(eventScroll - delta * SCROLL_STEP_SIDE), 0, eventMaxScroll());
            }
            return true;
        }
        int mx0 = gx + mkX;
        int my0 = mkAbsY();
        MarketClientState st = MarketClientState.INSTANCE;
        int amountPx = -(int) Math.round(delta * SCROLL_STEP);   // outer and inner steps are identical
        if (mouseX >= mx0 && mouseX <= mx0 + mkW && mouseY >= my0 && mouseY <= my0 + mkH) {
            if (mouseY < my0 + TAB_H) {
                return true;    // nothing scrolls under the tab bar
            }
            if (hasSubTabs() && mouseY < my0 + TAB_H + SUB_TAB_H) {
                return true;    // same for the sub-tab bar
            }
            //: while any modal is open the wheel is swallowed outright -- content under the scrim (including list scrolling) is never scrolled through.
            if (anyModalOpen()) {
                return true;
            }
            //: while the dropdown is expanded the wheel scrolls only that list panel (the panel rect is cached during drawing).
            // Its upper bound runs pickerMaxScroll -- the same convention as the lists (a 4px gap at the bottom).
            if (pickerOpen && pickerPanelValid
                    && mouseX >= pickerPanelRect[0] && mouseX < pickerPanelRect[2]
                    && mouseY >= pickerPanelRect[1] && mouseY < pickerPanelRect[3]) {
                pickerScroll = clamp(pickerScroll - (int) Math.round(delta * SCROLL_STEP), 0, pickerMaxScroll());
                return true;
            }
            // On the Exchange tab the wheel scrolls only the left ore list; the right column (search / inputs / buttons) never moves.
            if (tab == TAB_EXCH) {
                int[] exRect = {gx + exX + exListL, exViewTop(), gx + exX + exListR, exViewBottom()};
                if (inRect(exRect, mouseX, mouseY)) {
                    int[] r = bubbleScroll(exScroll, maxExScroll(), midScroll, maxMidScroll(), amountPx);
                    exScroll = r[0];
                    cancelLinkedExAnim();
                    midScroll = r[1];
                }
                return true;
            }
            if (tab == TAB_TRADE) {
                return handleTradePageScroll(mouseX, mouseY, delta);
            }
            if (tab == TAB_RANK) {
                layoutRankPage(st);
                rankScroll = clamp(rankScroll + amountPx, 0, rankLayout.maxScrollOffset() + LIST_PAD);
                return true;
            }
            if (tab == TAB_MARKET) {
                layoutMarket(st);
                int[] listRect = stockListDrawRect();
                int outerMax = maxMidScroll();
                if (inRect(listRect, mouseX, mouseY)) {
                    int[] r = bubbleScroll(mkScroll, listMaxScroll(), midScroll, outerMax, amountPx);
                    mkScroll = r[0];
                    cancelLinkedMkAnim();   //: the user's wheel takes over and the linking animation yields
                    midScroll = r[1];
                    return true;
                }
                int[] detailRect = {mx0 + rightColOff, my0 + splitTop,
                        mx0 + rightColOff + rightColW, my0 + splitTop + splitH};
                if (inRect(detailRect, mouseX, mouseY)) {
                    int[] r = bubbleScroll(detailScroll, detailMaxScroll(), midScroll, outerMax, amountPx);
                    detailScroll = r[0];
                    midScroll = r[1];
                    return true;
                }
                if (outerMax > 0) {
                    midScroll = clamp(midScroll + amountPx, 0, outerMax);
                }
                return true;
            }
            if (tab == TAB_LOG) {
                if (logTooSmall) {
                    return true;
                }
                layoutLogPage(st);
                // The log page holds a single log block; the wheel counts only inside the log rect, step 20
                int[] listRect = logListRect();
                if (inRect(listRect, mouseX, mouseY)) {
                    logScroll = clamp(logScroll + amountPx, 0, logMaxScroll);
                }
                return true;
            }
            if (tab == TAB_LOAN) {
                //: the wheel counts only inside the scroll viewport (over the fixed button row or the tab bar it never scrolls content).
                int[] vp = loanViewport();
                if (loanOverflow > 0 && mouseX >= vp[0] && mouseX < vp[2]
                        && mouseY >= vp[1] && mouseY < vp[3]) {
                    loanScroll = clamp(loanScroll + amountPx, 0, loanOverflow);
                }
                return true;
            }
            if (loanOverflow > 0) {
                loanScroll = clamp((int) Math.round(loanScroll - delta * SCROLL_STEP_SIDE), 0, loanOverflow);
                return true;
            }
            if (stacked && maxMidScroll() > 0) {
                midScroll = clamp(midScroll + amountPx, 0, maxMidScroll());
            }
            return true;
        }
        int exy = exAbsY();
        if (mouseX >= gx && mouseX <= gx + exW && mouseY >= exy && mouseY <= exy + exH) {
            //: the bottom toolbar (quantity input) is deleted; wheel events go straight into the content-area test
            int[] exRect = {gx + exX, exAbsY() + exRowTop, gx + exX + exW, exAbsY() + exFooterY};
            int outerMax = maxMidScroll();
            if (inRect(exRect, mouseX, mouseY)) {
                int[] r = bubbleScroll(exScroll, maxExScroll(), midScroll, outerMax, amountPx);
                exScroll = r[0];
                cancelLinkedExAnim();       //: the user's wheel takes over and the linking animation yields
                midScroll = r[1];
            } else if (stacked && outerMax > 0) {
                midScroll = clamp(midScroll + amountPx, 0, outerMax);
            }
            return true;
        }
        if (stacked && maxMidScroll() > 0 && mouseY >= gy + midY && mouseY <= gy + midY + midH) {
            midScroll = clamp(midScroll + amountPx, 0, maxMidScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private int maxMidScroll() {
        return stacked ? Math.max(0, exH + STACK_GAP + mkH - midH) : 0;
    }

    /** Hover tooltip for suspended rows (left column); the no-select-on-click lives in mouseClicked. */
    private void hoverSuspendCheck(MarketClientState st, int mouseX, int mouseY) {
        if (pendingTooltip != null || this.minecraft.player == null) {
            return;
        }
        if (hoverListRow >= 0 && st.suspended(hoverListRow)) {
            int i = hoverListRow;
            pendingTooltip = Component.literal(L.tr(st.stocks[i].displayName() + cn.oremarket.common.RawText.t("oremarket.text.0129")))
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(L.tr(cn.oremarket.common.RawText.t("oremarket.exchange.suspend.remaining") + MarketTime.human(st.suspendTicks[i])
                            + cn.oremarket.common.RawText.t("oremarket.text.0240"))).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(L.tr(cn.oremarket.common.RawText.t("oremarket.exchange.suspend.no_trade"))).withStyle(ChatFormatting.RED));
        }
    }

    /** Whether the cursor is over any vanilla widget (button / edit box) -- widgets win first and drag mode never starts over them. */
    private boolean overAnyWidget(double mx, double my) {
        for (var w : this.children()) {
            if (w instanceof AbstractWidget aw && aw.isMouseOver(mx, my)) {
                return true;
            }
        }
        return false;
    }

    /** This frame's scrollable region rects (baseline canvas coordinates [l,t,r,b]; null when none is valid). */
    private double[] dragRegionRect(int region) {
        switch (region) {
            case REGION_EX: {
                // The exchange row list exists only on the Exchange tab, so other tabs produce no drag region;
                // the drag viewport = the left column's list rect (the fixed right column never scrolls).
                if (tab != TAB_EXCH) {
                    return null;
                }
                int x0 = gx + exX;
                return new double[]{x0 + exListL, exAbsY() + exRowTop,
                        x0 + exListR, exAbsY() + exFooterY};
            }
            case REGION_MK: {
                int[] r = stockListDrawRect();
                return new double[]{r[0], r[1], r[2], r[3]};
            }
            case REGION_SHORT: {
                int[] r = shortListRect();
                return new double[]{r[0], r[1], r[2], r[3]};
            }
            case REGION_ORDER: {
                int[] r = orderListRect(false);
                return new double[]{r[0], r[1], r[2], r[3]};
            }
            case REGION_LIMIT: {
                int[] r = orderListRect(true);
                return new double[]{r[0], r[1], r[2], r[3]};
            }
            case REGION_LOG: {
                int[] r = logListRect();
                return new double[]{r[0], r[1], r[2], r[3]};
            }
            case REGION_RANK: {
                int top = tradeBoundsTop() + 4;
                int bottom = tradeContentBottom();
                return new double[]{tradeContentLeft(), top, tradeContentRight(), bottom};
            }
            case REGION_EVENT:
                return new double[]{gx, gy + barY, gx + gw, gy + barY + barH};
            case REGION_TOAST:
                return errorToast.dragRect();
            case REGION_DETAIL: {
                if (tab != TAB_MARKET) {
                    return null;
                }
                layoutMarket(MarketClientState.INSTANCE);
                int mx0 = gx + mkX;
                int my0 = mkAbsY();
                return new double[]{mx0 + rightColOff, my0 + splitTop,
                        mx0 + rightColOff + rightColW, my0 + splitTop + splitH};
            }
            case REGION_LOAN: {
                // With a dialog open no loan drag region is produced; the drag viewport's bottom excludes the fixed button row.
                if (tab != TAB_LOAN || anyModalOpen()) {
                    return null;
                }
                int[] vp = loanViewport();
                return new double[]{vp[0], vp[1], vp[2], vp[3]};
            }
            case REGION_TIP: {
                if (eventTipPanelW <= 0) {
                    return null;
                }
                return new double[]{eventTipPanelX, eventTipPanelY,
                        eventTipPanelX + eventTipPanelW, eventTipPanelY + eventTipPanelH};
            }
            case REGION_MID: {
                if (!stacked) {
                    return null;
                }
                return new double[]{gx, gy + midY, gx + gw, gy + midY + midH};
            }
            case REGION_SHORT_OUTER:
            case REGION_ORDER_OUTER:
            case REGION_LIMIT_OUTER: {
                int top = tradeBoundsTop() + 4;
                return new double[]{tradeContentLeft(), top, tradeContentRight(), tradeContentBottom()};
            }
            case REGION_PICKER: {
                // Part 5: the dropdown panel uses the very same drag / scroll / anti-mistouch logic as the lists.
                if (!pickerOpen || !pickerPanelValid) {
                    return null;
                }
                return new double[]{pickerPanelRect[0], pickerPanelRect[1],
                        pickerPanelRect[2], pickerPanelRect[3]};
            }
            default:
                return null;
        }
    }

    /** Region hit priority: the topmost overlay first -> event bar -> each page's inner lists -> each page's outer -> the stacked whole-page fallback. */
    private int dragHitRegion(double mx, double my) {
        // overlays (error toast / event detail panel) sit above every list and are tested first;
        // where they overlap, paint layering decides -- the toast is topmost and takes drags first.
        for (int reg : new int[]{REGION_TOAST, REGION_TIP}) {
            double[] r = dragRegionRect(reg);
            if (r != null && mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                return reg;
            }
        }
        // Part 5: the dropdown panel floats above all lists -- it hits before the event bar and the page lists.
        if (pickerOpen && pickerPanelValid) {
            double[] pr = dragRegionRect(REGION_PICKER);
            if (pr != null && mx >= pr[0] && mx < pr[2] && my >= pr[1] && my < pr[3]) {
                return REGION_PICKER;
            }
        }
        double[] bar = dragRegionRect(REGION_EVENT);
        if (bar != null && mx >= bar[0] && mx < bar[2] && my >= bar[1] && my < bar[3]) {
            return REGION_EVENT;
        }
        // After the exchange moved into its tab only one page is visible at a time, so drag regions no longer stack REGION_EX.
        int[] candidates;
        if (tab == TAB_TRADE) {
            candidates = new int[]{REGION_SHORT, REGION_ORDER, REGION_LIMIT,
                    REGION_SHORT_OUTER, REGION_ORDER_OUTER, REGION_LIMIT_OUTER, REGION_RANK};
        } else if (tab == TAB_RANK) {
            candidates = new int[]{REGION_RANK};
        } else if (tab == TAB_LOG) {
            candidates = new int[]{REGION_LOG};
        } else if (tab == TAB_MARKET) {
            candidates = new int[]{REGION_MK, REGION_DETAIL};
        } else if (tab == TAB_LOAN) {
            candidates = new int[]{REGION_LOAN};
        } else if (tab == TAB_EXCH) {
            candidates = new int[]{REGION_EX};
        } else {
            candidates = new int[0];
        }
        for (int reg : candidates) {
            double[] r = dragRegionRect(reg);
            if (r == null || mx < r[0] || mx >= r[2] || my < r[1] || my >= r[3]) {
                continue;
            }
            if (reg == REGION_SHORT && tradeSub != 0) {
                continue;
            }
            if ((reg == REGION_ORDER || reg == REGION_ORDER_OUTER) && tradeSub != 1) {
                continue;
            }
            if ((reg == REGION_LIMIT || reg == REGION_LIMIT_OUTER) && tradeSub != 2) {
                continue;
            }
            if (reg == REGION_SHORT_OUTER && tradeSub != 0) {
                continue;
            }
            return reg;
        }
        // fallback: in stacked mode the whole page's vertical scroll region (checked only after the lists and exchange are ruled out)
        double[] mid = dragRegionRect(REGION_MID);
        if (mid != null && mx >= mid[0] && mx < mid[2] && my >= mid[1] && my < mid[3]) {
            return REGION_MID;
        }
        return -1;
    }

    /** Hot zone (dragging must never steal its click): the small in-row cancel button on limit rows. */
    private java.util.List<int[]> dragHotZones(int region) {
        java.util.List<int[]> zones = new ArrayList<>();
        if (region == REGION_EX) {
            // The row hot zones are gone -- any press inside the left column rect first arms the ready state:
            // moving > 3px drags the list, releasing within 3px replays an ordinary click and still selects the row (selection logic in handleClick).
            // re-introducing a full-row hot zone would make the list undraggable forever (the old dead-drag root cause).
            return zones;
        } else if (region == REGION_LIMIT) {
            MarketClientState st = MarketClientState.INSTANCE;
            int[] rect = orderListRect(true);
            int dataTop = rect[1] + 14;
            int[] c = limitColumns(tradeContentLeft(), tradeContentRight(),
                    limitHeaderLabel(rect[0], rect[2]));
            int h = limitRowH();
            int chipTop0 = limitTwoLine ? 16 : 0;
            int chipH = limitTwoLine ? h - 16 : h;
            // Part 2: hot zones intersect the clip viewport -- a cancel button on a half-clipped row stops answering presses
            int hotBot = nestedInnerBottom(dataTop, rect[3]);
            for (int i = 0; i < st.limitOrders.size(); i++) {
                int yy = dataTop + i * h - limitScroll;
                int zy1 = Math.max(dataTop, yy + chipTop0);
                int zy2 = Math.min(hotBot, yy + chipTop0 + chipH);
                if (zy2 - zy1 < 4) {
                    continue;                                   // less than 4px visible: this hot zone is not recognised
                }
                zones.add(new int[]{c[5] + 4, zy1, c[5] + 66, zy2});
            }
        }
        return zones;
    }

    /** Drag event: region drags mutate that region's own offset; everything else returns to the widgets (edit boxes selecting text). */
    @Override
    public boolean mouseDragged(double screenMouseX, double screenMouseY, int button,
                                double dragX, double dragY) {
        // While a dialog is open, drag events are swallowed as the first act -
        // the armed drag state may predate the dialog and must never scroll the background lists or start text selection in an edit box.
        if (anyModalOpen()) {
            cancelDragStates();
            return true;
        }
        updateUiTransform();
        double mouseX = (screenMouseX - offsetX) / uiScale;
        double mouseY = (screenMouseY - offsetY) / uiScale;
        int reg = armedRegion();
        if (reg >= 0 && button == 0) {
            // canvas coordinates converted through the GUI scaling throughout (physical pixels are banned).
            // Within the threshold the event is consumed but nothing scrolls; beyond 3px it upgrades to a drag that scrolls,
            // and every click-through path during a drag is cut here by return true.
            return dragMove(reg, mouseY);
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX / uiScale, dragY / uiScale);
    }

    /**
     * Release event: Parts 1/2 -
     * while a dialog exists it intercepts first: clicks outside the panel return true outright, inside passes only to the widget layer;
     * when a drag ends, dragRelease sets suppressNextClick so this release replays no click (guards against accidental row selects);
     * small movements under the 3px threshold count as ordinary clicks and replay the original click dispatch.
     */
    @Override
    public boolean mouseReleased(double screenMouseX, double screenMouseY, int button) {
        updateUiTransform();
        double mouseX = (screenMouseX - offsetX) / uiScale;
        double mouseY = (screenMouseY - offsetY) / uiScale;
        if (anyModalOpen()) {
            int[] modal = liquidateConfirmOpen ? modalPanelRect() : loanConfirmRect();
            boolean inModal = mouseX >= modal[0] && mouseX < modal[2]
                    && mouseY >= modal[1] && mouseY < modal[3];
            if (!inModal) {
                return true;
            }
            return super.mouseReleased(mouseX, mouseY, button);
        }
        int reg = armedRegion();
        if (reg >= 0) {
            DragState d = dragStates[reg];
            boolean wasDragging = d.isDragging;
            dragRelease(reg, mouseY);                     // reset armed / isDragging; after a real drag, set suppressNextClick
            if (wasDragging || d.suppressNextClick) {
                d.suppressNextClick = false;              // this release-click is the swallowed shot and never replays
                return true;
            }
            if (button == 0) {
                // press to release with no effective movement: that is an ordinary click, so replay the original dispatch
                // (row selection / self-drawn hot zones / vanilla widgets all funnel into the same handleClick entry).
                return handleClick(mouseX, mouseY, button);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * While a dialog is open, character input (chat-style typing -> EditBox) is intercepted as well:
     * the underlying edit boxes are hidden by now, yet focus may still sit on a hidden one, and letting keys through
     * would write the player's typing into a covered quantity field. Esc and shortcuts go through keyPressed.
     */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (anyModalOpen()) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // While a dialog exists only ESC and the open-market UI hotkey pass
        // (both serve to close the dialog); every other shortcut is intercepted on the first line and returns true --
        // background hotkeys / typing behaviour never fire. After closing, the underlying hover recomputes from the current mouse position.
        if (anyModalOpen()) {
            // Part 2: with a dialog open only ESC passes (closing it); all other shortcuts are intercepted
            if (keyCode == 256) {
                closeAllModals();
            }
            return true;
        }
        if (pickerOpen && keyCode == 256) {
            pickerOpen = false;
            return true;
        }
        if (MarketKeys.isHotkey(keyCode, scanCode)
                && !(getFocused() instanceof EditBox box && box.canConsumeInput())) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ================================================================ small helpers

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Profit colours: gain red 0xFFE04A4A, loss green 0xFF3FBF5F, flat grey 0xFFAAAAAA. */
    private static int pnlColor(double v) {
        return v > 0.005 ? PNL_UP : v < -0.005 ? PNL_DOWN : PNL_FLAT;
    }

    // ---- Part 1: dynamic font scaling + multi-line layout; ellipses and hiding are globally banned

    /** Font scaling ladder: starts at 1.0, steps down by 0.05, floor 0.65. Width measurement = font.width x scale. */
    private static final double FONT_MIN_SCALE = 0.65;
    /** Table cell extreme fallback: when even 0.65 overflows and the row may not grow, press on to the floor step (the last rung before nothing else remains). */
    private static final double FONT_FLOOR_SCALE = 0.45;

    /** Scaled actual advance (Part 1 item 3: every width decision uses the scaled value). */
    private int scaledW(String translated, double scale) {
        if (font == null || translated == null || translated.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(font.width(translated) * scale);
    }

    /**
     * Ladder scaling step: returns the smallest rung (1.0 / 0.95 / ... / 0.65) that fits the whole line into availW;
     * 0 when even 0.65 overflows, and the caller wraps or grows the row per the rules.
     */
    private double fitScale(String translated, int availW) {
        if (font == null || translated == null || translated.isEmpty()) {
            return 1.0;
        }
        if (availW <= 0) {
            return 0.0;
        }
        int base = font.width(translated);
        if (base <= availW) {
            return 1.0;
        }
        for (double s = 0.95; s >= FONT_MIN_SCALE - 1e-9; s -= 0.05) {
            if (base * s <= availW + 0.5) {
                return Math.round(s * 100.0) / 100.0;
            }
        }
        return 0.0;
    }

    /** Run the same ladder (1.0 -> 0.65) over a sum of segment widths; when still short, finish on the floor rung. */
    private double fitScaleW(int baseW, int availW) {
        if (availW <= 0 || baseW <= 0) {
            return 1.0;
        }
        if (baseW <= availW) {
            return 1.0;
        }
        for (double sc = 0.95; sc >= FONT_MIN_SCALE - 1e-9; sc -= 0.05) {
            if (baseW * sc <= availW + 0.5) {
                return Math.round(sc * 100.0) / 100.0;
            }
        }
        return Math.max(FONT_FLOOR_SCALE, availW / (double) baseW);
    }

    /** Fallback rung for cells that may not wrap: when 0.65 still overflows, press proportionally to the floor (smaller text is fine; ellipsis, overflow and hiding are not). */
    private double cellFitScale(String translated, int availW) {
        double s = fitScale(translated, availW);
        if (s > 0) {
            return s;
        }
        if (font == null || availW <= 0) {
            return FONT_FLOOR_SCALE;
        }
        int base = font.width(translated);
        if (base <= 0) {
            return 1.0;
        }
        return Math.max(FONT_FLOOR_SCALE, Math.min(FONT_MIN_SCALE, availW / (double) base));
    }

    /** Draw one text line scaled (poseStack scaling with the start point already on screen, so it self-corrects by scale). Returns the advance. */
    private int drawScaled(GuiGraphics g, String translated, int x, int y, double scale, int color) {
        if (translated == null || translated.isEmpty()) {
            return 0;
        }
        if (scale >= 0.999) {
            g.drawString(font, translated, x, y, color);
            return font.width(translated);
        }
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0F);
        pose.scale((float) scale, (float) scale, 1.0F);
        g.drawString(font, translated, 0, 0, color);
        pose.popPose();
        return scaledW(translated, scale);
    }

    /** // centred scaled drawing; cx is the centre x. */
    private int drawScaledCentered(GuiGraphics g, String translated, int cx, int y, double scale, int color) {
        int w = scaledW(translated, scale);
        return drawScaled(g, translated, cx - w / 2, y, scale, color);
    }

    /**
     * Adaptive single-line entry (left-aligned): run the scaling ladder first, then the floor rung;
     * never ellipsis, never hide, and the advance never exceeds availW (availW non-positive draws nothing).
     */
    private void drawFitLeft(GuiGraphics g, String rawOrTranslated, int x, int y, int availW, int color) {
        String t = L.tr(rawOrTranslated);
        if (t.isEmpty() || availW <= 0 || font == null) {
            return;
        }
        drawScaled(g, t, x, y, cellFitScale(t, availW), color);
    }

    /**
     * Hard fit scale: runs the standard ladder first (1.0 -> 0.65 -> 0.45 floor via cellFitScale) and, when even
     * the floor rung still overflows, keeps shrinking proportionally (0.01 steps down to a 0.05 guard) until the
     * measured advance really fits availW. Guarantees "whole text visible, never overlapping the next column"
     * for cells that must not wrap (the stock-list name cell).
     */
    private double hardFitScale(String translated, int availW) {
        if (font == null || translated == null || translated.isEmpty() || availW <= 0) {
            return 1.0;
        }
        double s = cellFitScale(translated, availW);
        if (scaledW(translated, s) <= availW) {
            return s;
        }
        int base = font.width(translated);
        if (base <= 0) {
            return 1.0;
        }
        s = Math.max(0.05, availW / (double) base);
        while (s > 0.05 && scaledW(translated, s) > availW) {
            s -= 0.01;
        }
        return s;
    }

    /**
     * Stock-list name cell entry: same contract as drawFitLeft (never ellipsis, never hide, left-aligned) but
     * WITHOUT the 0.45 floor -- an over-long name keeps shrinking until it fully fits the column, so in any
     * language (and for any player-made stock name) it can never spill over into the price column.
     */
    private void drawFitLeftFull(GuiGraphics g, String rawOrTranslated, int x, int y, int availW, int color) {
        String t = L.tr(rawOrTranslated);
        if (t.isEmpty() || availW <= 0 || font == null) {
            return;
        }
        drawScaled(g, t, x, y, hardFitScale(t, availW), color);
    }

    /** Same as above, right-aligned (x is the right edge). */
    private void drawFitRight(GuiGraphics g, String rawOrTranslated, int right, int y, int availW, int color) {
        String t = L.tr(rawOrTranslated);
        if (t.isEmpty() || availW <= 0 || font == null) {
            return;
        }
        double s = cellFitScale(t, availW);
        drawScaled(g, t, right - scaledW(t, s), y, s, color);
    }

    /**
     * Numeric cell fallback:
     * 1) extreme long numbers first take Num.compact smart abbreviation (the scientific 9.99E18 convention,
     * the numeric part never longer than 10 characters);
     * 2) still no fit -> walk the 1.0 -> 0.65 ladder with the same global rungs;
     * 3) still overflowing at 0.65 -> ellipsis truncation is allowed for digits only (every other UI text keeps the ban),
     * guaranteeing the cell never pierces neighbouring columns or covers buttons.
     */
    private void drawFitNumber(GuiGraphics g, String numeric, int right, int y, int availW, int color) {
        String t = cn.oremarket.common.Num.compact(numeric);
        if (t == null || t.isEmpty() || availW <= 0 || font == null) {
            return;
        }
        double s = fitScale(t, availW);
        if (s > 0) {
            drawScaled(g, t, right - scaledW(t, s), y, s, color);
            return;
        }
        String ell = "\u2026";
        String cut = t;
        while (cut.length() > 1 && scaledW(cut + ell, FONT_MIN_SCALE) > availW) {
            cut = cut.substring(0, cut.length() - 1);
        }
        String out = cut.length() < t.length() ? cut + ell : cut;
        drawScaled(g, out, right - scaledW(out, FONT_MIN_SCALE), y, FONT_MIN_SCALE, color);
    }

    /** Same as above, horizontally centred within [l, r]. */
    private void drawFitCentered(GuiGraphics g, String rawOrTranslated, int l, int r, int y, int color) {
        String t = L.tr(rawOrTranslated);
        if (t.isEmpty() || r <= l || font == null) {
            return;
        }
        double s = cellFitScale(t, r - l - 2);
        drawScaledCentered(g, t, (l + r) / 2, y, s, color);
    }

    /**
     * Part 7 item 2: single entry for section titles -- adaptive single line plus a 1px dark shadow line under the text (0xFF111111)
     * faking bold for depth. The shadow spans only the title's real advance and never passes availW.
     */
    private void drawSectionTitle(GuiGraphics g, String raw, int x, int y, int availW, int color) {
        String t = L.tr(raw);
        if (t.isEmpty() || availW <= 0 || font == null) {
            return;
        }
        double s = cellFitScale(t, availW);
        //: section titles no longer draw underlines (titles separate from body via panel borders and row backgrounds)
        drawScaled(g, t, x, y, s, color);
    }

    /**
     * Return the full text within the pixel budget -- this helper used to append ellipses; ellipses are globally banned.
     * The method survives only for compatibility: new code goes through drawFitLeft / drawFitRight / drawFitCentered
     * or wraps via wrapAllNoEllipsis and draws line by line. No caller may feed the result back into a narrow rect.
     */
    private String trimToWidth(String s, int max) {
        String t = L.tr(s);
        return t == null ? "" : t;
    }

    private List<String> wrapped(String text, int maxW) {
        return wrapAll(text, maxW);
    }

    private List<String> wrapAll(String text, int maxW) {
        List<String> out = new ArrayList<>();
        wrapAllNoEllipsis(text, Math.max(1, maxW), out);
        return out;
    }

    private String limitFlag(double chgPct, double limit) {
        double pct = limit * 100.0;
        if (chgPct >= pct - LIMIT_EPS) {
            return cn.oremarket.common.RawText.t("oremarket.text.0051");
        }
        if (chgPct <= -(pct - LIMIT_EPS)) {
            return cn.oremarket.common.RawText.t("oremarket.text.0068");
        }
        return null;
    }

    /** Right-aligned drawing: x is the column's right edge; when it does not fit, the scaling ladder backs it up; ellipses and overflow are banned. */
    private void drawRightAligned(GuiGraphics g, String text, int right, int y, int color) {
        String t = L.tr(text);
        if (t.isEmpty()) {
            return;
        }
        int avail = Math.max(1, right);
        double s = cellFitScale(t, avail);
        drawScaled(g, t, right - scaledW(t, s), y, s, color);
    }

    /**
     * Generic amount abbreviation (total assets, market value and other float conventions): always through L.money(double) --
     * Chinese uses the wan (10k) / yi (100m) / qianyi (100B) units, English K/M/B/T, so one number shows the same amount in both languages.
     */
    private String roundNum(double v) {
        return L.money(v);
    }

    /**
     * Guard four: coin balance display formatting (the value stays long end to end, never truncated to int midway).
     * Always through L.money(long): Chinese >= 1e8 -> yi, >= 1e4 -> wan (one decimal);
     * English >= 1e9 -> B, >= 1e6 -> M, >= 1e3 -> K (one decimal); 1.0 yi equals 100.0 M.
     * Negatives format on absolute value with the sign re-appended. Keeps long digits from colliding in the top bar.
     */
    private String formatOre(long v) {
        return L.money(v);
    }

    private boolean lockBlocked(int idx) {
        MarketClientState st = MarketClientState.INSTANCE;
        if (idx < 0) {
            ClientEvents.showToast(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0406")));
            return true;
        }
        if (!st.hasData || st.unlocked(idx)) {
            return false;
        }
        ClientEvents.showToast(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0182") + st.unlockHint(idx)));
        return true;
    }

    /**
     * Open the clear-all secondary confirmation dialog. It reuses the loan page's Modal scrim (0x99000000)
     * and opaque panel (0xFF2B2B2B); opening it closes any loan dialog so two layers never stack.
     */
    private void openLiquidateConfirm() {
        if (!hasAnySellable()) {
            ClientEvents.showToast(trKey("oremarket.text.0295"));
            return;
        }
        loanConfirmOpen = false;
        liquidateConfirmOpen = true;
        dismissFloatingPanels();   // Part 1: dialogs take priority; overlays must never paint above the scrim
        modalScroll = 0;                          // Part 1: every dialog open resets its inner scroll
        pickerOpen = false;
    }

    /** Whether at least one unlocked, non-frozen, non-suspended sellable holding exists (shared by the clear-all button and its pre-confirm check). */
    private boolean hasAnySellable() {
        MarketClientState st = MarketClientState.INSTANCE;
        int m = n();
        if (st.available == null || st.available.length < m) {
            return false;
        }
        for (int i = 0; i < m; i++) {
            if (st.available[i] > 0 && st.unlocked(i) && !st.suspended(i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Re-validate positions live after confirming the clear and before actually sending (guards against concurrent / stale data mis-selling).
     * While iterating, every index takes the min with the array length first, ruling out IndexOutOfBounds.
     */
    private void confirmLiquidateAll(Button btn) {
        MarketClientState st = MarketClientState.INSTANCE;
        if (!hasAnySellable()) {
            ClientEvents.showToast(trKey("oremarket.liquidate.stale"));
            closeAllModals();
            return;
        }
        int m = Math.min(n(), st.available == null ? 0 : st.available.length);
        boolean sent = false;
        for (int i = 0; i < m; i++) {
            if (st.available[i] > 0 && st.unlocked(i) && !st.suspended(i)) {
                String code = st.codeAt(i);
                if (code == null || code.isEmpty()) {
                    continue;
                }
                OreMarketNetwork.CHANNEL.sendToServer(new C2STradePacket(
                        code, C2STradePacket.QTY_ALL_AVAILABLE, false));
                sent = true;
            }
        }
        if (sent) {
            markSent(btn);
        }
        closeAllModals();                         // Part 1: the unified close path (reset scroll + recompute hover)
    }

    /** The clear-all confirmation dialog's two buttons (loan-dialog geometry: bottom of the centred opaque panel). */
    private void buildLiquidateConfirmButtons() {
        int[] r = modalPanelRect();
        int innerW = r[2] - r[0] - 16;
        int half = (innerW - 8) / 2;
        int by = r[3] - LOAN_BTN_H - 8;
        addBtn(Component.literal(trKey("oremarket.liquidate.confirm")),
                r[0] + 8, by, half, LOAN_BTN_H, M_CLEAR_CONFIRM,
                this::confirmLiquidateAll);
        addBtn(Component.literal(trKey("oremarket.liquidate.cancel")),
                r[0] + 8 + half + 8, by, half, LOAN_BTN_H, M_CLEAR_CANCEL,
                btn -> closeAllModals());          // Part 1: the unified close path
    }

    /**
     * The panel rect shared by the clear-all and loan confirmation dialogs (screen-centred, fully opaque).
     * Returns [x1, y1, x2, y2], one source for hit testing and drawing.
     */
    private int[] modalPanelRect() {
        int pw = Math.min(320, Math.max(220, gw - 80));
        int ph = 110;
        int px = gx + (gw - pw) / 2;
        int py = gy + Math.max(headerH + 8, (gh - ph) / 2);
        return new int[]{px, py, px + pw, py + ph};
    }

    /** Is any modal open (loan confirmation / clear-all confirmation)? While open, every button beneath swallows clicks. */
    private boolean anyModalOpen() {
        return loanConfirmOpen || liquidateConfirmOpen;
    }

    /**
     * Locate-position -- smooth-scroll the stock list to a stock currently held.
     * With several holdings, repeated clicks cycle through them; with one, it scrolls there directly; no holdings greys the button and this code never runs.
     * The animation rides the existing tickLinkedScroll (per-frame lerp; while loops and blocking loops are strictly banned).
     */
    private void locateNextHolding() {
        int[] holds = holdingIndices();
        if (holds.length == 0) {
            return;                                             // no holdings: the button is already greyed and this is a second belt
        }
        int next;
        if (holds.length == 1) {
            next = holds[0];
        } else {
            int cur = -1;
            if (locateCursorCode != null) {
                for (int i = 0; i < holds.length; i++) {
                    if (locateCursorCode.equals(MarketClientState.INSTANCE.codeAt(holds[i]))) {
                        cur = i;
                        break;
                    }
                }
            }
            next = holds[(cur + 1) % holds.length];
        }
        locateCursorCode = MarketClientState.INSTANCE.codeAt(next);
        // Locating must also select that stock -- the selectedIndex highlight follows (setSelected
        // fires the linking scroll itself); scrolling without highlighting counts as unfinished.
        // Pass playSound=false -- locating plays only the button's own click sound;
        // the selected stock's own selection sound no longer stacks (two overlapping sounds are the double-click noise players heard).
        setSelected(next, false);
        int target = targetMkScroll(next);
        if (target < 0) {
            return;
        }
        // targetMkScroll already guarantees the whole row lands inside the viewport (top/bottom alignment + 4px gap clamp) --
        // the highlighted row must end up visible; we still set the target explicitly here to cover the case where setSelected
        // skips the linking scroll because the selection did not change (the row must scroll in even while outside the viewport).
        mkScrollTarget = target;
        mkAnimOffset = mkScroll;                                // linear interpolation from the current position; the main thread never blocks
    }

    /** Indexes of stocks currently held (totalShares > 0), in list order. Empty yields length 0 and never returns null. */
    private int[] holdingIndices() {
        MarketClientState st = MarketClientState.INSTANCE;
        int m = n();
        if (m <= 0) {
            return new int[0];
        }
        int cap = Math.min(m, st.available == null ? 0 : st.available.length);
        int count = 0;
        for (int i = 0; i < cap; i++) {
            if (st.totalShares(i) > 0) {
                count++;
            }
        }
        int[] out = new int[count];
        int k = 0;
        for (int i = 0; i < cap; i++) {
            if (st.totalShares(i) > 0) {
                out[k++] = i;
            }
        }
        return out;
    }

    /**
     * The exchange's right-column widget (the custom quantity input box).
     * The box defaults to 0 and its responder admits digits only; parsing runs parseExQty (try-catch Integer.parseInt).
     * Part 4: the search box and button are removed wholesale and the freed space goes to the detail panel.
     */
    private void buildExchangeWidgets(MarketClientState st) {
        if (!exOpEnough) {
            return;
        }
        int[] qr = exQtyBoxRect();
        exQtyBox = new EditBox(font, qr[0], qr[1], qr[2], qr[3],
                Component.literal(trKey("oremarket.exch.qty_hint")));
        exQtyBox.setMaxLength(8);
        exQtyBox.setValue(exQtyText == null ? "0" : exQtyText);
        exQtyBox.setBordered(true);
        exQtyBox.setResponder(text -> {
            String raw = text == null ? "" : text;
            String clean = raw.replaceAll("[^0-9]", "");
            if (clean.length() > 8) {
                clean = clean.substring(0, 8);
            }
            if (!clean.equals(raw)) {
                exQtyBox.setValue(clean);
            }
            exQtyText = clean.isEmpty() ? "0" : clean;
        });
        addRenderableWidget(exQtyBox);
    }

    /**
     * Cut text to a pixel budget (no ellipsis, no shrinking; the excess drops by character),
     * for label-style places that cannot go through the drawFit ladder.
     */
    private String clipToWidth(String text, int avail) {
        if (font == null || avail <= 0 || text == null) {
            return text == null ? "" : text;
        }
        if (font.width(text) <= avail) {
            return text;
        }
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end)) > avail) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * Custom quantity parsing. Empty / over-long / non-numeric / negative all return 0,
     * Integer.parseInt stays inside try-catch, and a stray non-digit must never throw and crash the game.
     */
    private int parseExQty() {
        String text = exQtyText == null ? "" : exQtyText.trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            long v = Long.parseLong(text);
            if (v <= 0) {
                return 0;
            }
            return (int) Math.min(v, 6400L);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * The exchange's right-column deposit / withdraw. Every illegal action (empty, 0, negative, beyond inventory, nothing selected)
     * first toasts locally after parsing through parseExQty (try-catch), so non-digits can never crash.
     * Part 4: the search button and onExchangeSearch are removed wholesale (the space goes to the detail panel).
     */
    private void onExchangeOp(ExOpButton b) {
        if (b == null || !b.enabled || b.inDebounce()) {
            return;
        }
        // Deposit all / withdraw all -- the quantity input plays no part in these two buttons;
        // the client pre-check is instant feedback only (0 in inventory -> ErrorToast), and how much can actually move is decided by the
        // server's own inventory measurement -- trusting client estimates is strictly banned; execution always runs the server's authoritative logic.
        if (b.kind == ExOpButton.DEPOSIT_ALL || b.kind == ExOpButton.WITHDRAW_ALL) {
            MarketClientState stA = MarketClientState.INSTANCE;
            if (selected < 0 || selected >= n()) {
                ClientEvents.showToast(trKey("oremarket.exch.err.pick"));
                return;
            }
            if (selected >= stA.stocks.length) {
                return;
            }
            Stock sA = stA.stocks[selected];
            if (sA == null) {
                return;
            }
            String codeA = st0code(selected);
            if (codeA == null || codeA.isEmpty()) {
                ClientEvents.showToast(trKey("oremarket.exch.err.pick"));
                return;
            }
            if (b.kind == ExOpButton.DEPOSIT_ALL) {
                if (countLocal(sA) <= 0) {
                    // this ore is not in the inventory at all: a whole-row deposit would be empty -> ErrorToast, never send a pointless request
                    ClientEvents.showToast(trKey("oremarket.exch.err.bag_empty"));
                    return;
                }
                OreMarketNetwork.CHANNEL.sendToServer(new C2SExchangePacket(
                        codeA, C2SExchangePacket.DEPOSIT_ALL_ROW, 0));
            } else {
                // withdraw all: the server's Inventory.add simulation knows exactly how much fits and prevents overflow loss;
                // when fewer come out than asked, the reply says the inventory was full and only X came back, and pushes a fresh snapshot to refresh the client.
                OreMarketNetwork.CHANNEL.sendToServer(new C2SExchangePacket(
                        codeA, C2SExchangePacket.WITHDRAW_ALL, 0));
            }
            b.pressUntil = ClientEvents.clientTick() + 6;
            long nowA = System.currentTimeMillis();
            b.disabledAtMs = nowA;
            exDebounceAt.put(exDebounceKey(b.kind), nowA);
            return;
        }
        MarketClientState st = MarketClientState.INSTANCE;
        if (selected < 0 || selected >= n()) {
            ClientEvents.showToast(trKey("oremarket.exch.err.pick"));
            return;
        }
        if (selected >= st.stocks.length) {
            return;
        }
        Stock s = st.stocks[selected];
        if (s == null) {
            return;
        }
        int qty = parseExQty();
        if (qty <= 0) {
            ClientEvents.showToast(trKey("oremarket.error.quantity_must_be_positive"));
            return;
        }
        String code = st0code(selected);
        if (code == null || code.isEmpty()) {
            ClientEvents.showToast(trKey("oremarket.exch.err.pick"));
            return;
        }
        if (b.kind == ExOpButton.DEPOSIT) {
            int have = countLocal(s);
            if (have <= 0) {
                ClientEvents.showToast(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0332")));
                return;
            }
            if (qty > have) {
                ClientEvents.showToast(trKey("oremarket.exch.err.over_bag", String.valueOf(have)));
                return;
            }
            OreMarketNetwork.CHANNEL.sendToServer(new C2SExchangePacket(
                    code, C2SExchangePacket.DEPOSIT_AMOUNT, qty));
        } else {
            long rate = Math.max(1, s.exchangeRate());
            long max = st.balance / rate;
            if (max < 1) {
                ClientEvents.showToast(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0185")));
                return;
            }
            if (qty > max) {
                ClientEvents.showToast(trKey("oremarket.exch.err.over_coins", L.money(st.balance)));
                return;
            }
            OreMarketNetwork.CHANNEL.sendToServer(new C2SExchangePacket(
                    code, C2SExchangePacket.WITHDRAW_AMOUNT, qty));
        }
        long now = System.currentTimeMillis();
        b.pressUntil = ClientEvents.clientTick() + 6;
        b.disabledAtMs = now;
        exDebounceAt.put(exDebounceKey(b.kind), now);
    }

    /** Seven: the 200ms debounce window starts only after the trading C2S packet truly goes out (intercepted clicks do not count). */
    private void sendTrade(Button btn, int qty, boolean buy) {
        if (lockBlocked(selected)) {
            return;
        }
        // race protection: while the stock list swaps dynamically / a snapshot expires, selectedCode may be blank,
        // and writeUtf(null) would throw an NPE on the network thread; read it, check it, and skip the send when empty.
        String code = selectedCodeSafe();
        if (code == null) {
            ClientEvents.showToast(L.tr(cn.oremarket.common.RawText.t("oremarket.text.0406")));
            return;
        }
        OreMarketNetwork.CHANNEL.sendToServer(new C2STradePacket(code, qty, buy));
        markSent(btn);
    }

    private int countLocal(Stock s) {
        if (this.minecraft.player == null) {
            return 0;
        }
        int count = 0;
        var inv = this.minecraft.player.getInventory();
        // The server counts exactly the same -- only the main inventory's 36 slots (getContainerSize=41 already includes the off-hand;
        // the old code double-counted the off-hand stack, so figures like 2304 in inventory on screen were inflated to begin with)
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(s.item())) {
                count += st.getCount();
            }
        }
        ItemStack off = this.minecraft.player.getOffhandItem();
        if (off.is(s.item())) {
            count += off.getCount();
        }
        return count;
    }

    /**
     * Bottom red notice: one line at a time, newest replacing the oldest, floating centred above the event bar.
     * position = horizontally centred under the root container, its bottom 8px above the event bar's edge; width adapts to the text (60 .. root width - 40),
     * over-long content wraps without truncation; a 2-second progress bar with 200ms fade in/out; the panel scrolls internally when content outgrows it.
     * Every action button (buy/sell/short/order/limit/exchange/loan) routes both local validation failures and server refusals through this path.
     */
    private void drawToast(GuiGraphics g) {
        ClientEvents.drainToasts(errorToast, font, gw - 40);
        if (!errorToast.active()) {
            return;
        }
        errorToast.render(g, font, gx, gy, gw, gy + barY, screenHeight, -1, -1,
                uiScale, offsetX, offsetY);
    }
}
