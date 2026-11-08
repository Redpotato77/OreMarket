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

import cn.oremarket.market.log.LogService;
import cn.oremarket.common.LogType;
import cn.oremarket.net.OreMarketNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Separation of level judgement and level display.
 *
 * Two lines:
 * - REALTIME level = the live peakOreCoins compared against the live config thresholds ({@link #realtimeLevel(long)}),
 * used for JUDGEMENT only: after thresholds are raised, new players must accumulate again before advancing;
 * - ALL-TIME highest level highestLevel = monotonically increasing, used for DISPLAY + PERMISSIONS:
 * the top-bar 'Level N/4', row dots, exchange and trading unlocks, loan eligibility and credit limit all read it;
 * once unlocked, a right is never revoked.
 *
 * The three recalculation moments (all on the server main thread only):
 * 1. config hot reload -> the END phase of ServerTickEvent (marketTick runs in START; different phases never overlap),
 * see {@link MarketReloadService};
 * 2. player login -> the NEXT tick of ServerTickEvent, ordered 'starting coins first, highestLevel recalc second';
 * recalculating directly in PlayerLoggedInEvent is forbidden ({@link #recalcOnLogin});
 * 3. peak raised while online -> {@code MarketSavedData.bumpPeak} lifts highestLevel immediately,
 * then {@link #announceIfNeeded} notifies, writes the log and pushes SyncPlayerStatePacket.
 *
 * Recalculation only READS peakOreCoins and never modifies it; starting coins and loans are excluded from the peak anyway.
 */
public final class LevelService {

    private LevelService() {
    }

    /** Realtime level: the peak compared against the realtime thresholds (1~4). */
    public static int realtimeLevel(long peak) {
        return MarketSettings.server().levelForPeak(peak);
    }

    /** A player's realtime level (reads the peak only). */
    public static int realtimeLevel(MarketSavedData data, UUID id) {
        return realtimeLevel(data.peak(id));
    }

    /** Level used for display and permissions = highestLevel. */
    public static int displayed(MarketSavedData data, UUID id) {
        return data.playerTier(id);
    }

    /** Unified unlock test: stock level <= highestLevel (built-ins and customs share one code path). */
    public static boolean unlocked(MarketSavedData data, UUID id, Stock stock) {
        return displayed(data, id) >= stock.tier();
    }

    /** Tooltip text: the threshold figures always come from the realtime config values. */
    public static String unlockHint(Stock stock) {
        return cn.oremarket.common.LogFormatter.format("oremarket.hint.unlock_peak", stock.tierUnlockPeak());
    }

    /** Raise highestLevel to max(old, realtime level): reads peakOreCoins only, grows only, setDirty immediately. Returns whether a raise happened. */
    public static boolean raise(MarketSavedData data, UUID id) {
        long realtime = realtimeLevel(data.peak(id));
        return data.raiseHighestLevel(id, realtime);
    }

    /** Post-raise notification: chat line 'Level raised to X' + one system log entry + immediate SyncPlayerStatePacket push. The first time a player is seen, only a baseline is established - no chat spam. */
    public static void announceIfNeeded(MinecraftServer server, MarketSavedData data, ServerPlayer player) {
        if (server == null || player == null) {
            return;
        }
        UUID id = player.getUUID();
        int now = displayed(data, id);
        Integer last = LogService.lastAnnouncedLevel(id);
        if (last != null && last == now) {
            return;
        }
        boolean hadBaseline = last != null;
        LogService.rememberAnnouncedLevel(id, now);
        if (!hadBaseline || now <= 1) {
            return;
        }
        player.displayClientMessage(Component.translatable("oremarket.level.log.unlock", now,
                        newlyUnlockedNames(now).isEmpty()
                                ? cn.oremarket.common.RawText.t(
                                        "oremarket.level.unlock.none")
                                : newlyUnlockedNames(now))
                .withStyle(ChatFormatting.GOLD), false);
        String names = newlyUnlockedNames(now);
        LogService.pushAllKey(server, data, LogType.SYSTEM, "oremarket.level.log.unlock", now,
                names.isEmpty()
                        ? cn.oremarket.common.RawText.t(
                                "oremarket.level.unlock.none")
                        : names);
    }

    /** Names of stocks newly unlocked at this level (for the log text). */
    private static String newlyUnlockedNames(int level) {
        StringBuilder sb = new StringBuilder();
        // Walk the server registry (built-in + custom, current active list); the level gate is shared
        for (Stock s : StockRegistry.getActiveStocks()) {
            if (s.tier() == level) {
                if (sb.length() > 0) {
                    sb.append(',').append(' ');
                }
                sb.append(s.displayName());
            }
        }
        return sb.toString();
    }

    /** After a config hot reload: walk every online player and recalculate highestLevel (reads the peak only, writes back with setDirty). */
    public static void recalcAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        MarketSavedData data = MarketSavedData.get(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            raise(data, p.getUUID());
            announceIfNeeded(server, data, p);
            push(data, p);
        }
        data.setDirty();
    }

    /**
     * After login (starting coins already granted) recalculate on the next tick.
     * Backfilling a missing highestLevel field in old saves is folded in here too - first compute the realtime
     * level from peakOreCoins under the currently effective thresholds, then write highestLevel = max(1, realtime level) with
     * setDirty. (Backfilling during SavedData load or inside PlayerLoggedInEvent is forbidden.)
     */
    public static void recalcOnLogin(MinecraftServer server, ServerPlayer player) {
        MarketSavedData data = MarketSavedData.get(server);
        java.util.UUID id = player.getUUID();
        if (!data.hasHighestLevelRecord(id)) {
            data.backfillHighestLevel(id, Math.max(1, realtimeLevel(data.peak(id))));
        }
        raise(data, id);
        announceIfNeeded(server, data, player);
        push(data, player);
    }

    /** Push one player's level and peak (SyncPlayerStatePacket). */
    public static void push(MarketSavedData data, ServerPlayer player) {
        OreMarketNetwork.sendPlayerState(player, data.highestLevelOf(player.getUUID()),
                data.peak(player.getUUID()));
    }

    /** Push all online players (after a config reload: SyncConfigPacket first, this second - never invert the order). */
    public static void pushAll(MinecraftServer server, MarketSavedData data) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            push(data, p);
        }
    }
}
