package com.example.feed.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestObservabilityFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestObservabilityFilter.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private final String userIdClaim;

    public RequestObservabilityFilter(
            @Value("${feed.security.jwt.user-id-claim:sub}") String userIdClaim) {
        this.userIdClaim = userIdClaim;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = requestId(request.getHeader(REQUEST_ID_HEADER));
        long started = System.nanoTime();
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
            try {
                filterChain.doFilter(request, response);
            } finally {
                long durationMillis = (System.nanoTime() - started) / 1_000_000;
                log.atInfo()
                        .addKeyValue("userId", authenticatedUserId())
                        .log("http_request method={} path={} status={} durationMs={} remoteAddress={}",
                                request.getMethod(), request.getRequestURI(), response.getStatus(), durationMillis,
                                request.getRemoteAddr());
            }
        }
    }

    private String requestId(String candidate) {
        return candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()
                ? candidate : UUID.randomUUID().toString();
    }

    private String authenticatedUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            Object claim = jwt.getClaim(userIdClaim);
            return claim == null ? "unknown" : String.valueOf(claim);
        }
        return "anonymous";
    }
}
