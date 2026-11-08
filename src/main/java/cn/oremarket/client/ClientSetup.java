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
import cn.oremarket.common.SanityCheck;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client startup self-check hook (MOD bus, client only).
 *
 * Only the {@link SanityCheck} items that do NOT depend on the resource manager run here (guards, number formatting,
 * fees, and the lang key sets - all read the in-jar JSON straight from the classpath). The {@code L.tr()} smoke test
 * is deferred to the first client tick (see {@link ClientEvents#runStartupTrSmoke()}):
 * FMLClientSetupEvent fires before the first resource-pack load, so seeding the zh_cn baseline-table cache during
 * setup would freeze an empty table into {@code L} and permanently break the overlay - the key reason this hook exists.
 */
@Mod.EventBusSubscriber(modid = OreMarketMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        SanityCheck.runAndLog();
        // The client runs the i18n guard too (read-only classpath JSON, never touches the resource manager,
        // so no empty baseline table can be frozen into L; real translation overrides still wait for the first tick).
        event.enqueueWork(cn.oremarket.common.I18nGuard::runStartupChecks);
    }
}
