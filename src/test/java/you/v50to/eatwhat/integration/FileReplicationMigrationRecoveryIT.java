package you.v50to.eatwhat.integration;

import org.apache.commons.lang3.tuple.Pair;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** V28 must roll back atomically and retry without changing the existing V27 schema or rows. */
class FileReplicationMigrationRecoveryIT {
    private static final List<String> V28_TABLES = List.of(
            "file_publication", "file_replica_alert");

    @Test
    void interruptedV28RollsBackAndRetryPreservesV27BusinessRowsAndChecksums() {
        try (var postgres = IsolatedContainers.postgres("file-v28-recovery")) {
            postgres.start();
            String database = "file_v28_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            Flyway v27 = migration(url, "27");
            assertEquals(26, v27.migrate().migrationsExecuted,
                    "V27 includes 26 migrations because V9 is intentionally absent");
            long user = admin.queryForObject("""
                    INSERT INTO public.users(nick_name,password_hash) VALUES('file-v28-user','synthetic') RETURNING id
                    """, Long.class);
            var userBefore = admin.queryForList("SELECT * FROM public.users WHERE id=?", user);
            var v27Checksums = checksums(admin);

            // Insert test data into V27 tables
            UUID fileId = UUID.randomUUID();
            String sha256Hex = "a".repeat(64);
            admin.update("""
                    INSERT INTO eatwhat_file.file_object(file_id,owner_gid,biz,logical_key,canonical_revision,created_at)
                    VALUES (?,'550e8400-e29b-41d4-a716-446655440000','avatar','test/file-v28-recovery',1,now())
                    """, fileId);
            admin.update("""
                    INSERT INTO eatwhat_file.file_revision(file_id,revision,final_key,sha256,size_bytes,content_type,state,created_at)
                    VALUES (?,?,'test/file-v28-recovery-final',?,1024,'image/png','VERIFIED',now())
                    """, fileId, 1, sha256Hex);
            admin.update("""
                    INSERT INTO eatwhat_file.file_replica(file_id,revision,site,object_key,state,updated_at)
                    VALUES (?,?,'LOCAL','test/file-v28-recovery-object','AVAILABLE_LOCAL',now())
                    """, fileId, 1);
            var v27RowsBefore = admin.queryForObject("""
                    SELECT count(*) FROM eatwhat_file.file_object
                    """, Integer.class);

            admin.execute("""
                    CREATE FUNCTION public.fail_v28_file_migration_for_test() RETURNS event_trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                      IF EXISTS (SELECT 1 FROM pg_event_trigger_ddl_commands()
                                 WHERE object_identity='eatwhat_file.file_publication' AND command_tag='CREATE TABLE') THEN
                        RAISE EXCEPTION 'Synthetic interrupted V28 migration' USING ERRCODE='P0001';
                      END IF;
                    END
                    $$
                    """);
            admin.execute("CREATE EVENT TRIGGER fail_v28_file_migration_for_test ON ddl_command_end "
                    + "EXECUTE FUNCTION public.fail_v28_file_migration_for_test()");

            Flyway v28 = migration(url, "28");
            assertThrows(FlywayException.class, v28::migrate);
            for (String table : V28_TABLES) {
                assertFalse(tableExists(admin, table), "Failed V28 leaves no table: " + table);
            }
            assertEquals(26, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            assertEquals(v27Checksums, checksums(admin), "Failed V28 cannot rewrite earlier migration checksums");
            assertEquals(userBefore, admin.queryForList("SELECT * FROM public.users WHERE id=?", user));
            assertEquals(v27RowsBefore, admin.queryForObject("""
                    SELECT count(*) FROM eatwhat_file.file_object
                    """, Integer.class), "V27 data must be preserved after failed V28");

            admin.execute("DROP EVENT TRIGGER fail_v28_file_migration_for_test");
            admin.execute("DROP FUNCTION public.fail_v28_file_migration_for_test()");
            assertEquals(1, v28.migrate().migrationsExecuted, "Retry applies only V28");
            assertEquals(0, v28.migrate().migrationsExecuted, "A second migration is idempotent");
            v28.validate();
            assertEquals(27, admin.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
            for (String table : V28_TABLES) assertTrue(tableExists(admin, table), "V28 table exists: " + table);
            assertEquals(v27Checksums, checksumsExcludingV28(admin), "All V1-V27 checksums remain unchanged");
            assertEquals(userBefore, admin.queryForList("SELECT * FROM public.users WHERE id=?", user));
            assertEquals(v27RowsBefore, admin.queryForObject("""
                    SELECT count(*) FROM eatwhat_file.file_object
                    """, Integer.class), "V27 data must survive successful V28");
        }
    }

    @Test
    void v27DataUpgradesSuccessfullyToV28WithDefaultNewColumnsAndExistingRowsPreserved() {
        try (var postgres = IsolatedContainers.postgres("file-v28-upgrade")) {
            postgres.start();
            String database = "file_v28_upgrade_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            // Migrate to V27
            Flyway v27 = migration(url, "27");
            assertEquals(26, v27.migrate().migrationsExecuted);

            // Insert V27 data
            UUID fileId = UUID.randomUUID();
            String sha256Hex = "c".repeat(64);
            admin.update("""
                    INSERT INTO eatwhat_file.file_object(file_id,owner_gid,biz,logical_key,canonical_revision,created_at)
                    VALUES (?,'550e8400-e29b-41d4-a716-446655440000','avatar','v27-file',1,now())
                    """, fileId);
            admin.update("""
                    INSERT INTO eatwhat_file.file_revision(file_id,revision,final_key,sha256,size_bytes,content_type,state,created_at)
                    VALUES (?,?,'v27-final',?,1024,'image/png','VERIFIED',now())
                    """, fileId, 1, sha256Hex);
            admin.update("""
                    INSERT INTO eatwhat_file.file_replica(file_id,revision,site,object_key,state,updated_at)
                    VALUES (?,?,'LOCAL','v27-object','AVAILABLE_LOCAL',now())
                    """, fileId, 1);
            UUID jobId = UUID.randomUUID();
            admin.update("""
                    INSERT INTO eatwhat_file.file_transfer_job(job_id,file_id,revision,target_site,status,attempts,next_attempt_at,created_at)
                    VALUES (?,?,?,'R2','READY',0,now(),now())
                    """, jobId, fileId, 1);

            // Upgrade to V28
            Flyway v28 = migration(url, "28");
            assertEquals(1, v28.migrate().migrationsExecuted);

            // Verify V27 data intact and new columns have correct defaults
            var replicaRow = admin.queryForMap("""
                    SELECT verified_sha256, verified_size, verified_at, site, state
                    FROM eatwhat_file.file_replica WHERE file_id=? AND revision=?
                    """, fileId, 1);
            assertNull(replicaRow.get("verified_sha256"), "verified_sha256 must default to NULL");
            assertNull(replicaRow.get("verified_size"), "verified_size must default to NULL");
            assertNull(replicaRow.get("verified_at"), "verified_at must default to NULL");
            assertEquals("LOCAL", replicaRow.get("site"), "site value unchanged");
            assertEquals("AVAILABLE_LOCAL", replicaRow.get("state"), "state value unchanged");

            var jobRow = admin.queryForMap("""
                    SELECT kind, fencing_token, last_error, consent_epoch, updated_at, completed_at, status
                    FROM eatwhat_file.file_transfer_job WHERE job_id=?
                    """, jobId);
            assertEquals("COPY_TO_PEER", jobRow.get("kind"), "legacy R2 row keeps default kind COPY_TO_PEER");
            assertEquals(0L, jobRow.get("fencing_token"), "fencing_token must default to 0");
            assertEquals("LEGACY_ROW", jobRow.get("last_error"), "last_error must be LEGACY_ROW for R2 target_site");
            assertEquals("QUARANTINED", jobRow.get("status"), "status must be QUARANTINED for legacy R2 row");
            assertNull(jobRow.get("consent_epoch"), "consent_epoch must default to NULL");
            assertNotNull(jobRow.get("updated_at"), "updated_at must have a value");
            assertNull(jobRow.get("completed_at"), "completed_at must default to NULL");
        }
    }

    @Test
    void v28NewColumnsAreUpdatableButIdentityColumnsAndCheckValuesAreEnforced() {
        try (var postgres = IsolatedContainers.postgres("file-v28-constraints")) {
            postgres.start();
            String database = "file_v28_constraints_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            Flyway flyway = migration(url, "28");
            assertEquals(27, flyway.migrate().migrationsExecuted);

            // Setup test data
            UUID fileId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            String sha256Hex = "d".repeat(64);
            admin.update("""
                    INSERT INTO eatwhat_file.file_object(file_id,owner_gid,biz,logical_key,canonical_revision,created_at)
                    VALUES (?,'550e8400-e29b-41d4-a716-446655440000','avatar','test-constraints-file',1,now())
                    """, fileId);
            admin.update("""
                    INSERT INTO eatwhat_file.file_revision(file_id,revision,final_key,sha256,size_bytes,content_type,state,created_at)
                    VALUES (?,?,'test-constraints-final',?,2048,'image/png','VERIFIED',now())
                    """, fileId, 1, sha256Hex);
            admin.update("""
                    INSERT INTO eatwhat_file.file_replica(file_id,revision,site,object_key,state,updated_at)
                    VALUES (?,?,'LOCAL','test-constraints-object','AVAILABLE_LOCAL',now())
                    """, fileId, 1);
            admin.update("""
                    INSERT INTO eatwhat_file.file_transfer_job(job_id,file_id,revision,target_site,status,attempts,next_attempt_at,created_at)
                    VALUES (?,?,?,'PEER','READY',0,now(),now())
                    """, jobId, fileId, 1);

            // Test 1: New columns are updatable
            admin.update("""
                    UPDATE eatwhat_file.file_replica SET verified_sha256=?, verified_size=?, verified_at=now()
                    WHERE file_id=? AND revision=? AND site='LOCAL'
                    """, sha256Hex, 2048L, fileId, 1);
            var replicaAfterUpdate = admin.queryForObject("""
                    SELECT verified_size FROM eatwhat_file.file_replica WHERE file_id=? AND revision=?
                    """, (rs, row) -> rs.getLong("verified_size"), fileId, 1);
            assertEquals(2048L, replicaAfterUpdate, "verified_size update successful");

            admin.update("""
                    UPDATE eatwhat_file.file_transfer_job SET fencing_token=?, last_error=?
                    WHERE job_id=?
                    """, 10L, "test error", jobId);
            var jobAfterUpdate = admin.queryForObject("""
                    SELECT fencing_token, last_error FROM eatwhat_file.file_transfer_job WHERE job_id=?
                    """, (rs, row) -> Pair.of(rs.getLong("fencing_token"), rs.getString("last_error")), jobId);
            assertEquals(10L, jobAfterUpdate.getLeft(), "fencing_token update successful");
            assertEquals("test error", jobAfterUpdate.getRight(), "last_error update successful");

            // Test 2: Identity columns reject updates
            assertThrows(RuntimeException.class,
                    () -> admin.update("""
                            UPDATE eatwhat_file.file_replica SET site='PEER' WHERE file_id=? AND revision=?
                            """, fileId, 1),
                    "file_replica identity column site must reject UPDATE");
            assertThrows(RuntimeException.class,
                    () -> admin.update("""
                            UPDATE eatwhat_file.file_replica SET object_key='different' WHERE file_id=? AND revision=?
                            """, fileId, 1),
                    "file_replica identity column object_key must reject UPDATE");
            assertThrows(RuntimeException.class,
                    () -> admin.update("""
                            UPDATE eatwhat_file.file_transfer_job SET job_id=? WHERE job_id=?
                            """, UUID.randomUUID(), jobId),
                    "file_transfer_job identity column job_id must reject UPDATE");

            // Test 3: CHECK constraints enforce valid values
            assertThrows(DataIntegrityViolationException.class,
                    () -> admin.update("""
                            UPDATE eatwhat_file.file_transfer_job SET status=? WHERE job_id=?
                            """, "INVALID_STATUS", jobId),
                    "file_transfer_job status must enforce CHECK constraint");
            assertThrows(DataIntegrityViolationException.class,
                    () -> admin.update("""
                            UPDATE eatwhat_file.file_transfer_job SET kind=? WHERE job_id=?
                            """, "INVALID_KIND", jobId),
                    "file_transfer_job kind must enforce CHECK constraint");
            assertThrows(DataIntegrityViolationException.class,
                    () -> admin.update("""
                            INSERT INTO eatwhat_file.file_replica(file_id,revision,site,object_key,state,updated_at)
                            VALUES (?,?,'INVALID_SITE','test','AVAILABLE_LOCAL',now())
                            """, UUID.randomUUID(), 1),
                    "file_replica site must enforce CHECK constraint");
        }
    }

    @Test
    void v28TablesEnforceAppendOnlyAndUpdateRestrictions() {
        try (var postgres = IsolatedContainers.postgres("file-v28-constraints")) {
            postgres.start();
            String database = "file_v28_constraints_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            Flyway flyway = migration(url, "28");
            assertEquals(27, flyway.migrate().migrationsExecuted);

            // Test file_replica_alert append-only constraint
            UUID fileId = UUID.randomUUID();
            UUID alertId = UUID.randomUUID();
            String sha256HexB = "b".repeat(64);
            admin.update("""
                    INSERT INTO eatwhat_file.file_object(file_id,owner_gid,biz,logical_key,canonical_revision,created_at)
                    VALUES (?,'550e8400-e29b-41d4-a716-446655440000','avatar','test/alert-file',1,now())
                    """, fileId);
            admin.update("""
                    INSERT INTO eatwhat_file.file_revision(file_id,revision,final_key,sha256,size_bytes,content_type,state,created_at)
                    VALUES (?,?,'test/alert-final',?,2048,'image/png','VERIFIED',now())
                    """, fileId, 1, sha256HexB);
            admin.update("""
                    INSERT INTO eatwhat_file.file_replica_alert(alert_id,file_id,revision,site,alert_type,message,created_at)
                    VALUES (?,?,?,'LOCAL','SYNC_FAILED','Test alert',now())
                    """, alertId, fileId, 1);

            assertThrows(RuntimeException.class,
                    () -> admin.update("UPDATE eatwhat_file.file_replica_alert SET alert_type='UPDATED' WHERE alert_id=?", alertId),
                    "file_replica_alert must reject UPDATE");
            assertThrows(RuntimeException.class,
                    () -> admin.update("DELETE FROM eatwhat_file.file_replica_alert WHERE alert_id=?", alertId),
                    "file_replica_alert must reject DELETE");
        }
    }

    private static Flyway migration(String url, String target) {
        return Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").target(target).baselineOnMigrate(false).cleanDisabled(true).load();
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
    }

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_tables WHERE schemaname='eatwhat_file' AND tablename=?)",
                Boolean.class, table);
    }

    private static List<Map<String, Object>> checksums(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank");
    }

    private static List<Map<String, Object>> checksumsExcludingV28(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE success AND version<>'28' ORDER BY installed_rank");
    }
}
