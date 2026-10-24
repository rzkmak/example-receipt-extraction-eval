package com.sandbox.receipt.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import org.springframework.ai.chat.metadata.Usage;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Layer 2 — token cost & prompt-caching economics.
 *
 * <p>Raw {@code input_tokens}/{@code output_tokens} hide the financial
 * footprint: every extraction also pays for judge calls, so judge spend can
 * rival extractor spend even when both run the same model — the {@code role}
 * tag keeps them separable — and the static system prompt + JSON schema
 * should produce provider cache hits on the repeated prefix. This recorder
 * turns one Spring AI {@link Usage} into:
 *
 * <ul>
 *   <li>span attributes: {@code gen_ai.usage.input_tokens},
 *       {@code gen_ai.usage.output_tokens},
 *       {@code gen_ai.usage.cache_read_input_tokens},
 *       {@code gen_ai.usage.cost_usd}, {@code gen_ai.role}</li>
 *   <li>Micrometer: {@code llm.cost.usd.total{role,model}} counter and a
 *       {@code llm.prompt.cache.hit.ratio{model}} gauge (exported by
 *       Prometheus as {@code llm_prompt_cache_hit_ratio}, matching the spec's
 *       {@code llm.prompt_cache.hit_ratio} naming)</li>
 * </ul>
 *
 * <p>The same recorder is reused for the judge side (role {@code judge}) via
 * {@link CostTrackingChatModel}, so extractor cost and evaluation overhead
 * stay separable in the metrics.
 */
public final class LLMCostRecorder {

    private static final double ONE_MILLION = 1_000_000.0;

    private final ObservabilityProperties properties;
    private final MeterRegistry meters;

    /** model -> [cachedInputTokens, totalInputTokens] accumulator for the gauge. */
    private final Map<String, AtomicLong[]> cacheStats = new ConcurrentHashMap<>();

    /** Models that already have their hit-ratio gauge registered. */
    private final Set<String> gaugesRegistered = ConcurrentHashMap.newKeySet();

    public LLMCostRecorder(ObservabilityProperties properties, MeterRegistry meters) {
        this.properties = properties;
        this.meters = meters;
    }

    /**
     * Records one model call's usage. Safe to call with a null usage (stub
     * models, providers without usage data) and with a no-op span.
     *
     * @param role  {@code "extractor"} or {@code "judge"}
     * @param model model id, e.g. {@code glm-5.3-flash}
     * @param usage token usage from the chat response, may be null
     * @param span  span to attach {@code gen_ai.usage.*} attributes to
     * @return the computed USD cost (0.0 when no usage or no price entry)
     */
    public double record(String role, String model, Usage usage, Span span) {
        if (usage == null || usage.getPromptTokens() == null || usage.getCompletionTokens() == null) {
            return 0.0;
        }
        int inputTokens = Math.max(0, usage.getPromptTokens());
        int outputTokens = Math.max(0, usage.getCompletionTokens());
        int cachedTokens = cachedReadTokens(usage);

        double cost = costOf(model, inputTokens, cachedTokens, outputTokens);

        if (span != null) {
            span.setAttribute("gen_ai.usage.input_tokens", inputTokens);
            span.setAttribute("gen_ai.usage.output_tokens", outputTokens);
            if (cachedTokens > 0) {
                span.setAttribute("gen_ai.usage.cache_read_input_tokens", cachedTokens);
            }
            span.setAttribute("gen_ai.usage.cost_usd", cost);
            span.setAttribute("gen_ai.role", role);
        }

        Counter.builder("llm.cost.usd.total")
                .tag("role", role)
                .tag("model", model == null ? "unknown" : model)
                .register(meters)
                .increment(cost);

        AtomicLong[] stats = cacheStats.computeIfAbsent(model == null ? "unknown" : model,
                m -> new AtomicLong[]{new AtomicLong(), new AtomicLong()});
        stats[0].addAndGet(cachedTokens);
        stats[1].addAndGet(inputTokens);
        registerHitRatioGauge(model == null ? "unknown" : model);

        return cost;
    }

    /** Current cache hit ratio for a model (cached input tokens / input tokens). */
    public double cacheHitRatio(String model) {
        AtomicLong[] stats = cacheStats.get(model);
        if (stats == null || stats[1].get() == 0) {
            return 0.0;
        }
        return (double) stats[0].get() / stats[1].get();
    }

    private void registerHitRatioGauge(String model) {
        if (!gaugesRegistered.add(model)) {
            return;
        }
        Gauge.builder("llm.prompt.cache.hit.ratio", this, r -> r.cacheHitRatio(model))
                .tag("model", model)
                .description("Fraction of input tokens served from the provider prompt cache")
                .register(meters);
    }

    private double costOf(String model, int inputTokens, int cachedTokens, int outputTokens) {
        if (model == null) {
            return 0.0;
        }
        ObservabilityProperties.ModelPrice price = properties.getPricing().get(model);
        if (price == null) {
            // Unknown model: tokens are still recorded on the span, but we
            // refuse to guess a price rather than emit a wrong cost.
            return 0.0;
        }
        int uncachedInput = Math.max(0, inputTokens - cachedTokens);
        return (uncachedInput / ONE_MILLION) * price.inputUsdPerMillionTokens()
                + (cachedTokens / ONE_MILLION) * price.cachedInputUsdPerMillionTokens()
                + (outputTokens / ONE_MILLION) * price.outputUsdPerMillionTokens();
    }

    /**
     * Extracts {@code prompt_tokens_details.cached_tokens} from the native
     * usage payload when the provider reports it. Returns 0 when absent —
     * the attribute simply won't be set, which keeps the telemetry honest
     * for providers without caching.
     */
    @SuppressWarnings("unchecked")
    private int cachedReadTokens(Usage usage) {
        Object nativeUsage = usage.getNativeUsage();
        if (nativeUsage instanceof Map<?, ?> map) {
            Object details = map.get("prompt_tokens_details");
            if (details instanceof Map<?, ?> detailsMap) {
                Object cached = detailsMap.get("cached_tokens");
                if (cached instanceof Number number) {
                    return Math.max(0, number.intValue());
                }
            }
        }
        return 0;
    }
}
