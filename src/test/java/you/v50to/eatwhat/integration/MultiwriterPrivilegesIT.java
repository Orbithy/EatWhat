package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import static org.junit.jupiter.api.Assertions.*;

class MultiwriterPrivilegesIT {
    private static GenericContainer<?> postgres;
    @BeforeAll static void start() { postgres = IsolatedContainers.startedPostgresWithInspectRetry("multiwriter-acl"); }
    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }

    @Test void runtimeCannotActivateMultiwriterOrRewriteActorEvidence() {
        var db = EventFoundationTestSupport.createDatabase(postgres);
        for (String privilege : new String[]{"INSERT","UPDATE","DELETE","TRUNCATE"}) {
            assertFalse(db.runtime().queryForObject(
                    "SELECT has_table_privilege(current_user,'eatwhat_sync.catalog_multiwriter_config',?)",
                    Boolean.class, privilege), "Runtime must not enable multiwriter using " + privilege);
        }
        assertTrue(db.runtime().queryForObject(
                "SELECT has_table_privilege(current_user,'eatwhat_sync.catalog_multiwriter_config','SELECT')", Boolean.class));
        assertFalse(db.runtime().queryForObject(
                "SELECT has_column_privilege(current_user,'eatwhat_sync.catalog_actor_evidence','evidence','UPDATE')", Boolean.class));
        assertTrue(db.runtime().queryForObject(
                "SELECT has_column_privilege(current_user,'eatwhat_sync.catalog_projection_state','status','UPDATE')", Boolean.class));
        assertFalse(db.runtime().queryForObject(
                "SELECT has_column_privilege(current_user,'eatwhat_sync.catalog_projection_state','object_gid','UPDATE')", Boolean.class));
        assertTrue(db.runtime().queryForObject(
                "SELECT has_column_privilege(current_user,'eatwhat_sync.activity_intent','published_revision','UPDATE')", Boolean.class));
        assertFalse(db.runtime().queryForObject(
                "SELECT has_table_privilege(current_user,'eatwhat_sync.catalog_decision_projection_context','SELECT,INSERT,UPDATE,DELETE,TRUNCATE')", Boolean.class));
        assertFalse(db.runtime().queryForObject(
                "SELECT has_function_privilege(current_user,'eatwhat_sync.catalog_decision_proven(uuid)','EXECUTE')", Boolean.class));
        assertTrue(db.runtime().queryForObject(
                "SELECT has_function_privilege(current_user,'eatwhat_sync.apply_catalog_decision_projection(uuid)','EXECUTE')", Boolean.class));
        assertFalse(db.runtime().queryForObject(
                "SELECT has_table_privilege(current_user,'eatwhat_sync.historical_domain_source_claim','INSERT,UPDATE,DELETE,TRUNCATE')", Boolean.class));
        assertFalse(db.runtime().queryForObject(
                "SELECT has_function_privilege(current_user,'eatwhat_sync.claim_historical_domain_source(text,bigint,uuid,text,text)','EXECUTE')", Boolean.class));
    }

    @Test void erasureContextsAndHelpersRemainOwnerOnly() {
        var db = EventFoundationTestSupport.createDatabase(postgres);
        for (String table : new String[]{"scoped_erasure_context", "catalog_erased_operation"})
            assertFalse(db.runtime().queryForObject("SELECT has_table_privilege(current_user,?,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE')",
                    Boolean.class, "eatwhat_sync."+table));
        assertFalse(db.runtime().queryForObject("SELECT has_table_privilege(current_user,'eatwhat_sync.purged_personal_event','INSERT,UPDATE,DELETE,TRUNCATE')", Boolean.class));
        for (String fn : new String[]{"erase_scoped_rows(text,jsonb)", "check_erased_catalog_identity()",
                "prepare_catalog_withdrawal_cleanup_v1(uuid,bigint)", "personal_event_subjects(jsonb)",
                "erase_withdrawn_business_payload(uuid)"})
            assertFalse(db.runtime().queryForObject("SELECT has_function_privilege(current_user,?,'EXECUTE')", Boolean.class, "eatwhat_sync."+fn));
        assertTrue(db.runtime().queryForObject("SELECT has_function_privilege(current_user,'eatwhat_sync.personal_event_retention_blocked(jsonb)','EXECUTE')", Boolean.class));
    }

    @Test void oldMigrationTargetDoesNotRequireNewOptionalTables() {
        var site = new you.v50to.eatwhat.config.SiteProperties();
        site.setMode(you.v50to.eatwhat.config.SiteProperties.Mode.CAMPUS);
        site.setId("campus-acl-old"); site.setInstanceId(java.util.UUID.randomUUID());
        var db = EventFoundationTestSupport.createDatabaseAtVersion(postgres,site,"60");
        assertNull(db.runtime().queryForObject("SELECT to_regclass('eatwhat_sync.catalog_multiwriter_config')",String.class));
        assertTrue(db.runtime().queryForObject(
                "SELECT has_column_privilege(current_user,'eatwhat_sync.delivery','status','UPDATE')",Boolean.class));
    }
    @Test void deliveryAcceptsAllInstalledDomainsAndKeepsABoundedFilter() {
        var db=EventFoundationTestSupport.createDatabase(postgres);
        var delivery=new you.v50to.eatwhat.sync.DeliveryService(db.runtime(),db.transactions(),new you.v50to.eatwhat.sync.EventCodec());
        var all=new java.util.HashSet<>(you.v50to.eatwhat.sync.transport.EventSchemas.legacy().keySet());
        all.addAll(java.util.Set.of("activity_state","notification.history","catalog.decision","catalog.import","file.gc"));
        assertEquals(18,all.size());
        assertTrue(delivery.claimForAggregates("external",100,java.time.Duration.ofSeconds(10),all).isEmpty());
        var excessive=new java.util.HashSet<String>();for(int n=0;n<65;n++)excessive.add("test."+n);
        assertThrows(IllegalArgumentException.class,()->delivery.claimForAggregates("external",100,java.time.Duration.ofSeconds(10),excessive));
    }

}
