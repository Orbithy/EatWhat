package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Real encrypted files, locks, parent/child claims and original artifact bytes.
 * Admission and remote SSH/native workers are explicit mocks. Preceding phase
 * receipts and JAR/UI bytes are fixtures, not a real-host installation proof. */
class ControlBootstrapControllerPhasesTest {
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
    @interface WithCatalog {boolean images() default false;}
    @TempDir Path root;
    ControlServerStore store;ControlBootstrapJournal journal;ControlBootstrapAdmission admission;ControlBootstrapPhaseClaims claims;
    ControlBootstrapNativeInputs inputs;ControlBootstrapNativePhases nativePhases;ControlBootstrapControllerOperations operations;
    ControlBootstrapControllerPhases driver;ControlSsh ssh;DeploymentExecutor deployment;ControlBootstrapAdmission.Frozen frozen;
    ControlBootstrapNativeInputs.Sealed original;
    byte[] jar,ui;Path jarPath;boolean remotePrepared,losePrepare,prepareNoEffect,losePut,loseStage,loseVerify,completeUnsealed;
    int prepareCalls,stageCalls,verifyCalls,putCalls;List<String> commands=new ArrayList<>();List<Long> putOffsets=new ArrayList<>();
    Map<String,byte[]> remote=new HashMap<>();
    Map<String,byte[]> expectedBlobs=new HashMap<>();List<String> putKinds=new ArrayList<>();
    static <T>T stub(Class<T> type){return mock(type,withSettings().mockMaker(org.mockito.MockMakers.INLINE));}
    static String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    @BeforeEach @SuppressWarnings("unchecked") void setup(TestInfo info)throws Exception{
        WithCatalog catalog=info.getTestMethod().orElseThrow().getAnnotation(WithCatalog.class);
        root=root.toRealPath();var audit=new ControlAudit(root);store=new ControlServerStore(audit);deployment=new DeploymentExecutor(new ControlProperties(),audit);
        var servers=stub(ControlServers.class);admission=stub(ControlBootstrapAdmission.class);ssh=stub(ControlSsh.class);
        journal=new ControlBootstrapJournal(store,audit,deployment,servers);claims=new ControlBootstrapPhaseClaims(journal,admission);
        jar=new byte[(catalog==null?1024*1024:16*1024*1024)+37];for(int i=0;i<jar.length;i++)jar[i]=(byte)(i*31+17);ui="synthetic-ui".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jarPath=root.resolve("original.jar");Files.write(jarPath,jar);Path uiPath=root.resolve("original-ui.tar.gz");Files.write(uiPath,ui);
        byte[] manifest=JsonMapper.builder().build().writeValueAsBytes(Map.of("sha256",sha(ui),"files",Map.of()));Path manifestPath=root.resolve("manifest.json");Files.write(manifestPath,manifest);
        ControlBootstrapArtifacts.File images=null;
        if(catalog!=null&&catalog.images()){
            byte[] bytes="synthetic-image-archive".repeat(1024).getBytes(java.nio.charset.StandardCharsets.US_ASCII);Path path=root.resolve("images.tar");Files.write(path,bytes);
            images=new ControlBootstrapArtifacts.File(path.toString(),sha(bytes),bytes.length);expectedBlobs.put("IMAGES",bytes);
        }
        var artifact=new ControlBootstrapArtifacts.Frozen(new ControlBootstrapArtifacts.File(jarPath.toString(),sha(jar),jar.length),
                new ControlBootstrapArtifacts.File(uiPath.toString(),sha(ui),ui.length),new ControlBootstrapArtifacts.File(manifestPath.toString(),sha(manifest),manifest.length),
                Map.of(),"fixture-acceptance","fixture-business",new ControlBootstrapArtifacts.File(jarPath.toString(),sha(jar),jar.length),"fixture-business-acceptance",
                Map.of("jre","eclipse-temurin:21-jre","postgres","postgis/postgis:16-3.5","redis","redis:7.4-alpine"),images,"fixture-catalog");
        expectedBlobs.put("JAR",jar);expectedBlobs.put("UI",ui);if(catalog!=null){expectedBlobs.put("BUSINESS",jar);expectedBlobs.put("UI_MANIFEST",manifest);}
        var spec=new ControlBootstrapJournal.Spec(UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),2,UUID.randomUUID(),2,sha(jar),"fixture-business",sha(jar),2);
        var review=Map.<String,Object>of("sourceAddress","10.253.74.1","targetAddress","10.253.74.3","targetEndpoint","fixture.example.invalid","wireguardPort",51820,"httpPort",18090,"appBaseUrl","https://fixture.example.invalid/api");
        frozen=new ControlBootstrapAdmission.Frozen(spec,review,"123","admin",UUID.randomUUID(),UUID.randomUUID(),1,"source-creds","target-creds","source-route","target-route","roster",null,null,null,Map.of(),"encrypted",artifact,"fixture-capsule");
        store.mutate(data->{var rows=(Map<String,Object>)data.get("servers");rows.put(spec.targetServerId().toString(),new LinkedHashMap<>(Map.of("id",spec.targetServerId().toString(),"username","ubuntu","privilege","SUDO","revision",2)));return null;});
        when(ssh.routeBinding(any())).thenReturn("target-route");
        when(admission.beforeInitialIntent(eq(frozen),any())).thenAnswer(inv->((Supplier<?>)inv.getArgument(1)).get());
        when(admission.beforeMembershipIntent(eq(frozen),any())).thenAnswer(inv->((Supplier<?>)inv.getArgument(1)).get());
        journal.preview("123",spec,review,Map.of("admission",Map.of("binding",frozen.binding())),()->{});journal.accept("123",spec.id(),ControlBootstrapJournal.PERMISSIONS,()->{});
        for(var phase:List.of(SOURCE_RUNTIME,TARGET_RUNTIME,SOURCE_PRIVATE_PEER,TARGET_PRIVATE_PEER,SOURCE_SHARED_STATE,TARGET_SHARED_STATE)){
            var claim=claims.claim(frozen,phase);journal.observe(spec.id(),phase,claim.claimToken(),Map.of("id",claim.id().toString(),"protocol",phase.protocol,"state",phase.success));
        }
        for(var op:ControlBootstrapJournal.DependencyOperation.values()){
            var claim=claims.dependency(frozen,op,"a".repeat(64),"b".repeat(64));journal.observeDependencyOperation(spec.id(),op,claim.claimToken(),
                    Map.of("id",claim.nativeId().toString(),"protocol",DEPENDENCY_LINK.protocol,"role",op.role,"operation",op.name(),"binding",claim.nativeBinding(),"state",op.success));
        }
        restart();
        String agent=ControlBootstrapNativePhases.expanded("controller-bootstrap-controller.py","__EATWHAT_CONTROLLER_CONTROLLER_POLICY_BASE64__",ControlServers.resource("controller-bootstrap-controller-policy.py"));
        var header=new LinkedHashMap<String,Object>();header.put("id",ControlBootstrapJournal.phaseId(spec.id(),CONTROLLER_CONFIG).toString());header.put("verifyId",ControlBootstrapJournal.phaseId(spec.id(),CONTROLLER_VERIFY).toString());
        header.put("dependencyId",ControlBootstrapJournal.phaseId(spec.id(),DEPENDENCY_LINK).toString());header.put("dependencyBinding","a".repeat(64));header.put("dependencyAgentSha256","b".repeat(64));
        header.put("sourceAuthority",Map.of("fixture",true));var bundle=new LinkedHashMap<String,Object>();bundle.put("properties",Map.of("synthetic.password","synthetic-controller-password"));
        if(catalog!=null)bundle.put("catalog",ControlBootstrapControllerCatalog.metadata(artifact));header.put("bundle",bundle);
        header.put("expectedProof","e".repeat(64));header.put("agentSha256",ControlBootstrapNativeJson.sha(agent));String approval=journal.job(spec.id()).get("requestHash").toString();header.put("approvalDigest",approval);
        original=inputs.prepare(spec.id(),CONTROLLER_CONFIG,approval,spec.targetServerId(),2,"target-route",true,()->new ControlBootstrapNativeInputs.Prepared(header,agent));
        doAnswer(inv->{
            String script=inv.getArgument(1);assertEquals(original.agent(),script);List<String> args=inv.getArgument(2);commands.add(String.join(" ",args));
            var out=new ByteArrayOutputStream();((ControlSsh.Upload)inv.getArgument(3)).write(out);byte[] wire=out.toByteArray();String command=args.getFirst();byte[] streamBytes=null;Map<String,Object> payload;
            if(command.equals("put-stream")){
                int newline=0;while(wire[newline]!='\n')newline++;payload=JsonMapper.builder().build().readValue(Arrays.copyOfRange(wire,0,newline),Map.class);streamBytes=Arrays.copyOfRange(wire,newline+1,wire.length);
                assertEquals(streamBytes.length,((Number)payload.get("length")).intValue());
            }else payload=JsonMapper.builder().build().readValue(wire,Map.class);
            Map<String,Object> h=Set.of("put","put-stream").contains(command)?(Map<String,Object>)payload.get("header"):new LinkedHashMap<>(payload);h.remove("agent");assertEquals(original.header(),h);
            if(command.equals("prepare")){
                prepareCalls++;assertEquals(1,prepareCalls,"Preparation may never be dispatched again");if(!prepareNoEffect)remotePrepared=true;if(losePrepare)throw new IOException("lost prepare response");return receipt("PREPARE","PREPARED");
            }
            if(command.equals("prepare-status"))return receipt("PREPARE",remotePrepared?"PREPARED":"NOT_PREPARED");
            assertTrue(remotePrepared);String kind=args.get(1);
            if(command.equals("blob-status"))return blob(kind);
            if(command.equals("put")||command.equals("put-stream")){
                byte[] before=remote.getOrDefault(kind,new byte[0]);long offset=((Number)payload.get("offset")).longValue();assertEquals(before.length,offset);assertEquals(sha(before),payload.get("prefixSha256"));
                assertTrue(Files.exists(root.resolve("controller-operations-"+frozen.spec().id()+".json")));assertEquals("CLAIMED",operations.progress(frozen).get("TRANSFER"));
                byte[] bytes=streamBytes==null?Base64.getDecoder().decode(payload.get("bytes").toString()):streamBytes;
                assertTrue(bytes.length<=ControlBootstrapControllerCatalog.maximumChunk(original.header()));putCalls++;putOffsets.add(offset);putKinds.add(kind);
                var joined=new ByteArrayOutputStream();joined.write(before);joined.write(bytes);remote.put(kind,joined.toByteArray());if(losePut){losePut=false;throw new IOException("lost append response");}return blob(kind);
            }
            if(command.equals("finalize")){completeUnsealed=false;return blob(kind);}
            if(command.equals("dispatch")){
                if(kind.equals("STAGE")){stageCalls++;assertEquals(1,stageCalls);if(loseStage)throw new IOException("lost stage response");}
                else{verifyCalls++;assertEquals(1,verifyCalls);if(loseVerify)throw new IOException("lost verify response");}
                return receipt(kind,kind.equals("STAGE")?"STAGED":"VERIFIED");
            }
            assertEquals("status",command);return receipt(kind,kind.equals("STAGE")?"STAGED":"VERIFIED");
        }).when(ssh).call(any(),anyString(),anyList(),any(),anyInt());
    }
    @AfterEach void close(){if(deployment!=null)deployment.close();}
    void restart(){inputs=new ControlBootstrapNativeInputs(store);nativePhases=new ControlBootstrapNativePhases(store,journal,admission,claims,inputs,ssh);
        operations=new ControlBootstrapControllerOperations(store,journal,claims,inputs,nativePhases);driver=new ControlBootstrapControllerPhases(nativePhases,operations,claims,journal,inputs,stub(ControlBootstrapControllerFactory.class));}
    Map<String,Object> receipt(String operation,String state){return Map.of("protocol",CONTROLLER_CONFIG.protocol,"id",original.header().get(operation.equals("VERIFY")?"verifyId":"id"),
            "configurationId",original.header().get("id"),"operation",operation,"binding",original.binding(),"state",state);}
    Map<String,Object> blob(String kind)throws Exception{
        byte[] data=remote.getOrDefault(kind,new byte[0]);byte[] expected=expectedBlobs.get(kind);
        return Map.of("kind",kind,"offset",data.length,"prefixSha256",sha(data),"state",data.length==expected.length?(completeUnsealed?"COMPLETE_UNSEALED":"READY"):data.length==0?"PENDING":"PARTIAL");
    }
    void transferDone(){for(int i=0;i<10&&!"SUCCEEDED".equals(operations.progress(frozen).get("TRANSFER"));i++)driver.configure(frozen);assertEquals("SUCCEEDED",operations.progress(frozen).get("TRANSFER"));}
    @Test void lostPrepareReplyUsesOriginalReadOnlyObserverAfterRestart(){
        losePrepare=true;assertThrows(ControlFailure.class,()->driver.configure(frozen));restart();driver.configure(frozen);assertEquals(1,prepareCalls);assertEquals(List.of("prepare","prepare-status"),commands);
        assertEquals("SUCCEEDED",operations.progress(frozen).get("PREPARE"));assertEquals(0,stageCalls);
    }
    @Test void missingRemotePreparationAfterAClaimNeverAuthorizesPrepareAgain(){
        losePrepare=true;prepareNoEffect=true;assertThrows(ControlFailure.class,()->driver.configure(frozen));restart();driver.configure(frozen);driver.configure(frozen);
        assertEquals(List.of("prepare","prepare-status","prepare-status"),commands);assertEquals("CLAIMED",operations.progress(frozen).get("PREPARE"));assertEquals(0,putCalls);
    }
    @Test void missingChildLedgerForAnAlreadyClaimedTaskCannotBeCreated(){
        claims.claim(frozen,CONTROLLER_CONFIG);assertEquals("BOOTSTRAP_CONTROLLER_RECORD_MISSING",assertThrows(ControlFailure.class,()->driver.configure(frozen)).code);
        assertTrue(commands.isEmpty());assertFalse(Files.exists(root.resolve("controller-operations-"+frozen.spec().id()+".json")));
    }
    @Test void genericRuntimeDriverCannotConsumeAControllerIntentEvenWithASealedControllerHeader(){
        assertEquals("BOOTSTRAP_NATIVE_DRIVER_REQUIRED",assertThrows(ControlFailure.class,()->nativePhases.advance(frozen,CONTROLLER_CONFIG)).code);
        assertEquals("PENDING",((Map<?,?>)((List<?>)journal.job(frozen.spec().id()).get("steps")).get(CONTROLLER_CONFIG.ordinal())).get("state"));assertTrue(commands.isEmpty());
    }
    @Test void lostAppendReplyContinuesTheExactPrefixAndDoesNotDuplicateBytes(){
        driver.configure(frozen);losePut=true;assertThrows(ControlFailure.class,()->driver.configure(frozen));assertEquals(512*1024,remote.get("JAR").length);
        restart();transferDone();assertArrayEquals(jar,remote.get("JAR"));assertArrayEquals(ui,remote.get("UI"));assertEquals(List.of(0L,524288L,1048576L,0L),putOffsets);
        assertEquals(0,stageCalls);driver.configure(frozen);assertEquals(1,stageCalls);assertEquals("CONTROLLER_VERIFY",journal.job(frozen.spec().id()).get("stage"));
    }
    @Test void wrongRetainedPrefixIsPreservedWithoutAppendingOrTruncating(){
        driver.configure(frozen);losePut=true;assertThrows(ControlFailure.class,()->driver.configure(frozen));byte[] retained=remote.get("JAR");retained[0]^=1;
        assertEquals("BOOTSTRAP_CONTROLLER_PREFIX_MISMATCH",assertThrows(ControlFailure.class,()->driver.configure(frozen)).code);assertEquals(1,putCalls);assertArrayEquals(retained,remote.get("JAR"));assertEquals(0,stageCalls);
    }
    @Test void changedLocalArtifactPermanentlyInvalidatesApprovalEvenIfFileIsRestored()throws Exception{
        driver.configure(frozen);byte[] changed=jar.clone();changed[0]^=1;Files.write(jarPath,changed);
        assertEquals("BOOTSTRAP_ARTIFACT_CHANGED",assertThrows(ControlFailure.class,()->driver.configure(frozen)).code);assertEquals(true,journal.job(frozen.spec().id()).get("requiresReapproval"));
        Files.write(jarPath,jar);assertEquals("BOOTSTRAP_REAPPROVAL_REQUIRED",assertThrows(ControlFailure.class,()->driver.configure(frozen)).code);assertEquals(0,putCalls);
    }
    @Test void lostStageAndVerifyRepliesObserveOriginalWorkerAndDistinctOriginalIds(){
        driver.configure(frozen);transferDone();loseStage=true;assertThrows(ControlFailure.class,()->driver.configure(frozen));restart();driver.configure(frozen);assertEquals(1,stageCalls);assertEquals("status STAGE",commands.getLast());
        loseVerify=true;assertThrows(ControlFailure.class,()->driver.verify(frozen));restart();driver.verify(frozen);assertEquals(1,verifyCalls);assertEquals("status VERIFY",commands.getLast());
        assertEquals("BUSINESS_INSTALL",journal.job(frozen.spec().id()).get("stage"));assertEquals(original.header().get("verifyId"),ControlBootstrapJournal.phaseId(frozen.spec().id(),CONTROLLER_VERIFY).toString());
    }
    @Test void sourceOrLeaseGuardFailureCannotDispatchAnotherChildOrUploadChunk(){
        driver.configure(frozen);when(admission.beforeMembershipIntent(eq(frozen),any())).thenThrow(new ControlFailure(503,"DEPENDENCY_UNAVAILABLE"));
        assertThrows(ControlFailure.class,()->driver.configure(frozen));assertEquals(0,putCalls);assertEquals("PENDING",operations.progress(frozen).get("TRANSFER"));
    }
    @Test void sealingACompleteOriginalBlobIsIndependentOfAppendingItAgain(){
        driver.configure(frozen);remote.put("JAR",jar.clone());remote.put("UI",ui.clone());completeUnsealed=true;
        driver.configure(frozen);assertEquals("finalize JAR",commands.getLast());driver.configure(frozen);assertEquals("SUCCEEDED",operations.progress(frozen).get("TRANSFER"));assertEquals(0,putCalls);
    }
    @Test void publicProgressAndEncryptedRecordsDoNotExposePrivateConfiguration()throws Exception{
        driver.configure(frozen);String publicView=driver.configure(frozen).toString();assertFalse(publicView.contains("synthetic-controller-password"));assertFalse(publicView.contains("bundle"));
        for(Path path:List.of(root.resolve("controller-operations-"+frozen.spec().id()+".json"),root.resolve("controller-native-"+frozen.spec().id()+"-CONTROLLER_CONFIG.json")))
            assertFalse(Files.readString(path).contains("synthetic-controller-password"));
    }
    @Test @SuppressWarnings("unchecked") void uncertainChildIntentPersistenceNeverProducesAPrepareDispatch(){
        var spy=mock(ControlServerStore.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(store).defaultAnswer(CALLS_REAL_METHODS));
        doAnswer(inv->{
            var value=(Map<String,Object>)inv.getArgument(1);var body=JsonMapper.builder().build().readValue(store.decrypt(value.get("body").toString()),Map.class);
            boolean claimed=((List<Map<String,Object>>)body.get("operations")).stream().anyMatch(row->"CLAIMED".equals(row.get("state")));
            inv.callRealMethod();if(claimed)throw new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE");return null;
        }).when(spy).saveJson(eq(root.resolve("controller-operations-"+frozen.spec().id()+".json")),any());
        operations=new ControlBootstrapControllerOperations(spy,journal,claims,inputs,nativePhases);driver=new ControlBootstrapControllerPhases(nativePhases,operations,claims,journal,inputs,stub(ControlBootstrapControllerFactory.class));
        assertThrows(ControlFailure.class,()->driver.configure(frozen));assertEquals(0,prepareCalls);restart();driver.configure(frozen);
        assertEquals(List.of("prepare-status"),commands);assertEquals("CLAIMED",operations.progress(frozen).get("PREPARE"));
    }
    @Test @WithCatalog(images=true) void catalogStreamsEveryDeclaredAssetAndContinuesAfterLostRawAck(){
        driver.configure(frozen);losePut=true;assertThrows(ControlFailure.class,()->driver.configure(frozen));assertEquals(16*1024*1024,remote.get("JAR").length);
        restart();transferDone();assertEquals(expectedBlobs.keySet(),remote.keySet());
        expectedBlobs.forEach((kind,bytes)->assertArrayEquals(bytes,remote.get(kind),kind));
        assertTrue(commands.contains("put-stream BUSINESS"));assertTrue(commands.contains("put-stream UI_MANIFEST"));assertTrue(commands.contains("put-stream IMAGES"));
        assertFalse(commands.stream().anyMatch(c->c.startsWith("put ")));assertEquals(2,putKinds.stream().filter("JAR"::equals).count());
        assertEquals(16777216L,putOffsets.get(1));assertEquals(0,stageCalls);driver.configure(frozen);assertEquals(1,stageCalls);
    }
    @Test @WithCatalog void catalogCannotAdvanceStageAfterOnlyLegacyBlobsArePresent(){
        driver.configure(frozen);remote.put("JAR",jar.clone());remote.put("UI",ui.clone());driver.configure(frozen);
        assertEquals(List.of("BUSINESS"),putKinds);assertEquals("CLAIMED",operations.progress(frozen).get("TRANSFER"));assertEquals(0,stageCalls);
        transferDone();assertEquals(Set.of("JAR","UI","BUSINESS","UI_MANIFEST"),remote.keySet());
    }
}
