package com.sandbox.receipt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline wiring check: the Spring context starts (ChatClient.Builder bean,
 * OpenTelemetry SDK bean, controller) and the controller route exists — no
 * API key and no Phoenix required.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContextSmokeTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    org.springframework.boot.test.web.client.TestRestTemplate restTemplate;

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
}
