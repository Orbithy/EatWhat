package you.v50to.eatwhat.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.control.bootstrap.ControllerExistingAuthoritySchema;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real Flyway/PG on synthetic migrated databases, not JOIN or native NFS. */
class ControllerExistingAuthoritySchemaIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("existing-authority-schema");
    @BeforeAll static void start(){PG.start();}
    @AfterAll static void stop(){PG.stop();}
    EventFoundationTestSupport.Database db;SiteProperties site;MockEnvironment env;
    @BeforeEach void fixture(){prepare(null,true);}
    void prepare(String version,boolean active){
        site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId("campus");site.setInstanceId(UUID.randomUUID());site.setClusterId(UUID.randomUUID());site.setAllocationSlot(0);
        db=EventFoundationTestSupport.createDatabaseAtVersion(PG,site,version);
        db.admin().execute("GRANT USAGE ON SCHEMA eatwhat_cluster TO \""+db.runtimeUsername()+"\"");
        db.admin().execute("GRANT SELECT ON ALL TABLES IN SCHEMA eatwhat_cluster TO \""+db.runtimeUsername()+"\"");
        String fingerprint=db.admin().queryForObject("SELECT md5(cluster_identifier||':'||database_oid::text) FROM eatwhat_identity.node_state",String.class);
        db.admin().update("INSERT INTO eatwhat_cluster.state(cluster_id,coordinator_site,coordinator_instance,epoch) VALUES(?,?,?,1)",site.getClusterId(),site.getId(),site.getInstanceId());
        db.admin().update("INSERT INTO eatwhat_cluster.member(site_id,instance_id,profile_id,mode,allocation_slot,state,database_fingerprint,joined_epoch) VALUES(?,?,'campus','CAMPUS',0,?,?,1)",site.getId(),site.getInstanceId(),active?"ACTIVE":"REGISTERED",fingerprint);
        env=new MockEnvironment();for(String name:List.of("app.control.enabled","app.control.node-enabled","app.control.shared-state-required","app.cluster.enabled","app.cluster.coordinator","spring.flyway.enabled"))env.setProperty(name,"true");
        env.setProperty("app.control.environment-kind","managed-deployment");env.setProperty("app.control.database-mode","existing-authority");env.setProperty("app.cluster.single-site-only","false");
        env.setProperty("app.control.authority-database-fingerprint",fingerprint);env.setProperty("spring.datasource.url",db.url());
        env.setProperty("spring.datasource.username",db.runtimeUsername());env.setProperty("spring.datasource.password",IsolatedContainers.PASSWORD);
    }
    Flyway flyway(){return Flyway.configure().dataSource(db.dataSource()).locations("classpath:db/migration").load();}
    String snapshot(){return db.admin().queryForObject("""
        SELECT jsonb_build_object(
          'history',(SELECT jsonb_agg(to_jsonb(h) ORDER BY installed_rank) FROM public.flyway_schema_history h),
          'node',(SELECT to_jsonb(n) FROM eatwhat_identity.node_state n),
          'allocation',(SELECT to_jsonb(a) FROM eatwhat_identity.allocation a),
          'members',(SELECT jsonb_agg(to_jsonb(m) ORDER BY site_id) FROM eatwhat_cluster.member m),
          'state',(SELECT to_jsonb(s) FROM eatwhat_cluster.state s),
          'roles',(SELECT jsonb_agg(jsonb_build_array(rolname,rolsuper,rolcreaterole,rolcreatedb) ORDER BY rolname) FROM pg_roles),
          'acls',(SELECT jsonb_agg(jsonb_build_array(n.nspname,c.relname,c.relacl::text) ORDER BY n.nspname,c.relname)
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','eatwhat_identity','eatwhat_cluster'))
        )::text
        """,String.class);}
    @Test void acceptsCompleteAuthorityWithoutCallbacksHistoryWritesGrantsOrPoolContamination()throws Exception {
        String before=snapshot();var called=new AtomicInteger();
        try(var pool=new HikariDataSource()){
            pool.setJdbcUrl(db.url());pool.setUsername(db.runtimeUsername());pool.setPassword(IsolatedContainers.PASSWORD);pool.setMaximumPoolSize(1);
            var original=Flyway.configure().dataSource(pool).locations("classpath:db/migration").callbacks(new BaseCallback(){
                @Override public boolean supports(Event event,Context context){return true;}
                @Override public void handle(Event event,Context context){called.incrementAndGet();}
            }).load();
            ControllerExistingAuthoritySchema.validateExisting(original,env,site);
            assertEquals(before,snapshot());assertEquals(0,called.get());
            try(var connection=pool.getConnection();var statement=connection.createStatement()){
                try(var rows=statement.executeQuery("SHOW default_transaction_read_only")){rows.next();assertEquals("off",rows.getString(1));}
                assertEquals(1,statement.executeUpdate("INSERT INTO public.business_counter(gid,numeric_id,value) VALUES('"+UUID.randomUUID()+"',1,1)"));
            }
        }
    }
    @Test void pendingMigrationRejectsAndDoesNotMigrateOrRepair(){
        prepare("54",true);String before=snapshot();
        var failure=assertThrows(IllegalStateException.class,()->ControllerExistingAuthoritySchema.validateExisting(flyway(),env,site));
        assertEquals("EXISTING_AUTHORITY_SCHEMA_REQUIRED",failure.getMessage());assertNull(failure.getCause());assertEquals(before,snapshot());
        assertEquals("54",db.admin().queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",String.class));
    }
    @Test void checksumDriftIsRejectedAndNotRepaired(){
        db.admin().update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='55'");String before=snapshot();
        assertThrows(IllegalStateException.class,()->ControllerExistingAuthoritySchema.validateExisting(flyway(),env,site));assertEquals(before,snapshot());
    }
    @Test void wrongAuthorityBindingAndNotActiveAuthorityAreRejected(){
        String before=snapshot();env.setProperty("app.control.authority-database-fingerprint","e".repeat(32));
        assertThrows(IllegalStateException.class,()->ControllerExistingAuthoritySchema.validateExisting(flyway(),env,site));assertEquals(before,snapshot());
        prepare(null,false);before=snapshot();assertThrows(IllegalStateException.class,()->ControllerExistingAuthoritySchema.validateExisting(flyway(),env,site));assertEquals(before,snapshot());
    }
    @Test void privilegedMigrationRoleCannotBecomeRuntimeController(){
        String before=snapshot();env.setProperty("spring.datasource.username",IsolatedContainers.USER);
        assertThrows(IllegalStateException.class,()->ControllerExistingAuthoritySchema.validateExisting(flyway(),env,site));assertEquals(before,snapshot());
    }
    @Test void dedicatedSessionsDenyWritesAcrossAutocommitChangesAndDoNotAffectNormalConnections()throws Exception {
        try(var connection=ControllerExistingAuthoritySchema.readonlyDataSource(env).getConnection()){
            connection.setAutoCommit(false);connection.setAutoCommit(true);connection.setReadOnly(false);
            try(var statement=connection.createStatement()){
                var denied=assertThrows(SQLException.class,()->statement.executeUpdate("UPDATE public.business_counter SET value=2"));assertEquals("25006",denied.getSQLState());
                denied=assertThrows(SQLException.class,()->statement.execute("CREATE TABLE public.should_never_exist(value integer)"));assertEquals("25006",denied.getSQLState());
            }
        }
        assertFalse(db.runtime().queryForObject("SELECT current_setting('default_transaction_read_only')::boolean",Boolean.class));
        assertNull(db.admin().queryForObject("SELECT to_regclass('public.should_never_exist')",String.class));
    }
    @Test void emptyDatabaseCannotBeInitializedByValidationMode(){
        String name="empty_controller_"+UUID.randomUUID().toString().replace("-","");
        db.admin().execute("CREATE DATABASE "+name+" TEMPLATE template0");String url=IsolatedContainers.jdbcUrl(PG,name);
        env.setProperty("spring.datasource.url",url);
        assertThrows(IllegalStateException.class,()->ControllerExistingAuthoritySchema.validateExisting(flyway(),env,site));
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,IsolatedContainers.USER,IsolatedContainers.PASSWORD));
        assertNull(admin.queryForObject("SELECT to_regclass('public.flyway_schema_history')",String.class));
        assertEquals(0L,admin.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname LIKE 'eatwhat_%'",Long.class));
    }
}
