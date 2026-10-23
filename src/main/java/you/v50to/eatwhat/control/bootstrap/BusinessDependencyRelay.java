package you.v50to.eatwhat.control.bootstrap;

import tools.jackson.databind.json.JsonMapper;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Fixed TLS endpoints between an owned Docker bridge and approved loopback
 * dependency tunnels. It does not interpret or retain database passwords or
 * SQL text, change membership, or select an arbitrary upstream destination.
 * PostgreSQL startup user/database are restricted to the dedicated identity,
 * even when the captured authority uses loopback trust authentication.
 * The native provisioning driver must authenticate network/interface ownership,
 * approval, certificates and source tunnel bindings before starting this CLI. */
public final class BusinessDependencyRelay implements AutoCloseable {
    public enum Protocol { TLS, POSTGRESQL_TLS }
    public enum Upstream { CLEAR, TLS, POSTGRESQL_TLS }
    public record DatabaseIdentity(String database,String username) {
        public DatabaseIdentity {require(database!=null&&database.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")&&username!=null&&username.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"));}
        @Override public String toString(){return "BusinessDependencyDatabaseIdentity[redacted]";}
    }
    public record Endpoint(String purpose,InetSocketAddress listen,String listenInterface,String clientSubnet,
                           InetSocketAddress target,Protocol protocol,Upstream upstream,String peerName,DatabaseIdentity databaseIdentity) {
        @Override public String toString(){return "BusinessDependencyEndpoint[redacted]";}
    }
    private static final byte[] PG_SSL=ByteBuffer.allocate(8).putInt(8).putInt(80877103).array();
    private static final byte[] PG_GSS=ByteBuffer.allocate(8).putInt(8).putInt(80877104).array();
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final int BUFFER=16384;
    private final SSLContext serverTls;
    private final Map<String,SSLContext> upstreamTls;
    private final int connectMs,handshakeMs,idleMs;
    private final Semaphore slots;
    private final ExecutorService threads=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"business-dependency-relay-timeouts");t.setDaemon(true);return t;});
    private final List<ServerSocket> listeners=new ArrayList<>();
    private final Set<Pair> active=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicBoolean failed=new AtomicBoolean();
    private final CountDownLatch stopped=new CountDownLatch(1);
    private static void require(boolean ok){if(!ok)throw new IllegalArgumentException("BUSINESS_DEPENDENCY_RELAY_CONFIGURATION_INVALID");}
    private static int millis(Duration value,int low,int high){long ms=value.toMillis();require(ms>=low&&ms<=high);return (int)ms;}
    private static long ipv4(InetAddress address){require(address instanceof Inet4Address);long result=0;for(byte b:address.getAddress())result=(result<<8)|(b&255);return result;}
    private static boolean privateAddress(InetAddress value){long ip=ipv4(value);return (ip>>>24)==10||(ip>>>20)==0xac1||(ip>>>16)==0xc0a8;}
    private static long[] subnet(String value)throws IOException{
        require(value!=null&&value.matches("[0-9.]+/[0-9]{1,2}"));String[] parts=value.split("/");int prefix=Integer.parseInt(parts[1]);require(prefix>=8&&prefix<=32);
        InetAddress address=InetAddress.getByName(parts[0]);long ip=ipv4(address),mask=(0xffffffffL<<(32-prefix))&0xffffffffL;require((ip&mask)==ip);
        require(privateAddress(address)||address.isLoopbackAddress());return new long[]{ip,mask};
    }
    private static boolean contains(String cidr,InetAddress address)throws IOException{long[] network=subnet(cidr);return address instanceof Inet4Address&&(ipv4(address)&network[1])==network[0];}
    private static void validate(Endpoint e)throws IOException{
        require(Set.of("AUTHORITY_HTTP","AUTHORITY_DATABASE","MEMBER_HTTP").contains(e.purpose())&&!e.listen().isUnresolved()&&!e.target().isUnresolved());
        require(!e.listen().getAddress().isAnyLocalAddress()&&(privateAddress(e.listen().getAddress())||e.listen().getAddress().isLoopbackAddress()));
        require(e.listen().getPort()==0&&e.listen().getAddress().isLoopbackAddress()||e.listen().getPort()>=1024);
        boolean member=e.purpose().equals("MEMBER_HTTP");
        require(e.listenInterface()!=null&&e.listenInterface().matches("[A-Za-z0-9_.:-]{1,15}"));
        if(member){subnet(e.clientSubnet());require(e.clientSubnet().endsWith("/32")&&e.upstream()==Upstream.CLEAR);}
        else require(contains(e.clientSubnet(),e.listen().getAddress()));
        var network=NetworkInterface.getByInetAddress(e.listen().getAddress());require(network!=null&&network.getName().equals(e.listenInterface()));
        require(e.target().getAddress().equals(InetAddress.getByName("127.0.0.1")));
        // The reusable transport only accepts IPv4 loopback targets; the CLI
        // below fixes the two production target ports rather than reading them
        // from configuration. Tests can use isolated ephemeral loopback ports.
        boolean http=member||e.purpose().equals("AUTHORITY_HTTP");require(e.target().getPort()>0&&e.protocol()==(http?Protocol.TLS:Protocol.POSTGRESQL_TLS)
                &&(e.upstream()==Upstream.CLEAR||e.upstream()==(http?Upstream.TLS:Upstream.POSTGRESQL_TLS)));
        require(http?e.databaseIdentity()==null:e.databaseIdentity()!=null);
        require(e.upstream()==Upstream.CLEAR?e.peerName().isEmpty():Set.of("127.0.0.1","localhost").contains(e.peerName()));
    }
    /** Binds every endpoint before accepting any traffic. A partial bind is
     * closed on failure; it cannot leave a half-configured relay running. */
    public BusinessDependencyRelay(SSLContext serverTls,SSLContext upstreamTls,List<Endpoint> endpoints,int maxConnections,
                                   Duration connectTimeout,Duration handshakeTimeout,Duration idleTimeout)throws IOException {
        this(serverTls,upstreamTls,endpoints,maxConnections,connectTimeout,handshakeTimeout,idleTimeout,Map.of());
    }
    public BusinessDependencyRelay(SSLContext serverTls,SSLContext fallbackTrust,List<Endpoint> endpoints,int maxConnections,
                                   Duration connectTimeout,Duration handshakeTimeout,Duration idleTimeout,Map<String,SSLContext> scopedTrust)throws IOException {
        this.serverTls=Objects.requireNonNull(serverTls);
        connectMs=millis(connectTimeout,100,10000);handshakeMs=millis(handshakeTimeout,100,10000);idleMs=millis(idleTimeout,1000,120000);
        require(idleMs>=connectMs+handshakeMs&&maxConnections>=1&&maxConnections<=32&&endpoints!=null&&!endpoints.isEmpty()&&endpoints.size()<=2);
        require(scopedTrust!=null);var trusts=new HashMap<String,SSLContext>();
        for(var e:endpoints)if(e.upstream()!=Upstream.CLEAR){var trust=scopedTrust.getOrDefault(e.purpose(),fallbackTrust);if(trust!=null)trusts.put(e.purpose(),trust);}
        require(trusts.keySet().containsAll(scopedTrust.keySet()));this.upstreamTls=Map.copyOf(trusts);
        slots=new Semaphore(maxConnections);var purposes=new HashSet<String>();
        try{
            for(var e:endpoints){validate(e);require(purposes.add(e.purpose())&&(e.upstream()==Upstream.CLEAR||this.upstreamTls.containsKey(e.purpose())));
                var socket=new ServerSocket();listeners.add(socket);socket.setReuseAddress(false);socket.bind(e.listen(),32);
            }
            for(int i=0;i<endpoints.size();i++){var endpoint=endpoints.get(i);var listener=listeners.get(i);threads.submit(()->accept(endpoint,listener));}
            clock.scheduleAtFixedRate(()->{
                long now=System.nanoTime();for(var pair:active)if(now-pair.lastProgress.get()>TimeUnit.MILLISECONDS.toNanos(idleMs))pair.close();
            },100,100,TimeUnit.MILLISECONDS);
        }catch(IOException|RuntimeException failure){close();throw failure;}
    }
    public List<InetSocketAddress> addresses(){return listeners.stream().map(s->(InetSocketAddress)s.getLocalSocketAddress()).toList();}
    public int activeConnections(){return active.size();}
    public void awaitTermination()throws InterruptedException,IOException{stopped.await();if(failed.get())throw new IOException("RELAY_LISTENER_FAILED");}
    private void accept(Endpoint endpoint,ServerSocket listener){
        while(!closed.get()){
            Socket socket=null;Pair admitted=null;
            try{
                socket=listener.accept();
                boolean self=endpoint.purpose().equals("MEMBER_HTTP")&&endpoint.listen().getAddress().equals(socket.getInetAddress());
                if(closed.get()||!(self||contains(endpoint.clientSubnet(),socket.getInetAddress()))||!slots.tryAcquire()){socket.close();continue;}
                var pair=new Pair();admitted=pair;pair.add(socket);active.add(pair);if(closed.get()){pair.close();continue;}threads.submit(()->connect(endpoint,pair));
            }catch(Exception failure){
                if(admitted!=null)admitted.close();else if(socket!=null)try{socket.close();}catch(IOException ignored){}
                // An acceptor/resource failure must not spin forever or leave
                // the other endpoint advertised as a healthy partial relay.
                if(!closed.get()){failed.set(true);close();}return;
            }
        }
    }
    private void connect(Endpoint endpoint,Pair pair){
        try{
            Socket downstream=pair.first();downstream.setTcpNoDelay(true);downstream.setSoTimeout(handshakeMs);
            if(endpoint.protocol()==Protocol.POSTGRESQL_TLS){
                // PostgreSQL's legacy SSLRequest is negotiated before TLS.
                // Reject GSS encryption once with its standard 'N' response,
                // then still require SSLRequest. Clear Startup/CancelRequest
                // are never forwarded to the authority.
                byte[] request=downstream.getInputStream().readNBytes(8);
                if(Arrays.equals(request,PG_GSS)){downstream.getOutputStream().write('N');downstream.getOutputStream().flush();request=downstream.getInputStream().readNBytes(8);}
                require(Arrays.equals(request,PG_SSL));downstream.getOutputStream().write('S');downstream.getOutputStream().flush();
            }
            SSLSocket client=(SSLSocket)serverTls.getSocketFactory().createSocket(downstream,downstream.getInetAddress().getHostAddress(),downstream.getPort(),true);
            pair.add(client);client.setUseClientMode(false);client.setEnabledProtocols(new String[]{"TLSv1.3","TLSv1.2"});client.setNeedClientAuth(false);client.setSoTimeout(handshakeMs);client.startHandshake();
            byte[] startup=endpoint.protocol()==Protocol.POSTGRESQL_TLS?postgresStartup(client.getInputStream(),endpoint.databaseIdentity()):null;
            // Never connect to the authority until the inbound TLS handshake
            // has completed. Failed/cleartext clients consume no upstream slot.
            Socket target=new Socket();pair.add(target);target.setTcpNoDelay(true);target.connect(endpoint.target(),connectMs);target.setSoTimeout(handshakeMs);
            if(endpoint.upstream()!=Upstream.CLEAR){
                if(endpoint.upstream()==Upstream.POSTGRESQL_TLS){target.getOutputStream().write(PG_SSL);target.getOutputStream().flush();require(target.getInputStream().read()=='S');}
                SSLSocket secured=(SSLSocket)upstreamTls.get(endpoint.purpose()).getSocketFactory().createSocket(target,endpoint.peerName(),endpoint.target().getPort(),true);
                pair.add(secured);secured.setUseClientMode(true);secured.setEnabledProtocols(new String[]{"TLSv1.3","TLSv1.2"});
                SSLParameters parameters=secured.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");secured.setSSLParameters(parameters);secured.setSoTimeout(handshakeMs);secured.startHandshake();target=secured;
            }
            // One shared progress deadline covers both directions. A long
            // upload with no response bytes must not time out its idle reverse
            // reader while the request body is still making progress.
            if(startup!=null)target.getOutputStream().write(startup);
            client.setSoTimeout(0);target.setSoTimeout(0);pair.progress();Socket upstream=target;
            threads.submit(()->copy(pair,client,upstream));copy(pair,upstream,client);
        }catch(Exception refused){pair.close();}
    }
    private static byte[] postgresStartup(InputStream input,DatabaseIdentity identity)throws IOException{
        byte[] size=input.readNBytes(4);require(size.length==4);int length=ByteBuffer.wrap(size).getInt();require(length>=9&&length<=8192);
        byte[] payload=input.readNBytes(length-4);require(payload.length==length-4&&ByteBuffer.wrap(payload).getInt()==196608);
        var decoder=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        String fields=decoder.decode(ByteBuffer.wrap(payload,4,payload.length-4)).toString();require(fields.endsWith("\0"));String[] parts=fields.split("\0",-1);
        require(parts.length>=6&&parts[parts.length-1].isEmpty()&&parts[parts.length-2].isEmpty()&&(parts.length-2)%2==0&&parts.length<=34);
        Set<String> allowed=Set.of("user","database","client_encoding","DateStyle","TimeZone","extra_float_digits","application_name");var values=new HashMap<String,String>();
        for(int i=0;i<parts.length-2;i+=2){require(allowed.contains(parts[i])&&!parts[i+1].chars().anyMatch(Character::isISOControl)&&values.put(parts[i],parts[i+1])==null);}
        require(identity.username().equals(values.get("user"))&&identity.database().equals(values.get("database")));
        return ByteBuffer.allocate(length).putInt(length).put(payload).array();
    }
    private void copy(Pair pair,Socket from,Socket to){
        try{
            byte[] buffer=new byte[BUFFER];InputStream input=from.getInputStream();OutputStream output=to.getOutputStream();int length;
            while((length=input.read(buffer))>=0){if(length>0){output.write(buffer,0,length);pair.progress();}}
            to.shutdownOutput();if(pair.finished.incrementAndGet()==2)pair.close();
        }catch(Exception disconnected){pair.close();}
    }
    private final class Pair implements AutoCloseable {
        final List<Socket> sockets=new ArrayList<>();final AtomicBoolean done=new AtomicBoolean();final AtomicInteger finished=new AtomicInteger();final AtomicLong lastProgress=new AtomicLong(System.nanoTime());
        synchronized void add(Socket socket)throws IOException{if(done.get()){socket.close();throw new IOException("RELAY_CLOSED");}sockets.add(socket);}
        synchronized Socket first(){return sockets.getFirst();}
        void progress(){lastProgress.set(System.nanoTime());}
        @Override public void close(){
            if(!done.compareAndSet(false,true))return;
            List<Socket> copy; synchronized(this){copy=List.copyOf(sockets);}
            // Close raw transports before JSSE wrappers so a peer which stops
            // reading cannot indefinitely block the timeout monitor in close_notify.
            for(boolean tls:new boolean[]{false,true})for(Socket socket:copy)if((socket instanceof SSLSocket)==tls)try{socket.close();}catch(IOException ignored){}
            active.remove(this);slots.release();
        }
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        for(var socket:listeners)try{socket.close();}catch(IOException ignored){}
        for(var pair:active)pair.close();clock.shutdownNow();threads.shutdownNow();stopped.countDown();
    }
    public static SSLContext serverContext(byte[] pkcs12,char[] password)throws GeneralSecurityException,IOException{
        var keys=KeyStore.getInstance("PKCS12");keys.load(new ByteArrayInputStream(pkcs12),password);var names=Collections.list(keys.aliases());
        require(keys.size()<=8&&names.stream().filter(n->{try{return keys.isKeyEntry(n);}catch(KeyStoreException error){return false;}}).toList().equals(List.of("relay")));
        var chain=keys.getCertificateChain("relay");require(chain!=null&&chain.length>=2&&chain.length<=8);
        for(var cert:chain)((X509Certificate)cert).checkValidity();var leaf=(X509Certificate)chain[0];require(leaf.getBasicConstraints()<0
                &&leaf.getExtendedKeyUsage()!=null&&leaf.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"));
        for(int i=0;i<chain.length-1;i++){require(((X509Certificate)chain[i+1]).getBasicConstraints()>=0);chain[i].verify(chain[i+1].getPublicKey());}
        var managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,password);
        var context=SSLContext.getInstance("TLS");context.init(managers.getKeyManagers(),null,new SecureRandom());return context;
    }
    public static SSLContext upstreamContext(byte[] pem)throws GeneralSecurityException,IOException{
        var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem));require(!certificates.isEmpty()&&certificates.size()<=8);
        var store=KeyStore.getInstance("PKCS12");store.load(null,null);int i=0;
        for(var cert:certificates){var ca=(X509Certificate)cert;ca.checkValidity();require(ca.getBasicConstraints()>=0);store.setCertificateEntry("ca-"+(i++),ca);}
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);var context=SSLContext.getInstance("TLS");context.init(null,trust.getTrustManagers(),new SecureRandom());return context;
    }
    static byte[] privateBytes(Path path,int maximum)throws IOException{
        require(path.isAbsolute());for(Path p=path;p!=null;p=p.getParent())require(!Files.isSymbolicLink(p));
        for(Path p=path.getParent();p!=null;p=p.getParent())require(Integer.valueOf(0).equals(Files.getAttribute(p,"unix:uid"))&&((int)Files.getAttribute(p,"unix:mode")&0022)==0);
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)<=maximum
                &&Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))&&Integer.valueOf(0).equals(Files.getAttribute(path,"unix:uid")));
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] raw=input.readNBytes(maximum+1);require(raw.length<=maximum);return raw;}
    }
    /** Fixed root-private configuration, generated only by the native driver.
     * No listener/command/destination can be supplied through an HTTP body. */
    @SuppressWarnings("unchecked") public static void main(String[] args){
        try{
            require(args.length==1&&UUID.fromString(args[0]).toString().equals(args[0]));
            Path folder=Path.of("/opt/eatwhat-sites",args[0],"private","dependency-relay");var data=JSON.readValue(privateBytes(folder.resolve("config.json"),65536),Map.class);
            require(data.keySet().equals(Set.of("serverId","networkId","listenAddress","clientSubnet","maxConnections","httpUpstreamTls","databaseUpstreamTls","httpUpstreamPeerName","databaseUpstreamPeerName","databaseChannelBinding","databaseName","databaseUsername"))&&args[0].equals(data.get("serverId"))
                    &&data.get("networkId") instanceof String id&&id.matches("[a-f0-9]{64}"));
            String address=(String)data.get("listenAddress");require(address.matches("[0-9.]+")&&privateAddress(InetAddress.getByName(address)));
            String iface="br-"+data.get("networkId").toString().substring(0,12);String cidr=(String)data.get("clientSubnet");
            require(data.get("maxConnections") instanceof Integer max&&max>=1&&max<=32&&data.get("httpUpstreamTls") instanceof Boolean&&data.get("databaseUpstreamTls") instanceof Boolean);
            boolean http=(boolean)data.get("httpUpstreamTls"),database=(boolean)data.get("databaseUpstreamTls");
            String httpPeer=(String)data.get("httpUpstreamPeerName"),databasePeer=(String)data.get("databaseUpstreamPeerName");
            require(database?"disable".equals(data.get("databaseChannelBinding")):Set.of("disable","prefer").contains(data.get("databaseChannelBinding")));
            require(("eatwhat_auth_"+args[0].replace("-","")).equals(data.get("databaseUsername")));var databaseIdentity=new DatabaseIdentity((String)data.get("databaseName"),(String)data.get("databaseUsername"));
            String secret=new String(privateBytes(folder.resolve("server.password"),256),java.nio.charset.StandardCharsets.US_ASCII).strip();require(secret.matches("[A-Za-z0-9_-]{43,128}"));char[] password=secret.toCharArray();
            SSLContext tls;try{tls=serverContext(privateBytes(folder.resolve("server.p12"),65536),password);}finally{Arrays.fill(password,'\0');}
            var trusts=new HashMap<String,SSLContext>();
            for(String purpose:List.of("http","database"))if(purpose.equals("http")?http:database){
                Path scoped=folder.resolve("upstream-"+purpose+"-ca.pem");
                // Retain the original component's fixed shared-CA input for
                // old configs. Newly provisioned routes always use scoped files.
                Path file=Files.exists(scoped,LinkOption.NOFOLLOW_LINKS)?scoped:folder.resolve("upstream-ca.pem");
                trusts.put(purpose.equals("http")?"AUTHORITY_HTTP":"AUTHORITY_DATABASE",upstreamContext(privateBytes(file,65536)));
            }
            var endpoints=List.of(new Endpoint("AUTHORITY_HTTP",new InetSocketAddress(address,28443),iface,cidr,new InetSocketAddress("127.0.0.1",28090),Protocol.TLS,http?Upstream.TLS:Upstream.CLEAR,httpPeer,null),
                    new Endpoint("AUTHORITY_DATABASE",new InetSocketAddress(address,25443),iface,cidr,new InetSocketAddress("127.0.0.1",25432),Protocol.POSTGRESQL_TLS,database?Upstream.POSTGRESQL_TLS:Upstream.CLEAR,databasePeer,databaseIdentity));
            var relay=new BusinessDependencyRelay(tls,null,endpoints,(int)data.get("maxConnections"),Duration.ofSeconds(3),Duration.ofSeconds(5),Duration.ofSeconds(60),trusts);
            Runtime.getRuntime().addShutdownHook(new Thread(relay::close));System.out.println("{\"protocol\":\"eatwhat-business-dependency-relay-v1\",\"state\":\"LISTENING\"}");
            relay.stopped.await();if(relay.failed.get())throw new IOException("RELAY_LISTENER_FAILED");
        }catch(Exception failure){System.out.println("{\"code\":\"BUSINESS_DEPENDENCY_RELAY_UNAVAILABLE\"}");System.exit(1);}
    }
}
