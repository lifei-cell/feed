package com.example.feed.repository;

import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.FanoutPolicyChangeTrigger;
import com.example.feed.domain.FanoutPolicySource;
import com.example.feed.support.IntegrationContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FanoutPolicyAuditRepository.class)
class FanoutPolicyAuditRepositoryIntegrationTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        IntegrationContainers.registerMySql(registry);
    }

    @Autowired
    FanoutPolicyAuditRepository audits;
    @Autowired
    JdbcClient jdbc;

    @Test
    void auditHistorySupportsFiltersAndStableCursorPagination() {
        long authorId = createUser();
        audits.add(authorId, FanoutMode.PUSH, FanoutMode.PULL, null, FanoutPolicySource.AUTO,
                FanoutPolicyChangeTrigger.AUTO_SCHEDULED, "promote", 12_000L,
                10_000L, 8_000L, null, null);
        audits.add(authorId, FanoutMode.PULL, FanoutMode.PUSH, FanoutPolicySource.AUTO, null,
                FanoutPolicyChangeTrigger.AUTO_ADMIN, "revert", 7_000L,
                10_000L, 8_000L, authorId, null);
        audits.add(authorId, FanoutMode.PUSH, FanoutMode.PULL, null, FanoutPolicySource.MANUAL,
                FanoutPolicyChangeTrigger.MANUAL_SET, "operator", null,
                null, null, authorId, null);

        var first = audits.findPage(authorId, null, null, 2);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextBeforeId()).isEqualTo(first.items().getLast().id());

        var second = audits.findPage(authorId, null, first.nextBeforeId(), 2);
        assertThat(second.items()).hasSize(1);
        assertThat(second.nextBeforeId()).isNull();

        var automaticAdmin = audits.findPage(null,
                FanoutPolicyChangeTrigger.AUTO_ADMIN, null, 20);
        assertThat(automaticAdmin.items()).singleElement().satisfies(audit -> {
            assertThat(audit.authorId()).isEqualTo(authorId);
            assertThat(audit.actorId()).isEqualTo(authorId);
            assertThat(audit.previousSource()).isEqualTo(FanoutPolicySource.AUTO);
            assertThat(audit.targetSource()).isNull();
        });
    }

    private long createUser() {
        String username = "audit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc.sql("""
                INSERT INTO users(username, nickname, password_hash)
                VALUES (:username, 'Audit Test', 'not-used')
                """).param("username", username).update();
        return jdbc.sql("SELECT id FROM users WHERE username = :username")
                .param("username", username).query(Long.class).single();
    }
}
