package you.v50to.eatwhat.sync.activity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncEvent;

/** Per-domain switch; disabled by default and independent of the transport switch. */
@Getter
@Setter
@ConfigurationProperties("app.sync.activity")
public final class ActivitySyncProperties {
    private boolean enabled;
    private boolean durableIntentsEnabled;
    private String peerSiteId;

    public void validate(SiteProperties site) {
        if (site.getMode() == null || site.getMode() == SiteProperties.Mode.LEGACY) {
            throw new IllegalArgumentException("app.sync.activity.enabled requires an explicit site mode");
        }
        if(!you.v50to.eatwhat.cluster.ClusterRouting.configured(site)){
            SyncEvent.requireName(peerSiteId, "app.sync.activity.peer-site-id", 64);
            if (peerSiteId.equals(site.getId())) throw new IllegalArgumentException("Activity peer must be a remote site");
        }
    }
}
