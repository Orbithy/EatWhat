package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.config.identity.GlobalIdentityMigrationCallback;
import you.v50to.eatwhat.config.sync.SyncSchemaMigrationCallback;
import you.v50to.eatwhat.account.ConsentSchemaMigrationCallback;
import you.v50to.eatwhat.sync.SyncTransactions;

import java.util.UUID;

/** Each fixture has its own migrated database and a genuine non-owner application role. */
final class EventFoundationTestSupport {
    private EventFoundationTestSupport() { }

    static Database createDatabase(GenericContainer<?> postgres) {
        SiteProperties site = new SiteProperties();
        site.setMode(SiteProperties.Mode.CAMPUS);
        site.setId("campus-test");
        site.setInstanceId(UUID.randomUUID());
        return createDatabase(postgres, site);
    }

    static Database createDatabase(GenericContainer<?> postgres, SiteProperties site) {
        return createDatabaseAtVersion(postgres,site,null);
    }

    static Database createDatabaseAtVersion(GenericContainer<?> postgres, SiteProperties site,String version) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String name = "events_" + suffix;
        String runtimeUsername = "events_app_" + suffix;
        JdbcTemplate server = admin(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
        server.execute("CREATE DATABASE " + name + " TEMPLATE template0");
        String url = IsolatedContainers.jdbcUrl(postgres, name);
        JdbcTemplate admin = admin(url);
        admin.execute("CREATE ROLE " + runtimeUsername
                + " LOGIN PASSWORD 'synthetic-test-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT");
        admin.execute("GRANT USAGE ON SCHEMA public TO " + runtimeUsername);
        admin.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO " + runtimeUsername);
        admin.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO " + runtimeUsername);
        var migration=Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).validateOnMigrate(true).cleanDisabled(true)
                .callbacks(new GlobalIdentityMigrationCallback(site, runtimeUsername),
                        new SyncSchemaMigrationCallback(site, runtimeUsername),
                        new ConsentSchemaMigrationCallback(site, runtimeUsername));
        if(version!=null)migration.target(version);
        migration.load().migrate();
        // Synthetic business state belongs only to these tests; no real controller captures it.
        admin.execute("""
                CREATE TABLE public.business_counter (
                    gid uuid PRIMARY KEY,
                    numeric_id bigint NOT NULL,
                    value bigint NOT NULL DEFAULT 0
                )
                """);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, runtimeUsername, IsolatedContainers.PASSWORD);
        JdbcTemplate runtime = new JdbcTemplate(dataSource);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        return new Database(name, url, admin, runtime, dataSource, manager,
                new SyncTransactions(runtime, manager), site, runtimeUsername);
    }

    private static JdbcTemplate admin(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
    }

    record Database(String name, String url, JdbcTemplate admin, JdbcTemplate runtime,
                    DriverManagerDataSource dataSource, DataSourceTransactionManager transactionManager,
                    SyncTransactions transactions, SiteProperties site, String runtimeUsername) { }
}
