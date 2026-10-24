package com.sandbox.receipt.api;

import com.sandbox.receipt.domain.ReceiptData;
import com.sandbox.receipt.service.ReceiptExtractorService;
import org.springframework.http.MediaType;
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
     * Extracts structured receipt data from raw bilingual receipt text.
     *
     * <p>Pass {@code testcaseId} (e.g. {@code rcpt-001-konbini-mixed-tax}) when
     * calling from the evaluation harness; it lands on the trace as
     * {@code receipt.eval.testcase_id} so Phoenix joins trace to dataset case.
     */
    @PostMapping(value = "/extract", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReceiptData extract(@RequestBody ExtractRequest request) {
        return extractorService.extract(request.rawInput(), request.testcaseId());
    }
}
