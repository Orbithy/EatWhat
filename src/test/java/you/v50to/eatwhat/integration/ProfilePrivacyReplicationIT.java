package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.account.ConsentModels;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.dto.PrivacyDTO;
import you.v50to.eatwhat.data.dto.UpdateUserInfoDTO;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.EventHandlerRegistry;
import you.v50to.eatwhat.sync.InboxService;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.sync.privacy.PrivacyCommands;
import you.v50to.eatwhat.sync.privacy.PrivacyEventHandler;
import you.v50to.eatwhat.sync.privacy.PrivacyEvents;
import you.v50to.eatwhat.sync.privacy.PrivacyStore;
import you.v50to.eatwhat.sync.profile.ProfileCommands;
import you.v50to.eatwhat.sync.profile.ProfileConflictService;
import you.v50to.eatwhat.sync.profile.ProfileEventHandler;
import you.v50to.eatwhat.sync.profile.ProfileEvents;
import you.v50to.eatwhat.sync.profile.ProfileStore;
import you.v50to.eatwhat.sync.DeliveryService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PostgreSQL profile/privacy facts on two consented sites; no public site data is used. */
class ProfilePrivacyReplicationIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("profile-privacy");
    private static final String POLICY = "profile-privacy-test-policy";
    private static final Duration LEASE = Duration.ofSeconds(20);
    private static final EventCodec CODEC = new EventCodec();

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void multiwriterExternalCanInitializeBeforeCampusAndBothIndependentFieldsConverge() throws Exception {
        Pair pair=pair(); pair.campus().profileCommands().setMultiwriter(true,null); pair.external().profileCommands().setMultiwriter(true,null);
        assertTrue(pair.external().profileCommands().canEdit(pair.userId()));
        updateProfile(pair.external(),pair.userId(),"signature","external-first");
        updateProfile(pair.campus(),pair.userId(),"gender","female");
        var a=new ProfileEventHandler(pair.campus().db().runtime(),pair.campus().profiles(),new EntityIdentityService(pair.campus().db().runtime()),pair.campus().consent(),pair.campus().db().site(),2,null);
        var b=new ProfileEventHandler(pair.external().db().runtime(),pair.external().profiles(),new EntityIdentityService(pair.external().db().runtime()),pair.external().consent(),pair.external().db().site(),2,null);
        for(var event:profileEvents(pair.external())) pair.campus().db().transactions().required(()->{assertTrue(a.dependenciesSatisfied(event));a.apply(event);return null;});
        for(var event:profileEvents(pair.campus())) pair.external().db().transactions().required(()->{assertTrue(b.dependenciesSatisfied(event));b.apply(event);return null;});
        assertEquals("external-first",publicValue(pair.campus(),pair.userId(),"signature"));
        assertEquals("female",publicValue(pair.external(),pair.userId(),"gender"));
        assertEquals(0,count(pair.campus(),"SELECT count(*) FROM eatwhat_sync.profile_conflict WHERE status='OPEN'"));
    }

    @Test
    void multiwriterConcurrentAdministratorDecisionsRemainVisibleAndConvergeToRestrictedProjection() throws Exception {
        Pair pair=pair();
        var aa=new you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts(pair.campus().db().runtime(),pair.campus().db().transactions(),pair.campus().db().site(),true);
        var ab=new you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts(pair.external().db().runtime(),pair.external().db().transactions(),pair.external().db().site(),true);
        for(var site:List.of(pair.campus(),pair.external())) {
            site.db().admin().update("UPDATE users SET role='admin' WHERE id=?",pair.userId());
            site.db().admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true");
        }
        aa.initialize(pair.userId());ab.initialize(pair.userId());
        pair.campus().profileCommands().setMultiwriter(true,aa);pair.external().profileCommands().setMultiwriter(true,ab);
        updateProfile(pair.campus(),pair.userId(),"signature","choice-a");
        updateProfile(pair.external(),pair.userId(),"signature","choice-b");
        var ha=new ProfileEventHandler(pair.campus().db().runtime(),pair.campus().profiles(),new EntityIdentityService(pair.campus().db().runtime()),pair.campus().consent(),pair.campus().db().site(),2,aa);
        var hb=new ProfileEventHandler(pair.external().db().runtime(),pair.external().profiles(),new EntityIdentityService(pair.external().db().runtime()),pair.external().consent(),pair.external().db().site(),2,ab);
        for(var e:profileEvents(pair.campus())) applyV2(pair.external(),hb,e);
        for(var e:profileEvents(pair.external())) applyV2(pair.campus(),ha,e);
        assertNull(publicValue(pair.campus(),pair.userId(),"signature"));
        assertNull(publicValue(pair.external(),pair.userId(),"signature"));
        int selected=0;
        for(var site:List.of(pair.campus(),pair.external())) {
            UUID conflict=site.db().runtime().queryForObject("SELECT conflict_id FROM eatwhat_sync.profile_conflict WHERE field_group='signature'",UUID.class);
            long revision=count(site,"SELECT revision FROM eatwhat_sync.profile_conflict WHERE conflict_id=?",conflict);
            List<UUID> heads=site.db().runtime().query("SELECT value->>'id' FROM eatwhat_sync.profile_conflict c CROSS JOIN LATERAL jsonb_array_elements(c.heads) value WHERE conflict_id=? ORDER BY value->>'id'",(r,n)->UUID.fromString(r.getString(1)),conflict);
            assertTrue(site.profileCommands().resolveConflict(conflict,revision,heads.get(selected++),pair.userId()));
        }
        ab.receive(pair.userId(),aa.changes(pair.userId()),aa.witnesses(pair.userId()),new you.v50to.eatwhat.sync.transport.NodeIdentity(pair.campus().db().site().getId(),pair.campus().db().site().getInstanceId()));
        aa.receive(pair.userId(),ab.changes(pair.userId()),ab.witnesses(pair.userId()),new you.v50to.eatwhat.sync.transport.NodeIdentity(pair.external().db().site().getId(),pair.external().db().site().getInstanceId()));
        for(var e:profileEvents(pair.campus())) applyV2(pair.external(),hb,e);
        for(var e:profileEvents(pair.external())) applyV2(pair.campus(),ha,e);
        for(var site:List.of(pair.campus(),pair.external())) {
            assertNull(publicValue(site,pair.userId(),"signature"));
            assertEquals(1,count(site,"SELECT count(*) FROM eatwhat_sync.profile_conflict WHERE status='OPEN'"));
            assertEquals(2,count(site,"SELECT jsonb_array_length(heads) FROM eatwhat_sync.profile_conflict WHERE field_group='signature'"));
            assertEquals(2,count(site,"SELECT count(*) FROM eatwhat_sync.profile_authorization WHERE outcome='AUTHORIZED'"));
        }
    }

    private void applyV2(Site target,ProfileEventHandler handler,SyncEvent event) {
        target.db().transactions().required(()->{assertTrue(handler.dependenciesSatisfied(event));handler.apply(event);return null;});
    }

    @Test
    void multiwriterBackfillsIndependentExternalProfileAfterLegacyLeaseExpires() throws Exception {
        Pair pair=pair();var site=pair.external();
        site.db().admin().update("UPDATE eatwhat_consent.subject SET valid_until=clock_timestamp()-interval '1 day' WHERE user_id=?",pair.userId());
        site.profileCommands().setMultiwriter(true,null);
        updateProfile(site,pair.userId(),"signature","offline-pending");
        site.profileCommands().runOnce();assertEquals(0,profileEvents(site).size());
        var accounts=new you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts(site.db().runtime(),site.db().transactions(),site.db().site(),true);
        org.springframework.test.util.ReflectionTestUtils.setField(site.consent(),"multiwriter",accounts);
        site.profileCommands().runOnce();
        assertEquals(1,profileEvents(site).size());
        assertEquals(2,profileEvents(site).getFirst().schemaVersion());
        site.profileCommands().runOnce();assertEquals(1,profileEvents(site).size());
    }

    @Test
    void concurrentRevokedAdministratorProposalIsVisibleAndCanBeReapprovedWithFreshAuthority() throws Exception {
        Pair pair=pair();
        var left=org.mockito.Mockito.mock(you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.class);
        var right=org.mockito.Mockito.mock(you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.class);
        var proof=new you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.AuthorizationEvidence(pair.subjectId(),List.of(UUID.randomUUID()),"admin",false,UUID.randomUUID());
        org.mockito.Mockito.when(left.capture(pair.userId())).thenReturn(proof);
        org.mockito.Mockito.when(right.capture(pair.userId())).thenReturn(proof);
        org.mockito.Mockito.when(left.assess(org.mockito.ArgumentMatchers.any())).thenReturn(you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.AUTHORIZED);
        org.mockito.Mockito.when(right.assess(org.mockito.ArgumentMatchers.any())).thenReturn(you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.AUTHORIZED);
        pair.campus().profileCommands().setMultiwriter(true,left);pair.external().profileCommands().setMultiwriter(true,right);
        var ha=new ProfileEventHandler(pair.campus().db().runtime(),pair.campus().profiles(),new EntityIdentityService(pair.campus().db().runtime()),pair.campus().consent(),pair.campus().db().site(),2,left);
        var hb=new ProfileEventHandler(pair.external().db().runtime(),pair.external().profiles(),new EntityIdentityService(pair.external().db().runtime()),pair.external().consent(),pair.external().db().site(),2,right);
        updateProfile(pair.campus(),pair.userId(),"signature","review-a");updateProfile(pair.external(),pair.userId(),"signature","review-b");
        for(var e:profileEvents(pair.campus()))applyV2(pair.external(),hb,e);
        for(var e:profileEvents(pair.external()))applyV2(pair.campus(),ha,e);
        var conflicts=new ProfileConflictService(pair.campus().db().runtime(),pair.campus().profiles(),pair.campus().profileCommands(),pair.campus().consent(),null,pair.campus().db().site(),pair.campus().peer());
        var c=conflicts.openConflicts().getFirst();UUID selected=UUID.fromString(c.heads().getFirst().get("id").toString());
        assertTrue(pair.campus().profileCommands().resolveConflict(c.conflictId(),c.revision(),selected,pair.userId()));
        var decision=profileEvents(pair.campus()).getLast();
        org.mockito.Mockito.when(right.assess(org.mockito.ArgumentMatchers.any())).thenReturn(you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.CONCURRENT_REVOCATION_REVIEW);
        applyV2(pair.external(),hb,decision);applyV2(pair.external(),hb,decision);
        var reviews=new ProfileConflictService(pair.external().db().runtime(),pair.external().profiles(),pair.external().profileCommands(),pair.external().consent(),null,pair.external().db().site(),pair.external().peer());
        assertEquals(1,reviews.authorizationReviews().size());
        var review=reviews.authorizationReviews().getFirst();assertEquals(decision.eventId(),review.eventId());
        assertNull(publicValue(pair.external(),pair.userId(),"signature"));
        org.mockito.Mockito.when(right.assess(org.mockito.ArgumentMatchers.any())).thenReturn(you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.AUTHORIZED);
        assertTrue(pair.external().profileCommands().resolveConflict(review.conflict().conflictId(),review.conflict().revision(),decision.eventId(),pair.userId()));
        assertTrue(reviews.authorizationReviews().isEmpty());
        applyV2(pair.campus(),ha,profileEvents(pair.external()).getLast());
        assertEquals(publicValue(pair.campus(),pair.userId(),"signature"),publicValue(pair.external(),pair.userId(),"signature"));
        assertEquals(1,count(pair.external(),"SELECT count(*) FROM eatwhat_sync.profile_authorization_review_decision"));
    }

    @Test
    void emptyProfileAndNullValuesEstablishAStableBaselineWithoutCreatingAnEmptyEvent() throws Exception {
        Pair pair = pair();
        long userId = pair.userId();
        UpdateUserInfoDTO empty = new UpdateUserInfoDTO();
        Map<String, Object> before = pair.campus().profileCommands().snapshotBeforeUpdate(userId);
        pair.campus().db().transactions().required(() -> {
            pair.campus().profileCommands().capture(userId, empty, before);
            return null;
        });

        assertEquals(0, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.profile_operation"));
        assertEquals(0, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type=?",
                ProfileEvents.AGGREGATE));
        assertEquals(1, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.profile_domain"));

        updateProfile(pair.campus(), userId, "signature", "first authored value");
        exchange(pair);

        assertEquals("first authored value", publicValue(pair.external(), userId, "signature"));
        Map<String, Object> baseline = ProfileStore.map(pair.campus().db().runtime().queryForObject(
                "SELECT baseline_value::text FROM eatwhat_sync.profile_domain WHERE subject_gid=?",
                String.class, pair.subjectId()));
        assertEquals(5, baseline.size());
        assertNull(baseline.get("avatar"));
        assertNull(baseline.get("birthday"));
        assertNull(baseline.get("gender"));
        assertNull(baseline.get("signature"));
        Map<String, Object> expectedHometown = new LinkedHashMap<>();
        expectedHometown.put("provinceId", null);
        expectedHometown.put("cityId", null);
        assertEquals(expectedHometown, baseline.get("hometown"));
        assertEquals(1, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.profile_operation"));
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM eatwhat_sync.profile_operation"));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type=?",
                ProfileEvents.AGGREGATE), "Applying a profile event must never echo another event");
    }

    @Test
    void firstProfileBaselineWaitsInsteadOfOverwritingAnExternalLocalEdit() throws Exception {
        Pair pair = pair();
        pair.external().db().runtime().update("UPDATE public.user_info SET signature=? WHERE id=?",
                "external-local-before-baseline", pair.userId());
        pair.external().db().runtime().update("UPDATE eatwhat_consent.subject SET local_dirty=true WHERE subject_id=?",
                pair.subjectId());

        updateProfile(pair.campus(), pair.userId(), "signature", "campus-first-baseline-value");
        SyncEvent source = profileEvents(pair.campus()).getFirst();
        receiveAndProcess(pair.external(), source);

        assertEquals("external-local-before-baseline", publicValue(pair.external(), pair.userId(), "signature"));
        assertEquals(0, count(pair.external(),
                "SELECT count(*) FROM eatwhat_sync.profile_domain WHERE subject_gid=?", pair.subjectId()));
        assertEquals(1, count(pair.external(),
                "SELECT count(*) FROM eatwhat_consent.subject WHERE subject_id=? AND local_dirty", pair.subjectId()));
        assertEquals(0, count(pair.external(),
                "SELECT count(*) FROM eatwhat_sync.profile_conflict WHERE subject_gid=?", pair.subjectId()));
        assertEquals(1, count(pair.external(), """
                SELECT count(*) FROM eatwhat_sync.inbox
                WHERE event_id=? AND status='WAITING_DEPENDENCY'
                """, source.eventId()));
    }

    @Test
    void differentProfileFieldsMergeAndConcurrentSameFieldValuesRemainConflicted() throws Exception {
        Pair pair = pair();
        updateProfile(pair.campus(), pair.userId(), "gender", "other");
        exchange(pair);

        updateProfile(pair.campus(), pair.userId(), "signature", "campus signature");
        updateProfile(pair.external(), pair.userId(), "avatar", "avatar/external-profile.jpg");
        exchange(pair);

        assertEquals("campus signature", publicValue(pair.campus(), pair.userId(), "signature"));
        assertEquals("campus signature", publicValue(pair.external(), pair.userId(), "signature"));
        assertEquals("avatar/external-profile.jpg", publicValue(pair.campus(), pair.userId(), "avatar"));
        assertEquals("avatar/external-profile.jpg", publicValue(pair.external(), pair.userId(), "avatar"));

        updateProfile(pair.campus(), pair.userId(), "birthday", "2001-02-03");
        updateProfile(pair.external(), pair.userId(), "birthday", "2002-04-05");
        String campusBirthdayVector = fieldVector(pair.campus(), pair.subjectId(), "birthday");
        String externalBirthdayVector = fieldVector(pair.external(), pair.subjectId(), "birthday");
        exchange(pair);

        for (Site site : List.of(pair.campus(), pair.external())) {
            assertEquals(1, count(site, "SELECT count(*) FROM eatwhat_sync.profile_conflict "
                    + "WHERE subject_gid=? AND field_group='birthday' AND status='OPEN'", pair.subjectId()));
            String heads = site.db().runtime().queryForObject("""
                    SELECT heads::text FROM eatwhat_sync.profile_conflict
                    WHERE subject_gid=? AND field_group='birthday'
                    """, String.class, pair.subjectId());
            assertTrue(heads.contains("2001-02-03"));
            assertTrue(heads.contains("2002-04-05"));
        }
        assertEquals("2001-02-03", publicValue(pair.campus(), pair.userId(), "birthday"));
        assertEquals("2002-04-05", publicValue(pair.external(), pair.userId(), "birthday"));
        assertEquals(campusBirthdayVector, fieldVector(pair.campus(), pair.subjectId(), "birthday"),
                "A concurrent incoming value is a conflict head, not an applied frontier");
        assertEquals(externalBirthdayVector, fieldVector(pair.external(), pair.subjectId(), "birthday"),
                "A concurrent incoming value is a conflict head, not an applied frontier");

        UUID conflictId=pair.campus().db().runtime().queryForObject("SELECT conflict_id FROM eatwhat_sync.profile_conflict WHERE subject_gid=? AND field_group='birthday'",UUID.class,pair.subjectId());
        long revision=count(pair.campus(),"SELECT revision FROM eatwhat_sync.profile_conflict WHERE conflict_id=?",conflictId);
        List<UUID> heads=pair.campus().db().runtime().query("SELECT value->>'id' FROM eatwhat_sync.profile_conflict c CROSS JOIN LATERAL jsonb_array_elements(c.heads) value WHERE c.conflict_id=?",(row,index)->UUID.fromString(row.getString(1)),conflictId);
        assertEquals(2,heads.size());
        var listedConflicts = new ProfileConflictService(pair.campus().db().runtime(), pair.campus().profiles(),
                pair.campus().profileCommands(), pair.campus().consent(), null,
                pair.campus().db().site(), pair.campus().peer()).openConflicts();
        var listed = listedConflicts.stream().filter(conflict -> conflict.conflictId().equals(conflictId))
                .findFirst().orElseThrow();
        assertEquals(revision, listed.revision());
        assertEquals("birthday", listed.fieldGroup());
        assertEquals(2, listed.heads().size());
        var pool=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try {
            var first=pool.submit(()->{start.await();return pair.campus().profileCommands().resolveConflict(conflictId,revision,heads.get(0),pair.userId());});
            var second=pool.submit(()->{start.await();return pair.campus().profileCommands().resolveConflict(conflictId,revision,heads.get(1),pair.userId());});
            start.countDown();
            boolean firstWon=first.get(15,TimeUnit.SECONDS);
            boolean secondWon=second.get(15,TimeUnit.SECONDS);
            assertTrue(firstWon^secondWon,"Expected-revision CAS allows exactly one administrator decision");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1,count(pair.campus(),"SELECT count(*) FROM eatwhat_sync.profile_conflict_audit WHERE conflict_id=?",conflictId));
        assertEquals(1,count(pair.campus(),"SELECT count(*) FROM eatwhat_sync.profile_conflict_audit a JOIN eatwhat_sync.profile_operation o ON o.event_id=a.event_id JOIN eatwhat_sync.outbox b ON b.event_id=a.event_id WHERE a.conflict_id=?",conflictId),
                "The decision audit, profile fact, and Outbox event must commit together");
        assertThrows(IllegalStateException.class,()->pair.external().profileCommands().resolveConflict(conflictId,revision,heads.get(0),pair.userId()));
    }

    @Test
    void reorderedProfileHeadsConvergeToTheSameCausalMaximum() throws Exception {
        Pair pair = pair();
        updateProfile(pair.campus(), pair.userId(), "gender", "other");
        exchange(pair);

        updateProfile(pair.campus(), pair.userId(), "birthday", "2001-02-03"); // A1
        updateProfile(pair.external(), pair.userId(), "birthday", "2002-04-05"); // B1
        updateProfile(pair.campus(), pair.userId(), "birthday", "2003-06-07"); // A2 observes A1 only

        SyncEvent a1 = profileEvent(pair.campus(), "2001-02-03");
        SyncEvent a2 = profileEvent(pair.campus(), "2003-06-07");
        SyncEvent b1 = profileEvent(pair.external(), "2002-04-05");

        receiveAndProcess(pair.campus(), b1);
        receiveAndProcess(pair.external(), a1);
        long operationsBeforeRejectedEdit = count(pair.external(),
                "SELECT count(*) FROM eatwhat_sync.profile_operation WHERE subject_gid=?", pair.subjectId());
        assertThrows(IllegalStateException.class,
                () -> updateProfile(pair.external(), pair.userId(), "birthday", "2005-10-11"));
        assertEquals(operationsBeforeRejectedEdit, count(pair.external(),
                "SELECT count(*) FROM eatwhat_sync.profile_operation WHERE subject_gid=?", pair.subjectId()),
                "An ordinary same-field edit must not silently resolve or append to an open conflict");
        receiveAndProcess(pair.external(), a2);

        for (Site site : List.of(pair.campus(), pair.external())) {
            assertConflictHeads(site, pair.subjectId(), Set.of("2002-04-05", "2003-06-07"),
                    Set.of("2001-02-03"));
        }
    }

    @Test
    void interruptedWorkersCanResumeOnTheSameCommandInstances() throws Exception {
        Pair pair = pair();

        Thread.currentThread().interrupt();
        pair.campus().profileCommands().runOnce();
        assertTrue(Thread.currentThread().isInterrupted(), "The worker must preserve the caller's interrupt signal");
        Thread.interrupted();
        pair.campus().profileCommands().runOnce();
        pair.campus().privacyCommands().runOnce();

        assertEquals(1, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.profile_operation"),
                "The same profile command instance must recover after an interrupted call");
        assertEquals(1, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.privacy_operation"),
                "The same privacy command instance must recover after an interrupted call");
    }

    @Test
    void blockedSubjectsBeforeTheCandidateDoNotStarveProfileOrPrivacyWorkers() throws Exception {
        Pair pair = pair(UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff"));
        UUID target = pair.subjectId();

        for (int blockedIndex = 0; blockedIndex < 65; blockedIndex++) {
            createBlockedSubject(pair, blockedIndex);
        }
        assertTrue(count(pair.campus(), """
                SELECT count(*) FROM eatwhat_consent.subject
                WHERE status='GRANTED' AND identity_ready AND subject_id<? AND user_id<>?
                """, target, pair.userId()) >= 65, "Fixture must place at least 65 non-replicable subjects ahead of its target");

        pair.campus().profileCommands().runOnce();
        pair.campus().privacyCommands().runOnce();

        assertEquals(1, count(pair.campus(),
                "SELECT count(*) FROM eatwhat_sync.profile_domain WHERE subject_gid=?", target),
                "A later valid profile candidate must not be starved by earlier banned candidates");
        assertEquals(1, count(pair.campus(),
                "SELECT count(*) FROM eatwhat_sync.privacy_domain WHERE subject_gid=?", target),
                "A later valid privacy candidate must not be starved by earlier banned candidates");
        assertEquals(1, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type=?",
                ProfileEvents.AGGREGATE));
        assertEquals(1, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type=?",
                PrivacyEvents.AGGREGATE));
    }

    @Test
    void sameValueHeadsAndDisjointFieldFrontiersSurviveRegrantBridge() throws Exception {
        Pair pair = pair();
        updateProfile(pair.campus(), pair.userId(), "signature", "baseline signature");
        exchange(pair);

        updateProfile(pair.campus(), pair.userId(), "gender", "other");
        updateProfile(pair.external(), pair.userId(), "gender", "other");
        exchange(pair);
        assertEquals(0, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.profile_conflict WHERE status='OPEN'"));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM eatwhat_sync.profile_conflict WHERE status='OPEN'"));

        updateProfile(pair.external(), pair.userId(), "signature", "external B1");
        exchange(pair);
        updateProfile(pair.campus(), pair.userId(), "avatar", "avatar/campus-A1.png");
        SyncEvent campusAvatar = profileEvents(pair.campus()).stream()
                .filter(event -> "avatar/campus-A1.png".equals(ProfileEvents.parse(event.payloadJson()).changes().get("avatar")))
                .findFirst().orElseThrow();
        ProfileEvents.Operation avatarOperation = ProfileEvents.parse(campusAvatar.payloadJson());
        assertFalse(avatarOperation.contexts().get("avatar").containsKey(pair.external().db().site().getId()),
                "The avatar's field context must not inherit an unrelated signature operation");
        exchange(pair);

        Map<UUID, Map<String, Long>> campusHeads = pair.campus().profiles()
                .fieldHeads(pair.subjectId(), "avatar", null).stream()
                .collect(java.util.stream.Collectors.toMap(ProfileEvents.Head::id, ProfileEvents.Head::vector));
        Map<UUID, Map<String, Long>> externalHeads = pair.external().profiles()
                .fieldHeads(pair.subjectId(), "avatar", null).stream()
                .collect(java.util.stream.Collectors.toMap(ProfileEvents.Head::id, ProfileEvents.Head::vector));
        assertEquals(campusHeads, externalHeads,
                "Both sites must store identical field head IDs and vectors after applying the same operation");
        assertEquals("external B1", publicValue(pair.campus(), pair.userId(), "signature"));
        assertEquals("external B1", publicValue(pair.external(), pair.userId(), "signature"));

        var withdrawn = pair.campus().consent().withdraw(pair.userId());
        var purge = pair.campus().consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == pair.userId() && job.kind().equals("PURGE")
                        && job.epoch() == withdrawn.epoch()).findFirst().orElseThrow();
        assertEquals("APPLIED", pair.external().consent().apply(pair.campus().consent().export(purge)));
        pair.campus().consent().complete(purge);

        var granted = pair.campus().consent().grant(pair.userId(), POLICY);
        var upsert = pair.campus().consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == pair.userId() && job.kind().equals("UPSERT")
                        && job.epoch() == granted.epoch()).findFirst().orElseThrow();
        assertEquals("APPLIED", pair.external().consent().apply(pair.campus().consent().export(upsert)));
        pair.campus().consent().complete(upsert);
        pair.campus().profileCommands().runOnce();
        pair.external().profileCommands().runOnce();
        pair.campus().privacyCommands().runOnce();
        pair.external().privacyCommands().runOnce();
        exchange(pair);

        assertEquals("avatar/campus-A1.png", publicValue(pair.campus(), pair.userId(), "avatar"));
        assertEquals("avatar/campus-A1.png", publicValue(pair.external(), pair.userId(), "avatar"));
        assertEquals("external B1", publicValue(pair.campus(), pair.userId(), "signature"));
        assertEquals("external B1", publicValue(pair.external(), pair.userId(), "signature"));
        assertEquals(0, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.profile_conflict WHERE status='OPEN'"));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM eatwhat_sync.profile_conflict WHERE status='OPEN'"));

        updateProfile(pair.external(), pair.userId(), "avatar", "avatar/external-A2.png");
        exchange(pair);
        assertEquals("avatar/external-A2.png", publicValue(pair.campus(), pair.userId(), "avatar"));
        assertEquals("avatar/external-A2.png", publicValue(pair.external(), pair.userId(), "avatar"));
        assertEquals(0, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.profile_conflict WHERE status='OPEN'"));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM eatwhat_sync.profile_conflict WHERE status='OPEN'"));
    }

    private static void createBlockedSubject(Pair pair, int index) {
        long userId = pair.campus().db().admin().queryForObject("""
                INSERT INTO public.users(nick_name,password_hash,banned)
                VALUES(?,'synthetic-only',true) RETURNING id
                """, Long.class, "blocked-profile-" + index);
        pair.campus().db().admin().update("INSERT INTO public.user_info(id) VALUES(?)", userId);
        pair.campus().db().admin().update("INSERT INTO public.privacy(account_id) VALUES(?)", userId);
        forceUserIdentity(pair.campus().db(), userId, UUID.fromString(
                "00000000-0000-4000-8000-" + String.format(java.util.Locale.ROOT, "%012d", index + 1000)));
        pair.campus().consent().onRegistered(userId, true, POLICY);
        pair.campus().db().admin().update("""
                UPDATE eatwhat_consent.subject SET identity_ready=true,valid_until=clock_timestamp()+interval '5 minutes',
                  backfill_status='ACCOUNT_COMPLETE_DOMAINS_PENDING' WHERE user_id=?
                """, userId);
    }

    @Test
    void resolutionFirstPeerReopensWithARealWithheldConcurrentHead() throws Exception {
        Pair pair = pair();
        updateProfile(pair.campus(), pair.userId(), "gender", "other");
        exchange(pair);

        updateProfile(pair.campus(), pair.userId(), "birthday", "2001-02-03"); // A1
        updateProfile(pair.external(), pair.userId(), "birthday", "2002-04-05"); // B1
        updateProfile(pair.campus(), pair.userId(), "birthday", "2003-06-07"); // A2 observes A1 only

        SyncEvent b1 = profileEvent(pair.external(), "2002-04-05");
        SyncEvent b2;
        receiveAndProcess(pair.campus(), b1);
        assertConflictHeads(pair.campus(), pair.subjectId(), Set.of("2002-04-05", "2003-06-07"),
                Set.of("2001-02-03"));

        // External remains offline from A1/A2 and authors B2 from its B1 frontier.
        updateProfile(pair.external(), pair.userId(), "birthday", "2004-08-09");
        b2 = profileEvent(pair.external(), "2004-08-09");
        ProfileEvents.Operation b2Operation = ProfileEvents.parse(b2.payloadJson());
        assertFalse(b2Operation.contexts().get("birthday").containsKey(
                pair.campus().db().site().getId()),
                "The withheld offline head must not observe A2");

        UUID conflictId = pair.campus().db().runtime().queryForObject(
                "SELECT conflict_id FROM eatwhat_sync.profile_conflict WHERE subject_gid=? AND field_group='birthday'",
                UUID.class, pair.subjectId());
        long revision = count(pair.campus(),
                "SELECT revision FROM eatwhat_sync.profile_conflict WHERE conflict_id=?", conflictId);
        UUID selectedA2 = pair.campus().db().runtime().queryForObject("""
                SELECT (head->>'id')::uuid FROM eatwhat_sync.profile_conflict c,
                  LATERAL jsonb_array_elements(c.heads) head
                WHERE c.conflict_id=? AND head->>'value'='2003-06-07'
                """, UUID.class, conflictId);
        assertTrue(pair.campus().profileCommands().resolveConflict(
                conflictId, revision, selectedA2, pair.userId()));

        SyncEvent resolution = profileEventWithResolution(pair.campus());
        // The resolution reaches this peer first; B2 is a real local operation withheld from campus.
        receiveAndProcess(pair.external(), resolution);
        receiveAndProcess(pair.campus(), b2);

        Set<String> expectedIds = Set.of(resolution.eventId().toString(), b2.eventId().toString());
        for (Site site : List.of(pair.campus(), pair.external())) {
            assertEquals(1, count(site, "SELECT count(*) FROM eatwhat_sync.profile_conflict "
                    + "WHERE subject_gid=? AND field_group='birthday' AND status='OPEN'", pair.subjectId()));
            Set<String> actualIds = new java.util.HashSet<>(site.db().runtime().query("""
                    SELECT head->>'id' FROM eatwhat_sync.profile_conflict c,
                      LATERAL jsonb_array_elements(c.heads) head
                    WHERE c.subject_gid=? AND c.field_group='birthday' AND c.status='OPEN'
                    """, (row, index) -> row.getString(1), pair.subjectId()));
            assertEquals(expectedIds, actualIds,
                    "A late concurrent head must conflict only with the resolution head, not covered old heads");
            long expectedHistory = site == pair.campus() ? 6 : 4;
            assertEquals(expectedHistory, count(site,
                    "SELECT count(*) FROM eatwhat_sync.profile_operation WHERE subject_gid=?", pair.subjectId()),
                    "All authored and applied profile facts remain immutable history");
        }
        assertNotNull(ProfileEvents.parse(resolution.payloadJson()).resolution());
    }

    @Test
    void committedResolutionSurvivesWithdrawalAndRegrantWhenItsOriginalDeliveryWasWithheld() throws Exception {
        Pair pair = pair();
        updateProfile(pair.campus(), pair.userId(), "gender", "other");
        exchange(pair);

        updateProfile(pair.campus(), pair.userId(), "birthday", "2001-02-03");
        updateProfile(pair.external(), pair.userId(), "birthday", "2002-04-05");
        SyncEvent campusHead = profileEvent(pair.campus(), "2001-02-03");
        SyncEvent externalHead = profileEvent(pair.external(), "2002-04-05");
        receiveAndProcess(pair.external(), campusHead);
        receiveAndProcess(pair.campus(), externalHead);

        UUID conflictId = pair.campus().db().runtime().queryForObject(
                "SELECT conflict_id FROM eatwhat_sync.profile_conflict WHERE subject_gid=? AND field_group='birthday'",
                UUID.class, pair.subjectId());
        long revision = count(pair.campus(),
                "SELECT revision FROM eatwhat_sync.profile_conflict WHERE conflict_id=?", conflictId);
        UUID selectedCampusHead = pair.campus().db().runtime().queryForObject("""
                SELECT (head->>'id')::uuid FROM eatwhat_sync.profile_conflict conflict,
                  LATERAL jsonb_array_elements(conflict.heads) head
                WHERE conflict.conflict_id=? AND head->>'value'='2001-02-03'
                """, UUID.class, conflictId);
        assertTrue(pair.campus().profileCommands().resolveConflict(
                conflictId, revision, selectedCampusHead, pair.userId()));
        SyncEvent oldResolution = profileEventWithResolution(pair.campus());
        assertEquals(1, count(pair.campus(), """
                SELECT count(*) FROM eatwhat_sync.delivery
                WHERE event_id=? AND status='READY'
                """, oldResolution.eventId()), "The committed resolution has not reached the peer yet");

        var withdrawn = pair.campus().consent().withdraw(pair.userId());
        var purge = pair.campus().consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == pair.userId() && job.kind().equals("PURGE")
                        && job.epoch() == withdrawn.epoch()).findFirst().orElseThrow();
        assertEquals("APPLIED", pair.external().consent().apply(pair.campus().consent().export(purge)));
        pair.campus().consent().complete(purge);

        var granted = pair.campus().consent().grant(pair.userId(), POLICY);
        var upsert = pair.campus().consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == pair.userId() && job.kind().equals("UPSERT")
                        && job.epoch() == granted.epoch()).findFirst().orElseThrow();
        assertEquals("APPLIED", pair.external().consent().apply(pair.campus().consent().export(upsert)));
        pair.campus().consent().complete(upsert);
        receiveAndProcess(pair.external(), oldResolution);
        assertEquals(1, count(pair.external(), """
                SELECT count(*) FROM eatwhat_sync.profile_conflict
                WHERE subject_gid=? AND field_group='birthday' AND status='OPEN'
                """, pair.subjectId()),
                "An in-flight old-epoch resolution is a stale no-op and cannot settle the peer conflict");
        assertEquals(0, count(pair.external(),
                "SELECT count(*) FROM eatwhat_sync.profile_operation WHERE event_id=?", oldResolution.eventId()));
        pair.campus().profileCommands().runOnce();

        exchange(pair);

        assertEquals(0, count(pair.external(),
                "SELECT count(*) FROM eatwhat_sync.profile_operation WHERE event_id=?", oldResolution.eventId()),
                "The old-epoch resolution event itself is never applied to the regranted peer");
        assertEquals(1, count(pair.campus(),
                "SELECT count(*) FROM eatwhat_sync.profile_conflict_audit WHERE conflict_id=?", conflictId),
                "The original administrator decision and audit remain immutable");
        assertEquals(1, count(pair.external(), """
                SELECT count(*) FROM eatwhat_sync.profile_conflict
                WHERE subject_gid=? AND field_group='birthday' AND status='RESOLVED'
                """, pair.subjectId()),
                "The current-epoch resolution bridge must settle the peer's still-open conflict");
        assertEquals("2001-02-03", publicValue(pair.external(), pair.userId(), "birthday"));
        assertEquals("2001-02-03", publicValue(pair.campus(), pair.userId(), "birthday"));
    }

    @Test
    void aProfileEditThatArrivesBeforeItsResolutionStillSettlesTheConflict() throws Exception {
        Pair pair = pair();
        updateProfile(pair.campus(), pair.userId(), "gender", "other");
        exchange(pair);

        updateProfile(pair.campus(), pair.userId(), "birthday", "2001-02-03"); // A1
        updateProfile(pair.external(), pair.userId(), "birthday", "2002-04-05"); // B1
        SyncEvent campusHead = profileEvent(pair.campus(), "2001-02-03");
        SyncEvent externalHead = profileEvent(pair.external(), "2002-04-05");
        receiveAndProcess(pair.external(), campusHead);
        receiveAndProcess(pair.campus(), externalHead);

        UUID conflictId = pair.campus().db().runtime().queryForObject(
                "SELECT conflict_id FROM eatwhat_sync.profile_conflict WHERE subject_gid=? AND field_group='birthday'",
                UUID.class, pair.subjectId());
        long revision = count(pair.campus(),
                "SELECT revision FROM eatwhat_sync.profile_conflict WHERE conflict_id=?", conflictId);
        UUID selected = pair.campus().db().runtime().queryForObject("""
                SELECT (head->>'id')::uuid FROM eatwhat_sync.profile_conflict conflict,
                  LATERAL jsonb_array_elements(conflict.heads) head
                WHERE conflict.conflict_id=? AND head->>'value'='2001-02-03'
                """, UUID.class, conflictId);
        assertTrue(pair.campus().profileCommands().resolveConflict(conflictId, revision, selected, pair.userId()));
        SyncEvent resolution = profileEventWithResolution(pair.campus());

        updateProfile(pair.campus(), pair.userId(), "birthday", "2005-10-11"); // D observes R
        SyncEvent laterEdit = profileEvent(pair.campus(), "2005-10-11");
        ProfileEvents.Operation d = ProfileEvents.parse(laterEdit.payloadJson());
        assertTrue(d.contexts().get("birthday").containsKey(pair.campus().db().site().getId()));

        receiveAndProcess(pair.external(), laterEdit);
        assertEquals(1, count(pair.external(), """
                SELECT count(*) FROM eatwhat_sync.profile_conflict
                WHERE subject_gid=? AND field_group='birthday' AND status='OPEN'
                """, pair.subjectId()), "D arriving first cannot silently erase the still-open conflict");
        receiveAndProcess(pair.external(), resolution);

        assertEquals(1, count(pair.external(), """
                SELECT count(*) FROM eatwhat_sync.profile_conflict
                WHERE subject_gid=? AND field_group='birthday' AND status='RESOLVED'
                """, pair.subjectId()), "The late valid R proof settles the conflict using its later active descendant D");
        assertEquals("2005-10-11", publicValue(pair.external(), pair.userId(), "birthday"));
    }

    @Test
    void concurrentPrivacyRestrictionWinsUntilALaterTrueOperationObservesIt() throws Exception {
        Pair pair = pair();
        setFollowing(pair.campus(), pair.userId(), false);
        setFollowing(pair.external(), pair.userId(), false);
        setFollowing(pair.external(), pair.userId(), true);
        exchange(pair);

        assertEquals(false, privacyValue(pair.campus(), pair.userId()));
        assertEquals(false, privacyValue(pair.external(), pair.userId()));

        setFollowing(pair.external(), pair.userId(), true);
        exchange(pair);

        assertEquals(true, privacyValue(pair.campus(), pair.userId()));
        assertEquals(true, privacyValue(pair.external(), pair.userId()));
        assertEquals(4, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.privacy_operation"));
        assertEquals(4, count(pair.external(), "SELECT count(*) FROM eatwhat_sync.privacy_operation"));
    }

    @Test
    void concurrentPrivacyRelaxationsNeedToObserveEveryRestrictiveWitness() throws Exception {
        Pair pair = pair();
        String campusSite = pair.campus().db().site().getId();
        String externalSite = pair.external().db().site().getId();

        setFollowing(pair.campus(), pair.userId(), false); // A false
        setFollowing(pair.external(), pair.userId(), false); // B false, concurrent with A false
        setFollowing(pair.campus(), pair.userId(), true); // A true observes only A false
        setFollowing(pair.external(), pair.userId(), true); // B true observes only B false

        PrivacyEvents.Operation campusTrue = privacyEvent(pair.campus(), true);
        PrivacyEvents.Operation externalTrue = privacyEvent(pair.external(), true);
        assertEquals(1L, campusTrue.contexts().get("following").get(campusSite));
        assertFalse(campusTrue.contexts().get("following").containsKey(externalSite));
        assertEquals(1L, externalTrue.contexts().get("following").get(externalSite));
        assertFalse(externalTrue.contexts().get("following").containsKey(campusSite));

        exchange(pair);
        assertFalse(privacyValue(pair.campus(), pair.userId()),
                "Concurrent true operations cannot synthesize one witness that observed both false operations");
        assertFalse(privacyValue(pair.external(), pair.userId()));

        setFollowing(pair.campus(), pair.userId(), true); // Explicitly observes both restrictive witnesses.
        PrivacyEvents.Operation finalTrue = privacyEvents(pair.campus()).getLast();
        assertTrue(finalTrue.contexts().get("following").getOrDefault(campusSite, 0L) >= 1L);
        assertTrue(finalTrue.contexts().get("following").getOrDefault(externalSite, 0L) >= 1L);
        exchange(pair);

        assertTrue(privacyValue(pair.campus(), pair.userId()));
        assertTrue(privacyValue(pair.external(), pair.userId()));
    }

    @Test
    void sameRevisionRenewalAfterManagedProfileAndPrivacyChangesStillRejectsSecurityPayloadChanges() throws Exception {
        Pair pair = pair();
        var source = pair.campus();
        var target = pair.external();
        long id = pair.userId();
        long revision = source.consent().get(id).revision();
        var baseline = source.profileCommands().snapshotBeforeUpdate(id);
        source.db().transactions().required(() -> {
            source.profileCommands().capture(id, new UpdateUserInfoDTO(), baseline);
            return null;
        });
        updateProfile(source, id, "signature", "legitimate-managed-change-before-renewal");
        exchange(pair);
        setFollowing(target, id, false);
        exchange(pair);
        assertEquals(revision, source.consent().get(id).revision());
        assertEquals(revision, target.consent().get(id).revision());
        target.db().admin().update("UPDATE eatwhat_consent.subject SET valid_until=clock_timestamp()-interval '1 second' WHERE user_id=?", id);
        var renewed = source.consent().exportCurrent(id);
        assertEquals("APPLIED", target.consent().apply(renewed),
                "A legal domain update must not permanently prevent the five-minute authorization lease from renewing");
        assertTrue(target.consent().isAuthorized(id));
        assertEquals("legitimate-managed-change-before-renewal", publicValue(target, id, "signature"));
        assertFalse(privacyValue(target, id));
        assertEquals(0, count(target, "SELECT count(*) FROM eatwhat_consent.conflict_receipt WHERE subject_id=?", pair.subjectId()));

        var tampered = renewed.data().deepCopy();
        ((tools.jackson.databind.node.ObjectNode) tampered.get("users").get(0)).put("password_hash", "unapproved-same-version-password").put("role", "admin");
        var invalid = new ConsentModels.Snapshot(renewed.subjectId(), renewed.userId(), renewed.epoch(), renewed.revision(),
                renewed.status(), renewed.policyVersion(), renewed.validUntil(), renewed.sourceSite(), renewed.sourceInstance(),
                renewed.withdrawalRequestId(), tampered);
        target.db().admin().update("UPDATE eatwhat_consent.subject SET valid_until=clock_timestamp()-interval '1 second' WHERE user_id=?", id);
        assertEquals("CONFLICT", target.consent().apply(invalid));
        assertFalse(target.consent().isAuthorized(id));
        assertEquals("synthetic-only", target.db().runtime().queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, id));
        assertEquals("user", target.db().runtime().queryForObject("SELECT role FROM users WHERE id=?", String.class, id));
    }

    @Test
    void accountRevisionRefreshPreservesExternalManagedProfileAndPrivacyButUpdatesAccountFields() throws Exception {
        Pair pair = pair();
        updateProfile(pair.campus(), pair.userId(), "signature", "campus-owned-baseline");
        exchange(pair);

        updateProfile(pair.external(), pair.userId(), "avatar", "avatar/external-owned.png");
        setFollowing(pair.external(), pair.userId(), false);
        exchange(pair);
        assertEquals("avatar/external-owned.png", publicValue(pair.external(), pair.userId(), "avatar"));
        assertEquals(false, privacyValue(pair.external(), pair.userId()));

        pair.campus().db().runtime().update("UPDATE public.users SET nick_name=?,password_hash=?,role=? WHERE id=?",
                "account-authoritative-name", "account-authoritative-hash", "admin", pair.userId());
        ConsentModels.Job accountRefresh = pair.campus().consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == pair.userId() && job.kind().equals("UPSERT"))
                .max(Comparator.comparingLong(ConsentModels.Job::revision)).orElseThrow();
        ConsentModels.Snapshot snapshot = pair.campus().consent().export(accountRefresh);
        assertNotNull(snapshot);
        long profileOperationsBeforeRefresh = count(pair.external(),
                "SELECT count(*) FROM eatwhat_sync.profile_operation WHERE subject_gid=?", pair.subjectId());
        long privacyOperationsBeforeRefresh = count(pair.external(),
                "SELECT count(*) FROM eatwhat_sync.privacy_operation WHERE subject_gid=?", pair.subjectId());
        assertEquals("APPLIED", pair.external().consent().apply(snapshot));
        pair.campus().consent().complete(accountRefresh);

        assertEquals("account-authoritative-name", pair.external().db().runtime().queryForObject(
                "SELECT nick_name FROM public.users WHERE id=?", String.class, pair.userId()));
        assertEquals("account-authoritative-hash", pair.external().db().runtime().queryForObject(
                "SELECT password_hash FROM public.users WHERE id=?", String.class, pair.userId()));
        assertEquals("admin", pair.external().db().runtime().queryForObject(
                "SELECT role FROM public.users WHERE id=?", String.class, pair.userId()));
        assertEquals("campus-owned-baseline", publicValue(pair.external(), pair.userId(), "signature"));
        assertEquals("avatar/external-owned.png", publicValue(pair.external(), pair.userId(), "avatar"));
        assertEquals(false, privacyValue(pair.external(), pair.userId()));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM eatwhat_sync.profile_domain WHERE subject_gid=? AND local_dirty", pair.subjectId()));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM eatwhat_sync.privacy_domain WHERE subject_gid=? AND local_dirty", pair.subjectId()));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM eatwhat_consent.conflict_receipt WHERE subject_id=?", pair.subjectId()));
        assertEquals(profileOperationsBeforeRefresh, count(pair.external(),
                "SELECT count(*) FROM eatwhat_sync.profile_operation WHERE subject_gid=?", pair.subjectId()));
        assertEquals(privacyOperationsBeforeRefresh, count(pair.external(),
                "SELECT count(*) FROM eatwhat_sync.privacy_operation WHERE subject_gid=?", pair.subjectId()));
    }

    @Test
    void localFoodPreferencesStayPrivateAndExternalWithdrawalCascadesTheirRemoval() throws Exception {
        Pair pair = pair();
        pair.campus().db().runtime().update("INSERT INTO public.user_food_preferences(account_id,liked_tags) VALUES(?,ARRAY['campus-taste'])", pair.userId());
        pair.external().db().runtime().update("INSERT INTO public.user_food_preferences(account_id,liked_tags) VALUES(?,ARRAY['external-taste'])", pair.userId());
        updateProfile(pair.campus(), pair.userId(), "signature", "public field only");
        exchange(pair);
        assertEquals("campus-taste", pair.campus().db().runtime().queryForObject(
                "SELECT liked_tags[1] FROM public.user_food_preferences WHERE account_id=?", String.class, pair.userId()));
        assertEquals("external-taste", pair.external().db().runtime().queryForObject(
                "SELECT liked_tags[1] FROM public.user_food_preferences WHERE account_id=?", String.class, pair.userId()));

        // Real detail requests leave local history even after the referenced catalog entry is gone.
        pair.external().db().runtime().update("INSERT INTO public.browse_history(account_id,target_type,target_id) VALUES(?,'food',123),(?,'restaurant',456)", pair.userId(), pair.userId());
        pair.campus().db().runtime().update("INSERT INTO public.browse_history(account_id,target_type,target_id) VALUES(?,'food',123)", pair.userId());
        var withdrawn = pair.campus().consent().withdraw(pair.userId());
        var purge = pair.campus().consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == pair.userId() && job.kind().equals("PURGE")
                        && job.epoch() == withdrawn.epoch()).findFirst().orElseThrow();
        assertEquals("APPLIED", pair.external().consent().apply(pair.campus().consent().export(purge)));
        pair.campus().consent().complete(purge);
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM public.user_food_preferences WHERE account_id=?", pair.userId()));
        assertEquals(1, count(pair.campus(), "SELECT count(*) FROM public.user_food_preferences WHERE account_id=?", pair.userId()));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM public.browse_history WHERE account_id=?", pair.userId()));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM public.users WHERE id=?", pair.userId()));
        assertEquals(1, count(pair.campus(), "SELECT count(*) FROM public.browse_history WHERE account_id=?", pair.userId()));
    }

    @Test
    void anotherUserDependencyOnHistoryStillBlocksWithdrawalCleanup() throws Exception {
        Pair pair = pair();
        pair.external().db().admin().execute("ALTER TABLE public.browse_history ADD COLUMN reviewer_id bigint REFERENCES public.users(id) ON DELETE CASCADE");
        pair.external().db().runtime().update("INSERT INTO public.browse_history(account_id,target_type,target_id,reviewer_id) VALUES(?,'food',123,?)", pair.userId(), pair.userId());
        pair.external().db().runtime().update("INSERT INTO public.user_food_preferences(account_id,liked_tags) VALUES(?,ARRAY['test-taste'])", pair.userId());
        pair.external().consent().withdraw(pair.userId());
        assertEquals("BLOCKED_DEPENDENCIES", pair.external().consent().get(pair.userId()).cleanupStatus());
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM public.users WHERE id=?", pair.userId()));
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM public.browse_history WHERE account_id=?", pair.userId()));
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM public.user_food_preferences WHERE account_id=?", pair.userId()));
    }

    private static Pair pair() throws Exception {
        return pair(null);
    }

    private static Pair pair(UUID deterministicSubjectId) throws Exception {
        SiteProperties campusSite = site(SiteProperties.Mode.CAMPUS, "profile-campus");
        SiteProperties externalSite = site(SiteProperties.Mode.EXTERNAL, "profile-external");
        var campusDb = EventFoundationTestSupport.createDatabase(POSTGRES, campusSite);
        var externalDb = EventFoundationTestSupport.createDatabase(POSTGRES, externalSite);
        Site campus = site(campusDb, externalDb);
        Site external = site(externalDb, campusDb);

        long userId = campusDb.runtime().queryForObject("""
                INSERT INTO public.users(nick_name,password_hash) VALUES('pr05duser','synthetic-only') RETURNING id
                """, Long.class);
        campusDb.runtime().update("INSERT INTO public.user_info(id) VALUES(?)", userId);
        campusDb.runtime().update("INSERT INTO public.privacy(account_id,following,follower) VALUES(?,true,true)", userId);
        if (deterministicSubjectId != null) {
            forceUserIdentity(campusDb, userId, deterministicSubjectId);
        }
        campus.consent().onRegistered(userId, true, POLICY);
        ConsentModels.Job grant = campus.consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == userId && job.kind().equals("UPSERT"))
                .max(Comparator.comparingLong(ConsentModels.Job::epoch)).orElseThrow();
        assertEquals("APPLIED", external.consent().apply(campus.consent().export(grant)));
        campus.consent().complete(grant);
        UUID subject = campusDb.runtime().queryForObject("""
                SELECT subject_id FROM eatwhat_consent.subject WHERE user_id=?
                """, UUID.class, userId);
        return new Pair(campus, external, userId, subject);
    }

    private static void forceUserIdentity(EventFoundationTestSupport.Database db, long userId, UUID gid) {
        assertEquals(1, db.admin().update("""
                UPDATE eatwhat_identity.entity_identity SET global_id=?
                WHERE entity_type='users' AND numeric_id=?
                """, gid, userId));
    }

    private static Site site(EventFoundationTestSupport.Database db,
                              EventFoundationTestSupport.Database peerDb) {
        String peer = peerDb.site().getId();
        ConsentStore consent = new ConsentStore(db.runtime(), db.transactions(), db.site(), POLICY,
                Duration.ofMinutes(5), Duration.ofMinutes(2), peer, peerDb.site().getInstanceId());
        OutboxService outbox = new OutboxService(db.runtime(), db.transactions(), CODEC, db.site());
        EntityIdentityService identities = new EntityIdentityService(db.runtime());
        ProfileStore profiles = new ProfileStore(db.runtime());
        PrivacyStore privacy = new PrivacyStore(db.runtime());
        ProfileCommands profileCommands = new ProfileCommands(db.runtime(), db.transactions(), profiles,
                outbox, identities, consent, db.site(), peer);
        PrivacyCommands privacyCommands = new PrivacyCommands(db.runtime(), db.transactions(), privacy,
                outbox, identities, consent, db.site(), peer);
        var registry = new EventHandlerRegistry(List.of(
                new ProfileEventHandler(db.runtime(), profiles, identities, consent, db.site()),
                new PrivacyEventHandler(db.runtime(), privacy, identities, consent)));
        InboxService inbox = new InboxService(db.runtime(), db.transactions(), CODEC, registry);
        DeliveryService delivery = new DeliveryService(db.runtime(), db.transactions(), CODEC);
        return new Site(db, consent, profiles, privacy, profileCommands, privacyCommands, inbox, delivery, peer);
    }

    private static void updateProfile(Site site, long userId, String field, Object value) {
        Map<String, Object> before = site.profiles().readPublicProfile(userId);
        UpdateUserInfoDTO dto = new UpdateUserInfoDTO();
        switch (field) {
            case "avatar" -> dto.setAvatar((String) value);
            case "gender" -> dto.setGender((String) value);
            case "birthday" -> dto.setBirthday(java.time.LocalDate.parse((String) value));
            case "signature" -> dto.setSignature((String) value);
            default -> throw new IllegalArgumentException(field);
        }
        site.db().transactions().required(() -> {
            if (field.equals("avatar")) {
                site.db().runtime().update("UPDATE public.users SET avatar=? WHERE id=?", value, userId);
            } else if (field.equals("birthday")) {
                site.db().runtime().update("UPDATE public.user_info SET birthday=?::date WHERE id=?", value, userId);
            } else {
                site.db().runtime().update("UPDATE public.user_info SET " + field + "=? WHERE id=?", value, userId);
            }
            site.profileCommands().capture(userId, dto, before);
            return null;
        });
    }

    private static void setFollowing(Site site, long userId, boolean visible) {
        PrivacyDTO dto = new PrivacyDTO();
        dto.setFollowing(visible);
        Map<String, Boolean> before = site.privacyCommands().snapshotBeforeChange(userId);
        site.privacyCommands().prepareChange(userId, before);
        site.db().transactions().required(() -> {
            site.db().runtime().update("UPDATE public.privacy SET following=? WHERE account_id=?", visible, userId);
            site.privacyCommands().capture(userId, dto, before);
            return null;
        });
    }

    private static void exchange(Pair pair) {
        for (Site source : List.of(pair.campus(), pair.external())) {
            Site target = source == pair.campus() ? pair.external() : pair.campus();
            Set<String> domains = Set.of(ProfileEvents.AGGREGATE, PrivacyEvents.AGGREGATE);
            for (var lease : source.delivery().claimForAggregates(target.db().site().getId(), 100, LEASE, domains)) {
                target.inbox().receive(lease.event(), lease.event().originSite());
                assertTrue(source.delivery().acknowledge(lease, "DURABLY_QUEUED"));
            }
        }
        process(pair.campus());
        process(pair.external());
        pair.campus().inbox().requeueWaiting(100, Duration.ZERO);
        pair.external().inbox().requeueWaiting(100, Duration.ZERO);
        process(pair.campus());
        process(pair.external());
    }

    private static SyncEvent profileEvent(Site site, String birthday) {
        return profileEvents(site).stream().filter(event -> {
            ProfileEvents.Operation operation = ProfileEvents.parse(event.payloadJson());
            return birthday.equals(operation.changes().get("birthday"));
        }).findFirst().orElseThrow(() -> new AssertionError("Missing birthday event " + birthday));
    }

    private static SyncEvent profileEventWithResolution(Site site) {
        return profileEvents(site).stream().filter(event ->
                ProfileEvents.parse(event.payloadJson()).resolution() != null)
                .findFirst().orElseThrow(() -> new AssertionError("Missing profile resolution event"));
    }

    private static List<SyncEvent> profileEvents(Site site) {
        return site.db().runtime().query("""
                SELECT envelope::text FROM eatwhat_sync.outbox
                WHERE aggregate_type=? ORDER BY local_order
                """, (row, index) -> CODEC.decode(row.getString(1)), ProfileEvents.AGGREGATE);
    }

    private static List<PrivacyEvents.Operation> privacyEvents(Site site) {
        return site.db().runtime().query("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type=? ORDER BY local_order",
                (row, index) -> PrivacyEvents.parse(CODEC.decode(row.getString(1)).payloadJson()),
                PrivacyEvents.AGGREGATE);
    }

    private static PrivacyEvents.Operation privacyEvent(Site site, boolean following) {
        return privacyEvents(site).stream()
                .filter(operation -> operation.changes().get("following") != null
                        && operation.changes().get("following") == following)
                .findFirst().orElseThrow();
    }

    private static void receiveAndProcess(Site target, SyncEvent event) {
        target.inbox().receive(event, event.originSite());
        process(target);
    }

    private static void assertConflictHeads(Site site, UUID subject, Set<String> expectedValues,
                                            Set<String> absentValues) {
        assertEquals(expectedValues.size(), count(site, """
                SELECT jsonb_array_length(heads) FROM eatwhat_sync.profile_conflict
                WHERE subject_gid=? AND field_group='birthday' AND status='OPEN'
                """, subject));
        Set<String> values = Set.copyOf(site.db().runtime().query("""
                SELECT head->>'value' FROM eatwhat_sync.profile_conflict c,
                  LATERAL jsonb_array_elements(c.heads) head
                WHERE c.subject_gid=? AND c.field_group='birthday' AND c.status='OPEN'
                """, (row, index) -> row.getString(1), subject));
        assertEquals(expectedValues, values);
        for (String absent : absentValues) assertFalse(values.contains(absent));
    }

    private static void process(Site site) {
        for (var lease : site.inbox().claim(100, LEASE)) site.inbox().process(lease);
    }

    private static String publicValue(Site site, long userId, String field) {
        String table = field.equals("avatar") ? "users" : "user_info";
        return site.db().runtime().queryForObject("SELECT " + field + " FROM public." + table + " WHERE id=?",
                String.class, userId);
    }

    private static boolean privacyValue(Site site, long userId) {
        return site.db().runtime().queryForObject("SELECT following FROM public.privacy WHERE account_id=?",
                Boolean.class, userId);
    }

    private static String fieldVector(Site site, UUID subject, String field) {
        return site.db().runtime().queryForObject("""
                SELECT applied_vector::text FROM eatwhat_sync.profile_field_state
                WHERE subject_gid=? AND field_group=?
                """, String.class, subject, field);
    }

    private static long count(Site site, String sql, Object... args) {
        return site.db().runtime().queryForObject(sql, Long.class, args);
    }

    private static SiteProperties site(SiteProperties.Mode mode, String id) {
        SiteProperties site = new SiteProperties();
        site.setMode(mode);
        site.setId(id);
        site.setInstanceId(UUID.randomUUID());
        return site;
    }

    private record Site(EventFoundationTestSupport.Database db, ConsentStore consent,
                        ProfileStore profiles, PrivacyStore privacy,
                        ProfileCommands profileCommands, PrivacyCommands privacyCommands,
                        InboxService inbox, DeliveryService delivery, String peer) { }

    private record Pair(Site campus, Site external, long userId, UUID subjectId) { }
}
