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

package cn.oremarket.market;

import cn.oremarket.config.OreMarketConfig;
import cn.oremarket.market.event.MarketModifiers;
import cn.oremarket.market.log.LogService;
import cn.oremarket.common.LogType;
import cn.oremarket.net.OreMarketNetwork;
import cn.oremarket.net.packet.C2STradePacket;
import cn.oremarket.net.packet.S2CFeedbackPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import cn.oremarket.common.Fees;
import cn.oremarket.common.MarketTime;
import cn.oremarket.common.Num;
import cn.oremarket.common.StockState;
import java.util.Locale;

/**
 * Exchange and trading, all executed server-side; the client only displays and sends packets:
 * - deposits validate the real inventory quantity; withdrawals validate the coin balance; buys validate the balance, sells validate the sellable position;
 * - ore coins live only in SavedData as Map&lt;UUID,Long&gt;: they are not an item, cannot drop, cannot enter containers;
 * - stock level gates both the market and the exchange: locked tiers are refused (server-side re-validation, so tampered packets are useless);
 * - T+1: shares bought today go into a frozen bucket and cannot be sold same day; they unfreeze at the trading-day close
 * (every 400 marketTicks = 20 minutes). Coins received from a sale may be re-spent the same day; the T+1 rule does not bind coins
 * (sold today means the shares bought today may go out tomorrow);
 * - one-click buy/sell (negative qty markers) is re-computed server-side from the current price, fees and holdings;
 * - the exchange rate is fixed and never follows share prices;
 * - the trade fee defaults to 0.5%, with a sqrt(quantity) price impact (impact factor decided by the price mode);
 * - buys record a weighted-average cost on the amount actually paid; sells accumulate realised P&L as coins-in-hand minus qty x cost.
 * On the first UI open the network layer sends a chat hint, deduplicated by player NBT key oremarket:hint_shown.
 */
public final class TradeService {
    /** Cap used when the server recomputes a one-click buy quantity, to prevent overflow. */
    /**
     * The single-trade quantity cap now comes from the maxTradeQuantity config entry (category 22).
     * Kept as documentation of the old constant: default 1_000_000.
     */

    private TradeService() {
    }

    // ------------------------------------------------------------- exchange

    /**
     * Deposit (sell ores for coins; amount <= 0 means deposit everything).
     * Rewritten (coin-loss fix, deposit side):
     * 1. playerHas = the true quantity of that ore read live from the player inventory (main 36 slots + offhand);
     * 2. playerHas <= 0 -> tell the player the item is not in the inventory and return; nothing is deducted or added;
     * 3. items are removed first, and coins are paid on the ACTUALLY REMOVED count (removeItem's return value) --
     * when fewer items than requested are removed, only the removed count is paid; never inflate on the request;
     * 4. gain = removed count x rate, saturated long addition throughout (addBalance clamps negatives internally); no overflow.
     */
    public static void deposit(ServerPlayer player, Stock stock, int amount) {
        MinecraftServer server = player.server;
        if (server == null) {
            return;
        }
        if (stock == null || stock.retired() || stock.item() == null) {
            feedbackKey(player, "oremarket.trade.err.invalid_stock", false);
            return;
        }
        MarketSavedData data = MarketSavedData.get(server);
        if (!requireUnlocked(player, data, stock, "oremarket.trade.err.ore_locked")) {
            return;
        }
        int playerHas = countItem(player, stock.item());
        int want = amount <= 0 ? playerHas : Math.min(amount, playerHas);
        if (want <= 0) {
            feedbackKey(player, "oremarket.trade.err.no_deposit", false, stock.displayName());
            return;
        }
        int removed = removeItem(player, stock.item(), want);
        if (removed <= 0) {
            feedbackKey(player, "oremarket.trade.err.no_deposit", false, stock.displayName());
            return;
        }
        long gain = (long) removed * Math.max(1L, stock.exchangeRate());
        data.addBalance(player.getUUID(), gain);
        // Appendix 7: a successful exchange no longer sends chat; it writes to the log tab (exchange = blue type)
        // Logs carry only a translation key + parameters, and the UI fetches the template in the current language by key.
        LogService.pushKey(player, data, LogType.EXCHANGE,
                "oremarket.exchange.log.deposit", removed, stock.displayName(), gain);
        feedbackKey(player, "oremarket.exchange.log.deposit", true,
                String.valueOf(removed), stock.displayName(), String.valueOf(gain));
        MarketEngine.pushAfterAction(server, player);
    }

