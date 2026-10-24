package com.sandbox.receipt.observability;

import com.sandbox.receipt.observability.ExtractionChatClient;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wiring for the V2 observability components. {@link SpanProcessor} beans are
 * picked up by Spring Boot's tracing auto-configuration
 * ({@code spanProcessors(ObjectProvider<SpanProcessor>)}) and registered on
 * the SDK {@code SdkTracerProvider} — that is the entire hookup for layer 6.
 */
@Configuration
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityConfig {

    @Bean
    public SpanProcessor experimentLineageSpanProcessor(ObservabilityProperties properties) {
        return new ExperimentLineageSpanProcessor(properties);
    }

    @Bean
    public OpenInferenceSpanKindFilter openInferenceSpanKindFilter() {
        // Tags Boot's HTTP server observations (CHAIN) and Spring AI's LLM /
        // ChatClient / tool observations, so Phoenix's kind column stops
        // showing "unknown" on framework spans.
        return new OpenInferenceSpanKindFilter();
    }

    @Bean
    public PiiScrubber piiScrubber(ObservabilityProperties properties) {
        return new PiiScrubber(properties.isPiiRedactionEnabled());
    }

    @Bean
    public PiiGuardrail piiGuardrail(PiiScrubber piiScrubber,
                                     io.opentelemetry.api.OpenTelemetry openTelemetry) {
        return new PiiGuardrail(piiScrubber, openTelemetry);
    }

    @Bean
    public LLMCostRecorder llmCostRecorder(ObservabilityProperties properties,
                                           io.micrometer.core.instrument.MeterRegistry meters) {
        return new LLMCostRecorder(properties, meters);
    }

    @Bean
    public ReceiptInvariants receiptInvariants(io.micrometer.core.instrument.MeterRegistry meters,
                                               Clock observabilityClock,
                                               io.opentelemetry.api.OpenTelemetry openTelemetry) {
        return new ReceiptInvariants(meters, observabilityClock, openTelemetry);
    }

    @Bean
    public Clock observabilityClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public EvalSpanRegistry evalSpanRegistry() {
        return new EvalSpanRegistry();
    }

    @Bean
    public PhoenixSpanAnnotationClient phoenixSpanAnnotationClient(ObservabilityProperties properties) {
        return new PhoenixSpanAnnotationClient(properties);
    }

    @Bean
    public HighRiskAuditForwarder highRiskAuditForwarder(ObservabilityProperties properties) {
        return new HighRiskAuditForwarder(properties);
    }

    @Bean
    public PipelineObservation.Factory pipelineObservations(io.opentelemetry.api.OpenTelemetry openTelemetry,
                                                            io.micrometer.core.instrument.MeterRegistry meters,
                                                            EvalSpanRegistry evalSpanRegistry,
                                                            HighRiskAuditForwarder auditForwarder,
                                                            @Value("${spring.ai.openai.chat.model:glm-5.3-flash}")
                                                            String extractorModel) {
        return new PipelineObservation.Factory(openTelemetry, meters, evalSpanRegistry, auditForwarder, extractorModel);
    }

    @Bean
    public ExtractionChatClient extractionChatClient(ChatClient.Builder chatClientBuilder,
                                                     LLMCostRecorder costRecorder,
                                                     @Value("${spring.ai.openai.chat.model:glm-5.3-flash}")
                                                     String extractorModel) {
        return new ExtractionChatClient(chatClientBuilder, costRecorder, extractorModel);
    }

    @Bean
    public SchemaValidatedExtractor schemaValidatedExtractor(ExtractionChatClient extractionChatClient,
                                                             PiiGuardrail piiGuardrail,
                                                             ObservabilityProperties properties,
                                                             io.opentelemetry.api.OpenTelemetry openTelemetry) {
        return new SchemaValidatedExtractor(extractionChatClient, piiGuardrail, properties, openTelemetry);
    }
}
