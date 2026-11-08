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

import cn.oremarket.market.MarketSettings;
import cn.oremarket.market.Stock;
import cn.oremarket.market.StockRegistry;
import cn.oremarket.net.OreMarketNetwork;
import cn.oremarket.net.packet.RequestConfigPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * Client-side config cache: every number the market UI shows comes from this cache
 * delivered by the server's SyncConfigPacket. The local config/oremarket.toml is <b>never read</b>,
 * and the raw customStocks string is <b>never parsed</b> either -- custom stock entries finish the
 * ten-step server-side validation first and arrive as stockRegistry (a ListTag); the client only
 * trusts this already-resolved registry.
 *
 * Three-state state machine:
 *  - LOADING: RequestConfigPacket sent but no reply yet -> the UI shows "loading" and renders no
 *    config-dependent numbers;
 *  - READY: the config cache has arrived -> normal rendering;
 *  - FAILED: no reply 5 seconds after the request -> the UI shows "config load failed, click to
 *    retry", and a click sends the request again.
 *
 * Race protection: <b>server pushes</b> (isResponse=0) update lastSyncPushTime; a request/reply
 * packet (isResponse=1) arriving less than 2 seconds after the latest push is dropped whole, so a
 * stale reply can never overwrite a fresh push. When the protocol version (the protocol field in
 * SyncConfigPacket) differs from the local one, nothing is written to the cache and
 * {@link ClientPlay#onProtocolMismatch()} is called back to close the screen.
 */
public final class ClientConfigCache {
    /** Request timeout: 5 seconds (100 client ticks). */
    public static final int TIMEOUT_TICKS = 100;
    /** Race window between an outright push and a reply packet: 2000ms. */
    public static final int PUSH_RACE_MS = 2000;
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    public enum Status { READY, LOADING, FAILED }

    private static volatile Status status = Status.LOADING;
    private static int sinceRequestTicks = 0;
    /** Registry entry count from the last successful parse (0 = registry not built yet). */
    private static volatile int registrySize = 0;
    /** Content fingerprint of the previous registry (0 = none received yet; the first packet always counts as a change). */
    private static volatile long lastRegistrySignature = 0L;

    private ClientConfigCache() {
    }

    public static Status status() {
        return status;
    }

    public static boolean ready() {
        return status == Status.READY;
    }

    /** Whether the client stock registry is built (at least one stockRegistry received). One precondition for UI rendering. */
    public static boolean registryReady() {
        return registrySize > 0;
    }

    /** Called when opening the UI or clicking retry: flip to LOADING and request the config (carries the local protocol version; the server rejects a mismatch). */
    public static void request() {
        if (status != Status.READY) {
            status = Status.LOADING;
        }
        sinceRequestTicks = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getConnection() != null) {
            OreMarketNetwork.CHANNEL.sendToServer(new RequestConfigPacket(OreMarketNetwork.PROTOCOL));
        }
    }

    /** Click-to-retry after a timeout: send the request again. */
    public static void retry() {
        status = Status.LOADING;
        sinceRequestTicks = 0;
        request();
    }

    /** Client tick: counts up only while not READY; flips to FAILED past 5 seconds. */
    public static void tick() {
        if (status == Status.READY) {
            return;
        }
        if (++sinceRequestTicks >= TIMEOUT_TICKS) {
            status = Status.FAILED;
        }
    }

    /**
     * Handles a received SyncConfigPacket; call only on the client main thread (ClientPlay has
     * already hopped back to it). Order: protocol check -> race check -> wholesale replacement of
     * the config snapshot -> wholesale replacement of the stock registry -> flip to READY.
     *
     * @return true = this packet was processed; false = dropped (reply within 2s of an outright push / protocol mismatch)
     */
    public static boolean apply(CompoundTag tag) {
        // Parsing arbitrary NBT from the server: a wrong Tag type (getDouble hitting a string, etc.)
        // throws ClassCastException. A single packet must not crash the main loop -- catch it and
        // drop the whole packet (return false), leaving the local cache on its last valid snapshot.
        try {
            return applyInner(tag);
        } catch (RuntimeException malformed) {
            org.slf4j.LoggerFactory.getLogger("OreMarket/Client")
                    .warn("config sync packet parse failed, dropped: {}", malformed.toString());
            return false;
        }
    }

    private static boolean applyInner(CompoundTag tag) {
        if (tag == null) {
            return false;
        }
        String protocol = tag.getString("protocol");
        if (!protocol.isEmpty() && !OreMarketNetwork.PROTOCOL.equals(protocol)) {
            // Protocol mismatch: nothing is written to the cache; close the market screen and notify (ClientPlay performs the action)
            ClientPlay.onProtocolMismatch(protocol);
            return false;
        }
        boolean isResponse = tag.getByte("isResponse") != 0;
        long now = System.currentTimeMillis();
        if (isResponse && MarketClientState.INSTANCE.lastSyncPushTime > 0
                && now - MarketClientState.INSTANCE.lastSyncPushTime < PUSH_RACE_MS) {
            // Reply arrived less than 2 seconds after the latest outright push: stale answer, drop it
            return false;
        }
        if (!isResponse) {
            MarketClientState.INSTANCE.lastSyncPushTime = now;     // Outright push: refresh the timestamp
        }
        MarketSettings.setClient(MarketSettings.readFrom(tag));
        // A display-only config change must rebuild currentVolume immediately, even when the registry itself is unchanged.
        MarketClientState.INSTANCE.redrawPending = true;
        Tag raw = tag.get("stockRegistry");
        if (raw instanceof ListTag list) {
            List<Stock> before = StockRegistry.client().snapshot();
            List<Stock> parsed = StockRegistry.replaceFromClientTag(list);
            registrySize = parsed.size();
            // The icon cache and the entire
            // translation cache are invalidated <b>only when the registry content really changed</b>.
            // The old code wiped the 8000-entry translation cache and every item icon on every
            // SyncConfigPacket (hot reloads, listing/delisting broadcasts and request replies all
            // send one), forcing a full screen of text back through the "format-template reverse
            // lookup + fragment replacement" fallback path -- whose cost grows linearly with the
            // stock count, exactly the amplifier behind the stutter on random listings.
            long signature = registrySignature(parsed);
            boolean registryChanged = signature != lastRegistrySignature;
            lastRegistrySignature = signature;
            if (registryChanged) {
                // Registry swapped out: the items may differ, so drop the whole icon cache (rebuilt next frame from the new registry)
                ItemIconCache.clear();
                // Stock names may have changed too, so invalidate the event-template translation cache to keep old names out of {stock} matching.
                L.invalidateStockTemplates();
            }
            // Relisting detection: a code retired in the previous snapshot but not any more -> evict its stale quote-cache entry
            for (Stock s : parsed) {
                if (wasRetired(before, s.code()) && !s.retired()) {
                    MarketClientState.INSTANCE.evictQuote(s.code());
                }
            }
            // Rebuild the derived views right after a registry swap (selectedCode validity is checked by MarketScreen on the next render)
            MarketClientState.INSTANCE.rebuildView();
            MarketClientState.INSTANCE.redrawPending = true;
        }
        status = Status.READY;
        sinceRequestTicks = 0;
        return true;
    }

    /**
     * Registry content fingerprint: code + item id + display name + base price + exchange rate +
     * unlock level + retired flag + order. It only answers "is this batch of stocks identical to
     * the last one" and takes part in no business decision. FNV-1a 64-bit, zero allocation: a few
     * microseconds no matter how many entries.
     */
    private static long registrySignature(List<Stock> stocks) {
        long signature = FNV_OFFSET_BASIS;
        for (Stock stock : stocks) {
            if (stock == null) {
                signature *= FNV_PRIME;
                continue;
            }
            signature = mix(signature, stock.code());
            signature = mix(signature, stock.itemId());
            signature = mix(signature, stock.displayName());
            signature = signature * FNV_PRIME ^ Double.doubleToLongBits(stock.basePrice());
            signature = signature * FNV_PRIME ^ stock.exchangeRate();
            signature = signature * FNV_PRIME ^ stock.unlockLevel();
            signature = signature * FNV_PRIME ^ (stock.retired() ? 1L : 0L);
        }
        return signature == 0L ? 1L : signature; // 0 is reserved for the initial empty-registry state
    }

    private static long mix(long signature, String text) {
        for (int characterIndex = 0; characterIndex < text.length(); characterIndex++) {
            signature = (signature ^ text.charAt(characterIndex)) * FNV_PRIME;
        }
        return signature * FNV_PRIME; // One extra multiplier prevents boundary cancellation
    }

    private static boolean wasRetired(List<Stock> before, String code) {
        for (Stock s : before) {
            if (s.code().equals(code)) {
                return s.retired();
            }
        }
        return false;
    }

    /** Number of stocks in the client registry (active + retired; the UI list only shows active ones). */
    public static int stockCount() {
        return registrySize;
    }

    /**
     * Client-side local config reload goes through here only when not connected to a server (main
     * menu / single-player world not started): read the local oremarket.toml directly and build a
     * snapshot for the UI (the server side is driven by this same config, so the two are equivalent).
     * Once a server is connected, the local file is <b>ignored</b> and only the server's
     * SyncConfigPacket is accepted.
     */
    public static void applyLocalConfig() {
        if (!notConnected()) {
            return;
        }
        MarketSettings ms = MarketSettings.reloadServer();
        StockRegistry.rebuild(ms);              // Side-effect-free local rebuild (in single-player the config is the registry)
        CompoundTag tag = new CompoundTag();
        ms.writeTo(tag);
        apply(tag);
    }

    /** Redrawing from local config is allowed only while not connected (main menu / single-player world not started). */
    public static boolean notConnected() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.getConnection() == null;
    }

    /** Disconnect / leave world: clear the cache; the next server join requests it afresh. */
    public static void clear() {
        status = Status.LOADING;
        sinceRequestTicks = 0;
        registrySize = 0;
        lastRegistrySignature = 0L;             // The first packet on the next join always counts as "registry changed" and rebuilds the caches
        MarketSettings.setClient(null);
    }
}
