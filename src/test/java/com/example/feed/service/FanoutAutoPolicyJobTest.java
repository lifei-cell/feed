package com.example.feed.service;

import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.FanoutPolicyChangeTrigger;
import com.example.feed.repository.RelationshipRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FanoutAutoPolicyJobTest {
    private final RelationshipRepository relationships = mock(RelationshipRepository.class);
    private final FanoutPolicyService policies = mock(FanoutPolicyService.class);

    @Test
    void reportsChangedBlockedAndFailedAuthorsWithoutStoppingTheBatch() {
        when(relationships.findConnectionCountsAfter(0, 10)).thenReturn(List.of(
                count(1, 120), count(2, 70), count(3, 110), count(4, 200)));
        when(policies.applyAutomatic(1, 120, 100, 80,
                FanoutPolicyChangeTrigger.AUTO_SCHEDULED, null))
                .thenReturn(changed(FanoutMode.PUSH, FanoutMode.PULL));
        when(policies.applyAutomatic(2, 70, 100, 80,
                FanoutPolicyChangeTrigger.AUTO_SCHEDULED, null))
                .thenReturn(changed(FanoutMode.PULL, FanoutMode.PUSH));
        when(policies.applyAutomatic(3, 110, 100, 80,
                FanoutPolicyChangeTrigger.AUTO_SCHEDULED, null))
                .thenReturn(new FanoutPolicyService.AutoPolicyResult(
                        FanoutPolicyService.AutoPolicyOutcome.BLOCKED_ACTIVE_BACKFILL,
                        FanoutMode.PUSH, FanoutMode.PULL, null));
        when(policies.applyAutomatic(4, 200, 100, 80,
                FanoutPolicyChangeTrigger.AUTO_SCHEDULED, null))
                .thenThrow(new IllegalStateException("database unavailable"));
        FanoutAutoPolicyJob job = new FanoutAutoPolicyJob(relationships, policies,
                new SimpleMeterRegistry(), true, 100, 80, 10, 2);

        var result = job.refresh();

        assertThat(result.evaluatedThisRun()).isEqualTo(4);
        assertThat(result.promotedThisRun()).isEqualTo(1);
        assertThat(result.revertedThisRun()).isEqualTo(1);
        assertThat(result.backfillsCreatedThisRun()).isEqualTo(2);
        assertThat(result.blockedThisRun()).isEqualTo(1);
        assertThat(result.failuresThisRun()).isEqualTo(1);
    }

    @Test
    void administratorRunCarriesTheActorIntoTheAuditTrigger() {
        when(relationships.findConnectionCountsAfter(0, 10)).thenReturn(List.of(count(7, 120)));
        when(policies.applyAutomatic(7, 120, 100, 80,
                FanoutPolicyChangeTrigger.AUTO_ADMIN, 99L))
                .thenReturn(changed(FanoutMode.PUSH, FanoutMode.PULL));
        FanoutAutoPolicyJob job = new FanoutAutoPolicyJob(relationships, policies,
                new SimpleMeterRegistry(), true, 100, 80, 10, 2);

        job.refreshNow(99);

        verify(policies).applyAutomatic(7, 120, 100, 80,
                FanoutPolicyChangeTrigger.AUTO_ADMIN, 99L);
    }

    private FanoutPolicyService.AutoPolicyResult changed(FanoutMode previous, FanoutMode target) {
        return new FanoutPolicyService.AutoPolicyResult(
                FanoutPolicyService.AutoPolicyOutcome.CHANGED, previous, target, null);
    }

    private RelationshipRepository.ConnectionCount count(long id, long count) {
        return new RelationshipRepository.ConnectionCount(id, count);
    }
}
