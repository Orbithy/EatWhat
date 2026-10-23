package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.account.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.preferences.*;
import you.v50to.eatwhat.sync.transport.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real restricted-role replicas, transport gates, account purge and request-time recommendation SQL. */
class PreferenceReplicationIT {
    static GenericContainer<?> pg;
    static final EventCodec CODEC=new EventCodec();
    static final Duration LEASE=Duration.ofSeconds(30);
    @BeforeAll static void start(){pg=IsolatedContainers.startedPostgresWithInspectRetry("preferences-replication");}
    @AfterAll static void stop(){if(pg!=null)pg.stop();}
    record Site(RelationReplicationIT.Site base,PreferenceCommands commands,InboxService inbox,PreferenceEvents policy){
        EventFoundationTestSupport.Database db(){return base.db();}
        ConsentStore consent(){return base.consent();}
    }
    record Pair(RelationReplicationIT.Pair fixture,Site a,Site b){long actor(){return fixture.actor();}}
    static Site site(RelationReplicationIT.Site base){
        var db=base.db();
        var commands=new PreferenceCommands(db.runtime(),db.transactions(),new OutboxService(db.runtime(),db.transactions(),CODEC,db.site()),base.consent(),db.site(),base.peerSiteId(),32);
        return new Site(base,commands,new InboxService(db.runtime(),db.transactions(),CODEC,
                new EventHandlerRegistry(List.of(new PreferenceEventHandler(db.runtime(),base.consent(),commands)))),new PreferenceEvents(base.consent()));
    }
    static Pair pair()throws Exception{
        var f=RelationReplicationIT.pair(pg);RelationReplicationIT.extendGrantedConsentForSlowSyntheticTest(f);
        return new Pair(f,site(f.a()),site(f.b()));
    }
    static List<SyncEvent> events(Site s){return s.db().runtime().query("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type=? ORDER BY local_order",(r,n)->CODEC.decode(r.getString(1)),PreferenceEvents.AGGREGATE);}
    static void receive(Site s,SyncEvent e){s.policy.validate(e,new NodeIdentity(e.originSite(),e.originInstanceId()));s.inbox.receive(e,e.originSite());for(var lease:s.inbox.claim(100,LEASE))s.inbox.process(lease);}
    static void exchange(Pair p){
        for(var from:List.of(p.a,p.b)){
            var to=from==p.a?p.b:p.a;
            for(var lease:from.base.delivery().claimForAggregates(to.db().site().getId(),100,LEASE,Set.of(PreferenceEvents.AGGREGATE))){
                if(from.policy.outbound(lease.event())==EventPolicy.OutboundDecision.ALLOW){receive(to,lease.event());assertTrue(from.base.delivery().acknowledge(lease,"DURABLY_QUEUED"));}
            }
        }
    }
    static List<Map<String,Object>> values(Site s,long user){return s.db().runtime().queryForList("SELECT array_to_json(liked_tags)::text AS liked,array_to_json(disliked_tags)::text AS disliked,updated_at FROM user_food_preferences WHERE account_id=?",user);}
    static void assertValue(Site s,long user,List<String> likes,List<String> dislikes){
        if(likes.isEmpty()&&dislikes.isEmpty()){assertTrue(values(s,user).isEmpty());return;}
        assertEquals(1,values(s,user).size());
        assertEquals(likes,s.db().runtime().query("SELECT unnest(liked_tags) FROM user_food_preferences WHERE account_id=?",(r,n)->r.getString(1),user));
        assertEquals(dislikes,s.db().runtime().query("SELECT unnest(disliked_tags) FROM user_food_preferences WHERE account_id=?",(r,n)->r.getString(1),user));
    }
    static ConsentModels.Job job(Site s,long user,String kind){return s.consent().claim(32,LEASE).stream().filter(j->j.userId()==user&&j.kind().equals(kind)).findFirst().orElseThrow();}
    static void withdraw(Pair p){p.a.consent().withdraw(p.actor());var job=job(p.a,p.actor(),"PURGE");assertEquals("APPLIED",p.b.consent().apply(p.a.consent().export(job)));p.a.consent().complete(job);}
    static void grant(Pair p){p.a.consent().grant(p.actor(),RelationReplicationIT.POLICY);var job=job(p.a,p.actor(),"UPSERT");assertEquals("APPLIED",p.b.consent().apply(p.a.consent().export(job)));p.a.consent().complete(job);}

