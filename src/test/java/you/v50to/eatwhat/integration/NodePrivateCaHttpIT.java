package you.v50to.eatwhat.integration;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import you.v50to.eatwhat.config.NodeHttpClients;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import you.v50to.eatwhat.cluster.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

/** Real TLS handshakes; ephemeral test PKI only, no JVM/system trust modifications. */
class NodePrivateCaHttpIT {
 @TempDir Path root;
 @Test void explicitlyConfiguredPrivateCaPreservesHostnameChecksAndDefaultTrust()throws Exception{
  root=root.toRealPath();Path certs=root.resolve("pki");var fixture=TransportTlsFixture.create(certs);
  Path ca=certs.resolve("ca.crt");Files.setPosixFilePermissions(ca,PosixFilePermissions.fromString("rw-------"));
  SSLContext original=SSLContext.getDefault();String trustStore=System.getProperty("javax.net.ssl.trustStore");
  var privateClient=NodeHttpClients.create(ca);var defaultClient=NodeHttpClients.create(null);
  var servers=new ArrayList<HttpsServer>();
  try{
   var good=server(fixture.context(fixture.campus(),fixture.external()));servers.add(good);
   var wrong=server(fixture.context(fixture.wrongHostname(),fixture.external()));servers.add(wrong);
   var untrusted=server(fixture.context(fixture.untrusted(),fixture.external()));servers.add(untrusted);
   assertEquals(200,get(privateClient,good));assertThrows(SSLHandshakeException.class,()->get(privateClient,wrong));
   assertThrows(SSLHandshakeException.class,()->get(privateClient,untrusted));assertThrows(SSLHandshakeException.class,()->get(defaultClient,good));
   assertSame(original,SSLContext.getDefault());assertEquals(trustStore,System.getProperty("javax.net.ssl.trustStore"));
   assertEquals("HTTPS",privateClient.sslParameters().getEndpointIdentificationAlgorithm());assertEquals(HttpClient.Redirect.NEVER,privateClient.followRedirects());
  }finally{servers.forEach(s->s.stop(0));}
 }
 @Test void unsafeOrMalformedCaRefusesClientCreationRatherThanFallingBack()throws Exception{
  root=root.toRealPath();Path ca=root.resolve("invalid-ca.pem");Files.writeString(ca,"malformed certificate");Files.setPosixFilePermissions(ca,PosixFilePermissions.fromString("rw-------"));
  assertEquals("NODE_PRIVATE_CA_INVALID",assertThrows(IllegalStateException.class,()->NodeHttpClients.create(ca)).getMessage());
  assertThrows(IllegalStateException.class,()->NodeHttpClients.create(Path.of("relative.pem")));
  Path link=root.resolve("symlink.pem");Files.createSymbolicLink(link,ca);assertThrows(IllegalStateException.class,()->NodeHttpClients.create(link));
  Files.setPosixFilePermissions(ca,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(IllegalStateException.class,()->NodeHttpClients.create(ca));
 }
 @Test void membershipSynchronizationUsesConfiguredPrivateCaForItsOwnHttpClient()throws Exception{
  root=root.toRealPath();Path certs=root.resolve("mesh-pki");var fixture=TransportTlsFixture.create(certs);
  Path ca=certs.resolve("ca.crt");Files.setPosixFilePermissions(ca,PosixFilePermissions.fromString("rw-------"));
  var cluster=UUID.randomUUID();var change=UUID.randomUUID();var member=new ClusterMember("campus",UUID.randomUUID(),"CAMPUS",0);
  var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForList(anyString(),eq(change))).thenReturn(List.of(Map.of("account_complete",true,"account_last_user",0L)));
  var source=mock(javax.sql.DataSource.class);when(jdbc.getDataSource()).thenReturn(source);
  when(jdbc.queryForList("SELECT * FROM eatwhat_cluster.member WHERE site_id=?","campus")).thenReturn(List.of(Map.of("database_fingerprint","a".repeat(32))));
  when(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.control_lease WHERE singleton",Long.class)).thenReturn(0L);
  var transactions=new you.v50to.eatwhat.sync.SyncTransactions(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
  var membership=new ClusterStore(jdbc,transactions,cluster,member.identity());
  @SuppressWarnings("unchecked") ObjectProvider<ClusterFileCoordinator> files=mock(ObjectProvider.class);
  var server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(fixture.context(fixture.campus(),fixture.external())));
  var operations=new ArrayList<String>();
  server.createContext("/internal/cluster/v1/",exchange->{
   String operation=exchange.getRequestURI().getPath().substring("/internal/cluster/v1/".length());
   if(!"x".repeat(64).equals(exchange.getRequestHeaders().getFirst("X-Control-Node-Token"))){exchange.sendResponseHeaders(401,-1);exchange.close();return;}
   operations.add(operation);exchange.getRequestBody().readAllBytes();
   var body=Map.of("siteId",member.siteId(),"instanceId",member.instanceId().toString(),"clusterId",cluster.toString(),"databaseFingerprint","a".repeat(32),"progress",Map.of("seeded",true,"pendingDeliveries",0,"pendingInbox",0,"fileTransfers",0));
   byte[] bytes=JsonMapper.builder().build().writeValueAsBytes(body);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
  });server.start();
  try{
   var token=root.resolve("mesh.token");Files.writeString(token,"x".repeat(64));Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));
   var node=new ClusterProfiles.Entry("authority",member,URI.create("https://127.0.0.1:"+server.getAddress().getPort()),token,Map.of(),"");
   var plan=new ClusterMembershipCoordinator.Plan(cluster,change,1,2,"JOIN","campus",List.of(member),List.of(member));
   assertThrows(ClusterFailure.class,()->new ClusterMeshSynchronizer(jdbc,null,null,files,membership,null).synchronize(plan,List.of(node)));
   assertTrue(operations.isEmpty());
   new ClusterMeshSynchronizer(jdbc,null,null,files,membership,ca).synchronize(plan,List.of(node));
   assertEquals(List.of("replay-seed","flush","flush","replay-status"),operations);
  }finally{server.stop(0);}
 }
 HttpsServer server(SSLContext context)throws Exception{
  var server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(context));
  server.createContext("/health",exchange->{byte[] body="synthetic-ready".getBytes();exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});server.start();return server;
 }
 int get(HttpClient client,HttpsServer server)throws Exception{
  var request=HttpRequest.newBuilder(URI.create("https://127.0.0.1:"+server.getAddress().getPort()+"/health")).timeout(Duration.ofSeconds(4)).GET().build();return client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode();
 }
}
