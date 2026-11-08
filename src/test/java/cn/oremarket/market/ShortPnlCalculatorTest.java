package cn.oremarket.market;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShortPnlCalculatorTest {
    @Test
    void settledShortProfitAndLossAccumulatesByCode() {
        Map<String, Double> ledger = new HashMap<>();

        ShortPnlCalculator.add(ledger, "COAL", 37.5);
        ShortPnlCalculator.add(ledger, "COAL", -12.5);
        ShortPnlCalculator.add(ledger, "IRON", -20.0);

        assertEquals(25.0, ledger.get("COAL"), 1.0e-9);
        assertEquals(5.0, ShortPnlCalculator.realizedTotal(ledger), 1.0e-9);
    }

    @Test
    void invalidDeltasCannotCreateAccountingEntries() {
        Map<String, Double> ledger = new HashMap<>();

        ShortPnlCalculator.add(ledger, "COAL", Double.NaN);
        ShortPnlCalculator.add(ledger, "COAL", Double.POSITIVE_INFINITY);
        ShortPnlCalculator.add(ledger, "", 10.0);
        ShortPnlCalculator.add(ledger, null, 10.0);

        assertEquals(0.0, ShortPnlCalculator.realizedTotal(ledger), 0.0);
        assertEquals(0, ledger.size());
    }

    @Test
    void reversingASettlementRemovesZeroEntry() {
        Map<String, Double> ledger = new HashMap<>();

        ShortPnlCalculator.add(ledger, "COAL", 10.0);
        ShortPnlCalculator.add(ledger, "COAL", -10.0);

        assertEquals(0, ledger.size());
        assertEquals(0.0, ShortPnlCalculator.realizedTotal(ledger), 0.0);
    }
}
