package com.sandbox.receipt;

import com.sandbox.receipt.domain.ReceiptData;
import com.sandbox.receipt.evaluators.ExactTotalAndTaxEvaluator;
import com.sandbox.receipt.evaluators.LineItemSumConsistencyEvaluator;
import com.sandbox.receipt.evaluators.TranslationRubricFactory;
import com.sandbox.receipt.service.ReceiptExtractorService;
import dev.dokimos.core.Dataset;
import dev.dokimos.core.Evaluator;
import dev.dokimos.core.Example;
import dev.dokimos.core.Experiment;
import dev.dokimos.core.ExperimentResult;
import dev.dokimos.core.ItemResult;
import dev.dokimos.core.JudgeLM;
import dev.dokimos.core.Task;
import dev.dokimos.server.client.DokimosServerReporter;
import dev.dokimos.springai.SpringAiSupport;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
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
 *   <li><b>B (model swap):</b> {@code EXTRACTOR_MODEL=gpt-4o-mini} vs a smaller
 *       model (or a local Ollama endpoint via
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

    @Test
    void goldenDatasetExperiment() {
        Dataset dataset = GoldenReceipts.asDokimosDataset();

        JudgeLM judge = ReceiptEvaluationTest.judge();
        List<Evaluator> evaluators = List.of(
                new ExactTotalAndTaxEvaluator(),
                new LineItemSumConsistencyEvaluator(),
                TranslationRubricFactory.translationFidelityJudge(judge),
                TranslationRubricFactory.categoryClassificationJudge(judge));

        Task task = example -> {
            String rawInput = String.valueOf(example.input());
            String testcaseId = String.valueOf(example.inputs().getOrDefault("id", "unknown"));
            ReceiptData data = extractorService.extract(rawInput, testcaseId);
            return java.util.Map.of("output", data);
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
                    .projectName("receipt-eval-sandbox")
                    .build());
        }

        ExperimentResult result = builder.build().run();

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
}
