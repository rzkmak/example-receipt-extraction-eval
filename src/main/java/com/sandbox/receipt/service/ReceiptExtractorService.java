package com.sandbox.receipt.service;

import com.sandbox.receipt.domain.ReceiptData;
import com.sandbox.receipt.observability.EvalSpanRegistry;
import com.sandbox.receipt.observability.ExtractionResult;
import com.sandbox.receipt.observability.HighRiskAuditForwarder;
import com.sandbox.receipt.observability.LLMCostRecorder;
import com.sandbox.receipt.observability.ObservabilityProperties;
import com.sandbox.receipt.observability.PiiScrubber;
import com.sandbox.receipt.observability.ReceiptInvariants;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Extracts normalized {@link ReceiptData} from raw bilingual receipt text,
 * instrumented end-to-end per the V2 observability spec:
 *
 * <pre>
 * receipt.pipeline                        (root; lineage + testcase + prompt sha256)
 * ├── guardrail.pii_redact                (layer 5: scrubbed before any gen_ai attr exists)
 * ├── spring_ai.chat.*                    (Spring AI's own span, child via scope)
 * ├── receipt.schema_validation           (layer 3: raw string -> ReceiptData, with retry)
 * └── schema.validate_and_invariants      (layer 7: reference-free math invariants + drift)
 * </pre>
 *
 * <p>Layer 2 (cost/caching) rides on the usage metadata of each chat call;
 * layer 4 (metrics/logs) via Micrometer + MDC; layer 1 via the
 * {@link EvalSpanRegistry} and the returned {@link ExtractionResult}, whose
 * span stays open until the caller closes it so evaluation verdicts can be
 * attached in-process.
 */
@Service
public class ReceiptExtractorService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptExtractorService.class);

    private static final String BASE_SYSTEM_PROMPT = """
            You are an expert bilingual (Japanese/English) accounting data extractor.

            You receive raw Japanese receipt text (possibly noisy OCR) and must return ONE
            JSON object that strictly follows the provided JSON schema.

            Extraction rules:
            1. Every purchased line item must appear in `items[]`, with `quantity` and
               `unitPrice` taken from the receipt. Repeat lines count as quantity > 1.
            2. Discounts (値引, 割引, シール値引, セット割引) MUST be preserved as line items
               with a NEGATIVE `unitPrice`, never dropped and never turned into positive lines.
            3. Footer noise is NOT a line item: cash tendered (お預り), change given
               (お釣り), point balances, and store slogans must be ignored.
            4. `merchantNameNormalized` is the English name of the store chain
               (e.g. "セブン-イレブン" -> "7-Eleven"), keeping the branch description
               (e.g. "Meguro Station East Exit").
            5. `englishTranslation` must be natural, specific English for the product or
               brand — not transliteration and not a generic word like "Food".
            6. Prices are tax-inclusive yen. The bottom tax lines (内消費税等 8%対象 / 10%対象)
               give the embedded consumption tax per rate bucket:
               - `tax8PercentAmount`   <- the 8% 対象 amount (reduced rate: food, newspaper)
               - `tax10PercentAmount`  <- the 10% 対象 amount (standard rate)
               - `subtotalAmount`      <- totalAmount - tax8PercentAmount - tax10PercentAmount
               - `totalAmount`         <- 合計 (never お預り or お釣り)
            7. `reducedTaxApplicable` is true for items under the 8% reduced rate
               (ready-to-eat food, groceries), false for the 10% standard rate
               (plastic bags, alcohol, most non-food goods).
            8. `expenseCategory` must be one of the enum values. Judge it from the
               merchant and the majority of items.
            """;

    /**
     * The hint removed in Milestone 3 Experiment A (prompt regression).
     * {@code receipt.extraction.tax-hint=false} turns it off.
     */
    private static final String TAX_HINT = """
            9. On Japanese receipts, items marked with a leading `※` or `*` are subject to
               the 8% reduced consumption tax, so their `reducedTaxApplicable` is true.
            """;

    private final ChatClient chatClient;
    private final Tracer tracer;
    private final MeterRegistry meters;
    private final ObservabilityProperties properties;
    private final PiiScrubber piiScrubber;
    private final LLMCostRecorder costRecorder;
    private final ReceiptInvariants invariants;
    private final EvalSpanRegistry evalSpanRegistry;
    private final HighRiskAuditForwarder auditForwarder;
    private final boolean taxHintEnabled;
    private final String extractorModel;

    public ReceiptExtractorService(ChatClient.Builder chatClientBuilder,
                                   OpenTelemetry openTelemetry,
                                   MeterRegistry meters,
                                   ObservabilityProperties properties,
                                   PiiScrubber piiScrubber,
                                   LLMCostRecorder costRecorder,
                                   ReceiptInvariants invariants,
                                   EvalSpanRegistry evalSpanRegistry,
                                   HighRiskAuditForwarder auditForwarder,
                                   @Value("${receipt.extraction.tax-hint:true}") boolean taxHintEnabled,
                                   @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String extractorModel) {
        this.chatClient = chatClientBuilder.build();
        this.tracer = openTelemetry.getTracer("com.sandbox.receipt");
        this.meters = meters;
        this.properties = properties;
        this.piiScrubber = piiScrubber;
        this.costRecorder = costRecorder;
        this.invariants = invariants;
        this.evalSpanRegistry = evalSpanRegistry;
        this.auditForwarder = auditForwarder;
        this.taxHintEnabled = taxHintEnabled;
        this.extractorModel = extractorModel;
    }

    /** Convenience for callers that only need the data (span closed on return). */
    public ReceiptData extract(String rawReceiptText, String testcaseId) {
        try (ExtractionResult result = extractWithMetadata(rawReceiptText, testcaseId, "unknown", false)) {
            return result.data();
        }
    }

    /**
     * One extraction's input: either raw receipt text or a receipt image for
     * a vision-capable model (never both). Kept as a package record so the
     * pipeline stays single-tracked — every layer below behaves identically
     * for both modalities except where explicitly branched.
     */
    record ExtractionInput(String text, byte[] image, String imageMediaType) {

        boolean isImage() {
            return image != null;
        }
    }

    /**
     * Full-pipeline extraction with trace metadata. The parent span stays
     * open until {@link ExtractionResult#close()} — close it immediately
     * (live API) or after evaluation (harness) so {@code dokimos.eval.*}
     * attributes can be attached in-process.
     *
     * @param difficulty eval difficulty tag for the duration metric
     *                   ({@code "unknown"} outside the harness)
     * @param live       true for API traffic: enables the layer-7 HIGH_RISK
     *                   tagging and async judge audit on invariant failures
     */
    public ExtractionResult extractWithMetadata(String rawReceiptText, String testcaseId,
                                                String difficulty, boolean live) {
        return runPipeline(new ExtractionInput(rawReceiptText, null, null), testcaseId, difficulty, live);
    }

    /**
     * Image-path extraction: the vision-capable extractor model reads the
     * receipt photo directly (no OCR pre-pass). Same pipeline as the text
     * path, with two deliberate differences:
     *
     * <ul>
     *   <li><b>Input cannot be PII-scrubbed</b> — a regex guardrail has no
     *       purchase on pixels. The guardrail span therefore records
     *       {@code guardrail.pii.input_scrubbed=false} and the mitigation
     *       moves to the output side: anything the model transcribed that
     *       would land in a span attribute (e.g. a malformed payload on the
     *       failure path) is scrubbed first. Deployments that want zero PII
     *       in telemetry should also disable completion capture
     *       ({@code CHAT_COMPLETION_TELEMETRY=false}).</li>
     *   <li><b>Drift characteristics differ</b> — image byte size and media
     *       type replace char length / CJK ratio.</li>
     * </ul>
     */
    public ExtractionResult extractImageWithMetadata(byte[] imageBytes, String mediaType, String testcaseId,
                                                     String difficulty, boolean live) {
        return runPipeline(new ExtractionInput(null, imageBytes, mediaType), testcaseId, difficulty, live);
    }

    private ExtractionResult runPipeline(ExtractionInput input, String testcaseId,
                                         String difficulty, boolean live) {
        long startNanos = System.nanoTime();
        Span span = tracer.spanBuilder("receipt.pipeline").startSpan();

        if (testcaseId != null && !testcaseId.isBlank()) {
            span.setAttribute("receipt.eval.testcase_id", testcaseId);
        }
        String prompt = systemPrompt();
        span.setAttribute("prompt.template.sha256", sha256Prefix(prompt));
        span.setAttribute("receipt.input.live", live);
        span.setAttribute("receipt.input.modality", input.isImage() ? "image" : "text");
        // OpenInference span kind — what Phoenix's `kind` column reads;
        // without it the column shows "unknown".
        span.setAttribute("openinference.span.kind", "CHAIN");

        // Layer 4: structured-log correlation. traceId/spanId also reach MDC
        // automatically via micrometer's Slf4JEventListener; the testcase id
        // is ours to add.
        if (testcaseId != null && !testcaseId.isBlank()) {
            MDC.put("testcase_id", testcaseId);
        }

        try (var ignored = span.makeCurrent()) {
            // ── Layer 5: guardrail BEFORE any gen_ai.prompt attribute exists ──
            Span guardrailSpan = tracer.spanBuilder("guardrail.pii_redact").startSpan();
            String textForAudit;
            ExtractionInput modelInput = input;
            if (input.isImage()) {
                // Pixels cannot be regex-scrubbed; see extractImageWithMetadata.
                guardrailSpan.setAttribute("guardrail.pii.input_scrubbed", false);
                guardrailSpan.setAttribute("guardrail.pii.mode", "IMAGE_OUTPUT_REDACTION");
                textForAudit = "(image input, " + input.image().length + " bytes)";
            } else {
                PiiScrubber.Result pii = piiScrubber.scrub(input.text());
                guardrailSpan.setAttribute("guardrail.pii.input_scrubbed", true);
                guardrailSpan.setAttribute("guardrail.pii.detected", pii.detected());
                guardrailSpan.setAttribute("guardrail.pii.redacted_count", pii.redactedCount());
                guardrailSpan.setAttribute("guardrail.pii.entity_types", String.join(",", pii.entityTypes()));
                textForAudit = pii.scrubbedText();
                // Only the scrubbed text may reach the model — drift stats
                // below still measure the raw input.
                modelInput = new ExtractionInput(pii.scrubbedText(), null, null);
            }
            guardrailSpan.end();

            // OpenInference convention: Phoenix's Input/Output columns (and
            // any evals on the trace) read input.value / output.value. The
            // input here is always the PII-scrubbed text — the raw text
            // never reaches telemetry.
            span.setAttribute("input.value", textForAudit);

            // ── Layers 2+3: chat call + schema deserialization with retry ──
            CallOutcome outcome = callAndParse(span, prompt, modelInput);
            ReceiptData data = outcome.data();
            span.setAttribute("output.value", toJson(data));

            // ── Layer 7: reference-free invariants + drift ──
            ReceiptInvariants.InvariantReport report = invariants.evaluate(data, input.isImage() ? "" : input.text());
            Span invariantSpan = tracer.spanBuilder("schema.validate_and_invariants").startSpan();
            invariantSpan.setAttribute("invariant.line_items_sum_matches_subtotal", report.lineItemsSumMatchesSubtotal());
            invariantSpan.setAttribute("invariant.tax_bounds_valid", report.taxBoundsValid());
            invariantSpan.setAttribute("invariant.date_not_future", report.dateNotFuture());
            if (input.isImage()) {
                invariantSpan.setAttribute("input.image.bytes", input.image().length);
                invariantSpan.setAttribute("input.image.media_type",
                        input.imageMediaType() == null ? "unknown" : input.imageMediaType());
            } else {
                invariantSpan.setAttribute("input.char_length", report.inputCharLength());
                invariantSpan.setAttribute("input.cjk_char_ratio", report.inputCjkCharRatio());
            }
            if (live && !report.allPassed()) {
                // Tag both the invariant span and the pipeline root: the root
                // is what gets filtered in the Phoenix UI.
                invariantSpan.setAttribute("sampling.priority", "HIGH_RISK");
                span.setAttribute("sampling.priority", "HIGH_RISK");
                auditForwarder.forwardHighRisk(
                        testcaseId == null || testcaseId.isBlank() ? "live-" + span.getSpanContext().getSpanId() : testcaseId,
                        textForAudit, data, report.violations());
            }
            invariantSpan.end();
            invariants.recordViolations(report);

            span.setAttribute("receipt.extracted.item_count",
                    data.items() == null ? 0 : data.items().size());
            span.setAttribute("receipt.extracted.category",
                    data.expenseCategory() == null ? "UNKNOWN" : data.expenseCategory().name());
            span.setAttribute("receipt.parse.success", true);

            // ── Layer 4: aggregate SLIs ──
            recordLatencyAndThroughput(startNanos, span, difficulty, outcome.outputTokens());

            // ── Layer 1: register for the eval feedback loop ──
            evalSpanRegistry.register(testcaseId, span);

            var context = span.getSpanContext();
            return new ExtractionResult(data, context.getTraceId(), context.getSpanId(), report,
                    textForAudit, toJson(data), span, live);
        } catch (RuntimeException e) {
            span.setAttribute("receipt.parse.success", false);
            span.setAttribute("output.value",
                    "error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            span.recordException(e);
            // Failure path: there is no ExtractionResult to close, so end
            // the span here or it is never exported (the exception unwinds
            // past every close()).
            span.end();
            throw e;
        } finally {
            MDC.remove("testcase_id");
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OUTPUT_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** The extracted data as compact JSON for the output.value attribute. */
    private static String toJson(ReceiptData data) {
        try {
            return OUTPUT_JSON.writeValueAsString(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(data);
        }
    }

    /** One extraction's data plus the token count of its final model reply. */
    record CallOutcome(ReceiptData data, long outputTokens) {
    }

    /**
     * One model call + explicit deserialization, retried with a
     * self-correction instruction when the model returns something that does
     * not parse into {@link ReceiptData}. All layer-3 attributes land on the
     * {@code receipt.schema_validation} span, layer-2 usage/cost attributes
     * on the parent pipeline span.
     */
    private CallOutcome callAndParse(Span parentSpan, String systemPrompt, ExtractionInput input) {
        BeanOutputConverter<ReceiptData> converter = new BeanOutputConverter<>(ReceiptData.class);
        String format = converter.getFormat();

        Span schemaSpan = tracer.spanBuilder("receipt.schema_validation").startSpan();
        schemaSpan.setAttribute("schema.target_class", ReceiptData.class.getName());
        schemaSpan.setAttribute("schema.input.modality", input.isImage() ? "image" : "text");
        int maxAttempts = properties.getSchemaMaxAttempts();
        String lastRawOutput = null;
        String lastErrorType = null;
        long lastOutputTokens = 0;

        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                schemaSpan.setAttribute("schema.retry.attempt_count", attempt - 1);
                String userText = buildUserText(
                        input.isImage() ? IMAGE_INSTRUCTION : input.text(),
                        format, attempt, lastRawOutput, lastErrorType);

                ChatResponse response;
                if (input.isImage()) {
                    response = chatClient.prompt()
                            .system(systemPrompt)
                            .user(u -> u.text(userText)
                                    .media(toMimeType(input.imageMediaType()),
                                            new ByteArrayResource(input.image())))
                            .call()
                            .chatResponse();
                } else {
                    response = chatClient.prompt()
                            .system(systemPrompt)
                            .user(userText)
                            .call()
                            .chatResponse();
                }
                String rawOutput = response == null || response.getResult() == null
                        ? null
                        : response.getResult().getOutput().getText();
                lastRawOutput = rawOutput;

                // ── Layer 2: usage -> cost + cache telemetry on the parent ──
                Usage usage = response != null && response.getMetadata() != null
                        ? response.getMetadata().getUsage()
                        : null;
                costRecorder.record("extractor", extractorModel, usage, parentSpan);
                if (usage != null && usage.getCompletionTokens() != null) {
                    lastOutputTokens = usage.getCompletionTokens();
                }

                try {
                    ReceiptData data = converter.convert(rawOutput);
                    validateRequiredFields(data);
                    schemaSpan.setAttribute("schema.validation.success", true);
                    return new CallOutcome(data, lastOutputTokens);
                } catch (RuntimeException parseError) {
                    lastErrorType = classifySchemaError(parseError);
                    if (attempt == maxAttempts) {
                        schemaSpan.setAttribute("schema.validation.success", false);
                        schemaSpan.setAttribute("schema.error.type", lastErrorType);
                        // On the image path the model may have transcribed
                        // card numbers / member ids from the photo — scrub
                        // before the payload lands in a span attribute.
                        String payload = truncate(rawOutput == null ? "null" : rawOutput,
                                properties.getMalformedPayloadAttributeLimit());
                        if (input.isImage()) {
                            payload = piiScrubber.scrub(payload).scrubbedText();
                        }
                        schemaSpan.setAttribute("schema.raw_malformed_payload", payload);
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "LLM output did not deserialize to ReceiptData after "
                                        + maxAttempts + " attempt(s): " + lastErrorType, parseError);
                    }
                    log.warn("Schema parse attempt {} failed ({}); retrying with self-correction",
                            attempt, lastErrorType);
                }
            }
            throw new IllegalStateException("unreachable");
        } finally {
            schemaSpan.end();
        }
    }

    /** Text part of the vision user message; the image rides alongside it as media. */
    private static final String IMAGE_INSTRUCTION = """
            Extract the receipt data from the attached receipt image.
            Read the printed Japanese text directly from the image.""";

    private static MimeType toMimeType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return MimeTypeUtils.IMAGE_PNG;
        }
        try {
            return MimeType.valueOf(mediaType);
        } catch (InvalidMimeTypeException e) {
            return MimeTypeUtils.IMAGE_PNG;
        }
    }

    private String buildUserText(String scrubbedInput, String format, int attempt,
                                 String lastRawOutput, String lastErrorType) {
        if (attempt == 1) {
            return scrubbedInput + "\n\n" + format;
        }
        return "Your previous reply was not valid JSON for the requested schema.\n"
                + "Previous reply (invalid):\n" + truncate(String.valueOf(lastRawOutput), 2000) + "\n"
                + "Problem: " + lastErrorType + "\n\n"
                + "Return ONE corrected JSON object that follows the schema, with no commentary.\n\n"
                + scrubbedInput + "\n\n" + format;
    }

    /** The spec's {@code MissingRequiredField} signal for fields the domain cannot do without. */
    private void validateRequiredFields(ReceiptData data) {
        if (data == null) {
            throw new MissingRequiredFieldException("conversion produced no ReceiptData");
        }
        List<String> missing = new ArrayList<>();
        if (data.totalAmount() == null) {
            missing.add("totalAmount");
        }
        if (data.items() == null) {
            missing.add("items");
        }
        if (!missing.isEmpty()) {
            throw new MissingRequiredFieldException("missing required fields: " + String.join(", ", missing));
        }
    }

    static String classifySchemaError(Throwable error) {
        if (error == null) {
            return "Unknown";
        }
        if (error instanceof MissingRequiredFieldException) {
            return "MissingRequiredField";
        }
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof com.fasterxml.jackson.core.JsonParseException) {
                return "JsonParseException";
            }
            if (cause instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException) {
                return "MismatchedInputException";
            }
            cause = cause.getCause();
        }
        return error.getClass().getSimpleName();
    }

    private static final class MissingRequiredFieldException extends RuntimeException {
        MissingRequiredFieldException(String message) {
            super(message);
        }
    }

    private void recordLatencyAndThroughput(long startNanos, Span span, String difficulty, long outputTokens) {
        long durationNanos = System.nanoTime() - startNanos;
        Timer.builder("receipt.extraction.duration")
                .tag("model", extractorModel)
                .tag("difficulty", difficulty == null || difficulty.isBlank() ? "unknown" : difficulty)
                .description("End-to-end extraction pipeline latency")
                .register(meters)
                .record(durationNanos, TimeUnit.NANOSECONDS);

        double seconds = durationNanos / 1_000_000_000.0;
        if (outputTokens > 0 && seconds > 0) {
            DistributionSummary
                    .builder("receipt.tokens.throughput.tps")
                    .tag("model", extractorModel)
                    .description("Model output tokens per second of pipeline time")
                    .register(meters)
                    .record(outputTokens / seconds);
        }
    }

    private String systemPrompt() {
        return taxHintEnabled ? BASE_SYSTEM_PROMPT + TAX_HINT : BASE_SYSTEM_PROMPT;
    }

    /** Layer 6: first 12 hex chars of the SHA-256 of the effective system prompt. */
    static String sha256Prefix(String prompt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(prompt.getBytes(StandardCharsets.UTF_8)), 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
