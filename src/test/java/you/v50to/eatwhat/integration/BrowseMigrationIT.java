package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.account.ConsentSchemaMigrationCallback;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.config.identity.GlobalIdentityMigrationCallback;
import you.v50to.eatwhat.config.sync.SyncSchemaMigrationCallback;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BrowseMigrationIT {
    @Test void v57HistorySurvivesAdditiveUpgradeAndLegacyWritesRemainCompatible() {
        try (var pg = IsolatedContainers.startedPostgresWithInspectRetry("browse-upgrade")) {
            var site = new SiteProperties(); site.setMode(SiteProperties.Mode.CAMPUS); site.setId("browse-upgrade"); site.setInstanceId(UUID.randomUUID());
            var db = EventFoundationTestSupport.createDatabaseAtVersion(pg, site, "57");
            long user = db.runtime().queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('upgrade-browser','synthetic-only') RETURNING id", Long.class);
            db.runtime().update("INSERT INTO browse_history(account_id,target_type,target_id,created_at) VALUES(?,'food',123,'2026-01-01T01:02:03.123456Z')", user);
            var prior = db.runtime().queryForMap("SELECT id,account_id,target_type,target_id,created_at FROM browse_history WHERE account_id=?", user);
            var flyway = Flyway.configure().dataSource(db.url(), IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                    .locations("classpath:db/migration").validateOnMigrate(true).cleanDisabled(true)
                    .callbacks(new GlobalIdentityMigrationCallback(site, db.runtimeUsername()), new SyncSchemaMigrationCallback(site, db.runtimeUsername()),
                            new ConsentSchemaMigrationCallback(site, db.runtimeUsername())).load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals(prior, db.runtime().queryForMap("SELECT id,account_id,target_type,target_id,created_at FROM browse_history WHERE account_id=?", user));
            db.runtime().update("INSERT INTO browse_history(account_id,target_type,target_id) VALUES(?,'restaurant',456)", user);
            assertEquals(2, db.runtime().queryForObject("SELECT count(*) FROM browse_history WHERE account_id=?", Long.class, user));
            assertTrue(db.runtime().queryForObject("SELECT indisvalid AND indisunique FROM pg_index WHERE indexrelid='eatwhat_sync.browse_visit_pkey'::regclass", Boolean.class));
            assertTrue(flyway.validateWithResult().validationSuccessful); assertEquals(0, flyway.migrate().migrationsExecuted);
        }
    }
}