    @Test void runtimeCanUpdateOnlyTheMutableVersionColumnsAndCannotDeleteMetadata()throws Exception{
        var p=pair();
        for(var site:List.of(p.a,p.b)){
            assertFalse(site.db().runtime().queryForObject("SELECT has_table_privilege(current_user,'eatwhat_sync.preference_state','UPDATE,DELETE,TRUNCATE')",Boolean.class));
            assertFalse(site.db().runtime().queryForObject("SELECT has_column_privilege(current_user,'eatwhat_sync.preference_state','subject_gid','UPDATE')",Boolean.class));
            assertTrue(site.db().runtime().queryForObject("SELECT has_column_privilege(current_user,'eatwhat_sync.preference_state','winner_id','UPDATE')",Boolean.class));
        }
    }
    @Test void wholeSettingsReplaceInBothDirectionsWithOriginalTimeAndNoEcho()throws Exception{
        var p=pair();p.a.commands.replace(p.actor(),List.of("spicy"),List.of("sweet"));exchange(p);
        assertEquals(values(p.a,p.actor()),values(p.b,p.actor()));assertValue(p.b,p.actor(),List.of("spicy"),List.of("sweet"));
        p.b.commands.replace(p.actor(),List.of("sweet"),List.of("spicy"));exchange(p);
        assertEquals(values(p.a,p.actor()),values(p.b,p.actor()));assertValue(p.a,p.actor(),List.of("sweet"),List.of("spicy"));
        assertEquals(0,p.a.commands.runOnce());assertEquals(0,p.b.commands.runOnce());
        assertEquals(1,events(p.a).size());assertEquals(1,events(p.b).size());
    }
    @Test void clearDeletesProjectionButRetainsContentFreeVersionAndRejectsDelayedSet()throws Exception{
        var p=pair();p.a.commands.replace(p.actor(),List.of("spicy"),List.of());var old=events(p.a).getFirst();
        p.a.commands.replace(p.actor(),List.of(),List.of());var clear=events(p.a).getLast();
        receive(p.b,clear);receive(p.b,old);receive(p.b,clear);
        assertValue(p.b,p.actor(),List.of(),List.of());
        assertTrue(p.b.db().runtime().queryForObject("SELECT is_empty FROM eatwhat_sync.preference_state",Boolean.class));
        assertEquals(0,p.b.commands.runOnce());
        assertEquals(0,p.b.db().runtime().queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='eatwhat_sync' AND table_name='preference_state' AND column_name IN ('liked_tags','disliked_tags','payload')",Long.class));
    }
    @Test void concurrentOfflineCompleteVersionsConvergeAndNextObservedEditWins()throws Exception{
        var p=pair();p.a.commands.replace(p.actor(),List.of("spicy"),List.of("sweet"));
        p.b.commands.replace(p.actor(),List.of("sweet"),List.of("spicy"));exchange(p);
        assertEquals(values(p.a,p.actor()),values(p.b,p.actor()));
        boolean aWins=p.a.db().site().getId().compareTo(p.b.db().site().getId())>0;
        assertValue(p.a,p.actor(),List.of(aWins?"spicy":"sweet"),List.of(aWins?"sweet":"spicy"));
        p.a.commands.replace(p.actor(),List.of("umami"),List.of());exchange(p);
        assertValue(p.b,p.actor(),List.of("umami"),List.of());assertEquals(values(p.a,p.actor()),values(p.b,p.actor()));
    }
    @Test void outOfOrderAndDuplicateEventsDoNotRollBackTheLatestSave()throws Exception{
        var p=pair();p.a.commands.replace(p.actor(),List.of("first"),List.of());p.a.commands.replace(p.actor(),List.of("last"),List.of());
        var es=events(p.a);receive(p.b,es.getLast());receive(p.b,es.getFirst());receive(p.b,es.getLast());
        assertValue(p.b,p.actor(),List.of("last"),List.of());assertEquals(values(p.a,p.actor()),values(p.b,p.actor()));
        assertEquals(2,p.b.db().runtime().queryForObject("SELECT count(*)FROM eatwhat_sync.inbox WHERE status='APPLIED'",Long.class));
    }
    @Test void firstIncomingSnapshotCannotEraseTheOtherSitesUnpublishedLegacyChoice()throws Exception{
        for(boolean campusFirst:List.of(true,false)){
            var p=pair();
            p.a.db().runtime().update("INSERT INTO user_food_preferences(account_id,liked_tags)VALUES(?,ARRAY['spicy'])",p.actor());
            p.b.db().runtime().update("INSERT INTO user_food_preferences(account_id,liked_tags)VALUES(?,ARRAY['sweet'])",p.actor());
            var from=campusFirst?p.a:p.b;var to=campusFirst?p.b:p.a;
            assertEquals(1,from.commands.runOnce());receive(to,events(from).getFirst());
            assertTrue(events(to).isEmpty(),"Inbound must never recapture an outbound event");
            assertEquals("WAITING_DEPENDENCY",to.db().runtime().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?",String.class,events(from).getFirst().eventId()));
            assertEquals(1,to.commands.runOnce());
            to.inbox.requeueWaiting(100,Duration.ZERO);for(var lease:to.inbox.claim(100,LEASE))to.inbox.process(lease);
            assertEquals(1,events(to).size(),"The source worker captures the local choice before replacement");
            exchange(p);assertEquals(values(p.a,p.actor()),values(p.b,p.actor()));
            boolean campusWins=p.a.db().site().getId().compareTo(p.b.db().site().getId())>0;
            assertValue(p.a,p.actor(),List.of(campusWins?"spicy":"sweet"),List.of());
            assertEquals(0,p.a.commands.runOnce());assertEquals(0,p.b.commands.runOnce());
        }
    }
    @Test void legacySettingsBackfillOncePreservingTheOriginalTimestamp()throws Exception{
        var p=pair();p.a.db().runtime().update("INSERT INTO user_food_preferences(account_id,liked_tags,disliked_tags,updated_at)VALUES(?,ARRAY['spicy'],ARRAY['sweet'],clock_timestamp()-interval '8 days')",p.actor());
        assertEquals(1,p.a.commands.runOnce());exchange(p);assertEquals(values(p.a,p.actor()),values(p.b,p.actor()));
        assertEquals(0,site(p.a.base).commands.runOnce());assertEquals(0,site(p.b.base).commands.runOnce());
    }
    @Test void settingsThatExistedOnlyOverseasAlsoBackfill()throws Exception{
        var p=pair();p.b.db().runtime().update("INSERT INTO user_food_preferences(account_id,liked_tags)VALUES(?,ARRAY['sweet'])",p.actor());
        assertEquals(0,p.a.commands.runOnce());assertEquals(1,p.b.commands.runOnce());exchange(p);
        assertValue(p.a,p.actor(),List.of("sweet"),List.of());
    }
    @Test void localNonConsentingUserKeepsSettingsAndClearUntilExplicitGrant()throws Exception{
        var p=pair();long user=p.a.db().runtime().queryForObject("INSERT INTO users(nick_name,password_hash)VALUES('local-taste','synthetic')RETURNING id",Long.class);
        p.a.consent().onRegistered(user,false,RelationReplicationIT.POLICY);
        p.a.commands.replace(user,List.of("spicy"),List.of());p.a.commands.replace(user,List.of(),List.of());assertTrue(events(p.a).isEmpty());
        p.a.consent().grant(user,RelationReplicationIT.POLICY);var job=job(p.a,user,"UPSERT");p.b.consent().apply(p.a.consent().export(job));p.a.consent().complete(job);
        assertEquals(1,p.a.commands.runOnce());exchange(p);assertValue(p.b,user,List.of(),List.of());assertEquals(1,events(p.a).size());
    }
    @Test void expiryStopsOverseasEditsAndDeliveryWithoutChangingTheSavedProjection()throws Exception{
        var p=pair();p.b.commands.replace(p.actor(),List.of("spicy"),List.of());var event=events(p.b).getFirst();
        p.b.db().admin().update("UPDATE eatwhat_consent.subject SET valid_until=clock_timestamp()-interval '1 second' WHERE user_id=?",p.actor());
        assertEquals(EventPolicy.OutboundDecision.WAIT,p.b.policy.outbound(event));
        assertThrows(RuntimeException.class,()->p.b.commands.replace(p.actor(),List.of(),List.of("spicy")));
        assertValue(p.b,p.actor(),List.of("spicy"),List.of());assertEquals(0,p.b.commands.runOnce());
    }
    @Test void withdrawalCascadesSettingsAndDelayedEventCannotRecreateThem()throws Exception{
        var p=pair();p.a.commands.replace(p.actor(),List.of("spicy"),List.of("sweet"));exchange(p);
        p.a.commands.replace(p.actor(),List.of("umami"),List.of());var delayed=events(p.a).getLast();withdraw(p);
        assertValue(p.b,p.actor(),List.of(),List.of());assertValue(p.a,p.actor(),List.of("umami"),List.of());
        assertEquals(EventPolicy.OutboundDecision.POLICY_BLOCKED,p.a.policy.outbound(delayed));receive(p.b,delayed);
        assertValue(p.b,p.actor(),List.of(),List.of());assertEquals(0,p.b.commands.runOnce());
    }
    @Test void reconsentBridgesTheRetainedCampusClearButNeverReplaysAnErasedOverseasSetting()throws Exception{
        var p=pair();p.b.commands.replace(p.actor(),List.of("sweet"),List.of());exchange(p);
        p.a.commands.replace(p.actor(),List.of(),List.of());var delayedClear=events(p.a).getLast();withdraw(p);grant(p);
        assertEquals(0,p.b.commands.runOnce());receive(p.b,delayedClear);assertValue(p.b,p.actor(),List.of(),List.of());
        assertEquals(1,p.a.commands.runOnce());exchange(p);assertValue(p.b,p.actor(),List.of(),List.of());
        assertEquals(p.a.consent().get(p.actor()).epoch(),PreferenceEvents.parse(events(p.a).getLast().payloadJson()).epoch());
    }
    @Test void rolledBackBusinessTransactionLeaksNeitherSettingsNorEvent()throws Exception{
        var p=pair();assertThrows(IllegalStateException.class,()->p.a.db().transactions().required(()->{
            p.a.commands.replace(p.actor(),List.of("spicy"),List.of());throw new IllegalStateException("synthetic rollback");
        }));assertValue(p.a,p.actor(),List.of(),List.of());assertTrue(events(p.a).isEmpty());
        assertEquals(0,p.a.db().runtime().queryForObject("SELECT count(*)FROM eatwhat_sync.preference_state",Long.class));
    }
    @Test void concurrentLocalSavesAllocateDistinctMonotonicLogicalVersions()throws Exception{
        var p=pair();var pool=Executors.newFixedThreadPool(4);
        try{for(var result:pool.invokeAll(java.util.stream.IntStream.range(0,8).<Callable<Void>>mapToObj(i->()->{
            p.a.commands.replace(p.actor(),List.of("taste"+i),List.of());return null;}).toList()))result.get();}finally{pool.shutdownNow();}
        assertEquals(List.of(1L,2L,3L,4L,5L,6L,7L,8L),events(p.a).stream().map(e->PreferenceEvents.parse(e.payloadJson()).clock()).toList());
        exchange(p);assertEquals(values(p.a,p.actor()),values(p.b,p.actor()));
    }
    @Test void manualLikeBoostsAndManualDislikeExcludesActualRecommendationsOnBothSites()throws Exception{
        var p=pair();var base=p.fixture;long restaurant=p.a.db().runtime().queryForObject("SELECT restaurant_id FROM foods WHERE id=?",Long.class,base.food());
        long match=p.a.db().runtime().queryForObject("INSERT INTO foods(account_id,restaurant_id,name,picture_url)VALUES(?,?,'match',ARRAY['foods/test.png'])RETURNING id",Long.class,base.owner(),restaurant);
        long popular=p.a.db().runtime().queryForObject("INSERT INTO foods(account_id,restaurant_id,name,picture_url,likes_count)VALUES(?,?,'popular',ARRAY['foods/test.png'],10)RETURNING id",Long.class,base.owner(),restaurant);
        long tagging=p.a.db().runtime().queryForObject("INSERT INTO food_taggings(account_id,food_id,tag_id)VALUES(?,?,?)RETURNING id",Long.class,base.owner(),match,base.tag());
        for(long id:List.of(match,popular))RelationReplicationIT.mirror(p.a.db(),p.b.db(),"foods",id);
        RelationReplicationIT.mirror(p.a.db(),p.b.db(),"food_taggings",tagging);
        assertEquals(List.of(popular,match),BrowseReplicationIT.ranking(BrowseReplicationIT.site(p.a.base),p.actor()));
        p.a.commands.replace(p.actor(),List.of("fixture tag"),List.of());exchange(p);
        for(var s:List.of(p.a,p.b))assertEquals(List.of(match,popular),BrowseReplicationIT.ranking(BrowseReplicationIT.site(s.base),p.actor()));
        p.b.commands.replace(p.actor(),List.of(),List.of("fixture tag"));exchange(p);
        for(var s:List.of(p.a,p.b))assertEquals(List.of(popular),BrowseReplicationIT.ranking(BrowseReplicationIT.site(s.base),p.actor()));
        p.a.commands.replace(p.actor(),List.of(),List.of());exchange(p);
        for(var s:List.of(p.a,p.b))assertEquals(List.of(popular,match),BrowseReplicationIT.ranking(BrowseReplicationIT.site(s.base),p.actor()));
    }
    @Test void oldPolicyGrantDoesNotAuthorizeTheNewManualSettingsScope()throws Exception{
        var p=pair();var consent=new ConsentStore(p.a.db().runtime(),p.a.db().transactions(),p.a.db().site(),"cross-border-preferences-v3",Duration.ofMinutes(5),Duration.ofMinutes(2),p.b.db().site().getId(),p.b.db().site().getInstanceId());
        var cmd=new PreferenceCommands(p.a.db().runtime(),p.a.db().transactions(),new OutboxService(p.a.db().runtime(),p.a.db().transactions(),CODEC,p.a.db().site()),consent,p.a.db().site(),p.b.db().site().getId(),32);
        cmd.replace(p.actor(),List.of("spicy"),List.of());assertValue(p.a,p.actor(),List.of("spicy"),List.of());assertTrue(events(p.a).isEmpty());assertEquals(0,cmd.runOnce());
    }
    @Test void newerLogicalVersionWinsEvenWhenItsOriginalWallTimeIsEarlier()throws Exception{
        var p=pair();p.a.commands.replace(p.actor(),List.of("spicy"),List.of());var first=events(p.a).getFirst();receive(p.b,first);
        var op=PreferenceEvents.parse(first.payloadJson());var next=new PreferenceEvents.Snapshot(op.subject(),op.epoch(),op.clock()+1,List.of("sweet"),List.of(),op.updatedAtMicros()-1000000);
        UUID id=UUID.randomUUID();var event=new SyncEvent(id,first.originSite(),first.originInstanceId(),first.aggregateType(),id,first.numericId()+1,first.eventType(),1,VersionVector.empty(),first.nextVersion(),first.dependencies(),first.actorId(),"preferences:"+id,id,PreferenceEvents.payload(next));
        receive(p.b,event);assertValue(p.b,p.actor(),List.of("sweet"),List.of());
    }
}
