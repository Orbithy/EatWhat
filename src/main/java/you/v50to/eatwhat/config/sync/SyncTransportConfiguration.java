package you.v50to.eatwhat.config.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.DeliveryService;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.InboxService;
import you.v50to.eatwhat.sync.activity.ActivityEventPolicy;
import you.v50to.eatwhat.sync.transport.EventPolicy;
import you.v50to.eatwhat.sync.transport.TransportMetrics;
import org.springframework.beans.factory.ObjectProvider;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.sync.transport-enabled", havingValue = "true")
@EnableConfigurationProperties(SyncTransportProperties.class)
public class SyncTransportConfiguration {
    @Bean public TransportMetrics transportMetrics() { return new TransportMetrics(); }

    @Bean
    @ConditionalOnProperty(name="app.cluster.enabled",havingValue="false",matchIfMissing=true)
    public SyncTransportRuntime syncTransportRuntime(SiteProperties site, SyncTransportProperties properties,
                                                     DeliveryService delivery, InboxService inbox, EventCodec codec,
                                                     TransportMetrics metrics,
                                                     ObjectProvider<EventPolicy> domains) {
        // Synthetic-only is the default allowlist; domain families are carried only when explicitly opted in.
        EventPolicy policy = properties.isSyntheticOnly() ? EventPolicy.SYNTHETIC_ONLY
                : EventPolicy.compose(domains.orderedStream().toList());
        return new SyncTransportRuntime(site, properties, delivery, inbox, codec, metrics, policy);
    }
}
