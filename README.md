# OreMarket

A small, server-authoritative stock market for Minecraft ores. Trade, watch prices move, take loans, and decide how much risk you want to carry.

## Features

- Market GUI with quotes, candlestick charts, positions, orders, loans, and trade logs
- Eight built-in ore markets, with configurable prices, exchange rates, unlock levels, fees, and time settings
- Market orders, limit orders, and short positions
- System loans and optional player-to-player lending
- Random market events with different rarities and effects
- Persistent player balances, holdings, loans, and market data
- Optional custom stocks through the server configuration
- English and Chinese translations

## Requirements

- Minecraft **1.20.1**
- **Forge 47.4.23** or newer Forge 47.x build compatible with 1.20.1
- Java **17**

In multiplayer, install the mod on both the server and every client. In single-player, install it in the client instance.

## Installation

1. Install Minecraft Forge for 1.20.1.
2. Put the OreMarket `.jar` in the `mods` folder.
3. Start the game once to generate `config/oremarket.toml`.
4. In multiplayer, make sure the server and clients use the same mod version.

## Getting started

Press **Ctrl+G** by default to open or close the market. The key can be changed in **Options → Controls → Key Binds → Miscellaneous**.

Useful commands:

- `/oremarket open` — open the market
- `/oremarket balance` — view your coins and holdings
- `/oremarket price <stock>` — view a stock quote
- `/oremarket config show` — view the active market settings

The market is configured in `config/oremarket.toml`. Server settings are authoritative in multiplayer, so client-side values do not override the server.

### Rising and falling colours

`config/oremarket.toml` → `[uiTheme].colorScheme` chooses the convention:

- `RED_UP` (default): red means rising or profit, green means falling or loss (A-share style).
- `GREEN_UP`: green means rising or profit, red means falling or loss (US-stock style).

The server pushes the choice to every client, so the whole market palette stays consistent. The switch repaints quote changes, P&L, candle bodies and wicks, order-book and limit-order direction labels, event tone bars, and `/oremarket price` chat output. Warning, error and rarity colours never move.

The trade-log prefixes have their own switch in `config/oremarket.toml` → `[uiTheme].logColorScheme`:

- `BUY_GREEN` (default): 买入绿、卖出红
- `BUY_RED`: 买入红、卖出绿
- `FOLLOW_MARKET`: 日志买卖前缀跟随上面的 `colorScheme`

`[uiTheme].logBuy` and `[uiTheme].logSell` customize the two fixed log colours; they are ignored by `FOLLOW_MARKET`. The server sends both settings to clients. Save the file and run `/oremarket config reload` as an operator to apply changes without restarting.

## Notes

This is a beta release. The economy is deliberately configurable; server owners should review prices, fees, loan settings, and event frequency before starting a long-running world.

The project is licensed under **GPL-3.0-or-later**. See [LICENSE](LICENSE).
