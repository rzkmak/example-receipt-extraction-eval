package com.sandbox.receipt;

import dev.dokimos.core.Dataset;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline (no LLM) integrity checks for the golden dataset: the §7 matrix
 * buckets are all covered, ids are unique, and the arithmetic baked into each
 * expectation reconciles, so the deterministic evaluators have clean ground truth.
 */
class GoldenDatasetIntegrityTest {

    private final List<GoldenReceipt> cases = GoldenReceipts.load();

    @Test
    void hasFifteenCasesWithUniqueIds() {
        assertEquals(15, cases.size(), "golden matrix is 3 cases x 5 buckets");
        Set<String> ids = new HashSet<>();
        for (GoldenReceipt rc : cases) {
            assertTrue(ids.add(rc.id()), "duplicate id: " + rc.id());
        }
    }

    @Test
    void everyCaseIsComplete() {
        for (GoldenReceipt rc : cases) {
            assertNotNull(rc.difficulty(), rc.id() + ": difficulty");
            assertNotNull(rc.notes(), rc.id() + ": notes");
            assertFalse(rc.rawInput().isBlank(), rc.id() + ": rawInput");
            assertNotNull(rc.expected(), rc.id() + ": expected");
            assertNotNull(rc.expected().totalAmount(), rc.id() + ": expected.totalAmount");
            assertTrue(rc.expected().totalAmount() > 0, rc.id() + ": positive total");
        }
    }

    @Test
    void expectationsReconcileArithmetically() {
        for (GoldenReceipt rc : cases) {
            GoldenReceipt.ReceiptExpectation e = rc.expected();
            if (e.subtotalAmount() != null && e.tax8PercentAmount() != null && e.tax10PercentAmount() != null) {
                int reconstructed = e.subtotalAmount() + e.tax8PercentAmount() + e.tax10PercentAmount();
                assertEquals(e.totalAmount(), reconstructed,
                        rc.id() + ": subtotal + tax8 + tax10 must equal totalAmount");
            }
        }
    }

    @Test
    void expectationsDeclareKeywordsAndCategories() {
        for (GoldenReceipt rc : cases) {
            GoldenReceipt.ReceiptExpectation e = rc.expected();
            assertNotNull(e.allowedCategories(), rc.id() + ": allowedCategories");
            assertFalse(e.allowedCategories().isEmpty(), rc.id() + ": allowedCategories empty");
            assertNotNull(e.mustContainTranslatedKeywords(), rc.id() + ": keywords");
            assertFalse(e.mustContainTranslatedKeywords().isEmpty(), rc.id() + ": keywords empty");
        }
    }

    @Test
    void dokimosDatasetBridgeCarriesPrimaryInputAndExpectation() {
        Dataset dataset = GoldenReceipts.asDokimosDataset();
        assertEquals(cases.size(), dataset.size(), "dataset size");
        dataset.forEach(example -> {
            assertNotNull(example.input(), "primary input (raw receipt text)");
            assertFalse(((String) example.input()).isBlank(), "primary input non-blank");
            assertNotNull(example.expectedOutput(), "primary expected output (expectation)");
        });
    }
}
