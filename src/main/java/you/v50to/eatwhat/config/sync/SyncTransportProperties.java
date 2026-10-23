package you.v50to.eatwhat.config.sync;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import you.v50to.eatwhat.sync.transport.RetryPolicy;
import you.v50to.eatwhat.sync.transport.SyncHttpClient;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** Deliberately no generated toString: store passwords are never diagnostic labels. */
@Getter
@Setter
@ConfigurationProperties("app.sync")
public final class SyncTransportProperties {
    private boolean transportEnabled;
    private boolean syntheticOnly;
    private String peerSiteId;
    private UUID peerInstanceId;
    private URI peerBaseUrl;
    private Path keyStore;
    private String keyStorePassword;
    private Path trustStore;
    private String trustStorePassword;
    private Set<String> peerCertificateSha256 = Set.of();
    private String listenHost = "127.0.0.1";
    private int listenPort = 9443;
    private Duration requestTimeout = Duration.ofSeconds(5);
    private Duration leaseDuration = Duration.ofSeconds(30);
    private Duration pollInterval = Duration.ofSeconds(5);
    private Duration retryBase = Duration.ofSeconds(1);
    private Duration retryMax = Duration.ofMinutes(1);

    public void validate(SiteProperties site) {
        validate(site, false);
    }

    /**
     * Synthetic-only is the default. Carrying domain events needs an explicitly enabled domain and
     * app.sync.synthetic-only=false, so the operator opts in to non-synthetic traffic on purpose.
     */
    public void validate(SiteProperties site, boolean domainEventsEnabled) {
        if (!syntheticOnly && !domainEventsEnabled) {
            throw new IllegalArgumentException("app.sync.transport-enabled requires app.sync.synthetic-only=true");
        }
        if (site.getMode() == null || site.getMode() == SiteProperties.Mode.LEGACY) {
            throw new IllegalArgumentException("app.sync.transport-enabled requires an explicit site mode");
        }
        NodeIdentity local = new NodeIdentity(site.getId(), site.getInstanceId());
        validateTiming();
        if(you.v50to.eatwhat.cluster.ClusterRouting.configured(site)){
            if(syntheticOnly||!domainEventsEnabled||site.getAllocationSlot()==null)throw new IllegalArgumentException("Cluster transport requires domain integration and a bound allocation slot");
            return;
        }
        NodeIdentity peer = new NodeIdentity(peerSiteId, peerInstanceId);
        if (local.siteId().equals(peer.siteId()) || local.instanceId().equals(peer.instanceId())) {
            throw new IllegalArgumentException("Local and peer identities must differ");
        }
        if (peerCertificateSha256 == null || peerCertificateSha256.isEmpty() || peerCertificateSha256.size() > 2
                || peerCertificateSha256.stream().anyMatch(pin -> pin == null || !pin.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("app.sync.peer-certificate-sha256 requires one or two SHA-256 pins");
        }
        requireStore(keyStore, keyStorePassword); requireStore(trustStore, trustStorePassword);
        if (site.getMode() == SiteProperties.Mode.CAMPUS) SyncHttpClient.validateBaseUri(peerBaseUrl);
        else {
            if (peerBaseUrl != null) throw new IllegalArgumentException("EXTERNAL sync is passive and does not accept a peer URL");
            if (listenHost == null || !listenHost.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
                    || listenPort < 1024 || listenPort > 65535) {
                throw new IllegalArgumentException("app.sync listener requires an explicit IPv4 address and unprivileged port");
            }
            for (String octet : listenHost.split("\\.")) {
                if (Integer.parseInt(octet) > 255) throw new IllegalArgumentException("Invalid app.sync listen address");
            }
        }
    }

    private void validateTiming() {
        if (requestTimeout == null || requestTimeout.compareTo(Duration.ofMillis(250)) < 0
                || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0 || leaseDuration == null
                || leaseDuration.compareTo(requestTimeout.multipliedBy(3)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(15)) > 0 || pollInterval == null
                || pollInterval.compareTo(Duration.ofMillis(250)) < 0
                || pollInterval.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid app.sync timeout, lease or polling bounds");
        }
        new RetryPolicy(retryBase, retryMax);
    }

    private static void requireStore(Path path, String password) {
        if (path == null || !path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path)
                || password == null || password.isEmpty() || password.length() > 1024) {
            throw new IllegalArgumentException("app.sync TLS stores require readable absolute files and passwords");
        }
    }
}
