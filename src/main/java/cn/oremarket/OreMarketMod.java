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

package cn.oremarket;

import cn.oremarket.config.OreMarketConfig;
import cn.oremarket.market.MarketReloadService;
import cn.oremarket.net.OreMarketNetwork;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * oremarket - a lightweight mineral stock market.
 *
 * Default design across the six modules:
 * - Exchange: fixed rate; deposit and withdraw support custom quantities plus all-in/all-out actions; every operation is validated server-side;
 * - Market: 8 stocks; marketTick interval, price limits, mean-reversion strength, volatility and impact all configurable;
 * - Events: template pool drawn with rarity-weighted odds; trigger chance and every duration class (all in seconds) configurable;
 * - Persistence and network: Overworld SavedData; a full snapshot when the UI opens, then marketTick deltas;
 * - Audio/visual: vanilla item icons, dark-gray translucent GUI, candlestick (K-line) charts, vanilla sounds and particles;
 * - Build: purely server-authoritative, zero external libraries, identical logic in single-player and on servers.
 * The remaining idea-pool variants (high-volatility/steady, event-storm/gentle, on-demand/full sync) are server
 * config switches in config/oremarket.toml and never conflict.
 */
@Mod(OreMarketMod.MOD_ID)
public final class OreMarketMod {
    public static final String MOD_ID = "oremarket";
    /** Mod version as shown to users. Matches build.gradle's version with the internal build suffix (-rNNN) stripped -
     *  mods.toml's @mod_version@ is rendered the same way; keep the base number in sync across all three. */
    public static final String MOD_VERSION = "1.0.0-beta.1";

    public OreMarketMod() {
        org.slf4j.LoggerFactory.getLogger("OreMarket").info(
                "[OreMarket] Version: {} (BETA)", MOD_VERSION);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, OreMarketConfig.SPEC, "oremarket.toml");
        OreMarketNetwork.init();
        // Config hot reload: the player edits and saves config/oremarket.toml, then Forge fires ModConfigEvent.Reloading.
        // Only a flag is set here; the real recalculation is scheduled to the END phase of ServerTickEvent (see MarketReloadService).
        FMLJavaModLoadingContext.get().getModEventBus().addListener(OreMarketMod::onConfigReloading);
    }

    /**
     * Mod-bus event (IModBusEvent): the server only sets a flag; the real recalculation is scheduled to the END phase of ServerTickEvent.
     * The client receives this event too: it refreshes its display cache from the local file ONLY while not connected to a server;
     * when connected, local config is ignored in favor of the server's SyncConfigPacket (local values do not count).
     */
    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (!event.getConfig().getModId().equals(MOD_ID)) {
            return;
        }
        MarketReloadService.requestReload();
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT) {
            // Client process: repaint from the local file only when offline (main menu / single-player world not started yet);
            // connected to a server, local config is ignored entirely and the SyncConfigPacket push is awaited.
            if (cn.oremarket.client.ClientConfigCache.notConnected()) {
                net.minecraft.client.Minecraft.getInstance().execute(
                        cn.oremarket.client.ClientConfigCache::applyLocalConfig);
            }
        }
    }
}
