package you.v50to.eatwhat.integration;

import you.v50to.eatwhat.sync.transport.NodeTls;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Ephemeral certificates signed by a test-only CA; never imports a host key or trust store. */
final class TransportTlsFixture {
    static final String PASSWORD = "synthetic-tls-only";
    record Identity(Path keyStore, String pin) { }

    private final Path trustStore;
    private final Identity campus;
    private final Identity external;
    private final Identity otherTrusted;
    private final Identity untrusted;
    private final Identity wrongHostname;

    private TransportTlsFixture(Path trustStore, Identity campus, Identity external,
                                Identity otherTrusted, Identity untrusted, Identity wrongHostname) {
        this.trustStore = trustStore;
        this.campus = campus;
        this.external = external;
        this.otherTrusted = otherTrusted;
        this.untrusted = untrusted;
        this.wrongHostname = wrongHostname;
    }

    static TransportTlsFixture create(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path ca = directory.resolve("ca.p12");
        Path caCertificate = directory.resolve("ca.crt");
        Path trust = directory.resolve("trust.p12");
        keytool(directory, "-genkeypair", "-alias", "ca", "-keystore", ca.toString(),
                "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=EatWhat Test CA",
                "-validity", "2", "-ext", "bc=ca:true", "-ext", "ku=keyCertSign,cRLSign");
        keytool(directory, "-exportcert", "-alias", "ca", "-keystore", ca.toString(),
                "-file", caCertificate.toString(), "-rfc");
        keytool(directory, "-importcert", "-alias", "ca", "-keystore", trust.toString(),
                "-file", caCertificate.toString(), "-noprompt");
        Identity campus = signed(directory, ca, caCertificate, "campus", "DNS:localhost,IP:127.0.0.1");
        Identity external = signed(directory, ca, caCertificate, "external", "DNS:localhost,IP:127.0.0.1");
        Identity other = signed(directory, ca, caCertificate, "other-trusted", "DNS:localhost,IP:127.0.0.1");
        Identity wrongHost = signed(directory, ca, caCertificate, "wrong-hostname", "DNS:wrong.example.invalid");
        Path rogueStore = directory.resolve("untrusted.p12");
        keytool(directory, "-genkeypair", "-alias", "node", "-keystore", rogueStore.toString(),
                "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Untrusted Test Node", "-validity", "2",
                "-ext", "SAN=DNS:localhost,IP:127.0.0.1", "-ext", "EKU=serverAuth,clientAuth");
        return new TransportTlsFixture(trust, campus, external, other, identity(rogueStore), wrongHost);
    }

    Path trustStore() { return trustStore; }
    Identity campus() { return campus; }
    Identity external() { return external; }
    Identity otherTrusted() { return otherTrusted; }
    Identity untrusted() { return untrusted; }
    Identity wrongHostname() { return wrongHostname; }

    /** Same ephemeral identity under the standalone relay's fixed private-key alias. */
    static byte[] relayStore(Identity identity)throws Exception {
        var old=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(identity.keyStore())){old.load(in,PASSWORD.toCharArray());}
        var store=KeyStore.getInstance("PKCS12");store.load(null,null);store.setKeyEntry("relay",old.getKey("node",PASSWORD.toCharArray()),PASSWORD.toCharArray(),old.getCertificateChain("node"));
        var out=new java.io.ByteArrayOutputStream();store.store(out,PASSWORD.toCharArray());return out.toByteArray();
    }

    SSLContext context(Identity local, Identity peer) {
        return NodeTls.create(local.keyStore(), PASSWORD.toCharArray(), trustStore,
                PASSWORD.toCharArray(), Set.of(peer.pin()));
    }

    /** Only the missing-client-certificate negative test uses a context without a key manager. */
    SSLContext withoutClientCertificate() throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(trustStore)) { trust.load(input, PASSWORD.toCharArray()); }
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[0], managers.getTrustManagers(), new SecureRandom());
        return context;
    }

    private static Identity signed(Path directory, Path ca, Path caCertificate, String name, String san) throws Exception {
        Path store = directory.resolve(name + ".p12");
        Path request = directory.resolve(name + ".csr");
        Path certificate = directory.resolve(name + ".crt");
        keytool(directory, "-genkeypair", "-alias", "node", "-keystore", store.toString(),
                "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + name, "-validity", "2");
        keytool(directory, "-certreq", "-alias", "node", "-keystore", store.toString(), "-file", request.toString());
        keytool(directory, "-gencert", "-alias", "ca", "-keystore", ca.toString(), "-infile", request.toString(),
                "-outfile", certificate.toString(), "-rfc", "-validity", "2", "-ext", "SAN=" + san,
                "-ext", "KU=digitalSignature,keyEncipherment", "-ext", "EKU=serverAuth,clientAuth");
        keytool(directory, "-importcert", "-alias", "ca", "-keystore", store.toString(),
                "-file", caCertificate.toString(), "-noprompt");
        keytool(directory, "-importcert", "-alias", "node", "-keystore", store.toString(),
                "-file", certificate.toString(), "-noprompt");
        return identity(store);
    }

    private static Identity identity(Path store) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, PASSWORD.toCharArray()); }
        String pin = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keys.getCertificate("node").getEncoded()));
        return new Identity(store, pin);
    }

    private static void keytool(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        command.addAll(List.of("-storetype", "PKCS12", "-storepass", PASSWORD));
        Path output = Files.createTempFile(directory, "keytool-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().clear();
        builder.environment().put("LANG", "C.UTF-8");
        Process process = builder.start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("Test certificate generation failed; diagnostic: " + output);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test keytool did not terminate");
            }
        }
    }
}
