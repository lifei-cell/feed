package com.example.feed.repository;

import com.example.feed.domain.FanoutBackfillStatus;
import com.example.feed.domain.FanoutMode;
import com.example.feed.support.IntegrationContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FanoutBackfillJobRepository.class)
class FanoutBackfillJobRepositoryIntegrationTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        IntegrationContainers.registerMySql(registry);
    }

    @Autowired
    FanoutBackfillJobRepository jobs;
    @Autowired
    JdbcClient jdbc;

    @Test
    void taskCanBeClaimedCheckpointedAndCompleted() {
        String username = "bf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc.sql("""
                INSERT INTO users(username, nickname, password_hash)
                VALUES (:username, 'Backfill Test', 'not-used')
                """).param("username", username).update();
        long authorId = jdbc.sql("SELECT id FROM users WHERE username = :username")
                .param("username", username).query(Long.class).single();

        var created = jobs.create(UUID.randomUUID().toString(), authorId, FanoutMode.PUSH,
                FanoutMode.PULL, "integration test", null, 2, authorId);
        assertThat(created.status()).isEqualTo(FanoutBackfillStatus.PENDING);

        var claimed = jobs.claimNext("integration-worker").orElseThrow();
        Instant cursor = Instant.parse("2026-08-16T00:00:00Z");
        jobs.completeBatch(claimed.id(), "integration-worker", 1, 0,
                cursor, "post-1", false);

        var checkpointed = jobs.find(claimed.id()).orElseThrow();
        assertThat(checkpointed.status()).isEqualTo(FanoutBackfillStatus.PENDING);
        assertThat(checkpointed.processedPosts()).isEqualTo(1);
        assertThat(checkpointed.lastPostId()).isEqualTo("post-1");

        var claimedAgain = jobs.claimNext("integration-worker").orElseThrow();
        jobs.completeBatch(claimedAgain.id(), "integration-worker", 1, 0,
                cursor.minusSeconds(1), "post-0", true);
        assertThat(jobs.find(claimed.id()).orElseThrow().status())
                .isEqualTo(FanoutBackfillStatus.COMPLETED);
    }
}
