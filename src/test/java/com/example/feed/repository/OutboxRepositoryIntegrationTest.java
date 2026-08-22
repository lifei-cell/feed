package com.example.feed.repository;

import com.example.feed.support.IntegrationContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxRepository.class)
class OutboxRepositoryIntegrationTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        IntegrationContainers.registerMySql(registry);
    }

    @Autowired
    OutboxRepository outbox;
    @Autowired
    JdbcClient jdbc;

    @Test
    void timedOutFinalAttemptBecomesFailedAndCanBeReplayed() {
        String aggregateId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO outbox_events(aggregate_id, event_type, status, attempts)
                VALUES (:aggregateId, 'POST_PUBLISHED', 'PENDING', 7)
                """).param("aggregateId", aggregateId).update();
        long eventId = jdbc.sql("SELECT id FROM outbox_events WHERE aggregate_id = :aggregateId")
                .param("aggregateId", aggregateId).query(Long.class).single();

        assertThat(outbox.markDispatching(eventId, "dispatcher:test")).isTrue();
        Instant timedOutAt = Instant.now().minusSeconds(300);
        jdbc.sql("UPDATE outbox_events SET processing_started_at = :time WHERE id = :id")
                .param("time", Timestamp.from(timedOutAt)).param("id", eventId).update();

        OutboxRepository.TimedOutEvent timedOut = outbox.findTimedOut(Instant.now().minusSeconds(120), 20)
                .stream().filter(event -> event.id() == eventId).findFirst().orElseThrow();
        assertThat(outbox.recoverTimedOut(timedOut, Instant.now(), 8)).isTrue();
        assertThat(outbox.findStatus(eventId)).contains("FAILED");

        assertThat(outbox.replayFailed(eventId)).isTrue();
        assertThat(outbox.findStatus(eventId)).contains("PENDING");
        assertThat(jdbc.sql("SELECT attempts FROM outbox_events WHERE id = :id")
                .param("id", eventId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT replay_count FROM outbox_events WHERE id = :id")
                .param("id", eventId).query(Integer.class).single()).isEqualTo(1);
    }
}
