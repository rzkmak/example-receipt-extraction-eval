package com.sandbox.receipt.observability;

import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Layer 7 — async LLM-as-a-Judge auditing of high-risk live extractions.
 *
 * <p>When a deterministic invariant fails on live traffic (no golden ground
 * truth available there), the extraction span is tagged
 * {@code sampling.priority=HIGH_RISK} and the (already PII-scrubbed) payload
 * is forwarded to the Dokimos server, where an asynchronous judge audit can
 * run without blocking the user request.
 *
 * <p>Disabled by default; enable with {@code DOKIMOS_AUDIT_ENABLED=true} plus
 * {@code DOKIMOS_SERVER_URL}. Fire-and-forget: observability must never take
 * the request path down with it.
 */
public final class HighRiskAuditForwarder {

    private static final Logger log = LoggerFactory.getLogger(HighRiskAuditForwarder.class);

    private final boolean enabled;
    private final String url;
    private final String endpoint;
    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dokimos-high-risk-audit");
        thread.setDaemon(true);
        return thread;
    });

    public HighRiskAuditForwarder(ObservabilityProperties properties) {
        this.enabled = properties.getAudit().isEnabled();
        this.url = properties.getAudit().getUrl();
        this.endpoint = properties.getAudit().getEndpoint();
    }

    public static HighRiskAuditForwarder disabled() {
        return new HighRiskAuditForwarder(new ObservabilityProperties());
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enqueues a high-risk extraction for async judge auditing. Never throws.
     *
     * @param testcaseId    testcase id when invoked from the harness, else a
     *                      synthetic live id
     * @param scrubbedInput PII-scrubbed receipt text (never the raw text)
     * @param data          the extracted structured data
     * @param violations    the failed invariant types, e.g. {@code [tax_math]}
     */
    public void forwardHighRisk(String testcaseId, String scrubbedInput,
                                com.sandbox.receipt.domain.ReceiptData data, java.util.List<String> violations) {
        if (!enabled || violations == null || violations.isEmpty()) {
            return;
        }
        String payload = buildPayload(testcaseId, scrubbedInput, data, violations);
        executor.submit(() -> {
            try {
                RestClient.create().post()
                        .uri(url + endpoint)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("Dokimos high-risk audit forward failed (evaluation continues): {}", e.getMessage());
            }
        });
    }

    String buildPayload(String testcaseId, String scrubbedInput,
                        com.sandbox.receipt.domain.ReceiptData data, java.util.List<String> violations) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("testcaseId", testcaseId);
            body.put("reason", "invariant_violation");
            body.put("violations", violations);
            body.put("input", scrubbedInput);
            body.put("extraction", data);
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("Could not serialize high-risk audit payload: {}", e.getMessage());
            return null;
        }
    }
}
