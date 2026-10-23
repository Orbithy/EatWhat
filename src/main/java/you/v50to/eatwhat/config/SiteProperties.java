package you.v50to.eatwhat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Deployment identity only; selecting a site does not enable replication or cross-border consent. */
@Data
@Component
@ConfigurationProperties(prefix = "app.site")
public class SiteProperties {
    public enum Mode { LEGACY, CAMPUS, EXTERNAL }

    private Mode mode = Mode.LEGACY;
    private String id;
    /** Stable for this database installation; generate once, never on each application restart. */
    private UUID instanceId;
    /** Explicit, immutable multi-site allocation opt-in. Slots 0/1 retain the capped legacy odd/even domains. */
    private UUID clusterId;
    private Integer allocationSlot;
}
