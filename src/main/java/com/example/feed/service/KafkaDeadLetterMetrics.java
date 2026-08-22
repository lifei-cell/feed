package com.example.feed.service;

import com.example.feed.repository.KafkaDeadLetterRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class KafkaDeadLetterMetrics {
    private static final Logger log = LoggerFactory.getLogger(KafkaDeadLetterMetrics.class);
    private final KafkaDeadLetterRepository deadLetters;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeMillis = new AtomicLong();

    public KafkaDeadLetterMetrics(KafkaDeadLetterRepository deadLetters, MeterRegistry registry) {
        this.deadLetters = deadLetters;
        Gauge.builder("feed.kafka.dead.letters.pending", pending, AtomicLong::get)
                .description("Governed Kafka dead-letter records awaiting operator resolution")
                .register(registry);
        Gauge.builder("feed.kafka.dead.letters.oldest.age.seconds", oldestAgeMillis,
                        value -> value.get() / 1000.0)
                .description("Age of the oldest unresolved Kafka dead letter")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${feed.fanout.metrics-delay-ms:5000}")
    public void refresh() {
        try {
            pending.set(deadLetters.countPending());
            oldestAgeMillis.set(Math.round(deadLetters.oldestPendingAgeSeconds() * 1000));
        } catch (RuntimeException exception) {
            log.warn("Failed to refresh Kafka dead-letter metrics", exception);
        }
    }
}
