package you.v50to.eatwhat.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.account.*;
import you.v50to.eatwhat.mapper.*;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.browse.*;
import you.v50to.eatwhat.sync.transport.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Two real migrated databases, restricted roles, genuine Inbox/Outbox and request-time ranking. */
class BrowseReplicationIT {
    static GenericContainer<?> pg;
    static final EventCodec CODEC = new EventCodec();
    static final Duration LEASE = Duration.ofSeconds(30);
    @BeforeAll static void start() { pg = IsolatedContainers.startedPostgresWithInspectRetry("browse-replication"); }
    @AfterAll static void stop() { if (pg != null) pg.stop(); }
    record Site(RelationReplicationIT.Site base, BrowseCommands commands, InboxService inbox, BrowseEvents policy) {
        EventFoundationTestSupport.Database db() { return base.db(); }
        ConsentStore consent() { return base.consent(); }
    }
    record Pair(RelationReplicationIT.Pair fixture, Site a, Site b) { long actor() { return fixture.actor(); } long owner() { return fixture.owner(); } long food() { return fixture.food(); } }
    static Site site(RelationReplicationIT.Site base) {
        var db = base.db();
        return new Site(base, new BrowseCommands(db.runtime(), db.transactions(),
                new OutboxService(db.runtime(), db.transactions(), CODEC, db.site()), base.consent(), db.site(), base.peerSiteId(), 32),
                new InboxService(db.runtime(), db.transactions(), CODEC, new EventHandlerRegistry(List.of(new BrowseEventHandler(db.runtime(), base.consent()), new BrowseEventHandler(db.runtime(), base.consent(),2), new BrowseClearEventHandler(db.runtime(),base.consent())))),
                new BrowseEvents(base.consent()));
    }
    static Pair pair() throws Exception {
        // Browsing fixtures also run against the scoped V74.2 release without V75 activities.
        var fixture = RelationReplicationIT.pair(pg, false);
        RelationReplicationIT.extendGrantedConsentForSlowSyntheticTest(fixture);
        return new Pair(fixture, site(fixture.a()), site(fixture.b()));
    }
    static List<SyncEvent> events(Site site) {
        return site.db().runtime().query("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type=? ORDER BY local_order",
                (r, n) -> CODEC.decode(r.getString(1)), BrowseEvents.AGGREGATE);
    }
    static void receive(Site to, SyncEvent event) {
        to.policy.validate(event, new NodeIdentity(event.originSite(), event.originInstanceId()));
        to.inbox.receive(event, event.originSite());
        for (var lease : to.inbox.claim(100, LEASE)) to.inbox.process(lease);
    }
    static void exchange(Pair pair) {
        for (Site from : List.of(pair.a, pair.b)) {
            Site to = from == pair.a ? pair.b : pair.a;
            for (var lease : from.base.delivery().claimForAggregates(to.db().site().getId(), 100, LEASE, Set.of(BrowseEvents.AGGREGATE))) {
                if (from.policy.outbound(lease.event()) == EventPolicy.OutboundDecision.ALLOW) {
                    receive(to, lease.event()); assertTrue(from.base.delivery().acknowledge(lease, "DURABLY_QUEUED"));
                }
            }
        }
    }
    static long history(Site s, long actor) { return s.db().runtime().queryForObject("SELECT count(*) FROM public.browse_history WHERE account_id=?", Long.class, actor); }
    static List<Map<String, Object>> visits(Site s, long actor) {
        return s.db().runtime().queryForList("SELECT sync_visit_id,target_type,target_id,created_at FROM public.browse_history WHERE account_id=? ORDER BY created_at,sync_visit_id", actor);
    }
    static long restaurant(Pair p) { return p.a.db().runtime().queryForObject("SELECT restaurant_id FROM foods WHERE id=?", Long.class, p.food()); }
    static List<Long> ranking(Site site, long actor) throws Exception {
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true); configuration.addMapper(FoodMapper.class);
        var bean = new MybatisSqlSessionFactoryBean(); bean.setDataSource(site.db().dataSource()); bean.setConfiguration(configuration);
        try (var session = bean.getObject().openSession()) { return session.getMapper(FoodMapper.class).selectRecommendationCandidates(actor, 1L, true, null, 20).stream().map(f -> f.getId()).toList(); }
    }
    @Test void clearBothTabsConvergesAndDoesNotTouchAnotherAccountsHistory() throws Exception {
        var p=pair();
        p.a.commands.record(p.actor(),"food",p.food());
        p.b.commands.record(p.actor(),"restaurant",restaurant(p)); exchange(p);
        p.a.commands.record(p.owner(),"food",p.food()); exchange(p);
        assertTrue(p.b.commands.clear(p.actor(),null)); exchange(p);
        assertEquals(0,history(p.a,p.actor())); assertEquals(0,history(p.b,p.actor()));
        assertEquals(1,history(p.a,p.owner())); assertEquals(1,history(p.b,p.owner()));
        assertEquals(0,site(p.b.base).commands.runOnce());
    }

    @Test void offlineClearSuppressesUnknownDelayedAndLegacyVisitsWithoutUsingWallClock() throws Exception {
        var p=pair();
        p.a.commands.record(p.actor(),"food",p.food()); var delayed=events(p.a).getFirst();
        // B has never seen this visit; deleting known IDs alone cannot pass this case.
        p.b.commands.clear(p.actor(),"food"); var clear=events(p.b).getFirst();
        receive(p.a,clear); receive(p.b,delayed);
        assertEquals(0,history(p.a,p.actor())); assertEquals(0,history(p.b,p.actor()));
        p.a.commands.runOnce(); assertEquals(0,history(p.a,p.actor()));
        receive(p.a,clear); assertEquals(0,history(p.a,p.actor()));
    }

    @Test void newVisitCarriesClearFrontierAndSurvivesArrivalBeforeClearEvent() throws Exception {
        var p=pair();
        p.a.commands.record(p.actor(),"food",p.food()); var old=events(p.a).getFirst();
        p.a.commands.clear(p.actor(),"food"); var clear=events(p.a).getLast();
        assertTrue(p.a.commands.record(p.actor(),"food",p.food())); var fresh=events(p.a).getLast();
        assertEquals(2,fresh.schemaVersion());
        receive(p.b,fresh); receive(p.b,old); receive(p.b,clear);
        assertEquals(1,history(p.a,p.actor())); assertEquals(visits(p.a,p.actor()),visits(p.b,p.actor()));
        assertEquals(1L,p.b.db().admin().queryForObject(
                "SELECT count(*) FROM eatwhat_sync.personal_event_subjects(?::jsonb)",Long.class,CODEC.encode(clear).json()));
    }

    @Test void concurrentClearsWinOverUnobservedVisitsAndLaterBrowseStartsAgain() throws Exception {
        var p=pair();
        p.a.commands.clear(p.actor(),"food"); p.b.commands.clear(p.actor(),"food");
        p.a.commands.record(p.actor(),"food",p.food()); p.b.commands.record(p.actor(),"food",p.food());
        exchange(p); assertEquals(0,history(p.a,p.actor())); assertEquals(0,history(p.b,p.actor()));
        assertTrue(p.a.commands.record(p.actor(),"food",p.food())); exchange(p);
        assertEquals(1,history(p.a,p.actor())); assertEquals(visits(p.a,p.actor()),visits(p.b,p.actor()));
    }

    @Test void scopedClearAndRollbackKeepOtherTabAndAtomicOutbox() throws Exception {
        var p=pair(); p.a.commands.record(p.actor(),"food",p.food());
        p.a.commands.record(p.actor(),"restaurant",restaurant(p)); exchange(p);
        int before=events(p.a).size();
        assertThrows(IllegalStateException.class,()->p.a.db().transactions().required(()->{
            p.a.commands.clear(p.actor(),null); throw new IllegalStateException("rollback");
        }));
        assertEquals(2,history(p.a,p.actor())); assertEquals(before,events(p.a).size());
        p.a.commands.clear(p.actor(),"food"); exchange(p);
        assertEquals(1,history(p.a,p.actor())); assertEquals(visits(p.a,p.actor()),visits(p.b,p.actor()));
        assertEquals("restaurant",visits(p.b,p.actor()).getFirst().get("target_type"));
    }

    @Test void campusClearBeforeConsentDoesNotPublishAndGrantLaterPublishesFrontier() throws Exception {
        var p=pair();
        long local=p.a.db().runtime().queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('clear-local','synthetic') RETURNING id",Long.class);
        p.a.consent().onRegistered(local,false,RelationReplicationIT.POLICY);
        p.a.commands.record(local,"food",p.food()); p.a.commands.clear(local,null);
        assertTrue(events(p.a).isEmpty()); assertEquals(0,history(p.a,local));
        p.a.consent().grant(local,RelationReplicationIT.POLICY);
        var job=p.a.consent().claim(32,LEASE).stream().filter(j->j.userId()==local && j.kind().equals("UPSERT")).findFirst().orElseThrow();
        assertEquals("APPLIED",p.b.consent().apply(p.a.consent().export(job))); p.a.consent().complete(job);
        assertEquals(2,site(p.a.base).commands.runOnce()); exchange(p);
        assertTrue(events(p.a).stream().allMatch(e->e.eventType().equals(BrowseEvents.CLEAR)));
        assertEquals(0,history(p.b,local));
    }

    @Test void foodAndRestaurantVisitsConvergeInBothDirectionsWithOriginalTimeAndNoEcho() throws Exception {
        var p = pair();
        assertTrue(p.a.commands.record(p.actor(), "food", p.food()));
        assertTrue(p.b.commands.record(p.actor(), "restaurant", restaurant(p)));
        var first = events(p.a).getFirst(); exchange(p);
        assertEquals(2, history(p.a, p.actor())); assertEquals(visits(p.a, p.actor()), visits(p.b, p.actor()));
        receive(p.b, first); receive(p.b, first);
        assertEquals(2, history(p.b, p.actor()));
        assertFalse(p.b.commands.record(p.actor(), "food", p.food()));
        assertEquals(0, p.b.commands.runOnce()); assertEquals(1, events(p.a).size()); assertEquals(1, events(p.b).size());
    }
    @Test void synchronizedBehaviorChangesActualRecommendationOnBothSites() throws Exception {
        var p = pair(); long restaurant = restaurant(p);
        long candidate = p.a.db().runtime().queryForObject("INSERT INTO foods(account_id,restaurant_id,name,picture_url) VALUES(?,?,'matching',ARRAY['foods/synthetic.png']) RETURNING id", Long.class, p.owner(), restaurant);
        long popular = p.a.db().runtime().queryForObject("INSERT INTO foods(account_id,restaurant_id,name,likes_count,picture_url) VALUES(?,?,'popular',10,ARRAY['foods/popular.png']) RETURNING id", Long.class, p.owner(), restaurant);
        p.a.db().runtime().update("INSERT INTO food_taggings(account_id,food_id,tag_id) VALUES(?,?,?),(?,?,?)",
                p.owner(), p.food(), p.fixture.tag(), p.owner(), candidate, p.fixture.tag());
        for (long id : List.of(candidate, popular)) RelationReplicationIT.mirror(p.a.db(), p.b.db(), "foods", id);
        for (long id : p.a.db().runtime().queryForList("SELECT id FROM food_taggings WHERE account_id=?", Long.class, p.owner())) RelationReplicationIT.mirror(p.a.db(), p.b.db(), "food_taggings", id);
        assertEquals(List.of(popular, candidate), ranking(p.a, p.actor())); assertEquals(ranking(p.a, p.actor()), ranking(p.b, p.actor()));
        p.a.commands.record(p.actor(), "food", p.food()); exchange(p);
        assertEquals(List.of(candidate, popular), ranking(p.a, p.actor())); assertEquals(ranking(p.a, p.actor()), ranking(p.b, p.actor()));
        p.b.commands.record(p.actor(), "restaurant", restaurant); exchange(p);
        assertEquals(ranking(p.a, p.actor()), ranking(p.b, p.actor()));
        p.a.commands.clear(p.actor(),null); exchange(p);
        assertEquals(List.of(popular,candidate),ranking(p.a,p.actor()));
        assertEquals(ranking(p.a,p.actor()),ranking(p.b,p.actor()));
    }
    @Test void concurrentOfflineVisitsAndOutOfOrderReplayPreserveFactsWithoutIncreasingVisitWeight() throws Exception {
        var p = pair(); p.a.commands.record(p.actor(), "food", p.food()); p.b.commands.record(p.actor(), "food", p.food());
        assertEquals(1, history(p.a, p.actor())); assertEquals(1, history(p.b, p.actor()));
        receive(p.a, events(p.b).getFirst()); receive(p.b, events(p.a).getFirst());
        assertEquals(2, history(p.a, p.actor())); assertEquals(visits(p.a, p.actor()), visits(p.b, p.actor()));
        for (Site s : List.of(p.a, p.b)) assertEquals(1, s.db().runtime().queryForObject("SELECT count(*) FROM (SELECT target_type,target_id,MAX(created_at) FROM browse_history WHERE account_id=? GROUP BY target_type,target_id) t", Long.class, p.actor()));
    }
    @Test void legacyHistoryBackfillsInBoundedRestartablePassesAndKeepsOldTime() throws Exception {
        var p = pair();
        p.a.db().runtime().update("INSERT INTO browse_history(account_id,target_type,target_id,created_at) VALUES(?,'food',?,clock_timestamp()-interval '31 days'),(?,'food',?,clock_timestamp()-interval '32 days')", p.actor(), p.food(), p.actor(), p.food());
        var oneAtATime = new BrowseCommands(p.a.db().runtime(), p.a.db().transactions(),
                new OutboxService(p.a.db().runtime(), p.a.db().transactions(), CODEC, p.a.db().site()), p.a.consent(),
                p.a.db().site(), p.b.db().site().getId(), 1);
        assertEquals(1, oneAtATime.runOnce()); assertEquals(1, events(p.a).size());
        assertEquals(1, site(p.a.base).commands.runOnce()); assertEquals(0, site(p.a.base).commands.runOnce());
        exchange(p); assertEquals(visits(p.a, p.actor()), visits(p.b, p.actor()));
        assertTrue(BrowseEvents.parse(events(p.a).getFirst().payloadJson()).viewedAtMicros() < System.currentTimeMillis() * 1000 - Duration.ofDays(30).toMillis() * 1000);
        assertEquals(0, p.b.commands.runOnce());
    }
    @Test void missingTargetWaitsThenAppliesWhenTheCatalogDependencyArrives() throws Exception {
        var p = pair();
        long target = p.a.db().runtime().queryForObject("INSERT INTO foods(account_id,restaurant_id,name) VALUES(?,?,'late dependency') RETURNING id", Long.class, p.owner(), restaurant(p));
        p.a.commands.record(p.actor(), "food", target); receive(p.b, events(p.a).getFirst());
        assertEquals(0, history(p.b, p.actor()));
        RelationReplicationIT.mirror(p.a.db(), p.b.db(), "foods", target);
        p.b.inbox.requeueWaiting(100, Duration.ZERO); for (var lease : p.b.inbox.claim(100, LEASE)) p.b.inbox.process(lease);
        assertEquals(1, history(p.b, p.actor()));
    }
    @Test void noGrantKeepsLocalHistoryAndGrantLaterBackfillsIt() throws Exception {
        var p = pair();
        long local = p.a.db().runtime().queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('local-browser','synthetic') RETURNING id", Long.class);
        p.a.consent().onRegistered(local, false, RelationReplicationIT.POLICY);
        assertTrue(p.a.commands.record(local, "food", p.food())); assertEquals(1, history(p.a, local)); assertTrue(events(p.a).isEmpty());
        p.a.consent().grant(local, RelationReplicationIT.POLICY);
        var job = p.a.consent().claim(32, LEASE).stream().filter(j -> j.userId() == local && j.kind().equals("UPSERT")).findFirst().orElseThrow();
        assertEquals("APPLIED", p.b.consent().apply(p.a.consent().export(job))); p.a.consent().complete(job);
        assertEquals(1, p.a.commands.runOnce()); exchange(p); assertEquals(1, history(p.b, local));
    }
    @Test void ownerGrantIsRequiredAndWithdrawalBlocksQueuedObservation() throws Exception {
        var p = pair(); p.a.commands.record(p.actor(), "food", p.food()); var event = events(p.a).getFirst();
        p.a.consent().withdraw(p.owner());
        assertEquals(EventPolicy.OutboundDecision.POLICY_BLOCKED, p.a.policy.outbound(event));
        var job = p.a.consent().claim(32, LEASE).stream().filter(j -> j.userId() == p.owner() && j.kind().equals("PURGE")).findFirst().orElseThrow();
        p.b.consent().apply(p.a.consent().export(job));
        receive(p.b, event); assertEquals(0, history(p.b, p.actor()));
        assertTrue(p.a.commands.record(p.actor(), "restaurant", restaurant(p))); assertEquals(1, events(p.a).size());
    }
    @Test void normalWithdrawalClearsRemoteHistoryAndLateReplayCannotResurrectIt() throws Exception {
        var p = pair(); p.a.commands.record(p.actor(), "food", p.food()); exchange(p);
        p.a.commands.record(p.actor(), "restaurant", restaurant(p)); var event = events(p.a).getLast();
        p.a.consent().withdraw(p.actor());
        var job = p.a.consent().claim(32, LEASE).stream().filter(j -> j.userId() == p.actor() && j.kind().equals("PURGE")).findFirst().orElseThrow();
        assertEquals("APPLIED", p.b.consent().apply(p.a.consent().export(job))); p.a.consent().complete(job);
        assertEquals(0, history(p.b, p.actor())); assertEquals(2, history(p.a, p.actor()));
        assertEquals(0, p.b.db().runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.browse_visit", Long.class));
        receive(p.b, event); assertEquals(0, history(p.b, p.actor()));
        assertFalse(p.b.commands.record(p.actor(), "food", p.food()));
    }
    @Test void concurrentLocalRequestsDeduplicateAndRollbackDoesNotLeakOutbox() throws Exception {
        var p = pair(); var executor = Executors.newFixedThreadPool(4);
        try {
            var results = executor.invokeAll(java.util.stream.IntStream.range(0, 8)
                    .<Callable<Boolean>>mapToObj(i -> () -> p.a.commands.record(p.actor(), "food", p.food())).toList());
            int inserted = 0; for (var result : results) if (result.get()) inserted++;
            assertEquals(1, inserted); assertEquals(1, events(p.a).size()); assertEquals(1, history(p.a, p.actor()));
        } finally { executor.shutdownNow(); }
        assertThrows(IllegalStateException.class, () -> p.a.db().transactions().required(() -> {
            p.a.commands.record(p.actor(), "restaurant", restaurant(p)); throw new IllegalStateException("synthetic rollback");
        }));
        assertEquals(1, history(p.a, p.actor())); assertEquals(1, events(p.a).size());
    }

    @Test void expiredExternalConsentStopsCaptureAndDelivery() throws Exception {
        var p = pair(); p.b.commands.record(p.actor(), "food", p.food()); var event = events(p.b).getFirst();
        p.b.db().admin().update("UPDATE eatwhat_consent.subject SET valid_until=clock_timestamp()-interval '1 second' WHERE user_id=?", p.actor());
        assertEquals(EventPolicy.OutboundDecision.WAIT, p.b.policy.outbound(event));
        assertFalse(p.b.commands.record(p.actor(), "restaurant", restaurant(p)));
        assertEquals(0, p.b.commands.runOnce()); assertEquals(1, history(p.b, p.actor()));
    }

    @Test void reusedVisitIdentityCannotOverwriteHistory() throws Exception {
        var p = pair(); p.a.commands.record(p.actor(), "food", p.food()); var event = events(p.a).getFirst(); receive(p.b, event);
        var op = BrowseEvents.parse(event.payloadJson());
        var forged = new BrowseEvents.Observation(op.visitId(), op.actor(), op.owner(), op.targetType(), op.target(),
                op.actorEpoch(), op.ownerEpoch(), op.viewedAtMicros() + 1);
        UUID id = UUID.randomUUID();
        var changed = new SyncEvent(id, event.originSite(), event.originInstanceId(), event.aggregateType(), id, event.numericId() + 2,
                event.eventType(), 1, VersionVector.empty(), event.nextVersion(), event.dependencies(), event.actorId(),
                "browse:" + id, id, BrowseEvents.payload(forged));
        p.b.db().transactions().required(() -> {
            var handler = new BrowseEventHandler(p.b.db().runtime(), p.b.consent());
            assertTrue(handler.dependenciesSatisfied(changed));
            assertThrows(IllegalArgumentException.class, () -> handler.apply(changed)); return null;
        });
        assertEquals(visits(p.a, p.actor()), visits(p.b, p.actor()));
    }

    @Test void newConsentEpochReplaysTheSameObservationWithoutChangingTime() throws Exception {
        var p = pair(); p.a.commands.record(p.actor(), "food", p.food()); exchange(p);
        p.a.consent().withdraw(p.actor());
        var purge = p.a.consent().claim(32, LEASE).stream().filter(j -> j.userId() == p.actor() && j.kind().equals("PURGE")).findFirst().orElseThrow();
        p.b.consent().apply(p.a.consent().export(purge)); p.a.consent().complete(purge);
        p.a.consent().grant(p.actor(), RelationReplicationIT.POLICY);
        var grant = p.a.consent().claim(32, LEASE).stream().filter(j -> j.userId() == p.actor() && j.kind().equals("UPSERT")).findFirst().orElseThrow();
        assertEquals("APPLIED", p.b.consent().apply(p.a.consent().export(grant))); p.a.consent().complete(grant);
        long historyId = p.a.db().runtime().queryForObject("SELECT id FROM browse_history WHERE account_id=?", Long.class, p.actor());
        assertTrue(p.a.commands.publish(historyId)); assertFalse(p.a.commands.publish(historyId)); exchange(p);
        assertEquals(1, history(p.b, p.actor())); assertEquals(visits(p.a, p.actor()), visits(p.b, p.actor()));
        var events = events(p.a);
        assertEquals(BrowseEvents.parse(events.getFirst().payloadJson()).visitId(), BrowseEvents.parse(events.getLast().payloadJson()).visitId());
        receive(p.b, events.getFirst()); assertEquals(1, history(p.b, p.actor()));
    }

    @Test void clearFrontierSurvivesWithdrawalAndIsRepublishedUnderTheNewConsentEpoch() throws Exception {
        var p=pair();p.a.commands.record(p.actor(),"food",p.food());var old=events(p.a).getFirst();exchange(p);
        p.a.commands.clear(p.actor(),null);exchange(p);
        p.a.consent().withdraw(p.actor());
        var purge=p.a.consent().claim(32,LEASE).stream().filter(j->j.userId()==p.actor() && j.kind().equals("PURGE")).findFirst().orElseThrow();
        assertEquals("APPLIED",p.b.consent().apply(p.a.consent().export(purge)));p.a.consent().complete(purge);
        p.a.consent().grant(p.actor(),RelationReplicationIT.POLICY);
        var grant=p.a.consent().claim(32,LEASE).stream().filter(j->j.userId()==p.actor() && j.kind().equals("UPSERT")).findFirst().orElseThrow();
        assertEquals("APPLIED",p.b.consent().apply(p.a.consent().export(grant)));p.a.consent().complete(grant);
        assertEquals(2,site(p.a.base).commands.runOnce());exchange(p);
        receive(p.b,old);assertEquals(0,history(p.b,p.actor()));
        assertTrue(p.b.commands.record(p.actor(),"food",p.food()));exchange(p);
        assertEquals(1,history(p.a,p.actor()));assertEquals(visits(p.a,p.actor()),visits(p.b,p.actor()));
    }

    @Test void aDeletedCatalogTargetDiscardsItsLateObservationWithoutRecreatingAnything() throws Exception {
        var p = pair(); p.a.commands.record(p.actor(), "food", p.food()); var event = events(p.a).getFirst();
        var op = BrowseEvents.parse(event.payloadJson());
        p.b.db().runtime().update("DELETE FROM foods WHERE id=?", p.food());
        p.b.db().runtime().update("INSERT INTO eatwhat_sync.catalog_object(kind,object_gid,numeric_id,origin_site,authority_site,author_gid,tombstone) VALUES('FOOD',?,?,?,?,?,true)",
                op.target(), p.food(), p.a.db().site().getId(), p.a.db().site().getId(), op.owner());
        receive(p.b, event);
        assertEquals(0, history(p.b, p.actor()));
        assertEquals("APPLIED", p.b.db().runtime().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, event.eventId()));
        assertEquals(0, p.b.db().runtime().queryForObject("SELECT count(*) FROM foods WHERE id=?", Long.class, p.food()));
    }

    @Test void anOlderConsentPolicyDoesNotSilentlyAuthorizeTheExpandedBrowsingScope() throws Exception {
        var p = pair();
        var newConsent = new ConsentStore(p.a.db().runtime(), p.a.db().transactions(), p.a.db().site(), "cross-border-browse-v2",
                Duration.ofMinutes(5), Duration.ofMinutes(2), p.b.db().site().getId(), p.b.db().site().getInstanceId());
        var commands = new BrowseCommands(p.a.db().runtime(), p.a.db().transactions(),
                new OutboxService(p.a.db().runtime(), p.a.db().transactions(), CODEC, p.a.db().site()), newConsent,
                p.a.db().site(), p.b.db().site().getId(), 32);
        assertTrue(commands.record(p.actor(), "food", p.food()));
        assertEquals(0, commands.runOnce()); assertTrue(events(p.a).isEmpty());
        assertEquals(RelationReplicationIT.POLICY, newConsent.get(p.actor()).policyVersion());
        assertEquals(1, history(p.a, p.actor())); assertEquals(0, history(p.b, p.actor()));
    }
}
