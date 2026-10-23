package you.v50to.eatwhat.sync.notification;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncEvent;

@Getter @Setter @ConfigurationProperties("app.sync.notification")
public final class NotificationReadSyncProperties {
    private boolean enabled;
    private boolean historicalBackfillEnabled;
    private String peerSiteId;
    private int workerBatchSize = 32;

    public void validate(SiteProperties site) {
        if (site.getMode() == null || site.getMode() == SiteProperties.Mode.LEGACY) {
            throw new IllegalArgumentException("Explicit site required");
        }
        if(!you.v50to.eatwhat.cluster.ClusterRouting.configured(site)){
            SyncEvent.requireName(peerSiteId, "notification peer", 64);
            if (peerSiteId.equals(site.getId())) throw new IllegalArgumentException("Remote peer required");
        }
        if (workerBatchSize < 1 || workerBatchSize > 128) {
            throw new IllegalArgumentException("Invalid notification read worker batch size");
        }
    }
}
