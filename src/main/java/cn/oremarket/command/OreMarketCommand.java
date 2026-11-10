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

package cn.oremarket.command;

import cn.oremarket.config.OreMarketConfig;
import cn.oremarket.market.ActiveEvent;
import cn.oremarket.common.MarketTime;
import cn.oremarket.market.EventCard;
import cn.oremarket.market.MarketEngine;
import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.Stock;
import cn.oremarket.market.event.EventEngine;
import cn.oremarket.market.event.EventRegistry;
import cn.oremarket.market.event.EventTemplate;
import cn.oremarket.net.OreMarketNetwork;
import cn.oremarket.net.packet.S2COpenUiPacket;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.Locale;

/**
 * /oremarket command: open shows the UI, balance queries coins and holdings, event manages events, config switches modes,
 * coins is the admin test tool: add / remove / set any player's ore coins freely.
 * Server authority: commands execute on the server only.
 */
public final class OreMarketCommand {

    private OreMarketCommand() {
    }

    private static Component msg(String key, Object... args) {
        return Component.translatable(key, args);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oremarket")
                .then(Commands.literal("open").executes(ctx -> open(ctx)))
                .then(Commands.literal("balance").executes(ctx -> balance(ctx)))
                // debugstatus - read-only dump of each stock's short pool X / Y and the whole-server ore coin supply;
                // testshort - pure in-memory rehearsal of one-click short arithmetic (defaults: balance 1000 / coal 10);
                // testloan - pure in-memory rehearsal of splitting 1000 over 3 installments with a remainder.
                // These three commands NEVER call ShortService / LoanService and NEVER touch NBT / setDirty:
                // all arithmetic happens in the zero-side-effect DebugCalculator + ShortPoolCalculator + LoanSchedule.
                .then(Commands.literal("debugstatus")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> debugStatus(ctx)))
                .then(Commands.literal("testshort")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> testShort(ctx, 1000L, 10.0, -1))
                        .then(Commands.argument("balance", LongArgumentType.longArg(0, 1_000_000_000L))
                                .executes(ctx -> testShort(ctx,
                                        LongArgumentType.getLong(ctx, "balance"), 10.0, -1))
                                .then(Commands.argument("price", IntegerArgumentType.integer(1, 1_000_000))
                                        .executes(ctx -> testShort(ctx,
                                                LongArgumentType.getLong(ctx, "balance"),
                                                IntegerArgumentType.getInteger(ctx, "price"), -1))
                                        .then(Commands.argument("pool", IntegerArgumentType.integer(0, 10_000_000))
                                                .executes(ctx -> testShort(ctx,
                                                        LongArgumentType.getLong(ctx, "balance"),
                                                        IntegerArgumentType.getInteger(ctx, "price"),
                                                        IntegerArgumentType.getInteger(ctx, "pool")))))))
                .then(Commands.literal("testloan")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> testLoan(ctx, 1000L, 3))
                        .then(Commands.argument("principal", LongArgumentType.longArg(1, 1_000_000_000L))
                                .executes(ctx -> testLoan(ctx,
                                        LongArgumentType.getLong(ctx, "principal"), 3))
                                .then(Commands.argument("installments", IntegerArgumentType.integer(1, 5))
                                        .executes(ctx -> testLoan(ctx,
                                                LongArgumentType.getLong(ctx, "principal"),
                                                IntegerArgumentType.getInteger(ctx, "installments"))))))
                // Admin test: /oremarket coins <add|take|set> <amount> [player] (permission level >= 2)
                // omitting the mode and giving just a number = add coins to yourself: /oremarket coins 100000
                .then(Commands.literal("coins")
                        .requires(src -> src.hasPermission(2))
                        .then(coinsMode("add"))
                        .then(coinsMode("take"))
                        .then(coinsMode("set"))
                        .then(Commands.argument("amount", LongArgumentType.longArg(0, COINS_MAX))
                                .executes(ctx -> coins(ctx, "add", false))))
                // Admin test: /oremarket hold ... (permission level >= 2)
                // add/clear target one stock with a given quantity; freeze/unfreeze target one stock's whole position;
                // addall/freezeall/unfreezeall/clearall cover all the player's relevant positions; list verifies the result.
                .then(Commands.literal("hold")
                        .requires(src -> src.hasPermission(2))
                        .then(holdStockAmountMode("add"))
                        .then(holdStockAmountMode("clear"))
                        .then(holdStockMode("freeze"))
                        .then(holdStockMode("unfreeze"))
                        .then(holdAmountMode("addall"))
                        .then(holdNoArgMode("freezeall"))
                        .then(holdNoArgMode("unfreezeall"))
                        .then(holdNoArgMode("clearall"))
                        .then(holdNoArgMode("list")))
                .then(Commands.literal("price")
                        .then(Commands.argument("stock", StringArgumentType.word())
                                .suggests((ctx, sb) -> {
                                    // candidates = ACTIVE stock codes of the current server registry (built-in + custom)
                                    for (Stock s : cn.oremarket.market.StockRegistry
                                            .server().getActiveStocks()) {
                                        sb.suggest(s.code());
                                    }
                                    return sb.buildFuture();
                                })
                                .executes(ctx -> price(ctx))))
                .then(Commands.literal("event")
                        .then(Commands.literal("list").executes(ctx -> eventList(ctx)))
                        .then(Commands.literal("templates").executes(ctx -> eventTemplates(ctx)))
                        .then(Commands.literal("clear")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> eventClear(ctx)))
                        .then(Commands.literal("trigger")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("card", StringArgumentType.word())
                                        .suggests((ctx, sb) -> {
                                            for (EventTemplate t : EventRegistry.all()) {
                                                sb.suggest(t.id);
                                            }
                                            return sb.buildFuture();
                                        })
                                        .executes(ctx -> eventTrigger(ctx)))))
                // Config show is available to every player; config reload and sync need OP permission level >= 2,
                // and two reload executions must be 5 seconds apart (a code-level cooldown; running inside it reports the remaining time)
                .then(Commands.literal("config")
                        .then(Commands.literal("show")
                                .executes(ctx -> showConfig(ctx)))
                        .then(Commands.literal("reload")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> reloadConfig(ctx)))
                        .then(Commands.literal("sync")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("model", StringArgumentType.word())
                                        .executes(ctx -> setSyncModel(ctx))))));
    }

    // ---------------------------------------------------------------- Admin test: ore coin adjustments

    /** Max ore coins one adjustment may move: leaves ample headroom so the long can never overflow. */
    private static final long COINS_MAX = 1_000_000_000_000L;

    /**
     * One mode branch of coins: {@code /oremarket coins <mode> <amount> [player]}.
     * The amount is required, the player optional (defaults to the command sender).
     */
    private static ArgumentBuilder<CommandSourceStack, ?> coinsMode(String mode) {
        return Commands.literal(mode)
                .then(Commands.argument("amount", LongArgumentType.longArg(0, COINS_MAX))
                        .suggests((ctx, sb) -> {
                            sb.suggest("100");
                            sb.suggest("1000");
                            sb.suggest("100000");
                            return sb.buildFuture();
                        })
                        .executes(ctx -> coins(ctx, mode, false))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> coins(ctx, mode, true))));
    }

    /**
     * Directly change a player's ore-coin balance (admin test; permission level >= 2 required):
     * - add: increase the current balance by the amount (refreshing the all-time peak per the rules, so the level rises normally);
     * - take: remove the amount, bottoming out at 0 - a negative balance never appears;
     * - set: make the balance exactly the amount.
     * Right after any change {@code MarketEngine.pushAfterAction} runs: level, peak and balance are pushed back to that player in one shot,
     * and the market screen shows the new figures without reopening.
     * Feedback text goes through translation keys, so the client renders it in the player's own game language (Chinese / English both registered).
     */
    private static int coins(CommandContext<CommandSourceStack> ctx, String mode, boolean hasPlayer)
            throws CommandSyntaxException {
        long amount = LongArgumentType.getLong(ctx, "amount");
        ServerPlayer target = hasPlayer
                ? EntityArgument.getPlayer(ctx, "player")
                : ctx.getSource().getPlayerOrException();
        MarketSavedData data = MarketSavedData.get(ctx.getSource().getServer());
        java.util.UUID id = target.getUUID();
        long before = data.balance(id);
        long applied;
        if ("take".equals(mode)) {
            applied = Math.min(amount, before);            // over-deducting simply floors at 0
            data.addBalance(id, -applied);
        } else if ("set".equals(mode)) {
            applied = amount - before;                     // report the delta, not the target value
            // set is implemented as a make-up-the-difference add: equivalent to writing the balance directly while still raising the peak and level per the rules
            data.addBalance(id, applied);
        } else {
            // overflow guard: an out-of-range add saturates at Long.MAX_VALUE and never wraps negative
            long delta = before > Long.MAX_VALUE - amount ? Long.MAX_VALUE - before : amount;
            applied = delta;
            data.addBalance(id, delta);   // route through the unified entry: raises the all-time peak and levels up per the rules along the way
        }
        long after = data.balance(id);
        // push back immediately on any balance change: level / peak / snapshot sync together, the screen reflects the new figures instantly
        MarketEngine.pushAfterAction(ctx.getSource().getServer(), target);
        final String key = "take".equals(mode) ? "oremarket.cmd.coins.taken"
                : "set".equals(mode) ? "oremarket.cmd.coins.set" : "oremarket.cmd.coins.added";
        // add / take report a positive delta; set reports a signed one (setting lower shows a negative)
        final long shown = applied;
        ctx.getSource().sendSuccess(() -> Component.translatable(key,
                target.getDisplayName(), shown, after), true);
        return 1;
    }

    private static final int POSITION_MAX = 1_000_000_000;

    private static ArgumentBuilder<CommandSourceStack, ?> holdStockAmountMode(String mode) {
        return Commands.literal(mode)
                .then(Commands.argument("stock", StringArgumentType.word())
                        .suggests((ctx, sb) -> holdStockSuggestions(sb))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, POSITION_MAX))
                                .suggests((ctx, sb) -> {
                                    sb.suggest("1");
                                    sb.suggest("10");
                                    sb.suggest("100");
                                    sb.suggest("1000");
                                    return sb.buildFuture();
                                })
                                .executes(ctx -> holdStockAmount(ctx, mode, false))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> holdStockAmount(ctx, mode, true)))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> holdStockMode(String mode) {
        return Commands.literal(mode)
                .then(Commands.argument("stock", StringArgumentType.word())
                        .suggests((ctx, sb) -> holdStockSuggestions(sb))
                        .executes(ctx -> holdStock(ctx, mode, false))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> holdStock(ctx, mode, true))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> holdAmountMode(String mode) {
        return Commands.literal(mode)
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, POSITION_MAX))
                        .suggests((ctx, sb) -> {
                            sb.suggest("1");
                            sb.suggest("10");
                            sb.suggest("100");
                            sb.suggest("1000");
                            return sb.buildFuture();
                        })
                        .executes(ctx -> holdAmount(ctx, mode, false))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> holdAmount(ctx, mode, true))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> holdNoArgMode(String mode) {
        return Commands.literal(mode)
                .executes(ctx -> holdNoArg(ctx, mode, false))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> holdNoArg(ctx, mode, true)));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> holdStockSuggestions(
            com.mojang.brigadier.suggestion.SuggestionsBuilder sb) {
        for (Stock s : cn.oremarket.market.StockRegistry.server().getActiveStocks()) {
            sb.suggest(s.code());
        }
        return sb.buildFuture();
    }

    private static ServerPlayer holdTarget(CommandContext<CommandSourceStack> ctx, boolean hasPlayer)
            throws CommandSyntaxException {
        return hasPlayer ? EntityArgument.getPlayer(ctx, "player") : ctx.getSource().getPlayerOrException();
    }

    private static Stock holdStock(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "stock");
        Stock stock = cn.oremarket.market.StockRegistry.server()
                .getByCode(raw.toUpperCase(java.util.Locale.ROOT));
        if (stock == null || stock.retired()) {
            ctx.getSource().sendFailure(Component.translatable("oremarket.cmd.unknown_stock", raw));
            return null;
        }
        return stock;
    }

    private static int holdStockAmount(CommandContext<CommandSourceStack> ctx, String mode, boolean hasPlayer)
            throws CommandSyntaxException {
        Stock stock = holdStock(ctx);
        if (stock == null) {
            return 0;
        }
        ServerPlayer target = holdTarget(ctx, hasPlayer);
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        MarketSavedData data = MarketSavedData.get(ctx.getSource().getServer());
        int actual;
        if ("add".equals(mode)) {
            data.addShares(target.getUUID(), stock, amount);
            actual = amount;
            ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.hold.added",
                    target.getDisplayName(), stock.displayName(), actual,
                    data.shares(target.getUUID(), stock)), true);
        } else {
            actual = data.reducePlayerPosition(target.getUUID(), stock, amount);
            if (actual <= 0) {
                ctx.getSource().sendFailure(Component.translatable("oremarket.cmd.hold.no_clear", stock.displayName()));
                return 0;
            }
            ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.hold.cleared",
                    target.getDisplayName(), stock.displayName(), actual,
                    data.totalShares(target.getUUID(), stock)), true);
        }
        MarketEngine.pushAfterAction(ctx.getSource().getServer(), target);
        return 1;
    }

    private static int holdStock(CommandContext<CommandSourceStack> ctx, String mode, boolean hasPlayer)
            throws CommandSyntaxException {
        Stock stock = holdStock(ctx);
        if (stock == null) {
            return 0;
        }
        ServerPlayer target = holdTarget(ctx, hasPlayer);
        MarketSavedData data = MarketSavedData.get(ctx.getSource().getServer());
        int actual;
        if ("freeze".equals(mode)) {
            actual = data.freezePlayerShares(target.getUUID(), stock);
            if (actual <= 0) {
                ctx.getSource().sendFailure(Component.translatable("oremarket.cmd.hold.no_freeze", stock.displayName()));
                return 0;
            }
            ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.hold.frozen",
                    target.getDisplayName(), stock.displayName(), actual), true);
        } else {
            actual = data.unfreezePlayerShares(target.getUUID(), stock);
            if (actual <= 0) {
                ctx.getSource().sendFailure(Component.translatable("oremarket.cmd.hold.no_unfreeze", stock.displayName()));
                return 0;
            }
            ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.hold.unfrozen",
                    target.getDisplayName(), stock.displayName(), actual), true);
        }
        MarketEngine.pushAfterAction(ctx.getSource().getServer(), target);
        return 1;
    }

    private static int holdAmount(CommandContext<CommandSourceStack> ctx, String mode, boolean hasPlayer)
            throws CommandSyntaxException {
        ServerPlayer target = holdTarget(ctx, hasPlayer);
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        MarketSavedData data = MarketSavedData.get(ctx.getSource().getServer());
        if ("addall".equals(mode)) {
            int count = 0;
            for (Stock stock : cn.oremarket.market.StockRegistry.server().getActiveStocks()) {
                data.addShares(target.getUUID(), stock, amount);
                count++;
            }
            final int shown = count;
            ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.hold.added_all",
                    target.getDisplayName(), amount, shown), true);
            MarketEngine.pushAfterAction(ctx.getSource().getServer(), target);
            return count > 0 ? 1 : 0;
        }
        return 0;
    }

    private static int holdNoArg(CommandContext<CommandSourceStack> ctx, String mode, boolean hasPlayer)
            throws CommandSyntaxException {
        ServerPlayer target = holdTarget(ctx, hasPlayer);
        MarketSavedData data = MarketSavedData.get(ctx.getSource().getServer());
        java.util.UUID id = target.getUUID();
        if ("list".equals(mode)) {
            java.util.Set<String> codes = new java.util.LinkedHashSet<>();
            codes.addAll(data.holdingsOf(id).keySet());
            codes.addAll(data.frozenOf(id).keySet());
            ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.holdings_header",
                    target.getDisplayName()).withStyle(ChatFormatting.GOLD), false);
            if (codes.isEmpty()) {
                ctx.getSource().sendSystemMessage(Component.translatable("oremarket.cmd.holdings_none").withStyle(ChatFormatting.GRAY));
            } else {
                for (String code : codes) {
                    Stock stock = cn.oremarket.market.StockRegistry.server().getByCode(code);
                    String name = stock == null ? code : stock.displayName();
                    int available = data.holdingsOf(id).getOrDefault(code, 0);
                    int frozen = data.frozenOf(id).getOrDefault(code, 0);
                    ctx.getSource().sendSystemMessage(Component.translatable("oremarket.cmd.holding_line",
                            name, code, available, frozen).withStyle(ChatFormatting.AQUA));
                }
            }
            return 1;
        }

        int total = 0;
        int stocks = 0;
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        codes.addAll(data.holdingsOf(id).keySet());
        codes.addAll(data.frozenOf(id).keySet());
        if ("freezeall".equals(mode)) {
            total = data.freezePlayerAll(id);
            stocks = data.frozenOf(id).size();
            final int shownStocks = stocks;
            final int shownTotal = total;
            ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.hold.frozen_all",
                    target.getDisplayName(), shownStocks, shownTotal), true);
        } else if ("unfreezeall".equals(mode)) {
            total = data.unfreezePlayerAll(id);
            stocks = data.holdingsOf(id).size();
            final int shownStocks = stocks;
            final int shownTotal = total;
            ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.hold.unfrozen_all",
                    target.getDisplayName(), shownStocks, shownTotal), true);
        } else if ("clearall".equals(mode)) {
            for (String code : codes) {
                Stock stock = cn.oremarket.market.StockRegistry.server().getByCode(code);
                if (stock != null) {
                    int removed = data.reducePlayerPosition(id, stock, Integer.MAX_VALUE);
                    if (removed > 0) {
                        total = (int) Math.min((long) Integer.MAX_VALUE, total + (long) removed);
                        stocks++;
                    }
                }
            }
            final int shownStocks = stocks;
            final int shownTotal = total;
            ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.hold.cleared_all",
                    target.getDisplayName(), shownStocks, shownTotal), true);
        }
        MarketEngine.pushAfterAction(ctx.getSource().getServer(), target);
        return 1;
    }

    private static int open(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            OreMarketNetwork.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new S2COpenUiPacket());
        } catch (CommandSyntaxException e) {
            ctx.getSource().sendFailure(Component.translatable("oremarket.text.0402"));
        }
        return 1;
    }

    private static int balance(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.translatable("oremarket.text.0196"));
            return 0;
        }
        MarketSavedData data = MarketSavedData.get(ctx.getSource().getServer());
        player.displayClientMessage(Component.translatable("oremarket.cmd.balance",
                        data.balance(player.getUUID()))
                .withStyle(ChatFormatting.GOLD), false);
        var held = data.holdingsOf(player.getUUID());   // code -> share quantity
        if (held.isEmpty()) {
            player.displayClientMessage(Component.translatable("oremarket.text.0245").withStyle(ChatFormatting.GRAY), false);
        } else {
            held.forEach((code, qty) -> {
                Stock stock = cn.oremarket.market.StockRegistry
                        .server().getByCode(code);
                String name = stock == null ? code : stock.displayName();
                player.displayClientMessage(Component.translatable("oremarket.cmd.position_line",
                                name, qty)
                        .withStyle(ChatFormatting.AQUA), false);
            });
        }
        return 1;
    }

    private static int price(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "stock");
        // lookup by stock code string (case-insensitive) in the dynamic registry
        Stock stock = cn.oremarket.market.StockRegistry
                .server().getByCode(id.toUpperCase(java.util.Locale.ROOT));
        if (stock == null || stock.retired()) {
            ctx.getSource().sendFailure(Component.translatable("oremarket.cmd.unknown_stock", id));
            return 0;
        }
        MarketSavedData data = MarketSavedData.get(ctx.getSource().getServer());
        var st = data.state(stock);
        if (st == null) {
            ctx.getSource().sendFailure(Component.translatable("oremarket.cmd.no_quote", id));
            return 0;
        }
        double chg = st.changePercent();
        // The rising/falling hue comes from one place: the server's configured colour convention (red-up or green-up).
        ChatFormatting fmt = cn.oremarket.market.MarketSettings.server().changeStyle(chg);
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.price",
                        stock.displayName(),
                        String.format(Locale.ROOT, "%.2f", st.price),
                        stock.basePrice(),
                        (chg >= 0 ? "+" : "") + String.format(Locale.ROOT, "%.1f", chg))
                .withStyle(fmt), false);
        return 1;
    }

    private static int eventList(CommandContext<CommandSourceStack> ctx) {
        MarketSavedData data = MarketSavedData.get(ctx.getSource().getServer());
        if (data.events.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.text.0326").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        for (ActiveEvent e : data.events) {
            ctx.getSource().sendSystemMessage(Component.translatable("oremarket.cmd.event_line",
                    e.rarity.label, e.displayName, e.description, e.effectSummary(),
                    MarketTime.human(e.ticksLeft), MarketTime.human(e.duration))
                    .withStyle(ChatFormatting.YELLOW));
        }
        return data.events.size();
    }

    /** List the template pool: rarity, weight, conditions, chain-trigger-only flag. */
    private static int eventTemplates(CommandContext<CommandSourceStack> ctx) {
        MarketSavedData data = MarketSavedData.get(ctx.getSource().getServer());
        for (EventTemplate t : EventRegistry.all()) {
            ctx.getSource().sendSystemMessage(Component.translatable("oremarket.cmd.event_template",
                    t.rarity.label, t.id, t.nameTemplate, t.rarity.weight,
                    MarketTime.human(t.durationMin), MarketTime.human(t.durationMax),
                    t.effects.size(),
                    t.condition == null ? "" : net.minecraft.network.chat.Component.translatable("oremarket.cmd.event_condition", t.condition.describe()).getString(),
                    t.chain == null ? "" : net.minecraft.network.chat.Component.translatable("oremarket.cmd.event_chain", t.chain.childId,
                            Math.round(t.chain.chance * 1000.0) / 10.0 + "%",
                            t.chain.onEnd ? net.minecraft.network.chat.Component.translatable("oremarket.cmd.event_on_end").getString()
                                    : net.minecraft.network.chat.Component.translatable("oremarket.cmd.event_on_during").getString()).getString(),
                    t.chainOnly ? net.minecraft.network.chat.Component.translatable("oremarket.cmd.event_chain_only").getString() : "")
                    .withStyle(ChatFormatting.GRAY));
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.event_templates_summary",
                EventRegistry.all().size(), data.events.size()).withStyle(ChatFormatting.AQUA), false);
        return EventRegistry.all().size();
    }

    private static int eventTrigger(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "card");
        EventTemplate tpl = EventRegistry.byId(id);
        // legacy compatibility: the 12 original card ids still trigger
        if (tpl == null) {
            EventCard card = EventCard.byId(id);
            if (card != null) {
                tpl = EventRegistry.legacy(card);
            }
        }
        if (tpl == null) {
            ctx.getSource().sendFailure(Component.translatable("oremarket.cmd.event_unknown", id));
            return 0;
        }
        MarketSavedData data = MarketSavedData.get(ctx.getSource().getServer());
        net.minecraft.util.RandomSource rand = ctx.getSource().getServer().overworld().getRandom();
        ActiveEvent spawned = EventEngine.forceTrigger(ctx.getSource().getServer(), data, tpl, rand,
                e -> EventEngine.announce(ctx.getSource().getServer(), data, e));
        if (spawned == null) {
            ctx.getSource().sendFailure(Component.translatable("oremarket.text.0516"));
            return 0;
        }
        // command-triggered events go through the config-driven particle pipeline too (no more hard-coded particle types)
        cn.oremarket.net.ParticleService.playEventAll(
                ctx.getSource().getServer(), data);
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.event_triggered",
                spawned.displayName, spawned.rarity.label, spawned.effectSummary(),
                MarketTime.human(spawned.duration)).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** Clear all current events (op-only, for testing). */
    private static int eventClear(CommandContext<CommandSourceStack> ctx) {
        MarketSavedData data = MarketSavedData.get(ctx.getSource().getServer());
        int n = data.events.size();
        data.events.clear();
        data.setDirty();
        int finalN = n;
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.event_clear", finalN)
                .withStyle(ChatFormatting.GREEN), true);
        return n;
    }

    /**
     * Print the effective config snapshot (key figures of all ten categories): admins use it after editing the toml to see what actually took effect.
     * Note every value comes from config/oremarket.toml; the command itself NEVER WRITES the config file (change values through the config file).
     */
    private static int showConfig(CommandContext<CommandSourceStack> ctx) {
        cn.oremarket.market.MarketSettings ms =
                cn.oremarket.market.MarketSettings.server();
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.text.0484").withStyle(ChatFormatting.GOLD), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.config.levels",
                ms.level2Threshold, ms.level3Threshold, ms.level4Threshold).withStyle(ChatFormatting.YELLOW), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.config.price",
                ms.meanReversionFactor, ms.randomFluctuation, ms.trendImpact, ms.tradeImpactFactor,
                ms.priceFloorPercent, ms.priceCeilingPercent).withStyle(ChatFormatting.YELLOW), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.config.time",
                ms.marketTickInterval, ms.tradingDayTicks, ms.marketTicksPerTradeDay()).withStyle(ChatFormatting.YELLOW), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.config.loan",
                ms.creditLimitLevel2 + "/" + ms.creditLimitLevel3 + "/" + ms.creditLimitLevel4,
                String.format(Locale.ROOT, "%.2f", ms.loanDailyInterestPercent * 100.0),
                ms.loanTermDays, String.format(Locale.ROOT, "%.1f", ms.loanInterestCapMultiplier),
                ms.bankruptcyCooldownDays).withStyle(ChatFormatting.YELLOW), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.config.loan2",
                ms.loanAutoRepay ? "true" : "false", ms.loanGracePeriodDays,
                String.format(Locale.ROOT, "%.2f", ms.loanPenaltyPercent),
                ms.loanCreditScoreEnabled ? "true" : "false").withStyle(ChatFormatting.YELLOW), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.config.loan3",
                String.format(Locale.ROOT, "%.1f", ms.loanLimitAssetPercent),
                String.format(Locale.ROOT, "%.2f", ms.loanCollateralLimitFactor),
                ms.loanEmergencyCap,
                String.format(Locale.ROOT, "%.2f", ms.loanMarginRateFactor),
                ms.loanOverdueStrikeLimit, ms.loanClawbackDays).withStyle(ChatFormatting.YELLOW), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.config.loan4",
                String.format(Locale.ROOT, "%.2f", ms.loanDailyInterestPercent * 100.0),
                String.format(Locale.ROOT, "%.1f", ms.loanInterestVariance),
                String.format(Locale.ROOT, "%.1f", ms.loanRateCapPercent),
                String.format(Locale.ROOT, "%.1f", ms.loanPrepayDiscountPercent),
                String.format(Locale.ROOT, "%.1f", ms.loanPrepayFeePercent),
                ms.loanLimitRecoverDays,
                ms.loanP2PEnabled ? "true" : "false",
                String.format(Locale.ROOT, "%.1f", ms.loanP2PFeePercent)).withStyle(ChatFormatting.YELLOW), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.config.trade",
                String.format(Locale.ROOT, "%.2f", ms.tradeFeePercent * 100.0),
                String.format(Locale.ROOT, "%.1f", ms.priceLimitPercent * 100.0), ms.startingCoins)
                .withStyle(ChatFormatting.YELLOW), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.config.events",
                String.format(Locale.ROOT, "%.1f", ms.eventTriggerChance * 100.0), ms.legendCooldownDays,
                ms.eventHaltMaxSeconds,
                ms.eventDurationCommonMinSec, ms.eventDurationCommonMaxSec,
                ms.eventDurationRareMinSec, ms.eventDurationRareMaxSec,
                ms.eventDurationEpicMinSec, ms.eventDurationEpicMaxSec,
                ms.eventDurationLegendMinSec, ms.eventDurationLegendMaxSec).withStyle(ChatFormatting.YELLOW), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.text.0369").withStyle(ChatFormatting.YELLOW), false);
        // walk the server's dynamic registry (built-ins first, customs after; delisted ones included with a status mark)
        for (Stock s : cn.oremarket.market.StockRegistry.server().getAll()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.config.stock_line",
                    s.displayName(), s.code(), s.tier(), s.basePrice(), s.exchangeRate(),
                    s.retired() ? Component.translatable("oremarket.text.0231").getString() : "")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    /** reload command cooldown: at least 5 seconds between executions (matching the hot-reload debounce itself). */
    private static final long RELOAD_COOLDOWN_MS = 5_000L;
    private static long lastReloadAtMs = 0L;

    /**
     * Manually fire one config reload (equivalent to saving the config file once; OP permission level >= 2 required).
     * It takes exactly the same path as ModConfigEvent.Reloading: set the flag -> on the next ServerTickEvent.END
     * read the new config + rebuild the registry + recalculate highestLevel + push SyncConfig/SyncPlayerState/MarketSnapshot.
     */
    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        long now = System.currentTimeMillis();
        long left = lastReloadAtMs + RELOAD_COOLDOWN_MS - now;
        if (left > 0) {
            ctx.getSource().sendFailure(Component.translatable("oremarket.cmd.reload_wait", (left + 999) / 1000));
            return 0;
        }
        lastReloadAtMs = now;
        cn.oremarket.market.MarketReloadService.requestReload();
        ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.text.0536")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int setSyncModel(CommandContext<CommandSourceStack> ctx) {
        String v = StringArgumentType.getString(ctx, "model");
        try {
            OreMarketConfig.SyncModel m = OreMarketConfig.SyncModel.valueOf(v.toUpperCase(java.util.Locale.ROOT));
            OreMarketConfig.SYNC_MODEL.set(m);
            ctx.getSource().sendSuccess(() -> Component.translatable("oremarket.cmd.sync_mode", m.name())
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.translatable("oremarket.text.0523"));
            return 0;
        }
    }

    // ==================== debug sandbox commands (all from OP level 2)

    private static final org.slf4j.Logger DEBUG_LOG =
            org.slf4j.LoggerFactory.getLogger("OreMarket/DebugCmd");

    /**
     * [Purpose] /oremarket debugstatus - read-only inspection: prints each stock's short pool 'remaining / cap' in shares,
     * then the whole-server ore coin balance across online + offline players. It reads existing MarketSavedData only,
     * calls no ensure (that would setDirty) and changes no field.
     * [Worked example] the line 'COAL: 4968 / 5000' means 32 shares borrowed out of the coal pool; daily refill runs at 10% of the cap.
     */
    private static int debugStatus(CommandContext<CommandSourceStack> ctx) {
        MarketSavedData data = MarketSavedData.get(ctx.getSource().getServer());
        java.util.Map<String, int[]> snap = data.shortPoolSnapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("[debugstatus] short pools (left / max shares):");
        for (java.util.Map.Entry<String, int[]> e : snap.entrySet()) {
            sb.append('\n').append("  ").append(e.getKey()).append(": ")
                    .append(e.getValue()[1]).append(" / ").append(e.getValue()[0]);
        }
        if (snap.isEmpty()) {
            sb.append("\n  (none yet)");
        }
        sb.append("\n[debugstatus] total player coins: ").append(data.totalPlayerCoins());
        String text = sb.toString();
        DEBUG_LOG.info(text);
        for (String line : text.split("\n")) {
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return snap.size();
    }

    /**
     * [Purpose] /oremarket testshort [balance] [price] [poolLeft] - a pure in-memory rehearsal of one-click short arithmetic:
     * it runs the production ShortPoolCalculator and a DebugCalculator using the server's open-time rounding basis;
     * no packets, no deductions, no persistence. The defaults are the requirement's example: balance 1000, price 10.
     * [Worked example] 1000, half-position 500 / (10 x 1.5 + 10 x feeTax) -> floored shares, then min with the pool left.
     */
    private static int testShort(CommandContext<CommandSourceStack> ctx,
                                 long balance, double price, int pool) {
        cn.oremarket.market.MarketSettings ms =
                cn.oremarket.market.MarketSettings.server();
        double ratio = ms == null ? 1.5 : ms.shortMarginRatio;
        double feeTax = ms == null ? 0.03 : cn.oremarket.common.Fees
                .combined(cn.oremarket.common.Fees.effectiveFeeRate(ms.feeRate()),
                        ms.taxRate());
        int poolLeft = pool >= 0 ? pool : (ms == null ? 5000
                : ms.shortPoolDefaultShares);   // default pool = the base-share equivalent (shares at price ~= 1, enough for a demo)
        int maxOrder = ms == null ? 1_000_000 : ms.maxTradeQuantity;
        cn.oremarket.common.DebugCalculator.SimResult r =
                cn.oremarket.common.DebugCalculator
                        .simulateShortOpen(balance, price, ratio, feeTax, poolLeft, maxOrder);
        return emitSandbox(ctx, r);
    }

    /**
     * [Purpose] /oremarket testloan [principal] [installments] - a pure in-memory rehearsal of Equal-Principal installment splitting:
     * it calls the very same LoanSchedule.build as production, verifying the ledger identity
     * 'remainder to the final installment, sum == principal' for indivisible cases.
     * [Worked example] 1000 over 3 installments = 333 / 333 / 334, sum = 1000.
     */
    private static int testLoan(CommandContext<CommandSourceStack> ctx, long principal, int installments) {
        cn.oremarket.common.DebugCalculator.SimResult r =
                cn.oremarket.common.DebugCalculator
                        .simulateLoanSplit(principal, installments);
        return emitSandbox(ctx, r);
    }

    /** Sandbox output: printed to console and chat in sync; a failed assertion line is red and echoes [TEST FAILED]. */
    private static int emitSandbox(CommandContext<CommandSourceStack> ctx,
                                   cn.oremarket.common.DebugCalculator.SimResult r) {
        StringBuilder sb = new StringBuilder();
        for (String line : r.lines) {
            sb.append(line).append('\n');
        }
        DEBUG_LOG.info(sb.toString().trim());
        for (String line : r.lines) {
            Component c = r.passed || !line.contains("[TEST FAILED]")
                    ? Component.literal(line)
                    : Component.literal(line).withStyle(ChatFormatting.RED);
            ctx.getSource().sendSuccess(() -> c, false);
        }
        return r.passed ? 1 : 0;
    }

    /** Called by the tick event. */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        MarketEngine.onServerTick(server);
    }
}
