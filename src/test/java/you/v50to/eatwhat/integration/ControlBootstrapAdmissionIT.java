package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.control.*;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import you.v50to.eatwhat.support.BootstrapArtifactFixture;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real migrated PG, restricted role, real lease/state/user locks, private
 * registry, catalog policy and journal. Remote inventory/capture/phase receipts
 * are synthetic mocks: this is not a native SSH installer or JOIN acceptance. */
class ControlBootstrapAdmissionIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("bootstrap-admission");
    static final JsonMapper JSON=JsonMapper.builder().build();
    @BeforeAll static void start(){PG.start();}
    @AfterAll static void stop(){PG.stop();}
    @TempDir Path root;
    EventFoundationTestSupport.Database db;SiteProperties site;ControlProperties settings;ClusterProperties cluster;
    ControlServerStore store;ControlAudit audit;ControlServers servers;ControlSsh ssh;DeploymentExecutor deployment;ClusterControlLease lease;
    ControlBootstrapArtifacts artifacts;ControlBootstrapPreflights preflights;ControlBootstrapSources sources;ControlBootstrapAdmission admission;
    ControlBootstrapJournal journal;ControlBootstrapPhaseClaims claims;
    UUID sourceId,targetId;String actor,fingerprint;ControlBootstrapJournal.Spec spec;ControlBootstrapSources.Request sourceRequest;
    ControlBootstrapPreflights.Captured sourceInitial,targetInitial;ControlBootstrapSources.Captured sourceCaptured;Map<String,Object> review;
    ControlBootstrapArtifacts.Frozen acceptedArtifacts;boolean managedPreview;
    @BeforeEach void setup()throws Exception{setup(null);}
    void setup(String storedMemberFingerprint)throws Exception{
        root=root.toRealPath();settings=new ControlProperties();settings.setEnabled(true);settings.setNodeEnabled(true);settings.setEnvironmentKind("managed-deployment");settings.getControllerBootstrap().setEnabled(true);
        Path catalog=Files.createDirectory(root.resolve("catalog"));BootstrapArtifactFixture.configure(settings,catalog);
        site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId("campus");site.setInstanceId(UUID.randomUUID());site.setClusterId(UUID.randomUUID());site.setAllocationSlot(0);
        db=EventFoundationTestSupport.createDatabase(PG,site);String role="\""+db.runtimeUsername()+"\"";
        db.admin().execute("GRANT USAGE ON SCHEMA eatwhat_cluster,eatwhat_control TO "+role);
        db.admin().execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA eatwhat_cluster TO "+role);
        // Match ClusterSchemaConfiguration: immutable reservations never
        // grant UPDATE/DELETE to the deployed runtime role.
        db.admin().execute("REVOKE UPDATE,DELETE ON eatwhat_cluster.installation_allocation FROM "+role);
        db.admin().execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA eatwhat_cluster TO "+role);
        db.admin().execute("GRANT SELECT ON eatwhat_control.credential_version TO "+role);
        fingerprint=db.admin().queryForObject("SELECT md5(cluster_identifier||':'||database_oid::text) FROM eatwhat_identity.node_state",String.class);
        db.admin().update("INSERT INTO eatwhat_cluster.state(cluster_id,coordinator_site,coordinator_instance,epoch) VALUES(?,'campus',?,1)",site.getClusterId(),site.getInstanceId());
        db.admin().update("INSERT INTO eatwhat_cluster.member(site_id,instance_id,profile_id,mode,allocation_slot,state,database_fingerprint,joined_epoch) VALUES('campus',?,'campus','CAMPUS',0,'ACTIVE',?,1)",site.getInstanceId(),storedMemberFingerprint==null?fingerprint:storedMemberFingerprint);
        db.admin().update("INSERT INTO eatwhat_cluster.local_state(cluster_id,epoch,site_id,instance_id,state,members) VALUES(?,1,'campus',?,'ACTIVE',?::jsonb)",site.getClusterId(),site.getInstanceId(),JSON.writeValueAsString(List.of(new ClusterMember(site.getId(),site.getInstanceId(),"CAMPUS",0))));
        actor=db.admin().queryForObject("INSERT INTO users(nick_name,password_hash,role) VALUES('bootstrapadmin','synthetic-private-admin-hash','admin') RETURNING id",Long.class).toString();
        audit=new ControlAudit(root.resolve("state"));settings.setStateDirectory(audit.directory());store=new ControlServerStore(audit);deployment=new DeploymentExecutor(settings,audit);
        cluster=new ClusterProperties();cluster.setEnabled(true);cluster.setCoordinator(true);cluster.setSingleSiteOnly(false);cluster.getControlLease().setEnabled(true);
        lease=new ClusterControlLease(db.runtime(),site.getClusterId(),120);new ClusterControlHandoff(db.runtime(),db.transactions(),lease,deployment,"fixture-primary");assertTrue(lease.tick());
        var beans=new StaticListableBeanFactory();beans.addBean("cluster",cluster);beans.addBean("lease",lease);
        servers=new ControlServers(store,new ControlSsh(store,settings),deployment,audit,site,beans.getBeanProvider(ClusterProperties.class),beans.getBeanProvider(ClusterControlLease.class));
        ssh=new ControlSsh(store,settings);sourceId=UUID.randomUUID();targetId=UUID.randomUUID();
        store.mutate(data->{var list=(Map<String,Object>)data.get("servers");list.put(sourceId.toString(),server(sourceId,"127.0.0.1",true));list.put(targetId.toString(),server(targetId,"target.example.invalid",false));return null;});
        artifacts=new ControlBootstrapArtifacts(settings,store);acceptedArtifacts=artifacts.freeze("fixture-business");
        spec=new ControlBootstrapJournal.Spec(UUID.randomUUID(),sourceId,1,targetId,1,site.getClusterId(),1,acceptedArtifacts.controller().sha256(),"fixture-business",acceptedArtifacts.business().sha256(),1);
        var expected=new ControllerSourceSnapshot.Expected(site.getClusterId(),site.getId(),site.getInstanceId(),fingerprint,deployment.stateIdentity(),1,"fixture-source",
                "a".repeat(64),"b".repeat(64),ControllerSourceSnapshot.sha(Files.readAllBytes(audit.directory().resolve("servers.key"))));
        sourceRequest=new ControlBootstrapSources.Request(acceptedArtifacts.controller().sha256(),acceptedArtifacts.controller().sha256(),"c".repeat(64),expected);
        sourceInitial=initial("SOURCE");targetInitial=initial("TARGET");
        sourceCaptured=new ControlBootstrapSources.Captured(sourceId,1,ssh.routeBinding(store.server(sourceId)),Map.of("captureBinding","d".repeat(64),
                "fileHashes",Map.of("application.properties",expected.applicationSha256(),"cluster-profiles.json",expected.profilesSha256()),"proof",Map.of("generation",1L,"epoch",1L)),
                Map.of("properties",Map.of("spring.datasource.password","synthetic-private-source-secret","app.control.enabled","true","app.control.node-enabled","true","app.control.controller-bootstrap.enabled","true"),"privateFiles",Map.of()));
        preflights=mock(ControlBootstrapPreflights.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));sources=mock(ControlBootstrapSources.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(sources.capture(eq(sourceId),eq(1L),eq(sourceRequest))).thenReturn(sourceCaptured);
        when(sources.observeExecuting(eq(sourceId),eq(1L),eq(sourceRequest))).thenReturn(sourceCaptured);
        admission=new ControlBootstrapAdmission(settings,site,cluster,db.runtime(),db.transactions(),lease,deployment,store,servers,ssh,artifacts,preflights,sources);
        journal=new ControlBootstrapJournal(store,audit,deployment,servers);claims=new ControlBootstrapPhaseClaims(journal,admission);
        review=new LinkedHashMap<>(Map.of("sourceAddress","10.253.74.1","targetAddress","10.253.74.3","targetEndpoint","fixture.example.invalid","wireguardPort",51820,"httpPort",18090,"appBaseUrl","https://fixture.example.invalid/api"));
    }
    String key(){byte[] name="ssh-ed25519".getBytes(java.nio.charset.StandardCharsets.US_ASCII);return "ssh-ed25519 "+Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(4+name.length+4+32).putInt(name.length).put(name).putInt(32).put(new byte[32]).array());}
    Map<String,Object> server(UUID id,String host,boolean source){return new LinkedHashMap<>(Map.of("id",id.toString(),"host",host,"port",22,"username","ubuntu","privilege","SUDO","authentication","PASSWORD",
            "credential",store.encrypt("synthetic-private-ssh-password"),"hostKey",key(),"revision",1,"installation",Map.of("state",source?"SUCCEEDED":"NOT_INSTALLED")));}
    ControlBootstrapPreflights.Captured initial(String role){
        var phase=role.equals("SOURCE")?ControlBootstrapJournal.Phase.SOURCE_RUNTIME:ControlBootstrapJournal.Phase.TARGET_RUNTIME;
        var request=new LinkedHashMap<String,Object>();request.put("id",ControlBootstrapJournal.phaseId(spec.id(),phase).toString());request.put("role",role);
        request.put("sourceServerId",sourceId.toString());request.put("targetServerId",targetId.toString());request.put("sourceAddress","10.253.74.1");request.put("targetAddress","10.253.74.3");request.put("targetEndpoint","fixture.example.invalid");request.put("wireguardPort",51820);
        request.put("sharedStateId",sourceRequest.expected().stateId().toString());request.put("controllerArtifactSha256",spec.controllerSha256());request.put("businessAllocationSlot",1);
        UUID id=role.equals("SOURCE")?sourceId:targetId;return new ControlBootstrapPreflights.Captured(id,1,ssh.routeBinding(store.server(id)),request,Map.of("proof","e".repeat(64)));
    }
    ControlBootstrapAdmission.Frozen freeze(){return admission.freeze(actor,spec,review,sourceInitial,targetInitial,sourceRequest,sourceCaptured,acceptedArtifacts);}
    ControlBootstrapAdmission.Frozen accepted(){
        var f=freeze();var inputs=new LinkedHashMap<String,Object>();inputs.put("admission",f);
        if(managedPreview)inputs.put("executionProtocol","eatwhat-controller-bootstrap-managed-v1");
        admission.beforeEffects(f,()->journal.preview(actor,spec,review,inputs,()->{}));
        admission.beforeEffects(f,()->journal.accept(actor,spec.id(),ControlBootstrapJournal.PERMISSIONS,()->{}));return f;
    }
    ControlBootstrapJournal.Dispatch sourceDone(ControlBootstrapAdmission.Frozen f){
        var claim=claims.claim(f,ControlBootstrapJournal.Phase.SOURCE_RUNTIME);assertTrue(claim.dispatchAllowed());
        journal.observe(spec.id(),ControlBootstrapJournal.Phase.SOURCE_RUNTIME,claim.claimToken(),Map.of("protocol","eatwhat-controller-bootstrap-runtime-v1","id",claim.id().toString(),"state","RUNTIME_READY"));return claim;
    }
    @AfterEach void close(){if(deployment!=null)deployment.close();}
    @Test void sourceDiscoveryAnchorComesFromActualAuthorityAndPrivateSharedState()throws Exception{
        assertTrue(lease.tick());var authority=admission.sourceAuthority(actor);
        assertEquals(site.getClusterId(),authority.clusterId());assertEquals(site.getInstanceId(),authority.authorityInstanceId());assertEquals(fingerprint,authority.authorityDatabaseFingerprint());
        assertEquals(1,authority.epoch());assertEquals(deployment.stateIdentity(),authority.stateId());assertEquals(sourceRequest.expected().sharedKeySha256(),authority.sharedKeySha256());
        verifyNoInteractions(sources,preflights);assertFalse(Files.exists(store.directory().resolve("controller-job-"+spec.id()+".json")));
    }
    @Test void revokedAdminAndExpiredProcessCannotSupplyDiscoveryAuthority(){
        assertTrue(lease.tick());db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(actor));
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->admission.sourceAuthority(actor)).code);
        db.admin().update("UPDATE users SET banned=false WHERE id=?",Long.parseLong(actor));
        db.admin().update("UPDATE eatwhat_cluster.control_process SET expires_at=clock_timestamp()-interval '1 second'");
        assertEquals("CONTROL_LEASE_NOT_HELD",assertThrows(ControlFailure.class,()->admission.sourceAuthority(actor)).code);verifyNoInteractions(sources,preflights);
    }
    @Test void copiedAuthorityFingerprintCannotSupplyDiscoveryAnchor()throws Exception{
        // Inconsistent copied seed, not a forbidden mutation of retained rows.
        close();root=Files.createDirectory(root.resolve("foreign-authority"));setup("f".repeat(32));assertTrue(lease.tick());
        assertEquals("BOOTSTRAP_SOURCE_IDENTITY_CHANGED",assertThrows(ControlFailure.class,()->admission.sourceAuthority(actor)).code);verifyNoInteractions(sources,preflights);
    }
    @Test void actualAdminLeaseIdentityRegistryAndCatalogProduceEncryptedDurableIntent()throws Exception{
        var f=accepted();assertEquals("BootstrapAdmission[redacted]",f.toString());
        assertEquals("synthetic-private-source-secret",((Map<?,?>)admission.privateSourcePayload(f).get("properties")).get("spring.datasource.password"));
        String encoded=JSON.writeValueAsString(f);assertFalse(encoded.contains("synthetic-private-source-secret"));assertFalse(encoded.contains("synthetic-private-admin-hash"));assertFalse(encoded.contains("synthetic-private-ssh-password"));
        var claim=claims.claim(f,ControlBootstrapJournal.Phase.SOURCE_RUNTIME);assertTrue(claim.dispatchAllowed());
        assertEquals("OUTCOME_UNKNOWN",journal.job(spec.id()).get("state"));assertFalse(Files.readString(audit.directory().resolve("controller-job-"+spec.id()+".json")).contains("synthetic-private-source-secret"));
        verify(preflights,atLeastOnce()).requireInitialUnchanged(sourceInitial);verify(preflights,atLeastOnce()).requireInitialUnchanged(targetInitial);
    }
    @Test void anUnstagedSourceVersionCannotBecomeAnApprovedJob(){
        sourceCaptured=new ControlBootstrapSources.Captured(sourceCaptured.serverId(),sourceCaptured.revision(),sourceCaptured.routeBinding(),sourceCaptured.evidence(),
                Map.of("privateFiles",Map.of(),"properties",Map.of("app.control.releases[retained-old].artifact","/source/old.jar",
                        "app.control.releases[retained-old].sha256","9".repeat(64),"app.control.releases[retained-old].acceptance-evidence-id","old-acceptance")));
        assertEquals("BOOTSTRAP_SOURCE_RELEASE_CATALOG_CHANGED",assertThrows(ControlFailure.class,this::freeze).code);
        assertFalse(Files.exists(audit.directory().resolve("controller-job-"+spec.id()+".json")));
    }
    @Test void disabledSourceReceiverCannotBecomeAnApprovedJob(){
        var properties=new LinkedHashMap<>((Map<String,String>)sourceCaptured.privatePayload().get("properties"));properties.put("app.control.controller-bootstrap.enabled","false");
        sourceCaptured=new ControlBootstrapSources.Captured(sourceCaptured.serverId(),sourceCaptured.revision(),sourceCaptured.routeBinding(),sourceCaptured.evidence(),Map.of("properties",properties,"privateFiles",Map.of()));
        assertEquals("BOOTSTRAP_SOURCE_PROFILE_RECEIVER_REQUIRED",assertThrows(ControlFailure.class,this::freeze).code);
        assertFalse(Files.exists(audit.directory().resolve("controller-job-"+spec.id()+".json")));
    }
    @Test @SuppressWarnings("unchecked") void targetPlanningCannotReadChangedRegisteredCredentialsAtTheOldRevision(){
        var f=freeze();Map<UUID,Map<String,Object>> rows=org.springframework.test.util.ReflectionTestUtils.invokeMethod(admission,"registeredServers",f);
        assertEquals(Set.of(sourceId,targetId),rows.keySet());
        store.mutate(data->{((Map<String,Object>)((Map<String,Object>)data.get("servers")).get(sourceId.toString())).put("credential",store.encrypt("changed-at-same-revision"));return null;});
        var failure=assertThrows(ControlFailure.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(admission,"registeredServers",f));
        assertEquals("SERVER_REVISION_CHANGED",failure.code);
    }
    void controllerVerified(ControlBootstrapAdmission.Frozen f){controllerVerified(f,Map.of());}
    void controllerVerified(ControlBootstrapAdmission.Frozen f,Map<String,Object> database){
        controllerClaimed(f);var stage=claims.claim(f,ControlBootstrapJournal.Phase.CONTROLLER_CONFIG);
        journal.observe(spec.id(),ControlBootstrapJournal.Phase.CONTROLLER_CONFIG,stage.claimToken(),Map.of("id",stage.id().toString(),"protocol","eatwhat-controller-bootstrap-controller-v1","state","STAGED"));
        assertTrue(lease.tick());var verified=claims.claim(f,ControlBootstrapJournal.Phase.CONTROLLER_VERIFY);
        var receipt=new LinkedHashMap<String,Object>(Map.of("id",verified.id().toString(),"protocol","eatwhat-controller-bootstrap-controller-v1","state","VERIFIED","controllerHealthProven",true));
        if(!database.isEmpty())receipt.put("database",database);journal.observe(spec.id(),ControlBootstrapJournal.Phase.CONTROLLER_VERIFY,verified.claimToken(),receipt);
    }
    ControlBootstrapBusinessAllocation allocations(){
        var clusterStore=new ClusterStore(db.runtime(),db.transactions(),site.getClusterId(),new ClusterMember(site.getId(),site.getInstanceId(),"CAMPUS",0).identity(),lease);
        var provider=new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("membership",clusterStore)).getBeanProvider(ClusterStore.class);
        return new ControlBootstrapBusinessAllocation(admission,journal,provider);
    }
    @Test void compositeBusinessIdentityUsesTheNormalReservationWithoutRegisteringOrJoining(){
        var f=accepted();controllerVerified(f);var allocator=allocations();var first=allocator.reserve(f);assertEquals(first,allocator.reserve(f));
        var saved=db.runtime().queryForMap("SELECT * FROM eatwhat_cluster.installation_allocation WHERE server_id=?",targetId);
        assertEquals(first.instanceId(),saved.get("instance_id"));assertEquals(first.siteId(),saved.get("site_id"));assertEquals("EXTERNAL",saved.get("mode"));
        assertEquals(ControlBootstrapJournal.phaseId(spec.id(),ControlBootstrapJournal.Phase.BUSINESS_INSTALL),saved.get("first_job_id"));
        assertEquals(1,db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.member",Integer.class));
        assertEquals(1,db.runtime().queryForObject("SELECT epoch FROM eatwhat_cluster.state WHERE singleton",Integer.class));
        var wrongStore=new ClusterStore(db.runtime(),db.transactions(),UUID.randomUUID(),new ClusterMember(site.getId(),site.getInstanceId(),"CAMPUS",0).identity(),lease);
        var wrongProvider=new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("membership",wrongStore)).getBeanProvider(ClusterStore.class);
        assertEquals("INSTALLATION_CLUSTER_CONFIGURATION_REQUIRED",assertThrows(ControlFailure.class,()->new ControlBootstrapBusinessAllocation(admission,journal,wrongProvider).reserve(f)).code);
    }
    @Test void aClaimedBusinessPhaseCannotCreateAMissingAllocationAfterTheFact(){
        var f=accepted();controllerVerified(f);assertTrue(claims.claim(f,ControlBootstrapJournal.Phase.BUSINESS_INSTALL).dispatchAllowed());
        assertEquals("BOOTSTRAP_BUSINESS_ALLOCATION_MISSING",assertThrows(ControlFailure.class,()->allocations().reserve(f)).code);
        assertEquals(0,db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.installation_allocation WHERE server_id=?",Integer.class,targetId));
    }
    @Test void actualUserAndLeaseAndStateLocksEncloseForcedFileWrite(){
        var f=accepted();var phase=ControlBootstrapJournal.Phase.SOURCE_RUNTIME;
        var claim=journal.claimFenced(spec.id(),phase,intent->admission.beforeEffects(f,()->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            for(String table:List.of("eatwhat_cluster.control_lease","eatwhat_cluster.state"))
                assertThrows(DataAccessException.class,()->db.admin().queryForList("SELECT singleton FROM "+table+" WHERE singleton FOR UPDATE NOWAIT"));
            assertThrows(DataAccessException.class,()->db.admin().queryForList("SELECT id FROM users WHERE id=? FOR UPDATE NOWAIT",Long.parseLong(actor)));
            intent.run();assertEquals("OUTCOME_UNKNOWN",journal.job(spec.id()).get("state"));return null;
        }));assertTrue(claim.dispatchAllowed());
    }
    void controllerClaimed(ControlBootstrapAdmission.Frozen f){
        for(var phase:List.of(ControlBootstrapJournal.Phase.SOURCE_RUNTIME,ControlBootstrapJournal.Phase.TARGET_RUNTIME,
                ControlBootstrapJournal.Phase.SOURCE_PRIVATE_PEER,ControlBootstrapJournal.Phase.TARGET_PRIVATE_PEER,
                ControlBootstrapJournal.Phase.SOURCE_SHARED_STATE,ControlBootstrapJournal.Phase.TARGET_SHARED_STATE)){
            assertTrue(lease.tick()); // Real heartbeat/renewal; do not forge timestamps or relax expiry.
            var claim=claims.claim(f,phase);
            String protocol=switch(phase){case SOURCE_RUNTIME,TARGET_RUNTIME->"eatwhat-controller-bootstrap-runtime-v1";case SOURCE_PRIVATE_PEER,TARGET_PRIVATE_PEER->"eatwhat-controller-bootstrap-link-v1";default->"eatwhat-controller-bootstrap-state-v1";};
            String state=switch(phase){case SOURCE_RUNTIME,TARGET_RUNTIME->"RUNTIME_READY";case SOURCE_PRIVATE_PEER,TARGET_PRIVATE_PEER->"CONFIGURED";case SOURCE_SHARED_STATE->"EXPORTED";default->"MOUNTED";};
            journal.observe(spec.id(),phase,claim.claimToken(),Map.of("protocol",protocol,"id",claim.id().toString(),"state",state));
        }
        for(var op:ControlBootstrapJournal.DependencyOperation.values()){
            assertTrue(lease.tick());
            var claim=claims.dependency(f,op,"a".repeat(64),"b".repeat(64));
            journal.observeDependencyOperation(spec.id(),op,claim.claimToken(),Map.of("protocol","eatwhat-controller-bootstrap-dependency-v1","id",claim.nativeId().toString(),
                    "role",op==ControlBootstrapJournal.DependencyOperation.SOURCE?"SOURCE":"TARGET","operation",op.name(),"binding",claim.nativeBinding(),
                    "state",switch(op){case KEY->"KEY_READY";case SOURCE->"AUTHORIZED";case CONNECT->"VERIFIED";}));
        }assertTrue(lease.tick());claims.claim(f,ControlBootstrapJournal.Phase.CONTROLLER_CONFIG);
    }
    @Test void controllerChildIntentRetainsActualUserStateAndLeaseLocksUntilForcedWrite(){
        var f=accepted();controllerClaimed(f);var writes=new AtomicInteger();
        Runnable write=()->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            for(String table:List.of("eatwhat_cluster.control_lease","eatwhat_cluster.state"))
                assertThrows(DataAccessException.class,()->db.admin().queryForList("SELECT singleton FROM "+table+" WHERE singleton FOR UPDATE NOWAIT"));
            assertThrows(DataAccessException.class,()->db.admin().queryForList("SELECT id FROM users WHERE id=? FOR UPDATE NOWAIT",Long.parseLong(actor)));
            store.saveJson(store.directory().resolve("controller-child-intent-fixture.json"),Map.of("forcedChildIntent",true));writes.incrementAndGet();
        };
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(claims,"controllerIntent",f,write);
        assertEquals(1,writes.get());assertEquals(true,store.readJson(store.directory().resolve("controller-child-intent-fixture.json")).get("forcedChildIntent"));
    }
    @Test void revokedActualAdministratorBlocksLaterControllerChunksAndInvalidatesApproval(){
        var f=accepted();controllerClaimed(f);db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(actor));
        Runnable write=()->fail("No child or chunk intent may be written after administrator revocation");
        var failure=assertThrows(ControlFailure.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(claims,"controllerIntent",f,write));
        assertEquals("ADMIN_REQUIRED",failure.code);assertEquals(true,journal.job(spec.id()).get("requiresReapproval"));
        assertFalse(claims.claim(f,ControlBootstrapJournal.Phase.CONTROLLER_CONFIG).dispatchAllowed());
    }
    @Test void businessChildIntentKeepsActualAuthorityLocksThroughItsForcedWrite(){
        var f=accepted();controllerVerified(f);allocations().reserve(f);assertTrue(lease.tick());claims.claim(f,ControlBootstrapJournal.Phase.BUSINESS_INSTALL);
        var writes=new AtomicInteger();Runnable write=()->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            for(String table:List.of("eatwhat_cluster.control_lease","eatwhat_cluster.state"))
                assertThrows(DataAccessException.class,()->db.admin().queryForList("SELECT singleton FROM "+table+" WHERE singleton FOR UPDATE NOWAIT"));
            assertThrows(DataAccessException.class,()->db.admin().queryForList("SELECT id FROM users WHERE id=? FOR UPDATE NOWAIT",Long.parseLong(actor)));
            store.saveJson(store.directory().resolve("business-child-intent-fixture.json"),Map.of("forcedChildIntent",true));writes.incrementAndGet();
        };
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(claims,"businessIntent",f,write);assertEquals(1,writes.get());
        assertEquals(true,store.readJson(store.directory().resolve("business-child-intent-fixture.json")).get("forcedChildIntent"));
    }
    @Test void revokedAdministratorBlocksBusinessPreparationOrInstallationButRetainsObservation(){
        var f=accepted();controllerVerified(f);allocations().reserve(f);assertTrue(lease.tick());claims.claim(f,ControlBootstrapJournal.Phase.BUSINESS_INSTALL);
        db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(actor));
        Runnable write=()->fail("No new preparation or installation after revocation");
        var failure=assertThrows(ControlFailure.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(claims,"businessIntent",f,write));
        assertEquals("ADMIN_REQUIRED",failure.code);assertEquals(true,journal.job(spec.id()).get("requiresReapproval"));
        assertFalse(claims.claim(f,ControlBootstrapJournal.Phase.BUSINESS_INSTALL).dispatchAllowed());
    }
    void memberConfigurationClaimed(ControlBootstrapAdmission.Frozen f){memberConfigurationClaimed(f,Map.of());}
    void memberConfigurationClaimed(ControlBootstrapAdmission.Frozen f,Map<String,Object> database){
        controllerVerified(f,database);allocations().reserve(f);assertTrue(lease.tick());var installed=claims.claim(f,ControlBootstrapJournal.Phase.BUSINESS_INSTALL);
        // Explicit remote-success fixture, not evidence of a real installation.
        journal.observe(spec.id(),ControlBootstrapJournal.Phase.BUSINESS_INSTALL,installed.claimToken(),Map.of("protocol","eatwhat-install-v1","jobId",installed.id().toString(),"state","SUCCEEDED"));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(claims,"memberConfiguration",f);
    }
    @Test void memberRelayChildIntentKeepsActualAuthorityLocksThroughItsForcedWrite(){
        var f=accepted();memberConfigurationClaimed(f);var writes=new AtomicInteger();
        Runnable write=()->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            for(String table:List.of("eatwhat_cluster.control_lease","eatwhat_cluster.state"))
                assertThrows(DataAccessException.class,()->db.admin().queryForList("SELECT singleton FROM "+table+" WHERE singleton FOR UPDATE NOWAIT"));
            assertThrows(DataAccessException.class,()->db.admin().queryForList("SELECT id FROM users WHERE id=? FOR UPDATE NOWAIT",Long.parseLong(actor)));
            store.saveJson(store.directory().resolve("member-relay-child-intent-fixture.json"),Map.of("forcedChildIntent",true));writes.incrementAndGet();
        };
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(claims,"memberConfigurationIntent",f,write);assertEquals(1,writes.get());
        assertEquals(true,store.readJson(store.directory().resolve("member-relay-child-intent-fixture.json")).get("forcedChildIntent"));
    }
    @Test void revokedAdministratorBlocksNewMemberRelayChildIntent(){
        var f=accepted();memberConfigurationClaimed(f);db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(actor));
        Runnable write=()->fail("No new relay effects after revocation");
        var failure=assertThrows(ControlFailure.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(claims,"memberConfigurationIntent",f,write));
        assertEquals("ADMIN_REQUIRED",failure.code);assertEquals(true,journal.job(spec.id()).get("requiresReapproval"));
    }
    @Test void revokedAdministratorInvalidatesOnlyFutureOperationsButOriginalIsObservationOnly(){
        var f=accepted();sourceDone(f);db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(actor));
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->claims.claim(f,ControlBootstrapJournal.Phase.TARGET_RUNTIME)).code);
        assertEquals(true,journal.job(spec.id()).get("requiresReapproval"));assertFalse(claims.claim(f,ControlBootstrapJournal.Phase.SOURCE_RUNTIME).dispatchAllowed());
    }
    @Test void registryCredentialDriftWithoutRevisionCannotBypassFrozenConnection(){
        var f=accepted();sourceDone(f);store.mutate(data->{((Map<String,Object>)((Map<?,?>)data.get("servers")).get(targetId.toString())).put("credential",store.encrypt("different-private-credential"));return null;});
        assertEquals("SERVER_REVISION_CHANGED",assertThrows(ControlFailure.class,()->claims.claim(f,ControlBootstrapJournal.Phase.TARGET_RUNTIME)).code);
        assertEquals(true,journal.job(spec.id()).get("requiresReapproval"));
    }
    @Test void sameProcessNewLeaseGenerationCannotResumeOldApproval(){
        var f=accepted();sourceDone(f);db.admin().update("UPDATE eatwhat_cluster.control_lease SET expires_at=clock_timestamp()-interval '1 second'");assertTrue(lease.tick());
        assertEquals("BOOTSTRAP_PLAN_CHANGED",assertThrows(ControlFailure.class,()->claims.claim(f,ControlBootstrapJournal.Phase.TARGET_RUNTIME)).code);
        assertEquals(true,journal.job(spec.id()).get("requiresReapproval"));
    }
    @Test void epochDriftAndSameEpochForeignRegistrationAreDetected(){
        var f=accepted();sourceDone(f);var m=new ClusterStore(db.runtime(),db.transactions(),site.getClusterId(),new ClusterMember(site.getId(),site.getInstanceId(),"CAMPUS",0).identity(),lease);
        m.register(actor,UUID.randomUUID(),1,"foreign",new ClusterMember("foreign",UUID.randomUUID(),"EXTERNAL",2));
        assertTrue(lease.tick()); // Keep the real process witness live while checking roster drift.
        assertEquals("BOOTSTRAP_PLAN_CHANGED",assertThrows(ControlFailure.class,()->claims.claim(f,ControlBootstrapJournal.Phase.TARGET_RUNTIME)).code);
        assertEquals(true,journal.job(spec.id()).get("requiresReapproval"));
    }
    @Test void staleEpochCannotAuthorizeAnyNewPhase(){
        var f=accepted();sourceDone(f);clearInvocations(sources);db.admin().update("UPDATE eatwhat_cluster.state SET epoch=2");
        doThrow(new ControlFailure(502,"BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE")).when(sources).observeExecuting(eq(sourceId),eq(1L),eq(sourceRequest));
        assertEquals("STALE_MEMBERSHIP_EPOCH",assertThrows(ControlFailure.class,()->claims.claim(f,ControlBootstrapJournal.Phase.TARGET_RUNTIME)).code);
        assertEquals(true,journal.job(spec.id()).get("requiresReapproval"));
        verify(sources,never()).observeExecuting(any(),anyLong(),any());
    }
    @Test void changedControllerBytesInvalidateApprovalWithoutAnyNewDispatch()throws Exception{
        var f=accepted();sourceDone(f);Files.writeString(settings.getControllerBootstrap().getControllerArtifact(),"different public artifact");
        assertEquals("BOOTSTRAP_ARTIFACT_CHANGED",assertThrows(ControlFailure.class,()->claims.claim(f,ControlBootstrapJournal.Phase.TARGET_RUNTIME)).code);
        assertFalse(claims.claim(f,ControlBootstrapJournal.Phase.SOURCE_RUNTIME).dispatchAllowed());
    }
    @Test void reservedIdentityAndSingleSiteModeCannotMasqueradeAsEmptyFullCluster(){
        var m=new ClusterStore(db.runtime(),db.transactions(),site.getClusterId(),new ClusterMember(site.getId(),site.getInstanceId(),"CAMPUS",0).identity(),lease);
        m.reserveInstallation(actor,UUID.randomUUID(),targetId,new ClusterMember("retained",UUID.randomUUID(),"EXTERNAL",1));
        assertEquals("INSTALLATION_ALLOCATION_RETAINED",assertThrows(ControlFailure.class,this::freeze).code);
        cluster.setSingleSiteOnly(true);assertEquals("BOOTSTRAP_MANAGED_AUTHORITY_REQUIRED",assertThrows(ControlFailure.class,this::freeze).code);
    }
    @Test void sourceConfigurationDriftPersistsReapprovalButNetworkFailureDoesNot(){
        var f=accepted();sourceDone(f);
        doThrow(new ControlFailure(502,"BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE")).when(sources).observeExecuting(eq(sourceId),eq(1L),eq(sourceRequest));
        assertEquals("BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE",assertThrows(ControlFailure.class,()->claims.claim(f,ControlBootstrapJournal.Phase.TARGET_RUNTIME)).code);
        assertEquals(false,journal.job(spec.id()).get("requiresReapproval"));
        doThrow(new ControlFailure(409,"BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED")).when(sources).observeExecuting(eq(sourceId),eq(1L),eq(sourceRequest));
        assertEquals("BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED",assertThrows(ControlFailure.class,()->claims.claim(f,ControlBootstrapJournal.Phase.TARGET_RUNTIME)).code);
        assertEquals(true,journal.job(spec.id()).get("requiresReapproval"));
    }
    @Test void anotherValidSnapshotCannotReplaceTheOneInAnAcceptedJob(){
        var original=accepted();var another=freeze();assertNotEquals(original.binding(),another.binding());
        assertEquals("BOOTSTRAP_PLAN_CHANGED",assertThrows(ControlFailure.class,()->claims.claim(another,ControlBootstrapJournal.Phase.SOURCE_RUNTIME)).code);
        assertEquals("QUEUED",journal.job(spec.id()).get("state"));assertTrue(claims.claim(original,ControlBootstrapJournal.Phase.SOURCE_RUNTIME).dispatchAllowed());
    }
    @Test void publicReviewMustMatchNativeNetworkAndAvoidControllerDependencyPorts(){
        review.put("targetAddress","10.253.74.9");assertEquals("BOOTSTRAP_INVALID_BINDING",assertThrows(ControlFailure.class,this::freeze).code);
        review.put("targetAddress","10.253.74.3");
        for(int port:List.of(25432,16379,28090)){
            review.put("httpPort",port);assertEquals("BOOTSTRAP_INVALID_PORT",assertThrows(ControlFailure.class,this::freeze).code);
        }
    }
}
