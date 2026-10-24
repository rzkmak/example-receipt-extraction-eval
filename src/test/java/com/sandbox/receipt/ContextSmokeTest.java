package com.sandbox.receipt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline wiring check: the Spring context starts (ChatClient.Builder bean,
 * OpenTelemetry SDK bean, controller) and the controller route exists — no
 * API key and no Phoenix required.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ContextSmokeTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
        assertNotNull(context.getBean(org.springframework.ai.chat.client.ChatClient.Builder.class),
                "Spring AI ChatClient.Builder bean");
        assertNotNull(context.getBean(io.opentelemetry.api.OpenTelemetry.class),
                "OpenTelemetry SDK bean (micrometer-tracing-bridge-otel + OTLP exporter)");
        assertNotNull(context.getBean(com.sandbox.receipt.service.ReceiptExtractorService.class));
        assertNotNull(context.getBean(com.sandbox.receipt.api.ReceiptController.class));
    }

    @Test
    void actuatorHealthEndpointIsUp() {
        var health = restTemplate.getForEntity("/actuator/health", String.class);
        assertTrue(health.getStatusCode().is2xxSuccessful());
        assertTrue(health.getBody().contains("UP"));
    }

    @Test
    void prometheusEndpointIsUpAndObservabilityBeansExist() {
        // Layer 4: the scrape endpoint is where the cost counters, duration
        // timer and invariant violations become aggregate SLIs. The meters
        // themselves register lazily on first use, so here we assert the
        // wiring: endpoint serving + recorder/invariants beans in the context.
        var prometheus = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertTrue(prometheus.getStatusCode().is2xxSuccessful());
        assertNotNull(prometheus.getBody());

        assertNotNull(context.getBean(com.sandbox.receipt.observability.LLMCostRecorder.class),
                "LLM cost recorder bean");
        assertNotNull(context.getBean(com.sandbox.receipt.observability.ReceiptInvariants.class),
                "receipt invariants bean");
        assertNotNull(context.getBean(com.sandbox.receipt.observability.PiiScrubber.class),
                "PII scrubber bean");
        assertNotNull(context.getBean(com.sandbox.receipt.observability.EvalSpanRegistry.class),
                "eval span registry bean");
        assertNotNull(context.getBean("experimentLineageSpanProcessor",
                        io.opentelemetry.sdk.trace.SpanProcessor.class),
                "experiment lineage span processor registered into the SDK tracer provider");
    }

    @Test
    void jsonStackIsJackson3() {
        // Jackson 3 (tools.jackson) is Boot 4's JSON stack: the auto-configured
        // mapper must be a JsonMapper. Legacy Jackson 2 (com.fasterxml.jackson)
        // rides on the classpath inside the OpenAI SDK, so a stray auto-config
        // of its ObjectMapper would be silent — assert there is none.
        assertNotNull(context.getBean(tools.jackson.databind.json.JsonMapper.class),
                "Boot 4 auto-configured Jackson 3 JsonMapper");
        assertNull(context.getBeanProvider(com.fasterxml.jackson.databind.ObjectMapper.class)
                        .getIfAvailable(),
                "no auto-configured Jackson 2 ObjectMapper");
    }
}
