package com.sandbox.receipt;

import com.sandbox.receipt.GoldenReceipt.ReceiptExpectation;
import com.sandbox.receipt.domain.ExpenseCategory;
import com.sandbox.receipt.domain.ReceiptData;
import com.sandbox.receipt.evaluators.ExactTotalAndTaxEvaluator;
import com.sandbox.receipt.evaluators.LineItemSumConsistencyEvaluator;
import com.sandbox.receipt.evaluators.TranslationRubricFactory;
import dev.dokimos.core.EvalResult;
import dev.dokimos.core.EvalTestCase;
import dev.dokimos.core.Evaluator;
import dev.dokimos.core.JudgeLM;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline (no LLM) verification of the deterministic evaluators (spec §5,
 * tiers 1 and 2) and of the judge factory wiring (tiers 3 and 4).
 */
class DeterministicEvaluatorTest {

    // ---- fixtures -------------------------------------------------------

    private static ReceiptData receipt(Integer subtotal, Integer tax8, Integer tax10, Integer total,
                                       ReceiptData.ReceiptItem... items) {
        return new ReceiptData(
                "セブン-イレブン 目黒駅東口店",
                "7-Eleven Meguro Station East Exit",
                "2026-10-24T08:42:00",
                "JPY",
                subtotal, tax8, tax10, total,
                ExpenseCategory.GROCERIES,
                List.of(items));
    }

    private static ReceiptData.ReceiptItem item(String original, String english, int unitPrice,
                                                boolean reducedTax) {
        return new ReceiptData.ReceiptItem(original, english, 1, unitPrice, reducedTax);
    }

    private static GoldenReceipt.ReceiptExpectation expectation(Integer total, Integer tax8, Integer tax10) {
        return new ReceiptExpectation(total, tax8, tax10, null, List.of("GROCERIES"), List.of("salmon"));
    }

    private static EvalTestCase testCase(ReceiptData actual, ReceiptExpectation expected) {
        return EvalTestCase.builder()
                .input("セブン-イレブン 目黒駅東口店\n合計 ¥352")
                .actualOutput("output", actual)
                .expectedOutput("output", expected)
                .build();
    }

    // ---- ExactTotalAndTaxEvaluator --------------------------------------

    @Test
    void exactTotalAndTaxPassesOnExactMatch() {
        ReceiptData good = receipt(327, 25, 0, 352,
                item("手巻おにぎり 熟成紅しゃけ", "Hand-rolled Onigiri (Aged Red Salmon)", 189, true),
                item("サントリー 伊右衛門 600ml", "Suntory Iyemon Green Tea 600ml", 160, true),
                item("レジ袋", "Plastic Shopping Bag", 3, false));

        EvalResult result = new ExactTotalAndTaxEvaluator().evaluate(testCase(good, expectation(352, 25, 0)));
        assertEquals(1.0, result.score());
        assertTrue(result.success());
    }

    @Test
    void exactTotalAndTaxFailsOnWrongTaxBucket() {
        ReceiptData mixedUp = receipt(327, 0, 25, 352,
                item("手巻おにぎり 熟成紅しゃけ", "Onigiri", 189, true));
        EvalResult result = new ExactTotalAndTaxEvaluator().evaluate(testCase(mixedUp, expectation(352, 25, 0)));
        assertEquals(0.0, result.score());
        assertFalse(result.success());
        assertTrue(result.reason().contains("tax8PercentAmount"), () -> result.reason());
    }

    @Test
    void exactTotalAndTaxSkipsNullExpectationFields() {
        ReceiptData actual = receipt(327, 25, 0, 352, item("おにぎり", "Onigiri", 189, true));
        ReceiptExpectation totalOnly = new ReceiptExpectation(352, null, null, null, null, null);
        EvalResult result = new ExactTotalAndTaxEvaluator().evaluate(testCase(actual, totalOnly));
        assertEquals(1.0, result.score());
    }

    // ---- LineItemSumConsistencyEvaluator --------------------------------

    @Test
    void lineItemSumPassesWhenItemsReconcile() {
        ReceiptData good = receipt(327, 25, 0, 352,
                item("おにぎり", "Onigiri", 189, true),
                item("お茶", "Green Tea", 160, true),
                item("レジ袋", "Plastic Shopping Bag", 3, false));
        EvalResult result = new LineItemSumConsistencyEvaluator().evaluate(testCase(good, expectation(352, 25, 0)));
        assertEquals(1.0, result.score(), () -> result.reason());
    }

    @Test
    void lineItemSumPassesWithNegativeDiscountLines() {
        // 248 + 158 + 258 - 50 = 614, subtotal 569 + tax 45 = 614
        ReceiptData discounted = receipt(569, 45, 0, 614,
                item("卵 M 6個", "Eggs (M, 6 pack)", 248, true),
                item("食パン 6枚切り", "Sliced Bread (6 slices)", 158, true),
                item("牛乳 1000ml", "Milk 1000ml", 258, true),
                item("シール値引", "Markdown sticker discount", -50, true));
        EvalResult result = new LineItemSumConsistencyEvaluator().evaluate(testCase(discounted, expectation(614, 45, 0)));
        assertEquals(1.0, result.score(), () -> result.reason());
    }

    @Test
    void lineItemSumFailsWhenDiscountBecomesPositive() {
        // Classic Bucket-2 failure: -50 flipped to +50 breaks the sum.
        ReceiptData flipped = receipt(619, 45, 0, 614,
                item("卵 M 6個", "Eggs (M, 6 pack)", 248, true),
                item("食パン 6枚切り", "Sliced Bread (6 slices)", 158, true),
                item("牛乳 1000ml", "Milk 1000ml", 258, true),
                item("シール値引", "Markdown sticker discount", 50, true));
        EvalResult result = new LineItemSumConsistencyEvaluator().evaluate(testCase(flipped, expectation(614, 45, 0)));
        assertEquals(0.0, result.score());
        assertTrue(result.reason().contains("NEGATIVE"), () -> result.reason());
    }

    @Test
    void lineItemSumRespectsQuantity() {
        ReceiptData withQty = receipt(327, 25, 0, 352,
                item("おにぎり", "Onigiri", 189, true),
                new ReceiptData.ReceiptItem("お茶", "Green Tea", 2, 80, true),
                item("レジ袋", "Plastic Shopping Bag", 3, false));
        EvalResult result = new LineItemSumConsistencyEvaluator().evaluate(testCase(withQty, expectation(352, 25, 0)));
        assertEquals(1.0, result.score(), () -> result.reason());
    }

    // ---- judge factory wiring -------------------------------------------

    @Test
    void judgeFactoryBuildsEvaluatorsWithSpecThresholds() {
        JudgeLM noopJudge = prompt -> "reasoning... 0.9";
        Evaluator translation = TranslationRubricFactory.translationFidelityJudge(noopJudge);
        Evaluator category = TranslationRubricFactory.categoryClassificationJudge(noopJudge);

        assertEquals("TranslationFidelityJudge", translation.name());
        assertEquals(0.8, translation.threshold());
        assertEquals("CategoryClassificationJudge", category.name());
        assertEquals(0.85, category.threshold());

        assertTrue(TranslationRubricFactory.TRANSLATION_RUBRIC.contains("熟成紅しゃけ"));
        assertTrue(TranslationRubricFactory.TRANSLATION_RUBRIC.contains("お釣り"));
        assertTrue(TranslationRubricFactory.CATEGORY_RUBRIC.contains("allowedCategories"));
    }
}