    /**
     * Withdraw by quantity (spend coins to buy ores); amount <= 0 is treated as 1 (defensive against malformed packets).
     * Rewritten (coin-loss fix, fixed-quantity withdrawal side) -- every core formula is recomputed server-side:
     * qty = min(requested, real free inventory space, spendable coins / rate);
     * - hard pre-deduction checks: balance >= cost AND own coins (excluding the loan funds pocket) >= cost;
     * failing either, nothing moves and the request is refused (spendOwnCoinsIfEnough does the check + spend atomically);
     * - delivery goes through giveItemsSafe (manual slot by slot; never the vanilla Inventory.add, which in creative mode
     * with a full inventory swallows the stack and returns success -- the very root cause that once drained coins to 1);
     * - when fewer items are delivered than were paid for, the difference is refunded on the spot; the saturated
     * arithmetic guarantees the remaining balance never goes below 0.
     */
    public static void withdraw(ServerPlayer player, Stock stock, int amount) {
        MinecraftServer server = player.server;
        if (server == null) {
            return;
        }
        if (stock == null || stock.retired() || stock.item() == null) {
            feedbackKey(player, "oremarket.trade.err.invalid_stock", false);
            return;
        }
        int want = amount <= 0 ? 1 : amount;
        MarketSavedData data = MarketSavedData.get(server);
        if (!requireUnlocked(player, data, stock, "oremarket.trade.err.ore_locked")) {
            return;
        }
        long rate = Math.max(1L, stock.exchangeRate());
        // Server-side re-validation: clamp the quantity to real inventory space first, then to the spendable balance
        int free = freeSpaceFor(player, stock.item());
        long freeOfLoan = LoanService.exchangeableCoins(data, player.getUUID());
        long own = LoanService.withdrawableCoins(data, player.getUUID());
        long affordable = own / rate;
        int qty = (int) Math.min(Math.min((long) want, (long) free), affordable);
        if (qty <= 0) {
            long needCost = (long) want * rate;
            if (affordable <= 0 && freeOfLoan <= 0 && data.balance(player.getUUID()) >= needCost) {
                // Balance is enough but the surplus sits entirely in the borrowed-but-unspent coin pocket: loan money must not be converted into ores (anti-arbitrage)
                feedbackKey(player, "oremarket.loan.err.ore_locked", false,
                        String.valueOf(LoanService.lockedLoanFunds(data, player.getUUID())),
                        String.valueOf(needCost));
            } else if (affordable <= 0 && freeOfLoan > 0 && data.lockedCoins(player.getUUID()) > 0) {
                // A resting order has pledged the otherwise exchangeable coins; cancelling it releases the reservation.
                feedbackKey(player, "oremarket.trade.err.orders_locked", false,
                        String.valueOf(freeOfLoan),
                        String.valueOf(data.lockedCoins(player.getUUID())));
            } else if (affordable <= 0) {
                feedbackKey(player, "oremarket.trade.err.withdraw_short", false,
                        String.valueOf(want), stock.displayName(), String.valueOf(needCost));
            } else {
                feedbackKey(player, "oremarket.exch.err.bag_full", false);
            }
            return;
        }
        long cost = (long) qty * rate;
        // Atomic pre-deduction validation: insufficient balance or insufficient own (non-loan) funds are both refused, and the balance can never go negative
        if (!data.spendOwnCoinsIfEnough(player.getUUID(), cost)) {
            feedbackKey(player, "oremarket.trade.err.withdraw_short", false,
                    String.valueOf(qty), stock.displayName(), String.valueOf(cost));
            return;
        }
        int given = giveItemsSafe(player, stock.item(), qty);
        if (given < qty) {
            // Last-resort guard: space calculation and delivery use the same tick and the same rules, so a difference should be impossible; still refund it on the spot -- coins are never taken for nothing
            data.addBalance(player.getUUID(), (long) (qty - given) * rate);
            cost = (long) given * rate;
        }
        if (given <= 0) {
            feedbackKey(player, "oremarket.exch.err.bag_full", false);
            MarketEngine.pushAfterAction(server, player);
            return;
        }
        // Appendix 7: a successful withdrawal writes a log instead of chat (every number comes from the server's actually delivered count given)
        LogService.pushKey(player, data, LogType.EXCHANGE,
                "oremarket.exchange.log.withdraw", given, stock.displayName(), cost);
        if (given < want) {
            // The message shows the number actually placed into the inventory -- never the leftover space, never the requested number
            feedbackKey(player, "oremarket.exch.withdraw_partial", false, String.valueOf(given));
        } else {
            feedbackKey(player, "oremarket.exchange.log.withdraw", true,
                    String.valueOf(given), stock.displayName(), String.valueOf(cost));
        }
        MarketEngine.pushAfterAction(server, player);
    }

