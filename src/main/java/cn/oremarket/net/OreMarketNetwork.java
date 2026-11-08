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

package cn.oremarket.net;

import cn.oremarket.OreMarketMod;
import cn.oremarket.client.ClientPlay;
import cn.oremarket.market.MarketEngine;
import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.MarketSettings;
import cn.oremarket.market.LoanService;
import cn.oremarket.market.Stock;
import cn.oremarket.market.StockRegistry;
import cn.oremarket.market.TradeService;
import cn.oremarket.net.packet.C2SCloseUiPacket;
import cn.oremarket.net.packet.C2SExchangePacket;
import cn.oremarket.net.packet.C2SLimitPacket;
import cn.oremarket.net.packet.C2SLoanPacket;
import cn.oremarket.net.packet.C2SOrderPacket;
import cn.oremarket.net.packet.C2SRequestBookPacket;
import cn.oremarket.net.packet.C2SShortPacket;
import cn.oremarket.net.packet.C2SRequestSnapshotPacket;
import cn.oremarket.net.packet.C2STradePacket;
import cn.oremarket.net.packet.ErrorPacket;
import cn.oremarket.net.packet.MarketSnapshotPacket;
import cn.oremarket.net.packet.MarketTickPacket;
import cn.oremarket.net.packet.RequestConfigPacket;
import cn.oremarket.net.packet.RequestMarketSnapshotPacket;
import cn.oremarket.net.packet.S2CLeaderboardPacket;
import cn.oremarket.net.packet.S2COrderBookPacket;
import cn.oremarket.net.packet.SpawnParticlePacket;
import cn.oremarket.net.packet.SyncConfigPacket;
import cn.oremarket.net.packet.SyncPlayerStatePacket;
import cn.oremarket.net.packet.S2CFeedbackPacket;
import cn.oremarket.net.packet.S2CLogPacket;
import cn.oremarket.net.packet.S2COpenUiPacket;
import cn.oremarket.net.packet.S2CSnapshotPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Networking layer: the client only renders and sends packets; exchange, trading and prices are entirely server-authoritative.
 * The integrated server embedded in single-player and the dedicated server run the same code.
 *
 * - added MarketTickPacket (per-marketTick delta), MarketSnapshotPacket (full quotes on UI open),
 * ErrorPacket (server rejection reasons), RequestMarketSnapshotPacket (C2S quote request);
 * - removed S2CDeltaPacket / KlineSnapshot: quote history rides only the two new packets;
 * - C2S trade/exchange packets carry stock CODE strings rather than indices: validated via StockRegistry.getByCode,
 * an invalid or delisted code is refused outright with an ErrorPacket;
 * - SyncConfigPacket embeds the protocol version: the client checks on every receipt and closes the market screen
 * on mismatch; the server likewise ignores old-protocol requests and replies with an error (the Forge handshake
 * already covers most cases; this is the double lock).
 */
