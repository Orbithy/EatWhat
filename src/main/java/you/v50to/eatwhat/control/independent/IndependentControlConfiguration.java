package you.v50to.eatwhat.control.independent;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.callback.Callback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import you.v50to.eatwhat.cluster.ClusterControlLease;
import you.v50to.eatwhat.config.SiteProperties;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Validate a deliberately provisioned local management replica. No identity
 * rebinding, business migrations or business migration callbacks occur here. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.control.independent.enabled",havingValue="true")
@EnableConfigurationProperties(IndependentControlProperties.class)
public class IndependentControlConfiguration {
    private static void require(boolean condition){if(!condition)throw new IllegalStateException("INDEPENDENT_CONTROLLER_BINDING_REQUIRED");}
    @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor independentManagementRunners(){
        return factory->{
            // The business dictionary initializer locks/writes public tables.
            // Its already provisioned local projection is read-only here.
            String name="provincesCitiesInitializer";
            require(factory.containsBeanDefinition(name)&&"you.v50to.eatwhat.utils.ProvincesCitiesInitializer".equals(factory.getBeanDefinition(name).getBeanClassName()));
            ((org.springframework.beans.factory.support.BeanDefinitionRegistry)factory).removeBeanDefinition(name);
        };
    }
    @Bean IndependentControlAccounts independentControlAccounts(JdbcTemplate jdbc,PasswordEncoder passwords,StringRedisTemplate redis,
            PlatformTransactionManager tx,ObjectProvider<ClusterControlLease> lease){return new IndependentControlAccounts(jdbc,passwords,redis,tx,lease);}
    @Bean IndependentControlWeb independentControlWeb(){return new IndependentControlWeb();}
    @Bean IndependentControlHandoffQueue independentControlHandoffQueue(JdbcTemplate jdbc,IndependentControlAccounts accounts,IndependentControlProperties properties,ObjectProvider<ClusterControlLease> lease,PlatformTransactionManager tx){return new IndependentControlHandoffQueue(jdbc,accounts,properties,lease,tx);}
    public static void admission(Environment env,IndependentControlProperties p){
        require("independent-local".equals(env.getProperty("app.control.database-mode"))
                &&env.getProperty("app.control.enabled",Boolean.class,false)
                &&env.getProperty("app.cluster.enabled",Boolean.class,false)
                &&env.getProperty("app.cluster.coordinator",Boolean.class,false)
                &&env.getProperty("app.cluster.control-lease.enabled",Boolean.class,false)
                &&!env.getProperty("app.cluster.control-lease.automatic-takeover",Boolean.class,true)
                &&!env.getProperty("app.control.shared-state-required",Boolean.class,true)
                &&p.getControllerId()!=null&&p.getInstallationId()!=null
                &&p.getAuthoritySiteId()!=null&&p.getAuthorityInstanceId()!=null
                &&p.getControllerId().toString().equals(env.getProperty("app.cluster.control-lease.controller-id")));
        try{
            URI database=URI.create(env.getRequiredProperty("spring.datasource.url").substring(5));
            require(Set.of("localhost","127.0.0.1","::1","[::1]").contains(database.getHost()));
            require(Set.of("localhost","127.0.0.1","::1").contains(env.getRequiredProperty("spring.data.redis.host")));
            Path state=Path.of(env.getRequiredProperty("app.control.state-directory"));
            require(state.isAbsolute()&&!Files.isSymbolicLink(state)&&Files.isDirectory(state)
                    &&!Set.of("nfs","nfs4").contains(Files.getFileStore(state).type()));
        }catch(Exception invalid){throw new IllegalStateException("INDEPENDENT_CONTROLLER_LOCAL_DEPENDENCIES_REQUIRED");}
    }
    @Bean FlywayMigrationStrategy independentControllerValidation(Environment env,IndependentControlProperties properties,SiteProperties site){
        admission(env,properties);
        return original->{
            // Provisioning has already applied both histories. Startup is validation only.
            // Disable callbacks before Flyway can run a business identity activation.
            var checked=Flyway.configure().configuration(original.getConfiguration()).callbacks(new Callback[0])
                    .skipDefaultCallbacks(true).initSql("").connectRetries(0).cleanDisabled(true)
                    .ignoreMigrationPatterns(new String[0]).load();
            checked.validate();for(var migration:checked.info().all())require(migration.getState()==MigrationState.SUCCESS);
            var management=Flyway.configure().dataSource(original.getConfiguration().getDataSource())
                    .locations("classpath:db/control-independent").schemas("eatwhat_controller")
                    .table("independent_schema_history").cleanDisabled(true).load();
            management.validate();require(management.info().pending().length==0&&management.info().applied().length>0);
            try(var connection=original.getConfiguration().getDataSource().getConnection()){
                validateBinding(connection,properties,site);
            }catch(SQLException failure){throw new IllegalStateException("INDEPENDENT_CONTROLLER_BINDING_REQUIRED");}
        };
    }
    public static void validateBinding(Connection connection,IndependentControlProperties properties,SiteProperties site)throws SQLException{
        try(var query=connection.prepareStatement("""
            SELECT EXISTS(SELECT 1 FROM eatwhat_controller.installation i
              JOIN eatwhat_identity.node_state n ON n.singleton
              JOIN eatwhat_identity.allocation a ON a.singleton
              JOIN eatwhat_cluster.state s ON s.singleton
              WHERE i.singleton AND i.installation_id=? AND i.controller_id=? AND i.cluster_id=?
                AND i.source_site=? AND i.source_instance=? AND n.site_id=i.source_site AND n.instance_id=i.source_instance
                AND i.source_database_fingerprint=md5(n.cluster_identifier||':'||n.database_oid::text)
                AND i.local_database_fingerprint=eatwhat_controller.physical_fingerprint()
                AND a.cluster_id=i.cluster_id AND a.site_id=n.site_id AND a.instance_id=n.instance_id
                AND s.cluster_id=i.cluster_id AND s.coordinator_site=? AND s.coordinator_instance=?)
            """)){
            query.setQueryTimeout(5);query.setObject(1,properties.getInstallationId());query.setObject(2,properties.getControllerId());
            query.setObject(3,site.getClusterId());query.setString(4,site.getId());query.setObject(5,site.getInstanceId());
            query.setString(6,properties.getAuthoritySiteId());query.setObject(7,properties.getAuthorityInstanceId());
            try(var rows=query.executeQuery()){require(rows.next()&&rows.getBoolean(1)&&!rows.next());}
        }
    }
}
