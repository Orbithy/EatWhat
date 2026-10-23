package you.v50to.eatwhat.control.bootstrap;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.callback.Callback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.AbstractDataSource;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.config.identity.GlobalIdentityMigrationCallback;

import java.sql.*;
import java.util.*;

/** Existing shared authority: validate complete history/identity, never migrate,
 * baseline, repair, grant privileges or invoke AFTER_MIGRATE callbacks. Uses
 * dedicated physical readonly sessions, never changes the runtime Hikari pool.
 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.control.database-mode",havingValue="existing-authority")
public class ControllerExistingAuthoritySchema {
    private static void require(boolean value){if(!value)throw new IllegalStateException("EXISTING_AUTHORITY_SCHEMA_REQUIRED");}
    public static void admission(Environment env,SiteProperties site){
        for(String name:List.of("app.control.enabled","app.control.node-enabled","app.control.shared-state-required",
                "app.cluster.enabled","app.cluster.coordinator","spring.flyway.enabled"))
            require(Boolean.TRUE.equals(env.getProperty(name,Boolean.class,false)));
        require("managed-deployment".equals(env.getProperty("app.control.environment-kind"))
                &&!env.getProperty("app.cluster.single-site-only",Boolean.class,true)
                &&site.getMode()==SiteProperties.Mode.CAMPUS&&Integer.valueOf(0).equals(site.getAllocationSlot())
                &&site.getClusterId()!=null&&site.getInstanceId()!=null&&site.getId()!=null
                &&!site.getClusterId().equals(new UUID(0,0))&&!site.getInstanceId().equals(new UUID(0,0)));
        require(Objects.toString(env.getProperty("app.control.authority-database-fingerprint"),"").matches("[a-f0-9]{32}"));
        require(env.getProperty("spring.flyway.url")==null&&env.getProperty("spring.flyway.user")==null
                &&env.getProperty("spring.flyway.password")==null);
        require(Objects.toString(env.getProperty("spring.datasource.url"),"").startsWith("jdbc:postgresql://")
                &&!Objects.toString(env.getProperty("spring.datasource.username"),"").isBlank()
                &&!Objects.toString(env.getProperty("spring.datasource.password"),"").isEmpty());
    }
    @Bean public FlywayMigrationStrategy existingAuthorityValidation(Environment env,SiteProperties site){
        admission(env,site);return flyway->validateExisting(flyway,env,site);
    }
    /** Own unpooled connections: a session-level readonly GUC cannot leak into
     * the application's pooled write connections, even if Flyway toggles JDBC
     * autocommit while validating. Credentials never enter an error message.
     */
    public static AbstractDataSource readonlyDataSource(Environment env){
        return new AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException {
                Connection connection=DriverManager.getConnection(env.getRequiredProperty("spring.datasource.url"),
                        env.getRequiredProperty("spring.datasource.username"),env.getRequiredProperty("spring.datasource.password"));
                try {
                    try(var statement=connection.createStatement()){
                        statement.setQueryTimeout(5);statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
                        statement.execute("SET statement_timeout='10s'");statement.execute("SET lock_timeout='5s'");
                    }
                    connection.setReadOnly(true);return connection;
                } catch(Exception failure){connection.close();throw new SQLException("EXISTING_AUTHORITY_CONNECTION_UNAVAILABLE");}
            }
            @Override public Connection getConnection(String user,String password)throws SQLException {
                throw new SQLException("EXISTING_AUTHORITY_ALTERNATE_CREDENTIALS_FORBIDDEN");
            }
        };
    }
    public static void validateExisting(Flyway original,Environment env,SiteProperties site){
        admission(env,site);
        try {
            var datasource=readonlyDataSource(env);
            var checked=Flyway.configure().configuration(original.getConfiguration()).dataSource(datasource)
                    .callbacks(new Callback[0]).skipDefaultCallbacks(true).initSql("").connectRetries(0)
                    .ignoreMigrationPatterns(new String[0]).cleanDisabled(true).load();
            checked.validate();
            var migrations=checked.info().all();require(migrations.length>0);
            for(var migration:migrations)require(migration.getState()==MigrationState.SUCCESS);
            try(var connection=datasource.getConnection()){
                new GlobalIdentityMigrationCallback(site,env.getProperty("spring.datasource.username")).verifyRuntimeRole(connection);
                try(var query=connection.prepareStatement("""
                    SELECT EXISTS(
                      SELECT 1 FROM eatwhat_identity.node_state n
                      JOIN eatwhat_identity.allocation a ON a.singleton
                      JOIN eatwhat_cluster.state s ON s.singleton
                      JOIN eatwhat_cluster.member m ON m.site_id=n.site_id AND m.instance_id=n.instance_id
                      WHERE n.singleton AND n.site_id=? AND n.instance_id=? AND n.mode='CAMPUS'
                        AND n.database_name=current_database()
                        AND n.database_oid=(SELECT oid::bigint FROM pg_database WHERE datname=current_database())
                        AND md5(n.cluster_identifier||':'||n.database_oid::text)=?
                        AND a.cluster_id=? AND a.site_id=n.site_id AND a.instance_id=n.instance_id AND a.slot=0
                        AND s.cluster_id=a.cluster_id AND s.coordinator_site=n.site_id AND s.coordinator_instance=n.instance_id AND s.epoch>0
                        AND m.mode='CAMPUS' AND m.allocation_slot=0 AND m.state='ACTIVE'
                        AND m.database_fingerprint=md5(n.cluster_identifier||':'||n.database_oid::text)
                    ) AND current_user=?
                    """)){
                    query.setQueryTimeout(5);query.setString(1,site.getId());query.setObject(2,site.getInstanceId());
                    query.setString(3,env.getProperty("app.control.authority-database-fingerprint"));query.setObject(4,site.getClusterId());
                    query.setString(5,env.getProperty("spring.datasource.username"));
                    try(var rows=query.executeQuery()){require(rows.next()&&rows.getBoolean(1)&&!rows.next());}
                }
            }
        }catch(Exception failure){
            // Underlying JDBC/Flyway messages may include a URL or credentials.
            throw new IllegalStateException("EXISTING_AUTHORITY_SCHEMA_REQUIRED");
        }
    }
}
