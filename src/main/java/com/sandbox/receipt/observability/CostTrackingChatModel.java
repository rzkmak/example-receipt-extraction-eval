package com.sandbox.receipt.observability;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Layer 2 (judge side) — a {@link ChatModel} decorator that records token
 * cost with {@code role="judge"} through {@link LLMCostRecorder}.
 *
 * <p>The evaluation harness wraps the judge model in this before handing it
 * to Dokimos, so judge spend shows up in the same
 * {@code llm.cost.usd.total{role,model}} metric as the extractor's,
 * separable by the {@code role} tag.
 */
public final class CostTrackingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final LLMCostRecorder costRecorder;
    private final String role;
    private final String model;

    public CostTrackingChatModel(ChatModel delegate, LLMCostRecorder costRecorder, String role, String model) {
        this.delegate = delegate;
        this.costRecorder = costRecorder;
        this.role = role;
        this.model = model;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        var usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        costRecorder.record(role, model, usage, io.opentelemetry.api.trace.Span.current());
        return response;
    }

    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return delegate.getOptions();
    }
}
