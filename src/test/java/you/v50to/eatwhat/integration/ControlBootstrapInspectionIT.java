package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.control.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real authority/candidate PostgreSQL, original encrypted admission/profile/
 * membership records and real JOIN votes. Native installation and SSH are
 * explicit fixtures; this does not prove retained Linux runtime diagnostics. */
class ControlBootstrapInspectionIT {
    @TempDir static Path pki;@TempDir Path root;
    ControlBootstrapMembershipIT fixture;ControlBootstrapAdmissionIT base;
    @BeforeAll static void start()throws Exception{ControlBootstrapMembershipIT.pki=pki;ControlBootstrapMembershipIT.start();}
    @AfterAll static void stop(){ControlBootstrapMembershipIT.stop();}
    @BeforeEach void setup()throws Exception{fixture=new ControlBootstrapMembershipIT();fixture.root=root;fixture.setup();base=fixture.base;}
    @AfterEach void close(){fixture.close();}
    Map<String,byte[]> files()throws Exception{
        var result=new TreeMap<String,byte[]>();try(var paths=Files.walk(root)){
            for(var path:paths.filter(Files::isRegularFile).toList())result.put(root.relativize(path).toString(),Files.readAllBytes(path));
        }return result;
    }
    Map<String,String> database(ControlBootstrapMembershipIT.Case c){
        var result=new TreeMap<String,String>();int index=0;
        for(var db:List.of(base.db,c.target)){
            for(String table:List.of("command","audit","change","change_vote","member","local_state","local_change","local_phase"))
                result.put(index+":"+table,db.runtime().queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM eatwhat_cluster."+table+" t",String.class));
            index++;
        }return result;
    }
    void unchanged(ControlBootstrapMembershipIT.Case c,Runnable check)throws Exception{
        var before=files();var sql=database(c);var sent=List.copyOf(c.sent);clearInvocations(c.coordinator,c.runtime,c.profile.nativePhases);
        check.run();var after=files();assertEquals(before.keySet(),after.keySet());before.forEach((key,value)->assertArrayEquals(value,after.get(key),key));
        assertEquals(sql,database(c));assertEquals(sent,c.sent);verifyNoInteractions(c.coordinator,c.runtime);
        var calls=mockingDetails(c.profile.nativePhases).getInvocations();assertTrue(calls.stream().anyMatch(v->v.getMethod().getName().equals("bound")));
        assertFalse(calls.stream().anyMatch(v->v.getMethod().getName().equals("call")));
    }
    ControlBootstrapMembership.Inspection inspect(ControlBootstrapMembershipIT.Case c){return c.manager.inspectOriginal(c.profile.frozen);}
    @Test void registeredPreparingAndCompletedOriginalJoinAreObservedWithoutAnyProjection()throws Exception{try(var c=fixture.new Case(true)){
        c.configure();c.register();
        unchanged(c,()->{var seen=inspect(c);assertEquals("REGISTERED",seen.observation().get("state"));assertTrue(seen.joinedProof().isEmpty());assertEquals(false,seen.observation().get("joinCommandObserved"));});
        c.manager.join(c.profile.frozen);
        unchanged(c,()->{var seen=inspect(c);assertEquals("PREPARING",seen.observation().get("state"));assertTrue(seen.joinedProof().isEmpty());assertEquals(false,seen.observation().get("allJoinConfirmationsObserved"));});
        c.manager.reconcileJoin(c.profile.frozen);org.springframework.test.util.ReflectionTestUtils.invokeMethod(base.journal,"executionObservation",base.spec.id(),"BOOTSTRAP_ORIGINAL_CONTROLLER_REQUIRED",true);
        unchanged(c,()->{var seen=inspect(c);assertEquals("APPLIED",seen.observation().get("state"));assertEquals(true,seen.observation().get("allJoinConfirmationsObserved"));
            assertEquals(true,seen.observation().get("currentMembershipMatchesOriginalJoin"));assertTrue(seen.joinedProof().isPresent());
            assertEquals(true,base.journal.job(base.spec.id()).get("requiresReapproval"));assertEquals(1,c.profile.frozen.spec().epoch());assertEquals(2L,seen.observation().get("clusterEpoch"));});
    }}
    @Test void missingOriginalCommandAfterLostRegisterRequestStaysUnknownAndCannotRedispatch()throws Exception{try(var c=fixture.new Case(true)){
        c.configure();c.before=action->{if(action.equals("register"))throw new ControlFailure(503,"FIXTURE_LOST_REQUEST");};assertThrows(ControlFailure.class,c::register);
        unchanged(c,()->{var seen=inspect(c);assertEquals("OUTCOME_UNKNOWN",seen.observation().get("state"));assertEquals(false,seen.observation().get("registrationCommandObserved"));assertTrue(seen.joinedProof().isEmpty());});
    }}
    @Test void differentOwnerOrMissingReleaseVoteCannotBecomeACompletedJoinProof()throws Exception{try(var c=fixture.new Case(true)){
        c.configure();c.register();c.manager.join(c.profile.frozen);
        c.phaseAfter=phase->{if(phase.equals("RELEASE:campus"))throw new ControlFailure(503,"FIXTURE_LOST_RELEASE_ACK");};
        assertThrows(ControlFailure.class,()->c.manager.reconcileJoin(c.profile.frozen));var accepted=c.manager.original(base.spec.id());
        unchanged(c,()->{var seen=inspect(c);assertEquals("INSTALLED",seen.observation().get("state"));assertEquals(false,seen.observation().get("allJoinConfirmationsObserved"));assertTrue(seen.joinedProof().isEmpty());
            assertEquals("BOOTSTRAP_MEMBERSHIP_OWNER_CHANGED",assertThrows(ClusterFailure.class,()->c.store.inspectOwnedJoin(UUID.randomUUID(),accepted.binding(),base.actor,accepted.registerId(),accepted.joinId(),1,c.member,accepted.databaseFingerprint())).getMessage());});
    }}
    @Test void currentEpochDriftDoesNotEraseHistoricalAppliedStateOrAuthorizeRuntimeProof()throws Exception{try(var c=fixture.new Case(true)){
        c.configure();c.register();c.manager.join(c.profile.frozen);c.manager.reconcileJoin(c.profile.frozen);
        base.db.admin().update("UPDATE eatwhat_cluster.state SET epoch=epoch+1 WHERE singleton");
        unchanged(c,()->{var seen=inspect(c);assertEquals("APPLIED",seen.observation().get("state"));assertEquals(false,seen.observation().get("currentMembershipMatchesOriginalJoin"));assertTrue(seen.joinedProof().isEmpty());});
    }}
    @Test void sameEpochExtraRegisteredMemberCannotPassTheOriginalMembershipSnapshot()throws Exception{try(var c=fixture.new Case(true)){
        c.configure();c.register();c.manager.join(c.profile.frozen);c.manager.reconcileJoin(c.profile.frozen);
        // A same-epoch extra registered member is also drift, even though the
        // original active set and target fingerprint have not changed.
        var primary=new ClusterStore(base.db.runtime(),base.db.transactions(),base.site.getClusterId(),new you.v50to.eatwhat.sync.transport.NodeIdentity(base.site.getId(),base.site.getInstanceId()),base.lease);
        primary.register(base.actor,UUID.randomUUID(),2,"unrelated",new ClusterMember("unrelated",UUID.randomUUID(),"EXTERNAL",2));
        unchanged(c,()->{var seen=inspect(c);assertEquals(2L,seen.observation().get("clusterEpoch"));assertEquals(false,seen.observation().get("currentMembershipMatchesOriginalJoin"));assertTrue(seen.joinedProof().isEmpty());});
    }}
    @Test void sqlSnapshotIsDatabaseEnforcedReadOnlyAndRepeatableRatherThanAWriteLease()throws Exception{try(var c=fixture.new Case(true)){
        c.configure();c.register();var accepted=c.manager.original(base.spec.id());
        var observer=mock(ClusterStore.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(c.store).defaultAnswer(CALLS_REAL_METHODS));
        doAnswer(inv->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals("on",base.db.runtime().queryForObject("SHOW transaction_read_only",String.class));assertEquals("repeatable read",base.db.runtime().queryForObject("SHOW transaction_isolation",String.class));
            // Even a no-row UPDATE is forbidden by PostgreSQL, not just by a
            // Java convention. This is a test-owned database only.
            base.db.runtime().update("UPDATE eatwhat_cluster.member SET state=state WHERE false");return inv.callRealMethod();
        }).when(observer).registeredCommand(anyString(),any(),anyLong(),anyString(),any());
        var before=database(c);assertThrows(org.springframework.dao.DataAccessException.class,()->observer.inspectOwnedJoin(accepted.id(),accepted.binding(),base.actor,accepted.registerId(),accepted.joinId(),1,c.member,accepted.databaseFingerprint()));
        assertEquals(before,database(c));assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }}
}
