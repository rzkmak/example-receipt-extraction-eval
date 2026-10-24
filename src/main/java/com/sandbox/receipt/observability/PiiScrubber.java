package com.sandbox.receipt.observability;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 5 — pre-inference PII guardrail.
 *
 * <p>Real Japanese receipts carry card numbers, point-card member ids and
 * phone numbers. Everything this class masks is removed <em>before</em> the
 * text reaches {@code ChatClient}, which means the Spring AI chat span's
 * {@code gen_ai.prompt} attribute (recorded via {@code include-prompt}) can
 * only ever contain scrubbed text — the leak is structurally impossible, not
 * just discouraged.
 *
 * <p>Rules (spec §5):
 * <ul>
 *   <li>Credit/debit card numbers → {@code [REDACTED_CARD]}. A digit run of
 *       12–19 digits (spaces/dashes allowed) counts as a card only if it
 *       passes the Luhn checksum — that keeps receipt serial numbers and
 *       dates from being masked while still catching real PANs.</li>
 *   <li>Point/member card ids ({@code 会員番号}, {@code ポイントカード}, …)
 *       → {@code [REDACTED_MEMBER_ID]}.</li>
 *   <li>Phone numbers after a TEL/FAX/電話 label → {@code [REDACTED_PHONE]}.</li>
 * </ul>
 */
public final class PiiScrubber {

    public static final String PLACEHOLDER_CARD = "[REDACTED_CARD]";
    public static final String PLACEHOLDER_MEMBER_ID = "[REDACTED_MEMBER_ID]";
    public static final String PLACEHOLDER_PHONE = "[REDACTED_PHONE]";

    public static final String TYPE_CREDIT_CARD = "CREDIT_CARD";
    public static final String TYPE_MEMBER_ID = "MEMBER_ID";
    public static final String TYPE_PHONE = "PHONE";

    private static final Pattern PHONE =
            Pattern.compile("(?:TEL|ＴＥＬ|FAX|ＦＡＸ|電話)\\s*[#:：]?\\s*([0-9]{2,5}[\\-−–][0-9]{1,4}[\\-−–][0-9]{3,4})");

    private static final Pattern MEMBER_ID =
            Pattern.compile("(?:会員番号|ポイントカード|ポイント会員|会員NO|会員No|会員no|会員カード)\\s*[#:：]?\\s*([0-9][0-9 \\-−–]{2,18}[0-9])");

    private static final Pattern CARD_CANDIDATE =
            Pattern.compile("(?<![0-9])([0-9][0-9 \\-−–]{10,40}[0-9])(?![0-9])");

    private final boolean enabled;

    public PiiScrubber(boolean enabled) {
        this.enabled = enabled;
    }

    public static PiiScrubber enabled() {
        return new PiiScrubber(true);
    }

    public static PiiScrubber disabled() {
        return new PiiScrubber(false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** What the scrubber did to one input text. */
    public record Result(String scrubbedText, boolean detected, int redactedCount, List<String> entityTypes) {
    }

    public Result scrub(String rawText) {
        if (!enabled || rawText == null || rawText.isEmpty()) {
            return new Result(rawText == null ? "" : rawText, false, 0, List.of());
        }

        Set<String> entityTypes = new LinkedHashSet<>();
        int redacted = 0;
        String text = rawText;

        // Order matters: labelled ids first (they carry context), then the
        // generic checksummed card rule so a 13+ digit member id that happens
        // to pass Luhn still gets masked (as a card) instead of leaking.
        for (Rule rule : RULES) {
            text = rule.apply(text, entityTypes);
            redacted += rule.lastCount;
        }

        return new Result(text, redacted > 0, redacted, new ArrayList<>(entityTypes));
    }

    /** One redaction rule: replaces matches in place and reports its count. */
    private abstract static class Rule {
        private int lastCount;
        private final Pattern pattern;
        private final String placeholder;
        private final String entityType;

        Rule(Pattern pattern, String placeholder, String entityType) {
            this.pattern = pattern;
            this.placeholder = placeholder;
            this.entityType = entityType;
        }

        String apply(String text, Set<String> entityTypes) {
            lastCount = 0;
            Matcher matcher = pattern.matcher(text);
            StringBuffer out = new StringBuffer();
            while (matcher.find()) {
                if (accept(matcher)) {
                    matcher.appendReplacement(out, Matcher.quoteReplacement(placeholder));
                    lastCount++;
                    entityTypes.add(entityType);
                } else {
                    matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                }
            }
            matcher.appendTail(out);
            return out.toString();
        }

        /** Extra predicate for checksummed rules like the card rule. */
        boolean accept(Matcher matcher) {
            return true;
        }
    }

    private static final class CardRule extends Rule {
        CardRule() {
            super(CARD_CANDIDATE, PLACEHOLDER_CARD, TYPE_CREDIT_CARD);
        }

        @Override
        boolean accept(Matcher matcher) {
            String digits = matcher.group(1).replaceAll("[^0-9]", "");
            return digits.length() >= 12 && digits.length() <= 19 && passesLuhn(digits);
        }
    }

    private static final Rule[] RULES = {
            new Rule(MEMBER_ID, PLACEHOLDER_MEMBER_ID, TYPE_MEMBER_ID) {
            },
            new Rule(PHONE, PLACEHOLDER_PHONE, TYPE_PHONE) {
            },
            new CardRule(),
    };

    /** Standard Luhn checksum used to validate card-number candidates. */
    static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
