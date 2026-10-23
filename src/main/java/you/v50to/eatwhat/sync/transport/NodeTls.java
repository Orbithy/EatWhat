package you.v50to.eatwhat.sync.transport;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Mutual TLS with normal PKIX validation and a configured peer leaf pin, checked during the handshake. */
public final class NodeTls {
    private NodeTls() { }

    public static SSLContext create(Path keyStore, char[] keyPassword, Path trustStore,
                                    char[] trustPassword, Set<String> peerSha256) {
        char[] keySecret = keyPassword == null ? null : keyPassword.clone();
        char[] trustSecret = trustPassword == null ? null : trustPassword.clone();
        try {
            if (keyStore == null || trustStore == null || keySecret == null || trustSecret == null
                    || keySecret.length == 0 || trustSecret.length == 0 || peerSha256 == null
                    || peerSha256.isEmpty() || peerSha256.size() > 2
                    || peerSha256.stream().anyMatch(pin -> pin == null || !pin.matches("[a-fA-F0-9]{64}"))) {
                throw new IllegalArgumentException();
            }
            List<byte[]> pins = peerSha256.stream().map(HexFormat.of()::parseHex).toList();
            KeyStore keys = load(keyStore, keySecret);
            boolean privateKeyFound = false;
            var aliases = keys.aliases();
            while (aliases.hasMoreElements()) {
                if (keys.entryInstanceOf(aliases.nextElement(), KeyStore.PrivateKeyEntry.class)) privateKeyFound = true;
            }
            if (!privateKeyFound) throw new IllegalArgumentException();
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, keySecret);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(load(trustStore, trustSecret));
            X509ExtendedTrustManager delegate = Arrays.stream(trustManagers.getTrustManagers())
                    .filter(X509ExtendedTrustManager.class::isInstance)
                    .map(X509ExtendedTrustManager.class::cast).findFirst().orElseThrow();
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), new TrustManager[]{new PinnedTrustManager(delegate, pins)}, null);
            return context;
        } catch (Exception failure) {
            // Paths, store passwords, certificate contents and provider exception text stay out of logs/API errors.
            throw new IllegalStateException("Node TLS configuration could not be loaded");
        } finally {
            if (keySecret != null) Arrays.fill(keySecret, '\0');
            if (trustSecret != null) Arrays.fill(trustSecret, '\0');
        }
    }

    private static KeyStore load(Path path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) { store.load(input, password); }
        return store;
    }

    private static final class PinnedTrustManager extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        private final List<byte[]> pins;

        private PinnedTrustManager(X509ExtendedTrustManager delegate, List<byte[]> pins) {
            this.delegate = delegate;
            this.pins = pins;
        }

        private void check(X509Certificate[] chain, TrustCheck standardValidation) throws CertificateException {
            try {
                standardValidation.run();
                if (chain == null || chain.length == 0) throw new CertificateException();
                chain[0].checkValidity();
                byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                boolean matched = false;
                for (byte[] pin : pins) matched |= MessageDigest.isEqual(actual, pin);
                if (!matched) throw new CertificateException();
            } catch (Exception failure) {
                throw new CertificateException("Node TLS peer validation failed");
            }
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, () -> delegate.checkClientTrusted(chain, authType));
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, () -> delegate.checkServerTrusted(chain, authType));
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            check(chain, () -> delegate.checkClientTrusted(chain, authType, socket));
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            // Passing the original socket/engine preserves the HTTPS endpoint identification algorithm.
            check(chain, () -> delegate.checkServerTrusted(chain, authType, socket));
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            check(chain, () -> delegate.checkClientTrusted(chain, authType, engine));
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            check(chain, () -> delegate.checkServerTrusted(chain, authType, engine));
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers().clone(); }
    }

    @FunctionalInterface private interface TrustCheck { void run() throws CertificateException; }
}
