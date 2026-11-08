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

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Shared vertical layout stack for all content pages.
 * Elements only declare their height; positions are computed by the engine as top pad 4, element gap 4, bottom pad 4.
 */
public final class VerticalLayout {
    public interface LayoutElement {
        int getHeight();
    }

    private final List<LayoutElement> elements = new ArrayList<>();
    private final List<Integer> positions = new ArrayList<>();
    private int contentHeight;
    private int availableHeight;
    private int maxScrollOffset;
    private int boundsTop;
    private int boundsLeft;
    private int width;

    public VerticalLayout clear() {
        elements.clear();
        positions.clear();
        contentHeight = 0;
        availableHeight = 0;
        maxScrollOffset = 0;
        return this;
    }

    public VerticalLayout add(int height) {
        elements.add(() -> height);
        return this;
    }

    public VerticalLayout add(IntSupplier height) {
        if (height != null) {
            elements.add(height::getAsInt);
        }
        return this;
    }

    /**
     * Lay elements out in order; containerHeight is the raw content-area height, not including the engine's padding.
     * available height = containerHeight - 8 (top 4 + bottom 4 padding);
     * content height = sum of element heights + (effective element count - 1) x 4;
     * maxScrollOffset = max(0, contentHeight - availableHeight).
     * At full scroll the last row's bottom = boundsTop + 4 + availableHeight = container bottom - 4,
     * fully visible with a constant 4 px bottom gap; the old +8 slack on maxScrollOffset is gone (it double-counted).
     */
    public void layout(int left, int top, int width, int containerHeight) {
        boundsLeft = left;
        boundsTop = top;
        this.width = Math.max(0, width);
        availableHeight = Math.max(0, containerHeight - 8);
        contentHeight = 0;
        positions.clear();
        int y = top + 4;
        boolean firstPlaced = true;
        for (int i = 0; i < elements.size(); i++) {
            int height = Math.max(0, elements.get(i).getHeight());
            if (height <= 0) {
                // Zero-height elements (e.g. the spare form row before it wraps) take no space and add no gap
                positions.add(y);
                continue;
            }
            if (!firstPlaced) {
                y += 4;
                contentHeight += 4;
            }
            positions.add(y);
            y += height;
            contentHeight += height;
            firstPlaced = false;
        }
        maxScrollOffset = Math.max(0, contentHeight - availableHeight);
    }

    public int y(int index) {
        return index >= 0 && index < positions.size() ? positions.get(index) : boundsTop + 4;
    }

    public int height(int index) {
        return index >= 0 && index < elements.size()
                ? Math.max(0, elements.get(index).getHeight()) : 0;
    }

    public int size() {
        return elements.size();
    }

    public int contentHeight() {
        return contentHeight;
    }

    public int availableHeight() {
        return availableHeight;
    }

    public int maxScrollOffset() {
        return maxScrollOffset;
    }

    public int boundsLeft() {
        return boundsLeft;
    }

    public int boundsTop() {
        return boundsTop;
    }

    public int width() {
        return width;
    }

    public int clampScroll(int scrollOffset) {
        return Math.max(0, Math.min(maxScrollOffset, scrollOffset));
    }

    /** Draw a dim line 4 px above the content-area bottom while not scrolled to the end. */
    public boolean shouldDrawBottomLine(int scrollOffset) {
        return contentHeight > availableHeight && scrollOffset < maxScrollOffset;
    }
}
