package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.control.bootstrap.*;
import you.v50to.eatwhat.support.SourceMaterialFixture;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual source capture and target JSON rewriting; admission signature is an
 * explicit mock. This does not prove source TLS, target routes or installation. */
class ControlBootstrapTargetMaterialsTest {
    @TempDir Path root;
    final JsonMapper json=JsonMapper.builder().build();
    record Harness(ControlBootstrapAdmission admission,ControlBootstrapAdmission.Frozen frozen,ControlBootstrapTargetMaterials service){}
    Harness harness(ControllerSourceSnapshot.Captured capture){
        var admission=mock(ControlBootstrapAdmission.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var frozen=mock(ControlBootstrapAdmission.Frozen.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(frozen.sourceEvidence()).thenReturn(Map.of("fileHashes",capture.hashes()));
        when(admission.privateSourcePayload(frozen)).thenReturn(Map.of("properties",capture.properties(),"privateFiles",capture.privateFiles()));
        return new Harness(admission,frozen,new ControlBootstrapTargetMaterials(admission));
    }
    @SuppressWarnings("unchecked") Map<String,Object> file(ControlBootstrapTargetMaterials.Prepared p,String property){
        String name=Path.of(p.pathOverrides().get(property)).getFileName().toString();return json.readValue(Base64.getDecoder().decode(p.privateFiles().get(name)),Map.class);
    }
    @Test void credentialAndMemberReferencePathsAreRewrittenWhileSecretBytesStayExact()throws Exception{
        var f=new SourceMaterialFixture(root);f.credentials();f.members();var h=harness(f.capture());var prepared=h.service().prepare(h.frozen());
        var credentials=file(prepared,"app.control.servers.credentials-file");var entry=(Map<?,?>)((Map<?,?>)credentials.get("credentials")).get("remote-admin");String target=entry.get("file").toString();
        assertTrue(target.startsWith("/opt/eatwhat-control/config/bootstrap-"));assertArrayEquals(Files.readAllBytes(f.key),Base64.getDecoder().decode(prepared.privateFiles().get(Path.of(target).getFileName().toString())));
        var members=file(prepared,"app.control.servers.member-configurations-file");var member=(Map<?,?>)((Map<?,?>)members.get("servers")).get(f.server.toString());
        for(String field:List.of("authPasswordFile","authCaFile"))assertTrue(member.get(field).toString().startsWith("/opt/eatwhat-control/config/bootstrap-"));
        assertEquals("jdbc:postgresql://127.0.0.1:25432/eatwhat",member.get("authJdbcOrigin"));assertFalse(prepared.privateFiles().containsKey(ControllerSourceMaterials.INDEX));
        verify(h.admission()).requireBound(h.frozen());
    }
    @Test void sourcePhysicalRoutesAreWithheldForExplicitTargetGeneration()throws Exception{
        var f=new SourceMaterialFixture(root);f.routes();var capture=f.capture();var h=harness(capture);var prepared=h.service().prepare(h.frozen());
        assertEquals(1,prepared.sourceRoutes().size());assertFalse(prepared.pathOverrides().containsKey("app.control.servers.ssh-routes-file"));
        for(String value:prepared.privateFiles().values())assertFalse(new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8).contains("source-only.internal"));
        assertEquals(List.of("campus"),prepared.profilesRequiringRouteReview());assertEquals(true,prepared.review().get("targetRoutesStillRequireGeneration"));
    }
    @Test void explicitTerminationRouteSurvivesSourceCaptureAndTargetPrivatePathRelocation()throws Exception{
        var f=new SourceMaterialFixture(root);f.terminatedMembers();var h=harness(f.capture());var prepared=h.service().prepare(h.frozen());
        var members=file(prepared,"app.control.servers.member-configurations-file");assertEquals(2,members.get("version"));
        var member=(Map<?,?>)((Map<?,?>)members.get("servers")).get(f.server.toString());assertEquals("TLS_TERMINATED",member.get("authTransport"));
        assertEquals("jdbc:postgresql://127.0.0.1:25443/eatwhat",member.get("authJdbcOrigin"));
        for(String field:List.of("authPasswordFile","authCaFile")){
            String name=Path.of(member.get(field).toString()).getFileName().toString();
            assertArrayEquals(Files.readAllBytes(field.equals("authCaFile")?f.ca:f.password),Base64.getDecoder().decode(prepared.privateFiles().get(name)));
        }
    }
    @Test void jdbcTlsPathsMoveButVerificationModeAndOtherQueryBytesAreNotChanged()throws Exception{
        var f=new SourceMaterialFixture(root);Path key=f.privateDirectory.resolve("client key.pfx");SourceMaterialFixture.write(key,new byte[]{0,1,2});
        f.properties.put("spring.datasource.url","jdbc:postgresql://db.internal/eatwhat?sslmode=verify-full&sslkey="+URLEncoder.encode(key.toString(),StandardCharsets.UTF_8)+"&sslpassword=fixture%2Bprivate&ApplicationName=control%2Bsource");
        var h=harness(f.capture());String jdbc=h.service().prepare(h.frozen()).pathOverrides().get("spring.datasource.url");
        assertTrue(jdbc.startsWith("jdbc:postgresql://db.internal/eatwhat?sslmode=verify-full&"));assertTrue(jdbc.endsWith("&sslpassword=fixture%2Bprivate&ApplicationName=control%2Bsource"));
        var refs=ControllerSourceMaterials.jdbcFiles(jdbc);assertTrue(refs.get("sslkey").startsWith("/opt/eatwhat-control/config/bootstrap-"));assertTrue(refs.get("sslkey").endsWith(".pfx"));
    }
    @Test void legacyCapturedTokensRelocateWithoutReadingThisManagersFilesystem()throws Exception{
        var f=new SourceMaterialFixture(root);var h=harness(f.capture());var p=h.service().prepare(h.frozen());
        assertEquals(Set.of("cluster-profiles.json","authority.token"),p.privateFiles().keySet());assertEquals("/opt/eatwhat-control/config/authority.token",p.pathOverrides().get("app.control.node-token-file"));
        assertEquals("MATERIALS_RELOCATED_ENDPOINTS_UNRESOLVED",p.review().get("status"));
    }
    @Test void perProfileCaIsCapturedBoundAndRelocatedWithItsOwnProfile()throws Exception{
        var f=new SourceMaterialFixture(root);Path ca=f.config.resolve("external-ca.pem");byte[] pem="-----BEGIN CERTIFICATE-----\nsynthetic-public-ca\n-----END CERTIFICATE-----\n".getBytes(StandardCharsets.US_ASCII);SourceMaterialFixture.write(ca,pem);
        var profiles=json.readValue(Files.readAllBytes(f.config.resolve("cluster-profiles.json")),LinkedHashMap.class);var campus=(Map<String,Object>)profiles.get("campus");campus.put("origin","https://node.example.invalid");campus.put("caFile",ca.toString());
        SourceMaterialFixture.write(f.config.resolve("cluster-profiles.json"),json.writeValueAsBytes(profiles));var captured=f.capture();assertEquals(ControllerSourceSnapshot.sha(pem),captured.hashes().get(ca.getFileName().toString()));
        var h=harness(captured);var prepared=h.service().prepare(h.frozen());var target=json.readValue(Base64.getDecoder().decode(prepared.privateFiles().get("cluster-profiles.json")),Map.class);
        String path=((Map<?,?>)target.get("campus")).get("caFile").toString();assertEquals("/opt/eatwhat-control/config/external-ca.pem",path);assertArrayEquals(pem,Base64.getDecoder().decode(prepared.privateFiles().get(Path.of(path).getFileName().toString())));
        f.credentials();var indexed=harness(f.capture());var indexedPrepared=indexed.service().prepare(indexed.frozen());var indexedProfiles=json.readValue(Base64.getDecoder().decode(indexedPrepared.privateFiles().get("cluster-profiles.json")),Map.class);assertEquals(path,((Map<?,?>)indexedProfiles.get("campus")).get("caFile"));
        SourceMaterialFixture.write(ca,"-----BEGIN CERTIFICATE-----\nchanged-ca\n-----END CERTIFICATE-----\n".getBytes(StandardCharsets.US_ASCII));assertNotEquals(captured.hashes().get("external-ca.pem"),f.capture().hashes().get("external-ca.pem"));
        campus.put("caFile",f.privateDirectory.resolve("not-a-profile-ca.pem").toString());SourceMaterialFixture.write(f.config.resolve("cluster-profiles.json"),json.writeValueAsBytes(profiles));assertThrows(Exception.class,f::capture);
    }
    @Test void oldCaptureCannotInventAnUncapturedCredentialFile()throws Exception{
        var f=new SourceMaterialFixture(root);var capture=f.capture();var h=harness(capture);var properties=new HashMap<>(capture.properties());
        properties.put("app.control.servers.credentials-file",root.resolve("not-captured.json").toString());
        when(h.admission().privateSourcePayload(h.frozen())).thenReturn(Map.of("properties",properties,"privateFiles",capture.privateFiles()));
        assertEquals("BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_REQUIRED",assertThrows(ControlFailure.class,()->h.service().prepare(h.frozen())).code);
    }
    @Test void alteredPrivateFileCannotPassTheFrozenPublicHashSet()throws Exception{
        var f=new SourceMaterialFixture(root);f.members();var capture=f.capture();var h=harness(capture);var files=new HashMap<>(capture.privateFiles());files.put("authority.token",Base64.getEncoder().encodeToString("changed".getBytes(StandardCharsets.US_ASCII)));
        when(h.admission().privateSourcePayload(h.frozen())).thenReturn(Map.of("properties",capture.properties(),"privateFiles",files));
        assertEquals("BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED",assertThrows(ControlFailure.class,()->h.service().prepare(h.frozen())).code);
    }
    @Test void publicSerializationAndStringRenderingCannotExposePrivateMaterial()throws Exception{
        var f=new SourceMaterialFixture(root);f.credentials();f.members();f.routes();var h=harness(f.capture());var result=h.service().prepare(h.frozen());String publicJson=json.writeValueAsString(result);
        for(String value:List.of("synthetic-private-ssh-key","auth-private-password","source-only.internal",root.toString(),"jdbc:"))assertFalse(publicJson.contains(value));
        assertEquals("BootstrapTargetMaterials[redacted]",result.toString());assertTrue(publicJson.contains("ENDPOINTS_UNRESOLVED"));
    }
}
