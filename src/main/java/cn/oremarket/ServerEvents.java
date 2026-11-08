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

package cn.oremarket;

import cn.oremarket.command.OreMarketCommand;
import cn.oremarket.market.LoanService;
import cn.oremarket.market.MarketEngine;
import cn.oremarket.market.LevelService;
import cn.oremarket.market.LeaderboardService;
import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.MarketSettings;
import cn.oremarket.market.MarketReloadService;
import cn.oremarket.market.log.LogService;
import cn.oremarket.common.LogType;
import cn.oremarket.net.OreMarketNetwork;
import cn.oremarket.net.packet.S2CSnapshotPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server event bindings. The two phases split duties cleanly:
 * - ServerTickEvent START: drives the market engine's marketTick;
 * - ServerTickEvent END: consumes pending starting-coin grants -> immediately recalculates that player's highestLevel
 * (granting always precedes recalculation), and performs the config hot reload's recalculation.
 * This way marketTick and config recalculation never overlap within one tick (mixing a hot reload with
 * the quote feed in one phase would produce prices built 'half on old, half on new' parameters).
 *
 * On player login the chat notices for loan liquidations / bankruptcies that happened while offline are re-sent;
 * starting coins in PlayerLoggedInEvent ONLY RECORD the pending-grant flag - player NBT is never touched,
 * no coins are granted and no level is recalculated there.
 */
