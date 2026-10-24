package com.sandbox.receipt;

import com.sandbox.receipt.service.ReceiptExtractorService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline verification of the image path ({@code /extract-image}): a
 * vision-capable model reads the photo directly. Asserts that the media
 * actually reaches the model, the modality telemetry lands, and — the part
 * the text path gets for free — that transcribed PII never reaches span
 * attributes on the failure path.
 */
class ReceiptImageExtractionTest {

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

    /** A "transcription" the vision model might emit, carrying PII from the photo. */
    private static final String MALFORMED_JSON_WITH_PII = """
            {"merchantNameOriginal": "セブン-イレブン",
             "cardNumber": "4111 1111 1111 1111",
             "memberId": "会員番号 12345678",
             "totalAmount": broken}""";

    private static final byte[] FAKE_PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    private TestObservability obs;

    @BeforeEach
    void setUp() {
        obs = new TestObservability();
    }

    @AfterEach
    void tearDown() {
        obs.tracerProvider.shutdown();
    }

    /** Stub that captures the prompt so tests can assert on the media part. */
    private static final class CapturingChatModel implements ChatModel {
        volatile Prompt lastPrompt;
        final String completion;

        CapturingChatModel(String completion) {
            this.completion = completion;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPrompt = prompt;
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(completion))))
                    .build();
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
    }

    private SpanData spanByName(String name) {
        return obs.exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span not found: " + name));
    }

    @Test
    void imageReachesTheModelAndPipelineExtracts() {
        CapturingChatModel stub = new CapturingChatModel(GOOD_JSON);

        try (var result = obs.serviceOver(stub, "gpt-4o-mini")
                .extractImageWithMetadata(FAKE_PNG, "image/png", "rcpt-img-001", "unit", false)) {
            assertEquals(352, result.data().totalAmount());
        }

        // The model call carried the image as media, mime type intact.
        List<org.springframework.ai.content.Media> media =
                ((UserMessage) stub.lastPrompt.getInstructions().stream()
                        .filter(m -> m instanceof UserMessage).findFirst().orElseThrow()).getMedia();
        assertEquals(1, media.size());
        assertEquals("image/png", media.getFirst().getMimeType().toString());
        Object data = media.getFirst().getData();
        int byteLength = data instanceof byte[] bytes ? bytes.length
                : data instanceof ByteArrayResource resource ? resource.getByteArray().length
                : -1;
        assertTrue(byteLength > 0, "media data present (got " + data.getClass().getSimpleName() + ")");

        // Modality telemetry: pipeline, schema and drift spans all know this
        // was an image extraction.
        SpanData pipeline = spanByName("receipt.pipeline");
        assertEquals("image", pipeline.getAttributes().get(AttributeKey.stringKey("receipt.input.modality")));
        assertEquals("image", spanByName("receipt.schema_validation").getAttributes()
                .get(AttributeKey.stringKey("schema.input.modality")));
        SpanData invariants = spanByName("schema.validate_and_invariants");
        assertEquals((long) FAKE_PNG.length, invariants.getAttributes().get(AttributeKey.longKey("input.image.bytes")));
        assertEquals("image/png", invariants.getAttributes().get(AttributeKey.stringKey("input.image.media_type")));

        // Guardrail states the truth: input was NOT scrubbed (pixels), the
        // mitigation is output-side.
        SpanData guardrail = spanByName("guardrail.pii_redact");
        assertEquals(false, guardrail.getAttributes().get(AttributeKey.booleanKey("guardrail.pii.input_scrubbed")));
        assertEquals("IMAGE_OUTPUT_REDACTION",
                guardrail.getAttributes().get(AttributeKey.stringKey("guardrail.pii.mode")));
    }

    @Test
    void transcribedPiiIsScrubbedBeforeLandingInSpanAttributes() {
        CapturingChatModel stub = new CapturingChatModel(MALFORMED_JSON_WITH_PII);

        assertThrows(RuntimeException.class, () -> obs.serviceOver(stub, "gpt-4o-mini")
                .extractImageWithMetadata(FAKE_PNG, "image/png", "rcpt-img-002", "unit", false));

        String payload = spanByName("receipt.schema_validation").getAttributes()
                .get(AttributeKey.stringKey("schema.raw_malformed_payload"));
        assertFalse(payload.contains("4111"), "card digits must not reach the span attribute");
        assertFalse(payload.contains("12345678"), "member id must not reach the span attribute");
        assertTrue(payload.contains("[REDACTED_CARD]"));
        assertTrue(payload.contains("[REDACTED_MEMBER_ID]"));
    }

    @Test
    void imagePathStillGatesOnInvariantsAndRegistersTrace() {
        // Same broken tax math as the text-path test: the invariant layer is
        // output-based, so it works identically for images.
        String badTax = GOOD_JSON.replace("\"tax8PercentAmount\": 25", "\"tax8PercentAmount\": 200");
        CapturingChatModel stub = new CapturingChatModel(badTax);

        try (var result = obs.serviceOver(stub, "gpt-4o-mini")
                .extractImageWithMetadata(FAKE_PNG, "image/jpeg", "rcpt-img-003", "unit", true)) {
            assertEquals(352, result.data().totalAmount());
        }

        SpanData pipeline = spanByName("receipt.pipeline");
        assertEquals("HIGH_RISK", pipeline.getAttributes().get(AttributeKey.stringKey("sampling.priority")));
        assertEquals(1L, obs.meters.get("receipt.invariant.violation.total")
                .tag("type", "tax_math").counter().count());

        // Layer 1 coordinates registered under the testcase id.
        var entry = obs.registry.get("rcpt-img-003");
        assertEquals(pipeline.getTraceId(), entry.traceId());
    }
}
