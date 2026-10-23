package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.node.ObjectNode;
import you.v50to.eatwhat.account.ConsentModels;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Nickname (users.display_name) in the consent snapshot path: campus export, external import, mixed versions. */
class AccountDisplayNameSnapshotIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("account-display-name");
    static final String POLICY="display-name-test-policy";
    static final Duration LEASE=Duration.ofSeconds(30);
    @BeforeAll static void start(){PG.start();}
    @AfterAll static void stop(){PG.stop();}

    record Site(EventFoundationTestSupport.Database db,ConsentStore consent) { }

    Site campus,external;

    @BeforeEach void sites(){
        var ap=new SiteProperties();ap.setMode(SiteProperties.Mode.CAMPUS);ap.setId("nickname-campus");ap.setInstanceId(UUID.randomUUID());
        var bp=new SiteProperties();bp.setMode(SiteProperties.Mode.EXTERNAL);bp.setId("nickname-external");bp.setInstanceId(UUID.randomUUID());
        var ad=EventFoundationTestSupport.createDatabase(PG,ap);var bd=EventFoundationTestSupport.createDatabase(PG,bp);
        campus=new Site(ad,new ConsentStore(ad.runtime(),ad.transactions(),ad.site(),POLICY,Duration.ofMinutes(5),Duration.ofMinutes(2),bp.getId(),bp.getInstanceId()));
        external=new Site(bd,new ConsentStore(bd.runtime(),bd.transactions(),bd.site(),POLICY,Duration.ofMinutes(5),Duration.ofMinutes(2),ap.getId(),ap.getInstanceId()));
    }

    long register(String handle,String nickname){
        long id=campus.db.runtime().queryForObject("INSERT INTO users(nick_name,display_name,password_hash) VALUES(?,?,'synthetic-only') RETURNING id",Long.class,handle,nickname);
        campus.db.runtime().update("INSERT INTO privacy(account_id) VALUES(?)",id);
        campus.consent.onRegistered(id,true,POLICY);
        sync(id);return id;
    }

    void sync(long user){
        var jobs=campus.consent.claim(32,LEASE).stream().filter(j->j.userId()==user&&j.kind().equals("UPSERT"))
                .sorted(Comparator.comparingLong(ConsentModels.Job::epoch)).toList();
        assertFalse(jobs.isEmpty(),"The account change must schedule a snapshot");
        for(var job:jobs){assertEquals("APPLIED",external.consent.apply(campus.consent.export(job)));campus.consent.complete(job);}
    }

    String nickname(Site site,long user){return site.db.runtime().queryForObject("SELECT display_name FROM users WHERE id=?",String.class,user);}

    @Test void nicknameIsCopiedOnRegistrationAndFollowsLaterEdits(){
        long user=register("p_nickname01","同学0001");
        assertEquals("同学0001",nickname(external,user));
        campus.db.runtime().update("UPDATE users SET display_name='干饭同学' WHERE id=?",user);
        sync(user);
        assertEquals("干饭同学",nickname(external,user));
    }

    @Test void unsetNicknameIsLeftOutOfTheSnapshotForSitesWithoutDisplayName(){
        long user=register("legacy_name",null);
        var snapshot=campus.consent.exportCurrent(user);
        assertFalse(snapshot.data().path("users").get(0).has("display_name"));
        assertNull(nickname(external,user));
    }

    @Test void snapshotFromAnOlderSiteKeepsTheLocalNickname(){
        long user=register("p_nickname02","同学0002");
        var current=campus.consent.exportCurrent(user);
        var data=(ObjectNode)current.data().deepCopy();((ObjectNode)data.path("users").get(0)).remove("display_name");
        // An older site sends its next revision without the column; the same revision with other content is a conflict.
        var legacy=new ConsentModels.Snapshot(current.subjectId(),current.userId(),current.epoch(),current.revision()+1,current.status(),
                current.policyVersion(),current.validUntil(),current.sourceSite(),current.sourceInstance(),current.withdrawalRequestId(),data);
        assertEquals("APPLIED",external.consent.apply(legacy));
        assertEquals("同学0002",nickname(external,user));
    }
}
