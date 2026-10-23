package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ControlBootstrapSourcesTest {
    @TempDir Path root;
    ControlServerStore store;ControlServers servers;ControlSsh ssh;ControlBootstrapSources service;
    UUID id=UUID.randomUUID();Map<String,Object> server,response;ControlBootstrapSources.Request request;
    @BeforeEach void setup()throws Exception{
        store=new ControlServerStore(new ControlAudit(root.toRealPath()));servers=mock(ControlServers.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));ssh=mock(ControlSsh.class);
        server=new LinkedHashMap<>(Map.of("id",id.toString(),"revision",1,"username","ubuntu","privilege","SUDO"));
        store.mutate(data->{((Map<String,Object>)data.get("servers")).put(id.toString(),server);return null;});
        var expected=new ControllerSourceSnapshot.Expected(UUID.randomUUID(),"campus",UUID.randomUUID(),"a".repeat(32),UUID.randomUUID(),2,"campus-control","b".repeat(64),"c".repeat(64),"d".repeat(64));
        request=new ControlBootstrapSources.Request("e".repeat(64),"f".repeat(64),"0".repeat(64),expected);
        response=new LinkedHashMap<>(Map.of("protocol",ControllerSourceSnapshot.PROTOCOL,"captureBinding","1".repeat(64),
                "sourceAuthority",Map.of("clusterId",expected.clusterId().toString(),"siteId","campus","instanceId",expected.authorityInstanceId().toString(),"databaseFingerprint","a".repeat(32)),
                "proof",Map.of("epoch",2,"readOnlyTransaction",true,"actualDatabaseNameAndOidVerified",true),"fileHashes",Map.of(),"sourceProcess",Map.of("pid",42),
                "_private",Map.of("properties",Map.of("spring.datasource.password","source-private-value"),"privateFiles",Map.of())));
        when(ssh.routeBinding(anyMap())).thenReturn("opaque-route-binding");
        when(ssh.call(anyMap(),anyString(),anyList(),any(),anyInt())).thenAnswer(call->{
            assertTrue(Set.of(List.of(),List.of("profile-receiver")).contains(call.getArgument(2)));assertEquals(50,(int)call.getArgument(4));
            var input=new java.io.ByteArrayOutputStream();((ControlSsh.Upload)call.getArgument(3)).write(input);
            var parsed=JsonMapper.builder().build().readTree(input.toByteArray());assertEquals(request.sourceArtifactSha256(),parsed.path("sourceArtifactSha256").asText());
            return response;
        });
        service=new ControlBootstrapSources(store,servers,ssh);
    }
    ControllerSourceSnapshot.Authority authority(){var e=request.expected();return new ControllerSourceSnapshot.Authority(e.clusterId(),e.authoritySiteId(),e.authorityInstanceId(),e.authorityDatabaseFingerprint(),e.stateId(),e.epoch(),e.sharedKeySha256());}
    Map<String,Object> discovered;
    void discovery()throws Exception{
        request=new ControlBootstrapSources.Request(request.sourceArtifactSha256(),request.sourceArtifactSha256(),request.unitSha256(),request.expected());
        discovered=new LinkedHashMap<>(Map.of("protocol","eatwhat-controller-source-discovery-v1","request",JsonMapper.builder().build().readValue(JsonMapper.builder().build().writeValueAsBytes(request),Map.class),"capture",response));
        doAnswer(inv->{
            assertEquals(List.of("discover"),inv.getArgument(2));assertEquals(80,(int)inv.getArgument(4));
            var out=new java.io.ByteArrayOutputStream();((ControlSsh.Upload)inv.getArgument(3)).write(out);
            var tree=JsonMapper.builder().build().readTree(out.toByteArray());assertEquals(Set.of("authority"),new HashSet<>(tree.propertyNames()));
            assertFalse(out.toString().contains("sourceArtifactSha256"));assertFalse(out.toString().contains("private-value"));return discovered;
        }).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
    }
    @Test void discoveryUsesOnlyTrustedAuthorityAndReturnsAPrivateCapture()throws Exception{
        discovery();var result=service.discover(id,1,authority());assertEquals(request,result.request());assertEquals("BootstrapSourceDiscovery[redacted]",result.toString());
        assertEquals("source-private-value",((Map<?,?>)result.captured().privatePayload().get("properties")).get("spring.datasource.password"));
        assertFalse(JsonMapper.builder().build().writeValueAsString(result).contains("source-private-value"));verify(servers,times(2)).requireWrite();
    }
    @Test void discoveryRefusesForeignAuthorityOrEpochAndArbitraryHeaderOptions()throws Exception{
        discovery();var header=(Map<String,Object>)discovered.get("request");var expected=(Map<String,Object>)header.get("expected");
        expected.put("sharedKeySha256","0".repeat(64));assertEquals("BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE",assertThrows(ControlFailure.class,()->service.discover(id,1,authority())).code);
        expected.put("sharedKeySha256",authority().sharedKeySha256());header.put("path","/private/other");assertThrows(ControlFailure.class,()->service.discover(id,1,authority()));header.remove("path");
        response.put("proof",Map.of("epoch",3,"readOnlyTransaction",true,"actualDatabaseNameAndOidVerified",true));assertThrows(ControlFailure.class,()->service.discover(id,1,authority()));
    }
    @Test void discoveryChecksRootRevisionAndAnchorBeforeConnecting()throws Exception{
        discovery();assertEquals("SERVER_REVISION_CHANGED",assertThrows(ControlFailure.class,()->service.discover(id,2,authority())).code);
        assertEquals("BOOTSTRAP_INVALID_SOURCE_AUTHORITY",assertThrows(ControlFailure.class,()->service.discover(id,1,null)).code);
        store.mutate(data->{((Map<String,Object>)((Map<?,?>)data.get("servers")).get(id.toString())).put("privilege","NONE");return null;});
        assertEquals("BOOTSTRAP_LINUX_ROOT_REQUIRED",assertThrows(ControlFailure.class,()->service.discover(id,1,authority())).code);verify(ssh,never()).call(anyMap(),anyString(),anyList(),any(),anyInt());
    }
    @Test void discoveryDoesNotAdoptAChangedPhysicalRoute()throws Exception{
        discovery();when(ssh.routeBinding(anyMap())).thenReturn("old","new");assertEquals("SSH_ROUTE_BINDING_CHANGED",assertThrows(ControlFailure.class,()->service.discover(id,1,authority())).code);
    }
    @Test void olderSourceDiscoveryCapabilityHasASafeActionableRefusal()throws Exception{
        discovery();doThrow(new ControlSsh.RemoteRefusal("BOOTSTRAP_SOURCE_DISCOVERY_REQUIRED")).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
        assertEquals("BOOTSTRAP_SOURCE_DISCOVERY_REQUIRED",assertThrows(ControlFailure.class,()->service.discover(id,1,authority())).code);
    }
    @Test void pinnedCollectionKeepsSecretsOutOfEvidenceToStringAndJson()throws Exception{
        var capture=service.capture(id,1,request);assertTrue(capture.privatePayload().toString().contains("source-private-value"));
        verify(ssh).call(anyMap(),anyString(),eq(List.of("profile-receiver")),any(),eq(50));
        assertFalse(capture.evidence().toString().contains("source-private-value"));assertEquals("BootstrapSourceCapture[redacted]",capture.toString());
        assertFalse(JsonMapper.builder().build().writeValueAsString(capture).contains("source-private-value"));
        verify(servers,times(2)).requireWrite();service.requireRegisteredBinding(capture);
    }
    @Test void serverRevisionIsCheckedBeforeAnyConnection()throws Exception{
        assertEquals("SERVER_REVISION_CHANGED",assertThrows(ControlFailure.class,()->service.capture(id,2,request)).code);
        verify(ssh,never()).call(anyMap(),anyString(),anyList(),any(),anyInt());
    }
    @Test void noRootExecutionCannotCollectSource()throws Exception{
        store.mutate(data->{((Map<String,Object>)((Map<?,?>)data.get("servers")).get(id.toString())).put("privilege","NONE");return null;});
        assertEquals("BOOTSTRAP_LINUX_ROOT_REQUIRED",assertThrows(ControlFailure.class,()->service.capture(id,1,request)).code);
        verify(ssh,never()).call(anyMap(),anyString(),anyList(),any(),anyInt());
    }
    @Test void physicalRouteChangeDuringCollectionInvalidatesResult(){
        when(ssh.routeBinding(anyMap())).thenReturn("old","new");
        assertEquals("SSH_ROUTE_BINDING_CHANGED",assertThrows(ControlFailure.class,()->service.capture(id,1,request)).code);
    }
    @Test void sourceIdentityOrEpochMismatchCannotEnterPrivateJournal(){
        response.put("proof",Map.of("epoch",3,"readOnlyTransaction",true,"actualDatabaseNameAndOidVerified",true));
        assertEquals("BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE",assertThrows(ControlFailure.class,()->service.capture(id,1,request)).code);
        response.put("proof",Map.of("epoch",2,"readOnlyTransaction",true,"actualDatabaseNameAndOidVerified",true));response.put("sourceAuthority",Map.of());
        assertThrows(ControlFailure.class,()->service.capture(id,1,request));
    }
    @Test void lostSshResponseIsGenericAndDoesNotRetainCredentialsInError()throws Exception{
        doThrow(new java.io.IOException("source-private-value")).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
        var e=assertThrows(ControlFailure.class,()->service.capture(id,1,request));assertEquals("BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE",e.code);assertNull(e.getCause());
    }
    @Test void acceptedCaptureRechecksRevisionAndRouteBeforeFutureDispatch(){
        var capture=service.capture(id,1,request);when(ssh.routeBinding(anyMap())).thenReturn("another-route");
        assertEquals("SSH_ROUTE_BINDING_CHANGED",assertThrows(ControlFailure.class,()->service.requireRegisteredBinding(capture)).code);
        store.mutate(data->{((Map<String,Object>)((Map<?,?>)data.get("servers")).get(id.toString())).put("revision",2);return null;});
        assertEquals("SERVER_REVISION_CHANGED",assertThrows(ControlFailure.class,()->service.requireRegisteredBinding(capture)).code);
    }
    @Test void approvedJobObservationUsesHeldLeaseRatherThanNewPlanAdmission(){
        var result=service.observeExecuting(id,1,request);assertEquals(id,result.serverId());
        verify(servers,never()).requireWrite();verify(servers,times(2)).requireExecuting();
    }
    @Test void originalObservationDoesNotSilentlyBecomeANewReceiverAdmission()throws Exception{
        service.observeExecuting(id,1,request);verify(ssh).call(anyMap(),anyString(),eq(List.of()),any(),eq(50));
    }
    @Test void missingSourceReceiverIsReportedBeforeCreatingAPlan()throws Exception{
        doThrow(new ControlSsh.RemoteRefusal("BOOTSTRAP_SOURCE_PROFILE_RECEIVER_REQUIRED")).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
        assertEquals("BOOTSTRAP_SOURCE_PROFILE_RECEIVER_REQUIRED",assertThrows(ControlFailure.class,()->service.capture(id,1,request)).code);
    }
    @Test void confirmedConfigurationDriftRemainsADistinctSafeCode()throws Exception{
        doThrow(new ControlSsh.RemoteRefusal("BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED")).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
        assertEquals("BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED",assertThrows(ControlFailure.class,()->service.observeExecuting(id,1,request)).code);
    }
    @Test void sourceProfileStorageRefusalsRemainActionableWithoutPrivateDetails()throws Exception{
        for(String code:List.of("BOOTSTRAP_SOURCE_PROFILE_DIRECTORY_REQUIRED","BOOTSTRAP_SOURCE_PROFILE_STORAGE_READ_ONLY")){
            doThrow(new ControlSsh.RemoteRefusal(code)).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
            var failure=assertThrows(ControlFailure.class,()->service.capture(id,1,request));assertEquals(code,failure.code);assertNull(failure.getCause());
        }
    }
    void inspectionReply()throws Exception{
        var safe=new LinkedHashMap<>(response);safe.remove("_private");safe.put("sourceProtocol",ControllerSourceSnapshot.PROTOCOL);safe.put("protocol",ControlBootstrapSources.INSPECTION_PROTOCOL);safe.put("privateMaterialIncluded",false);
        doAnswer(inv->{assertEquals(List.of("inspection"),inv.getArgument(2));assertEquals(50,(int)inv.getArgument(4));
            var raw=new java.io.ByteArrayOutputStream();((ControlSsh.Upload)inv.getArgument(3)).write(raw);
            assertEquals(JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsBytes(request)),JsonMapper.builder().build().readTree(raw.toByteArray()));return safe;
        }).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());response=safe;
    }
    @Test void independentInspectionUsesOnlyFixedReadModeAndNeverRequiresExecutionAuthority()throws Exception{
        inspectionReply();var seen=service.inspectOriginal(id,1,"opaque-route-binding",request);assertTrue(seen.privatePayload().isEmpty());assertFalse(seen.toString().contains("source-private-value"));verifyNoInteractions(servers);
    }
    @Test void independentInspectionCannotContactAChangedOriginalRoute()throws Exception{
        inspectionReply();assertEquals("SSH_ROUTE_BINDING_CHANGED",assertThrows(ControlFailure.class,()->service.inspectOriginal(id,1,"different-original-route",request)).code);
        verify(ssh,never()).call(anyMap(),anyString(),anyList(),any(),anyInt());
        when(ssh.routeBinding(anyMap())).thenReturn("opaque-route-binding","changed-after-read");
        assertEquals("SSH_ROUTE_BINDING_CHANGED",assertThrows(ControlFailure.class,()->service.inspectOriginal(id,1,"opaque-route-binding",request)).code);
    }
    @Test void independentInspectionRejectsPrivateMaterialOrWrongSourceIdentity()throws Exception{
        inspectionReply();response.put("_private",Map.of("properties",Map.of("password","never-public")));
        assertEquals("BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE",assertThrows(ControlFailure.class,()->service.inspectOriginal(id,1,"opaque-route-binding",request)).code);
        response.remove("_private");response.put("sourceAuthority",Map.of());
        assertEquals("BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE",assertThrows(ControlFailure.class,()->service.inspectOriginal(id,1,"opaque-route-binding",request)).code);
    }
    @Test void actualPythonInspectionEntryIsReadOnlyAndDropsPrivateMaterialWhileLegacyModesStayCompatible()throws Exception{
        String program="""
            import contextlib,io,json,sys
            x=json.load(sys.stdin);n={'__name__':'source_observer_contract'};exec(compile(x['source'],'<source-observer>','exec'),n)
            fixture={'protocol':n['PROTOCOL'],'proof':{},'sourceAuthority':{},'fileHashes':{},'sourceProcess':{},'captureBinding':'a'*64,'_private':{'properties':{'password':'never-public'},'privateFiles':{}}}
            for mode in [[],['profile-receiver'],['inspection']]:
                seen=[]
                def collect(request,accepting=False):
                    seen.append(accepting);return dict(fixture)
                n['collect']=collect;sys.argv=['observer']+mode;sys.stdin=io.TextIOWrapper(io.BytesIO(b'{}'));out=io.StringIO()
                with contextlib.redirect_stdout(out):n['main']()
                value=json.loads(out.getvalue());assert seen==[mode==['profile-receiver']]
                if mode==['inspection']:
                    assert set(value)=={'protocol','sourceProtocol','proof','sourceAuthority','fileHashes','sourceProcess','captureBinding','privateMaterialIncluded'}
                    assert value['protocol']=='eatwhat-controller-source-inspection-v1' and value['privateMaterialIncluded'] is False and 'never-public' not in out.getvalue()
                else:assert value['_private']==fixture['_private']
            print('PASS')
            """;
        byte[] input=JsonMapper.builder().build().writeValueAsBytes(Map.of("source",ControlServers.resource("controller-bootstrap-source.py")));
        assertEquals("PASS",new String(ControlSsh.process(List.of("/usr/bin/python3","-I","-c",program),Map.of(),out->out.write(input),20,1024,true)).strip());
    }
}
