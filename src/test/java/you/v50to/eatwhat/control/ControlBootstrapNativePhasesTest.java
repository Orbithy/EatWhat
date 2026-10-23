package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Real encrypted input/journal storage and bundled Python validators. Only
 * authority admission and SSH are mocked: these are not native-host acceptance. */
class ControlBootstrapNativePhasesTest {
    @TempDir Path root;
    ControlServerStore store;ControlAudit audit;DeploymentExecutor deployment;ControlBootstrapJournal journal;
    ControlBootstrapNativeInputs inputs;ControlBootstrapNativePhases driver;ControlBootstrapAdmission admission;
    ControlBootstrapPhaseClaims claims;ControlServers servers;ControlSsh ssh;
    ControlBootstrapDependencyPhases dependencies;
    ControlBootstrapAdmission.Frozen frozen;ControlBootstrapJournal.Spec spec;
    List<Map<String,Object>> sent=new ArrayList<>();List<String> commands=new ArrayList<>();
    List<String> programs=new ArrayList<>();boolean loseReply,wrongBinding,notStarted;
    String sourceKey=Base64.getEncoder().encodeToString(new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32});
    String targetKey=Base64.getEncoder().encodeToString(new byte[]{2,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32});
    static <T>T stub(Class<T> type){return mock(type,withSettings().mockMaker(org.mockito.MockMakers.INLINE));}
    @BeforeEach @SuppressWarnings("unchecked") void setup()throws Exception{
        root=root.toRealPath();audit=new ControlAudit(root);store=new ControlServerStore(audit);deployment=new DeploymentExecutor(new ControlProperties(),audit);
        servers=stub(ControlServers.class);admission=stub(ControlBootstrapAdmission.class);ssh=stub(ControlSsh.class);
        journal=new ControlBootstrapJournal(store,audit,deployment,servers);claims=new ControlBootstrapPhaseClaims(journal,admission);inputs=new ControlBootstrapNativeInputs(store);
        spec=new ControlBootstrapJournal.Spec(UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),2,UUID.randomUUID(),2,"a".repeat(64),"fixture-business","b".repeat(64),2);
        var review=Map.<String,Object>of("sourceAddress","10.253.74.1","targetAddress","10.253.74.3","targetEndpoint","fixture.example.invalid","wireguardPort",51820,"httpPort",18090,"appBaseUrl","https://fixture.example.invalid/api");
        var source=new ControlBootstrapPreflights.Captured(spec.sourceServerId(),1,"source-route",request(true),Map.of("proof","c".repeat(64)));
        var target=new ControlBootstrapPreflights.Captured(spec.targetServerId(),2,"target-route",request(false),Map.of("proof","d".repeat(64)));
        frozen=new ControlBootstrapAdmission.Frozen(spec,review,"123","admin",UUID.randomUUID(),UUID.randomUUID(),1,
                "source-credentials","target-credentials","source-route","target-route","roster",source,target,null,Map.of(),"encrypted",null,"capsule-binding");
        store.mutate(data->{var rows=(Map<String,Object>)data.get("servers");
            rows.put(spec.sourceServerId().toString(),new LinkedHashMap<>(Map.of("id",spec.sourceServerId().toString(),"username","root","revision",1)));
            rows.put(spec.targetServerId().toString(),new LinkedHashMap<>(Map.of("id",spec.targetServerId().toString(),"username","ubuntu","privilege","SUDO","revision",2)));return null;});
        when(ssh.routeBinding(any())).thenAnswer(inv->spec.sourceServerId().toString().equals(((Map<?,?>)inv.getArgument(0)).get("id"))?"source-route":"target-route");
        when(admission.beforeInitialIntent(eq(frozen),any())).thenAnswer(inv->((Supplier<?>)inv.getArgument(1)).get());
        when(admission.beforeMembershipIntent(eq(frozen),any())).thenAnswer(inv->((Supplier<?>)inv.getArgument(1)).get());
        when(ssh.call(any(),anyString(),anyList(),any(),anyInt())).thenAnswer(inv->{
            var out=new ByteArrayOutputStream();((ControlSsh.Upload)inv.getArgument(3)).write(out);
            Map<String,Object> payload=JsonMapper.builder().build().readValue(out.toByteArray(),Map.class);
            List<String> args=inv.getArgument(2);String cmd=args.isEmpty()?"host-key":args.getFirst();sent.add(payload);commands.add(cmd);programs.add(inv.getArgument(1));
            if(cmd.equals("host-key"))return Map.of("protocol",DEPENDENCY_LINK.protocol,"id",payload.get("id"),"effectsStarted",false,"publicKey",sshPublic(sourceKey));
            if(cmd.equals("metadata"))return Map.of("protocol",SOURCE_SHARED_STATE.protocol,"id",payload.get("id"),"effectsStarted",false,
                    "privateMetadata",Map.of("ownerUid",1000,"ownerGid",1000,"idmapDomain","eatwhat.invalid","stateKeySha256","f".repeat(64)));
            if(cmd.equals("inspect"))return Map.of("protocol",payload.containsKey("stateId")?DEPENDENCY_LINK.protocol:SOURCE_SHARED_STATE.protocol,"id",payload.get("id"),"effectsStarted",false,
                    "role",payload.containsKey("stateId")?payload.get("role"):ControlBootstrapJournal.phaseId(spec.id(),SOURCE_SHARED_STATE).toString().equals(payload.get("id"))?"SOURCE":"TARGET","proof","e".repeat(64));
            if(cmd.equals("dispatch")&&loseReply)throw new IOException("synthetic lost reply");
            var header=new LinkedHashMap<>(payload);header.remove("agent");var receipt=new LinkedHashMap<String,Object>();
            receipt.put("binding",wrongBinding?"0".repeat(64):ControlBootstrapNativeJson.digest(header));
            if(payload.containsKey("stateId")){
                String op=args.get(1);receipt.put("protocol",DEPENDENCY_LINK.protocol);receipt.put("id",payload.get("id"));receipt.put("role",payload.get("role"));receipt.put("operation",op);
                receipt.put("state",switch(op){case "KEY"->"KEY_READY";case "SOURCE"->"AUTHORIZED";default->"VERIFIED";});
                if(op.equals("KEY"))receipt.put("publicKey",sshPublic(targetKey));
            }else if(payload.get("request") instanceof Map<?,?> request){
                receipt.put("protocol",SOURCE_RUNTIME.protocol);receipt.put("id",request.get("id"));receipt.put("role",request.get("role"));
                receipt.put("sourceServerId",spec.sourceServerId().toString());receipt.put("targetServerId",spec.targetServerId().toString());
                receipt.put("publicKey","SOURCE".equals(request.get("role"))?sourceKey:targetKey);receipt.put("state",notStarted?"NOT_STARTED":"RUNTIME_READY");
                receipt.put("approvalDigest",payload.get("approvalDigest"));
            }else if(payload.containsKey("runtimeId")){
                receipt.put("protocol",SOURCE_PRIVATE_PEER.protocol);receipt.put("id",payload.get("id"));receipt.put("runtimeId",payload.get("runtimeId"));
                receipt.put("approvalDigest",payload.get("approvalDigest"));receipt.put("state","CONFIGURED");
            }else{
                receipt.put("protocol",SOURCE_SHARED_STATE.protocol);receipt.put("id",payload.get("id"));
                receipt.put("state",ControlBootstrapJournal.phaseId(spec.id(),SOURCE_SHARED_STATE).toString().equals(payload.get("id"))?"EXPORTED":"MOUNTED");
            }return receipt;
        });
        journal.preview("123",spec,review,Map.of("admission",Map.of("binding",frozen.binding())),()->{});
        journal.accept("123",spec.id(),ControlBootstrapJournal.PERMISSIONS,()->{});
        restart();
    }
    @AfterEach void close(){deployment.close();}
    Map<String,Object> request(boolean source){
        var result=new LinkedHashMap<String,Object>();result.put("id",ControlBootstrapJournal.phaseId(spec.id(),source?SOURCE_RUNTIME:TARGET_RUNTIME).toString());
        result.put("role",source?"SOURCE":"TARGET");result.put("sourceServerId",spec.sourceServerId().toString());result.put("targetServerId",spec.targetServerId().toString());
        result.put("sourceAddress","10.253.74.1");result.put("targetAddress","10.253.74.3");result.put("targetEndpoint","fixture.example.invalid");
        result.put("wireguardPort",51820);result.put("sharedStateId",UUID.randomUUID().toString());result.put("controllerArtifactSha256",spec.controllerSha256());result.put("businessAllocationSlot",2);return result;
    }
    ControlBootstrapTargetNetwork network;
    void restart(){inputs=new ControlBootstrapNativeInputs(store);driver=new ControlBootstrapNativePhases(store,journal,admission,claims,inputs,ssh);
        network=mock(ControlBootstrapTargetNetwork.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(network.forwards(frozen)).thenReturn(List.of(Map.of("purpose","CONTROL_DATABASE","localPort",25432,"remotePort",25432),
                Map.of("purpose","CONTROL_CACHE","localPort",16379,"remotePort",16379),Map.of("purpose","AUTHORITY_HTTP","localPort",28090,"remotePort",18090)));
        dependencies=new ControlBootstrapDependencyPhases(driver,inputs,journal,claims,network);}
    String sshPublic(String raw){
        var wire=java.nio.ByteBuffer.allocate(51).putInt(11).put("ssh-ed25519".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(32).put(Base64.getDecoder().decode(raw)).array();
        return "ssh-ed25519 "+Base64.getEncoder().encodeToString(wire);
    }
    String approval(){return journal.job(spec.id()).get("requestHash").toString();}
    Path nativePath(ControlBootstrapJournal.Phase phase){return root.resolve("controller-native-"+spec.id()+"-"+phase+".json");}
    @Test void lostReplyReconcilesOriginalIdAndCodeAfterRestartWithoutDispatchingAgain(){
        loseReply=true;assertEquals("BOOTSTRAP_NATIVE_OBSERVATION_UNAVAILABLE",assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_RUNTIME)).code);
        assertEquals("OUTCOME_UNKNOWN",journal.job(spec.id()).get("state"));var original=new LinkedHashMap<>(sent.getFirst());original.remove("agent");String code=programs.getFirst();
        restart();loseReply=false;assertEquals("QUEUED",driver.advance(frozen,SOURCE_RUNTIME).get("state"));
        assertEquals(List.of("dispatch","status"),commands);assertEquals(original,sent.getLast());assertEquals(code,programs.getLast());
        verify(admission,times(1)).beforeInitialIntent(eq(frozen),any());
    }
    @Test void remoteNotStartedAfterLostReplyNeverAllowsReplay(){
        loseReply=true;assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_RUNTIME));loseReply=false;notStarted=true;
        assertEquals("OUTCOME_UNKNOWN",driver.advance(frozen,SOURCE_RUNTIME).get("state"));restart();driver.advance(frozen,SOURCE_RUNTIME);
        assertEquals(List.of("dispatch","status","status"),commands);
    }
    @Test void independentInspectionAfterInvalidationUsesOriginalBytesWithoutChangingTheJournal()throws Exception{
        loseReply=true;assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_RUNTIME));loseReply=false;
        var header=new LinkedHashMap<>(sent.getFirst());header.remove("agent");String agent=programs.getFirst();
        journal.executionObservation(spec.id(),"BOOTSTRAP_ORIGINAL_CONTROLLER_REQUIRED",true);restart();
        assertEquals(true,journal.job(spec.id()).get("requiresReapproval"));
        Path job=root.resolve("controller-job-"+spec.id()+".json");byte[] before=Files.readAllBytes(job),input=Files.readAllBytes(nativePath(SOURCE_RUNTIME));
        clearInvocations(admission,servers);
        assertEquals("RUNTIME_READY",driver.inspectSaved(frozen,SOURCE_RUNTIME).get("state"));
        assertEquals(List.of("dispatch","status"),commands);assertEquals(header,sent.getLast());assertEquals(agent,programs.getLast());
        assertArrayEquals(before,Files.readAllBytes(job));assertArrayEquals(input,Files.readAllBytes(nativePath(SOURCE_RUNTIME)));
        assertEquals(true,journal.job(spec.id()).get("requiresReapproval"));
        verify(admission,never()).beforeInitialIntent(any(),any());verify(admission,never()).beforeMembershipIntent(any(),any());
        verifyNoInteractions(servers);
    }
    @Test void inspectionNeverRecreatesAMissingOriginalOrUsesAChangedRoute()throws Exception{
        loseReply=true;assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_RUNTIME));loseReply=false;
        byte[] bytes=Files.readAllBytes(nativePath(SOURCE_RUNTIME));Files.delete(nativePath(SOURCE_RUNTIME));
        assertEquals("BOOTSTRAP_NATIVE_INPUT_MISSING",assertThrows(ControlFailure.class,()->driver.inspectSaved(frozen,SOURCE_RUNTIME)).code);
        assertFalse(Files.exists(nativePath(SOURCE_RUNTIME)));assertEquals(1,commands.size());
        Files.write(nativePath(SOURCE_RUNTIME),bytes);Files.setPosixFilePermissions(nativePath(SOURCE_RUNTIME),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        doReturn("changed-route").when(ssh).routeBinding(any());
        assertEquals("SSH_ROUTE_BINDING_CHANGED",assertThrows(ControlFailure.class,()->driver.inspectSaved(frozen,SOURCE_RUNTIME)).code);assertEquals(1,commands.size());
    }
    @Test void inspectionRejectsForgedRemoteBindingsWithoutProjectingThem()throws Exception{
        loseReply=true;assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_RUNTIME));loseReply=false;wrongBinding=true;
        Path job=root.resolve("controller-job-"+spec.id()+".json");byte[] before=Files.readAllBytes(job);
        assertEquals("BOOTSTRAP_NATIVE_RECEIPT_MISMATCH",assertThrows(ControlFailure.class,()->driver.inspectSaved(frozen,SOURCE_RUNTIME)).code);
        assertArrayEquals(before,Files.readAllBytes(job));assertEquals(List.of("dispatch","status"),commands);
    }
    @Test void retainedDependencyInspectionUsesTheOriginalIncompleteChildAndHost()throws Exception{
        prepareDependencies();dependencies.advance(frozen,ControlBootstrapJournal.DependencyOperation.KEY);
        loseReply=true;assertThrows(ControlFailure.class,()->dependencies.advance(frozen,ControlBootstrapJournal.DependencyOperation.SOURCE));loseReply=false;
        var header=new LinkedHashMap<>(sent.getLast());header.remove("agent");String agent=programs.getLast();
        byte[] before=Files.readAllBytes(root.resolve("controller-job-"+spec.id()+".json"));
        var result=driver.inspectSaved(frozen,DEPENDENCY_LINK);
        assertEquals("SOURCE",result.get("inspectionComponent"));assertEquals("AUTHORIZED",result.get("state"));
        assertEquals(header,sent.getLast());assertEquals(agent,programs.getLast());assertEquals("status",commands.getLast());
        assertArrayEquals(before,Files.readAllBytes(root.resolve("controller-job-"+spec.id()+".json")));
        // Inspecting a success must not project it: the next query observes the
        // same SOURCE child, rather than authorizing CONNECT.
        assertEquals("SOURCE",driver.inspectSaved(frozen,DEPENDENCY_LINK).get("inspectionComponent"));
    }
    ControlBootstrapNativeInputs.Sealed retainedInput(UUID id,ControlBootstrapJournal.Phase phase,boolean source,Map<String,Object> header){
        String agent="# original sealed fixture agent\n";var h=new LinkedHashMap<>(header);h.put("approvalDigest",approval());h.put("agentSha256",ControlBootstrapNativeJson.sha(agent));
        return inputs.prepare(id,phase,approval(),source?spec.sourceServerId():spec.targetServerId(),source?1:2,source?"source-route":"target-route",true,
                ()->new ControlBootstrapNativeInputs.Prepared(h,agent));
    }
    Map<String,byte[]> privateSnapshot()throws Exception{
        var result=new TreeMap<String,byte[]>();try(var paths=Files.list(root)){
            for(var p:paths.filter(Files::isRegularFile).toList())result.put(p.getFileName().toString(),Files.readAllBytes(p));
        }return result;
    }
    void assertPrivateSnapshot(Map<String,byte[]> expected)throws Exception{
        var actual=privateSnapshot();assertEquals(expected.keySet(),actual.keySet());expected.forEach((key,bytes)->assertArrayEquals(bytes,actual.get(key),key));
    }
    void returnsOnlyOriginalStatus(ControlBootstrapNativeInputs.Sealed input,boolean source,List<String> args,Map<String,Object> result)throws Exception{
        doAnswer(inv->{
            assertEquals((source?spec.sourceServerId():spec.targetServerId()).toString(),((Map<?,?>)inv.getArgument(0)).get("id"));
            assertEquals(input.agent(),inv.getArgument(1));assertEquals(args,inv.getArgument(2));assertEquals(45,(int)inv.getArgument(4));
            var raw=new ByteArrayOutputStream();((ControlSsh.Upload)inv.getArgument(3)).write(raw);
            assertEquals(ControlBootstrapNativeJson.encode(input.header())+"\n",raw.toString(java.nio.charset.StandardCharsets.UTF_8));return result;
        }).when(ssh).call(any(),anyString(),anyList(),any(),anyInt());
    }
    @Test void controllerVerifyInspectionLoadsConfigurationInputAndCannotPublishItsResult()throws Exception{
        UUID id=UUID.randomUUID(),verifyId=UUID.randomUUID();var input=retainedInput(spec.id(),CONTROLLER_CONFIG,false,Map.of("id",id.toString(),"verifyId",verifyId.toString()));
        var receipt=new LinkedHashMap<String,Object>(Map.of("protocol","eatwhat-controller-bootstrap-controller-v1","operation","VERIFY","id",verifyId.toString(),
                "configurationId",id.toString(),"binding",input.binding(),"state","VERIFIED"));
        returnsOnlyOriginalStatus(input,false,List.of("status","VERIFY"),receipt);var before=privateSnapshot();
        assertEquals("VERIFY",driver.inspectSaved(frozen,CONTROLLER_VERIFY).get("inspectionComponent"));assertPrivateSnapshot(before);
        receipt.put("configurationId",UUID.randomUUID().toString());
        assertEquals("BOOTSTRAP_NATIVE_RECEIPT_MISMATCH",assertThrows(ControlFailure.class,()->driver.inspectSaved(frozen,CONTROLLER_VERIFY)).code);assertPrivateSnapshot(before);
        verify(admission,never()).beforeInitialIntent(any(),any());verify(admission,never()).beforeMembershipIntent(any(),any());
    }
    @Test void businessInspectionRejectsAnotherServerEvenWithTheOriginalInputBinding()throws Exception{
        UUID id=UUID.randomUUID();var input=retainedInput(spec.id(),BUSINESS_INSTALL,false,Map.of("id",id.toString()));
        var receipt=new LinkedHashMap<String,Object>(Map.of("protocol","eatwhat-install-v1","bootstrapProtocol","eatwhat-controller-bootstrap-business-v1","operation","INSTALL",
                "jobId",id.toString(),"serverId",spec.targetServerId().toString(),"binding",input.binding(),"state","SUCCEEDED"));
        returnsOnlyOriginalStatus(input,false,List.of("status"),receipt);var before=privateSnapshot();
        assertEquals("INSTALL",driver.inspectSaved(frozen,BUSINESS_INSTALL).get("inspectionComponent"));assertPrivateSnapshot(before);
        receipt.put("serverId",spec.sourceServerId().toString());
        assertEquals("BOOTSTRAP_NATIVE_RECEIPT_MISMATCH",assertThrows(ControlFailure.class,()->driver.inspectSaved(frozen,BUSINESS_INSTALL)).code);assertPrivateSnapshot(before);
    }
    @Test void memberInspectionUsesTheLatestSavedComponentWithoutPreparingItsSuccessor()throws Exception{
        String[][] kinds={{"MEMBER_TLS_RELAY","eatwhat-controller-bootstrap-member-relay-v1","RELAY_READY","TARGET"},
                {"MEMBER_AUTH_ROLE","eatwhat-controller-bootstrap-member-auth-v1","ROLE_READY","SOURCE"},
                {"MEMBER_HTTPS_INGRESS","eatwhat-controller-bootstrap-member-ingress-v1","HTTPS_READY","TARGET"},
                {"MEMBER_RUNTIME","eatwhat-member-configure-v1","CONFIGURED","TARGET"}};
        for(var kind:kinds){
            UUID id=UUID.nameUUIDFromBytes((spec.id()+":"+kind[0]+"_INPUTS").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            boolean source=kind[3].equals("SOURCE");var input=retainedInput(id,MEMBER_CONFIGURE,source,Map.of("id",UUID.randomUUID().toString()));
            var receipt=new LinkedHashMap<String,Object>(Map.of("protocol",kind[1],"id",input.header().get("id"),"serverId",spec.targetServerId().toString(),"state",kind[2]));
            if(!kind[0].equals("MEMBER_RUNTIME")){receipt.put("binding",input.binding());receipt.put("operation",source?"CREATE":"START");}
            returnsOnlyOriginalStatus(input,source,List.of("status"),receipt);var before=privateSnapshot();
            assertEquals(kind[0],driver.inspectSaved(frozen,MEMBER_CONFIGURE).get("inspectionComponent"));assertPrivateSnapshot(before);
            receipt.put("id",UUID.randomUUID().toString());
            assertEquals("BOOTSTRAP_NATIVE_RECEIPT_MISMATCH",assertThrows(ControlFailure.class,()->driver.inspectSaved(frozen,MEMBER_CONFIGURE)).code);assertPrivateSnapshot(before);
        }
        verify(admission,never()).beforeInitialIntent(any(),any());verify(admission,never()).beforeMembershipIntent(any(),any());
    }
    @Test void missingSealedInputAfterClaimDoesNotRebuildOrCallSsh()throws Exception{
        loseReply=true;assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_RUNTIME));Files.delete(nativePath(SOURCE_RUNTIME));restart();
        assertEquals("BOOTSTRAP_NATIVE_INPUT_MISSING",assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_RUNTIME)).code);assertEquals(1,sent.size());
    }
    @Test void forgedBindingCannotAdvanceAndNextCallOnlyObserves(){
        wrongBinding=true;assertEquals("BOOTSTRAP_NATIVE_RECEIPT_MISMATCH",assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_RUNTIME)).code);
        assertEquals("OUTCOME_UNKNOWN",journal.job(spec.id()).get("state"));wrongBinding=false;driver.advance(frozen,SOURCE_RUNTIME);assertEquals(List.of("dispatch","status"),commands);
    }
    @Test void changedRouteBeforeObservationCannotContactAnotherHost(){
        loseReply=true;assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_RUNTIME));doReturn("changed-route").when(ssh).routeBinding(any());
        assertEquals("SSH_ROUTE_BINDING_CHANGED",assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_RUNTIME)).code);assertEquals(1,sent.size());
    }
    @Test void failedActualAdmissionLeavesSealedInputsButNeverDispatchesOrClaims(){
        when(admission.beforeInitialIntent(eq(frozen),any())).thenThrow(new ControlFailure(503,"DEPENDENCY_UNAVAILABLE"));
        assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_RUNTIME));assertTrue(sent.isEmpty());assertTrue(Files.exists(nativePath(SOURCE_RUNTIME)));
        assertEquals("QUEUED",journal.job(spec.id()).get("state"));
    }
    @Test void stateProbeRunsBeforeClaimAndAStaleInspectionCannotBeRebuiltAfterDispatch(){
        for(var phase:List.of(SOURCE_RUNTIME,TARGET_RUNTIME,SOURCE_PRIVATE_PEER,TARGET_PRIVATE_PEER))driver.advance(frozen,phase);
        loseReply=true;assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_SHARED_STATE));int n=commands.size();restart();loseReply=false;
        driver.advance(frozen,SOURCE_SHARED_STATE);assertEquals(n+1,commands.size());assertEquals("status",commands.getLast());
        assertEquals(1,commands.stream().filter("metadata"::equals).count());assertEquals(1,commands.stream().filter("inspect"::equals).count());
    }
    @Test void allNativeNetworkDriversProduceHeadersAcceptedByTheActualBundledPythonPrograms()throws Exception{
        for(var phase:List.of(SOURCE_RUNTIME,TARGET_RUNTIME,SOURCE_PRIVATE_PEER,TARGET_PRIVATE_PEER,SOURCE_SHARED_STATE,TARGET_SHARED_STATE))driver.advance(frozen,phase);
        assertEquals("DEPENDENCY_LINK",journal.job(spec.id()).get("stage"));
        assertEquals(targetKey,sent.get(2).get("peerPublicKey"));assertEquals(sourceKey,sent.get(3).get("peerPublicKey"));
        assertEquals(programs.get(0),programs.get(1));assertEquals(programs.get(2),programs.get(3));
        for(var op:ControlBootstrapJournal.DependencyOperation.values())dependencies.advance(frozen,op);
        assertEquals("CONTROLLER_CONFIG",journal.job(spec.id()).get("stage"));
        for(int i=0;i<sent.size();i++)if(commands.get(i).equals("dispatch")){
            var payload=sent.get(i);String agent=programs.get(i);
            String validator="import json,sys,hashlib\nx=json.load(sys.stdin);n={'__name__':'sealed_protocol_test'};exec(compile(x['agent'],'<sealed-agent>','exec'),n)\n"
                    +"d=x['payload'];n['validate'](d,'dispatch' if 'ownerUid' in d or 'stateId' in d else True)\n"
                    +"h={k:v for k,v in d.items() if k!='agent'};b=hashlib.sha256(json.dumps(h,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()).hexdigest()\n"
                    +"assert n['fields'](h)['binding']==b==x['binding'];print('VALID')\n";
            var header=new LinkedHashMap<>(payload);header.remove("agent");
            byte[] raw=JsonMapper.builder().build().writeValueAsBytes(Map.of("agent",agent,"payload",payload,"binding",ControlBootstrapNativeJson.digest(header)));
            assertEquals("VALID",new String(ControlSsh.process(List.of("/usr/bin/python3","-I","-c",validator),Map.of(),out->out.write(raw),20,1024,true)).strip());
        }
        String disk=Files.readString(nativePath(SOURCE_SHARED_STATE));assertFalse(disk.contains("eatwhat.invalid"));assertFalse(disk.contains("import base64"));
        assertFalse(journal.job(spec.id()).toString().contains("ownerUid"));assertFalse(journal.job(spec.id()).toString().contains("agentSha256"));
    }
    @Test void anotherPhaseCiphertextCannotReplaceTheOriginalInputs()throws Exception{
        driver.advance(frozen,SOURCE_RUNTIME);driver.advance(frozen,TARGET_RUNTIME);
        Files.copy(nativePath(TARGET_RUNTIME),nativePath(SOURCE_RUNTIME),StandardCopyOption.REPLACE_EXISTING);int n=sent.size();
        assertEquals("BOOTSTRAP_NATIVE_INPUT_CHANGED",assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_RUNTIME)).code);assertEquals(n,sent.size());
    }
    @Test void journalStillRejectsFutureStagesWithoutTheirNativeDrivers(){
        assertEquals("BOOTSTRAP_NATIVE_DRIVER_REQUIRED",assertThrows(ControlFailure.class,()->driver.advance(frozen,BUSINESS_INSTALL)).code);assertTrue(sent.isEmpty());
    }
    @Test void sealedInputPersistenceFailureCannotReachTheIntentBoundaryOrSsh(){
        var failingStore=mock(ControlServerStore.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(store).defaultAnswer(CALLS_REAL_METHODS));
        doThrow(new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE")).when(failingStore).saveJson(eq(nativePath(SOURCE_RUNTIME)),any());
        driver=new ControlBootstrapNativePhases(store,journal,admission,claims,new ControlBootstrapNativeInputs(failingStore),ssh);
        assertThrows(ControlFailure.class,()->driver.advance(frozen,SOURCE_RUNTIME));verify(admission,never()).beforeInitialIntent(any(),any());
        assertTrue(sent.isEmpty());assertEquals("QUEUED",journal.job(spec.id()).get("state"));
    }
    void prepareDependencies(){for(var phase:List.of(SOURCE_RUNTIME,TARGET_RUNTIME,SOURCE_PRIVATE_PEER,TARGET_PRIVATE_PEER,SOURCE_SHARED_STATE,TARGET_SHARED_STATE))driver.advance(frozen,phase);}
    @Test void memberAndAdminPortsStayInTheOriginalKeyHeaderThroughSourceAndConnect(){
        var forwards=new ArrayList<>(network.forwards(frozen));forwards.add(Map.of("purpose","MEMBER_HTTP_1","localPort",28100,"remotePort",28091));forwards.add(Map.of("purpose","SOURCE_ADMIN_SSH","localPort",28300,"remotePort",22));
        when(network.forwards(frozen)).thenReturn(forwards);prepareDependencies();dependencies.advance(frozen,ControlBootstrapJournal.DependencyOperation.KEY);
        var original=sent.getLast().get("forwards");when(network.forwards(frozen)).thenThrow(new AssertionError("SOURCE and CONNECT must not generate a new port plan"));
        dependencies.advance(frozen,ControlBootstrapJournal.DependencyOperation.SOURCE);assertEquals(original,sent.getLast().get("forwards"));
        dependencies.advance(frozen,ControlBootstrapJournal.DependencyOperation.CONNECT);assertEquals(original,sent.getLast().get("forwards"));
        assertEquals(original,dependencies.completedTarget(frozen,approval()).header().get("forwards"));
    }
    @Test void lostSourceAuthorizationReplyKeepsKeyAndOriginalAgentForConnect(){
        prepareDependencies();dependencies.advance(frozen,ControlBootstrapJournal.DependencyOperation.KEY);
        var keyHeader=new LinkedHashMap<>(sent.getLast());keyHeader.remove("agent");String keyAgent=programs.getLast();
        loseReply=true;assertThrows(ControlFailure.class,()->dependencies.advance(frozen,ControlBootstrapJournal.DependencyOperation.SOURCE));
        var sourceHeader=new LinkedHashMap<>(sent.getLast());sourceHeader.remove("agent");restart();loseReply=false;
        dependencies.advance(frozen,ControlBootstrapJournal.DependencyOperation.SOURCE);assertEquals("status",commands.getLast());assertEquals(sourceHeader,sent.getLast());
        dependencies.advance(frozen,ControlBootstrapJournal.DependencyOperation.CONNECT);
        var connectHeader=new LinkedHashMap<>(sent.getLast());connectHeader.remove("agent");assertEquals(keyHeader,connectHeader);assertEquals(keyAgent,programs.getLast());
        assertEquals("CONTROLLER_CONFIG",journal.job(spec.id()).get("stage"));assertEquals(1,commands.stream().filter("host-key"::equals).count());
    }
    @Test void missingKeyInputPreventsConnectAndDoesNotGenerateAnotherKey()throws Exception{
        prepareDependencies();dependencies.advance(frozen,ControlBootstrapJournal.DependencyOperation.KEY);dependencies.advance(frozen,ControlBootstrapJournal.DependencyOperation.SOURCE);
        Files.delete(root.resolve("controller-native-"+spec.id()+"-DEPENDENCY_LINK-"+spec.targetServerId()+".json"));int n=sent.size();restart();
        assertThrows(ControlFailure.class,()->dependencies.advance(frozen,ControlBootstrapJournal.DependencyOperation.CONNECT));assertEquals(n,sent.size());
    }
}
