package you.v50to.eatwhat.account.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import you.v50to.eatwhat.account.*;
import you.v50to.eatwhat.account.transport.AccountNodeClient;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.mapper.ContactMapper;
import you.v50to.eatwhat.mapper.PrivacyMapper;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import you.v50to.eatwhat.sync.transport.NodeTls;

import javax.net.ssl.SSLContext;
import java.util.Arrays;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true")
@EnableConfigurationProperties(AccountProperties.class)
public class AccountConfiguration {
    @Bean public ConsentPolicyService consentPolicyService(JdbcTemplate jdbc,SyncTransactions transactions,
            you.v50to.eatwhat.sync.OutboxService outbox,SiteProperties site,AccountProperties properties) {
        var membership=new you.v50to.eatwhat.cluster.ClusterMembershipView(jdbc);
        java.util.function.Supplier<NodeIdentity> authority=()->{
            if(!you.v50to.eatwhat.cluster.ClusterRouting.configured(site))return site.getMode()==SiteProperties.Mode.CAMPUS
                    ?new NodeIdentity(site.getId(),site.getInstanceId())
                    :new NodeIdentity(properties.getPeerSiteId(),properties.getPeerInstanceId());
            String id=you.v50to.eatwhat.cluster.ClusterRouting.authority(membership,site,properties.getPeerSiteId());
            return membership.retained().stream().filter(m->m.siteId().equals(id)).map(you.v50to.eatwhat.cluster.ClusterMember::identity)
                    .findFirst().orElseThrow(AccountUnavailableException::new);
        };
        return new ConsentPolicyService(jdbc,transactions,outbox,site,properties.getPolicyVersion(),authority,
                you.v50to.eatwhat.cluster.ClusterRouting.legacyRecipients(site,properties.getPeerSiteId()));
    }

    @Bean public ConsentPolicyEvents consentPolicyEvents(ConsentPolicyService policies){return new ConsentPolicyEvents(policies);}

    @Bean public ConsentStore consentStore(JdbcTemplate jdbc, SyncTransactions transactions, SiteProperties site,
                                           AccountProperties properties, org.springframework.beans.factory.ObjectProvider<you.v50to.eatwhat.cluster.ClusterProperties> cluster) {
        properties.validate(site);
        var peer=peer(site,properties,cluster);
        return new ConsentStore(jdbc, transactions, site, properties.getPolicyVersion(),
                properties.getAuthorizationTtl(), properties.getProofTtl(),
                peer==null?null:peer.siteId(),peer==null?null:peer.instanceId());
    }

    @Bean("accountTls") @ConditionalOnProperty(name="app.cluster.enabled",havingValue="false",matchIfMissing=true)
    public SSLContext accountTls(SiteProperties site, AccountProperties properties) {
        properties.validate(site);
        char[] key = properties.getKeyStorePassword().toCharArray();
        char[] trust = properties.getTrustStorePassword().toCharArray();
        try { return NodeTls.create(properties.getKeyStore(), key, properties.getTrustStore(), trust,
                properties.getPeerCertificateSha256()); }
        finally { Arrays.fill(key, '\0'); Arrays.fill(trust, '\0'); }
    }

    @Bean(destroyMethod = "close") @ConditionalOnProperty(name="app.cluster.enabled",havingValue="false",matchIfMissing=true)
    public AccountNodeClient accountChannel(SiteProperties site,
            AccountProperties properties, @Qualifier("accountTls") SSLContext tls) {
        return new AccountNodeClient(properties.getPeerBaseUrl(), tls,
                new NodeIdentity(site.getId(), site.getInstanceId()),
                new NodeIdentity(properties.getPeerSiteId(), properties.getPeerInstanceId()), properties.getRequestTimeout());
    }

    @Bean public AccountAuthService accountAuthService(ConsentStore store, SiteProperties site, PasswordEncoder encoder,
            UserMapper users, ContactMapper contacts, PrivacyMapper privacy, PlatformTransactionManager transactions,
            AccountChannel channel, AccountProperties properties, StringRedisTemplate redis,
            org.springframework.beans.factory.ObjectProvider<you.v50to.eatwhat.cluster.ClusterProperties> cluster) {
        var peer=peer(site,properties,cluster);
        return new AccountAuthService(store, site, encoder, users, contacts, privacy, transactions, channel,
                properties.getPolicyVersion(),peer==null?null:peer.siteId(), redis);
    }
    private static NodeIdentity peer(SiteProperties site,AccountProperties properties,org.springframework.beans.factory.ObjectProvider<you.v50to.eatwhat.cluster.ClusterProperties> cluster){
        if(you.v50to.eatwhat.cluster.ClusterRouting.configured(site)){
            if(site.getMode()==SiteProperties.Mode.CAMPUS)return null;
            var authority=cluster.getObject();return new NodeIdentity(authority.getAuthoritySiteId(),authority.getAuthorityInstanceId());
        }
        return new NodeIdentity(properties.getPeerSiteId(),properties.getPeerInstanceId());
    }

    @Bean public AccountAccessGate accountAccessGate(AccountAuthService authentication) {
        return new AccountAccessGate(authentication);
    }

    @Bean public AccountSyncWorker accountSyncWorker(ConsentStore store, AccountChannel channel,
            AccountAuthService authentication, SiteProperties site, AccountProperties properties) {
        return new AccountSyncWorker(store, channel, authentication, site, properties.getLeaseDuration());
    }

    @Bean public AccountNodeDispatcher accountNodeDispatcher(SiteProperties site, ConsentStore store,
                                                            AccountAuthService authentication) {
        return new AccountNodeDispatcher(site, store, authentication);
    }

    @Bean @ConditionalOnProperty(name="app.cluster.enabled",havingValue="false",matchIfMissing=true)
    public AccountRuntime accountRuntime(SiteProperties site, AccountProperties properties,
            @Qualifier("accountTls") SSLContext tls, AccountNodeClient client, AccountNodeDispatcher dispatcher,
            AccountSyncWorker worker) {
        return new AccountRuntime(site, properties, tls, client, dispatcher, worker);
    }
}
