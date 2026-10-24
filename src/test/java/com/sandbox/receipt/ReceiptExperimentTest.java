package com.sandbox.receipt;

import com.sandbox.receipt.evaluators.ExactTotalAndTaxEvaluator;
import com.sandbox.receipt.evaluators.LineItemSumConsistencyEvaluator;
import com.sandbox.receipt.evaluators.TranslationRubricFactory;
import com.sandbox.receipt.service.ReceiptExtractorService;
import dev.dokimos.core.Dataset;
import dev.dokimos.core.Evaluator;
import dev.dokimos.core.Experiment;
import dev.dokimos.core.ExperimentResult;
import dev.dokimos.core.ItemResult;
import dev.dokimos.core.JudgeLM;
import dev.dokimos.core.Task;
import dev.dokimos.server.client.DokimosServerReporter;
import dev.dokimos.springai.SpringAiSupport;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone 3 harness: runs the whole golden dataset as one Dokimos
 * {@link Experiment}, prints the pass rate and per-case failures, optionally
 * reports to the Dokimos server UI, and gates on a pass-rate threshold.
 *
 * <p>Experiments:
 * <ul>
 *   <li><b>A (prompt regression):</b> {@code RECEIPT_TAX_HINT=false} drops the
 *       ※ = 8% hint from the extractor prompt — the suite should fail.</li>
 *   <li><b>B (model swap):</b> {@code EXTRACTOR_MODEL=glm-5.3-flash} vs a
 *       different model (or a local Ollama endpoint via
 *       {@code SPRING_AI_OPENAI_BASE_URL}); compare judge pass rates and token
 *       usage across runs in the Dokimos UI.</li>
 *   <li><b>C (judge bias check):</b> rubric weights are fixed in
 *       {@link TranslationRubricFactory}; a verbose-but-wrong translation
 *       should score below a concise-but-accurate one.</li>
 * </ul>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class ReceiptExperimentTest {

    @Autowired
    ReceiptExtractorService extractorService;

    @Autowired
    com.sandbox.receipt.observability.PhoenixSpanAnnotationClient phoenix;

    @Autowired
    com.sandbox.receipt.observability.LLMCostRecorder costRecorder;

    @Autowired
    com.sandbox.receipt.observability.EvalSpanRegistry evalSpanRegistry;

    @Test
    void goldenDatasetExperiment() {
        Dataset dataset = GoldenReceipts.asDokimosDataset();

        JudgeLM judge = judge();
        List<Evaluator> evaluators = List.of(
                new com.sandbox.receipt.evaluators.SpanAnnotatingEvaluator(
                        new ExactTotalAndTaxEvaluator(), evalSpanRegistry, phoenix),
                new com.sandbox.receipt.evaluators.SpanAnnotatingEvaluator(
                        new LineItemSumConsistencyEvaluator(), evalSpanRegistry, phoenix),
                new com.sandbox.receipt.evaluators.SpanAnnotatingEvaluator(
                        TranslationRubricFactory.translationFidelityJudge(judge), evalSpanRegistry, phoenix),
                new com.sandbox.receipt.evaluators.SpanAnnotatingEvaluator(
                        TranslationRubricFactory.categoryClassificationJudge(judge), evalSpanRegistry, phoenix));

        Task task = example -> {
            String rawInput = String.valueOf(example.input());
            String testcaseId = String.valueOf(example.inputs().getOrDefault("id", "unknown"));
            // The Experiment runs evaluators after the task returns, so the
            // span ends here and in-process attribute attachment is a no-op;
            // the Phoenix span_annotations POST carries the verdicts instead.
            try (com.sandbox.receipt.observability.ExtractionResult result =
                         extractorService.extractWithMetadata(rawInput, testcaseId, "experiment", false)) {
                return java.util.Map.of("output", (Object) result.data());
            }
        };

        Experiment.Builder builder = Experiment.builder()
                .name("receipt-extraction-golden")
                .description("Bilingual receipt extraction over the 15-case golden matrix")
                .dataset(dataset)
                .task(task)
                .evaluators(evaluators);

        String serverUrl = System.getenv("DOKIMOS_SERVER_URL");
        if (serverUrl != null && !serverUrl.isBlank()) {
            builder.reporter(DokimosServerReporter.builder()
                    .serverUrl(serverUrl)
                    .projectName("example-receipt-extraction-eval")
                    .build());
        }

        ExperimentResult result = builder.build().run();
        evalSpanRegistry.clear();

        System.out.printf("Pass rate: %.2f%% (passed %d / %d, failed %d)%n",
                result.passRate() * 100, result.passCount(), result.totalCount(), result.failCount());
        for (ItemResult failure : result.itemResults().stream()
                .filter(item -> !item.evalResults().isEmpty() && item.evalResults().stream().noneMatch(e -> e.success()))
                .toList()) {
            System.out.println("FAILED: " + failure.example().inputs().get("id"));
            failure.evalResults().forEach(e -> System.out.println("  " + e.name() + " -> " + e.score() + ": " + e.reason()));
        }

        double threshold = Double.parseDouble(System.getenv().getOrDefault("RECEIPT_PASS_RATE_THRESHOLD", "0.6"));
        assertTrue(result.passRate() >= threshold,
                "pass rate %.2f%% below threshold %.2f%%".formatted(result.passRate() * 100, threshold * 100));
    }

    private JudgeLM judge() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        String model = System.getenv().getOrDefault("JUDGE_MODEL", "glm-5.3-flash");
        String baseUrl = System.getenv("JUDGE_BASE_URL") != null
                ? System.getenv("JUDGE_BASE_URL")
                : System.getenv("OPENAI_BASE_URL");
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(apiKey);
        if (baseUrl != null && !baseUrl.isBlank()) {
            apiBuilder.baseUrl(baseUrl);
        }
        ChatModel judgeModel = OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.0)
                        .build())
                .build();
        return SpringAiSupport.asJudge(
                new com.sandbox.receipt.observability.CostTrackingChatModel(judgeModel, costRecorder, "judge", model));
    }
}
