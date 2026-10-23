package you.v50to.eatwhat.control;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlServiceTest {
    @TempDir Path root;
    ControlProperties p;ControlAudit audit;ControlService control;
    @BeforeEach void setup(){p=new ControlProperties();p.setEnvironmentKind("isolated-acceptance");audit=new ControlAudit(root.resolve("state"));control=new ControlService(p,audit);}
    @Test void missingNodesAreUnknownNotHealthy(){var s=control.sites();assertEquals("UNVERIFIED",s.get("pairStatus"));assertTrue(s.toString().contains("UNKNOWN"));assertFalse(s.toString().contains("OBSERVED"));}
    @Test void urlsCannotSupplyCredentialsPathsRedirectSchemesOrNonLoopbackPlaintext(){for(String uri:List.of("http://localhost:12","http://example.org","https://user:password@example.org","https://example.org/path","https://example.org?a=b","file:///tmp/file"))assertThrows(ControlFailure.class,()->ControlService.origin(uri));assertEquals("127.0.0.1",ControlService.origin("http://127.0.0.1:8080").getHost());}
    @Test void proxyPrefixIsSeparateBoundedAndCannotChangeTheOriginOrEscapeItsPath(){
        assertEquals("https://backend.example.invalid/api",ControlService.nodeBase("https://backend.example.invalid/","/api/").toString());
        assertEquals("https://backend.example.invalid",ControlService.nodeBase("https://backend.example.invalid",null).toString());
        assertEquals("/backend/v1",ControlService.apiPrefix("/backend/v1"));
        assertEquals("",ControlService.apiPrefix("/"));
        for(String prefix:List.of("api","//elsewhere.invalid","/../admin","/api/./admin","/api//v1","/api%2fadmin","/api?x=y","/api#x","/api\\admin","/api;admin","/api/.."," /api","/"+"x".repeat(128)))
            assertEquals("NODE_API_PREFIX_INVALID",assertThrows(ControlFailure.class,()->ControlService.nodeBase("https://backend.example.invalid",prefix)).code);
        assertThrows(ControlFailure.class,()->ControlService.nodeBase("http://backend.example.invalid","/api"));
        assertThrows(ControlFailure.class,()->ControlService.nodeBase("https://backend.example.invalid/api","/api"));
    }
    @Test void blockedRequestsRequireVerifiedPairAndDoNotWriteFalseAppliedAudit(){assertEquals("PAIR_NOT_VERIFIED",assertThrows(ControlFailure.class,()->control.fault("1","campus","events","BLOCK",10)).code);assertTrue(control.audit(50).isEmpty());}
    @Test void arbitrarySuiteReleaseAndSingleSiteDeploymentAreRejected(){assertThrows(ControlFailure.class,()->control.plan("1","unknown",List.of("campus")));assertThrows(ControlFailure.class,()->control.plan("1","unknown",List.of("campus","external")));try(var runs=new ControlRuns(p,audit,control)){assertEquals("SUITE_NOT_ALLOWED",assertThrows(ControlFailure.class,()->runs.start("1","deploy;rm -rf")).code);}}
    @Test void auditedPlansPersistAndExecutionCannotBeUnlocked()throws Exception {
        var artifact=root.resolve("candidate.jar");Files.writeString(artifact,"synthetic artifact");
        var release=new ControlProperties.Release();release.setArtifact(artifact);release.setSha256(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));p.getReleases().put("candidate",release);
        var plan=control.plan("1","candidate",List.of("campus","external"));UUID id=UUID.fromString((String)plan.get("id"));
        assertEquals(false,plan.get("executionEnabled"));assertEquals("PREVIEW_ONLY",control.loadPlan(id).get("state"));
        assertEquals(423,assertThrows(ControlFailure.class,()->control.execute("1",id)).status);assertEquals(2,control.audit(50).size());
        Files.writeString(artifact,"changed");assertEquals("ARTIFACT_SHA256_MISMATCH",assertThrows(ControlFailure.class,()->control.plan("1","candidate",List.of("campus","external"))).code);
    }
    @Test void tokenRequiresPrivatePermissionsAndNeverAppearsInAudit()throws Exception {
        var path=root.resolve("token");Files.writeString(path,"x".repeat(50));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));assertEquals(50,ControlAudit.token(path).length());
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(ControlFailure.class,()->ControlAudit.token(path));
        audit.append(UUID.randomUUID(),"1","BLOCK","events","REQUESTED");assertFalse(Files.readString(audit.directory().resolve("audit.jsonl")).contains("x".repeat(50)));
    }
    @Test void duplicateIdentityOrPartialPairCannotStart()throws Exception {
        p.getNodes().put("campus",node("campus",1234));assertThrows(ControlFailure.class,()->new ControlService(p,audit));
        p.getNodes().put("external",node("external",1235));p.getNodes().get("external").setInstanceId(p.getNodes().get("campus").getInstanceId());assertThrows(ControlFailure.class,()->new ControlService(p,audit));
    }
    @Test void offlineRecoveryCatalogDoesNotProbeNodesOrPretendRoutingWasObserved()throws Exception {
        p.getNodes().put("campus",node("campus",1));p.getNodes().put("external",node("external",2));p.getNodes().get("campus").getChannels().put("events",60);
        control=new ControlService(p,audit);var catalog=control.channelCatalog();
        var channels=(List<?>)catalog.get("campus");var event=(Map<?,?>)channels.getFirst();
        assertEquals("events",event.get("name"));assertEquals(60,event.get("maximumBlockSeconds"));assertEquals("NOT_OBSERVED",event.get("state"));assertEquals(false,event.get("routingVerified"));
        assertEquals("UNVERIFIED",control.sites().get("pairStatus"));assertEquals(catalog,control.channelCatalog());
        p.getNodes().get("campus").getChannels().put("bad",301);assertEquals("CHANNEL_CATALOG_INVALID",assertThrows(ControlFailure.class,()->new ControlService(p,audit)).code);
    }
    ControlProperties.Node node(String key,int port)throws Exception {
        Path t=root.resolve(key+".token");Files.writeString(t,"z".repeat(50));Files.setPosixFilePermissions(t,PosixFilePermissions.fromString("rw-------"));
        var n=new ControlProperties.Node();n.setOrigin("http://127.0.0.1:"+port);n.setSiteId(key);n.setInstanceId(UUID.randomUUID());n.setTokenFile(t);return n;
    }
    @Test void realNodeHttpTransportRejectsStaleIdentitySameDatabaseAndRedirects()throws Exception {
        var servers=new ArrayList<HttpServer>();var observations=new HashMap<String,Map<String,Object>>();
        try {
            for(String key:List.of("campus","external")) {
                var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);servers.add(server);
                var node=node(key,server.getAddress().getPort());p.getNodes().put(key,node);
                node.setApiPrefix(key.equals("campus")?"/api/":"");
                observations.put(key,new LinkedHashMap<>(Map.of("siteId",key,"instanceId",node.getInstanceId().toString(),"mode",key.equals("campus")?"CAMPUS":"EXTERNAL","observedAt",Instant.now().toString(),"observationStatus","OBSERVED","database",Map.of("databaseFingerprint",(key.equals("campus")?"a":"b").repeat(32)))));
                String path=ControlService.apiPrefix(node.getApiPrefix())+"/internal/control/v1/snapshot";
                server.createContext(path,exchange->{assertEquals(path,exchange.getRequestURI().getPath());assertEquals("z".repeat(50),exchange.getRequestHeaders().getFirst("X-Control-Node-Token"));byte[] b=JsonMapper.builder().build().writeValueAsBytes(observations.get(key));exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);exchange.close();});server.start();
            }
            control=new ControlService(p,audit);assertEquals("VERIFIED_DISTINCT",control.sites().get("pairStatus"));
            observations.get("external").put("database",Map.of("databaseFingerprint","a".repeat(32)));assertEquals("SAME_DATABASE_REJECTED",control.sites().get("pairStatus"));
            observations.get("external").put("observedAt",Instant.now().minusSeconds(60).toString());assertEquals("UNVERIFIED",control.sites().get("pairStatus"));
            observations.get("external").put("observedAt",Instant.now().toString());observations.get("external").put("instanceId",UUID.randomUUID().toString());assertTrue(control.sites().toString().contains("NODE_IDENTITY_MISMATCH"));
            servers.getFirst().removeContext("/api/internal/control/v1/snapshot");servers.getFirst().createContext("/api/internal/control/v1/snapshot",exchange->{exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/secret");exchange.sendResponseHeaders(302,-1);exchange.close();});assertTrue(control.sites().toString().contains("NODE_REQUEST_FAILED"));
        } finally {servers.forEach(s->s.stop(0));}
    }
}
