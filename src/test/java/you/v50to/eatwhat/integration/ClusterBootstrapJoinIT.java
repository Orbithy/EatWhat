package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.control.*;
import java.nio.file.*;
import java.net.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real independent authority/target databases and node agents. The empty
 * data-plane fixture is explicit: this does not prove HTTP, replay or Linux
 * installation. No fabricated coordinator votes or local membership rows. */
class ClusterBootstrapJoinIT extends ClusterMembershipFixture {
    @TempDir Path directory;
    EventFoundationTestSupport.Database target;
    ClusterControlLease primary,source;
    ClusterStore sourceStore;
    DeploymentExecutor primaryExecutor,sourceExecutor;
    ScheduledExecutorService heartbeat;
    final AtomicReference<Throwable> heartbeatFailure=new AtomicReference<>();
    ClusterProfiles profiles;
    ClusterProperties properties;
    ClusterNodeAgent campusAgent,targetAgent;
    AgentNodes nodes;
    ClusterMembershipCoordinator coordinator;
    UUID stateId;
    long generation;
    final AtomicBoolean approved=new AtomicBoolean(true);
    final List<String> checked=new ArrayList<>(),written=new ArrayList<>(),remote=new ArrayList<>();
    final AtomicReference<String> failAfter=new AtomicReference<>();
    final AtomicReference<String> revokeAfter=new AtomicReference<>();
    final AtomicReference<String> nextFingerprint=new AtomicReference<>();
    UUID joinId;

