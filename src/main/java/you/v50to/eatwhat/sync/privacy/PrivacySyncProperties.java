package you.v50to.eatwhat.sync.privacy;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncEvent;

@Getter @Setter @ConfigurationProperties("app.sync.privacy")
public final class PrivacySyncProperties {
    private boolean enabled;
    private String peerSiteId;

    public void validate(SiteProperties site, String configuredPeer) {
        if (site.getMode() == null || site.getMode() == SiteProperties.Mode.LEGACY) {
            throw new IllegalArgumentException("Explicit site required");
        }
        if(!you.v50to.eatwhat.cluster.ClusterRouting.configured(site)){
            SyncEvent.requireName(peerSiteId, "privacy peer", 64);
            if (peerSiteId.equals(site.getId()) || !peerSiteId.equals(configuredPeer)) {
                throw new IllegalArgumentException("Matching remote peer required");
            }
        }
    }
}
