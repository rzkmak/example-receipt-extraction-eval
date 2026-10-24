package com.sandbox.receipt.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for the V2 observability layers (spec: "LLM Observability
 * Enhancement Specification V2 Addendum").
 *
 * <p>Bound from {@code receipt.observability.*}. Everything has a working
 * default so the app boots offline; Phoenix annotations and the Dokimos
 * high-risk audit forwarder simply log-and-drop when the backend is absent.
 */
@ConfigurationProperties(prefix = "receipt.observability")
public class ObservabilityProperties {

    /** Layer 6: groups every trace produced by one experiment campaign. */
    private String experimentName = "Receipt Extractor Baseline";

    /**
     * Layer 6: identifies one run of the suite. Blank means "generate per
     * process" ({@code run-<UTC timestamp>}), which is what you want when the
     * JUnit harness drives the app in-process.
     */
    private String runId = "";

    /** Layer 6: stable id of the extraction system prompt template. */
    private String promptTemplateId = "bilingual-receipt-extractor";

    /** Layer 6: human-readable prompt version, bumped on prompt edits. */
    private String promptTemplateVersion = "v1.3-split-tax-rules";

    /** Layer 3: total model calls allowed per extraction (1 call + retries). */
    private int schemaMaxAttempts = 2;

    /** Max characters of a malformed payload kept on the span for debugging. */
    private int malformedPayloadAttributeLimit = 4000;

    /** Layer 5: master switch for the pre-inference PII scrubber. */

    private boolean piiRedactionEnabled = true;

    private final Phoenix phoenix = new Phoenix();
    private final Audit audit = new Audit();

    /**
     * Layer 2: USD price per million tokens per model. The extractor's model
     * and the judge's model are priced independently, which is the whole point
     * (judge tokens cost ~15x extractor tokens).
     */
    private Map<String, ModelPrice> pricing = defaultPricing();

    public String getExperimentName() {
        return experimentName;
    }

    public void setExperimentName(String experimentName) {
        this.experimentName = experimentName;
    }

    public String getRunId() {
        if (runId == null || runId.isBlank()) {
            return "run-" + DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
                    .withZone(ZoneOffset.UTC).format(Instant.now());
        }
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getPromptTemplateId() {
        return promptTemplateId;
    }

    public void setPromptTemplateId(String promptTemplateId) {
        this.promptTemplateId = promptTemplateId;
    }

    public String getPromptTemplateVersion() {
        return promptTemplateVersion;
    }

    public void setPromptTemplateVersion(String promptTemplateVersion) {
        this.promptTemplateVersion = promptTemplateVersion;
    }

    public int getSchemaMaxAttempts() {
        return schemaMaxAttempts;
    }

    public void setSchemaMaxAttempts(int schemaMaxAttempts) {
        this.schemaMaxAttempts = Math.max(1, schemaMaxAttempts);
    }

    public int getMalformedPayloadAttributeLimit() {
        return malformedPayloadAttributeLimit;
    }

    public void setMalformedPayloadAttributeLimit(int malformedPayloadAttributeLimit) {
        this.malformedPayloadAttributeLimit = malformedPayloadAttributeLimit;
    }

    public boolean isPiiRedactionEnabled() {
        return piiRedactionEnabled;
    }

    public void setPiiRedactionEnabled(boolean piiRedactionEnabled) {
        this.piiRedactionEnabled = piiRedactionEnabled;
    }

    public Phoenix getPhoenix() {
        return phoenix;
    }

    public Audit getAudit() {
        return audit;
    }

    public Map<String, ModelPrice> getPricing() {
        return pricing;
    }

    public void setPricing(Map<String, ModelPrice> pricing) {
        this.pricing = pricing;
    }

    /** Layer 1: Phoenix span-annotation API settings. */
    public static class Phoenix {
        private boolean annotationsEnabled = true;
        private String annotationsUrl = "http://localhost:6006";

        public boolean isAnnotationsEnabled() {
            return annotationsEnabled;
        }

        public void setAnnotationsEnabled(boolean annotationsEnabled) {
            this.annotationsEnabled = annotationsEnabled;
        }

        public String getAnnotationsUrl() {
            return annotationsUrl;
        }

        public void setAnnotationsUrl(String annotationsUrl) {
            this.annotationsUrl = annotationsUrl;
        }
    }

    /** Layer 7: async Dokimos-server auditing for high-risk live extractions. */
    public static class Audit {
        private boolean enabled = false;
        private String url = "http://localhost:8080";
        private String endpoint = "/api/v1/high-risk-extractions";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
    }

    /** USD price per million tokens for one model. */
    public record ModelPrice(
            double inputUsdPerMillionTokens,
            double cachedInputUsdPerMillionTokens,
            double outputUsdPerMillionTokens) {
    }

    private static Map<String, ModelPrice> defaultPricing() {
        Map<String, ModelPrice> prices = new LinkedHashMap<>();
        prices.put("glm-5.3-flash", new ModelPrice(0.15, 0.075, 0.60));
        prices.put("gpt-4o", new ModelPrice(2.50, 1.25, 10.00));
        prices.put("gpt-4.1-mini", new ModelPrice(0.40, 0.10, 1.60));
        prices.put("gpt-4.1", new ModelPrice(2.00, 0.50, 8.00));
        return prices;
    }
}
