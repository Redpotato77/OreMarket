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

package cn.oremarket.common;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * "Raw text template" reader.
 *
 * All UI/log raw text in this mod is keyed: the values in zh_cn.json ARE the raw text used by both server and
 * client; at render time the client's language overlay (L) swaps raw text into the player's current language.
 * When the server assembles sentences it fetches templates from the embedded zh_cn.json through this class and
 * fills them, so the raw text matches the lang table verbatim and the client can hit whole-sentence translations.
 *
 * Loading: straight through the mod jar's classloader (the same path client and server; dev uses build/resources/main),
 * with no sided API dependency; any load failure degrades to an empty table plus one warning, so every call site
 * gets a non-null string and no exception is thrown.
 */
public final class RawText {

    private static final Logger LOG = LoggerFactory.getLogger("OreMarket/RawText");
    private static final String PATH = "assets/oremarket/lang/zh_cn.json";

    /** key -> raw text (zh_cn value); read once and kept resident. */
    private static volatile Map<String, String> byKey = null;
    /** raw text -> key reverse index (first key wins on ties; only for mapping legacy raw text back to keys). */
    private static Map<String, String> byValue = null;
    /** Warn once per missing key to avoid log spam. */
    private static final java.util.Set<String> warned =
            Collections.synchronizedSet(new java.util.HashSet<>());

    private RawText() {
    }

    private static Map<String, String> table() {
        Map<String, String> local = byKey;
        if (local != null) {
            return local;
        }
        synchronized (RawText.class) {
            if (byKey == null) {
                byKey = load();
            }
            return byKey;
        }
    }

    private static Map<String, String> load() {
        Map<String, String> out = new HashMap<>();
        try (InputStream in = RawText.class.getClassLoader().getResourceAsStream(PATH)) {
            if (in == null) {
                LOG.warn("RawText: zh_cn.json not found on classpath ({})", PATH);
            } else {
                JsonElement root = new Gson().fromJson(
                        new InputStreamReader(in, StandardCharsets.UTF_8), JsonElement.class);
                if (root != null && root.isJsonObject()) {
                    JsonObject obj = root.getAsJsonObject();
                    for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                        if (e.getValue().isJsonPrimitive()) {
                            out.put(e.getKey(), e.getValue().getAsString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("RawText: failed to load {}", PATH, e);
        }
        Map<String, String> rev = new HashMap<>();
        for (Map.Entry<String, String> e : out.entrySet()) {
            rev.putIfAbsent(e.getValue(), e.getKey());
        }
        byValue = rev;
        LOG.info("RawText: loaded {} raw templates", out.size());
        return out;
    }

    /** Whether a key exists in the raw table (used by self-checks and degradation logic). */
    public static boolean has(String key) {
        return key != null && table().containsKey(key);
    }

    /** The whole zh_cn baseline table (read-only view): the language overlay walks it during startup self-check. */
    public static Map<String, String> all() {
        return java.util.Collections.unmodifiableMap(table());
    }

    /**
     * key -> raw text. Missing keys return the key itself with a one-time warning: logs and text would rather show
     * the key name than throw and break the server main loop.
     */
    public static String t(String key) {
        if (key == null) {
            return "";
        }
        String v = table().get(key);
        if (v == null) {
            if (warned.add(key)) {
                LOG.warn("RawText: missing lang key {}", key);
            }
            return key;
        }
        return v;
    }

    /** key + args -> finished raw sentence (printf-style %s). */
    public static String f(String key, Object... args) {
        String tpl = t(key);
        if (args == null || args.length == 0) {
            return tpl;
        }
        try {
            return String.format(Locale.ROOT, tpl, args);
        } catch (RuntimeException badTemplate) {
            LOG.warn("RawText: bad placeholder usage in key {}", key, badTemplate);
            return tpl;
        }
    }

    /** raw text -> key reverse lookup; returns null for unregistered text (the caller then treats it as plain user data). */
    public static String keyOf(String raw) {
        if (raw == null || table().isEmpty()) {
            return null;
        }
        Map<String, String> rev = byValue;
        return rev == null ? null : rev.get(raw);
    }
}
