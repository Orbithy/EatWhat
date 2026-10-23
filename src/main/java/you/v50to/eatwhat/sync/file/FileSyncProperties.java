package you.v50to.eatwhat.sync.file;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.config.sync.SyncTransportProperties;
import java.time.Duration;

@Getter @Setter @ConfigurationProperties("app.sync.files")
public final class FileSyncProperties {
    private boolean enabled;
    private int batchSize=32;
    private Duration pollInterval=Duration.ofSeconds(5);
    public void validate(SiteProperties site,SyncTransportProperties transport,boolean replicationEnabled) {
        if(site.getMode()==null||site.getMode()==SiteProperties.Mode.LEGACY||!replicationEnabled
                ||!transport.isTransportEnabled()||transport.isSyntheticOnly()||batchSize<1||batchSize>100
                ||pollInterval==null||pollInterval.compareTo(Duration.ofMillis(250))<0||pollInterval.compareTo(Duration.ofMinutes(5))>0)
            throw new IllegalArgumentException("File channel requires explicit site, replication and domain transport; invalid worker bounds");
        new you.v50to.eatwhat.sync.transport.NodeIdentity(site.getId(),site.getInstanceId());
        if(you.v50to.eatwhat.cluster.ClusterRouting.configured(site))return;
        var peer=new you.v50to.eatwhat.sync.transport.NodeIdentity(transport.getPeerSiteId(),transport.getPeerInstanceId());
        if(peer.siteId().equals(site.getId())||peer.instanceId().equals(site.getInstanceId())) throw new IllegalArgumentException("Distinct file peer required");
    }
}
