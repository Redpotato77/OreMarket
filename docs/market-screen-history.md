# MarketScreen - historical UI design notes

Moved out of the class javadoc of `src/main/java/cn/oremarket/client/MarketScreen.java` so the
source file keeps only the current responsibilities and hard invariants. This text documents
why the screen is laid out the way it is; it is reference material, not executable behaviour.

## Original class-level design notes (verbatim)

```text
/**
Main screen of the ore stock market (the price chart is now a real
candlestick (K-line) chart; the Quotes tab is split into a left and a right column with a
position details panel, and a third tab (Log) was added; every listed stock has an
fixed exchange rate in the right column of each Exchange row; the server is authoritative, this screen only renders and
sends packets).

Two-level structure:
 - Top bar: title / "Trading day N" / hotkey hints. The right-hand side shows the coin
   balance and the level (never hidden), plus the total P/L and total assets sent by the
   server. When space runs short, items are hidden in this order: selected stock name ->
   total assets -> total P/L -> loan status -> historical peak.
 - Middle area: Exchange container + Market container. Side by side when there is enough
   width; on narrow screens they stack vertically and the middle area scrolls vertically
   (Exchange fixed at 470, Market fixed at 240, gap 4).
 - Each Exchange row is 20 px tall with a 2 px gap and four right-aligned columns:
   18 px item icon, ore name (truncated with an ellipsis when necessary), a 30 px
   inventory count and an 86 px fixed exchange-rate column. Actions live in the
   right-hand detail column; the rate is supplied by the server state (built from
   StockRegistry config), never derived from the fluctuating quote.
   The list scrolls vertically when needed behind a 4 px overlay scrollbar, so the
   scrollbar does not compress row content. Locked ores still show their configured rate.
 - Market container = tab bar (the three tabs "Quotes / Loans / Log" split it into equal
   thirds, 20 px tall and never compressed; the whole rectangle is the click area;
   selected 0xFF3A3A5A, unselected 0xFF2A2A2A, unselected while hovered 0xFF404040, and
   the selected state wins over hover) + content area. Switching tabs only affects the
   content area; each tab keeps its own scroll position and the log content is never
   cleared by a tab switch;
   Quotes tab = candlestick chart + two columns (left stock list 60% / right position
   details panel 40%, gap 4, 2 px inner padding each, equal height) + a two-row trading
   button area (44 px tall, always pinned to the bottom). Available list height =
   container height - tab bar 20 - chart minimum 40 - button area 44 - the two gaps 8
   (clamped to 0 when negative, so no rows are rendered at all; "Not enough space" when
   below 16); the whole section is clipped with enableScissor inside try/finally and only
   fully visible rows are drawn; the scrollbar is a 4 px overlay (shown on demand, takes
   no width from the content, drawn inside the clipped area); the button area never moves
   out of the way, overflow is solved by scissor alone;
   Loans tab = the loan panel with a fixed height of about 92;
Log tab = the log area only (the position overview block together with
   its header, data rows, separator line and the "No open positions" hint was deleted as
   a whole, no overview height is reserved at the top any more; stock and position
   information lives in the right column of the Quotes tab).
   A single log block fills the content area: top = content top + 4, bottom = content
   bottom - 4, first log line y = content top + 4; text wraps at W = log width - 4 px
   padding on each side - 4 px for the scrollbar, one entry is 12 px tall (9 px per
   wrapped line), entries are 2 px apart, with a dynamic total height + wrap cache +
   render snapshot; newest entry on top, at most 100 entries, wrapping never uses an
   ellipsis;
   the scroll wheel only takes effect while the mouse is inside the log rectangle, with a
   step of 20 and the offset clamped to 0..max(total content height - log height, 0);
   no scrollbar / badge / triangle is drawn; while not scrolled to the bottom a 1 px dark
   line 0xFF2A2A2A is drawn 4 pixels above the bottom edge of the log area;
   when content height - 8 < 48 the whole page shows "Not enough space";
- The event bar is pinned to the bottom (clarified: the live event bar is
   never deleted and never moved into the Log tab; it coexists with the historical event
   records of the Log tab without conflicting). The red hint at the bottom floats centred
   above the event bar, and only one is visible at a time. A failed-trade hint is still
red text at the bottom for 2 seconds and is not written to the log.
- Trade / Leaderboard tab content area = top y at
   the lower edge of the sub-tab bar + 4, bottom y at the upper edge of the event bar - 4
   (whichever of that and container bottom - 4 sits higher), height = bottom - top, so the
   area shrinks along with the event bar every frame when that bar grows by wrapping;
   vertical scrolling starts as soon as the content is taller than the area (scrollbar
   flush with the right side of the content area, 6 px wide) and the whole page is clipped
   with enableScissor to [content top, content bottom], so overflow is clipped instead of
   drawn; buttons and input boxes inside the page (and the Loans tab, same rule this
   revision) use pixel-level clipping + scissor - a widget stays interactive as long as it
   shares at least 1 px with the content area, whatever sticks out is cut by the
   enableScissor inside the widget's render using the very same viewport rectangle, the
   hit-area follows the clipping and a widget is only hidden once it has scrolled out
   completely. Short Selling content, headers, data rows and widgets therefore can never
   be covered by the event bar, nor cover the event bar themselves.
   The first row of the Short Selling page is the top note block (y = content top + 4,
   wrapped per character when too wide, line height 9, not glued to the sub-tab bar);
   below it come, in order: the stock row, the short pool row (short pool remaining:
X / Y shares - the four short-quota rows were deleted and the
   pool is shown purely in shares now: white above 50%, yellow above 0 and below 20%, red
   at 0, which also greys out the "Open Position / Short All" buttons), the form row, the
   button row (Open Position / Short All / Close Half / Close Selected / Close All, five
   buttons in one row on wide screens and stacked vertically on narrow ones) and the
   position table;
   the position table has seven columns: Stock (available width - 382 - 12, minimum 60) /
   Quantity 40 / Entry Price 60 / Locked Margin 72 / Current Price 60 / Unrealized P/L 70
   / Liquidation Price 78; text inside a cell must be narrower than the column width - 4
   or it is truncated with an ellipsis; the margin amount is right-aligned to
   content right edge - 8 with a text budget of 280 (falls back to "Margin: X", and to the
   bare number when even that does not fit).
- Global clipping hardening, which settles every UI overflow in one pass:
   main content area / each sub-page content area / nested lists / the Exchange container
   / the event bar / the top bar / the hint strip - every container calls
   enableScissor(left, top, right, bottom) before rendering (in GUI-scaled coordinates,
   the same space the drawing uses), draws inside a try block and calls disableScissor in
   finally; GuiGraphics keeps its own scissor stack, so nested containers push from the
   outside in (automatically intersected with the parent rectangle) and pop from the inside
   out, which means the clipping can never go out of sync.
   The bottom of the short position list rectangle is always clamped to the bottom of the
   content area (event bar top - 4); list height = max(30, min(header 14 + count*16,
   200)); the 14 px header is never squashed, and at full scroll the last row is still
   completely visible;
   the 8 px margin (4 top + 4 bottom) is deducted exactly once from the available height
   when computing the scroll limit, and the old +8 tolerance is gone (no double deduction).
   Limit Order page form: side selector, price input, quantity input, Submit and Cancel
   chain from left to right on one row (element x = previous x + previous width + 4); when
   they do not fit the input boxes are compressed in steps (60 -> 50 -> at least 24), and
   only if that still does not fit do the input boxes move to a second row while
   Submit/Cancel stay at the right end of the first row; in no mode do widgets overlap or
   leave the content area.

Candlestick chart (rewritten from scratch; polylines, fills and dots are all
forbidden): the Y-axis scale strip is 40 px wide, inner padding 6 px left and right and
6 px top and bottom, X-axis height 12 px; the plot area starts at container x + 6 + 40 and
ends at container x + W - 6, so its width is W - 40 - 12;
topY = container y + 6, bottomY = container y + H - 12 - 6, height = bottomY - topY.
The slot width is derived from the plot width (not the container width); at most 80
candles, newest at the far right, the left side staying empty when there are fewer.
yMax = maxHigh*1.05 and yMin = minLow*0.95 (shifted by +/-1 first when they are equal);
the highest price is never taken as yMax directly;
screenY = topY + height - (price - yMin)/(yMax - yMin)*height.
Four horizontal grid lines 0xFF333333 (yMax / -0.25R / -0.5R / -0.75R) plus three vertical
grid lines, drawn only inside the plot area;
Y-axis price labels are right-aligned with 2 decimals in colour 0xFF888888; when they
cannot all fit vertically, labels that would overlap each other are skipped automatically.
Body width = slot*0.6 (minimum 3, maximum 8), rising red 0xFFE04A4A / falling green
0xFF3FBF5F / unchanged grey 0xFFAAAAAA, with a 1 px outline 0xFF1A1A1A on the outside; the
wick is 1 px and centred, rising 0xFFCC3A3A / falling 0xFF2F9F4F / unchanged 0xFF888888.
The current unfinished candle is always drawn at the far right; when klineHistory is empty
and there is no unfinished candle either, "Not enough data, waiting for more" appears in
the centre; below a chart height of 60 px neither candles nor grid lines are drawn and only
the latest price and the change percentage are shown centred.

The position details in the right column (position / average cost / current price /
unrealized P/L / position value / realized P/L) come entirely from the averageCost /
realizedPnl synchronised by the server; the client performs no local calculation.
Without a position it shows "--" or "No open positions" and never a number; only your own
data is displayed (this packet is sent to the recipient alone).
The position overview block of the Log tab is gone, and position details
live only in the right column of the Quotes tab.

Scroll-wheel ownership: event bar / tab bar (does not scroll) / left column of Quotes /
right column of Quotes / Loans page / log area (single block) / Exchange / middle area
(narrow screens) - exactly one container reacts at any moment.

Time presentation: internally everything is counted in ticks / marketTicks, but every
player-facing string is converted into seconds / minutes / trading days (in-game days);
T+1 is worded as "sellable the next day".
/
```
