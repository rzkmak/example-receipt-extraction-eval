package com.sandbox.receipt.api.dto;

/**
 * Request body for {@code POST /api/v1/receipts/extract}.
 *
 * @param rawInput    raw bilingual (Japanese/English) receipt text, possibly
 *                    noisy OCR
 * @param testcaseId  optional dataset case id (e.g.
 *                    {@code rcpt-001-konbini-mixed-tax}) — lands on the trace
 *                    as {@code receipt.eval.testcase_id} so Phoenix joins the
 *                    trace to its golden case
 */
public record ExtractRequest(String rawInput, String testcaseId) {
}
