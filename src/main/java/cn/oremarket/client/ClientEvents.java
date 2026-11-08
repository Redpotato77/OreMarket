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

import cn.oremarket.OreMarketMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client tick driver (the merging and timeouts of market-data sync packets
 * are handled here):
 *  - clientTick: purely visual timing such as button-press highlights;
 *  - **redraw merging**: packet handling only raises the redrawPending flag; each tick
 *    here checks "flag set and at least 250ms since the last redraw" before really
 *    rebuilding the derived arrays and letting the screen refresh on the next frame;
 *  - **config timeout**: ClientConfigCache waits 5 seconds (100 ticks) for a
 *    SyncConfigPacket, otherwise FAILED;
 *  - **market-data timeout**: MarketClientState.snapshotStatus stays LOADING for 5
 *    seconds without a MarketSnapshotPacket, otherwise FAILED; both failures show
 *    "click to retry" in the screen;
 *  - failure toast: one line of text is queued when a server reply fails validation or
 *    the client rejects it locally; it lasts 40 ticks (2 seconds), shows as red text
 *    centred at the screen bottom, and concurrent lines keep only the newest.
 */
@Mod.EventBusSubscriber(modid = OreMarketMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {

    /** Toast lifetime: 40 ticks = 2 seconds. */
    public static final int TOAST_TICKS = 40;
    /** Market Snapshot request timeout: 5 seconds (100 client ticks), same criterion as the config timeout. */
    public static final int SNAPSHOT_TIMEOUT_TICKS = 100;

    private static int clientTick = 0;

    /**
     * PendingToast in here; the render frame pumps it into {@link ToastNotification}.
     * While no screen is open, forced Events are parked as a single text line and
     * everything else is dropped - Toast objects never pile up in the background.
     */
    static final class PendingToast {
        final String text;
        final int kind;
        final int ticks;
        PendingToast(String text, int kind, int ticks) {
            this.text = text;
            this.kind = kind;
            this.ticks = ticks;
        }
    }

    private static final java.util.ArrayDeque<PendingToast> pendingToasts = new java.util.ArrayDeque<>();
    private static final int MAX_PENDING = 16;
    private static int toastSerial = 0;
    /** Forced-Event text parked while no screen is open (up to 8, replayed as log toasts on the next open). */
    private static final java.util.ArrayDeque<String> offlineForceNotes = new java.util.ArrayDeque<>();
    /** One-shot startup smoke flag: runs on the first client tick (language assets ready by then). */
    private static boolean startupTrSmokeDone = false;
    /** Which language code the overlay whole-table self-check ran on; rerun once after a language switch (once per language). */
    private static String overlayScanLang = null;
    /** Queue of base-table keys still to scan: only a small batch per tick, so startup never stutters. */
    private static java.util.Deque<String> overlayQueue = null;
    /** Previously filled sample from the queue, used to also validate the "two-segment splice" form. */
    private static String overlayPrevSample = null;
    /** Residue reports accumulated by this self-check run (logged in one go when the scan finishes). */
    private static final java.util.List<String> overlayLeaks = new java.util.ArrayList<>();
    /** Self-check logger (same naming style as L / I18nGuard). */
    private static final org.slf4j.Logger I18N_LOG =
            org.slf4j.LoggerFactory.getLogger("OreMarket/I18n");
    /** Templates scanned per tick: the whole table of a bit over a thousand finishes in twenty-odd ticks, unnoticed by players. */
    private static final int OVERLAY_SCAN_BATCH = 40;
    /** Max residue reports per self-check run (log-flood guard). */
    private static final int OVERLAY_SCAN_MAX_REPORT = 40;

    private ClientEvents() {
    }

    /**
     * Translation-chain smoke test: repeatedly walks the reverse-matching paths of the
     * "stock template / fragment / format template" to prove the deep guard and template
     * preprocessing cannot blow the stack; exceptions are only logged, the UI is untouched.
     */
    static void runStartupTrSmoke() {
        try {
            String[] probes = {
                    cn.oremarket.common.RawText.t("oremarket.text.0052"), cn.oremarket.common.RawText.t("oremarket.text.0624"),
                    // probe the {stock}-template path without any literal: render it live
                    cn.oremarket.common.RawText.t("oremarket.event.desc.player_frenzy"),
                    cn.oremarket.common.RawText.t("oremarket.event.desc.rumor1"),
                    // probe the full-sentence format-template path
                    cn.oremarket.common.RawText.f("oremarket.event.log.trigger",
                            cn.oremarket.common.RawText.t("oremarket.text.0052"),
                            cn.oremarket.common.RawText.t("oremarket.text.0623"), "-", "-"),
                    cn.oremarket.common.RawText.f("oremarket.trade.log.buy",
                            cn.oremarket.common.RawText.t("oremarket.text.0052"),
                            10, "123", "1230", "24.6", "12.3", "1267"),
            };
            for (int i = 0; i < 8; i++) {
                for (String p : probes) {
                    if (L.tr(p) == null) {
                        cn.oremarket.common.SanityCheck
                                .report(java.util.List.of("L.tr returned null: " + p));
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            cn.oremarket.common.SanityCheck
                    .report(java.util.List.of("L.tr smoke-test exception: " + t));
        }
        runOverlayLeakScan();
    }

    /**
     * Entry point of the overlay whole-table self-check (the acceptance gate
     * for "zero Chinese residue in an English locale").
     *
     * <p>It only runs under a <b>non-Chinese</b> display language: the full zh_cn table is
     * filled with typical arguments to produce the very sentences the server would send,
     * and both "two-segment splice" forms (with a fragment boundary / bare space; effect
     * shorthand / toast line only) are pushed through {@code L.trForScan}. Any remaining
     * Han character or full-width symbol raises a WARN. After a player switches language,
     * the scan reruns in the new language.</p>
     *
     * <p>The real translation calls happen in {@link #tickOverlayLeakScan()} in per-tick
     * batches: grinding through the whole table of 1000+ templates at once would visibly
     * stutter the client; batched, each tick handles only a few dozen.</p>
     */
    static void runOverlayLeakScan() {
        try {
            if (L.zh()) {
                overlayScanLang = null;
                overlayQueue = null;
                return;
            }
            String lang = L.langCode();
            if (lang == null || lang.equals(overlayScanLang) || overlayQueue != null) {
                return;
            }
            overlayScanLang = lang;
            overlayLeaks.clear();
            overlayPrevSample = null;
            overlayQueue = new java.util.ArrayDeque<>(
                    cn.oremarket.common.RawText.all().keySet());
        } catch (Throwable t) {
            overlayQueue = null;
            I18N_LOG.warn("I18n overlay scan could not start", t);
        }
    }

    /** Advances one small self-check batch per client tick; the report is emitted when the scan finishes. No exception can affect the game. */
    static void tickOverlayLeakScan() {
        java.util.Deque<String> queue = overlayQueue;
        if (queue == null) {
            return;
        }
        try {
            int budget = OVERLAY_SCAN_BATCH;
            while (budget-- > 0 && !queue.isEmpty()) {
                String key = queue.poll();
                cn.oremarket.common.I18nGuard
                        .scanOverlayLeaksOne(key, L::trForScan, overlayLeaks, OVERLAY_SCAN_MAX_REPORT,
                                overlayPrevSample);
                String sample = cn.oremarket.common.I18nGuard
                        .sampleFor(key);
                // Keep as splice material only the templates the server really joins together
                // (effect shorthand / toast line); the queue iterates in hash order, so gluing
                // two arbitrary originals together would just forge a malformed sentence no
                // player can ever see, and a leak reported on such a forgery is a false alarm.
                if (sample != null && !sample.isEmpty()
                        && cn.oremarket.common.I18nGuard
                        .isComposableEffectKey(key)) {
                    overlayPrevSample = sample;
                } else if (!cn.oremarket.common.I18nGuard
                        .isComposableEffectKey(key)) {
                    overlayPrevSample = null;
                }
            }
            if (queue.isEmpty()) {
                finishOverlayLeakScan();
            }
        } catch (Throwable t) {
            overlayQueue = null;
            I18N_LOG.warn("I18n overlay scan aborted", t);
        }
    }

    private static void finishOverlayLeakScan() {
        String lang = overlayScanLang;
        overlayQueue = null;
        overlayPrevSample = null;
        if (overlayLeaks.isEmpty()) {
            I18N_LOG.info("I18n overlay scan passed for locale {} (no Chinese residue)", lang);
            return;
        }
        for (String leak : overlayLeaks) {
            // Console-stays-English discipline: the leaked sample is original Chinese text, so sanitize it before logging (no mojibake on Windows consoles).
            I18N_LOG.warn("I18n residue in locale {}: {}", lang,
                    cn.oremarket.common.DebugLog.ascii(leak));
        }
        cn.oremarket.common.SanityCheck
                .report(java.util.List.of("OreMarket i18n residue in " + lang + ": "
                        + overlayLeaks.size() + " template(s), see latest.log"));
    }

    public static int clientTick() {
        return clientTick;
    }

    /** Shows one line of local validation feedback (yellow warning by default: insufficient balance, nothing selected, etc.). Callable from any thread. */
    public static void showToast(String text) {
        showToast(text, TOAST_TICKS, ToastNotification.KIND_WARN);
    }

    /** Shows a toast with an explicit lifetime in ticks (yellow warning by default). */
    public static void showToast(String text, int ticks) {
        showToast(text, ticks, ToastNotification.KIND_WARN);
    }

    public static void showToast(String text, int ticks, int kind) {
        String localized = text == null ? null : L.tr(text);
        if (localized == null || localized.isEmpty()) {
            return;
        }
        int k = kind;
        int t = Math.max(1, ticks);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        Runnable push = () -> pushToast(localized, k, t);
        if (!mc.isSameThread()) {
            mc.execute(push);
        } else {
            push.run();
        }
    }

    private static void pushToast(String text, int kind, int ticks) {
        boolean uiOpen = Minecraft.getInstance().screen instanceof MarketScreen;
        if (!uiOpen) {
            // forced Events (red) park one text line and replay on the next screen open; success / warning are dropped outright.
            if (kind == ToastNotification.KIND_ERROR) {
                if (offlineForceNotes.size() >= 8) {
                    offlineForceNotes.pollFirst();
                }
                offlineForceNotes.addLast(text);
            }
            return;
        }
        if (pendingToasts.size() >= MAX_PENDING) {
            pendingToasts.pollFirst();
        }
        pendingToasts.addLast(new PendingToast(text, kind, ticks));
        toastSerial++;
    }

    /** Render-frame pump: moves queued toasts into ToastNotification; reports whether this frame had new entries. */
    public static void drainToasts(ToastNotification target, net.minecraft.client.gui.Font font, int maxWidth) {
        if (target == null) {
            pendingToasts.clear();
            return;
        }
        PendingToast p;
        while ((p = pendingToasts.pollFirst()) != null) {
            long ms = Math.max(200L, p.ticks * 50L);
            target.enqueue(p.text, p.kind, ms, font, maxWidth);
        }
    }

    /** Screen closing: drop the not-yet-pumped queue so nothing can linger into the next open. */
    public static void dropPendingToasts() {
        pendingToasts.clear();
    }

    /** Screen just opened: replay the forced Events parked while it was closed. */
    public static void flushOfflineNotes(ToastNotification target, net.minecraft.client.gui.Font font, int maxWidth) {
        if (target == null) {
            offlineForceNotes.clear();
            return;
        }
        String note;
        while ((note = offlineForceNotes.pollFirst()) != null) {
            target.enqueue(note, ToastNotification.KIND_ERROR, DURATION_MS(font), font, maxWidth);
        }
    }

    private static long DURATION_MS(net.minecraft.client.gui.Font ignored) {
        return Math.max(200L, ToastNotification.DURATION_MS);
    }

    /** Serial of the latest toast arrival (the screen uses it to drive the drain). */
    public static int toastSerial() {
        return toastSerial;
    }

    /** Kept for old call sites: a toast is "present" when the queue is non-empty or ToastNotification is still playing one. */
    public static String toast() {
        PendingToast p = pendingToasts.peekLast();
        return p == null ? null : p.text;
    }

    /** Decides the toast colour from the key-name prefix plus the success flag. */
    public static int kindOf(String key, boolean success) {
        if (key == null) {
            return success ? ToastNotification.KIND_SUCCESS : ToastNotification.KIND_ERROR;
        }
        if (key.contains("force") || key.contains("bankrupt") || key.contains("overdue")
                || key.contains("shortfall") || key.contains("liquidat")
                || key.contains("force_close") || key.contains("force_sell")
                // system-cancelled orders (TTL expiry / stock delisting / trigger failure): must never
                // vanish unnoticed - park and replay on the next market-screen open, like forced notices
                || key.contains("cancel")
                // offline catch-up notices (system fills / forced liquidations / money movements that
                // happened while the player was away): same park-and-replay rule - never silent
                || key.contains("offline")) {
            return ToastNotification.KIND_ERROR;
        }
        if (!success) {
            return ToastNotification.KIND_WARN;
        }
        return ToastNotification.KIND_SUCCESS;
    }

    /**
     * Draws the topmost overlay last: ScreenEvent.Render.Post fires after
     * Screen.renderWithTooltip completes. Reuse the event's GuiGraphics, commit the body
     * text first, then place the overlay on a Z-layer above the buttons and the vanilla
     * tooltip.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof MarketScreen screen) {
            screen.renderTopOverlays(event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
        }
    }

    /**
     * On-the-fly translation of system chat messages (multi-language): the notices the
     * server broadcasts (exchange irreversibility, keybinding hints, protocol mismatch,
     * command echoes, ...) are all framed in Chinese, so the client reassembles them in
     * the current language on arrival: only text segments containing Chinese are replaced,
     * styles and the nested structure are kept as they are; translation-key content
     * (Component.translatable) already follows the language during vanilla rendering and
     * needs no touch. With no overlay mapping at all (Simplified Chinese) this is a
     * zero-cost pass-through; Traditional Chinese does carry a "Simplified original ->
     * Traditional translation" mapping, so chat runs through the pipeline there too.
     */
    @SubscribeEvent
    public static void onSystemChat(ClientChatReceivedEvent.System event) {
        if (!L.overlayActive()) {
            return;
        }
        event.setMessage(translateChat(event.getMessage()));
    }

    /** Recursively rebuilds the component tree: text segments go through L.tr, everything else is kept as-is. */
    private static Component translateChat(Component msg) {
        if (!(msg instanceof MutableComponent mc)) {
            return msg;
        }
        ComponentContents contents = mc.getContents();
        MutableComponent out;
        if (contents instanceof LiteralContents lit) {
            String text = lit.text();
            out = Component.literal(hasCjk(text) ? L.tr(text) : text).withStyle(mc.getStyle());
        } else if (contents instanceof net.minecraft.network.chat.contents.TranslatableContents trans) {
            Object[] oldArgs = trans.getArgs();
            Object[] newArgs = new Object[oldArgs.length];
            for (int i = 0; i < oldArgs.length; i++) {
                newArgs[i] = oldArgs[i] instanceof Component nested ? translateChat(nested)
                        : oldArgs[i] instanceof String s && hasCjk(s) ? L.tr(s) : oldArgs[i];
            }
            out = Component.translatable(trans.getKey(), newArgs).withStyle(mc.getStyle());
        } else {
            out = MutableComponent.create(contents).withStyle(mc.getStyle());
        }
        for (Component sib : mc.getSiblings()) {
            out.append(translateChat(sib));
        }
        return out;
    }

    /**
     * Whether a string is worth running through the translation pipeline: it contains
     * CJK Han characters (basic block + Extension A), or carries the fragment boundaries
     * of the server's multi-segment texts (a string spliced from pure numeric fragments
     * has no Han characters but must still be split up and translated).
     */
    private static boolean hasCjk(String s) {
        if (cn.oremarket.common.EffectText.isComposed(s)) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x3400 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    /** Client tick of the last full-Snapshot refetch caused by "market skeleton missing"; -1 = never refetched. */
    private static int lastQuoteRefetchTick = -1;

    private static void maybeRefetchMissingQuotes(MarketClientState st) {
        if (!(Minecraft.getInstance().screen instanceof MarketScreen)) {
            return;
        }
        if (!ClientConfigCache.registryReady()
                || st.snapshotStatus != MarketClientState.SnapshotStatus.READY) {
            return;
        }
        if (lastQuoteRefetchTick >= 0 && clientTick - lastQuoteRefetchTick < 40) {
            return;
        }
        boolean missing = false;
        for (cn.oremarket.market.Stock s
                : cn.oremarket.market.StockRegistry.client().getActiveStocks()) {
            if (s != null && !st.hasQuoteCache(s.code())) {
                missing = true;
                break;
            }
        }
        if (!missing) {
            return;
        }
        lastQuoteRefetchTick = clientTick;
        st.snapshotRequestedTick = clientTick;
        cn.oremarket.net.OreMarketNetwork.CHANNEL.sendToServer(
                new cn.oremarket.net.packet.RequestMarketSnapshotPacket(
                        cn.oremarket.net.OreMarketNetwork.PROTOCOL));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        clientTick++;
        // One-off translation smoke test once the asset tables are ready (see the runStartupTrSmoke comment).
        if (!startupTrSmokeDone) {
            startupTrSmokeDone = true;
            runStartupTrSmoke();
        }
        // Overlay whole-table self-check: advance only a small batch per tick and report once the scan ends (see the runOverlayLeakScan comment).
        tickOverlayLeakScan();
        // 5-second timeout check for the config request (no SyncConfigPacket in time -> FAILED; the screen shows "config load failed, click to retry")
        ClientConfigCache.tick();
        // 5-second timeout check for the market Snapshot (opening the screen and retrying both restart the clock)
        MarketClientState st = MarketClientState.INSTANCE;
        if (st.snapshotStatus == MarketClientState.SnapshotStatus.LOADING
                && st.snapshotRequestedTick >= 0
                && clientTick - st.snapshotRequestedTick >= SNAPSHOT_TIMEOUT_TICKS) {
            st.snapshotStatus = MarketClientState.SnapshotStatus.FAILED;
        }
        // proactively refetch one full Snapshot. Cooldown 40 ticks (2 seconds), clear of the server's 500ms request gate.
        maybeRefetchMissingQuotes(st);
        // Redraw merging + button-debounce recovery: driven only by the screen instance's own timing fields
        if (Minecraft.getInstance().screen instanceof MarketScreen ms) {
            ms.onClientTickPulse();
        }
        // While the debug switch is on, log one **pure-English**
        // text-parsing profile every 5 seconds (fallback-parse count + average/worst duration + stock count +
        // sizes of the three language tables) so players can see for themselves whether "the screen gets
        // laggier over time" really comes from the growing number of stocks.
        // With the switch off this is just an integer compare; not a single piece of player-visible text is touched.
        if (clientTick % 100 == 0
                && cn.oremarket.common.DebugLog.on()) {
            L.reportFallbackProfile(st.viewSize());
        }
    }

    /** Player logout (leaving the world / disconnecting): every client cache is voided and the next session starts from scratch. */
    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        MarketClientState st = MarketClientState.INSTANCE;
        st.clearLogs();
        // Disconnect / leave world: config cache, market cache and player-state cache all go void; the next join re-requests everything.
        // Never display thresholds, levels or quotes from the previous session (the server-side config may have changed since).
        ClientConfigCache.clear();
        ClientPlay.resetProtocolMismatch();
        // The parked market NBT must be released too: otherwise a Snapshot payload would hang on a static field forever
        ClientPlay.clearPendingMarketSnapshot();
        ItemIconCache.clear();
        st.clearMarketCache();
        st.clearOnClose();
        pendingToasts.clear();
        offlineForceNotes.clear();
    }
}
