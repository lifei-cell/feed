CREATE TABLE kafka_dead_letters (
    id BIGINT NOT NULL AUTO_INCREMENT,
    original_topic VARCHAR(255) NOT NULL,
    original_partition INT NOT NULL,
    original_offset BIGINT NOT NULL,
    message_key VARCHAR(255) NULL,
    payload MEDIUMTEXT NOT NULL,
    exception_class VARCHAR(255) NULL,
    exception_message VARCHAR(1000) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    occurrence_count INT NOT NULL DEFAULT 1,
    replay_count INT NOT NULL DEFAULT 0,
    last_seen_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_replayed_at TIMESTAMP(6) NULL,
    resolved_at TIMESTAMP(6) NULL,
    resolved_by BIGINT NULL,
    resolution_note VARCHAR(255) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_kafka_dead_letter_source UNIQUE (
        original_topic, original_partition, original_offset
    ),
    INDEX idx_kafka_dead_letter_status (status, created_at),
    CONSTRAINT fk_kafka_dead_letter_resolver FOREIGN KEY (resolved_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
