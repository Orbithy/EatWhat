package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.account.ConsentModels.Job;
import you.v50to.eatwhat.account.ConsentModels.Snapshot;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.EventCodec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic account data, actual PostgreSQL transactions/triggers, and non-owner runtime roles. */
class ConsentStoreIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("consent-store");
    private static final String POLICY = "policy-2026-10";
    private static final Duration LEASE = Duration.ofSeconds(20);
    private static final Duration TTL = Duration.ofMinutes(5);
    @BeforeAll static void start() { POSTGRES.start(); }
    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test
    void registrationConsentAndJobJoinTheRealBusinessTransactionAndLegacyAbsenceDoesNotInferConsent() {
        var db = database(SiteProperties.Mode.CAMPUS, "campus-store");
        var store = store(db, "external-store", UUID.randomUUID());
        assertEquals(db.runtimeUsername(), db.runtime().queryForObject("SELECT current_user", String.class));
        assertFalse(db.runtime().queryForObject("SELECT has_sequence_privilege(current_user,'public.users_id_seq','UPDATE')", Boolean.class));
        assertThrows(IllegalStateException.class, () -> db.transactions().required(() -> {
            long id = user(db, "rolled_back", false);
            store.onRegistered(id, true, POLICY);
            throw new IllegalStateException("synthetic rollback");
        }));
        assertEquals(0, count(db, "public.users"));
        assertEquals(0, count(db, "eatwhat_consent.subject"));
        assertEquals(0, count(db, "eatwhat_consent.job"));
        assertEquals(0, count(db, "eatwhat_identity.entity_identity"));
        long id = user(db, "not_granted", false);
        assertNull(store.get(id));
        var state = store.onRegistered(id, false, null);
        assertEquals("NOT_GRANTED", state.status());
        assertEquals(0, state.epoch());
        assertEquals(0, count(db, "eatwhat_consent.job"));
        assertNoEvents(db);
    }

    @Test
    void proofConsumptionGrantAndQueueRollbackTogetherThenTheSameProofCanRecover() {
        var db = database(SiteProperties.Mode.CAMPUS, "campus-store");
        var store = store(db, "external-store", UUID.randomUUID());
        long id = user(db, "proof_atomic", false);
        store.onRegistered(id, false, null);
        String proof = store.issueProof(id, "external-store");
        assertTrue(proof.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(EventCodec.sha256(proof), db.admin().queryForObject("SELECT token_hash FROM eatwhat_consent.proof", String.class));
        db.admin().execute("""
                CREATE FUNCTION public.fail_consent_job_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Synthetic grant job failure'; END $$
                """);
        db.admin().execute("CREATE TRIGGER fail_consent_job_for_test BEFORE INSERT ON eatwhat_consent.job FOR EACH ROW EXECUTE FUNCTION public.fail_consent_job_for_test()");
        try {
            assertThrows(DataAccessException.class, () -> store.grantWithProof(proof, "external-store", POLICY));
            assertEquals("NOT_GRANTED", store.get(id).status());
            assertEquals(0, count(db, "eatwhat_consent.job"));
            assertNull(db.admin().queryForObject("SELECT consumed_at FROM eatwhat_consent.proof", java.sql.Timestamp.class));
        } finally {
            db.admin().execute("DROP TRIGGER fail_consent_job_for_test ON eatwhat_consent.job");
            db.admin().execute("DROP FUNCTION public.fail_consent_job_for_test()");
        }
        var granted = store.grantWithProof(proof, "external-store", POLICY);
        assertEquals("GRANTED", granted.status());
        assertEquals(1, count(db, "eatwhat_consent.job"));
        assertEquals(granted.epoch(), store.grant(id, POLICY).epoch());
        assertEquals(1, count(db, "eatwhat_consent.job"));
        assertFalse(db.admin().queryForObject("SELECT string_agg(to_jsonb(p)::text,'') FROM eatwhat_consent.proof p", String.class).contains(proof));
        assertNoEvents(db);
    }

    @Test
    void concurrentProofConsumptionSucceedsExactlyOnceAndEveryOtherAttemptIsRejected() throws Exception {
        var db = database(SiteProperties.Mode.CAMPUS, "campus-store");
        UUID peer = UUID.randomUUID();
        var store = store(db, "external-store", peer);
        long id = user(db, "proof_race", false);
        store.onRegistered(id, false, null);
        String proof = store.issueProof(id, "external-store");
        CountDownLatch begin = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        var pool = Executors.newFixedThreadPool(6);
        try {
            for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> {
                assertTrue(begin.await(5, TimeUnit.SECONDS));
                try { return store(db, "external-store", peer).grantWithProof(proof, "external-store", POLICY).status(); }
                catch (ConsentStore.ConsentException failure) { return failure.code(); }
            }));
            begin.countDown();
            List<String> outcomes = new ArrayList<>();
            for (var future : futures) outcomes.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("GRANTED"::equals).count());
            assertEquals(5, outcomes.stream().filter("PROOF_INVALID"::equals).count());
            assertEquals(1, count(db, "eatwhat_consent.job"));
            assertNotNull(db.admin().queryForObject("SELECT consumed_at FROM eatwhat_consent.proof", java.sql.Timestamp.class));
        } finally { begin.countDown(); futures.forEach(f -> f.cancel(true)); pool.shutdownNow(); }
    }

    @Test
    void passwordBanAudiencePolicyExpiryAndEpochChangesInvalidatePreviouslyIssuedProofs() {
        var db = database(SiteProperties.Mode.CAMPUS, "campus-store");
        var store = store(db, "external-store", UUID.randomUUID());
        long id = user(db, "proof_changes", false);
        store.onRegistered(id, false, null);
        String changedPassword = store.issueProof(id, "external-store");
        db.runtime().update("UPDATE public.users SET password_hash='synthetic-new-password-hash' WHERE id=?", id);
        invalidProof(() -> store.grantWithProof(changedPassword, "external-store", POLICY));
        String banned = store.issueProof(id, "external-store");
        db.runtime().update("UPDATE public.users SET banned=true WHERE id=?", id);
        invalidProof(() -> store.grantWithProof(banned, "external-store", POLICY));
        db.runtime().update("UPDATE public.users SET banned=false WHERE id=?", id);
        invalidProof(() -> store.grantWithProof(banned, "external-store", POLICY));
        assertNull(db.admin().queryForObject("SELECT consumed_at FROM eatwhat_consent.proof WHERE token_hash=?", java.sql.Timestamp.class, EventCodec.sha256(banned)),
                "Ban then unban restores the fingerprint but must not revive the old source revision proof");
        String current = store.issueProof(id, "external-store");
        invalidProof(() -> store.grantWithProof(current, "wrong-audience", POLICY));
        assertEquals("POLICY_CHANGED", assertThrows(ConsentStore.ConsentException.class,
                () -> store.grantWithProof(current, "external-store", "old-policy")).code());
        db.admin().update("UPDATE eatwhat_consent.proof SET expires_at=clock_timestamp()-interval '1 second' WHERE token_hash=?", EventCodec.sha256(current));
        invalidProof(() -> store.grantWithProof(current, "external-store", POLICY));
        String oldEpoch = store.issueProof(id, "external-store");
        store.grant(id, POLICY);
        store.withdraw(id);
        invalidProof(() -> store.grantWithProof(oldEpoch, "external-store", POLICY));
        assertEquals("WITHDRAWN", store.get(id).status());
    }

    @Test
    void controlledProjectionPreservesAllNumericAndGlobalIdentitiesWithoutAdvancingTargetSequencesOrAcceptingGucBypass() {
        var pair = pair();
        long id = user(pair.campus, "history_before_grant", true);
        pair.source.onRegistered(id, true, POLICY);
        var identities = accountIdentities(pair.campus, id);
        var beforeSequences = sequences(pair.external);
        var job = claim(pair.source, id, "UPSERT");
        var snapshot = pair.source.export(job);
        assertEquals("Snapshot[redacted]", snapshot.toString());
        assertEquals("APPLIED", pair.target.apply(snapshot));
        assertEquals(identities, accountIdentities(pair.external, id));
        assertEquals(beforeSequences, sequences(pair.external));
        assertEquals("history_before_grant", pair.external.runtime().queryForObject("SELECT nick_name FROM public.users WHERE id=?", String.class, id));
        assertEquals("synthetic-history-signature", pair.external.runtime().queryForObject("SELECT signature FROM public.user_info WHERE id=?", String.class, id));
        assertTrue(pair.target.isAuthorized(id));
        assertEquals("ACCOUNT_COMPLETE_DOMAINS_PENDING", pair.target.get(id).backfillStatus());
        assertEquals(0, count(pair.external, "eatwhat_consent.import_context"));
        var altered = snapshot.data().deepCopy();
        ((tools.jackson.databind.node.ObjectNode) altered.get("users").get(0)).put("password_hash", "synthetic-unexpected-replacement");
        var sameVersionDifferentPayload = new Snapshot(snapshot.subjectId(), snapshot.userId(), snapshot.epoch(), snapshot.revision(),
                snapshot.status(), snapshot.policyVersion(), snapshot.validUntil(), snapshot.sourceSite(), snapshot.sourceInstance(),
                snapshot.withdrawalRequestId(), altered);
        assertEquals("CONFLICT", pair.target.apply(sameVersionDifferentPayload));
        assertEquals("synthetic-password-hash-history_before_grant", pair.external.runtime().queryForObject("SELECT password_hash FROM public.users WHERE id=?", String.class, id));
        assertThrows(DataAccessException.class, () -> pair.external.runtime().update("INSERT INTO eatwhat_consent.import_context(transaction_id,backend_id,entity_type,numeric_id,global_id,origin_site,subject_id) VALUES(txid_current(),pg_backend_pid(),'users',?,?,?,?)",
                id + 20, UUID.randomUUID(), pair.campus.site().getId(), snapshot.subjectId()));
        assertThrows(DataAccessException.class, () -> pair.external.transactions().required(() -> {
            pair.external.runtime().queryForObject("SELECT set_config('eatwhat.identity_import','true',true)", String.class);
            pair.external.runtime().queryForObject("SELECT set_config('eatwhat.import_context','true',true)", String.class);
            pair.external.runtime().update("INSERT INTO public.users(id,nick_name,password_hash) VALUES(?,'forged_import','synthetic')", id + 20);
            return null;
        }));
        assertEquals(1, count(pair.external, "public.users"));
        assertNoEvents(pair.campus); assertNoEvents(pair.external);
    }

    @Test
    void concurrentAccountUpdateAndDeletionInvalidateAnOldBackfillLeaseAndLatestRevisionRepairsTheProjection() throws Exception {
        var pair = pair();
        long id = user(pair.campus, "revision_source", true);
        pair.source.onRegistered(id, true, POLICY);
        var oldJob = claim(pair.source, id, "UPSERT");
        var oldSnapshot = pair.source.export(oldJob);
        assertEquals("APPLIED", pair.target.apply(oldSnapshot));
        var originalIdentities = accountIdentities(pair.campus, id);
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        Future<?> writer = null; Future<Snapshot> exporter = null;
        try {
            writer = pool.submit(() -> pair.campus.transactions().required(() -> {
                pair.campus.runtime().update("UPDATE public.users SET avatar='synthetic-new-avatar' WHERE id=?", id);
                pair.campus.runtime().update("DELETE FROM public.contacts WHERE account_id=?", id);
                pair.campus.runtime().update("DELETE FROM public.user_info WHERE id=?", id);
                changed.countDown();
                try { assertTrue(commit.await(8, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return null;
            }));
            assertTrue(changed.await(5, TimeUnit.SECONDS));
            exporter = pool.submit(() -> pair.source.export(oldJob));
            await(() -> pair.campus.admin().queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND usename=? AND wait_event_type='Lock')", Boolean.class, pair.campus.runtimeUsername()));
            assertFalse(exporter.isDone(), "Export must not race past an in-progress account revision transaction");
            commit.countDown();
            writer.get(12, TimeUnit.SECONDS);
            assertNull(exporter.get(12, TimeUnit.SECONDS), "Old revision job must not export a mixed or stale account");
        } finally {
            commit.countDown(); if (writer != null) writer.cancel(true); if (exporter != null) exporter.cancel(true); pool.shutdownNow();
        }
        var current = pair.source.get(id);
        assertTrue(current.revision() >= oldSnapshot.revision() + 3);
        var latestJob = pair.source.claim(32, LEASE).stream().filter(j -> j.userId() == id && j.revision() == current.revision()).findFirst().orElseThrow();
        var latest = pair.source.export(latestJob);
        assertNotNull(latest);
        assertEquals("APPLIED", pair.target.apply(latest));
        assertEquals("STALE", pair.target.apply(oldSnapshot));
        assertEquals("synthetic-new-avatar", pair.external.runtime().queryForObject("SELECT avatar FROM public.users WHERE id=?", String.class, id));
        assertEquals(0, count(pair.external, "public.contacts"));
        assertEquals(0, count(pair.external, "public.user_info"));
        assertEquals(originalIdentities, pair.external.runtime().queryForList("SELECT entity_type,numeric_id,global_id,origin_site FROM eatwhat_identity.entity_identity WHERE entity_type IN ('users','contacts','verifications','privacy') ORDER BY entity_type,numeric_id"));
        assertNoEvents(pair.campus); assertNoEvents(pair.external);
    }

    @ParameterizedTest
    @ValueSource(strings = {"contacts", "user_info"})
    void firstConsentGrantWaitsForAnAlreadyWrittenChildTransactionBeforePublishingItsInitialRevision(String child) throws Exception {
        var pair = pair();
        long id = user(pair.campus, "initial_" + child, true);
        assertNull(pair.source.get(id));
        CountDownLatch writtenWithoutSubject = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        Future<?> writer = null;
        Future<you.v50to.eatwhat.account.ConsentModels.State> grant = null;
        try {
            writer = pool.submit(() -> pair.campus.transactions().required(() -> {
                if (child.equals("contacts")) {
                    pair.campus.runtime().update("UPDATE public.contacts SET email='new-before-consent@example.invalid' WHERE account_id=?", id);
                } else {
                    pair.campus.runtime().update("UPDATE public.user_info SET signature='new-before-consent-signature' WHERE id=?", id);
                }
                assertNull(pair.source.get(id), "The old AFTER trigger must actually finish before any consent subject exists");
                writtenWithoutSubject.countDown();
                try { assertTrue(commit.await(8, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return null;
            }));
            assertTrue(writtenWithoutSubject.await(5, TimeUnit.SECONDS));
            grant = pool.submit(() -> pair.source.grant(id, POLICY));
            await(() -> pair.campus.admin().queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND usename=? AND wait_event_type='Lock')", Boolean.class, pair.campus.runtimeUsername()));
            assertFalse(grant.isDone(), "Initial grant must not overtake an older uncommitted child write whose trigger saw no subject");
            assertNull(pair.source.get(id));
            assertEquals(0, count(pair.campus, "eatwhat_consent.job"));
            commit.countDown();
            writer.get(12, TimeUnit.SECONDS);
            assertEquals("GRANTED", grant.get(12, TimeUnit.SECONDS).status());
        } finally {
            commit.countDown();
            if (writer != null) writer.cancel(true);
            if (grant != null) grant.cancel(true);
            pool.shutdownNow();
        }
        var job = claim(pair.source, id, "UPSERT");
        var initial = pair.source.export(job);
        assertNotNull(initial);
        assertEquals("APPLIED", pair.target.apply(initial));
        String actual = child.equals("contacts")
                ? pair.external.runtime().queryForObject("SELECT email::text FROM public.contacts WHERE account_id=?", String.class, id)
                : pair.external.runtime().queryForObject("SELECT signature FROM public.user_info WHERE id=?", String.class, id);
        assertEquals(child.equals("contacts") ? "new-before-consent@example.invalid" : "new-before-consent-signature", actual);
        var repeated = pair.source.export(job);
        assertEquals(initial.revision(), repeated.revision());
        assertEquals("APPLIED", pair.target.apply(repeated), "A first-grant race must not create two payloads under one revision");
        assertEquals(0, count(pair.external, "eatwhat_consent.conflict_receipt"));
        assertEquals(accountIdentities(pair.campus, id), accountIdentities(pair.external, id));
    }

    @Test
    void withdrawalArrivingBeforeAccountLeavesOnlyTombstoneAndLateGrantCannotResurrectButNewEpochCan() {
        var pair = pair();
        long id = user(pair.campus, "reordered_grant", true);
        pair.source.onRegistered(id, true, POLICY);
        var grant = pair.source.export(claim(pair.source, id, "UPSERT"));
        pair.source.withdraw(id);
        var purge = pair.source.export(claim(pair.source, id, "PURGE"));
        assertNull(purge.data());
        assertEquals("APPLIED", pair.target.apply(purge));
        assertEquals(0, count(pair.external, "public.users"));
        assertFalse(pair.target.isAuthorized(id));
        assertEquals("STALE", pair.target.apply(grant));
        assertEquals(0, count(pair.external, "public.users"));
        pair.source.grant(id, POLICY);
        var regrant = pair.source.export(claim(pair.source, id, "UPSERT"));
        assertTrue(regrant.epoch() > purge.epoch());
        assertEquals("APPLIED", pair.target.apply(regrant));
        assertEquals(grant.subjectId(), pair.target.get(id).subjectId());
        assertEquals(accountIdentities(pair.campus, id), accountIdentities(pair.external, id));
        assertTrue(pair.target.isAuthorized(id));
    }

    @Test
    void delayedWithdrawalAcknowledgementUsesItsOriginalReceiptEpochSoTheSubsequentGrantCanStillApply() {
        var pair = pair();
        long id = user(pair.campus, "late_withdraw_ack", false);
        pair.source.onRegistered(id, true, POLICY);
        var original = pair.source.export(claim(pair.source, id, "UPSERT"));
        pair.target.apply(original);
        var withdrawn = pair.target.withdraw(id);
        UUID request = withdrawn.withdrawalRequestId();
        var accepted = pair.source.acceptWithdrawal(withdrawn.subjectId(), request);
        long receiptEpoch = pair.source.withdrawalEpoch(withdrawn.subjectId(), request);
        assertEquals(accepted.epoch(), receiptEpoch);
        var regranted = pair.source.grant(id, POLICY);
        assertTrue(regranted.epoch() > receiptEpoch);
        // The interactive grant occurred while the previous worker's withdrawal ACK was in flight.
        var replay = pair.source.acceptWithdrawal(withdrawn.subjectId(), request);
        assertEquals("GRANTED", replay.status());
        assertEquals(regranted.epoch(), replay.epoch());
        assertEquals(receiptEpoch, pair.source.withdrawalEpoch(withdrawn.subjectId(), request));
        assertEquals(1, count(pair.campus, "eatwhat_consent.withdrawal_receipt"));
        var grantJob = claim(pair.source, id, "UPSERT");
        var nextGrant = pair.source.export(grantJob);
        assertEquals("STALE", pair.target.apply(nextGrant), "Unacknowledged local withdrawal must still deny a grant");
        pair.target.acknowledgeWithdrawal(withdrawn.subjectId(), request, receiptEpoch);
        assertEquals(receiptEpoch, pair.target.get(id).epoch());
        assertNull(pair.target.get(id).withdrawalRequestId());
        assertFalse(pair.target.isAuthorized(id));
        assertEquals("APPLIED", pair.target.apply(nextGrant));
        assertTrue(pair.target.isAuthorized(id));
        assertEquals(regranted.epoch(), pair.target.get(id).epoch());
        assertEquals(original.subjectId(), pair.target.get(id).subjectId());
        assertEquals(accountIdentities(pair.campus, id), accountIdentities(pair.external, id));
    }

    @Test
    void withdrawalClearsOnlyImportedAccountDataRetainsIdentityAndKeepsEffectsAndOtherDomainsExplicitlyPending() {
        var pair = pair();
        long id = user(pair.campus, "withdraw_projection", true);
        pair.source.onRegistered(id, true, POLICY);
        var grant = pair.source.export(claim(pair.source, id, "UPSERT"));
        pair.target.apply(grant);
        var identities = accountIdentities(pair.external, id);
        pair.target.completeEffects(id, pair.target.effectsVersion(id));
        assertFalse(pair.target.pendingEffects(100).contains(id));
        var withdrawn = pair.target.withdraw(id);
        assertNotNull(withdrawn.withdrawalRequestId());
        assertFalse(pair.target.isAuthorized(id));
        assertEquals(0, count(pair.external, "public.users"));
        assertEquals(0, count(pair.external, "public.contacts"));
        assertEquals(0, count(pair.external, "public.verifications"));
        assertEquals(0, count(pair.external, "public.privacy"));
        assertEquals(0, count(pair.external, "public.user_info"));
        assertEquals(identities, pair.external.runtime().queryForList("SELECT entity_type,numeric_id,global_id,origin_site FROM eatwhat_identity.entity_identity WHERE entity_type IN ('users','contacts','verifications','privacy') ORDER BY entity_type,numeric_id"));
        assertTrue(pair.target.pendingEffects(100).contains(id));
        assertEquals("ACCOUNT_CLEARED_EFFECTS_PENDING", pair.target.get(id).cleanupStatus());
        assertEquals("STALE", pair.target.apply(grant));
        var authority = pair.source.acceptWithdrawal(grant.subjectId(), withdrawn.withdrawalRequestId());
        assertEquals(authority.epoch(), pair.source.acceptWithdrawal(grant.subjectId(), withdrawn.withdrawalRequestId()).epoch());
        pair.target.acknowledgeWithdrawal(grant.subjectId(), withdrawn.withdrawalRequestId(), authority.epoch());
        pair.target.completeEffects(id, pair.target.effectsVersion(id));
        assertEquals("ACCOUNT_COMPLETE_DOMAINS_PENDING", pair.target.get(id).cleanupStatus());
        assertNotNull(pair.campus.runtime().queryForObject("SELECT nick_name FROM public.users WHERE id=?", String.class, id));
        assertFalse(pair.target.isAuthorized(id));
        pair.source.grant(id, POLICY);
        assertEquals("APPLIED", pair.target.apply(pair.source.export(claim(pair.source, id, "UPSERT"))));
        assertEquals(identities, accountIdentities(pair.external, id));
        assertTrue(pair.target.isAuthorized(id), "A new authority epoch rebuilds exactly the purged identity");
    }

    @Test
    void effectsCompletionFromBeforeWithdrawalCannotClearANewerCleanupRequirement() {
        var pair = pair();
        long id = user(pair.campus, "effects_fence", false);
        pair.source.onRegistered(id, true, POLICY);
        pair.target.apply(pair.source.export(claim(pair.source, id, "UPSERT")));
        long oldEffects = pair.target.effectsVersion(id);
        assertTrue(pair.target.pendingEffects(100).contains(id));
        pair.target.withdraw(id);
        long cleanupEffects = pair.target.effectsVersion(id);
        assertTrue(cleanupEffects > oldEffects);
        pair.target.completeEffects(id, oldEffects);
        assertTrue(pair.target.pendingEffects(100).contains(id));
        assertEquals("ACCOUNT_CLEARED_EFFECTS_PENDING", pair.target.get(id).cleanupStatus());
        pair.target.requireEffects(id);
        pair.target.completeEffects(id, cleanupEffects);
        assertTrue(pair.target.pendingEffects(100).contains(id));
        pair.target.completeEffects(id, pair.target.effectsVersion(id));
        assertFalse(pair.target.pendingEffects(100).contains(id));
        assertEquals("ACCOUNT_COMPLETE_DOMAINS_PENDING", pair.target.get(id).cleanupStatus());
    }

    @Test
    void sourceAccountDeletionProducesAPayloadFreePurgeAndCannotLeaveALiveExternalProjection() {
        var pair = pair();
        long id = user(pair.campus, "deleted_source", false);
        pair.source.onRegistered(id, true, POLICY);
        var old = pair.source.export(claim(pair.source, id, "UPSERT"));
        pair.target.apply(old);
        pair.campus.runtime().update("DELETE FROM public.users WHERE id=?", id);
        assertEquals("WITHDRAWN", pair.source.get(id).status());
        assertTrue(pair.source.get(id).epoch() > old.epoch());
        var purge = pair.source.export(claim(pair.source, id, "PURGE"));
        assertNotNull(purge);
        assertNull(purge.data());
        assertEquals("APPLIED", pair.target.apply(purge));
        assertEquals(0, count(pair.external, "public.users"));
        assertEquals("STALE", pair.target.apply(old));
        assertFalse(pair.target.isAuthorized(id));
    }

    @Test
    void foreignBusinessDependencyBlocksPurgeWithoutDeletingBusinessRowsOrAllowingLogin() {
        var pair = pair();
        long id = user(pair.campus, "dependent_account", false);
        pair.source.onRegistered(id, true, POLICY);
        pair.target.apply(pair.source.export(claim(pair.source, id, "UPSERT")));
        // A fixture FK represents a later business domain; cleanup must discover it, not cascade around it.
        pair.external.admin().execute("CREATE TABLE public.synthetic_owned_data(id int PRIMARY KEY,account_id bigint REFERENCES public.users(id) ON DELETE CASCADE)");
        pair.external.runtime().update("INSERT INTO public.synthetic_owned_data VALUES(1,?)", id);
        pair.target.withdraw(id);
        assertFalse(pair.target.isAuthorized(id));
        assertEquals("BLOCKED_DEPENDENCIES", pair.target.get(id).cleanupStatus());
        assertEquals(1, count(pair.external, "public.users"));
        assertEquals(1, count(pair.external, "public.synthetic_owned_data"));
        var blockedCleanup = claim(pair.target, id, "CLEANUP");
        assertEquals("RETRY", pair.target.runCleanup(blockedCleanup));
        pair.target.retry(blockedCleanup);
        assertEquals("READY", pair.external.admin().queryForObject("SELECT status FROM eatwhat_consent.job WHERE job_id=?", String.class, blockedCleanup.jobId()));
        assertTrue(pair.external.admin().queryForObject("SELECT available_at>clock_timestamp() FROM eatwhat_consent.job WHERE job_id=?", Boolean.class, blockedCleanup.jobId()));
        pair.external.runtime().update("DELETE FROM public.synthetic_owned_data WHERE id=1");
        pair.external.admin().update("UPDATE eatwhat_consent.job SET available_at=clock_timestamp()-interval '1 millisecond' WHERE job_id=?", blockedCleanup.jobId());
        var retried = claim(pair.target, id, "CLEANUP");
        assertEquals(blockedCleanup.jobId(), retried.jobId());
        assertNotEquals(blockedCleanup.leaseToken(), retried.leaseToken());
        assertEquals(blockedCleanup.attempts() + 1, retried.attempts());
        assertEquals("DONE", pair.target.runCleanup(retried));
        pair.target.complete(retried);
        assertEquals(0, count(pair.external, "public.users"));
        assertEquals("DONE", pair.external.admin().queryForObject("SELECT status FROM eatwhat_consent.job WHERE job_id=?", String.class, retried.jobId()));
    }

    @Test
    void oldEpochCleanupCannotDeleteAccountAfterANewAuthorityGrantEvenOnceItsDependencyIsGone() {
        var pair = pair();
        long id = user(pair.campus, "old_cleanup", false);
        pair.source.onRegistered(id, true, POLICY);
        pair.target.apply(pair.source.export(claim(pair.source, id, "UPSERT")));
        pair.external.admin().execute("CREATE TABLE public.synthetic_owned_data(id int PRIMARY KEY,account_id bigint REFERENCES public.users(id) ON DELETE CASCADE)");
        pair.external.runtime().update("INSERT INTO public.synthetic_owned_data VALUES(1,?)", id);
        var withdrawn = pair.target.withdraw(id);
        var accepted = pair.source.acceptWithdrawal(withdrawn.subjectId(), withdrawn.withdrawalRequestId());
        pair.target.acknowledgeWithdrawal(withdrawn.subjectId(), withdrawn.withdrawalRequestId(), accepted.epoch());
        var oldCleanup = claim(pair.target, id, "CLEANUP");
        pair.source.grant(id, POLICY);
        assertEquals("APPLIED", pair.target.apply(pair.source.export(claim(pair.source, id, "UPSERT"))));
        assertTrue(pair.target.get(id).epoch() > oldCleanup.epoch());
        assertTrue(pair.target.isAuthorized(id));
        pair.external.runtime().update("DELETE FROM public.synthetic_owned_data WHERE id=1");
        assertEquals("DISCARD", pair.target.runCleanup(oldCleanup));
        pair.target.discard(oldCleanup);
        assertEquals(1, count(pair.external, "public.users"));
        assertEquals("GRANTED", pair.target.get(id).status());
        assertTrue(pair.target.isAuthorized(id));
    }

    @Test
    void cleanupLocksParentBeforeDependencyScanSoConcurrentCascadeChildCannotCommitAndThenBeSilentlyDeleted() throws Exception {
        var pair = pair();
        long id = user(pair.campus, "cleanup_child_race", false);
        pair.source.onRegistered(id, true, POLICY);
        pair.target.apply(pair.source.export(claim(pair.source, id, "UPSERT")));
        pair.external.admin().execute("CREATE TABLE public.synthetic_owned_data(id int PRIMARY KEY,account_id bigint REFERENCES public.users(id) ON DELETE CASCADE)");
        pair.external.runtime().update("INSERT INTO public.synthetic_owned_data VALUES(1,?)", id);
        pair.target.withdraw(id);
        assertEquals("BLOCKED_DEPENDENCIES", pair.target.get(id).cleanupStatus());
        assertEquals(1, count(pair.external, "public.synthetic_owned_data"));
        assertEquals(1, count(pair.external, "public.users"));
        pair.external.runtime().update("DELETE FROM public.synthetic_owned_data WHERE id=1");
        pair.external.admin().execute("CREATE TABLE public.synthetic_scan_barrier(id int PRIMARY KEY,account_id bigint REFERENCES public.users(id) ON DELETE CASCADE)");
        var job = pair.target.claim(32, Duration.ofSeconds(30)).stream().filter(j -> j.kind().equals("CLEANUP")).findFirst().orElseThrow();
        var pool = Executors.newFixedThreadPool(2);
        Future<String> cleanup = null;
        Future<String> insert = null;
        try (var barrier = pair.external.admin().getDataSource().getConnection()) {
            barrier.setAutoCommit(false);
            try (var statement = barrier.createStatement()) {
                statement.execute("LOCK TABLE public.synthetic_scan_barrier IN ACCESS EXCLUSIVE MODE");
            }
            try {
                cleanup = pool.submit(() -> pair.target.runCleanup(job));
                // Pause during SELECT of a dependency, before DELETE itself can supply a parent row lock.
                await(() -> pair.external.admin().queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid
                          WHERE a.datname=current_database() AND a.usename=? AND NOT l.granted
                            AND l.locktype='relation' AND l.relation='public.synthetic_scan_barrier'::regclass)
                        """, Boolean.class, pair.external.runtimeUsername()));
                var incoming = pool.submit(() -> {
                    try {
                        pair.external.runtime().update("INSERT INTO public.synthetic_owned_data VALUES(2,?)", id);
                        return "COMMITTED";
                    } catch (DataAccessException failure) {
                        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                            if (cause instanceof java.sql.SQLException sql) return sql.getSQLState();
                        }
                        throw failure;
                    }
                });
                insert = incoming;
                BooleanSupplier waitingForParent = () -> pair.external.admin().queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid
                          WHERE a.datname=current_database() AND a.usename=? AND NOT l.granted
                            AND l.locktype='transactionid' AND a.query LIKE 'INSERT INTO public.synthetic_owned_data%')
                        """, Boolean.class, pair.external.runtimeUsername());
                await(() -> incoming.isDone() || waitingForParent.getAsBoolean());
                assertFalse(incoming.isDone(), "A new cascade child must not commit while cleanup is checking dependencies");
                assertTrue(waitingForParent.getAsBoolean(), "The INSERT must wait for the parent row, not the unrelated scan barrier");
                barrier.commit();
                assertEquals("DONE", cleanup.get(12, TimeUnit.SECONDS));
                pair.target.complete(job);
                assertEquals("23503", incoming.get(12, TimeUnit.SECONDS), "After parent cleanup, the uncommitted child must fail its FK");
                assertEquals(0, count(pair.external, "public.users"));
                assertEquals(0, count(pair.external, "public.synthetic_owned_data"));
            } finally {
                barrier.rollback();
            }
        } finally {
            if (cleanup != null) cleanup.cancel(true);
            if (insert != null) insert.cancel(true);
            pool.shutdownNow();
        }
    }

    @Test
    void cleanupLeaseExpiringDuringDeleteRollsBackAccountDeletionAndEffectsProgress() {
        var pair = pair();
        long id = user(pair.campus, "cleanup_expiry", false);
        pair.source.onRegistered(id, true, POLICY);
        pair.target.apply(pair.source.export(claim(pair.source, id, "UPSERT")));
        pair.external.admin().execute("CREATE TABLE public.synthetic_owned_data(id int PRIMARY KEY,account_id bigint REFERENCES public.users(id) ON DELETE CASCADE)");
        pair.external.runtime().update("INSERT INTO public.synthetic_owned_data VALUES(1,?)", id);
        pair.target.withdraw(id);
        pair.external.runtime().update("DELETE FROM public.synthetic_owned_data WHERE id=1");
        pair.external.admin().execute("""
                CREATE FUNCTION public.delay_account_delete_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_sleep(2.2); RETURN OLD; END $$
                """);
        pair.external.admin().execute("CREATE TRIGGER delay_account_delete_for_test BEFORE DELETE ON public.users FOR EACH ROW EXECUTE FUNCTION public.delay_account_delete_for_test()");
        long beforeEffects = pair.target.effectsVersion(id);
        var job = pair.target.claim(32, Duration.ofSeconds(2)).stream().filter(j -> j.kind().equals("CLEANUP")).findFirst().orElseThrow();
        try {
            assertThrows(IllegalStateException.class, () -> pair.target.runCleanup(job));
            assertEquals(1, count(pair.external, "public.users"));
            assertEquals(1, count(pair.external, "public.privacy"));
            assertEquals(beforeEffects, pair.target.effectsVersion(id));
            assertEquals("BLOCKED_DEPENDENCIES", pair.target.get(id).cleanupStatus());
        } finally {
            pair.external.admin().execute("DROP TRIGGER delay_account_delete_for_test ON public.users");
            pair.external.admin().execute("DROP FUNCTION public.delay_account_delete_for_test()");
        }
        var recovered = claim(pair.target, id, "CLEANUP");
        assertEquals(job.jobId(), recovered.jobId());
        assertEquals("DONE", pair.target.runCleanup(recovered));
        pair.target.complete(recovered);
        assertEquals(0, count(pair.external, "public.users"));
    }

    @Test
    void externalOfflineRegistrationAndWithdrawalRetainTheOnlyCopyUntilCampusHasArchivedTheSameIdentity() {
        var pair = pair();
        long id = user(pair.external, "offline_external", true);
        var provisional = pair.target.onRegistered(id, true, POLICY);
        assertEquals(0, id % 2);
        assertEquals("PROVISIONAL", provisional.status());
        assertFalse(pair.target.isAuthorized(id));
        var identity = accountIdentities(pair.external, id);
        pair.target.withdraw(id);
        assertEquals("AWAITING_CAMPUS", pair.target.get(id).cleanupStatus());
        assertEquals(1, count(pair.external, "public.users"));
        var jobs = pair.target.claim(32, LEASE);
        var archiveJob = jobs.stream().filter(j -> j.kind().equals("REGISTER")).max(java.util.Comparator.comparingLong(Job::revision)).orElseThrow();
        var cleanup = jobs.stream().filter(j -> j.kind().equals("CLEANUP")).findFirst().orElseThrow();
        assertEquals("RETRY", pair.target.runCleanup(cleanup));
        pair.target.retry(cleanup);
        assertEquals(1, count(pair.external, "public.users"), "Unreachable campus must not cost the only account copy");
        var archive = pair.target.export(archiveJob);
        assertNotNull(archive);
        assertEquals("WITHDRAWN", archive.status());
        assertEquals("APPLIED", pair.source.acceptRegistration(archive));
        assertEquals(identity, accountIdentities(pair.campus, id));
        assertEquals("WITHDRAWN", pair.source.get(id).status());
        pair.target.complete(archiveJob);
        assertEquals(0, count(pair.external, "public.users"));
        assertEquals(1, count(pair.campus, "public.users"));
        assertFalse(pair.target.isAuthorized(id));
        assertNoEvents(pair.campus); assertNoEvents(pair.external);
    }

    @Test
    void crossSiteDuplicateUsernameBecomesConflictWithoutMergingOrOverwritingEitherAccount() {
        var pair = pair();
        long campus = user(pair.campus, "same_name", false);
        pair.source.onRegistered(campus, true, POLICY);
        long external = user(pair.external, "same_name", false);
        pair.target.onRegistered(external, true, POLICY);
        UUID campusGid = pair.source.get(campus).subjectId();
        UUID externalGid = pair.target.get(external).subjectId();
        assertNotEquals(campus, external);
        assertNotEquals(campusGid, externalGid);
        var job = claim(pair.target, external, "REGISTER");
        assertEquals("CONFLICT", pair.source.acceptRegistration(pair.target.export(job)));
        pair.target.conflict(job);
        assertEquals("CONFLICT", pair.target.get(external).backfillStatus());
        assertFalse(pair.target.isAuthorized(external));
        assertEquals(1, count(pair.campus, "public.users"));
        assertEquals(1, count(pair.external, "public.users"));
        assertEquals(campusGid, pair.source.get(campus).subjectId());
        assertEquals(externalGid, pair.target.get(external).subjectId());
        assertEquals(campus, pair.campus.runtime().queryForObject("SELECT id FROM public.users WHERE nick_name='same_name'", Long.class));
        assertEquals(external, pair.external.runtime().queryForObject("SELECT id FROM public.users WHERE nick_name='same_name'", Long.class));
        assertEquals(1, count(pair.campus, "eatwhat_consent.conflict_receipt"));
    }

    @Test
    void dirtyExternalProjectionCannotRefreshItsAuthorizationFromANewerSourceSnapshot() {
        var pair = pair();
        long id = user(pair.campus, "dirty_projection", false);
        pair.source.onRegistered(id, true, POLICY);
        var old = pair.source.export(claim(pair.source, id, "UPSERT"));
        pair.target.apply(old);
        var originalExpiry = pair.target.get(id).validUntil();
        pair.external.runtime().update("UPDATE public.users SET avatar='local-conflicting-avatar' WHERE id=?", id);
        pair.campus.runtime().update("UPDATE public.users SET avatar='source-new-avatar' WHERE id=?", id);
        var newer = pair.source.export(claim(pair.source, id, "UPSERT"));
        assertEquals("CONFLICT", pair.target.apply(newer));
        assertEquals(originalExpiry, pair.target.get(id).validUntil());
        assertEquals("local-conflicting-avatar", pair.external.runtime().queryForObject("SELECT avatar FROM public.users WHERE id=?", String.class, id));
        assertEquals("CONFLICT", pair.target.get(id).backfillStatus());
    }

    @Test
    void concurrentJobClaimsAreDisjointAndExpiredWorkerCannotCompleteOrRetryANewOwnersLease() throws Exception {
        var db = database(SiteProperties.Mode.CAMPUS, "campus-store");
        UUID peer = UUID.randomUUID();
        var store = store(db, "external-store", peer);
        for (int i = 0; i < 8; i++) store.onRegistered(user(db, "job_" + i, false), true, POLICY);
        var before = sequences(db);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Job>>> futures = new ArrayList<>();
        List<Job> oldJobs = new ArrayList<>();
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return store(db, "external-store", peer).claim(4, LEASE);
            }));
            start.countDown();
            for (var future : futures) oldJobs.addAll(future.get(12, TimeUnit.SECONDS));
        } finally { start.countDown(); futures.forEach(f -> f.cancel(true)); pool.shutdownNow(); }
        assertEquals(8, oldJobs.size());
        assertEquals(8, new HashSet<>(oldJobs.stream().map(Job::jobId).toList()).size());
        assertTrue(oldJobs.stream().allMatch(job -> job.attempts() == 1));
        db.admin().update("UPDATE eatwhat_consent.job SET lease_until=clock_timestamp()-interval '1 millisecond' WHERE status='LEASED'");
        var recoveredStore = store(db, "external-store", peer);
        var recovered = recoveredStore.claim(32, LEASE);
        assertEquals(Set.copyOf(oldJobs.stream().map(Job::jobId).toList()), Set.copyOf(recovered.stream().map(Job::jobId).toList()));
        assertTrue(recovered.stream().allMatch(job -> job.attempts() == 2));
        for (var old : oldJobs) {
            assertNull(store.export(old));
            store.complete(old);
            store.retry(old);
        }
        assertEquals(8, db.admin().queryForObject("SELECT count(*) FROM eatwhat_consent.job WHERE status='LEASED'", Integer.class));
        for (var current : recovered) { assertNotNull(recoveredStore.export(current)); recoveredStore.complete(current); }
        assertEquals(8, db.admin().queryForObject("SELECT count(*) FROM eatwhat_consent.job WHERE status='READY' AND attempts=2 AND available_at>clock_timestamp()", Integer.class));
        assertEquals(before, sequences(db));
        assertNoEvents(db);
    }

    @Test
    void discardedOrExpiredRegistrationJobNeverMarksTheOnlyExternalAccountAsArchivedOrPurgesIt() {
        var db = database(SiteProperties.Mode.EXTERNAL, "external-store");
        var store = store(db, "campus-store", UUID.randomUUID());
        long discardedId = user(db, "discard_archive", false);
        store.onRegistered(discardedId, true, POLICY);
        store.withdraw(discardedId);
        var discarded = claim(store, discardedId, "REGISTER");
        store.discard(discarded);
        assertFalse(db.admin().queryForObject("SELECT source_archived FROM eatwhat_consent.subject WHERE user_id=?", Boolean.class, discardedId));
        assertEquals("AWAITING_CAMPUS", store.get(discardedId).cleanupStatus());
        assertEquals(1, count(db, "public.users"));

        long expiredId = user(db, "expired_archive", false);
        store.onRegistered(expiredId, true, POLICY);
        store.withdraw(expiredId);
        var expired = claim(store, expiredId, "REGISTER");
        db.admin().update("UPDATE eatwhat_consent.job SET lease_until=clock_timestamp()-interval '1 millisecond' WHERE job_id=?", expired.jobId());
        store.complete(expired);
        assertFalse(db.admin().queryForObject("SELECT source_archived FROM eatwhat_consent.subject WHERE user_id=?", Boolean.class, expiredId));
        assertEquals("AWAITING_CAMPUS", store.get(expiredId).cleanupStatus());
        assertEquals("LEASED", db.admin().queryForObject("SELECT status FROM eatwhat_consent.job WHERE job_id=?", String.class, expired.jobId()));
        assertEquals(2, count(db, "public.users"));
    }

    private record Pair(EventFoundationTestSupport.Database campus, EventFoundationTestSupport.Database external,
                        ConsentStore source, ConsentStore target) { }
    private static Pair pair() {
        var campus = database(SiteProperties.Mode.CAMPUS, "campus-store");
        var external = database(SiteProperties.Mode.EXTERNAL, "external-store");
        return new Pair(campus, external, store(campus, external.site().getId(), external.site().getInstanceId()),
                store(external, campus.site().getId(), campus.site().getInstanceId()));
    }
    private static EventFoundationTestSupport.Database database(SiteProperties.Mode mode, String id) {
        var properties = new SiteProperties(); properties.setMode(mode); properties.setId(id); properties.setInstanceId(UUID.randomUUID());
        var db = EventFoundationTestSupport.createDatabase(POSTGRES, properties);
        // Every new JDBC connection inherits finite waits, including failing concurrency tests.
        db.admin().execute("ALTER ROLE " + db.runtimeUsername() + " SET statement_timeout='12s'");
        db.admin().execute("ALTER ROLE " + db.runtimeUsername() + " SET lock_timeout='8s'");
        return db;
    }
    private static ConsentStore store(EventFoundationTestSupport.Database db, String peer, UUID instance) {
        return new ConsentStore(db.runtime(), db.transactions(), db.site(), POLICY, TTL, Duration.ofMinutes(2), peer, instance);
    }
    private static long user(EventFoundationTestSupport.Database db, String name, boolean history) {
        return db.transactions().required(() -> {
            long id = db.runtime().queryForObject("INSERT INTO public.users(nick_name,password_hash) VALUES(?,?) RETURNING id", Long.class,
                    name, "synthetic-password-hash-" + name);
            db.runtime().update("INSERT INTO public.privacy(account_id,following,follower) VALUES(?,false,true)", id);
            if (history) {
                db.runtime().update("INSERT INTO public.contacts(account_id,email) VALUES(?,?)", id, name + "@example.invalid");
                db.runtime().update("INSERT INTO public.verifications(account_id,method,verified,verified_email) VALUES(?,'school_email',true,?)", id, name + "@school.invalid");
                db.runtime().update("INSERT INTO public.user_info(id,gender,signature) VALUES(?,'other','synthetic-history-signature')", id);
            }
            return id;
        });
    }
    private static Job claim(ConsentStore store, long id, String kind) {
        return store.claim(32, LEASE).stream().filter(job -> job.userId() == id && kind.equals(job.kind()))
                .max(java.util.Comparator.comparingLong(Job::epoch).thenComparingLong(Job::revision)).orElseThrow();
    }
    private static int count(EventFoundationTestSupport.Database db, String trustedTable) {
        return db.admin().queryForObject("SELECT count(*) FROM " + trustedTable, Integer.class);
    }
    private static List<Map<String,Object>> sequences(EventFoundationTestSupport.Database db) {
        return db.admin().queryForList("SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename");
    }
    private static List<Map<String,Object>> accountIdentities(EventFoundationTestSupport.Database db, long id) {
        return db.runtime().queryForList("""
                SELECT entity_type,numeric_id,global_id,origin_site FROM eatwhat_identity.entity_identity
                WHERE (entity_type='users' AND numeric_id=?) OR (entity_type='contacts' AND numeric_id IN (SELECT id FROM public.contacts WHERE account_id=?))
                OR (entity_type='verifications' AND numeric_id IN (SELECT id FROM public.verifications WHERE account_id=?))
                OR (entity_type='privacy' AND numeric_id IN (SELECT id FROM public.privacy WHERE account_id=?)) ORDER BY entity_type,numeric_id
                """, id,id,id,id);
    }
    private static void assertNoEvents(EventFoundationTestSupport.Database db) {
        assertEquals(0, count(db, "eatwhat_sync.outbox"));
        assertEquals(0, count(db, "eatwhat_sync.inbox"));
        assertEquals(0, count(db, "eatwhat_sync.command_dedup"));
        String metadata = db.admin().queryForObject("SELECT coalesce(string_agg(to_jsonb(j)::text,''),'') FROM eatwhat_consent.job j", String.class);
        assertFalse(metadata.contains("synthetic-password"));
        assertFalse(metadata.contains("@example.invalid"));
        assertFalse(metadata.contains("@school.invalid"));
        assertFalse(metadata.contains("synthetic-history-signature"));
    }
    private static void invalidProof(org.junit.jupiter.api.function.Executable action) {
        assertEquals("PROOF_INVALID", assertThrows(ConsentStore.ConsentException.class, action).code());
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "Expected real PostgreSQL lock wait was not observed");
    }
}
