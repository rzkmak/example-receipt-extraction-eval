package com.sandbox.receipt.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Layer 1 — pushes Dokimos evaluation verdicts into Arize Phoenix via
 * {@code POST /v1/span_annotations}, linking a judge score directly to the
 * extractor's span id. This is what makes "show all traces where
 * TranslationFidelityJudge < 0.8" a filter in the Phoenix UI instead of a
 * manual join across two tools.
 *
 * <p>Fire-and-forget by design: evaluation must never fail because the
 * observability backend is down. A single daemon thread drains the queue;
 * failures are logged at WARN and dropped.
 */
public final class PhoenixSpanAnnotationClient {

    private static final Logger log = LoggerFactory.getLogger(PhoenixSpanAnnotationClient.class);

    private final boolean enabled;
    private final String annotationsUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "phoenix-span-annotations");
        thread.setDaemon(true);
        return thread;
    });

    public PhoenixSpanAnnotationClient(ObservabilityProperties properties) {
        this.enabled = properties.getPhoenix().isAnnotationsEnabled();
        this.annotationsUrl = properties.getPhoenix().getAnnotationsUrl();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enqueues one annotation for the given span. Never throws.
     *
     * @param spanId      Phoenix span id (hex), or null/blank to skip
     * @param name        evaluator name, e.g. {@code TranslationFidelityJudge}
     * @param passed      whether the evaluator met its threshold
     * @param score       judge score 0..1
     * @param explanation judge reasoning (truncated to 2000 chars)
     */
    public void annotate(String spanId, String name, boolean passed, double score, String explanation) {
        if (!enabled || spanId == null || spanId.isBlank()) {
            return;
        }
        String payload = buildPayload(spanId, name, passed, score, explanation);
        if (payload == null) {
            return;
        }
        executor.submit(() -> {
            try {
                RestClient.create().post()
                        .uri(annotationsUrl + "/v1/span_annotations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("Phoenix span annotation for span {} failed (backend down? evaluation continues): {}",
                        spanId, e.getMessage());
            }
        });
    }

    /**
     * Builds the Phoenix span-annotation payload (spec §1). Kept as an
     * explicit JSON string rather than a request-object hierarchy so the
     * exact wire shape stays auditable against the spec example.
     */
    String buildPayload(String spanId, String name, boolean passed, double score, String explanation) {
        try {
            var items = objectMapper.createArrayNode();
            var item = items.addObject();
            item.put("span_id", spanId);
            item.put("name", name);
            item.put("annotator_kind", "LLM");
            var result = item.putObject("result");
            result.put("label", passed ? "PASS" : "FAIL");
            result.put("score", score);
            result.put("explanation", explanation == null ? "" : truncate(explanation, 2000));
            var envelope = objectMapper.createObjectNode();
            envelope.set("data", items);
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("Could not serialize Phoenix span annotation: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
