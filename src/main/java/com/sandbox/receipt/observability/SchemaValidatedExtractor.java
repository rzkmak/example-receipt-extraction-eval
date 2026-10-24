package com.sandbox.receipt.observability;

import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.MismatchedInputException;
import com.sandbox.receipt.domain.ReceiptData;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 3 phase bean — owns the {@code receipt.schema_validation} span and
 * the whole "model said something, does it deserialize?" problem: the retry
 * loop with self-correction, error classification, and required-field
 * validation.
 *
 * <p>Each attempt goes through {@link ExtractionChatClient} (which owns cost
 * telemetry); this class decides whether the reply is usable and, when it
 * is not, what corrective instruction the next attempt gets.
 */
public final class SchemaValidatedExtractor {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidatedExtractor.class);

    /** Text part of the vision user message; the image rides alongside as media. */
    private static final String IMAGE_INSTRUCTION = """
            Extract the receipt data from the attached receipt image.
            Read the printed Japanese text directly from the image.""";

    private final ExtractionChatClient chatClient;
    private final PiiGuardrail piiGuardrail;
    private final ObservabilityProperties properties;
    private final io.opentelemetry.api.trace.Tracer tracer;

    public SchemaValidatedExtractor(ExtractionChatClient chatClient, PiiGuardrail piiGuardrail,
                                    ObservabilityProperties properties, OpenTelemetry openTelemetry) {
        this.chatClient = chatClient;
        this.piiGuardrail = piiGuardrail;
        this.properties = properties;
        this.tracer = openTelemetry.getTracer("com.sandbox.receipt");
    }

    /** One validated extraction plus the token count of its final model reply. */
    public record Outcome(ReceiptData data, long outputTokens) {
    }

    public Outcome extract(ExtractionInput input, String systemPrompt) {
        BeanOutputConverter<ReceiptData> converter = new BeanOutputConverter<>(ReceiptData.class);
        String format = converter.getFormat();

        Span schemaSpan = tracer.spanBuilder("receipt.schema_validation").startSpan();
        schemaSpan.setAttribute("schema.target_class", ReceiptData.class.getName());
        schemaSpan.setAttribute("schema.input.modality", input.isImage() ? "image" : "text");
        int maxAttempts = properties.getSchemaMaxAttempts();
        String lastRawOutput = null;
        String lastErrorType = null;
        long lastOutputTokens = 0;

        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                schemaSpan.setAttribute("schema.retry.attempt_count", attempt - 1);
                String userText = buildUserText(
                        input.isImage() ? IMAGE_INSTRUCTION : input.text(),
                        format, attempt, lastRawOutput, lastErrorType);

                ExtractionChatClient.Completion completion =
                        chatClient.complete(systemPrompt, userText, input);
                String rawOutput = completion.rawOutput();
                lastRawOutput = rawOutput;
                lastOutputTokens = completion.outputTokens();

                try {
                    ReceiptData data = converter.convert(rawOutput);
                    validateRequiredFields(data);
                    schemaSpan.setAttribute("schema.validation.success", true);
                    return new Outcome(data, lastOutputTokens);
                } catch (RuntimeException parseError) {
                    lastErrorType = classifySchemaError(parseError);
                    if (attempt == maxAttempts) {
                        schemaSpan.setAttribute("schema.validation.success", false);
                        schemaSpan.setAttribute("schema.error.type", lastErrorType);
                        // On the image path the model may have transcribed
                        // card numbers / member ids from the photo — scrub
                        // before the payload lands in a span attribute.
                        String payload = truncate(rawOutput == null ? "null" : rawOutput,
                                properties.getMalformedPayloadAttributeLimit());
                        if (input.isImage()) {
                            payload = piiGuardrail.scrubText(payload);
                        }
                        schemaSpan.setAttribute("schema.raw_malformed_payload", payload);
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "LLM output did not deserialize to ReceiptData after "
                                        + maxAttempts + " attempt(s): " + lastErrorType, parseError);
                    }
                    log.warn("Schema parse attempt {} failed ({}); retrying with self-correction",
                            attempt, lastErrorType);
                }
            }
            throw new IllegalStateException("unreachable");
        } finally {
            schemaSpan.end();
        }
    }

    private String buildUserText(String inputOrInstruction, String format, int attempt,
                                 String lastRawOutput, String lastErrorType) {
        if (attempt == 1) {
            return inputOrInstruction + "\n\n" + format;
        }
        return "Your previous reply was not valid JSON for the requested schema.\n"
                + "Previous reply (invalid):\n" + truncate(String.valueOf(lastRawOutput), 2000) + "\n"
                + "Problem: " + lastErrorType + "\n\n"
                + "Return ONE corrected JSON object that follows the schema, with no commentary.\n\n"
                + inputOrInstruction + "\n\n" + format;
    }

    /** The spec's {@code MissingRequiredField} signal for fields the domain cannot do without. */
    private void validateRequiredFields(ReceiptData data) {
        if (data == null) {
            throw new MissingRequiredFieldException("conversion produced no ReceiptData");
        }
        List<String> missing = new ArrayList<>();
        if (data.totalAmount() == null) {
            missing.add("totalAmount");
        }
        if (data.items() == null) {
            missing.add("items");
        }
        if (!missing.isEmpty()) {
            throw new MissingRequiredFieldException("missing required fields: " + String.join(", ", missing));
        }
    }

    static String classifySchemaError(Throwable error) {
        if (error == null) {
            return "Unknown";
        }
        if (error instanceof MissingRequiredFieldException) {
            return "MissingRequiredField";
        }
        Throwable cause = error;
        while (cause != null) {
            // Telemetry labels keep the historic names (documented in the
            // README); Jackson 3 renamed JsonParseException -> StreamReadException.
            if (cause instanceof StreamReadException) {
                return "JsonParseException";
            }
            if (cause instanceof MismatchedInputException) {
                return "MismatchedInputException";
            }
            cause = cause.getCause();
        }
        return error.getClass().getSimpleName();
    }

    private static final class MissingRequiredFieldException extends RuntimeException {
        MissingRequiredFieldException(String message) {
            super(message);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
