package com.sandbox.receipt.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Layer 6 — content hash of the effective system prompt (first 12 hex chars
 * of the SHA-256). Auto-detects uncommitted prompt edits during local
 * testing, since the hash changes the moment the prompt text does.
 */
public final class PromptHash {

    private PromptHash() {
    }

    public static String sha256Prefix(String prompt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(prompt.getBytes(StandardCharsets.UTF_8)), 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
