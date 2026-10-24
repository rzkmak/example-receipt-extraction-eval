package com.sandbox.receipt;

import com.sandbox.receipt.domain.ExpenseCategory;
import com.sandbox.receipt.domain.ReceiptData;
import com.sandbox.receipt.service.ReceiptExtractorService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline extraction-path test: a stub {@link ChatModel} returns a canned
 * JSON completion, so we can verify prompt plumbing, structured-output
 * parsing ({@code entity(ReceiptData.class)}) and the tax-hint toggle without
 * an API key. The real-model trace check is {@code ReceiptEvaluationTest}.
 */
class StubbedChatModelExtractionTest {

    private static final String CANNED_COMPLETION = """
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
                 "quantity": 1, "unitPrice": 189, "reducedTaxApplicable": true},
                {"originalName": "レジ袋",
                 "englishTranslation": "Plastic Shopping Bag",
                 "quantity": 1, "unitPrice": 3, "reducedTaxApplicable": false}
              ]
            }""";

    /** Stub that echoes a fixed completion and records the prompt it saw. */
    private static final class RecordingStubChatModel implements ChatModel {
        volatile String lastSystemText;

        @Override
        public ChatResponse call(Prompt prompt) {
            for (Message message : prompt.getInstructions()) {
                if (message instanceof SystemMessage system) {
                    lastSystemText = system.getText();
                }
            }
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(CANNED_COMPLETION))))
                    .build();
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
    }

    private static ReceiptExtractorService serviceOver(RecordingStubChatModel stub, boolean taxHint) {
        TestObservability obs = new TestObservability();
        return new ReceiptExtractorService(
                ChatClient.builder(stub), obs.otel, obs.meters, obs.properties, obs.piiScrubber,
                obs.costRecorder, obs.invariants, obs.registry, obs.auditForwarder, taxHint, "gpt-4o-mini");
    }

    @Test
    void extractsStructuredDataFromCannedCompletion() {
        ReceiptData data = serviceOver(new RecordingStubChatModel(), true)
                .extract("セブン-イレブン 目黒駅東口店\n合計 ¥352", "rcpt-001-konbini-mixed-tax");

        assertEquals("7-Eleven Meguro Station East Exit", data.merchantNameNormalized());
        assertEquals(352, data.totalAmount());
        assertEquals(25, data.tax8PercentAmount());
        assertEquals(0, data.tax10PercentAmount());
        assertEquals(ExpenseCategory.GROCERIES, data.expenseCategory());
        assertEquals(2, data.items().size());
        assertEquals("Hand-rolled Onigiri (Aged Red Salmon)", data.items().get(0).englishTranslation());
        assertTrue(data.items().get(0).reducedTaxApplicable());
        assertFalse(data.items().get(1).reducedTaxApplicable());
    }

    @Test
    void systemPromptContainsTaxHintWhenEnabled() {
        RecordingStubChatModel stub = new RecordingStubChatModel();
        serviceOver(stub, true).extract("x", "rcpt-001");
        assertTrue(stub.lastSystemText.contains("※"), "hint mentions the ※ marker");
        assertTrue(stub.lastSystemText.contains("8%"), "hint mentions the reduced rate");
    }

    @Test
    void taxHintCanBeDisabledForPromptRegressionExperiment() {
        RecordingStubChatModel stub = new RecordingStubChatModel();
        serviceOver(stub, false).extract("x", "rcpt-001");
        assertFalse(stub.lastSystemText.contains("※"), "hint must be gone when disabled");
        // but the base extraction rules stay
        assertTrue(stub.lastSystemText.contains("お釣り"));
    }
}
