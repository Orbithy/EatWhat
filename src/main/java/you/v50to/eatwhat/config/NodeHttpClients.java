package you.v50to.eatwhat.config;

import javax.net.ssl.*;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.cert.*;
import java.time.Duration;
import java.util.*;

/** Per-client private node CA. Does not modify JVM defaults, public storage clients or hostname validation. */
public final class NodeHttpClients {
    private NodeHttpClients() {}
    /** One declared peer trust, with no global/private-CA union. The loaded
     * bytes stay frozen until an explicit profile reload. Changed or unsafe
     * files fail before a request can send a node token. */
    public static final class ScopedTrust {
        private final Path path;private final byte[] pem;private final HttpClient client;
        private ScopedTrust(Path path){this.path=path;this.pem=read(path);this.client=build(pem,false);}
        public HttpClient client(){
            if(!java.security.MessageDigest.isEqual(pem,read(path)))throw new IllegalStateException("NODE_PRIVATE_CA_CHANGED");
            return client;
        }
        @Override public String toString(){return "ScopedNodeTrust[redacted]";}
    }
    public static ScopedTrust scoped(Path caFile){if(caFile==null)throw new IllegalStateException("NODE_PRIVATE_CA_INVALID");return new ScopedTrust(caFile);}
    public static HttpClient create(Path caFile){
        return caFile==null?builder().build():build(read(caFile),true);
    }
    private static HttpClient.Builder builder(){return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER);}
    private static byte[] read(Path caFile){
        try{
            if(!caFile.isAbsolute()||!caFile.equals(caFile.normalize()))throw new Exception();
            for(Path p=caFile;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new Exception();
            if(!Files.isRegularFile(caFile,LinkOption.NOFOLLOW_LINKS)||Files.size(caFile)>65536
                    ||!Files.getPosixFilePermissions(caFile).equals(PosixFilePermissions.fromString("rw-------")))throw new Exception();
            byte[] pem;try(var input=Files.newInputStream(caFile,LinkOption.NOFOLLOW_LINKS)){pem=input.readNBytes(65537);}
            if(pem.length>65536)throw new Exception();String text=new String(pem,java.nio.charset.StandardCharsets.US_ASCII);
            if(!text.strip().startsWith("-----BEGIN CERTIFICATE-----")||!text.strip().endsWith("-----END CERTIFICATE-----")||text.contains("PRIVATE KEY"))throw new Exception();
            roots(pem);return pem;
        }catch(Exception invalid){throw new IllegalStateException("NODE_PRIVATE_CA_INVALID");}
    }
    private static KeyStore roots(byte[] pem)throws Exception{
        var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(pem));
        if(certificates.isEmpty()||certificates.size()>16)throw new Exception();
        KeyStore roots=KeyStore.getInstance("PKCS12");roots.load(null,null);int index=0;
        for(var certificate:certificates){var cert=(X509Certificate)certificate;cert.checkValidity();if(cert.getBasicConstraints()<0)throw new Exception();roots.setCertificateEntry("node-ca-"+(index++),cert);}return roots;
    }
    private static HttpClient build(byte[] pem,boolean systemRoots){
        try{
            var declared=manager(roots(pem));var tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{systemRoots?new AdditionalRoots(manager(null),declared):declared},null);
            var parameters=new SSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
            return builder().sslContext(tls).sslParameters(parameters).build();
        }catch(Exception invalid){throw new IllegalStateException("NODE_PRIVATE_CA_INVALID");}
    }
    private static X509ExtendedTrustManager manager(KeyStore roots)throws Exception{
        var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(roots);
        return Arrays.stream(factory.getTrustManagers()).filter(X509ExtendedTrustManager.class::isInstance).map(X509ExtendedTrustManager.class::cast).findFirst().orElseThrow();
    }
    private static final class AdditionalRoots extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager system,additional;
        AdditionalRoots(X509ExtendedTrustManager system,X509ExtendedTrustManager additional){this.system=system;this.additional=additional;}
        interface Check {void run(X509ExtendedTrustManager manager)throws CertificateException;}
        private void check(Check check)throws CertificateException{try{check.run(system);}catch(CertificateException first){check.run(additional);}}
        @Override public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{check(m->m.checkServerTrusted(c,a));}
        @Override public void checkServerTrusted(X509Certificate[] c,String a,Socket s)throws CertificateException{check(m->m.checkServerTrusted(c,a,s));}
        @Override public void checkServerTrusted(X509Certificate[] c,String a,SSLEngine e)throws CertificateException{check(m->m.checkServerTrusted(c,a,e));}
        @Override public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{check(m->m.checkClientTrusted(c,a));}
        @Override public void checkClientTrusted(X509Certificate[] c,String a,Socket s)throws CertificateException{check(m->m.checkClientTrusted(c,a,s));}
        @Override public void checkClientTrusted(X509Certificate[] c,String a,SSLEngine e)throws CertificateException{check(m->m.checkClientTrusted(c,a,e));}
        @Override public X509Certificate[] getAcceptedIssuers(){return java.util.stream.Stream.concat(Arrays.stream(system.getAcceptedIssuers()),Arrays.stream(additional.getAcceptedIssuers())).toArray(X509Certificate[]::new);}
    }
}
