package com.sandbox.receipt.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Adds {@code openinference.span.kind} to the framework observations that
 * never set it themselves, so Phoenix's kind column shows what a span is
 * instead of "unknown":
 *
 * <ul>
 *   <li>Boot HTTP server requests → {@code CHAIN} (the request wrapper around
 *       the receipt pipeline).</li>
 *   <li>Spring AI chat-model calls → {@code LLM}.</li>
 *   <li>Spring AI ChatClient operations → {@code CHAIN}.</li>
 *   <li>Spring AI tool-call loops → {@code TOOL}.</li>
 * </ul>
 *
 * <p>An {@link ObservationFilter} rather than a {@code SpanProcessor},
 * because the framework spans carry no usable identity when they start — at
 * start they are literally named {@code <unspecified span name>} and only get
 * their real name and attributes when their observation stops, so a span-level
 * matcher has nothing to key on. The observation context type, by contrast, is
 * stable from the beginning, and key-values added by a filter reach the span
 * when the tracing handler starts it.
 *
 * <p>The app's own spans set their kind at creation; the guard below never
 * second-guesses those.
 */
public final class OpenInferenceSpanKindFilter implements ObservationFilter {

    public static final String OPENINFERENCE_KIND = "openinference.span.kind";

    @Override
    public Observation.Context map(Observation.Context context) {
        if (context.getHighCardinalityKeyValue(OPENINFERENCE_KIND) != null) {
            return context; // the app sets its own kinds — never second-guess them
        }
        String kind = kindFor(context);
        if (kind != null) {
            context.addHighCardinalityKeyValue(KeyValue.of(OPENINFERENCE_KIND, kind));
        }
        return context;
    }

    private String kindFor(Observation.Context context) {
        if (context instanceof ServerRequestObservationContext) {
            return "CHAIN"; // Boot HTTP server span wrapping the pipeline
        }
        if (context instanceof ChatModelObservationContext) {
            return "LLM";   // Spring AI chat-model call
        }
        if (context instanceof ChatClientObservationContext) {
            return "CHAIN"; // Spring AI ChatClient operation
        }
        if (context instanceof org.springframework.ai.chat.client.advisor.observation.AdvisorObservationContext) {
            return "CHAIN"; // Spring AI advisor chain (ChatModelCallAdvisor "call", ToolCallingAdvisor "tool _calling")
        }
        if (context instanceof ToolCallingObservationContext) {
            return "TOOL";  // Spring AI tool-call loop
        }
        return null;
    }
}
