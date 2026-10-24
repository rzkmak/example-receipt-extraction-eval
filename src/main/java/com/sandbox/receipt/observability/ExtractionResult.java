package com.sandbox.receipt.observability;

import com.sandbox.receipt.domain.ReceiptData;
import io.opentelemetry.api.trace.Span;

/**
 * Layer 1 — the "metadata wrapper alongside {@link ReceiptData}" the spec
 * asks for: the extracted data plus the OTel coordinates of the trace that
 * produced it.
 *
 * <p><b>Why it is {@link AutoCloseable}:</b> OpenTelemetry silently drops
 * attribute writes on an ended span, and evaluation scores only exist after
 * extraction finishes. The caller therefore controls the parent span's
 * lifetime:
 *
 * <ul>
 *   <li>the live controller closes it right away (normal request span),</li>
 *   <li>the JUnit harness closes it only <em>after</em> the Dokimos
 *       evaluators ran, so {@code SpanAnnotatingEvaluator} can attach
 *       {@code dokimos.eval.*} attributes in-process before export.</li>
 * </ul>
 */
public record ExtractionResult(
        ReceiptData data,
        String traceId,
        String spanId,
        ReceiptInvariants.InvariantReport invariants,
        String inputValue,
        String outputJson,
        Span span,
        boolean live) implements AutoCloseable {

    /** Ends the parent extraction span. Idempotent enough for our use. */
    public void endSpan() {
        span.end();
    }

    @Override
    public void close() {
        endSpan();
    }
}
