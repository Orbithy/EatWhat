package you.v50to.eatwhat.account.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import you.v50to.eatwhat.sync.transport.SyncHttpClient;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** No toString: TLS passwords must never become diagnostics. Bilateral exercises stay synthetic. */
@Getter
@Setter
@ConfigurationProperties("app.account")
public final class AccountProperties {
    private boolean enabled;
    private boolean syntheticOnly;
    private String policyVersion = "cross-border-v1";
    private Duration authorizationTtl = Duration.ofMinutes(5);
    private Duration proofTtl = Duration.ofMinutes(5);
    private String peerSiteId;
    private UUID peerInstanceId;
    private URI peerBaseUrl;
    private Path keyStore;
    private String keyStorePassword;
    private Path trustStore;
    private String trustStorePassword;
    private Set<String> peerCertificateSha256 = Set.of();
    private String listenHost = "127.0.0.1";
    private int listenPort = 9444;
    private Duration requestTimeout = Duration.ofSeconds(5);
    private Duration leaseDuration = Duration.ofSeconds(30);
    private Duration pollInterval = Duration.ofSeconds(2);

    public void validate(SiteProperties site) {
        if (site.getMode() == null || site.getMode() == SiteProperties.Mode.LEGACY) {
            throw new IllegalArgumentException("Account exercise requires synthetic-only and an explicit site");
        }
        boolean clustered=you.v50to.eatwhat.cluster.ClusterRouting.configured(site);
        if(!syntheticOnly&&!clustered)throw new IllegalArgumentException("Bilateral account exercise requires synthetic-only");
        var local = new NodeIdentity(site.getId(), site.getInstanceId());
        if (policyVersion == null || !policyVersion.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid account policy version");
        }
        bounded(authorizationTtl, Duration.ofSeconds(1), Duration.ofMinutes(5));
        bounded(proofTtl, Duration.ofSeconds(1), Duration.ofMinutes(5));
        bounded(requestTimeout, Duration.ofMillis(250), Duration.ofSeconds(10));
        bounded(leaseDuration, requestTimeout.multipliedBy(3), Duration.ofMinutes(2));
        bounded(pollInterval, Duration.ofMillis(100), Duration.ofMinutes(1));
        if(clustered)return;
        var peer = new NodeIdentity(peerSiteId, peerInstanceId);
        if (local.siteId().equals(peer.siteId()) || local.instanceId().equals(peer.instanceId())) {
            throw new IllegalArgumentException("Account node identities must differ");
        }
        SyncHttpClient.validateBaseUri(peerBaseUrl);
        if (peerCertificateSha256 == null || peerCertificateSha256.isEmpty() || peerCertificateSha256.size() > 2
                || peerCertificateSha256.stream().anyMatch(pin -> pin == null || !pin.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Account TLS requires one or two peer pins");
        }
        store(keyStore, keyStorePassword); store(trustStore, trustStorePassword);
        if (listenHost == null || !listenHost.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
                || listenPort < 1024 || listenPort > 65535) {
            throw new IllegalArgumentException("Account listener requires an explicit IPv4 and unprivileged port");
        }
        for (String part : listenHost.split("\\.")) {
            if (Integer.parseInt(part) > 255) throw new IllegalArgumentException("Invalid account listen address");
        }
    }

    private static void bounded(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Invalid account time bound");
        }
    }

    private static void store(Path path, String password) {
        if (path == null || !path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path)
                || password == null || password.isEmpty() || password.length() > 1024) {
            throw new IllegalArgumentException("Account TLS requires readable stores and passwords");
        }
    }
}
