package com.sandbox.receipt.evaluators;

import com.sandbox.receipt.GoldenReceipt.ReceiptExpectation;
import com.sandbox.receipt.domain.ReceiptData;
import dev.dokimos.core.BaseEvaluator;
import dev.dokimos.core.EvalResult;
import dev.dokimos.core.EvalTestCase;
import dev.dokimos.core.EvalTestCaseParam;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic tier 1 (threshold 1.0): {@code totalAmount} and both tax
 * buckets must match the golden expectation's integers exactly. Expectation
 * fields left null by the dataset are skipped, so a case can constrain, say,
 * only the total.
 */
public class ExactTotalAndTaxEvaluator extends BaseEvaluator {

    public ExactTotalAndTaxEvaluator() {
        super("ExactTotalAndTaxEvaluator", 1.0, List.of(
                EvalTestCaseParam.ACTUAL_OUTPUT,
                EvalTestCaseParam.EXPECTED_OUTPUT));
    }

    @Override
    protected EvalResult runEvaluation(EvalTestCase testCase) {
        ReceiptData actual = testCase.actualOutputAs(ReceiptData.class);
        ReceiptExpectation expected = testCase.expectedOutputAs(ReceiptExpectation.class);

        if (actual == null) {
            return result(0.0, false, "No ReceiptData in actual output (extraction or parse failed)");
        }
        if (expected == null) {
            return result(0.0, false, "No expectation on the test case");
        }

        List<String> mismatches = new ArrayList<>();
        check("totalAmount", actual.totalAmount(), expected.totalAmount(), mismatches);
        check("tax8PercentAmount", actual.tax8PercentAmount(), expected.tax8PercentAmount(), mismatches);
        check("tax10PercentAmount", actual.tax10PercentAmount(), expected.tax10PercentAmount(), mismatches);

        if (mismatches.isEmpty()) {
            return result(1.0, true, "totalAmount and both tax buckets match the golden values exactly");
        }
        return result(0.0, false, "Mismatched: " + String.join("; ", mismatches));
    }

    private void check(String field, Integer actual, Integer expected, List<String> mismatches) {
        if (expected == null) {
            return;
        }
        if (actual == null) {
            mismatches.add(field + " missing (expected " + expected + ")");
        } else if (!expected.equals(actual)) {
            mismatches.add(field + " " + actual + " != " + expected);
        }
    }

    private EvalResult result(double score, boolean success, String reason) {
        return EvalResult.builder()
                .name(name())
                .score(score)
                .threshold(threshold())
                .reason(reason)
                .build();
    }
}
