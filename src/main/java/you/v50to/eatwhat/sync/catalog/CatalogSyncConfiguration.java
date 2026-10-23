package you.v50to.eatwhat.sync.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.cluster.ClusterMembershipView;
import you.v50to.eatwhat.cluster.ClusterRouting;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncTransactions;

import java.util.Set;

/** Catalog event capture and handlers are absent unless the domain is explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.sync.catalog.enabled", havingValue = "true")
@EnableConfigurationProperties(CatalogSyncProperties.class)
public class CatalogSyncConfiguration {
    @Bean
    public CatalogDecisionBackfill catalogDecisionBackfill(JdbcTemplate jdbc,SyncTransactions transactions,CatalogMultiwriterSupport support,
            CatalogDecisionService service,CatalogCommands commands,ConsentStore consent,OutboxService outbox,SiteProperties site,CatalogSyncProperties properties,CatalogStore store,CatalogProjection projection) {
        var staging=new CatalogEventHandler(CatalogEvents.BRIDGE,jdbc,store,projection,consent,authority(jdbc,site,properties),sites(jdbc,site,properties));
        return new CatalogDecisionBackfill(jdbc,transactions,support,service,commands,consent,outbox,site,sites(jdbc,site,properties),staging,store);
    }
    @Bean
    public CatalogAuthorizationReviewWorker catalogAuthorizationReviewWorker(JdbcTemplate jdbc,SyncTransactions transactions,
            CatalogMultiwriterSupport support,@org.springframework.beans.factory.annotation.Qualifier("catalogMultiwriterActionHandler") CatalogMultiwriterHandler handler,CatalogDecisionService decisions,
            @org.springframework.beans.factory.annotation.Qualifier("catalogMultiwriterBridgeHandler") CatalogMultiwriterHandler bridge) {
        var worker=new CatalogAuthorizationReviewWorker(jdbc,transactions,support,handler,decisions);worker.setBridgeHandler(bridge);return worker;
    }
    @Bean
    public CatalogImportReplication catalogImportReplication(JdbcTemplate jdbc,CatalogStore store,CatalogMultiwriterSupport support,
            CatalogCommands commands,OutboxService outbox,SiteProperties site,CatalogSyncProperties properties) {
        return new CatalogImportReplication(jdbc,store,support,commands,outbox,site,sites(jdbc,site,properties));
    }
    @Bean
    public CatalogDecisionService catalogDecisionService(JdbcTemplate jdbc,SyncTransactions transactions,CatalogStore store,
            CatalogMultiwriterSupport support,OutboxService outbox,SiteProperties site,CatalogCommands commands,ConsentStore consent) {
        return new CatalogDecisionService(jdbc,transactions,store,support,outbox,site,commands,consent);
    }
    @Bean
    public CatalogDecisionHandler catalogDecisionHandler(JdbcTemplate jdbc,CatalogDecisionService decisions,SiteProperties site,CatalogSyncProperties properties) {
        return new CatalogDecisionHandler(decisions,sites(jdbc,site,properties));
    }
    @Bean
    public CatalogDecisionPolicy catalogDecisionPolicy(JdbcTemplate jdbc,ConsentStore consent,SiteProperties site,CatalogSyncProperties properties,CatalogDecisionBackfill backfill) {
        var policy=new CatalogDecisionPolicy(consent,sites(jdbc,site,properties));policy.setBackfill(backfill);return policy;
    }
    @Bean
    public CatalogMultiwriterSupport catalogMultiwriterSupport(JdbcTemplate jdbc, SyncTransactions transactions,
            SiteProperties site, org.springframework.beans.factory.ObjectProvider<you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts> accounts) {
        return new CatalogMultiwriterSupport(jdbc,accounts.getIfAvailable(()->new you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts(jdbc,transactions,site,false)));
    }
    @Bean
    public CatalogMultiwriterHandler catalogMultiwriterActionHandler(JdbcTemplate jdbc,CatalogStore store,
            CatalogProjection projection,ConsentStore consent,SiteProperties site,CatalogSyncProperties properties,CatalogMultiwriterSupport support) {
        var authority=authority(jdbc,site,properties);
        return new CatalogMultiwriterHandler(new CatalogEventHandler(CatalogEvents.ACTION,jdbc,store,projection,consent,authority,sites(jdbc,site,properties)),support,jdbc,authority);
    }
    @Bean
    public CatalogMultiwriterHandler catalogMultiwriterBridgeHandler(JdbcTemplate jdbc,CatalogStore store,
            CatalogProjection projection,ConsentStore consent,SiteProperties site,CatalogSyncProperties properties,CatalogMultiwriterSupport support) {
        var authority=authority(jdbc,site,properties);
        return new CatalogMultiwriterHandler(new CatalogEventHandler(CatalogEvents.BRIDGE,jdbc,store,projection,consent,authority,sites(jdbc,site,properties)),support,jdbc,authority);
    }
    @Bean
    public CatalogStore catalogStore(JdbcTemplate jdbc) {
        return new CatalogStore(jdbc);
    }

    @Bean
    public CatalogEventPolicy catalogEventPolicy(JdbcTemplate jdbc,ConsentStore consent, SiteProperties site,
                                                  CatalogSyncProperties properties) {
        properties.validate(site, properties.getPeerSiteId());
        return new CatalogEventPolicy(consent,authority(jdbc,site,properties),sites(jdbc,site,properties));
    }

    @Bean
    public CatalogEventHandler catalogActionEventHandler(JdbcTemplate jdbc, CatalogStore store,
            CatalogProjection projection, ConsentStore consent, SiteProperties site,
            CatalogSyncProperties properties) {
        properties.validate(site, properties.getPeerSiteId());
        return new CatalogEventHandler(CatalogEvents.ACTION, jdbc, store, projection, consent,authority(jdbc,site,properties),
                sites(jdbc,site,properties));
    }

    @Bean
    public CatalogEventHandler catalogStateBridgeEventHandler(JdbcTemplate jdbc, CatalogStore store,
            CatalogProjection projection, ConsentStore consent, SiteProperties site,
            CatalogSyncProperties properties) {
        properties.validate(site, properties.getPeerSiteId());
        return new CatalogEventHandler(CatalogEvents.BRIDGE, jdbc, store, projection, consent,authority(jdbc,site,properties),
                sites(jdbc,site,properties));
    }

    @Bean
    public CatalogCommands catalogCommands(JdbcTemplate jdbc, SyncTransactions transactions,
            OutboxService outbox, CatalogStore store, EntityIdentityService identities,
            ConsentStore consent, SiteProperties site, CatalogSyncProperties properties,
            CatalogProjection projection, CatalogMultiwriterSupport support) {
        properties.validate(site, properties.getPeerSiteId());
        CatalogCommands commands=new CatalogCommands(jdbc, transactions, outbox, store, identities, consent, site, properties, projection);
        commands.setMultiwriterSupport(support);
        return commands;
    }

    @Bean
    public CatalogBackfillStore catalogBackfillStore(JdbcTemplate jdbc, SyncTransactions transactions,
            CatalogCommands commands, ConsentStore consent, SiteProperties site, CatalogSyncProperties properties) {
        return new CatalogBackfillStore(jdbc, transactions, commands, consent, site, properties);
    }

    @Bean
    public CatalogBackfillWorker catalogBackfillWorker(CatalogBackfillStore store,
            CatalogSyncProperties properties) {
        return new CatalogBackfillWorker(store, properties.getBackfillLeaseDuration(),
                properties.getBackfillBatchSize());
    }

    private static java.util.function.Supplier<String> authority(JdbcTemplate jdbc,SiteProperties site,CatalogSyncProperties properties){
        var membership=new ClusterMembershipView(jdbc);
        return ()->ClusterRouting.authority(membership,site,properties.getPeerSiteId());
    }
    private static Set<String> sites(JdbcTemplate jdbc,SiteProperties site,CatalogSyncProperties properties){
        return ClusterMembershipView.sites(jdbc,ClusterRouting.legacySites(site,properties.getPeerSiteId()));
    }
}
