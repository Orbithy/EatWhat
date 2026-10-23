package you.v50to.eatwhat.account.multiwriter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncTransactions;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.account.enabled",havingValue="true")
public class MultiwriterConfiguration {
    @Bean public MultiwriterAccounts multiwriterAccounts(JdbcTemplate jdbc,SyncTransactions transactions,SiteProperties site,org.springframework.core.env.Environment environment){return new MultiwriterAccounts(jdbc,transactions,site,environment.getProperty("app.account.multiwriter.enabled",Boolean.class,false));}
    @Bean public MultiwriterExchange multiwriterExchange(JdbcTemplate jdbc,SyncTransactions transactions,you.v50to.eatwhat.account.ConsentStore consent,MultiwriterAccounts accounts,SiteProperties site){return new MultiwriterExchange(jdbc,transactions,consent,accounts,site);}
}
