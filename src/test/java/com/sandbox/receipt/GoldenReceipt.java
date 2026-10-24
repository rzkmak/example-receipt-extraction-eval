package com.sandbox.receipt;

import java.util.List;

/**
 * Mirror of the golden dataset schema (src/test/resources/datasets/golden-receipts.json).
 *
 * <p>{@code expected} is intentionally partial: only the fields a case actually
 * constrains are present, and every accessor on the expectation record is
 * nullable so an evaluator skips a check the dataset did not specify.
 */
public record GoldenReceipt(
        String id,
        String difficulty,
        String notes,
        String rawInput,
        ReceiptExpectation expected
) {

    /**
     * The constrained subset of {@link com.sandbox.receipt.domain.ReceiptData}.
     * Null fields mean "not checked for this case".
     */
    public record ReceiptExpectation(
            Integer totalAmount,
            Integer tax8PercentAmount,
            Integer tax10PercentAmount,
            Integer subtotalAmount,
            List<String> allowedCategories,
            List<String> mustContainTranslatedKeywords
    ) {
    }
}
