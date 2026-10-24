package com.sandbox.receipt;

import com.sandbox.receipt.GoldenReceipt.ReceiptExpectation;
import com.sandbox.receipt.domain.ReceiptData;
import com.sandbox.receipt.evaluators.ExactTotalAndTaxEvaluator;
import com.sandbox.receipt.evaluators.LineItemSumConsistencyEvaluator;
import com.sandbox.receipt.evaluators.SpanAnnotatingEvaluator;
import com.sandbox.receipt.evaluators.TranslationRubricFactory;
import com.sandbox.receipt.service.ReceiptExtractorService;
import dev.dokimos.core.Assertions;
import dev.dokimos.core.EvalTestCase;
import dev.dokimos.core.Evaluator;
import dev.dokimos.core.JudgeLM;
import dev.dokimos.springai.SpringAiSupport;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The Milestone 2 evaluation harness: runs the extractor over every golden
 * case and gates it on all four evaluators (two deterministic + two LLM
 * judges) with {@code Assertions.assertEval}, so a quality drop fails the
 * JUnit build.
 *
 * <p>Requires {@code OPENAI_API_KEY}. Judge model is {@code JUDGE_MODEL}
 * (default glm-5.3-flash); extractor model comes from {@code EXTRACTOR_MODEL}
 * (default glm-5.3-flash), which is the lever for Milestone 3 Experiment B.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@TestPropertySource(properties = "spring.ai.openai.api-key=${OPENAI_API_KEY:}")
class ReceiptEvaluationTest {

    @Autowired
    ReceiptExtractorService extractorService;

    @Autowired
    com.sandbox.receipt.observability.PhoenixSpanAnnotationClient phoenix;

    @Autowired
    com.sandbox.receipt.observability.LLMCostRecorder costRecorder;

    @Autowired
    com.sandbox.receipt.observability.EvalSpanRegistry evalSpanRegistry;

    static Stream<GoldenReceipt> cases() {
        return GoldenReceipts.load().stream();
    }

    JudgeLM judge() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        String model = System.getenv().getOrDefault("JUDGE_MODEL", "glm-5.3-flash");
        // Judge can use its own OpenAI-compatible endpoint; falls back to the
        // extractor's OPENAI_BASE_URL, then to the OpenAI default.
        String baseUrl = firstNonBlank(System.getenv("JUDGE_BASE_URL"), System.getenv("OPENAI_BASE_URL"));
        // Spring AI 2.0 builds on the official OpenAI Java SDK. Wire the same
        // stack the auto-configuration uses: ClientOptions + Spring AI's OkHttp
        // bridge, instead of the removed OpenAiApi builder.
        ClientOptions.Builder clientOptions = ClientOptions.builder()
                .apiKey(apiKey)
                .httpClient(SpringAiOpenAiHttpClient.builder().build());
        if (baseUrl != null) {
            clientOptions.baseUrl(baseUrl);
        }
        OpenAIClient api = new OpenAIClientImpl(clientOptions.build());
        ChatModel judgeModel = OpenAiChatModel.builder()
                .openAiClient(api)
                .options(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.0)
                        .build())
                .build();
        // Layer 2: judge tokens land in llm.cost.usd.total{role="judge"} so
        // evaluation overhead stays separable from extractor spend.
        return SpringAiSupport.asJudge(
                new com.sandbox.receipt.observability.CostTrackingChatModel(judgeModel, costRecorder, "judge", model));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void extractionPassesAllFourEvaluators(GoldenReceipt rc) {
        // The extraction span stays OPEN until evaluators ran, so the
        // SpanAnnotatingEvaluator can attach dokimos.eval.* attributes
        // in-process before the span is exported to Phoenix (layer 1).
        try (com.sandbox.receipt.observability.ExtractionResult result =
                     extractorService.extractWithMetadata(rc.rawInput(), rc.id(), rc.difficulty(), false)) {
            ReceiptData actual = result.data();

            ReceiptExpectation expectation = rc.expected();
            EvalTestCase testCase = EvalTestCase.builder()
                    .input(rc.rawInput())
                    .actualOutput("output", actual)
                    .expectedOutput("output", expectation)
                    .metadata("id", rc.id())
                    .metadata("difficulty", rc.difficulty())
                    .build();

            List<Evaluator> evaluators = new ArrayList<>();
            evaluators.add(new SpanAnnotatingEvaluator(new ExactTotalAndTaxEvaluator(), evalSpanRegistry, phoenix));
            evaluators.add(new SpanAnnotatingEvaluator(new LineItemSumConsistencyEvaluator(), evalSpanRegistry, phoenix));
            evaluators.add(new SpanAnnotatingEvaluator(
                    TranslationRubricFactory.translationFidelityJudge(judge()), evalSpanRegistry, phoenix));
            evaluators.add(new SpanAnnotatingEvaluator(
                    TranslationRubricFactory.categoryClassificationJudge(judge()), evalSpanRegistry, phoenix));

            Assertions.assertEval(testCase, evaluators);
        }
    }
}
