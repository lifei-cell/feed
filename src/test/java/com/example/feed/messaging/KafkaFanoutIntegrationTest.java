package com.example.feed.messaging;

import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.Visibility;
import com.example.feed.repository.FanoutPolicyRepository;
import com.example.feed.repository.FeedInboxRepository;
import com.example.feed.repository.RelationshipRepository;
import com.example.feed.repository.UserRepository;
import com.example.feed.repository.KafkaDeadLetterRepository;
import com.example.feed.service.PostService;
import com.example.feed.service.FeedQueryService;
import com.example.feed.service.OutboxDispatcher;
import com.example.feed.support.IntegrationContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.admin.fail-fast=true",
        "feed.fanout.dispatch-delay-ms=50",
        "feed.fanout.recovery-delay-ms=100",
        "feed.security.jwt.secret=integration-test-secret-with-at-least-32-bytes"
})
class KafkaFanoutIntegrationTest {
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        IntegrationContainers.registerMySql(registry);
        IntegrationContainers.registerKafka(registry);
        IntegrationContainers.registerRedis(registry);
    }

    @Autowired
    UserRepository users;
    @Autowired
    RelationshipRepository relationships;
    @Autowired
    PostService posts;
    @Autowired
    FeedInboxRepository inbox;
    @Autowired
    FanoutPolicyRepository fanoutPolicies;
    @Autowired
    FeedQueryService feed;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    KafkaDeadLetterRepository deadLetters;
    @Value("${feed.fanout.topic}")
    String topic;

    @Test
    void outboxTravelsThroughKafkaAndFansOutExactlyOnce() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        long author = users.create("kafka_author_" + suffix, "Kafka Author", "ACCOUNT_DISABLED");
        long friend = users.create("kafka_friend_" + suffix, "Kafka Friend", "ACCOUNT_DISABLED");
        relationships.addFriend(author, friend);

        UUID key = UUID.randomUUID();
        var first = posts.publish(author, key, "through kafka", Visibility.ALL_FRIENDS, Set.of());
        var duplicate = posts.publish(author, key, "through kafka", Visibility.ALL_FRIENDS, Set.of());
        assertThat(duplicate.id()).isEqualTo(first.id());
        dispatcher.dispatch();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)
                && inbox.findPage(friend, null, 10).stream().noneMatch(row -> row.postId().equals(first.id()))) {
            Thread.sleep(100);
        }

        assertThat(inbox.findPage(friend, null, 10)).filteredOn(row -> row.postId().equals(first.id()))
                .hasSize(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM posts WHERE author_id = :author AND idempotency_key = :key")
                .param("author", author).param("key", key.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM outbox_events WHERE aggregate_id = :postId")
                .param("postId", first.id()).query(String.class).single()).isEqualTo("PROCESSED");
    }

    @Test
    void pullPostCompletesOutboxWithoutFriendInboxAndIsMergedAtReadTime() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        long author = users.create("pull_author_" + suffix, "Pull Author", "ACCOUNT_DISABLED");
        long friend = users.create("pull_friend_" + suffix, "Pull Friend", "ACCOUNT_DISABLED");
        relationships.addFriend(author, friend);
        fanoutPolicies.upsert(author, FanoutMode.PULL, "integration test");

        var post = posts.publish(author, UUID.randomUUID(), "pull through kafka",
                Visibility.ALL_FRIENDS, Set.of());
        dispatcher.dispatch();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)
                && !"PROCESSED".equals(jdbc.sql(
                                "SELECT status FROM outbox_events WHERE aggregate_id = :postId")
                        .param("postId", post.id()).query(String.class).single())) {
            Thread.sleep(100);
        }

        assertThat(inbox.findPage(friend, null, 10))
                .noneMatch(row -> row.postId().equals(post.id()));
        assertThat(feed.getFeed(friend, null, 10).items())
                .anyMatch(item -> item.id().equals(post.id()));
        assertThat(jdbc.sql("SELECT delivery_mode FROM posts WHERE id = :postId")
                .param("postId", post.id()).query(String.class).single()).isEqualTo("PULL");
        var outboxState = jdbc.sql("""
                        SELECT status, attempts, last_error, processor_id
                          FROM outbox_events WHERE aggregate_id = :postId
                        """).param("postId", post.id()).query().singleRow();
        assertThat(outboxState.get("status"))
                .as("outbox state: %s", outboxState)
                .isEqualTo("PROCESSED");
    }

    @Test
    void poisonMessageIsCapturedInGovernedDeadLetterStore() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        kafka.send(topic, key, "{not-valid-json").get();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)
                && deadLetters.find("PENDING", 20).stream()
                .noneMatch(item -> key.equals(item.messageKey()))) {
            Thread.sleep(100);
        }

        assertThat(deadLetters.find("PENDING", 20))
                .anySatisfy(item -> {
                    assertThat(item.messageKey()).isEqualTo(key);
                    assertThat(item.originalTopic()).isEqualTo(topic);
                    assertThat(item.payload()).isEqualTo("{not-valid-json");
                    assertThat(item.exceptionClass()).contains("Json");
                });
    }
}
