package com.sandbox.receipt.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Owns the extraction system prompt: the base rules plus the optional
 * reduced-tax hint. Keeping the prompt text here (not in the service) means
 * prompt edits — and their {@code prompt.template.sha256} trace diff — are
 * reviewable in one place.
 *
 * <p>The hint is the lever for Milestone 3 Experiment A (prompt regression):
 * {@code RECEIPT_TAX_HINT=false} drops rule 9 and the eval suite should fail
 * on tax-flag-heavy cases.
 */
@Component
public class ExtractionPromptFactory {

    private static final String BASE_SYSTEM_PROMPT = """
            You are an expert bilingual (Japanese/English) accounting data extractor.

            You receive raw Japanese receipt text (possibly noisy OCR) and must return ONE
            JSON object that strictly follows the provided JSON schema.

            Extraction rules:
            1. Every purchased line item must appear in `items[]`, with `quantity` and
               `unitPrice` taken from the receipt. Repeat lines count as quantity > 1.
            2. Discounts (値引, 割引, シール値引, セット割引) MUST be preserved as line items
               with a NEGATIVE `unitPrice`, never dropped and never turned into positive lines.
            3. Footer noise is NOT a line item: cash tendered (お預り), change given
               (お釣り), point balances, and store slogans must be ignored.
            4. `merchantNameNormalized` is the English name of the store chain
               (e.g. "セブン-イレブン" -> "7-Eleven"), keeping the branch description
               (e.g. "Meguro Station East Exit").
            5. `englishTranslation` must be natural, specific English for the product or
               brand — not transliteration and not a generic word like "Food".
            6. Prices are tax-inclusive yen. The bottom tax lines (内消費税等 8%対象 / 10%対象)
               give the embedded consumption tax per rate bucket:
               - `tax8PercentAmount`   <- the 8% 対象 amount (reduced rate: food, newspaper)
               - `tax10PercentAmount`  <- the 10% 対象 amount (standard rate)
               - `subtotalAmount`      <- totalAmount - tax8PercentAmount - tax10PercentAmount
               - `totalAmount`         <- 合計 (never お預り or お釣り)
            7. `reducedTaxApplicable` is true for items under the 8% reduced rate
               (ready-to-eat food, groceries), false for the 10% standard rate
               (plastic bags, alcohol, most non-food goods).
            8. `expenseCategory` must be one of the enum values. Judge it from the
               merchant and the majority of items.
            """;

    private static final String TAX_HINT = """
            9. On Japanese receipts, items marked with a leading `※` or `*` are subject to
               the 8% reduced consumption tax, so their `reducedTaxApplicable` is true.
            """;

    private final boolean taxHintEnabled;

    public ExtractionPromptFactory(@Value("${receipt.extraction.tax-hint:true}") boolean taxHintEnabled) {
        this.taxHintEnabled = taxHintEnabled;
    }

    /** The effective system prompt for one extraction run. */
    public String systemPrompt() {
        return taxHintEnabled ? BASE_SYSTEM_PROMPT + TAX_HINT : BASE_SYSTEM_PROMPT;
    }
}
