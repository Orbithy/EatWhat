package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.SiteProperties;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FileAckErasureMigrationIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("file-ack-erasure");
    static final String HASH="1".repeat(64);
    @BeforeAll static void start(){PG.start();}
    @AfterAll static void stop(){PG.stop();}
    EventFoundationTestSupport.Database database(){
        var site=new SiteProperties();site.setMode(SiteProperties.Mode.EXTERNAL);
        site.setId("file-ack-erasure");site.setInstanceId(UUID.randomUUID());
        var db=EventFoundationTestSupport.createDatabaseAtVersion(PG,site,"74");
        db.admin().execute("GRANT USAGE ON SCHEMA eatwhat_file TO "+db.runtimeUsername());
        db.admin().execute("GRANT SELECT,INSERT ON eatwhat_file.file_sync_ack TO "+db.runtimeUsername());
        return db;
    }
    Flyway upgrade(EventFoundationTestSupport.Database db){
        String directory=System.getProperty("eatwhat.ack-migration-directory");
        String[] locations=directory==null?new String[]{"classpath:db/migration"}:
                new String[]{"classpath:db/migration","filesystem:"+directory};
        return Flyway.configure().dataSource(db.url(),IsolatedContainers.USER,IsolatedContainers.PASSWORD)
                .locations(locations)
                .target("74.1").validateOnMigrate(true).cleanDisabled(true).load();
    }
    UUID event(EventFoundationTestSupport.Database db,UUID subject){
        UUID event=UUID.randomUUID();
        db.admin().update("""
            INSERT INTO eatwhat_sync.outbox(event_id,origin_site,origin_instance_id,aggregate_type,aggregate_id,numeric_id,envelope,envelope_hash)
            VALUES(?,'source',?,'file.replica',?,0,jsonb_build_object('aggregateType','file.replica',
              'payloadJson',jsonb_build_object('ownerGid',?::text,'epoch',1)),?)
            """,event,UUID.randomUUID(),event,subject.toString(),HASH);
        return event;
    }
    void ack(EventFoundationTestSupport.Database db,UUID event,String hash){
        db.runtime().update("INSERT INTO eatwhat_file.file_sync_ack(event_id,receipt_event_id,envelope_hash,outcome)VALUES(?,?,?,'APPLIED')",event,UUID.randomUUID(),hash);
    }
    @Test void realWithdrawalErasesEnvelopeAndRetainsImmutableAck(){
        var db=database();UUID subject=UUID.randomUUID();
        db.admin().update("INSERT INTO eatwhat_consent.subject(subject_id,user_id,origin_site,epoch,status,policy_version,backfill_status)VALUES(?,1000000001,'source',2,'WITHDRAWN','synthetic','WITHDRAWN')",subject);
        UUID event=event(db,subject);ack(db,event,HASH);
        assertThrows(DataAccessException.class,()->db.admin().queryForObject("SELECT eatwhat_sync.erase_withdrawn_business_payload(?)",Boolean.class,subject));
        var migration=upgrade(db);assertEquals(1,migration.migrate().migrationsExecuted);
        assertTrue(migration.validateWithResult().validationSuccessful);assertEquals(0,migration.migrate().migrationsExecuted);
        assertTrue(db.admin().queryForObject("SELECT eatwhat_sync.erase_withdrawn_business_payload(?)",Boolean.class,subject));
        assertEquals(0L,db.admin().queryForObject("SELECT count(*)FROM eatwhat_sync.outbox WHERE event_id=?",Long.class,event));
        assertEquals(HASH,db.admin().queryForObject("SELECT envelope_hash FROM eatwhat_sync.purged_personal_event WHERE event_id=?",String.class,event));
        assertEquals(HASH,db.admin().queryForObject("SELECT envelope_hash FROM eatwhat_file.file_sync_ack WHERE event_id=?",String.class,event));
        assertThrows(DataAccessException.class,()->db.admin().update("DELETE FROM eatwhat_file.file_sync_ack WHERE event_id=?",event));
        assertThrows(DataAccessException.class,()->db.admin().update("UPDATE eatwhat_file.file_sync_ack SET outcome='IGNORED' WHERE event_id=?",event));
        // An already running V74 package still validates its classpath migrations after this additive patch.
        assertTrue(Flyway.configure().dataSource(db.url(),IsolatedContainers.USER,IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").load().validateWithResult().validationSuccessful);
    }
    @Test void unknownOrMismatchedSourcesCannotCreateAcknowledgements(){
        var db=database();upgrade(db).migrate();
        assertThrows(DataAccessException.class,()->ack(db,UUID.randomUUID(),HASH));
        UUID event=event(db,UUID.randomUUID());
        assertThrows(DataAccessException.class,()->ack(db,event,"2".repeat(64)));
        ack(db,event,HASH);
        assertThrows(DataAccessException.class,()->db.runtime().update("INSERT INTO eatwhat_sync.purged_personal_event VALUES(?,?)",UUID.randomUUID(),HASH));
        UUID erased=UUID.randomUUID();db.admin().update("INSERT INTO eatwhat_sync.purged_personal_event VALUES(?,?)",erased,HASH);
        ack(db,erased,HASH);
    }
    @Test void sourceCannotDisappearWithoutMatchingErasureReceipt(){
        var db=database();upgrade(db).migrate();UUID event=event(db,UUID.randomUUID());ack(db,event,HASH);
        assertThrows(DataAccessException.class,()->db.admin().queryForObject("SELECT eatwhat_sync.erase_scoped_rows('outbox',(SELECT jsonb_agg(to_jsonb(o))FROM eatwhat_sync.outbox o WHERE event_id=?))",Long.class,event));
        assertEquals(1L,db.admin().queryForObject("SELECT count(*)FROM eatwhat_sync.outbox WHERE event_id=?",Long.class,event));
    }
}
