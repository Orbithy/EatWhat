package you.v50to.eatwhat.integration;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentModels;
import you.v50to.eatwhat.account.transport.AccountProtocol;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.*;
import you.v50to.eatwhat.control.ControlProperties;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real independent PKIs and HTTPS requests; membership/SQL are explicit mocks.
 * Does not claim a JOIN, persistent file replication or account projection. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClusterProfileCaIT {
    @TempDir static Path root;TransportTlsFixture a,b;Path caA,caB;
    final JsonMapper json=JsonMapper.builder().build();final UUID cluster=UUID.randomUUID();
    final List<HttpsServer> servers=new ArrayList<>();final AtomicInteger requests=new AtomicInteger();
    final byte[] object="immutable-test-object".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    @BeforeAll void certificates()throws Exception{
        root=root.toRealPath();a=TransportTlsFixture.create(root.resolve("a"));b=TransportTlsFixture.create(root.resolve("b"));caA=root.resolve("a/ca.crt");caB=root.resolve("b/ca.crt");
        for(Path path:List.of(caA,caB))Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
    }
    @AfterEach void stop(){servers.forEach(s->s.stop(0));servers.clear();requests.set(0);}
    HttpsServer server(TransportTlsFixture fixture,TransportTlsFixture.Identity identity,ClusterMember member)throws Exception{
        var server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(fixture.context(identity,fixture.external())));
        server.createContext("/internal/cluster/v1/",exchange->{
            requests.incrementAndGet();if(!"x".repeat(64).equals(exchange.getRequestHeaders().getFirst("X-Control-Node-Token"))){exchange.sendResponseHeaders(401,-1);exchange.close();return;}
            byte[] response;
            if(exchange.getRequestURI().getPath().endsWith("/account")){
                var protocol=new AccountProtocol();var frame=protocol.decode(exchange.getRequestBody().readAllBytes());response=protocol.encode(frame.reply(json.createObjectNode().put("status","APPLIED")));
            }else if(exchange.getRequestURI().getPath().contains("/files/")){
                if(exchange.getRequestMethod().equals("PUT")){byte[] uploaded=exchange.getRequestBody().readAllBytes();if(!Arrays.equals(object,uploaded)){exchange.sendResponseHeaders(400,-1);exchange.close();return;}response="{}".getBytes();}
                else response=object;
            }else response=json.writeValueAsBytes(Map.of("siteId",member.siteId(),"instanceId",member.instanceId(),"clusterId",cluster,"databaseFingerprint","a".repeat(32),
                    "dataPlaneReady",true,"protocolVersion",1,"mode",member.mode(),"allocationSlot",member.allocationSlot()));
            exchange.sendResponseHeaders(200,response.length);try(var out=exchange.getResponseBody()){out.write(response);}
        });server.start();servers.add(server);return server;
    }
    Path token()throws Exception{Path token=root.resolve(UUID.randomUUID()+".token");Files.writeString(token,"x".repeat(64));Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));return token;}
    ClusterProperties.Profile profile(ClusterMember member,HttpsServer server,Path ca)throws Exception{
        var p=new ClusterProperties.Profile();p.setSiteId(member.siteId());p.setInstanceId(member.instanceId());p.setMode(member.mode());p.setAllocationSlot(member.allocationSlot());
        p.setOrigin("https://127.0.0.1:"+server.getAddress().getPort());p.setTokenFile(token());p.setCaFile(ca);return p;
    }
    ClusterMember member(String site,int slot){return new ClusterMember(site,UUID.randomUUID(),slot==0?"CAMPUS":"EXTERNAL",slot);}
    ClusterStore membership(){var membership=mock(ClusterStore.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));when(membership.member(anyString())).thenReturn(Map.of("database_fingerprint","a".repeat(32)));return membership;}
    ClusterHttpNodes nodes(){return new ClusterHttpNodes(cluster,(plan,entries)->{},membership(),caA);}
    @Test void eachProfileUsesOnlyItsOwnCaAcrossRealIndependentHttpsPeers()throws Exception{
        var first=member("campus",0);var second=member("overseas",1);var sa=server(a,a.campus(),first);var sb=server(b,b.campus(),second);
        var settings=new ClusterProperties();settings.setProfiles(Map.of("campus",profile(first,sa,caA),"overseas",profile(second,sb,caB)));var profiles=new ClusterProfiles(settings);var nodes=nodes();
        assertEquals("a".repeat(32),nodes.fingerprint(profiles.get("campus")));assertEquals("a".repeat(32),nodes.fingerprint(profiles.get("overseas")));
        int before=requests.get();var forged=new ClusterProfiles.Entry("wrong-trust",second,URI.create("https://127.0.0.1:"+sb.getAddress().getPort()),token(),Map.of(),"",NodeHttpClients.scoped(caA));
        assertThrows(ClusterFailure.class,()->nodes.fingerprint(forged));assertEquals(before,requests.get());
        String publicProfiles=json.writeValueAsString(profiles.list());assertFalse(publicProfiles.contains(caA.toString()));assertFalse(publicProfiles.contains(caB.toString()));assertFalse(publicProfiles.contains("https"));
        assertEquals("ScopedNodeTrust[redacted]",profiles.get("campus").trust().toString());
    }
    @Test void modifiedCaFailsBeforeTokenDeliveryUntilExplicitPrivateProfileReload()throws Exception{
        var identity=member("peer",1);var sa=server(a,a.campus(),identity);var sb=server(b,b.campus(),identity);Path mutable=root.resolve("mutable-ca.pem");Files.copy(caA,mutable);Files.setPosixFilePermissions(mutable,PosixFilePermissions.fromString("rw-------"));
        var settings=new ClusterProperties();var p=profile(identity,sa,mutable);settings.setProfiles(Map.of("peer",p));var profiles=new ClusterProfiles(settings);var old=profiles.get("peer");var nodes=nodes();nodes.fingerprint(old);
        Files.write(mutable,Files.readAllBytes(caB));int before=requests.get();assertThrows(ClusterFailure.class,()->nodes.fingerprint(old));assertEquals(before,requests.get());
        p.setOrigin("https://127.0.0.1:"+sb.getAddress().getPort());profiles.reload(List.of(identity));assertEquals("a".repeat(32),nodes.fingerprint(profiles.get("peer")));
        before=requests.get();assertThrows(ClusterFailure.class,()->nodes.fingerprint(old));assertEquals(before,requests.get());
        var retained=profiles.get("peer");Files.setPosixFilePermissions(mutable,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(IllegalStateException.class,()->profiles.reload(List.of(identity)));assertSame(retained,profiles.get("peer"));
    }
    @Test void scopedTrustKeepsHostnameChecksAndDoesNotChangeDefaultJvmTrust()throws Exception{
        var member=member("peer",1);var bad=server(a,a.wrongHostname(),member);var settings=new ClusterProperties();var p=profile(member,bad,caA);settings.setProfiles(Map.of("peer",p));var profiles=new ClusterProfiles(settings);
        var original=javax.net.ssl.SSLContext.getDefault();var store=System.getProperty("javax.net.ssl.trustStore");assertThrows(ClusterFailure.class,()->nodes().fingerprint(profiles.get("peer")));assertEquals(0,requests.get());
        p.setOrigin("http://127.0.0.1:18090");assertEquals("PROFILE_CA_REQUIRES_HTTPS",assertThrows(ClusterFailure.class,()->profiles.reload(List.of(member))).getMessage());
        assertSame(original,javax.net.ssl.SSLContext.getDefault());assertEquals(store,System.getProperty("javax.net.ssl.trustStore"));
        assertEquals(HttpClient.Redirect.NEVER,profiles.get("peer").client(NodeHttpClients.create(null)).followRedirects());
    }
    static Object invoke(Object target,String name,Class<?>[] types,Object... args)throws Exception{
        Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(target,args);}catch(InvocationTargetException error){if(error.getCause() instanceof Exception e)throw e;throw error;}
    }
    @Test void accountProjectionAndFileRelayUseTargetScopedTrustWithoutAddingItToGlobalClient()throws Exception{
        var remote=member("overseas",1);var sb=server(b,b.campus(),remote);var properties=new ClusterProperties();properties.setCoordinator(true);properties.setNodeCaFile(caA);
        var p=profile(remote,sb,caB);properties.setProfiles(Map.of("overseas",p));var profiles=new ClusterProfiles(properties);var node=profiles.get("overseas");
        var local=new SiteProperties();local.setId("campus");local.setInstanceId(UUID.randomUUID());
        @SuppressWarnings("unchecked") ObjectProvider<ClusterProfiles> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(profiles);
        var jdbc=mock(JdbcTemplate.class);var channel=new ClusterAccountChannel(jdbc,properties,new ControlProperties(),local,provider);
        var snapshot=new ConsentModels.Snapshot(UUID.randomUUID(),1,1,1,"GRANTED","v1",Instant.now().plusSeconds(3600),local.getId(),local.getInstanceId(),null,json.createObjectNode());
        var reply=(tools.jackson.databind.JsonNode)invoke(channel,"project",new Class[]{ClusterProfiles.Entry.class,ConsentModels.Snapshot.class},node,snapshot);assertEquals("APPLIED",reply.path("status").asText());
        var files=new ClusterFileCoordinator(jdbc,membership(),caA);var revision=new ClusterFileTransfer.Revision(UUID.randomUUID(),1);Path target=root.resolve("download.bin");
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(object));
        invoke(files,"download",new Class[]{ClusterProfiles.Entry.class,ClusterFileTransfer.Revision.class,Path.class,String.class,long.class},node,revision,target,digest,(long)object.length);assertArrayEquals(object,Files.readAllBytes(target));
        invoke(files,"upload",new Class[]{ClusterProfiles.Entry.class,ClusterFileTransfer.Revision.class,Path.class},node,revision,target);assertEquals(3,requests.get());
        var withoutTrust=new ClusterProfiles.Entry(node.id(),node.member(),node.origin(),node.tokenFile(),node.channels(),node.apiPrefix());
        assertThrows(Exception.class,()->invoke(channel,"project",new Class[]{ClusterProfiles.Entry.class,ConsentModels.Snapshot.class},withoutTrust,snapshot));
        assertThrows(ClusterFailure.class,()->invoke(files,"upload",new Class[]{ClusterProfiles.Entry.class,ClusterFileTransfer.Revision.class,Path.class},withoutTrust,revision,target));assertEquals(3,requests.get());
    }
}