@Mod.EventBusSubscriber(modid = OreMarketMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEvents {

    /** Player NBT field: whether starting coins were already granted. getBoolean defaults to false when the field is absent, inherently null-pointer-proof. */
    public static final String STARTER_KEY = "oremarket:starter_granted";
    /** Pending-grant set: PlayerLoggedInEvent only adds UUIDs here; the next ServerTickEvent.END consumes them. */
    private static final Set<UUID> PENDING_STARTER = ConcurrentHashMap.newKeySet();
    /** Newly logged-in players awaiting level recalculation: right after the starting-coin grant finishes, within the same tick. */
    private static final Set<UUID> PENDING_LEVEL = ConcurrentHashMap.newKeySet();

    private ServerEvents() {
    }

    /** Market advancement lives in the START phase; hot reload and login recalculation in the END phase, so the two never run concurrently. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            MarketEngine.onServerTick(event.getServer());
            return;
        }
        if (event.phase == TickEvent.Phase.END) {
            // The END-phase order required: starting coins -> config hot reload -> highestLevel recalculation.
            // 1) starting coins: the login event only recorded the pending flag; the actual grant happens here (next tick)
            grantPendingStarterBalances(event.getServer());
            // 2) config hot reload (ModConfigEvent.Reloading only sets the flag; real recalculation and registry rebuild happen here)
            MarketReloadService.tickEnd(event.getServer());
            // 3) after the reload, run the post-login highestLevel recalculation (backfilling with the just-effective thresholds; order is mandatory)
            recalcLevelsForPendingLogins(event.getServer());
            // 4) every 5 seconds (100 game ticks) refresh the leaderboard in real time: daily gain = current total assets - dailyBaseline,
            // and push the freshest boards to all players whose screen is open.
            if (dataTickReady(event.getServer())) {
                refreshLeaderboard(event.getServer());
            }
        }
    }

    private static boolean dataTickReady(MinecraftServer server) {
        return server != null && server.overworld() != null
                && server.getTickCount() % 100 == 0;
    }

    /** Realtime leaderboard recomputation + push to WATCHERS (players with the UI open). */
    private static void refreshLeaderboard(MinecraftServer server) {
        if (server == null || MarketEngine.WATCHERS.isEmpty()) {
            return; // when nobody has the screen open, do not scan every historical player on the main thread every 5 seconds
        }
        MarketSavedData data = MarketSavedData.get(server);
        LeaderboardService.refresh(server, data);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (MarketEngine.WATCHERS.contains(p.getUUID())) {
                OreMarketNetwork.sendLeaderboard(p);
            }
        }
    }

    /**
     * Post-login recalculation of highestLevel: reads peakOreCoins only, grows only, writes back to SavedData and pushes
     * SyncPlayerStatePacket; if the level rose, announce in chat + write a log. Doing this directly in PlayerLoggedInEvent is forbidden.
     */
    private static void recalcLevelsForPendingLogins(MinecraftServer server) {
        if (server == null || PENDING_LEVEL.isEmpty()) {
            return;
        }
        for (UUID id : new ArrayList<>(PENDING_LEVEL)) {
            PENDING_LEVEL.remove(id);
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) {
                continue;    // player logged off right after the flag: re-register on the next login
            }
            LevelService.recalcOnLogin(server, sp);
        }
    }

    /**
     * Starting-coin grant: each player receives it exactly once, on their first join.
     * - the granted state is authoritative in the Overworld SavedData, migrating the legacy per-player NBT flag along the way;
     * - addBalanceNoPeak: excluded from the peak, the level still starts at 1, and no existing save data is affected;
     * - on granting, write one system log line 'received 100 starting coins' and push that player a full snapshot (coin-change packet);
     * - the server only updates NBT / balance / logs / packets and never touches a client Screen directly;
     * client refreshes are marshalled back to the main thread at the receiving end (see ClientPlay).
     */
    private static void grantPendingStarterBalances(MinecraftServer server) {
        if (server == null || PENDING_STARTER.isEmpty()) {
            return;
        }
        MarketSavedData data = MarketSavedData.get(server);
        for (UUID id : new ArrayList<>(PENDING_STARTER)) {
            PENDING_STARTER.remove(id);
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) {
                continue;    // player logged off right after the flag: re-registers on the next login
            }
            CompoundTag nbt = sp.getPersistentData();
            // The authoritative flag lives in the Overworld SavedData; the legacy NBT is only a one-time migration source, preventing double grants if the flag were lost.
            boolean firstEver = !data.isStarterGranted(id) && !nbt.getBoolean(STARTER_KEY);
            if (firstEver) {
                data.markStarterGranted(id);
                nbt.putBoolean(STARTER_KEY, true);
                // the amount reads from config (category 7 startingCoins); HARD-CODING 100 IS FORBIDDEN; 0 means no grant
                long grant = MarketSettings.server().startingCoins;
                if (grant > 0) {
                    data.addBalanceNoPeak(id, grant);
                    // starting coins do not count into peakOreCoins (addBalanceNoPeak); the level still starts from 1
                    sp.displayClientMessage(Component.translatable("oremarket.welcome.initial_grant", grant)
                            .withStyle(ChatFormatting.GOLD), false);
                    LogService.pushKey(server, data, id, LogType.SYSTEM, "oremarket.log.initial_grant", grant);
                } else {
                    // config says 0: skip the grant but set the flag to true right away so the next login stops retrying (avoids a per-tick re-check)
                    LogService.pushKey(server, data, id, LogType.SYSTEM, "oremarket.text.0526");
                }
            } else {
                // Old saves that have the NBT flag but no SavedData flag: migrate once, then SavedData rules.
                data.markStarterGranted(id);
                nbt.putBoolean(STARTER_KEY, true);
            }
            // Daily baseline: backfill only when the field is missing, exactly once (first join = firstLogin, old player missing the field = offlineLogin);
            // after backfill it is rewritten only at the next trading-day switch; repeated per-login backfills / resets are forbidden.
            // Starting coins are granted before the baseline is recorded, so the baseline naturally includes them.
            data.initDailyBaselineIfMissing(id, firstEver ? "firstLogin" : "offlineLogin");
            // right after the grant (or skip), push config + snapshot + player state: any of the three caches missing and the client shows 'loading'
            OreMarketNetwork.syncConfig(sp, MarketSettings.server());
            OreMarketNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                    S2CSnapshotPacket.forPlayer(data, id));
            LevelService.push(data, sp);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        OreMarketCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        MarketEngine.WATCHERS.remove(id);
        // if the player logs off before the END phase consumes the flag, the pending marks must not leak across sessions.
        PENDING_STARTER.remove(id);
        PENDING_LEVEL.remove(id);
        // A player's log clears when they leave and restarts on the next join (memory only, never persisted)
        LogService.onLogout(id);
        // On disconnect, clean every piece of this player's transient data
        // (contribution records, particle rate limits; contributions in the player-action windows are removed by UUID)
        cn.oremarket.market.event.PlayerVolumeTracker.onPlayerLogout(id);
        cn.oremarket.net.ParticleService.onPlayerLogout(id);
        // the request-cooldown table is cleaned per player too (the companion of read-amplification guards; no cross-session residue)
        cn.oremarket.net.OreMarketNetwork.clearRequestGate(id);
    }

    /**
     * Login re-send: chat notices for loans force-liquidated / bankruptcies settled while the player was offline;
     * starting coins: ONLY the pending-grant flag is recorded here - no player-NBT reads or writes, no coins issued.
     */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            // only register the flag here: no coins, no player-NBT reads or writes, no level recalculation
            PENDING_STARTER.add(sp.getUUID());
            PENDING_LEVEL.add(sp.getUUID());
            // the leaderboard and order list need every player name (offline included), written into SavedData on login.
            MarketSavedData.get(sp.server).rememberName(sp.getUUID(), sp.getName().getString());
            LoanService.onPlayerLogin(sp);
            // orders auto-cancelled while the player was offline: replay them as a chat line + toast
            cn.oremarket.market.OrderNoticeService.onPlayerLogin(sp);
            // while we are at it, push config and the stock registry to the just-joined client (ready before the UI opens, avoiding a permanent 'loading')
            OreMarketNetwork.syncConfig(sp, MarketSettings.server());
        }
    }

    /** Server starting: establish the first config snapshot, register the server thread, recalculate online players' levels under the current config. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MarketSettings.markServerThread(Thread.currentThread());
        MarketReloadService.bootstrap(event.getServer());
        // server startup self-check (pure logic + classpath lang key validation; no client classes involved).
        cn.oremarket.common.SanityCheck.runAndLog();
        // The i18n guard - lang key-set equality, no Chinese in en_us/zh_hk/zh_tw, zh_cn letter whitelist.
        cn.oremarket.common.I18nGuard.runStartupChecks();
        // When the switch is true, print an EYE-CATCHING YELLOW WARNING at startup (ANSI 33 = yellow).
        // Console log discipline: the text takes DebugLog's en_us templates (developer/server-owner output, 100% plain English);
        // when the switch is off, this block does not build a single character.
        if (cn.oremarket.common.DebugLog.on()) {
            org.slf4j.LoggerFactory.getLogger("OreMarket/Debug").warn(
                    "\u001B[33m\n"
                            + "********************************************************\n"
                            + "*  {}\n"
                            + "********************************************************\u001B[0m",
                    cn.oremarket.common.DebugLog.en(
                            "oremarket.debug.warn.enabled"));
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MarketEngine.WATCHERS.clear();
        PENDING_STARTER.clear();
        PENDING_LEVEL.clear();
        LogService.onServerStop();
        // Server stopping: clean every piece of in-memory state in the event subsystems (player-volume windows / particle rate limits / legendary preview queue)
        cn.oremarket.market.event.PlayerVolumeTracker.clearAll();
        cn.oremarket.market.event.EventPreviewService.clearAll();
        cn.oremarket.net.ParticleService.clearAll();
        // the event-frequency window is an absolute marketTick counter plus the combo gate
        cn.oremarket.market.event.EventEngine.clearAll();
        // board snapshots and refresh timestamps (if uncleared, refreshIfStale stays broken after a world switch and players see the old world's boards)
        cn.oremarket.market.LeaderboardService.clearAll();
        // offline liquidation-retry cooldown table
        cn.oremarket.market.LoanService.clearAll();
        // request cooldown table (read-amplification guard)
        cn.oremarket.net.OreMarketNetwork.clearAllRequestGates();
        MarketSavedData.invalidateCache();
        // do not leave the stopped server thread in static fields, which would keep the old thread context across world switches.
        MarketSettings.clearServerThread();
    }
}
