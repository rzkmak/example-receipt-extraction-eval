package com.sandbox.receipt.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.receipt.domain.ReceiptData;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Owns everything the {@code receipt.pipeline} root span needs: creation,
 * lineage/modality attributes, MDC correlation, Phoenix Input/Output
 * attributes, success/failure bookkeeping, aggregate SLIs, and the layer-1
 * eval-registry hookup.
 *
 * <p>Exists so {@code ReceiptExtractorService} reads as pure business flow.
 * It is deliberately <b>not</b> an annotation/AOP mechanism: the root span's
 * lifetime is a business decision — it stays open after
 * {@link #success} returns, until the caller closes the
 * {@link ExtractionResult}, so the Dokimos evaluation harness can attach
 * {@code dokimos.eval.*} attributes before export. No declarative mechanism
 * can express "end this span when some external caller decides to".
 *
 * <p>Lifecycle: {@code start} → business calls ({@link #inputValue},
 * {@link #escalate}) → {@link #success} (span stays open, handle goes to the
 * caller) or {@link #failed} (span ends here). {@link #close} clears the MDC
 * either way; use it in try-with-resources.
 */
public final class PipelineObservation implements AutoCloseable {

    private static final ObjectMapper OUTPUT_JSON = new ObjectMapper();

    private final Span span;
    private final MeterRegistry meters;
    private final EvalSpanRegistry evalSpanRegistry;
    private final HighRiskAuditForwarder auditForwarder;
    private final String extractorModel;
    private final ExtractionInput input;
    private final String testcaseId;
    private final String difficulty;
    private final boolean live;
    private final long startNanos;

    PipelineObservation(Span span, MeterRegistry meters, EvalSpanRegistry evalSpanRegistry,
                        HighRiskAuditForwarder auditForwarder, String extractorModel,
                        ExtractionInput input, String testcaseId, String difficulty, boolean live) {
        this.span = span;
        this.meters = meters;
        this.evalSpanRegistry = evalSpanRegistry;
        this.auditForwarder = auditForwarder;
        this.extractorModel = extractorModel;
        this.input = input;
        this.testcaseId = testcaseId;
        this.difficulty = difficulty;
        this.live = live;
        this.startNanos = System.nanoTime();
    }

    /** The pipeline span — phases run inside its scope so they become children. */
    public Span span() {
        return span;
    }

    /**
     * OpenInference input content for Phoenix's Input column. Must always be
     * the PII-scrubbed text (or the image marker) — the raw input never
     * reaches telemetry.
     */
    public void inputValue(String scrubbedOrMarker) {
        span.setAttribute("input.value", scrubbedOrMarker);
    }

    /**
     * Business escalation on live traffic: tag the trace root (what Phoenix
     * filters on) and forward the already-scrubbed payload for async judge
     * auditing.
     */
    public void escalate(String inputForAudit, ReceiptData data,
                         List<String> violations) {
        span.setAttribute("sampling.priority", "HIGH_RISK");
        auditForwarder.forwardHighRisk(
                testcaseId == null || testcaseId.isBlank()
                        ? "live-" + span.getSpanContext().getSpanId()
                        : testcaseId,
                inputForAudit, data, violations);
    }

    /**
     * Successful extraction: records the output and aggregate SLIs, registers
     * the trace for the eval feedback loop, and returns the result with the
     * span still open — the caller ends it via {@link ExtractionResult#close()}.
     */
    public ExtractionResult success(ReceiptData data,
                                    ReceiptInvariants.InvariantReport report,
                                    long outputTokens, String inputValueText) {
        String outputJson = toJson(data);
        span.setAttribute("output.value", outputJson);
        span.setAttribute("receipt.extracted.item_count",
                data.items() == null ? 0 : data.items().size());
        span.setAttribute("receipt.extracted.category",
                data.expenseCategory() == null ? "UNKNOWN" : data.expenseCategory().name());
        span.setAttribute("receipt.parse.success", true);
        recordLatencyAndThroughput(outputTokens);
        evalSpanRegistry.register(testcaseId, span);

        var context = span.getSpanContext();
        return new ExtractionResult(data, context.getTraceId(), context.getSpanId(), report,
                inputValueText, outputJson, span, live);
    }

    /**
     * Failure path: there is no ExtractionResult to close, so the span must
     * be ended here or it is never exported.
     */
    public void failed(RuntimeException e) {
        span.setAttribute("receipt.parse.success", false);
        span.setAttribute("output.value",
                "error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        span.recordException(e);
        span.end();
    }

    @Override
    public void close() {
        MDC.remove("testcase_id");
    }

    private void recordLatencyAndThroughput(long outputTokens) {
        long durationNanos = System.nanoTime() - startNanos;
        Timer.builder("receipt.extraction.duration")
                .tag("model", extractorModel)
                .tag("difficulty", difficulty == null || difficulty.isBlank() ? "unknown" : difficulty)
                .description("End-to-end extraction pipeline latency")
                .register(meters)
                .record(durationNanos, TimeUnit.NANOSECONDS);

        double seconds = durationNanos / 1_000_000_000.0;
        if (outputTokens > 0 && seconds > 0) {
            DistributionSummary
                    .builder("receipt.tokens.throughput.tps")
                    .tag("model", extractorModel)
                    .description("Model output tokens per second of pipeline time")
                    .register(meters)
                    .record(outputTokens / seconds);
        }
    }

    private static String toJson(ReceiptData data) {
        try {
            return OUTPUT_JSON.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return String.valueOf(data);
        }
    }

    /** Stateless factory holding the collaborators one observation needs. */
    public static final class Factory {

        private final OpenTelemetry openTelemetry;
        private final MeterRegistry meters;
        private final EvalSpanRegistry evalSpanRegistry;
        private final HighRiskAuditForwarder auditForwarder;
        private final String extractorModel;

        public Factory(OpenTelemetry openTelemetry, MeterRegistry meters,
                       EvalSpanRegistry evalSpanRegistry, HighRiskAuditForwarder auditForwarder,
                       String extractorModel) {
            this.openTelemetry = openTelemetry;
            this.meters = meters;
            this.evalSpanRegistry = evalSpanRegistry;
            this.auditForwarder = auditForwarder;
            this.extractorModel = extractorModel;
        }

        /**
         * Starts one observation: creates the root span, stamps lineage and
         * modality, and puts the testcase id into the MDC (cleared by
         * {@link PipelineObservation#close}).
         */
        public PipelineObservation start(ExtractionInput input, String systemPrompt,
                                         String testcaseId, String difficulty, boolean live) {
            Span span = openTelemetry.getTracer("com.sandbox.receipt")
                    .spanBuilder("receipt.pipeline").startSpan();
            if (testcaseId != null && !testcaseId.isBlank()) {
                span.setAttribute("receipt.eval.testcase_id", testcaseId);
                // Layer 4: structured-log correlation. traceId/spanId reach
                // the MDC automatically via micrometer's Slf4JEventListener;
                // the testcase id is ours to add.
                MDC.put("testcase_id", testcaseId);
            }
            span.setAttribute("prompt.template.sha256", PromptHash.sha256Prefix(systemPrompt));
            span.setAttribute("receipt.input.live", live);
            span.setAttribute("receipt.input.modality", input.isImage() ? "image" : "text");
            // OpenInference span kind — what Phoenix's `kind` column reads;
            // without it the column shows "unknown".
            span.setAttribute("openinference.span.kind", "CHAIN");
            return new PipelineObservation(span, meters, evalSpanRegistry, auditForwarder,
                    extractorModel, input, testcaseId, difficulty, live);
        }
    }
}
