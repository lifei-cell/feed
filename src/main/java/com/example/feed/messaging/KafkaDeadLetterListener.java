package com.example.feed.messaging;

import com.example.feed.repository.KafkaDeadLetterRepository;
import com.example.feed.repository.KafkaDeadLetterRepository.DeadLetterCapture;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

@Component
public class KafkaDeadLetterListener {
    private final KafkaDeadLetterRepository deadLetters;
    private final Counter captured;

    public KafkaDeadLetterListener(KafkaDeadLetterRepository deadLetters, MeterRegistry registry) {
        this.deadLetters = deadLetters;
        this.captured = Counter.builder("feed.kafka.dead.letters.captured")
                .description("Kafka records captured in the governed dead-letter store")
                .register(registry);
    }

    @KafkaListener(
            topics = "${feed.fanout.dlt-topic:${feed.fanout.topic}.DLT}",
            groupId = "${feed.fanout.dlt-consumer-group:friend-feed-fanout-dlt-governance-v1}",
            containerFactory = "dltKafkaListenerContainerFactory"
    )
    @Transactional
    public void capture(ConsumerRecord<String, String> record) {
        deadLetters.capture(new DeadLetterCapture(
                stringHeader(record, KafkaHeaders.DLT_ORIGINAL_TOPIC, record.topic()),
                intHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION, record.partition()),
                longHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET, record.offset()),
                record.key(), record.value(),
                stringHeader(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                        stringHeader(record, KafkaHeaders.DLT_EXCEPTION_FQCN, null)),
                stringHeader(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE, null)));
        captured.increment();
    }

    private String stringHeader(ConsumerRecord<?, ?> record, String name, String fallback) {
        Header header = record.headers().lastHeader(name);
        return header == null ? fallback : new String(header.value(), StandardCharsets.UTF_8);
    }

    private int intHeader(ConsumerRecord<?, ?> record, String name, int fallback) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value().length != Integer.BYTES
                ? fallback : ByteBuffer.wrap(header.value()).getInt();
    }

    private long longHeader(ConsumerRecord<?, ?> record, String name, long fallback) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value().length != Long.BYTES
                ? fallback : ByteBuffer.wrap(header.value()).getLong();
    }
}
