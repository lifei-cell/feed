package com.example.feed.service;

import com.example.feed.api.ConflictException;
import com.example.feed.repository.KafkaDeadLetterRepository;
import com.example.feed.repository.KafkaDeadLetterRepository.DeadLetter;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaDeadLetterAdminServiceTest {
    private final KafkaDeadLetterRepository deadLetters = mock(KafkaDeadLetterRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final KafkaDeadLetterAdminService service = new KafkaDeadLetterAdminService(
            deadLetters, kafka, Duration.ofSeconds(1));

    @Test
    void replayPublishesOriginalRecordThenAuditsResolution() {
        DeadLetter record = record("PENDING");
        when(deadLetters.findById(7)).thenReturn(Optional.of(record));
        when(kafka.send("feed.post-published.v1", "key", "payload"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(deadLetters.markReplayed(7, 42)).thenReturn(true);

        service.replay(7, 42);

        verify(kafka).send("feed.post-published.v1", "key", "payload");
        verify(deadLetters).markReplayed(7, 42);
    }

    @Test
    void resolvedRecordCannotBeReplayedAgain() {
        when(deadLetters.findById(7)).thenReturn(Optional.of(record("DISCARDED")));

        assertThatThrownBy(() -> service.replay(7, 42))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PENDING");
    }

    private DeadLetter record(String status) {
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        return new DeadLetter(7, "feed.post-published.v1", 1, 9, "key", "payload",
                "JsonParseException", "bad json", status, 1, 0, now, null,
                null, null, null, now);
    }
}
