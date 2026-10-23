package you.v50to.eatwhat.control;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Separate opt-in control plane. No endpoints, listeners or commands exist by default. */
@Data
@ConfigurationProperties("app.control")
public class ControlProperties {
    private boolean enabled;
    /** Optional operator-owned PEM CA for node HTTP clients only. */
    @ToString.Exclude private Path nodeCaFile;
    private String environmentKind = "";
    private Path stateDirectory;
    /** Remote controller only: never create local state when the required NFS mount is absent. */
    private boolean sharedStateRequired;
    private you.v50to.eatwhat.control.independent.IndependentControlProperties independent = new you.v50to.eatwhat.control.independent.IndependentControlProperties();
    public enum DatabaseMode { MIGRATE, EXISTING_AUTHORITY, INDEPENDENT_LOCAL }
    /** New shared controller: validate an already activated authority, never migrate it. */
    private DatabaseMode databaseMode = DatabaseMode.MIGRATE;
    private String authorityDatabaseFingerprint;
    private Map<String, Node> nodes = new LinkedHashMap<>();
    private Map<String, Release> releases = new LinkedHashMap<>();
    private Map<String, Proxy> proxies = new LinkedHashMap<>();
    @ToString.Exclude private Path nodeTokenFile;
    private boolean nodeEnabled;
    private Path repository;
    private Path javaHome;
    private Deployment deployment = new Deployment();
    private Servers servers = new Servers();
    /** Reviewed local catalog only. No HTTP-supplied artifact or manifest paths. */
    private ControllerBootstrap controllerBootstrap = new ControllerBootstrap();
    @Data public static class ControllerBootstrap {
        private boolean enabled;
        private Path controllerArtifact;
        private String controllerSha256;
        private Path uiArchive;
        private String uiSha256;
        private Path uiManifest;
        private String acceptanceEvidenceId;
    }
    @Data public static class Servers {
        private Path keyscanExecutable = Path.of("/usr/bin/ssh-keyscan");
        private boolean installationEnabled;
        private boolean clusterEnrollmentEnabled;
        /** Private operator inventory for remote-member configuration; never supplied by HTTP. */
        @ToString.Exclude private Path memberConfigurationsFile;
        /** Per-process private physical routes for shared registry entries; credentials and pins are unchanged. */
        @ToString.Exclude private Path sshRoutesFile;
        /** Private operator-owned SSH key references; only identifiers and labels are public. */
        @ToString.Exclude private Path credentialsFile;
        private String jreImage = "eclipse-temurin:21-jre";
        private String postgresImage = "postgis/postgis:16-3.5";
        private String redisImage = "redis:7.4-alpine";
        private Path imageArchive;
        private String imageArchiveSha256;
    }
    @Data public static class Node {
        private String origin;
        private String apiPrefix = "";
        private String siteId;
        private UUID instanceId;
        @ToString.Exclude private Path tokenFile;
        /** Offline recovery catalog only; does not prove actual proxy wiring or runtime state. */
        private Map<String,Integer> channels = new LinkedHashMap<>();
    }
    @Data public static class Release {
        private Path artifact;
        private String sha256;
        private String acceptanceEvidenceId;
        private boolean rollbackCompatible;
        private java.util.List<String> compatibleFromSha256 = new java.util.ArrayList<>();
    }
    @Data public static class Deployment {
        public enum TargetSource { STATIC, REGISTERED_SERVERS }
        private boolean enabled;
        private TargetSource targetSource = TargetSource.STATIC;
        private Path sshExecutable = Path.of("/usr/bin/ssh");
        private Path knownHostsFile;
        @ToString.Exclude private Path identityFile;
        private Path controllerArtifact;
        private String authorityOrigin;
        @ToString.Exclude private Path authorityCaFile;
        private int timeoutSeconds = 900;
        private Map<String, DeploymentTarget> targets = new LinkedHashMap<>();
    }
    @Data public static class DeploymentTarget {
        private String host;
        private String user;
        private int port = 22;
        private String agent = "/opt/eatwhat/deploy/remote-release.py";
        private String config = "/opt/eatwhat/deploy/release.json";
        private String siteId;
        private UUID instanceId;
        private String currentSha256;
        private String restoreEvidenceId;
        private boolean controlHost;
        private UUID serverId;
        private long serverRevision;
        private long membershipEpoch;
        private UUID clusterId;
        private String connectionFingerprint;
        private String databaseFingerprint;
        private String deploymentManifestHash;
        private String authorityOrigin;
        @ToString.Exclude private String authorityCaPem;
        private String remoteConfigurationProof;
        @ToString.Exclude private String encryptedAuthorityToken;
        @ToString.Exclude private String encryptedConnection;
    }
    @Data public static class Proxy {
        private int listenPort;
        private String targetHost;
        private int targetPort;
        private int maximumBlockSeconds = 60;
    }
}
