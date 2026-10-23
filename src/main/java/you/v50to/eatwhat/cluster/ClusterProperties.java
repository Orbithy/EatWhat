package you.v50to.eatwhat.cluster;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;
import java.util.*;

@Data
@ConfigurationProperties("app.cluster")
public class ClusterProperties {
    private boolean enabled;
    /** Optional operator-owned PEM CA for node HTTP clients only. */
    @ToString.Exclude private Path nodeCaFile;
    private boolean coordinator;
    /** Explicit single-authority mode. No remote member or cross-site replay is permitted. */
    private boolean singleSiteOnly;
    private boolean automaticGcEnabled=true;
    private String authorityOrigin;
    private String authorityApiPrefix = "";
    private String authoritySiteId;
    private UUID authorityInstanceId;
    private AuthStore authStore=new AuthStore();
    private ControlLease controlLease=new ControlLease();
    @ToString.Exclude private Path profilesFile;
    private Map<String,Profile> profiles=new LinkedHashMap<>();
    @Data public static class AuthStore {
        private boolean enabled;
        private String jdbcUrl;
        private String username;
        @ToString.Exclude private Path passwordFile;
    }
    @Data public static class ControlLease {
        private boolean enabled;
        private boolean candidate=true;
        /** False: heartbeat and accept an explicit administrator handoff, never claim an unowned/expired foreign lease. */
        private boolean automaticTakeover=true;
        private int durationSeconds=15;
        private String controllerName;
        /** Operator-pinned identity; allowed only with automatic-takeover=false. */
        private UUID controllerId;
    }
    @Data public static class Profile {
        private String siteId;
        private UUID instanceId;
        private String mode;
        private Integer allocationSlot;
        private String origin;
        private String apiPrefix = "";
        @ToString.Exclude private Path tokenFile;
        @ToString.Exclude private Path caFile;
        private Map<String,Integer> channels=new LinkedHashMap<>();
        public ClusterMember member(){if(allocationSlot==null)throw new ClusterFailure(503,"PROFILE_SLOT_REQUIRED");return new ClusterMember(siteId,instanceId,mode,allocationSlot);}
        @Override public String toString(){return "ClusterProfile[redacted]";}
    }
}
