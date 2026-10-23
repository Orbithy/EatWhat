package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import you.v50to.eatwhat.cluster.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real durable journal/locks and background lifecycle; authority and native
 * workflow are explicit mocks. No remote installation is asserted here. */
class ControlBootstrapWorkerTest {
    @TempDir Path root;ControlServerStore store;ControlAudit audit;DeploymentExecutor deployment;ControlBootstrapJournal journal;
    ControlServers servers;ClusterControlLease lease;ControlBootstrapWorkflow workflow;ControlBootstrapWorker worker;
    UUID id=UUID.randomUUID(),holder=UUID.randomUUID();ControlBootstrapAdmission.Frozen frozen;
    static <T>T stub(Class<T> type){return mock(type,withSettings().mockMaker(org.mockito.MockMakers.INLINE));}
    @BeforeEach void setup()throws Exception{
        root=root.toRealPath();audit=new ControlAudit(root);store=new ControlServerStore(audit);deployment=new DeploymentExecutor(new ControlProperties(),audit);
        servers=stub(ControlServers.class);lease=stub(ClusterControlLease.class);workflow=stub(ControlBootstrapWorkflow.class);journal=new ControlBootstrapJournal(store,audit,deployment,servers);
        when(lease.controllerId()).thenReturn(holder);when(lease.status()).thenReturn(Map.of("localLeader",true,"generation",3));
        worker=new ControlBootstrapWorker(store,journal,lease,workflow,deployment);
        var spec=new ControlBootstrapJournal.Spec(id,UUID.randomUUID(),1,UUID.randomUUID(),1,UUID.randomUUID(),2,"a".repeat(64),"fixture","b".repeat(64),2);
        var review=Map.<String,Object>of("sourceAddress","10.253.74.1","targetAddress","10.253.74.3","targetEndpoint","target.example.invalid","wireguardPort",51820,"httpPort",18090,"appBaseUrl","https://fixture.example.invalid/api");
        frozen=new ControlBootstrapAdmission.Frozen(spec,review,"123","fixture-admin",UUID.randomUUID(),holder,3,null,null,null,null,null,null,null,null,Map.of(),null,null,"fixture-binding");
    }
    void accept(boolean managed){
        var inputs=managed?Map.<String,Object>of("admission",frozen,"executionProtocol","eatwhat-controller-bootstrap-managed-v1"):Map.<String,Object>of("old","fixture");
        journal.preview("123",frozen.spec(),frozen.review(),inputs,()->{});journal.accept("123",id,ControlBootstrapJournal.PERMISSIONS,()->{});
    }
    @AfterEach void close(){worker.stop();deployment.close();}
    @Test void queuedTaskRunsIndependentlyAndUnknownDispatchIsNeverRepeatedAfterWorkerRestart(){
        accept(true);var dispatches=new AtomicInteger();var observations=new AtomicInteger();
        when(workflow.advance(id)).thenAnswer(inv->{var claim=journal.claim(id,ControlBootstrapJournal.Phase.SOURCE_RUNTIME,()->{});
            if(claim.dispatchAllowed())dispatches.incrementAndGet();else observations.incrementAndGet();throw new ControlFailure(503,"BOOTSTRAP_NATIVE_OBSERVATION_UNAVAILABLE");});
        worker.cycle();assertEquals(1,dispatches.get());assertEquals("OUTCOME_UNKNOWN",journal.job(id).get("state"));assertEquals("BOOTSTRAP_NATIVE_OBSERVATION_UNAVAILABLE",journal.job(id).get("executionCode"));
        worker=new ControlBootstrapWorker(store,journal,lease,workflow,deployment);worker.cycle();assertEquals(1,dispatches.get());assertEquals(1,observations.get());
        assertNotNull(deployment.idleFenceReason());assertNotNull(deployment.whenIdle(()->fail("unknown task must hold handoff")));
    }
    @Test void anotherControllerOrGenerationCannotAdoptTheOriginalApproval(){
        accept(true);when(lease.status()).thenReturn(Map.of("localLeader",true,"generation",4));worker.cycle();
        assertEquals(true,journal.job(id).get("requiresReapproval"));assertEquals("BOOTSTRAP_ORIGINAL_CONTROLLER_REQUIRED",journal.job(id).get("executionCode"));verifyNoInteractions(workflow);
        when(lease.status()).thenReturn(Map.of("localLeader",true,"generation",3));worker.cycle();verifyNoInteractions(workflow);
    }
    @Test void standbyAndUnavailableLeaseNeverRunTheTask(){
        accept(true);when(lease.status()).thenReturn(Map.of("localLeader",false));worker.cycle();verifyNoInteractions(workflow);
        doThrow(new ClusterFailure(503,"CONTROL_LEASE_NOT_HELD")).when(lease).requireHeld();worker.cycle();verifyNoInteractions(workflow);
    }
    @Test void legacyInternalTasksStayReadableAndNeverBecomeManagedWork(){
        accept(false);worker.cycle();verifyNoInteractions(workflow);assertEquals("QUEUED",journal.job(id).get("state"));assertNotNull(deployment.idleFenceReason());
    }
    @Test void sharedWorkerLockDoesNotDispatchFromTwoManagers(){
        accept(true);var path=root.resolve("controller-bootstrap-execution.lock");
        try{Files.createFile(path,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.WRITE);var held=channel.lock()){worker.cycle();verifyNoInteractions(workflow);}
        }catch(Exception e){fail(e);}
        worker.cycle();verify(workflow).advance(id);
    }
    @Test void lifecycleStartsAndStopsWithoutARequestOrBusyLoop(){
        assertFalse(worker.isRunning());worker.start();assertTrue(worker.isRunning());worker.start();worker.stop();assertFalse(worker.isRunning());verifyNoInteractions(workflow);
    }
    @Test void idlePollingDoesNotFenceOrdinaryWritesWhenThereIsNoAcceptedJob(){
        doAnswer(inv->{assertNull(deployment.idleFenceReason());worker.cycle();return null;}).when(lease).requireHeld();
        worker.cycle();assertNull(deployment.idleFenceReason());verifyNoInteractions(workflow);
    }
}
