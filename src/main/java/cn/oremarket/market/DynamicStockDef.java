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

import net.minecraft.nbt.CompoundTag;

/** Persistent definition of a dynamically listed stock; when listed=false the record is kept as a delisted entry, awaiting re-listing. */
public final class DynamicStockDef {
    public final String code;
    public final String itemId;
    public final String displayName;
    public long exchangeRate;
    public double basePrice;
    public int unlockLevel;
    public boolean listed;
    public long delistedDay;

    public DynamicStockDef(String code, String itemId, String displayName, long exchangeRate,
                           double basePrice, int unlockLevel, boolean listed, long delistedDay) {
        this.code = code == null ? "" : code;
        this.itemId = itemId == null ? "" : itemId;
        this.displayName = displayName == null ? "" : displayName;
        this.exchangeRate = Math.max(1L, exchangeRate);
        this.basePrice = Math.max(1.0, basePrice);
        this.unlockLevel = Math.max(1, Math.min(Stock.MAX_TIER, unlockLevel));
        this.listed = listed;
        this.delistedDay = Math.max(0L, delistedDay);
    }

    public Stock toStock() {
        return Stock.custom(code, itemId, displayName, basePrice, exchangeRate, unlockLevel);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Code", code);
        tag.putString("ItemId", itemId);
        tag.putString("DisplayName", displayName);
        tag.putLong("ExchangeRate", exchangeRate);
        tag.putDouble("BasePrice", basePrice);
        tag.putInt("UnlockLevel", unlockLevel);
        tag.putBoolean("Listed", listed);
        tag.putLong("DelistedDay", delistedDay);
        return tag;
    }

    public static DynamicStockDef fromTag(CompoundTag tag) {
        return new DynamicStockDef(tag.getString("Code"), tag.getString("ItemId"),
                tag.getString("DisplayName"), tag.getLong("ExchangeRate"), tag.getDouble("BasePrice"),
                tag.getInt("UnlockLevel"), tag.getBoolean("Listed"), tag.getLong("DelistedDay"));
    }
}
