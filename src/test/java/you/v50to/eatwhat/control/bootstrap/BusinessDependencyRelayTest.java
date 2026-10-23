package you.v50to.eatwhat.control.bootstrap;

import org.junit.jupiter.api.Test;
import javax.net.ssl.SSLContext;
import java.net.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static you.v50to.eatwhat.control.bootstrap.BusinessDependencyRelay.*;

/** Policy checks only; real TLS and PostgreSQL use BusinessDependencyRelayIT. */
class BusinessDependencyRelayTest {
    @Test void member_https_has_exact_peer_policy_without_relaxing_existing_dependency_routes()throws Exception{
        String iface=loopback();var own=new InetSocketAddress("127.0.0.1",0);var target=new InetSocketAddress("127.0.0.1",18090);
        try(var relay=start(new Endpoint("MEMBER_HTTP",own,iface,"10.253.74.1/32",target,Protocol.TLS,Upstream.CLEAR,"",null),2,Duration.ofSeconds(1))){assertEquals(1,relay.addresses().size());}
        for(String cidr:List.of("10.253.74.0/24","0.0.0.0/0","203.0.113.1/32"))
            assertThrows(IllegalArgumentException.class,()->start(new Endpoint("MEMBER_HTTP",own,iface,cidr,target,Protocol.TLS,Upstream.CLEAR,"",null),2,Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->start(new Endpoint("AUTHORITY_HTTP",own,iface,"10.253.74.1/32",target,Protocol.TLS,Upstream.CLEAR,"",null),2,Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->start(new Endpoint("MEMBER_HTTP",own,iface,"10.253.74.1/32",target,Protocol.POSTGRESQL_TLS,Upstream.CLEAR,"",null),2,Duration.ofSeconds(1)));
    }
    Endpoint endpoint(String address,String iface,String cidr,String target,Protocol protocol,Upstream upstream,String peer)throws Exception{
        return new Endpoint("AUTHORITY_HTTP",new InetSocketAddress(address,0),iface,cidr,new InetSocketAddress(target,28090),protocol,upstream,peer,null);
    }
    String loopback()throws Exception{return NetworkInterface.getByInetAddress(InetAddress.getByName("127.0.0.1")).getName();}
    BusinessDependencyRelay start(Endpoint endpoint,int limit,Duration idle)throws Exception{
        return new BusinessDependencyRelay(SSLContext.getDefault(),null,List.of(endpoint),limit,Duration.ofMillis(200),Duration.ofMillis(300),idle);
    }
    @Test void wildcard_public_target_wrong_interface_or_broad_client_network_are_refused()throws Exception{
        String iface=loopback();var candidates=List.of(endpoint("0.0.0.0",iface,"127.0.0.0/8","127.0.0.1",Protocol.TLS,Upstream.CLEAR,""),
                endpoint("127.0.0.1",iface,"127.0.0.0/8","192.0.2.99",Protocol.TLS,Upstream.CLEAR,""),
                endpoint("127.0.0.1","not-owned","127.0.0.0/8","127.0.0.1",Protocol.TLS,Upstream.CLEAR,""),
                endpoint("127.0.0.1",iface,"0.0.0.0/0","127.0.0.1",Protocol.TLS,Upstream.CLEAR,""));
        for(var candidate:candidates)assertThrows(IllegalArgumentException.class,()->start(candidate,2,Duration.ofSeconds(1)));
    }
    @Test void protocol_downgrade_missing_upstream_trust_and_capacity_limits_are_refused()throws Exception{
        String iface=loopback();var wrong=endpoint("127.0.0.1",iface,"127.0.0.0/8","127.0.0.1",Protocol.POSTGRESQL_TLS,Upstream.CLEAR,"");
        assertThrows(IllegalArgumentException.class,()->start(wrong,2,Duration.ofSeconds(1)));
        var noTrust=endpoint("127.0.0.1",iface,"127.0.0.0/8","127.0.0.1",Protocol.TLS,Upstream.TLS,"127.0.0.1");assertThrows(IllegalArgumentException.class,()->start(noTrust,2,Duration.ofSeconds(1)));
        var valid=endpoint("127.0.0.1",iface,"127.0.0.0/8","127.0.0.1",Protocol.TLS,Upstream.CLEAR,"");
        for(int limit:new int[]{0,33})assertThrows(IllegalArgumentException.class,()->start(valid,limit,Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->start(valid,2,Duration.ofMillis(300)));
    }
    @Test void endpoint_diagnostics_never_serialize_addresses_or_private_config()throws Exception{
        assertEquals("BusinessDependencyEndpoint[redacted]",endpoint("127.0.0.1",loopback(),"127.0.0.0/8","127.0.0.1",Protocol.TLS,Upstream.CLEAR,"").toString());
    }
    @Test void scoped_trust_cannot_be_assigned_to_another_or_cleartext_purpose()throws Exception{
        var tls=endpoint("127.0.0.1",loopback(),"127.0.0.0/8","127.0.0.1",Protocol.TLS,Upstream.TLS,"127.0.0.1");
        var clear=endpoint("127.0.0.1",loopback(),"127.0.0.0/8","127.0.0.1",Protocol.TLS,Upstream.CLEAR,"");var context=SSLContext.getDefault();
        for(var candidate:List.of(tls,clear))assertThrows(IllegalArgumentException.class,()->new BusinessDependencyRelay(context,null,List.of(candidate),2,
                Duration.ofMillis(200),Duration.ofMillis(300),Duration.ofSeconds(1),Map.of("AUTHORITY_DATABASE",context)));
        assertThrows(IllegalArgumentException.class,()->new BusinessDependencyRelay(context,null,List.of(clear),2,
                Duration.ofMillis(200),Duration.ofMillis(300),Duration.ofSeconds(1),Map.of("AUTHORITY_HTTP",context)));
        try(var relay=new BusinessDependencyRelay(context,null,List.of(tls),2,Duration.ofMillis(200),Duration.ofMillis(300),Duration.ofSeconds(1),Map.of("AUTHORITY_HTTP",context))){assertEquals(1,relay.addresses().size());}
    }
}
