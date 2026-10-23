package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.account.ConsentSchemaMigrationCallback;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.config.identity.GlobalIdentityMigrationCallback;
import you.v50to.eatwhat.config.sync.SyncSchemaMigrationCallback;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ControlCredentialWithdrawalMigrationIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("credential-withdrawal-migration");
    @BeforeAll static void start(){PG.start();}
    @AfterAll static void stop(){PG.stop();}
    private EventFoundationTestSupport.Database database(SiteProperties.Mode mode,String version){
        var site=new SiteProperties();site.setMode(mode);site.setId("credential-cleanup-"+mode.name().toLowerCase());site.setInstanceId(UUID.randomUUID());
        return EventFoundationTestSupport.createDatabaseAtVersion(PG,site,version);
    }
    private long formerAdministrator(EventFoundationTestSupport.Database db,String status,boolean archived){
        long id=db.runtime().queryForObject("INSERT INTO users(nick_name,password_hash,role) VALUES(?,'synthetic-only','admin') RETURNING id",Long.class,"cleanup_"+UUID.randomUUID().toString().substring(0,8));
        db.runtime().update("UPDATE users SET role='user',banned=true WHERE id=?",id);
        db.runtime().update("INSERT INTO contacts(account_id,email) VALUES(?,'cleanup@example.invalid')",id);
        db.admin().update("INSERT INTO eatwhat_consent.subject(subject_id,user_id,origin_site,epoch,revision,status,policy_version,source_archived,backfill_status) SELECT global_id,numeric_id,origin_site,1,1,?,'synthetic-cleanup',?,'ACCOUNT_PENDING' FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",status,archived,id);
        assertEquals(1L,db.admin().queryForObject("SELECT count(*) FROM eatwhat_control.credential_version WHERE user_id=?",Long.class,id));
        return id;
    }
    private boolean purge(EventFoundationTestSupport.Database db,long id){
        return db.transactions().required(()->db.runtime().queryForObject("SELECT eatwhat_consent.purge_account(subject_id,user_id) FROM eatwhat_consent.subject WHERE user_id=?",Boolean.class,id));
    }
    private void retained(EventFoundationTestSupport.Database db,long id){
        assertEquals(1L,db.runtime().queryForObject("SELECT count(*) FROM users WHERE id=?",Long.class,id));
        assertEquals(1L,db.runtime().queryForObject("SELECT count(*) FROM contacts WHERE account_id=?",Long.class,id));
        assertEquals(1L,db.admin().queryForObject("SELECT count(*) FROM eatwhat_control.credential_version WHERE user_id=?",Long.class,id));
    }
    @Test void additiveUpgradePermitsOnlyOwnedCascadingCredentialFence(){
        var db=database(SiteProperties.Mode.EXTERNAL,"73");long id=formerAdministrator(db,"WITHDRAWN",true);
        assertFalse(purge(db,id),"V73 reproduces the credential-version dependency block");retained(db,id);
        var flyway=Flyway.configure().dataSource(db.url(),IsolatedContainers.USER,IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").target("74").validateOnMigrate(true).cleanDisabled(true)
                .callbacks(new GlobalIdentityMigrationCallback(db.site(),db.runtimeUsername()),new SyncSchemaMigrationCallback(db.site(),db.runtimeUsername()),new ConsentSchemaMigrationCallback(db.site(),db.runtimeUsername())).load();
        assertEquals(1,flyway.migrate().migrationsExecuted);assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(0,flyway.migrate().migrationsExecuted);assertTrue(purge(db,id));
        assertEquals(0L,db.runtime().queryForObject("SELECT count(*) FROM users WHERE id=?",Long.class,id));
        assertEquals(0L,db.runtime().queryForObject("SELECT count(*) FROM contacts WHERE account_id=?",Long.class,id));
        assertEquals(0L,db.admin().queryForObject("SELECT count(*) FROM eatwhat_control.credential_version WHERE user_id=?",Long.class,id));
    }
    @Test void grantedAndCampusSubjectsRemainIneligible(){
        var external=database(SiteProperties.Mode.EXTERNAL,"74");long granted=formerAdministrator(external,"GRANTED",true);
        assertThrows(DataAccessException.class,()->purge(external,granted));retained(external,granted);
        var campus=database(SiteProperties.Mode.CAMPUS,"74");long withdrawn=formerAdministrator(campus,"WITHDRAWN",true);
        assertThrows(DataAccessException.class,()->purge(campus,withdrawn));retained(campus,withdrawn);
    }
    @Test void unarchivedExternalSourceRemainsProtected(){
        var db=database(SiteProperties.Mode.EXTERNAL,"74");long id=formerAdministrator(db,"WITHDRAWN",false);
        assertFalse(purge(db,id));retained(db,id);
    }
    @Test void unknownCascadingDependencyRemainsProtected(){
        var db=database(SiteProperties.Mode.EXTERNAL,"74");long id=formerAdministrator(db,"WITHDRAWN",true);
        db.admin().execute("CREATE TABLE public.unreviewed_account_dependency(user_id bigint REFERENCES public.users(id) ON DELETE CASCADE)");
        db.admin().update("INSERT INTO public.unreviewed_account_dependency VALUES(?)",id);
        assertFalse(purge(db,id));retained(db,id);
    }
    @Test void sameCredentialTableAndColumnWithoutCascadeRemainProtected(){
        var db=database(SiteProperties.Mode.EXTERNAL,"74");long id=formerAdministrator(db,"WITHDRAWN",true);
        db.admin().execute("ALTER TABLE eatwhat_control.credential_version DROP CONSTRAINT credential_version_user_id_fkey");
        db.admin().execute("ALTER TABLE eatwhat_control.credential_version ADD CONSTRAINT credential_version_user_id_fkey FOREIGN KEY(user_id) REFERENCES public.users(id) ON DELETE NO ACTION");
        assertFalse(purge(db,id));retained(db,id);
    }
}
