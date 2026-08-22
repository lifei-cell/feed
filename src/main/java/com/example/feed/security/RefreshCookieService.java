package com.example.feed.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RefreshCookieService {
    private final String name;
    private final String path;
    private final String domain;
    private final boolean secure;
    private final String sameSite;

    public RefreshCookieService(
            @Value("${feed.security.refresh-token.cookie.name:ff-refresh}") String name,
            @Value("${feed.security.refresh-token.cookie.path:/api/auth}") String path,
            @Value("${feed.security.refresh-token.cookie.domain:}") String domain,
            @Value("${feed.security.refresh-token.cookie.secure:false}") boolean secure,
            @Value("${feed.security.refresh-token.cookie.same-site:Strict}") String sameSite) {
        this.name = name;
        this.path = path;
        this.domain = domain;
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public void set(HttpServletResponse response, String token, long maxAgeSeconds) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie(token, Duration.ofSeconds(Math.max(1, maxAgeSeconds))).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(path)
                .maxAge(maxAge);
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }
        return builder.build();
    }
}
