package com.discoveryhub.ingestion.api;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RequestSizeFilterTest {

    private static final long LIMIT = 64;

    private final RequestSizeFilter filter = new RequestSizeFilter(LIMIT, new ObjectMapper());
    private final MockFilterChain chain = new MockFilterChain();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void refusesABodyThatDeclaresTooLargeALength() throws Exception {
        filter.doFilter(post("x".repeat(100), true), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void refusesAnOversizedBodyThatDeclaresNoLengthAtAll() throws Exception {
        // The bypass this filter existed to prevent: a chunked request has no Content-Length, so
        // getContentLengthLong() returns -1 and every "-1 > limit" check passes it straight
        // through. Checking the declared length alone left the cap off for any streaming client.
        filter.doFilter(post("x".repeat(100), false), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void passesAnUndeclaredBodyUnderTheLimitThroughWithItsContentIntact() throws Exception {
        String body = "[{\"externalId\":\"E-1\"}]";

        filter.doFilter(post(body, false), response, chain);

        HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
        assertThat(forwarded).isNotNull();
        // The body was consumed to count it, so it has to be replayable downstream or the filter
        // would break every request it allowed.
        assertThat(new String(forwarded.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(body);
        assertThat(forwarded.getContentLengthLong()).isEqualTo(body.length());
    }

    @Test
    void refusalIsReportedInTheSameShapeAsEveryOtherOutcome() throws Exception {
        filter.doFilter(post("x".repeat(100), true), response, chain);

        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"rejected\":1")
                .contains("exceeds the " + LIMIT + " byte limit");
    }

    @Test
    void leavesRequestsForOtherPathsAlone() throws Exception {
        MockHttpServletRequest request = post("x".repeat(100), true);
        request.setRequestURI("/actuator/health");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    /** @param declareLength false to model a chunked request, which sends no Content-Length */
    private static MockHttpServletRequest post(String body, boolean declareLength) {
        MockHttpServletRequest request = declareLength
                ? new MockHttpServletRequest()
                : new MockHttpServletRequest() {
                    @Override
                    public int getContentLength() {
                        return -1;
                    }

                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                };
        request.setMethod("POST");
        request.setRequestURI("/messages");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
