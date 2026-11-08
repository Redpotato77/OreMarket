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

import cn.oremarket.market.Stock;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * ItemStack cache for stock item icons.
 *
 * Previously every frame and every row did {@code new ItemStack(stock.item())}: 8-30 rows x 60 fps meant thousands
 * of throwaway objects per second, and the exchange side rebuilt one per stock per frame.
 * An ItemStack here is fully determined by the item itself (count 1, no tag), so caching by stock code is exactly equivalent.
 *
 * Lifecycle: entries for old codes are dropped when the registry rebuilds ({@link #clear()}), so a delisted stock's icon
 * is never reused; the cache is also cleared on screen close / logout, holding no item references long-term.
 */
final class ItemIconCache {

    private static final Map<String, ItemStack> BY_CODE = new HashMap<>();

    private ItemIconCache() {
    }

    /** Icon stack for a stock; returns {@link ItemStack#EMPTY} when the item is null - the caller draws a placeholder. */
    static ItemStack of(Stock s) {
        if (s == null || s.item() == null) {
            return ItemStack.EMPTY;
        }
        String code = s.code();
        ItemStack cached = BY_CODE.get(code);
        if (cached != null) {
            return cached;
        }
        ItemStack fresh = new ItemStack(s.item());
        BY_CODE.put(code, fresh);
        return fresh;
    }

    /** Cleared on registry rebuild / screen close / logout. */
    static void clear() {
        BY_CODE.clear();
    }
}
