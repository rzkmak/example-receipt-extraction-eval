package com.sandbox.receipt.service;

import com.sandbox.receipt.domain.ReceiptData;
import com.sandbox.receipt.observability.ExtractionInput;
import com.sandbox.receipt.observability.ExtractionResult;
import com.sandbox.receipt.observability.PiiGuardrail;
import com.sandbox.receipt.observability.PipelineObservation;
import com.sandbox.receipt.observability.ReceiptInvariants;
import com.sandbox.receipt.observability.SchemaValidatedExtractor;
import org.springframework.stereotype.Service;

/**
 * Extracts normalized {@link ReceiptData} from raw bilingual receipt text.
 * This class is business flow only: prompt resolution → guardrail →
 * schema-validated model call → invariants → escalation. Every other concern
 * lives with the component that owns its data:
 *
 * <pre>
 * receipt.pipeline                        (PipelineObservation: lineage, I/O, SLIs, MDC)
 * ├── guardrail.pii_redact                (PiiGuardrail, layer 5)
 * ├── spring_ai.chat.*                    (Spring AI's own span, child via scope)
 * ├── receipt.schema_validation           (SchemaValidatedExtractor, layer 3)
 * └── schema.validate_and_invariants      (ReceiptInvariants, layer 7)
 * </pre>
 *
 * <p>The system prompt comes from {@link ExtractionPromptFactory}; the root
 * span's lifetime is deliberately caller-controlled — open until the harness
 * closes {@link ExtractionResult}, so evaluation verdicts can be attached
 * in-process — which is why it is not annotation-driven.
 */
@Service
public class ReceiptExtractorService {

    private final ExtractionPromptFactory promptFactory;
    private final PipelineObservation.Factory observations;
    private final PiiGuardrail piiGuardrail;
    private final SchemaValidatedExtractor schemaExtractor;
    private final ReceiptInvariants invariants;

    public ReceiptExtractorService(ExtractionPromptFactory promptFactory,
                                   PipelineObservation.Factory observations,
                                   PiiGuardrail piiGuardrail,
                                   SchemaValidatedExtractor schemaExtractor,
                                   ReceiptInvariants invariants) {
        this.promptFactory = promptFactory;
        this.observations = observations;
        this.piiGuardrail = piiGuardrail;
        this.schemaExtractor = schemaExtractor;
        this.invariants = invariants;
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
     * path; the PII mitigation differences are owned by {@link PiiGuardrail}.
     */
    public ExtractionResult extractImageWithMetadata(byte[] imageBytes, String mediaType, String testcaseId,
                                                     String difficulty, boolean live) {
        return runPipeline(new ExtractionInput(null, imageBytes, mediaType), testcaseId, difficulty, live);
    }

    private ExtractionResult runPipeline(ExtractionInput input, String testcaseId,
                                         String difficulty, boolean live) {
        String prompt = promptFactory.systemPrompt();
        try (PipelineObservation observation = observations.start(input, prompt, testcaseId, difficulty, live)) {
            try (var ignored = observation.span().makeCurrent()) {
                // ── Layer 5: guardrail phase ──
                PiiGuardrail.Outcome guardrail = piiGuardrail.inspect(input);
                observation.inputValue(guardrail.textForAudit());
                // Only the scrubbed text may reach the model; drift stats
                // below still measure the raw input.
                ExtractionInput modelInput = new ExtractionInput(
                        guardrail.textForModel(), input.image(), input.imageMediaType());

                // ── Layers 2+3: schema-validated model call ──
                SchemaValidatedExtractor.Outcome outcome = schemaExtractor.extract(modelInput, prompt);

                // ── Layer 7: invariants phase ──
                ReceiptInvariants.InvariantReport report = invariants.evaluateAndObserve(
                        outcome.data(), input.text(), input.image(), input.imageMediaType(), live);
                if (live && !report.allPassed()) {
                    // Escalation is business flow; the observation tags the
                    // trace root and forwards the scrubbed payload.
                    observation.escalate(guardrail.textForAudit(), outcome.data(), report.violations());
                }

                // ── Layer 1: the span stays open until the caller closes the result ──
                return observation.success(outcome.data(), report, outcome.outputTokens(),
                        guardrail.textForAudit());
            } catch (RuntimeException e) {
                observation.failed(e);
                throw e;
            }
        }
    }
}
