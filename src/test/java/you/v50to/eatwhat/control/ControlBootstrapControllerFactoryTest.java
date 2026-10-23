package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Real derived bundle, pure Python policy, encrypted sealing and original-input
 * reuse. Admission/dependency receipts and remote inspect are explicit mocks. */
class ControlBootstrapControllerFactoryTest {
    @TempDir Path root;
    BootstrapTargetFixture fixture;ControlBootstrapJournal journal;ControlBootstrapNativeInputs inputs;
    ControlBootstrapNativePhases nativePhases;ControlBootstrapDependencyPhases dependencies;ControlBootstrapControllerFactory factory;
    final String approval="c".repeat(64);Map<String,Object> job;Path inputFile;
    @BeforeEach void setup()throws Exception{
        fixture=new BootstrapTargetFixture(root);fixture.extraProfiles();journal=BootstrapTargetFixture.stub(ControlBootstrapJournal.class);
        nativePhases=BootstrapTargetFixture.stub(ControlBootstrapNativePhases.class);dependencies=BootstrapTargetFixture.stub(ControlBootstrapDependencyPhases.class);
        inputs=new ControlBootstrapNativeInputs(new ControlServerStore(new ControlAudit(root.resolve("manager-state"))));
        var steps=new ArrayList<Map<String,Object>>();for(var phase:values())steps.add(new HashMap<>(Map.of("state","PENDING")));
        job=new LinkedHashMap<>(Map.of("requestHash",approval,"steps",steps));when(journal.job(fixture.frozen.spec().id())).thenReturn(job);
        when(nativePhases.server(fixture.frozen,CONTROLLER_CONFIG)).thenReturn(fixture.targetServer);
        when(nativePhases.call(anyMap(),anyString(),eq(List.of("inspect")),anyMap())).thenAnswer(inv->{
            var header=(Map<?,?>)inv.getArgument(3);assertFalse(header.containsKey("agent"));assertFalse(header.containsKey("expectedProof"));
            return Map.of("protocol",CONTROLLER_CONFIG.protocol,"id",header.get("id"),"effectsStarted",false,"proof","d".repeat(64));
        });
        dependency(fixture.network.plan(fixture.frozen).forwards());
        factory=new ControlBootstrapControllerFactory(fixture.network,dependencies,nativePhases,inputs,journal);
        inputFile=root.resolve("manager-state/controller-native-"+fixture.frozen.spec().id()+"-CONTROLLER_CONFIG.json");
    }
    void dependency(List<Map<String,Object>> forwards){
        var f=fixture.frozen;var header=Map.<String,Object>of("id",ControlBootstrapJournal.phaseId(f.spec().id(),DEPENDENCY_LINK).toString(),"forwards",forwards,"agentSha256","b".repeat(64));
        var sealed=new ControlBootstrapNativeInputs.Sealed(f.spec().id(),DEPENDENCY_LINK,approval,f.spec().targetServerId(),1,f.targetRoute(),header,"fixture-agent","a".repeat(64));
        when(dependencies.completedTarget(f,approval)).thenReturn(sealed);
    }
    ControlBootstrapNativeInputs.Sealed load(){var f=fixture.frozen;return inputs.load(f.spec().id(),CONTROLLER_CONFIG,approval,f.spec().targetServerId(),1,f.targetRoute());}
    @Test @SuppressWarnings("unchecked") void actualFactoryCreatesTheCompleteTargetHeaderWithoutCallerConfiguration()throws Exception{
        factory.ensure(fixture.frozen);var sealed=load();var bundle=(Map<String,Object>)sealed.header().get("bundle");var values=(Map<String,String>)bundle.get("properties");
        assertEquals("127.0.0.1",values.get("server.address"));assertEquals("18081",values.get("server.port"));assertEquals("false",values.get("app.cluster.control-lease.automatic-takeover"));
        assertEquals("jdbc:postgresql://127.0.0.1:25432/eatwhat?sslmode=disable",values.get("spring.datasource.url"));assertEquals("existing-authority",values.get("app.control.database-mode"));
        assertEquals("true",values.get("app.control.controller-bootstrap.enabled"));assertEquals("REGISTERED_SERVERS",values.get("app.control.deployment.target-source"));
        assertFalse(values.containsKey("spring.flyway.password"));assertFalse(values.toString().contains(root.toString()));
        var files=(Map<String,String>)bundle.get("privateFiles");var profiles=fixture.source.json.readValue(Base64.getDecoder().decode(files.get("cluster-profiles.json")),Map.class);
        assertEquals("http://127.0.0.1:28100",((Map<?,?>)profiles.get("external")).get("origin"));
        var routes=fixture.source.json.readValue(Base64.getDecoder().decode(files.get(ControlBootstrapControllerFactory.ROUTES)),Map.class);
        assertEquals(28300,((Map<?,?>)((Map<?,?>)routes.get("routes")).get(fixture.sourceId.toString())).get("port"));
        assertEquals(ControlBootstrapJournal.phaseId(fixture.frozen.spec().id(),CONTROLLER_VERIFY).toString(),sealed.header().get("verifyId"));
        assertFalse(Files.readString(inputFile).contains("runtime-private-secret"));verify(nativePhases,times(1)).call(anyMap(),anyString(),eq(List.of("inspect")),anyMap());
    }
    @Test void aRestartReusesTheExactOriginalInputAndAgentWithoutReinspection()throws Exception{
        factory.ensure(fixture.frozen);byte[] before=Files.readAllBytes(inputFile);var original=load();
        factory=new ControlBootstrapControllerFactory(fixture.network,dependencies,nativePhases,inputs,journal);factory.ensure(fixture.frozen);
        assertArrayEquals(before,Files.readAllBytes(inputFile));assertEquals(original,load());verify(dependencies,times(1)).completedTarget(any(),anyString());
        verify(nativePhases,times(1)).call(anyMap(),anyString(),eq(List.of("inspect")),anyMap());
    }
    @Test @SuppressWarnings("unchecked") void aClaimedPhaseWithoutInputsCannotGenerateAReplacement(){
        ((List<Map<String,Object>>)job.get("steps")).get(CONTROLLER_CONFIG.ordinal()).put("state","DISPATCHED");
        assertEquals("BOOTSTRAP_NATIVE_INPUT_MISSING",assertThrows(ControlFailure.class,()->factory.ensure(fixture.frozen)).code);
        verifyNoInteractions(dependencies);verify(nativePhases,never()).call(anyMap(),anyString(),anyList(),anyMap());assertFalse(Files.exists(inputFile));
    }
    @Test void theHeaderCannotChangeTheCompletedTunnelPortPlan(){
        dependency(List.of(Map.of("purpose","CONTROL_DATABASE","localPort",25432,"remotePort",9999)));
        assertEquals("BOOTSTRAP_TARGET_DEPENDENCY_PLAN_CHANGED",assertThrows(ControlFailure.class,()->factory.ensure(fixture.frozen)).code);
        assertFalse(Files.exists(inputFile));verify(nativePhases,never()).call(anyMap(),anyString(),anyList(),anyMap());
    }
    @Test void aNonReadonlyOrMismatchedInspectReplyCannotBeSealed(){
        when(nativePhases.call(anyMap(),anyString(),anyList(),anyMap())).thenReturn(Map.of("protocol",CONTROLLER_CONFIG.protocol,"id","wrong","effectsStarted",true,"proof","d".repeat(64)));
        assertEquals("BOOTSTRAP_NATIVE_RECEIPT_MISMATCH",assertThrows(ControlFailure.class,()->factory.ensure(fixture.frozen)).code);assertFalse(Files.exists(inputFile));
    }
}
