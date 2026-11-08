package cn.oremarket.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the custom-event config parser and its validation rules.
 * Covers: valid lines, wrong field counts, illegal rarity, illegal stock/effect, out-of-range duration, the 50-entry cap, name dedup and conflicts with built-ins.
 */
class CustomEventParserTest {

    private static final Set<String> CODES = Set.of("COAL", "IRON", "GOLD", "DIAMOND");
    private static final Set<String> RESERVED = Set.of("\u77FF\u8109\u5927\u53D1\u73B0", "\u592E\u884C\u52A0\u606F");

    private static CustomEventParser.Result one(String line) {
        List<CustomEventParser.Result> rs = CustomEventParser.parseAll(List.of(line), CODES, RESERVED);
        assertEquals(1, rs.size());
        return rs.get(0);
    }

    @Test
    void parsesFullValidLine() {
        CustomEventParser.Result r = one("\u6D4B\u8BD5\u4E8B\u4EF6|rare|COAL,IRON|price:1.2,volatility:0.5|300|\u5355\u5143\u6D4B\u8BD5\u63CF\u8FF0");
        assertEquals(CustomEventParser.Status.OK, r.status);
        assertEquals("\u6D4B\u8BD5\u4E8B\u4EF6", r.event.name);
        assertEquals(2, r.event.codes.size());
        assertEquals(300, r.event.durationSeconds);
        assertEquals(2, r.event.effects.size());
    }

    @Test
    void rejectsWrongFieldCount() {
        assertEquals(CustomEventParser.Status.BAD_FIELD_COUNT,
                one("\u574F\u884C|\u5E38\u89C1|COAL|price:1.2").status);
    }

    @Test
    void rejectsUnknownRarity() {
        assertEquals(CustomEventParser.Status.BAD_RARITY,
                one("\u6D4B\u8BD5|rare2|COAL|price:1.2|60|d").status);
    }

    @Test
    void rejectsUnknownStockCode() {
        assertEquals(CustomEventParser.Status.BAD_TARGET,
                one("\u6D4B\u8BD5|common|UNOBTAINIUM|price:1.2|60|d").status);
    }

    @Test
    void rejectsOutOfRangeEffect() {
        // price must be within 0.5~1.5; 2.0 is out of range
        assertEquals(CustomEventParser.Status.BAD_EFFECT,
                one("\u6D4B\u8BD5|common|COAL|price:2.0|60|d").status);
    }

    @Test
    void rejectsOutOfRangeDuration() {
        assertEquals(CustomEventParser.Status.BAD_DURATION,
                one("\u6D4B\u8BD5|common|COAL|price:1.2|0|d").status);
    }

    @Test
    void rejectsNameCollidingWithBuiltIn() {
        assertEquals(CustomEventParser.Status.DUPLICATE_NAME,
                one("\u77FF\u8109\u5927\u53D1\u73B0|common|COAL|price:1.2|60|d").status);
    }

    @Test
    void rejectsDuplicateNameWithinBatch() {
        List<CustomEventParser.Result> rs = CustomEventParser.parseAll(
                List.of("\u91CD\u540D|common|COAL|price:1.2|60|d", "\u91CD\u540D|common|IRON|price:1.2|60|d"),
                CODES, RESERVED);
        assertEquals(CustomEventParser.Status.OK, rs.get(0).status);
        assertEquals(CustomEventParser.Status.DUPLICATE_NAME, rs.get(1).status);
    }

    @Test
    void capsAtMaxEntries() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < CustomEventParser.MAX_CUSTOM_EVENTS + 5; i++) {
            lines.add("\u4E8B\u4EF6" + i + "|common|COAL|price:1.2|60|d");
        }
        List<CustomEventParser.Result> rs = CustomEventParser.parseAll(lines, CODES, RESERVED);
        long ok = rs.stream().filter(r -> r.status == CustomEventParser.Status.OK).count();
        long capped = rs.stream().filter(r -> r.status == CustomEventParser.Status.CAP_REACHED).count();
        assertEquals(CustomEventParser.MAX_CUSTOM_EVENTS, ok);
        assertEquals(5, capped);
    }

    @Test
    void blankLinesSkipped() {
        // parseAll silently drops blank lines (they never appear in the result table); only parse reports the SKIPPED_BLANK status
        assertEquals(0, CustomEventParser.parseAll(List.of("  ", ""), CODES, RESERVED).size());
        assertEquals(CustomEventParser.Status.SKIPPED_BLANK,
                CustomEventParser.parse("   ", CODES, RESERVED).status);
    }

    @Test
    void haltEffectNeedsNoParam() {
        CustomEventParser.Result r = one("\u505C\u724C\u6D4B\u8BD5|epic|GOLD|halt|120|d");
        assertEquals(CustomEventParser.Status.OK, r.status);
    }

    @Test
    void priceLimitPercentConvertedToFraction() {
        CustomEventParser.Result r = one("\u505C\u677F\u6D4B\u8BD5|common|COAL|priceLimit:15|60|d");
        assertEquals(CustomEventParser.Status.OK, r.status);
        assertTrue(r.event.effects.get(0).max <= 0.25 + 1e-9,
                "15% must convert to 0.15 and stay inside the legal fraction range");
    }

    @Test
    void emptyCodeListIsRejected() {
        // Validation rule: an empty target list is illegal (market-wide impact is expressed by built-in templates' global effect; custom events must name stocks)
        assertEquals(CustomEventParser.Status.BAD_TARGET,
                one("\u5168\u573A\u4E8B\u4EF6|legend||price:1.2|60|d").status);
    }

    @Test
    void tooShortNameRejected() {
        assertEquals(CustomEventParser.Status.BAD_NAME,
                one("\u77ED|common|COAL|price:1.2|60|d").status);
        assertFalse(one("\u6B63\u5E38\u957F\u5EA6\u540D\u5B57|common|COAL|price:1.2|60|d").status
                == CustomEventParser.Status.BAD_NAME);
    }
}
