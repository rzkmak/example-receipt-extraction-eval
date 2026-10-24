package com.sandbox.receipt.observability;

import io.opentelemetry.sdk.trace.SpanProcessor;
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
    public PiiScrubber piiScrubber(ObservabilityProperties properties) {
        return new PiiScrubber(properties.isPiiRedactionEnabled());
    }

    @Bean
    public LLMCostRecorder llmCostRecorder(ObservabilityProperties properties,
                                           io.micrometer.core.instrument.MeterRegistry meters) {
        return new LLMCostRecorder(properties, meters);
    }

    @Bean
    public ReceiptInvariants receiptInvariants(io.micrometer.core.instrument.MeterRegistry meters,
                                               Clock observabilityClock) {
        return new ReceiptInvariants(meters, observabilityClock);
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
}
