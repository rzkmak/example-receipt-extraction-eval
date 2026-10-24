package com.sandbox.receipt.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

/**
 * Layer 5 phase bean — owns the {@code guardrail.pii_redact} span so the
 * extraction service stays pure business flow.
 *
 * <p>Two responsibilities, kept together deliberately because they share one
 * concern (what PII reaches where):
 *
 * <ul>
 *   <li>{@link #inspect}: pre-inference guardrail. On the text path the raw
 *       text is scrubbed here — meaning the service never even sees a
 *       scrubbed/raw distinction. On the image path pixels cannot be
 *       regex-scrubbed, so the span records
 *       {@code guardrail.pii.input_scrubbed=false} and the mitigation moves
 *       to the output side ({@link #scrubText}).</li>
 *   <li>{@link #scrubText}: output-side redaction for anything the model
 *       transcribed that would land in a span attribute (e.g. a malformed
 *       payload captured on the schema-failure path).</li>
 * </ul>
 */
public final class PiiGuardrail {

    private final PiiScrubber scrubber;
    private final Tracer tracer;

    public PiiGuardrail(PiiScrubber scrubber, OpenTelemetry openTelemetry) {
        this.scrubber = scrubber;
        this.tracer = openTelemetry.getTracer("com.sandbox.receipt");
    }

    /**
     * What the guardrail decided for one input: the text the model may see
     * (scrubbed on the text path) and the text safe for telemetry/audit
     * (the image marker on the image path).
     */
    public record Outcome(String textForModel, String textForAudit) {
    }

    public Outcome inspect(ExtractionInput input) {
        Span span = tracer.spanBuilder("guardrail.pii_redact").startSpan();
        try {
            if (input.isImage()) {
                // Pixels cannot be regex-scrubbed; see class javadoc.
                span.setAttribute("guardrail.pii.input_scrubbed", false);
                span.setAttribute("guardrail.pii.mode", "IMAGE_OUTPUT_REDACTION");
                return new Outcome(input.text(), "(image input, " + input.image().length + " bytes)");
            }
            PiiScrubber.Result pii = scrubber.scrub(input.text());
            span.setAttribute("guardrail.pii.input_scrubbed", true);
            span.setAttribute("guardrail.pii.detected", pii.detected());
            span.setAttribute("guardrail.pii.redacted_count", pii.redactedCount());
            span.setAttribute("guardrail.pii.entity_types", String.join(",", pii.entityTypes()));
            return new Outcome(pii.scrubbedText(), pii.scrubbedText());
        } finally {
            span.end();
        }
    }

    /** Output-side redaction — see class javadoc. */
    public String scrubText(String text) {
        return scrubber.scrub(text).scrubbedText();
    }
}
