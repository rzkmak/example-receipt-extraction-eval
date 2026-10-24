package com.sandbox.receipt.domain;

/**
 * Expense categories the extractor may assign. Kept deliberately coarse:
 * ambiguous receipts (drugstore with medicine + snacks + cosmetics, a café
 * selling beans next to dine-in lattes) are exactly the borderline cases the
 * {@code CategoryClassificationJudge} probes, so the category list should not
 * be so fine-grained that classification becomes trivially wrong.
 */
public enum ExpenseCategory {
    GROCERIES,
    DINING,
    MEDICAL,
    ELECTRONICS,
    TRANSIT,
    PERSONAL_CARE,
    HOUSEHOLD,
    OTHER
}
