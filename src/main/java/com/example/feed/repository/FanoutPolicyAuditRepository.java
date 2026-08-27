package com.example.feed.repository;

import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.FanoutPolicyChangeTrigger;
import com.example.feed.domain.FanoutPolicySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Repository
public class FanoutPolicyAuditRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, author_id, previous_mode, target_mode, previous_source, target_source,
                   trigger_type, reason, evaluated_friend_count, pull_threshold, push_threshold,
                   actor_id, backfill_job_id, created_at
              FROM fanout_policy_change_audit
            """;

    private final JdbcClient jdbc;

    public FanoutPolicyAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public FanoutPolicyAudit add(long authorId, FanoutMode previousMode, FanoutMode targetMode,
                                 FanoutPolicySource previousSource,
                                 FanoutPolicySource targetSource,
                                 FanoutPolicyChangeTrigger triggerType, String reason,
                                 Long evaluatedFriendCount, Long pullThreshold,
                                 Long pushThreshold, Long actorId, String backfillJobId) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO fanout_policy_change_audit(
                    author_id, previous_mode, target_mode, previous_source, target_source,
                    trigger_type, reason, evaluated_friend_count, pull_threshold, push_threshold,
                    actor_id, backfill_job_id)
                VALUES (:authorId, :previousMode, :targetMode, :previousSource, :targetSource,
                        :triggerType, :reason, :friendCount, :pullThreshold, :pushThreshold,
                        :actorId, :backfillJobId)
                """).param("authorId", authorId).param("previousMode", previousMode.name())
                .param("targetMode", targetMode.name())
                .param("previousSource", name(previousSource)).param("targetSource", name(targetSource))
                .param("triggerType", triggerType.name()).param("reason", normalize(reason))
                .param("friendCount", evaluatedFriendCount).param("pullThreshold", pullThreshold)
                .param("pushThreshold", pushThreshold).param("actorId", actorId)
                .param("backfillJobId", backfillJobId).update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("扩散策略审计写入后未返回主键");
        }
        return find(key.longValue());
    }

    public FanoutPolicyAuditPage findPage(Long authorId, FanoutPolicyChangeTrigger triggerType,
                                          Long beforeId, int size) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append(" WHERE 1 = 1");
        if (authorId != null) {
            sql.append(" AND author_id = :authorId");
        }
        if (triggerType != null) {
            sql.append(" AND trigger_type = :triggerType");
        }
        if (beforeId != null) {
            sql.append(" AND id < :beforeId");
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("limit", size + 1);
        if (authorId != null) {
            statement = statement.param("authorId", authorId);
        }
        if (triggerType != null) {
            statement = statement.param("triggerType", triggerType.name());
        }
        if (beforeId != null) {
            statement = statement.param("beforeId", beforeId);
        }
        List<FanoutPolicyAudit> rows = statement.query(this::map).list();
        boolean hasMore = rows.size() > size;
        List<FanoutPolicyAudit> items = hasMore ? List.copyOf(rows.subList(0, size)) : rows;
        Long nextBeforeId = hasMore ? items.getLast().id() : null;
        return new FanoutPolicyAuditPage(items, nextBeforeId);
    }

    private FanoutPolicyAudit find(long id) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE id = :id").param("id", id)
                .query(this::map).optional()
                .orElseThrow(() -> new IllegalStateException("扩散策略审计写入后无法读取"));
    }

    private FanoutPolicyAudit map(ResultSet rs, int rowNum) throws SQLException {
        return new FanoutPolicyAudit(
                rs.getLong("id"), rs.getLong("author_id"),
                FanoutMode.valueOf(rs.getString("previous_mode")),
                FanoutMode.valueOf(rs.getString("target_mode")),
                source(rs.getString("previous_source")), source(rs.getString("target_source")),
                FanoutPolicyChangeTrigger.valueOf(rs.getString("trigger_type")),
                rs.getString("reason"), rs.getObject("evaluated_friend_count", Long.class),
                rs.getObject("pull_threshold", Long.class),
                rs.getObject("push_threshold", Long.class),
                rs.getObject("actor_id", Long.class), rs.getString("backfill_job_id"),
                rs.getTimestamp("created_at").toInstant());
    }

    private FanoutPolicySource source(String value) {
        return value == null ? null : FanoutPolicySource.valueOf(value);
    }

    private String name(FanoutPolicySource value) {
        return value == null ? null : value.name();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record FanoutPolicyAudit(
            long id, long authorId, FanoutMode previousMode, FanoutMode targetMode,
            FanoutPolicySource previousSource, FanoutPolicySource targetSource,
            FanoutPolicyChangeTrigger triggerType, String reason, Long evaluatedFriendCount,
            Long pullThreshold, Long pushThreshold, Long actorId, String backfillJobId,
            Instant createdAt) {
    }

    public record FanoutPolicyAuditPage(List<FanoutPolicyAudit> items, Long nextBeforeId) {
    }
}
