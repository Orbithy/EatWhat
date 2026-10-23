package you.v50to.eatwhat.control;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.config.SiteProperties;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.control.enabled",havingValue="true")
public class ControlAccountConfiguration {
    @Bean ControlAccountSecurity controlAccountSecurity(JdbcTemplate jdbc,SiteProperties site){return new ControlAccountSecurity(jdbc,site);}
    @Bean ControlAccountWeb controlAccountWeb(ControlAccountSecurity security){return new ControlAccountWeb(security);}
    @Bean ControlAccountService controlAccountService(JdbcTemplate jdbc,org.springframework.security.crypto.password.PasswordEncoder passwords,ControlAccountSecurity security,org.springframework.transaction.PlatformTransactionManager manager,org.springframework.data.redis.core.StringRedisTemplate redis,org.springframework.beans.factory.ObjectProvider<you.v50to.eatwhat.cluster.ClusterControlLease> lease,org.springframework.beans.factory.ObjectProvider<you.v50to.eatwhat.cluster.ClusterProperties> cluster){return new ControlAccountService(jdbc,passwords,security,manager,redis,lease,cluster);}
}
