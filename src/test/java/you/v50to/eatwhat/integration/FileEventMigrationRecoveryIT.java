package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL transactional DDL: a failed V34 rolls back constraints, triggers and reconciliation effects together. */
class FileEventMigrationRecoveryIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("file-channel-migration");
    @TempDir Path directory;
    @BeforeAll static void start() {PG.start();}
    @AfterAll static void stop() {PG.stop();}
    @Test void interruptedUpgradeRetainsV33AndItsReferencesThenRetriesAndReconcilesStaleReferences() throws Exception {
        String name="channel_migration_"+UUID.randomUUID().toString().replace("-","");
        var server=new JdbcTemplate(new DriverManagerDataSource(IsolatedContainers.jdbcUrl(PG,IsolatedContainers.DATABASE),IsolatedContainers.USER,IsolatedContainers.PASSWORD));
        server.execute("CREATE DATABASE "+name+" TEMPLATE template0");String url=IsolatedContainers.jdbcUrl(PG,name);
        Flyway.configure().dataSource(url,IsolatedContainers.USER,IsolatedContainers.PASSWORD).target("33").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,IsolatedContainers.USER,IsolatedContainers.PASSWORD));
        long uid=jdbc.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('migration-user','synthetic') RETURNING id",Long.class);
        UUID owner=jdbc.queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",UUID.class,uid);
        UUID file=UUID.randomUUID();String key="avatar/"+uid+"/"+file+".png";
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id,owner_gid,biz,logical_key) VALUES(?,?,'avatar',?)",file,owner,key);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id,revision,final_key,sha256,size_bytes,content_type,state,verified_at) VALUES(?,1,?,?,12,'image/png','VERIFIED',clock_timestamp())",file,"verified/"+file,"a".repeat(64));
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key,file_id,revision,owner_gid,biz) VALUES(?,?,1,?,'avatar')",key,file,owner);
        // Before V34, a raw avatar replacement did not release this existing public reference.
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id,visibility) VALUES(?,'user_avatar',?,'PUBLIC')",key,Long.toString(uid));
        Path migrations=Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        try(var files=Files.list(migrations)) {for(Path p:files.filter(p->p.getFileName().toString().endsWith(".sql")).toList())Files.copy(p,directory.resolve(p.getFileName()));}
        Files.writeString(directory.resolve("V34__file_event_channel.sql"),"\nSELECT 1/0;\n",StandardOpenOption.APPEND);
        var broken=Flyway.configure().dataSource(url,IsolatedContainers.USER,IsolatedContainers.PASSWORD).locations("filesystem:"+directory).load();
        assertThrows(org.flywaydb.core.api.FlywayException.class,broken::migrate);
        assertEquals("33",broken.info().current().getVersion().getVersion());
        assertNull(jdbc.queryForObject("SELECT to_regclass('eatwhat_file.file_sync_dirty')::text",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference_live WHERE logical_key=?",Integer.class,key));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation",Integer.class));
        var recovered=Flyway.configure().dataSource(url,IsolatedContainers.USER,IsolatedContainers.PASSWORD).target("34").load();
        assertEquals(1,recovered.migrate().migrationsExecuted);assertEquals(0,recovered.migrate().migrationsExecuted);recovered.validate();
        assertEquals("34",recovered.info().current().getVersion().getVersion());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference_live WHERE logical_key=?",Integer.class,key));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation",Integer.class));
        assertEquals(file,jdbc.queryForObject("SELECT file_id FROM eatwhat_file.file_key_alias WHERE logical_key=?",UUID.class,key));
    }
}
