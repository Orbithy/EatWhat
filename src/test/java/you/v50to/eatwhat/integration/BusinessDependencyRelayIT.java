package you.v50to.eatwhat.integration;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;
import you.v50to.eatwhat.control.bootstrap.BusinessDependencyRelay;
import you.v50to.eatwhat.control.ControlMemberConfiguration;
import you.v50to.eatwhat.cluster.ClusterMember;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static you.v50to.eatwhat.control.bootstrap.BusinessDependencyRelay.*;

/** Real local TLS/HTTP and isolated PostgreSQL, with ephemeral test PKI only.
 * No host trust-store changes, production credentials, member configuration or JOIN. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusinessDependencyRelayIT {
    @TempDir static Path root;
    TransportTlsFixture pki;SSLContext server,clientTrust;String iface;Path ca;
    GenericContainer<?> pg,pgTls;
    static final String AUTH_USER="eatwhat_auth_test",AUTH_PASSWORD="synthetic-business-auth-only";
    @BeforeAll void prepare()throws Exception{
        root=root.toRealPath();pki=TransportTlsFixture.create(root.resolve("pki"));ca=root.resolve("pki/ca.crt");
        iface=NetworkInterface.getByInetAddress(InetAddress.getByName("127.0.0.1")).getName();
        server=BusinessDependencyRelay.serverContext(repacked(pki.campus()),TransportTlsFixture.PASSWORD.toCharArray());
        clientTrust=BusinessDependencyRelay.upstreamContext(Files.readAllBytes(ca));
        pg=IsolatedContainers.postgres("business-relay-clear");pg.start();
        var keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(pki.external().keyStore())){keys.load(in,TransportTlsFixture.PASSWORD.toCharArray());}
        Path cert=root.resolve("pg.crt"),key=root.resolve("pg.key");StringBuilder chain=new StringBuilder();for(var c:keys.getCertificateChain("node"))chain.append(pem("CERTIFICATE",c.getEncoded()));
        Files.writeString(cert,chain);Files.writeString(key,pem("PRIVATE KEY",keys.getKey("node",TransportTlsFixture.PASSWORD.toCharArray()).getEncoded()));
        Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        pgTls=IsolatedContainers.postgres("business-relay-tls")
                .withCopyFileToContainer(MountableFile.forHostPath(cert,0644),"/tmp/relay-pg.crt")
                .withCopyFileToContainer(MountableFile.forHostPath(key,0600),"/tmp/relay-pg.key")
                .withCreateContainerCmdModifier(command->command.withEntrypoint("/bin/sh","-c"))
                .withCommand(new String[]{"chown postgres:postgres /tmp/relay-pg.key && chmod 600 /tmp/relay-pg.key && exec /usr/local/bin/docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tmp/relay-pg.crt -c ssl_key_file=/tmp/relay-pg.key"});
        pgTls.start();
        for(var database:List.of(pg,pgTls))try(var connection=DriverManager.getConnection(IsolatedContainers.jdbcUrl(database,IsolatedContainers.DATABASE),IsolatedContainers.USER,IsolatedContainers.PASSWORD);var statement=connection.createStatement()){
            statement.execute("CREATE ROLE "+AUTH_USER+" LOGIN PASSWORD '"+AUTH_PASSWORD+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION");
            statement.execute("GRANT CONNECT ON DATABASE "+IsolatedContainers.DATABASE+" TO "+AUTH_USER);
        }
    }
    @AfterAll void stop(){if(pgTls!=null)pgTls.close();if(pg!=null)pg.close();}
    static String pem(String name,byte[] bytes){return "-----BEGIN "+name+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+name+"-----\n";}
    byte[] repacked(TransportTlsFixture.Identity identity)throws Exception{
        return TransportTlsFixture.relayStore(identity);
    }
    Endpoint endpoint(String purpose,int target,Protocol incoming,Upstream outgoing){return new Endpoint(purpose,new InetSocketAddress("127.0.0.1",0),iface,"127.0.0.0/8",new InetSocketAddress("127.0.0.1",target),incoming,outgoing,outgoing==Upstream.CLEAR?"":"127.0.0.1",
            purpose.equals("AUTHORITY_DATABASE")?new DatabaseIdentity(IsolatedContainers.DATABASE,AUTH_USER):null);}
    BusinessDependencyRelay start(Endpoint e,SSLContext identity,int capacity,Duration handshake,Duration idle)throws Exception{
        return new BusinessDependencyRelay(identity,clientTrust,List.of(e),capacity,Duration.ofMillis(300),handshake,idle);
    }
    BusinessDependencyRelay start(Endpoint e)throws Exception{return start(e,server,8,Duration.ofSeconds(2),Duration.ofSeconds(10));}
    HttpClient client(SSLContext context){return HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_1_1).build();}
    URI uri(BusinessDependencyRelay relay,String path){return URI.create("https://127.0.0.1:"+relay.addresses().getFirst().getPort()+path);}
    static void eventually(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);assertTrue(condition.getAsBoolean());}
    @Test void real_https_to_loopback_http_preserves_token_path_and_streamed_body()throws Exception{
        var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();byte[] body=new byte[256*1024];new Random(1).nextBytes(body);
        upstream.createContext("/api/internal/cluster/v1/fixture",exchange->{calls.incrementAndGet();assertEquals("synthetic-node-token",exchange.getRequestHeaders().getFirst("X-Control-Node-Token"));
            byte[] received=exchange.getRequestBody().readAllBytes();assertArrayEquals(body,received);exchange.sendResponseHeaders(200,received.length);try(var out=exchange.getResponseBody()){out.write(received);}});upstream.start();
        try(var relay=start(endpoint("AUTHORITY_HTTP",upstream.getAddress().getPort(),Protocol.TLS,Upstream.CLEAR))){
            var request=HttpRequest.newBuilder(uri(relay,"/api/internal/cluster/v1/fixture")).header("X-Control-Node-Token","synthetic-node-token").timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response=client(clientTrust).send(request,HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,response.statusCode());assertArrayEquals(body,response.body());assertEquals(1,calls.get());
        }finally{upstream.stop(0);}
    }
    @Test void real_jdbc_authentication_and_query_cross_postgresql_ssl_request_into_plain_loopback_tunnel()throws Exception{
        try(var relay=start(endpoint("AUTHORITY_DATABASE",pg.getMappedPort(5432),Protocol.POSTGRESQL_TLS,Upstream.CLEAR));var connection=jdbc(relay)){
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT current_database(),current_user,(SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()),repeat('x',65536)")){
                assertTrue(rows.next());assertEquals(IsolatedContainers.DATABASE,rows.getString(1));assertEquals(AUTH_USER,rows.getString(2));assertFalse(rows.getBoolean(3));assertEquals(65536,rows.getString(4).length());
            }
            try(var statement=connection.createStatement()){assertEquals("42501",assertThrows(SQLException.class,()->statement.execute("SET ROLE "+IsolatedContainers.USER)).getSQLState());}
        }
    }
    @Test void upstream_postgresql_tls_is_verified_and_remains_encrypted_at_the_real_database()throws Exception{
        // A deliberately trusted TLS termination point has two different leaf
        // certificates. SCRAM-PLUS cannot transparently bind both endpoints;
        // the generated client route must explicitly opt into the segmented
        // TLS mode, while both TLS hostname/CA checks remain enabled.
        try(var relay=start(endpoint("AUTHORITY_DATABASE",pgTls.getMappedPort(5432),Protocol.POSTGRESQL_TLS,Upstream.POSTGRESQL_TLS));var connection=jdbc(relay,"disable")){
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()")){assertTrue(rows.next());assertTrue(rows.getBoolean(1));}
        }
    }
    @Test void required_scram_channel_binding_is_rejected_across_different_tls_certificates()throws Exception{
        try(var relay=start(endpoint("AUTHORITY_DATABASE",pgTls.getMappedPort(5432),Protocol.POSTGRESQL_TLS,Upstream.POSTGRESQL_TLS))){
            SQLException failure=assertThrows(SQLException.class,()->jdbc(relay,"require"));assertTrue(failure.getMessage().contains("channel binding"));
        }
    }
    @Test void production_member_configuration_authenticates_through_two_verified_tls_segments()throws Exception{
        try(var relay=start(endpoint("AUTHORITY_DATABASE",pgTls.getMappedPort(5432),Protocol.POSTGRESQL_TLS,Upstream.POSTGRESQL_TLS))){
            var member=new ClusterMember("external",UUID.randomUUID(),"EXTERNAL",1);
            var authority=new ClusterMember("campus",UUID.randomUUID(),"CAMPUS",0);
            var properties=ControlMemberConfiguration.runtime(UUID.randomUUID(),member,authority,"https://127.0.0.1:28443","/api",
                    "jdbc:postgresql://127.0.0.1:"+relay.addresses().getFirst().getPort()+"/"+IsolatedContainers.DATABASE,AUTH_USER,ControlMemberConfiguration.AuthTransport.TLS_TERMINATED);
            String managed=properties.get("app.cluster.auth-store.jdbc-url");
            assertTrue(managed.endsWith("&sslmode=verify-full&sslrootcert=/config/authority-ca.pem&channelBinding=disable"));
            // Only relocate the managed container's CA file into this disposable test directory.
            // All generated protocol, verification and timeout options reach the real JDBC driver.
            String url=managed.replace("sslrootcert=/config/authority-ca.pem","sslrootcert="+ca);
            try(var connection=DriverManager.getConnection(url,AUTH_USER,AUTH_PASSWORD);var statement=connection.createStatement();
                var rows=statement.executeQuery("SELECT current_user,ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()")){
                assertTrue(rows.next());assertEquals(AUTH_USER,rows.getString(1));assertTrue(rows.getBoolean(2));
            }
        }
    }
    @Test void native_provisioner_keytool_material_is_accepted_by_real_relay_and_verified_https_client()throws Exception{
        Path home=root.resolve("native-mint");Files.createDirectory(home);UUID id=UUID.randomUUID();
        Path folder=home.resolve(id.toString()).resolve("private/dependency-relay");Files.createDirectories(folder.getParent());
        Path resource=Path.of(Objects.requireNonNull(getClass().getResource("/control/controller-bootstrap-member-relay.py")).toURI());
        String script="import types,os,sys;from pathlib import Path;"+
                "a=types.ModuleType('native_mint');exec(compile(Path(sys.argv[1]).read_bytes(),'<reviewed-native-mint>','exec'),a.__dict__);"+
                "a.SITES=Path(sys.argv[2]);a.ROOT_UID=os.getuid();a.ROOT_GID=os.getgid();os.umask(0o077);original=a.run;"+
                "a.run=lambda argv,timeout=15:original([sys.argv[4] if argv[0]=='/usr/bin/keytool' else argv[0],*argv[1:]],timeout);"+
                "a.mint({'serverId':sys.argv[3]},{'config':{'listenAddress':'127.0.0.1'},'upstreamCa':{}})";
        var process=new ProcessBuilder("python3","-I","-B","-c",script,resource.toString(),home.toString(),id.toString(),Path.of(System.getProperty("java.home"),"bin/keytool").toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{assertTrue(process.waitFor(120,TimeUnit.SECONDS));assertEquals(0,process.exitValue());}finally{if(process.isAlive())process.destroyForcibly();}
        try(var files=Files.list(folder)){assertEquals(Set.of("server.password","ca.p12","ca.pem","server.p12","relay.csr","relay.crt","config.json"),files.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));}
        char[] password=Files.readString(folder.resolve("server.password")).strip().toCharArray();
        SSLContext minted=BusinessDependencyRelay.serverContext(Files.readAllBytes(folder.resolve("server.p12")),password);Arrays.fill(password,'\0');
        SSLContext trust=BusinessDependencyRelay.upstreamContext(Files.readAllBytes(folder.resolve("ca.pem")));
        var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);upstream.createContext("/health",exchange->{exchange.sendResponseHeaders(204,-1);exchange.close();});upstream.start();
        try(var relay=start(endpoint("AUTHORITY_HTTP",upstream.getAddress().getPort(),Protocol.TLS,Upstream.CLEAR),minted,4,Duration.ofSeconds(2),Duration.ofSeconds(10))){
            assertEquals(204,client(trust).send(HttpRequest.newBuilder(uri(relay,"/health")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            assertThrows(IOException.class,()->client(clientTrust).send(HttpRequest.newBuilder(uri(relay,"/health")).GET().build(),HttpResponse.BodyHandlers.discarding()));
        }finally{upstream.stop(0);}
        var calls=new AtomicInteger();var secured=upstream(minted,calls);
        var endpoints=List.of(endpoint("AUTHORITY_HTTP",secured.getAddress().getPort(),Protocol.TLS,Upstream.TLS),endpoint("AUTHORITY_DATABASE",pgTls.getMappedPort(5432),Protocol.POSTGRESQL_TLS,Upstream.POSTGRESQL_TLS));
        try{
            for(boolean swap:List.of(false,true)){
                var perRoute=Map.of("AUTHORITY_HTTP",swap?clientTrust:trust,"AUTHORITY_DATABASE",swap?trust:clientTrust);
                try(var relay=new BusinessDependencyRelay(server,null,endpoints,8,Duration.ofMillis(300),Duration.ofSeconds(2),Duration.ofSeconds(10),perRoute)){
                    var request=HttpRequest.newBuilder(uri(relay,"/health")).timeout(Duration.ofSeconds(4)).GET().build();
                    // Each route must authenticate with its own CA. Swapping
                    // them fails both routes, rather than accepting a union.
                    if(swap){assertThrows(IOException.class,()->client(clientTrust).send(request,HttpResponse.BodyHandlers.ofString()));assertThrows(SQLException.class,()->jdbcAt(relay.addresses().get(1).getPort(),"disable"));}
                    else{
                        assertEquals(200,client(clientTrust).send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
                        try(var connection=jdbcAt(relay.addresses().get(1).getPort(),"disable");var statement=connection.createStatement();var rows=statement.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()")){assertTrue(rows.next());assertTrue(rows.getBoolean(1));}
                    }
                }
            }assertEquals(1,calls.get());
        }finally{secured.stop(0);}
    }
    Connection jdbc(BusinessDependencyRelay relay)throws Exception{return jdbc(relay,"prefer");}
    Connection jdbc(BusinessDependencyRelay relay,String channelBinding)throws Exception{
        return jdbcAt(relay.addresses().getFirst().getPort(),channelBinding);
    }
    Connection jdbcAt(int port,String channelBinding)throws Exception{
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+port+"/"+IsolatedContainers.DATABASE+
                "?sslmode=verify-full&sslrootcert="+ca+"&connectTimeout=3&socketTimeout=5&gssEncMode=disable&channelBinding="+channelBinding,AUTH_USER,AUTH_PASSWORD);
    }
    HttpsServer upstream(SSLContext identity,AtomicInteger calls)throws Exception{
        var upstream=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);upstream.setHttpsConfigurator(new HttpsConfigurator(identity));
        upstream.createContext("/health",exchange->{calls.incrementAndGet();byte[] body="synthetic-up".getBytes();exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});upstream.start();return upstream;
    }
    @Test void upstream_https_hostname_and_trust_checks_never_fall_back_to_cleartext()throws Exception{
        for(var identity:List.of(pki.campus(),pki.wrongHostname(),pki.untrusted())){
            var calls=new AtomicInteger();var upstream=upstream(pki.context(identity,pki.external()),calls);
            try(var relay=start(endpoint("AUTHORITY_HTTP",upstream.getAddress().getPort(),Protocol.TLS,Upstream.TLS))){
                var request=HttpRequest.newBuilder(uri(relay,"/health")).timeout(Duration.ofSeconds(4)).GET().build();
                if(identity.equals(pki.campus())){assertEquals("synthetic-up",client(clientTrust).send(request,HttpResponse.BodyHandlers.ofString()).body());assertEquals(1,calls.get());}
                else{assertThrows(IOException.class,()->client(clientTrust).send(request,HttpResponse.BodyHandlers.ofString()));assertEquals(0,calls.get());}
            }finally{upstream.stop(0);}
        }
    }
    @Test void inbound_wrong_hostname_default_trust_and_cleartext_are_rejected_before_authority_connection()throws Exception{
        var calls=new AtomicInteger();var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);upstream.createContext("/",e->{calls.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});upstream.start();
        SSLContext original=SSLContext.getDefault();String property=System.getProperty("javax.net.ssl.trustStore");
        try(var relay=start(endpoint("AUTHORITY_HTTP",upstream.getAddress().getPort(),Protocol.TLS,Upstream.CLEAR))){
            var request=HttpRequest.newBuilder(uri(relay,"/health")).timeout(Duration.ofSeconds(4)).GET().build();
            assertThrows(SSLHandshakeException.class,()->client(SSLContext.getDefault()).send(request,HttpResponse.BodyHandlers.ofString()));
            try(var raw=new Socket("127.0.0.1",relay.addresses().getFirst().getPort())){raw.setSoTimeout(4000);raw.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes());try{raw.getInputStream().read();}catch(IOException expected){}}
        }
        try(var relay=start(endpoint("AUTHORITY_HTTP",upstream.getAddress().getPort(),Protocol.TLS,Upstream.CLEAR),BusinessDependencyRelay.serverContext(repacked(pki.wrongHostname()),TransportTlsFixture.PASSWORD.toCharArray()),8,Duration.ofSeconds(2),Duration.ofSeconds(10))){
            var request=HttpRequest.newBuilder(uri(relay,"/health")).timeout(Duration.ofSeconds(4)).GET().build();assertThrows(SSLHandshakeException.class,()->client(clientTrust).send(request,HttpResponse.BodyHandlers.ofString()));
        }finally{upstream.stop(0);}
        assertEquals(0,calls.get());assertSame(original,SSLContext.getDefault());assertEquals(property,System.getProperty("javax.net.ssl.trustStore"));
    }
    @Test void clear_postgresql_startup_never_reaches_authority_and_gss_refusal_still_requires_tls()throws Exception{
        try(var listener=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));var relay=start(endpoint("AUTHORITY_DATABASE",listener.getLocalPort(),Protocol.POSTGRESQL_TLS,Upstream.CLEAR))){
            listener.setSoTimeout(300);
            try(var socket=new Socket("127.0.0.1",relay.addresses().getFirst().getPort())){socket.setSoTimeout(2000);socket.getOutputStream().write(ByteBuffer.allocate(8).putInt(8).putInt(196608).array());assertEquals(-1,socket.getInputStream().read());}
            assertThrows(SocketTimeoutException.class,listener::accept);
            try(var socket=new Socket("127.0.0.1",relay.addresses().getFirst().getPort())){socket.setSoTimeout(2000);socket.getOutputStream().write(ByteBuffer.allocate(8).putInt(8).putInt(80877104).array());assertEquals('N',socket.getInputStream().read());socket.getOutputStream().write(ByteBuffer.allocate(8).putInt(8).putInt(80877103).array());assertEquals('S',socket.getInputStream().read());}
            assertThrows(SocketTimeoutException.class,listener::accept);
        }
    }
    @Test void capacity_handshake_timeout_and_shutdown_release_admitted_connections()throws Exception{
        try(var upstream=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));var relay=start(endpoint("AUTHORITY_HTTP",upstream.getLocalPort(),Protocol.TLS,Upstream.CLEAR),server,1,Duration.ofMillis(300),Duration.ofSeconds(1))){
            try(var slow=new Socket("127.0.0.1",relay.addresses().getFirst().getPort())){
                eventually(()->relay.activeConnections()==1);
                try(var excess=new Socket("127.0.0.1",relay.addresses().getFirst().getPort())){excess.setSoTimeout(1000);assertEquals(-1,excess.getInputStream().read());}
                eventually(()->relay.activeConnections()==0);
            }
            try(var slow=new Socket("127.0.0.1",relay.addresses().getFirst().getPort())){eventually(()->relay.activeConnections()==1);relay.close();eventually(()->relay.activeConnections()==0);}
        }
    }
    @Test void wrong_startup_user_database_or_options_never_open_an_authority_connection()throws Exception{
        try(var listener=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));var relay=start(endpoint("AUTHORITY_DATABASE",listener.getLocalPort(),Protocol.POSTGRESQL_TLS,Upstream.CLEAR))){
            listener.setSoTimeout(300);
            for(String fields:List.of("user\0postgres\0database\0"+IsolatedContainers.DATABASE+"\0\0",
                    "user\0"+AUTH_USER+"\0database\0other_database\0\0","user\0"+AUTH_USER+"\0database\0"+IsolatedContainers.DATABASE+"\0options\0-c role=postgres\0\0")){
                try(var raw=new Socket("127.0.0.1",relay.addresses().getFirst().getPort())){
                    raw.setSoTimeout(2000);raw.getOutputStream().write(ByteBuffer.allocate(8).putInt(8).putInt(80877103).array());assertEquals('S',raw.getInputStream().read());
                    var tls=(SSLSocket)clientTrust.getSocketFactory().createSocket(raw,"127.0.0.1",relay.addresses().getFirst().getPort(),true);tls.setUseClientMode(true);
                    var params=tls.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(params);tls.startHandshake();
                    byte[] bytes=fields.getBytes(java.nio.charset.StandardCharsets.UTF_8);tls.getOutputStream().write(ByteBuffer.allocate(8+bytes.length).putInt(8+bytes.length).putInt(196608).put(bytes).array());
                    try{assertEquals(-1,tls.getInputStream().read());}catch(SSLException|SocketException closed){}
                }
                assertThrows(SocketTimeoutException.class,listener::accept);eventually(()->relay.activeConnections()==0);
            }
        }
    }
    @Test void ongoing_upload_progress_is_not_killed_by_a_quiet_reverse_direction()throws Exception{
        var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/slow",exchange->{long count=0;int n;byte[] buffer=new byte[128];while((n=exchange.getRequestBody().read(buffer))>=0)count+=n;
            byte[] response=Long.toString(count).getBytes();exchange.sendResponseHeaders(200,response.length);try(var out=exchange.getResponseBody()){out.write(response);}});upstream.start();
        try(var relay=start(endpoint("AUTHORITY_HTTP",upstream.getAddress().getPort(),Protocol.TLS,Upstream.CLEAR),server,2,Duration.ofMillis(300),Duration.ofSeconds(1))){
            var ssl=(SSLSocket)clientTrust.getSocketFactory().createSocket("127.0.0.1",relay.addresses().getFirst().getPort());
            try(ssl){var params=ssl.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");ssl.setSSLParameters(params);ssl.setSoTimeout(4000);ssl.startHandshake();
                var out=ssl.getOutputStream();out.write("POST /slow HTTP/1.1\r\nHost: localhost\r\nContent-Length: 12\r\nConnection: close\r\n\r\n".getBytes());
                for(int i=0;i<12;i++){out.write('a');out.flush();Thread.sleep(150);}
                String response=new String(ssl.getInputStream().readAllBytes());assertTrue(response.endsWith("12"));
            }
        }finally{upstream.stop(0);}
    }
    @Test void idle_established_tls_connection_is_closed_even_when_both_readers_block()throws Exception{
        try(var upstream=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));var relay=start(endpoint("AUTHORITY_HTTP",upstream.getLocalPort(),Protocol.TLS,Upstream.CLEAR),server,1,Duration.ofMillis(300),Duration.ofSeconds(1))){
            upstream.setSoTimeout(2000);
            try(var socket=(SSLSocket)clientTrust.getSocketFactory().createSocket("127.0.0.1",relay.addresses().getFirst().getPort())){
                socket.setSoTimeout(3000);socket.startHandshake();
                try(var accepted=upstream.accept()){eventually(()->relay.activeConnections()==0);accepted.setSoTimeout(1000);assertEquals(-1,accepted.getInputStream().read());}
            }
        }
    }
}
