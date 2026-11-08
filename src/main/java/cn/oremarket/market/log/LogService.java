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

package cn.oremarket.market.log;

import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.MarketSettings;
import cn.oremarket.market.Stock;
import cn.oremarket.net.OreMarketNetwork;
import cn.oremarket.net.packet.S2CLogPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import cn.oremarket.common.LogType;

/**
 * Server-side log queue (append: log tab 6):
 *  - logs are <b>not</b> written into SavedData; only an in-memory ring queue is kept on each side,
 *    server and client, at most 100 entries per player;
 *  - whenever an operation worth logging happens, the server pushes that single entry to the player
 *    over S2CLogPacket, and the client prepends it to its own queue and trims it to the limit;
 *  - once a player logs out, both the server-side and client-side queues are dropped, and the next
 *    session starts from scratch;
 *  - never put another player's sensitive information in here: each entry goes only to the player it
 *    concerns; truly global ones (event start / end) are broadcast, and their content covers only
 *    events and market quotes.
 * Tier-unlock detection also lives here: peak only ever grows, so a higher tier means "unlocked"
 * and the player gets a matching system log entry.
 */
public final class LogService {
    /** Per-player log cap (same limit on the server and client queues). */
    /** Deprecated: the entry cap now comes from the config option logMaxEntries (category 22). */
    @Deprecated
    public static final int MAX_ENTRIES = 100;

    /** Server-side in-memory queue per player (newest at the head). */
    private static final Map<UUID, Deque<MarketLogEntry>> LOGS = new HashMap<>();
    /** Last remembered tier: kept in memory only, used to detect "level up to tier X"; cleared on logout. */
    private static final Map<UUID, Integer> LAST_TIER = new HashMap<>();

    private LogService() {
    }

    /** Logs one entry for a given player: queued server-side and pushed if the player is online; skipped entirely when offline. */
    public static void push(MinecraftServer server, MarketSavedData data, UUID id, LogType type, String text) {
        pushEntry(server, data, id, type, new MarketLogEntry(data.tradeDay(), type, text));
    }

    /**
     * Keyed log entry: the server sends only a translation key + arguments, never a
     * fully assembled sentence any more. The fallback string here is filled on the spot from the
     * zh_cn baseline templates: when the key is unregistered or missing from the client's language
     * table, the UI falls back to the old whole-sentence reverse lookup, so an empty line never
     * shows up.
     */
    public static void pushKey(MinecraftServer server, MarketSavedData data, UUID id,
                               LogType type, String key, Object... args) {
        pushEntry(server, data, id, type, keyEntry(data, type, key, args));
    }

    public static void pushKey(ServerPlayer player, MarketSavedData data, LogType type,
                               String key, Object... args) {
        if (player == null) {
            return;
        }
        pushKey(player.server, data, player.getUUID(), type, key, args);
    }

    /** Global keyed log: pushes one entry to every online player; the content must not mention any player. */
    public static void pushAllKey(MinecraftServer server, MarketSavedData data, LogType type,
                                  String key, Object... args) {
        if (server == null || data == null) {
            return;
        }
        MarketLogEntry proto = keyEntry(data, type, key, args);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            pushEntry(server, data, p.getUUID(), type, proto);
        }
    }

    private static MarketLogEntry keyEntry(MarketSavedData data, LogType type, String key, Object... args) {
        java.util.List<String> flat = new java.util.ArrayList<>(args == null ? 0 : args.length);
        if (args != null) {
            for (Object a : args) {
                flat.add(a == null ? "" : String.valueOf(a));
            }
        }
        String fallback = cn.oremarket.common.LogFormatter.formatLoose(key, args);
        return new MarketLogEntry(data.tradeDay(), type, fallback, key, flat);
    }

    private static void pushEntry(MinecraftServer server, MarketSavedData data, UUID id,
                                  LogType type, MarketLogEntry entry) {
        if (server == null || data == null || id == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null) {
            return;     // Offline players are not logged: the logs live only in session memory, never in the save
        }
        LOGS.computeIfAbsent(id, k -> new ArrayDeque<>()).addFirst(entry);
        Deque<MarketLogEntry> q = LOGS.get(id);
        while (q.size() > MarketSettings.server().logMaxEntries) {
            q.removeLast();
        }
        OreMarketNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new S2CLogPacket(entry));
    }

    public static void push(ServerPlayer player, MarketSavedData data, LogType type, String text) {
        if (player == null) {
            return;
        }
        push(player.server, data, player.getUUID(), type, text);
    }

    /** Global log (event start / end): pushes one entry to every online player; the content mentions no player. */
    public static void pushAll(MinecraftServer server, MarketSavedData data, LogType type, String text) {
        if (server == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            push(server, data, p.getUUID(), type, text);
        }
    }

    /**
     * Tier-unlock detection: it watches highestLevel (only ever grows), so the tier can only go up.
     * The first time a player is seen, only the baseline is recorded and no log is appended (past
     * unlocks at login do not count as a "current event"). Whenever the tier rises, one
     * "level up to tier X, unlocked: ore names" entry is appended per newly reached tier; the ore
     * list is built from the stock list sent by the server, so custom stocks count in as well
     */
    public static void syncTier(MinecraftServer server, MarketSavedData data, ServerPlayer player) {
        if (server == null || data == null || player == null) {
            return;
        }
        UUID id = player.getUUID();
        int tier = data.playerTier(id);
        Integer prev = LAST_TIER.get(id);
        if (prev == null) {
            LAST_TIER.put(id, tier);
            return;
        }
        if (tier <= prev) {
            return;
        }
        for (int t = prev + 1; t <= tier; t++) {
            StringBuilder names = new StringBuilder();
            // Iterate the server-side registry (built-in + custom); no hardcoded enum order any more
            for (Stock s : cn.oremarket.market.StockRegistry.getActiveStocks()) {
                if (s.tier() == t) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(s.displayName());
                }
            }
            // Ship the key + args: the ore list goes over as one argument, and the client translates each fragment
            pushKey(server, data, id, LogType.SYSTEM, "oremarket.level.log.unlock", t,
                    names.length() == 0
                            ? cn.oremarket.common.RawText.t(
                                    "oremarket.level.unlock.none")
                            : names.toString());
        }
        LAST_TIER.put(id, tier);
        // Push highestLevel + peakOreCoins to the player the moment the tier changes (no waiting for the next marketTick)
        OreMarketNetwork.sendPlayerState(player, tier, data.peak(id));
    }

    /** Runs tier detection for every online player (once per marketTick; covers unusual balance changes such as forced sell / bankruptcy). */
    public static void syncTiers(MinecraftServer server, MarketSavedData data) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            syncTier(server, data, p);
        }
    }

    /** Baseline of the last announced tier (memory only): lets LevelService tell whether a real increase happened. */
    public static Integer lastAnnouncedLevel(UUID id) {
        return LAST_TIER.get(id);
    }

    /** Records the baseline of the last announced tier. */
    public static void rememberAnnouncedLevel(UUID id, int level) {
        LAST_TIER.put(id, level);
    }

    /** Player logout: drop the log queue and the tier baseline (requirement: start logging fresh on the next login). */
    public static void onLogout(UUID id) {
        LOGS.remove(id);
        LAST_TIER.remove(id);
    }

    /** Server stop: clear all in-memory queues. */
    public static void onServerStop() {
        LOGS.clear();
        LAST_TIER.clear();
    }
}