    @BeforeEach void initializeRealAgents()throws Exception{
        target=database(external,true);grantNode(target);
        primary=new ClusterControlLease(db.runtime(),cluster,120);source=new ClusterControlLease(db.runtime(),cluster,120);
        store=new ClusterStore(db.runtime(),db.transactions(),cluster,campus.identity(),primary);
        sourceStore=new ClusterStore(db.runtime(),db.transactions(),cluster,campus.identity(),source);
        var audit=new ControlAudit(directory.resolve("state"));
        primaryExecutor=new DeploymentExecutor(new ControlProperties(),audit);sourceExecutor=new DeploymentExecutor(new ControlProperties(),audit);
        stateId=primaryExecutor.stateIdentity();new ClusterControlHandoff(db.runtime(),db.transactions(),primary,primaryExecutor,"primary");new ClusterControlHandoff(db.runtime(),db.transactions(),source,sourceExecutor,"physical-source");
        properties=new ClusterProperties();addProfile(campus,31001);addProfile(external,31002);profiles=new ClusterProfiles(properties);
        campusAgent=agent(db,campus);targetAgent=agent(target,external);nodes=new AgentNodes();
        assertTrue(primary.tick());assertFalse(source.tick());generation=((Number)primary.status().get("generation")).longValue();
        heartbeat=Executors.newSingleThreadScheduledExecutor();heartbeat.scheduleWithFixedDelay(()->{try{primary.tick();}catch(Throwable failure){heartbeatFailure.compareAndSet(null,failure);}},500,500,TimeUnit.MILLISECONDS);
        // Bootstrap the initial authority through the same genuine protocol.
        store.register("admin",UUID.randomUUID(),0,campus.siteId(),campus);
        var initial=new ClusterMembershipCoordinator(cluster,store,profiles,nodes);UUID bootstrap=UUID.randomUUID();initial.begin("admin",bootstrap,0,"JOIN",campus.siteId());assertEquals("APPLIED",initial.reconcile("admin",bootstrap).get("state"));
        store.register("admin",UUID.randomUUID(),1,external.siteId(),external);
        coordinator=new ClusterMembershipCoordinator(cluster,sourceStore,profiles,nodes);joinId=UUID.randomUUID();checked.clear();written.clear();remote.clear();
    }
    @AfterEach void closeExecutors()throws Exception{if(heartbeat!=null){heartbeat.shutdownNow();assertTrue(heartbeat.awaitTermination(5,TimeUnit.SECONDS));}if(primaryExecutor!=null)primaryExecutor.close();if(sourceExecutor!=null)sourceExecutor.close();assertNull(heartbeatFailure.get(),"The fixture's real control-process heartbeat must stay live");}
    void grantNode(EventFoundationTestSupport.Database node){String role="\""+node.runtimeUsername()+"\"";node.admin().execute("GRANT USAGE ON SCHEMA eatwhat_cluster,eatwhat_file TO "+role);node.admin().execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA eatwhat_cluster,eatwhat_file TO "+role);node.admin().execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA eatwhat_cluster,eatwhat_file TO "+role);}
    void addProfile(ClusterMember member,int port)throws Exception{var p=new ClusterProperties.Profile();p.setSiteId(member.siteId());p.setInstanceId(member.instanceId());p.setMode(member.mode());p.setAllocationSlot(member.allocationSlot());p.setOrigin("http://127.0.0.1:"+port);Path token=directory.resolve(member.siteId()+".token");Files.writeString(token,"x".repeat(64));Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));p.setTokenFile(token);properties.getProfiles().put(member.siteId(),p);}
    ClusterNodeAgent agent(EventFoundationTestSupport.Database node,ClusterMember member){return new ClusterNodeAgent(node.runtime(),node.transactions(),cluster,member,new ClusterNodeAgent.DataPlane(){
        public boolean ready(){return true;}
        public void verifyCatchup(ClusterMembershipCoordinator.Plan p,ClusterMember local){assertEquals(0,node.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox",Integer.class),"Only empty replication is part of this fixture");}
    });}
    String actualFingerprint(){return targetAgent.probe().get("databaseFingerprint").toString();}
    ClusterMembershipCoordinator.Boundary boundary(){return new ClusterMembershipCoordinator.Boundary(){
        public void check(String operation){source.writeUnderApprovedHolder(db.transactions(),primary.controllerId(),generation,stateId,()->{guard(operation);return null;});}
        public void write(String operation,Runnable effect){source.writeUnderApprovedHolder(db.transactions(),primary.controllerId(),generation,stateId,()->{guard(operation);written.add(operation);effect.run();return null;});}
        void guard(String operation){assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.hasResource(db.dataSource()));if(!approved.get())throw new ControlFailure(409,"ADMIN_REQUIRED");checked.add(operation);}
    };}
    void begin(){coordinator.beginJoinExpected("admin",joinId,1,external,actualFingerprint(),boundary());}
    Map<String,Object> reconcile(){return coordinator.reconcileJoinExpected("admin",joinId,1,external,actualFingerprint(),boundary());}
    void assertCompleted(){
        assertEquals("APPLIED",sourceStore.change(joinId).get("state"));assertEquals("ACTIVE",sourceStore.member(external.siteId()).get("state"));assertEquals(2,((Number)sourceStore.status().get("epoch")).intValue());
        for(var node:List.of(db,target)){var row=node.runtime().queryForMap("SELECT epoch,state,pending_change,members::text AS members FROM eatwhat_cluster.local_state WHERE singleton");assertEquals(2,((Number)row.get("epoch")).intValue());assertEquals("ACTIVE",row.get("state"));assertNull(row.get("pending_change"));assertTrue(row.get("members").toString().contains(external.instanceId().toString()));assertEquals(3,node.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.local_phase WHERE change_id=? AND phase IN ('PREPARED','APPLIED','RELEASED')",Integer.class,joinId));}
        assertEquals(6,db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.change_vote WHERE change_id=?",Integer.class,joinId));
        assertEquals(1,db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.command WHERE request_id=?",Integer.class,joinId));
        assertEquals(1,db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.audit WHERE request_id=? AND action='JOIN_PREPARE'",Integer.class,joinId));
        assertFalse((Boolean)source.status().get("localLeader"));assertTrue((Boolean)primary.status().get("localLeader"));
        assertThrows(ClusterFailure.class,()->sourceStore.register("admin",UUID.randomUUID(),2,"outside-scope",third));
    }
    @Test void standbySourceCompletesRealTwoDatabaseJoinWithShortFencesAndExactOriginalCommand(){
        begin();assertEquals("JOINING",sourceStore.member(external.siteId()).get("state"));assertEquals(1,((Number)sourceStore.status().get("epoch")).intValue());
        assertEquals("APPLIED",reconcile().get("state"));assertCompleted();
        for(String phase:List.of("BEGIN","DECIDE","FINISH","RELEASED","VOTE_PREPARED:campus","VOTE_PREPARED:external","VOTE_APPLIED:campus","VOTE_APPLIED:external","VOTE_RELEASED:campus","VOTE_RELEASED:external"))assertTrue(written.contains(phase),phase);
        int mutations=written.size(),calls=remote.size();begin();reconcile();assertEquals(mutations,written.size());assertEquals(calls,remote.size());
    }
    @Test void lostStageAcknowledgementResumesOriginalChangeWithoutAnotherBegin(){
        begin();failAfter.set("stage:external");assertThrows(IllegalStateException.class,this::reconcile);assertEquals("PREPARING",sourceStore.change(joinId).get("state"));
        coordinator=new ClusterMembershipCoordinator(cluster,sourceStore,profiles,new AgentNodes());assertEquals("APPLIED",reconcile().get("state"));assertCompleted();assertEquals(1,written.stream().filter("BEGIN"::equals).count());
    }
    @Test void lostApplyAcknowledgementResumesAfterAuthorityEpochHasCommitted(){
        begin();failAfter.set("apply:campus");assertThrows(IllegalStateException.class,this::reconcile);assertEquals("COMMITTED",sourceStore.change(joinId).get("state"));
        assertEquals(2,db.runtime().queryForObject("SELECT epoch FROM eatwhat_cluster.local_state WHERE singleton",Integer.class));assertEquals(1,target.runtime().queryForObject("SELECT epoch FROM eatwhat_cluster.local_state WHERE singleton",Integer.class));
        assertEquals("APPLIED",reconcile().get("state"));assertCompleted();assertEquals(1,written.stream().filter("BEGIN"::equals).count());
    }
    @Test void lostReleaseAcknowledgementKeepsBarrierUntilEveryOriginalParticipantConfirms(){
        begin();failAfter.set("release:campus");assertThrows(IllegalStateException.class,this::reconcile);assertEquals("INSTALLED",sourceStore.change(joinId).get("state"));
        assertNull(db.runtime().queryForObject("SELECT pending_change FROM eatwhat_cluster.local_state WHERE singleton",UUID.class));assertEquals(joinId,target.runtime().queryForObject("SELECT pending_change FROM eatwhat_cluster.local_state WHERE singleton",UUID.class));
        assertEquals("APPLIED",reconcile().get("state"));assertCompleted();
    }
    @Test void approvalRevokedAfterRemotePrepareCannotPersistVoteOrCommit(){
        begin();revokeAfter.set("prepared:campus");assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,this::reconcile).code);
        assertEquals("PREPARING",sourceStore.change(joinId).get("state"));assertEquals(0,db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.change_vote WHERE change_id=?",Integer.class,joinId));assertEquals(1,((Number)sourceStore.status().get("epoch")).intValue());
        assertThrows(ControlFailure.class,this::reconcile);assertEquals(1,written.stream().filter("BEGIN"::equals).count());
    }
    @Test void changedFingerprintAndCatalogIdentityCannotBecomeNewApproval(){
        String expected=actualFingerprint();nextFingerprint.set("e".repeat(32));assertEquals("DATABASE_IDENTITY_CHANGED",assertThrows(ClusterFailure.class,()->coordinator.beginJoinExpected("admin",joinId,1,external,expected,boundary())).getMessage());
        assertTrue(sourceStore.joinedCommand("admin",joinId,1,external,expected).isEmpty());assertTrue(written.isEmpty());nextFingerprint.set(null);
        properties.getProfiles().get(external.siteId()).setInstanceId(UUID.randomUUID());profiles.reload(List.of());int calls=remote.size();assertEquals("MEMBER_PROFILE_IDENTITY_CHANGED",assertThrows(ClusterFailure.class,this::begin).getMessage());assertEquals(calls,remote.size());
    }
    @Test void participantCatalogDriftRejectsBeforeAnyReconcileIo(){
        begin();properties.getProfiles().get(campus.siteId()).setInstanceId(UUID.randomUUID());profiles.reload(List.of());int calls=remote.size();assertEquals("MEMBER_PROFILE_IDENTITY_CHANGED",assertThrows(ClusterFailure.class,this::reconcile).getMessage());assertEquals(calls,remote.size());
    }
    @Test void retainedJoinRequiresOriginalActorFingerprintEpochIdentityAndImmutableEvidence(){
        begin();String fp=actualFingerprint();assertTrue(sourceStore.joinedCommand("admin",joinId,1,external,fp).isPresent());
        assertEquals("JOIN_PROOF_CHANGED",assertThrows(ClusterFailure.class,()->sourceStore.joinedCommand("another-admin",joinId,1,external,fp)).getMessage());
        assertThrows(ClusterFailure.class,()->sourceStore.joinedCommand("admin",joinId,2,external,fp));assertThrows(ClusterFailure.class,()->sourceStore.joinedCommand("admin",joinId,1,external,"f".repeat(32)));
        assertThrows(ClusterFailure.class,()->sourceStore.joinedCommand("admin",joinId,1,new ClusterMember(external.siteId(),UUID.randomUUID(),"EXTERNAL",1),fp));
        assertThrows(DataAccessException.class,()->db.admin().update("UPDATE eatwhat_cluster.command SET result_json='{}'::jsonb WHERE request_id=?",joinId));
        assertThrows(DataAccessException.class,()->db.admin().update("UPDATE eatwhat_cluster.change SET participants='[]'::jsonb WHERE change_id=?",joinId));
        assertEquals("JOIN_COMMAND_REQUIRED",assertThrows(ClusterFailure.class,()->coordinator.reconcileJoinExpected("admin",UUID.randomUUID(),1,external,fp,boundary())).getMessage());
    }
    @Test void authorityTransactionsCannotLeakIntoRemoteIoAndWrongDataSourceCannotFence(){
        assertThrows(IllegalArgumentException.class,()->primary.write(target.transactions(),()->true));
        assertThrows(IllegalStateException.class,()->new org.springframework.transaction.support.TransactionTemplate(target.transactionManager()).execute(status->{primary.requireHeldInTransaction();return true;}));
        var ordinary=new ClusterMembershipCoordinator.Boundary(){public void check(String op){}public void write(String op,Runnable effect){effect.run();}};
        int calls=remote.size();assertEquals("MEMBERSHIP_REMOTE_TRANSACTION_FORBIDDEN",assertThrows(ClusterFailure.class,()->db.transactions().required(()->coordinator.beginJoinExpected("admin",joinId,1,external,actualFingerprint(),ordinary))).getMessage());assertEquals(calls,remote.size());
        var enclosing=new ClusterMembershipCoordinator.Boundary(){public void check(String op){}public void write(String op,Runnable effect){effect.run();}public void remote(String op,Runnable effect){db.transactions().required(()->{effect.run();return null;});}};
        assertEquals("MEMBERSHIP_REMOTE_TRANSACTION_FORBIDDEN",assertThrows(ClusterFailure.class,()->coordinator.beginJoinExpected("admin",joinId,1,external,actualFingerprint(),enclosing)).getMessage());assertEquals(calls,remote.size());
    }
    @Test void changedLeaseGenerationStopsTheOriginalJoinBeforeAnotherRemoteEffect(){
        begin();int calls=remote.size();db.admin().update("UPDATE eatwhat_cluster.control_lease SET generation=generation+1 WHERE singleton");
        assertEquals("CONTROL_LEASE_NOT_HELD",assertThrows(ClusterFailure.class,this::reconcile).getMessage());assertEquals(calls,remote.size());assertEquals("PREPARING",sourceStore.change(joinId).get("state"));
    }
    @Test void standbyUsesAuthenticatedHttpAndRealBaselineProgressOnlyWithinOriginalProtocolScope()throws Exception{
        var calls=new AtomicInteger();var errors=new AtomicReference<Throwable>();
        var campusHttp=nodeHttp(campusAgent,calls,errors);var targetHttp=nodeHttp(targetAgent,calls,errors);
        try{
            properties.getProfiles().get(campus.siteId()).setOrigin("http://127.0.0.1:"+campusHttp.getAddress().getPort());properties.getProfiles().get(external.siteId()).setOrigin("http://127.0.0.1:"+targetHttp.getAddress().getPort());profiles.reload(List.of());
            var handle=new AtomicReference<ClusterHttpNodes>();var baseline=ClusterTestEmptyBaseline.synchronizer(db.runtime(),sourceStore);
            var http=new ClusterHttpNodes(cluster,(plan,peers)->{
                // A remote protocol scope must not become general SQL authority
                // or leak into a different worker thread.
                assertEquals("CONTROL_LEASE_NOT_HELD",assertThrows(ClusterFailure.class,()->sourceStore.register("admin",UUID.randomUUID(),1,"outside-scope",third)).getMessage());
                try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
                    var outside=pool.submit(()->{handle.get().stage(peers.getFirst(),plan);return true;});
                    var failure=assertThrows(ExecutionException.class,()->outside.get(5,TimeUnit.SECONDS));assertInstanceOf(ClusterFailure.class,failure.getCause());assertEquals("CONTROL_LEASE_NOT_HELD",failure.getCause().getMessage());
                }
                baseline.synchronize(plan,peers);
            },sourceStore);handle.set(http);
            coordinator=new ClusterMembershipCoordinator(cluster,sourceStore,profiles,http);begin();
            assertEquals("APPLIED",reconcile().get("state"));assertCompleted();assertNull(errors.get());assertEquals(10,calls.get());
            assertTrue(checked.contains("PROTOCOL_PEER"));assertTrue(written.contains("PROTOCOL_PROGRESS"));
            assertEquals(true,db.runtime().queryForObject("SELECT account_complete FROM eatwhat_cluster.replay_cursor WHERE change_id=? AND source_site=?",Boolean.class,joinId,campus.siteId()));
            int originalCalls=calls.get();assertEquals("CONTROL_LEASE_NOT_HELD",assertThrows(ClusterFailure.class,()->http.stage(profiles.get(external.siteId()),coordinator.plan(joinId))).getMessage());assertEquals(originalCalls,calls.get());
        }finally{campusHttp.stop(0);targetHttp.stop(0);}
    }
    @Test void deferredWriteCallbackCannotApplyAJoinAfterItsBoundaryReturned(){
        var retained=new AtomicReference<Runnable>();var deferred=new ClusterMembershipCoordinator.Boundary(){public void check(String op){}public void write(String op,Runnable effect){retained.set(effect);}};
        assertEquals("MEMBERSHIP_BOUNDARY_INVALID",assertThrows(ClusterFailure.class,()->coordinator.beginJoinExpected("admin",joinId,1,external,actualFingerprint(),deferred)).getMessage());
        assertNotNull(retained.get());assertEquals("MEMBERSHIP_BOUNDARY_INVALID",assertThrows(ClusterFailure.class,()->retained.get().run()).getMessage());assertTrue(sourceStore.joinedCommand("admin",joinId,1,external,actualFingerprint()).isEmpty());assertEquals("REGISTERED",sourceStore.member(external.siteId()).get("state"));
    }
    HttpServer nodeHttp(ClusterNodeAgent agent,AtomicInteger calls,AtomicReference<Throwable> errors)throws Exception{
        var json=JsonMapper.builder().build();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/cluster/v1/",exchange->{
            try{
                assertEquals("x".repeat(64),exchange.getRequestHeaders().getFirst("X-Control-Node-Token"));String operation=exchange.getRequestURI().getPath().substring("/internal/cluster/v1/".length());
                Map<String,Object> result=new LinkedHashMap<>(agent.probe());
                if(!operation.equals("probe")){
                    assertEquals("POST",exchange.getRequestMethod());var plan=json.readValue(exchange.getRequestBody().readNBytes(65537),ClusterMembershipCoordinator.Plan.class);
                    String proof=switch(operation){case "stage"->agent.stage(plan);case "fence"->agent.fence(plan);case "prepared"->agent.prepared(plan);case "apply"->agent.apply(plan);case "release"->agent.release(plan);default->throw new AssertionError("Unexpected fixture RPC");};
                    calls.incrementAndGet();result.put("changeId",plan.changeId());result.put("planHash",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(plan))));result.put("proofHash",proof);
                }
                byte[] body=json.writeValueAsBytes(result);exchange.sendResponseHeaders(200,body.length);try(var output=exchange.getResponseBody()){output.write(body);}
            }catch(Throwable failure){errors.compareAndSet(null,failure);exchange.sendResponseHeaders(503,-1);exchange.close();}
        });server.start();return server;
    }
    final class AgentNodes implements ClusterMembershipCoordinator.Nodes {
        ClusterNodeAgent node(ClusterProfiles.Entry p){assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Remote node work cannot inherit authority locks");return p.member().equals(campus)?campusAgent:targetAgent;}
        void after(String op){remote.add(op);String revoke=revokeAfter.get(),fail=failAfter.get();if(op.equals(revoke)&&revokeAfter.compareAndSet(revoke,null))approved.set(false);if(op.equals(fail)&&failAfter.compareAndSet(fail,null))throw new IllegalStateException("synthetic lost acknowledgement");}
        public String fingerprint(ClusterProfiles.Entry p){String fp=node(p).probe().get("databaseFingerprint").toString();after("fingerprint:"+p.id());return nextFingerprint.get()==null?fp:nextFingerprint.get();}
        public void stage(ClusterProfiles.Entry p,ClusterMembershipCoordinator.Plan plan){node(p).stage(plan);after("stage:"+p.id());}
        public void fence(ClusterProfiles.Entry p,ClusterMembershipCoordinator.Plan plan){node(p).fence(plan);after("fence:"+p.id());}
        public void synchronize(ClusterMembershipCoordinator.Plan plan,List<ClusterProfiles.Entry> peers){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());after("synchronize");}
        public String prepared(ClusterProfiles.Entry p,ClusterMembershipCoordinator.Plan plan){String proof=node(p).prepared(plan);after("prepared:"+p.id());return proof;}
        public String apply(ClusterProfiles.Entry p,ClusterMembershipCoordinator.Plan plan){String proof=node(p).apply(plan);after("apply:"+p.id());return proof;}
        public String release(ClusterProfiles.Entry p,ClusterMembershipCoordinator.Plan plan){String proof=node(p).release(plan);after("release:"+p.id());return proof;}
    }
}
