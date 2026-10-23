package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.control.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Real authority/standby SQL registration and durable encrypted workflow.
 * Native install/runtime and TARGET publication are explicit fixtures; SSH is
 * replaced by actual authenticated SOURCE node-controller calls. No JOIN. */
class ControlBootstrapMembershipIT {
    @TempDir static Path pki;@TempDir Path root;
    ControlBootstrapProfileApplyIT fixture;ControlBootstrapAdmissionIT base;
    @BeforeAll static void start()throws Exception{ControlBootstrapProfileApplyIT.pki=pki;ControlBootstrapProfileApplyIT.start();}
    @AfterAll static void stop(){ControlBootstrapProfileApplyIT.stop();}
    @BeforeEach void setup()throws Exception{fixture=new ControlBootstrapProfileApplyIT();fixture.root=root;fixture.setup();base=fixture.base;}
    @AfterEach void close(){fixture.close();}
    class Case implements AutoCloseable {
        final ControlBootstrapProfileApplyIT.Case profile;
        final ClusterMember member;final ClusterStore store;final ClusterMembershipCoordinator coordinator;
        final ControlBootstrapMembership manager,source;final ControlBootstrapMemberRuntime runtime;final ControlNode node;
        final ControlBootstrapMembershipNodeController controller;final Map<String,Object> ready;
        final List<String> sent=new ArrayList<>();java.util.function.Consumer<String> before=action->{};java.util.function.Consumer<String> phaseAfter=phase->{};boolean loseAfter,loseJoinAfter;
        java.util.function.Function<Map<String,Object>,Map<String,Object>> joinedObservation=proof->null;
        EventFoundationTestSupport.Database target;java.util.concurrent.ScheduledExecutorService heartbeat;
        Case()throws Exception{this(false);}
        Case(boolean realJoin)throws Exception{
            profile=fixture.new Case(ControlBootstrapProfileState.Role.SOURCE,true,true);profile.observe(profile.apply());
            assertTrue(profile.state.claim(profile.frozen,ControlBootstrapProfileState.Role.TARGET).dispatchAllowed());
            // Explicit original TARGET publication success fixture. SOURCE
            // file/cache publication and its standby identity are real here.
            var targetPublication=Map.<String,Object>ofEntries(Map.entry("protocol","eatwhat-controller-profile-publication-v1"),Map.entry("bootstrapId",base.spec.id().toString()),Map.entry("role","TARGET"),Map.entry("binding",profile.original.binding()),
                Map.entry("planBinding",profile.pair.target().binding()),Map.entry("controllerId",profile.original.targetControllerId().toString()),Map.entry("controllerName","control-"+base.targetId.toString().replace("-","").substring(0,24)),
                Map.entry("applicationSha256",profile.original.targetApplicationSha256()),Map.entry("state","RUNTIME_RELOADED"),Map.entry("observedBinding",profile.pair.target().afterBinding()),Map.entry("joinAuthorized",false));
            Map<String,Object> signed=ReflectionTestUtils.invokeMethod(ControlBootstrapProfileState.class,"sign",base.store,targetPublication);profile.state.observe(profile.original,ControlBootstrapProfileState.Role.TARGET,signed);
            member=ControlBootstrapBusinessAllocation.identity(base.spec);
            String targetFingerprint="b".repeat(32);
            if(realJoin){var targetSite=new you.v50to.eatwhat.config.SiteProperties();targetSite.setMode(you.v50to.eatwhat.config.SiteProperties.Mode.EXTERNAL);targetSite.setId(member.siteId());targetSite.setInstanceId(member.instanceId());targetSite.setClusterId(base.site.getClusterId());targetSite.setAllocationSlot(member.allocationSlot());
                target=EventFoundationTestSupport.createDatabase(ControlBootstrapAdmissionIT.PG,targetSite);grantNode(base.db);grantNode(target);targetFingerprint=target.runtime().queryForObject("SELECT md5(cluster_identifier||':'||database_oid::text) FROM eatwhat_identity.node_state WHERE singleton",String.class);
            }
            ready=new LinkedHashMap<>(Map.ofEntries(Map.entry("protocol","eatwhat-member-configure-v1"),Map.entry("id",UUID.nameUUIDFromBytes((base.spec.id()+":MEMBER_RUNTIME").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()),
                Map.entry("state","CONFIGURED"),Map.entry("siteId",member.siteId()),Map.entry("instanceId",member.instanceId().toString()),Map.entry("clusterId",base.site.getClusterId().toString()),Map.entry("allocationSlot",member.allocationSlot()),
                Map.entry("artifactSha256",base.spec.businessSha256()),Map.entry("databaseFingerprint",targetFingerprint),Map.entry("configurationRevalidated",true),Map.entry("authStoreVerified",true),Map.entry("dataPreserved",true),Map.entry("clusterMembership","NOT_JOINED"),Map.entry("joinAuthorized",false)));
            runtime=mock(ControlBootstrapMemberRuntime.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).defaultAnswer(inv->switch(inv.getMethod().getName()){case "ready"->Map.copyOf(ready);case "joined","inspectJoined"->joinedObservation.apply(inv.getArgument(1));default->RETURNS_DEFAULTS.answer(inv);}));
            store=new ClusterStore(base.db.runtime(),base.db.transactions(),base.site.getClusterId(),new you.v50to.eatwhat.sync.transport.NodeIdentity(base.site.getId(),base.site.getInstanceId()),profile.sourceStandby);
            var nodes=realJoin?realNodes():mock(ClusterMembershipCoordinator.Nodes.class);var actualCoordinator=new ClusterMembershipCoordinator(base.site.getClusterId(),store,profile.catalog,nodes);
            coordinator=mock(ClusterMembershipCoordinator.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(actualCoordinator).defaultAnswer(CALLS_REAL_METHODS));
            var beans=new StaticListableBeanFactory(Map.of("store",store,"coordinator",coordinator));var empty=new StaticListableBeanFactory();
            source=new ControlBootstrapMembership(base.store,base.journal,profile.localAdmission,profile.state,runtime,beans.getBeanProvider(ClusterStore.class),beans.getBeanProvider(ClusterMembershipCoordinator.class),profile.nativePhases,profile.receiver);
            // The caller has no usable local coordinator/catalog for this new
            // peer. It must delegate through the original SOURCE route.
            manager=new ControlBootstrapMembership(base.store,base.journal,base.admission,profile.state,runtime,beans.getBeanProvider(ClusterStore.class),empty.getBeanProvider(ClusterMembershipCoordinator.class),profile.nativePhases,profile.receiver);
            var nodeSettings=new ControlProperties();nodeSettings.setEnvironmentKind("managed-deployment");nodeSettings.setNodeTokenFile(Path.of(profile.original.bridge().sourceTokenFile()));
            node=new ControlNode(nodeSettings,base.db.runtime(),base.site,new ControlAudit(base.store.directory()));controller=new ControlBootstrapMembershipNodeController(node,source);
            profile.bridgeCall=inv->{
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(base.lease.tick());assertFalse(profile.sourceStandby.tick());assertFalse(profile.standby.tick());
                String agent=inv.getArgument(1);List<String> args=inv.getArgument(2);Map<String,Object> payload=inv.getArgument(3);String action=args.getFirst();sent.add(action);
                assertEquals(profile.original.bridge().agent(),agent);assertEquals(base.sourceRequest.sourceArtifactSha256(),payload.get("artifactSha256"));assertEquals("SOURCE",payload.get("role"));
                before.accept(action);var frame=Map.<String,Object>of("id",payload.get("id"),"role",payload.get("role"),"binding",payload.get("binding"));
                Object result=switch(action){case "register"->controller.register("t".repeat(43),frame);case "register-status"->controller.status("t".repeat(43),frame);case "join"->controller.join("t".repeat(43),frame);case "join-status"->controller.joinStatus("t".repeat(43),frame);case "join-reconcile"->controller.reconcile("t".repeat(43),frame);default->throw new AssertionError("Unexpected fixed bridge operation");};
                if(loseAfter&&action.equals("register")||loseJoinAfter&&action.equals("join"))throw new ControlFailure(503,"BOOTSTRAP_NATIVE_OBSERVATION_UNAVAILABLE");return result;
            };
            if(realJoin){heartbeat=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();heartbeat.scheduleWithFixedDelay(()->{try{base.lease.tick();profile.sourceStandby.tick();profile.standby.tick();}catch(RuntimeException ignored){}},0,500,java.util.concurrent.TimeUnit.MILLISECONDS);}
        }
        void grantNode(EventFoundationTestSupport.Database db){String role="\""+db.runtimeUsername()+"\"";db.admin().execute("GRANT USAGE ON SCHEMA eatwhat_cluster,eatwhat_file TO "+role);db.admin().execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA eatwhat_cluster,eatwhat_file TO "+role);db.admin().execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA eatwhat_cluster,eatwhat_file TO "+role);}
        ClusterMembershipCoordinator.Nodes realNodes(){var campus=new ClusterMember(base.site.getId(),base.site.getInstanceId(),"CAMPUS",0);
            var data=new ClusterNodeAgent.DataPlane(){public boolean ready(){return true;}public void verifyCatchup(ClusterMembershipCoordinator.Plan plan,ClusterMember local){}};
            var authority=new ClusterNodeAgent(base.db.runtime(),base.db.transactions(),base.site.getClusterId(),campus,data);var candidate=new ClusterNodeAgent(target.runtime(),target.transactions(),base.site.getClusterId(),member,data);
            return new ClusterMembershipCoordinator.Nodes(){
                ClusterNodeAgent node(ClusterProfiles.Entry entry){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return entry.member().equals(campus)?authority:candidate;}
                public String fingerprint(ClusterProfiles.Entry entry){return node(entry).probe().get("databaseFingerprint").toString();}
                public void stage(ClusterProfiles.Entry entry,ClusterMembershipCoordinator.Plan plan){node(entry).stage(plan);phaseAfter.accept("STAGE:"+entry.member().siteId());}
                public void synchronize(ClusterMembershipCoordinator.Plan plan,List<ClusterProfiles.Entry> entries){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());}
                public void fence(ClusterProfiles.Entry entry,ClusterMembershipCoordinator.Plan plan){node(entry).fence(plan);phaseAfter.accept("FENCE:"+entry.member().siteId());}
                public String prepared(ClusterProfiles.Entry entry,ClusterMembershipCoordinator.Plan plan){String result=node(entry).prepared(plan);phaseAfter.accept("PREPARED:"+entry.member().siteId());return result;}
                public String apply(ClusterProfiles.Entry entry,ClusterMembershipCoordinator.Plan plan){String result=node(entry).apply(plan);phaseAfter.accept("APPLY:"+entry.member().siteId());return result;}
                public String release(ClusterProfiles.Entry entry,ClusterMembershipCoordinator.Plan plan){String result=node(entry).release(plan);phaseAfter.accept("RELEASE:"+entry.member().siteId());return result;}
            };
        }
        void configure(){assertTrue(base.lease.tick());assertFalse(profile.sourceStandby.tick());manager.configure(profile.frozen);}
        Map<String,Object> register(){assertTrue(base.lease.tick());assertFalse(profile.sourceStandby.tick());return manager.register(profile.frozen);}
        long commands(){return base.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.command WHERE request_id=?",Long.class,manager.original(base.spec.id()).registerId());}
        long members(){return base.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.member",Long.class);}
        public void close(){if(heartbeat!=null){heartbeat.shutdownNow();try{assertTrue(heartbeat.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}node.close();}
    }
    @Test void configurationCompletesThenActualStandbySourceRegistersTheOriginalMember()throws Exception{try(var c=new Case()){
        c.configure();assertEquals("MEMBER_JOIN",base.journal.job(base.spec.id()).get("stage"));assertEquals(1,c.members());
        var result=c.register();assertEquals("SUCCEEDED",result.get("registration"));assertEquals(true,result.get("joinPending"));assertEquals(false,result.get("joinAuthorized"));
        assertEquals(2,c.members());assertEquals(1,c.commands());var row=c.store.member(c.member.siteId());assertEquals("REGISTERED",row.get("state"));assertEquals(c.member.instanceId(),row.get("instance_id"));assertEquals(c.member.siteId(),row.get("profile_id"));
        assertNull(row.get("joined_epoch"));assertEquals(1L,c.store.status().get("epoch"));assertTrue(base.lease.status().get("localLeader").equals(true));assertFalse((Boolean)c.profile.sourceStandby.status().get("localLeader"));
        assertThrows(ClusterFailure.class,c.profile.sourceStandby::requireHeld);assertThrows(ClusterFailure.class,()->base.db.transactions().required(()->{c.profile.sourceStandby.requireHeldInTransaction();return null;}));
    }}
    @Test void lostCommitAcknowledgementOnlyObservesRegistrationAfterAdministratorRevocation()throws Exception{try(var c=new Case()){
        c.configure();c.loseAfter=true;assertThrows(ControlFailure.class,c::register);assertEquals(1,c.commands());assertEquals(2,c.members());
        base.db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(base.actor));
        assertEquals("SUCCEEDED",c.register().get("registration"));assertEquals(List.of("register","register-status"),c.sent);assertEquals(1,c.commands());
        verify(c.coordinator,times(1)).registerExpected(anyString(),any(),anyLong(),anyString(),any());
    }}
    @Test void lostRequestBeforeSourceExecutionDoesNotGrantADispatchRetry()throws Exception{try(var c=new Case()){
        c.configure();c.before=action->{if(action.equals("register"))throw new ControlFailure(503,"BOOTSTRAP_NATIVE_OBSERVATION_UNAVAILABLE");};assertThrows(ControlFailure.class,c::register);
        assertEquals("OUTCOME_UNKNOWN",c.register().get("registration"));assertEquals("OUTCOME_UNKNOWN",c.register().get("registration"));
        assertEquals(List.of("register","register-status","register-status"),c.sent);assertEquals(0,c.commands());assertEquals(1,c.members());verifyNoInteractions(c.coordinator);
    }}
    @Test void sqlRollbackAfterForcedSourceIntentCannotExecuteAgain()throws Exception{try(var c=new Case()){
        c.configure();doAnswer(inv->{inv.callRealMethod();throw new ControlFailure(503,"FIXTURE_LOST_COMMIT");}).when(c.coordinator).registerExpected(anyString(),any(),anyLong(),anyString(),any());
        assertThrows(ControlFailure.class,c::register);assertEquals(0,c.commands());assertEquals(1,c.members());
        assertEquals("OUTCOME_UNKNOWN",c.register().get("registration"));
        var a=c.manager.original(base.spec.id());assertEquals("OUTCOME_UNKNOWN",c.source.localRegistration(a.id(),ControlBootstrapProfileState.Role.SOURCE,a.binding(),true).get("state"));
        verify(c.coordinator,times(1)).registerExpected(anyString(),any(),anyLong(),anyString(),any());assertEquals(0,c.commands());
    }}
    @Test void administratorRevokedAfterCallerClaimIsRejectedAtTheActualSqlEffect()throws Exception{try(var c=new Case()){
        c.configure();c.before=action->{if(action.equals("register"))base.db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(base.actor));};
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,c::register).code);assertEquals(0,c.commands());assertEquals(1,c.members());
        base.db.admin().update("UPDATE users SET banned=false WHERE id=?",Long.parseLong(base.actor));var a=c.manager.original(base.spec.id());
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->c.source.localRegistration(a.id(),ControlBootstrapProfileState.Role.SOURCE,a.binding(),true)).code);
        assertEquals("OUTCOME_UNKNOWN",c.register().get("registration"));assertEquals(0,c.commands());
    }}
    @Test void sourceChecksOriginalLeaseGenerationAtTheActualEffect()throws Exception{try(var c=new Case()){
        c.configure();c.before=action->{if(action.equals("register"))base.db.admin().update("UPDATE eatwhat_cluster.control_lease SET generation=generation+1 WHERE singleton");};
        assertEquals("CONTROL_LEASE_NOT_HELD",assertThrows(ControlFailure.class,c::register).code);assertEquals(0,c.commands());assertEquals(1,c.members());verifyNoInteractions(c.coordinator);
    }}
    @Test void committedInputLossCannotRegenerateFingerprintOrDispatchPermission()throws Exception{try(var c=new Case()){
        c.configure();Files.delete(base.store.directory().resolve("controller-membership-input-"+base.spec.id()+".json"));Files.delete(base.store.directory().resolve("controller-membership-operations-"+base.spec.id()+".json"));
        assertEquals("BOOTSTRAP_MEMBERSHIP_RECORD_MISSING",assertThrows(ControlFailure.class,c::configure).code);assertEquals(1,c.members());assertTrue(c.sent.isEmpty());
    }}
    @Test void changedTargetFingerprintRequiresReapprovalBeforeRegistrationClaim()throws Exception{try(var c=new Case()){
        c.configure();c.ready.put("databaseFingerprint","c".repeat(32));assertEquals("BOOTSTRAP_MEMBER_RUNTIME_NOT_VERIFIED",assertThrows(ControlFailure.class,c::register).code);
        assertEquals(0,c.commands());assertEquals(1,c.members());assertTrue(c.sent.isEmpty());
    }}
    @Test void registrationObservationRequiresTheExactCommandAndActorAudit()throws Exception{try(var c=new Case()){
        c.configure();c.register();var a=c.manager.original(base.spec.id());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->base.db.admin().update("UPDATE eatwhat_cluster.command SET request_hash=? WHERE request_id=?","0".repeat(64),a.registerId()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->base.db.admin().update("UPDATE eatwhat_cluster.audit SET actor='other' WHERE request_id=?",a.registerId()));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(ClusterFailure.class,()->c.store.registeredCommand(base.actor,a.registerId(),base.spec.epoch()+1,c.member.siteId(),c.member)).getMessage());
        assertEquals("REGISTRATION_PROOF_CHANGED",assertThrows(ClusterFailure.class,()->c.store.registeredCommand("other",a.registerId(),base.spec.epoch(),c.member.siteId(),c.member)).getMessage());
        assertEquals("SUCCEEDED",c.manager.observeRegistration(a).get("registration"));assertEquals(2,c.members());
    }}
    @Test void borrowedAuthorityCannotMoveToAnotherThreadOrTransactionResource()throws Exception{try(var c=new Case()){
        c.configure();var f=c.profile.frozen;
        c.profile.sourceStandby.writeUnderApprovedHolder(base.db.transactions(),f.holderId(),f.generation(),f.stateId(),()->{
            c.profile.sourceStandby.requireHeldInTransaction();
            var other=java.util.concurrent.CompletableFuture.supplyAsync(()->assertThrows(ClusterFailure.class,()->base.db.transactions().required(()->{c.profile.sourceStandby.requireHeldInTransaction();return null;})));
            assertNotNull(other.join());
            var transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(base.db.runtime().getDataSource()));transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThrows(ClusterFailure.class,()->transaction.execute(status->{c.profile.sourceStandby.requireHeldInTransaction();return null;}));c.profile.sourceStandby.requireHeldInTransaction();return null;
        });assertThrows(ClusterFailure.class,c.profile.sourceStandby::requireHeld);
        var ds=mock(javax.sql.DataSource.class);var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);var tx=new you.v50to.eatwhat.sync.SyncTransactions(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        assertThrows(IllegalArgumentException.class,()->new ClusterStore(jdbc,tx,base.site.getClusterId(),new you.v50to.eatwhat.sync.transport.NodeIdentity(base.site.getId(),base.site.getInstanceId()),c.profile.sourceStandby));
    }}
}
