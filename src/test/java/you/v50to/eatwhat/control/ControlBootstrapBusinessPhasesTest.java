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

/** Real encrypted originals, journal/child claims, projection and restart
 * recovery. Authority allocation and remote SSH/native results are explicit
 * mocks; these tests cannot prove a real Docker installation or JOIN. */
class ControlBootstrapBusinessPhasesTest {
    @TempDir static Path runtimePki;
    static String runtimeCa;
    @BeforeAll static void runtimeCertificate()throws Exception{
        Path keytool=Path.of(System.getProperty("java.home"),"bin/keytool"),store=runtimePki.resolve("test.p12"),ca=runtimePki.resolve("ca.pem");
        for(var args:List.of(List.of("-genkeypair","-alias","ca","-keyalg","EC","-groupname","secp256r1","-dname","CN=Runtime Fixture CA","-ext","bc:c","-validity","2","-storetype","PKCS12","-keystore",store.toString(),"-storepass","fixture-only-password","-noprompt"),
                List.of("-exportcert","-rfc","-alias","ca","-keystore",store.toString(),"-storepass","fixture-only-password","-file",ca.toString()))){
            var cmd=new ArrayList<>(List.of(keytool.toString()));cmd.addAll(args);var p=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            assertTrue(p.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,p.exitValue());
        }runtimeCa=Files.readString(ca);
    }
    class RuntimeHarness {
        ControlBootstrapMemberRuntime runtime;ControlBootstrapMemberRelay relay=stub(ControlBootstrapMemberRelay.class);
        ControlBootstrapMemberAuth auth=stub(ControlBootstrapMemberAuth.class);ControlBootstrapMemberIngress ingress=stub(ControlBootstrapMemberIngress.class);
        int inspections,dispatches,verifies;boolean loseDispatch;String state="CONFIGURED";Map<String,Object> changes=Map.of();
        RuntimeHarness()throws Exception{
            var ah=new AuthHarness();ah.auth.advance(f);ah.auth.advance(f);
            String agent="inert owned ingress fixture";
            inputs.prepare(derived("MEMBER_HTTPS_INGRESS_INPUTS"),MEMBER_CONFIGURE,approval,f.spec().targetServerId(),f.spec().targetRevision(),f.targetRoute(),true,
                ()->new ControlBootstrapNativeInputs.Prepared(Map.of("id",derived("MEMBER_HTTPS_INGRESS").toString(),"approvalDigest",approval,"agentSha256",ControlBootstrapNativeJson.sha(agent)),agent));
            when(auth.ready(f)).thenReturn(Map.of("state","ROLE_READY"));when(ingress.ready(f)).thenReturn(Map.of("state","PEER_VERIFIED"));when(relay.ready(f)).thenReturn(Map.of("state","RELAY_READY"));
            var material=Map.<String,Object>of("caPem",runtimeCa,"config",Map.of("listenAddress","172.19.0.1","databaseName","eatwhat","databaseUsername","eatwhat_auth_"+f.spec().targetServerId().toString().replace("-","")));
            when(relay.materials(f)).thenReturn(material);
            reset(ssh);String targetRoute=f.targetRoute();when(ssh.routeBinding(anyMap())).thenReturn(targetRoute);restart();
            doAnswer(inv->{
                var bytes=new ByteArrayOutputStream();((ControlSsh.Upload)inv.getArgument(3)).write(bytes);var h=JsonMapper.builder().build().readValue(bytes.toByteArray(),LinkedHashMap.class);
                Object code=h.remove("agent");String cmd=((List<String>)inv.getArgument(2)).getFirst();assertEquals(f.spec().targetServerId().toString(),((Map<?,?>)inv.getArgument(0)).get("id"));
                if(cmd.equals("verify-joined")){
                    assertEquals(120,(int)inv.getArgument(4));assertEquals(saved().header(),h.get("original"));assertEquals(saved().agent(),inv.getArgument(1));var proof=(Map<String,Object>)h.get("joinProof");
                    var members=List.of(JsonMapper.builder().build().convertValue(proof.get("members"),you.v50to.eatwhat.cluster.ClusterMember[].class));
                    var plan=new you.v50to.eatwhat.cluster.ClusterMembershipCoordinator.Plan(f.spec().clusterId(),UUID.fromString(proof.get("changeId").toString()),f.spec().epoch(),f.spec().epoch()+1,"JOIN",ControlBootstrapBusinessAllocation.identity(f.spec()).siteId(),members,members);
                    var joined=new LinkedHashMap<String,Object>();for(String key:List.of("id","serverId","siteId","instanceId","clusterId","allocationSlot","artifactSha256","databaseFingerprint"))joined.put(key,saved().header().get(key));
                    for(String key:List.of("bootstrapId","changeId","epoch","membershipBinding"))joined.put(key,proof.get(key));
                    joined.put("protocol","eatwhat-member-joined-runtime-v1");joined.put("state","JOINED_VERIFIED");joined.put("clusterMembership","ACTIVE");joined.put("healthCheckedAt",java.time.Instant.now().toString());joined.put("planHash",ControlBootstrapNativeJson.sha(JsonMapper.builder().build().writeValueAsString(plan)));
                    for(String key:List.of("configurationRevalidated","authStoreVerified","localMembershipVerified","dataPreserved","nativeServicesRevalidated","networkBaselineRevalidated","applicationReady","databaseReady","redisReady","storageReadCheckVerified"))joined.put(key,true);
                    joined.put("storageWriteProbePerformed",false);joined.put("joinAuthorized",false);joined.putAll(changes);return joined;
                }
                var out=new LinkedHashMap<String,Object>(Map.of("protocol","eatwhat-member-configure-v1","id",h.get("id"),"serverId",h.get("serverId")));
                if(cmd.equals("inspect")){inspections++;out.putAll(Map.of("ready",true,"freezeHash","c".repeat(64),"databaseFingerprint","f".repeat(32)));return out;}
                assertEquals(saved().header(),h);assertEquals(saved().agent(),inv.getArgument(1));
                if(cmd.equals("dispatch")){dispatches++;assertEquals(saved().agent(),code);if(loseDispatch)throw new IOException("synthetic lost acknowledgement");}
                out.put("state",state);
                if(cmd.equals("verify")){verifies++;out.putAll(Map.of("configurationRevalidated",true,"authStoreVerified",true,"dataPreserved",true,"clusterMembership","NOT_JOINED","joinAuthorized",false));
                    for(String k:List.of("siteId","instanceId","clusterId","allocationSlot","artifactSha256","databaseFingerprint"))out.put(k,h.get(k));out.putAll(changes);}
                return out;
            }).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
        }
        UUID derived(String suffix){return UUID.nameUUIDFromBytes((f.spec().id()+":"+suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        ControlBootstrapNativeInputs.Sealed saved(){return inputs.load(derived("MEMBER_RUNTIME_INPUTS"),MEMBER_CONFIGURE,approval,f.spec().targetServerId(),f.spec().targetRevision(),f.targetRoute());}
        void restart(){runtime=new ControlBootstrapMemberRuntime(store,journal,nativePhases,claims,inputs,relay,auth,ingress);}
    }
    @Test void originalCredentialsAndVerifiedRelayCreateRuntimeButNeverJoin()throws Exception{
        var h=new RuntimeHarness();var result=h.runtime.advance(f);assertEquals("CONFIGURED",result.get("memberRuntimeState"));assertEquals(true,result.get("controllerProfilesPending"));assertEquals(true,result.get("clusterJoinPending"));
        var input=h.saved();var payload=(Map<?,?>)input.header().get("runtime");var properties=(Map<?,?>)payload.get("properties");
        assertEquals("https://172.19.0.1:28443",properties.get("app.cluster.authority-origin"));assertTrue(properties.get("app.cluster.auth-store.jdbc-url").toString().endsWith("&channelBinding=disable"));assertEquals(runtimeCa,payload.get("authCaPem"));
        var original=inputs.load(h.derived("MEMBER_AUTH_ROLE_INPUTS"),MEMBER_CONFIGURE,approval,f.spec().sourceServerId(),f.spec().sourceRevision(),f.sourceRoute());
        assertEquals(((Map<?,?>)original.header().get("role")).get("password"),payload.get("authPassword"));
        assertFalse(result.toString().contains(payload.get("authPassword").toString()));assertFalse(input.toString().contains(runtimeCa));
        var steps=(List<Map<String,Object>>)journal.job(f.spec().id()).get("steps");assertEquals("DISPATCHED",steps.get(MEMBER_CONFIGURE.ordinal()).get("state"));assertEquals("PENDING",steps.get(MEMBER_JOIN.ordinal()).get("state"));
        h.runtime.ready(f);assertEquals(2,h.verifies);assertEquals(1,h.dispatches);
    }
    @Test void lostRuntimeDispatchIsObservedWithoutRetryEvenWhenNotStarted()throws Exception{
        var h=new RuntimeHarness();h.loseDispatch=true;assertThrows(ControlFailure.class,()->h.runtime.advance(f));var original=h.saved();h.restart();h.state="NOT_STARTED";
        assertEquals("CLAIMED",h.runtime.advance(f).get("memberRuntimeState"));h.runtime.advance(f);assertEquals(1,h.dispatches);assertEquals(original,h.saved());assertEquals(0,h.verifies);
    }
    @Test void runtimeMissingLedgerAndNewAdmissionFailureCannotCauseAnotherDispatch()throws Exception{
        var h=new RuntimeHarness();h.state="ACCEPTED";h.runtime.advance(f);Files.delete(store.directory().resolve("controller-member-runtime-"+f.spec().id()+".json"));h.restart();
        assertEquals("BOOTSTRAP_MEMBER_RUNTIME_RECORD_MISSING",assertThrows(ControlFailure.class,()->h.runtime.advance(f)).code);assertEquals(1,h.inspections);assertEquals(1,h.dispatches);
    }
    @Test void runtimeClaimRequiresCurrentAdministratorAndDependencyProof()throws Exception{
        var h=new RuntimeHarness();when(admission.beforeMembershipIntent(eq(f),any())).thenThrow(new ControlFailure(403,"ADMIN_REQUIRED"));
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->h.runtime.advance(f)).code);assertEquals(0,h.dispatches);
    }
    @Test void configuredReceiptNeedsIndependentFreshRuntimeProof()throws Exception{
        var h=new RuntimeHarness();h.changes=Map.of("authStoreVerified",false);assertThrows(ControlFailure.class,()->h.runtime.advance(f));assertEquals(1,h.dispatches);
        h.changes=Map.of("databaseFingerprint","0".repeat(32));assertThrows(ControlFailure.class,()->h.runtime.advance(f));assertEquals(1,h.dispatches);
        h.changes=Map.of();assertEquals("CONFIGURED",h.runtime.advance(f).get("memberRuntimeState"));assertEquals(1,h.dispatches);
    }
    @Test void relayMaterialCannotIntroducePublicOrNonCanonicalGateway()throws Exception{
        var h=new RuntimeHarness();var material=Map.<String,Object>of("caPem",runtimeCa,"config",Map.of("listenAddress","8.8.8.8","databaseName","eatwhat","databaseUsername","eatwhat_auth_"+f.spec().targetServerId().toString().replace("-","")));
        when(h.relay.materials(f)).thenReturn(material);
        assertEquals("BOOTSTRAP_MEMBER_RUNTIME_ROUTE_INVALID",assertThrows(ControlFailure.class,()->h.runtime.advance(f)).code);assertEquals(0,h.inspections);assertEquals(0,h.dispatches);
    }
    @Test void runtimePayloadIsAcceptedByTheActualBundledPythonValidator()throws Exception{
        var h=new RuntimeHarness();h.runtime.advance(f);var input=h.saved();
        String code="import json,sys,types\nx=json.load(sys.stdin)\nm=types.ModuleType('runtime_validator')\nexec(compile(x['agent'],'<reviewed-runtime>','exec'),m.__dict__)\nm.runtime(x['header'])\nprint('OK')\n";
        var p=new ProcessBuilder("python3","-I","-B","-c",code).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try(var out=p.getOutputStream()){out.write(JsonMapper.builder().build().writeValueAsBytes(Map.of("agent",input.agent(),"header",input.header())));}
        assertTrue(p.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,p.exitValue());assertEquals("OK",new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip());
    }
    @Test void joinedRuntimeUsesSavedAgentAndRejectsStaleOrPartialAcknowledgements()throws Exception{
        var h=new RuntimeHarness();h.runtime.advance(f);var authority=f.sourceRequest().expected();var members=List.of(new you.v50to.eatwhat.cluster.ClusterMember(authority.authoritySiteId(),authority.authorityInstanceId(),"CAMPUS",0),ControlBootstrapBusinessAllocation.identity(f.spec()));
        var proof=Map.<String,Object>of("bootstrapId",f.spec().id().toString(),"changeId",ControlBootstrapJournal.phaseId(f.spec().id(),MEMBER_JOIN).toString(),"baseEpoch",f.spec().epoch(),"epoch",f.spec().epoch()+1,"databaseFingerprint",h.saved().header().get("databaseFingerprint"),"members",members,"membershipBinding","a".repeat(43)+"=");
        assertEquals("JOINED_VERIFIED",h.runtime.joined(f,proof).get("state"));assertEquals(1,h.dispatches);
        for(var change:List.of(Map.of("authStoreVerified",false),Map.of("storageReadCheckVerified",false),Map.of("databaseReady",false),Map.of("storageWriteProbePerformed",true),Map.of("planHash","0".repeat(64)),Map.of("healthCheckedAt",java.time.Instant.now().minusSeconds(90).toString()))){h.changes=(Map)change;assertThrows(ControlFailure.class,()->h.runtime.joined(f,proof));}
        h.changes=Map.of();var different=new HashMap<>(proof);different.put("epoch",f.spec().epoch()+2);assertThrows(ControlFailure.class,()->h.runtime.joined(f,different));assertEquals(1,h.dispatches);
    }
    class IngressHarness {
        ControlBootstrapMemberIngress ingress;ControlBootstrapMemberRelay relay;ControlBootstrapMemberAuth auth;
        int inspections,prepares,starts,sourceProbes;boolean prepared,losePrepare,loseStart,badCa;String state="HTTPS_READY";Map<String,Object> peerChanges=Map.of();
        final String ca="-----BEGIN CERTIFICATE-----\npublic-ingress-fixture\n-----END CERTIFICATE-----\n";
        IngressHarness()throws Exception{
            var r=new RelayHarness();r.relay.advance(f);r.relay.advance(f);relay=r.relay;
            auth=mock(ControlBootstrapMemberAuth.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));doReturn(Map.of("state","ROLE_READY")).when(auth).ready(f);
            when(f.sourceRoute()).thenReturn("source-route");
            store.mutate(data->{var row=new LinkedHashMap<String,Object>(fixture.sourceServer);row.put("username","ubuntu");row.put("privilege","SUDO");((Map<String,Object>)data.get("servers")).put(f.spec().sourceServerId().toString(),row);return null;});
            when(ssh.routeBinding(anyMap())).thenAnswer(inv->f.spec().sourceServerId().toString().equals(((Map<?,?>)inv.getArgument(0)).get("id"))?f.sourceRoute():f.targetRoute());restart();
            doAnswer(inv->{
                var bytes=new ByteArrayOutputStream();((ControlSsh.Upload)inv.getArgument(3)).write(bytes);var h=JsonMapper.builder().build().readValue(bytes.toByteArray(),LinkedHashMap.class);
                String command=((List<String>)inv.getArgument(2)).getFirst();Object agent=h.remove("agent");
                if(h.containsKey("businessId"))return new LinkedHashMap<String,Object>(Map.ofEntries(Map.entry("protocol",ControlBootstrapMemberRelay.PROTOCOL),Map.entry("id",h.get("id")),Map.entry("serverId",h.get("serverId")),
                    Map.entry("operation","START"),Map.entry("binding",ControlBootstrapNativeJson.digest(h)),Map.entry("state","RELAY_READY"),Map.entry("networkId","2".repeat(64)),Map.entry("caSha256","a".repeat(64)),Map.entry("materialManifestSha256","b".repeat(64)),
                    Map.entry("tlsListenerVerified",true),Map.entry("authorityHttpVerified",true),Map.entry("databaseAuthenticated",false),Map.entry("memberConfigured",false),Map.entry("joinAuthorized",false)));
                String op=command.equals("inspect")?"INSPECT":command.startsWith("prepare")?"PREPARE":command.equals("materials")?"MATERIALS":command.equals("verify-source")?"VERIFY_SOURCE":"START";
                var result=new LinkedHashMap<String,Object>(Map.of("protocol",ControlBootstrapMemberIngress.PROTOCOL,"id",h.get("id"),"serverId",h.get("serverId"),"operation",op,"binding",ControlBootstrapNativeJson.digest(h)));
                if(command.equals("inspect")){inspections++;result.putAll(Map.of("effectsStarted",false,"proof","a".repeat(64)));return result;}
                if(command.equals("verify-source")){
                    assertEquals(f.spec().sourceServerId().toString(),((Map<?,?>)inv.getArgument(0)).get("id"));sourceProbes++;
                    assertEquals(ca,h.remove("caPem"));assertEquals(ControlBootstrapNativeJson.sha(ca),h.remove("caSha256"));assertEquals(saved().header(),h);
                    result.putAll(Map.of("state","PEER_VERIFIED","sourceRouteVerified",true,"targetTlsVerified",true,"healthReady",true,"anonymousSnapshotDenied",true,"memberConfigured",false,"joinAuthorized",false));result.putAll(peerChanges);return result;
                }
                assertEquals(f.spec().targetServerId().toString(),((Map<?,?>)inv.getArgument(0)).get("id"));assertEquals(saved().header(),h);assertEquals(saved().agent(),inv.getArgument(1));
                if(command.equals("prepare")){prepares++;assertEquals(saved().agent(),agent);prepared=true;if(losePrepare)throw new IOException("private lost prepare");}
                if(op.equals("PREPARE")){result.put("state",prepared?"PREPARED":"NOT_PREPARED");return result;}
                if(command.equals("dispatch")){starts++;if(loseStart)throw new IOException("private lost start");}
                result.put("state",state);
                if(command.equals("materials")){String value=badCa?ca+"changed":ca;result.putAll(Map.of("caPem",value,"caSha256",ControlBootstrapNativeJson.sha(value),"targetAddress","10.253.74.3"));return result;}
                if(state.equals("HTTPS_READY"))result.putAll(Map.of("networkId","2".repeat(64),"targetAddress","10.253.74.3","caSha256",ControlBootstrapNativeJson.sha(ca),
                    "tlsListenerVerified",true,"healthReady",true,"anonymousSnapshotDenied",true,"memberConfigured",false,"joinAuthorized",false,"acceptedAt",1792300000.25));return result;
            }).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
        }
        void restart(){ingress=new ControlBootstrapMemberIngress(store,journal,nativePhases,claims,inputs,relay,auth);}
        ControlBootstrapNativeInputs.Sealed saved(){return inputs.load(UUID.nameUUIDFromBytes((f.spec().id()+":MEMBER_HTTPS_INGRESS_INPUTS").getBytes(java.nio.charset.StandardCharsets.UTF_8)),MEMBER_CONFIGURE,approval,f.spec().targetServerId(),f.spec().targetRevision(),f.targetRoute());}
    }
    @Test void privateIngressKeepsParentPendingAndVerifiesActualPeerThroughPinnedSourceRoute()throws Exception{
        var h=new IngressHarness();h.ingress.advance(f);var result=h.ingress.advance(f);assertEquals(Map.of("PREPARE","SUCCEEDED","START","SUCCEEDED"),result.get("memberIngressOperations"));
        assertEquals(true,result.get("memberConfigurationPending"));assertEquals("PENDING",((List<Map<String,Object>>)result.get("steps")).get(MEMBER_JOIN.ordinal()).get("state"));
        assertEquals("PEER_VERIFIED",h.ingress.ready(f).get("state"));assertEquals(1,h.sourceProbes);assertEquals(1,h.prepares);assertEquals(1,h.starts);assertFalse(result.toString().contains("caPem"));
    }
    @Test void ingressLostRepliesCanOnlyObserveTheOriginalPreparationAndStart()throws Exception{
        var h=new IngressHarness();h.losePrepare=true;assertThrows(ControlFailure.class,()->h.ingress.advance(f));var input=h.saved();h.restart();h.ingress.advance(f);
        h.loseStart=true;assertThrows(ControlFailure.class,()->h.ingress.advance(f));h.state="NOT_STARTED";h.restart();h.ingress.advance(f);h.ingress.advance(f);
        assertEquals(input,h.saved());assertEquals(1,h.prepares);assertEquals(1,h.starts);assertThrows(ControlFailure.class,()->h.ingress.ready(f));assertEquals(0,h.sourceProbes);
    }
    @Test void ingressMissingLedgerCannotBeRegeneratedFromRetainedInputs()throws Exception{
        var h=new IngressHarness();h.ingress.advance(f);Files.delete(store.directory().resolve("controller-member-ingress-"+f.spec().id()+".json"));h.restart();
        assertEquals("BOOTSTRAP_MEMBER_INGRESS_RECORD_MISSING",assertThrows(ControlFailure.class,()->h.ingress.advance(f)).code);assertEquals(1,h.inspections);assertEquals(0,h.starts);
    }
    @Test void earlierRoleApprovalCannotGrantPrivateIngressScope()throws Exception{
        var h=new IngressHarness();var restricted=mock(ControlBootstrapJournal.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(journal).defaultAnswer(CALLS_REAL_METHODS));
        var scope=new HashSet<>(ControlBootstrapJournal.PERMISSIONS);scope.remove(ControlBootstrapMemberIngress.PERMISSION);UUID id=f.spec().id();doReturn(scope).when(restricted).approvedPermissions(id);
        var driver=new ControlBootstrapMemberIngress(store,restricted,nativePhases,claims,inputs,h.relay,h.auth);
        assertEquals("BOOTSTRAP_MEMBER_INGRESS_SCOPE_REQUIRED",assertThrows(ControlFailure.class,()->driver.advance(f)).code);assertEquals(0,h.inspections);
    }
    @Test void newIngressStartRequiresCurrentRoleAndFreshAdmissionBeforeForcedClaim()throws Exception{
        var h=new IngressHarness();h.ingress.advance(f);doThrow(new ControlFailure(409,"ROLE_CHANGED")).when(h.auth).ready(f);assertThrows(ControlFailure.class,()->h.ingress.advance(f));assertEquals(0,h.starts);
        doReturn(Map.of("state","ROLE_READY")).when(h.auth).ready(f);when(admission.beforeMembershipIntent(eq(f),any())).thenThrow(new ControlFailure(403,"ADMIN_REQUIRED"));
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->h.ingress.advance(f)).code);assertEquals(0,h.starts);
    }
    @Test void ingressCaAndPeerProofCannotChangeAfterTheOriginalStart()throws Exception{
        var h=new IngressHarness();h.ingress.advance(f);h.ingress.advance(f);h.badCa=true;assertThrows(ControlFailure.class,()->h.ingress.ready(f));assertEquals(0,h.sourceProbes);
        h.badCa=false;h.peerChanges=Map.of("anonymousSnapshotDenied",false);assertThrows(ControlFailure.class,()->h.ingress.ready(f));assertEquals(1,h.sourceProbes);
    }
    class AuthHarness {
        ControlBootstrapMemberAuth auth;ControlBootstrapMemberRelay relay;int inspections,prepares,creates;boolean prepared,losePrepare,loseCreate,relayReady=true;
        String state="ROLE_READY";Map<String,Object> changedReceipt=Map.of();
        AuthHarness()throws Exception{
            var r=new RelayHarness();r.relay.advance(f);r.relay.advance(f);relay=r.relay;
            UUID sourceState=f.sourceRequest().expected().stateId();when(f.stateId()).thenReturn(sourceState);when(f.holderId()).thenReturn(UUID.randomUUID());when(f.generation()).thenReturn(1L);
            when(f.adminBinding()).thenReturn("a".repeat(43)+"=");when(f.sourceRoute()).thenReturn("source-route");
            when(f.sourceEvidence()).thenReturn(Map.of("captureBinding","d".repeat(64)));
            store.mutate(data->{var row=new LinkedHashMap<String,Object>(fixture.sourceServer);row.put("username","ubuntu");row.put("privilege","SUDO");
                ((Map<String,Object>)data.get("servers")).put(f.spec().sourceServerId().toString(),row);return null;});
            when(ssh.routeBinding(anyMap())).thenAnswer(inv->f.spec().sourceServerId().toString().equals(((Map<?,?>)inv.getArgument(0)).get("id"))?f.sourceRoute():f.targetRoute());restart();
            doAnswer(inv->{
                var bytes=new ByteArrayOutputStream();((ControlSsh.Upload)inv.getArgument(3)).write(bytes);
                var h=JsonMapper.builder().build().readValue(bytes.toByteArray(),LinkedHashMap.class);String command=((List<String>)inv.getArgument(2)).getFirst();Object agent=h.remove("agent");
                if(h.containsKey("businessId")){
                    var response=new LinkedHashMap<String,Object>(Map.of("protocol",ControlBootstrapMemberRelay.PROTOCOL,"id",h.get("id"),"serverId",h.get("serverId"),
                        "operation","START","binding",ControlBootstrapNativeJson.digest(h),"state",relayReady?"RELAY_READY":"OUTCOME_UNKNOWN"));
                    if(relayReady)response.putAll(Map.of("networkId","2".repeat(64),"caSha256","a".repeat(64),"materialManifestSha256","b".repeat(64),
                        "tlsListenerVerified",true,"authorityHttpVerified",true,"databaseAuthenticated",false,"memberConfigured",false,"joinAuthorized",false));return response;
                }
                assertEquals(f.spec().sourceServerId().toString(),((Map<?,?>)inv.getArgument(0)).get("id"));
                String op=command.equals("inspect")?"INSPECT":command.startsWith("prepare")?"PREPARE":"CREATE";
                var result=new LinkedHashMap<String,Object>(Map.of("protocol",ControlBootstrapMemberAuth.PROTOCOL,"id",h.get("id"),"serverId",h.get("serverId"),"operation",op,"binding",ControlBootstrapNativeJson.digest(h)));
                if(command.equals("inspect")){inspections++;result.putAll(Map.of("effectsStarted",false,"proof","a".repeat(64),"credentialBinding","b".repeat(64)));return result;}
                assertEquals(saved().header(),h);assertEquals(saved().agent(),inv.getArgument(1));
                if(command.equals("prepare")){prepares++;assertEquals(saved().agent(),agent);prepared=true;if(losePrepare)throw new IOException("private lost prepare");}
                if(op.equals("PREPARE")){result.put("state",prepared?"PREPARED":"NOT_PREPARED");result.putAll(Map.of("credentialBinding","b".repeat(64),"privateCredentialSha256","c".repeat(64)));return result;}
                if(command.equals("dispatch")){creates++;if(loseCreate)throw new IOException("private lost SQL acknowledgement");}
                result.put("state",state);
                if(state.equals("ROLE_READY"))result.putAll(Map.of("policySha256","c".repeat(64),"role","eatwhat_auth_"+f.spec().targetServerId().toString().replace("-",""),
                    "validUntil",((Map<?,?>)h.get("role")).get("validUntil"),"membershipChanged",false,"memberConfigured",false,"joinAuthorized",false));
                result.putAll(changedReceipt);return result;
            }).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
        }
        void restart(){auth=new ControlBootstrapMemberAuth(store,journal,nativePhases,claims,inputs,relay);}
        ControlBootstrapNativeInputs.Sealed saved(){return inputs.load(UUID.nameUUIDFromBytes((f.spec().id()+":MEMBER_AUTH_ROLE_INPUTS").getBytes(java.nio.charset.StandardCharsets.UTF_8)),MEMBER_CONFIGURE,approval,f.spec().sourceServerId(),f.spec().sourceRevision(),f.sourceRoute());}
    }
    @Test void restrictedAuthRoleRunsOnPinnedSourceAndLeavesParentConfigurationJoinPending()throws Exception{
        var h=new AuthHarness();h.changedReceipt=Map.of("acceptedAt",1792300000.25,"finishedAt",1792300001.5);h.auth.advance(f);var result=h.auth.advance(f);h.auth.advance(f);assertEquals(1,h.prepares);assertEquals(1,h.creates);assertEquals(1,h.inspections);
        assertEquals(Map.of("PREPARE","SUCCEEDED","CREATE","SUCCEEDED"),result.get("memberAuthOperations"));assertEquals(true,result.get("memberConfigurationPending"));
        var steps=(List<Map<String,Object>>)result.get("steps");assertEquals("DISPATCHED",steps.get(MEMBER_CONFIGURE.ordinal()).get("state"));assertEquals("PENDING",steps.get(MEMBER_JOIN.ordinal()).get("state"));
        String password=((Map<?,?>)h.saved().header().get("role")).get("password").toString();assertEquals(43,password.length());
        assertFalse(result.toString().contains(password));
        try(var files=Files.list(store.directory())){for(Path file:files.filter(p->p.getFileName().toString().endsWith(".json")).toList())assertFalse(Files.readString(file).contains(password));}
        assertEquals("ROLE_READY",h.auth.ready(f).get("state"));
    }
    @Test void authLostPrepareAndCreateAcknowledgementsNeverRedispatchAfterManagerRestart()throws Exception{
        var h=new AuthHarness();h.losePrepare=true;assertThrows(ControlFailure.class,()->h.auth.advance(f));var original=h.saved();h.restart();h.auth.advance(f);
        h.loseCreate=true;assertThrows(ControlFailure.class,()->h.auth.advance(f));h.state="NOT_STARTED";h.restart();h.auth.advance(f);h.auth.advance(f);
        assertEquals(original,h.saved());assertEquals(1,h.prepares);assertEquals(1,h.creates);assertEquals(1,h.inspections);assertThrows(ControlFailure.class,()->h.auth.ready(f));
    }
    @Test void authOriginalCannotBeRegeneratedAfterDurableClaim()throws Exception{
        var h=new AuthHarness();h.auth.advance(f);var child=UUID.nameUUIDFromBytes((f.spec().id()+":MEMBER_AUTH_ROLE_INPUTS").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.delete(store.directory().resolve("controller-native-"+child+"-MEMBER_CONFIGURE.json"));h.restart();
        assertEquals("BOOTSTRAP_NATIVE_INPUT_MISSING",assertThrows(ControlFailure.class,()->h.auth.advance(f)).code);assertEquals(1,h.inspections);assertEquals(0,h.creates);
    }
    @Test void authMissingOperationLedgerIsNotReinitializedFromRetainedInput()throws Exception{
        var h=new AuthHarness();h.auth.advance(f);var saved=h.saved();Files.delete(store.directory().resolve("controller-member-auth-"+f.spec().id()+".json"));h.restart();
        assertEquals("BOOTSTRAP_MEMBER_AUTH_RECORD_MISSING",assertThrows(ControlFailure.class,()->h.auth.advance(f)).code);assertEquals(saved,h.saved());assertEquals(1,h.prepares);assertEquals(0,h.creates);
    }
    @Test void earlierRelayApprovalDoesNotAuthorizeNewSqlRole()throws Exception{
        var h=new AuthHarness();var restricted=mock(ControlBootstrapJournal.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(journal).defaultAnswer(CALLS_REAL_METHODS));
        var scope=new HashSet<>(ControlBootstrapJournal.PERMISSIONS);scope.remove(ControlBootstrapMemberAuth.PERMISSION);UUID id=f.spec().id();doReturn(scope).when(restricted).approvedPermissions(id);
        var auth=new ControlBootstrapMemberAuth(store,restricted,nativePhases,claims,inputs,h.relay);
        assertEquals("BOOTSTRAP_MEMBER_AUTH_SCOPE_REQUIRED",assertThrows(ControlFailure.class,()->auth.advance(f)).code);assertEquals(0,h.inspections);assertEquals(0,h.prepares);
    }
    @Test void authCreateNeedsFreshRelayAndRealAdmissionIntentBeforeClaim()throws Exception{
        var h=new AuthHarness();h.auth.advance(f);h.relayReady=false;assertThrows(ControlFailure.class,()->h.auth.advance(f));assertEquals(0,h.creates);
        h.relayReady=true;when(admission.beforeMembershipIntent(eq(f),any())).thenThrow(new ControlFailure(403,"ADMIN_REQUIRED"));
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->h.auth.advance(f)).code);assertEquals(0,h.creates);
    }
    @Test void alreadyClaimedAuthSqlCanBeObservedWhenRelayIsUnavailable()throws Exception{
        var h=new AuthHarness();h.auth.advance(f);h.loseCreate=true;assertThrows(ControlFailure.class,()->h.auth.advance(f));h.relayReady=false;
        h.restart();assertEquals(Map.of("PREPARE","SUCCEEDED","CREATE","SUCCEEDED"),h.auth.advance(f).get("memberAuthOperations"));assertEquals(1,h.creates);
    }
    @Test void authRoleOrMembershipReceiptMismatchIsNotAccepted()throws Exception{
        var h=new AuthHarness();h.auth.advance(f);h.changedReceipt=Map.of("role","foreign_role");assertThrows(ControlFailure.class,()->h.auth.advance(f));
        h.changedReceipt=Map.of("membershipChanged",true);assertThrows(ControlFailure.class,()->h.auth.advance(f));assertEquals(1,h.creates);
        h.changedReceipt=Map.of();h.auth.advance(f);assertEquals("ROLE_READY",h.auth.ready(f).get("state"));
    }
    @Test void authSealedHeaderMatchesActualNativePythonProtocolAndJavaRoleParser()throws Exception{
        var h=new AuthHarness();h.auth.advance(f);var input=h.saved();
        var role=JsonMapper.builder().build().convertValue(input.header().get("role"),you.v50to.eatwhat.control.bootstrap.AuthorityAuthRole.Request.class);
        assertTrue(input.header().get("role") instanceof Map<?,?> m&&m.get("validUntil") instanceof String);assertEquals(f.spec().targetServerId(),role.targetServerId());
        String script="import json,sys,types\nx=json.load(sys.stdin)\nm=types.ModuleType('reviewed_auth_worker')\nexec(compile(x['agent'],'<reviewed-auth>','exec'),m.__dict__)\nm.validate(x['header'])\nm.collector(x['header'])\nprint(m.digest(m.canonical(x['header'])))\n";
        var process=new ProcessBuilder("python3","-I","-B","-c",script).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try(var out=process.getOutputStream()){out.write(JsonMapper.builder().build().writeValueAsBytes(Map.of("agent",input.agent(),"header",input.header())));}
        assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        assertEquals(input.binding(),new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip());
    }
    /** Continue the actual manager fixture through business success into relay
     * children. Only the remote observations and authority fence are mocks. */
    class RelayHarness {
        ControlBootstrapMemberRelay relay;int inspections,prepares,starts;boolean prepared,losePrepare,loseStart;
        String state="RELAY_READY";Map<String,Object> changedReceipt=Map.of(),materialChanges=Map.of();
        RelayHarness()throws Exception{
            driver.advance(f);driver.advance(f);reset(ssh);String route=f.targetRoute();when(ssh.routeBinding(anyMap())).thenReturn(route);restart();
            doAnswer(inv->{
                var bytes=new ByteArrayOutputStream();((ControlSsh.Upload)inv.getArgument(3)).write(bytes);
                var h=JsonMapper.builder().build().readValue(bytes.toByteArray(),LinkedHashMap.class);String command=((List<String>)inv.getArgument(2)).getFirst();
                Object agent=h.remove("agent");String op=command.equals("inspect")?"INSPECT":command.startsWith("prepare")?"PREPARE":command.equals("materials")?"MATERIALS":"START";
                var result=new LinkedHashMap<String,Object>(Map.of("protocol",ControlBootstrapMemberRelay.PROTOCOL,"id",h.get("id"),"serverId",h.get("serverId"),
                        "operation",op,"binding",ControlBootstrapNativeJson.digest(h)));
                if(command.equals("inspect")){inspections++;result.putAll(Map.of("effectsStarted",false,"proof","a".repeat(64)));return result;}
                assertEquals(saved().header(),h);assertEquals(saved().agent(),inv.getArgument(1));
                if(command.equals("prepare")){prepares++;assertEquals(saved().agent(),agent);prepared=true;if(losePrepare)throw new IOException("private prepare failure");}
                if(op.equals("PREPARE")){result.put("state",prepared?"PREPARED":"NOT_PREPARED");return result;}
                if(command.equals("dispatch")){starts++;if(loseStart)throw new IOException("private start failure");}
                result.put("state",state);
                if(state.equals("RELAY_READY"))result.putAll(Map.of("networkId","2".repeat(64),"caSha256",ControlBootstrapNativeJson.sha(runtimeCa),"materialManifestSha256","b".repeat(64),
                        "tlsListenerVerified",true,"authorityHttpVerified",true,"databaseAuthenticated",false,"memberConfigured",false,"joinAuthorized",false));
                if(command.equals("materials")){result.putAll(Map.of("caPem",runtimeCa,"config",Map.of("serverId",f.spec().targetServerId().toString(),"networkId","2".repeat(64))));result.putAll(materialChanges);}
                result.putAll(changedReceipt);return result;
            }).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
        }
        void restart(){relay=new ControlBootstrapMemberRelay(store,journal,nativePhases,claims,inputs);}
        ControlBootstrapNativeInputs.Sealed saved(){return inputs.load(UUID.nameUUIDFromBytes((f.spec().id()+":MEMBER_TLS_RELAY_INPUTS").getBytes(java.nio.charset.StandardCharsets.UTF_8)),MEMBER_CONFIGURE,approval,f.spec().targetServerId(),f.spec().targetRevision(),f.targetRoute());}
    }
    @Test void relayPublicMaterialMustMatchTheOriginalStartAndNetwork()throws Exception{
        var h=new RelayHarness();h.relay.advance(f);h.relay.advance(f);assertEquals(runtimeCa,h.relay.materials(f).get("caPem"));
        h.materialChanges=Map.of("caPem",runtimeCa+"changed");assertThrows(ControlFailure.class,()->h.relay.materials(f));
        h.materialChanges=Map.of();h.changedReceipt=Map.of("caSha256","0".repeat(64));assertThrows(ControlFailure.class,()->h.relay.materials(f));
    }
    @Test void terminalRelayFailureFinishesParentAndRetainsOriginalOnceOnlyStart()throws Exception{
        var h=new RelayHarness();h.relay.advance(f);h.state="FAILED";var result=h.relay.advance(f);
        assertEquals("FAILED",result.get("state"));assertEquals("BOOTSTRAP_PHASE_FAILED_PRESERVED",result.get("code"));assertEquals(1,h.starts);
        assertTrue(Files.exists(store.directory().resolve("controller-member-relay-"+f.spec().id()+".json")));
        assertEquals("PENDING",((List<Map<String,Object>>)result.get("steps")).get(MEMBER_JOIN.ordinal()).get("state"));
        h.restart();assertThrows(ControlFailure.class,()->h.relay.advance(f));assertEquals(1,h.starts);
    }
    @Test void terminalAuthFailureIsNotAnUnknownParentForever()throws Exception{
        var h=new AuthHarness();h.auth.advance(f);h.state="FAILED";assertEquals("FAILED",h.auth.advance(f).get("state"));assertEquals(1,h.creates);
        assertFalse(journal.job(f.spec().id()).toString().contains("password"));
    }
    @Test void terminalIngressFailureDoesNotStartRuntimeOrJoin()throws Exception{
        var h=new IngressHarness();h.ingress.advance(f);h.state="FAILED";assertEquals("FAILED",h.ingress.advance(f).get("state"));assertEquals(1,h.starts);assertEquals(0,h.sourceProbes);
    }
    @Test void terminalRuntimeFailureIsPreservedAndDoesNotPublishProfiles()throws Exception{
        var h=new RuntimeHarness();h.state="FAILED";assertEquals("FAILED",h.runtime.advance(f).get("state"));assertEquals(1,h.dispatches);assertEquals(0,h.verifies);
        assertEquals("PENDING",((List<Map<String,Object>>)journal.job(f.spec().id()).get("steps")).get(MEMBER_JOIN.ordinal()).get("state"));
    }
    @Test void businessThenRelaySuccessLeavesMemberConfigurationAndJoinPending()throws Exception{
        var h=new RelayHarness();h.changedReceipt=Map.of("acceptedAt",1792300000.25,"finishedAt",1792300001.5);h.relay.advance(f);var result=h.relay.advance(f);
        assertEquals(Map.of("PREPARE","SUCCEEDED","START","SUCCEEDED"),result.get("memberRelayOperations"));assertEquals(true,result.get("memberConfigurationPending"));
        var steps=(List<Map<String,Object>>)result.get("steps");assertEquals("DISPATCHED",steps.get(MEMBER_CONFIGURE.ordinal()).get("state"));assertEquals("PENDING",steps.get(MEMBER_JOIN.ordinal()).get("state"));
        h.relay.advance(f);assertEquals(1,h.prepares);assertEquals(1,h.starts);assertEquals(1,h.inspections);assertEquals("RELAY_READY",h.relay.ready(f).get("state"));
        assertFalse(result.toString().contains("agentSha256"));assertFalse(result.toString().contains("authPassword"));
    }
    @Test void relayLostPreparationReplyCanOnlyObserveOriginalPreparation()throws Exception{
        var h=new RelayHarness();h.losePrepare=true;assertThrows(ControlFailure.class,()->h.relay.advance(f));var saved=h.saved();h.restart();
        h.relay.advance(f);assertEquals(saved,h.saved());assertEquals(1,h.prepares);assertEquals(0,h.starts);h.relay.advance(f);assertEquals(1,h.starts);
    }
    @Test void relayLostStartReplyCannotDispatchAgainEvenWhenRemoteReportsNotStarted()throws Exception{
        var h=new RelayHarness();h.relay.advance(f);h.loseStart=true;assertThrows(ControlFailure.class,()->h.relay.advance(f));h.state="NOT_STARTED";h.restart();
        h.relay.advance(f);h.relay.advance(f);assertEquals(1,h.starts);assertEquals(1,h.prepares);assertThrows(ControlFailure.class,()->h.relay.ready(f));
    }
    @Test void missingRelayOriginalAfterClaimCannotBeRegenerated()throws Exception{
        var h=new RelayHarness();h.relay.advance(f);
        UUID child=UUID.nameUUIDFromBytes((f.spec().id()+":MEMBER_TLS_RELAY_INPUTS").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.delete(store.directory().resolve("controller-native-"+child+"-MEMBER_CONFIGURE.json"));h.restart();
        assertEquals("BOOTSTRAP_NATIVE_INPUT_MISSING",assertThrows(ControlFailure.class,()->h.relay.advance(f)).code);assertEquals(1,h.inspections);assertEquals(0,h.starts);
    }
    @Test void oldBusinessApprovalCannotGrantRelayScope()throws Exception{
        var h=new RelayHarness();var restricted=mock(ControlBootstrapJournal.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(journal).defaultAnswer(CALLS_REAL_METHODS));
        UUID id=f.spec().id();doReturn(Set.of("INSTALL_BUSINESS_AND_JOIN",ControlBootstrapBusinessFactory.NETWORK_PERMISSION,ControlBootstrapBusinessFactory.FORWARD_POLICY_PERMISSION)).when(restricted).approvedPermissions(id);
        var relay=new ControlBootstrapMemberRelay(store,restricted,nativePhases,claims,inputs);
        assertEquals("BOOTSTRAP_MEMBER_RELAY_SCOPE_REQUIRED",assertThrows(ControlFailure.class,()->relay.advance(f)).code);assertEquals(0,h.inspections);assertEquals(0,h.prepares);
    }
    @Test void relayAuthorityRevocationBeforeStartDoesNotUseThePreparationApproval()throws Exception{
        var h=new RelayHarness();h.relay.advance(f);when(admission.beforeMembershipIntent(eq(f),any())).thenThrow(new ControlFailure(403,"ADMIN_REQUIRED"));
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->h.relay.advance(f)).code);assertEquals(0,h.starts);
    }
    @Test void relayReceiptCannotClaimOtherNetworkOrPrematureMembership()throws Exception{
        var h=new RelayHarness();h.relay.advance(f);
        for(var edit:Map.<String,Object>of("networkId","9".repeat(64),"joinAuthorized",true,"memberConfigured",true,"databaseAuthenticated",true,"caSha256","invalid").entrySet()){
            h.changedReceipt=Map.of(edit.getKey(),edit.getValue());assertThrows(ControlFailure.class,()->h.relay.advance(f));
        }assertEquals(1,h.starts);assertEquals(1,h.prepares);
    }
    @Test void relayReadyIsAlwaysRevalidatedBeforeFollowingConfiguration()throws Exception{
        var h=new RelayHarness();h.relay.advance(f);h.relay.advance(f);h.state="OUTCOME_UNKNOWN";
        assertEquals("BOOTSTRAP_MEMBER_RELAY_NOT_READY",assertThrows(ControlFailure.class,()->h.relay.ready(f)).code);assertEquals(1,h.starts);
    }
    @TempDir Path root;
    BootstrapTargetFixture fixture;ControlServerStore store;ControlBootstrapJournal journal;ControlBootstrapAdmission admission;
    ControlBootstrapNativeInputs inputs;ControlBootstrapNativePhases nativePhases;ControlBootstrapPhaseClaims claims;
    ControlBootstrapBusinessAllocation allocations;ControlBootstrapBusinessFactory factory;ControlBootstrapBusinessOperations operations;
    ControlBootstrapBusinessPhases driver;ControlSsh ssh;DeploymentExecutor deployment;ControlBootstrapAdmission.Frozen f;
    String approval;int inspections,prepares,installs;boolean prepared,losePrepare,prepareHasEffects=true,loseInstall;
    String remoteState="SUCCEEDED";List<String> commands=new ArrayList<>();Map<String,Object> lastReceipt;
    static <T>T stub(Class<T> type){return BootstrapTargetFixture.stub(type);}
    @BeforeEach void setup()throws Exception{setup(null);}
    @SuppressWarnings("unchecked") void setup(ControlBootstrapStorage.Selection storage)throws Exception{
        fixture=new BootstrapTargetFixture(root);f=fixture.frozen;admission=fixture.admission;
        when(f.actor()).thenReturn("123");when(f.binding()).thenReturn("fixture-binding");
        var review=Map.<String,Object>of("sourceAddress","10.253.74.1","targetAddress","10.253.74.3","targetEndpoint","fixture.invalid","wireguardPort",51820,"httpPort",18090,"appBaseUrl","https://fixture.invalid/api");
        when(f.review()).thenReturn(review);
        var audit=new ControlAudit(root.resolve("manager"));store=new ControlServerStore(audit);deployment=new DeploymentExecutor(new ControlProperties(),audit);
        ssh=stub(ControlSsh.class);journal=new ControlBootstrapJournal(store,audit,deployment,stub(ControlServers.class));claims=new ControlBootstrapPhaseClaims(journal,admission);
        store.mutate(data->{var rows=(Map<String,Object>)data.get("servers");var row=new LinkedHashMap<>(fixture.targetServer);row.put("username","ubuntu");row.put("privilege","SUDO");rows.put(f.spec().targetServerId().toString(),row);return null;});
        String route=f.targetRoute();when(ssh.routeBinding(anyMap())).thenReturn(route);
        when(admission.beforeInitialIntent(eq(f),any())).thenAnswer(inv->((Supplier<?>)inv.getArgument(1)).get());
        when(admission.beforeMembershipIntent(eq(f),any())).thenAnswer(inv->((Supplier<?>)inv.getArgument(1)).get());
        var parentInputs=new LinkedHashMap<String,Object>();parentInputs.put("admission",Map.of("binding",f.binding()));
        if(storage!=null)parentInputs.put("targetStorage",new ControlBootstrapStorage(store).freeze(f.spec().id(),f.spec().targetServerId(),f.spec().targetRevision(),storage,Map.of()));
        journal.preview("123",f.spec(),review,parentInputs,()->{});journal.accept("123",f.spec().id(),ControlBootstrapJournal.PERMISSIONS,()->{});
        for(var phase:List.of(SOURCE_RUNTIME,TARGET_RUNTIME,SOURCE_PRIVATE_PEER,TARGET_PRIVATE_PEER,SOURCE_SHARED_STATE,TARGET_SHARED_STATE)){
            var permit=claims.claim(f,phase);journal.observe(f.spec().id(),phase,permit.claimToken(),Map.of("protocol",phase.protocol,"id",permit.id().toString(),"state",phase.success));
        }
        for(var op:ControlBootstrapJournal.DependencyOperation.values()){
            var permit=claims.dependency(f,op,"a".repeat(64),"b".repeat(64));journal.observeDependencyOperation(f.spec().id(),op,permit.claimToken(),
                    Map.of("id",permit.nativeId().toString(),"protocol",DEPENDENCY_LINK.protocol,"role",op.role,"operation",op.name(),"binding",permit.nativeBinding(),"state",op.success));
        }
        inputs=new ControlBootstrapNativeInputs(store);approval=journal.job(f.spec().id()).get("requestHash").toString();
        String controllerAgent="fixture original controller";var controllerHeader=new LinkedHashMap<String,Object>();
        controllerHeader.put("id",ControlBootstrapJournal.phaseId(f.spec().id(),CONTROLLER_CONFIG).toString());controllerHeader.put("verifyId",ControlBootstrapJournal.phaseId(f.spec().id(),CONTROLLER_VERIFY).toString());
        var authority=f.sourceRequest().expected();String profiles=JsonMapper.builder().build().writeValueAsString(Map.of("campus",Map.of("siteId",authority.authoritySiteId(),"instanceId",authority.authorityInstanceId().toString(),"mode","CAMPUS","allocationSlot",0,"apiPrefix","/api")));
        controllerHeader.put("bundle",Map.of("catalog",ControlBootstrapControllerCatalog.metadata(f.artifacts()),"authoritySiteId",authority.authoritySiteId(),
                "authorityInstanceId",authority.authorityInstanceId().toString(),"privateFiles",Map.of("cluster-profiles.json",Base64.getEncoder().encodeToString(profiles.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));controllerHeader.put("approvalDigest",approval);controllerHeader.put("agentSha256",ControlBootstrapNativeJson.sha(controllerAgent));
        var controller=inputs.prepare(f.spec().id(),CONTROLLER_CONFIG,approval,f.spec().targetServerId(),1,f.targetRoute(),true,()->new ControlBootstrapNativeInputs.Prepared(controllerHeader,controllerAgent));
        var configured=claims.claim(f,CONTROLLER_CONFIG);journal.observe(f.spec().id(),CONTROLLER_CONFIG,configured.claimToken(),Map.of("protocol",CONTROLLER_CONFIG.protocol,"id",configured.id().toString(),"state","STAGED"));
        var verified=claims.claim(f,CONTROLLER_VERIFY);journal.observe(f.spec().id(),CONTROLLER_VERIFY,verified.claimToken(),Map.of("protocol",CONTROLLER_VERIFY.protocol,"id",verified.id().toString(),"state","VERIFIED",
                "configurationId",controllerHeader.get("id"),"binding",controller.binding(),"operation","VERIFY","controllerHealthProven",true));
        allocations=stub(ControlBootstrapBusinessAllocation.class);var identity=ControlBootstrapBusinessAllocation.identity(f.spec());when(allocations.reserve(f)).thenReturn(identity);
        restart();
        doAnswer(inv->{
            assertEquals(45,(int)inv.getArgument(4));var args=(List<String>)inv.getArgument(2);String command=args.getFirst();commands.add(command);
            var out=new ByteArrayOutputStream();((ControlSsh.Upload)inv.getArgument(3)).write(out);var data=JsonMapper.builder().build().readValue(out.toByteArray(),Map.class);
            var header=new LinkedHashMap<String,Object>(data);header.remove("agent");var b=(Map<?,?>)header.get("business");
            if(command.equals("inspect")){
                inspections++;assertFalse(header.containsKey("agentSha256"));assertFalse(header.containsKey("expectedProof"));
                var response=receipt(header,"INSPECT","INSPECTED");response.put("effectsStarted",false);response.put("proof","d".repeat(64));
                response.put("networkContextSha256","e".repeat(64));response.put("kernelBaselineSha256","f".repeat(64));response.put("servicesBaselineSha256","9".repeat(64));
                response.put("firewallBaselineSha256","a".repeat(64));response.put("ipv6BaselineSha256","b".repeat(64));return response;
            }
            var saved=original();assertEquals(saved.header(),header);assertEquals(saved.agent(),inv.getArgument(1));
            if(command.equals("prepare")){
                prepares++;assertEquals(1,prepares);assertEquals("CLAIMED",operations.progress(f).get("PREPARE"));assertEquals(saved.agent(),data.get("agent"));
                prepared=prepareHasEffects;if(losePrepare)throw new IOException("lost prepare ack");return receipt(header,"PREPARE","PREPARED");
            }
            if(command.equals("prepare-status"))return receipt(header,"PREPARE",prepared?"PREPARED":"NOT_PREPARED");
            assertTrue(prepared);assertFalse(data.containsKey("agent"));
            if(command.equals("dispatch")){installs++;assertEquals(1,installs);assertEquals("CLAIMED",operations.progress(f).get("INSTALL"));if(loseInstall)throw new IOException("lost install ack");}
            else assertEquals("status",command);
            var response=receipt(header,"INSTALL",remoteState);
            if(remoteState.equals("SUCCEEDED")){
                for(String key:List.of("siteId","instanceId","mode","clusterId","allocationSlot","artifactSha256","httpPort"))response.put(key,b.get(key));
                response.put("root","/opt/eatwhat-sites/"+b.get("serverId"));response.put("restoreEvidenceId","fresh-install-restore-"+header.get("id"));response.put("restoreCleanupVerified",true);
                response.put("clusterMembership","NOT_JOINED");response.put("priorNetworkRevalidationRequired",true);response.put("businessHealthRevalidated",false);
                networkReceipt(header,response);
            }lastReceipt=response;return response;
        }).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
    }
    @AfterEach void close(){if(deployment!=null)deployment.close();}
    void restart(){
        inputs=new ControlBootstrapNativeInputs(store);nativePhases=new ControlBootstrapNativePhases(store,journal,admission,claims,inputs,ssh);
        factory=new ControlBootstrapBusinessFactory(nativePhases,inputs,journal,allocations,fixture.network);
        operations=new ControlBootstrapBusinessOperations(store,journal,claims,inputs,nativePhases);driver=new ControlBootstrapBusinessPhases(store,admission,nativePhases,operations,factory,journal);
    }
    ControlBootstrapNativeInputs.Sealed original(){return inputs.load(f.spec().id(),BUSINESS_INSTALL,approval,f.spec().targetServerId(),1,f.targetRoute());}
    Map<String,Object> receipt(Map<String,Object> header,String op,String state){return new LinkedHashMap<>(Map.of("protocol",BUSINESS_INSTALL.protocol,"bootstrapProtocol",ControlBootstrapBusinessFactory.PROTOCOL,
            "jobId",header.get("id"),"operation",op,"binding",ControlBootstrapNativeJson.sha(ControlBootstrapNativeJson.encode(header)),"serverId",((Map<?,?>)header.get("business")).get("serverId"),"state",state));}
    void networkReceipt(Map<String,Object> header,Map<String,Object> value){
        var proof=new LinkedHashMap<String,Object>();proof.put("protocol","eatwhat-business-kernel-network-v1");proof.put("state","KERNEL_IPV4_DELTA_VERIFIED");
        proof.put("serverId",((Map<?,?>)header.get("business")).get("serverId"));proof.put("jobId",header.get("id"));proof.put("approvalDigest",approval);
        proof.put("inputBinding",ControlBootstrapNativeJson.digest(header));proof.put("baselineSha256",header.get("expectedKernelBaselineSha256"));
        proof.put("observationSha256","1".repeat(64));proof.put("ownedNetworkId","2".repeat(64));
        for(String key:List.of("kernelIpv4DeltaVerified","ipv4DefaultRoutePreserved","dnsPreserved","ipv6RevalidationRequired","privateLinkRevalidationRequired","firewallRevalidationRequired"))proof.put(key,true);
        proof.put("businessHealthVerified",false);proof.put("joinAuthorized",false);
        value.put("kernelIpv4DeltaVerified",true);value.put("kernelNetworkProof",proof);value.put("createdNetworkId","2".repeat(64));
        value.put("createdNetworkProofSha256","3".repeat(64));value.put("networkBaselineSha256","4".repeat(64));value.put("installationState","SUCCEEDED");
        var services=new LinkedHashMap<String,Object>();services.put("protocol","eatwhat-business-controller-services-v1");services.put("state","CONTROLLER_SERVICES_REVALIDATED");
        services.put("jobId",header.get("id"));services.put("serverId",((Map<?,?>)header.get("business")).get("serverId"));services.put("inputBinding",ControlBootstrapNativeJson.digest(header));
        services.put("controllerBinding",header.get("controllerBinding"));services.put("approvalDigest",approval);services.put("baselineSha256",header.get("expectedServicesBaselineSha256"));services.put("observationSha256","8".repeat(64));
        for(String key:List.of("controllerServicesRevalidated","boundedNfsIdentityVerified","mountGuardVerified","privateSshMasterVerified","anonymousAdminDenied","readOnlyAuthorityDatabaseVerified",
                "currentControllerLeaseLive","firewallRevalidationRequired","ipv6RevalidationRequired"))services.put(key,true);
        services.put("controllerReadiness","UP");services.put("businessHealthVerified",false);services.put("joinAuthorized",false);
        value.put("controllerServicesRevalidated",true);value.put("controllerServicesProof",services);
        for(String prefix:List.of("firewall","ipv6")){
            var result=new LinkedHashMap<String,Object>();result.put("protocol","eatwhat-business-"+prefix+"-v1");result.put("state",prefix.equals("firewall")?"FIREWALL_DELTA_VERIFIED":"IPV6_DELTA_VERIFIED");
            result.put("jobId",header.get("id"));result.put("serverId",((Map<?,?>)header.get("business")).get("serverId"));result.put("approvalDigest",approval);result.put("inputBinding",ControlBootstrapNativeJson.digest(header));
            result.put("baselineSha256",header.get(prefix.equals("firewall")?"expectedFirewallBaselineSha256":"expectedIpv6BaselineSha256"));result.put("observationSha256","c".repeat(64));
            result.put("ownedNetworkId",value.get("createdNetworkId"));result.put(prefix+"DeltaVerified",true);result.put("joinAuthorized",false);
            for(String key:List.of("originalRulesPreserved","originalAddressesAndRoutesPreserved","originalSysctlsPreserved"))result.put(key,true);
            value.put(prefix+"DeltaVerified",true);value.put(prefix+"Proof",result);
        }
    }
    @Test void missingOriginalNetworkScopeStopsBeforeAllocationOrRemoteInspection(){
        var restricted=mock(ControlBootstrapJournal.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(journal).defaultAnswer(CALLS_REAL_METHODS));
        UUID id=f.spec().id();doReturn(Set.of("INSTALL_BUSINESS_AND_JOIN")).when(restricted).approvedPermissions(id);
        var factory=new ControlBootstrapBusinessFactory(nativePhases,inputs,restricted,allocations,fixture.network);
        assertEquals("BOOTSTRAP_BUSINESS_NETWORK_SCOPE_REQUIRED",assertThrows(ControlFailure.class,()->factory.ensure(f)).code);
        verifyNoInteractions(allocations);assertEquals(0,inspections);assertFalse(Files.exists(inputFile()));
    }
    @Test void oldNetworkingScopeCannotGrantTheNewForwardPolicyPermission(){
        var restricted=mock(ControlBootstrapJournal.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(journal).defaultAnswer(CALLS_REAL_METHODS));
        UUID id=f.spec().id();doReturn(Set.of(ControlBootstrapBusinessFactory.NETWORK_PERMISSION)).when(restricted).approvedPermissions(id);
        var factory=new ControlBootstrapBusinessFactory(nativePhases,inputs,restricted,allocations,fixture.network);
        assertEquals("BOOTSTRAP_BUSINESS_FIREWALL_POLICY_SCOPE_REQUIRED",assertThrows(ControlFailure.class,()->factory.ensure(f)).code);
        verifyNoInteractions(allocations);assertEquals(0,inspections);
    }
    @Test void originalLegacyInputReusesOriginalAgentWithoutGrantingNewNetworkScope(){
        var header=new LinkedHashMap<String,Object>();header.put("id",ControlBootstrapJournal.phaseId(f.spec().id(),BUSINESS_INSTALL).toString());
        header.put("bootstrapId",f.spec().id().toString());header.put("approvalDigest",approval);header.put("business",Map.of("serverId",f.spec().targetServerId().toString()));
        String agent="# immutable legacy worker\n";header.put("agentSha256",ControlBootstrapNativeJson.sha(agent));
        var saved=inputs.prepare(f.spec().id(),BUSINESS_INSTALL,approval,f.spec().targetServerId(),1,f.targetRoute(),true,()->new ControlBootstrapNativeInputs.Prepared(header,agent));
        var restricted=mock(ControlBootstrapJournal.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(journal).defaultAnswer(CALLS_REAL_METHODS));
        doThrow(new AssertionError("Must not grant new scope for an existing original")).when(restricted).approvedPermissions(any());
        new ControlBootstrapBusinessFactory(nativePhases,inputs,restricted,allocations,fixture.network).ensure(f);
        assertEquals(saved,original());assertEquals(0,inspections);verifyNoInteractions(allocations);
    }
    @Test @SuppressWarnings("unchecked") void successfulInstallationRequiresTheExactOriginalKernelProof(){
        driver.advance(f);driver.advance(f);var saved=original();ControlBootstrapBusinessOperations.validate(saved,ControlBootstrapBusinessOperations.Operation.INSTALL,lastReceipt);
        for(var edit:Map.<String,Object>of("jobId",UUID.randomUUID().toString(),"approvalDigest","a".repeat(64),"inputBinding","b".repeat(64),"baselineSha256","c".repeat(64),
                "ownedNetworkId","d".repeat(64),"joinAuthorized",true,"businessHealthVerified",true,"privateLinkRevalidationRequired",false,"observationSha256","bad").entrySet()){
            var value=new LinkedHashMap<>(lastReceipt);var proof=new LinkedHashMap<>((Map<String,Object>)value.get("kernelNetworkProof"));proof.put(edit.getKey(),edit.getValue());value.put("kernelNetworkProof",proof);
            assertEquals("BOOTSTRAP_BUSINESS_NETWORK_UNCONFIRMED",assertThrows(ControlFailure.class,()->ControlBootstrapBusinessOperations.validate(saved,ControlBootstrapBusinessOperations.Operation.INSTALL,value)).code,edit.getKey());
        }
        for(String key:List.of("kernelNetworkProof","kernelIpv4DeltaVerified","networkBaselineSha256","createdNetworkId","createdNetworkProofSha256")){
            var value=new LinkedHashMap<>(lastReceipt);value.remove(key);
            assertEquals("BOOTSTRAP_BUSINESS_NETWORK_UNCONFIRMED",assertThrows(ControlFailure.class,()->ControlBootstrapBusinessOperations.validate(saved,ControlBootstrapBusinessOperations.Operation.INSTALL,value)).code,key);
        }
    }
    @Test @SuppressWarnings("unchecked") void serviceSuccessRequiresTheOriginalBindingAndIndependentCurrentProbes(){
        driver.advance(f);driver.advance(f);var saved=original();
        for(var edit:Map.<String,Object>of("controllerBinding","b".repeat(64),"baselineSha256","c".repeat(64),"jobId",UUID.randomUUID().toString(),"boundedNfsIdentityVerified",false,
                "privateSshMasterVerified",false,"readOnlyAuthorityDatabaseVerified",false,"currentControllerLeaseLive",false,"joinAuthorized",true).entrySet()){
            var value=new LinkedHashMap<>(lastReceipt);var proof=new LinkedHashMap<>((Map<String,Object>)value.get("controllerServicesProof"));proof.put(edit.getKey(),edit.getValue());value.put("controllerServicesProof",proof);
            assertEquals("BOOTSTRAP_BUSINESS_SERVICES_UNCONFIRMED",assertThrows(ControlFailure.class,()->ControlBootstrapBusinessOperations.validate(saved,ControlBootstrapBusinessOperations.Operation.INSTALL,value)).code,edit.getKey());
        }
        var value=new LinkedHashMap<>(lastReceipt);value.remove("controllerServicesProof");
        assertEquals("BOOTSTRAP_BUSINESS_SERVICES_UNCONFIRMED",assertThrows(ControlFailure.class,()->ControlBootstrapBusinessOperations.validate(saved,ControlBootstrapBusinessOperations.Operation.INSTALL,value)).code);
    }
    @Test @SuppressWarnings("unchecked") void firewallAndIpv6ProofsCannotBeMissingOrBoundToAnotherNetwork(){
        driver.advance(f);driver.advance(f);var saved=original();
        for(String prefix:List.of("firewall","ipv6")){
            for(var edit:Map.<String,Object>of("baselineSha256","d".repeat(64),"ownedNetworkId","e".repeat(64),"jobId",UUID.randomUUID().toString(),"joinAuthorized",true).entrySet()){
                var value=new LinkedHashMap<>(lastReceipt);var proof=new LinkedHashMap<>((Map<String,Object>)value.get(prefix+"Proof"));proof.put(edit.getKey(),edit.getValue());value.put(prefix+"Proof",proof);
                assertEquals("BOOTSTRAP_BUSINESS_FIREWALL_UNCONFIRMED",assertThrows(ControlFailure.class,()->ControlBootstrapBusinessOperations.validate(saved,ControlBootstrapBusinessOperations.Operation.INSTALL,value)).code);
            }
            var value=new LinkedHashMap<>(lastReceipt);value.remove(prefix+"Proof");
            assertEquals("BOOTSTRAP_BUSINESS_FIREWALL_UNCONFIRMED",assertThrows(ControlFailure.class,()->ControlBootstrapBusinessOperations.validate(saved,ControlBootstrapBusinessOperations.Operation.INSTALL,value)).code);
        }
    }
    Map<?,?> installation(){return (Map<?,?>)store.server(f.spec().targetServerId()).get("installation");}
    Path inputFile(){return store.directory().resolve("controller-native-"+f.spec().id()+"-BUSINESS_INSTALL.json");}
    @Test void installDoesNotRunOnPreparationAndCompletesWithoutJoining(){
        driver.advance(f);assertEquals(List.of("inspect","prepare"),commands);assertEquals(0,installs);assertNull(installation());
        driver.advance(f);assertEquals(1,installs);assertEquals("MEMBER_CONFIGURE",journal.job(f.spec().id()).get("stage"));
        assertEquals("SUCCEEDED",installation().get("state"));assertEquals("NOT_JOINED",installation().get("clusterMembership"));assertEquals(true,installation().get("networkRevalidationRequired"));assertEquals(false,installation().get("businessHealthRevalidated"));
    }
    @SuppressWarnings("unchecked") void inventoryInstallation(Map<String,Object> value){
        store.mutate(data->{((Map<String,Object>)((Map<String,Object>)data.get("servers")).get(f.spec().targetServerId().toString())).put("installation",new LinkedHashMap<>(value));return null;});
    }
    @Test void actualNewServerInventorySentinelAcceptsOnlyTheVerifiedOriginalResult(){
        inventoryInstallation(Map.of("state","NOT_INSTALLED"));driver.advance(f);driver.advance(f);
        assertEquals("SUCCEEDED",installation().get("state"));assertEquals("NOT_JOINED",installation().get("clusterMembership"));
        assertEquals(ControlBootstrapJournal.phaseId(f.spec().id(),BUSINESS_INSTALL).toString(),installation().get("jobId"));assertEquals(1,installs);
        restart();driver.advance(f);assertEquals(1,installs);
    }
    @Test void anUninstalledLabelCannotHideRetainedForeignInstallationMetadata(){
        String other=UUID.randomUUID().toString();inventoryInstallation(Map.of("state","NOT_INSTALLED","jobId",other));
        driver.advance(f);assertEquals("BOOTSTRAP_BUSINESS_INSTALLATION_CHANGED",assertThrows(ControlFailure.class,()->driver.advance(f)).code);
        assertEquals(other,installation().get("jobId"));assertEquals("NOT_INSTALLED",installation().get("state"));assertEquals(1,installs);
    }
    @Test void actualUninstalledSentinelAlsoPreservesANativeFailure(){
        inventoryInstallation(Map.of("state","NOT_INSTALLED"));driver.advance(f);remoteState="FAILED";driver.advance(f);
        assertEquals("FAILED",installation().get("state"));assertEquals("FAILED",journal.job(f.spec().id()).get("state"));assertEquals(1,installs);
    }
    @Test @SuppressWarnings("unchecked") void sealedSecretsAreFreshPrivateAndReusedWithoutGeneratingNewCredentials()throws Exception{
        driver.advance(f);var before=original();var business=(Map<String,Object>)before.header().get("business");var db=(Map<String,String>)business.get("database");
        assertNotEquals(db.get("POSTGRES_PASSWORD"),db.get("PG_APP_PASSWORD"));assertNotEquals(db.get("REDIS_PASSWORD"),db.get("PG_APP_PASSWORD"));assertEquals(43,business.get("nodeToken").toString().length());
        var p=(Map<String,String>)business.get("properties");assertEquals("false",p.get("app.cluster.enabled"));assertEquals("true",p.get("app.control.enrollment-required"));assertEquals("jdbc:postgresql://postgres:5432/eatwhat",p.get("spring.datasource.url"));
        assertFalse(business.containsKey("administrator"));assertFalse(p.containsValue("fixture_runtime"));assertEquals(f.artifacts().business().sha256(),business.get("artifactSha256"));
        byte[] saved=Files.readAllBytes(inputFile());restart();factory.ensure(f);assertEquals(before,original());assertArrayEquals(saved,Files.readAllBytes(inputFile()));
        assertFalse(Files.readString(inputFile()).contains(db.get("POSTGRES_PASSWORD")));assertFalse(driver.advance(f).toString().contains(db.get("POSTGRES_PASSWORD")));
        assertEquals(1,inspections);verify(allocations,times(1)).reserve(f);
    }
    @Test void lostPrepareAckIsObservedAfterRestartAndNeverPreparedAgain(){
        losePrepare=true;assertThrows(ControlFailure.class,()->driver.advance(f));restart();driver.advance(f);
        assertEquals(List.of("inspect","prepare","prepare-status"),commands);assertEquals(1,prepares);assertEquals(0,installs);
    }
    @Test void absentRemotePreparationAfterLostAckDoesNotGrantRedispatch(){
        losePrepare=true;prepareHasEffects=false;assertThrows(ControlFailure.class,()->driver.advance(f));restart();driver.advance(f);driver.advance(f);
        assertEquals(List.of("inspect","prepare","prepare-status","prepare-status"),commands);assertEquals("CLAIMED",operations.progress(f).get("PREPARE"));assertEquals(0,installs);
    }
    @Test void lostInstallAckUsesOnlyTheOriginalStatus(){
        driver.advance(f);loseInstall=true;assertThrows(ControlFailure.class,()->driver.advance(f));restart();driver.advance(f);
        assertEquals(1,installs);assertEquals("status",commands.getLast());assertEquals("SUCCEEDED",installation().get("state"));
    }
    @Test void remoteNotStartedAfterClaimRemainsUnknownWithoutStartingAnotherWorker(){
        driver.advance(f);loseInstall=true;assertThrows(ControlFailure.class,()->driver.advance(f));remoteState="NOT_STARTED";restart();driver.advance(f);driver.advance(f);
        assertEquals(1,installs);assertEquals("OUTCOME_UNKNOWN",journal.job(f.spec().id()).get("state"));assertEquals("CLAIMED",operations.progress(f).get("INSTALL"));
    }
    @Test void claimedPhaseCannotRegenerateMissingPrivateInputOrChildLedger()throws Exception{
        factory.ensure(f);claims.claim(f,BUSINESS_INSTALL);
        assertEquals("BOOTSTRAP_BUSINESS_RECORD_MISSING",assertThrows(ControlFailure.class,()->driver.advance(f)).code);assertEquals(0,prepares);
        Files.delete(inputFile());restart();assertEquals("BOOTSTRAP_NATIVE_INPUT_MISSING",assertThrows(ControlFailure.class,()->driver.advance(f)).code);assertEquals(1,inspections);
    }
    @Test void failedInstallIsRetainedEvenWhenItMightBeRetryable(){
        driver.advance(f);remoteState="FAILED";driver.advance(f);assertEquals("FAILED",installation().get("state"));assertEquals("FAILED",journal.job(f.spec().id()).get("state"));
        assertThrows(ControlFailure.class,()->driver.advance(f));assertEquals(1,installs);
    }
    @Test void aNewInstallIntentIsFencedButOriginalObservationCanContinueWithoutFreshPermission(){
        driver.advance(f);when(admission.beforeMembershipIntent(eq(f),any())).thenThrow(new ControlFailure(503,"DEPENDENCY_UNAVAILABLE"));
        assertThrows(ControlFailure.class,()->driver.advance(f));assertEquals(0,installs);assertEquals("PENDING",operations.progress(f).get("INSTALL"));
        when(admission.beforeMembershipIntent(eq(f),any())).thenAnswer(inv->((Supplier<?>)inv.getArgument(1)).get());loseInstall=true;assertThrows(ControlFailure.class,()->driver.advance(f));
        when(admission.beforeMembershipIntent(eq(f),any())).thenThrow(new ControlFailure(503,"DEPENDENCY_UNAVAILABLE"));restart();driver.advance(f);assertEquals("SUCCEEDED",installation().get("state"));assertEquals(1,installs);
    }
    @Test void mismatchedIdentityRestoreAndNetworkSemanticsCannotAdvance(){
        driver.advance(f);remoteState="ACCEPTED";driver.advance(f);var permit=operations.claim(f,ControlBootstrapBusinessOperations.Operation.INSTALL);
        for(var entry:Map.<String,Object>of("allocationSlot",999,"restoreCleanupVerified",false,"restoreEvidenceId","wrong","clusterMembership","ACTIVE","businessHealthRevalidated",true).entrySet()){
            var h=original().header();var b=(Map<?,?>)h.get("business");var value=receipt(h,"INSTALL","SUCCEEDED");
            for(String key:List.of("siteId","instanceId","mode","clusterId","allocationSlot","artifactSha256","httpPort"))value.put(key,b.get(key));
            value.put("root","/opt/eatwhat-sites/"+b.get("serverId"));value.put("restoreEvidenceId","fresh-install-restore-"+h.get("id"));value.put("restoreCleanupVerified",true);value.put("clusterMembership","NOT_JOINED");value.put("priorNetworkRevalidationRequired",true);value.put("businessHealthRevalidated",false);value.put(entry.getKey(),entry.getValue());
            assertEquals("BOOTSTRAP_BUSINESS_INSTALLATION_UNCONFIRMED",assertThrows(ControlFailure.class,()->operations.observe(f,permit,value)).code,entry.getKey());
        }assertEquals("CLAIMED",operations.progress(f).get("INSTALL"));assertNull(installation());
    }
    @Test @SuppressWarnings("unchecked") void replayingAnAlreadyCompletedPhaseDoesNotResetALaterJoin(){
        driver.advance(f);driver.advance(f);store.mutate(data->{var rows=(Map<String,Object>)data.get("servers");var server=(Map<String,Object>)rows.get(f.spec().targetServerId().toString());((Map<String,Object>)server.get("installation")).put("clusterMembership","ACTIVE");return null;});
        restart();driver.advance(f);assertEquals("ACTIVE",installation().get("clusterMembership"));assertEquals(1,installs);assertEquals(1,prepares);
    }
    @Test void terminalReceiptSurvivesAProjectionFailureWithoutRepeatingInstall(){
        driver.advance(f);var failing=mock(ControlServerStore.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(store).defaultAnswer(CALLS_REAL_METHODS));
        doThrow(new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE")).when(failing).mutate(any());driver=new ControlBootstrapBusinessPhases(failing,admission,nativePhases,operations,factory,journal);
        assertThrows(ControlFailure.class,()->driver.advance(f));assertEquals("SUCCEEDED",operations.progress(f).get("INSTALL"));assertNull(installation());
        restart();driver.advance(f);assertEquals(1,installs);assertEquals("SUCCEEDED",installation().get("state"));assertEquals("MEMBER_CONFIGURE",journal.job(f.spec().id()).get("stage"));
    }
    @Test @SuppressWarnings("unchecked") void sameJobIdentifierCannotHideAChangedProjectedIdentity(){
        driver.advance(f);driver.advance(f);String changed=UUID.randomUUID().toString();
        store.mutate(data->{var rows=(Map<String,Object>)data.get("servers");var server=(Map<String,Object>)rows.get(f.spec().targetServerId().toString());((Map<String,Object>)server.get("installation")).put("instanceId",changed);return null;});
        restart();assertEquals("BOOTSTRAP_BUSINESS_INSTALLATION_CHANGED",assertThrows(ControlFailure.class,()->driver.advance(f)).code);
        assertEquals(changed,installation().get("instanceId"));assertEquals(1,installs);
    }
    @Test @SuppressWarnings("unchecked") void uncertainDurableChildWriteGrantsOnlyObservationAfterRestart(){
        var failing=mock(ControlServerStore.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(store).defaultAnswer(CALLS_REAL_METHODS));
        Path ledger=store.directory().resolve("business-operations-"+f.spec().id()+".json");
        doAnswer(inv->{
            var file=(Map<?,?>)inv.getArgument(1);var body=JsonMapper.builder().build().readValue(store.decrypt(file.get("body").toString()),Map.class);
            boolean claimed=((List<Map<String,Object>>)body.get("operations")).stream().anyMatch(row->"CLAIMED".equals(row.get("state")));
            inv.callRealMethod();if(claimed)throw new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE");return null;
        }).when(failing).saveJson(eq(ledger),any());
        operations=new ControlBootstrapBusinessOperations(failing,journal,claims,inputs,nativePhases);driver=new ControlBootstrapBusinessPhases(store,admission,nativePhases,operations,factory,journal);
        assertThrows(ControlFailure.class,()->driver.advance(f));assertEquals(0,prepares);restart();driver.advance(f);
        assertEquals(List.of("inspect","prepare-status"),commands);assertEquals("CLAIMED",operations.progress(f).get("PREPARE"));assertEquals(0,installs);
    }
    @Test void actualBundledPythonPolicyAcceptsTheJavaGeneratedPrivateInput()throws Exception{
        factory.ensure(f);var saved=original();String program="import json,sys,types\nh=json.load(sys.stdin)\nns={'__name__':'reviewed_business_fixture'}\n"+
                "exec(compile(h['agent'],'<bundled-business>','exec'),ns)\np={'__name__':'reviewed_policy_fixture'}\nexec(compile(h['policy'],'<bundled-policy>','exec'),p)\n"+
                "ns['controller']=lambda data:(types.SimpleNamespace(policy=lambda:types.SimpleNamespace(**p)),h['parent'])\n"+
                "ns['validate'](h['header'])\nv=ns['payload'](h['header'])\nassert v['mode']=='EXTERNAL' and 'administrator' not in v\n"+
                "assert 'app.control.enrollment-required=true\\n' in v['properties']\nprint(json.dumps({'valid':True,'mode':v['mode']}))\n";
        var parent=Map.of("sourceAuthority",Map.of("clusterId",f.spec().clusterId().toString()),"bundle",Map.of("catalog",ControlBootstrapControllerCatalog.metadata(f.artifacts())));
        var wire=Map.of("agent",saved.agent(),"header",saved.header(),"policy",ControlServers.resource("controller-bootstrap-controller-policy.py"),"parent",parent);
        byte[] raw=ControlBootstrapNativeJson.encode(wire).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] output=ControlSsh.process(List.of("/usr/bin/python3","-I","-c",program),Map.of(),out->out.write(raw),10,1024,true);
        assertEquals(Map.of("valid",true,"mode","EXTERNAL"),JsonMapper.builder().build().readValue(output,Map.class));
    }
}
