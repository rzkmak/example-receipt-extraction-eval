package com.sandbox.receipt.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Phoenix's kind column reads {@code openinference.span.kind}. The filter must
 * tag Boot's HTTP server observations as CHAIN, must not invent kinds for
 * unknown contexts, and must never override a kind that was already set.
 */
class OpenInferenceSpanKindFilterTest {

    private final OpenInferenceSpanKindFilter filter = new OpenInferenceSpanKindFilter();

    @Test
    void tagsBootServerRequestsAsChain() {
        ServerRequestObservationContext context = new ServerRequestObservationContext(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class));

        filter.map(context);

        assertEquals("CHAIN", context.getHighCardinalityKeyValue(OpenInferenceSpanKindFilter.OPENINFERENCE_KIND).getValue(),
                "Boot HTTP server observation is the CHAIN wrapper of the pipeline");
    }

    @Test
    void leavesUnknownContextsUntouched() {
        Observation.Context context = new Observation.Context();

        filter.map(context);

        assertNull(context.getHighCardinalityKeyValue(OpenInferenceSpanKindFilter.OPENINFERENCE_KIND),
                "no kind invented for contexts the filter does not know");
    }

    @Test
    void neverOverridesAnExistingKind() {
        ServerRequestObservationContext context = new ServerRequestObservationContext(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class));
        context.addHighCardinalityKeyValue(
                KeyValue.of(OpenInferenceSpanKindFilter.OPENINFERENCE_KIND, "AGENT"));

        filter.map(context);

        assertEquals("AGENT", context.getHighCardinalityKeyValue(OpenInferenceSpanKindFilter.OPENINFERENCE_KIND).getValue(),
                "an explicitly set kind must survive");
    }
}
