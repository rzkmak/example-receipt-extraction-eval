package com.sandbox.receipt;

import com.sandbox.receipt.domain.ExpenseCategory;
import com.sandbox.receipt.domain.ReceiptData;
import com.sandbox.receipt.observability.PiiScrubber;
import com.sandbox.receipt.service.ReceiptExtractorService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline verification of the V2 observability layers against a real OTel
 * SDK exporting into memory: PII guardrails (5), schema retry telemetry (3),
 * cost/caching economics (2), reference-free invariants (7), and lineage
 * tagging (6).
 */
class ObservabilityPipelineTest {

    private static final String GOOD_JSON = """
            {
              "merchantNameOriginal": "セブン-イレブン 目黒駅東口店",
              "merchantNameNormalized": "7-Eleven Meguro Station East Exit",
              "transactionDate": "2026-10-24T08:42:00",
              "currency": "JPY",
              "subtotalAmount": 327,
              "tax8PercentAmount": 25,
              "tax10PercentAmount": 0,
              "totalAmount": 352,
              "expenseCategory": "GROCERIES",
              "items": [
                {"originalName": "手巻おにぎり 熟成紅しゃけ",
                 "englishTranslation": "Hand-rolled Onigiri (Aged Red Salmon)",
                 "quantity": 1, "unitPrice": 349, "reducedTaxApplicable": true},
                {"originalName": "レジ袋",
                 "englishTranslation": "Plastic Shopping Bag",
                 "quantity": 1, "unitPrice": 3, "reducedTaxApplicable": false}
              ]
            }""";

    private static final String PII_INPUT = """
            セブン-イレブン 目黒駅東口店
            会員番号 12345678
            TEL: 03-1234-5678
            カード支払い 4111 1111 1111 1111
            合計 ¥352
            """;

    private TestObservability obs;

    @BeforeEach
    void setUp() {
        obs = new TestObservability();
    }

    /** Runs one extraction through the fixture, closing the span on return. */
    private static ReceiptData runExtraction(TestObservability obs, ScriptedChatModel stub) {
        try (var result = obs.serviceOver(stub, "glm-5.3-flash")
                .extractWithMetadata(PII_INPUT, "rcpt-001", "unit", false)) {
            return result.data();
        }
    }

    @AfterEach
    void tearDown() {
        obs.tracerProvider.shutdown();
    }

    /** Stub returning scripted completions and recording the last user text. */
    private static final class ScriptedChatModel implements ChatModel {
        final Deque<String> completions = new ArrayDeque<>();
        volatile Usage usage;
        volatile String lastUserText;

        ScriptedChatModel(String... completions) {
            for (String completion : completions) {
                this.completions.add(completion);
            }
        }

