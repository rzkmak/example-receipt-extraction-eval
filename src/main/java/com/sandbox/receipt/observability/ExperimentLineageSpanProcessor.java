package com.sandbox.receipt.observability;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Layer 6 — prompt version & experiment lineage tagging.
 *
 * <p>A {@link SpanProcessor} that stamps every span created in this process
 * with the experiment name/run id and prompt template id/version, so 150
 * traces from 10 iterations of the suite group into comparable runs in the
 * Phoenix UI instead of streaming past as ungrouped noise.
 *
 * <p>Spring Boot's tracing auto-configuration collects every {@code SpanProcessor}
 * bean into the {@code SdkTracerProvider}, so registering this bean is all the
 * wiring that is needed.
 *
 * <p>The prompt SHA-256 is <em>not</em> set here: it is per-prompt-content and
 * computed where the prompt string is built ({@code ReceiptExtractorService}),
 * which is what catches uncommitted prompt edits during local testing.
 */
public final class ExperimentLineageSpanProcessor implements SpanProcessor {

    private final String experimentName;
    private final String runId;
    private final String promptTemplateId;
    private final String promptTemplateVersion;

    public ExperimentLineageSpanProcessor(ObservabilityProperties properties) {
        this.experimentName = properties.getExperimentName();
        this.runId = properties.getRunId();
        this.promptTemplateId = properties.getPromptTemplateId();
        this.promptTemplateVersion = properties.getPromptTemplateVersion();
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        span.setAttribute("dokimos.experiment.name", experimentName);
        span.setAttribute("dokimos.experiment.run_id", runId);
        span.setAttribute("prompt.template.id", promptTemplateId);
        span.setAttribute("prompt.template.version", promptTemplateVersion);
    }

    @Override
    public void onEnd(io.opentelemetry.sdk.trace.ReadableSpan span) {
        // nothing to do at span end; lineage is stamped at start
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}
