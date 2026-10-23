package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ControlBootstrapPreflightsTest {
    @TempDir Path root;
    ControlServerStore store;ControlServers servers;ControlSsh ssh;ControlBootstrapPreflights preflights;
    UUID id=UUID.randomUUID(),requestId=UUID.randomUUID();Map<String,Object> request,response;
    @BeforeEach void setup()throws Exception{
        store=new ControlServerStore(new ControlAudit(root.toRealPath()));servers=mock(ControlServers.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));ssh=mock(ControlSsh.class);
        store.mutate(data->{((Map<String,Object>)data.get("servers")).put(id.toString(),new LinkedHashMap<>(Map.of("id",id.toString(),"revision",1,"username","root")));return null;});
        request=Map.of("id",requestId.toString(),"role","SOURCE","sourceServerId",id.toString());
        response=new LinkedHashMap<>(Map.of("protocol","eatwhat-controller-bootstrap-preflight-v1","id",requestId.toString(),"role","SOURCE","ready",true,
                "effectsStarted",false,"proof","a".repeat(64),"facts",Map.of("host",Map.of("_privateFiles",Map.of("owned-source-file","private-hash"))),"plannedEffects",Map.of("synthetic",true)));
        when(ssh.routeBinding(anyMap())).thenReturn("route-bound");when(ssh.call(anyMap(),anyString(),anyList(),any(),anyInt())).thenReturn(response);
        preflights=new ControlBootstrapPreflights(store,servers,ssh);
    }
    @Test void sourceCaptureUsesOnlyRegisteredPinnedSshAndReadonlyAgent()throws Exception{
        var c=preflights.capture(id,1,request);assertEquals("route-bound",c.routeBinding());assertEquals(request,c.request());assertEquals("BootstrapPreflight[redacted]",c.toString());
        var args=org.mockito.ArgumentCaptor.forClass(ControlSsh.Upload.class);verify(ssh).call(anyMap(),eq(ControlServers.resource("controller-bootstrap-preflight.py")),eq(List.of("inspect")),args.capture(),eq(45));
        var input=new java.io.ByteArrayOutputStream();args.getValue().write(input);assertTrue(input.toString(java.nio.charset.StandardCharsets.UTF_8).contains(requestId.toString()));
    }
    @Test void changedRoleOrRevisionFailsBeforeRemoteRead()throws Exception{
        assertThrows(ControlFailure.class,()->preflights.capture(id,2,request));assertThrows(ControlFailure.class,()->preflights.capture(id,1,Map.of("role","TARGET","targetServerId",UUID.randomUUID().toString())));
        verify(ssh,never()).call(anyMap(),anyString(),anyList(),any(),anyInt());
    }
    @Test void sshArgumentsReachTheActualPythonReadonlyInputValidator()throws Exception{
        // Run the real CLI with invalid input: validation rejects before any
        // operating-system inventory. A missing mode instead yields the exact
        // BOOTSTRAP_INSPECT_ONLY refusal found by the real Linux/API flow.
        var command=new java.util.ArrayList<>(List.of("python3","-I","-B","-c",ControlServers.resource("controller-bootstrap-preflight.py")));
        doAnswer(invocation->{command.addAll(invocation.<List<String>>getArgument(2));return response;})
                .when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
        preflights.capture(id,1,request);
        var process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try(var input=process.getOutputStream()){input.write("{}".getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
        assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,process.exitValue());
        var reply=tools.jackson.databind.json.JsonMapper.builder().build().readTree(process.getInputStream().readAllBytes());
        assertEquals("BOOTSTRAP_INVALID_INPUT",reply.path("code").asText());
        assertFalse(reply.path("effectsStarted").asBoolean());
    }
    @Test void sourceFactsDriftCannotReuseInitialApproval(){
        var initial=preflights.capture(id,1,request);response.put("proof","b".repeat(64));
        assertEquals("BOOTSTRAP_PLAN_CHANGED",assertThrows(ControlFailure.class,()->preflights.requireInitialUnchanged(initial)).code);
    }
    @Test void endpointRouteDriftDuringCaptureIsRejected(){
        when(ssh.routeBinding(anyMap())).thenReturn("original","changed");assertEquals("SSH_ROUTE_BINDING_CHANGED",assertThrows(ControlFailure.class,()->preflights.capture(id,1,request)).code);
    }
    @Test void acceptedTaskRepeatsInventoryUnderHeldLeaseWithoutReopeningNewWork(){
        var initial=preflights.capture(id,1,request);clearInvocations(servers);
        doThrow(new ControlFailure(409,"CONTROLLER_BOOTSTRAP_RUNNING")).when(servers).requireWrite();
        assertDoesNotThrow(()->preflights.requireInitialExecutingUnchanged(initial));
        verify(servers,times(2)).requireExecuting();verify(servers,never()).requireWrite();
        assertEquals("CONTROLLER_BOOTSTRAP_RUNNING",assertThrows(ControlFailure.class,()->preflights.requireInitialUnchanged(initial)).code);
        doThrow(new ControlFailure(503,"CONTROL_LEASE_NOT_HELD")).when(servers).requireExecuting();
        assertEquals("CONTROL_LEASE_NOT_HELD",assertThrows(ControlFailure.class,()->preflights.requireInitialExecutingUnchanged(initial)).code);
    }
    @Test void knownRefusalIsSafeButNetworkOutputCannotLeak()throws Exception{
        doThrow(new ControlSsh.RemoteRefusal("BOOTSTRAP_TARGET_CONTROLLER_RETAINED")).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
        assertEquals("BOOTSTRAP_TARGET_CONTROLLER_RETAINED",assertThrows(ControlFailure.class,()->preflights.capture(id,1,request)).code);
        doThrow(new java.io.IOException("private-password-output")).when(ssh).call(anyMap(),anyString(),anyList(),any(),anyInt());
        var failure=assertThrows(ControlFailure.class,()->preflights.capture(id,1,request));assertEquals("BOOTSTRAP_PREFLIGHT_UNAVAILABLE",failure.code);assertNull(failure.getCause());
    }
    @Test void effectfulOrUnboundProbeCannotBeAccepted(){
        response.put("effectsStarted",true);assertThrows(ControlFailure.class,()->preflights.capture(id,1,request));
        response.put("effectsStarted",false);response.put("id",UUID.randomUUID().toString());assertThrows(ControlFailure.class,()->preflights.capture(id,1,request));
    }
}
