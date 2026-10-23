package you.v50to.eatwhat.sync.relation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.sync.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.sync.relation.enabled",havingValue="true")
@EnableConfigurationProperties(RelationSyncProperties.class)
public class RelationSyncConfiguration {
    @Bean public RelationEvents relationEventPolicy(JdbcTemplate jdbc,ConsentStore consent, SiteProperties site,
            RelationSyncProperties properties) {
        properties.validate(site);
        return new RelationEvents(consent, you.v50to.eatwhat.cluster.ClusterMembershipView.sites(jdbc,you.v50to.eatwhat.cluster.ClusterRouting.legacySites(site,properties.getPeerSiteId())));
    }
    @Bean public RelationStore relationStore(JdbcTemplate jdbc){return new RelationStore(jdbc);}
    @Bean public RelationCausalStore relationCausalStore(JdbcTemplate jdbc){return new RelationCausalStore(jdbc);}
    @Bean public RelationEventHandler relationEventHandler(JdbcTemplate jdbc,RelationStore store,
            RelationCausalStore causal,ConsentStore consent){return new RelationEventHandler(jdbc,store,causal,consent);}
    @Bean public RelationCausalEventHandler relationCausalActionHandler(JdbcTemplate jdbc,RelationStore store,
            RelationCausalStore causal,ConsentStore consent, SiteProperties site, RelationSyncProperties properties){
        properties.validate(site);
        return new RelationCausalEventHandler(RelationCausalEvents.ACTION,jdbc,store,causal,consent,
                you.v50to.eatwhat.cluster.ClusterMembershipView.sites(jdbc,you.v50to.eatwhat.cluster.ClusterRouting.legacySites(site,properties.getPeerSiteId())));}
    @Bean public RelationCausalEventHandler relationCausalBridgeHandler(JdbcTemplate jdbc,RelationStore store,
            RelationCausalStore causal,ConsentStore consent, SiteProperties site, RelationSyncProperties properties){
        properties.validate(site);
        return new RelationCausalEventHandler(RelationCausalEvents.BRIDGE,jdbc,store,causal,consent,
                you.v50to.eatwhat.cluster.ClusterMembershipView.sites(jdbc,you.v50to.eatwhat.cluster.ClusterRouting.legacySites(site,properties.getPeerSiteId())));}
    @Bean public RelationCommands relationCommands(JdbcTemplate jdbc,SyncTransactions tx,RelationStore store,
            RelationCausalStore causal,OutboxService outbox, EntityIdentityService ids,ConsentStore consent,
            SiteProperties site,RelationSyncProperties p){
        p.validate(site);return new RelationCommands(jdbc,tx,store,causal,outbox,ids,consent,site,p.getPeerSiteId());
    }
    @Bean public RelationBackfillStore relationBackfillStore(JdbcTemplate jdbc, SyncTransactions tx,
            RelationStore store, RelationCausalStore causal, OutboxService outbox, EntityIdentityService ids, ConsentStore consent,
            SiteProperties site, RelationSyncProperties properties) {
        return new RelationBackfillStore(jdbc, tx, store, causal, outbox, ids, consent, site, properties.getPeerSiteId());
    }
    @Bean public RelationBackfillWorker relationBackfillWorker(RelationBackfillStore store,
            RelationSyncProperties properties) {
        return new RelationBackfillWorker(store, properties.getBackfillLeaseDuration(),
                properties.getBackfillBatchSize());
    }
}
