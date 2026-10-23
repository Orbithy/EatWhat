package you.v50to.eatwhat.sync.catalog;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncEvent;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties("app.sync.catalog")
public final class CatalogSyncProperties {
    private boolean enabled;
    private boolean multiWriterEnabled;
    private String peerSiteId;
    private int backfillBatchSize = 32;
    private int maxHubImportBatchSize = 100;
    private int maxHubAssignments = 500;
    private Duration backfillLeaseDuration = Duration.ofSeconds(30);

    public void validate(SiteProperties site, String globalPeerSiteId) {
        if (site == null || site.getMode() == null || site.getMode() == SiteProperties.Mode.LEGACY) {
            throw new IllegalArgumentException("Explicit site is required for catalog sync");
        }
        if(!you.v50to.eatwhat.cluster.ClusterRouting.configured(site)){
            SyncEvent.requireName(peerSiteId, "catalog peer site", 64);
            if (peerSiteId.equals(site.getId()) || !peerSiteId.equals(globalPeerSiteId)) {
                throw new IllegalArgumentException("Catalog peer must match the configured remote peer");
            }
        }
        if (backfillBatchSize < 1 || backfillBatchSize > 128
                || maxHubImportBatchSize < 1 || maxHubImportBatchSize > 100
                || maxHubAssignments < 1 || maxHubAssignments > 500
                || backfillLeaseDuration == null || backfillLeaseDuration.isZero()
                || backfillLeaseDuration.isNegative() || backfillLeaseDuration.toMillis() < 1
                || backfillLeaseDuration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid catalog synchronization limits");
        }
    }
}
