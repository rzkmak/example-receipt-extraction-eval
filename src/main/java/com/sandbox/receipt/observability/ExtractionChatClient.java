package com.sandbox.receipt.observability;

import io.opentelemetry.api.trace.Span;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * The model gateway for extraction: one method, one chat call, for either
 * modality. Owns the layer-2 usage→cost recording so no business component
 * ever touches {@code ChatClient} or token accounting.
 *
 * <p>Usage attributes land on the span that is current at call time — the
 * pipeline span, which the service holds open for exactly this reason.
 */
public final class ExtractionChatClient {

    private final ChatClient chatClient;
    private final LLMCostRecorder costRecorder;
    private final String extractorModel;

    public ExtractionChatClient(ChatClient.Builder chatClientBuilder, LLMCostRecorder costRecorder,
                                String extractorModel) {
        this.chatClient = chatClientBuilder.build();
        this.costRecorder = costRecorder;
        this.extractorModel = extractorModel;
    }

    /** One model reply plus the token count of its final completion. */
    public record Completion(String rawOutput, long outputTokens) {
    }

    public Completion complete(String systemPrompt, String userText, ExtractionInput input) {
        ChatResponse response = input.isImage()
                ? chatClient.prompt()
                        .system(systemPrompt)
                        .user(u -> u.text(userText)
                                .media(toMimeType(input.imageMediaType()),
                                        new ByteArrayResource(input.image())))
                        .call()
                        .chatResponse()
                : chatClient.prompt()
                        .system(systemPrompt)
                        .user(userText)
                        .call()
                        .chatResponse();

        String rawOutput = response == null || response.getResult() == null
                ? null
                : response.getResult().getOutput().getText();

        // Layer 2: usage -> cost + cache telemetry on the current (pipeline) span.
        Usage usage = response != null && response.getMetadata() != null
                ? response.getMetadata().getUsage()
                : null;
        costRecorder.record("extractor", extractorModel, usage, Span.current());

        long outputTokens = usage != null && usage.getCompletionTokens() != null
                ? usage.getCompletionTokens()
                : 0;
        return new Completion(rawOutput, outputTokens);
    }

    private static MimeType toMimeType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return MimeTypeUtils.IMAGE_PNG;
        }
        try {
            return MimeType.valueOf(mediaType);
        } catch (InvalidMimeTypeException e) {
            return MimeTypeUtils.IMAGE_PNG;
        }
    }
}
