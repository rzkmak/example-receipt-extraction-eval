package com.sandbox.receipt.evaluators;

import com.sandbox.receipt.observability.EvalSpanRegistry;
import com.sandbox.receipt.observability.PhoenixSpanAnnotationClient;
import dev.dokimos.core.EvalResult;
import dev.dokimos.core.EvalTestCase;
import dev.dokimos.core.Evaluator;
import io.opentelemetry.api.trace.Span;

/**
 * Layer 1 — wraps a Dokimos {@link Evaluator} so its verdict is attached to
 * the trace that produced the extraction:
 *
 * <ol>
 *   <li><b>In-process span attributes</b> — {@code dokimos.eval.<name>.score},
 *       {@code .passed}, {@code .reason} on the {@code receipt.pipeline} span,
 *       when it is still open (the JUnit parameterized harness keeps it open
 *       until after evaluation; attribute writes on an ended span are no-ops).</li>
 *   <li><b>Phoenix span annotations</b> — an async
 *       {@code POST /v1/span_annotations} linking the verdict to the span id,
 *       which works regardless of span state and is what makes
 *       "filter traces where TranslationFidelityJudge &lt; 0.8" possible.</li>
 * </ol>
 */
public final class SpanAnnotatingEvaluator implements Evaluator {

    private final Evaluator delegate;
    private final EvalSpanRegistry registry;
    private final PhoenixSpanAnnotationClient phoenix;

    public SpanAnnotatingEvaluator(Evaluator delegate, EvalSpanRegistry registry,
                                   PhoenixSpanAnnotationClient phoenix) {
        this.delegate = delegate;
        this.registry = registry;
        this.phoenix = phoenix;
    }

    @Override
    public EvalResult evaluate(EvalTestCase testCase) {
        EvalResult result = delegate.evaluate(testCase);

        String testcaseId = String.valueOf(testCase.metadata().getOrDefault("id", "unknown"));
        EvalSpanRegistry.Entry entry = registry.get(testcaseId);
        if (entry != null) {
            attachAttributes(entry.span(), result);
            phoenix.annotate(entry.spanId(), result.name(), result.success(), result.score(), result.reason());
        }
        return result;
    }

    private void attachAttributes(Span span, EvalResult result) {
        String prefix = "dokimos.eval." + result.name();
        span.setAttribute(prefix + ".score", result.score());
        span.setAttribute(prefix + ".passed", result.success());
        if (result.reason() != null) {
            span.setAttribute(prefix + ".reason",
                    result.reason().length() <= 1000 ? result.reason() : result.reason().substring(0, 1000));
        }
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public double threshold() {
        return delegate.threshold();
    }
}
