package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.account.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.*;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.notification.NotificationReadCommands;
import you.v50to.eatwhat.sync.notification.NotificationReadEvents;
import you.v50to.eatwhat.sync.notification.NotificationReadStore;
import you.v50to.eatwhat.sync.notification.NotificationReadWorker;
import you.v50to.eatwhat.sync.relation.*;
import you.v50to.eatwhat.sync.transport.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Two real databases and restricted application roles; directory parents are trusted bootstrap fixtures only. */
class RelationReplicationIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("relations");
    static final String POLICY="relation-test-policy";
    static final Duration LEASE=Duration.ofSeconds(20);
    static final EventCodec CODEC=new EventCodec();
    @BeforeAll static void start(){PG.start();}
    @AfterAll static void stop(){PG.stop();}
    record Site(EventFoundationTestSupport.Database db,ConsentStore consent,RelationStore store,RelationCommands commands,
                InboxService inbox,DeliveryService delivery,String peerSiteId) { }
    record Pair(Site a,Site b,long actor,long owner,long food,long activityFood,long dinner,long post,long tag) {
        long target(RelationKind k){return switch(k){case follow->owner;case activity_food_like->activityFood;case activity_dinner_like->dinner;case activity_post_like->post;default->food;};}
        Long tag(RelationKind k){return k==RelationKind.tagging?tag:null;}
    }
    static Site site(EventFoundationTestSupport.Database db,EventFoundationTestSupport.Database peer) {
        var consent=new ConsentStore(db.runtime(),db.transactions(),db.site(),POLICY,Duration.ofMinutes(5),Duration.ofMinutes(2),peer.site().getId(),peer.site().getInstanceId());
        var store=new RelationStore(db.runtime());var causal=new RelationCausalStore(db.runtime());
        var outbox=new OutboxService(db.runtime(),db.transactions(),CODEC,db.site());
        var handlers=List.<EventHandler>of(
                new RelationEventHandler(db.runtime(),store,causal,consent),
                new RelationCausalEventHandler(RelationCausalEvents.ACTION,db.runtime(),store,causal,consent),
                new RelationCausalEventHandler(RelationCausalEvents.BRIDGE,db.runtime(),store,causal,consent));
        return new Site(db,consent,store,new RelationCommands(db.runtime(),db.transactions(),store,causal,outbox,new EntityIdentityService(db.runtime()),consent,db.site(),peer.site().getId()),
                new InboxService(db.runtime(),db.transactions(),CODEC,new EventHandlerRegistry(handlers)),
                new DeliveryService(db.runtime(),db.transactions(),CODEC),peer.site().getId());
    }
    static Pair pair() throws Exception {
        return pair(PG);
    }
    static Pair pair(GenericContainer<?> postgres) throws Exception {
        var ap=new SiteProperties();ap.setMode(SiteProperties.Mode.CAMPUS);ap.setId("relation-campus");ap.setInstanceId(UUID.randomUUID());
        var bp=new SiteProperties();bp.setMode(SiteProperties.Mode.EXTERNAL);bp.setId("relation-external");bp.setInstanceId(UUID.randomUUID());
        var ad=EventFoundationTestSupport.createDatabase(postgres,ap);var bd=EventFoundationTestSupport.createDatabase(postgres,bp);
        var a=site(ad,bd);var b=site(bd,ad);
        long actor=user(a,b,"actor"),owner=user(a,b,"owner");
        long restaurant=ad.runtime().queryForObject("INSERT INTO restaurant(name,location,gcj_lng,gcj_lat,account_id) VALUES('fixture',ST_SetSRID(ST_Point(117,36),4326),117,36,?) RETURNING id",Long.class,owner);
        long food=ad.runtime().queryForObject("INSERT INTO foods(account_id,restaurant_id,name) VALUES(?,?,'fixture food') RETURNING id",Long.class,owner,restaurant);
        long tag=ad.runtime().queryForObject("INSERT INTO food_tags(name,normalized_name,tag_type) VALUES('fixture tag','fixture tag','system') RETURNING id",Long.class);
        long activity=ad.runtime().queryForObject("INSERT INTO activity_foods(account_id,food_name,picture_url) VALUES(?,'fixture',ARRAY['activity/test.png']) RETURNING id",Long.class,owner);
        long dinner=ad.runtime().queryForObject("INSERT INTO activity_dinners(account_id,picture_url) VALUES(?,ARRAY['activity/test.png']) RETURNING id",Long.class,owner);
        long definition=ad.runtime().queryForObject("INSERT INTO activities(code,name,status) VALUES('fixture','fixture','ONLINE') RETURNING id",Long.class);
        long post=ad.runtime().queryForObject("INSERT INTO activity_posts(activity_id,account_id,picture_url) VALUES(?,?,ARRAY['activity/test.png']) RETURNING id",Long.class,definition,owner);
        for(var entry:Map.of("restaurant",restaurant,"foods",food,"food_tags",tag,"activity_foods",activity,"activity_dinners",dinner).entrySet()) mirror(ad,bd,entry.getKey(),entry.getValue());
        // The post depends on its activity definition, so the definition is mirrored first.
        mirror(ad,bd,"activities",definition);mirror(ad,bd,"activity_posts",post);
        return new Pair(a,b,actor,owner,food,activity,dinner,post,tag);
    }
    static void extendGrantedConsentForSlowSyntheticTest(Pair pair) {
        // Testcontainers runs amd64 PostgreSQL under emulation on arm64; keep this causal-order
        // test's unrelated five-minute account lease from expiring during slow fixture setup.
        for (Site site : List.of(pair.a, pair.b)) {
            site.db.admin().update("""
                    UPDATE eatwhat_consent.subject
                    SET valid_until=clock_timestamp()+interval '1 hour'
                    WHERE user_id IN (?,?) AND status='GRANTED'
                    """, pair.actor, pair.owner);
            assertTrue(site.consent.replicationAllowed(pair.actor),
                    "The synthetic actor consent must remain valid during causal-order testing");
            assertTrue(site.consent.replicationAllowed(pair.owner),
                    "The synthetic owner consent must remain valid during causal-order testing");
        }
    }
    static long user(Site a,Site b,String name) {
        long id=a.db.runtime().queryForObject("INSERT INTO users(nick_name,password_hash) VALUES(?,'synthetic-only') RETURNING id",Long.class,name);
        a.db.runtime().update("INSERT INTO privacy(account_id) VALUES(?)",id);
        a.consent.onRegistered(id,true,POLICY);
        var job=a.consent.claim(32,LEASE).stream().filter(j->j.userId()==id && j.kind().equals("UPSERT")).max(Comparator.comparingLong(ConsentModels.Job::epoch)).orElseThrow();
        assertEquals("APPLIED",b.consent.apply(a.consent.export(job)));a.consent.complete(job);return id;
    }
    static void mirror(EventFoundationTestSupport.Database from,EventFoundationTestSupport.Database to,String table,long id) throws Exception {
        String row=from.admin().queryForObject("SELECT row_to_json(t)::text FROM public."+table+" t WHERE id=?",String.class,id);
        String identity=from.admin().queryForObject("SELECT row_to_json(i)::text FROM eatwhat_identity.entity_identity i WHERE entity_type=? AND numeric_id=?",String.class,table,id);
        try(var c=to.admin().getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try(var s=c.createStatement()){s.execute("SET LOCAL session_replication_role='replica'");}
            try(var s=c.prepareStatement("INSERT INTO public."+table+" SELECT * FROM json_populate_record(NULL::public."+table+",?::json)")){s.setString(1,row);s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO eatwhat_identity.entity_identity SELECT * FROM json_populate_record(NULL::eatwhat_identity.entity_identity,?::json)")){s.setString(1,identity);s.executeUpdate();}
            c.commit();
        }
    }
    static List<SyncEvent> events(Site site) {
        return site.db.runtime().query("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type=? ORDER BY local_order",(r,n)->CODEC.decode(r.getString(1)),RelationEvents.AGGREGATE);
    }
    static long actorEpoch(SyncEvent event) {
        if (RelationCausalEvents.ACTION.equals(event.eventType())) {
            return RelationCausalEvents.parseAction(event.payloadJson()).actorEpoch();
        }
        if (RelationCausalEvents.BRIDGE.equals(event.eventType())) {
            return RelationCausalEvents.parseBridge(event.payloadJson()).actorEpoch();
        }
        return RelationEvents.parse(event.payloadJson()).actorEpoch();
    }
    static RelationEvents.Operation v1Evidence(SyncEvent event) {
        if (RelationCausalEvents.ACTION.equals(event.eventType())) {
            return RelationCausalEvents.parseAction(event.payloadJson()).v1Evidence();
        }
        if (RelationCausalEvents.BRIDGE.equals(event.eventType())) {
            throw new IllegalArgumentException("A pure causal bridge is not a V1 relation operation");
        }
        return RelationEvents.parse(event.payloadJson());
    }
    static SyncEvent legacyV1Wire(SyncEvent source) {
        RelationEvents.Operation operation = v1Evidence(source);
        return new SyncEvent(source.eventId(), source.originSite(), source.originInstanceId(),
                RelationEvents.AGGREGATE, source.eventId(), source.numericId(), RelationEvents.EVENT, 1,
                VersionVector.empty(), VersionVector.empty().increment(source.originSite()),
                operation.dependencies(), operation.actor(), "relation:" + source.eventId(), source.eventId(),
                RelationEvents.payload(operation));
    }
    static SyncEvent legacyV1Wire(Site origin, RelationEvents.Operation operation) {
        UUID eventId = UUID.randomUUID();
        String originSite = origin.db.site().getId();
        long numericId = origin.db.admin().queryForObject("SELECT eatwhat_sync.next_relation_number()", Long.class);
        return new SyncEvent(eventId, originSite, origin.db.site().getInstanceId(),
                RelationEvents.AGGREGATE, eventId, numericId, RelationEvents.EVENT, 1,
                VersionVector.empty(), VersionVector.empty().increment(originSite),
                operation.dependencies(), operation.actor(), "relation:" + eventId, eventId,
                RelationEvents.payload(operation));
    }
    static RelationEvents.Notice notification(SyncEvent event) {
        if (RelationCausalEvents.ACTION.equals(event.eventType())) {
            return RelationCausalEvents.parseAction(event.payloadJson()).v1Evidence().notification();
        }
        if (RelationCausalEvents.BRIDGE.equals(event.eventType())) return null;
        return RelationEvents.parse(event.payloadJson()).notification();
    }
    static Boolean adding(SyncEvent event) {
        if (RelationCausalEvents.ACTION.equals(event.eventType())) {
            return RelationCausalEvents.parseAction(event.payloadJson()).adding();
        }
        if (RelationCausalEvents.BRIDGE.equals(event.eventType())) return null;
        return RelationEvents.parse(event.payloadJson()).adding();
    }
    static void receive(Site to,SyncEvent event) {
        new RelationEvents().validate(event,new NodeIdentity(event.originSite(),event.originInstanceId()));to.inbox.receive(event,event.originSite());
    }
    static void process(Site site) {for(var lease:site.inbox.claim(100,LEASE))site.inbox.process(lease);}
    static void exchange(Pair p) {
        for(Site from:List.of(p.a,p.b)) {
            Site to=from==p.a?p.b:p.a;
            for(var lease:from.delivery.claimForAggregates(to.db.site().getId(),100,LEASE,Set.of(RelationEvents.AGGREGATE))) {
                receive(to,lease.event());assertTrue(from.delivery.acknowledge(lease,"DURABLY_QUEUED"));
            }
        }
        process(p.a);process(p.b);
        for(Site s:List.of(p.a,p.b)){s.inbox.requeueWaiting(100,Duration.ZERO);process(s);}
    }
    static boolean present(Pair p,Site site,RelationKind kind){return site.store.present(kind,p.actor,p.target(kind),p.tag(kind));}
    static long count(Site s,String sql,Object...args){return s.db.runtime().queryForObject(sql,Long.class,args);}
    static String relationDiagnostics(Site site, Pair pair) {
        var facts = site.db.runtime().queryForList("""
                SELECT event_id::text,relation_id::text,dot_site,dot_counter,context::text,adding,payload::text
                FROM eatwhat_sync.relation_operation ORDER BY relation_id,dot_site,dot_counter
                """);
        var states = site.db.runtime().queryForList("""
                SELECT relation_id::text,vector::text FROM eatwhat_sync.relation_state ORDER BY relation_id
                """);
        var intents = site.db.runtime().queryForList("""
                SELECT natural_id::text,kind,desired_present,intent_revision,published_revision,
                       published_actor_epoch,published_owner_epoch,row_id,row_gid::text,local_intent,blocked_reason
                FROM eatwhat_sync.relation_intent WHERE kind='follow'
                  AND actor_gid=(SELECT global_id FROM eatwhat_identity.entity_identity
                                  WHERE entity_type='users' AND numeric_id=?)
                  AND target_gid=(SELECT global_id FROM eatwhat_identity.entity_identity
                                   WHERE entity_type='users' AND numeric_id=?)
                ORDER BY natural_id
                """, pair.actor, pair.owner);
        return "site=" + site.db.site().getId() + " facts=" + facts + " states=" + states + " intents=" + intents;
    }
    static void change(Pair p,Site s,RelationKind k,boolean add){s.commands.change(k,p.actor,p.target(k),p.tag(k),add);}
    static boolean changeResult(Pair p,Site s,RelationKind k,boolean add){return s.commands.change(k,p.actor,p.target(k),p.tag(k),add);}

    static RelationBackfillStore backfillStore(Site site) {
        var outbox = new OutboxService(site.db.runtime(), site.db.transactions(), CODEC, site.db.site());
        return new RelationBackfillStore(site.db.runtime(), site.db.transactions(), site.store, outbox,
                new EntityIdentityService(site.db.runtime()), site.consent, site.db.site(), site.peerSiteId);
    }

    static void drainBackfill(Site site, long userId, long epoch) {
        var store = backfillStore(site);
        var worker = new RelationBackfillWorker(store, LEASE, 16);
        for (int pass = 0; pass < 128; pass++) {
            store.enqueueReadySubjects();
            if (count(site, "SELECT count(*) FROM eatwhat_sync.relation_backfill_job WHERE user_id=? AND epoch=? AND status='DONE'",
                    userId, epoch) > 0) return;
            worker.runOnce();
        }
        fail("Backfill did not finish for user=" + userId + " epoch=" + epoch + ": "
                + site.db.runtime().queryForList("SELECT user_id,epoch,status,phase,row_cursor,history_cursor,last_error "
                        + "FROM eatwhat_sync.relation_backfill_job WHERE user_id=? ORDER BY epoch", userId));
    }

    @ParameterizedTest @EnumSource(RelationKind.class)
    void concurrentAddRemoveConvergesAndObservedReAddRestoresWithSameLocalIdentity(RelationKind kind) throws Exception {
        Pair p=pair();
        if(kind==RelationKind.tagging){
            // A missing tagging is a legacy API error/no-op. Establish a real row before removing concurrently.
            assertFalse(changeResult(p,p.a,kind,false));
            assertTrue(events(p.a).isEmpty(),"Removing an absent tag must not create a tombstone or Outbox event");
            change(p,p.a,kind,true);exchange(p);
            change(p,p.a,kind,false);change(p,p.a,kind,true);change(p,p.b,kind,false);
        } else {
            change(p,p.a,kind,true);change(p,p.b,kind,false); // disconnected, neither observed the other
        }
        var original=v1Evidence(events(p.a).getFirst());
        exchange(p);assertFalse(present(p,p.a,kind));assertFalse(present(p,p.b,kind));
        change(p,p.b,kind,true);exchange(p);assertTrue(present(p,p.a,kind));assertTrue(present(p,p.b,kind));
        long row=count(p.a,"SELECT id FROM "+kind.table+" WHERE account_id=?",p.actor);
        assertEquals(original.rowId(),row,"Existing numeric IDs never change when a relation is restored");
        for(Site s:List.of(p.a,p.b))assertEquals(0,count(s,"SELECT count(*) FROM eatwhat_sync.conflict"));
        if(kind==RelationKind.food_like)for(Site s:List.of(p.a,p.b))assertEquals(1,count(s,"SELECT likes_count FROM foods WHERE id=?",p.food));
    }
    @ParameterizedTest @EnumSource(RelationKind.class)
    void concurrentAddsWithDifferentSourceIdsAreAliasedAndNeverDoubleCounted(RelationKind kind) throws Exception {
        Pair p=pair();change(p,p.a,kind,true);change(p,p.b,kind,true);
        var a=v1Evidence(events(p.a).getFirst());var b=v1Evidence(events(p.b).getFirst());
        assertNotEquals(a.rowId(),b.rowId());assertNotEquals(a.rowGid(),b.rowGid());
        exchange(p);
        for(Site s:List.of(p.a,p.b)) {
            assertTrue(present(p,s,kind));assertEquals(2,count(s,"SELECT count(*) FROM eatwhat_sync.relation_alias WHERE entity_type=?",kind.table));
            assertEquals(1,count(s,"SELECT count(*) FROM "+kind.table+" WHERE account_id=?",p.actor));
            if(kind==RelationKind.food_like)assertEquals(1,count(s,"SELECT likes_count FROM foods WHERE id=?",p.food));
            if(kind==RelationKind.activity_food_like)assertEquals(1,count(s,"SELECT likes_count FROM activity_foods WHERE id=?",p.activityFood));
            if(kind==RelationKind.activity_post_like)assertEquals(1,count(s,"SELECT likes_count FROM activity_posts WHERE id=?",p.post));
        }
    }
    @Test void localActionUsesItsExistingProjectionAliasAfterReceivingPeerHistory() throws Exception {
        Pair p = pair();
        RelationStore.Projection local = tx(p.a, () -> p.a.store.project(RelationKind.follow,
                p.actor, p.owner, null, true, null, p.a.db.site().getId()));
        assertTrue(present(p, p.a, RelationKind.follow));

        change(p, p.b, RelationKind.follow, true);
        SyncEvent peerAction = events(p.b).getFirst();
        RelationEvents.Operation peerEvidence = v1Evidence(peerAction);
        assertNotEquals(local.rowId(), peerEvidence.rowId());
        receive(p.a, peerAction);
        process(p.a);

        assertTrue(changeResult(p, p.a, RelationKind.follow, false),
                "A local edit must use its pre-existing projection alias after accepting the peer history");
        SyncEvent localAction = events(p.a).getLast();
        RelationEvents.Operation localEvidence = v1Evidence(localAction);
        assertFalse(localEvidence.adding());
        assertEquals(local.rowId(), localEvidence.rowId());
        assertEquals(local.rowGid(), localEvidence.rowGid());
        assertEquals(0, count(p.a,
                "SELECT count(*) FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner));
        assertEquals(local.rowGid().toString(), p.a.db.runtime().queryForObject("""
                SELECT global_id::text FROM eatwhat_identity.entity_identity
                WHERE entity_type='follow' AND numeric_id=?
                """, String.class, local.rowId()), "The local alias identity survives a public REMOVE");
        UUID naturalId = RelationStore.naturalId(localEvidence.kind(), localEvidence.actor(),
                localEvidence.target(), localEvidence.owner(), localEvidence.tag());
        assertEquals(peerEvidence.rowId(), new RelationCausalStore(p.a.db.runtime()).load(naturalId).identity().rowId(),
                "Causal state retains the peer alias while the local REMOVE uses its existing local projection alias");
        receive(p.b, localAction);
        process(p.b);
        assertFalse(present(p, p.a, RelationKind.follow));
        assertFalse(present(p, p.b, RelationKind.follow));
        for (Site site : List.of(p.a, p.b)) {
            assertEquals(0, count(site, "SELECT count(*) FROM follow WHERE account_id=? AND target_id=?",
                    p.actor, p.owner));
        }
    }
    @Test void manyActionsKeepTheEpochIndependentCausalFrontierBounded() throws Exception {
        Pair p = pair();
        List<SyncEvent> expectedActions = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            boolean adding = index % 2 == 0;
            assertTrue(changeResult(p, p.a, RelationKind.favorite, adding));
            expectedActions.add(events(p.a).getLast());
        }

        UUID naturalId = RelationStore.naturalId(RelationKind.favorite,
                globalId(p.a, "users", p.actor), globalId(p.a, "foods", p.food),
                globalId(p.a, "users", p.owner), null);
        RelationCausalStore.State sourceState = causalState(p.a, new RelationCausalStore(p.a.db.runtime()), naturalId);
        assertEquals(32, sourceState.seen().getOrDefault(
                RelationCausalEvents.nativeStream(p.a.db.site().getId()), 0L));
        assertEquals(1, sourceState.adds().size(), "Only the latest ADD witness per stream is retained");
        assertEquals(1, sourceState.removes().size(), "Only the latest REMOVE witness per stream is retained");
        String compactBridge = RelationCausalEvents.payload(
                new RelationCausalStore(p.a.db.runtime()).bridge(sourceState));
        assertTrue(compactBridge.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 8 * 1024,
                "Many logical actions do not grow the state bridge with the event history");
        assertEquals(32, count(p.a,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", naturalId),
                "Immutable history keeps every real user action even while the wire frontier stays compact");

        exchange(p);
        assertEquals(expectedActions.size(), expectedActions.stream()
                .filter(event -> count(p.b,
                        "SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=? AND status='APPLIED'",
                        event.eventId()) == 1).count());
        RelationCausalStore.State peerState = causalState(p.b, new RelationCausalStore(p.b.db.runtime()), naturalId);
        assertEquals(sourceState.seen(), peerState.seen());
        assertEquals(new HashSet<>(sourceState.adds()), new HashSet<>(peerState.adds()));
        assertEquals(new HashSet<>(sourceState.removes()), new HashSet<>(peerState.removes()));
        assertFalse(present(p, p.a, RelationKind.favorite));
        assertFalse(present(p, p.b, RelationKind.favorite));
        assertEquals(0, count(p.a, "SELECT count(*) FROM notifications"));
        assertEquals(0, count(p.b, "SELECT count(*) FROM notifications"));
    }

    @Test void relationFrontierRemainsBoundedAcrossTwentyFourConsentReauthorizations() throws Exception {
        Pair p = pair();
        change(p, p.a, RelationKind.follow, true);
        exchange(p);

        UUID naturalId = RelationStore.naturalId(RelationKind.follow,
                globalId(p.a, "users", p.actor), globalId(p.a, "users", p.owner),
                globalId(p.a, "users", p.owner), null);
        RelationCausalStore aStore = new RelationCausalStore(p.a.db.runtime());
        RelationCausalStore bStore = new RelationCausalStore(p.b.db.runtime());
        RelationCausalStore.State initialA = causalState(p.a, aStore, naturalId);
        RelationCausalStore.State initialB = causalState(p.b, bStore, naturalId);
        assertEquals(initialA.seen(), initialB.seen());
        assertEquals(new HashSet<>(initialA.adds()), new HashSet<>(initialB.adds()));
        assertEquals(new HashSet<>(initialA.removes()), new HashSet<>(initialB.removes()));
        List<Map<String, Object>> initialFactsA = causalFacts(p.a, naturalId);
        List<Map<String, Object>> initialFactsB = causalFacts(p.b, naturalId);
        List<Map<String, Object>> initialV1A = relationV1Facts(p.a, naturalId);
        List<Map<String, Object>> initialV1B = relationV1Facts(p.b, naturalId);
        long originalId = initialA.identity().rowId();
        UUID originalGid = initialA.identity().rowGid();
        long campusNotices = count(p.a, "SELECT count(*) FROM notifications");
        Set<String> originalStreams = initialA.seen().keySet();
        assertEquals(1, originalStreams.size(), "Only the site that performed the action has a native stream");
        assertTrue(originalStreams.contains(RelationCausalEvents.nativeStream(p.a.db.site().getId())));
        assertFalse(originalStreams.contains(RelationCausalEvents.nativeStream(p.b.db.site().getId())));

        long expectedActorEpoch = p.a.consent.get(p.actor).epoch();
        for (int cycle = 0; cycle < 24; cycle++) {
            p.a.consent.withdraw(p.actor);
            var purge = p.a.consent.claim(32, LEASE).stream()
                    .filter(job -> job.userId() == p.actor && job.kind().equals("PURGE"))
                    .findFirst().orElseThrow();
            assertEquals("APPLIED", p.b.consent.apply(p.a.consent.export(purge)));
            p.a.consent.complete(purge);
            assertFalse(present(p, p.b, RelationKind.follow), "Withdrawal clears the peer projection");

            var grant = p.a.consent.grant(p.actor, POLICY);
            expectedActorEpoch = grant.epoch();
            var upsert = p.a.consent.claim(32, LEASE).stream()
                    .filter(job -> job.userId() == p.actor && job.epoch() == grant.epoch()
                            && job.kind().equals("UPSERT"))
                    .findFirst().orElseThrow();
            assertEquals("APPLIED", p.b.consent.apply(p.a.consent.export(upsert)));
            p.a.consent.complete(upsert);

            // Both sites perform the real relation backfill/bridge path before exchanging it.
            drainBackfill(p.a, p.actor, grant.epoch());
            drainBackfill(p.b, p.actor, grant.epoch());
            exchange(p);

            RelationCausalStore.State currentA = causalState(p.a, aStore, naturalId);
            RelationCausalStore.State currentB = causalState(p.b, bStore, naturalId);
            assertEquals(originalStreams, currentA.seen().keySet(), "A regrant cannot add an epoch-specific stream");
            assertEquals(originalStreams, currentB.seen().keySet(), "B regrant cannot add an epoch-specific stream");
            assertEquals(initialA.seen(), currentA.seen(), "Pure state repair does not allocate a new dot");
            assertEquals(initialA.adds(), currentA.adds());
            assertEquals(initialA.removes(), currentA.removes());
            assertEquals(initialA.seen(), currentB.seen());
            assertEquals(new HashSet<>(initialA.adds()), new HashSet<>(currentB.adds()));
            assertEquals(new HashSet<>(initialA.removes()), new HashSet<>(currentB.removes()));
            assertEquals(initialFactsA, causalFacts(p.a, naturalId), "Backfill adds no logical relation fact");
            assertEquals(initialFactsB, causalFacts(p.b, naturalId));
            assertEquals(initialV1A, relationV1Facts(p.a, naturalId), "V1 identity/notification evidence is retained");
            assertEquals(initialV1B, relationV1Facts(p.b, naturalId));
            assertTrue(present(p, p.a, RelationKind.follow));
            assertTrue(present(p, p.b, RelationKind.follow));
            assertEquals(originalId, currentA.identity().rowId());
            assertEquals(originalId, currentB.identity().rowId());
            assertEquals(originalGid, currentA.identity().rowGid());
            assertEquals(originalGid, currentB.identity().rowGid());
            assertEquals(1, count(p.a, "SELECT count(*) FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner));
            assertEquals(1, count(p.b, "SELECT count(*) FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner));
            assertEquals(campusNotices, count(p.a, "SELECT count(*) FROM notifications"));
            assertEquals(0, count(p.b, "SELECT count(*) FROM notifications"),
                    "Withdrawal purges the peer notice; historical backfill never recreates it");
        }
        assertEquals(49, expectedActorEpoch, "Registration plus 24 withdraw/regrant cycles advances the epoch safely");
    }

    @Test void explicitRemoveAfterPurgeUsesRetainedLogicalPresenceBeforeBackfill() throws Exception {
        Pair p = pair();
        change(p, p.b, RelationKind.favorite, true);
        exchange(p);
        change(p, p.b, RelationKind.favorite, false);
        exchange(p);
        change(p, p.b, RelationKind.favorite, true);
        exchange(p);
        long originalId = count(p.b, "SELECT id FROM food_favorites WHERE account_id=? AND food_id=?",
                p.actor, p.food);
        UUID naturalId = RelationStore.naturalId(RelationKind.favorite,
                globalId(p.b, "users", p.actor), globalId(p.b, "foods", p.food),
                globalId(p.b, "users", p.owner), null);
        assertEquals(3, count(p.b,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", naturalId));

        p.a.consent.withdraw(p.actor);
        var purge = p.a.consent.claim(32, LEASE).stream()
                .filter(job -> job.userId() == p.actor && job.kind().equals("PURGE"))
                .findFirst().orElseThrow();
        assertEquals("APPLIED", p.b.consent.apply(p.a.consent.export(purge)));
        p.a.consent.complete(purge);
        assertFalse(present(p, p.b, RelationKind.favorite));
        assertTrue(causalState(p.b, new RelationCausalStore(p.b.db.runtime()), naturalId).present(),
                "Purge removes the peer projection but retains the logical ADD3 after REMOVE2");

        var grant = p.a.consent.grant(p.actor, POLICY);
        var grantJob = p.a.consent.claim(32, LEASE).stream()
                .filter(job -> job.userId() == p.actor && job.epoch() == grant.epoch()
                        && job.kind().equals("UPSERT"))
                .findFirst().orElseThrow();
        assertEquals("APPLIED", p.b.consent.apply(p.a.consent.export(grantJob)));
        p.a.consent.complete(grantJob);
        assertEquals(0, count(p.b,
                "SELECT count(*) FROM eatwhat_sync.relation_backfill_job WHERE user_id=? AND epoch=? AND status='DONE'",
                p.actor, grant.epoch()), "The explicit user command runs before historical backfill");

        assertFalse(changeResult(p, p.b, RelationKind.favorite, false),
                "The absent public row preserves the numeric API's repeated-remove result");
        SyncEvent removeFour = events(p.b).stream()
                .filter(event -> RelationCausalEvents.ACTION.equals(event.eventType())
                        && actorEpoch(event) == grant.epoch())
                .findFirst().orElseThrow();
        RelationCausalEvents.Action action = RelationCausalEvents.parseAction(removeFour.payloadJson());
        assertFalse(action.adding(), "The command must record REMOVE4 rather than restore the stale ADD3");
        assertEquals(4, action.counter(), "The post-purge REMOVE follows this site's ADD1/REMOVE2/ADD3 stream");
        assertTrue(action.context().getOrDefault(RelationCausalEvents.nativeStream(p.b.db.site().getId()), 0L) >= 3,
                "REMOVE4 carries the retained ADD3 context without waiting for an older event");
        assertEquals(originalId, action.rowId());
        assertNull(notification(removeFour));
        receive(p.a, removeFour);
        process(p.a);
        assertFalse(present(p, p.a, RelationKind.favorite));
        assertFalse(present(p, p.b, RelationKind.favorite));
        assertEquals(0, count(p.a, "SELECT count(*) FROM food_favorites WHERE account_id=? AND food_id=?",
                p.actor, p.food));
        assertEquals(0, count(p.b, "SELECT count(*) FROM food_favorites WHERE account_id=? AND food_id=?",
                p.actor, p.food));
    }

    @Test void oversizedCompleteActionIsRejectedWithoutChangingAnyLocalRelationState() throws Exception {
        Pair p = pair();
        change(p, p.a, RelationKind.follow, true);
        SyncEvent initial = events(p.a).getFirst();
        RelationEvents.Operation evidence = v1Evidence(initial);
        UUID naturalId = RelationStore.naturalId(evidence.kind(), evidence.actor(), evidence.target(),
                evidence.owner(), evidence.tag());
        RelationCausalStore store = new RelationCausalStore(p.a.db.runtime());
        RelationCausalStore.State initialState = causalState(p.a, store, naturalId);
        Map<String, Long> seen = new TreeMap<>();
        List<RelationCausalEvents.Dot> adds = new ArrayList<>();
        List<RelationCausalEvents.Dot> removes = new ArrayList<>();
        String localStream = RelationCausalEvents.nativeStream(p.a.db.site().getId());
        String peerStream = RelationCausalEvents.nativeStream(p.b.db.site().getId());
        RelationCausalEvents.Dot originalLocalAdd = initialState.adds().stream()
                .filter(dot -> dot.stream().equals(localStream)).findFirst().orElseThrow();
        seen.put(localStream, 1L);
        seen.put(peerStream, 1L);
        adds.add(originalLocalAdd);
        removes.add(new RelationCausalEvents.Dot(peerStream, 1, UUID.randomUUID(), Map.of()));
        String legacyOrigin = "legacy-old-site";
        for (int index = 0; index < 199; index++) {
            String stream = RelationCausalEvents.legacyStream(UUID.randomUUID(), legacyOrigin);
            seen.put(stream, 1L);
            adds.add(new RelationCausalEvents.Dot(stream, 1, UUID.randomUUID(), Map.of()));
        }
        for (int index = 0; index < 199; index++) {
            String stream = RelationCausalEvents.legacyStream(UUID.randomUUID(), legacyOrigin);
            seen.put(stream, 1L);
            removes.add(new RelationCausalEvents.Dot(stream, 1, UUID.randomUUID(), Map.of()));
        }
        RelationCausalEvents.Bridge largeButValidState = new RelationCausalEvents.Bridge(naturalId,
                evidence.kind(), evidence.actor(), evidence.target(), evidence.owner(), evidence.tag(),
                evidence.actorEpoch(), evidence.ownerEpoch(), evidence.rowId(), evidence.rowGid(), evidence.rowOrigin(),
                seen, adds, removes, null);
        String statePayload = RelationCausalEvents.payload(largeButValidState);
        assertDoesNotThrow(() -> RelationCausalEvents.parseBridge(statePayload));
        assertTrue(statePayload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 120 * 1024,
                "The compact state itself stays below the protocol's 128 KiB envelope limit");
        tx(p.a, () -> store.mergeBridge(largeButValidState));
        RelationCausalStore.State beforeLocalAction = causalState(p.a, store, naturalId);
        assertEquals("MULTI_LEGACY", beforeLocalAction.blockedReason());
        assertEquals(400, beforeLocalAction.seen().size());
        assertEquals(200, beforeLocalAction.adds().size());
        assertEquals(200, beforeLocalAction.removes().size());
        long publicRowsBefore = count(p.a,
                "SELECT count(*) FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner);

        var oversizedDot = new RelationCausalEvents.Dot(localStream, 2, UUID.randomUUID(), beforeLocalAction.seen());
        List<RelationCausalEvents.Dot> actionAdds = new ArrayList<>(beforeLocalAction.adds());
        List<RelationCausalEvents.Dot> actionRemoves = new ArrayList<>(beforeLocalAction.removes());
        actionRemoves.removeIf(dot -> dot.stream().equals(localStream));
        actionRemoves.add(oversizedDot);
        Map<String, Long> actionSeen = new TreeMap<>(beforeLocalAction.seen());
        actionSeen.put(localStream, 2L);
        var actionState = new RelationCausalEvents.Bridge(naturalId, evidence.kind(), evidence.actor(),
                evidence.target(), evidence.owner(), evidence.tag(), evidence.actorEpoch(), evidence.ownerEpoch(),
                evidence.rowId(), evidence.rowGid(), evidence.rowOrigin(), actionSeen, actionAdds, actionRemoves, null);
        String candidateBridgePayload = RelationCausalEvents.payload(actionState);
        assertDoesNotThrow(() -> RelationCausalEvents.parseBridge(candidateBridgePayload));
        assertTrue(candidateBridgePayload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 120 * 1024,
                "The post-action compact state remains valid even though the full ACTION is too large");
        var oversizedAction = new RelationCausalEvents.Action(naturalId, evidence.kind(), evidence.actor(),
                evidence.target(), evidence.owner(), evidence.tag(), evidence.actorEpoch(), evidence.ownerEpoch(),
                evidence.rowId(), evidence.rowGid(), evidence.rowOrigin(), localStream, 2,
                beforeLocalAction.seen(), false, new RelationEvents.Operation(evidence.kind(), evidence.actor(),
                evidence.target(), evidence.owner(), evidence.tag(), evidence.actorEpoch(), evidence.ownerEpoch(),
                evidence.rowId(), evidence.rowGid(), evidence.rowOrigin(), Map.of(), p.a.db.site().getId(), 1,
                false, null), actionState);
        assertThrows(IllegalArgumentException.class, () -> RelationCausalEvents.payload(oversizedAction),
                "The complete ACTION, which repeats its causal context, must exceed the envelope limit");

        long causalFactsBefore = count(p.a,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", naturalId);
        long operationFactsBefore = count(p.a,
                "SELECT count(*) FROM eatwhat_sync.relation_operation WHERE relation_id=?", evidence.relationId());
        long outboxBefore = count(p.a, "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type=?",
                RelationEvents.AGGREGATE);
        long noticesBefore = count(p.a, "SELECT count(*) FROM notifications");
        var intentBefore = p.a.db.runtime().queryForList("""
                SELECT desired_present,intent_revision,published_revision,published_actor_epoch,published_owner_epoch
                FROM eatwhat_sync.relation_intent WHERE natural_id=?
                """, naturalId);
        var rejected = assertThrows(you.v50to.eatwhat.exception.BizException.class,
                () -> changeResult(p, p.a, RelationKind.follow, false),
                "The full ACTION envelope, including V1 evidence and the 400-entry context, must fit before commit");
        assertEquals(you.v50to.eatwhat.data.enums.BizCode.DATA_SYNC_PENDING, rejected.getBizCode());
        assertEquals(beforeLocalAction, causalState(p.a, store, naturalId));
        assertEquals(causalFactsBefore, count(p.a,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", naturalId));
        assertEquals(operationFactsBefore, count(p.a,
                "SELECT count(*) FROM eatwhat_sync.relation_operation WHERE relation_id=?", evidence.relationId()));
        assertEquals(outboxBefore, count(p.a, "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type=?",
                RelationEvents.AGGREGATE));
        assertEquals(noticesBefore, count(p.a, "SELECT count(*) FROM notifications"));
        assertEquals(intentBefore, p.a.db.runtime().queryForList("""
                SELECT desired_present,intent_revision,published_revision,published_actor_epoch,published_owner_epoch
                FROM eatwhat_sync.relation_intent WHERE natural_id=?
                """, naturalId));
        assertEquals(publicRowsBefore, count(p.a,
                "SELECT count(*) FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner),
                "A rejected action cannot alter the public projection");
        assertEquals(initialState.identity().rowId(), beforeLocalAction.identity().rowId());
    }
    @Test void registeredLegacySmallNumericIdentitySurvivesLocalRestoreAndCrossSiteReplay() throws Exception {
        Pair p=pair();long legacyId=123L;UUID legacyGid=UUID.randomUUID();
        seedLegacyLike(p.a,legacyId,legacyGid,p.actor,p.food,p.a.db.site().getId());
        mirror(p.a.db,p.b.db,"likes",legacyId);
        for(Site s:List.of(p.a,p.b))s.db.admin().update("UPDATE foods SET likes_count=1 WHERE id=?",p.food);

        change(p,p.a,RelationKind.food_like,false);
        assertEquals(legacyId,count(p.a,"SELECT id FROM likes WHERE account_id=? AND target_id=? AND target_type='food'",p.actor,p.food));
        change(p,p.a,RelationKind.food_like,true);
        assertEquals(legacyId,count(p.a,"SELECT id FROM likes WHERE account_id=? AND target_id=? AND target_type='food'",p.actor,p.food));
        exchange(p);
        for(Site s:List.of(p.a,p.b)){
            assertTrue(present(p,s,RelationKind.food_like));
            assertEquals(legacyId,count(s,"SELECT id FROM likes WHERE account_id=? AND target_id=? AND target_type='food'",p.actor,p.food));
            assertEquals(legacyGid.toString(),s.db.runtime().queryForObject("SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='likes' AND numeric_id=?",String.class,legacyId));
            assertEquals(1,count(s,"SELECT likes_count FROM foods WHERE id=?",p.food));
        }
    }
    @Test void unknownLegacyIdentityCannotBeBoundToAnotherNaturalRelation() throws Exception {
        Pair p=pair();
        UUID actorGid=UUID.fromString(p.a.db.runtime().queryForObject("SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",String.class,p.actor));
        UUID ownerGid=UUID.fromString(p.a.db.runtime().queryForObject("SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",String.class,p.owner));
        UUID foodGid=UUID.fromString(p.a.db.runtime().queryForObject("SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='foods' AND numeric_id=?",String.class,p.food));
        var unregistered=new RelationEvents.Operation(RelationKind.favorite,actorGid,foodGid,ownerGid,null,1,1,
                123L,UUID.randomUUID(),p.a.db.site().getId(),Map.of(),p.a.db.site().getId(),1,true,null);
        assertThrows(DataAccessException.class,()->p.b.store.project(RelationKind.favorite,p.actor,p.food,null,true,unregistered,p.a.db.site().getId()));
        assertEquals(0,count(p.b,"SELECT count(*) FROM eatwhat_identity.entity_identity WHERE entity_type='food_favorites' AND numeric_id=123"));
        assertEquals(0,count(p.b,"SELECT count(*) FROM eatwhat_sync.relation_alias WHERE entity_type='food_favorites' AND numeric_id=123"));
        assertEquals(0,count(p.b,"SELECT count(*) FROM eatwhat_sync.relation_projection WHERE entity_type='food_favorites' AND numeric_id=123"));
        assertFalse(present(p,p.b,RelationKind.favorite));
    }

    @Test void registeredTombstoneWithoutNaturalKeyEvidenceCannotBeRebound() throws Exception {
        Pair p=pair();
        long tombstoneId=1_000_000_001L;
        UUID rowGid=UUID.randomUUID();
        String origin=p.a.db.site().getId();
        p.b.db.admin().update("INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site) VALUES('likes',?,?,?)",
                tombstoneId,rowGid,origin);
        UUID actorGid=UUID.fromString(p.b.db.runtime().queryForObject(
                "SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",
                String.class,p.actor));
        UUID ownerGid=UUID.fromString(p.b.db.runtime().queryForObject(
                "SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",
                String.class,p.owner));
        UUID foodGid=UUID.fromString(p.b.db.runtime().queryForObject(
                "SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='foods' AND numeric_id=?",
                String.class,p.food));
        var operation=new RelationEvents.Operation(RelationKind.food_like,actorGid,foodGid,ownerGid,null,
                1,1,tombstoneId,rowGid,origin,Map.of(),origin,1,true,null);

        assertThrows(DataAccessException.class,()->p.b.store.project(RelationKind.food_like,p.actor,p.food,
                null,true,operation,origin));
        assertEquals(1,count(p.b,"SELECT count(*) FROM eatwhat_identity.entity_identity WHERE entity_type='likes' AND numeric_id=?",tombstoneId));
        assertEquals(0,count(p.b,"SELECT count(*) FROM eatwhat_sync.relation_alias WHERE entity_type='likes' AND numeric_id=?",tombstoneId));
        assertEquals(0,count(p.b,"SELECT count(*) FROM eatwhat_sync.relation_projection WHERE entity_type='likes' AND numeric_id=?",tombstoneId));
        assertFalse(present(p,p.b,RelationKind.food_like));
    }

    @Test void notificationIdentityCannotBeReusedWithDifferentContent() throws Exception {
        Pair p=pair();
        change(p,p.a,RelationKind.follow,true);
        var event=events(p.a).getFirst();
        var original=v1Evidence(event).notification();
        exchange(p);
        var altered=new RelationEvents.Notice(original.numericId(),original.globalId(),original.recipientGid(),
                original.originSite(),"like","food",original.createdAt(),original.expiresAt());

        assertThrows(DataAccessException.class,()->p.b.store.notification(event.eventId(),p.actor,p.owner,altered,event.originSite()));
        assertEquals(1,count(p.b,"SELECT count(*) FROM eatwhat_sync.relation_notification WHERE source_event_id=?",event.eventId()));
        assertEquals(1,count(p.b,"SELECT count(*) FROM notifications"));
    }

    static void seedLegacyLike(Site site,long id,UUID gid,long actor,long food,String origin) throws Exception {
        try(var c=site.db.admin().getDataSource().getConnection()){
            c.setAutoCommit(false);
            try(var s=c.createStatement()){s.execute("SET LOCAL session_replication_role='replica'");}
            try(var s=c.prepareStatement("INSERT INTO public.likes(id,account_id,target_type,target_id) VALUES(?,?,'food',?)")){
                s.setLong(1,id);s.setLong(2,actor);s.setLong(3,food);s.executeUpdate();
            }
            try(var s=c.prepareStatement("INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site) VALUES('likes',?,?,?)")){
                s.setLong(1,id);s.setObject(2,gid);s.setString(3,origin);s.executeUpdate();
            }
            c.commit();
        }
    }
    @ParameterizedTest @EnumSource(RelationKind.class)
    void reverseDeliveryWaitsForPredecessorsThenReplaysTenTimesWithoutChangingEffects(RelationKind kind) throws Exception {
        Pair p=pair();change(p,p.a,kind,true);change(p,p.a,kind,false);change(p,p.a,kind,true);
        // Exercise the actual retained schema-1 wire contract. V2 action events carry their own state and
        // do not need this predecessor WAIT; the old V1 envelope must continue to wait for missing dots.
        var events=events(p.a).stream().map(RelationReplicationIT::legacyV1Wire).toList();
        receive(p.b,events.get(2));receive(p.b,events.get(1));process(p.b);
        assertEquals(2,count(p.b,"SELECT count(*) FROM eatwhat_sync.inbox WHERE status='WAITING_DEPENDENCY'"));
        receive(p.b,events.getFirst());process(p.b);
        for(int i=0;i<4;i++){p.b.inbox.requeueWaiting(100,Duration.ZERO);process(p.b);}
        assertTrue(present(p,p.b,kind));assertEquals(3,count(p.b,"SELECT count(*) FROM eatwhat_sync.inbox WHERE status='APPLIED'"));
        long notifications=count(p.b,"SELECT count(*) FROM notifications");
        for(int i=0;i<10;i++)for(var event:events)receive(p.b,event);
        process(p.b);assertEquals(notifications,count(p.b,"SELECT count(*) FROM notifications"));
        assertEquals(3,count(p.b,"SELECT count(*) FROM eatwhat_sync.relation_operation"));
        if(kind==RelationKind.activity_food_like || kind==RelationKind.activity_dinner_like || kind==RelationKind.activity_post_like || kind==RelationKind.follow) {
            assertEquals(2,notifications);
            for(var event:events) {
                var note=v1Evidence(event).notification();if(note==null)continue;
                for(Site s:List.of(p.a,p.b))assertEquals(note.globalId().toString(),s.db.runtime().queryForObject("SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='notifications' AND numeric_id=?",String.class,note.numericId()));
                assertEquals(note.expiresAt(),count(p.b,"SELECT (extract(epoch FROM expires_at)*1000)::bigint FROM notifications WHERE id=?",note.numericId()));
            }
        }
    }
    @Test void sameSiteConcurrentRequestsSerializeAndCreateOneNoticeAndOneOperation() throws Exception {
        Pair p=pair();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(8);
        try {
            var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<8;i++)tasks.add(pool.submit(()->{start.await();return p.a.commands.change(RelationKind.follow,p.actor,p.owner,null,true);}));
            start.countDown();int changed=0;for(var task:tasks)if(task.get(20,TimeUnit.SECONDS))changed++;
            assertEquals(1,changed);assertEquals(1,events(p.a).size());assertEquals(1,count(p.a,"SELECT count(*) FROM notifications"));
            exchange(p);assertTrue(present(p,p.b,RelationKind.follow));
        } finally {pool.shutdownNow();}
    }

    @Test void notificationReadWorkerRotatesPastAnOlderBatchOfUnacknowledgedReads() throws Exception {
        Pair p=pair();
        for(int i=0;i<17;i++) {
            assertTrue(changeResult(p,p.a,RelationKind.follow,true));
            assertTrue(changeResult(p,p.a,RelationKind.follow,false));
        }

        NotificationReadStore store=new NotificationReadStore(p.a.db.runtime());
        var readOutbox=new OutboxService(p.a.db.runtime(),p.a.db.transactions(),CODEC,p.a.db.site());
        var commands=new NotificationReadCommands(p.a.db.runtime(),p.a.db.transactions(),readOutbox,store,
                p.a.consent,p.a.db.site(),p.a.peerSiteId);
        var worker=new NotificationReadWorker(p.a.db.transactions(),store,commands,p.a.consent,4);
        commands.markAll(p.owner);
        assertEquals(17,count(p.a,"SELECT count(*) FROM eatwhat_sync.notification_read_member"));
        worker.runOnce();
        assertEquals(4,count(p.a,"SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE status='WAIT' AND event_id IS NOT NULL"));

        assertTrue(changeResult(p,p.a,RelationKind.follow,true));
        commands.markAll(p.owner);
        long laterNotice=count(p.a,"SELECT max(notification_id) FROM eatwhat_sync.notification_read_member");
        assertEquals(1,count(p.a,"SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE notification_id=? AND status='READY'",laterNotice));

        int attempts=0;
        while(attempts++<100 && count(p.a,"SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE notification_id=? AND event_id IS NOT NULL",laterNotice)==0) {
            worker.runOnce();
        }

        assertEquals(1,count(p.a,"SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE notification_id=? AND event_id IS NOT NULL",laterNotice),
                "A later READY read must make progress behind older unacknowledged WAIT members");
        assertEquals(1,count(p.a,"SELECT count(*) FROM eatwhat_sync.notification_read_operation WHERE notification_gid=(SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='notifications' AND numeric_id=?)",laterNotice));
    }

    @Test void notificationReadEpochStreamsRemainBoundedAfterMoreThanTwentyRegrants() throws Exception {
        Pair p=pair();
        assertTrue(changeResult(p,p.a,RelationKind.follow,true));
        long notificationId=count(p.a,"SELECT max(id) FROM public.notifications WHERE account_id=?",p.owner);
        UUID notificationGid=p.a.db.runtime().queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='notifications' AND numeric_id=?",UUID.class,notificationId);
        UUID recipientGid=p.a.db.runtime().queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",UUID.class,p.owner);
        UUID actorGid=p.a.db.runtime().queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",UUID.class,p.actor);
        String origin=p.a.db.runtime().queryForObject("SELECT origin_site FROM eatwhat_identity.entity_identity WHERE entity_type='notifications' AND numeric_id=?",String.class,notificationId);
        var store=new NotificationReadStore(p.a.db.runtime());
        var streams=new HashSet<String>();
        var numericIds=new HashSet<Long>();

        for(long epoch=1;epoch<=24;epoch++) {
            String dot=NotificationReadEvents.dotSite(p.a.db.site().getId(),epoch,0);
            String peerDot=NotificationReadEvents.dotSite(p.a.peerSiteId,epoch,0);
            var next=store.nextDot(notificationGid,recipientGid,dot,peerDot);
            var operation=new NotificationReadEvents.Operation(next.numericId(),notificationGid,notificationId,origin,
                    recipientGid,0,actorGid,epoch,dot,next.counter(),next.context());
            store.insertOperation(UUID.randomUUID(),next,operation);
            assertTrue(streams.add(dot),"Each actor epoch gets a distinct read stream");
            assertTrue(numericIds.add(next.numericId()),"Read-operation numeric IDs are never reused");
            assertEquals(1,next.counter(),"A fresh epoch stream starts at its first dot");
            assertTrue(next.context().size()<=2,"An operation carries only the current two-site causal stream");
            for(String oldStream:streams)if(!oldStream.equals(dot))assertFalse(next.context().containsKey(oldStream));
        }

        assertEquals(24,count(p.a,"SELECT count(*) FROM eatwhat_sync.notification_read_operation WHERE notification_gid=?",notificationGid));
        assertEquals(24,count(p.a,"SELECT count(*) FROM jsonb_object_keys((SELECT vector FROM eatwhat_sync.notification_read_state WHERE notification_gid=? AND recipient_gid=?))",notificationGid,recipientGid));
    }
    @Test void opposingFollowsAndDifferentActorsLikingOneParentDoNotDeadlock() throws Exception {
        Pair p=pair();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(4);
        try {
            var forward=pool.submit(()->{start.await();return p.a.commands.change(RelationKind.follow,p.actor,p.owner,null,true);});
            var reverse=pool.submit(()->{start.await();return p.a.commands.change(RelationKind.follow,p.owner,p.actor,null,true);});
            start.countDown();assertTrue(forward.get(20,TimeUnit.SECONDS));assertTrue(reverse.get(20,TimeUnit.SECONDS));
            assertEquals(2,count(p.a,"SELECT count(*) FROM follow"));

            var first=pool.submit(()->p.a.commands.change(RelationKind.food_like,p.actor,p.food,null,true));
            var second=pool.submit(()->p.a.commands.change(RelationKind.food_like,p.owner,p.food,null,true));
            assertTrue(first.get(20,TimeUnit.SECONDS));assertTrue(second.get(20,TimeUnit.SECONDS));
            assertEquals(2,count(p.a,"SELECT count(*) FROM likes WHERE target_type='food' AND target_id=? AND deleted_at IS NULL",p.food));
            assertEquals(2,count(p.a,"SELECT likes_count FROM foods WHERE id=?",p.food));
            exchange(p);
            assertEquals(2,count(p.b,"SELECT count(*) FROM follow"));
            assertEquals(2,count(p.b,"SELECT count(*) FROM likes WHERE target_type='food' AND target_id=? AND deleted_at IS NULL",p.food));
            assertEquals(2,count(p.b,"SELECT likes_count FROM foods WHERE id=?",p.food));
        } finally {pool.shutdownNow();}
    }
    @Test void regrantAndRepeatedAddRepublishesCurrentEpochWithoutChangingIdentityOrNotice() throws Exception {
        Pair p=pair();change(p,p.a,RelationKind.follow,true);exchange(p);
        var originalLogicalFacts=p.a.db.runtime().queryForList("""
                SELECT event_id::text,natural_id::text,stream_key,dot_counter,context::text,adding,payload::text
                FROM eatwhat_sync.relation_causal_fact ORDER BY event_id
                """);
        var originalV1Evidence=p.a.db.runtime().queryForList("""
                SELECT event_id::text,relation_id::text,dot_site,dot_counter,context::text,adding,payload::text
                FROM eatwhat_sync.relation_operation ORDER BY event_id
                """);
        long id=count(p.a,"SELECT id FROM follow WHERE account_id=? AND target_id=?",p.actor,p.owner);
        UUID gid=UUID.fromString(p.a.db.runtime().queryForObject("SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='follow' AND numeric_id=?",String.class,id));
        long notices=count(p.a,"SELECT count(*) FROM notifications");
        p.a.consent.withdraw(p.actor);
        var purgeJob=p.a.consent.claim(32,LEASE).stream().filter(j->j.userId()==p.actor&&j.kind().equals("PURGE")).findFirst().orElseThrow();
        var purge=p.a.consent.export(purgeJob);assertNull(purge.data());
        assertEquals("APPLIED",p.b.consent.apply(purge));p.a.consent.complete(purgeJob);
        assertEquals(0,count(p.b,"SELECT count(*) FROM follow WHERE account_id=? AND target_id=?",p.actor,p.owner));
        assertEquals(0,count(p.b,"SELECT count(*) FROM notifications"));
        var grant=p.a.consent.grant(p.actor,POLICY);
        var job=p.a.consent.claim(32,LEASE).stream().filter(j->j.userId()==p.actor&&j.epoch()==grant.epoch()&&j.kind().equals("UPSERT")).findFirst().orElseThrow();
        assertEquals("APPLIED",p.b.consent.apply(p.a.consent.export(job)));p.a.consent.complete(job);
        // Campus keeps its relation locally through withdrawal; explicit current-epoch ADD restores the projection.
        assertFalse(p.a.commands.change(RelationKind.follow,p.actor,p.owner,null,true),
                "Legacy duplicate-follow API result stays false while current-epoch state is republished");
        var afterRegrantEvents=events(p.a);
        assertEquals(2,afterRegrantEvents.size(),"Regrant emits one pure state bridge, not a synthetic new relation action");
        assertEquals(1,afterRegrantEvents.stream().filter(e->RelationCausalEvents.ACTION.equals(e.eventType())).count());
        var currentBridge=afterRegrantEvents.stream()
                .filter(e->RelationCausalEvents.BRIDGE.equals(e.eventType())).findFirst().orElseThrow();
        var bridgedState=RelationCausalEvents.parseBridge(currentBridge.payloadJson());
        assertTrue(bridgedState.addWitnesses().stream().anyMatch(dot->dot.eventId().equals(afterRegrantEvents.getFirst().eventId())),
                "The regrant bridge carries the original immutable ADD witness");
        assertEquals(originalLogicalFacts,p.a.db.runtime().queryForList("""
                SELECT event_id::text,natural_id::text,stream_key,dot_counter,context::text,adding,payload::text
                FROM eatwhat_sync.relation_causal_fact ORDER BY event_id
                """),"Regrant is state repair and cannot mint a replacement logical action");
        assertEquals(originalV1Evidence,p.a.db.runtime().queryForList("""
                SELECT event_id::text,relation_id::text,dot_site,dot_counter,context::text,adding,payload::text
                FROM eatwhat_sync.relation_operation ORDER BY event_id
                """),"V1 notification evidence remains immutable across regrant");
        exchange(p);
        assertTrue(present(p,p.a,RelationKind.follow));assertTrue(present(p,p.b,RelationKind.follow));
        assertEquals(id,count(p.a,"SELECT id FROM follow WHERE account_id=? AND target_id=?",p.actor,p.owner));
        assertEquals(gid.toString(),p.a.db.runtime().queryForObject("SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='follow' AND numeric_id=?",String.class,id));
        assertEquals(1,count(p.b,"SELECT count(*) FROM follow WHERE account_id=? AND target_id=?",p.actor,p.owner));
        assertEquals(notices,count(p.a,"SELECT count(*) FROM notifications"));
        assertEquals(0,count(p.b,"SELECT count(*) FROM notifications"),"Re-add does not recreate the withdrawn projection's old notification");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void regrantBackfillPreservesAnAddThatObservedAnUndeliveredRemove(boolean campusBridgeArrivesFirst) throws Exception {
        Pair p = pair();
        change(p, p.a, RelationKind.follow, true);
        exchange(p);
        extendGrantedConsentForSlowSyntheticTest(p);
        assertTrue(present(p, p.a, RelationKind.follow));
        assertTrue(present(p, p.b, RelationKind.follow));
        long originalId = count(p.a, "SELECT id FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner);
        UUID originalGid = UUID.fromString(p.a.db.runtime().queryForObject(
                "SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='follow' AND numeric_id=?",
                String.class, originalId));

        assertTrue(changeResult(p, p.b, RelationKind.follow, false),
                "The external site has current actor and owner consent and a present follow to remove");
        SyncEvent removeEvent = events(p.b).stream()
                .filter(event -> RelationCausalEvents.ACTION.equals(event.eventType())
                        && Boolean.FALSE.equals(adding(event)))
                .findFirst().orElseThrow(() -> new AssertionError("External REMOVE produced no action: "
                        + relationDiagnostics(p.b, p)));
        RelationEvents.Operation remove = v1Evidence(removeEvent);
        receive(p.a, removeEvent);
        process(p.a);
        assertFalse(present(p, p.a, RelationKind.follow));
        assertFalse(present(p, p.b, RelationKind.follow));

        extendGrantedConsentForSlowSyntheticTest(p);
        assertTrue(changeResult(p, p.a, RelationKind.follow, true));
        SyncEvent laterAddEvent = events(p.a).getLast();
        RelationEvents.Operation laterAdd = v1Evidence(laterAddEvent);
        assertTrue(laterAdd.context().getOrDefault(remove.dotSite(), 0L) >= remove.dotCounter(),
                "The later A-side ADD must causally observe B's REMOVE before it is withheld");
        assertNotEquals(removeEvent.eventId(), laterAddEvent.eventId());

        p.a.consent.withdraw(p.actor);
        var purge = p.a.consent.claim(32, LEASE).stream()
                .filter(job -> job.userId() == p.actor && job.kind().equals("PURGE"))
                .findFirst().orElseThrow();
        assertEquals("APPLIED", p.b.consent.apply(p.a.consent.export(purge)));
        p.a.consent.complete(purge);
        assertEquals(0, count(p.b, "SELECT count(*) FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner));
        long campusNoticesBeforeRegrant = count(p.a, "SELECT count(*) FROM notifications");
        long externalNoticesBeforeRegrant = count(p.b, "SELECT count(*) FROM notifications");

        var grant = p.a.consent.grant(p.actor, POLICY);
        var grantJob = p.a.consent.claim(32, LEASE).stream()
                .filter(job -> job.userId() == p.actor && job.epoch() == grant.epoch() && job.kind().equals("UPSERT"))
                .findFirst().orElseThrow();
        assertEquals("APPLIED", p.b.consent.apply(p.a.consent.export(grantJob)));
        p.a.consent.complete(grantJob);
        extendGrantedConsentForSlowSyntheticTest(p);

        // Both sites complete the current-epoch reconstruction before either bridge is delivered.
        drainBackfill(p.a, p.actor, grant.epoch());
        drainBackfill(p.b, p.actor, grant.epoch());
        List<SyncEvent> campusBridge = events(p.a).stream()
                .filter(event -> actorEpoch(event) == grant.epoch()).toList();
        List<SyncEvent> externalBridge = events(p.b).stream()
                .filter(event -> actorEpoch(event) == grant.epoch()).toList();
        assertEquals(1, campusBridge.size(), "Campus should publish its retained, later local ADD exactly once");
        assertEquals(1, externalBridge.size(), "External should publish its retained REMOVE exactly once");
        assertTrue(campusBridge.stream().allMatch(event -> notification(event) == null));
        assertTrue(externalBridge.stream().allMatch(event -> notification(event) == null));
        String preBridgeDiagnostics = "campus{" + relationDiagnostics(p.a, p) + "}; external{"
                + relationDiagnostics(p.b, p) + "}; campusBridge=" + campusBridge.stream()
                .map(SyncEvent::payloadJson).toList() + "; externalBridge=" + externalBridge.stream()
                .map(SyncEvent::payloadJson).toList();

        if (campusBridgeArrivesFirst) {
            campusBridge.forEach(event -> receive(p.b, event));
            process(p.b);
            externalBridge.forEach(event -> receive(p.a, event));
            process(p.a);
        } else {
            externalBridge.forEach(event -> receive(p.a, event));
            process(p.a);
            campusBridge.forEach(event -> receive(p.b, event));
            process(p.b);
        }

        String diagnostics = "before bridge delivery: " + preBridgeDiagnostics + "; after merge: campus{"
                + relationDiagnostics(p.a, p) + "}; external{" + relationDiagnostics(p.b, p) + "}";
        assertTrue(present(p, p.a, RelationKind.follow),
                "A REMOVE from the prior consent epoch must not erase the later ADD that observed it. " + diagnostics);
        assertTrue(present(p, p.b, RelationKind.follow),
                "Both bridge arrival orders must converge to the causally later ADD. " + diagnostics);
        assertEquals(1, count(p.a, "SELECT count(*) FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner));
        assertEquals(1, count(p.b, "SELECT count(*) FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner));
        UUID naturalId = RelationStore.naturalId(laterAdd.kind(), laterAdd.actor(), laterAdd.target(),
                laterAdd.owner(), laterAdd.tag());
        RelationCausalStore.State causalA = new RelationCausalStore(p.a.db.runtime()).load(naturalId);
        RelationCausalStore.State causalB = new RelationCausalStore(p.b.db.runtime()).load(naturalId);
        assertEquals(originalId, causalA.identity().rowId(),
                "The removed public row keeps its stable numeric identity in causal state");
        assertEquals(originalId, causalB.identity().rowId(),
                "The removed public row keeps the same stable numeric identity at the peer");
        assertEquals(originalGid.toString(), p.a.db.runtime().queryForObject(
                "SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='follow' AND numeric_id=?",
                String.class, originalId));
        assertEquals(originalGid.toString(), p.b.db.runtime().queryForObject(
                "SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='follow' AND numeric_id=?",
                String.class, originalId));
        assertEquals(campusNoticesBeforeRegrant, count(p.a, "SELECT count(*) FROM notifications"),
                "Historical replay must not add or extend notices");
        assertEquals(externalNoticesBeforeRegrant, count(p.b, "SELECT count(*) FROM notifications"),
                "Historical replay must not synthesize notices at the peer");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void aGenuineConcurrentCurrentEpochRemoveStillWins(boolean campusActionArrivesFirst) throws Exception {
        Pair p = pair();
        change(p, p.a, RelationKind.follow, true);
        exchange(p);
        long originalId = count(p.a, "SELECT id FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner);
        UUID originalGid = UUID.fromString(p.a.db.runtime().queryForObject(
                "SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='follow' AND numeric_id=?",
                String.class, originalId));
        long campusNotices = count(p.a, "SELECT count(*) FROM notifications");

        p.a.consent.withdraw(p.actor);
        var purge = p.a.consent.claim(32, LEASE).stream()
                .filter(job -> job.userId() == p.actor && job.kind().equals("PURGE"))
                .findFirst().orElseThrow();
        assertEquals("APPLIED", p.b.consent.apply(p.a.consent.export(purge)));
        p.a.consent.complete(purge);
        assertFalse(present(p, p.b, RelationKind.follow));

        // Campus keeps its source row through withdrawal. This real local cancellation persists R2,
        // but authorization prevents it from entering the Outbox.
        int campusEventsBeforeCancel = events(p.a).size();
        assertTrue(changeResult(p, p.a, RelationKind.follow, false),
                "A local cancellation during withdrawal is a committed user operation");
        assertFalse(present(p, p.a, RelationKind.follow));
        assertEquals(campusEventsBeforeCancel, events(p.a).size(),
                "An unauthorized REMOVE is durable locally but cannot enter the Outbox");

        var grant = p.a.consent.grant(p.actor, POLICY);
        var grantJob = p.a.consent.claim(32, LEASE).stream()
                .filter(job -> job.userId() == p.actor && job.epoch() == grant.epoch() && job.kind().equals("UPSERT"))
                .findFirst().orElseThrow();
        assertEquals("APPLIED", p.b.consent.apply(p.a.consent.export(grantJob)));
        p.a.consent.complete(grantJob);

        // Both sites now have the same current authorization. B creates a new-epoch REMOVE while
        // its public projection is absent after purge; the old logical ADD is still known to causal state.
        assertFalse(changeResult(p, p.b, RelationKind.follow, false),
                "The legacy absent-unfollow result remains false even though a first REMOVE is recorded");
        SyncEvent externalRemove = events(p.b).stream()
                .filter(event -> RelationCausalEvents.ACTION.equals(event.eventType())
                        && actorEpoch(event) == grant.epoch())
                .findFirst().orElseThrow();
        var externalAction = RelationCausalEvents.parseAction(externalRemove.payloadJson());
        assertFalse(externalAction.adding());

        // A's explicit current-epoch ADD observes its private R2, but has not observed B's REMOVE.
        assertTrue(changeResult(p, p.a, RelationKind.follow, true));
        SyncEvent campusAdd = events(p.a).stream()
                .filter(event -> RelationCausalEvents.ACTION.equals(event.eventType())
                        && actorEpoch(event) == grant.epoch())
                .findFirst().orElseThrow();
        var campusAction = RelationCausalEvents.parseAction(campusAdd.payloadJson());
        assertTrue(campusAction.adding());
        String campusStream = RelationCausalEvents.nativeStream(p.a.db.site().getId());
        String externalStream = RelationCausalEvents.nativeStream(p.b.db.site().getId());
        assertTrue(campusAction.context().getOrDefault(campusStream, 0L) >= 2,
                "The current ADD observes the private earlier REMOVE");
        assertEquals(0, campusAction.context().getOrDefault(externalStream, 0L),
                "The current ADD is concurrent with B's withheld REMOVE");
        assertEquals(1, externalAction.context().getOrDefault(campusStream, 0L),
                "B's current REMOVE observed only the original A ADD, not R2 or A's later ADD");
        assertTrue(present(p, p.a, RelationKind.follow),
                "Before learning B's concurrent REMOVE, A's ADD that observed its local REMOVE is effective");

        if (campusActionArrivesFirst) {
            receive(p.b, campusAdd);
            process(p.b);
            receive(p.a, externalRemove);
            process(p.a);
        } else {
            receive(p.a, externalRemove);
            process(p.a);
            receive(p.b, campusAdd);
            process(p.b);
        }

        assertFalse(present(p, p.a, RelationKind.follow), "A true concurrent new-epoch REMOVE still wins");
        assertFalse(present(p, p.b, RelationKind.follow), "Both action arrival orders preserve remove-wins");
        var causalA = new RelationCausalStore(p.a.db.runtime()).load(campusAction.naturalId());
        var causalB = new RelationCausalStore(p.b.db.runtime()).load(campusAction.naturalId());
        assertEquals(causalA.identity().naturalId(), causalB.identity().naturalId());
        assertEquals(causalA.identity().kind(), causalB.identity().kind());
        assertEquals(causalA.identity().actor(), causalB.identity().actor());
        assertEquals(causalA.identity().target(), causalB.identity().target());
        assertEquals(causalA.identity().owner(), causalB.identity().owner());
        assertEquals(causalA.identity().tag(), causalB.identity().tag());
        assertEquals(causalA.identity().actorEpoch(), causalB.identity().actorEpoch());
        assertEquals(causalA.identity().ownerEpoch(), causalB.identity().ownerEpoch());
        assertEquals(causalA.seen(), causalB.seen(), "Both sites have the same causal frontier");
        assertEquals(new HashSet<>(causalA.adds()), new HashSet<>(causalB.adds()));
        assertEquals(new HashSet<>(causalA.removes()), new HashSet<>(causalB.removes()));
        assertEquals(causalA.blockedReason(), causalB.blockedReason());
        assertTrue(causalA.removes().stream().anyMatch(dot -> dot.eventId().equals(externalRemove.eventId())));
        assertTrue(causalA.adds().stream().anyMatch(dot -> dot.eventId().equals(campusAdd.eventId())));
        assertEquals(originalGid.toString(), p.a.db.runtime().queryForObject(
                "SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='follow' AND numeric_id=?",
                String.class, originalId));
        assertEquals(originalGid.toString(), p.b.db.runtime().queryForObject(
                "SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE entity_type='follow' AND numeric_id=?",
                String.class, originalId));
        assertEquals(0, count(p.a, "SELECT count(*) FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner));
        assertEquals(0, count(p.b, "SELECT count(*) FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner));
        assertEquals(originalId, causalA.identity().rowId(),
                "The removed public row keeps its stable numeric identity in causal state");
        assertEquals(originalId, causalB.identity().rowId(),
                "The removed public row keeps the same stable numeric identity at the peer");
        assertEquals(campusNotices + 1, count(p.a, "SELECT count(*) FROM notifications"),
                "The explicit ADD sends exactly its ordinary notification");
        assertEquals(1, count(p.b, "SELECT count(*) FROM notifications"));
    }

    @Test void authorizedActionSelfContainsACommittedSameEpochRemoveWithNoOutboxPredecessor() throws Exception {
        Pair p = pair();
        change(p, p.a, RelationKind.follow, true);
        SyncEvent initialAdd = events(p.a).getFirst();
        receive(p.b, initialAdd);
        process(p.b);
        assertTrue(present(p, p.b, RelationKind.follow));

        long epochBefore = p.a.consent.get(p.actor).epoch();
        p.a.db.admin().update("UPDATE eatwhat_consent.subject SET identity_ready=false WHERE user_id=?", p.actor);
        assertTrue(changeResult(p, p.a, RelationKind.follow, false),
                "The authorized local API still commits a same-epoch removal while replication is temporarily unavailable");
        assertFalse(present(p, p.a, RelationKind.follow));
        assertEquals(1, events(p.a).size(), "The unavailable REMOVE has durable local evidence but no Outbox delivery");
        assertEquals(epochBefore, p.a.consent.get(p.actor).epoch(), "This path must not change consent epoch");
        assertEquals(2, count(p.a, "SELECT count(*) FROM eatwhat_sync.relation_causal_fact"));

        p.a.db.admin().update("UPDATE eatwhat_consent.subject SET identity_ready=true WHERE user_id=?", p.actor);
        assertEquals(epochBefore, p.a.consent.get(p.actor).epoch());
        assertTrue(changeResult(p, p.a, RelationKind.follow, true),
                "A subsequent explicit ADD is a real user action and may publish the compact causal state");
        SyncEvent addAfterGap = events(p.a).getLast();
        assertEquals(RelationCausalEvents.ACTION, addAfterGap.eventType());
        var action = RelationCausalEvents.parseAction(addAfterGap.payloadJson());
        String localStream = RelationCausalEvents.nativeStream(p.a.db.site().getId());
        assertTrue(action.context().getOrDefault(localStream, 0L) >= 2,
                "The new action's context includes the locally committed, never-exported REMOVE dot");
        assertTrue(action.state().removeFrontier().stream().anyMatch(dot -> dot.stream().equals(localStream)
                        && dot.counter() == 2),
                "The self-contained action carries the skipped REMOVE frontier");

        // Deliver only the latest action. The peer must not wait for an event that was never allowed into Outbox.
        receive(p.b, addAfterGap);
        process(p.b);
        assertEquals(1, count(p.b, "SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=? AND status='APPLIED'",
                addAfterGap.eventId()));
        assertTrue(present(p, p.a, RelationKind.follow));
        assertTrue(present(p, p.b, RelationKind.follow));
        assertTrue(new RelationCausalStore(p.b.db.runtime()).load(action.naturalId()).removes().stream()
                .anyMatch(dot -> dot.stream().equals(localStream) && dot.counter() == 2),
                "The receiver retains the REMOVE evidence imported from the self-contained action");
        assertEquals(3, count(p.a, "SELECT count(*) FROM eatwhat_sync.relation_operation"),
                "The local V1 evidence ledger also retains the unpublished REMOVE dot");
        assertEquals(2, count(p.b, "SELECT count(*) FROM eatwhat_sync.relation_operation"));
        assertEquals(2, count(p.a, "SELECT count(*) FROM notifications"),
                "The later explicit authorized ADD preserves normal notification behavior");
        assertEquals(2, count(p.b, "SELECT count(*) FROM notifications"));
    }

    @Test void causalMergeRejectsWitnessContextRegressionAndCoveredOlderWitnessIsIdempotent() throws Exception {
        Pair p = pair();
        RelationCausalStore store = new RelationCausalStore(p.a.db.runtime());
        UUID actorGid = globalId(p.a, "users", p.actor);
        UUID ownerGid = globalId(p.a, "users", p.owner);
        UUID foodGid = globalId(p.a, "foods", p.food);
        String campus = RelationCausalEvents.nativeStream(p.a.db.site().getId());
        String external = RelationCausalEvents.nativeStream(p.b.db.site().getId());

        UUID followNatural = RelationStore.naturalId(RelationKind.follow, actorGid, ownerGid, ownerGid, null);
        var followIdentity = new RelationCausalStore.Identity(followNatural, RelationKind.follow, actorGid,
                ownerGid, ownerGid, null, 1, 1, 1_000_000_101L, UUID.randomUUID(), p.a.db.site().getId());
        tx(p.a, () -> store.ensure(followIdentity, false, false, true));
        UUID observedRemove = UUID.randomUUID();
        UUID earlierAdd = UUID.randomUUID();
        var prior = bridge(followIdentity, Map.of(campus, 1L, external, 5L),
                List.of(new RelationCausalEvents.Dot(campus, 1, earlierAdd, Map.of(external, 5L))),
                List.of(new RelationCausalEvents.Dot(external, 5, observedRemove, Map.of(external, 4L))));
        assertDoesNotThrow(() -> RelationCausalEvents.parseBridge(RelationCausalEvents.payload(prior)));
        tx(p.a, () -> store.mergeBridge(prior));
        assertTrue(causalState(p.a, store, followNatural).present(), "The earlier ADD observed the known REMOVE");

        long factsBeforeRegression = count(p.a, "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?",
                followNatural);
        RelationCausalStore.State beforeRegression = causalState(p.a, store, followNatural);
        var regressedA2 = bridge(followIdentity, Map.of(campus, 2L),
                List.of(new RelationCausalEvents.Dot(campus, 2, UUID.randomUUID(), Map.of(campus, 1L))), List.of());
        assertDoesNotThrow(() -> RelationCausalEvents.parseBridge(RelationCausalEvents.payload(regressedA2)),
                "A2 is a valid standalone summary when it has its own A1 predecessor");
        assertThrows(IllegalStateException.class, () -> tx(p.a, () -> store.mergeBridge(regressedA2)),
                "A higher A-stream witness may not discard B5 that known A1 had observed");
        assertEquals(beforeRegression, causalState(p.a, store, followNatural));
        assertEquals(factsBeforeRegression, count(p.a,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", followNatural),
                "A rejected context regression cannot append a fact");

        var validA2 = bridge(followIdentity, Map.of(campus, 2L, external, 5L),
                List.of(new RelationCausalEvents.Dot(campus, 2, UUID.randomUUID(),
                        Map.of(campus, 1L, external, 5L))),
                List.of(new RelationCausalEvents.Dot(external, 5, observedRemove, Map.of(external, 4L))));
        assertDoesNotThrow(() -> RelationCausalEvents.parseBridge(RelationCausalEvents.payload(validA2)));
        tx(p.a, () -> store.mergeBridge(validA2));
        RelationCausalStore.State afterA2 = causalState(p.a, store, followNatural);
        var coveredOldA1 = bridge(followIdentity, Map.of(campus, 1L, external, 5L),
                List.of(new RelationCausalEvents.Dot(campus, 1, earlierAdd, Map.of(external, 5L))),
                List.of(new RelationCausalEvents.Dot(external, 5, observedRemove, Map.of(external, 4L))));
        assertDoesNotThrow(() -> RelationCausalEvents.parseBridge(RelationCausalEvents.payload(coveredOldA1)));
        tx(p.a, () -> store.mergeBridge(coveredOldA1));
        assertEquals(afterA2, causalState(p.a, store, followNatural),
                "A stale A1 whose context is covered by A2 is an idempotent replay");

        UUID favoriteNatural = RelationStore.naturalId(RelationKind.favorite, actorGid, foodGid, ownerGid, null);
        var favoriteIdentity = new RelationCausalStore.Identity(favoriteNatural, RelationKind.favorite, actorGid,
                foodGid, ownerGid, null, 1, 1, 1_000_000_102L, UUID.randomUUID(), p.a.db.site().getId());
        tx(p.a, () -> store.ensure(favoriteIdentity, false, false, true));
        var a2ThatObservedB5 = bridge(favoriteIdentity, Map.of(campus, 2L, external, 5L),
                List.of(new RelationCausalEvents.Dot(campus, 2, UUID.randomUUID(),
                        Map.of(campus, 1L, external, 5L))),
                List.of(new RelationCausalEvents.Dot(external, 5, UUID.randomUUID(), Map.of(external, 4L))));
        assertDoesNotThrow(() -> RelationCausalEvents.parseBridge(RelationCausalEvents.payload(a2ThatObservedB5)));
        tx(p.a, () -> store.mergeBridge(a2ThatObservedB5));
        RelationCausalStore.State knownA2 = causalState(p.a, store, favoriteNatural);
        long favoriteFactsBefore = count(p.a,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", favoriteNatural);
        var olderA1WithUnobservedB6 = bridge(favoriteIdentity, Map.of(campus, 1L, external, 6L),
                List.of(new RelationCausalEvents.Dot(campus, 1, UUID.randomUUID(), Map.of(external, 6L))),
                List.of(new RelationCausalEvents.Dot(external, 6, UUID.randomUUID(), Map.of(external, 5L))));
        assertDoesNotThrow(() -> RelationCausalEvents.parseBridge(
                RelationCausalEvents.payload(olderA1WithUnobservedB6)));
        assertThrows(IllegalStateException.class,
                () -> tx(p.a, () -> store.mergeBridge(olderA1WithUnobservedB6)),
                "An older A1 cannot claim a B6 observation that known A2 does not contain");
        assertEquals(knownA2, causalState(p.a, store, favoriteNatural));
        assertEquals(favoriteFactsBefore, count(p.a,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", favoriteNatural));
    }

    @Test void multiLegacyEpochsRequireANativeProofToResolveAndReplicate() throws Exception {
        Pair p = pair();
        change(p, p.a, RelationKind.follow, true);
        exchange(p);
        SyncEvent first = events(p.a).stream()
                .filter(event -> RelationCausalEvents.ACTION.equals(event.eventType())).findFirst().orElseThrow();
        RelationEvents.Operation current = v1Evidence(first);
        RelationCausalStore aStore = new RelationCausalStore(p.a.db.runtime());
        RelationCausalStore bStore = new RelationCausalStore(p.b.db.runtime());
        UUID naturalId = RelationStore.naturalId(current.kind(), current.actor(), current.target(),
                current.owner(), current.tag());

        RelationCausalStore.State initial = causalState(p.a, aStore, naturalId);
        RelationCausalStore.Identity identity = initial.identity();
        RelationEvents.Operation oldEpochOne = legacyOperation(current, 7, 7, p.a.db.site().getId());
        RelationEvents.Operation oldEpochTwo = legacyOperation(current, 8, 8, p.a.db.site().getId());
        UUID oldFactOne = UUID.randomUUID();
        UUID oldFactTwo = UUID.randomUUID();
        for (RelationCausalStore store : List.of(aStore, bStore)) {
            Site site = store == aStore ? p.a : p.b;
            tx(site, () -> {
                store.mergeLegacy(oldEpochOne, oldFactOne);
                store.mergeLegacy(oldEpochTwo, oldFactTwo);
                return null;
            });
        }

        RelationCausalStore.State blockedA = causalState(p.a, aStore, naturalId);
        RelationCausalStore.State blockedB = causalState(p.b, bStore, naturalId);
        assertEquals("MULTI_LEGACY", blockedA.blockedReason());
        assertEquals("MULTI_LEGACY", blockedB.blockedReason());
        assertTrue(blockedA.present(), "The matching public state does not prove cross-epoch ordering");

        // An empty bridge and a bridge carrying only the original legacy proofs cannot choose an
        // ordering for distinct V1 relation IDs, even when their materialized presence agrees.
        RelationCausalEvents.Bridge empty = bridge(identity, Map.of(), List.of(), List.of());
        assertFalse(tx(p.a, () -> aStore.canApplyBridge(empty)));
        RelationCausalEvents.Bridge legacyOnly = bridge(identity, blockedA.seen(), blockedA.adds(), blockedA.removes());
        assertFalse(tx(p.a, () -> aStore.canApplyBridge(legacyOnly)));
        assertEquals(blockedA, causalState(p.a, aStore, naturalId));

        // The user explicitly confirms the present relation. This is a real same-value action,
        // whose native witness covers every retained legacy epoch and can safely bridge the proof.
        assertFalse(changeResult(p, p.a, RelationKind.follow, true),
                "The historical follow already exists, preserving the duplicate-follow API result");
        SyncEvent resolvingAction = events(p.a).getLast();
        RelationCausalEvents.Action action = RelationCausalEvents.parseAction(resolvingAction.payloadJson());
        assertEquals(RelationCausalEvents.ACTION, resolvingAction.eventType());
        assertTrue(action.state().addWitnesses().stream().anyMatch(dot ->
                        dot.stream().startsWith("native|")
                                && dot.context().getOrDefault(RelationCausalEvents.legacyStream(
                                        oldEpochOne.relationId(), oldEpochOne.dotSite()), 0L) >= 1
                                && dot.context().getOrDefault(RelationCausalEvents.legacyStream(
                                        oldEpochTwo.relationId(), oldEpochTwo.dotSite()), 0L) >= 1),
                "The action's native proof carries both old relation-id frontiers");
        receive(p.b, resolvingAction);
        process(p.b);
        assertEquals(1, count(p.b, "SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=? AND status='APPLIED'",
                resolvingAction.eventId()));
        assertNull(causalState(p.a, aStore, naturalId).blockedReason());
        assertNull(causalState(p.b, bStore, naturalId).blockedReason());
        assertTrue(present(p, p.a, RelationKind.follow));
        assertTrue(present(p, p.b, RelationKind.follow));
        assertEquals(1, count(p.a, "SELECT count(*) FROM notifications"),
                "Same-value resolution does not create a historical notice");
        assertEquals(1, count(p.b, "SELECT count(*) FROM notifications"));

        RelationCausalStore.State resolvedAtB = causalState(p.b, bStore, naturalId);
        long resolvedFactsAtB = count(p.b,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", naturalId);
        tx(p.b, () -> bStore.mergeBridge(legacyOnly));
        assertNull(causalState(p.b, bStore, naturalId).blockedReason(),
                "A delayed legacy-only bridge cannot re-block a relation whose existing native witness covers it");
        assertEquals(resolvedAtB, causalState(p.b, bStore, naturalId),
                "Replaying the covered legacy bridge is idempotent");
        assertEquals(resolvedFactsAtB, count(p.b,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", naturalId));

        long noticesBeforeRemove = count(p.a, "SELECT count(*) FROM notifications");
        assertTrue(changeResult(p, p.a, RelationKind.follow, false));
        SyncEvent firstRemove = events(p.a).getLast();
        assertFalse(v1Evidence(firstRemove).adding());
        receive(p.b, firstRemove);
        process(p.b);
        assertFalse(present(p, p.a, RelationKind.follow));
        assertFalse(present(p, p.b, RelationKind.follow));

        RelationEvents.Operation uncoveredLaterEpoch = legacyOperation(current, 9, 9,
                p.a.db.site().getId());
        tx(p.a, () -> aStore.mergeLegacy(uncoveredLaterEpoch, UUID.randomUUID()));
        RelationCausalStore.State withUncoveredEpoch = causalState(p.a, aStore, naturalId);
        assertEquals("MULTI_LEGACY", withUncoveredEpoch.blockedReason(),
                "A genuinely new, unobserved legacy epoch cannot inherit proof from the earlier resolution");
        tx(p.b, () -> bStore.mergeBridge(aStore.bridge(withUncoveredEpoch)));
        assertEquals("MULTI_LEGACY", causalState(p.b, bStore, naturalId).blockedReason(),
                "The newly uncovered legacy frontier remains blocked when forwarded");

        assertFalse(present(p, p.a, RelationKind.follow));
        assertFalse(present(p, p.b, RelationKind.follow));
        p.b.db.admin().update("UPDATE eatwhat_consent.subject SET valid_until=NULL WHERE user_id=?", p.owner);
        int campusOutboxBeforeConfirmedRemove = events(p.a).size();
        int peerOutboxBeforeConfirmedRemove = events(p.b).size();
        long factsBeforeConfirmedRemove = count(p.b,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", naturalId);
        assertFalse(changeResult(p, p.b, RelationKind.follow, false),
                "Repeating an already-satisfied unfollow preserves the old boolean API result");
        assertEquals(peerOutboxBeforeConfirmedRemove, events(p.b).size(),
                "An owner TTL wait cannot publish the blocked confirmation early");
        assertEquals(factsBeforeConfirmedRemove + 1, count(p.b,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", naturalId),
                "The explicit REMOVE is still durably recorded while its owner is unavailable");
        var dirtyIntent = p.b.db.runtime().queryForList("""
                SELECT intent_revision,published_revision FROM eatwhat_sync.relation_intent WHERE natural_id=?
                """, naturalId).getFirst();
        assertTrue(((Number) dirtyIntent.get("intent_revision")).longValue()
                        > ((Number) dirtyIntent.get("published_revision")).longValue(),
                "The new same-value causal action must leave a dirty revision for recovery");
        p.b.db.admin().update("UPDATE eatwhat_consent.subject SET valid_until=clock_timestamp()+interval '5 minutes' WHERE user_id=?",
                p.owner);
        drainBackfill(p.b, p.actor, p.b.consent.get(p.actor).epoch());
        SyncEvent resolvingRemove = events(p.b).stream()
                .filter(event -> RelationCausalEvents.BRIDGE.equals(event.eventType()))
                .filter(event -> RelationCausalEvents.parseBridge(event.payloadJson()).naturalId().equals(naturalId))
                .reduce((firstEvent, secondEvent) -> secondEvent).orElseThrow();
        RelationCausalEvents.Bridge removeBridge = RelationCausalEvents.parseBridge(resolvingRemove.payloadJson());
        assertTrue(removeBridge.removeFrontier().stream().anyMatch(dot -> dot.context().getOrDefault(
                RelationCausalEvents.legacyStream(uncoveredLaterEpoch.relationId(),
                        uncoveredLaterEpoch.dotSite()), 0L) >= 1),
                "Reauthorization publishes the precise blocked REMOVE instead of losing its causal revision");
        assertNull(notification(resolvingRemove));
        assertEquals(campusOutboxBeforeConfirmedRemove, events(p.a).size(),
                "The separate campus action stream remains unchanged while the peer recovers its own intent");
        receive(p.a, resolvingRemove);
        process(p.a);
        assertNull(causalState(p.a, aStore, naturalId).blockedReason());
        assertNull(causalState(p.b, bStore, naturalId).blockedReason());
        assertFalse(present(p, p.a, RelationKind.follow));
        assertFalse(present(p, p.b, RelationKind.follow));
        assertEquals(noticesBeforeRemove, count(p.a, "SELECT count(*) FROM notifications"));

        /*
         * Keep these assertions near the recovery edge: it is intentionally a state bridge, not
         * a second user action, so no V1 operation or historical notification is fabricated.
         */

    }

    @Test void waitingV1EpochFactCanBeReleasedByExplicitNativeContextWithoutDuplicateNotice() throws Exception {
        Pair p = pair();
        change(p, p.b, RelationKind.follow, true);
        SyncEvent originalAction = events(p.b).stream()
                .filter(event -> RelationCausalEvents.ACTION.equals(event.eventType()))
                .findFirst().orElseThrow();
        RelationEvents.Operation original = v1Evidence(originalAction);
        SyncEvent oldEpochV1 = legacyV1Wire(originalAction);
        receive(p.a, oldEpochV1);
        process(p.a);
        assertEquals(1, count(p.a,
                "SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=? AND status='APPLIED'", oldEpochV1.eventId()));
        assertTrue(present(p, p.a, RelationKind.follow));

        p.a.consent.withdraw(p.actor);
        var purge = p.a.consent.claim(32, LEASE).stream()
                .filter(job -> job.userId() == p.actor && job.kind().equals("PURGE"))
                .findFirst().orElseThrow();
        assertEquals("APPLIED", p.b.consent.apply(p.a.consent.export(purge)));
        p.a.consent.complete(purge);
        var grant = p.a.consent.grant(p.actor, POLICY);
        var upsert = p.a.consent.claim(32, LEASE).stream()
                .filter(job -> job.userId() == p.actor && job.epoch() == grant.epoch()
                        && job.kind().equals("UPSERT"))
                .findFirst().orElseThrow();
        assertEquals("APPLIED", p.b.consent.apply(p.a.consent.export(upsert)));
        p.a.consent.complete(upsert);

        // Rebuild the retained logical ADD into A's public projection before introducing the
        // distinct current-epoch legacy REMOVE. The waiting REMOVE must preserve that projection.
        drainBackfill(p.a, p.actor, grant.epoch());
        assertTrue(present(p, p.a, RelationKind.follow));
        long publicRowsBeforeWaiting = count(p.a,
                "SELECT count(*) FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner);
        long noticesBeforeWaiting = count(p.a, "SELECT count(*) FROM notifications");

        long currentActorEpoch = p.b.consent.get(p.actor).epoch();
        long currentOwnerEpoch = p.b.consent.get(p.owner).epoch();
        RelationEvents.Operation currentEpochRemove = new RelationEvents.Operation(original.kind(), original.actor(),
                original.target(), original.owner(), original.tag(), currentActorEpoch, currentOwnerEpoch,
                original.rowId(), original.rowGid(), original.rowOrigin(), Map.of(), p.b.db.site().getId(),
                1, false, null);
        SyncEvent currentEpochV1 = legacyV1Wire(p.b, currentEpochRemove);
        receive(p.a, currentEpochV1);
        process(p.a);
        assertEquals(1, count(p.a, "SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=? "
                + "AND status='WAITING_DEPENDENCY'", currentEpochV1.eventId()),
                "A distinct legacy relation ID remains ambiguous until a native action covers both histories");
        RelationCausalStore aStore = new RelationCausalStore(p.a.db.runtime());
        UUID naturalId = RelationStore.naturalId(original.kind(), original.actor(), original.target(),
                original.owner(), original.tag());
        RelationCausalStore.State blocked = causalState(p.a, aStore, naturalId);
        assertEquals("MULTI_LEGACY", blocked.blockedReason());
        assertFalse(blocked.present(), "The waiting REMOVE is retained in causal state");
        assertTrue(present(p, p.a, RelationKind.follow),
                "A blocked legacy merge must leave the public projection unchanged");
        assertEquals(publicRowsBeforeWaiting, count(p.a,
                "SELECT count(*) FROM follow WHERE account_id=? AND target_id=?", p.actor, p.owner));
        assertEquals(noticesBeforeWaiting, count(p.a, "SELECT count(*) FROM notifications"));
        List<Map<String, Object>> factsWithWaitingV1 = causalFacts(p.a, naturalId);
        assertEquals(2, factsWithWaitingV1.size(),
                "Both the original and waiting V1 events are durably archived before projection");
        long noticesBeforeConfirmation = count(p.a, "SELECT count(*) FROM notifications");
        long operationsBeforeConfirmation = count(p.a,
                "SELECT count(*) FROM eatwhat_sync.relation_operation");

        assertFalse(changeResult(p, p.a, RelationKind.follow, true),
                "The explicit confirmation retains the duplicate-follow API result");
        SyncEvent nativeConfirmation = events(p.a).stream()
                .filter(event -> RelationCausalEvents.ACTION.equals(event.eventType()))
                .reduce((first, second) -> second).orElseThrow();
        RelationCausalEvents.Action confirmation = RelationCausalEvents.parseAction(nativeConfirmation.payloadJson());
        assertTrue(confirmation.context().getOrDefault(RelationCausalEvents.legacyStream(
                original.relationId(), original.dotSite()), 0L) >= original.dotCounter());
        assertTrue(confirmation.context().getOrDefault(RelationCausalEvents.legacyStream(
                currentEpochRemove.relationId(), currentEpochRemove.dotSite()), 0L)
                >= currentEpochRemove.dotCounter());
        assertNull(notification(nativeConfirmation), "Same-value confirmation does not generate a new notice");

        p.a.inbox.requeueWaiting(100, Duration.ZERO);
        process(p.a);
        assertEquals(1, count(p.a, "SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=? AND status='APPLIED'",
                currentEpochV1.eventId()), "The same immutable V1 event applies after native confirmation");
        assertNull(causalState(p.a, aStore, naturalId).blockedReason());
        assertTrue(present(p, p.a, RelationKind.follow));
        assertEquals(factsWithWaitingV1.size() + 1, causalFacts(p.a, naturalId).size(),
                "Retrying the V1 event does not append a second copy of its immutable fact");
        assertEquals(operationsBeforeConfirmation + 1, count(p.a,
                "SELECT count(*) FROM eatwhat_sync.relation_operation"),
                "Only the explicit native command adds operation evidence");
        assertEquals(noticesBeforeConfirmation, count(p.a, "SELECT count(*) FROM notifications"));

        RelationCausalStore.State beforeStale = causalState(p.a, aStore, naturalId);
        List<Map<String, Object>> factsBeforeStale = causalFacts(p.a, naturalId);
        RelationEvents.Operation staleOldEpoch = new RelationEvents.Operation(original.kind(), original.actor(),
                original.target(), original.owner(), original.tag(), original.actorEpoch(), original.ownerEpoch(),
                original.rowId(), original.rowGid(), original.rowOrigin(),
                Map.of(original.dotSite(), 1L),
                original.dotSite(), 2, false, null);
        SyncEvent staleEvent = legacyV1Wire(p.b, staleOldEpoch);
        receive(p.a, staleEvent);
        process(p.a);
        assertEquals(1, count(p.a, "SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=? AND status='APPLIED'",
                staleEvent.eventId()), "A stale old-epoch V1 event is acknowledged as a stale no-op");
        assertEquals(beforeStale, causalState(p.a, aStore, naturalId));
        assertEquals(factsBeforeStale, causalFacts(p.a, naturalId));
        assertEquals(noticesBeforeConfirmation, count(p.a, "SELECT count(*) FROM notifications"));
    }

    @Test void v1EventWithMissingPredecessorCannotAdvanceTheLegacyStream() throws Exception {
        Pair p = pair();
        change(p, p.b, RelationKind.favorite, true);
        SyncEvent originalAction = events(p.b).stream()
                .filter(event -> RelationCausalEvents.ACTION.equals(event.eventType()))
                .findFirst().orElseThrow();
        RelationEvents.Operation original = v1Evidence(originalAction);
        SyncEvent firstV1 = legacyV1Wire(originalAction);
        receive(p.a, firstV1);
        process(p.a);
        assertEquals(1, count(p.a,
                "SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=? AND status='APPLIED'", firstV1.eventId()));

        UUID naturalId = RelationStore.naturalId(original.kind(), original.actor(), original.target(),
                original.owner(), original.tag());
        RelationCausalStore store = new RelationCausalStore(p.a.db.runtime());
        RelationCausalStore.State beforeGap = causalState(p.a, store, naturalId);
        String legacyStream = RelationCausalEvents.legacyStream(original.relationId(), original.dotSite());
        assertEquals(1, beforeGap.seen().getOrDefault(legacyStream, 0L));
        RelationEvents.Operation missingSecond = new RelationEvents.Operation(original.kind(), original.actor(),
                original.target(), original.owner(), original.tag(), original.actorEpoch(), original.ownerEpoch(),
                original.rowId(), original.rowGid(), original.rowOrigin(), Map.of(original.dotSite(), 2L),
                original.dotSite(), 3, false, null);
        SyncEvent gapEvent = legacyV1Wire(p.b, missingSecond);
        receive(p.a, gapEvent);
        process(p.a);

        assertEquals(1, count(p.a, "SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=? "
                + "AND status='WAITING_DEPENDENCY'", gapEvent.eventId()));
        RelationCausalStore.State afterGap = causalState(p.a, store, naturalId);
        assertEquals(beforeGap.seen(), afterGap.seen(),
                "A V1 event cannot claim counter 3 while counter 2 is absent");
        assertEquals(beforeGap.adds(), afterGap.adds());
        assertEquals(beforeGap.removes(), afterGap.removes());
        assertEquals(beforeGap.present(), afterGap.present());
        assertTrue(present(p, p.a, RelationKind.favorite));
        assertEquals(1, afterGap.seen().getOrDefault(legacyStream, 0L));
    }

    @Test void localContextUnknownCannotBeClearedByEmptyOrRemoteBridge() throws Exception {
        Pair p = pair();
        RelationCausalStore store = new RelationCausalStore(p.a.db.runtime());
        UUID actor = globalId(p.a, "users", p.actor);
        UUID owner = globalId(p.a, "users", p.owner);
        UUID food = globalId(p.a, "foods", p.food);
        UUID naturalId = RelationStore.naturalId(RelationKind.favorite, actor, food, owner, null);
        RelationCausalStore.Identity identity = new RelationCausalStore.Identity(naturalId,
                RelationKind.favorite, actor, food, owner, null, 1, 1, 1_000_000_303L,
                UUID.randomUUID(), p.a.db.site().getId());
        tx(p.a, () -> store.ensure(identity, false, false, false));
        RelationCausalStore.State unknown = causalState(p.a, store, naturalId);
        assertEquals("LOCAL_CONTEXT_UNKNOWN", unknown.blockedReason());

        RelationCausalEvents.Bridge empty = bridge(identity, Map.of(), List.of(), List.of());
        assertFalse(tx(p.a, () -> store.canApplyBridge(empty)),
                "An empty bridge cannot convert an unknown local boolean into shared intent");

        String peerStream = RelationCausalEvents.nativeStream(p.b.db.site().getId());
        UUID remoteActionId = UUID.randomUUID();
        RelationCausalEvents.Dot remoteAdd = new RelationCausalEvents.Dot(peerStream, 1, remoteActionId, Map.of());
        RelationCausalEvents.Bridge remoteProof = bridge(identity, Map.of(peerStream, 1L),
                List.of(remoteAdd), List.of());
        assertFalse(tx(p.a, () -> store.canApplyBridge(remoteProof)),
                "A remote native proof cannot overwrite unrepresented local state");
        assertEquals(unknown, causalState(p.a, store, naturalId));
        assertEquals(0, count(p.a, "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", naturalId));
        assertEquals(0, events(p.a).size());
    }

    @Test void mergingTwoSeparateSingleLegacyBridgesCreatesMultiLegacyBlocker() throws Exception {
        Pair p = pair();
        RelationCausalStore aStore = new RelationCausalStore(p.a.db.runtime());
        RelationCausalStore bStore = new RelationCausalStore(p.b.db.runtime());
        UUID actor = globalId(p.a, "users", p.actor);
        UUID owner = globalId(p.a, "users", p.owner);
        UUID food = globalId(p.a, "foods", p.food);
        UUID naturalId = RelationStore.naturalId(RelationKind.favorite, actor, food, owner, null);
        RelationCausalStore.Identity identity = new RelationCausalStore.Identity(naturalId,
                RelationKind.favorite, actor, food, owner, null, 1, 1, 1_000_000_304L,
                UUID.randomUUID(), p.a.db.site().getId());
        tx(p.a, () -> aStore.ensure(identity, false, false, true));
        tx(p.b, () -> bStore.ensure(identity, false, false, true));

        RelationEvents.Operation epochOne = syntheticLegacyOperation(identity, 11, 11, p.a.db.site().getId(), true);
        RelationEvents.Operation epochTwo = syntheticLegacyOperation(identity, 12, 12, p.a.db.site().getId(), false);
        tx(p.a, () -> aStore.mergeLegacy(epochOne, UUID.randomUUID()));
        RelationCausalEvents.Bridge firstLegacyBridge = aStore.bridge(causalState(p.a, aStore, naturalId));
        tx(p.b, () -> bStore.mergeBridge(firstLegacyBridge));
        assertNull(causalState(p.b, bStore, naturalId).blockedReason(),
                "One legacy relation ID is still independently orderable");
        RelationCausalStore.State beforeForgedReplay = causalState(p.b, bStore, naturalId);
        long factsBeforeForgedReplay = count(p.b,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", naturalId);
        RelationCausalEvents.Dot original = firstLegacyBridge.addWitnesses().getFirst();
        RelationCausalEvents.Bridge forgedSameDot = bridge(identity, firstLegacyBridge.seen(), List.of(),
                List.of(new RelationCausalEvents.Dot(original.stream(), original.counter(), original.eventId(),
                        original.context())));
        assertThrows(IllegalStateException.class, () -> tx(p.b, () -> bStore.mergeBridge(forgedSameDot)),
                "The same immutable event/dot cannot be replayed as a REMOVE instead of its original ADD");
        assertEquals(beforeForgedReplay, causalState(p.b, bStore, naturalId));
        assertEquals(factsBeforeForgedReplay, count(p.b,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", naturalId),
                "A conflicting proof variant rolls back the entire bridge merge");

        tx(p.a, () -> aStore.mergeLegacy(epochTwo, UUID.randomUUID()));
        RelationCausalStore.State twoEpochs = causalState(p.a, aStore, naturalId);
        assertEquals("MULTI_LEGACY", twoEpochs.blockedReason());
        tx(p.b, () -> bStore.mergeBridge(aStore.bridge(twoEpochs)));
        assertEquals("MULTI_LEGACY", causalState(p.b, bStore, naturalId).blockedReason(),
                "Bridge union must classify distinct consent-derived V1 relation IDs as ambiguous");
    }

    @Test void inconsistentPublicProjectionAndLegacyTruthIsBlockedAsLocalUnknown() throws Exception {
        Pair p = pair();
        RelationCausalStore store = new RelationCausalStore(p.a.db.runtime());
        UUID actor = globalId(p.a, "users", p.actor);
        UUID owner = globalId(p.a, "users", p.owner);
        UUID food = globalId(p.a, "foods", p.food);
        UUID naturalId = RelationStore.naturalId(RelationKind.favorite, actor, food, owner, null);
        RelationCausalStore.Identity identity = new RelationCausalStore.Identity(naturalId,
                RelationKind.favorite, actor, food, owner, null, 1, 1, 1_000_000_305L,
                UUID.randomUUID(), p.a.db.site().getId());
        RelationEvents.Operation oldAdd = syntheticLegacyOperation(identity, 21, 21,
                p.a.db.site().getId(), true);
        store.recordV1Evidence(UUID.randomUUID(), oldAdd);

        RelationCausalStore.State imported = tx(p.a, () -> store.ensure(identity, false, false, false));
        assertTrue(imported.present(), "The old operation history proves a prior ADD");
        assertEquals("LOCAL_CONTEXT_UNKNOWN", imported.blockedReason(),
                "A missing public row cannot be guessed as purge or user cancellation");
        assertEquals(0, count(p.a, "SELECT count(*) FROM food_favorites WHERE account_id=? AND food_id=?",
                p.actor, p.food));
        assertEquals(1, count(p.a,
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE natural_id=?", naturalId));
        assertTrue(events(p.a).isEmpty(), "Ambiguous imported history cannot be published as an action");
    }

    @Test void peerOnlyNativeWitnessCanBeForwardedInAStateBridge() throws Exception {
        Pair p = pair();
        change(p, p.a, RelationKind.follow, true);
        SyncEvent original = events(p.a).getFirst();
        receive(p.b, original);
        process(p.b);
        assertTrue(present(p, p.b, RelationKind.follow));

        p.a.consent.withdraw(p.actor);
        var purge = p.a.consent.claim(32, LEASE).stream()
                .filter(job -> job.userId() == p.actor && job.kind().equals("PURGE"))
                .findFirst().orElseThrow();
        assertEquals("APPLIED", p.b.consent.apply(p.a.consent.export(purge)));
        p.a.consent.complete(purge);
        var grant = p.a.consent.grant(p.actor, POLICY);
        var upsert = p.a.consent.claim(32, LEASE).stream()
                .filter(job -> job.userId() == p.actor && job.epoch() == grant.epoch()
                        && job.kind().equals("UPSERT"))
                .findFirst().orElseThrow();
        assertEquals("APPLIED", p.b.consent.apply(p.a.consent.export(upsert)));
        p.a.consent.complete(upsert);

        drainBackfill(p.b, p.actor, grant.epoch());
        SyncEvent forwarded = events(p.b).stream()
                .filter(event -> RelationCausalEvents.BRIDGE.equals(event.eventType())).findFirst().orElseThrow();
        RelationCausalEvents.Bridge bridge = RelationCausalEvents.parseBridge(forwarded.payloadJson());
        String campus = RelationCausalEvents.nativeStream(p.a.db.site().getId());
        String external = RelationCausalEvents.nativeStream(p.b.db.site().getId());
        assertTrue(forwarded.originSite().equals(p.b.db.site().getId()));
        assertEquals(1, bridge.seen().getOrDefault(campus, 0L));
        assertEquals(0, bridge.seen().getOrDefault(external, 0L),
                "A forwarding peer need not invent a local action to relay another site's witness");
        assertTrue(bridge.addWitnesses().stream().anyMatch(dot -> dot.stream().equals(campus)));
        assertTrue(bridge.addWitnesses().stream().noneMatch(dot -> dot.stream().equals(external)));

        receive(p.a, forwarded);
        process(p.a);
        assertEquals(1, count(p.a, "SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=? AND status='APPLIED'",
                forwarded.eventId()));
        assertTrue(present(p, p.a, RelationKind.follow));
        assertTrue(present(p, p.b, RelationKind.follow));
    }

    private static RelationEvents.Operation syntheticLegacyOperation(RelationCausalStore.Identity identity,
                                                                      long actorEpoch, long ownerEpoch,
                                                                      String dotSite, boolean adding) {
        return new RelationEvents.Operation(identity.kind(), identity.actor(), identity.target(), identity.owner(),
                identity.tag(), actorEpoch, ownerEpoch, identity.rowId(), identity.rowGid(), identity.rowOrigin(),
                Map.of(), dotSite, 1, adding, null);
    }

    private static RelationEvents.Operation legacyOperation(RelationEvents.Operation source,
                                                              long actorEpoch, long ownerEpoch, String dotSite) {
        return new RelationEvents.Operation(source.kind(), source.actor(), source.target(), source.owner(), source.tag(),
                actorEpoch, ownerEpoch, source.rowId(), source.rowGid(), source.rowOrigin(), Map.of(), dotSite, 1,
                true, null);
    }

    private static RelationCausalEvents.Bridge bridge(RelationCausalStore.Identity identity,
                                                       Map<String, Long> seen,
                                                       List<RelationCausalEvents.Dot> adds,
                                                       List<RelationCausalEvents.Dot> removes) {
        return new RelationCausalEvents.Bridge(identity.naturalId(), identity.kind(), identity.actor(),
                identity.target(), identity.owner(), identity.tag(), identity.actorEpoch(), identity.ownerEpoch(),
                identity.rowId(), identity.rowGid(), identity.rowOrigin(), seen, adds, removes, null);
    }

    private static UUID globalId(Site site, String entityType, long numericId) {
        return UUID.fromString(site.db.runtime().queryForObject("""
                SELECT global_id::text FROM eatwhat_identity.entity_identity
                WHERE entity_type=? AND numeric_id=?
                """, String.class, entityType, numericId));
    }

    private static <T> T tx(Site site, Supplier<T> action) {
        return site.db.transactions().required(action);
    }

    private static RelationCausalStore.State causalState(Site site, RelationCausalStore store, UUID naturalId) {
        return tx(site, () -> store.load(naturalId));
    }
    private static List<Map<String, Object>> causalFacts(Site site, UUID naturalId) {
        return site.db.runtime().queryForList("""
                SELECT event_id::text AS event_id,natural_id::text AS natural_id,stream_key,
                       dot_counter::text AS dot_counter,context::text AS context,adding::text AS adding,
                       payload::text AS payload,legacy::text AS legacy
                FROM eatwhat_sync.relation_causal_fact WHERE natural_id=? ORDER BY event_id
                """, naturalId);
    }
    private static List<Map<String, Object>> relationV1Facts(Site site, UUID naturalId) {
        return site.db.runtime().queryForList("""
                SELECT o.event_id::text AS event_id,o.relation_id::text AS relation_id,
                       o.dot_site,o.dot_counter::text AS dot_counter,o.context::text AS context,
                       o.adding::text AS adding,o.payload::text AS payload
                FROM eatwhat_sync.relation_operation o
                JOIN eatwhat_sync.relation_causal_fact f ON f.event_id=o.event_id
                WHERE f.natural_id=? ORDER BY o.event_id
                """, naturalId);
    }
    @Test void wholeCommandRollsBackProjectionNoticeHistoryAndOutboxWhenAppendFails() throws Exception {
        Pair p=pair();p.a.db.admin().execute("CREATE FUNCTION eatwhat_sync.synthetic_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic'; END $$");
        p.a.db.admin().execute("CREATE TRIGGER synthetic_reject BEFORE INSERT ON eatwhat_sync.outbox FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.synthetic_reject()");
        assertThrows(DataAccessException.class,()->change(p,p.a,RelationKind.follow,true));
        assertFalse(present(p,p.a,RelationKind.follow));assertEquals(0,count(p.a,"SELECT count(*) FROM notifications"));
        assertEquals(0,count(p.a,"SELECT count(*) FROM eatwhat_sync.relation_operation"));
        assertEquals(0,count(p.a,"SELECT count(*) FROM eatwhat_sync.relation_projection"));
        assertEquals(0,count(p.a,"SELECT count(*) FROM eatwhat_sync.relation_causal_state"),
                "A failed Outbox append rolls back the newly-created causal state");
        assertEquals(0,count(p.a,"SELECT count(*) FROM eatwhat_sync.relation_causal_fact"),
                "A failed Outbox append leaves no immutable action fact or consumed counter");
        assertEquals(0,count(p.a,"SELECT count(*) FROM eatwhat_sync.relation_intent"),
                "The source intent and public write share the failed transaction");
        assertEquals(0,count(p.a,"SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='relation.operation'"));
    }
    @Test void parentArrivesLateAndDeletedActivityCannotBeRevivedByALateLike() throws Exception {
        Pair p=pair();p.b.db.runtime().update("DELETE FROM activity_foods WHERE id=?",p.activityFood);
        change(p,p.a,RelationKind.activity_food_like,true);var e=events(p.a).getFirst();receive(p.b,e);process(p.b);
        assertEquals(1,count(p.b,"SELECT count(*) FROM eatwhat_sync.inbox WHERE status='WAITING_DEPENDENCY'"));
        // Restore the identical fixture with its already reserved identity, then delete it before retry.
        String row=p.a.db.admin().queryForObject("SELECT row_to_json(t)::text FROM activity_foods t WHERE id=?",String.class,p.activityFood);
        try(var c=p.b.db.admin().getDataSource().getConnection()){
            c.setAutoCommit(false);try(var s=c.createStatement()){s.execute("SET LOCAL session_replication_role='replica'");}
            try(var s=c.prepareStatement("INSERT INTO activity_foods SELECT * FROM json_populate_record(NULL::activity_foods,?::json)")){s.setString(1,row);s.executeUpdate();}c.commit();
        }
        // Activity fixtures mirror the parent only; the parent's derived counter is not mirrored.
        p.b.db.runtime().update("UPDATE activity_foods SET likes_count=0 WHERE id=?",p.activityFood);
        p.b.db.runtime().update("UPDATE activity_foods SET deleted_at=now() WHERE id=?",p.activityFood);
        p.b.inbox.requeueWaiting(100,Duration.ZERO);process(p.b);
        assertFalse(present(p,p.b,RelationKind.activity_food_like));
        assertEquals(0,count(p.b,"SELECT likes_count FROM activity_foods WHERE id=?",p.activityFood),
                "A late relation must not change the activity's unmirrored derived counter");
    }
    @Test void withdrawalAndRegrantDoNotApplyAnOldAuthorizationEpoch() throws Exception {
        Pair p=pair();change(p,p.a,RelationKind.follow,true);var event=events(p.a).getFirst();
        p.a.consent.withdraw(p.actor);var granted=p.a.consent.grant(p.actor,POLICY);
        var job=p.a.consent.claim(32,LEASE).stream().filter(j->j.userId()==p.actor && j.epoch()==granted.epoch() && j.kind().equals("UPSERT")).findFirst().orElseThrow();
        assertEquals("APPLIED",p.b.consent.apply(p.a.consent.export(job)));p.a.consent.complete(job);
        receive(p.b,event);process(p.b);assertFalse(present(p,p.b,RelationKind.follow));
        assertEquals(1,count(p.b,"SELECT count(*) FROM eatwhat_sync.inbox WHERE status='APPLIED'"));
        assertEquals(0,count(p.b,"SELECT count(*) FROM notifications"));
    }
    @Test void outboundAuthorizationRequiresBothCurrentGrantedSubjectsAndExternalExpiry() throws Exception {
        Pair p=pair();change(p,p.a,RelationKind.follow,true);
        var op=v1Evidence(events(p.a).getFirst());
        assertTrue(authorization(p.a,op).allowed());assertFalse(authorization(p.a,op).blocked());
        assertTrue(authorization(p.b,op).allowed());assertFalse(authorization(p.b,op).blocked());
        assertFalse(authorization(p.b,new RelationEvents.Operation(op.kind(),UUID.randomUUID(),op.target(),op.owner(),null,
                op.actorEpoch(),op.ownerEpoch(),op.rowId(),op.rowGid(),op.rowOrigin(),op.context(),op.dotSite(),op.dotCounter(),op.adding(),null)).allowed(),
                "Missing actor subject waits without being policy blocked");
        UUID missingOwner=UUID.randomUUID();
        assertFalse(p.b.consent.outboundAuthorization(op.actor(),op.actorEpoch(),missingOwner,op.ownerEpoch()).allowed(),
                "Missing owner subject also waits");
        p.b.db.admin().update("UPDATE eatwhat_consent.subject SET valid_until=NULL WHERE user_id=?",p.actor);
        assertFalse(authorization(p.b,op).allowed(),"External authorization with no deadline waits");
        p.b.db.admin().update("UPDATE eatwhat_consent.subject SET valid_until=clock_timestamp()-interval '1 second' WHERE user_id=?",p.actor);
        assertFalse(authorization(p.b,op).allowed(),"Expired external authorization waits");
        p.b.db.admin().update("UPDATE eatwhat_consent.subject SET valid_until=clock_timestamp()+interval '1 minute' WHERE user_id=?",p.actor);
        assertTrue(authorization(p.b,op).allowed());
        p.b.consent.withdraw(p.actor);
        assertTrue(authorization(p.b,op).blocked(),"A committed withdrawal blocks the operation's old epoch");
    }
    @Test void outboundAuthorizationBlocksOwnerWithdrawalIndependently() throws Exception {
        Pair p=pair();change(p,p.a,RelationKind.follow,true);
        var op=v1Evidence(events(p.a).getFirst());
        assertTrue(authorization(p.b,op).allowed());
        p.b.consent.withdraw(p.owner);
        assertTrue(authorization(p.b,op).blocked());
    }
    static ConsentStore.OutboundAuthorization authorization(Site site,RelationEvents.Operation op){
        return site.consent.outboundAuthorization(op.actor(),op.actorEpoch(),op.owner(),op.ownerEpoch());
    }
    @Test void noConsentDoesNotExportAndRuntimeCannotRewriteTombstonesOrImportContext() throws Exception {
        Pair p=pair();p.a.consent.withdraw(p.actor);change(p,p.a,RelationKind.favorite,true);
        assertTrue(present(p,p.a,RelationKind.favorite));assertTrue(events(p.a).isEmpty());
        assertThrows(DataAccessException.class,()->p.a.db.runtime().execute("INSERT INTO eatwhat_sync.relation_projection(relation_key,entity_type,numeric_id,global_id,origin_site) VALUES('forged','likes',1000000001,gen_random_uuid(),'forged')"));
        assertThrows(DataAccessException.class,()->p.a.db.runtime().execute("INSERT INTO eatwhat_sync.relation_alias(entity_type,numeric_id,global_id,relation_key) VALUES('likes',1000000001,gen_random_uuid(),'forged')"));
        assertThrows(DataAccessException.class,()->p.a.db.runtime().execute("INSERT INTO eatwhat_sync.relation_notification(source_event_id,recipient_gid,kind,numeric_id,global_id,payload) VALUES(gen_random_uuid(),gen_random_uuid(),'like',1000000001,gen_random_uuid(),'{}')"));
        assertThrows(DataAccessException.class,()->p.a.db.runtime().execute("UPDATE eatwhat_sync.relation_alias SET relation_key='forged'"));
        assertThrows(DataAccessException.class,()->p.a.db.runtime().execute("INSERT INTO eatwhat_consent.import_context VALUES(1,1,'likes',1000000002,gen_random_uuid(),'forged',gen_random_uuid())"));
        assertThrows(DataAccessException.class,()->p.a.db.runtime().execute("SET session_replication_role='replica'"));
    }

    @Test void runtimeCannotRewriteImmutableRelationOperationFacts() throws Exception {
        Pair p=pair();change(p,p.a,RelationKind.follow,true);
        assertEquals(1,count(p.a,"SELECT count(*) FROM eatwhat_sync.relation_operation"));
        assertEquals(1,count(p.a,"SELECT count(*) FROM eatwhat_sync.relation_causal_fact"));
        assertThrows(DataAccessException.class,()->p.a.db.runtime().execute("UPDATE eatwhat_sync.relation_operation SET payload='{}'"));
        assertThrows(DataAccessException.class,()->p.a.db.runtime().execute("DELETE FROM eatwhat_sync.relation_operation"));
        assertThrows(DataAccessException.class,()->p.a.db.runtime().execute("UPDATE eatwhat_sync.relation_causal_fact SET payload='{}'"));
        assertThrows(DataAccessException.class,()->p.a.db.runtime().execute("DELETE FROM eatwhat_sync.relation_causal_fact"));
        assertThrows(DataAccessException.class,()->p.a.db.runtime().execute("TRUNCATE eatwhat_sync.relation_causal_fact"));
        assertEquals(1,count(p.a,"SELECT count(*) FROM eatwhat_sync.relation_operation"));
        assertEquals(1,count(p.a,"SELECT count(*) FROM eatwhat_sync.relation_causal_fact"));
    }
}
