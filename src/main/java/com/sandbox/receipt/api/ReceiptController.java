package com.sandbox.receipt.api;

import com.sandbox.receipt.api.dto.ExtractRequest;
import com.sandbox.receipt.domain.ReceiptData;
import com.sandbox.receipt.observability.ExtractionResult;
import com.sandbox.receipt.service.ReceiptExtractorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptController {

    /** 10 MB upload cap — receipts compress far below this. */
    private static final long MAX_IMAGE_BYTES = 10 * 1_000_000;

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE, "image/webp");

    private final ReceiptExtractorService extractorService;

    public ReceiptController(ReceiptExtractorService extractorService) {
        this.extractorService = extractorService;
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

    /**
     * Image variant: a vision-capable extractor model reads the receipt photo
     * directly (no OCR pre-pass). Upload as {@code multipart/form-data} with
     * an image part; PNG, JPEG and WebP up to 10 MB are accepted.
     *
     * <p>Note on privacy: the input cannot be PII-scrubbed on this path — the
     * model reads any card numbers printed on the photo. Telemetry mitigations
     * are on the output side (see {@code ReceiptExtractorService}).
     */
    @PostMapping(value = "/extract-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReceiptData> extractImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "testcaseId", required = false) String testcaseId) {
        String mediaType = file.getContentType();
        if (mediaType == null || !ALLOWED_IMAGE_TYPES.contains(mediaType.toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "unsupported image type " + mediaType + " (allowed: " + ALLOWED_IMAGE_TYPES + ")");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "could not read uploaded image", e);
        }
        if (bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty upload");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "image exceeds " + (MAX_IMAGE_BYTES / 1_000_000) + " MB limit");
        }

        try (ExtractionResult result = extractorService.extractImageWithMetadata(
                bytes, mediaType, testcaseId, "live", true)) {
            return ResponseEntity.ok()
                    .header("X-Trace-Id", result.traceId())
                    .header("X-Span-Id", result.spanId())
                    .body(result.data());
        }
    }
}
