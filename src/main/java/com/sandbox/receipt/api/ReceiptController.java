package com.sandbox.receipt.api;

import com.sandbox.receipt.domain.ReceiptData;
import com.sandbox.receipt.observability.ExtractionResult;
import com.sandbox.receipt.service.ReceiptExtractorService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptController {

    private final ReceiptExtractorService extractorService;

    public ReceiptController(ReceiptExtractorService extractorService) {
        this.extractorService = extractorService;
    }

    public record ExtractRequest(String rawInput, String testcaseId) {
    }

    /**
     * Extracts structured receipt data from raw bilingual receipt text (live
     * mode: no golden ground truth, so layer-7 reference-free invariants run
     * and failures are tagged {@code sampling.priority=HIGH_RISK}).
     *
     * <p>Pass {@code testcaseId} (e.g. {@code rcpt-001-konbini-mixed-tax}) when
     * calling from the evaluation harness; it lands on the trace as
     * {@code receipt.eval.testcase_id} so Phoenix joins trace to dataset case.
     *
     * <p>Trace coordinates are returned in {@code X-Trace-Id}/{@code X-Span-Id}
     * so a caller can pull the exact extraction up in Phoenix.
     */
    @PostMapping(value = "/extract", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReceiptData> extract(@RequestBody ExtractRequest request) {
        try (ExtractionResult result = extractorService.extractWithMetadata(
                request.rawInput(), request.testcaseId(), "live", true)) {
            return ResponseEntity.ok()
                    .header("X-Trace-Id", result.traceId())
                    .header("X-Span-Id", result.spanId())
                    .body(result.data());
        }
    }
}
