package com.example.feed.service;

import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.FanoutPolicyChangeTrigger;
import com.example.feed.repository.FanoutBackfillJobRepository;
import com.example.feed.repository.FanoutPolicyAuditRepository;
import com.example.feed.repository.FanoutPolicyRepository;
import com.example.feed.repository.PostRepository;
import com.example.feed.repository.UserRepository;
import com.example.feed.support.IntegrationContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FanoutPolicyService.class, UserRepository.class, FanoutPolicyRepository.class,
        PostRepository.class, FanoutBackfillJobRepository.class, FanoutPolicyAuditRepository.class})
class FanoutPolicyServiceIntegrationTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        IntegrationContainers.registerMySql(registry);
    }

    @Autowired
    FanoutPolicyService service;
    @Autowired
    FanoutPolicyRepository policies;
    @Autowired
    JdbcClient jdbc;

    @Test
    void repeatedAutomaticEvaluationCreatesOneAtomicPolicyTaskAndAuditChain() {
        long authorId = createUser();

        var changed = service.applyAutomatic(authorId, 12_000, 10_000, 8_000,
                FanoutPolicyChangeTrigger.AUTO_SCHEDULED, null);
        var repeated = service.applyAutomatic(authorId, 12_100, 10_000, 8_000,
                FanoutPolicyChangeTrigger.AUTO_SCHEDULED, null);

        assertThat(changed.outcome()).isEqualTo(FanoutPolicyService.AutoPolicyOutcome.CHANGED);
        assertThat(repeated.outcome()).isEqualTo(FanoutPolicyService.AutoPolicyOutcome.UNCHANGED);
        assertThat(policies.resolveMode(authorId)).isEqualTo(FanoutMode.PULL);
        assertThat(count("fanout_backfill_jobs", authorId)).isEqualTo(1);
        assertThat(count("fanout_policy_change_audit", authorId)).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void auditFailureRollsBackThePolicyMutation() {
        long authorId = createUser();
        long missingActorId = Long.MAX_VALUE;

        assertThatThrownBy(() -> service.set(authorId, FanoutMode.PULL,
                "invalid actor rollback", missingActorId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(policies.find(authorId)).isEmpty();
        assertThat(count("fanout_policy_change_audit", authorId)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAutomaticEvaluationsCreateOnlyOneBackfill() throws Exception {
        long authorId = createUser();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> applyAfterSignal(authorId, ready, start));
            var second = executor.submit(() -> applyAfterSignal(authorId, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS).outcome(),
                            second.get(10, TimeUnit.SECONDS).outcome()))
                    .containsExactlyInAnyOrder(FanoutPolicyService.AutoPolicyOutcome.CHANGED,
                            FanoutPolicyService.AutoPolicyOutcome.UNCHANGED);
        }
        assertThat(count("fanout_backfill_jobs", authorId)).isEqualTo(1);
        assertThat(count("fanout_policy_change_audit", authorId)).isEqualTo(1);
    }

    private FanoutPolicyService.AutoPolicyResult applyAfterSignal(
            long authorId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent policy test did not start");
        }
        return service.applyAutomatic(authorId, 12_000, 10_000, 8_000,
                FanoutPolicyChangeTrigger.AUTO_SCHEDULED, null);
    }

    private long createUser() {
        String username = "policy_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc.sql("""
                INSERT INTO users(username, nickname, password_hash)
                VALUES (:username, 'Policy Test', 'not-used')
                """).param("username", username).update();
        return jdbc.sql("SELECT id FROM users WHERE username = :username")
                .param("username", username).query(Long.class).single();
    }

    private long count(String table, long authorId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE author_id = :authorId")
                .param("authorId", authorId).query(Long.class).single();
    }
}
