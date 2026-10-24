package com.sandbox.receipt.evaluators;

import dev.dokimos.core.EvalTestCaseParam;
import dev.dokimos.core.Evaluator;
import dev.dokimos.core.JudgeLM;
import dev.dokimos.core.evaluators.LLMJudgeEvaluator;

import java.util.List;

/**
 * Builds the two LLM-as-a-Judge evaluators (tiers 3 and 4) from the spec's
 * §5.1 rubric. Kept behind a factory so tests, experiments, and any future
 * harness share one authoritative rubric text.
 */
public final class TranslationRubricFactory {

    private TranslationRubricFactory() {
    }

    public static final String TRANSLATION_RUBRIC = """
            You are an expert bilingual (Japanese/English) accounting auditor evaluating an LLM receipt extraction pipeline.

            The INPUT is the [RAW RECEIPT INPUT]; the ACTUAL OUTPUT is the [EXTRACTED JSON].
            Score the extraction from 0.0 to 1.0 based on these strict rules:
            1. Completeness (0.4 weight): Every single purchased item in the raw receipt appears in `items[]`. Discounts (値引/割引) are preserved accurately as negative line items, and footer noise (change given お釣り, cash tendered お預り, point balances) is NOT mistakenly added as a line item.
            2. Translation Nuance (0.4 weight): Japanese food, electronics, or transit terms are translated into natural, specific English (e.g., "熟成紅しゃけ" -> "Aged Red Salmon", NOT generic "Food" or phonetic "Jukusei Beni Shake"). Abbreviated katakana brand names must resolve to the real brand (e.g. "ロジクール" -> "Logitech").
            3. Tax Flag Accuracy (0.2 weight): Items marked with `※` or `*` on Japanese receipts indicate 8% reduced consumption tax (`reducedTaxApplicable: true`). Standard items (alcohol, plastic bags, electronics) must be `false`.

            Work through the raw receipt line by line before scoring: list any missing items, hallucinated items, weak translations, or wrong tax flags, then output the final score on its own last line as a number between 0.0 and 1.0.
            """;

    public static final String CATEGORY_RUBRIC = """
            You are auditing the category assignment of a bilingual (Japanese/English) receipt extraction.

            The INPUT is the [RAW RECEIPT INPUT]; the ACTUAL OUTPUT is the [EXTRACTED JSON]
            (with `merchantNameNormalized`, `items[]` and `expenseCategory`); the EXPECTED OUTPUT
            lists the `allowedCategories` the dataset accepts for this receipt.

            Judge whether the selected `expenseCategory` logically fits the merchant and the
            majority of the purchased items. Borderline receipts are intentional (e.g. a drugstore
            selling medicine, snacks and cosmetics, or a café selling beans next to a dine-in latte):
            any category in `allowedCategories` is a full score of 1.0. A defensible but unlisted
            category earns 0.5. A category contradicted by the receipt earns 0.0.

            Reason about the merchant and the items briefly, then output the final score on its own
            last line as a number between 0.0 and 1.0.
            """;

    /** Tier 3: translation fidelity judge. Threshold 0.8 per the spec. */
    public static Evaluator translationFidelityJudge(JudgeLM judge) {
        return LLMJudgeEvaluator.builder()
                .name("TranslationFidelityJudge")
                .criteria(TRANSLATION_RUBRIC)
                .evaluationParams(List.of(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT))
                .threshold(0.8)
                .judge(judge)
                .build();
    }

    /** Tier 4: category classification judge. Threshold 0.85 per the spec. */
    public static Evaluator categoryClassificationJudge(JudgeLM judge) {
        return LLMJudgeEvaluator.builder()
                .name("CategoryClassificationJudge")
                .criteria(CATEGORY_RUBRIC)
                .evaluationParams(List.of(
                        EvalTestCaseParam.INPUT,
                        EvalTestCaseParam.ACTUAL_OUTPUT,
                        EvalTestCaseParam.EXPECTED_OUTPUT))
                .threshold(0.85)
                .judge(judge)
                .build();
    }
}
