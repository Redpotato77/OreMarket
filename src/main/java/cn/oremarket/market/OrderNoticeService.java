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

import cn.oremarket.common.LogType;
import cn.oremarket.market.log.LogService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * "No silent disappearance" notices for orders the system cancelled on its own: TTL expiry,
 * stock delisting and repeated trigger failure all route their player feedback through here.
 *
 * <p>Three delivery channels cover every situation:</p>
 * <ul>
 *   <li>owner ONLINE: one detail line in the log tab (LogService) plus a summary toast
 *       (the keys carry "cancel", so ClientEvents files them as forced notices - with the
 *       market screen closed the toast is parked and replayed the next time it opens);</li>
 *   <li>owner OFFLINE: a catch-up counter persisted in MarketSavedData; the next login
 *       replays a red chat line and the same toast, so nothing vanishes unnoticed;</li>
 *   <li>the locked coins / shares themselves need NO transfer-back: locks are derived live
 *       from the order lists, removing the order already releases them.</li>
 * </ul>
 */
public final class OrderNoticeService {

    private OrderNoticeService() {
    }

    /**
     * Records one auto-cancelled order for its owner. Online: pushes the detail log entry and
     * returns true (the caller then counts it into the summary toast batch). Offline: adds the
     * persistent login catch-up counter and returns false.
     */
    public static boolean record(MinecraftServer server, MarketSavedData data, UUID owner, boolean limit,
                                 String logKey, Object... logArgs) {
        if (data == null || owner == null) {
            return false;
        }
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(owner);
        if (player != null) {
            LogService.pushKey(server, data, owner, LogType.SYSTEM, logKey, logArgs);
            return true;
        }
        data.noteOrderCancel(owner, limit, 1);
        return false;
    }

    /** Summary toast for a batch of auto-cancelled orders (online owners only; offline ones were already noted by record). */
    public static void summary(MinecraftServer server, UUID owner, boolean limit, int count) {
        if (server == null || owner == null || count <= 0) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null) {
            return;
        }
        TradeService.feedbackKey(player,
                limit ? "oremarket.toast.limit.cancelled" : "oremarket.toast.order.cancelled",
                false, 80, String.valueOf(count));
    }

    /** Single-order toast (e.g. the repeated insufficient-funds cancellation of one limit order). */
    public static void single(MinecraftServer server, UUID owner, String key, String... args) {
        if (server == null || owner == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            TradeService.feedbackKey(player, key, false, 80, args);
        }
    }

    /**
     * System-side event affecting one player (order fill / forced close / dividend / P2P money
     * movement) with ONE delivery rule: online owners get a toast right away, offline owners get
     * the notice persisted into the login catch-up queue and replayed at the next login
     * (chat line + parked toast). Money never moves in these paths without landing here.
     */
    public static void notice(MinecraftServer server, MarketSavedData data, UUID owner,
                              String key, boolean success, Object... args) {
        if (owner == null || key == null || key.isEmpty()) {
            return;
        }
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(owner);
        if (player != null) {
            TradeService.feedbackKey(player, key, success, 80, strings(args));
            return;
        }
        if (data != null) {
            data.noteOfflineNotice(owner, key, args);
        }
    }

    private static String[] strings(Object... args) {
        if (args == null) {
            return new String[0];
        }
        String[] out = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = String.valueOf(args[i]);
        }
        return out;
    }

    /**
     * Login catch-up: replays the cancellations that happened while the player was offline as
     * a red chat line (visible even with the screen closed) plus the standard toast
     * (parked until the market screen opens next), then the general offline notice queue
     * (system fills, forced short closes, dividends, P2P movements, close-time auto repayments).
     */
    public static void onPlayerLogin(ServerPlayer player) {
        if (player == null || player.server == null) {
            return;
        }
        MarketSavedData data = MarketSavedData.get(player.server);
        int[] n = data.takeOrderCancelNotice(player.getUUID());
        if (n != null) {
            player.displayClientMessage(Component.translatable("oremarket.toast.cancelled.offline", n[0], n[1])
                    .withStyle(ChatFormatting.GOLD), false);
            TradeService.feedbackKey(player, "oremarket.toast.cancelled.offline", false, 100,
                    String.valueOf(n[0]), String.valueOf(n[1]));
            data.setDirty();
        }
        for (MarketSavedData.OfflineNotice notice : data.takeOfflineNotices(player.getUUID())) {
            // Chat line: arguments that ARE translation keys (liquidation reasons, sides) must not
            // leak as raw key strings, so they are resolved against the zh baseline first; the
            // client-side chat overlay re-localizes the result for other languages, exactly like
            // every other server-framed chat notice. The toast keeps the raw keys: its argument
            // pipeline translates them per the client language.
            String[] raw = notice.args.toArray(new String[0]);
            Object[] chatArgs = new Object[raw.length];
            for (int i = 0; i < raw.length; i++) {
                chatArgs[i] = raw[i] != null && raw[i].startsWith("oremarket.")
                        ? cn.oremarket.common.RawText.t(raw[i])
                        : raw[i];
            }
            player.displayClientMessage(Component.translatable(notice.key, chatArgs)
                    .withStyle(ChatFormatting.GOLD), false);
            TradeService.feedbackKey(player, notice.key, false, 100, raw);
        }
    }
}
