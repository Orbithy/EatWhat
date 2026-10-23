package you.v50to.eatwhat.control.bootstrap;

import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Fixed private WireGuard HTTPS entry to the installation's own loopback
 * business HTTP port. Only the approved SOURCE /32 and this host may connect.
 * The native driver proves the original business, bridge, private peer,
 * certificates and approval; no Spring context or general forwarding config. */
public final class MemberIngressRelay {
    public static final String PROTOCOL="eatwhat-member-ingress-relay-v1";
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static void require(boolean ok){if(!ok)throw new IllegalArgumentException("MEMBER_INGRESS_CONFIGURATION_INVALID");}
    private static UUID id(Object value){require(value instanceof String);var id=UUID.fromString(value.toString());require(id.toString().equals(value)&&!id.equals(new UUID(0,0)));return id;}
    private static InetAddress address(Object value)throws Exception{
        require(value instanceof String text&&text.matches("[0-9.]+"));var a=InetAddress.getByName(value.toString());
        require(a instanceof Inet4Address&&a.getHostAddress().equals(value));byte[] ip=a.getAddress();int first=ip[0]&255,second=ip[1]&255;
        require(first==10||first==172&&second>=16&&second<=31||first==192&&second==168);return a;
    }
    private MemberIngressRelay(){}
    public static void main(String[] args){
        try{
            require(args.length==1);UUID server=id(args[0]);Path folder=Path.of("/opt/eatwhat-sites",server.toString(),"private/member-ingress");
            var data=JSON.readValue(BusinessDependencyRelay.privateBytes(folder.resolve("config.json"),65536),Map.class);
            require(data.keySet().equals(Set.of("serverId","bootstrapId","businessId","networkId","listenAddress","sourceAddress","httpPort","maxConnections"))&&server.equals(id(data.get("serverId"))));
            UUID bootstrap=id(data.get("bootstrapId"));require(id(data.get("businessId")).equals(UUID.nameUUIDFromBytes((bootstrap+":BUSINESS_INSTALL").getBytes(StandardCharsets.UTF_8))));
            require(data.get("networkId") instanceof String hash&&hash.matches("[a-f0-9]{64}"));
            InetAddress listen=address(data.get("listenAddress")),source=address(data.get("sourceAddress"));require(!listen.equals(source));
            require(data.get("httpPort") instanceof Integer port&&port>=1024&&port<=65535&&!Set.of(2222,2223,18080,18081,28490,25432,16379,25443,28443).contains(port));
            require(data.get("maxConnections") instanceof Integer max&&max>=1&&max<=32);
            String secret=new String(BusinessDependencyRelay.privateBytes(folder.resolve("server.password"),256),StandardCharsets.US_ASCII).strip();require(secret.matches("[A-Za-z0-9_-]{43,128}"));char[] password=secret.toCharArray();
            javax.net.ssl.SSLContext tls;try{tls=BusinessDependencyRelay.serverContext(BusinessDependencyRelay.privateBytes(folder.resolve("server.p12"),65536),password);}finally{Arrays.fill(password,'\0');}
            var endpoint=new BusinessDependencyRelay.Endpoint("MEMBER_HTTP",new InetSocketAddress(listen,28490),"ewctrl0",source.getHostAddress()+"/32",
                    new InetSocketAddress("127.0.0.1",(int)data.get("httpPort")),BusinessDependencyRelay.Protocol.TLS,BusinessDependencyRelay.Upstream.CLEAR,"",null);
            try(var relay=new BusinessDependencyRelay(tls,null,List.of(endpoint),(int)data.get("maxConnections"),Duration.ofSeconds(3),Duration.ofSeconds(5),Duration.ofSeconds(60))){
                Runtime.getRuntime().addShutdownHook(new Thread(relay::close));System.out.println("{\"protocol\":\""+PROTOCOL+"\",\"state\":\"LISTENING\"}");relay.awaitTermination();
            }
        }catch(Exception failure){System.out.println("{\"code\":\"MEMBER_INGRESS_UNAVAILABLE\"}");System.exit(1);}
    }
}
