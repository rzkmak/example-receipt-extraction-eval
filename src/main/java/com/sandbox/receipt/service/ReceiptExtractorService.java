package com.sandbox.receipt.service;

import com.sandbox.receipt.domain.ReceiptData;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Extracts normalized {@link ReceiptData} from raw bilingual receipt text.
 *
 * <p>Every extraction runs inside an application span named {@code receipt.extract}
 * carrying the custom attributes from the observability spec, so traces in
 * Phoenix can be joined back to the golden dataset case that produced them.
 * The Spring AI chat call happens inside that span's scope, which makes the
 * model call ({@code chat gpt-4o-mini}, with gen_ai.* attributes) a child span
 * of {@code receipt.extract} in the exported trace.
 */
@Service
public class ReceiptExtractorService {

    private static final String BASE_SYSTEM_PROMPT = """
            You are an expert bilingual (Japanese/English) accounting data extractor.

            You receive raw Japanese receipt text (possibly noisy OCR) and must return ONE
            JSON object that strictly follows the provided JSON schema.

            Extraction rules:
            1. Every purchased line item must appear in `items[]`, with `quantity` and
               `unitPrice` taken from the receipt. Repeat lines count as quantity > 1.
            2. Discounts (値引, 割引, シール値引, セット割引) MUST be preserved as line items
               with a NEGATIVE `unitPrice`, never dropped and never turned into positive lines.
            3. Footer noise is NOT a line item: cash tendered (お預り), change given
               (お釣り), point balances, and store slogans must be ignored.
            4. `merchantNameNormalized` is the English name of the store chain
               (e.g. "セブン-イレブン" -> "7-Eleven"), keeping the branch description
               (e.g. "Meguro Station East Exit").
            5. `englishTranslation` must be natural, specific English for the product or
               brand — not transliteration and not a generic word like "Food".
            6. Prices are tax-inclusive yen. The bottom tax lines (内消費税等 8%対象 / 10%対象)
               give the embedded consumption tax per rate bucket:
               - `tax8PercentAmount`   <- the 8% 対象 amount (reduced rate: food, newspaper)
               - `tax10PercentAmount`  <- the 10% 対象 amount (standard rate)
               - `subtotalAmount`      <- totalAmount - tax8PercentAmount - tax10PercentAmount
               - `totalAmount`         <- 合計 (never お預り or お釣り)
            7. `reducedTaxApplicable` is true for items under the 8% reduced rate
               (ready-to-eat food, groceries), false for the 10% standard rate
               (plastic bags, alcohol, most non-food goods).
            8. `expenseCategory` must be one of the enum values. Judge it from the
               merchant and the majority of items.
            """;

    /**
     * The hint removed in Milestone 3 Experiment A (prompt regression).
     * {@code receipt.extraction.tax-hint=false} turns it off.
     */
    private static final String TAX_HINT = """
            9. On Japanese receipts, items marked with a leading `※` or `*` are subject to
               the 8% reduced consumption tax, so their `reducedTaxApplicable` is true.
            """;

    private final ChatClient chatClient;
    private final Tracer tracer;
    private final boolean taxHintEnabled;

    public ReceiptExtractorService(ChatClient.Builder chatClientBuilder,
                                   OpenTelemetry openTelemetry,
                                   @Value("${receipt.extraction.tax-hint:true}") boolean taxHintEnabled) {
        this.chatClient = chatClientBuilder.build();
        this.tracer = openTelemetry.getTracer("com.sandbox.receipt");
        this.taxHintEnabled = taxHintEnabled;
    }

    public ReceiptData extract(String rawReceiptText, String testcaseId) {
        Span span = tracer.spanBuilder("receipt.extract").startSpan();
        if (testcaseId != null && !testcaseId.isBlank()) {
            span.setAttribute("receipt.eval.testcase_id", testcaseId);
        }
        try (var ignored = span.makeCurrent()) {
            ReceiptData data = chatClient.prompt()
                    .system(systemPrompt())
                    .user(u -> u.text(rawReceiptText))
                    .call()
                    .entity(ReceiptData.class);

            span.setAttribute("receipt.extracted.item_count",
                    data.items() == null ? 0 : data.items().size());
            span.setAttribute("receipt.extracted.category",
                    data.expenseCategory() == null ? "UNKNOWN" : data.expenseCategory().name());
            span.setAttribute("receipt.parse.success", true);
            return data;
        } catch (RuntimeException e) {
            span.setAttribute("receipt.parse.success", false);
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private String systemPrompt() {
        return taxHintEnabled ? BASE_SYSTEM_PROMPT + TAX_HINT : BASE_SYSTEM_PROMPT;
    }
}
