package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.springframework.dao.DataAccessException;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.sync.ManagedDomainWriteGuard;
import you.v50to.eatwhat.account.ConsentModels;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.mapper.NotificationMapper;
import you.v50to.eatwhat.service.NotificationService;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import you.v50to.eatwhat.support.MvcContractSupport;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.EventHandlerRegistry;
import you.v50to.eatwhat.sync.InboxService;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.notification.NotificationReadCommands;
import you.v50to.eatwhat.sync.notification.NotificationReadEventHandler;
import you.v50to.eatwhat.sync.notification.NotificationReadEvents;
import you.v50to.eatwhat.sync.notification.NotificationReadStore;
import you.v50to.eatwhat.sync.notification.NotificationReadWorker;
import you.v50to.eatwhat.sync.relation.RelationEventHandler;
import you.v50to.eatwhat.sync.relation.RelationCausalEventHandler;
import you.v50to.eatwhat.sync.relation.RelationCausalEvents;
import you.v50to.eatwhat.sync.relation.RelationCausalStore;
import you.v50to.eatwhat.sync.relation.RelationEvents;
import you.v50to.eatwhat.sync.relation.RelationKind;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real PostgreSQL notification-read replication checks using the relation/consent fixture. */
class NotificationReadReplicationIT extends MvcContractSupport {
    private static final GenericContainer<?> PG = RelationReplicationIT.PG;
    private static final EventCodec CODEC = RelationReplicationIT.CODEC;
    private static final Duration LEASE = RelationReplicationIT.LEASE;

    @BeforeAll static void startPostgres() { if (!PG.isRunning()) PG.start(); }

    @Test void historicalLocalNoticeAndItsRemoteReadReplicateOnceWithoutInventingRelationEvents() throws Exception {
        var pair=RelationReplicationIT.pair();var a=pair.a();var b=pair.b();
        long notice=a.db().runtime().queryForObject("INSERT INTO public.notifications(account_id,actor_id,type,target_type,target_id,data) VALUES(?,?,'follow','user',?,'{}'::jsonb) RETURNING id",Long.class,pair.owner(),pair.actor(),pair.actor());
        var left=new you.v50to.eatwhat.sync.notification.NotificationHistoryCommands(a.db().runtime(),a.db().transactions(),new OutboxService(a.db().runtime(),a.db().transactions(),CODEC,a.db().site()),a.consent(),a.db().site(),a.peerSiteId(),32);
        var right=new you.v50to.eatwhat.sync.notification.NotificationHistoryCommands(b.db().runtime(),b.db().transactions(),new OutboxService(b.db().runtime(),b.db().transactions(),CODEC,b.db().site()),b.consent(),b.db().site(),b.peerSiteId(),32);
        assertEquals(1,left.runOnce());assertEquals(0,left.runOnce());
        var receiver=new you.v50to.eatwhat.sync.notification.NotificationHistoryHandler(b.db().runtime(),b.consent());
        var create=a.db().runtime().queryForObject("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='notification.history'",String.class);
        b.db().transactions().required(()->{var e=CODEC.decode(create);assertTrue(receiver.dependenciesSatisfied(e));receiver.apply(e);return null;});
        var reads=readSite(b);reads.commands().setHistory(right);reads.commands().markOne(pair.owner(),notice);
        assertEquals(1,right.runOnce());
        var read=b.db().runtime().queryForObject("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='notification.history'",String.class);
        var sourceReader=new you.v50to.eatwhat.sync.notification.NotificationHistoryHandler(a.db().runtime(),a.consent());
        for(int n=0;n<3;n++)a.db().transactions().required(()->{var e=CODEC.decode(read);assertTrue(sourceReader.dependenciesSatisfied(e));sourceReader.apply(e);return null;});
        assertEquals(0,unread(a,pair.owner(),notice));
        assertEquals(1,RelationReplicationIT.count(b,"SELECT count(*) FROM public.notifications WHERE id=?",notice));
        assertEquals(0,RelationReplicationIT.count(a,"SELECT count(*) FROM eatwhat_sync.relation_operation"));
    }

