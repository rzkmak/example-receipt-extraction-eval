package com.sandbox.receipt.domain;

import java.util.List;

/**
 * Target JSON schema the extractor LLM must produce. Spring AI's
 * {@code BeanOutputConverter} derives the JSON schema from this record and
 * injects it into the prompt, then parses the completion back into it.
 *
 * <p>Prices are tax-inclusive yen amounts, exactly as printed on Japanese
 * receipts. {@code subtotalAmount} is the tax-exclusive portion:
 * {@code totalAmount - tax8PercentAmount - tax10PercentAmount}.
 * {@code transactionDate} is an ISO-8601 local date-time string
 * (e.g. {@code 2026-10-24T08:42:00}).
 */
public record ReceiptData(
        String merchantNameOriginal,
        String merchantNameNormalized,
        String transactionDate,
        String currency,
        Integer subtotalAmount,
        Integer tax8PercentAmount,
        Integer tax10PercentAmount,
        Integer totalAmount,
        ExpenseCategory expenseCategory,
        List<ReceiptItem> items
) {

    public record ReceiptItem(
            String originalName,
            String englishTranslation,
            Integer quantity,
            Integer unitPrice,
            Boolean reducedTaxApplicable
    ) {
    }
}
