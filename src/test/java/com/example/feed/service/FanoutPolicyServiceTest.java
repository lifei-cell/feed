package com.example.feed.service;

import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.FanoutPolicySource;
import com.example.feed.domain.FanoutBackfillStatus;
import com.example.feed.domain.FanoutPolicyChangeTrigger;
import com.example.feed.repository.FanoutBackfillJobRepository;
import com.example.feed.repository.FanoutBackfillJobRepository.FanoutBackfillJob;
import com.example.feed.repository.FanoutPolicyRepository;
import com.example.feed.repository.FanoutPolicyRepository.FanoutPolicy;
import com.example.feed.repository.FanoutPolicyAuditRepository;
import com.example.feed.repository.PostRepository;
import com.example.feed.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class FanoutPolicyServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final FanoutPolicyRepository policies = mock(FanoutPolicyRepository.class);
    private final PostRepository posts = mock(PostRepository.class);
    private final FanoutBackfillJobRepository backfills = mock(FanoutBackfillJobRepository.class);
    private final FanoutPolicyAuditRepository audits = mock(FanoutPolicyAuditRepository.class);
    private final FanoutPolicyService service = new FanoutPolicyService(
            users, policies, posts, backfills, audits);

    @Test
    void missingPolicyResolvesToImplicitPush() {
        when(policies.find(7)).thenReturn(Optional.empty());

        FanoutPolicy result = service.get(7);

        assertThat(result.mode()).isEqualTo(FanoutMode.PUSH);
        assertThat(result.explicit()).isFalse();
    }

    @Test
    void administratorCanSetAndResetExplicitPolicy() {
        FanoutPolicy stored = policy(FanoutMode.PULL, "high degree");
        when(policies.find(7)).thenReturn(Optional.of(stored));

        assertThat(service.set(7, FanoutMode.PULL, "high degree", 99L)).isEqualTo(stored);
        service.reset(7, 99L);

        verify(policies).upsert(7, FanoutMode.PULL, "high degree");
        verify(policies).delete(7);
    }

    @Test
    void manualSetRecordsActorAndSourceChange() {
        FanoutPolicy stored = policy(FanoutMode.PULL, "high degree");
        when(policies.find(7)).thenReturn(Optional.empty(), Optional.of(stored));

        service.set(7, FanoutMode.PULL, "high degree", 99L);

        verify(audits).add(7, FanoutMode.PUSH, FanoutMode.PULL, null,
                FanoutPolicySource.MANUAL, FanoutPolicyChangeTrigger.MANUAL_SET,
                "high degree", null, null, null, 99L, null);
    }

    @Test
    void switchingToPullCreatesAsynchronousBackfillWithoutWritingHistoryInline() {
        FanoutPolicy stored = policy(FanoutMode.PULL, "high degree");
        FanoutBackfillJob job = job(FanoutMode.PUSH, FanoutMode.PULL, 100);
        when(policies.find(7)).thenReturn(Optional.empty(), Optional.of(stored));
        when(posts.countPostsForModeChange(7, FanoutMode.PULL)).thenReturn(400L);
        when(backfills.create(anyString(), eq(7L), eq(FanoutMode.PUSH), eq(FanoutMode.PULL),
                eq("high degree"), eq(100L), eq(100L), eq(null))).thenReturn(job);

        var result = service.switchMode(7, FanoutMode.PULL, "high degree", 100);

        assertThat(result.previousMode()).isEqualTo(FanoutMode.PUSH);
        assertThat(result.backfillJob()).isEqualTo(job);
        verify(posts, never()).updateDeliveryMode(org.mockito.ArgumentMatchers.any(), eq(FanoutMode.PULL));
    }

    @Test
    void nullHistoryLimitQueuesAllEligibleHistory() {
        FanoutPolicy stored = policy(FanoutMode.PUSH, "normal author");
        FanoutPolicy previous = new FanoutPolicy(7, FanoutMode.PULL, FanoutPolicySource.AUTO,
                "automatic", 12_000L, Instant.now(), Instant.now(), true);
        FanoutBackfillJob job = job(FanoutMode.PULL, FanoutMode.PUSH, 12_000);
        when(policies.find(7)).thenReturn(Optional.of(previous), Optional.of(stored));
        when(posts.countPostsForModeChange(7, FanoutMode.PUSH)).thenReturn(12_000L);
        when(backfills.create(anyString(), eq(7L), eq(FanoutMode.PULL), eq(FanoutMode.PUSH),
                eq("normal author"), eq(null), eq(12_000L), eq(99L))).thenReturn(job);

        var result = service.switchMode(7, FanoutMode.PUSH, "normal author", null, 99L);

        assertThat(result.backfillJob().totalPosts()).isEqualTo(12_000);
        verify(backfills).create(anyString(), eq(7L), eq(FanoutMode.PULL), eq(FanoutMode.PUSH),
                eq("normal author"), eq(null), eq(12_000L), eq(99L));
    }

    @Test
    void automaticPromotionCreatesOneFullBackfillAndAuditRecord() {
        FanoutPolicy automatic = new FanoutPolicy(7, FanoutMode.PULL, FanoutPolicySource.AUTO,
                "automatic", 12_000L, Instant.now(), Instant.now(), true);
        FanoutBackfillJob job = job(FanoutMode.PUSH, FanoutMode.PULL, 400);
        when(policies.find(7)).thenReturn(Optional.empty(), Optional.of(automatic));
        when(posts.countPostsForModeChange(7, FanoutMode.PULL)).thenReturn(400L);
        when(backfills.create(anyString(), eq(7L), eq(FanoutMode.PUSH), eq(FanoutMode.PULL),
                eq("automatic connection threshold: 12000"), eq(null), eq(400L), eq(null)))
                .thenReturn(job);

        var result = service.applyAutomatic(7, 12_000, 10_000, 8_000,
                FanoutPolicyChangeTrigger.AUTO_SCHEDULED, null);

        assertThat(result.outcome()).isEqualTo(FanoutPolicyService.AutoPolicyOutcome.CHANGED);
        assertThat(result.backfillJob()).isEqualTo(job);
        verify(audits).add(7, FanoutMode.PUSH, FanoutMode.PULL, null,
                FanoutPolicySource.AUTO, FanoutPolicyChangeTrigger.AUTO_SCHEDULED,
                "automatic connection threshold: 12000", 12_000L, 10_000L, 8_000L,
                null, job.id());
    }

    @Test
    void repeatedAutomaticEvaluationDoesNotCreateAnotherBackfillOrAudit() {
        FanoutPolicy automatic = new FanoutPolicy(7, FanoutMode.PULL, FanoutPolicySource.AUTO,
                "automatic", 12_000L, Instant.now(), Instant.now(), true);
        when(policies.find(7)).thenReturn(Optional.of(automatic));

        var result = service.applyAutomatic(7, 12_100, 10_000, 8_000,
                FanoutPolicyChangeTrigger.AUTO_SCHEDULED, null);

        assertThat(result.outcome()).isEqualTo(FanoutPolicyService.AutoPolicyOutcome.UNCHANGED);
        verify(policies).upsertAuto(7, FanoutMode.PULL, 12_100);
        verify(backfills, never()).create(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        verifyNoAuditWrites();
    }

    @Test
    void activeBackfillDefersAutomaticChangeWithoutMutatingPolicy() {
        when(policies.find(7)).thenReturn(Optional.empty());
        when(backfills.hasActiveForAuthor(7)).thenReturn(true);

        var result = service.applyAutomatic(7, 12_000, 10_000, 8_000,
                FanoutPolicyChangeTrigger.AUTO_SCHEDULED, null);

        assertThat(result.outcome())
                .isEqualTo(FanoutPolicyService.AutoPolicyOutcome.BLOCKED_ACTIVE_BACKFILL);
        verify(policies, never()).upsertAuto(7, FanoutMode.PULL, 12_000);
        verifyNoAuditWrites();
    }

    @Test
    void automaticRevertCreatesReverseBackfillAndAudit() {
        FanoutPolicy previous = new FanoutPolicy(7, FanoutMode.PULL, FanoutPolicySource.AUTO,
                "automatic", 9_000L, Instant.now(), Instant.now(), true);
        FanoutBackfillJob job = job(FanoutMode.PULL, FanoutMode.PUSH, 25);
        when(policies.find(7)).thenReturn(Optional.of(previous), Optional.empty());
        when(posts.countPostsForModeChange(7, FanoutMode.PUSH)).thenReturn(25L);
        when(backfills.create(anyString(), eq(7L), eq(FanoutMode.PULL), eq(FanoutMode.PUSH),
                eq("automatic connection threshold: 7000"), eq(null), eq(25L), eq(99L)))
                .thenReturn(job);

        var result = service.applyAutomatic(7, 7_000, 10_000, 8_000,
                FanoutPolicyChangeTrigger.AUTO_ADMIN, 99L);

        assertThat(result.targetMode()).isEqualTo(FanoutMode.PUSH);
        verify(policies).deleteAuto(7);
        verify(audits).add(7, FanoutMode.PULL, FanoutMode.PUSH, FanoutPolicySource.AUTO,
                null, FanoutPolicyChangeTrigger.AUTO_ADMIN,
                "automatic connection threshold: 7000", 7_000L, 10_000L, 8_000L,
                99L, job.id());
    }

    @Test
    void automaticEvaluationNeverOverwritesManualPolicy() {
        when(policies.find(7)).thenReturn(Optional.of(policy(FanoutMode.PUSH, "operator")));

        var result = service.applyAutomatic(7, 20_000, 10_000, 8_000,
                FanoutPolicyChangeTrigger.AUTO_SCHEDULED, null);

        assertThat(result.outcome()).isEqualTo(FanoutPolicyService.AutoPolicyOutcome.SKIPPED_MANUAL);
        verify(policies, never()).upsertAuto(7, FanoutMode.PULL, 20_000);
        verifyNoAuditWrites();
    }

    private void verifyNoAuditWrites() {
        org.mockito.Mockito.verifyNoInteractions(audits);
    }

    private FanoutPolicy policy(FanoutMode mode, String reason) {
        return new FanoutPolicy(7, mode, FanoutPolicySource.MANUAL, reason, null, null,
                Instant.parse("2026-08-15T00:00:00Z"), true);
    }

    private FanoutBackfillJob job(FanoutMode source, FanoutMode target, long total) {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        return new FanoutBackfillJob("job-1", 7, source, target, FanoutBackfillStatus.PENDING,
                "reason", null, total, 0, 0, null, null, 0, null,
                now, null, null, 99L, now, null, null, now);
    }
}
