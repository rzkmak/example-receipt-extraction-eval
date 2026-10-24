package com.sandbox.receipt;

import com.sandbox.receipt.observability.EvalSpanRegistry;
import com.sandbox.receipt.observability.ExtractionChatClient;
import com.sandbox.receipt.observability.ExperimentLineageSpanProcessor;
import com.sandbox.receipt.observability.HighRiskAuditForwarder;
import com.sandbox.receipt.observability.LLMCostRecorder;
import com.sandbox.receipt.observability.ObservabilityProperties;
import com.sandbox.receipt.observability.PiiGuardrail;
import com.sandbox.receipt.observability.PiiScrubber;
import com.sandbox.receipt.observability.PipelineObservation;
import com.sandbox.receipt.observability.ReceiptInvariants;
import com.sandbox.receipt.observability.SchemaValidatedExtractor;
import com.sandbox.receipt.service.ExtractionPromptFactory;
import com.sandbox.receipt.service.ReceiptExtractorService;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Clock;

/**
 * Offline wiring for observability tests: a real OTel SDK exporting into an
 * {@link InMemorySpanExporter} (so tests can assert span attributes), a real
 * {@link SimpleMeterRegistry} (so tests can assert counters/gauges), and the
 * production pipeline components with all network effects disabled.
 */
final class TestObservability {

    final ObservabilityProperties properties = new ObservabilityProperties();
    final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(new ExperimentLineageSpanProcessor(properties))
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    final OpenTelemetry otel = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final PiiScrubber piiScrubber = new PiiScrubber(true);
    final PiiGuardrail piiGuardrail = new PiiGuardrail(piiScrubber, otel);
    final LLMCostRecorder costRecorder = new LLMCostRecorder(properties, meters);
    final ReceiptInvariants invariants = new ReceiptInvariants(meters, Clock.systemUTC(), otel);
    final EvalSpanRegistry registry = new EvalSpanRegistry();
    final HighRiskAuditForwarder auditForwarder = HighRiskAuditForwarder.disabled();
    final PipelineObservation.Factory observations =
            new PipelineObservation.Factory(otel, meters, registry, auditForwarder, "gpt-4o-mini");

    ReceiptExtractorService serviceOver(ChatModel model, String extractorModel) {
        return service(model, extractorModel, true);
    }

    ReceiptExtractorService serviceOver(ChatModel model, boolean taxHint) {
        return service(model, "gpt-4o-mini", taxHint);
    }

    private ReceiptExtractorService service(ChatModel model, String extractorModel, boolean taxHint) {
        ExtractionChatClient chatClient =
                new ExtractionChatClient(ChatClient.builder(model), costRecorder, extractorModel);
        SchemaValidatedExtractor schemaExtractor =
                new SchemaValidatedExtractor(chatClient, piiGuardrail, properties, otel);
        return new ReceiptExtractorService(new ExtractionPromptFactory(taxHint),
                observations, piiGuardrail, schemaExtractor, invariants);
    }

    void reset() {
        exporter.reset();
        meters.clear();
        registry.clear();
    }
}