    @Test void archivedExternalNoticeAndReadRecoverAfterRegrantWithoutChangingItsIdentity() throws Exception {
        var pair=RelationReplicationIT.pair();var source=pair.b();var archive=pair.a();
        long notice=source.db().runtime().queryForObject("INSERT INTO public.notifications(account_id,actor_id,type,target_type,target_id,data) VALUES(?,?,'follow','user',?,'{}'::jsonb) RETURNING id",Long.class,pair.owner(),pair.actor(),pair.actor());
        var writer=new you.v50to.eatwhat.sync.notification.NotificationHistoryCommands(source.db().runtime(),source.db().transactions(),new OutboxService(source.db().runtime(),source.db().transactions(),CODEC,source.db().site()),source.consent(),source.db().site(),source.peerSiteId(),32);
        assertEquals(1,writer.runOnce());var reads=readSite(source);reads.commands().setHistory(writer);reads.commands().markOne(pair.owner(),notice);assertEquals(1,writer.runOnce());
        var archiveReader=new you.v50to.eatwhat.sync.notification.NotificationHistoryHandler(archive.db().runtime(),archive.consent());
        var originals=source.db().runtime().queryForList("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='notification.history' ORDER BY local_order",String.class);
        for(String encoded:originals)archive.db().transactions().required(()->{var event=CODEC.decode(encoded);assertTrue(archiveReader.dependenciesSatisfied(event));archiveReader.apply(event);return null;});
        UUID identity=source.db().runtime().queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='notifications' AND numeric_id=?",UUID.class,notice);
        source.db().admin().update("DELETE FROM public.notifications WHERE id=?",notice);
        source.db().admin().update("DELETE FROM eatwhat_sync.notification_history_read WHERE notification_gid=?",identity);
        source.db().admin().update("DELETE FROM eatwhat_sync.notification_history WHERE notification_gid=?",identity);
        for(var site:List.of(source,archive))site.db().admin().update("UPDATE eatwhat_consent.subject SET epoch=epoch+2 WHERE user_id IN (?,?)",pair.actor(),pair.owner());
        var archiveWriter=new you.v50to.eatwhat.sync.notification.NotificationHistoryCommands(archive.db().runtime(),archive.db().transactions(),new OutboxService(archive.db().runtime(),archive.db().transactions(),CODEC,archive.db().site()),archive.consent(),archive.db().site(),archive.peerSiteId(),32);
        assertEquals(2,archiveWriter.runOnce());
        var events=archive.db().runtime().queryForList("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='notification.history' ORDER BY local_order",String.class);
        var reader=new you.v50to.eatwhat.sync.notification.NotificationHistoryHandler(source.db().runtime(),source.consent());
        source.db().transactions().required(()->{assertFalse(reader.dependenciesSatisfied(CODEC.decode(events.getLast())));return null;});
        for(String encoded:events)source.db().transactions().required(()->{var event=CODEC.decode(encoded);assertTrue(reader.dependenciesSatisfied(event));reader.apply(event);return null;});
        assertEquals(0,unread(source,pair.owner(),notice));
        assertEquals(identity,source.db().runtime().queryForObject("SELECT notification_gid FROM eatwhat_sync.notification_history WHERE numeric_id=?",UUID.class,notice));
    }

    @Test void historicalDiagnosticsDoNotStarveLaterNoticesAndReadAllCapturesOnlyObservedRows() throws Exception {
        var pair=RelationReplicationIT.pair();var site=pair.a();
        long invalid=site.db().runtime().queryForObject("INSERT INTO public.notifications(account_id,actor_id,type,target_type,target_id,data) VALUES(?,?,'like','food',999,'{}'::jsonb) RETURNING id",Long.class,pair.owner(),pair.actor());
        long first=site.db().runtime().queryForObject("INSERT INTO public.notifications(account_id,actor_id,type,target_type,target_id,data) VALUES(?,?,'follow','user',?,'{}'::jsonb) RETURNING id",Long.class,pair.owner(),pair.actor(),pair.actor());
        var history=new you.v50to.eatwhat.sync.notification.NotificationHistoryCommands(site.db().runtime(),site.db().transactions(),new OutboxService(site.db().runtime(),site.db().transactions(),CODEC,site.db().site()),site.consent(),site.db().site(),site.peerSiteId(),1);
        assertEquals(0,history.runOnce());assertEquals(1,history.migrationIssues().size());
        assertEquals(invalid,((Number)history.migrationIssues().getFirst().get("numeric_id")).longValue());
        assertEquals(1,history.runOnce(),"A missing historical dependency must not starve the next scan page");
        var reads=readSite(site);reads.commands().setHistory(history);
        assertThrows(IllegalStateException.class,()->site.db().transactions().required(()->{reads.commands().markOne(pair.owner(),first);throw new IllegalStateException("rollback");}));
        assertEquals(0,RelationReplicationIT.count(site,"SELECT count(*) FROM eatwhat_sync.notification_history_read"));
        reads.commands().markAll(pair.owner());
        long later=site.db().runtime().queryForObject("INSERT INTO public.notifications(account_id,actor_id,type,target_type,target_id,data) VALUES(?,?,'follow','user',?,'{}'::jsonb) RETURNING id",Long.class,pair.owner(),pair.actor(),pair.actor());
        for(int i=0;i<4;i++)history.runOnce();
        assertEquals(0,unread(site,pair.owner(),first));assertEquals(1,unread(site,pair.owner(),later));
        assertEquals(1,RelationReplicationIT.count(site,"SELECT count(*) FROM eatwhat_sync.notification_history_read"));
        assertEquals(1,RelationReplicationIT.count(site,"SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='notification.history' AND envelope->'payloadJson'->>'action'='READ'"));
    }

