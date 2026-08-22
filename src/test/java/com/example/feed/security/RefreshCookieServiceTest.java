package com.example.feed.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshCookieServiceTest {
    private final RefreshCookieService cookies = new RefreshCookieService(
            "__Secure-ff-refresh", "/api/auth", "", true, "Strict");

    @Test
    void writesOpaqueTokenOnlyToHardenedHttpOnlyCookie() {
        var response = new MockHttpServletResponse();

        cookies.set(response, "opaque-refresh", 3600);

        assertThat(response.getHeader("Set-Cookie"))
                .contains("__Secure-ff-refresh=opaque-refresh")
                .contains("Path=/api/auth")
                .contains("Max-Age=3600")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict");
    }

    @Test
    void clearingCookieUsesSameScopeAndImmediateExpiry() {
        var response = new MockHttpServletResponse();

        cookies.clear(response);

        assertThat(response.getHeader("Set-Cookie"))
                .contains("__Secure-ff-refresh=")
                .contains("Path=/api/auth")
                .contains("Max-Age=0");
    }
}
