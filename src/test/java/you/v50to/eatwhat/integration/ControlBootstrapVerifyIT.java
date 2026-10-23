package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.control.*;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Original admission, real two-DB JOIN and cross-language retained phase
 * verification. Native files/services, SSH and health remain explicit fixtures;
 * this is not a Linux deployment or real S3/Redis health acceptance. */
class ControlBootstrapVerifyIT {
    @TempDir static Path pki;@TempDir Path root;
    static final JsonMapper JSON=JsonMapper.builder().build();
    ControlBootstrapMembershipIT fixture;ControlBootstrapAdmissionIT base;
    @BeforeAll static void start()throws Exception{ControlBootstrapMembershipIT.pki=pki;ControlBootstrapMembershipIT.start();}
    @AfterAll static void stop(){ControlBootstrapMembershipIT.stop();}
    @BeforeEach void setup()throws Exception{fixture=new ControlBootstrapMembershipIT();fixture.root=root;fixture.setup();base=fixture.base;}
    @AfterEach void close(){fixture.close();}
    class Case implements AutoCloseable {
        final ControlBootstrapMembershipIT.Case member;final AtomicInteger observations=new AtomicInteger(),captures=new AtomicInteger();
        java.util.function.Consumer<Map<String,Object>> afterRuntime=result->{};
        boolean changedSource;
        Case()throws Exception{
            member=fixture.new Case(true);member.configure();member.register();member.manager.join(member.profile.frozen);assertEquals("ACTIVE",member.manager.reconcileJoin(member.profile.frozen).get("state"));
            base.store.mutate(data->{var servers=(Map<String,Object>)data.get("servers");var row=(Map<String,Object>)servers.get(base.targetId.toString());
                row.put("installation",new LinkedHashMap<>(Map.ofEntries(Map.entry("state","SUCCEEDED"),Map.entry("jobId",ControlBootstrapJournal.phaseId(base.spec.id(),BUSINESS_INSTALL).toString()),Map.entry("bootstrapJobId",base.spec.id().toString()),
                    Map.entry("siteId",member.member.siteId()),Map.entry("instanceId",member.member.instanceId().toString()),Map.entry("clusterId",base.site.getClusterId().toString()),Map.entry("mode","EXTERNAL"),Map.entry("allocationSlot",member.member.allocationSlot()),
                    Map.entry("currentSha256",base.spec.businessSha256()),Map.entry("clusterMembership","NOT_JOINED"),Map.entry("restoreEvidenceId","synthetic-native-business-proof"),Map.entry("networkRevalidationRequired",true),Map.entry("businessHealthRevalidated",false))));return null;});
            doAnswer(inv->{
                var request=(ControlBootstrapSources.Request)inv.getArgument(2);var original=member.profile.pair.sourceCaptureRequest();var e=original.expected();
                assertEquals(new ControllerSourceSnapshot.Expected(e.clusterId(),e.authoritySiteId(),e.authorityInstanceId(),e.authorityDatabaseFingerprint(),e.stateId(),2,e.controllerName(),e.applicationSha256(),e.profilesSha256(),e.sharedKeySha256()),request.expected());
                assertEquals(original.sourceArtifactSha256(),request.sourceArtifactSha256());assertEquals(original.probeArtifactSha256(),request.probeArtifactSha256());assertEquals(original.unitSha256(),request.unitSha256());captures.incrementAndGet();
                var hashes=new HashMap<>(member.profile.pair.sourceCaptureFileHashes());if(changedSource)hashes.put("application.properties","0".repeat(64));
                return new ControlBootstrapSources.Captured(base.sourceId,1,member.profile.frozen.sourceRoute(),Map.of("fileHashes",hashes,"sourceProcess",member.profile.frozen.sourceEvidence().get("sourceProcess"),"proof",Map.of("epoch",2,"readOnlyTransaction",true)),Map.of());
            }).when(base.sources).observeExecuting(eq(base.sourceId),eq(1L),argThat(request->request!=null&&request.expected().epoch()==2));
            member.joinedObservation=proof->{observations.incrementAndGet();var result=new LinkedHashMap<>(member.ready);result.put("protocol","eatwhat-member-joined-runtime-v1");result.put("state","JOINED_VERIFIED");result.put("clusterMembership","ACTIVE");
                for(String key:List.of("bootstrapId","changeId","epoch","membershipBinding"))result.put(key,proof.get(key));
                result.put("planHash",nativePhaseProof(proof));result.put("healthCheckedAt",Instant.now().toString());
                for(String key:List.of("localMembershipVerified","nativeServicesRevalidated","networkBaselineRevalidated","applicationReady","databaseReady","redisReady","storageReadCheckVerified"))result.put(key,true);result.put("storageWriteProbePerformed",false);afterRuntime.accept(result);return result;};
        }
        ControlBootstrapVerify verifier(ControlBootstrapJournal journal){var beans=new StaticListableBeanFactory(Map.of("cluster",member.store));return new ControlBootstrapVerify(base.store,journal,base.admission,member.profile.state,member.manager,member.runtime,beans.getBeanProvider(ClusterStore.class));}
        Map<String,Object> advance(){return verifier(base.journal).advance(member.profile.frozen);}
        Map<String,Object> installation(){return (Map<String,Object>)base.store.server(base.targetId).get("installation");}
        String nativePhaseProof(Map<String,Object> expected){
            try{
                UUID change=UUID.fromString(expected.get("changeId").toString());String raw=member.target.runtime().queryForObject("""
                    SELECT json_build_object(
                      'state',(SELECT json_build_object('cluster_id',cluster_id,'site_id',site_id,'instance_id',instance_id,'epoch',epoch,'state',state,'pending_change',pending_change,'members',members) FROM eatwhat_cluster.local_state WHERE singleton),
                      'change',(SELECT json_build_object('change_id',change_id,'plan',plan,'plan_hash',plan_hash) FROM eatwhat_cluster.local_change WHERE change_id=?),
                      'phases',(SELECT json_agg(json_build_object('phase',phase,'proof_hash',proof_hash) ORDER BY phase) FROM eatwhat_cluster.local_phase WHERE change_id=?))::text
                    """,String.class,change,change);
                var header=Map.<String,Object>of("id",UUID.nameUUIDFromBytes((base.spec.id()+":MEMBER_RUNTIME").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),"siteId",member.member.siteId(),"instanceId",member.member.instanceId().toString(),"clusterId",base.site.getClusterId().toString(),"allocationSlot",member.member.allocationSlot(),
                    "databaseFingerprint",expected.get("databaseFingerprint"),"bootstrapContext",Map.of("bootstrapId",base.spec.id().toString(),"baseEpoch",1),"runtime",Map.of("properties",Map.of("app.cluster.authority-site-id",base.site.getId(),"app.cluster.authority-instance-id",base.site.getInstanceId().toString())));
                String program="import json,sys,types\nx=json.load(sys.stdin);m=types.ModuleType('actual_joined_verifier');exec(compile(x['source'],'<reviewed-joined-verifier>','exec'),m.__dict__);print(m.membership_proof(x['header'],x['expected'],x['observed']))\n";
                var p=new ProcessBuilder("python3","-I","-B","-c",program).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try(var out=p.getOutputStream()){out.write(JSON.writeValueAsBytes(Map.of("source",Files.readString(Path.of("src/main/resources/control/member-configure.py")),"header",header,"expected",expected,"observed",JSON.readValue(raw,Map.class))));}
                assertTrue(p.waitFor(10,TimeUnit.SECONDS));assertEquals(0,p.exitValue(),"The bundled Python verifier must accept real agent/SQL phase hashes");String result=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip();assertTrue(result.matches("[0-9a-f]{64}"));return result;
            }catch(Exception failure){throw new AssertionError(failure);}
        }
        public void close(){member.close();}
    }
    @Test void realJoinedEvidenceAndExactOwnSourceSuccessorPermitTerminalProjection()throws Exception{try(var c=new Case()){
        String original=c.member.profile.frozen.binding();assertEquals("SUCCEEDED",c.advance().get("state"));var installed=c.installation();assertEquals("ACTIVE",installed.get("clusterMembership"));assertEquals(true,installed.get("businessHealthRevalidated"));assertEquals(true,installed.get("storageReadCheckVerified"));assertEquals(false,installed.get("networkRevalidationRequired"));
        assertEquals(2L,((Number)installed.get("joinedEpoch")).longValue());assertEquals(original,c.member.profile.frozen.binding());assertEquals(1,c.member.profile.frozen.spec().epoch());assertEquals(1,c.member.profile.pair.sourceCaptureRequest().expected().epoch());
        assertEquals(1,c.observations.get());assertEquals(2,c.captures.get());assertEquals("SUCCEEDED",c.advance().get("state"));assertEquals(1,c.observations.get());
    }}
    @Test void nativeFailureStaleHealthOrSourceFileDriftCannotPublishSuccess()throws Exception{try(var c=new Case()){
        c.changedSource=true;assertEquals("BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED",assertThrows(ControlFailure.class,c::advance).code);assertEquals(0,c.observations.get());assertEquals("NOT_JOINED",c.installation().get("clusterMembership"));assertNotEquals("SUCCEEDED",base.journal.job(base.spec.id()).get("state"));
    }}
    @Test void oldDependencyObservationCannotPassEvenWithCompletedMembership()throws Exception{try(var c=new Case()){
        c.afterRuntime=seen->seen.put("healthCheckedAt",Instant.now().minusSeconds(90).toString());assertEquals("BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED",assertThrows(ControlFailure.class,c::advance).code);assertEquals("NOT_JOINED",c.installation().get("clusterMembership"));assertNotEquals("SUCCEEDED",base.journal.job(base.spec.id()).get("state"));
    }}
    @Test void administratorChangedAfterNativeReadCannotCommitTerminalReceipt()throws Exception{try(var c=new Case()){
        c.afterRuntime=seen->base.db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(base.actor));assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,c::advance).code);assertEquals("NOT_JOINED",c.installation().get("clusterMembership"));assertNotEquals("SUCCEEDED",base.journal.job(base.spec.id()).get("state"));
    }}
    @Test void projectionBeforeLostJournalWriteIsIdempotentAndCannotRedispatchInstall()throws Exception{try(var c=new Case()){
        var journal=mock(ControlBootstrapJournal.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(base.journal).defaultAnswer(CALLS_REAL_METHODS));
        doThrow(new ControlFailure(503,"FIXTURE_LOST_FINAL_WRITE")).when(journal).observe(eq(base.spec.id()),eq(VERIFY),anyString(),anyMap());
        assertEquals("FIXTURE_LOST_FINAL_WRITE",assertThrows(ControlFailure.class,()->c.verifier(journal).advance(c.member.profile.frozen)).code);assertEquals("ACTIVE",c.installation().get("clusterMembership"));assertNotEquals("SUCCEEDED",base.journal.job(base.spec.id()).get("state"));
        assertEquals("SUCCEEDED",c.advance().get("state"));assertEquals(2,c.observations.get());verify(c.member.coordinator,times(1)).beginOwnedJoinExpected(any(),anyString(),anyString(),any(),anyLong(),any(),anyString(),any());
    }}
}
