package com.sandbox.receipt.observability;

/**
 * One extraction's input: either raw receipt text or a receipt image for a
 * vision-capable model (never both). Shared by the pipeline collaborators —
 * the guardrail inspects it, the invariants layer derives drift
 * characteristics from it — so the pipeline stays single-tracked.
 */
public record ExtractionInput(String text, byte[] image, String imageMediaType) {

    public boolean isImage() {
        return image != null;
    }
}
