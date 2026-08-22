package com.example.feed.repository;

import com.example.feed.repository.KafkaDeadLetterRepository.DeadLetterCapture;
import com.example.feed.support.IntegrationContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({KafkaDeadLetterRepository.class, UserRepository.class})
class KafkaDeadLetterRepositoryIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        IntegrationContainers.registerMySql(registry);
    }

    @Autowired
    KafkaDeadLetterRepository deadLetters;
    @Autowired
    UserRepository users;

    @Test
    void captureIsIdempotentAndResolutionIsAudited() {
        String topic = "integration.poison." + System.nanoTime();
        var capture = new DeadLetterCapture(topic, 2, 17, "key", "payload",
                "JsonParseException", "bad json");
        deadLetters.capture(capture);
        deadLetters.capture(capture);

        var record = deadLetters.find("PENDING", 10).stream()
                .filter(item -> topic.equals(item.originalTopic())).findFirst().orElseThrow();
        assertThat(record.occurrenceCount()).isEqualTo(2);

        long operator = users.create("dlt_admin_" + System.nanoTime(), "DLT Admin", "ACCOUNT_DISABLED");
        assertThat(deadLetters.discard(record.id(), operator, "invalid legacy schema")).isTrue();
        assertThat(deadLetters.findById(record.id()).orElseThrow())
                .satisfies(resolved -> {
                    assertThat(resolved.status()).isEqualTo("DISCARDED");
                    assertThat(resolved.resolvedBy()).isEqualTo(operator);
                    assertThat(resolved.resolutionNote()).isEqualTo("invalid legacy schema");
                });
    }
}
