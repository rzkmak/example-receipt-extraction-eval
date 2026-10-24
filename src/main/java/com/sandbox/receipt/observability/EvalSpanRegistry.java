package com.sandbox.receipt.observability;

import io.opentelemetry.api.trace.Span;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 1 support — joins evaluation results back to the trace that produced
 * them.
 *
 * <p>{@code ReceiptExtractorService} registers each extraction span under the
 * testcase id it was invoked with; the test-scope
 * {@code SpanAnnotatingEvaluator} then looks the span up after a Dokimos
 * evaluator finishes and attaches the verdict to it.
 *
 * <p>Entries hold a reference to the (possibly still-open) span. The JUnit
 * parameterized harness keeps the span open until after evaluation, so
 * {@code dokimos.eval.*} attributes set here land on the exported span; the
 * experiment harness ends the span earlier, in which case attribute writes
 * are no-ops and the Phoenix annotation API carries the verdict instead.
 */
public final class EvalSpanRegistry {

    /** Trace coordinates of one extraction, registered under its testcase id. */
    public record Entry(String testcaseId, String traceId, String spanId, Span span) {
    }

    private final Map<String, Entry> byTestcaseId = new ConcurrentHashMap<>();

    public void register(String testcaseId, Span span) {
        if (testcaseId == null || testcaseId.isBlank()) {
            return;
        }
        var context = span.getSpanContext();
        byTestcaseId.put(testcaseId, new Entry(testcaseId, context.getTraceId(), context.getSpanId(), span));
    }

    public Entry get(String testcaseId) {
        return testcaseId == null ? null : byTestcaseId.get(testcaseId);
    }

    public void clear() {
        byTestcaseId.clear();
    }

    public int size() {
        return byTestcaseId.size();
    }
}
