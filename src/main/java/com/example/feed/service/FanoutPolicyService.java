package com.example.feed.service;

import com.example.feed.api.ConflictException;
import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.FanoutPolicyChangeTrigger;
import com.example.feed.domain.FanoutPolicySource;
import com.example.feed.repository.FanoutPolicyAuditRepository;
import com.example.feed.repository.FanoutPolicyRepository;
import com.example.feed.repository.FanoutPolicyRepository.FanoutPolicy;
import com.example.feed.repository.FanoutBackfillJobRepository;
import com.example.feed.repository.FanoutBackfillJobRepository.FanoutBackfillJob;
import com.example.feed.repository.PostRepository;
import com.example.feed.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class FanoutPolicyService {
    private final UserRepository users;
    private final FanoutPolicyRepository policies;
    private final PostRepository posts;
    private final FanoutBackfillJobRepository backfills;
    private final FanoutPolicyAuditRepository audits;

    public FanoutPolicyService(UserRepository users, FanoutPolicyRepository policies,
                               PostRepository posts,
                               FanoutBackfillJobRepository backfills,
                               FanoutPolicyAuditRepository audits) {
        this.users = users;
        this.policies = policies;
        this.posts = posts;
        this.backfills = backfills;
        this.audits = audits;
    }

    @Transactional(readOnly = true)
    public FanoutPolicy get(long authorId) {
        users.requireExists(authorId);
        return policies.find(authorId).orElseGet(() -> FanoutPolicy.defaultPush(authorId));
    }

    @Transactional
    public FanoutPolicy set(long authorId, FanoutMode mode, String reason, Long actorId) {
        users.requireExistsForUpdate(authorId);
        requireNoActiveBackfill(authorId);
        FanoutPolicy previous = currentPolicy(authorId);
        policies.upsert(authorId, mode, reason);
        FanoutPolicy policy = policies.find(authorId)
                .orElseThrow(() -> new IllegalStateException("扩散策略写入后无法读取"));
        auditIfChanged(previous, policy, FanoutPolicyChangeTrigger.MANUAL_SET,
                reason, null, null, null, actorId, null);
        return policy;
    }

    @Transactional
    public FanoutSwitchResult switchMode(long authorId, FanoutMode mode, String reason,
                                         Long historyLimit, Long createdBy) {
        users.requireExistsForUpdate(authorId);
        requireNoActiveBackfill(authorId);
        if (historyLimit != null && historyLimit < 0) {
            throw new IllegalArgumentException("历史回填数量不能为负数");
        }
        FanoutPolicy previous = currentPolicy(authorId);
        policies.upsert(authorId, mode, reason);
        long available = posts.countPostsForModeChange(authorId, mode);
        long totalPosts = historyLimit == null ? available : Math.min(available, historyLimit);
        FanoutBackfillJob job = backfills.create(UUID.randomUUID().toString(), authorId,
                previous.mode(), mode, reason, historyLimit, totalPosts, createdBy);
        FanoutPolicy policy = policies.find(authorId)
                .orElseThrow(() -> new IllegalStateException("扩散策略写入后无法读取"));
        auditIfChanged(previous, policy, FanoutPolicyChangeTrigger.MANUAL_SWITCH,
                reason, null, null, null, createdBy, job.id());
        return new FanoutSwitchResult(previous.mode(), policy, job);
    }

    FanoutSwitchResult switchMode(long authorId, FanoutMode mode, String reason, long historyLimit) {
        return switchMode(authorId, mode, reason, historyLimit, null);
    }

    @Transactional
    public void reset(long authorId, Long actorId) {
        users.requireExistsForUpdate(authorId);
        requireNoActiveBackfill(authorId);
        FanoutPolicy previous = currentPolicy(authorId);
        policies.delete(authorId);
        FanoutPolicy policy = FanoutPolicy.defaultPush(authorId);
        auditIfChanged(previous, policy, FanoutPolicyChangeTrigger.MANUAL_RESET,
                "reset to default PUSH", null, null, null, actorId, null);
    }

    @Transactional
    public AutoPolicyResult applyAutomatic(long authorId, long friendCount,
                                           long pullThreshold, long pushThreshold,
                                           FanoutPolicyChangeTrigger triggerType, Long actorId) {
        if (triggerType != FanoutPolicyChangeTrigger.AUTO_SCHEDULED
                && triggerType != FanoutPolicyChangeTrigger.AUTO_ADMIN) {
            throw new IllegalArgumentException("自动策略变更必须使用自动触发类型");
        }
        users.requireExistsForUpdate(authorId);
        FanoutPolicy previous = currentPolicy(authorId);
        if (previous.source() == FanoutPolicySource.MANUAL) {
            return AutoPolicyResult.of(AutoPolicyOutcome.SKIPPED_MANUAL, previous, null);
        }

        FanoutMode targetMode = automaticTarget(previous, friendCount, pullThreshold, pushThreshold);
        if (targetMode == null) {
            if (previous.source() == FanoutPolicySource.AUTO) {
                policies.upsertAuto(authorId, previous.mode(), friendCount);
            }
            return AutoPolicyResult.of(AutoPolicyOutcome.UNCHANGED, previous, null);
        }
        if (backfills.hasActiveForAuthor(authorId)) {
            return AutoPolicyResult.of(AutoPolicyOutcome.BLOCKED_ACTIVE_BACKFILL,
                    previous, targetMode);
        }

        String reason = "automatic connection threshold: " + friendCount;
        if (targetMode == FanoutMode.PULL) {
            policies.upsertAuto(authorId, targetMode, friendCount);
        } else {
            policies.deleteAuto(authorId);
        }
        long totalPosts = posts.countPostsForModeChange(authorId, targetMode);
        FanoutBackfillJob job = backfills.create(UUID.randomUUID().toString(), authorId,
                previous.mode(), targetMode, reason, null, totalPosts, actorId);
        FanoutPolicy policy = policies.find(authorId)
                .orElseGet(() -> FanoutPolicy.defaultPush(authorId));
        audits.add(authorId, previous.mode(), policy.mode(), previous.source(), policy.source(),
                triggerType, reason, friendCount, pullThreshold, pushThreshold, actorId, job.id());
        return new AutoPolicyResult(AutoPolicyOutcome.CHANGED, previous.mode(), policy.mode(), job);
    }

    private void requireNoActiveBackfill(long authorId) {
        if (backfills != null && backfills.hasActiveForAuthor(authorId)) {
            throw new ConflictException("该作者已有进行中的回填任务，请先完成或取消任务");
        }
    }

    private FanoutPolicy currentPolicy(long authorId) {
        return policies.find(authorId).orElseGet(() -> FanoutPolicy.defaultPush(authorId));
    }

    private FanoutMode automaticTarget(FanoutPolicy current, long friendCount,
                                       long pullThreshold, long pushThreshold) {
        if (friendCount >= pullThreshold && current.mode() != FanoutMode.PULL) {
            return FanoutMode.PULL;
        }
        if (current.source() == FanoutPolicySource.AUTO
                && current.mode() == FanoutMode.PULL && friendCount <= pushThreshold) {
            return FanoutMode.PUSH;
        }
        return null;
    }

    private void auditIfChanged(FanoutPolicy previous, FanoutPolicy policy,
                                FanoutPolicyChangeTrigger triggerType, String reason,
                                Long friendCount, Long pullThreshold, Long pushThreshold,
                                Long actorId, String backfillJobId) {
        if (previous.mode() == policy.mode() && previous.source() == policy.source()) {
            return;
        }
        audits.add(previous.authorId(), previous.mode(), policy.mode(),
                previous.source(), policy.source(), triggerType, reason, friendCount,
                pullThreshold, pushThreshold, actorId, backfillJobId);
    }

    public record FanoutSwitchResult(FanoutMode previousMode, FanoutPolicy policy,
                                     FanoutBackfillJob backfillJob) {
    }

    public enum AutoPolicyOutcome {
        CHANGED,
        UNCHANGED,
        SKIPPED_MANUAL,
        BLOCKED_ACTIVE_BACKFILL
    }

    public record AutoPolicyResult(AutoPolicyOutcome outcome, FanoutMode previousMode,
                                   FanoutMode targetMode, FanoutBackfillJob backfillJob) {
        private static AutoPolicyResult of(AutoPolicyOutcome outcome, FanoutPolicy policy,
                                           FanoutMode targetMode) {
            return new AutoPolicyResult(outcome, policy.mode(), targetMode, null);
        }
    }
}
