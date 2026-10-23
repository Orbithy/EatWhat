package you.v50to.eatwhat.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import you.v50to.eatwhat.control.bootstrap.BusinessDependencyRelay;
import javax.net.ssl.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Actual TLS, source-address filtering and loopback HTTP. Native WireGuard,
 * systemd and whole installation are separate acceptance requirements. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberIngressRelayIT {
    @TempDir static Path root;TransportTlsFixture pki;SSLContext serverTls,clientTls;String loopback;
    @BeforeAll void certificates()throws Exception{
        root=root.toRealPath();pki=TransportTlsFixture.create(root.resolve("pki"));
        serverTls=BusinessDependencyRelay.serverContext(TransportTlsFixture.relayStore(pki.campus()),TransportTlsFixture.PASSWORD.toCharArray());
        clientTls=BusinessDependencyRelay.upstreamContext(Files.readAllBytes(root.resolve("pki/ca.crt")));
        loopback=NetworkInterface.getByInetAddress(InetAddress.getByName("127.0.0.1")).getName();
    }
    BusinessDependencyRelay relay(int target,String peer)throws Exception{
        var endpoint=new BusinessDependencyRelay.Endpoint("MEMBER_HTTP",new InetSocketAddress("127.0.0.1",0),loopback,peer+"/32",new InetSocketAddress("127.0.0.1",target),
                BusinessDependencyRelay.Protocol.TLS,BusinessDependencyRelay.Upstream.CLEAR,"",null);
        return new BusinessDependencyRelay(serverTls,null,List.of(endpoint),4,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(5));
    }
    HttpServer backend(AtomicInteger calls)throws Exception{
        var backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);backend.createContext("/",exchange->{
            calls.incrementAndGet();int status=exchange.getRequestURI().getPath().startsWith("/internal/")?401:200;
            byte[] body=(status==200?"{\"status\":\"UP\"}":"{\"code\":\"NODE_AUTH_REQUIRED\"}").getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(status,body.length);try(var output=exchange.getResponseBody()){output.write(body);}
        });backend.start();return backend;
    }
    String request(BusinessDependencyRelay relay,InetAddress source,SSLContext context,String path)throws Exception{
        Socket raw=new Socket();if(source!=null)raw.bind(new InetSocketAddress(source,0));raw.connect(relay.addresses().getFirst(),1000);raw.setSoTimeout(3000);
        try(var tls=(SSLSocket)context.getSocketFactory().createSocket(raw,"127.0.0.1",relay.addresses().getFirst().getPort(),true)){
            var parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);tls.startHandshake();
            tls.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return new String(tls.getInputStream().readNBytes(65536),StandardCharsets.US_ASCII);
        }finally{raw.close();}
    }
    InetAddress localPrivate()throws Exception{
        for(var nic:Collections.list(NetworkInterface.getNetworkInterfaces()))if(nic.isUp())for(var address:Collections.list(nic.getInetAddresses()))
            if(address instanceof Inet4Address&&address.isSiteLocalAddress()&&!address.isLoopbackAddress())return address;
        throw new IllegalStateException("TEST_LOCAL_PRIVATE_INTERFACE_REQUIRED");
    }
    @Test void self_probe_and_explicit_peer_work_while_other_source_is_denied_before_upstream()throws Exception{
        var calls=new AtomicInteger();var backend=backend(calls);InetAddress local=localPrivate();
        try{
            try(var relay=relay(backend.getAddress().getPort(),"10.253.74.1")){
                assertTrue(request(relay,null,clientTls,"/actuator/health/readiness").startsWith("HTTP/1.1 200"));
                assertTrue(request(relay,null,clientTls,"/internal/control/v1/snapshot").startsWith("HTTP/1.1 401"));int before=calls.get();
                assertThrows(Exception.class,()->request(relay,local,clientTls,"/actuator/health/readiness"));assertEquals(before,calls.get());
            }
            try(var relay=relay(backend.getAddress().getPort(),local.getHostAddress())){
                assertTrue(request(relay,local,clientTls,"/actuator/health/readiness").startsWith("HTTP/1.1 200"));
            }
        }finally{backend.stop(0);}
    }
    @Test void cleartext_and_untrusted_tls_cannot_reach_the_loopback_business()throws Exception{
        var calls=new AtomicInteger();var backend=backend(calls);
        try(var relay=relay(backend.getAddress().getPort(),"10.253.74.1")){
            try(var socket=new Socket()){socket.connect(relay.addresses().getFirst());socket.setSoTimeout(3000);socket.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                try{int first=socket.getInputStream().read();assertTrue(first<0||first==21);}catch(java.net.SocketException expected){}}
            assertThrows(SSLHandshakeException.class,()->request(relay,null,SSLContext.getDefault(),"/actuator/health/readiness"));assertEquals(0,calls.get());
            assertTrue(request(relay,null,clientTls,"/actuator/health/readiness").contains("\"status\":\"UP\""));
            relay.close();relay.awaitTermination();
        }finally{backend.stop(0);}
    }
}
