package you.v50to.eatwhat.service.storage.replication;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import you.v50to.eatwhat.config.FileCdnInvalidationProperties;

import java.net.URI;

/** Selects the CDN provider. Only active when the invalidation worker is enabled; NOOP unless WEBHOOK is configured. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.storage.files.cdn-invalidation.enabled", havingValue = "true")
public class FileCdnInvalidationConfiguration {
    @Bean
    public CdnInvalidationProvider cdnInvalidationProvider(FileCdnInvalidationProperties props) {
        if (props.getProvider() == FileCdnInvalidationProperties.Provider.WEBHOOK) {
            return new HttpWebhookCdnProvider(URI.create(props.getWebhookUrl()), props.getWebhookSecret(), props.getTimeout());
        }
        return new NoopCdnProvider();
    }
}
