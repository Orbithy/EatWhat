package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.control.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Original sealed workflow/admission, real authority+candidate SQL and node
 * phase proofs. Native install, TARGET publication, catchup and SSH remain
 * explicit fixtures. No standalone Linux or full VERIFY acceptance. */
class ControlBootstrapJoinIT {
    @TempDir static Path pki;@TempDir Path root;
    ControlBootstrapMembershipIT fixture;ControlBootstrapAdmissionIT base;
    @BeforeAll static void start()throws Exception{ControlBootstrapMembershipIT.pki=pki;ControlBootstrapMembershipIT.start();}
    @AfterAll static void stop(){ControlBootstrapMembershipIT.stop();}
    @BeforeEach void setup()throws Exception{fixture=new ControlBootstrapMembershipIT();fixture.root=root;fixture.setup();base=fixture.base;}
    @AfterEach void close(){fixture.close();}
    void registered(ControlBootstrapMembershipIT.Case c){c.configure();assertEquals("SUCCEEDED",c.register().get("registration"));}
    void active(ControlBootstrapMembershipIT.Case c){
        assertEquals("ACTIVE",c.manager.reconcileJoin(c.profile.frozen).get("state"));
        assertEquals("VERIFY",base.journal.job(base.spec.id()).get("stage"));assertNotEquals("SUCCEEDED",base.journal.job(base.spec.id()).get("state"));
        assertEquals(1,c.profile.frozen.spec().epoch());assertEquals(1,c.profile.original.frozen().sourceRequest().expected().epoch());
        assertEquals("ACTIVE",c.store.member(c.member.siteId()).get("state"));assertEquals(2L,c.store.status().get("epoch"));
        for(var db:List.of(base.db,c.target)){var local=db.runtime().queryForMap("SELECT epoch,state,pending_change FROM eatwhat_cluster.local_state WHERE singleton");assertEquals(2L,((Number)local.get("epoch")).longValue());assertEquals("ACTIVE",local.get("state"));assertNull(local.get("pending_change"));}
        var accepted=c.manager.original(base.spec.id());assertEquals(6,base.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.change_vote WHERE change_id=?",Integer.class,accepted.joinId()));
        assertEquals(1,base.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.command WHERE request_id=?",Integer.class,accepted.joinId()));assertFalse((Boolean)c.profile.sourceStandby.status().get("localLeader"));
        assertTrue(c.store.ownedJoinedCommand(accepted.id(),accepted.binding(),base.actor,accepted.joinId(),1,c.member,accepted.databaseFingerprint()).isPresent());
    }
    @Test void originalTaskRegistersThenJoinsTwoDatabasesWithoutRebasingApproval()throws Exception{try(var c=fixture.new Case(true)){
        registered(c);String frozen=c.profile.frozen.binding();assertEquals("PREPARING",c.manager.join(c.profile.frozen).get("state"));active(c);assertEquals(frozen,c.profile.frozen.binding());
        assertEquals(List.of("register","join","join-reconcile"),c.sent);assertEquals("ACTIVE",c.manager.join(c.profile.frozen).get("state"));assertEquals("join-status",c.sent.getLast());
        verify(c.coordinator,times(1)).beginOwnedJoinExpected(any(),anyString(),anyString(),any(),anyLong(),any(),anyString(),any());
    }}
    @Test void automaticAdvancementRegistersOnceAndResumesOwnedJoinAcrossItsEpochSuccessor()throws Exception{try(var c=fixture.new Case(true)){
        c.configure();assertEquals("SUCCEEDED",c.manager.advance(c.profile.frozen).get("registration"));
        assertEquals("PREPARING",c.manager.advance(c.profile.frozen).get("state"));
        assertEquals("ACTIVE",c.manager.advance(c.profile.frozen).get("state"));active(c);
        assertEquals("ACTIVE",c.manager.advance(c.profile.frozen).get("state"));
        assertEquals(List.of("register","join","join-reconcile","join-status","join-status"),c.sent);
        verify(c.coordinator,times(1)).beginOwnedJoinExpected(any(),anyString(),anyString(),any(),anyLong(),any(),anyString(),any());
    }}
    @Test void automaticAdvancementCannotRepeatBeginWhenItsRequestNeverReachedSource()throws Exception{try(var c=fixture.new Case(true)){
        registered(c);c.before=action->{if(action.equals("join"))throw new ControlFailure(503,"FIXTURE_LOST_REQUEST");};assertThrows(ControlFailure.class,()->c.manager.advance(c.profile.frozen));
        assertEquals("OUTCOME_UNKNOWN",c.manager.advance(c.profile.frozen).get("state"));assertEquals("OUTCOME_UNKNOWN",c.manager.advance(c.profile.frozen).get("state"));
        assertEquals(List.of("register","join","join-status","join-status"),c.sent);verify(c.coordinator,never()).beginOwnedJoinExpected(any(),anyString(),anyString(),any(),anyLong(),any(),anyString(),any());
    }}
    @Test void lostBeginAcknowledgementObservesOnlyAndOrdinaryWorkerCannotAdoptOwnedChange()throws Exception{try(var c=fixture.new Case(true)){
        registered(c);c.loseJoinAfter=true;assertThrows(ControlFailure.class,()->c.manager.join(c.profile.frozen));
        assertEquals("PREPARING",c.manager.join(c.profile.frozen).get("state"));assertEquals(List.of("register","join","join-status"),c.sent);
        var accepted=c.manager.original(base.spec.id());var ordinaryStore=new ClusterStore(base.db.runtime(),base.db.transactions(),base.site.getClusterId(),new you.v50to.eatwhat.sync.transport.NodeIdentity(base.site.getId(),base.site.getInstanceId()),base.lease);
        var nodes=mock(ClusterMembershipCoordinator.Nodes.class);var ordinary=new ClusterMembershipCoordinator(base.site.getClusterId(),ordinaryStore,c.profile.catalog,nodes);
        assertEquals("BOOTSTRAP_MEMBERSHIP_TASK_REQUIRED",assertThrows(ClusterFailure.class,()->ordinary.reconcile("cluster-worker",accepted.joinId())).getMessage());verifyNoInteractions(nodes);
        assertEquals("BOOTSTRAP_MEMBERSHIP_TASK_REQUIRED",assertThrows(ClusterFailure.class,()->ordinaryStore.decide("another-admin",accepted.joinId())).getMessage());
        assertEquals("BOOTSTRAP_MEMBERSHIP_OWNER_CHANGED",assertThrows(ClusterFailure.class,()->c.coordinator.reconcileOwnedJoinExpected(UUID.randomUUID(),accepted.binding(),base.actor,accepted.joinId(),1,c.member,accepted.databaseFingerprint(),new ClusterMembershipCoordinator.Boundary(){public void check(String op){}public void write(String op,Runnable effect){effect.run();}})).getMessage());
        active(c);verify(c.coordinator,times(1)).beginOwnedJoinExpected(any(),anyString(),anyString(),any(),anyLong(),any(),anyString(),any());
    }}
    @Test void lostRequestBeforeBeginNeverGrantsAnotherInitialDispatch()throws Exception{try(var c=fixture.new Case(true)){
        registered(c);c.before=action->{if(action.equals("join"))throw new ControlFailure(503,"FIXTURE_LOST_REQUEST");};assertThrows(ControlFailure.class,()->c.manager.join(c.profile.frozen));
        assertEquals("OUTCOME_UNKNOWN",c.manager.join(c.profile.frozen).get("state"));assertEquals("OUTCOME_UNKNOWN",c.manager.join(c.profile.frozen).get("state"));
        assertEquals("BOOTSTRAP_JOIN_COMMAND_REQUIRED",assertThrows(ControlFailure.class,()->c.manager.reconcileJoin(c.profile.frozen)).code);assertEquals("REGISTERED",c.store.member(c.member.siteId()).get("state"));
        assertEquals(List.of("register","join","join-status","join-status"),c.sent);verify(c.coordinator,never()).beginOwnedJoinExpected(any(),anyString(),anyString(),any(),anyLong(),any(),anyString(),any());
    }}
    @Test void actualAdministratorRevocationAfterCallerIntentBlocksTheSourceEffect()throws Exception{try(var c=fixture.new Case(true)){
        registered(c);c.before=action->{if(action.equals("join"))base.db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(base.actor));};
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->c.manager.join(c.profile.frozen)).code);assertEquals("OUTCOME_UNKNOWN",c.manager.join(c.profile.frozen).get("state"));
        base.db.admin().update("UPDATE users SET banned=false WHERE id=?",Long.parseLong(base.actor));var accepted=c.manager.original(base.spec.id());
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->c.source.localJoin(accepted.id(),ControlBootstrapProfileState.Role.SOURCE,accepted.binding(),ControlBootstrapMembership.JoinAction.BEGIN)).code);
        assertEquals("REGISTERED",c.store.member(c.member.siteId()).get("state"));verify(c.coordinator,never()).beginOwnedJoinExpected(any(),anyString(),anyString(),any(),anyLong(),any(),anyString(),any());
    }}
    @Test void committedOwnEpochResumesAfterLostApplyWithoutChangingFrozenSource()throws Exception{try(var c=fixture.new Case(true)){
        registered(c);c.manager.join(c.profile.frozen);var once=new AtomicBoolean();c.phaseAfter=phase->{if(phase.equals("APPLY:campus")&&once.compareAndSet(false,true))throw new ControlFailure(503,"FIXTURE_LOST_APPLY_ACK");};
        assertThrows(ControlFailure.class,()->c.manager.reconcileJoin(c.profile.frozen));var accepted=c.manager.original(base.spec.id());assertEquals("COMMITTED",c.store.change(accepted.joinId()).get("state"));
        assertEquals(2,base.db.runtime().queryForObject("SELECT epoch FROM eatwhat_cluster.local_state WHERE singleton",Integer.class));assertEquals(1,c.target.runtime().queryForObject("SELECT epoch FROM eatwhat_cluster.local_state WHERE singleton",Integer.class));active(c);
    }}
    @Test void unrelatedRosterChangeCannotBecomeAnApprovedJoinSuccessor()throws Exception{try(var c=fixture.new Case(true)){
        registered(c);var other=new ClusterMember("unrelated",UUID.randomUUID(),"EXTERNAL",2);var primary=new ClusterStore(base.db.runtime(),base.db.transactions(),base.site.getClusterId(),new you.v50to.eatwhat.sync.transport.NodeIdentity(base.site.getId(),base.site.getInstanceId()),base.lease);
        primary.register(base.actor,UUID.randomUUID(),1,"unrelated",other);assertEquals("BOOTSTRAP_PLAN_CHANGED",assertThrows(ControlFailure.class,()->c.manager.join(c.profile.frozen)).code);
        assertEquals(List.of("register"),c.sent);assertEquals("REGISTERED",c.store.member(c.member.siteId()).get("state"));
    }}
    @Test void approvalRevokedAfterRealPrepareCannotPersistTheVoteOrBeBypassedByWorker()throws Exception{try(var c=fixture.new Case(true)){
        registered(c);c.manager.join(c.profile.frozen);var once=new AtomicBoolean();c.phaseAfter=phase->{if(phase.equals("PREPARED:campus")&&once.compareAndSet(false,true))base.db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(base.actor));};
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->c.manager.reconcileJoin(c.profile.frozen)).code);var accepted=c.manager.original(base.spec.id());
        assertEquals("PREPARING",c.store.change(accepted.joinId()).get("state"));assertEquals(0,base.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.change_vote WHERE change_id=?",Integer.class,accepted.joinId()));assertEquals(1L,c.store.status().get("epoch"));
        var primary=new ClusterStore(base.db.runtime(),base.db.transactions(),base.site.getClusterId(),new you.v50to.eatwhat.sync.transport.NodeIdentity(base.site.getId(),base.site.getInstanceId()),base.lease);
        assertEquals("BOOTSTRAP_MEMBERSHIP_TASK_REQUIRED",assertThrows(ClusterFailure.class,()->new ClusterMembershipCoordinator(base.site.getClusterId(),primary,c.profile.catalog,mock(ClusterMembershipCoordinator.Nodes.class)).reconcile("cluster-worker",accepted.joinId())).getMessage());
    }}
    @Test void missingOriginalJoinLedgerCannotBeRecreatedOrGrantDispatch()throws Exception{try(var c=fixture.new Case(true)){
        c.configure();Files.delete(base.store.directory().resolve("controller-membership-join-"+base.spec.id()+".json"));
        assertEquals("BOOTSTRAP_MEMBERSHIP_RECORD_MISSING",assertThrows(ControlFailure.class,c::configure).code);assertTrue(c.sent.isEmpty());assertEquals(1,mockingDetails(c.runtime).getInvocations().stream().filter(invocation->invocation.getMethod().getName().equals("ready")).count());
    }}
}
