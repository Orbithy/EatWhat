package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.account.ConsentModels.Job;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.identity.EntityType;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.activity.ActivityEventCapture;
import you.v50to.eatwhat.sync.activity.ActivityEventHandler;
import you.v50to.eatwhat.sync.activity.ActivityEventPolicy;
import you.v50to.eatwhat.sync.activity.ActivityEvents;
import you.v50to.eatwhat.sync.transport.InboxWorker;
import you.v50to.eatwhat.sync.transport.EventPolicy;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import you.v50to.eatwhat.sync.transport.TransportMetrics;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL on both sites; events move Outbox -> Inbox in-process (HTTPS transport is covered by PR-04). */
class ActivityReplicationIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("activity-replication");
    private static final String POLICY = "policy-2026-10";
    private static final Duration LEASE = Duration.ofSeconds(20);
    private static final EventCodec CODEC = new EventCodec();
    private static final ActivityEventPolicy ACTIVITY_POLICY = new ActivityEventPolicy();
    @BeforeAll static void start() { POSTGRES.start(); }
    @AfterAll static void stop() { POSTGRES.stop(); }

    private record Site(EventFoundationTestSupport.Database db, ConsentStore consent, OutboxService outbox,
                        DeliveryService delivery, InboxService inbox, ActivityEventCapture capture,
                        EntityIdentityService identities, AtomicBoolean inboundOpen) {
        NodeIdentity node() { return new NodeIdentity(db.site().getId(), db.site().getInstanceId()); }
    }

    private record Pair(Site campus, Site external) { }

    private static Pair pair() {
        var campusDb = database(SiteProperties.Mode.CAMPUS, "campus-activity");
        var externalDb = database(SiteProperties.Mode.EXTERNAL, "external-activity");
        for (var db : List.of(campusDb, externalDb)) {
            db.admin().update("INSERT INTO public.provinces(id,name) VALUES(1,'province-a'),(2,'province-b')");
            db.admin().update("INSERT INTO public.cities(id,province_id,name) VALUES(10,1,'city-a'),(20,2,'city-b')");
        }
        return new Pair(site(campusDb, externalDb), site(externalDb, campusDb));
    }

    private static Site site(EventFoundationTestSupport.Database db, EventFoundationTestSupport.Database peer) {
        var consent = new ConsentStore(db.runtime(), db.transactions(), db.site(), POLICY, Duration.ofMinutes(5),
                Duration.ofMinutes(2), peer.site().getId(), peer.site().getInstanceId());
        var open = new AtomicBoolean(true);
        java.util.function.LongPredicate gate = id -> open.get() && consent.replicationAllowed(id);
        var outbox = new OutboxService(db.runtime(), db.transactions(), CODEC, db.site());
        var identities = new EntityIdentityService(db.runtime());
        var handlers = new EventHandlerRegistry(List.of(
                new ActivityEventHandler(ActivityEvents.FOODS, ActivityEvents.CREATED, db.runtime(), gate),
                new ActivityEventHandler(ActivityEvents.FOODS, ActivityEvents.DELETED, db.runtime(), gate),
                new ActivityEventHandler(ActivityEvents.DINNERS, ActivityEvents.CREATED, db.runtime(), gate),
                new ActivityEventHandler(ActivityEvents.DINNERS, ActivityEvents.DELETED, db.runtime(), gate)));
        return new Site(db, consent, outbox, new DeliveryService(db.runtime(), db.transactions(), CODEC),
                new InboxService(db.runtime(), db.transactions(), CODEC, handlers),
                new ActivityEventCapture(db.runtime(), outbox, identities, db.site(), peer.site().getId(), consent::replicationAllowed),
                identities, open);
    }

    private static EventFoundationTestSupport.Database database(SiteProperties.Mode mode, String id) {
        var properties = new SiteProperties();
        properties.setMode(mode); properties.setId(id); properties.setInstanceId(UUID.randomUUID());
        var db = EventFoundationTestSupport.createDatabase(POSTGRES, properties);
        db.admin().execute("ALTER ROLE " + db.runtimeUsername() + " SET statement_timeout='12s'");
        db.admin().execute("ALTER ROLE " + db.runtimeUsername() + " SET lock_timeout='8s'");
        return db;
    }

    // ---- fixtures ------------------------------------------------------------------------------------------

    private static long user(Site site, String name) {
        return site.db().transactions().required(() -> {
            long id = site.db().runtime().queryForObject("INSERT INTO public.users(nick_name,password_hash) VALUES(?,?) RETURNING id",
                    Long.class, name, "synthetic-password-hash-" + name);
            site.db().runtime().update("INSERT INTO public.privacy(account_id,following,follower) VALUES(?,true,true)", id);
            return id;
        });
    }

    /** Real consent path: grant on campus, project the account to the external site, mark identity ready. */
    private static long consentedUser(Pair pair, String name) {
        long id = user(pair.campus(), name);
        pair.campus().consent().onRegistered(id, true, POLICY);
        Job job = pair.campus().consent().claim(32, LEASE).stream()
                .filter(candidate -> candidate.userId() == id && "UPSERT".equals(candidate.kind()))
                .max(Comparator.comparingLong(Job::epoch)).orElseThrow();
        assertEquals("APPLIED", pair.external().consent().apply(pair.campus().consent().export(job)));
        pair.campus().consent().complete(job);
        assertTrue(pair.campus().consent().replicationAllowed(id));
        assertTrue(pair.external().consent().replicationAllowed(id));
        return id;
    }

    private static long publishFood(Site site, long author, String name, int province, int city) {
        return site.db().transactions().required(() -> {
            long id = site.db().runtime().queryForObject("""
                    INSERT INTO public.activity_foods(account_id,food_name,description,province_id,city_id,picture_url)
                    VALUES(?,?,?,?,?,ARRAY['activity/a.png','activity/b.png']) RETURNING id
                    """, Long.class, author, name, "synthetic description", province, city);
            site.capture().created(ActivityEvents.FOODS, id, author, name, "synthetic description", province, city,
                    List.of("activity/a.png", "activity/b.png"));
            return id;
        });
    }

    private static long publishDinner(Site site, long author) {
        return site.db().transactions().required(() -> {
            long id = site.db().runtime().queryForObject("""
                    INSERT INTO public.activity_dinners(account_id,description,picture_url)
                    VALUES(?,?,ARRAY['activity/dinner.png']) RETURNING id
                    """, Long.class, author, "synthetic dinner");
            site.capture().created(ActivityEvents.DINNERS, id, author, null, "synthetic dinner", null, null,
                    List.of("activity/dinner.png"));
            return id;
        });
    }

    private static void delete(Site site, String table, long id, long author) {
        long now = System.currentTimeMillis();
        site.db().transactions().required(() -> {
            site.db().runtime().update("UPDATE public." + table + " SET deleted_at=to_timestamp(?/1000.0) WHERE id=?", now, id);
            site.capture().deleted(table, id, author, now);
            return null;
        });
    }

    /** Move every pending event from one site to the other without processing. Returns the accepted event ids. */
    private static List<UUID> push(Site from, Site to) {
        var delivered = new java.util.ArrayList<UUID>();
        for (var lease : from.delivery().claimForAggregates(to.db().site().getId(), 100, LEASE, ActivityEvents.TABLES)) {
            ACTIVITY_POLICY.validate(lease.event(), from.node());
            var receipt = to.inbox().receive(lease.event(), from.db().site().getId());
            assertTrue(from.delivery().acknowledge(lease, receipt.receiptStatus().name()));
            delivered.add(lease.eventId());
        }
        return delivered;
    }

    private static List<InboxService.Receipt> process(Site site) {
        var receipts = new java.util.ArrayList<InboxService.Receipt>();
        for (var lease : site.inbox().claim(100, LEASE)) receipts.add(site.inbox().process(lease));
        return receipts;
    }

    private static String inboxStatus(Site site, UUID eventId) {
        return site.db().admin().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, eventId);
    }

    private static UUID gid(Site site, String table, long id) {
        return site.identities().findGlobalId(EntityType.fromTableName(table), id).orElseThrow();
    }

    private static long count(Site site, String sql, Object... args) {
        return site.db().admin().queryForObject(sql, Long.class, args);
    }

    @Test
    void archivedExternalOriginActivityRestoresAfterWithdrawalWithOriginalIdentity() {
        var pair=pair();long author=consentedUser(pair,"relay_external");var source=pair.external();var archive=pair.campus();
        var commands=new you.v50to.eatwhat.sync.activity.ActivityDurableCommands(source.db().runtime(),source.db().transactions(),source.outbox(),source.consent(),source.db().site(),archive.db().site().getId(),32);
        source.capture().setDurableCommands(commands);long food=publishFood(source,author,"archive-me",1,10);assertEquals(1,commands.runOnce());
        var original=CODEC.decode(source.db().runtime().queryForObject("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='activity_state'",String.class));
        var archiveReader=new you.v50to.eatwhat.sync.activity.ActivityStateHandler(archive.db().runtime(),archive.consent());
        archive.db().transactions().required(()->{assertTrue(archiveReader.dependenciesSatisfied(original));archiveReader.apply(original);return null;});
        UUID identity=gid(source,ActivityEvents.FOODS,food);
        // Simulate only the owning public projection being erased; immutable provenance remains.
        source.db().admin().execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{
            connection.setAutoCommit(false);try(var statement=connection.createStatement()){statement.execute("SET LOCAL session_replication_role=replica");}
            try(var statement=connection.prepareStatement("DELETE FROM public.activity_foods WHERE id=?")){statement.setLong(1,food);statement.executeUpdate();}connection.commit();return null;
        });
        for(var site:List.of(source,archive))site.db().admin().update("UPDATE eatwhat_consent.subject SET epoch=epoch+2 WHERE user_id=?",author);
        var archiveCommands=new you.v50to.eatwhat.sync.activity.ActivityDurableCommands(archive.db().runtime(),archive.db().transactions(),archive.outbox(),archive.consent(),archive.db().site(),source.db().site().getId(),32);
        assertEquals(1,archiveCommands.runOnce());
        var relay=CODEC.decode(archive.db().runtime().queryForObject("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='activity_state'",String.class));
        assertNotEquals(relay.originSite(),you.v50to.eatwhat.sync.activity.ActivityStateEvents.parse(relay.payloadJson()).created().originSite());
        var reader=new you.v50to.eatwhat.sync.activity.ActivityStateHandler(source.db().runtime(),source.consent());
        source.db().transactions().required(()->{assertTrue(reader.dependenciesSatisfied(relay));reader.apply(relay);return null;});
        assertEquals(identity,gid(source,ActivityEvents.FOODS,food));assertEquals(1,count(source,"SELECT count(*) FROM public.activity_foods WHERE id=? AND food_name='archive-me'",food));
        // A peer cannot introduce an unknown origin identity by claiming it is an archive.
        source.db().admin().update("DELETE FROM eatwhat_sync.activity_source_proof WHERE object_gid=?",identity);
        source.db().admin().execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{connection.setAutoCommit(false);try(var statement=connection.createStatement()){statement.execute("SET LOCAL session_replication_role=replica");statement.execute("DELETE FROM public.activity_foods WHERE id="+food);}connection.commit();return null;});
        source.db().transactions().required(()->{assertFalse(reader.dependenciesSatisfied(relay));return null;});
    }

    @Test
    void reviewedHistoricalIdentityAndDeleteBeforeCreateRetainTerminalState() {
        var pair=pair();long author=consentedUser(pair,"historical_activity");UUID identity=UUID.randomUUID();
        pair.campus().db().admin().execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()){statement.execute("SET LOCAL session_replication_role=replica");}
            try(var statement=connection.prepareStatement("INSERT INTO public.activity_dinners(id,account_id,description,picture_url,created_at) VALUES(7,?,'historical',ARRAY['activity/history.png'],to_timestamp(1700000000))")){statement.setLong(1,author);statement.executeUpdate();}
            connection.commit();return null;
        });
        assertTrue(pair.campus().db().admin().queryForObject("SELECT eatwhat_sync.claim_historical_domain_source('activity_dinners',7,?,?,?)",Boolean.class,identity,pair.campus().db().site().getId(),"a".repeat(64)));
        pair.external().db().admin().update("INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site) VALUES('activity_dinners',7,?,?)",identity,pair.campus().db().site().getId());
        assertThrows(org.springframework.dao.DataAccessException.class,()->pair.campus().db().runtime().queryForObject("SELECT eatwhat_sync.claim_historical_domain_source('activity_dinners',7,?,?,?)",Boolean.class,identity,pair.campus().db().site().getId(),"a".repeat(64)));
        var commands=new you.v50to.eatwhat.sync.activity.ActivityDurableCommands(pair.campus().db().runtime(),pair.campus().db().transactions(),pair.campus().outbox(),pair.campus().consent(),pair.campus().db().site(),pair.external().db().site().getId(),32);
        pair.campus().capture().setDurableCommands(commands);
        assertEquals(1,commands.runOnce());delete(pair.campus(),ActivityEvents.DINNERS,7,author);assertEquals(1,commands.runOnce());
        var events=pair.campus().db().runtime().query("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='activity_state' ORDER BY local_order DESC",(r,n)->CODEC.decode(r.getString(1)));
        assertEquals(2,events.size());var handler=new you.v50to.eatwhat.sync.activity.ActivityStateHandler(pair.external().db().runtime(),pair.external().consent());
        for(var event:events)pair.external().db().transactions().required(()->{assertTrue(handler.dependenciesSatisfied(event));handler.apply(event);return null;});
        assertEquals(1,count(pair.external(),"SELECT count(*) FROM activity_dinners WHERE id=7 AND deleted_at IS NOT NULL AND created_at=to_timestamp(1700000000)"));
        assertEquals(identity,gid(pair.external(),ActivityEvents.DINNERS,7));
    }

    @Test
    void durableActivityPostSnapshotCarriesItsDefinitionTitleAndExtraFields() throws Exception {
        var pair = pair(); long author = consentedUser(pair, "durable_post");
        var source = pair.campus(); var target = pair.external();
        long definition = source.db().runtime().queryForObject(
                "INSERT INTO public.activities(code,name,status) VALUES('durable','Durable','ONLINE') RETURNING id", Long.class);
        RelationReplicationIT.mirror(source.db(), target.db(), "activities", definition);
        var commands = new you.v50to.eatwhat.sync.activity.ActivityDurableCommands(source.db().runtime(), source.db().transactions(),
                source.outbox(), source.consent(), source.db().site(), target.db().site().getId(), 50);
        source.capture().setDurableCommands(commands);
        long post = source.db().transactions().required(() -> {
            long id = source.db().runtime().queryForObject("""
                    INSERT INTO public.activity_posts(activity_id,account_id,title,picture_url,extra)
                    VALUES(?,?,'roll',ARRAY['activity/a.png'],'{"dish": "roll", "spicy": true}'::jsonb) RETURNING id
                    """, Long.class, definition, author);
            source.capture().createdPost(id, author, definition, "roll", null, null, null, List.of("activity/a.png"),
                    "{\"dish\":\"roll\",\"spicy\":true}");
            return id;
        });
        assertTrue(commands.publish(ActivityEvents.POSTS, post), "The post intent must publish");
        var reader = new you.v50to.eatwhat.sync.activity.ActivityStateHandler(target.db().runtime(),target.consent());
        var inbox = new InboxService(target.db().runtime(),target.db().transactions(),CODEC,new EventHandlerRegistry(List.of(reader)));
        var policy = new you.v50to.eatwhat.sync.activity.ActivityStateEvents(source.consent());
        for(var lease:source.delivery().claimForAggregates(target.db().site().getId(),100,LEASE,policy.aggregateTypes())) {
            policy.validate(lease.event(),source.node()); inbox.receive(lease.event(),source.db().site().getId());
            for(var claimed:inbox.claim(100,LEASE)) assertEquals(InboxService.InboxStatus.APPLIED,inbox.process(claimed).status());
            source.delivery().acknowledge(lease,"APPLIED");
        }
        assertEquals(1,count(target,"SELECT count(*) FROM public.activity_posts WHERE id="+post
                +" AND activity_id="+definition+" AND title='roll' AND extra='{\"dish\":\"roll\",\"spicy\":true}'::jsonb"));
        assertEquals(gid(source,ActivityEvents.POSTS,post),gid(target,ActivityEvents.POSTS,post));
    }

    @Test
    void durableDeletionSurvivesAnExistingLegacyConflictAndReplaysWithoutResurrection() {
        var pair = pair(); long author = consentedUser(pair, "durable_conflict");
        var source = pair.campus(); var target = pair.external();
        long food = publishFood(source, author, "durable", 1, 10);
        push(source, target); process(target);
        source.db().admin().update("UPDATE eatwhat_sync.entity_state SET conflicted=true WHERE aggregate_id=?", gid(source,ActivityEvents.FOODS,food));
        var commands = new you.v50to.eatwhat.sync.activity.ActivityDurableCommands(source.db().runtime(), source.db().transactions(),
                source.outbox(), source.consent(), source.db().site(), target.db().site().getId(), 50);
        source.capture().setDurableCommands(commands);
        delete(source, ActivityEvents.FOODS, food, author);
        assertEquals(1, count(source,"SELECT count(*) FROM eatwhat_sync.activity_intent WHERE table_name='activity_foods' AND numeric_id=?",food));
        assertTrue(commands.runOnce() >= 1);
        var reader = new you.v50to.eatwhat.sync.activity.ActivityStateHandler(target.db().runtime(),target.consent());
        var inbox = new InboxService(target.db().runtime(),target.db().transactions(),CODEC,new EventHandlerRegistry(List.of(reader)));
        var policy = new you.v50to.eatwhat.sync.activity.ActivityStateEvents(source.consent());
        for(var lease:source.delivery().claimForAggregates(target.db().site().getId(),100,LEASE,policy.aggregateTypes())) {
            policy.validate(lease.event(),source.node()); inbox.receive(lease.event(),source.db().site().getId());
            for(var claimed:inbox.claim(100,LEASE)) assertEquals(InboxService.InboxStatus.APPLIED,inbox.process(claimed).status());
            inbox.receive(lease.event(),source.db().site().getId());
            source.delivery().acknowledge(lease,"APPLIED");
        }
        assertEquals(1,count(target,"SELECT count(*) FROM public.activity_foods WHERE id=? AND deleted_at IS NOT NULL",food));
    }

    @Test
    void durableRemovalPassesLaterBanButLiveContentRemainsBlocked() {
        var pair=pair();long author=consentedUser(pair,"later_ban");var source=pair.campus();var target=pair.external();
        long food=publishFood(source,author,"deleted-before-ban",1,10);push(source,target);process(target);
        var commands=new you.v50to.eatwhat.sync.activity.ActivityDurableCommands(source.db().runtime(),source.db().transactions(),source.outbox(),source.consent(),source.db().site(),target.db().site().getId(),50);
        source.capture().setDurableCommands(commands);
        long pending=publishDinner(source,author);delete(source,ActivityEvents.FOODS,food,author);
        for(var site:List.of(source,target)){
            site.db().admin().update("UPDATE users SET banned=true WHERE id=?",author);
            assertFalse(site.consent().replicationAllowed(author));assertTrue(site.consent().removalReplicationAllowed(author));
        }
        assertEquals(1,commands.runOnce());assertFalse(commands.publish(ActivityEvents.DINNERS,pending));
        var event=CODEC.decode(source.db().admin().queryForObject("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='activity_state'",String.class));
        var policy=new you.v50to.eatwhat.sync.activity.ActivityStateEvents(source.consent());
        assertEquals(EventPolicy.OutboundDecision.ALLOW,policy.outbound(event));
        var op=you.v50to.eatwhat.sync.activity.ActivityStateEvents.parse(event.payloadJson());
        var live=new SyncEvent(event.eventId(),event.originSite(),event.originInstanceId(),event.aggregateType(),event.aggregateId(),event.numericId(),event.eventType(),event.schemaVersion(),event.baseVersion(),event.nextVersion(),event.dependencies(),event.actorId(),event.commandKey(),event.correlationId(),you.v50to.eatwhat.sync.activity.ActivityStateEvents.payload(new you.v50to.eatwhat.sync.activity.ActivityStateEvents.Snapshot(op.table(),op.created(),op.epoch(),null)));
        assertEquals(EventPolicy.OutboundDecision.WAIT,policy.outbound(live));
        var handler=new you.v50to.eatwhat.sync.activity.ActivityStateHandler(target.db().runtime(),target.consent());
        target.db().transactions().required(()->{assertFalse(handler.dependenciesSatisfied(live));assertTrue(handler.dependenciesSatisfied(event));handler.apply(event);return null;});
        assertEquals(1,count(target,"SELECT count(*) FROM activity_foods WHERE id=? AND deleted_at IS NOT NULL",food));
        assertEquals(0,count(target,"SELECT count(*) FROM activity_dinners WHERE id=?",pending));
    }

    @Test
    void durableDeletionUsesImmutableCreationProofAfterOneMillisecondReplayDrift() {
        var pair=pair();long author=consentedUser(pair,"millisecond_drift");var source=pair.campus();var target=pair.external();
        var commands=new you.v50to.eatwhat.sync.activity.ActivityDurableCommands(source.db().runtime(),source.db().transactions(),source.outbox(),source.consent(),source.db().site(),target.db().site().getId(),50);
        source.capture().setDurableCommands(commands);long dinner=publishDinner(source,author);assertEquals(1,commands.runOnce());
        var initial=CODEC.decode(source.db().admin().queryForObject("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='activity_state'",String.class));
        var reader=new you.v50to.eatwhat.sync.activity.ActivityStateHandler(target.db().runtime(),target.consent());
        target.db().transactions().required(()->{assertTrue(reader.dependenciesSatisfied(initial));reader.apply(initial);return null;});
        source.db().admin().update("UPDATE activity_dinners SET created_at=to_timestamp(?/1000.0) WHERE id=?",you.v50to.eatwhat.sync.activity.ActivityStateEvents.parse(initial.payloadJson()).created().createdAt()-1,dinner);
        delete(source,ActivityEvents.DINNERS,dinner,author);
        for(var site:List.of(source,target))site.db().admin().update("UPDATE users SET banned=true WHERE id=?",author);
        assertTrue(commands.publish(ActivityEvents.DINNERS,dinner));
        var removed=CODEC.decode(source.db().admin().queryForObject("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='activity_state'AND envelope->'payloadJson'->>'deletedAt'IS NOT NULL",String.class));
        assertEquals(you.v50to.eatwhat.sync.activity.ActivityStateEvents.parse(initial.payloadJson()).created(),you.v50to.eatwhat.sync.activity.ActivityStateEvents.parse(removed.payloadJson()).created());
        target.db().transactions().required(()->{assertTrue(reader.dependenciesSatisfied(removed));reader.apply(removed);return null;});
        assertEquals(1,count(target,"SELECT count(*) FROM activity_dinners WHERE id=? AND deleted_at IS NOT NULL",dinner));
        source.db().admin().update("UPDATE activity_dinners SET description='tampered' WHERE id=?",dinner);
        source.db().admin().update("UPDATE eatwhat_sync.activity_intent SET revision=revision+1 WHERE table_name='activity_dinners'AND numeric_id=?",dinner);
        assertThrows(IllegalStateException.class,()->commands.publish(ActivityEvents.DINNERS,dinner));
    }

    @Test
    void durableRemovalNeverCrossesWithdrawalOrAnOldConsentEpoch() {
        var pair=pair();long author=consentedUser(pair,"removal_privacy");var source=pair.campus();var target=pair.external();
        var commands=new you.v50to.eatwhat.sync.activity.ActivityDurableCommands(source.db().runtime(),source.db().transactions(),source.outbox(),source.consent(),source.db().site(),target.db().site().getId(),50);
        source.capture().setDurableCommands(commands);
        long dinner=publishDinner(source,author);delete(source,ActivityEvents.DINNERS,dinner,author);assertEquals(1,commands.runOnce());
        var event=CODEC.decode(source.db().admin().queryForObject("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='activity_state'",String.class));
        var policy=new you.v50to.eatwhat.sync.activity.ActivityStateEvents(source.consent());
        for(var site:List.of(source,target))site.db().admin().update("UPDATE eatwhat_consent.subject SET withdrawal_request_id=? WHERE user_id=?",UUID.randomUUID(),author);
        assertFalse(source.consent().removalReplicationAllowed(author));assertEquals(EventPolicy.OutboundDecision.POLICY_BLOCKED,policy.outbound(event));
        var handler=new you.v50to.eatwhat.sync.activity.ActivityStateHandler(target.db().runtime(),target.consent());
        assertFalse(target.db().transactions().required(()->handler.dependenciesSatisfied(event)));
        for(var site:List.of(source,target))site.db().admin().update("UPDATE eatwhat_consent.subject SET epoch=epoch+1,status='WITHDRAWN' WHERE user_id=?",author);
        assertEquals(EventPolicy.OutboundDecision.POLICY_BLOCKED,policy.outbound(event));assertFalse(commands.publish(ActivityEvents.DINNERS,dinner));
        target.db().transactions().required(()->{assertTrue(handler.dependenciesSatisfied(event));handler.apply(event);return null;});
        assertEquals(0,count(target,"SELECT count(*) FROM activity_dinners WHERE id=?",dinner),"stale removal payload cannot recreate private content");
    }

    @Test
    void durableBackfillPublishesOnlyAfterGrantAndPreservesOriginalIdentityAndTime() {
        var pair = pair(); var source=pair.campus(); var target=pair.external();
        long author=user(source,"durable_old"); source.consent().onRegistered(author,false,POLICY);
        var commands=new you.v50to.eatwhat.sync.activity.ActivityDurableCommands(source.db().runtime(),source.db().transactions(),source.outbox(),source.consent(),source.db().site(),target.db().site().getId(),50);
        source.capture().setDurableCommands(commands);
        long food=publishFood(source,author,"before-grant",1,10);
        assertEquals(0,commands.runOnce());
        assertEquals(0,count(source,"SELECT count(*) FROM eatwhat_sync.outbox"));
        source.consent().grant(author,POLICY);
        Job job=source.consent().claim(32,LEASE).stream().filter(j->j.userId()==author&&j.kind().equals("UPSERT")).findFirst().orElseThrow();
        assertEquals("APPLIED",target.consent().apply(source.consent().export(job))); source.consent().complete(job);
        assertEquals(1,commands.runOnce()); assertEquals(0,commands.runOnce());
        assertEquals(1,count(source,"SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='activity_state'"));
        var encoded=source.db().admin().queryForObject("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='activity_state'",String.class);
        var event=CODEC.decode(encoded); var op=you.v50to.eatwhat.sync.activity.ActivityStateEvents.parse(event.payloadJson());
        assertEquals(food,op.created().numericId()); assertEquals(gid(source,ActivityEvents.FOODS,food),op.created().globalId());
        assertEquals(source.db().runtime().queryForObject("SELECT (extract(epoch FROM created_at)*1000)::bigint FROM public.activity_foods WHERE id=?",Long.class,food),op.created().createdAt());
    }

    // ---- behavior ------------------------------------------------------------------------------------------

    @Test
    void campusFoodCreationReplicatesWithTheSameNumericAndGlobalIdentityAndNoRecapture() {
        var pair = pair();
        long author = consentedUser(pair, "food_author");
        var sequencesBefore = pair.external().db().admin().queryForList("SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='public' ORDER BY 1");
        long food = publishFood(pair.campus(), author, "dumpling", 1, 10);
        assertEquals(1, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='activity_foods'"));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM public.activity_foods"));

        var ids = push(pair.campus(), pair.external());
        assertEquals(1, ids.size());
        var receipts = process(pair.external());
        assertEquals(List.of(InboxService.InboxStatus.APPLIED), receipts.stream().map(InboxService.Receipt::status).toList());

        var row = pair.external().db().admin().queryForMap("""
                SELECT id,account_id,food_name,description,province_id,city_id,picture_url::text AS pictures,likes_count,deleted_at
                FROM public.activity_foods
                """);
        assertEquals(food, ((Number) row.get("id")).longValue());
        assertEquals(author, ((Number) row.get("account_id")).longValue());
        assertEquals("dumpling", row.get("food_name"));
        assertEquals("synthetic description", row.get("description"));
        assertEquals(1, ((Number) row.get("province_id")).intValue());
        assertEquals(10, ((Number) row.get("city_id")).intValue());
        assertEquals("{activity/a.png,activity/b.png}", row.get("pictures"));
        assertEquals(0, ((Number) row.get("likes_count")).intValue());
        assertNull(row.get("deleted_at"));
        assertEquals(gid(pair.campus(), "activity_foods", food), gid(pair.external(), "activity_foods", food));
        assertEquals("campus-activity", pair.external().db().admin().queryForObject(
                "SELECT origin_site FROM eatwhat_identity.entity_identity WHERE entity_type='activity_foods' AND numeric_id=?", String.class, food));
        // The inbound application must not produce its own outbound event, and no local sequence moves.
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM eatwhat_sync.outbox"));
        assertEquals(sequencesBefore, pair.external().db().admin().queryForList("SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='public' ORDER BY 1"));
    }

    @Test
    void externalCreatedDinnerReplicatesToCampusAndThenDeletionFlowsBackAndStaysDeleted() {
        var pair = pair();
        long author = consentedUser(pair, "dinner_author");
        long dinner = publishDinner(pair.external(), author);
        assertEquals(0, dinner % 2, "External allocation domain is even");
        push(pair.external(), pair.campus());
        assertEquals(InboxService.InboxStatus.APPLIED, process(pair.campus()).getFirst().status());
        assertEquals(1, count(pair.campus(), "SELECT count(*) FROM public.activity_dinners WHERE id=? AND account_id=? AND deleted_at IS NULL", dinner, author));

        // The campus replica is deleted by its author on the campus site; the base is the applied remote version.
        delete(pair.campus(), "activity_dinners", dinner, author);
        push(pair.campus(), pair.external());
        assertEquals(InboxService.InboxStatus.APPLIED, process(pair.external()).getFirst().status());
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM public.activity_dinners WHERE id=? AND deleted_at IS NOT NULL", dinner));
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM public.activity_dinners"), "Deletion is a tombstone, never a physical delete");
        var vector = pair.external().db().admin().queryForObject("SELECT applied_vector::text FROM eatwhat_sync.entity_state WHERE aggregate_id=?",
                String.class, gid(pair.external(), "activity_dinners", dinner));
        assertEquals(new VersionVector(java.util.Map.of("external-activity", 1L, "campus-activity", 1L)),
                EventCodec.vectorFromJson(vector));
    }

    @Test
    void deletionArrivingBeforeCreationWaitsThenAppliesAndAnOldCreationCannotResurrect() {
        var pair = pair();
        long author = consentedUser(pair, "ordered_author");
        long food = publishFood(pair.campus(), author, "ordered", 1, 10);
        delete(pair.campus(), "activity_foods", food, author);
        var ids = pair.campus().db().admin().queryForList(
                "SELECT event_id FROM eatwhat_sync.outbox ORDER BY local_order", UUID.class);
        assertEquals(2, ids.size());
        UUID created = ids.get(0), deleted = ids.get(1);

        // Deliver the deletion first, as a reordering network could.
        var envelope = CODEC.decode(pair.campus().db().admin().queryForObject(
                "SELECT envelope::text FROM eatwhat_sync.outbox WHERE event_id=?", String.class, deleted));
        pair.external().inbox().receive(envelope, "campus-activity");
        assertEquals(InboxService.InboxStatus.WAITING_DEPENDENCY, process(pair.external()).getFirst().status());
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM public.activity_foods"));

        var createdEnvelope = CODEC.decode(pair.campus().db().admin().queryForObject(
                "SELECT envelope::text FROM eatwhat_sync.outbox WHERE event_id=?", String.class, created));
        pair.external().inbox().receive(createdEnvelope, "campus-activity");
        assertEquals(InboxService.InboxStatus.APPLIED, process(pair.external()).getFirst().status());
        assertTrue(pair.external().inbox().retryWaiting(deleted));
        assertEquals(InboxService.InboxStatus.APPLIED, process(pair.external()).getFirst().status());
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM public.activity_foods WHERE id=? AND deleted_at IS NOT NULL", food));

        // Replaying the creation (exact same event and a fresh delivery of the import) never revives the row.
        assertEquals(InboxService.ReceiptStatus.ALREADY_APPLIED, pair.external().inbox().receive(createdEnvelope, "campus-activity").receiptStatus());
        String payload = createdEnvelope.payloadJson();
        assertEquals("ALREADY_APPLIED", pair.external().db().runtime().queryForObject(
                "SELECT eatwhat_sync.apply_activity_created('activity_foods', ?::jsonb)", String.class, payload));
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM public.activity_foods WHERE id=? AND deleted_at IS NOT NULL", food));
        assertEquals("ALREADY_DELETED", pair.external().db().runtime().queryForObject(
                "SELECT eatwhat_sync.apply_activity_deleted('activity_foods', ?, ?, now())", String.class, food, gid(pair.external(), "activity_foods", food)));
    }

    @Test
    void anAuthorWithoutAReplicationGrantProducesNoEventForCreationOrDeletion() {
        var pair = pair();
        long local = user(pair.campus(), "never_consented");
        pair.campus().consent().onRegistered(local, false, null);
        assertFalse(pair.campus().consent().replicationAllowed(local), "Campus-local use does not imply cross-border consent");
        long food = publishFood(pair.campus(), local, "private", 1, 10);
        delete(pair.campus(), "activity_foods", food, local);
        assertEquals(0, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.outbox"));
        assertEquals(0, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.entity_state"));
        // A user who is not even registered with consent behaves the same way.
        long unknown = user(pair.campus(), "no_subject");
        publishDinner(pair.campus(), unknown);
        assertEquals(0, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.outbox"));
    }

    @Test
    void anActivityCreatedBeforeConsentIsNeverReplicatedAndItsLaterDeletionStaysLocal() {
        var pair = pair();
        long author = user(pair.campus(), "late_consent");
        long food = publishFood(pair.campus(), author, "before-consent", 1, 10);
        pair.campus().consent().onRegistered(author, true, POLICY);
        Job job = pair.campus().consent().claim(32, LEASE).stream().filter(j -> j.userId() == author && "UPSERT".equals(j.kind()))
                .findFirst().orElseThrow();
        pair.external().consent().apply(pair.campus().consent().export(job));
        pair.campus().consent().complete(job);
        delete(pair.campus(), "activity_foods", food, author);
        assertEquals(0, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.outbox"),
                "Pre-consent content has no replication history; backfill is a separate, explicit step");
    }

    @Test
    void inboundEventsWaitWhileTheAuthorIsNotAllowedAndApplyAfterwards() {
        var pair = pair();
        long author = consentedUser(pair, "gated_author");
        long food = publishFood(pair.campus(), author, "gated", 1, 10);
        push(pair.campus(), pair.external());
        pair.external().inboundOpen().set(false);
        assertEquals(InboxService.InboxStatus.WAITING_DEPENDENCY, process(pair.external()).getFirst().status());
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM public.activity_foods"));
        pair.external().inboundOpen().set(true);
        UUID waiting = pair.external().db().admin().queryForObject("SELECT event_id FROM eatwhat_sync.inbox", UUID.class);
        assertTrue(pair.external().inbox().retryWaiting(waiting));
        assertEquals(InboxService.InboxStatus.APPLIED, process(pair.external()).getFirst().status());
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM public.activity_foods WHERE id=?", food));
    }

    @Test
    void withdrawnAuthorsContentIsNotAppliedAndMissingDictionaryEntriesWait() {
        var pair = pair();
        long author = consentedUser(pair, "withdrawn_author");
        // A city the external dictionary does not have is a genuine prerequisite gap.
        pair.campus().db().admin().update("INSERT INTO public.cities(id,province_id,name) VALUES(11,1,'campus-only-city')");
        publishFood(pair.campus(), author, "dictionary-gap", 1, 11);
        push(pair.campus(), pair.external());
        assertEquals(InboxService.InboxStatus.WAITING_DEPENDENCY, process(pair.external()).getFirst().status());
        // A city that belongs to another province is rejected rather than relinked.
        publishFood(pair.campus(), author, "mismatch", 2, 10);
        push(pair.campus(), pair.external());
        assertEquals(InboxService.InboxStatus.WAITING_DEPENDENCY, process(pair.external()).getFirst().status());
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM public.activity_foods"));
        pair.campus().consent().withdraw(author);
        assertFalse(pair.campus().consent().replicationAllowed(author));
        publishDinner(pair.campus(), author);
        assertEquals(2, count(pair.campus(), "SELECT count(*) FROM eatwhat_sync.outbox"), "No new event after withdrawal");
    }

    @Test
    void aDeletionSignedByAnotherAccountIsNeverApplied() {
        var pair = pair();
        long author = consentedUser(pair, "real_author");
        long other = consentedUser(pair, "other_user");
        long food = publishFood(pair.campus(), author, "protected", 1, 10);
        push(pair.campus(), pair.external());
        assertEquals(InboxService.InboxStatus.APPLIED, process(pair.external()).getFirst().status());

        UUID foodGid = gid(pair.campus(), "activity_foods", food);
        UUID otherGid = pair.campus().identities().findGlobalId(EntityType.USERS, other).orElseThrow();
        var base = new VersionVector(java.util.Map.of("campus-activity", 1L));
        var forged = new SyncEvent(UUID.randomUUID(), "campus-activity", pair.campus().db().site().getInstanceId(),
                "activity_foods", foodGid, food, ActivityEvents.DELETED, 1, base, base.increment("campus-activity"),
                List.of(new SyncEvent.Dependency("users", otherGid)), otherGid, "forged:" + foodGid, UUID.randomUUID(),
                ActivityEvents.deletedPayload(System.currentTimeMillis()));
        ACTIVITY_POLICY.validate(forged, pair.campus().node()); // Structurally valid; the author check is the defense.
        pair.external().inbox().receive(forged, "campus-activity");
        assertEquals(InboxService.InboxStatus.WAITING_DEPENDENCY, process(pair.external()).getFirst().status());
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM public.activity_foods WHERE id=? AND deleted_at IS NULL", food));
    }

    @Test
    void importFunctionsAndTheIdentityGuardRejectForgedOrOutOfDomainActivities() {
        var pair = pair();
        long author = consentedUser(pair, "guard_author");
        UUID authorGid = pair.campus().identities().findGlobalId(EntityType.USERS, author).orElseThrow();
        var campusDb = pair.campus().db();
        // The runtime role cannot insert a remote-parity id directly, nor write the import context.
        assertThrows(DataAccessException.class, () -> campusDb.runtime().update(
                "INSERT INTO public.activity_dinners(id,account_id,picture_url) VALUES(2000000000,?,ARRAY['activity/x.png'])", author));
        assertThrows(DataAccessException.class, () -> campusDb.runtime().update(
                "INSERT INTO eatwhat_consent.import_context VALUES(txid_current(),pg_backend_pid(),'activity_dinners',2000000000,?,?,?)",
                UUID.randomUUID(), "external-activity", authorGid));
        String template = """
                {"numeric_id":%d,"global_id":"%s","origin_site":"%s","author_global_id":"%s",
                 "description":"d","picture_url":["activity/p.png"],"created_at":1700000000000%s}
                """;
        UUID gid = UUID.randomUUID();
        // A local-parity numeric id from a "remote" origin would reserve identities in this site's own domain.
        assertThrows(DataAccessException.class, () -> campusDb.runtime().queryForObject(
                "SELECT eatwhat_sync.apply_activity_created('activity_dinners', ?::jsonb)", String.class,
                template.formatted(3000000001L, gid, "external-activity", authorGid, "")));
        // A payload naming this site as origin is not an import.
        assertThrows(DataAccessException.class, () -> campusDb.runtime().queryForObject(
                "SELECT eatwhat_sync.apply_activity_created('activity_dinners', ?::jsonb)", String.class,
                template.formatted(3000000002L, gid, "campus-activity", authorGid, "")));
        // Unknown fields, including a forged likes_count, are rejected.
        assertThrows(DataAccessException.class, () -> campusDb.runtime().queryForObject(
                "SELECT eatwhat_sync.apply_activity_created('activity_dinners', ?::jsonb)", String.class,
                template.formatted(3000000002L, gid, "external-activity", authorGid, ",\"likes_count\":99")));
        // An unknown author cannot be fabricated.
        assertThrows(DataAccessException.class, () -> campusDb.runtime().queryForObject(
                "SELECT eatwhat_sync.apply_activity_created('activity_dinners', ?::jsonb)", String.class,
                template.formatted(3000000002L, gid, "external-activity", UUID.randomUUID(), "")));
        // Another table cannot be imported through this door.
        assertThrows(DataAccessException.class, () -> campusDb.runtime().queryForObject(
                "SELECT eatwhat_sync.apply_activity_created('users', ?::jsonb)", String.class,
                template.formatted(3000000002L, gid, "external-activity", authorGid, "")));
        assertEquals(0, count(pair.campus(), "SELECT count(*) FROM public.activity_dinners"));
        // The well-formed remote import is accepted, idempotent, and does not move the local sequence.
        assertEquals("APPLIED", campusDb.runtime().queryForObject(
                "SELECT eatwhat_sync.apply_activity_created('activity_dinners', ?::jsonb)", String.class,
                template.formatted(3000000002L, gid, "external-activity", authorGid, "")));
        assertEquals("ALREADY_APPLIED", campusDb.runtime().queryForObject(
                "SELECT eatwhat_sync.apply_activity_created('activity_dinners', ?::jsonb)", String.class,
                template.formatted(3000000002L, gid, "external-activity", authorGid, "")));
        // The same numeric id with a different global identity is a collision, never an overwrite.
        assertThrows(DataAccessException.class, () -> campusDb.runtime().queryForObject(
                "SELECT eatwhat_sync.apply_activity_created('activity_dinners', ?::jsonb)", String.class,
                template.formatted(3000000002L, UUID.randomUUID(), "external-activity", authorGid, "")));
        assertEquals(1, count(pair.campus(), "SELECT count(*) FROM public.activity_dinners"));
        assertEquals(0, count(pair.campus(), "SELECT count(*) FROM eatwhat_consent.import_context"));
    }

    @Test
    void theInboxWorkerAppliesEventsAndRequeuesWaitingOnesOnceTheirParentArrivesWithoutAnyManualStep() {
        var pair = pair();
        long author = consentedUser(pair, "worker_author");
        long food = publishFood(pair.campus(), author, "worker", 1, 10);
        delete(pair.campus(), "activity_foods", food, author);
        var outbox = pair.campus().db().admin().queryForList("SELECT envelope::text FROM eatwhat_sync.outbox ORDER BY local_order", String.class);
        var metrics = new TransportMetrics();
        var worker = new InboxWorker(pair.external().inbox(), LEASE, Duration.ZERO, metrics);

        pair.external().inbox().receive(CODEC.decode(outbox.get(1)), "campus-activity"); // deletion first
        worker.runOnce();
        assertEquals(1, metrics.snapshot().get("inbox_waiting_dependency"));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM public.activity_foods"));

        pair.external().inbox().receive(CODEC.decode(outbox.get(0)), "campus-activity"); // creation arrives
        worker.runOnce(); // applies creation and requeues the waiting deletion
        worker.runOnce(); // applies the deletion
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM public.activity_foods WHERE id=? AND deleted_at IS NOT NULL", food));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM eatwhat_sync.inbox WHERE status<>'APPLIED'"));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM eatwhat_sync.pending WHERE resolved_at IS NULL"));
        assertTrue(metrics.snapshot().get("inbox_requeued") >= 1);
        assertEquals(0, metrics.snapshot().get("inbox_process_failed"));
        // A wait that is not yet old enough is left alone.
        assertEquals(0, pair.external().inbox().requeueWaiting(10, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> pair.external().inbox().requeueWaiting(0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> pair.external().inbox().requeueWaiting(1, Duration.ofDays(2)));
    }

    private static Job cleanupJob(Site site, long userId) {
        return site.consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == userId && "CLEANUP".equals(job.kind())).findFirst().orElseThrow();
    }

    @Test
    void withdrawalPurgesReplicatedActivitiesKeepsTombstonesAndTheOriginalAndALaterDeletionIsANoOp() {
        var pair = pair();
        long author = consentedUser(pair, "withdraw_author");
        long food = publishFood(pair.campus(), author, "to-purge", 1, 10);
        push(pair.campus(), pair.external());
        assertEquals(InboxService.InboxStatus.APPLIED, process(pair.external()).getFirst().status());
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM public.activity_foods"));

        pair.external().consent().withdraw(author);
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM public.activity_foods"), "Replica content is cleaned with the account");
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM public.users"));
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM eatwhat_identity.entity_identity WHERE entity_type='activity_foods'"),
                "Identity tombstones are never released");
        assertEquals(1, count(pair.campus(), "SELECT count(*) FROM public.activity_foods WHERE deleted_at IS NULL"), "The original stays on campus");

        // The author deletes on campus before the withdrawal reaches it: the event must not get stuck on the peer.
        delete(pair.campus(), "activity_foods", food, author);
        push(pair.campus(), pair.external());
        assertEquals(InboxService.InboxStatus.APPLIED, process(pair.external()).getFirst().status());
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM public.activity_foods"));
    }

    @Test
    void withdrawalDoesNotPurgeContentWhoseOutboundEventIsStillUndelivered() {
        var pair = pair();
        long author = consentedUser(pair, "undelivered_author");
        publishDinner(pair.external(), author);
        pair.external().consent().withdraw(author);
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM public.activity_dinners"), "Only copy of undelivered content");
        assertEquals(1, count(pair.external(), "SELECT count(*) FROM public.users"));
        var blocked = cleanupJob(pair.external(), author);
        assertEquals("RETRY", pair.external().consent().runCleanup(blocked));
        pair.external().consent().retry(blocked);

        push(pair.external(), pair.campus());
        assertEquals(InboxService.InboxStatus.APPLIED, process(pair.campus()).getFirst().status());
        pair.external().db().admin().update("UPDATE eatwhat_consent.job SET available_at=clock_timestamp()-interval '1 millisecond' WHERE job_id=?", blocked.jobId());
        var retried = cleanupJob(pair.external(), author);
        assertEquals("DONE", pair.external().consent().runCleanup(retried));
        pair.external().consent().complete(retried);
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM public.activity_dinners"));
        assertEquals(0, count(pair.external(), "SELECT count(*) FROM public.users"));
        assertEquals(1, count(pair.campus(), "SELECT count(*) FROM public.activity_dinners WHERE deleted_at IS NULL"), "Delivered content survives on campus");
    }

    @Test
    void concurrentDeletionOnBothSitesConvergesToDeletedAndLeavesOneConflictRecordPerSide() {
        var pair = pair();
        long author = consentedUser(pair, "double_delete");
        long food = publishFood(pair.campus(), author, "both-delete", 1, 10);
        push(pair.campus(), pair.external());
        process(pair.external());
        delete(pair.campus(), "activity_foods", food, author);
        delete(pair.external(), "activity_foods", food, author);
        push(pair.campus(), pair.external());
        push(pair.external(), pair.campus());
        assertEquals(InboxService.InboxStatus.CONFLICT, process(pair.external()).getFirst().status());
        assertEquals(InboxService.InboxStatus.CONFLICT, process(pair.campus()).getFirst().status());
        for (var site : List.of(pair.campus(), pair.external())) {
            assertEquals(1, count(site, "SELECT count(*) FROM public.activity_foods WHERE id=? AND deleted_at IS NOT NULL", food));
            assertEquals(1, count(site, "SELECT count(*) FROM eatwhat_sync.conflict"));
        }
    }
}
