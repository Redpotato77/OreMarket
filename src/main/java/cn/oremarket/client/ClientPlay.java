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

import cn.oremarket.net.OreMarketNetwork;
import cn.oremarket.net.packet.ErrorPacket;
import cn.oremarket.net.packet.MarketSnapshotPacket;
import cn.oremarket.net.packet.MarketTickPacket;
import cn.oremarket.net.packet.S2CFeedbackPacket;
import cn.oremarket.net.packet.S2CLogPacket;
import cn.oremarket.net.packet.S2CLeaderboardPacket;
import cn.oremarket.net.packet.S2COrderBookPacket;
import cn.oremarket.net.packet.S2CSnapshotPacket;
import cn.oremarket.net.packet.SyncConfigPacket;
import cn.oremarket.net.packet.SyncPlayerStatePacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

/**
 * Packet-handling logic allowed to run only on the client (Dist.CLIENT).
 * Trade feedback sounds: success = experience bottle, failure = low note-block pling; failures also leave a red hint at the screen bottom.
 *
 * - entry points for the three new packets MarketTickPacket / MarketSnapshotPacket / ErrorPacket;
 * - market packets write the cache ONLY WHILE THE SCREEN IS OPEN: with it closed, tick packets are dropped outright
 * (the server only pushes to WATCHERS anyway), and opening the screen always sends RequestMarketSnapshotPacket for a full pull;
 * - a snapshot arriving before the registry exists: stash the whole payload and apply it once SyncConfigPacket builds the registry;
 * - protocol mismatch: close MarketScreen and notify, render nothing further (reaches here via the ClientConfigCache.apply callback).
 *
 * All packet handling is marshalled back to the client main thread through {@link Minecraft#execute(Runnable)};
 * touching Screen objects on the network packet thread is forbidden. Handling does exactly two things: update caches, set the repaint flag -
 * the actual array rebuild and repaint are coalesced every 250 ms by ClientEvents.
 */
public final class ClientPlay {

    /** Market snapshot payload stashed while the registry does not exist yet (applied once when SyncConfig arrives, then cleared). */
    private static CompoundTag pendingMarketSnapshot = null;

    private ClientPlay() {
    }

    /**
     * Open the market UI (both S2COpenUiPacket and the hotkey land here).
     * Protocol-compat backstop: the Forge handshake already rejects old clients by PROTOCOL; the check here is the second lock -
     * once a local protocol mismatch is flagged (onProtocolMismatch set) reopening the screen is refused.
     */
    public static void openUi() {
        if (protocolMismatch) {
            return;     // refuse to open the market screen while the protocol is mismatched
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MarketScreen) {
            return;
        }
        mc.setScreen(new MarketScreen());
    }

    /** Set once a protocol-mismatching SyncConfigPacket arrives; reset on disconnect/reconnect (clear). */
    private static volatile boolean protocolMismatch = false;

    /** Lets ClientConfigCache.clear() reset the protocol flag. */
    public static void resetProtocolMismatch() {
        protocolMismatch = false;
    }

    /**
     * On disconnect / screen close, release the whole market NBT stashed while the registry was not ready.
     * Otherwise one config error or race could pin that snapshot on a static field until the next config packet arrives.
     */
    public static void clearPendingMarketSnapshot() {
        pendingMarketSnapshot = null;
    }

    /** Whether the market screen is currently open (the gate for market packet handling). */
    private static boolean marketUiOpen() {
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.screen;
        return s instanceof MarketScreen;
    }

    // ------------------------------------------------------------ Player data / config / state

