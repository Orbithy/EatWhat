package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.control.*;
import you.v50to.eatwhat.config.SiteProperties;
import org.testcontainers.containers.GenericContainer;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Real authority database and deployment worker, with isolated remote command receipts. */
class ClusterControlHandoffIT {
    static final GenericContainer<?> POSTGRES=IsolatedContainers.postgres("control-handoff");
    @BeforeAll static void start(){POSTGRES.start();}
    @AfterAll static void stop(){POSTGRES.stop();}
    EventFoundationTestSupport.Database db;
    ClusterMember campus;
    ClusterControlLease first,standby;
    @TempDir Path directory;
    DeploymentExecutor executor,otherExecutor;
    ClusterControlHandoff handoff,otherHandoff;
    final CountDownLatch applying=new CountDownLatch(1),finishApply=new CountDownLatch(1);
    final AtomicBoolean blockApply=new AtomicBoolean(),unknownApply=new AtomicBoolean();
    ControlProperties settings;
    String sha;

    @BeforeEach void controllers()throws Exception {
        UUID cluster=UUID.randomUUID();campus=new ClusterMember("campus",UUID.randomUUID(),"CAMPUS",0);
        var site=new SiteProperties();site.setId(campus.siteId());site.setInstanceId(campus.instanceId());site.setMode(SiteProperties.Mode.CAMPUS);site.setClusterId(cluster);site.setAllocationSlot(0);
        db=EventFoundationTestSupport.createDatabase(POSTGRES,site);String role="\""+db.runtimeUsername()+"\"";
        db.admin().execute("GRANT USAGE ON SCHEMA eatwhat_cluster TO "+role);db.admin().execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA eatwhat_cluster TO "+role);db.admin().execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA eatwhat_cluster TO "+role);
        first=new ClusterControlLease(db.runtime(),cluster,5);standby=new ClusterControlLease(db.runtime(),cluster,5);
        new ClusterStore(db.runtime(),db.transactions(),cluster,campus.identity(),first).initialize();new ClusterStore(db.runtime(),db.transactions(),cluster,campus.identity(),standby).initialize();
        settings=new ControlProperties();settings.getDeployment().setEnabled(true);
        Path artifact=directory.resolve("candidate.jar"),key=directory.resolve("key"),known=directory.resolve("known_hosts");Files.writeString(artifact,"synthetic isolated artifact");Files.writeString(key,"synthetic key");Files.writeString(known,"synthetic host");Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-------"));sha=DeploymentExecutor.artifactSha(artifact);
        settings.getDeployment().setSshExecutable(Path.of("/usr/bin/ssh"));settings.getDeployment().setIdentityFile(key);settings.getDeployment().setKnownHostsFile(known);
        var target=new ControlProperties.DeploymentTarget();target.setHost("campus.invalid");target.setUser("deploy");target.setSiteId(campus.siteId());target.setInstanceId(campus.instanceId());target.setCurrentSha256("b".repeat(64));target.setRestoreEvidenceId("synthetic-restore");settings.getDeployment().getTargets().put("campus",target);
        var release=new ControlProperties.Release();release.setArtifact(artifact);release.setSha256(sha);release.setAcceptanceEvidenceId("synthetic-review");release.setRollbackCompatible(true);release.setCompatibleFromSha256(List.of("b".repeat(64)));settings.getReleases().put("candidate",release);
        var audit=new ControlAudit(directory.resolve("shared"));
        DeploymentExecutor.Driver driver=(action,id,t,file,digest)->{
            if(action.equals("apply")){
                applying.countDown();if(blockApply.get()&&!finishApply.await(10,TimeUnit.SECONDS))throw new java.io.IOException("fixture timed out");
                if(unknownApply.getAndSet(false))throw new java.io.IOException("fixture lost response");
            }
            return Map.of("id",id.toString(),"siteId",t.getSiteId(),"instanceId",t.getInstanceId().toString(),"artifactSha256",digest,"state",action.equals("prepare")?"PREPARED":"SUCCEEDED");
        };
        executor=new DeploymentExecutor(settings,audit,driver);otherExecutor=new DeploymentExecutor(settings,audit,driver);
        handoff=new ClusterControlHandoff(db.runtime(),db.transactions(),first,executor,"campus-control");
        otherHandoff=new ClusterControlHandoff(db.runtime(),db.transactions(),standby,otherExecutor,"outside-control");
        assertTrue(first.tick());assertFalse(standby.tick());
    }
    UUID holder(ClusterControlLease lease){return UUID.fromString(lease.status().get("localControllerId").toString());}
    long generation(){return ((Number)first.status().get("generation")).longValue();}
    Map<String,Object> execute(UUID id){return executor.execute("admin",id,"candidate",sha,List.of(new DeploymentExecutor.Target("campus",campus.siteId(),campus.instanceId())),first::requireHeld);}
    void awaitState(UUID id,String expected)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until){if(executor.get(id).get("state").equals(expected))return;Thread.sleep(10);}fail("Deployment did not reach "+expected);
    }
    void complete(UUID request){first.tick();assertEquals("TRANSFERRING",handoff.get(request).get("state"));standby.tick();assertEquals("COMPLETED",handoff.get(request).get("state"));assertFalse((Boolean)first.status().get("localLeader"));assertTrue((Boolean)standby.status().get("localLeader"));}

    @Test void actualDeploymentFinishesBeforeTargetGetsLeadership()throws Exception {
        blockApply.set(true);UUID job=UUID.randomUUID();execute(job);assertTrue(applying.await(3,TimeUnit.SECONDS));
        UUID request=UUID.randomUUID();assertEquals("WAITING",otherHandoff.request("admin",request,generation(),holder(standby)).get("state"));
        first.tick();standby.tick();assertEquals("DEPLOYMENT_RUNNING",handoff.get(request).get("reason"));assertTrue((Boolean)first.status().get("localLeader"));
        finishApply.countDown();awaitState(job,"SUCCEEDED");complete(request);
        assertThrows(ClusterFailure.class,first::requireHeld);assertEquals(2,((Number)standby.status().get("generation")).intValue());
    }
    @Test void manualOnlyStandbyWaitsForAcceptedJobThenAcceptsOnlyExplicitHandoff()throws Exception {
        standby=new ClusterControlLease(db.runtime(),firstStoreCluster(),5,true,false);
        otherHandoff=new ClusterControlHandoff(db.runtime(),db.transactions(),standby,otherExecutor,"manual-only");
        assertFalse(standby.tick());assertEquals("MANUAL_HANDOFF_ONLY",standby.status().get("takeoverMode"));
        assertTrue(((List<Map<String,Object>>)handoff.view().get("controllers")).stream().anyMatch(c->c.get("name").equals("manual-only")));
        blockApply.set(true);UUID job=UUID.randomUUID();execute(job);assertTrue(applying.await(3,TimeUnit.SECONDS));
        UUID request=UUID.randomUUID();handoff.request("admin",request,generation(),holder(standby));
        first.tick();assertFalse(standby.tick());assertEquals("WAITING",handoff.get(request).get("state"));
        assertEquals("DEPLOYMENT_RUNNING",handoff.get(request).get("reason"));
        finishApply.countDown();awaitState(job,"SUCCEEDED");complete(request);
        assertTrue(standby.tick());standby.requireHeld();
        db.admin().update("UPDATE eatwhat_cluster.control_lease SET expires_at=clock_timestamp()-interval '1 second'");
        assertTrue(standby.tick(),"An explicitly assigned holder may renew its own lease without claiming another controller's lease");
        assertThrows(ClusterFailure.class,first::requireHeld);
    }
    @Test void unknownRemoteOutcomeWaitsForReadOnlyReconciliation()throws Exception {
        unknownApply.set(true);UUID job=UUID.randomUUID();execute(job);awaitState(job,"OUTCOME_UNKNOWN");
        UUID request=UUID.randomUUID();handoff.request("admin",request,generation(),holder(standby));first.tick();
        assertEquals("WAITING",handoff.get(request).get("state"));assertEquals("DEPLOYMENT_RECONCILIATION_REQUIRED",handoff.get(request).get("reason"));assertTrue((Boolean)first.status().get("localLeader"));
        assertEquals("SUCCEEDED",executor.reconcile("admin",job).get("state"));complete(request);
    }
    @Test void pendingRequestBlocksNewJobsAndCanBeCancelledWithoutChangingTheLease(){
        handoff.runtimeIdle(()->false);UUID request=UUID.randomUUID();handoff.request("admin",request,generation(),holder(standby));first.tick();
        assertEquals("CONTROL_CYCLE_RUNNING",handoff.get(request).get("reason"));
        assertEquals("CONTROL_HANDOFF_PENDING",assertThrows(ClusterFailure.class,()->execute(UUID.randomUUID())).getMessage());
        assertEquals("CANCELLED",otherHandoff.cancel("admin",request).get("state"));assertEquals("CANCELLED",handoff.cancel("admin",request).get("state"));first.requireAccepting();assertEquals(1,generation());
    }
    @Test void requestIdsAreIdempotentAndConflictingRequestsCannotRetargetHandoff(){
        UUID request=UUID.randomUUID(),target=holder(standby);handoff.request("admin",request,1,target);
        assertEquals("WAITING",otherHandoff.request("admin",request,1,target).get("state"));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(ClusterFailure.class,()->handoff.request("admin",request,2,target)).getMessage());
        assertEquals("CONTROL_HANDOFF_PENDING",assertThrows(ClusterFailure.class,()->handoff.request("admin",UUID.randomUUID(),1,target)).getMessage());
        complete(request);assertEquals("COMPLETED",handoff.request("admin",request,1,target).get("state"));
        assertEquals("CONTROL_HANDOFF_NOT_CANCELLABLE",assertThrows(ClusterFailure.class,()->handoff.cancel("admin",request)).getMessage());
    }
    @Test void targetsMustBeLiveAndUseTheSharedDeploymentState(){
        UUID target=holder(standby);
        assertEquals("CONTROL_GENERATION_CHANGED",assertThrows(ClusterFailure.class,()->handoff.request("admin",UUID.randomUUID(),2,target)).getMessage());
        assertEquals("CONTROL_TARGET_ALREADY_LEADER",assertThrows(ClusterFailure.class,()->handoff.request("admin",UUID.randomUUID(),1,holder(first))).getMessage());
        db.admin().update("UPDATE eatwhat_cluster.control_process SET state_id=? WHERE holder_id=?",UUID.randomUUID(),target);
        assertEquals("CONTROL_SHARED_STATE_REQUIRED",assertThrows(ClusterFailure.class,()->handoff.request("admin",UUID.randomUUID(),1,target)).getMessage());
        db.admin().update("UPDATE eatwhat_cluster.control_process SET expires_at=clock_timestamp()-interval '1 second' WHERE holder_id=?",target);
        assertEquals("CONTROL_TARGET_OFFLINE",assertThrows(ClusterFailure.class,()->handoff.request("admin",UUID.randomUUID(),1,target)).getMessage());
    }
    @Test void ordinaryFailoverDoesNotMisreportAnInterruptedGracefulHandoff(){
        handoff.runtimeIdle(()->false);UUID request=UUID.randomUUID();handoff.request("admin",request,1,holder(standby));
        db.admin().update("UPDATE eatwhat_cluster.control_lease SET expires_at=clock_timestamp()-interval '1 second'");standby.tick();
        assertEquals("FAILED",handoff.get(request).get("state"));assertEquals("SOURCE_LEASE_CHANGED",handoff.get(request).get("reason"));
    }
    @Test void expiredLeaseCannotBeTakenOverByAControllerThatLostTheSharedTaskState(){
        var foreign=new ClusterControlLease(db.runtime(),firstStoreCluster(),5);try(var isolated=new DeploymentExecutor(settings,new ControlAudit(directory.resolve("different-state")))){new ClusterControlHandoff(db.runtime(),db.transactions(),foreign,isolated,"different-state");first.release();assertFalse(foreign.tick());assertEquals("CONTROL_SHARED_STATE_REQUIRED",foreign.status().get("reason"));assertThrows(ClusterFailure.class,foreign::requireHeld);assertTrue(standby.tick());assertTrue((Boolean)standby.status().get("localLeader"));}
    }
    UUID firstStoreCluster(){return db.admin().queryForObject("SELECT cluster_id FROM eatwhat_cluster.state WHERE singleton",UUID.class);}
    @Test void handoffAlsoWaitsForAnAlreadyAdmittedControlOperation()throws Exception {
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var operation=pool.submit(()->handoff.operation(()->{entered.countDown();try{assertTrue(finish.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return true;}));
            assertTrue(entered.await(2,TimeUnit.SECONDS));UUID request=UUID.randomUUID();handoff.request("admin",request,1,holder(standby));first.tick();
            assertEquals("CONTROL_CYCLE_RUNNING",handoff.get(request).get("reason"));assertTrue((Boolean)first.status().get("localLeader"));
            finish.countDown();assertTrue(operation.get(3,TimeUnit.SECONDS));complete(request);
        }finally{finish.countDown();}
    }
    @AfterEach void closeExecutors(){finishApply.countDown();if(executor!=null)executor.close();if(otherExecutor!=null)otherExecutor.close();}
}
