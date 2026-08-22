package com.example.feed.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Rejects local-only security defaults when the production profile is active. */
@Component
public class ProductionConfigurationGuard {
    private final Environment environment;
    private final boolean demoDataEnabled;
    private final boolean verificationCodeLoggingEnabled;
    private final String jwtMode;
    private final boolean refreshCookieSecure;
    private final String refreshCookieSameSite;

    public ProductionConfigurationGuard(
            Environment environment,
            @Value("${feed.demo-data.enabled:false}") boolean demoDataEnabled,
            @Value("${feed.security.verification.log-code:false}") boolean verificationCodeLoggingEnabled,
            @Value("${feed.security.jwt.mode:HMAC}") String jwtMode,
            @Value("${feed.security.refresh-token.cookie.secure:false}") boolean refreshCookieSecure,
            @Value("${feed.security.refresh-token.cookie.same-site:Strict}") String refreshCookieSameSite) {
        this.environment = environment;
        this.demoDataEnabled = demoDataEnabled;
        this.verificationCodeLoggingEnabled = verificationCodeLoggingEnabled;
        this.jwtMode = jwtMode;
        this.refreshCookieSecure = refreshCookieSecure;
        this.refreshCookieSameSite = refreshCookieSameSite;
    }

    @PostConstruct
    void validate() {
        if (!environment.matchesProfiles("prod")) {
            return;
        }
        if ("HMAC".equalsIgnoreCase(jwtMode)) {
            throw new IllegalStateException(
                    "生产环境 JWT_MODE 必须使用 RSA 或 OIDC，禁止共享密钥 HMAC");
        }
        if (demoDataEnabled) {
            throw new IllegalStateException("生产环境禁止启用 DEMO_DATA_ENABLED");
        }
        if (verificationCodeLoggingEnabled) {
            throw new IllegalStateException("生产环境禁止启用 VERIFICATION_LOG_CODE");
        }
        if (!refreshCookieSecure) {
            throw new IllegalStateException("生产环境 REFRESH_COOKIE_SECURE 必须为 true");
        }
        if (!"Strict".equalsIgnoreCase(refreshCookieSameSite)) {
            throw new IllegalStateException("生产环境 Refresh Cookie SameSite 必须为 Strict");
        }
    }
}
