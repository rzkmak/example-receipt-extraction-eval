package com.sandbox.receipt.observability;

import com.sandbox.receipt.domain.ReceiptData;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Layer 7 — deterministic, reference-free domain invariants plus input drift
 * characteristics. These need no golden dataset, so they run on every live
 * extraction ({@code POST /api/v1/receipts/extract}) where no ground truth
 * exists.
 *
 * <p>Checks (spec §7):
 * <ul>
 *   <li>{@code invariant.line_items_sum_matches_subtotal}: on Japanese
 *       receipts the printed prices are tax-inclusive, so
 *       sum(unitPrice × quantity) must equal {@code totalAmount} (the
 *       tax-inclusive total; {@code subtotalAmount} here is tax-<em>exclusive</em>).
 *       This is the same arithmetic the offline
 *       {@code LineItemSumConsistencyEvaluator} gates on — computed without
 *       an expectation.</li>
 *   <li>{@code invariant.tax_bounds_valid}: tax8 + tax10 within [0%, 10.5%]
 *       of {@code subtotalAmount}.</li>
 *   <li>{@code invariant.date_not_future}: transactionDate not in the future
 *       (1 day of clock skew tolerated).</li>
 * </ul>
 *
 * <p>Drift characteristics: {@code input.char_length} and
 * {@code input.cjk_char_ratio} (Kanji/Kana share of non-whitespace chars) —
 * a sudden drop means non-Japanese or garbled binary input reached the model.
 *
 * <p>Each failed invariant increments
 * {@code receipt.invariant.violation.total{type=...}} so aggregate quality is
 * visible in Prometheus even when nobody is watching traces.
 */
public final class ReceiptInvariants {

    /** Max tolerated embedded-tax share of the tax-exclusive subtotal. */
    private static final double MAX_TAX_RATE = 0.105;

    private final MeterRegistry meters;
    private final Clock clock;

    public ReceiptInvariants(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * Pure evaluation of all invariants + drift characteristics. Metric
     * recording happens in {@link #recordViolations}, so callers can evaluate
     * without touching the registry.
     */
    public InvariantReport evaluate(ReceiptData data, String rawInput) {
        boolean sumOk = checkLineItemsSum(data);
        boolean taxOk = checkTaxBounds(data);
        boolean dateOk = checkDateNotFuture(data);
        int charLength = rawInput == null ? 0 : rawInput.length();
        double cjkRatio = cjkCharRatio(rawInput);
        return new InvariantReport(sumOk, taxOk, dateOk, charLength, cjkRatio);
    }

    /** Increments the violation counter for every failed invariant. */
    public void recordViolations(InvariantReport report) {
        for (String type : report.violations()) {
            Counter.builder("receipt.invariant.violation.total")
                    .tag("type", type)
                    .register(meters)
                    .increment();
        }
    }

    /**
     * sum(unitPrice × quantity) vs totalAmount (fallback subtotalAmount when
     * the total is missing). Empty/missing items count as a violation when a
     * total exists — an extraction that dropped every line is the classic
     * silent failure.
     */
    private boolean checkLineItemsSum(ReceiptData data) {
        if (data == null) {
            return false;
        }
        Integer expected = data.totalAmount() != null ? data.totalAmount() : data.subtotalAmount();
        if (expected == null) {
            return false;
        }
        if (data.items() == null || data.items().isEmpty()) {
            return false;
        }
        int sum = 0;
        for (ReceiptData.ReceiptItem item : data.items()) {
            if (item.unitPrice() == null) {
                return false;
            }
            sum += item.unitPrice() * (item.quantity() == null ? 1 : item.quantity());
        }
        return sum == expected;
    }

    /** (tax8 + tax10) / subtotal within [0, 10.5%]. Skipped (passes) when amounts are absent. */
    private boolean checkTaxBounds(ReceiptData data) {
        if (data == null || data.subtotalAmount() == null
                || data.tax8PercentAmount() == null || data.tax10PercentAmount() == null) {
            return true; // not evaluable; the offline evaluators cover the strict case
        }
        if (data.subtotalAmount() <= 0) {
            return (data.tax8PercentAmount() + data.tax10PercentAmount()) == 0;
        }
        double rate = (data.tax8PercentAmount() + data.tax10PercentAmount())
                / (double) data.subtotalAmount();
        return rate >= 0.0 && rate <= MAX_TAX_RATE;
    }

    /** transactionDate parses as ISO date-time/date and is not in the future. */
    private boolean checkDateNotFuture(ReceiptData data) {
        if (data == null || data.transactionDate() == null || data.transactionDate().isBlank()) {
            return true; // absence is a schema concern, not a future-date violation
        }
        LocalDate date;
        try {
            date = LocalDateTime.parse(data.transactionDate()).toLocalDate();
        } catch (DateTimeParseException tryDateOnly) {
            try {
                date = LocalDate.parse(data.transactionDate().substring(0, 10));
            } catch (DateTimeParseException | StringIndexOutOfBoundsException unparseable) {
                return true; // malformed dates are a schema concern
            }
        }
        return !date.isAfter(LocalDate.now(clock).plusDays(1));
    }

    /** Ratio of Kanji/Hiragana/Katakana code points among non-whitespace characters. */
    static double cjkCharRatio(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0;
        }
        long cjk = 0;
        long total = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            total++;
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA) {
                cjk++;
            }
        }
        return total == 0 ? 0.0 : (double) cjk / total;
    }

    /**
     * Result of one invariant evaluation. Field names map 1:1 to span
     * attributes in {@code ReceiptExtractorService}.
     */
    public record InvariantReport(
            boolean lineItemsSumMatchesSubtotal,
            boolean taxBoundsValid,
            boolean dateNotFuture,
            int inputCharLength,
            double inputCjkCharRatio) {

        /** Span attribute names per invariant type, for compact reporting. */
        public List<String> violations() {
            List<String> failed = new ArrayList<>();
            if (!lineItemsSumMatchesSubtotal) {
                failed.add("line_item_sum");
            }
            if (!taxBoundsValid) {
                failed.add("tax_math");
            }
            if (!dateNotFuture) {
                failed.add("date_not_future");
            }
            return failed;
        }

        public boolean allPassed() {
            return violations().isEmpty();
        }
    }
}