    /**
     * Packet-apply backstop (trust-boundary hardening): the client reads arbitrary NBT the server ships, and a wrong tag type /
     * out-of-range array throws RuntimeException - throwing inside a {@link Minecraft#execute} task would punch through the main
     * loop and crash outright. Everything is caught here: the packet is dropped, caches keep their last valid state, one warning logged.
     */
    private static void applySafely(String what, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException malformed) {
            org.slf4j.LoggerFactory.getLogger("OreMarket/Client")
                    .warn("{} parse failed, packet dropped: {}", what, malformed.toString());
        }
    }

    public static void applySnapshot(S2CSnapshotPacket p) {
        Minecraft.getInstance().execute(() -> {
            MarketClientState st = MarketClientState.INSTANCE;
            net.minecraft.client.Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                st.ownerUuid = mc.player.getUUID().toString();
            }
            if (!marketUiOpen()) {
                // screen closed: the personal-data cache still updates (the server re-pushes when it reopens), but no repaint flag and no array rebuild
                applySafely("personal snapshot packet", () -> st.applySnapshot(p));
                st.redrawPending = false;
                return;
            }
            applySafely("personal snapshot packet", () -> st.applySnapshot(p));
        });
    }

    /**
     * Config packet (SyncConfigPacket): validate + write the local config cache and the stock registry.
     * Marshalled onto the client main thread via {@link Minecraft#execute(Runnable)}.
     */
    public static void applyConfig(SyncConfigPacket p) {
        Minecraft.getInstance().execute(() -> {
            boolean applied = ClientConfigCache.apply(p.data);
            if (applied && pendingMarketSnapshot != null) {
                CompoundTag held = pendingMarketSnapshot;
                pendingMarketSnapshot = null;
                applySafely("staged market full snapshot", () ->
                        MarketClientState.INSTANCE.applyMarketSnapshot(new MarketSnapshotPacket(held)));
            }
        });
    }

    /**
     * Player-state packet (SyncPlayerStatePacket): highestLevel and peakOreCoins are both LongTags;
     * missing fields default to 0; client reading rule: highestLevel < 1 -> clamp to 1, missing peak -> 0.
     */
    public static void applyPlayerState(SyncPlayerStatePacket p) {
        Minecraft.getInstance().execute(() -> MarketClientState.INSTANCE.applyPlayerState(
                p.highestLevel(), p.peakOreCoins()));
    }

    // ------------------------------------------------------------ Market packets

    /**
     * Market delta packet (one per marketTick, S2C): processed only when the screen is open and both the registry and the quote cache are ready;
     * any condition unmet means ignore outright (merge details live in MarketClientState.applyMarketTick).
     */
    public static void applyMarketTick(MarketTickPacket p) {
        Minecraft.getInstance().execute(() -> {
            if (!marketUiOpen()) {
                return;                     // screen closed: do not process delta updates
            }
            if (!ClientConfigCache.registryReady()) {
                return;                     // stock list not built yet: wait for SyncConfigPacket
            }
            if (MarketClientState.INSTANCE.snapshotStatus == MarketClientState.SnapshotStatus.LOADING) {
                return;                     // no full snapshot received yet: wait for the snapshot to build the cache skeleton
            }
            applySafely("market delta packet", () -> MarketClientState.INSTANCE.applyMarketTick(p));
        });
    }

    /**
     * Full market snapshot (S2C): replaces the quote cache ONLY WHILE THE SCREEN IS OPEN; ignored wholesale otherwise.
     * While the stock list (registry) is not built yet, the whole payload is stashed and processed against the then-current list once SyncConfigPacket lands.
     * A response-style snapshot (isResponse=1) within 2 s of the last proactive push is dropped (race protection).
     */
    public static void applyMarketSnapshot(MarketSnapshotPacket p) {
        Minecraft.getInstance().execute(() -> {
            if (!marketUiOpen()) {
                return;                     // processed only while the screen is open: otherwise the packet is ignored wholesale
            }
            long now = System.currentTimeMillis();
            MarketClientState st = MarketClientState.INSTANCE;
            if (p.isResponse()) {
                if (st.lastSnapshotPushTime > 0 && now - st.lastSnapshotPushTime < 2000) {
                    return;                 // response landing within 2 s of a proactive push: drop
                }
            } else {
                st.lastSnapshotPushTime = now;
            }
            if (!ClientConfigCache.registryReady()) {
                pendingMarketSnapshot = p.payload;   // apply once the registry is built
                return;
            }
            applySafely("market full snapshot", () -> st.applyMarketSnapshot(p));
        });
    }

    /** Server rejection packet (ErrorPacket): goes through the yellow warning toast; dropped when the screen is closed. */
    public static void showError(ErrorPacket p) {
        Minecraft.getInstance().execute(() -> {
            ClientEvents.showToast(localizedPacketText(p.key(), p.args()),
                    ClientEvents.TOAST_TICKS, ToastNotification.KIND_WARN);
        });
    }

    private static String localizedPacketText(String key, java.util.List<String> args) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        if (!net.minecraft.client.resources.language.I18n.exists(key)) {
            org.slf4j.LoggerFactory.getLogger("OreMarket/I18n")
                    .warn("Missing client translation key: {}", key);
            return key;
        }
        Object[] translated = args == null ? new Object[0]
                : args.stream().map(L::argText).toArray();
        return net.minecraft.client.resources.language.I18n.get(key, translated);
    }

    /** Protocol mismatch (SyncConfigPacket's protocol differs from local): close the screen, notify, stop rendering. */
    public static void onProtocolMismatch(String remoteProtocol) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof MarketScreen) {
                mc.setScreen(null);
            }
            mcgui(mc, remoteProtocol);
        });
    }

    private static void mcgui(Minecraft mc, String remoteProtocol) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("oremarket.net.protocol_mismatch",
                            remoteProtocol, OreMarketNetwork.PROTOCOL).withStyle(ChatFormatting.RED), false);
        }
        protocolMismatch = true;
        ClientConfigCache.clear();
    }

    // ------------------------------------------------------------ Order book / leaderboard

    public static void applyOrderBook(S2COrderBookPacket p) {
        Minecraft.getInstance().execute(() -> MarketClientState.INSTANCE.applyOrderBook(p));
    }

    public static void applyLeaderboard(S2CLeaderboardPacket p) {
        Minecraft.getInstance().execute(() -> MarketClientState.INSTANCE.applyLeaderboard(p));
    }

    // ------------------------------------------------------------ Misc

    /** Log entry: inserted at the head of the client's in-memory queue on the main thread, capped at 100 lines; the screen refreshes on the next frame. */
    public static void addLog(S2CLogPacket p) {
        Minecraft.getInstance().execute(() -> MarketClientState.INSTANCE.addLog(p.entry));
    }

    /**
     * S2C particle packet playback (protocol 10). Null guards: a null level / player is skipped silently;
     * the particle type is validated once more against the client registry (illegal ids that slipped past the server are dropped here as a backstop, never thrown).
     * Particle objects are never cached; the vanilla particle system lets them die naturally.
     */
    public static void playParticle(cn.oremarket.net.packet.SpawnParticlePacket p) {
        if (p == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        net.minecraft.core.particles.ParticleOptions opts = resolveParticle(p.type);
        if (opts == null) {
            return;
        }
        net.minecraft.util.RandomSource r = mc.level.getRandom();
        int count = Math.max(1, Math.min(cn.oremarket.net.packet.SpawnParticlePacket.MAX_COUNT, p.count));
        double spread = Math.max(0.0, Math.min(5.0, p.spread));
        for (int i = 0; i < count; i++) {
            double dx = 0;
            double dy = 0;
            double dz = 0;
            if (spread > 0.0) {
                // pick uniform points inside the sphere (rejection sampling, at most 4 tries; if none lands, place it on the radius)
                for (int t = 0; t < 4; t++) {
                    dx = (r.nextDouble() * 2.0 - 1.0) * spread;
                    dy = (r.nextDouble() * 2.0 - 1.0) * spread;
                    dz = (r.nextDouble() * 2.0 - 1.0) * spread;
                    if (dx * dx + dy * dy + dz * dz <= spread * spread) {
                        break;
                    }
                }
            }
            mc.level.addParticle(opts, p.x + dx, p.y + dy, p.z + dz, 0.0, 0.02, 0.0);
        }
        if (p.playSound != 0) {
            mc.level.playLocalSound(p.x, p.y, p.z, SoundEvents.EXPERIENCE_ORB_PICKUP,
                    net.minecraft.sounds.SoundSource.MASTER, 0.7f, 1.0f, true);
        }
    }

    /** Particle id -> client registry object; null for invalid / non-ParticleOptions (callers skip silently). */
    private static net.minecraft.core.particles.ParticleOptions resolveParticle(String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id.trim());
        if (rl == null) {
            return null;
        }
        net.minecraft.core.particles.ParticleType<?> type =
                net.minecraftforge.registries.ForgeRegistries.PARTICLE_TYPES.getValue(rl);
        return type instanceof net.minecraft.core.particles.ParticleOptions opts ? opts : null;
    }

    public static void playFeedback(S2CFeedbackPacket p) {
        // while the screen is closed ClientEvents drops or stashes by color; it never piles up unboundedly in the background
        int kind = ClientEvents.kindOf(p.key, p.success);
        ClientEvents.showToast(localizedPacketText(p.key, p.args), p.toastTicks, kind);
        if (p.success) {
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f));
        } else {
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(NoteBlockInstrument.BASS.getSoundEvent().value(), 0.6f));
        }
    }
}
