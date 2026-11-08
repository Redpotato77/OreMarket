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

package cn.oremarket.market.event;

import cn.oremarket.common.CustomEventParser;
import cn.oremarket.market.StockRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Custom event bridge (market layer): turns the common parser's plain-data results into EventTemplates and registers them.
 *
 * Hot-reload flow (requirement 2, item 17):
 * 1. re-parse the customEvents entries from the config;
 * 2. {@link EventRegistry#replaceCustoms} swaps the whole template table -
 * stale custom templates that never fired are pruned here (the registry never grows unbounded);
 * 3. already-active custom event instances keep the old template object until they end naturally ("last until done").
 */
public final class CustomEventBridge {
    private static final Logger LOG = LoggerFactory.getLogger("OreMarket/CustomEvents");

    private CustomEventBridge() {
    }

    /** Parse and register a batch of config entries; returns the number of templates registered. Invalid entries are skipped with a WARN. */
    public static int reload(List<String> lines) {
        Set<String> validCodes = new LinkedHashSet<>();
        // The registry singleton is never null (static init seeds the 8 fallback stocks); after rebuildAndApply it holds the full configured code list
        for (var s : StockRegistry.server().getActiveStocks()) {
            validCodes.add(s.code());
        }
        List<CustomEventParser.Result> results =
                CustomEventParser.parseAll(lines, validCodes, EventRegistry.builtinNames());
        List<EventTemplate> templates = new ArrayList<>();
        for (CustomEventParser.Result r : results) {
            if (r.status == CustomEventParser.Status.OK) {
                templates.add(toTemplate(r.event));
            } else {
                LOG.warn("custom event entry skipped ({}): {}", r.status, r.reason);
            }
        }
        EventRegistry.replaceCustoms(templates);
        LOG.info("custom event hot-reload done: {} registered", templates.size());
        return templates.size();
    }

    /** Plain data -> template (fixed target code, fixed parameter effect, duration configured in seconds and converted to fixed ticks). */
    static EventTemplate toTemplate(CustomEventParser.ParsedEvent e) {
        EventTemplate.Builder b = EventTemplate.of(EventRegistry.customId(e.name), e.name, e.rarity)
                .desc(e.description)
                .fixedCodes(e.codes.toArray(new String[0]))
                .fixedSeconds(e.durationSeconds)
                .custom();
        for (var spec : e.effects) {
            b.effect(spec);
        }
        return b.build();
    }
}