        ScriptedChatModel withUsage(int promptTokens, int completionTokens, int cachedTokens) {
            this.usage = new FixedUsage(promptTokens, completionTokens, cachedTokens);
            return this;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            for (var message : prompt.getInstructions()) {
                if (message instanceof org.springframework.ai.chat.messages.UserMessage user) {
                    lastUserText = user.getText();
                }
            }
            String completion = completions.isEmpty() ? "null" : completions.removeFirst();
            var builder = ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(completion))));
            if (usage != null) {
                builder.metadata(ChatResponseMetadata.builder().usage(usage).build());
            }
            return builder.build();
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().build();
        }
    }

    record FixedUsage(int promptTokens, int completionTokens, int cachedTokens) implements Usage {
        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Object getNativeUsage() {
            return Map.of("prompt_tokens_details", Map.of("cached_tokens", cachedTokens));
        }
    }

    private SpanData spanByName(String name) {
        return obs.exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span not found: " + name
                        + " (exported: " + obs.exporter.getFinishedSpanItems().stream()
                        .map(SpanData::getName).toList() + ")"));
    }

    // ── Layer 5 ─────────────────────────────────────────────────────────

    @Test
    void masksCardMemberIdAndPhoneBeforeInference() {
        ScriptedChatModel stub = new ScriptedChatModel(GOOD_JSON);
        runExtraction(obs, stub);

        // The model must never see the raw PII.
        String seen = stub.lastUserText;
        assertFalse(seen.contains("4111"), "card digits must be scrubbed");
        assertFalse(seen.contains("12345678"), "member id must be scrubbed");
        assertFalse(seen.contains("03-1234-5678"), "phone must be scrubbed");
        assertTrue(seen.contains(PiiScrubber.PLACEHOLDER_CARD));
        assertTrue(seen.contains(PiiScrubber.PLACEHOLDER_MEMBER_ID));
        assertTrue(seen.contains(PiiScrubber.PLACEHOLDER_PHONE));

        SpanData guardrail = spanByName("guardrail.pii_redact");
        assertEquals(true, guardrail.getAttributes().get(AttributeKey.booleanKey("guardrail.pii.detected")));
        assertEquals(3L, guardrail.getAttributes().get(AttributeKey.longKey("guardrail.pii.redacted_count")));
        String entityTypes = guardrail.getAttributes().get(AttributeKey.stringKey("guardrail.pii.entity_types"));
        assertTrue(entityTypes.contains("CREDIT_CARD"));
        assertTrue(entityTypes.contains("MEMBER_ID"));
        assertTrue(entityTypes.contains("PHONE"));
    }

    @Test
    void luhnInvalidDigitRunsAreLeftAlone() {
        PiiScrubber.Result result = new PiiScrubber(true).scrub("領収書番号 1234567890123 は16桁ではない");
        // 13 digits failing Luhn -> not masked as a card
        assertFalse(result.scrubbedText().contains(PiiScrubber.PLACEHOLDER_CARD));
        assertTrue(result.scrubbedText().contains("1234567890123"));
    }

    // ── Layer 3 ─────────────────────────────────────────────────────────

    @Test
    void retriesOnMalformedJsonAndRecordsSchemaTelemetry() {
        ScriptedChatModel stub = new ScriptedChatModel(
                "```json\n{\"merchantNameOriginal\": broken\n```",
                GOOD_JSON);
        var data = runExtraction(obs, stub);

        assertEquals(352, data.totalAmount());

        SpanData schemaSpan = spanByName("receipt.schema_validation");
        assertEquals(true, schemaSpan.getAttributes().get(AttributeKey.booleanKey("schema.validation.success")));
        assertEquals(1L, schemaSpan.getAttributes().get(AttributeKey.longKey("schema.retry.attempt_count")));
        assertEquals("com.sandbox.receipt.domain.ReceiptData",
                schemaSpan.getAttributes().get(AttributeKey.stringKey("schema.target_class")));
        // the corrective retry really went out to the model
        assertTrue(stub.lastUserText.contains("corrected JSON object"));
    }

    @Test
    void recordsSchemaFailureWhenAllAttemptsMalformed() {
        ScriptedChatModel stub = new ScriptedChatModel(
                "totally not json #1",
                "totally not json #2");

        var exception = assertThrows(RuntimeException.class, () ->
                runExtraction(obs, stub));
        assertNotNull(exception);

        SpanData schemaSpan = spanByName("receipt.schema_validation");
        assertEquals(false, schemaSpan.getAttributes().get(AttributeKey.booleanKey("schema.validation.success")));
        assertEquals("JsonParseException",
                schemaSpan.getAttributes().get(AttributeKey.stringKey("schema.error.type")));
        String raw = schemaSpan.getAttributes().get(AttributeKey.stringKey("schema.raw_malformed_payload"));
        assertNotNull(raw);
        assertTrue(raw.contains("not json #2"), "final malformed payload is captured");
        assertEquals(1L, schemaSpan.getAttributes().get(AttributeKey.longKey("schema.retry.attempt_count")));
    }

    // ── Layer 2 ─────────────────────────────────────────────────────────

    @Test
    void recordsCostCacheAndRoleTelemetryFromUsage() {
        ScriptedChatModel stub = new ScriptedChatModel(GOOD_JSON).withUsage(340, 80, 210);
        runExtraction(obs, stub);

        SpanData pipeline = spanByName("receipt.pipeline");
        var inputKey = AttributeKey.longKey("gen_ai.usage.input_tokens");
        var outputKey = AttributeKey.longKey("gen_ai.usage.output_tokens");
        var cacheKey = AttributeKey.longKey("gen_ai.usage.cache_read_input_tokens");
        var costKey = AttributeKey.doubleKey("gen_ai.usage.cost_usd");
        var roleKey = AttributeKey.stringKey("gen_ai.role");

        assertEquals(340L, pipeline.getAttributes().get(inputKey));
        assertEquals(80L, pipeline.getAttributes().get(outputKey));
        assertEquals(210L, pipeline.getAttributes().get(cacheKey));
        assertEquals("extractor", pipeline.getAttributes().get(roleKey));

        // glm-5.3-flash: (340-210)/1e6 * $0.15 + 210/1e6 * $0.075 + 80/1e6 * $0.60
        double expected = (340 - 210) / 1e6 * 0.15 + 210 / 1e6 * 0.075 + 80 / 1e6 * 0.60;
        assertEquals(expected, pipeline.getAttributes().get(costKey), 1e-12);

        var counter = obs.meters.get("llm.cost.usd.total")
                .tag("role", "extractor").tag("model", "glm-5.3-flash").counter();
        assertEquals(expected, counter.count(), 1e-12);

        var gauge = obs.meters.get("llm.prompt.cache.hit.ratio")
                .tag("model", "glm-5.3-flash").gauge();
        assertEquals(210.0 / 340.0, gauge.value(), 1e-9);
    }

    // ── Layer 7 ─────────────────────────────────────────────────────────

    @Test
    void flagsInvariantViolationsAndHighRiskOnLiveTraffic() {
        // Items sum to 352 but taxes are broken (25+0 vs a 327 subtotal is
        // fine; make tax8 huge so the bounds check fails) and use a future date.
        String badMathJson = GOOD_JSON
                .replace("\"tax8PercentAmount\": 25", "\"tax8PercentAmount\": 200");
        ScriptedChatModel stub = new ScriptedChatModel(badMathJson);

        try (var result = obs.serviceOver(stub, "glm-5.3-flash")
                .extractWithMetadata(PII_INPUT, "rcpt-001", "live", true)) {
            assertNotNull(result.data());
        }

        SpanData invariants = spanByName("schema.validate_and_invariants");
        assertEquals(false, invariants.getAttributes()
                .get(AttributeKey.booleanKey("invariant.tax_bounds_valid")));
        assertEquals(true, invariants.getAttributes()
                .get(AttributeKey.booleanKey("invariant.line_items_sum_matches_subtotal")));
        assertEquals(true, invariants.getAttributes()
                .get(AttributeKey.booleanKey("invariant.date_not_future")));

        assertTrue(invariants.getAttributes().get(AttributeKey.longKey("input.char_length")) > 0,
                "drift characteristic recorded");
        double cjk = invariants.getAttributes().get(AttributeKey.doubleKey("input.cjk_char_ratio"));
        assertTrue(cjk > 0.2 && cjk < 1.0, "CJK ratio should reflect Japanese input, got " + cjk);

        assertEquals(1L, obs.meters.get("receipt.invariant.violation.total")
                .tag("type", "tax_math").counter().count());

        SpanData pipeline = spanByName("receipt.pipeline");
        assertEquals("HIGH_RISK", pipeline.getAttributes()
                .get(AttributeKey.stringKey("sampling.priority")));
    }

    @Test
    void noHighRiskTagWhenInvariantsPass() {
        ScriptedChatModel stub = new ScriptedChatModel(GOOD_JSON);
        try (var result = obs.serviceOver(stub, "glm-5.3-flash")
                .extractWithMetadata(PII_INPUT, "rcpt-001", "live", true)) {
            assertNotNull(result.data());
        }
        SpanData pipeline = spanByName("receipt.pipeline");
        assertNullValue(pipeline.getAttributes().get(AttributeKey.stringKey("sampling.priority")));
    }

    private static void assertNullValue(String value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }

    // ── Layer 6 ─────────────────────────────────────────────────────────

    @Test
    void lineageAttributesAreOnEveryExportedSpan() {
        ScriptedChatModel stub = new ScriptedChatModel(GOOD_JSON);
        runExtraction(obs, stub);

        List<SpanData> spans = obs.exporter.getFinishedSpanItems();
        assertTrue(spans.size() >= 3, "expected pipeline + guardrail + schema spans");
        for (SpanData span : spans) {
            assertEquals("Receipt Extractor Baseline",
                    span.getAttributes().get(AttributeKey.stringKey("dokimos.experiment.name")));
            assertNotNull(span.getAttributes().get(AttributeKey.stringKey("dokimos.experiment.run_id")));
            assertEquals("bilingual-receipt-extractor",
                    span.getAttributes().get(AttributeKey.stringKey("prompt.template.id")));
        }
        // prompt sha256 is content-derived: it must change when the prompt does
        String pipelineSha = spanByName("receipt.pipeline").getAttributes()
                .get(AttributeKey.stringKey("prompt.template.sha256"));
        assertEquals(12, pipelineSha.length(), "first 12 hex chars of SHA-256");
    }

    @Test
    void promptSha256DetectsPromptEdits() {
        // The tax hint changes the prompt content, so the hash must move.
        TestObservability obsNoHint = new TestObservability();
        ScriptedChatModel stubWithHint = new ScriptedChatModel(GOOD_JSON);
        ScriptedChatModel stubNoHint = new ScriptedChatModel(GOOD_JSON);

        try (var ignored = obs.serviceOver(stubWithHint, true)
                .extractWithMetadata(PII_INPUT, "rcpt-001", "unit", false)) {
            // hash captured below
        }

        String withHint = spanByName("receipt.pipeline").getAttributes()
                .get(AttributeKey.stringKey("prompt.template.sha256"));
        obs.exporter.reset();

        try (var ignored = obs.serviceOver(stubNoHint, false)
                .extractWithMetadata(PII_INPUT, "rcpt-001", "unit", false)) {
            // hash captured below
        }

        String withoutHint = spanByName("receipt.pipeline").getAttributes()
                .get(AttributeKey.stringKey("prompt.template.sha256"));
        assertFalse(withHint.equals(withoutHint), "prompt edit must change prompt.template.sha256");
        obsNoHint.tracerProvider.shutdown();
    }

    // ── Layer 1 (registry side; Phoenix POST is test-scope, see
    //    SpanAnnotatingEvaluator + ReceiptEvaluationTest) ─────────────────

    @Test
    void registryMapsTestcaseToTraceCoordinates() {
        ScriptedChatModel stub = new ScriptedChatModel(GOOD_JSON);
        try (var result = obs.serviceOver(stub, "glm-5.3-flash")
                .extractWithMetadata(PII_INPUT, "rcpt-777", "unit", false)) {
            var entry = obs.registry.get("rcpt-777");
            assertNotNull(entry);
            assertEquals(result.traceId(), entry.traceId());
            assertEquals(result.spanId(), entry.spanId());
        }
    }

    @Test
    void goodExtractionSucceedsEndToEndWithAllAttributes() {
        ScriptedChatModel stub = new ScriptedChatModel(GOOD_JSON);
        var data = runExtraction(obs, stub);

        assertEquals(ExpenseCategory.GROCERIES, data.expenseCategory());
        SpanData pipeline = spanByName("receipt.pipeline");
        assertEquals(true, pipeline.getAttributes().get(AttributeKey.booleanKey("receipt.parse.success")));
        assertEquals(2L, pipeline.getAttributes().get(AttributeKey.longKey("receipt.extracted.item_count")));
        assertNotNull(pipeline.getAttributes().get(AttributeKey.stringKey("receipt.eval.testcase_id")));
    }

    // ── Phoenix Input/Output columns (OpenInference input/output.value) ──

    @Test
    void pipelineCarriesInputValueAndOutputValueForPhoenix() {
        ScriptedChatModel stub = new ScriptedChatModel(GOOD_JSON);
        runExtraction(obs, stub);

        SpanData pipeline = spanByName("receipt.pipeline");
        String input = pipeline.getAttributes().get(AttributeKey.stringKey("input.value"));
        String output = pipeline.getAttributes().get(AttributeKey.stringKey("output.value"));

        // input.value is the SCRUBBED text: placeholders in, raw PII out.
        assertNotNull(input);
        assertTrue(input.contains(PiiScrubber.PLACEHOLDER_CARD));
        assertFalse(input.contains("4111"), "raw card digits must not appear in input.value");
        assertTrue(input.contains("合計"), "input.value carries the receipt text");

        // output.value is the extracted JSON.
        assertNotNull(output);
        assertTrue(output.contains("\"totalAmount\":352"));
        assertTrue(output.contains("7-Eleven Meguro Station East Exit"));
    }

    @Test
    void failedExtractionRecordsErrorAsOutputValue() {
        ScriptedChatModel stub = new ScriptedChatModel("not json #1", "not json #2");
        assertThrows(RuntimeException.class, () ->
                runExtraction(obs, stub));

        String output = spanByName("receipt.pipeline").getAttributes()
                .get(AttributeKey.stringKey("output.value"));
        assertNotNull(output);
        assertTrue(output.startsWith("error: "), "failure path marks output.value");
    }
}