    private record ReadSite(RelationReplicationIT.Site site, NotificationReadStore store,
                            NotificationReadCommands commands, NotificationReadWorker worker,
                            InboxService inbox) { }
    private record ReadPair(ReadSite a, ReadSite b) { }

    private static ReadPair readPair(RelationReplicationIT.Pair pair) {
        return new ReadPair(readSite(pair.a()), readSite(pair.b()));
    }

    private static ReadSite readSite(RelationReplicationIT.Site site) {
        var store = new NotificationReadStore(site.db().runtime());
        var outbox = new OutboxService(site.db().runtime(), site.db().transactions(), CODEC, site.db().site());
        var commands = new NotificationReadCommands(site.db().runtime(), site.db().transactions(), outbox, store,
                site.consent(), site.db().site(), site.peerSiteId());
        var worker = new NotificationReadWorker(site.db().transactions(), store, commands, site.consent(), 32);
        var causal = new RelationCausalStore(site.db().runtime());
        var handlers = new EventHandlerRegistry(List.of(
                new RelationEventHandler(site.db().runtime(), site.store(), causal, site.consent()),
                new RelationCausalEventHandler(RelationCausalEvents.ACTION, site.db().runtime(), site.store(), causal, site.consent()),
                new RelationCausalEventHandler(RelationCausalEvents.BRIDGE, site.db().runtime(), site.store(), causal, site.consent()),
                new NotificationReadEventHandler(site.db().runtime(), store, site.consent())));
        var inbox = new InboxService(site.db().runtime(), site.db().transactions(), CODEC, handlers);
        return new ReadSite(site, store, commands, worker, inbox);
    }

    private static List<SyncEvent> readEvents(RelationReplicationIT.Site site) {
        return site.db().runtime().query("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type=? ORDER BY local_order",
                (row, index) -> CODEC.decode(row.getString(1)), NotificationReadEvents.AGGREGATE);
    }

    private static List<Long> noticeIds(RelationReplicationIT.Site site, long recipient) {
        return site.db().runtime().query("SELECT id FROM public.notifications WHERE account_id=? ORDER BY id",
                (row, index) -> row.getLong(1), recipient);
    }

    private static long unread(RelationReplicationIT.Site site, long recipient, long noticeId) {
        return RelationReplicationIT.count(site,
                "SELECT count(*) FROM public.notifications WHERE account_id=? AND id=? AND read_at IS NULL",
                recipient, noticeId);
    }

