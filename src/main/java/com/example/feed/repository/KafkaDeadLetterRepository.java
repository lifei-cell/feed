package com.example.feed.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class KafkaDeadLetterRepository {
    private final JdbcClient jdbc;

    public KafkaDeadLetterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void capture(DeadLetterCapture capture) {
        jdbc.sql("""
                INSERT INTO kafka_dead_letters(
                    original_topic, original_partition, original_offset, message_key, payload,
                    exception_class, exception_message
                ) VALUES (
                    :topic, :partition, :offset, :messageKey, :payload, :exceptionClass, :exceptionMessage
                )
                ON DUPLICATE KEY UPDATE
                    occurrence_count = occurrence_count + 1,
                    last_seen_at = CURRENT_TIMESTAMP(6),
                    exception_class = VALUES(exception_class),
                    exception_message = VALUES(exception_message)
                """)
                .param("topic", capture.originalTopic())
                .param("partition", capture.originalPartition())
                .param("offset", capture.originalOffset())
                .param("messageKey", truncate(capture.messageKey(), 255))
                .param("payload", truncate(capture.payload(), 1_000_000))
                .param("exceptionClass", truncate(capture.exceptionClass(), 255))
                .param("exceptionMessage", truncate(capture.exceptionMessage(), 1000))
                .update();
    }

    public List<DeadLetter> find(String status, int limit) {
        String sql = """
                SELECT id, original_topic, original_partition, original_offset, message_key, payload,
                       exception_class, exception_message, status, occurrence_count, replay_count,
                       last_seen_at, last_replayed_at, resolved_at, resolved_by, resolution_note,
                       created_at
                  FROM kafka_dead_letters
                """ + (status == null || status.isBlank() ? "" : " WHERE status = :status")
                + " ORDER BY created_at DESC LIMIT :limit";
        var spec = jdbc.sql(sql).param("limit", limit);
        if (status != null && !status.isBlank()) {
            spec = spec.param("status", status);
        }
        return spec.query(this::map).list();
    }

    public Optional<DeadLetter> findById(long id) {
        return jdbc.sql("""
                SELECT id, original_topic, original_partition, original_offset, message_key, payload,
                       exception_class, exception_message, status, occurrence_count, replay_count,
                       last_seen_at, last_replayed_at, resolved_at, resolved_by, resolution_note,
                       created_at
                  FROM kafka_dead_letters WHERE id = :id
                """).param("id", id).query(this::map).optional();
    }

    public boolean markReplayed(long id, long userId) {
        return jdbc.sql("""
                UPDATE kafka_dead_letters
                   SET status = 'REPLAYED', replay_count = replay_count + 1,
                       last_replayed_at = CURRENT_TIMESTAMP(6), resolved_at = CURRENT_TIMESTAMP(6),
                       resolved_by = :userId, resolution_note = 'replayed to original topic'
                 WHERE id = :id AND status = 'PENDING'
                """).param("id", id).param("userId", userId).update() == 1;
    }

    public boolean discard(long id, long userId, String note) {
        return jdbc.sql("""
                UPDATE kafka_dead_letters
                   SET status = 'DISCARDED', resolved_at = CURRENT_TIMESTAMP(6),
                       resolved_by = :userId, resolution_note = :note
                 WHERE id = :id AND status = 'PENDING'
                """).param("id", id).param("userId", userId)
                .param("note", truncate(note, 255)).update() == 1;
    }

    public long countPending() {
        return jdbc.sql("SELECT COUNT(*) FROM kafka_dead_letters WHERE status = 'PENDING'")
                .query(Long.class).single();
    }

    public double oldestPendingAgeSeconds() {
        Long micros = jdbc.sql("""
                SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), CURRENT_TIMESTAMP(6)), 0)
                  FROM kafka_dead_letters WHERE status = 'PENDING'
                """).query(Long.class).single();
        return micros / 1_000_000.0;
    }

    private DeadLetter map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DeadLetter(rs.getLong("id"), rs.getString("original_topic"),
                rs.getInt("original_partition"), rs.getLong("original_offset"),
                rs.getString("message_key"), rs.getString("payload"),
                rs.getString("exception_class"), rs.getString("exception_message"),
                rs.getString("status"), rs.getInt("occurrence_count"), rs.getInt("replay_count"),
                instant(rs, "last_seen_at"), instant(rs, "last_replayed_at"),
                instant(rs, "resolved_at"), rs.getObject("resolved_by", Long.class),
                rs.getString("resolution_note"), instant(rs, "created_at"));
    }

    private Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record DeadLetterCapture(String originalTopic, int originalPartition, long originalOffset,
                                    String messageKey, String payload, String exceptionClass,
                                    String exceptionMessage) {
    }

    public record DeadLetter(long id, String originalTopic, int originalPartition, long originalOffset,
                             String messageKey, String payload, String exceptionClass,
                             String exceptionMessage, String status, int occurrenceCount,
                             int replayCount, Instant lastSeenAt, Instant lastReplayedAt,
                             Instant resolvedAt, Long resolvedBy, String resolutionNote,
                             Instant createdAt) {
    }
}
