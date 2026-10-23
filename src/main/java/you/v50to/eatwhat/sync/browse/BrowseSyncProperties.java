package you.v50to.eatwhat.sync.browse;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncEvent;

@Getter @Setter @ConfigurationProperties("app.sync.browse")
public final class BrowseSyncProperties {
    private boolean enabled;
    private String peerSiteId;
    private int batchSize = 32;
    // Existing grants excluded browsing. Enabling this domain alone must not expand their scope.
    private String consentPolicyVersion = "cross-border-browse-v2";

    public void validate(SiteProperties site, String configuredPeer) {
        SyncEvent.requireName(consentPolicyVersion, "browse consent policy", 64);
        if (site.getMode() == null || site.getMode() == SiteProperties.Mode.LEGACY || batchSize < 1 || batchSize > 128) {
            throw new IllegalArgumentException("Invalid browse sync configuration");
        }
        if (!you.v50to.eatwhat.cluster.ClusterRouting.configured(site)) {
            SyncEvent.requireName(peerSiteId, "browse peer", 64);
            if (peerSiteId.equals(site.getId()) || !peerSiteId.equals(configuredPeer)) {
                throw new IllegalArgumentException("Matching remote peer required");
            }
        }
    }

    public void validatePolicy(String accountPolicyVersion) {
        if (!consentPolicyVersion.equals(accountPolicyVersion)) {
            throw new IllegalArgumentException("Browse synchronization requires the declared account consent policy");
        }
    }
}
