package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.springframework.test.util.ReflectionTestUtils;
import you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.privacy.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MultiwriterOfflineBackfillIT {
    static GenericContainer<?> pg;
    @BeforeAll static void start(){pg=IsolatedContainers.startedPostgresWithInspectRetry("offline-backfill");}
    @AfterAll static void stop(){if(pg!=null)pg.stop();}

    @Test void expiredLegacyLeaseDoesNotStrandBrowseOrPreferenceIntentsInIndependentMode() throws Exception {
        var pair=RelationReplicationIT.pair(pg);var external=pair.b();var db=external.db();
        var browse=BrowseReplicationIT.site(external);var preference=PreferenceReplicationIT.site(external);
        db.runtime().update("INSERT INTO browse_history(account_id,target_type,target_id) VALUES(?,'food',?)",pair.actor(),pair.food());
        db.runtime().update("INSERT INTO user_food_preferences(account_id,liked_tags) VALUES(?,ARRAY['offline'])",pair.actor());
        db.admin().execute("UPDATE eatwhat_consent.subject SET valid_until=clock_timestamp()-interval '1 day'");
        assertEquals(0,browse.commands().runOnce());assertEquals(0,preference.commands().runOnce());
        enable(external);
        assertTrue(external.consent().replicationAllowed(pair.actor()));
        assertEquals(1,browse.commands().runOnce(),"A retained local observation must publish without renewing a remote lease");
        assertEquals(1,preference.commands().runOnce(),"Local preferences must not wait indefinitely for a legacy lease");
        assertEquals(0,browse.commands().runOnce());assertEquals(0,preference.commands().runOnce());
    }

    @Test void removingLeaseDependencyDoesNotBypassActualConsentWithdrawal() throws Exception {
        var pair=RelationReplicationIT.pair(pg);var external=pair.b();var db=external.db();
        db.runtime().update("INSERT INTO browse_history(account_id,target_type,target_id) VALUES(?,'food',?)",pair.actor(),pair.food());
        db.runtime().update("INSERT INTO user_food_preferences(account_id,liked_tags) VALUES(?,ARRAY['private'])",pair.actor());
        enable(external);
        db.admin().update("UPDATE eatwhat_consent.subject SET status='WITHDRAWN',identity_ready=false,valid_until=NULL WHERE user_id=?",pair.actor());
        assertEquals(0,BrowseReplicationIT.site(external).commands().runOnce());
        assertEquals(0,PreferenceReplicationIT.site(external).commands().runOnce());
        assertEquals(0,db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox",Integer.class));
    }

    @Test void privacyBackfillAlsoSurvivesAnExpiredLegacyLease() throws Exception {
        var pair=RelationReplicationIT.pair(pg);var external=pair.b();var db=external.db();
        db.admin().execute("UPDATE eatwhat_consent.subject SET valid_until=clock_timestamp()-interval '1 day'");
        var commands=new PrivacyCommands(db.runtime(),db.transactions(),new PrivacyStore(db.runtime()),
                new OutboxService(db.runtime(),db.transactions(),new EventCodec(),db.site()),
                new you.v50to.eatwhat.identity.EntityIdentityService(db.runtime()),external.consent(),db.site(),external.peerSiteId());
        commands.runOnce();
        assertEquals(0,db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='privacy.operation'",Integer.class));
        enable(external);commands.runOnce();
        int first=db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='privacy.operation'",Integer.class);
        assertTrue(first>0,"Privacy baseline must publish using durable local consent");
        commands.runOnce();
        assertEquals(first,db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='privacy.operation'",Integer.class));
    }

    private void enable(RelationReplicationIT.Site external){
        var db=external.db();db.admin().execute("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true");
        ReflectionTestUtils.setField(external.consent(),"multiwriter",new MultiwriterAccounts(db.runtime(),db.transactions(),db.site(),true));
    }
}
