package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import you.v50to.eatwhat.control.*;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import you.v50to.eatwhat.support.SourceMaterialFixture;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real authority/admin/lease SQL, source files, artifact policy, network
 * derivation and encrypted parent approval. SSH/preflight and source process/DB
 * capture envelopes are explicit fixtures, not native host discovery. */
class ControlBootstrapPlansIT {
    @TempDir Path root;ControlBootstrapAdmissionIT base;ControlBootstrapPlans plans;ControlBootstrapTargetNetwork network;
    ControlBootstrapProfileApplyIT.SetupHeartbeat heartbeat;
    @BeforeAll static void start(){ControlBootstrapAdmissionIT.PG.start();}
    @AfterAll static void stop(){ControlBootstrapAdmissionIT.PG.stop();}
    @BeforeEach void setup()throws Exception{
        base=new ControlBootstrapAdmissionIT();base.root=root;base.setup();heartbeat=new ControlBootstrapProfileApplyIT.SetupHeartbeat(base.lease);
        var files=new SourceMaterialFixture(Files.createDirectory(root.resolve("source")));
        files.properties.put("app.site.cluster-id",base.site.getClusterId().toString());files.properties.put("app.site.instance-id",base.site.getInstanceId().toString());
        files.properties.put("app.control.controller-bootstrap.enabled","true");files.properties.put("spring.datasource.url",base.db.url());files.properties.put("spring.datasource.username",base.db.runtimeUsername());
        SourceMaterialFixture.write(files.root.resolve("state/controller-state-id"),base.deployment.stateIdentity().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        SourceMaterialFixture.write(files.root.resolve("state/servers.key"),Files.readAllBytes(base.store.directory().resolve("servers.key")));
        SourceMaterialFixture.write(files.config.resolve("cluster-profiles.json"),files.json.writeValueAsBytes(Map.of("campus",Map.of("siteId","campus","instanceId",base.site.getInstanceId().toString(),"mode","CAMPUS","allocationSlot",0,"origin","http://127.0.0.1:18090","tokenFile",files.properties.get("app.control.node-token-file")))));
        SourceMaterialFixture.write(files.config.resolve("application.properties"),ControlBusinessRuntime.render(files.properties).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var expected=ControllerSourceSnapshot.discover(files.root,base.admission.sourceAuthority(base.actor));var captured=ControllerSourceSnapshot.capture(files.root,expected);
        base.sourceRequest=new ControlBootstrapSources.Request(base.acceptedArtifacts.controller().sha256(),base.acceptedArtifacts.controller().sha256(),"c".repeat(64),expected);
        base.sourceCaptured=new ControlBootstrapSources.Captured(base.sourceId,1,base.ssh.routeBinding(base.store.server(base.sourceId)),
                Map.of("captureBinding","d".repeat(64),"fileHashes",captured.hashes(),"sourceProcess",Map.of("pid",42,"startTicks","fixture-start"),"proof",Map.of("generation",1L,"epoch",1L)),
                Map.of("properties",captured.properties(),"privateFiles",captured.privateFiles()));
        when(base.sources.discover(eq(base.sourceId),eq(1L),any())).thenReturn(new ControlBootstrapSources.Discovered(base.sourceRequest,base.sourceCaptured));
        when(base.sources.capture(base.sourceId,1,base.sourceRequest)).thenReturn(base.sourceCaptured);when(base.sources.observeExecuting(base.sourceId,1,base.sourceRequest)).thenReturn(base.sourceCaptured);
        when(base.preflights.capture(any(),anyLong(),anyMap())).thenAnswer(inv->{UUID id=inv.getArgument(0);long revision=inv.getArgument(1);Map<String,Object> input=inv.getArgument(2);
            return new ControlBootstrapPreflights.Captured(id,revision,base.ssh.routeBinding(base.store.server(id)),input,Map.of("proof","e".repeat(64)));});
        network=new ControlBootstrapTargetNetwork(base.admission,new ControlBootstrapTargetMaterials(base.admission));
        plans=new ControlBootstrapPlans(base.servers,base.store,base.admission,base.sources,base.preflights,base.artifacts,network,base.journal);
    }
    @AfterEach void close()throws Exception{try{if(heartbeat!=null)heartbeat.close();}finally{if(base!=null)base.close();}}
    ControlBootstrapStorage.Selection storage(String secret){return new ControlBootstrapStorage.Selection("R2","https://r2.example.invalid","auto","eatwhat","fixture-target-access",secret,false,"https://objects.example.invalid",null);}
    ControlBootstrapPlans.Request request(String secret){return new ControlBootstrapPlans.Request(base.spec.id(),base.sourceId,1,base.targetId,1,"fixture-business",1,"10.253.74.1","10.253.74.3","target.example.invalid",51820,18090,"https://business.example.invalid/api",storage(secret));}
    ControlBootstrapPlans operatorPlans(you.v50to.eatwhat.cluster.ClusterControlLease lease,ControlSsh ssh){
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("cluster",base.cluster,"lease",lease));
        var servers=new ControlServers(base.store,ssh,base.deployment,base.audit,base.site,beans.getBeanProvider(you.v50to.eatwhat.cluster.ClusterProperties.class),beans.getBeanProvider(you.v50to.eatwhat.cluster.ClusterControlLease.class));
        var admission=new ControlBootstrapAdmission(base.settings,base.site,base.cluster,base.db.runtime(),base.db.transactions(),lease,base.deployment,base.store,servers,ssh,base.artifacts,base.preflights,base.sources);
        var routes=new ControlBootstrapTargetNetwork(admission,new ControlBootstrapTargetMaterials(admission));
        return new ControlBootstrapPlans(servers,base.store,admission,base.sources,base.preflights,base.artifacts,routes,base.journal);
    }
    @Test void newControllerCanCancelOnlyAnUndispatchedOldApprovalUsingCurrentAdministratorAndLease()throws Exception{
        plans.preview(base.actor,request("fixture-target-secret"));var queued=plans.accept(base.actor,base.spec.id(),ControlBootstrapJournal.PERMISSIONS);
        String originalBody=base.store.readJson(base.store.directory().resolve("controller-job-"+base.spec.id()+".json")).get("_body").toString();
        heartbeat.close();heartbeat=null;base.lease.release();
        var replacement=new you.v50to.eatwhat.cluster.ClusterControlLease(base.db.runtime(),base.site.getClusterId(),120);
        new you.v50to.eatwhat.cluster.ClusterControlHandoff(base.db.runtime(),base.db.transactions(),replacement,base.deployment,"replacement-primary");assertTrue(replacement.tick());heartbeat=new ControlBootstrapProfileApplyIT.SetupHeartbeat(replacement);
        base.deployment.addIdleGuard(()->ReflectionTestUtils.invokeMethod(base.journal,"busyReason"));
        String operator=base.db.admin().queryForObject("INSERT INTO users(nick_name,password_hash,role) VALUES('canceladmin','fixture-current-admin','admin') RETURNING id",Long.class).toString();
        base.db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(base.actor));
        var noNetwork=mock(ControlSsh.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));var current=operatorPlans(replacement,noNetwork);assertEquals(false,current.capabilities().get("canPlan"));clearInvocations(base.sources,base.preflights);
        var cancelled=current.cancelUnstarted(operator,base.spec.id(),queued.get("requestHash").toString());
        assertEquals(true,cancelled.get("cancelledBeforeDispatch"));assertEquals("FAILED",cancelled.get("state"));assertEquals(false,cancelled.get("effectsStarted"));assertEquals(false,cancelled.get("requiresReapproval"));
        assertEquals(originalBody,base.store.readJson(base.store.directory().resolve("controller-job-"+base.spec.id()+".json")).get("_body"));
        assertEquals(true,current.capabilities().get("canPlan"));assertEquals(0,base.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.installation_allocation",Integer.class));
        verifyNoInteractions(noNetwork,base.sources,base.preflights);
        base.db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(operator));
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->current.cancelUnstarted(operator,base.spec.id(),queued.get("requestHash").toString())).code);
    }
    @Test void queuedCancellationRemainsAvailableDuringHandoffAndReleasesItsIdleFence()throws Exception{
        plans.preview(base.actor,request("fixture-target-secret"));var queued=plans.accept(base.actor,base.spec.id(),ControlBootstrapJournal.PERMISSIONS);
        var handoff=(you.v50to.eatwhat.cluster.ClusterControlHandoff)ReflectionTestUtils.getField(base.lease,"handoff");
        try(var standbyDeployment=new DeploymentExecutor(base.settings,base.audit)){
            var standby=new you.v50to.eatwhat.cluster.ClusterControlLease(base.db.runtime(),base.site.getClusterId(),120);
            new you.v50to.eatwhat.cluster.ClusterControlHandoff(base.db.runtime(),base.db.transactions(),standby,standbyDeployment,"cancel-standby");assertFalse(standby.tick());
            UUID requestId=UUID.randomUUID();handoff.request(base.actor,requestId,((Number)base.lease.status().get("generation")).longValue(),standby.controllerId());
            assertTrue(base.lease.draining());assertEquals(true,plans.cancelUnstarted(base.actor,base.spec.id(),queued.get("requestHash").toString()).get("cancelledBeforeDispatch"));
            base.lease.tick();assertEquals("TRANSFERRING",handoff.get(requestId).get("state"));assertTrue(standby.tick());assertEquals("COMPLETED",handoff.get(requestId).get("state"));
        }
    }
    @Test void acceptedInitialClaimCanDrainWhileNewPlansAndMembershipAdmissionAreClosed()throws Exception{
        assertEquals(true,plans.capabilities().get("canPlan"));
        plans.preview(base.actor,request("fixture-target-secret"));plans.accept(base.actor,base.spec.id(),ControlBootstrapJournal.PERMISSIONS);
        var f=(ControlBootstrapAdmission.Frozen)ReflectionTestUtils.invokeMethod(base.journal,"originalAdmission",base.spec.id(),false);
        clearInvocations(base.sources,base.preflights);
        // The original accepted claim must use held-lease observations. The
        // accepting probes would be blocked by this task's own idle fence.
        doThrow(new ControlFailure(409,"CONTROLLER_BOOTSTRAP_RUNNING")).when(base.preflights).requireInitialUnchanged(any());
        doThrow(new ControlFailure(409,"CONTROLLER_BOOTSTRAP_RUNNING")).when(base.sources).capture(any(),anyLong(),any());
        base.deployment.addIdleGuard(()->ReflectionTestUtils.invokeMethod(base.journal,"busyReason"));
        var handoff=(you.v50to.eatwhat.cluster.ClusterControlHandoff)ReflectionTestUtils.getField(base.lease,"handoff");
        try(var standbyDeployment=new DeploymentExecutor(base.settings,base.audit)){
            var standby=new you.v50to.eatwhat.cluster.ClusterControlLease(base.db.runtime(),base.site.getClusterId(),120);
            new you.v50to.eatwhat.cluster.ClusterControlHandoff(base.db.runtime(),base.db.transactions(),standby,standbyDeployment,"fixture-standby");assertFalse(standby.tick());
            UUID requestId=UUID.randomUUID();handoff.request(base.actor,requestId,((Number)base.lease.status().get("generation")).longValue(),standby.controllerId());assertTrue(base.lease.draining());
            assertEquals(false,plans.capabilities().get("canPlan"));assertEquals("CONTROL_HANDOFF_PENDING",plans.capabilities().get("reason"));
            assertEquals("CONTROL_HANDOFF_PENDING",assertThrows(you.v50to.eatwhat.cluster.ClusterFailure.class,()->base.servers.requireWrite()).getMessage());
            var claims=new ControlBootstrapPhaseClaims(base.journal,base.admission);
            assertTrue(claims.claim(f,ControlBootstrapJournal.Phase.SOURCE_RUNTIME).dispatchAllowed());
            verify(base.preflights).requireInitialExecutingUnchanged(f.sourceInitial());verify(base.preflights).requireInitialExecutingUnchanged(f.targetInitial());
            verify(base.sources).observeExecuting(base.sourceId,1,base.sourceRequest);verify(base.sources,never()).capture(any(),anyLong(),any());
            assertTrue(base.lease.tick());assertEquals("WAITING",handoff.get(requestId).get("state"));assertTrue((Boolean)base.lease.status().get("localLeader"));
            assertFalse(claims.claim(f,ControlBootstrapJournal.Phase.SOURCE_RUNTIME).dispatchAllowed());
        }
    }
    @Test @SuppressWarnings("unchecked") void actualAuthorityAndFilesCreateAReviewablePreviewThenAnOriginalQueuedApproval()throws Exception{
        var preview=plans.preview(base.actor,request("fixture-target-secret"));assertEquals("PREVIEW_ONLY",preview.get("state"));assertEquals("R2",((Map<?,?>)preview.get("storage")).get("provider"));
        assertFalse(base.store.readJson(root.resolve("state/controller-plan-"+base.spec.id()+".json")).toString().contains("fixture-target-secret"));
        assertFalse(preview.toString().contains("runtime-private-secret"));assertFalse(preview.toString().contains("fixture-target-secret"));
        assertEquals(0,base.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.installation_allocation",Integer.class));assertEquals(1,base.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.member",Integer.class));
        assertEquals("QUEUED",plans.accept(base.actor,base.spec.id(),ControlBootstrapJournal.PERMISSIONS).get("state"));
        var f=(ControlBootstrapAdmission.Frozen)ReflectionTestUtils.invokeMethod(base.journal,"originalAdmission",base.spec.id(),false);base.admission.requireBound(f);
        assertEquals(base.spec.id(),f.spec().id());assertEquals(1,f.spec().epoch());
        var factory=new ControlBootstrapControllerFactory(network,null,null,null,base.journal);
        var bundle=(Map<String,Object>)ReflectionTestUtils.invokeMethod(factory,"bundle",f,network.plan(f));
        assertEquals("fixture-target-secret",((Map<?,?>)bundle.get("properties")).get("app.storage.s3.secret-key"));
        assertFalse(base.admission.privateSourcePayload(f).toString().contains("fixture-target-secret"));
        assertEquals("QUEUED",plans.accept(base.actor,base.spec.id(),ControlBootstrapJournal.PERMISSIONS).get("state"));
    }
    @Test void replayReturnsOriginalPreviewAndChangedCredentialsConflictBeforeMoreSsh()throws Exception{
        var first=plans.preview(base.actor,request("fixture-target-secret"));var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals(mapper.writeValueAsString(first),mapper.writeValueAsString(plans.preview(base.actor,request("fixture-target-secret"))));
        verify(base.sources,times(1)).discover(any(),anyLong(),any());verify(base.preflights,times(2)).capture(any(),anyLong(),anyMap());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(ControlFailure.class,()->plans.preview(base.actor,request("other-secret"))).code);
        verify(base.sources,times(1)).discover(any(),anyLong(),any());
    }
    @Test void acceptanceRequiresTheOriginalActorAllScopesAndAnUnchangedAuthority(){
        plans.preview(base.actor,request("fixture-target-secret"));
        assertEquals("BOOTSTRAP_ORIGINAL_ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->plans.accept("999",base.spec.id(),ControlBootstrapJournal.PERMISSIONS)).code);
        clearInvocations(base.preflights,base.sources);
        assertEquals("BOOTSTRAP_PERMISSION_APPROVAL_REQUIRED",assertThrows(ControlFailure.class,()->plans.accept(base.actor,base.spec.id(),Set.of())).code);
        verifyNoInteractions(base.preflights,base.sources);
        base.db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(base.actor));
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->plans.accept(base.actor,base.spec.id(),ControlBootstrapJournal.PERMISSIONS)).code);
        assertFalse(Files.exists(root.resolve("state/controller-job-"+base.spec.id()+".json")));
    }
    @Test void mixedSourceProbeVersionIsRefusedBeforeHostPreflightOrPlanWrite(){
        var other=new ControlBootstrapSources.Request("f".repeat(64),"f".repeat(64),base.sourceRequest.unitSha256(),base.sourceRequest.expected());
        when(base.sources.discover(any(),anyLong(),any())).thenReturn(new ControlBootstrapSources.Discovered(other,base.sourceCaptured));
        assertEquals("BOOTSTRAP_SOURCE_CONTROLLER_UPGRADE_REQUIRED",assertThrows(ControlFailure.class,()->plans.preview(base.actor,request("fixture-target-secret"))).code);
        verifyNoInteractions(base.preflights);assertFalse(Files.exists(root.resolve("state/controller-plan-"+base.spec.id()+".json")));
    }
    @Test void revocationDuringInitialHostInventoryCannotWriteAPreview(){
        doAnswer(inv->{base.db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(base.actor));
            return new ControlBootstrapPreflights.Captured(base.targetId,1,base.ssh.routeBinding(base.store.server(base.targetId)),inv.getArgument(2),Map.of("proof","e".repeat(64)));})
                .when(base.preflights).capture(eq(base.targetId),eq(1L),anyMap());
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->plans.preview(base.actor,request("fixture-target-secret"))).code);
        assertFalse(Files.exists(root.resolve("state/controller-plan-"+base.spec.id()+".json")));
    }
}
