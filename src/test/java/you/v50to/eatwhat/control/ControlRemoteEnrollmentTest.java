package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Service state and failure boundaries only; SSH and node/coordinator calls are explicit mocks. */
class ControlRemoteEnrollmentTest {
    @TempDir Path root;
    UUID server=UUID.randomUUID(),clusterId=UUID.randomUUID();ClusterMember campus=new ClusterMember("campus",UUID.randomUUID(),"CAMPUS",0),external=new ClusterMember("server-0123456789ab",UUID.randomUUID(),"EXTERNAL",1);
    ControlRemoteEnrollment remote;ControlServerStore store;ControlProperties settings;ClusterProperties cluster;ControlServers servers;ControlSsh ssh;ClusterStore membership;ClusterMembershipCoordinator coordinator;ClusterHttpNodes nodes;DeploymentExecutor deployment;JdbcTemplate jdbc;Path password;
    Map<String,Object> payload;final String sha="a".repeat(64),fp="b".repeat(32);
    @BeforeEach @SuppressWarnings("unchecked") void prepare()throws Exception{
        root=root.toRealPath();var audit=new ControlAudit(root);store=new ControlServerStore(audit);settings=new ControlProperties();settings.getServers().setClusterEnrollmentEnabled(true);deployment=new DeploymentExecutor(settings,audit);servers=mock(ControlServers.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));ssh=mock(ControlSsh.class);membership=mock(ClusterStore.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));coordinator=mock(ClusterMembershipCoordinator.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));nodes=mock(ClusterHttpNodes.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));jdbc=mock(JdbcTemplate.class);
        cluster=new ClusterProperties();cluster.setEnabled(true);cluster.setCoordinator(true);
        for(var m:List.of(campus,external)){Path token=root.resolve(m.siteId()+".token");write(token,"x".repeat(64));var p=new ClusterProperties.Profile();p.setSiteId(m.siteId());p.setInstanceId(m.instanceId());p.setMode(m.mode());p.setAllocationSlot(m.allocationSlot());p.setOrigin("https://"+m.siteId()+".example.invalid");p.setApiPrefix("/api");p.setTokenFile(token);cluster.getProfiles().put(m.siteId(),p);}
        var profiles=new ClusterProfiles(cluster);var site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId(campus.siteId());site.setInstanceId(campus.instanceId());site.setClusterId(clusterId);site.setAllocationSlot(0);
        password=root.resolve("auth.password");write(password,"synthetic-auth-private");Path inventory=root.resolve("inventory.json");write(inventory,JsonMapper.builder().build().writeValueAsString(Map.of("version",1,"clusterId",clusterId,"servers",Map.of(server.toString(),Map.of("profileId",external.siteId(),"authJdbcOrigin","jdbc:postgresql://127.0.0.1/eatwhat","authUsername","auth_reader","authPasswordFile",password.toString(),"authCaFile","")))));settings.getServers().setMemberConfigurationsFile(inventory);
        var installed=new LinkedHashMap<String,Object>(Map.of("state","SUCCEEDED","mode","EXTERNAL","siteId",external.siteId(),"instanceId",external.instanceId().toString(),"allocationSlot",1,"clusterId",clusterId.toString(),"clusterMembership","NOT_JOINED","currentSha256",sha));
        store.mutate(data->{((Map<String,Object>)data.get("servers")).put(server.toString(),new LinkedHashMap<>(Map.of("id",server.toString(),"revision",2,"username","root","location","REMOTE","installation",installed)));return null;});
        when(jdbc.queryForList(anyString(),eq(123L))).thenReturn(List.of(Map.of("id",123L,"nick_name","fixture-admin","password_hash","synthetic-private-hash")));
        when(membership.status()).thenReturn(Map.of("epoch",1L,"members",List.of(Map.of("site_id",campus.siteId(),"instance_id",campus.instanceId(),"mode","CAMPUS","allocation_slot",0,"state","ACTIVE")),"pendingChanges",List.of()));
        when(membership.installationAllocation(server)).thenReturn(Map.of("cluster_id",clusterId,"site_id",external.siteId(),"instance_id",external.instanceId(),"mode","EXTERNAL","allocation_slot",1));
        when(ssh.call(anyMap(),anyString(),anyList(),any(),anyInt())).thenAnswer(call->{var bytes=new java.io.ByteArrayOutputStream();((ControlSsh.Upload)call.getArgument(3)).write(bytes);payload=JsonMapper.builder().build().readValue(bytes.toByteArray(),Map.class);var reply=new LinkedHashMap<String,Object>(Map.of("protocol","eatwhat-member-configure-v1","id",payload.get("id"),"serverId",payload.get("serverId")));String action=((List<String>)call.getArgument(2)).getFirst();if(action.equals("inspect"))reply.putAll(Map.of("ready",true,"freezeHash","f".repeat(64),"databaseFingerprint",fp));else reply.put("state",action.equals("dispatch")?"ACCEPTED":"NOT_STARTED");return reply;});
        when(nodes.call(any(ClusterProfiles.Entry.class),eq("probe"),isNull())).thenReturn(Map.of("dataPlaneMode","MULTI_SITE","dataPlaneReady",true,"protocolVersion",1));
        var beans=new StaticListableBeanFactory();for(var e:Map.of("membership",membership,"profiles",profiles,"coordinator",coordinator,"cluster",cluster,"nodes",nodes).entrySet())beans.addBean(e.getKey(),e.getValue());
        remote=new ControlRemoteEnrollment(store,ssh,servers,deployment,settings,audit,site,jdbc,beans.getBeanProvider(ClusterStore.class),beans.getBeanProvider(ClusterProfiles.class),beans.getBeanProvider(ClusterMembershipCoordinator.class),beans.getBeanProvider(ClusterProperties.class),beans.getBeanProvider(ClusterHttpNodes.class));
    }
    void write(Path p,String value)throws Exception{Files.writeString(p,value);Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));}
    UUID plan(){var p=remote.create("123",server,2);assertEquals("READY",p.get("state"),p.toString());return UUID.fromString(p.get("id").toString());}
    Map<String,Object> job(UUID id){return ControlInstallExecutor.publicPlan(store.readJson(root.resolve("server-cluster-job-"+id+".json")));}
    @Test void privatePreflightCreatesReviewablePlanWithoutSecrets(){var p=remote.create("123",server,2);assertEquals("READY",p.get("state"));assertEquals("REMOTE_MEMBER",p.get("kind"));assertEquals(false,p.get("singleSiteOnly"));assertFalse(p.toString().contains("synthetic"));assertFalse(p.toString().contains("_payload"));assertEquals("/api",((Map<?,?>)((Map<?,?>)payload.get("runtime")).get("properties")).get("app.cluster.authority-api-prefix"));}
    @Test @SuppressWarnings("unchecked") void nonRootConfigurationRequiresExplicitSudoAndStillRunsActualPreflight()throws Exception{
        store.mutate(data->{var s=(Map<String,Object>)((Map<String,Object>)data.get("servers")).get(server.toString());s.put("username","ubuntu");return null;});
        assertTrue(((List<?>)remote.create("123",server,2).get("blockers")).contains("MEMBER_CONFIGURATION_ROOT_REQUIRED"));verifyNoInteractions(ssh);
        store.mutate(data->{((Map<String,Object>)((Map<String,Object>)data.get("servers")).get(server.toString())).put("privilege","SUDO");return null;});
        assertEquals("READY",remote.create("123",server,2).get("state"));verify(ssh).call(argThat(s->"SUDO".equals(s.get("privilege"))),anyString(),eq(List.of("inspect")),any(),anyInt());
    }
    @Test void singleSiteAuthorityAndMissingInventoryNeverContactSsh(){cluster.setSingleSiteOnly(true);assertTrue(((List<?>)remote.create("123",server,2).get("blockers")).contains("MULTISITE_AUTHORITY_SETUP_REQUIRED"));cluster.setSingleSiteOnly(false);settings.getServers().setMemberConfigurationsFile(null);assertTrue(((List<?>)remote.create("123",server,2).get("blockers")).contains("PRIVATE_MEMBER_CONFIGURATION_REQUIRED"));verifyNoInteractions(ssh);}
    @Test void authorityBusinessMustActuallySupportFullDataPlaneBeforeAnyRemoteMutation()throws Exception{
        for(var observation:List.of(Map.of("dataPlaneMode","SINGLE_SITE","dataPlaneReady",true,"protocolVersion",1),Map.of("dataPlaneMode","MULTI_SITE","dataPlaneReady",false,"protocolVersion",1),Map.of("dataPlaneMode","MULTI_SITE","dataPlaneReady",true,"protocolVersion",2),Map.of())){
            when(nodes.call(any(ClusterProfiles.Entry.class),eq("probe"),isNull())).thenReturn((Map)observation);
            var blocked=remote.create("123",server,2);assertTrue(((List<?>)blocked.get("blockers")).contains("AUTHORITY_DATA_PLANE_NOT_READY"));assertFalse(blocked.toString().contains("synthetic"));
        }
        verifyNoInteractions(ssh,coordinator);
    }
    @Test void authorityFailureOrDriftAfterPlanCannotBeUsedAsOldApproval()throws Exception{
        UUID id=plan();when(nodes.call(any(ClusterProfiles.Entry.class),eq("probe"),isNull())).thenReturn(Map.of("dataPlaneMode","SINGLE_SITE","dataPlaneReady",true,"protocolVersion",1));
        assertEquals("AUTHORITY_DATA_PLANE_NOT_READY",assertThrows(ControlFailure.class,()->remote.execute("123",id)).code);verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());
        when(nodes.call(any(ClusterProfiles.Entry.class),eq("probe"),isNull())).thenThrow(new ClusterFailure(503,"PRIVATE_TLS_DIAGNOSTIC"));
        var blocked=remote.create("123",server,2);assertTrue(((List<?>)blocked.get("blockers")).contains("AUTHORITY_DATA_PLANE_UNAVAILABLE"));assertFalse(blocked.toString().contains("PRIVATE_TLS_DIAGNOSTIC"));
    }
    @Test void changedReservationNeverContactsSsh(){when(membership.installationAllocation(server)).thenReturn(Map.of());assertTrue(((List<?>)remote.create("123",server,2).get("blockers")).contains("MEMBER_ALLOCATION_RESERVATION_REQUIRED"));verifyNoInteractions(ssh);}
    @Test void credentialChangeInvalidatesOldApprovalBeforeDispatch()throws Exception{UUID id=plan();write(password,"changed-private");assertEquals("MEMBER_PLAN_CHANGED",assertThrows(ControlFailure.class,()->remote.execute("123",id)).code);verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());}
    @Test void queuedLookupAndReadonlyReconcileDoNotDispatch()throws Exception{UUID id=plan();remote.execute("123",id);remote.reconcile(id);assertEquals("QUEUED",job(id).get("state"));verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());verifyNoInteractions(coordinator);assertEquals("DEPLOYMENT_RECONCILIATION_REQUIRED",deployment.whenIdle(()->fail("accepted enrollment must block deployment")));}
    @Test void queuedCredentialDriftEndsBeforeDispatchAndReleasesAdmission()throws Exception{
        UUID id=plan();remote.execute("123",id);write(password,"changed-after-acceptance");remote.reconcile(id);assertEquals("QUEUED",job(id).get("state"));
        remote.cycle();var failed=job(id);assertEquals("FAILED",failed.get("state"));assertEquals("MEMBER_PLAN_CHANGED",failed.get("code"));assertEquals(false,failed.get("effectsStarted"));
        verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());verifyNoInteractions(coordinator);
        assertNull(deployment.whenIdle(()->{}));remote.execute("123",id);remote.cycle();assertEquals("FAILED",job(id).get("state"));assertFalse(failed.toString().contains("changed-after"));
    }
    @Test void queuedRevokedAdministratorEndsWithoutRemoteEffects()throws Exception{
        UUID id=plan();remote.execute("123",id);when(jdbc.queryForList(anyString(),eq(123L))).thenReturn(List.of());remote.cycle();
        assertEquals("FAILED",job(id).get("state"));assertEquals("ADMIN_REQUIRED",job(id).get("code"));verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());verifyNoInteractions(coordinator);
    }
    @Test @SuppressWarnings("unchecked") void queuedRevisionChangeNeverDispatches()throws Exception{
        UUID id=plan();remote.execute("123",id);store.mutate(data->{((Map<String,Object>)((Map<?,?>)data.get("servers")).get(server.toString())).put("revision",3);return null;});remote.cycle();
        assertEquals("FAILED",job(id).get("state"));assertEquals("SERVER_REVISION_CHANGED",job(id).get("code"));verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());verifyNoInteractions(coordinator);
    }
    @Test void queuedEpochChangeEndsBeforeDispatch()throws Exception{
        UUID id=plan();remote.execute("123",id);var status=new LinkedHashMap<String,Object>(membership.status());status.put("epoch",2L);when(membership.status()).thenReturn(status);remote.cycle();
        assertEquals("FAILED",job(id).get("state"));assertEquals("STALE_MEMBERSHIP_EPOCH",job(id).get("code"));verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());verifyNoInteractions(coordinator);
    }
    @Test void configurationDriftAfterUncertainDispatchNeverBecomesSafeFailure()throws Exception{
        UUID id=plan();remote.execute("123",id);when(ssh.call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt())).thenThrow(new java.io.IOException("lost acknowledgement"));remote.cycle();write(password,"drift-after-dispatch");remote.cycle();
        assertEquals("OUTCOME_UNKNOWN",job(id).get("state"));assertNotEquals(false,job(id).get("effectsStarted"));verify(ssh,times(1)).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());assertEquals("DEPLOYMENT_RECONCILIATION_REQUIRED",deployment.whenIdle(()->fail("unresolved dispatch must stay fenced")));
    }
    @Test void queuedTemporaryAuthorityOutageStaysQueuedAndStillBlocksDeployment()throws Exception{
        UUID id=plan();remote.execute("123",id);when(nodes.call(any(ClusterProfiles.Entry.class),eq("probe"),isNull())).thenThrow(new ClusterFailure(503,"TRANSIENT_PRIVATE_DETAIL"));remote.cycle();
        assertEquals("QUEUED",job(id).get("state"));assertFalse(job(id).toString().contains("TRANSIENT_PRIVATE_DETAIL"));assertEquals("DEPLOYMENT_RECONCILIATION_REQUIRED",deployment.whenIdle(()->fail("accepted queue must remain fenced")));verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());
    }
    @Test void invalidQueuedPlanCannotBeFinalizedByStandby()throws Exception{
        UUID id=plan();remote.execute("123",id);write(password,"drift");doThrow(new ClusterFailure(409,"CONTROL_LEASE_NOT_HELD")).when(servers).requireExecuting();remote.cycle();
        assertEquals("QUEUED",job(id).get("state"));verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());
    }
    @Test void losingLeadershipDuringValidationCannotFinalizeQueuedPlan()throws Exception{
        UUID id=plan();remote.execute("123",id);write(password,"drift");doNothing().doThrow(new ClusterFailure(409,"CONTROL_LEASE_NOT_HELD")).when(servers).requireExecuting();remote.cycle();
        assertEquals("QUEUED",job(id).get("state"));verify(servers,times(2)).requireExecuting();verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());
    }
    @Test void queuedRejectionSerializesWithTheOriginalDeploymentLock()throws Exception{
        UUID id=plan();remote.execute("123",id);write(password,"drift");
        try(var channel=java.nio.channels.FileChannel.open(root.resolve("deployment.lock"),StandardOpenOption.WRITE);var lock=channel.lock()){
            remote.cycle();assertEquals("QUEUED",job(id).get("state"));
        }
        remote.cycle();assertEquals("FAILED",job(id).get("state"));verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());
    }
    @Test void lostSshAcknowledgmentIsNeverDispatchedTwice()throws Exception{UUID id=plan();remote.execute("123",id);when(ssh.call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt())).thenThrow(new java.io.IOException("private remote failure"));remote.cycle();assertEquals("OUTCOME_UNKNOWN",job(id).get("state"));remote.execute("123",id);remote.cycle();remote.reconcile(id);verify(ssh,times(1)).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());assertFalse(job(id).toString().contains("private remote"));verifyNoInteractions(coordinator);}
    @Test void unrelatedStatusReceiptCannotTriggerJoin()throws Exception{UUID id=plan();remote.execute("123",id);when(ssh.call(anyMap(),anyString(),eq(List.of("status")),any(),anyInt())).thenReturn(Map.of("protocol","eatwhat-member-configure-v1","id",UUID.randomUUID().toString(),"serverId",server.toString(),"state","CONFIGURED"));remote.cycle();assertEquals("OUTCOME_UNKNOWN",job(id).get("state"));verifyNoInteractions(coordinator);}
    @Test void preparedSourceDoesNotBecomeActiveWithoutRealProtocolProof()throws Exception{UUID id=plan();remote.execute("123",id);var receipt=new LinkedHashMap<String,Object>();for(String key:List.of("id","serverId","siteId","instanceId","clusterId","allocationSlot","artifactSha256"))receipt.put(key,payload.get(key));receipt.putAll(Map.of("protocol","eatwhat-member-configure-v1","state","CONFIGURED","databaseFingerprint",fp));when(ssh.call(anyMap(),anyString(),eq(List.of("status")),any(),anyInt())).thenReturn(receipt);when(membership.change(id)).thenReturn(Map.of("state","PREPARING"));when(membership.member(external.siteId())).thenReturn(Map.of("state","JOINING"));remote.cycle();verify(coordinator).register(eq("123"),any(UUID.class),eq(1L),eq(external.siteId()));verify(coordinator).begin("123",id,1L,"JOIN",external.siteId());verify(coordinator).reconcile("123",id);assertEquals("RUNNING",job(id).get("state"));assertEquals("NOT_JOINED",((Map<?,?>)store.server(server).get("installation")).get("clusterMembership"));}
    UUID failedMember()throws Exception{
        UUID id=plan();remote.execute("123",id);
        when(ssh.call(anyMap(),anyString(),eq(List.of("status")),any(),anyInt())).thenReturn(Map.of("protocol","eatwhat-member-configure-v1","id",id.toString(),"serverId",server.toString(),"state","FAILED"));
        remote.cycle();assertEquals("FAILED",job(id).get("state"));reset(ssh);recoveryReplies("RESTORED",false);return id;
    }
    @SuppressWarnings("unchecked") void recoveryReplies(String state,boolean loseDispatch)throws Exception{
        doAnswer(call->{
            var bytes=new java.io.ByteArrayOutputStream();((ControlSsh.Upload)call.getArgument(3)).write(bytes);payload=JsonMapper.builder().build().readValue(bytes.toByteArray(),Map.class);
            var reply=new LinkedHashMap<String,Object>();for(String key:List.of("id","failedId","serverId","siteId","instanceId","clusterId","allocationSlot","artifactSha256"))reply.put(key,payload.get(key));
            reply.put("protocol","eatwhat-member-configuration-recovery-v1");String action=((List<String>)call.getArgument(2)).getFirst();
            if(action.equals("inspect"))reply.putAll(Map.of("ready",true,"freezeHash","f".repeat(64),"databaseFingerprint",fp,"clusterMembership","NOT_JOINED"));
            else if(action.equals("dispatch")){if(loseDispatch)throw new java.io.IOException("private-dispatch-detail");reply.put("state","ACCEPTED");}
            else reply.putAll(Map.of("state",state,"databaseFingerprint",fp,"clusterMembership","NOT_JOINED","originalDatabasePreserved",true,"newEnrollmentPlanRequired",true));
            return reply;
        }).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
    }
    @Test void recoveryHasNewApprovalAndDoesNotExposeOldPrivatePayload()throws Exception{
        UUID failed=failedMember();var original=Files.readString(root.resolve("server-cluster-job-"+failed+".json"));var p=remote.createRecovery("123",failed);
        assertEquals("READY",p.get("state"));assertEquals(ControlRemoteEnrollment.RECOVERY_KIND,p.get("kind"));assertEquals(failed.toString(),p.get("failedId"));assertNotEquals(failed.toString(),p.get("id"));assertEquals(true,p.get("newEnrollmentPlanRequired"));assertFalse(p.toString().contains("synthetic"));assertFalse(p.containsKey("_sourceHash"));assertFalse(payload.containsKey("runtime"));assertEquals(original,Files.readString(root.resolve("server-cluster-job-"+failed+".json")));verifyNoInteractions(coordinator);
    }
    @Test void onlyTerminalFailedConfigurationCanCreateRecovery()throws Exception{
        UUID id=plan();remote.execute("123",id);reset(ssh);
        assertEquals("FAILED_MEMBER_CONFIGURATION_REQUIRED",assertThrows(ControlFailure.class,()->remote.createRecovery("123",id)).code);verifyNoInteractions(ssh);
    }
    @Test void recoverySuccessRestoresUnjoinedStateWithoutAnyMembershipWrites()throws Exception{
        UUID failed=failedMember();var original=Files.readString(root.resolve("server-cluster-job-"+failed+".json"));var installed=new LinkedHashMap<>((Map<?,?>)store.server(server).get("installation"));
        UUID id=UUID.fromString(remote.createRecovery("123",failed).get("id").toString());remote.execute("123",id);assertEquals("DEPLOYMENT_RECONCILIATION_REQUIRED",deployment.whenIdle(()->fail("accepted recovery blocks deployment")));remote.cycle();
        assertEquals("SUCCEEDED",job(id).get("state"));assertEquals("NOT_JOINED",((Map<?,?>)job(id).get("result")).get("clusterMembership"));assertEquals(installed,store.server(server).get("installation"));assertEquals(original,Files.readString(root.resolve("server-cluster-job-"+failed+".json")));assertNull(deployment.whenIdle(()->{}));verifyNoInteractions(coordinator);
    }
    @Test void lostRecoveryDispatchIsNeverRepeatedAndCanOnlyReadOriginalReceipt()throws Exception{
        UUID failed=failedMember();UUID id=UUID.fromString(remote.createRecovery("123",failed).get("id").toString());remote.execute("123",id);recoveryReplies("OUTCOME_UNKNOWN",true);remote.cycle();remote.execute("123",id);remote.cycle();remote.reconcile(id);
        assertEquals("OUTCOME_UNKNOWN",job(id).get("state"));verify(ssh,times(1)).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());assertEquals("DEPLOYMENT_RECONCILIATION_REQUIRED",deployment.whenIdle(()->fail("unknown recovery stays fenced")));verifyNoInteractions(coordinator);
    }
    @Test void recoveryRejectsOriginalJobDriftBeforeDispatch()throws Exception{
        UUID failed=failedMember();UUID id=UUID.fromString(remote.createRecovery("123",failed).get("id").toString());var path=root.resolve("server-cluster-job-"+failed+".json");var j=store.readJson(path);j.put("code","OTHER");store.saveJson(path,j);
        assertEquals("MEMBER_PLAN_CHANGED",assertThrows(ControlFailure.class,()->remote.execute("123",id)).code);verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());
    }
    @Test void recoveryCannotDisableAnAlreadyRetainedMember()throws Exception{
        UUID failed=failedMember();var state=new LinkedHashMap<String,Object>(membership.status());var members=new ArrayList<Object>((List<?>)state.get("members"));members.add(Map.of("site_id",external.siteId(),"instance_id",external.instanceId(),"allocation_slot",1,"state","REGISTERED"));state.put("members",members);when(membership.status()).thenReturn(state);
        var p=remote.createRecovery("123",failed);assertEquals("BLOCKED",p.get("state"));assertTrue(((List<?>)p.get("blockers")).contains("MEMBER_ALREADY_RETAINED"));verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("inspect")),any(),anyInt());verifyNoInteractions(coordinator);
    }
    @Test void mismatchedRestorationReceiptCannotBecomeSuccess()throws Exception{
        UUID failed=failedMember();UUID id=UUID.fromString(remote.createRecovery("123",failed).get("id").toString());remote.execute("123",id);
        when(ssh.call(anyMap(),anyString(),eq(List.of("status")),any(),anyInt())).thenReturn(Map.of("protocol","eatwhat-member-configuration-recovery-v1","id",id.toString(),"serverId",server.toString(),"state","RESTORED","failedId",UUID.randomUUID().toString()));remote.cycle();assertEquals("OUTCOME_UNKNOWN",job(id).get("state"));verifyNoInteractions(coordinator);
    }
    @Test void queuedRecoveryApprovalDriftEndsWithoutAnyRemoteMutation()throws Exception{
        UUID failed=failedMember();UUID id=UUID.fromString(remote.createRecovery("123",failed).get("id").toString());remote.execute("123",id);when(jdbc.queryForList(anyString(),eq(123L))).thenReturn(List.of());remote.cycle();
        assertEquals("FAILED",job(id).get("state"));assertEquals(false,job(id).get("effectsStarted"));verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());verifyNoInteractions(coordinator);
    }

    @Test @SuppressWarnings("unchecked") void freshRecoveryApprovalCanUseCorrectedSshCredentialsButFreezesNewRevision()throws Exception{
        UUID failed=failedMember();store.mutate(data->{((Map<String,Object>)((Map<?,?>)data.get("servers")).get(server.toString())).put("revision",3);return null;});
        var p=remote.createRecovery("123",failed);assertEquals("READY",p.get("state"));assertEquals(3,((Number)p.get("serverRevision")).intValue());UUID id=UUID.fromString(p.get("id").toString());remote.execute("123",id);remote.cycle();assertEquals("SUCCEEDED",job(id).get("state"));
    }

}
