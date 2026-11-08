package cn.oremarket.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Template-injection attack cases: bad pairs, self-referencing stock names and repeated placeholders must all finish in bounded time. */
final class TemplateMatchAttackTest {

    /** Hand-build a list containing null and truncated pairs (List.of rejects null, which is exactly what we need to simulate malicious data). */
    private static List<String[]> evilSegments() {
        List<String[]> list = new ArrayList<>();
        list.add(new String[]{"", "\u7206"});          // Empty source text: without skipping, the word would be inserted between every character
        list.add(new String[]{"a", "A"});
        list.add(null);                             // null pair
        list.add(new String[]{"\u574F"});              // truncated pair
        return list;
    }

    @Test
    void emptySegmentCannotExpandEveryCharacter() {
        String out = TemplateMatch.applySegments("abc", evilSegments());
        assertEquals("Abc", out, "malformed pair lists are skipped while valid segments still replace");
    }

    @Test
    void selfReferentialStockNameIsSinglePass() {
        List<String[]> pairs = List.of(new String[][]{
                {"{stock}\u5C0F\u9053\u6D88\u606F", "\u6D88\u606F\uFF1A{stock}"}});
        Map<String, String> names = Map.of("{stock}", "{stock}");
        String out = TemplateMatch.applyStockTemplates("{stock}\u5C0F\u9053\u6D88\u606F", pairs, names);
        assertEquals("\u6D88\u606F\uFF1A{stock}", out, "a self-referencing name replaces exactly once without recursion");
        // Reverse case: a normal name still hits -> single replacement
        String hit = TemplateMatch.applyStockTemplates("\u7164\u70AD\u5C0F\u9053\u6D88\u606F", pairs,
                Map.of("\u7164\u70AD", "Coal"));
        assertEquals("\u6D88\u606F\uFF1ACoal", hit);
    }

    @Test
    void longerStockNameWinsOverShorterSuffix() {
        List<String[]> pairs = List.of(new String[][]{
                {"{stock}\u7B49\u77FF\u533A\u8FDE\u73AF\u584C\u65B9\uFF0C\u51CF\u4EA7\u9884\u671F\u63A8\u9AD8\u4EF7\u683C\u4E14\u6CE2\u52A8\u52A0\u5267",
                        "{stock} mines hit by chain collapses; output-cut expectations lift prices and volatility"}});
        Map<String, String> names = new java.util.LinkedHashMap<>();
        names.put("\u91D1\u952D", "Gold Ingot");
        names.put("\u4E0B\u754C\u5408\u91D1\u952D", "Netherite Ingot");
        names.put("\u94DC\u952D", "Copper Ingot");
        String raw = "\u4E0B\u754C\u5408\u91D1\u952D\u7B49\u77FF\u533A\u8FDE\u73AF\u584C\u65B9\uFF0C\u51CF\u4EA7\u9884\u671F\u63A8\u9AD8\u4EF7\u683C\u4E14\u6CE2\u52A8\u52A0\u5267";
        String out = TemplateMatch.applyStockTemplates(raw, pairs, names);
        assertEquals("Netherite Ingot mines hit by chain collapses; output-cut expectations lift prices and volatility",
                out, "longest stock name must win; Gold Ingot must not eat Netherite Ingot");
        assertFalse(out.contains("Gold"), "suffix collision must not leak a shorter name");
        assertFalse(out.matches(".*[\\u3400-\\u9fff].*"), "English result must contain no CJK");
    }

    @Test
    void malformedPairsAndNullInputsDoNotThrow() {
        assertNull(TemplateMatch.applyStockTemplates(null, List.of(), Map.of("a", "b")));
        assertNull(TemplateMatch.applyStockTemplates("x", null, Map.of("a", "b")));
        assertNull(TemplateMatch.applyStockTemplates("x", List.<String[]>of(new String[]{"x"}), null));
        assertNull(TemplateMatch.applyStockTemplates("x", List.<String[]>of(new String[]{"x"}), Map.of()));
        assertEquals("x", TemplateMatch.applySegments("x", new ArrayList<>()));
        assertEquals("x", TemplateMatch.applySegments("x", List.<String[]>of(new String[]{"y", "z"})));
    }

    /**
     * The pre-sorted hot path is byte-equivalent to the Map entry point,
     * and malicious cases (empty name, null entry, self-referencing name) still perform at most one linear replacement.
     */
    @Test
    void orderedHotPathMatchesMapEntryExactly() {
        List<String[]> pairs = List.<String[]>of(new String[]{
                "{stock}\u5C0F\u9053\u6D88\u606F", "\u6D88\u606F\uFF1A{stock}"});
        Map<String, String> names = new java.util.LinkedHashMap<>();
        names.put("\u7164\u70AD", "Coal");
        names.put("\u4E0B\u754C\u5408\u91D1\u952D", "Netherite Ingot");
        List<String[]> ordered = new ArrayList<>();
        for (Map.Entry<String, String> e : names.entrySet()) {
            ordered.add(new String[]{e.getKey(), e.getValue()});
        }
        ordered.sort(Comparator.comparingInt((String[] pair) -> -pair[0].length()));

        for (String raw : List.of("\u7164\u70AD\u5C0F\u9053\u6D88\u606F", "\u4E0B\u754C\u5408\u91D1\u952D\u5C0F\u9053\u6D88\u606F", "\u5C0F\u9053\u6D88\u606F", "", "{stock}\u5C0F\u9053\u6D88\u606F")) {
            assertEquals(TemplateMatch.applyStockTemplates(raw, pairs, names),
                    TemplateMatch.applyStockTemplatesOrdered(raw, pairs, ordered),
                    "ordered API must equal the Map API for [" + raw + "]");
        }
        // Bad entries in the name table must be skipped without throwing and without blowing up the whole chain
        List<String[]> withBad = new ArrayList<>(ordered);
        withBad.add(null);
        withBad.add(new String[]{null, "X"});
        withBad.add(new String[]{"", "X"});
        withBad.add(new String[]{"\u94DC", null});
        assertEquals("\u6D88\u606F\uFF1ACoal",
                TemplateMatch.applyStockTemplatesOrdered("\u7164\u70AD\u5C0F\u9053\u6D88\u606F", pairs, withBad));
        assertNull(TemplateMatch.applyStockTemplatesOrdered("x", pairs, List.of()));
        assertNull(TemplateMatch.applyStockTemplatesOrdered(null, pairs, ordered));
    }

    /**
     * Character pre-filter for fragment replacement: a fragment is only tried when its source's first character occurs in the current string.
     * This case targets chains where a replacement output re-introduces the next fragment's source first character -- the pre-filter must never miss a replacement.
     */
    @Test
    void segmentPreFilterStillChainsAfterReplacement() {
        List<String[]> chain = new ArrayList<>();
        chain.add(new String[]{"\u7164", "\u7164\u70AD"});      // The replacement output newly contains the 'charcoal' character
        chain.add(new String[]{"\u70AD", "\u77FF"});        // The second rule must still hit
        // The first rule expands 'coal' into 'coal-charcoal'; the second rule must still catch the newly introduced 'charcoal'
        assertEquals("\u7164\u77FF", TemplateMatch.applySegments("\u7164", chain));
    }
}
