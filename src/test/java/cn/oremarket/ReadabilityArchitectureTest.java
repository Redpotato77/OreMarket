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

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the package boundaries that make manual review possible.
 *
 * <p>Layering: {@code client} and {@code command} sit on top, {@code net} and {@code market}
 * carry server behaviour, {@code config} declares settings and {@code common} holds shared pure
 * logic. Lower layers must never reach into {@code client}: that would make a server-only build
 * depend on rendering code and hide the direction of data flow from reviewers.</p>
 */
class ReadabilityArchitectureTest {

    /** Maximum number of directories walked upward to locate the Gradle project from a test JVM. */
    private static final int MAX_PROJECT_ROOT_LOOKUP_DEPTH = 6;
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?);", Pattern.MULTILINE);
    private static final Pattern FREEZE_BUY_CALL = Pattern.compile("data\\.addFrozen\\s*\\(");
    private static final String COST_WRITE = "data.setCost(";
    private static final String COST_METHOD_BODY = "public static double weightedAverageBuyCost(";
    private static final int COST_SEARCH_RADIUS = 800;
    private static final int EXPECTED_BUY_FREEZE_COUNT = 2;

    /** Packages that must never appear in an import issued by the owner on the left. */
    private static final List<Rule> RULES = List.of(
            new Rule("cn.oremarket.common", Set.of("cn.oremarket.client", "cn.oremarket.command")),
            new Rule("cn.oremarket.config", Set.of("cn.oremarket.client")),
            new Rule("cn.oremarket.market", Set.of("cn.oremarket.client")),
            new Rule("cn.oremarket.net.packet", Set.of("cn.oremarket.client")));

    @Test
    void lowerLayersNeverImportClientCode() throws IOException {
        Path root = projectRoot();
        List<String> violations = new ArrayList<>();
        for (Path directory : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
            Path sourceRoot = root.resolve(directory);
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (var paths = Files.walk(sourceRoot)) {
                for (Path path : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    checkFile(sourceRoot.relativize(path).toString(), Files.readString(path, StandardCharsets.UTF_8), violations);
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("Forbidden layer imports found:\n" + String.join("\n", violations));
        }
    }

    @Test
    void everyBuyFillWritesCostThroughSharedWeightedAverageEntry() throws IOException {
        Path root = projectRoot();
        Path tradeService = root.resolve("src/main/java/cn/oremarket/market/TradeService.java");
        Path limitOrderService = root.resolve("src/main/java/cn/oremarket/market/LimitOrderService.java");
        Path fees = root.resolve("src/main/java/cn/oremarket/common/Fees.java");
        String trade = Files.readString(tradeService, StandardCharsets.UTF_8);
        String limit = Files.readString(limitOrderService, StandardCharsets.UTF_8);
        String feeMath = Files.readString(fees, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        int freezeCount = assertEveryFreezeWritesCost("TradeService", trade, violations);
        freezeCount += assertEveryFreezeWritesCost("LimitOrderService", limit, violations);
        if (freezeCount != EXPECTED_BUY_FREEZE_COUNT) {
            violations.add("Expected exactly market-trade and limit-order buy freezes, found " + freezeCount);
        }
        if (!feeMath.contains(COST_METHOD_BODY)) {
            violations.add("Fees weightedAverageBuyCost entry is missing");
        }
        if (!violations.isEmpty()) {
            fail("Buy-fill cost accounting guard failed:\n" + String.join("\n", violations));
        }
    }

    private static int assertEveryFreezeWritesCost(String label, String source, List<String> violations) {
        Matcher freezeMatcher = FREEZE_BUY_CALL.matcher(source);
        int count = 0;
        while (freezeMatcher.find()) {
            count++;
            int start = Math.max(0, freezeMatcher.start() - COST_SEARCH_RADIUS);
            String around = source.substring(start,
                    Math.min(source.length(), freezeMatcher.end() + COST_SEARCH_RADIUS));
            if (!around.contains(COST_WRITE)) {
                violations.add(label + " addFrozen call is not followed by setCost");
            }
        }
        return count;
    }

    private static void checkFile(String relativePath, String source, List<String> violations) {
        Matcher packageMatcher = PACKAGE.matcher(source);
        if (!packageMatcher.find()) {
            return;
        }
        String owner = packageMatcher.group(1);
        Matcher importMatcher = IMPORT.matcher(source);
        while (importMatcher.find()) {
            String imported = importMatcher.group(1);
            for (Rule rule : RULES) {
                if (owner.startsWith(rule.owner) && startsWithAny(imported, rule.forbidden)) {
                    violations.add(relativePath + ": " + owner + " imports forbidden " + imported);
                }
            }
        }
    }

    private static boolean startsWithAny(String imported, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (imported.equals(prefix) || imported.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static Path projectRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < MAX_PROJECT_ROOT_LOOKUP_DEPTH && candidate != null; depth++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("build.gradle")) && Files.isDirectory(candidate.resolve("src/main/java"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate the project root from the test working directory");
    }

    private record Rule(String owner, Set<String> forbidden) {
    }
}
