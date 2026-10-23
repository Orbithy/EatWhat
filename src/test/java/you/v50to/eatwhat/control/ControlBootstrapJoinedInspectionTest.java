package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Real original encrypted inputs/runtime ledger; remote native diagnostics are
 * mocked. Verifies the retained entry point, not Linux health acceptance. */
class ControlBootstrapJoinedInspectionTest {
    @TempDir Path root;
    static final JsonMapper JSON=JsonMapper.builder().build();
    static <T>T stub(Class<T> type){return mock(type,withSettings().mockMaker(org.mockito.MockMakers.INLINE));}
    ControlServerStore store;ControlBootstrapNativeInputs inputs;ControlBootstrapMemberRuntime runtime;
    final ControlBootstrapJournal journal=stub(ControlBootstrapJournal.class);
    final ControlBootstrapNativePhases nativePhases=stub(ControlBootstrapNativePhases.class);
    final ControlBootstrapPhaseClaims claims=stub(ControlBootstrapPhaseClaims.class);
    final ControlBootstrapMemberRelay relay=stub(ControlBootstrapMemberRelay.class);
    final ControlBootstrapMemberAuth auth=stub(ControlBootstrapMemberAuth.class);
    final ControlBootstrapMemberIngress ingress=stub(ControlBootstrapMemberIngress.class);
    final ControlBootstrapAdmission.Frozen frozen=stub(ControlBootstrapAdmission.Frozen.class);
    ControlBootstrapJournal.Spec spec;ControlBootstrapNativeInputs.Sealed input;Map<String,Object> proof,receipt;Path ledger;
    @BeforeEach void setup()throws Exception{
        root=root.toRealPath();store=new ControlServerStore(new ControlAudit(root));inputs=new ControlBootstrapNativeInputs(store);
        spec=new ControlBootstrapJournal.Spec(UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),2,UUID.randomUUID(),1,"a".repeat(64),"fixture-business","b".repeat(64),1);
        var expected=new ControllerSourceSnapshot.Expected(spec.clusterId(),"campus",UUID.randomUUID(),"c".repeat(32),UUID.randomUUID(),1,"fixture-source","d".repeat(64),"e".repeat(64),"f".repeat(64));
        when(frozen.spec()).thenReturn(spec);when(frozen.targetRoute()).thenReturn("original-target-route");
        when(frozen.sourceRequest()).thenReturn(new ControlBootstrapSources.Request(spec.controllerSha256(),spec.controllerSha256(),"c".repeat(64),expected));
        String approval="a".repeat(64),agent="# exact original retained agent\n";when(journal.job(spec.id())).thenReturn(Map.of("requestHash",approval,"requiresReapproval",true));
        when(journal.approvedPermissions(spec.id())).thenReturn(Set.of("INSTALL_BUSINESS_AND_JOIN",ControlBootstrapMemberRelay.PERMISSION,ControlBootstrapMemberAuth.PERMISSION,ControlBootstrapMemberIngress.PERMISSION));
        var local=ControlBootstrapBusinessAllocation.identity(spec);var authority=new ClusterMember("campus",expected.authorityInstanceId(),"CAMPUS",0);
        var h=new LinkedHashMap<String,Object>(Map.of("id",UUID.nameUUIDFromBytes((spec.id()+":MEMBER_RUNTIME").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),"serverId",spec.targetServerId().toString(),
                "siteId",local.siteId(),"instanceId",local.instanceId().toString(),"clusterId",spec.clusterId().toString(),"allocationSlot",1,"artifactSha256",spec.businessSha256(),"databaseFingerprint","b".repeat(32),
                "bootstrapContext",Map.of("baseEpoch",1)));
        h.put("approvalDigest",approval);h.put("agentSha256",ControlBootstrapNativeJson.sha(agent));
        UUID inputId=UUID.nameUUIDFromBytes((spec.id()+":MEMBER_RUNTIME_INPUTS").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        input=inputs.prepare(inputId,MEMBER_CONFIGURE,approval,spec.targetServerId(),2,"original-target-route",true,()->new ControlBootstrapNativeInputs.Prepared(h,agent));
        ledger=root.resolve("controller-member-runtime-"+spec.id()+".json");String raw=JSON.writeValueAsString(Map.of("bootstrapId",spec.id().toString(),"inputBinding",input.binding(),"approvalDigest",approval,"state","CONFIGURED"));
        store.saveJson(ledger,Map.of("version",1,"body",store.encrypt(raw),"seal",store.fingerprint(raw)));
        var members=List.of(authority,local);proof=new LinkedHashMap<>(Map.of("bootstrapId",spec.id().toString(),"changeId",ControlBootstrapJournal.phaseId(spec.id(),MEMBER_JOIN).toString(),"baseEpoch",1,"epoch",2,
                "databaseFingerprint","b".repeat(32),"members",JSON.readValue(JSON.writeValueAsString(members),List.class),"membershipBinding","A".repeat(43)+"="));
        var plan=new ClusterMembershipCoordinator.Plan(spec.clusterId(),ControlBootstrapJournal.phaseId(spec.id(),MEMBER_JOIN),1,2,"JOIN",local.siteId(),members,members);
        receipt=new LinkedHashMap<>(h);receipt.put("protocol","eatwhat-member-joined-runtime-v1");receipt.put("state","JOINED_VERIFIED");receipt.put("clusterMembership","ACTIVE");
        for(String name:List.of("bootstrapId","changeId","epoch","membershipBinding"))receipt.put(name,proof.get(name));
        receipt.put("planHash",ControlBootstrapNativeJson.sha(JSON.writeValueAsString(plan)));receipt.put("healthCheckedAt",Instant.now().toString());
        for(String name:List.of("configurationRevalidated","authStoreVerified","localMembershipVerified","dataPreserved","nativeServicesRevalidated","networkBaselineRevalidated","applicationReady","databaseReady","redisReady","storageReadCheckVerified"))receipt.put(name,true);
        receipt.put("storageWriteProbePerformed",false);receipt.put("joinAuthorized",false);
        var endpoint=Map.<String,Object>of("id",spec.targetServerId().toString());when(nativePhases.server(frozen,MEMBER_CONFIGURE)).thenReturn(endpoint);
        when(nativePhases.call(eq(endpoint),eq(agent),eq(List.of("verify-joined")),anyMap())).thenAnswer(inv->{
            assertEquals(Map.of("original",input.header(),"joinProof",proof),inv.getArgument(3));return receipt;
        });
        runtime=new ControlBootstrapMemberRuntime(store,journal,nativePhases,claims,inputs,relay,auth,ingress);
    }
    Map<String,byte[]> files()throws Exception{var result=new TreeMap<String,byte[]>();try(var paths=Files.list(root)){for(var path:paths.filter(Files::isRegularFile).toList())result.put(path.getFileName().toString(),Files.readAllBytes(path));}return result;}
    void unchanged(Map<String,byte[]> before)throws Exception{var after=files();assertEquals(before.keySet(),after.keySet());before.forEach((key,value)->assertArrayEquals(value,after.get(key),key));}
    @Test void retainedOriginalIsVerifiedWithoutCreatingLocksClaimsOrCallingWritePrerequisites()throws Exception{
        var before=files();assertEquals("JOINED_VERIFIED",runtime.inspectJoined(frozen,proof).get("state"));unchanged(before);
        assertFalse(Files.exists(root.resolve("member-runtime-operations.lock")));verifyNoInteractions(claims,relay,auth,ingress);assertEquals(true,journal.job(spec.id()).get("requiresReapproval"));
    }
    @Test void missingOriginalRuntimeLedgerDoesNotRecreateItOrCallSsh()throws Exception{
        Files.delete(ledger);var before=files();assertEquals("BOOTSTRAP_MEMBER_RUNTIME_RECORD_MISSING",assertThrows(ControlFailure.class,()->runtime.inspectJoined(frozen,proof)).code);unchanged(before);
        verify(nativePhases,never()).call(any(),anyString(),anyList(),anyMap());verifyNoInteractions(claims,relay,auth,ingress);
    }
    @Test void differentOriginalChangeCannotReachRemoteDiagnostics()throws Exception{
        var changed=new LinkedHashMap<>(proof);changed.put("changeId",UUID.randomUUID().toString());var before=files();
        assertEquals("BOOTSTRAP_JOIN_SUCCESSOR_CHANGED",assertThrows(ControlFailure.class,()->runtime.inspectJoined(frozen,changed)).code);unchanged(before);verify(nativePhases,never()).call(any(),anyString(),anyList(),anyMap());
    }
    @Test void staleHealthOrAlteredIdentityCannotBePublishedAsFreshRuntimeEvidence()throws Exception{
        var before=files();receipt.put("healthCheckedAt",Instant.now().minusSeconds(90).toString());assertEquals("BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED",assertThrows(ControlFailure.class,()->runtime.inspectJoined(frozen,proof)).code);unchanged(before);
        receipt.put("healthCheckedAt",Instant.now().toString());receipt.put("instanceId",UUID.randomUUID().toString());assertEquals("BOOTSTRAP_NATIVE_RECEIPT_MISMATCH",assertThrows(ControlFailure.class,()->runtime.inspectJoined(frozen,proof)).code);unchanged(before);
    }
}
