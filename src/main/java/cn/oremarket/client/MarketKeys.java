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
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Hotkey Ctrl+G: the on/off key for the mineral stock market screen.
 *
 * Why the previous build did nothing when pressed: in 1.20.1 vanilla, KeyboardHandler#keyPress routes input only
 * to Screen#keyPressed while a screen is open and never records a click on any KeyMapping
 * (verified against the official decompiled bytecode: the screen != null branch skips KeyMapping.click directly),
 * so "press again to close" could never work through ClientTickEvent polling of consumeClick alone.
 * The real input path is now handled in three branches:
 * 1. In-world (no screen): listen on InputEvent.Key (Forge fires it on every key press regardless of screens),
 * open directly, and consume the KeyMapping click to prevent duplicates;
 * ClientTickEvent consumeClick polling stays as a fallback.
 * 2. Market screen open: its own keyPressed checks the same combo and closes on a hit -
 * the main path for "press again to close", independent of any event ordering.
 * 3. Any other screen (inventory etc.): ScreenEvent.KeyPressed.Post checks the combo, cancels the event
 * and opens the market screen; typing in chat is left alone.
 * The binding can be changed under Options - Controls - Miscellaneous; all three branches share one
 * isHotkey() check, so behavior stays consistent. On macOS Screen.hasControlDown() also matches Cmd.
 */
public final class MarketKeys {

    public static final KeyMapping OPEN_MARKET = new KeyMapping(
            "key." + OreMarketMod.MOD_ID + ".open_market",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            KeyMapping.CATEGORY_MISC);

    private MarketKeys() {
    }

    @Mod.EventBusSubscriber(modid = OreMarketMod.MOD_ID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_MARKET);
        }
    }

    @Mod.EventBusSubscriber(modid = OreMarketMod.MOD_ID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Handler {
        private Handler() {
        }

        /** Branch 1: Ctrl+G in-world (no screen open) opens the market directly. */
        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            if (event.getAction() != GLFW.GLFW_PRESS) {
                return; // Only press events count; key repeat never double-fires
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) {
                return; // In-screen cases are left to branches 2/3
            }
            if (isHotkey(event.getKey(), event.getScanCode())) {
                OPEN_MARKET.consumeClick(); // Consume the same press's KeyMapping click so the fallback poll cannot fire twice
                openMarket(mc);
            }
        }

        /** Branch 3: Ctrl+G in any other screen (inventory, chest, etc.) switches straight to the market screen. */
        @SubscribeEvent
        public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Post event) {
            Screen screen = event.getScreen();
            if (screen instanceof MarketScreen || screen instanceof ChatScreen) {
                return; // The market screen closes itself; chat input is not disturbed
            }
            if (isHotkey(event.getKeyCode(), event.getScanCode())) {
                event.setCanceled(true);
                openMarket(Minecraft.getInstance());
            }
        }

        /** Fallback: the standard KeyMapping click poll (in-world only; in-screen presses never count anyway). */
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == null && OPEN_MARKET.consumeClick()) {
                openMarket(mc);
            }
        }
    }

    /**
     * Decide whether the current key event hits this mod's hotkey combo.
     * Uses Forge's isActiveAndMatches: binding (honors user remaps), modifiers and conflict context
     * (UNIVERSAL is always active) are all validated - exactly the logic of the Controls screen.
     */
    public static boolean isHotkey(int keyCode, int scanCode) {
        return OPEN_MARKET.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode));
    }

    /** Top-of-screen hint text: follows the actual binding, e.g. "Ctrl+G toggles the market screen". */
    public static String hotkeyHint() {
        return OPEN_MARKET.getTranslatedKeyMessage().getString() + cn.oremarket.common.RawText.t("oremarket.text.0223");
    }

    private static void openMarket(Minecraft mc) {
        if (mc.player == null || mc.screen instanceof MarketScreen) {
            return;
        }
        mc.setScreen(new MarketScreen());
    }
}