    // ------------------------------------------------------------- withdraw all

    /**
     * Withdraw everything (spend coins to buy ores, taking the maximum of what you can afford AND can carry).
     * Rewritten (coin-loss fix, withdraw-all side) -- strict mathematical flow:
     * coins        = spendable ore coins (balance - unspent loan funds; loan money may not become ores);
     * maxAffordable = coins / rate (integer division; the remainder cannot buy even one item);
     * freeSpace    = real insertable count computed slot by slot (see {@link #freeSpaceFor});
     * actualBuy    = min(maxAffordable, freeSpace); <= 0 -> tell the player coins or space are insufficient;
     * spend coins = balance - actualBuy x rate (a SUBTRACTION, never an overwrite;
     * spendOwnCoinsIfEnough validates affordability atomically before spending, and the result is always >= 0);
     * items are delivered through giveItemsSafe (safe slot-by-slot insertion, stopping when full, zero loss);
     * delivered < paid-for count -> refund the difference on the spot.
     * Root cause of the old version: simulateInventoryFit probed with a temporary Inventory via vanilla Inventory.add,
     * and vanilla add zeroes the probe stack and returns success when the player is in instant-build (creative) mode with a
     * full inventory -- space was over-reported to infinity, the server charged coins for the WHOLE balance, while only
     * 36 x 64 = 2304 items actually landed, and the refund branch (given &lt; qty) never fired under the same stack-swallowing
     * semantics; 107k coins were left as 1. Removed that entire path.
     */
    public static void withdrawAll(ServerPlayer player, Stock stock) {
        MinecraftServer server = player.server;
        if (server == null) {
            return;
        }
        if (stock == null || stock.retired() || stock.item() == null) {
            feedbackKey(player, "oremarket.trade.err.invalid_stock", false);
            return;
        }
        MarketSavedData data = MarketSavedData.get(server);
        if (!requireUnlocked(player, data, stock, "oremarket.trade.err.ore_locked")) {
            return;
        }
        long rate = Math.max(1L, stock.exchangeRate());
        long coins = LoanService.withdrawableCoins(data, player.getUUID());
        long maxAffordable = coins / rate;                       // Maximum purchasing power (items)
        int freeSpace = freeSpaceFor(player, stock.item());      // Real inventory space (items)
        long takeL = Math.min(maxAffordable, freeSpace);
        if (takeL <= 0) {
            // Boundary defence: coins or space insufficient, nothing moves
            feedbackKey(player, "oremarket.exch.err.nothing_to_take", false);
            return;
        }
        int actualBuy = (int) Math.min(Integer.MAX_VALUE, takeL);
        long cost = (long) actualBuy * rate;
        // Server-side hard check: before spending, the balance must be >= cost (atomic validation + spend; rules out concurrent packet tampering and negatives)
        if (!data.spendOwnCoinsIfEnough(player.getUUID(), cost)) {
            feedbackKey(player, "oremarket.trade.err.withdraw_short", false,
                    String.valueOf(actualBuy), stock.displayName(), String.valueOf(cost));
            return;
        }
        int given = giveItemsSafe(player, stock.item(), actualBuy);
        if (given < actualBuy) {
            // Theoretically unreachable (single-threaded per tick, space math and delivery share one rule), yet still refund any difference as a guard
            data.addBalance(player.getUUID(), (long) (actualBuy - given) * rate);
        }
        long paid = (long) given * rate;
        LogService.pushKey(player, data, LogType.EXCHANGE,
                "oremarket.exchange.log.withdraw", given, stock.displayName(), paid);
        // Only say space was insufficient when delivered < what the balance could afford in total; the number equals the server's delivered count
        if (given < maxAffordable) {
            feedbackKey(player, "oremarket.exch.withdraw_partial", false, String.valueOf(given));
        } else {
            feedbackKey(player, "oremarket.exchange.log.withdraw", true,
                    String.valueOf(given), stock.displayName(), String.valueOf(paid));
        }
        MarketEngine.pushAfterAction(server, player);
    }

