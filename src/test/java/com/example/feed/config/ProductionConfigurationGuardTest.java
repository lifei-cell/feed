package com.example.feed.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationGuardTest {
    @Test
    void localProfileKeepsDeveloperDefaultsAvailable() {
        var guard = new ProductionConfigurationGuard(new MockEnvironment(),
                ProductionConfigurationGuard.LOCAL_JWT_SECRET, true, true);

        assertThatCode(guard::validate).doesNotThrowAnyException();
    }

    @Test
    void productionRejectsDefaultSecret() {
        var guard = new ProductionConfigurationGuard(productionEnvironment(),
                ProductionConfigurationGuard.LOCAL_JWT_SECRET, false, false);

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void productionRejectsUnsafeDeveloperFeatures() {
        var demoGuard = new ProductionConfigurationGuard(productionEnvironment(), validSecret(), true, false);
        var codeLoggingGuard = new ProductionConfigurationGuard(
                productionEnvironment(), validSecret(), false, true);

        assertThatThrownBy(demoGuard::validate).hasMessageContaining("DEMO_DATA_ENABLED");
        assertThatThrownBy(codeLoggingGuard::validate).hasMessageContaining("VERIFICATION_LOG_CODE");
    }

    @Test
    void productionAcceptsHardenedConfiguration() {
        var guard = new ProductionConfigurationGuard(productionEnvironment(), validSecret(), false, false);

        assertThatCode(guard::validate).doesNotThrowAnyException();
    }

    private static MockEnvironment productionEnvironment() {
        return new MockEnvironment().withProperty("spring.profiles.active", "prod");
    }

    private static String validSecret() {
        return "a-production-secret-with-more-than-32-random-bytes";
    }
}
