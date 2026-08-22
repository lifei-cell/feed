package com.example.feed.observability;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestObservabilityFilterTest {
    private final RequestObservabilityFilter filter = new RequestObservabilityFilter("sub");

    @Test
    void preservesSafeRequestIdAndReturnsItToCaller() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/feed");
        request.addHeader("X-Request-Id", "client-request-123");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("client-request-123");
    }

    @Test
    void replacesUnsafeRequestId() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/feed");
        request.addHeader("X-Request-Id", "bad id with spaces");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Request-Id"))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
