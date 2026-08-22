package com.example.feed.service;

import com.example.feed.api.ConflictException;
import com.example.feed.api.NotFoundException;
import com.example.feed.repository.KafkaDeadLetterRepository;
import com.example.feed.repository.KafkaDeadLetterRepository.DeadLetter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class KafkaDeadLetterAdminService {
    private static final Set<String> STATUSES = Set.of("PENDING", "REPLAYED", "DISCARDED");

    private final KafkaDeadLetterRepository deadLetters;
    private final KafkaTemplate<String, String> kafka;
    private final Duration sendTimeout;

    public KafkaDeadLetterAdminService(KafkaDeadLetterRepository deadLetters,
                                       KafkaTemplate<String, String> kafka,
                                       @Value("${feed.fanout.send-timeout:10s}") Duration sendTimeout) {
        this.deadLetters = deadLetters;
        this.kafka = kafka;
        this.sendTimeout = sendTimeout;
    }

    public List<DeadLetter> list(String status, int size) {
        String normalized = status == null || status.isBlank()
                ? null : status.strip().toUpperCase(java.util.Locale.ROOT);
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw new com.example.feed.api.BadRequestException("不支持的死信状态: " + status);
        }
        return deadLetters.find(normalized, Math.max(1, Math.min(size, 100)));
    }

    public DeadLetter get(long id) {
        return deadLetters.findById(id)
                .orElseThrow(() -> new NotFoundException("Kafka 死信不存在: " + id));
    }

    public void replay(long id, long operatorId) {
        DeadLetter record = get(id);
        requirePending(record);
        try {
            kafka.send(record.originalTopic(), record.messageKey(), record.payload())
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            throw new ConflictException("Kafka 死信重放失败，请检查 Broker 后重试");
        }
        if (!deadLetters.markReplayed(id, operatorId)) {
            throw new ConflictException("Kafka 死信已被其他操作员处理");
        }
    }

    public void discard(long id, long operatorId, String note) {
        DeadLetter record = get(id);
        requirePending(record);
        String reason = note == null || note.isBlank() ? "discarded by operator" : note.strip();
        if (!deadLetters.discard(id, operatorId, reason)) {
            throw new ConflictException("Kafka 死信已被其他操作员处理");
        }
    }

    private void requirePending(DeadLetter record) {
        if (!"PENDING".equals(record.status())) {
            throw new ConflictException("只有 PENDING Kafka 死信可以处理，当前状态: " + record.status());
        }
    }
}
