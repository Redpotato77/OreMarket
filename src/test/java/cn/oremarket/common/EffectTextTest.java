package cn.oremarket.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Attack cases for segment encoding: multi-segment copy must be splittable and translatable per segment on the client, and truncation or foreign data must never forge a segment boundary (structural fix for Chinese leaking into the English UI).
 *
 * <p>Source stays ASCII: Chinese is always written as unicode escape sequences, same convention as I18nGuard.scanJavaStringLiterals.</p>
 */
final class EffectTextTest {

    /** Price limits: +/- 10% */
    private static final String LIMIT_DOWN = "\u6da8\u8dcc\u505c \u00b110%";
    /** Trend: down arrow */
    private static final String TREND_DOWN = "\u8d8b\u52bf \u2193";
    /** Price multiplier x0.85 */
    private static final String PRICE_MULT = "\u4ef7\u683c\u500d\u7387 \u00d70.85";

    @Test
    void joinsPiecesWithInvisibleBoundaryOnly() {
        String encoded = EffectText.join(List.of(TREND_DOWN, LIMIT_DOWN));
        assertTrue(EffectText.isComposed(encoded), "the joined result must carry the piece boundary");
        assertEquals(List.of(TREND_DOWN, LIMIT_DOWN), EffectText.split(encoded));
        assertEquals(1, countPieces(encoded), "exactly one separator between two pieces");
    }

    @Test
    void skipsNullAndEmptyPieces() {
        assertEquals(TREND_DOWN, EffectText.join(Arrays.asList(TREND_DOWN, "", null)));
        assertEquals("", EffectText.join(List.of()));
        assertEquals("", EffectText.join(null));
        assertFalse(EffectText.isComposed(null));
        assertFalse(EffectText.isComposed(""));
    }

    @Test
    void plainJoinKeepsOldSpaceSeparatedShape() {
        assertEquals(TREND_DOWN + " " + LIMIT_DOWN,
                EffectText.joinPlain(List.of(TREND_DOWN, LIMIT_DOWN)));
        assertEquals("", EffectText.joinPlain(null));
    }

    @Test
    void foreignDataCannotForgeBoundaries() {
        // Foreign data (player-defined names etc.) containing separators: when embedded they are flattened to spaces and must not invent a segment boundary
        String evil = "abc" + EffectText.PIECE + "def";
        String encoded = EffectText.join(List.of(evil));
        assertEquals("abc def", encoded);
        assertFalse(EffectText.isComposed(encoded));
    }

    @Test
    void nestedPiecesFlattenInsteadOfSplittingTheOuterSentence() {
        // An already-encoded multi-segment string spliced into other copy: flattened to spaces, so the outer layer cannot split at the boundary and translate only half
        String nested = EffectText.join(List.of(TREND_DOWN, LIMIT_DOWN));
        String outer = EffectText.join(List.of(PRICE_MULT, nested));
        assertEquals(1, countPieces(outer), "only the outer layer's own single boundary remains");
        assertEquals(List.of(PRICE_MULT, TREND_DOWN + " " + LIMIT_DOWN), EffectText.split(outer));
    }

    @Test
    void flattenCollapsesRunsAndKeepsPlainTextUntouched() {
        assertEquals("a b", EffectText.flatten("a" + EffectText.PIECE + "b"));
        assertEquals("a b", EffectText.flatten("a" + EffectText.PIECE + EffectText.PIECE + "b"));
        // When a boundary sits next to whitespace only one space remains, with no leading/trailing blanks
        assertEquals("a b", EffectText.flatten("a " + EffectText.PIECE + " b"));
        assertEquals("a b", EffectText.flatten("a" + EffectText.PIECE + "  b"));
        // Text without boundaries is returned byte-for-byte unchanged (internal spaces belong to the copy itself; flattening does not rewrite them)
        assertEquals("a  b", EffectText.flatten("a  b"));
        assertEquals("ab", EffectText.flatten("ab"));
        assertEquals("", EffectText.flatten(""));
        assertNull(EffectText.flatten(null));
    }

    @Test
    void cutOnlyBreaksAtPieceBoundaries() {
        String encoded = EffectText.join(List.of(PRICE_MULT, LIMIT_DOWN));
        // Cap just too small for the second segment: keep only the first segment, never leave half a template
        assertEquals(PRICE_MULT, EffectText.cut(encoded, PRICE_MULT.length()));
        // Cap cannot even fit the first segment: hard-truncate instead of returning an empty string
        assertEquals(PRICE_MULT.substring(0, 4), EffectText.cut(encoded, 4));
        // Non-encoded strings are hard-truncated at the same length; text within the cap is returned as-is
        assertEquals("abcd", EffectText.cut("abcdefgh", 4));
        assertEquals(PRICE_MULT, EffectText.cut(PRICE_MULT, 999));
        assertEquals("", EffectText.cut(null, 5));
    }

    @Test
    void overlayScanOnlyJoinsEffectAndTooltipKeys() {
        // The server only ever joins effect abbreviations with their hover tooltip; command output and repayment diagnostics are separate sentences
        assertTrue(I18nGuard.isComposableEffectKey("oremarket.event.effect.price_mult"));
        assertTrue(I18nGuard.isComposableEffectKey("oremarket.event.tooltip.price_limit"));
        assertFalse(I18nGuard.isComposableEffectKey("oremarket.cmd.config.events"));
        assertFalse(I18nGuard.isComposableEffectKey("oremarket.text.0588"));
        assertFalse(I18nGuard.isComposableEffectKey(null));
        assertFalse(I18nGuard.isComposableEffectKey(""));
    }

    @Test
    void splitHandlesTrailingAndConsecutiveSeparators() {
        String sep = String.valueOf(EffectText.PIECE);
        assertEquals(List.of("a", "b"), EffectText.split("a" + sep + "b"));
        assertEquals(List.of("a", "b"), EffectText.split("a" + sep + sep + "b"));
        assertEquals(List.of("a"), EffectText.split("a" + sep));
        assertEquals(List.of(), EffectText.split(""));
        assertEquals(List.of(), EffectText.split(null));
    }

    private static int countPieces(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == EffectText.PIECE) {
                n++;
            }
        }
        return n;
    }
}
