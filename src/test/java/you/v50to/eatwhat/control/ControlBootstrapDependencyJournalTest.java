package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real durable journal/lock files; native dependency operation proofs are
 * synthetic. Native SSH/systemd/keys are accepted by separate VM evidence. */
class ControlBootstrapDependencyJournalTest {
    @TempDir Path root;
    ControlBootstrapJournalTest fixture;ControlBootstrapJournal journal;
    static final ControlBootstrapJournal.DependencyOperation KEY=ControlBootstrapJournal.DependencyOperation.KEY,
            SOURCE=ControlBootstrapJournal.DependencyOperation.SOURCE,CONNECT=ControlBootstrapJournal.DependencyOperation.CONNECT;
    static final ControlBootstrapJournal.Phase PHASE=ControlBootstrapJournal.Phase.DEPENDENCY_LINK;
    @BeforeEach void setup()throws Exception{
        fixture=new ControlBootstrapJournalTest();fixture.root=root;fixture.setup();fixture.accept();journal=fixture.journal;
        for(var phase:ControlBootstrapJournal.Phase.values()){
            if(phase==PHASE)break;var claim=journal.claim(fixture.id,phase,()->{});
            journal.observe(fixture.id,phase,claim.claimToken(),fixture.receipt(phase,phase.success));
        }
    }
    @AfterEach void stop(){fixture.close();}
    String binding(ControlBootstrapJournal.DependencyOperation op){return (op==SOURCE?"c":"a").repeat(64);}
    ControlBootstrapJournal.DependencyDispatch claim(ControlBootstrapJournal.DependencyOperation op){return journal.claimDependencyOperation(fixture.id,op,binding(op),"b".repeat(64),()->{});}
    Map<String,Object> receipt(ControlBootstrapJournal.DependencyDispatch d,String state){return new LinkedHashMap<>(Map.of(
            "id",d.nativeId().toString(),"protocol",PHASE.protocol,"operation",d.operation().name(),"role",d.operation().role,
            "binding",d.nativeBinding(),"state",state,"syntheticSecret","private-never-in-public"));}
    Map<String,Object> observe(ControlBootstrapJournal.DependencyDispatch d,String state){return journal.observeDependencyOperation(fixture.id,d.operation(),d.claimToken(),receipt(d,state));}
    ControlBootstrapJournal.DependencyDispatch done(ControlBootstrapJournal.DependencyOperation op){var d=claim(op);assertTrue(d.dispatchAllowed());observe(d,op.success);return d;}
    @Test void restartCannotRepeatKeyButOriginalUndispatchedSourceAndConnectRemainAvailable()throws Exception{
        var key=claim(KEY);String raw=Files.readString(fixture.path());
        journal=new ControlBootstrapJournal(fixture.store,fixture.audit,fixture.deployment,fixture.servers);
        var after=journal.claimDependencyOperation(fixture.id,KEY,binding(KEY),"b".repeat(64),()->fail("Already dispatched KEY is observation only"));
        assertFalse(after.dispatchAllowed());assertEquals(key.claimToken(),after.claimToken());assertEquals(raw,Files.readString(fixture.path()));
        observe(after,"NOT_STARTED");assertFalse(claim(KEY).dispatchAllowed());assertThrows(ControlFailure.class,()->claim(SOURCE));
        observe(after,KEY.success);var source=done(SOURCE);var connect=done(CONNECT);
        assertEquals(key.nativeId(),source.nativeId());assertEquals(key.nativeId(),connect.nativeId());
        assertEquals(ControlBootstrapJournal.Phase.CONTROLLER_CONFIG.name(),journal.job(fixture.id).get("stage"));
        assertFalse(journal.job(fixture.id).toString().contains("private-never-in-public"));assertFalse(Files.readString(fixture.path()).contains("private-never-in-public"));
    }
    @Test void noParentDispatchOrSyntheticParentVerifiedCanSkipThreeOperations(){
        assertEquals("BOOTSTRAP_DEPENDENCY_OPERATION_REQUIRED",assertThrows(ControlFailure.class,()->journal.claim(fixture.id,PHASE,()->{})).code);
        var key=done(KEY);
        assertEquals("BOOTSTRAP_DEPENDENCY_OPERATION_REQUIRED",assertThrows(ControlFailure.class,()->journal.observe(fixture.id,PHASE,key.claimToken(),fixture.receipt(PHASE,"VERIFIED"))).code);
        assertEquals("BOOTSTRAP_PREVIOUS_PHASE_REQUIRED",assertThrows(ControlFailure.class,()->journal.claim(fixture.id,ControlBootstrapJournal.Phase.CONTROLLER_CONFIG,()->{})).code);
        assertThrows(ControlFailure.class,()->claim(CONNECT));
    }
    @Test void targetKeyAndConnectMustUseSameNativeHeaderAndExecutor(){
        done(KEY);done(SOURCE);
        for(var values:List.of(List.of("d".repeat(64),"b".repeat(64)),List.of(binding(CONNECT),"d".repeat(64))))
            assertEquals("BOOTSTRAP_DEPENDENCY_BINDING_CHANGED",assertThrows(ControlFailure.class,()->journal.claimDependencyOperation(fixture.id,CONNECT,values.get(0),values.get(1),()->{})).code);
        assertTrue(claim(CONNECT).dispatchAllowed());
    }
    @Test void everyUndispatchedOperationRechecksApprovalAndInvalidationIsPermanent(){
        var key=done(KEY);var count=new AtomicInteger();
        assertEquals("SERVER_REVISION_CHANGED",assertThrows(ControlFailure.class,()->journal.claimDependencyOperation(fixture.id,SOURCE,binding(SOURCE),"b".repeat(64),()->{
            count.incrementAndGet();throw new ControlFailure(409,"SERVER_REVISION_CHANGED");})).code);
        assertEquals(1,count.get());assertEquals(true,journal.job(fixture.id).get("requiresReapproval"));
        assertFalse(claim(KEY).dispatchAllowed());observe(key,KEY.success);
        assertEquals("BOOTSTRAP_REAPPROVAL_REQUIRED",assertThrows(ControlFailure.class,()->claim(SOURCE)).code);
        assertNotNull(fixture.deployment.whenIdle(()->fail("Partial bootstrap still blocks handoff")));
    }
    @Test void receiptMustMatchExactNativeIdOperationRoleAndFrozenHeader(){
        var key=claim(KEY);
        for(var change:List.of(Map.entry("id",UUID.randomUUID().toString()),Map.entry("operation","SOURCE"),Map.entry("role","SOURCE"),Map.entry("binding","f".repeat(64)))){
            var r=receipt(key,KEY.success);r.put(change.getKey(),change.getValue());
            assertEquals("BOOTSTRAP_PHASE_RECEIPT_MISMATCH",assertThrows(ControlFailure.class,()->journal.observeDependencyOperation(fixture.id,KEY,key.claimToken(),r)).code);
        }
        assertEquals("BOOTSTRAP_PHASE_TOKEN_MISMATCH",assertThrows(ControlFailure.class,()->journal.observeDependencyOperation(fixture.id,KEY,"forged",receipt(key,KEY.success))).code);
        assertFalse(claim(KEY).dispatchAllowed());
    }
    @Test void oldSuccessfulKeyObservationCannotRewindLaterControllerPhase(){
        var key=done(KEY);done(SOURCE);done(CONNECT);var phase=ControlBootstrapJournal.Phase.CONTROLLER_CONFIG;
        var next=journal.claim(fixture.id,phase,()->{});journal.observe(fixture.id,phase,next.claimToken(),fixture.receipt(phase,"RUNNING"));
        String before=journal.job(fixture.id).get("stage").toString();observe(key,KEY.success);assertEquals(before,journal.job(fixture.id).get("stage"));
        assertFalse(claim(KEY).dispatchAllowed());
    }
    @Test void claimedOperationCannotAdoptChangedInputOrAgentEvenBeforeReceipt(){
        claim(KEY);
        assertEquals("BOOTSTRAP_DEPENDENCY_BINDING_CHANGED",assertThrows(ControlFailure.class,()->journal.claimDependencyOperation(fixture.id,KEY,"f".repeat(64),"b".repeat(64),()->{})).code);
        assertEquals("BOOTSTRAP_DEPENDENCY_BINDING_CHANGED",assertThrows(ControlFailure.class,()->journal.claimDependencyOperation(fixture.id,KEY,binding(KEY),"f".repeat(64),()->{})).code);
    }
    @Test void unknownSourceRemainsAnObservationAndDoesNotAuthorizeConnect(){
        done(KEY);var source=claim(SOURCE);var view=observe(source,"OUTCOME_UNKNOWN");assertEquals("OUTCOME_UNKNOWN",view.get("state"));
        assertFalse(claim(SOURCE).dispatchAllowed());assertThrows(ControlFailure.class,()->claim(CONNECT));
        observe(source,SOURCE.success);assertTrue(claim(CONNECT).dispatchAllowed());
    }
}
