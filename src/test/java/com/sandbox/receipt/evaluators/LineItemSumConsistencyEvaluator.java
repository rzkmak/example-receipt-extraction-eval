package com.sandbox.receipt.evaluators;

import com.sandbox.receipt.domain.ReceiptData;
import dev.dokimos.core.BaseEvaluator;
import dev.dokimos.core.EvalResult;
import dev.dokimos.core.EvalTestCase;
import dev.dokimos.core.EvalTestCaseParam;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic tier 2 (threshold 1.0): the extraction must reconcile
 * arithmetically with itself — the classic failure detector for discounts
 * turned into positive line items (Bucket 2 of the golden matrix).
 *
 * <p>Two checks:
 * <ol>
 *   <li>sum(unitPrice × quantity) over {@code items[]} equals
 *       {@code totalAmount} (prices are tax-inclusive on Japanese receipts), and</li>
 *   <li>{@code subtotalAmount + tax8PercentAmount + tax10PercentAmount} equals
 *       {@code totalAmount}.</li>
 * </ol>
 */
public class LineItemSumConsistencyEvaluator extends BaseEvaluator {

    public LineItemSumConsistencyEvaluator() {
        super("LineItemSumConsistencyEvaluator", 1.0, List.of(EvalTestCaseParam.ACTUAL_OUTPUT));
    }

    @Override
    protected EvalResult runEvaluation(EvalTestCase testCase) {
        ReceiptData actual = testCase.actualOutputAs(ReceiptData.class);
        if (actual == null) {
            return result(0.0, false, "No ReceiptData in actual output (extraction or parse failed)");
        }
        if (actual.totalAmount() == null) {
            return result(0.0, false, "totalAmount missing from extraction");
        }

        List<String> problems = new ArrayList<>();

        Integer lineSum = null;
        if (actual.items() == null || actual.items().isEmpty()) {
            problems.add("items[] is empty");
        } else {
            int sum = 0;
            for (int i = 0; i < actual.items().size(); i++) {
                ReceiptData.ReceiptItem item = actual.items().get(i);
                if (item.unitPrice() == null) {
                    problems.add("item[" + i + "] (" + item.originalName() + ") has no unitPrice");
                    continue;
                }
                int qty = item.quantity() == null ? 1 : item.quantity();
                sum += item.unitPrice() * qty;
            }
            lineSum = sum;
            if (problems.isEmpty() && sum != actual.totalAmount()) {
                problems.add("sum(items) = " + sum + " but totalAmount = " + actual.totalAmount()
                        + " (discounts must be NEGATIVE line items)");
            }
        }

        if (actual.subtotalAmount() != null
                && actual.tax8PercentAmount() != null
                && actual.tax10PercentAmount() != null) {
            int taxSplit = actual.subtotalAmount()
                    + actual.tax8PercentAmount()
                    + actual.tax10PercentAmount();
            if (taxSplit != actual.totalAmount()) {
                problems.add("subtotal + tax8 + tax10 = " + taxSplit
                        + " but totalAmount = " + actual.totalAmount());
            }
        }

        if (problems.isEmpty()) {
            return result(1.0, true, "Line items sum to " + lineSum
                    + " and reconcile with the total and tax split");
        }
        return result(0.0, false, String.join("; ", problems));
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
