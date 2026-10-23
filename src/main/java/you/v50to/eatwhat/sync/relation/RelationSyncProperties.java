package you.v50to.eatwhat.sync.relation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncEvent;

@Getter @Setter @ConfigurationProperties("app.sync.relation")
public final class RelationSyncProperties {
    private boolean enabled;private String peerSiteId;
    private int backfillBatchSize = 32;
    private java.time.Duration backfillLeaseDuration = java.time.Duration.ofSeconds(30);
    public void validate(SiteProperties site) {
        if(site.getMode()==null || site.getMode()==SiteProperties.Mode.LEGACY) throw new IllegalArgumentException("Explicit site required");
        if(!you.v50to.eatwhat.cluster.ClusterRouting.configured(site)){
            SyncEvent.requireName(peerSiteId,"relation peer",64);
            if(peerSiteId.equals(site.getId())) throw new IllegalArgumentException("Remote peer required");
        }
        if (backfillBatchSize < 1 || backfillBatchSize > 128 || backfillLeaseDuration == null
                || backfillLeaseDuration.isNegative() || backfillLeaseDuration.isZero()) {
            throw new IllegalArgumentException("Invalid relation backfill limits");
        }
    }
}
