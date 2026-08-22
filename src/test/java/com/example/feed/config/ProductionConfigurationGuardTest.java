package com.example.feed.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationGuardTest {
    @Test
    void localProfileKeepsDeveloperDefaultsAvailable() {
        var guard = new ProductionConfigurationGuard(new MockEnvironment(),
                true, true, "HMAC", false, "Lax");

        assertThatCode(guard::validate).doesNotThrowAnyException();
    }

    @Test
    void productionRejectsSharedKeyJwt() {
        var guard = new ProductionConfigurationGuard(productionEnvironment(),
                false, false, "HMAC", true, "Strict");

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_MODE");
    }

    @Test
    void productionRejectsUnsafeDeveloperFeatures() {
        var demoGuard = new ProductionConfigurationGuard(
                productionEnvironment(), true, false, "RSA", true, "Strict");
        var codeLoggingGuard = new ProductionConfigurationGuard(
                productionEnvironment(), false, true, "RSA", true, "Strict");

        assertThatThrownBy(demoGuard::validate).hasMessageContaining("DEMO_DATA_ENABLED");
        assertThatThrownBy(codeLoggingGuard::validate).hasMessageContaining("VERIFICATION_LOG_CODE");
    }

    @Test
    void productionRejectsUnsafeRefreshCookie() {
        var insecure = new ProductionConfigurationGuard(
                productionEnvironment(), false, false, "RSA", false, "Strict");
        var crossSite = new ProductionConfigurationGuard(
                productionEnvironment(), false, false, "OIDC", true, "None");

        assertThatThrownBy(insecure::validate).hasMessageContaining("REFRESH_COOKIE_SECURE");
        assertThatThrownBy(crossSite::validate).hasMessageContaining("SameSite");
    }

    @Test
    void productionAcceptsHardenedConfiguration() {
        var guard = new ProductionConfigurationGuard(
                productionEnvironment(), false, false, "RSA", true, "Strict");

        assertThatCode(guard::validate).doesNotThrowAnyException();
    }

    private static MockEnvironment productionEnvironment() {
        return new MockEnvironment().withProperty("spring.profiles.active", "prod");
    }
}
