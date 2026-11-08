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

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The adaptive toast pop-up at the bottom-centre of the screen (reworked
 * into a queue / waterfall layout).
 *
 * <p>At most 3 toasts share the screen; anything extra waits in a background queue
 * (up to 10); each toast stacks from the bottom up by its real wrapped height. Entry
 * is a 250ms ease-out slide-up plus fade-in while older items get pushed upward.
 * When a toast reaches the end of its dwell time it is removed before the next draw;
 * departure is intentionally instantaneous, so no transparent text or box frame can blink.</p>
 *
 * <p>Opacity is never assigned directly: it always comes from a {@link ToastTimeline}
 * segment. Re-raising a visible notice reuses it rather than stacking a duplicate;
 * this keeps the queue and layout deterministic.</p>
 *
 * <p>Colours: green for success / yellow for warnings / red for forced Events. The
 * threading rule is unchanged: the network thread may only call
 * {@code ClientEvents.showToast}; UI state is adopted on the render frame by
 * {@link #render} after {@code ClientEvents.drainToasts} has pumped the queue.</p>
 */
public final class ToastNotification {

    /** Success (green progress bar). */
    public static final int KIND_SUCCESS = 0;
    /** Warning (yellow progress bar: insufficient balance, inventory full, credit score too low). */
    public static final int KIND_WARN = 1;
    /** Error / forced Event (red progress bar: Forced Liquidation, blown account, overdue, bankruptcy). */
    public static final int KIND_ERROR = 2;

    public static long DURATION_MS = 2000L;
    /**
     * Compatibility value: departure is always atomic, so no fade duration is used.
     */
    public static final long FADE_MS = 0L;
    public static final int EVENT_BAR_GAP = 8;
    private static final int LINE_H = 9;
    private static final int PAD_X = 12;
    private static final int PAD_Y = 6;
    private static final int PROGRESS_RESERVE = 4;
    private static final int PROGRESS_H = 2;
    private static final int MIN_W = 60;
    private static final int MIN_H = 28;
    private static final int GAP = 4;
    private static final int SLIDE_PX = 20;
    private static final long IN_MS = 250L;
    private static final long MERGE_WINDOW_MS = 1000L;
    private static final int MAX_VISIBLE = 3;
    private static final int MAX_QUEUED = 10;
    private static int SCROLL_STEP = 10;

    private static int BG = 0xFF0E0E0E;
    private static final int RIM = 0xFF000000;
    private static int BORDER = 0xFF666666;
    private static int INNER_HIGHLIGHT = 0xFF4A4A4A;
    private static int SCROLL_HINT = 0xFF2A2A2A;
    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFAA00;
    private static final int RED = 0xFFFF5555;

    public static void applyTheme(cn.oremarket.market.MarketSettings ms) {
        DURATION_MS = Math.max(200, ms.uiToastDurationMs);
        // The user-facing fade setting is retained for config compatibility, but departure
        // is always immediate so the text and box cannot enter different alpha frames.
        SCROLL_STEP = Math.max(2, ms.uiScrollStepFine);
        BG = ms.uiColor(cn.oremarket.market.MarketSettings.UI_TOAST_BG);
        BORDER = ms.uiColor(cn.oremarket.market.MarketSettings.UI_DIM);
        INNER_HIGHLIGHT = ms.uiColor(cn.oremarket.market.MarketSettings.UI_HIGHLIGHT);
        SCROLL_HINT = ms.uiColor(cn.oremarket.market.MarketSettings.UI_DARK);
    }

    /** Runtime state of a single toast (height follows the wrapped text; y lerps toward its target). */
    static final class Item {
        String message;
        String baseMessage;
        int kind;
        int count = 1;
        long bornMs;
        long duration;
        List<String> lines = List.of();
        int boxW = MIN_W;
        int boxH = MIN_H;
        float y;
        float targetY;
        /** Explicit layout initialization flag; zero is a valid screen coordinate and cannot be a sentinel. */
        boolean positionInitialized;
        /** Last evaluated opacity; written only from {@link #render}, read for layout / blending. */
        float alpha;
        /** The only place opacity comes from, so no transition can step it. */
        final ToastTimeline timeline = new ToastTimeline();
        boolean entering = true;
        /** The previous frame's actual draw position; layout interpolation may continue after a frame is painted. */
        boolean lastPaintPositionValid;
        float lastPaintY;
        int lastPaintTop;
        int scrollOffset;
        long lastMergeMs;

        /** Marks this notice as atomically hidden; the render loop removes it immediately. */
        void beginFadeOut(long now) {
            timeline.beginFadeOut(now, 0L);
            entering = false;
            alpha = 0f;
        }
    }

    private final List<Item> live = new ArrayList<>(MAX_VISIBLE);
    private final List<Item> queued = new ArrayList<>(MAX_QUEUED);
    private long lastFrameMs;
    private int panelX, panelY, panelW, panelH;
    private Item hoverItem;

    public ToastNotification() {
    }

    public boolean active() {
        return !live.isEmpty() || !queued.isEmpty();
    }

    /**
     * Kept for legacy call sites: shows one toast with the error colour and the
     * currently configured duration. Real enqueuing goes through {@link #enqueue}.
     */
    public void show(String text, Font font, int maxWidth) {
        enqueue(text, KIND_ERROR, DURATION_MS, font, maxWidth);
    }

    /**
     * Enqueue a toast. An identical notice that is still on screen is re-used instead of
     * added a second time: its counter grows and its dwell restarts. Text arriving within
     * 1 second merges the same way; a genuinely new item goes to the background queue when
     * the screen is full (at most 10; anything beyond that is dropped).
     */
    public void enqueue(String text, int kind, long durationMs, Font font, int maxWidth) {
        if (text == null || text.isEmpty() || font == null) {
            return;
        }
        long now = monotonicMillis();
        int k = kind < KIND_SUCCESS || kind > KIND_ERROR ? KIND_ERROR : kind;
        for (Item it : live) {
            // Reuse the same live notice instead of stacking a duplicate, keeping the
            // layout stable while its dwell time is restarted.
            if (it.kind == k && it.baseMessage.equals(text)
                    && now - it.lastMergeMs <= MERGE_WINDOW_MS) {
                it.count++;
                it.message = it.count <= 1 ? it.baseMessage : it.baseMessage + " (x" + it.count + ")";
                it.bornMs = now;
                it.lastMergeMs = now;
                // Re-using a notice that has already been laid out only extends its dwell time;
                // an item merged before its first frame keeps the full entry animation.
                if (it.positionInitialized) {
                    it.entering = false;
                }
                it.alpha = it.timeline.alphaAt(now);
                recomputeItem(it, font, maxWidth);
                return;
            }
        }
        for (Item it : queued) {
            if (it.kind == k && it.baseMessage.equals(text)
                    && now - it.lastMergeMs <= MERGE_WINDOW_MS) {
                it.count++;
                it.message = it.count <= 1 ? it.baseMessage : it.baseMessage + " (x" + it.count + ")";
                it.lastMergeMs = now;
                return;
            }
        }
        Item n = new Item();
        n.baseMessage = text;
        n.message = text;
        n.kind = k;
        n.duration = Math.max(200L, durationMs);
        n.bornMs = now;
        n.lastMergeMs = now;
        n.alpha = 0f;
        n.timeline.beginEnter(now, IN_MS);
        recomputeItem(n, font, maxWidth);
        if (live.size() < MAX_VISIBLE) {
            n.entering = true;
            live.add(n);                 // newest goes at the list tail = visual bottom
        } else if (queued.size() < MAX_QUEUED) {
            queued.add(n);
        }
        // past MAX_QUEUED: dropped outright so a burst of 50 toasts cannot blow up the memory
    }

    /** Screen closed: clear both visible and queued toasts so nothing can linger into the next open. */
    public void clear() {
        live.clear();
        queued.clear();
        hoverItem = null;
        lastFrameMs = 0L;
    }

    private void recomputeItem(Item it, Font font, int maxWidth) {
        int cap = Math.max(MIN_W, maxWidth);
        List<String> lines = new ArrayList<>();
        int textW = font.width(it.message);
        int w = Math.max(MIN_W, Math.min(cap, textW + PAD_X * 2));
        int inner = w - PAD_X * 2;
        if (textW <= inner || it.message.isEmpty()) {
            if (!it.message.isEmpty()) {
                lines.add(it.message);
            }
        } else {
            wrap(font, it.message, inner, lines);
        }
        it.lines = List.copyOf(lines);
        it.boxW = w;
        it.boxH = Math.max(MIN_H, it.lines.size() * LINE_H + PAD_Y * 2 + PROGRESS_RESERVE);
    }

    static void wrap(Font font, String text, int inner, List<String> out) {
        if (inner <= 1) {
            return;
        }
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String piece = line.length() == 0 ? word : line + " " + word;
            if (font.width(piece) <= inner) {
                line.setLength(0);
                line.append(piece);
                continue;
            }
            if (line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
            }
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                if (font.width(cur.length() == 0 ? String.valueOf(c) : cur.toString() + c) > inner
                        && cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                cur.append(c);
            }
            line.append(cur);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        if (out.isEmpty() && !text.isEmpty()) {
            out.add(text);
        }
    }

    /**
     * Renders one frame. First accumulate the target Y bottom-up from the real wrapped
     * heights, then chase it with a deltaTime-based lerp on every toast's y while the
     * timeline supplies the continuous entry opacity. Departure is atomic: an expired
     * toast is removed before any draw call for the next frame.
     */
    public void render(GuiGraphics g, Font font, int rootX, int rootY, int rootW,
                       int barTopY, int screenH, int mouseX, int mouseY,
                       double uiScale, double offX, double offY) {
        long now = monotonicMillis();
        float dt = lastFrameMs <= 0 ? 0.016f : Math.min(0.05f, Math.max(0.0f, (now - lastFrameMs) / 1000f));
        lastFrameMs = now;
        if (font == null) {
            return;
        }

        // Expired notices are removed before layout and drawing. There is deliberately
        // no fade-out frame, which makes the text and box disappear as one atomic change.
        Iterator<Item> it = live.iterator();
        while (it.hasNext()) {
            Item item = it.next();
            if (now - item.bornMs >= item.duration) {
                item.beginFadeOut(now);
                it.remove();
            }
        }
        while (live.size() < MAX_VISIBLE && !queued.isEmpty()) {
            Item n = queued.remove(0);
            n.bornMs = now;
            n.entering = true;
            n.alpha = 0f;
            n.timeline.beginEnter(now, IN_MS);
            live.add(n);
        }
        if (live.isEmpty()) {
            hoverItem = null;
            panelX = panelY = panelW = panelH = 0;
            return;
        }

        // Evaluate every opacity before layout so a newly promoted item gets its slide
        // origin in the same frame in which it becomes visible.
        for (Item item : live) {
            item.alpha = ToastTimeline.clamp01(item.timeline.alphaAt(now));
        }

        int availAboveBar = Math.max(MIN_H, barTopY - EVENT_BAR_GAP - (rootY + 2));
        int hMax = Math.max(MIN_H, Math.min(screenH - 40, availAboveBar));
        int maxW = Math.max(MIN_W, rootW - 40);
        int stackH = 0;
        for (int i = live.size() - 1; i >= 0; i--) {
            Item item = live.get(i);
            if (item.boxW > maxW) {
                recomputeItem(item, font, maxW);
            }
            int visH = Math.min(item.boxH, hMax);
            stackH += visH;
            if (i > 0) {
                stackH += GAP;
            }
        }
        float baseBottom = barTopY - EVENT_BAR_GAP;
        if (stackH > availAboveBar) {
            baseBottom = rootY + 2 + stackH;
        }
        // Bottom-up: the newest (tail) sits on the baseline bottom edge; each item above it = real height below + 4px.
        float cursorBottom = baseBottom;
        for (int i = live.size() - 1; i >= 0; i--) {
            Item item = live.get(i);
            int visH = Math.min(item.boxH, hMax);
            item.targetY = cursorBottom - visH;
            if (!item.positionInitialized) {
                item.positionInitialized = true;
                item.y = item.entering ? item.targetY + SLIDE_PX : item.targetY;
            }
            cursorBottom = item.targetY - GAP;
        }

        hoverItem = null;
        int unionL = Integer.MAX_VALUE, unionT = Integer.MAX_VALUE, unionR = 0, unionB = 0;
        for (Item item : live) {
            long elapsed = now - item.bornMs;
            float inEased = 1f;
            if (item.entering) {
                float inT = ToastTimeline.clamp01((now - item.bornMs) / (float) IN_MS);
                inEased = ToastTimeline.easeOut(inT);
                if (inT >= 1f) {
                    item.entering = false;
                }
            }
            // Lerp based on dt: the factor tracks the frame time, so neither 60fps nor dropped frames teleport.
            float lerpK = 1f - (float) Math.exp(-14.0 * dt);
            item.y += (item.targetY - item.y) * lerpK;
            float drawY = item.y;

            int fullW = Math.max(MIN_W, Math.min(maxW, item.boxW));
            int w = item.entering ? Math.max(MIN_W, (int) Math.round(fullW * (0.7f + 0.3f * inEased))) : fullW;
            int h = Math.min(item.boxH, hMax);
            int x = rootX + (rootW - w) / 2;
            int top = Math.round(drawY);
            if (top < rootY + 2) {
                top = rootY + 2;
            }
            int fg = colorOf(item.kind);
            float a = Math.max(0f, Math.min(1f, item.alpha));
            if (a <= 0.01f) {
                continue;
            }
            // Record the actual draw position for stable layout diagnostics.
            item.lastPaintPositionValid = true;
            item.lastPaintY = drawY;
            item.lastPaintTop = top;
            g.renderOutline(x - 1, top - 1, w + 2, h + 2, mixAlpha(RIM, a));
            g.fill(x, top, x + w, top + h, mixAlpha(BG, a));
            g.renderOutline(x, top, w, h, mixAlpha(BORDER, a));
            if (w > 6 && h > 6) {
                g.renderOutline(x + 1, top + 1, w - 2, h - 2, mixAlpha(INNER_HIGHLIGHT, a));
            }
            g.flush();

            int maxScroll = Math.max(0, item.boxH - h);
            if (item.scrollOffset > maxScroll) {
                item.scrollOffset = maxScroll;
            }
            boolean clip = item.entering || maxScroll > 0;
            try {
                if (clip) {
                    g.enableScissor(
                            (int) Math.floor((x + 1) * uiScale + offX),
                            (int) Math.floor((top + 1) * uiScale + offY),
                            (int) Math.ceil((x + w - 1) * uiScale + offX),
                            (int) Math.ceil((top + h - PROGRESS_RESERVE) * uiScale + offY));
                }
                int ty = top + PAD_Y - item.scrollOffset;
                for (String line : item.lines) {
                    if (ty + LINE_H > top && ty < top + h - PROGRESS_RESERVE) {
                        g.drawCenteredString(font, line, x + w / 2, ty, mixAlpha(fg, a));
                    }
                    ty += LINE_H;
                }
            } finally {
                if (clip) {
                    g.disableScissor();
                }
            }
            long remaining = Math.max(0L, item.duration - elapsed);
            int barW = (int) ((w - 2) * (remaining / (double) Math.max(1L, item.duration)));
            g.fill(x + 1, top + h - PROGRESS_H, x + 1 + barW, top + h,
                    mixAlpha(fg, a));
            if (maxScroll > 0 && item.scrollOffset < maxScroll) {
                g.fill(x + 6, top + h - PROGRESS_RESERVE, x + w - 6, top + h - PROGRESS_RESERVE + 1,
                        mixAlpha(SCROLL_HINT, a));
            }
            int hitX = rootX + (rootW - fullW) / 2;
            if (mouseX >= hitX && mouseX < hitX + fullW && mouseY >= top && mouseY < top + h) {
                hoverItem = item;
            }
            unionL = Math.min(unionL, hitX);
            unionT = Math.min(unionT, top);
            unionR = Math.max(unionR, hitX + fullW);
            unionB = Math.max(unionB, top + h);
        }
        if (unionL < unionR) {
            panelX = unionL;
            panelY = unionT;
            panelW = unionR - unionL;
            panelH = unionB - unionT;
        } else {
            // A fully transparent final frame must not leave an old hit rectangle behind.
            panelX = panelY = panelW = panelH = 0;
        }
    }

    public boolean contains(double mx, double my) {
        return active() && mx >= panelX && mx < panelX + panelW && my >= panelY && my < panelY + panelH;
    }

    public boolean canScroll() {
        return hoverItem != null && hoverItem.boxH > MIN_H;
    }

    public void scroll(double delta) {
        if (hoverItem == null) {
            return;
        }
        int max = Math.max(0, hoverItem.boxH - MIN_H);
        hoverItem.scrollOffset = Math.max(0, Math.min(max,
                hoverItem.scrollOffset - (int) Math.round(delta * SCROLL_STEP)));
    }

    public int dragOffset() {
        return hoverItem == null ? 0 : hoverItem.scrollOffset;
    }

    public int dragMax() {
        if (hoverItem == null) {
            return 0;
        }
        return Math.max(0, hoverItem.boxH - MIN_H);
    }

    public void dragOffsetSet(int v) {
        if (hoverItem == null) {
            return;
        }
        hoverItem.scrollOffset = Math.max(0, Math.min(dragMax(), v));
    }

    public double[] dragRect() {
        if (!active() || panelW <= 0 || panelH <= 0) {
            return null;
        }
        return new double[]{panelX, panelY, panelX + panelW, panelY + panelH};
    }

    public List<String> lines() {
        return live.isEmpty() ? List.of() : live.get(live.size() - 1).lines;
    }

    public int height() {
        return live.isEmpty() ? 0 : live.get(live.size() - 1).boxH;
    }

    public String text() {
        return live.isEmpty() ? "" : live.get(live.size() - 1).message;
    }

    private static int colorOf(int kind) {
        return switch (kind) {
            case KIND_SUCCESS -> GREEN;
            case KIND_WARN -> YELLOW;
            default -> RED;
        };
    }

    private static int mixAlpha(int argb, float a) {
        int alpha = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** Render-time clock that cannot jump when the operating system adjusts wall time. */
    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }
}