public final class OreMarketNetwork {
    /**
     * Protocol version 17: event module snapshots carry the realized one-shot value.
     * The Forge handshake and every request-side protocol check use this single version gate,
     * so a client with the old event-view field layout is rejected before it can misread packets.
     */
    public static final String PROTOCOL = "17";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new net.minecraft.resources.ResourceLocation(OreMarketMod.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private static int id = 0;

    /**
     * C2S read-amplification guard: request-type packets (config / snapshot / full quotes / order book / leaderboard)
     * each make the server do a full-table scan and ship a big packet; a tampered client blasting them per tick could amplify CPU
     * and bandwidth tens of times. Per-player, per-request-kind cooldown of 500 ms; over that the packet is silently dropped.
     * A legitimate client sends each kind once when opening the screen and retries no faster than every 5 s, so legal traffic never hits the cooldown.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> REQUEST_GATE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REQUEST_GATE_MS = 500L;

    /** Shared read-amplification gate for request handlers and stale-view recovery. */
    public static boolean requestTooFast(java.util.UUID playerId, String kind) {
        String key = playerId + "|" + kind;
        long now = System.currentTimeMillis();
        Long prev = REQUEST_GATE.get(key);
        if (prev != null && now - prev < REQUEST_GATE_MS) {
            return true;
        }
        REQUEST_GATE.put(key, now);
        return false;
    }

    /** Player leaves: drop their cooldown records. */
    public static void clearRequestGate(java.util.UUID playerId) {
        String prefix = playerId + "|";
        REQUEST_GATE.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Server stopping: clear every request-cooldown table. */
    public static void clearAllRequestGates() {
        REQUEST_GATE.clear();
    }

    private OreMarketNetwork() {
    }

    public static void init() {
        CHANNEL.registerMessage(id++, C2SRequestSnapshotPacket.class,
                C2SRequestSnapshotPacket::encode, C2SRequestSnapshotPacket::decode, OreMarketNetwork::onRequestSnapshot);
        CHANNEL.registerMessage(id++, RequestMarketSnapshotPacket.class,
                RequestMarketSnapshotPacket::encode, RequestMarketSnapshotPacket::decode,
                OreMarketNetwork::onRequestMarketSnapshot);
        CHANNEL.registerMessage(id++, C2SCloseUiPacket.class,
                C2SCloseUiPacket::encode, C2SCloseUiPacket::decode, OreMarketNetwork::onCloseUi);
        CHANNEL.registerMessage(id++, C2SExchangePacket.class,
                C2SExchangePacket::encode, C2SExchangePacket::decode, OreMarketNetwork::onExchange);
        CHANNEL.registerMessage(id++, C2STradePacket.class,
                C2STradePacket::encode, C2STradePacket::decode, OreMarketNetwork::onTrade);
        CHANNEL.registerMessage(id++, S2COpenUiPacket.class,
                S2COpenUiPacket::encode, S2COpenUiPacket::decode, OreMarketNetwork::onOpenUi);
        CHANNEL.registerMessage(id++, S2CSnapshotPacket.class,
                S2CSnapshotPacket::encode, S2CSnapshotPacket::decode, OreMarketNetwork::onSnapshot);
        CHANNEL.registerMessage(id++, MarketTickPacket.class,
                MarketTickPacket::encode, MarketTickPacket::decode, OreMarketNetwork::onMarketTick);
        CHANNEL.registerMessage(id++, MarketSnapshotPacket.class,
                MarketSnapshotPacket::encode, MarketSnapshotPacket::decode, OreMarketNetwork::onMarketSnapshot);
        CHANNEL.registerMessage(id++, ErrorPacket.class,
                ErrorPacket::encode, ErrorPacket::decode, OreMarketNetwork::onError);
        CHANNEL.registerMessage(id++, S2CFeedbackPacket.class,
                S2CFeedbackPacket::encode, S2CFeedbackPacket::decode, OreMarketNetwork::onFeedback);
        CHANNEL.registerMessage(id++, C2SLoanPacket.class,
                C2SLoanPacket::encode, C2SLoanPacket::decode, OreMarketNetwork::onLoan);
        CHANNEL.registerMessage(id++, S2CLogPacket.class,
                S2CLogPacket::encode, S2CLogPacket::decode, OreMarketNetwork::onLog);
        // Config sync: C2S request -> S2C full config + parsed stock registry; player state rides its own S2C packet
        CHANNEL.registerMessage(id++, RequestConfigPacket.class,
                RequestConfigPacket::encode, RequestConfigPacket::decode, OreMarketNetwork::onRequestConfig);
        CHANNEL.registerMessage(id++, SyncConfigPacket.class,
                SyncConfigPacket::encode, SyncConfigPacket::decode, OreMarketNetwork::onSyncConfig);
        CHANNEL.registerMessage(id++, SyncPlayerStatePacket.class,
                SyncPlayerStatePacket::encode, SyncPlayerStatePacket::decode, OreMarketNetwork::onSyncPlayerState);
        // Shorts / order book / limit orders / leaderboard
        CHANNEL.registerMessage(id++, C2SShortPacket.class,
                C2SShortPacket::encode, C2SShortPacket::decode, OreMarketNetwork::onShort);
        CHANNEL.registerMessage(id++, C2SOrderPacket.class,
                C2SOrderPacket::encode, C2SOrderPacket::decode, OreMarketNetwork::onOrder);
        CHANNEL.registerMessage(id++, C2SLimitPacket.class,
                C2SLimitPacket::encode, C2SLimitPacket::decode, OreMarketNetwork::onLimit);
        CHANNEL.registerMessage(id++, C2SRequestBookPacket.class,
                C2SRequestBookPacket::encode, C2SRequestBookPacket::decode, OreMarketNetwork::onRequestBook);
        CHANNEL.registerMessage(id++, S2COrderBookPacket.class,
                S2COrderBookPacket::encode, S2COrderBookPacket::decode, OreMarketNetwork::onOrderBook);
        CHANNEL.registerMessage(id++, S2CLeaderboardPacket.class,
                S2CLeaderboardPacket::encode, S2CLeaderboardPacket::decode, OreMarketNetwork::onLeaderboard);
        // Protocol 10: particle effects (S2C)
        CHANNEL.registerMessage(id++, SpawnParticlePacket.class,
                SpawnParticlePacket::write, SpawnParticlePacket::read, OreMarketNetwork::onSpawnParticle);
    }

    /** S2C particle packet: runnable only on the client; the level null-check lives in ClientPlay. */
    private static void onSpawnParticle(SpawnParticlePacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> cn.oremarket.client.ClientPlay.playParticle(msg)));
        ctx.setPacketHandled(true);
    }

