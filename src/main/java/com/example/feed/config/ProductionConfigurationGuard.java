package com.example.feed.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Rejects local-only security defaults when the production profile is active. */
@Component
public class ProductionConfigurationGuard {
    static final String LOCAL_JWT_SECRET = "local-development-secret-change-before-production";

    private final Environment environment;
    private final String jwtSecret;
    private final boolean demoDataEnabled;
    private final boolean verificationCodeLoggingEnabled;

    public ProductionConfigurationGuard(
            Environment environment,
            @Value("${feed.security.jwt.secret:" + LOCAL_JWT_SECRET + "}") String jwtSecret,
            @Value("${feed.demo-data.enabled:false}") boolean demoDataEnabled,
            @Value("${feed.security.verification.log-code:false}") boolean verificationCodeLoggingEnabled) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.demoDataEnabled = demoDataEnabled;
        this.verificationCodeLoggingEnabled = verificationCodeLoggingEnabled;
    }

    @PostConstruct
    void validate() {
        if (!environment.matchesProfiles("prod")) {
            return;
        }
        if (jwtSecret == null || jwtSecret.length() < 32 || LOCAL_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "生产环境 JWT_SECRET 必须是至少 32 字节的随机值，且不能使用本地默认值");
        }
        if (demoDataEnabled) {
            throw new IllegalStateException("生产环境禁止启用 DEMO_DATA_ENABLED");
        }
        if (verificationCodeLoggingEnabled) {
            throw new IllegalStateException("生产环境禁止启用 VERIFICATION_LOG_CODE");
        }
    }
}
