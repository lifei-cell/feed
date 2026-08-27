CREATE TABLE fanout_policy_change_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    author_id BIGINT NOT NULL,
    previous_mode VARCHAR(16) NOT NULL,
    target_mode VARCHAR(16) NOT NULL,
    previous_source VARCHAR(16) NULL,
    target_source VARCHAR(16) NULL,
    trigger_type VARCHAR(32) NOT NULL,
    reason VARCHAR(128) NULL,
    evaluated_friend_count BIGINT NULL,
    pull_threshold BIGINT NULL,
    push_threshold BIGINT NULL,
    actor_id BIGINT NULL,
    backfill_job_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_fanout_policy_audit_author FOREIGN KEY (author_id) REFERENCES users(id),
    CONSTRAINT fk_fanout_policy_audit_actor FOREIGN KEY (actor_id) REFERENCES users(id),
    CONSTRAINT fk_fanout_policy_audit_backfill FOREIGN KEY (backfill_job_id)
        REFERENCES fanout_backfill_jobs(id),
    INDEX idx_fanout_policy_audit_author (author_id, id DESC),
    INDEX idx_fanout_policy_audit_trigger (trigger_type, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