    /** Adjust a synthetic source fact and its local projection together while bypassing V22's immutable fixture trigger. */
    private static void changeNoticeTimes(RelationReplicationIT.Site site, long noticeId, Long createdAt, Long expiresAt) throws Exception {
        long oldCreated = RelationReplicationIT.count(site,
                "SELECT (extract(epoch FROM created_at)*1000)::bigint FROM public.notifications WHERE id=?", noticeId);
        long oldExpires = RelationReplicationIT.count(site,
                "SELECT (extract(epoch FROM expires_at)*1000)::bigint FROM public.notifications WHERE id=?", noticeId);
        long nextCreated = createdAt == null ? oldCreated : createdAt;
        long nextExpires = expiresAt == null ? oldExpires : expiresAt;
        try (var connection = site.db().admin().getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL session_replication_role='replica'");
            }
            try (var statement = connection.prepareStatement("UPDATE public.notifications SET created_at=to_timestamp(?/1000.0),expires_at=to_timestamp(?/1000.0) WHERE id=?")) {
                statement.setLong(1, nextCreated); statement.setLong(2, nextExpires); statement.setLong(3, noticeId); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    UPDATE eatwhat_sync.relation_notification SET payload=jsonb_set(
                      jsonb_set(payload,'{createdAt}',to_jsonb(?::bigint)),'{expiresAt}',to_jsonb(?::bigint))
                    WHERE numeric_id=?
                    """)) {
                statement.setLong(1, nextCreated); statement.setLong(2, nextExpires); statement.setLong(3, noticeId);
                assertEquals(1, statement.executeUpdate());
            }
            try (var statement = connection.prepareStatement("""
                    UPDATE eatwhat_sync.relation_operation SET payload=jsonb_set(
                      jsonb_set(payload,'{notification,createdAt}',to_jsonb(?::bigint)),
                      '{notification,expiresAt}',to_jsonb(?::bigint))
                    WHERE (payload->'notification'->>'numericId')::bigint=?
                    """)) {
                statement.setLong(1, nextCreated); statement.setLong(2, nextExpires); statement.setLong(3, noticeId);
                assertEquals(1, statement.executeUpdate());
            }
            connection.commit();
        }
    }

    private static void process(ReadSite site) {
        for (var lease : site.inbox().claim(100, LEASE)) site.inbox().process(lease);
    }

    private static void deliverReads(ReadSite from, ReadSite to) {
        for (var lease : from.site().delivery().claimForAggregate(from.site().peerSiteId(), 100, LEASE,
                NotificationReadEvents.AGGREGATE)) {
            to.inbox().receive(lease.event(), lease.event().originSite());
            assertTrue(from.site().delivery().acknowledge(lease, "DURABLY_QUEUED"));
        }
        process(to);
    }

    private static void deliverFirstRead(ReadSite from, ReadSite to, UUID expectedEventId) {
        var leases = from.site().delivery().claimForAggregate(from.site().peerSiteId(), 1, LEASE,
                NotificationReadEvents.AGGREGATE);
        assertEquals(1, leases.size());
        var lease = leases.getFirst();
        assertEquals(expectedEventId, lease.event().eventId());
        to.inbox().receive(lease.event(), lease.event().originSite());
        assertTrue(from.site().delivery().acknowledge(lease, "DURABLY_QUEUED"));
        process(to);
    }

    private static void deliverRelations(ReadSite from, ReadSite to) {
        for (var lease : from.site().delivery().claimForAggregates(to.site().db().site().getId(), 100, LEASE,
                java.util.Set.of(RelationEvents.AGGREGATE))) {
            to.inbox().receive(lease.event(), lease.event().originSite());
            assertTrue(from.site().delivery().acknowledge(lease, "DURABLY_QUEUED"));
        }
        process(to);
        to.inbox().requeueWaiting(100, Duration.ZERO);
        process(to);
    }

    private static ConsentModels.Job syncConsent(RelationReplicationIT.Pair pair, long userId, boolean finishGrant) {
        var a = pair.a();
        var b = pair.b();
        var withdrawn = a.consent().withdraw(userId);
        ConsentModels.Job purge = a.consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == userId && job.kind().equals("PURGE") && job.epoch() == withdrawn.epoch())
                .findFirst().orElseThrow();
        assertEquals("APPLIED", b.consent().apply(a.consent().export(purge)));
        a.consent().complete(purge);

        var granted = a.consent().grant(userId, RelationReplicationIT.POLICY);
        ConsentModels.Job upsert = a.consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == userId && job.kind().equals("UPSERT") && job.epoch() == granted.epoch())
                .findFirst().orElseThrow();
        assertEquals("APPLIED", b.consent().apply(a.consent().export(upsert)));
        if (finishGrant) a.consent().complete(upsert);
        return upsert;
    }

    @Test void identityReadinessRetryKeepsOldEpochBindingUntilNewEventIsPublished() throws Exception {
        var pair = RelationReplicationIT.pair(PG);
        var sites = readPair(pair);
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, true));
        RelationReplicationIT.exchange(pair);
        long notificationId = noticeIds(pair.a(), pair.owner()).getFirst();

        sites.a().commands().markOne(pair.owner(), notificationId);
        sites.a().worker().runOnce();
        var old = readEvents(pair.a()).getFirst();
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE event_id=?", old.eventId()));

        // Snapshot grant reaches the peer while local identity readiness remains pending.
        ConsentModels.Job pendingGrant = syncConsent(pair, pair.actor(), false);
        var member = sites.a().store().pendingMembers(10).getFirst();
        long oldEpoch = member.actorEpoch();
        sites.a().worker().runOnce();
        assertEquals(old.eventId(), pair.a().db().runtime().queryForObject(
                "SELECT event_id FROM eatwhat_sync.notification_read_member WHERE batch_id=? AND notification_gid=?",
                UUID.class, member.batchId(), member.notificationGid()));
        assertEquals(oldEpoch, RelationReplicationIT.count(pair.a(),
                "SELECT actor_epoch FROM eatwhat_sync.notification_read_member WHERE batch_id=? AND notification_gid=?",
                member.batchId(), member.notificationGid()));

        // Completing the already-applied grant snapshot is the transition to identity_ready=true.
        pair.a().consent().complete(pendingGrant);
        sites.a().worker().runOnce();
        var events = readEvents(pair.a());
        assertEquals(2, events.size());
        var replacement = NotificationReadEvents.parse(events.getLast().payloadJson());
        assertNotEquals(old.eventId(), events.getLast().eventId());
        assertNotEquals(NotificationReadEvents.parse(old.payloadJson()).dotSite(), replacement.dotSite());
        assertTrue(replacement.context().isEmpty(), "A fresh epoch stream cannot depend on the withheld old dot");
        assertEquals(0, unread(pair.a(), pair.owner(), notificationId), "Reauthorization keeps the campus read monotonic");
    }

    @Test void doneMembersReopenIndependentlyWhenOneSiblingKeepsTheBatchWaiting() throws Exception {
        var pair = RelationReplicationIT.pair(PG);
        long secondActor = RelationReplicationIT.user(pair.a(), pair.b(), "notification-second-actor");
        var sites = readPair(pair);
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, true));
        assertTrue(pair.a().commands().change(RelationKind.follow, secondActor, pair.owner(), null, true));
        RelationReplicationIT.exchange(pair);
        List<Long> ids = noticeIds(pair.a(), pair.owner());
        assertEquals(2, ids.size());

        sites.a().commands().markAll(pair.owner());
        sites.a().worker().runOnce();
        deliverReads(sites.a(), sites.b());
        sites.a().worker().runOnce();
        assertEquals(2, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE status='DONE'"));

        // First actor is granted in the peer snapshot but remains identity-not-ready locally; second is ready.
        syncConsent(pair, pair.actor(), false);
        syncConsent(pair, secondActor, true);
        sites.a().worker().runOnce();

        assertEquals(2, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE status='WAIT'"));
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE status='WAIT' AND actor_gid=(SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?) AND event_id IS NOT NULL", pair.actor()));
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE status='WAIT' AND actor_gid=(SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?) AND actor_epoch=(SELECT epoch FROM eatwhat_consent.subject WHERE user_id=?)", secondActor, secondActor));
        assertEquals(3, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_operation"));
    }

    @Test void oldEpochAckAfterRegrantCausesASecondReadEventForTheRetainedNotice() throws Exception {
        var pair = RelationReplicationIT.pair(PG);
        var sites = readPair(pair);
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, true));
        RelationReplicationIT.exchange(pair);
        long notificationId = noticeIds(pair.b(), pair.owner()).getFirst();
        sites.b().commands().markOne(pair.owner(), notificationId);
        sites.b().worker().runOnce();
        SyncEvent old = readEvents(pair.b()).getFirst();

        syncConsent(pair, pair.owner(), true);
        assertEquals(0, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_domain"));
        deliverFirstRead(sites.b(), sites.a(), old.eventId()); // Old epoch is acknowledged as a stale no-op.
        assertEquals(0, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_domain"),
                "A stale no-op must not latch a recipient into managed read writes");
        assertEquals(1, unread(pair.a(), pair.owner(), notificationId),
                "The retained campus notification stays unread after the stale event is acknowledged");
        assertEquals(1, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM public.notifications WHERE account_id=? AND id=?", pair.owner(), notificationId));

        sites.b().worker().runOnce();
        List<SyncEvent> events = readEvents(pair.b());
        assertEquals(2, events.size());
        var oldOperation = NotificationReadEvents.parse(old.payloadJson());
        var newOperation = NotificationReadEvents.parse(events.getLast().payloadJson());
        assertNotEquals(oldOperation.dotSite(), newOperation.dotSite());
        assertFalse(newOperation.context().containsKey(oldOperation.dotSite()));
        deliverReads(sites.b(), sites.a());
        assertEquals(0, unread(pair.a(), pair.owner(), notificationId),
                "A fresh epoch operation must update the retained campus notification after stale ACK recovery");
    }

    @Test void readArrivingBeforeItsNoticeWaitsAndAppliesAfterTheRelationSourceArrives() throws Exception {
        var pair = RelationReplicationIT.pair(PG);
        var sites = readPair(pair);
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, true));
        long notificationId = noticeIds(pair.a(), pair.owner()).getFirst();
        sites.a().commands().markOne(pair.owner(), notificationId);
        sites.a().worker().runOnce();
        deliverReads(sites.a(), sites.b());
        assertEquals(1, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM eatwhat_sync.inbox WHERE aggregate_type=? AND status='WAITING_DEPENDENCY'",
                NotificationReadEvents.AGGREGATE));
        assertEquals(0, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM public.notifications WHERE id=?", notificationId));

        // Keep the read event in the inbox registry that knows its handler while the source relation arrives.
        deliverRelations(sites.a(), sites.b());
        assertEquals(1, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM public.notifications WHERE id=? AND read_at IS NOT NULL", notificationId));
        assertEquals(0, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM eatwhat_sync.inbox WHERE aggregate_type=? AND status='WAITING_DEPENDENCY'",
                NotificationReadEvents.AGGREGATE));
    }

    @Test void readAllUsesTheExactObservedSetAndForeignNumericIdsRemainNoOps() throws Exception {
        var pair = RelationReplicationIT.pair(PG);
        var sites = readPair(pair);
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, true));
        long firstId = noticeIds(pair.a(), pair.owner()).getFirst();
        sites.a().commands().markAll(pair.owner());
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_member"));

        long lateActor = RelationReplicationIT.user(pair.a(), pair.b(), "notification-late-actor");
        assertTrue(pair.a().commands().change(RelationKind.follow, lateActor, pair.owner(), null, true));
        long lateId = noticeIds(pair.a(), pair.owner()).stream().filter(id -> id != firstId).findFirst().orElseThrow();
        long oldCreatedAt = RelationReplicationIT.count(pair.a(),
                "SELECT (extract(epoch FROM created_at)*1000)::bigint FROM public.notifications WHERE id=?", firstId) - 3_600_000;
        changeNoticeTimes(pair.a(), lateId, oldCreatedAt, oldCreatedAt + Duration.ofDays(30).toMillis());
        assertEquals(1, unread(pair.a(), pair.owner(), lateId), "A later notice with an earlier created_at is outside the observed set");
        assertEquals(0, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE notification_id=?", lateId));

        sites.a().commands().markOne(pair.actor(), lateId);
        assertEquals(1, unread(pair.a(), pair.owner(), lateId), "A foreign account's numeric ID remains an API no-op");
        assertEquals(0, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE notification_id=?", lateId));
        assertEquals(0, readEvents(pair.a()).size());
    }

    @Test void expiredReadIsAWithoutRebuildAndDoesNotExtendTheSourceExpiry() throws Exception {
        var pair = RelationReplicationIT.pair(PG);
        var sites = readPair(pair);
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, true));
        RelationReplicationIT.exchange(pair);
        long notificationId = noticeIds(pair.a(), pair.owner()).getFirst();
        long expiredAt = System.currentTimeMillis() - 60_000;
        long expiredCreatedAt = expiredAt - Duration.ofDays(30).toMillis();
        changeNoticeTimes(pair.a(), notificationId, expiredCreatedAt, expiredAt);
        changeNoticeTimes(pair.b(), notificationId, expiredCreatedAt, expiredAt);
        pair.b().db().admin().update("DELETE FROM public.notifications WHERE id=?", notificationId);

        sites.a().commands().markOne(pair.owner(), notificationId);
        sites.a().worker().runOnce();
        UUID readEventId = readEvents(pair.a()).getFirst().eventId();
        deliverReads(sites.a(), sites.b());
        assertEquals(0, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM public.notifications WHERE id=?", notificationId));
        String inboxState = pair.b().db().runtime().queryForObject(
                "SELECT status || ':' || coalesce(last_error,'') FROM eatwhat_sync.inbox WHERE event_id=?",
                String.class, readEventId);
        assertEquals(1, RelationReplicationIT.count(pair.b(), """
                SELECT count(*) FROM eatwhat_sync.notification_read_operation
                WHERE notification_gid=(SELECT global_id FROM eatwhat_identity.entity_identity
                  WHERE entity_type='notifications' AND numeric_id=?)
                """, notificationId), "Expired read event inbox state: " + inboxState);
        assertEquals(expiredAt, RelationReplicationIT.count(pair.b(),
                "SELECT (payload->>'expiresAt')::bigint FROM eatwhat_sync.relation_notification WHERE numeric_id=?", notificationId));
    }

    @Test void failedOutboxAppendRollsBackOnlyTheWorkerAttemptAndDurableReadIntentRetries() throws Exception {
        var pair = RelationReplicationIT.pair(PG);
        var sites = readPair(pair);
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, true));
        long notificationId = noticeIds(pair.a(), pair.owner()).getFirst();
        sites.a().commands().markOne(pair.owner(), notificationId);
        pair.a().db().admin().execute("CREATE FUNCTION eatwhat_sync.synthetic_notification_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic notification outbox failure'; END $$");
        pair.a().db().admin().execute("CREATE TRIGGER synthetic_notification_reject BEFORE INSERT ON eatwhat_sync.outbox FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.synthetic_notification_reject()");
        assertThrows(DataAccessException.class, sites.a().worker()::runOnce);
        assertEquals(0, unread(pair.a(), pair.owner(), notificationId), "Local read remains durable for retry");
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE status='READY' AND event_id IS NULL"));
        assertEquals(0, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_operation"));
        pair.a().db().admin().execute("DROP TRIGGER synthetic_notification_reject ON eatwhat_sync.outbox");
        pair.a().db().admin().execute("DROP FUNCTION eatwhat_sync.synthetic_notification_reject()");
        sites.a().worker().runOnce();
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_operation"));
    }

    @Test void apiFailureRollsBackTheLocalReadAndItsExactBatchMembershipTogether() throws Exception {
        var pair = RelationReplicationIT.pair(PG);
        var sites = readPair(pair);
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, true));
        long notificationId = noticeIds(pair.a(), pair.owner()).getFirst();
        pair.a().db().admin().execute("CREATE FUNCTION eatwhat_sync.synthetic_member_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic member failure'; END $$");
        pair.a().db().admin().execute("CREATE TRIGGER synthetic_member_reject BEFORE INSERT ON eatwhat_sync.notification_read_member FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.synthetic_member_reject()");
        assertThrows(DataAccessException.class, () -> sites.a().commands().markOne(pair.owner(), notificationId));
        assertEquals(1, unread(pair.a(), pair.owner(), notificationId), "The API transaction rolls back the local read");
        assertEquals(0, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_batch"));
        assertEquals(0, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_member"));
        pair.a().db().admin().execute("DROP TRIGGER synthetic_member_reject ON eatwhat_sync.notification_read_member");
        pair.a().db().admin().execute("DROP FUNCTION eatwhat_sync.synthetic_member_reject()");
    }

    @Test void twoWorkersAndARecreatedWorkerPublishOneOperationForTheSameReadMember() throws Exception {
        var pair = RelationReplicationIT.pair(PG);
        var sites = readPair(pair);
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, true));
        long notificationId = noticeIds(pair.a(), pair.owner()).getFirst();
        sites.a().commands().markOne(pair.owner(), notificationId);
        var secondWorker = new NotificationReadWorker(pair.a().db().transactions(), sites.a().store(), sites.a().commands(),
                pair.a().consent(), 32);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> { start.await(); sites.a().worker().runOnce(); return null; });
            Future<?> second = pool.submit(() -> { start.await(); secondWorker.runOnce(); return null; });
            start.countDown(); first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        var restarted = new NotificationReadWorker(pair.a().db().transactions(), sites.a().store(), sites.a().commands(),
                pair.a().consent(), 32);
        restarted.runOnce();
        assertEquals(1, RelationReplicationIT.count(pair.a(), "SELECT count(*) FROM eatwhat_sync.notification_read_operation"));
        assertEquals(1, readEvents(pair.a()).size());
    }

    @Test void managedRecipientBlocksLegacyReadAfterFeatureOffAndKeepsDurableReadForResume() throws Exception {
        var pair = RelationReplicationIT.pair(PG);
        var sites = readPair(pair);
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, true));
        long alreadyReadId = noticeIds(pair.a(), pair.owner()).getFirst();

        // The enabled API first acquires the durable recipient latch and records a resumable read member.
        sites.a().commands().markOne(pair.owner(), alreadyReadId);
        assertEquals(1, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.notification_read_domain WHERE recipient_gid=("
                        + "SELECT global_id FROM eatwhat_identity.entity_identity "
                        + "WHERE entity_type='users' AND numeric_id=?)", pair.owner()));
        assertEquals(1, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE notification_id=?", alreadyReadId));

        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationService legacyService = new NotificationService();
        ReflectionTestUtils.setField(legacyService, "notificationMapper", mapper);
        ReflectionTestUtils.setField(legacyService, "objectStorageService", mock(ObjectStorageService.class));
        legacyService.setManagedDomainWriteGuard(new ManagedDomainWriteGuard(pair.a().db().runtime()));
        // Simulate a deployment with sync toggled off after this recipient became managed.
        legacyService.setNotificationReadCommands(null);
        bindToken(loginAs(pair.owner()));
        long secondActor = RelationReplicationIT.user(pair.a(), pair.b(), "notification-off-on-actor");
        assertTrue(pair.a().commands().change(RelationKind.follow, pair.owner(), secondActor, null, true));
        long foreignId = noticeIds(pair.a(), secondActor).getFirst();

        when(mapper.countUnreadByAccountId(pair.owner())).thenReturn(0L);
        assertEquals(BizCode.SUCCESS.getCode(), legacyService.markAllAsRead().getCode(),
                "An empty legacy mark-all remains a no-op after the recipient is managed");
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L, 0L, 0L, 1L);
        assertEquals(BizCode.SUCCESS.getCode(), legacyService.markOneAsRead(alreadyReadId).getCode(),
                "Already-read numeric IDs keep the legacy success response");
        assertEquals(BizCode.SUCCESS.getCode(), legacyService.markOneAsRead(Long.MAX_VALUE).getCode(),
                "A nonexistent numeric ID is a no-op");
        assertEquals(BizCode.SUCCESS.getCode(), legacyService.markOneAsRead(foreignId).getCode(),
                "A foreign-account numeric ID is a no-op");

        assertTrue(pair.a().commands().change(RelationKind.follow, secondActor, pair.owner(), null, true));
        long pendingId = noticeIds(pair.a(), pair.owner()).stream()
                .filter(id -> id != alreadyReadId).findFirst().orElseThrow();
        assertEquals(1, unread(pair.a(), pair.owner(), pendingId), "Fixture must be a real unread row");
        assertEquals(BizCode.DATA_SYNC_PENDING.getCode(), legacyService.markOneAsRead(pendingId).getCode(),
                "Turning the feature off must not silently perform an unmanaged legacy write");
        assertEquals(1, unread(pair.a(), pair.owner(), pendingId), "The managed unread row must remain unchanged");
        verify(mapper, never()).markOneAsRead(pair.owner(), pendingId);

        // Re-enabling the worker resumes the durable member recorded before the feature was switched off.
        sites.a().worker().runOnce();
        assertEquals(1, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.notification_read_operation WHERE notification_gid=("
                        + "SELECT global_id FROM eatwhat_identity.entity_identity "
                        + "WHERE entity_type='notifications' AND numeric_id=?)", alreadyReadId));
        assertEquals(1, RelationReplicationIT.count(pair.a(),
                "SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE notification_id=? AND event_id IS NOT NULL",
                alreadyReadId));

        long unmanagedRecipient = RelationReplicationIT.user(pair.a(), pair.b(), "notification-unmanaged-recipient");
        long unmanagedNotice = pair.a().db().runtime().queryForObject("""
                INSERT INTO public.notifications(account_id,actor_id,type,target_type,target_id,data)
                VALUES(?,?,'follow','user',?,'{}'::jsonb) RETURNING id
                """, Long.class, unmanagedRecipient, pair.owner(), pair.owner());
        bindToken(loginAs(unmanagedRecipient));
        assertEquals(BizCode.SUCCESS.getCode(), legacyService.markOneAsRead(unmanagedNotice).getCode());
        verify(mapper).markOneAsRead(unmanagedRecipient, unmanagedNotice);
        assertEquals(1, unread(pair.a(), unmanagedRecipient, unmanagedNotice),
                "The fresh default-off path delegates to the legacy mapper without claiming ownership");
    }

    @Test void inboundReadLatchesTheRecipientAndFeatureOffPreservesTheNextUnreadIntent() throws Exception {
        var pair = RelationReplicationIT.pair(PG);
        var sites = readPair(pair);
        assertTrue(RelationReplicationIT.changeResult(pair, pair.a(), RelationKind.follow, true));
        RelationReplicationIT.exchange(pair);
        List<Long> initialNotices = noticeIds(pair.a(), pair.owner());
        assertEquals(1, initialNotices.size());

        sites.a().commands().markOne(pair.owner(), initialNotices.getFirst());
        sites.a().worker().runOnce();
        deliverReads(sites.a(), sites.b());
        assertEquals(0, unread(pair.b(), pair.owner(), initialNotices.getFirst()));
        assertEquals(1, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM eatwhat_sync.notification_read_domain"),
                "A successfully applied inbound read must establish the recipient ownership latch");

        long secondActor = RelationReplicationIT.user(pair.a(), pair.b(), "notification-inbound-latch-actor");
        assertTrue(pair.a().commands().change(RelationKind.follow, secondActor, pair.owner(), null, true));
        RelationReplicationIT.exchange(pair);
        long secondNotice = noticeIds(pair.b(), pair.owner()).stream()
                .filter(id -> !id.equals(initialNotices.getFirst())).findFirst().orElseThrow();
        assertEquals(1, unread(pair.b(), pair.owner(), secondNotice));

        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationService legacyService = new NotificationService();
        ReflectionTestUtils.setField(legacyService, "notificationMapper", mapper);
        ReflectionTestUtils.setField(legacyService, "objectStorageService", mock(ObjectStorageService.class));
        legacyService.setManagedDomainWriteGuard(new ManagedDomainWriteGuard(pair.b().db().runtime()));
        legacyService.setNotificationReadCommands(null);
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        bindToken(loginAs(pair.owner()));
        long readOutboxBeforeOffWrite = RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type=?", NotificationReadEvents.AGGREGATE);

        assertEquals(BizCode.DATA_SYNC_PENDING.getCode(), legacyService.markOneAsRead(secondNotice).getCode());
        assertEquals(1, unread(pair.b(), pair.owner(), secondNotice),
                "Feature-off legacy path must preserve the unread value while the recipient is managed");
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE notification_id=?", secondNotice));
        assertEquals(readOutboxBeforeOffWrite, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type=?", NotificationReadEvents.AGGREGATE));

        long operationsBeforeResume = RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.notification_read_operation");
        sites.b().commands().markOne(pair.owner(), secondNotice);
        sites.b().worker().runOnce();
        assertEquals(0, unread(pair.b(), pair.owner(), secondNotice));
        assertEquals(1, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.notification_read_member WHERE notification_id=?", secondNotice));
        assertEquals(operationsBeforeResume + 1, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.notification_read_operation"));
        deliverReads(sites.b(), sites.a());
        assertEquals(0, unread(pair.a(), pair.owner(), secondNotice),
                "The resumed read must reach the source site's retained notification");
    }
}