    /**
     * Real insertable count of this item in the inventory -- computed manually slot by slot, NEVER via vanilla Inventory.add.
     * Vanilla add zeroes a whole stack and returns success in instant-build (creative) mode with a full inventory, so the
     * previous simulate-insert probe over-reported space -- the root cause of this coin-loss incident.
     * The rule matches {@link #giveItemsSafe} exactly: main inventory 36 slots only; an empty slot = one max stack, a
     * partial stack of the same item and NBT = its remaining capacity; armour slots and the offhand do not count
     * (delivery never touches them either, so what is counted is exactly what fits, and nothing is ever lost).
     */
    private static int freeSpaceFor(ServerPlayer player, net.minecraft.world.item.Item item) {
        if (player == null || item == null) {
            return 0;
        }
        int maxStack = Math.max(1, item.getMaxStackSize());
        ItemStack template = new ItemStack(item);
        int free = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) {
                free += maxStack;
            } else if (st.getCount() < maxStack && ItemStack.isSameItemSameTags(st, template)) {
                free += maxStack - st.getCount();
            }
        }
        return free;
    }

    /**
     * Manual slot-by-slot safe delivery: top up partial stacks of the same item and NBT first, then take empty slots; stop when full.
     * Zero ground loss, no creative stack-swallowing; returns the ACTUAL number of items inserted -- callers settle and refund on that count.
     * Afterwards broadcastChanges so the client inventory shows the result immediately (list quantities and the coin header bar refresh with it).
     */
    private static int giveItemsSafe(ServerPlayer player, net.minecraft.world.item.Item item, int qty) {
        if (player == null || item == null || qty <= 0) {
            return 0;
        }
        int maxStack = Math.max(1, item.getMaxStackSize());
        ItemStack template = new ItemStack(item);
        var inv = player.getInventory();
        int left = qty;
        int placed = 0;
        for (int i = 0; i < inv.items.size() && left > 0; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || st.getCount() >= maxStack
                    || !ItemStack.isSameItemSameTags(st, template)) {
                continue;
            }
            int take = Math.min(left, maxStack - st.getCount());
            st.grow(take);
            left -= take;
            placed += take;
        }
        for (int i = 0; i < inv.items.size() && left > 0; i++) {
            if (!inv.getItem(i).isEmpty()) {
                continue;
            }
            int take = Math.min(left, maxStack);
            inv.setItem(i, new ItemStack(item, take));
            left -= take;
            placed += take;
        }
        inv.setChanged();
        player.inventoryMenu.broadcastChanges();
        return placed;
    }

    // ------------------------------------------------------------- trading

    /**
     * Buy/sell. A normal positive qty trades at the given size;
     * qty == {@link C2STradePacket#QTY_ALL_IN} is a one-click buy (the server recomputes the maximum affordable shares from the balance);
     * qty == {@link C2STradePacket#QTY_ALL_AVAILABLE} is a one-click sell (the server takes the entire sellable position).
     */
    public static void trade(ServerPlayer player, Stock stock, int qty, boolean buy) {
        MinecraftServer server = player.server;
        if (server == null) {
            return;
        }
        if (stock == null || stock.retired()) {
            feedbackKey(player, "oremarket.trade.err.invalid_stock", false);
            return;
        }
        MarketSavedData data = MarketSavedData.get(server);
        UUID id = player.getUUID();
        // Level check (server-side re-validation; the client merely did not let it be selected)
        if (!requireUnlocked(player, data, stock, "oremarket.trade.err.stock_locked")) {
            return;
        }
        StockState st = data.state(stock);
        // Trades require a positive, finite price; 0 / NaN / Inf / missing are all refused
        // (trading at price 0 before quotes load would mint money, and the client would show --).
        if (st == null || !(st.price > 0) || !Double.isFinite(st.price)
                || data.isCodeRetiredFlagged(stock.code())) {
            feedbackKey(player, "oremarket.trade.err.no_market", false);
            return;
        }
        // Event modifier table: suspended stocks refuse both sides; fee and price impact follow the live events (looked up by stock code)
        MarketModifiers mm = currentModifiers(data);
        MarketModifiers.Per per = mm.get(stock.code());
        int suspend = per.suspendTicks;
        if (suspend > 0) {
            feedbackKey(player, "oremarket.trade.err.suspended", false, MarketTime.human(suspend));
            return;
        }
        MarketSettings ms = MarketSettings.current();
        // Total rate = fee rate + burn tax - fee rate x burn tax; events only modify the fee part
        double fee = Fees.effectiveFeeRate(per.fee);
        double totalRate = Fees.combined(fee, ms.taxRate());

        if (qty == C2STradePacket.QTY_ALL_IN) {
            if (!buy) {
                feedbackKey(player, "oremarket.trade.err.bad_qty", false);
                return;
            }
            // One-click buy keeps the legacy formula when slippage is off; when enabled the bounded shared solver
            // includes the quantity-dependent execution price so the button never overspends after slippage.
            int max;
            if (ms.tradeSlippage) {
                max = cn.oremarket.common.TradeSlippage.affordableBuyShares(
                        data.availableCoins(id), st.price, totalRate, ms.tradeImpactFactor, per.shockMult, ms.maxTradeQuantity);
            } else {
                double unit = st.price * (1.0 + totalRate);
                max = unit <= 0 ? 0 : (int) Math.min(ms.maxTradeQuantity, Math.floor(data.availableCoins(id) / unit));
            }
            if (max < 1) {
                feedbackKey(player, "oremarket.trade.err.short_coins", false);
                return;
            }
            qty = max;
        } else if (qty == C2STradePacket.QTY_ALL_AVAILABLE) {
            if (buy) {
                feedbackKey(player, "oremarket.trade.err.bad_qty", false);
                return;
            }
            int avail = data.availableUnlocked(id, stock);
            if (avail <= 0) {
                feedbackKey(player, data.frozenShares(id, stock) > 0
                        ? "oremarket.trade.err.t1" : "oremarket.trade.err.nothing_sell", false);
                return;
            }
            qty = avail;
        }
        if (qty <= 0 || qty > ms.maxTradeQuantity) {
            feedbackKey(player, "oremarket.trade.err.bad_qty", false);
            return;
        }

        double execPrice = st.price;
        if (ms.tradeSlippage) {
            double slip = cn.oremarket.common.TradeSlippage.slipFraction(
                    ms.tradeImpactFactor, per.shockMult, qty);
            execPrice = cn.oremarket.common.TradeSlippage
                    .execPrice(st.price, slip, buy);
        }
        if (!(execPrice > 0.0) || !Double.isFinite(execPrice)) {
            feedbackKey(player, "oremarket.trade.err.no_market", false);
            return;
        }
        if (buy) {
            // Deduction always goes through Fees.buyCost: ceil(subtotal x (1 + total rate)), the same entry the log shows -- two places writing two formulas is impossible now
            long cost = Fees.buyCost(execPrice, qty, totalRate);
            if (data.availableCoins(id) < cost) {
                feedbackKey(player, "oremarket.trade.err.buy_short", false,
                        String.valueOf(qty), stock.displayName(), String.valueOf(cost), pct(fee), pct(ms.taxRate()));
                return;
            }
            // Weighted-average cost: the old position keeps its old cost, the new buy is booked at the amount actually paid (fees included)
            // averageCost = (averageCost x oldShares + price x n + fee amount) / (oldShares + n); fees are part of the cost
            int oldShares = data.totalShares(id, stock);
            double oldAvg = data.costOr(id, stock, st.price);
            data.addBalance(id, -cost);
            // T+1: shares bought today freeze; they unfreeze into sellable at the close
            data.addFrozen(id, stock, qty);
            double newAvg = Fees.weightedAverageBuyCost(oldShares, oldAvg, cost, qty);
            data.setCost(id, stock, newAvg, "buy");
            applyShock(st, data, stock, qty, true, per.shockMult, true);
            // [BUSINESS] Buy log = a complete reconciliation chain the player can recompute: price -> subtotal -> fee -> burn tax -> actually charged.
            // All four money columns come from Fees.buyLedger (one entry and one input with the buyCost settlement above);
            // the pre-fix bug where total 12.4 / fee 0.0 / charged 13 disagreed with itself and the tax looked swallowed is now closed.
            // [WORKED EXAMPLE] 12.41 x 1 share, fee 2% tax 1%: subtotal 12.41, fee 0.25, burn tax 0.12,
            // charged ceil(12.41 x 1.0298) = 13 -- column by column identical to the UI's cost 13 coins / avg cost 13.00.
            String[] led = Fees.buyLedger(execPrice, qty, fee, ms.taxRate());
            if (!led[3].equals(String.valueOf(cost))) {
                led[3] = String.valueOf(cost);   // Defence: the settled value is always the final authority over the charged column
            }
            LogService.pushKey(player, data, LogType.BUY, "oremarket.trade.log.buy",
                    stock.displayName(), qty,
                    Num.fixed(execPrice, 2),
                    led[0], led[1], led[2], led[3]);
            feedbackKey(player, "oremarket.trade.buy_ok", true,
                    stock.displayName(), String.valueOf(qty), String.valueOf(cost), String.format(Locale.ROOT, "%.2f", newAvg));
            // Player-behaviour trigger statistics (the counterparty is the system, so self-trading cannot exist) + config-driven particles
            cn.oremarket.market.event.PlayerVolumeTracker
                    .recordBuy(stock.code(), id, qty, false);
            cn.oremarket.net.ParticleService
                    .playTrade(server, player, true);
        } else {
            int avail = data.availableUnlocked(id, stock);
            int frz = data.frozenShares(id, stock);
            if (qty > avail) {
                if (avail + frz >= qty) {
                    feedbackKey(player, "oremarket.trade.err.frozen_wait", false, String.valueOf(frz));
                } else {
                    feedbackKey(player, "oremarket.trade.err.sell_short", false,
                            stock.displayName(), String.valueOf(avail), String.valueOf(frz));
                }
                return;
            }
            // Proceeds always go through Fees.sellGain: floor(subtotal x (1 - total rate)), the same entry the log shows
            long gain = Fees.sellGain(execPrice, qty, totalRate);
            double avg = data.costOr(id, stock, st.price);
            // deltaPnl = (price x n - fee) - averageCost x n; a sale never changes averageCost (only buys and zeroing do)
            double pnl = gain - qty * avg;
            data.addShares(id, stock, -qty);
            // Coins received from a sale may be re-spent the same day: coins themselves are not bound by T+1
            data.creditSaleProceeds(id, gain);
            data.addRealized(id, stock, pnl);
            if (data.totalShares(id, stock) <= 0) {
                data.clearPositionCost(id, stock, "clear");
            }
            applyShock(st, data, stock, qty, false, per.shockMult, true);
            // [BUSINESS] The sell log uses the same reconciliation chain as the buy log: price -> subtotal -> fee -> burn tax ->
            // proceeds -> P&L. P&L is proceeds minus qty x cost basis; fees and rounding losses are honestly reflected in the proceeds.
            // [WORKED EXAMPLE] 12.41 x 1 share, fee 2% tax 1%: subtotal 12.41, fee 0.25, burn tax 0.12,
            // proceeds floor(12.41 x 0.9702) = 12; with a cost basis of 13.00, P&L = 12 - 1 x 13 = -1.0.
            // [SELF-CHECK] proceeds + fee + burn tax = 12.37 <= subtotal 12.41 (the gap is the 0.3698 in fees plus the floor
            // rounding loss); the four columns agree in order and value with the settlement entry sellGain -- no low-level math error.
            String[] led = Fees.sellLedger(execPrice, qty, fee, ms.taxRate());
            if (!led[3].equals(String.valueOf(gain))) {
                led[3] = String.valueOf(gain);   // Defence: the settled value is always the final authority over the proceeds column
            }
            LogService.pushKey(player, data, LogType.SELL, "oremarket.trade.log.sell",
                    stock.displayName(), qty,
                    Num.fixed(execPrice, 2),
                    led[0], led[1], led[2], led[3],
                    Num.signed(pnl, 1));
            feedbackKey(player, "oremarket.trade.sell_ok", true,
                    stock.displayName(), String.valueOf(qty), String.valueOf(gain), String.format(Locale.ROOT, "%+.1f", pnl));
            cn.oremarket.market.event.PlayerVolumeTracker
                    .recordSell(stock.code(), id, qty, false);
            cn.oremarket.net.ParticleService
                    .playTrade(server, player, false);
        }
        MarketEngine.pushAfterAction(server, player);
    }

    /**
     * Price impact caused by trades: clamped only by the base-price band; the daily +/-10% limits govern the ordinary marketTick evolution.
     * shockMult comes from the event trade-impact modifier (0.5~3.0); 1.0 means no event influence.
     */
    public static void applyShock(StockState st, MarketSavedData data, Stock stock, int qty,
                                   boolean buy, double shockMult) {
        applyShock(st, data, stock, qty, buy, shockMult, false);
    }

    /** Applies a quote shock and optionally records the fill as player-initiated display volume. */
    public static void applyShock(StockState st, MarketSavedData data, Stock stock, int qty,
                                   boolean buy, double shockMult, boolean playerFill) {
        double shock = MarketSettings.current().tradeImpactFactor * shockMult;
        double mult = buy ? 1.0 + shock * Math.sqrt(qty) : 1.0 - shock * Math.sqrt(qty);
        double previousPrice = st.price;
        st.price = data.clampPrice(stock, st.price * mult);
        st.recordPriceChange(previousPrice, st.price);
        st.appendHistory(st.price);
        if (playerFill) {
            st.addPlayerTradedVolume(qty);
        } else {
            st.addTradedVolume(qty);
        }
        data.setDirty();
    }

    /** The modifier table pressed out by the live events (suspension / fee / trade impact), on the same convention as the market engine. */
    private static MarketModifiers currentModifiers(MarketSavedData data) {
        return MarketModifiers.collect(data.events, MarketSettings.current().feeRate(),
                MarketSettings.current().meanReversionFactor, MarketEngine.defaultPriceLimit());
    }

    /** Level check: locked tiers reply with the requirement's wording (the client shows it as a red line at the bottom of the screen for 2 seconds). */
    private static boolean requireUnlocked(ServerPlayer player, MarketSavedData data, Stock stock, String key) {
        if (data.unlocked(player.getUUID(), stock)) {
            return true;
        }
        feedbackKey(player, key, false);
        return false;
    }

    // ------------------------------------------------------------- utilities

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.0f%%", v * 100);
    }

    /** Key-value feedback: packets carry only a translation key + parameters; the client renders the template in the current language by key (no more pre-localised text on the wire). */
    public static void feedbackKey(ServerPlayer player, String key, boolean success, String... args) {
        feedbackKey(player, key, success, 40, args);
    }

    public static void feedbackKey(ServerPlayer player, String key, boolean success, int ticks, String... args) {
        OreMarketNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new S2CFeedbackPacket(key, args == null ? java.util.List.of() : java.util.List.of(args),
                        success, ticks));
    }

    private static int countItem(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        var inv = player.getInventory();
        // Vanilla Inventory.getContainerSize == 41
        // already counts the 4 armour slots and the offhand, yet the old code added the offhand again below -- offhand items were double-counted.
        // Count only the real storage slots (main inventory 36) plus the offhand once, matching the simulate-insert and delivery rules exactly.
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(item)) {
                count += st.getCount();
            }
        }
        ItemStack off = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (off.is(item)) {
            count += off.getCount();
        }
        return count;
    }

    /**
     * Remove want items slot by slot from the inventory (main 36 slots + offhand, same convention as countItem);
     * returns the ACTUAL removed count -- callers settle coins on that number; less removed means less paid, never inflated.
     */
    private static int removeItem(ServerPlayer player, net.minecraft.world.item.Item item, int want) {
        int removed = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.items.size() && removed < want; i++) {   // Scan only the main inventory 36 slots
            ItemStack st = inv.getItem(i);
            if (st.is(item)) {
                int take = Math.min(st.getCount(), want - removed);
                st.shrink(take);
                removed += take;
                if (st.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        ItemStack off = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (removed < want && off.is(item)) {
            int take = Math.min(off.getCount(), want - removed);
            off.shrink(take);
            removed += take;
            if (off.isEmpty()) {
                player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            }
        }
        inv.setChanged();
        player.inventoryMenu.broadcastChanges();
        return removed;
    }

}
