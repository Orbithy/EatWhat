package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.account.ConsentSchemaMigrationCallback;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.config.identity.GlobalIdentityMigrationCallback;
import you.v50to.eatwhat.config.sync.SyncSchemaMigrationCallback;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.catalog.*;

import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class P1MigrationIT {
    static GenericContainer<?> pg;
    @BeforeAll static void start() { pg=IsolatedContainers.startedPostgresWithInspectRetry("p1-upgrade"); }
    @AfterAll static void stop() { if(pg!=null)pg.stop(); }
    EventFoundationTestSupport.Database oldDatabase() {
        var site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId("p1-upgrade");site.setInstanceId(UUID.randomUUID());
        return EventFoundationTestSupport.createDatabaseAtVersion(pg,site,System.getProperty("eatwhat.p1.baseline","76"));
    }
    Flyway upgrade(EventFoundationTestSupport.Database db) {
        return Flyway.configure().dataSource(db.url(),IsolatedContainers.USER,IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").validateOnMigrate(true).cleanDisabled(true)
                .callbacks(new GlobalIdentityMigrationCallback(db.site(),db.runtimeUsername()),
                        new SyncSchemaMigrationCallback(db.site(),db.runtimeUsername()),
                        new ConsentSchemaMigrationCallback(db.site(),db.runtimeUsername())).load();
    }
    long[] seed(EventFoundationTestSupport.Database db) {
        long actor=db.runtime().queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('p1-author','synthetic') RETURNING id",Long.class);
        long restaurant=db.runtime().queryForObject("INSERT INTO restaurant(account_id,name,location,gcj_lng,gcj_lat) VALUES(?,'P1 store',ST_SetSRID(ST_MakePoint(117,36),4326)::geography,117,36) RETURNING id",Long.class,actor);
        return new long[]{actor,restaurant};
    }
    @Test void upgradePreservesRowsRekeysExistingCandidatesAndKeepsLegacyInsertCompatible() {
        var db=oldDatabase();var seed=seed(db);
        long dish=db.runtime().queryForObject("INSERT INTO foods(account_id,restaurant_id,name) VALUES(?,?,'鲜虾 云吞') RETURNING id",Long.class,seed[0],seed[1]);
        UUID parent=UUID.randomUUID(),object=UUID.randomUUID(),candidate=UUID.randomUUID();
        String raw="{\"name\":\"鲜虾 云吞\",\"restaurantGid\":\""+parent+"\"}";
        String oldHash=EventCodec.sha256(raw);
        db.admin().update("INSERT INTO eatwhat_sync.catalog_natural_key(kind,key_hash,canonical_key) VALUES('FOOD',?,?::jsonb)",oldHash,raw);
        db.admin().update("INSERT INTO eatwhat_sync.catalog_candidate(candidate_id,kind,key_hash,object_gid,numeric_id,source_site,revision,content_hash,summary,status) VALUES(?,'FOOD',?,?,?,'p1-upgrade',1,?,'{}','PENDING')",candidate,oldHash,object,dish,"0".repeat(64));
        var before=db.runtime().queryForMap("SELECT id,name,created_at FROM foods WHERE id=?",dish);
        var migration=upgrade(db);assertEquals(2,migration.migrate().migrationsExecuted);
        assertEquals(before,db.runtime().queryForMap("SELECT id,name,created_at FROM foods WHERE id=?",dish));
        var expected=CatalogPayload.naturalKey(CatalogKind.FOOD,Map.of("name","鲜虾云吞"),null,parent.toString());
        assertEquals(expected.hash(),db.runtime().queryForObject("SELECT key_hash FROM eatwhat_sync.catalog_candidate WHERE candidate_id=?",String.class,candidate).trim());
        assertEquals(expected.canonical(),EventCodec.canonicalJson(db.runtime().queryForObject("SELECT canonical_key::text FROM eatwhat_sync.catalog_natural_key WHERE kind='FOOD' AND key_hash=?",String.class,expected.hash())));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->db.runtime().update("INSERT INTO foods(account_id,restaurant_id,name) VALUES(?,?,'鲜虾　云吞')",seed[0],seed[1]));
        db.runtime().update("INSERT INTO browse_history(account_id,target_type,target_id) VALUES(?,'food',?)",seed[0],dish);
        assertEquals("{}",db.runtime().queryForObject("SELECT sync_clear_context::text FROM browse_history WHERE account_id=?",String.class,seed[0]));
        db.transactions().required(()->{
            var store=new you.v50to.eatwhat.sync.browse.BrowseClearStore(db.runtime());
            store.merge(seed[0],"food",Map.of("p1-upgrade",1L));return null;
        });
        assertEquals(0L,db.runtime().queryForObject("SELECT count(*) FROM browse_history WHERE account_id=?",Long.class,seed[0]));
        assertTrue(migration.validateWithResult().validationSuccessful);assertEquals(0,migration.migrate().migrationsExecuted);
    }
    @Test void duplicateExistingDataBlocksUpgradeAtomicallyUntilExplicitlyReconciled() {
        var db=oldDatabase();var seed=seed(db);
        db.runtime().update("INSERT INTO foods(account_id,restaurant_id,name) VALUES(?,?,'米 饭'),(?,?,'米饭')",seed[0],seed[1],seed[0],seed[1]);
        var migration=upgrade(db);
        assertThrows(org.flywaydb.core.api.FlywayException.class,migration::migrate);
        assertEquals(System.getProperty("eatwhat.p1.baseline","76"),db.admin().queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",String.class));
        assertEquals(2L,db.runtime().queryForObject("SELECT count(*) FROM foods WHERE restaurant_id=?",Long.class,seed[1]));
        db.runtime().update("DELETE FROM foods WHERE name='米 饭'");
        assertEquals(2,migration.migrate().migrationsExecuted);
    }
}
