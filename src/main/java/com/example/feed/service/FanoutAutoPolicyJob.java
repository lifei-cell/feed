package com.example.feed.service;

import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.FanoutPolicyChangeTrigger;
import com.example.feed.repository.RelationshipRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class FanoutAutoPolicyJob {
    private static final Logger log = LoggerFactory.getLogger(FanoutAutoPolicyJob.class);

    private final RelationshipRepository relationships;
    private final FanoutPolicyService policies;
    private final boolean enabled;
    private final long pullThreshold;
    private final long pushThreshold;
    private final int batchSize;
    private final int maxBatches;
    private final Counter promoted;
    private final Counter reverted;
    private final Counter backfillsCreated;
    private final Counter blocked;
    private final Counter failures;
    private final AtomicLong lastEvaluated = new AtomicLong();

    public FanoutAutoPolicyJob(RelationshipRepository relationships, FanoutPolicyService policies,
                               MeterRegistry registry,
                               @Value("${feed.fanout.auto-policy.enabled:true}") boolean enabled,
                               @Value("${feed.fanout.auto-policy.pull-threshold:10000}") long pullThreshold,
                               @Value("${feed.fanout.auto-policy.push-threshold:8000}") long pushThreshold,
                               @Value("${feed.fanout.auto-policy.batch-size:500}") int batchSize,
                               @Value("${feed.fanout.auto-policy.max-batches:20}") int maxBatches) {
        if (pushThreshold > pullThreshold) {
            throw new IllegalArgumentException("自动 PUSH 阈值不能大于 PULL 阈值");
        }
        this.relationships = relationships;
        this.policies = policies;
        this.enabled = enabled;
        this.pullThreshold = pullThreshold;
        this.pushThreshold = pushThreshold;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.promoted = Counter.builder("feed.fanout.auto.promoted").register(registry);
        this.reverted = Counter.builder("feed.fanout.auto.reverted").register(registry);
        this.backfillsCreated = Counter.builder("feed.fanout.auto.backfill.created").register(registry);
        this.blocked = Counter.builder("feed.fanout.auto.blocked").register(registry);
        this.failures = Counter.builder("feed.fanout.auto.failed").register(registry);
    }

    @Scheduled(fixedDelayString = "${feed.fanout.auto-policy.delay-ms:60000}",
            initialDelayString = "${feed.fanout.auto-policy.initial-delay-ms:15000}")
    public void scheduledRefresh() {
        if (enabled) {
            refresh(FanoutPolicyChangeTrigger.AUTO_SCHEDULED, null);
        }
    }

    public Snapshot refresh() {
        return refresh(FanoutPolicyChangeTrigger.AUTO_SCHEDULED, null);
    }

    public Snapshot refreshNow(long actorId) {
        return refresh(FanoutPolicyChangeTrigger.AUTO_ADMIN, actorId);
    }

    private Snapshot refresh(FanoutPolicyChangeTrigger triggerType, Long actorId) {
        long afterUserId = 0;
        long evaluated = 0;
        long promotedCount = 0;
        long revertedCount = 0;
        long backfillCount = 0;
        long blockedCount = 0;
        long failureCount = 0;
        try {
            for (int batch = 0; batch < maxBatches; batch++) {
                var counts = relationships.findConnectionCountsAfter(afterUserId, batchSize);
                if (counts.isEmpty()) {
                    break;
                }
                for (var count : counts) {
                    evaluated++;
                    try {
                        var result = policies.applyAutomatic(count.userId(), count.friendCount(),
                                pullThreshold, pushThreshold, triggerType, actorId);
                        if (result.outcome() == FanoutPolicyService.AutoPolicyOutcome.CHANGED) {
                            backfillsCreated.increment();
                            backfillCount++;
                            if (result.targetMode() == FanoutMode.PULL) {
                                promoted.increment();
                                promotedCount++;
                            } else {
                                reverted.increment();
                                revertedCount++;
                            }
                        } else if (result.outcome()
                                == FanoutPolicyService.AutoPolicyOutcome.BLOCKED_ACTIVE_BACKFILL) {
                            blocked.increment();
                            blockedCount++;
                            log.info("Automatic fanout policy change deferred for author {} because a backfill is active",
                                    count.userId());
                        }
                    } catch (RuntimeException exception) {
                        failures.increment();
                        failureCount++;
                        log.warn("Automatic fanout policy evaluation failed for author {}",
                                count.userId(), exception);
                    }
                }
                afterUserId = counts.getLast().userId();
                if (counts.size() < batchSize) {
                    break;
                }
            }
            lastEvaluated.set(evaluated);
        } catch (RuntimeException exception) {
            failures.increment();
            failureCount++;
            log.warn("Automatic fanout policy evaluation failed", exception);
        }
        return new Snapshot(enabled, pullThreshold, pushThreshold, evaluated, promotedCount,
                revertedCount, backfillCount, blockedCount, failureCount, lastEvaluated.get());
    }

    public Snapshot snapshot() {
        return new Snapshot(enabled, pullThreshold, pushThreshold, 0, 0, 0,
                0, 0, 0, lastEvaluated.get());
    }

    public record Snapshot(boolean enabled, long pullThreshold, long pushThreshold,
                           long evaluatedThisRun, long promotedThisRun, long revertedThisRun,
                           long backfillsCreatedThisRun, long blockedThisRun,
                           long failuresThisRun, long lastEvaluated) {
    }
}
