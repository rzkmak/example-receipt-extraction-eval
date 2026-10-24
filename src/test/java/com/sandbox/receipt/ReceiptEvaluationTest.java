package com.sandbox.receipt;

import com.sandbox.receipt.GoldenReceipt.ReceiptExpectation;
import com.sandbox.receipt.domain.ReceiptData;
import com.sandbox.receipt.evaluators.ExactTotalAndTaxEvaluator;
import com.sandbox.receipt.evaluators.LineItemSumConsistencyEvaluator;
import com.sandbox.receipt.evaluators.TranslationRubricFactory;
import com.sandbox.receipt.service.ReceiptExtractorService;
import dev.dokimos.core.Assertions;
import dev.dokimos.core.EvalTestCase;
import dev.dokimos.core.Evaluator;
import dev.dokimos.core.JudgeLM;
import dev.dokimos.springai.SpringAiSupport;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
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
 * (default gpt-4o); extractor model comes from {@code EXTRACTOR_MODEL}
 * (default gpt-4o-mini), which is the lever for Milestone 3 Experiment B.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@TestPropertySource(properties = "spring.ai.openai.api-key=${OPENAI_API_KEY:}")
class ReceiptEvaluationTest {

    @Autowired
    ReceiptExtractorService extractorService;

    static Stream<GoldenReceipt> cases() {
        return GoldenReceipts.load().stream();
    }

    static JudgeLM judge() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        String model = System.getenv().getOrDefault("JUDGE_MODEL", "gpt-4o");
        // Judge can use its own OpenAI-compatible endpoint; falls back to the
        // extractor's OPENAI_BASE_URL, then to the OpenAI default.
        String baseUrl = firstNonBlank(System.getenv("JUDGE_BASE_URL"), System.getenv("OPENAI_BASE_URL"));
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(apiKey);
        if (baseUrl != null) {
            apiBuilder.baseUrl(baseUrl);
        }
        OpenAiApi api = apiBuilder.build();
        ChatModel judgeModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.0)
                        .build())
                .build();
        return SpringAiSupport.asJudge(judgeModel);
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
        ReceiptData actual = extractorService.extract(rc.rawInput(), rc.id());

        ReceiptExpectation expectation = rc.expected();
        EvalTestCase testCase = EvalTestCase.builder()
                .input(rc.rawInput())
                .actualOutput("output", actual)
                .expectedOutput("output", expectation)
                .metadata("id", rc.id())
                .metadata("difficulty", rc.difficulty())
                .build();

        List<Evaluator> evaluators = new ArrayList<>();
        evaluators.add(new ExactTotalAndTaxEvaluator());
        evaluators.add(new LineItemSumConsistencyEvaluator());
        evaluators.add(TranslationRubricFactory.translationFidelityJudge(judge()));
        evaluators.add(TranslationRubricFactory.categoryClassificationJudge(judge()));

        Assertions.assertEval(testCase, evaluators);
    }
}
