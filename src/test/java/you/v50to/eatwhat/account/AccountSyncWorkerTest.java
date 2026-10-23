package you.v50to.eatwhat.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentModels.Job;
import you.v50to.eatwhat.account.ConsentModels.Snapshot;
import you.v50to.eatwhat.account.ConsentModels.State;
import you.v50to.eatwhat.config.SiteProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountSyncWorkerTest {
    private static final Duration LEASE = Duration.ofSeconds(20);
    private final ConsentStore store = mock(ConsentStore.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    private final AccountAuthService authentication = mock(AccountAuthService.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    private final AccountChannel channel = mock(AccountChannel.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final SiteProperties site = new SiteProperties();

    @BeforeEach void defaults() {
        site.setMode(SiteProperties.Mode.EXTERNAL);
        site.setId("external-worker-test");
        site.setInstanceId(UUID.randomUUID());
        when(store.pendingEffects(16)).thenReturn(List.of());
        when(store.claim(4, LEASE)).thenReturn(List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REGISTER", "UPSERT", "PURGE"})
    void missingOrSupersededExportIsDiscardedAndNeverTreatedAsArchived(String kind) {
        Job job = job(kind);
        when(store.claim(4, LEASE)).thenReturn(List.of(job));
        when(store.export(job)).thenReturn(null);
        worker().runOnce();
        verify(store).discard(job);
        verify(store, never()).complete(any());
        verify(store, never()).retry(any());
        verifyNoInteractions(channel);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLIED", "STALE"})
    void acknowledgedRegistrationCompletesExactlyItsClaim(String status) {
        Job job = exportable("REGISTER");
        when(channel.call(eq("register"), any())).thenReturn(json.createObjectNode().put("status", status));
        worker().runOnce();
        verify(channel).call(eq("register"), argThat(body -> body.path("subjectId").asText().equals(job.subjectId().toString())
                && body.path("userId").asLong() == job.userId() && body.path("epoch").asLong() == job.epoch()));
        verify(store).complete(job);
        verify(store, never()).discard(any());
        verify(store, never()).retry(any());
        verify(store, never()).conflict(any());
    }

    @Test
    void registrationConflictRetainsManualConflictInsteadOfAcknowledgingOrRetryingIt() {
        Job job = exportable("REGISTER");
        when(channel.call(eq("register"), any())).thenReturn(json.createObjectNode().put("status", "CONFLICT"));
        worker().runOnce();
        verify(store).conflict(job);
        verify(store, never()).complete(any());
        verify(store, never()).retry(any());
        verify(store, never()).discard(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETRY", "REJECTED", "", "UNKNOWN"})
    void unacknowledgedRegistrationRetriesItsMetadataWithoutClaimingRemoteOwnership(String status) {
        Job job = exportable("REGISTER");
        when(channel.call(eq("register"), any())).thenReturn(json.createObjectNode().put("status", status));
        worker().runOnce();
        verify(store).retry(job);
        verify(store, never()).complete(any());
        verify(store, never()).conflict(any());
        verify(store, never()).discard(any());
    }

    @Test
    void networkFailureRetriesAndDoesNotPreventTheNextIndependentJob() {
        Job failed = job("REGISTER");
        Job good = job("REGISTER");
        when(store.claim(4, LEASE)).thenReturn(List.of(failed, good));
        when(store.export(failed)).thenReturn(snapshot(failed));
        when(store.export(good)).thenReturn(snapshot(good));
        when(channel.call(eq("register"), any())).thenThrow(new AccountUnavailableException())
                .thenReturn(json.createObjectNode().put("status", "APPLIED"));
        worker().runOnce();
        verify(store).retry(failed);
        verify(store, never()).complete(failed);
        verify(store).complete(good);
    }

    @Test
    void campusProjectionUsesTheProjectOperationWithoutChangingRegistrationRouting() {
        site.setMode(SiteProperties.Mode.CAMPUS);
        Job job = exportable("UPSERT");
        when(channel.call(eq("project"), any())).thenReturn(json.createObjectNode().put("status", "APPLIED"));
        worker().runOnce();
        verify(channel).call(eq("project"), any());
        verify(channel, never()).call(eq("register"), any());
        verify(store).complete(job);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DONE", "RETRY", "DISCARD"})
    void cleanupUsesOnlyLocalCleanupAndSettlesAccordingToItsFencedOutcome(String outcome) {
        Job job = job("CLEANUP");
        when(store.claim(4, LEASE)).thenReturn(List.of(job));
        when(store.runCleanup(job)).thenReturn(outcome);
        worker().runOnce();
        verify(store).runCleanup(job);
        verify(store, never()).export(any());
        verifyNoInteractions(channel);
        verify(store, times(outcome.equals("DONE") ? 1 : 0)).complete(job);
        verify(store, times(outcome.equals("RETRY") ? 1 : 0)).retry(job);
        verify(store, times(outcome.equals("DISCARD") ? 1 : 0)).discard(job);
    }

    @Test
    void cleanupFailureLeavesRetryableMetadataAndDoesNotAcknowledgeDeletion() {
        Job job = job("CLEANUP");
        when(store.claim(4, LEASE)).thenReturn(List.of(job));
        when(store.runCleanup(job)).thenThrow(new IllegalStateException("Synthetic transient database failure"));
        worker().runOnce();
        verify(store).retry(job);
        verify(store, never()).complete(any());
        verify(store, never()).discard(any());
        verifyNoInteractions(channel);
    }

    @Test
    void effectsAcknowledgementUsesVersionReadBeforeSessionInvalidationEvenWhenNewEffectsArrive() {
        long userId = 1_000_000_002L;
        when(store.pendingEffects(16)).thenReturn(List.of(userId), List.of());
        when(store.effectsVersion(userId)).thenReturn(7L);
        doAnswer(invocation -> {
            when(store.effectsVersion(userId)).thenReturn(8L);
            return null;
        }).when(authentication).invalidateLocalSessions(userId);
        worker().runOnce();
        var order = inOrder(store, authentication);
        order.verify(store).effectsVersion(userId);
        order.verify(authentication).invalidateLocalSessions(userId);
        order.verify(store).completeEffects(userId, 7L);
        verify(store, never()).completeEffects(userId, 8L);
    }

    @Test
    void failedSessionInvalidationDoesNotClearPersistentEffectsAndNextCycleCanRetry() {
        long userId = 1_000_000_002L;
        when(store.pendingEffects(16)).thenReturn(List.of(userId), List.of(), List.of(userId), List.of());
        when(store.effectsVersion(userId)).thenReturn(9L);
        doThrow(new IllegalStateException("Synthetic Redis outage")).doNothing()
                .when(authentication).invalidateLocalSessions(userId);
        var worker = worker();
        worker.runOnce();
        verify(store, never()).completeEffects(anyLong(), anyLong());
        worker.runOnce();
        verify(store).completeEffects(userId, 9L);
    }

    @Test
    void withdrawalWithoutCurrentRequestIsDiscardedWithoutSendingOrCompleting() {
        Job job = job("WITHDRAW_REQUEST");
        when(store.claim(4, LEASE)).thenReturn(List.of(job));
        when(store.get(job.userId())).thenReturn(null);
        worker().runOnce();
        verify(store).discard(job);
        verify(store, never()).complete(any());
        verifyNoInteractions(channel);
    }

    @Test
    void withdrawalAcknowledgementBindsSubjectRequestAndAuthorityEpochBeforeCompletion() {
        Job job = job("WITHDRAW_REQUEST");
        UUID requestId = UUID.randomUUID();
        when(store.claim(4, LEASE)).thenReturn(List.of(job));
        when(store.get(job.userId())).thenReturn(new State(job.userId(), job.subjectId(), job.epoch(), job.revision(),
                "WITHDRAWN", "policy-test", null, false, "WITHDRAWN", "PENDING", requestId));
        when(channel.call(eq("withdraw"), any())).thenReturn(json.createObjectNode().put("status", "APPLIED").put("epoch", 5));
        worker().runOnce();
        verify(channel).call(eq("withdraw"), argThat(body -> body.size() == 2 && body.path("subjectId").asText().equals(job.subjectId().toString())
                && body.path("requestId").asText().equals(requestId.toString())));
        var order = inOrder(store);
        order.verify(store).acknowledgeWithdrawal(job.subjectId(), requestId, 5L);
        order.verify(store).complete(job);
    }

    private AccountSyncWorker worker() { return new AccountSyncWorker(store, channel, authentication, site, LEASE); }
    private Job exportable(String kind) {
        Job job = job(kind);
        when(store.claim(4, LEASE)).thenReturn(List.of(job));
        when(store.export(job)).thenReturn(snapshot(job));
        return job;
    }
    private Snapshot snapshot(Job job) {
        return new Snapshot(job.subjectId(), job.userId(), job.epoch(), job.revision(), "PROVISIONAL", "policy-test",
                Instant.now().plusSeconds(300), site.getId(), site.getInstanceId(), null, json.createObjectNode());
    }
    private static Job job(String kind) {
        return new Job(UUID.randomUUID(), 1_000_000_002L, UUID.randomUUID(), 2, 3, kind, UUID.randomUUID(), 1);
    }
}
