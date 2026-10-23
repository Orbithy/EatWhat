package you.v50to.eatwhat.sync.preferences;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncEvent;

@Getter @Setter @ConfigurationProperties("app.sync.preferences")
public final class PreferenceSyncProperties {
    private boolean enabled;
    private String peerSiteId;
    private int batchSize=32;
    private String consentPolicyVersion="cross-border-preferences-v3";
    public void validate(SiteProperties site,String peer) {
        SyncEvent.requireName(consentPolicyVersion,"preferences policy",64);
        if(site.getMode()==null||site.getMode()==SiteProperties.Mode.LEGACY||batchSize<1||batchSize>128) throw new IllegalArgumentException("Invalid preferences sync configuration");
        if(!you.v50to.eatwhat.cluster.ClusterRouting.configured(site)) {
            SyncEvent.requireName(peerSiteId,"preferences peer",64);
            if(peerSiteId.equals(site.getId())||!peerSiteId.equals(peer)) throw new IllegalArgumentException("Matching remote peer required");
        }
    }
    public void validatePolicy(String accountPolicy) {
        if(!consentPolicyVersion.equals(accountPolicy)) throw new IllegalArgumentException("Preferences synchronization requires declared account policy");
    }
}
