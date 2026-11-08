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

import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.MarketSettings;
import cn.oremarket.net.packet.SpawnParticlePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side particle pipeline.
 *
 * - Particles are only TRIGGERED server-side; the S2C {@link SpawnParticlePacket} lets the client play them;
 * the server renders nothing and no particle touches trading logic or the price engine;
 * - particle type strings are validated against {@code ForgeRegistries.PARTICLE_TYPES}:
 * {@code ResourceLocation.tryParse} failure or registry miss -> degrade to minecraft:smoke with a WARN;
 * - coordinates are always player-centered (the "whole screen" concept is banned):
 * buy above the head +1.5, sell at the feet, events scatter 8 points inside a radius-3 sphere around the player,
 * short-open 1 block in front, close 1 block behind, bankruptcy 10 points inside a radius-3 sphere;
 * - rate limit: two particle packets for one player are at least particleEffectSpawnIntervalTicks game ticks apart
 * (at most 1 packet per tick; bursting several within one marketTick is forbidden);
 * - protocol gate: the Forge handshake already guarantees connected clients use PROTOCOL(10),
 * so pre-10 clients never receive this packet (the sender blocks not-yet-ready connections once more here).
 */
public final class ParticleService {
    private static final Logger LOG = LoggerFactory.getLogger("OreMarket/Particles");

    /** Event particle sphere radius (blocks). */
    private static final double EVENT_SPHERE_RADIUS = 3.0;
    /** Event particle point count. */
    private static final int EVENT_POINTS = 8;
    /** Bankruptcy particle sphere radius (blocks). */
    private static final double BANKRUPTCY_SPHERE_RADIUS = 3.0;
    /** Bankruptcy particle point count. */
    private static final int BANKRUPTCY_POINTS = 10;
    /** Short open/close front-back offset (blocks). */
    private static final double SHORT_OFFSET_BLOCKS = 1.0;

    /** Player -> game tick of the last packet sent (rate limit; cleared on logout, never accumulates). */
    private static final Map<UUID, Long> LAST_SENT = new HashMap<>();
    /** Config string -> validation-result cache (cleared by clearCaches after a hot reload). */
    private static final Map<String, Boolean> TYPE_VALID_CACHE = new HashMap<>();

    private ParticleService() {
    }

    /** Trade fill (buy above the head / sell at the feet). */
    public static void playTrade(MinecraftServer server, ServerPlayer player, boolean buy) {
        MarketSettings ms = MarketSettings.server();
        if (!ms.particleEnabled || !ms.particleOnTrade) {
            return;
        }
        String type = buy ? ms.particleTypeBuy : ms.particleTypeSell;
        double y = buy ? player.getY() + 1.5 : player.getY();
        send(server, player, type, player.getX(), y, player.getZ(), ms.particleCount, ms.particleSpread);
    }

    /** Event trigger: pushed to every online player, 8 points scattered within a 3-block radius centered on each player. */
    public static void playEventAll(MinecraftServer server, MarketSavedData data) {
        MarketSettings ms = MarketSettings.server();
        if (!ms.particleEnabled || !ms.particleOnEvent || server == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            send(server, p, ms.particleTypeEvent, p.getX(), p.getY() + 0.5, p.getZ(),
                    EVENT_POINTS, EVENT_SPHERE_RADIUS);
        }
    }

    /** Short open: 1 block in front of the player. */
    public static void playShortOpen(MinecraftServer server, ServerPlayer player) {
        MarketSettings ms = MarketSettings.server();
        if (!ms.particleEnabled || !ms.particleOnShortOpen) {
            return;
        }
        var look = player.getLookAngle();
        send(server, player, ms.particleTypeShortOpen,
                player.getX() + look.x * SHORT_OFFSET_BLOCKS,
                player.getY() + 0.5 + look.y * SHORT_OFFSET_BLOCKS,
                player.getZ() + look.z * SHORT_OFFSET_BLOCKS,
                ms.particleCount, ms.particleSpread);
    }

    /** Short close: 1 block behind the player. */
    public static void playShortClose(MinecraftServer server, ServerPlayer player) {
        MarketSettings ms = MarketSettings.server();
        if (!ms.particleEnabled || !ms.particleOnShortClose) {
            return;
        }
        var look = player.getLookAngle();
        send(server, player, ms.particleTypeShortClose,
                player.getX() - look.x * SHORT_OFFSET_BLOCKS,
                player.getY() + 0.5 - look.y * SHORT_OFFSET_BLOCKS,
                player.getZ() - look.z * SHORT_OFFSET_BLOCKS,
                ms.particleCount, ms.particleSpread);
    }

    /** Bankruptcy liquidation: 10 points scattered inside a radius-3 sphere around the player. */
    public static void playBankruptcy(MinecraftServer server, ServerPlayer player) {
        MarketSettings ms = MarketSettings.server();
        if (!ms.particleEnabled || !ms.particleOnBankruptcy) {
            return;
        }
        send(server, player, ms.particleTypeBankruptcy, player.getX(), player.getY() + 0.5,
                player.getZ(), BANKRUPTCY_POINTS, BANKRUPTCY_SPHERE_RADIUS);
    }

    /** Player logout: drop the rate-limit record. */
    public static void onPlayerLogout(UUID id) {
        LAST_SENT.remove(id);
    }

    /** Play trade particles for an online player by UUID (paths that only have a UUID, e.g. order matching; offline players are skipped outright). */
    public static void playTradeByUuid(MinecraftServer server, UUID id, boolean buy) {
        if (server == null || id == null) {
            return;
        }
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) {
            playTrade(server, p, buy);
        }
    }

    /** Server stopping: clear everything. */
    public static void clearAll() {
        LAST_SENT.clear();
        clearCaches();
    }

    /** Clear the type-validation cache after a hot reload (particle ids in the config may have changed). */
    public static void clearCaches() {
        TYPE_VALID_CACHE.clear();
    }

    // ------------------------------------------------------------ Internal

    private static void send(MinecraftServer server, ServerPlayer player, String rawType,
                             double x, double y, double z, int count, double spread) {
        if (server == null || player == null) {
            return;
        }
        MarketSettings ms = MarketSettings.server();
        long now = server.getTickCount();
        Long last = LAST_SENT.get(player.getUUID());
        if (last != null && now - last < ms.particleSpawnIntervalTicks) {
            return;   // Rate limit: repeat triggers within the interval are dropped
        }
        String type = validateType(rawType);
        LAST_SENT.put(player.getUUID(), now);
        OreMarketNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new SpawnParticlePacket(type, x, y, z, count, spread, ms.particlePlaySoundEnabled));
    }

    /** Particle type validation: parse failure or missing from the registry -> degrade to minecraft:smoke with a WARN. */
    static String validateType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "minecraft:smoke";
        }
        String key = raw.trim().toLowerCase(java.util.Locale.ROOT);
        Boolean cached = TYPE_VALID_CACHE.get(key);
        boolean ok;
        if (cached != null) {
            ok = cached;
        } else {
            ok = isKnownParticle(key);
            TYPE_VALID_CACHE.put(key, ok);
        }
        if (ok) {
            return key;
        }
        LOG.warn("invalid particle type, downgraded to minecraft:smoke: {}",
                cn.oremarket.common.DebugLog.ascii(raw));
        return "minecraft:smoke";
    }

    private static boolean isKnownParticle(String key) {
        ResourceLocation id = ResourceLocation.tryParse(key);
        if (id == null) {
            return false;
        }
        return ForgeRegistries.PARTICLE_TYPES.containsKey(id);
    }

}
