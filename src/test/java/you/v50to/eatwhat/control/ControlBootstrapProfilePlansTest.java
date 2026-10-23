package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Actual captured/rewritten source and target file plans. Runtime/SSH proof
 * and the admission fence are explicit mocks; this never publishes or JOINs. */
class ControlBootstrapProfilePlansTest {
    @TempDir static Path pki;@TempDir Path root;static String ca;
    BootstrapTargetFixture fixture;ControlBootstrapJournal journal;ControlBootstrapNativePhases nativePhases;
    ControlBootstrapMemberRuntime runtime;ControlBootstrapMemberIngress ingress;ControlBootstrapProfilePlans plans;
    @BeforeAll static void certificate()throws Exception{
        Path keytool=Path.of(System.getProperty("java.home"),"bin/keytool"),store=pki.resolve("ca.p12"),cert=pki.resolve("ca.pem");
        for(var args:List.of(List.of("-genkeypair","-alias","ca","-keyalg","EC","-groupname","secp256r1","-dname","CN=Profile Plan Fixture CA","-ext","bc:c","-validity","2","-storetype","PKCS12","-keystore",store.toString(),"-storepass","fixture-only-password","-noprompt"),List.of("-exportcert","-rfc","-alias","ca","-keystore",store.toString(),"-storepass","fixture-only-password","-file",cert.toString()))){
            var cmd=new ArrayList<>(List.of(keytool.toString()));cmd.addAll(args);var p=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();assertTrue(p.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,p.exitValue());
        }ca=Files.readString(cert);
    }
    @BeforeEach void setup()throws Exception{
        fixture=new BootstrapTargetFixture(root);fixture.source.credentials();fixture.source.members();fixture.capture();var f=fixture.frozen;when(f.review()).thenReturn(Map.of("sourceAddress","10.253.74.1","targetAddress","10.253.74.3","targetEndpoint","fixture.invalid","wireguardPort",51820,"httpPort",18090,"appBaseUrl","https://fixture.invalid/api"));
        journal=BootstrapTargetFixture.stub(ControlBootstrapJournal.class);nativePhases=BootstrapTargetFixture.stub(ControlBootstrapNativePhases.class);runtime=BootstrapTargetFixture.stub(ControlBootstrapMemberRuntime.class);ingress=BootstrapTargetFixture.stub(ControlBootstrapMemberIngress.class);
        UUID id=f.spec().id();when(journal.job(id)).thenReturn(Map.of("requestHash","a".repeat(64)));when(journal.approvedPermissions(id)).thenReturn(Set.of("INSTALL_BUSINESS_AND_JOIN"));
        when(journal.targetStorageProperties(f)).thenReturn(Map.of());
        var factory=new ControlBootstrapControllerFactory(fixture.network,null,null,null,journal);var bundle=factory.bundle(f,fixture.network.plan(f));
        var controller=new ControlBootstrapNativeInputs.Sealed(id,CONTROLLER_CONFIG,"a".repeat(64),f.spec().targetServerId(),1,f.targetRoute(),Map.of("bundle",bundle),"inert","c".repeat(64));
        var m=ControlBootstrapBusinessAllocation.identity(f.spec());var business=new ControlBootstrapNativeInputs.Sealed(id,BUSINESS_INSTALL,"a".repeat(64),f.spec().targetServerId(),1,f.targetRoute(),
                Map.of("business",Map.of("serverId",f.spec().targetServerId().toString(),"siteId",m.siteId(),"instanceId",m.instanceId().toString(),"nodeToken","n".repeat(64))),"inert","c".repeat(64));
        when(nativePhases.saved(f,CONTROLLER_CONFIG,"a".repeat(64))).thenReturn(controller);when(nativePhases.saved(f,BUSINESS_INSTALL,"a".repeat(64))).thenReturn(business);
        when(runtime.ready(f)).thenReturn(Map.of("state","CONFIGURED"));when(ingress.materials(f)).thenReturn(Map.of("targetAddress","10.253.74.3","caPem",ca));
        when(fixture.admission.beforeMembershipIntent(eq(f),any())).thenAnswer(inv->((Supplier<?>)inv.getArgument(1)).get());
        plans=new ControlBootstrapProfilePlans(fixture.admission,journal,nativePhases,runtime,ingress);
    }
    @Test void bothPlansPreserveTheirOwnOriginalRoutesAndShareOnlyTheNewMemberMaterial()throws Exception{
        var pair=plans.prepare(fixture.frozen);var json=JsonMapper.builder().build();String member=ControlBootstrapBusinessAllocation.identity(fixture.frozen.spec()).siteId();
        var source=json.readTree(Base64.getDecoder().decode(pair.source().afterBase64()));var target=json.readTree(Base64.getDecoder().decode(pair.target().afterBase64()));
        assertEquals("http://127.0.0.1:18090",source.path("campus").path("origin").asText());assertEquals("http://127.0.0.1:28090",target.path("campus").path("origin").asText());
        assertEquals("https://10.253.74.3:28490",source.path(member).path("origin").asText());assertEquals(source.path(member).path("origin"),target.path(member).path("origin"));assertEquals("",source.path(member).path("apiPrefix").asText());
        assertEquals(2,pair.source().additionsBase64().size());assertEquals(2,pair.target().additionsBase64().size());
        assertFalse(pair.toString().contains("n".repeat(64)));assertFalse(pair.source().toString().contains(ca));
        assertArrayEquals(Base64.getDecoder().decode(pair.source().beforeBase64()),Files.readAllBytes(Path.of(pair.source().profilesFile())));
        verify(fixture.admission).beforeMembershipIntent(eq(fixture.frozen),any());
    }
    @Test void wrongIngressTargetAndMissingScopeCannotCreateReplacementPlans(){
        var f=fixture.frozen;when(ingress.materials(f)).thenReturn(Map.of("targetAddress","10.253.74.99","caPem",ca));assertThrows(ControlFailure.class,()->plans.prepare(f));
        UUID id=f.spec().id();when(journal.approvedPermissions(id)).thenReturn(Set.of());assertEquals("BOOTSTRAP_PROFILE_SCOPE_REQUIRED",assertThrows(ControlFailure.class,()->plans.prepare(f)).code);
    }
    @Test void unverifiedRuntimeAndChangedAdministratorBlockThePlan(){
        var f=fixture.frozen;when(runtime.ready(f)).thenThrow(new ControlFailure(409,"RUNTIME_CHANGED"));assertThrows(ControlFailure.class,()->plans.prepare(f));
        doReturn(Map.of("state","CONFIGURED")).when(runtime).ready(f);when(fixture.admission.beforeMembershipIntent(eq(f),any())).thenThrow(new ControlFailure(403,"ADMIN_REQUIRED"));assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->plans.prepare(f)).code);
    }
    @Test void exactPlannedSourceCaptureIncludesTheVirtualPrivateFileIndex()throws Exception{
        var pair=plans.prepare(fixture.frozen);var result=you.v50to.eatwhat.cluster.ClusterProfilePublication.publish(pair.source(),Runnable::run);assertEquals("FILES_PUBLISHED",result.get("state"));
        assertTrue(pair.sourceCaptureFileHashes().containsKey(you.v50to.eatwhat.control.bootstrap.ControllerSourceMaterials.INDEX));
        var observed=you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot.capture(fixture.source.root,pair.sourceCaptureRequest().expected());
        assertEquals(pair.sourceCaptureFileHashes(),observed.hashes());assertEquals(fixture.frozen.sourceRequest().expected().applicationSha256(),pair.sourceCaptureRequest().expected().applicationSha256());
        assertEquals(fixture.frozen.sourceRequest().expected().epoch(),pair.sourceCaptureRequest().expected().epoch());
    }
}