    // ------------------------------------------------------------ Shorts / orders / limit orders / leaderboard: server entries

    /** Server -> client: the latest leaderboard results. */
    public static void sendLeaderboard(ServerPlayer player) {
        if (player == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), leaderboardPacket(player));
    }

    /** Assemble the leaderboard packet (server main thread). */
    public static S2CLeaderboardPacket leaderboardPacket(ServerPlayer player) {
        cn.oremarket.market.LeaderboardService.Snapshot snap =
                cn.oremarket.market.LeaderboardService.last();
        net.minecraft.nbt.CompoundTag payload = new net.minecraft.nbt.CompoundTag();
        payload.put("assets", boardTag(snap.assets, player));
        payload.put("daily", boardTag(snap.daily, player));
        payload.put("positions", boardTag(snap.positions, player));
        payload.put("debt", boardTag(snap.debt, player));
        payload.put("credit", boardTag(snap.credit, player));
        // the receiver's own rank and value (per board): rank -1 when off-board
        payload.putInt("rankAssets", snap.assets.rankOf(player.getUUID()));
        payload.putInt("rankDaily", snap.daily.rankOf(player.getUUID()));
        payload.putInt("rankPositions", snap.positions.rankOf(player.getUUID()));
        payload.putInt("rankDebt", snap.debt.rankOf(player.getUUID()));
        payload.putInt("rankCredit", snap.credit.rankOf(player.getUUID()));
        return new S2CLeaderboardPacket(payload);
    }

    private static net.minecraft.nbt.ListTag boardTag(
            cn.oremarket.market.LeaderboardService.Board board,
            ServerPlayer viewer) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        if (board == null) {
            return list;
        }
        for (cn.oremarket.market.LeaderboardService.Entry e : board.top) {
            net.minecraft.nbt.CompoundTag en = new net.minecraft.nbt.CompoundTag();
            en.putString("uuid", e.id.toString());
            en.putString("name", e.name);
            en.putLong("value", e.value);
            list.add(en);
        }
        return list;
    }

    /**
     * The server-side second hard validation for trade-type C2S packets (golden rule: every trade operation must be hard-validated server-side).
     * Stock missing / delisted (retired flag or the save's delisting mark) / market state absent / current price non-positive or non-finite
     * - all count as 'frozen / overdue-reclaimed / delisted' and are refused outright with an ErrorPacket.
     * Returning true = rejected and the caller must return immediately. No provisional funds lock here: the three entries deduct margin /
     * locked resources only after their own Services validate everything, so rejections happen before any charge and refunds are structurally unnecessary.
     */
    private static boolean rejectUntradable(ServerPlayer player, Stock stock, String code) {
        if (player == null) {
            return true;
        }
        if (stock == null) {
            pushError(player, "oremarket.error.invalid_stock_code", code == null ? "" : code);
            return true;
        }
        MarketSavedData data = MarketSavedData.get(player.server);
        if (stock.retired() || data.isCodeRetiredFlagged(stock.code())) {
            pushError(player, "oremarket.error.stock_untradable");
            return true;
        }
        cn.oremarket.common.StockState st = data.state(stock);
        if (st == null || !(st.price > 0) || !Double.isFinite(st.price)) {
            pushError(player, "oremarket.error.stock_untradable");
            return true;
        }
        return false;
    }

    private static void onShort(C2SShortPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            cn.oremarket.market.Stock stock =
                    cn.oremarket.market.StockRegistry.server()
                            .getByCode(msg.stockCode);
            if (msg.action == C2SShortPacket.ACTION_OPEN) {
                // Open position: hard-validated at the entry; frozen / overdue-reclaimed / delisted / no valid quote all rejected with ErrorPacket.
                if (rejectUntradable(player, stock, msg.stockCode)) {
                    return;
                }
                cn.oremarket.market.ShortService.open(player, stock, msg.quantity);
            } else if (msg.action == C2SShortPacket.ACTION_CLOSE) {
                // Close position: an invalid stock code or missing server-issued position ID is refused before any business code runs.
                // The service repeats the ID check after looking up the current position, so forged, replayed and stale packets cannot
                // fall back to the old code-only close path.
                if (stock == null) {
                    pushError(player, "oremarket.error.invalid_stock_code", msg.stockCode);
                    return;
                }
                if (!cn.oremarket.market.ShortPosition.validCloseId(msg.positionId)) {
                    pushError(player, "oremarket.short.err.stale");
                    cn.oremarket.market.MarketEngine.pushAfterAction(
                            player.server, player);
                    return;
                }
                cn.oremarket.market.ShortService.close(
                        player, stock, msg.quantity, msg.positionId);
            } else {
                // Do not reinterpret unknown action codes as a close request; malformed clients must never trigger a financial operation.
                pushError(player, "oremarket.error.generic");
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void onOrder(C2SOrderPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (msg.action == C2SOrderPacket.ACTION_PLACE) {
                cn.oremarket.market.Stock stock =
                        cn.oremarket.market.StockRegistry.server()
                                .getByCode(msg.stockCode);
                // Order-placement entry hard validation; frozen / overdue-reclaimed / delisted / no valid quote all rejected with ErrorPacket.
                if (rejectUntradable(player, stock, msg.stockCode)) {
                    return;
                }
                cn.oremarket.market.OrderBookService.place(
                        player, stock, msg.buy, msg.quantity, msg.price);
            } else if (msg.action == C2SOrderPacket.ACTION_TAKE) {
                cn.oremarket.market.OrderBookService.take(
                        player, msg.orderId, msg.quantity);
            } else if (msg.action == C2SOrderPacket.ACTION_CANCEL) {
                cn.oremarket.market.OrderBookService.cancel(player, msg.orderId);
            } else {
                // Unknown action codes are malformed packets, never implicit cancellations.
                pushError(player, "oremarket.error.generic");
                return;
            }
            cn.oremarket.market.MarketEngine.pushAfterAction(
                    player.server, player);
        });
        ctx.setPacketHandled(true);
    }

    private static void onLimit(C2SLimitPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (msg.action == C2SLimitPacket.ACTION_PLACE) {
                cn.oremarket.market.Stock stock =
                        cn.oremarket.market.StockRegistry.server()
                                .getByCode(msg.stockCode);
                // Limit-order entry hard validation; frozen / overdue-reclaimed / delisted / no valid quote all rejected with ErrorPacket.
                if (rejectUntradable(player, stock, msg.stockCode)) {
                    return;
                }
                cn.oremarket.market.LimitOrderService.place(
                        player, stock, msg.buy, msg.quantity, msg.triggerPrice);
            } else {
                cn.oremarket.market.LimitOrderService.cancel(player, msg.orderId);
            }
            cn.oremarket.market.MarketEngine.pushAfterAction(
                    player.server, player);
        });
        ctx.setPacketHandled(true);
    }

    private static void onRequestBook(C2SRequestBookPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (!C2SRequestBookPacket.isValidKind(msg.kind)) {
                pushError(player, "oremarket.error.generic");
                return;
            }
            if (requestTooFast(player.getUUID(), "book" + msg.kind)) {
                return;   // Read-amplification guard: order-book full scans / leaderboard likewise
            }
            if (msg.kind == C2SRequestBookPacket.KIND_ORDER_BOOK) {
                cn.oremarket.market.OrderBookService.requestFull(player);
            } else {
                sendLeaderboard(player);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void onOrderBook(S2COrderBookPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPlay.applyOrderBook(msg)));
        ctx.setPacketHandled(true);
    }

    private static void onLeaderboard(S2CLeaderboardPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPlay.applyLeaderboard(msg)));
        ctx.setPacketHandled(true);
    }

    // ------------------------------------------------------------ Config and player-state push entries

    /** Proactive server push (hot-reload broadcasts etc.): stamps SyncConfigPacket with isResponse=0. */
    public static void syncConfig(ServerPlayer player, MarketSettings settings) {
        syncConfig(player, settings, false);
    }

    /** Push one config snapshot to a single player; isResponse=true marks it as the reply to a RequestConfigPacket. */
    public static void syncConfig(ServerPlayer player, MarketSettings settings, boolean isResponse) {
        if (player == null || settings == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                SyncConfigPacket.of(settings, isResponse));
    }

    /** After a config hot reload: push the new config to all online players (must come BEFORE player-state packets; proactive pushes are non-responses). */
    public static void syncConfigToAll(net.minecraft.server.MinecraftServer server, MarketSettings settings) {
        if (server == null) {
            return;
        }
        MarketSettings ms = settings != null ? settings : MarketSettings.server();
        CHANNEL.send(PacketDistributor.ALL.noArg(), SyncConfigPacket.of(ms, false));
    }

    /** Push one player's level and peak. */
    public static void sendPlayerState(ServerPlayer player, long highestLevel, long peakOreCoins) {
        if (player == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncPlayerStatePacket(highestLevel, peakOreCoins));
    }

    /** After a config reload push every online player's level / peak (after the config packet). */
    public static void syncPlayerStateToAll(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        MarketSavedData data = MarketSavedData.get(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendPlayerState(p, data.highestLevelOf(p.getUUID()), data.peak(p.getUUID()));
        }
    }

    /** Push order once a hot reload completes: SyncConfigPacket -> SyncPlayerStatePacket -> MarketSnapshotPacket. */
    public static void pushReloadBundle(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        MarketSavedData data = MarketSavedData.get(server);
        syncConfigToAll(server, MarketSettings.server());
        syncPlayerStateToAll(server);
        // after a hot reload the order book and boards may have changed, so push per player with the screen open
        cn.oremarket.market.OrderBookService.pushAll(server, data);
        MarketSnapshotPacket snap = MarketEngine.snapshotPacket(data);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (MarketEngine.WATCHERS.contains(p.getUUID())) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), snap);
            }
        }
    }

    /** Server rejection / error notice: shown for 2 s in the red area at the screen bottom while the client UI is open. */
    public static void pushError(ServerPlayer player, String key, String... args) {
        if (player == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ErrorPacket(key, args == null ? java.util.List.of() : java.util.List.of(args)));
    }

    /** First UI open: chat hints 'coin exchange is irreversible, proceed carefully' and the Ctrl+G hotkey, deduplicated via the player NBT oremarket:hint_shown. */
    public static final String HINT_KEY = "oremarket:hint_shown";

    public static void showFirstTimeHint(ServerPlayer player) {
        if (!player.getPersistentData().getBoolean(HINT_KEY)) {
            player.getPersistentData().putBoolean(HINT_KEY, true);
            player.displayClientMessage(Component.translatable("oremarket.hint.exchange")
                    .withStyle(ChatFormatting.GOLD), false);
            player.displayClientMessage(Component.translatable("oremarket.hint.hotkey")
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    /** Protocol double lock: the request's protocol differs from the server's -> ignore the business logic + reply an error. */
    private static boolean protocolOk(String requested, ServerPlayer player) {
        if (PROTOCOL.equals(requested)) {
            return true;
        }
        pushError(player, "oremarket.error.protocol_mismatch", requested, PROTOCOL);
        return false;
    }

    // ------------------------------------------------------------ C2S

    private static void onRequestSnapshot(C2SRequestSnapshotPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || player.server == null) {
                return;
            }
            if (requestTooFast(player.getUUID(), "snapshot")) {
                return;   // read-amplification guard: silent drop (a normal client never re-requests within 1 s)
            }
            MarketEngine.WATCHERS.add(player.getUUID());
            showFirstTimeHint(player);
            MarketSavedData data = MarketSavedData.get(player.server);
            // while nobody watches the leaderboard skips its timed recompute; right after the screen opens refresh once from the current quotes so no stale cache shows
            cn.oremarket.market.LeaderboardService
                    .refreshIfStale(player.server, data);
            // UI open order: config (incl. the stock registry) -> player state -> player-data snapshot -> full market snapshot
            syncConfig(player, MarketSettings.server());
            sendPlayerState(player, data.highestLevelOf(player.getUUID()), data.peak(player.getUUID()));
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    S2CSnapshotPacket.forPlayer(data, player.getUUID()));
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    MarketEngine.snapshotPacket(data).asResponse());
        });
        ctx.setPacketHandled(true);
    }

    /** Full market quote request (sent on screen open and on timeout retry): only answers with MarketSnapshotPacket. */
    private static void onRequestMarketSnapshot(RequestMarketSnapshotPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || player.server == null) {
                return;
            }
            if (!protocolOk(msg.protocol(), player)) {
                return;
            }
            if (requestTooFast(player.getUUID(), "market")) {
                return;   // read-amplification guard: full quotes (60 history points + 80 candles per stock) would explode bandwidth if spammed
            }
            MarketEngine.WATCHERS.add(player.getUUID());
            MarketSavedData data = MarketSavedData.get(player.server);
            cn.oremarket.market.LeaderboardService
                    .refreshIfStale(player.server, data);
            // this is a request-response: stamp the resp flag (the client uses the timestamp for the 2 s race drop)
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    MarketEngine.snapshotPacket(data).asResponse());
        });
        ctx.setPacketHandled(true);
    }

    private static void onCloseUi(C2SCloseUiPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                MarketEngine.WATCHERS.remove(player.getUUID());
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void onExchange(C2SExchangePacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            // stock code validation: invalid or delisted codes refused outright with an ErrorPacket
            Stock stock = StockRegistry.server().getByCode(msg.stockCode);
            if (stock == null || stock.retired()) {
                pushError(player, "oremarket.error.invalid_stock_code", msg.stockCode);
                return;
            }
            switch (msg.action) {
                case C2SExchangePacket.DEPOSIT_ONE -> TradeService.deposit(player, stock, 1);
                case C2SExchangePacket.DEPOSIT_TEN -> TradeService.deposit(player, stock, 10);
                case C2SExchangePacket.DEPOSIT_ALL_ROW -> TradeService.deposit(player, stock, 0);
                case C2SExchangePacket.WITHDRAW_ONE -> TradeService.withdraw(player, stock, 1);
                case C2SExchangePacket.WITHDRAW_TEN -> TradeService.withdraw(player, stock, 10);
                // WITHDRAW_AMOUNT stays as the ordinary take mode behind the 'take all' button (the bottom-toolbar and one-click-take-all variants were removed)
                case C2SExchangePacket.WITHDRAW_AMOUNT -> TradeService.withdraw(player, stock, msg.amount);
                // The exchange's right-column 'custom amount deposit'; the server re-clamps against real inventory counts (anti-tamper).
                case C2SExchangePacket.DEPOSIT_AMOUNT -> TradeService.deposit(player, stock, msg.amount);
                // Take-all-at-once - the amount field is ignored entirely; the server reads the balance +
                // simulates insertion via Inventory.add, takes only what fits, and replies 'took out only X' when short.
                case C2SExchangePacket.WITHDRAW_ALL -> TradeService.withdrawAll(player, stock);
                default -> {
                }
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void onTrade(C2STradePacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            // stock code validation: invalid or delisted codes refused outright with an ErrorPacket
            Stock stock = StockRegistry.server().getByCode(msg.stockCode);
            if (stock == null || stock.retired()) {
                pushError(player, "oremarket.error.invalid_stock_code", msg.stockCode);
                return;
            }
            TradeService.trade(player, stock, msg.qty, msg.buy);
        });
        ctx.setPacketHandled(true);
    }

    private static void onLoan(C2SLoanPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            // unwaived debt / collateral / short positions / product and installment legality are all recomputed server-side by LoanService.
            switch (msg.action) {
                case C2SLoanPacket.ACTION_BORROW -> {
                    long amount = msg.amount;
                    if (amount == C2SLoanPacket.STEP_ALL) {
                        // 'borrow max' is decided by the server from that product's dynamic limit (client-computed limits are never trusted)
                        amount = LoanService.dynamicLimit(MarketSavedData.get(player.server),
                                player.getUUID(), cn.oremarket.market.LoanData
                                .clampProduct(msg.product));
                    }
                    LoanService.borrow(player, amount, msg.product, msg.mode, msg.installments);
                }
                // "Repay all" and the fixed-amount buttons share the normal current-bill entry; only
                // the dedicated prepay button routes to LoanService.prepay (see the two explicit methods).
                case C2SLoanPacket.ACTION_REPAY -> LoanService.repayCurrentBill(player);
                case C2SLoanPacket.ACTION_REPAY_AMOUNT -> LoanService.repayCurrentBill(player,
                        msg.amount == C2SLoanPacket.STEP_ALL ? Long.MAX_VALUE : msg.amount);
                case C2SLoanPacket.ACTION_PREPAY -> LoanService.prepay(player);
                case C2SLoanPacket.ACTION_P2P_LIST -> LoanService.p2pList(player,
                        msg.amount, msg.ratePercentTimes100 / 100.0, msg.termDays);
                case C2SLoanPacket.ACTION_P2P_CANCEL -> LoanService.p2pCancel(player, msg.listingId);
                case C2SLoanPacket.ACTION_P2P_TAKE -> LoanService.p2pTake(player, msg.listingId);
                // never eaten by the active loan's principal-and-interest; how much and which debt first is all recomputed by LoanService.repayHangover server-side.
                case C2SLoanPacket.ACTION_REPAY_HANGOVER -> LoanService.repayHangover(player, msg.amount);

                default -> {
                    // unknown intent: do nothing (never guess a dirty packet)
                }
            }
        });
        ctx.setPacketHandled(true);
    }

    // ------------------------------------------------------------ S2C (client-only execution)

    private static void onOpenUi(S2COpenUiPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPlay.openUi()));
        ctx.setPacketHandled(true);
    }

    private static void onSnapshot(S2CSnapshotPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPlay.applySnapshot(msg)));
        ctx.setPacketHandled(true);
    }

    /** Market delta packet: processed only when 'screen open + registry and caches ready'; see ClientPlay / MarketClientState. */
    private static void onMarketTick(MarketTickPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPlay.applyMarketTick(msg)));
        ctx.setPacketHandled(true);
    }

    private static void onMarketSnapshot(MarketSnapshotPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPlay.applyMarketSnapshot(msg)));
        ctx.setPacketHandled(true);
    }

    private static void onError(ErrorPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPlay.showError(msg)));
        ctx.setPacketHandled(true);
    }

    private static void onFeedback(S2CFeedbackPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPlay.playFeedback(msg)));
        ctx.setPacketHandled(true);
    }

    /** C2S config request (UI open / click-retry after timeout): replies with the full config + player state in that fixed order; this is a response. */
    private static void onRequestConfig(RequestConfigPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || player.server == null) {
                return;
            }
            if (!protocolOk(msg.protocol(), player)) {
                return;
            }
            if (requestTooFast(player.getUUID(), "config")) {
                return;   // read-amplification guard: config serializes the entire stock registry
            }
            MarketSavedData data = MarketSavedData.get(player.server);
            syncConfig(player, MarketSettings.server(), true);
            sendPlayerState(player, data.highestLevelOf(player.getUUID()), data.peak(player.getUUID()));
        });
        ctx.setPacketHandled(true);
    }

    private static void onSyncConfig(SyncConfigPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPlay.applyConfig(msg)));
        ctx.setPacketHandled(true);
    }

    private static void onSyncPlayerState(SyncPlayerStatePacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPlay.applyPlayerState(msg)));
        ctx.setPacketHandled(true);
    }

    /** Log entry: the client inserts it at the head of its in-memory queue, capped at 100 lines. */
    private static void onLog(S2CLogPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPlay.addLog(msg)));
        ctx.setPacketHandled(true);
    }
}
