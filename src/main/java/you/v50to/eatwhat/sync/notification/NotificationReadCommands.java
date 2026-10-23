package you.v50to.eatwhat.sync.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Local read commands preserve the HTTP surface while recording an exact, durable replication intent. */
public final class NotificationReadCommands {
    private NotificationHistoryCommands history;
    public void setHistory(NotificationHistoryCommands history) { this.history=history; }
    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final OutboxService outbox;
    private final NotificationReadStore store;
    private final ConsentStore consent;
    private final SiteProperties site;
    private final String peerSite;

    public NotificationReadCommands(JdbcTemplate jdbc, SyncTransactions transactions, OutboxService outbox,
                                    NotificationReadStore store, ConsentStore consent, SiteProperties site,
                                    String peerSite) {
        this.jdbc = jdbc; this.transactions = transactions; this.outbox = outbox; this.store = store;
        this.consent = consent; this.site = site; this.peerSite = peerSite;
        transactions.requireSameDataSource(jdbc);
    }

    public void markOne(long accountId, Long notificationId) {
        transactions.required(() -> {
            if (notificationId == null) return null;
            List<NotificationReadStore.Basic> initial = store.unreadBasics(accountId, List.of(notificationId), false);
            if (initial.isEmpty()) return null;
            var users = new ArrayList<Long>(); users.add(accountId); users.add(initial.getFirst().actorId());
            store.lockConsentSubjects(users);
            List<NotificationReadStore.Basic> locked = store.unreadBasics(accountId, List.of(notificationId), true);
            if (locked.isEmpty()) return null;
            // A durable recipient latch lets an off-toggle freeze legacy writes, including notices
            // without a stable V22 identity that cannot become a replication member.
            store.markRecipientManaged(accountId);
            List<NotificationReadStore.Notice> stable = store.stableNotices(accountId, List.of(notificationId));
            recordIntent(stable);
            if(history!=null)history.captureRead(accountId,List.of(notificationId));
            store.markRead(accountId, List.of(notificationId));
            return null;
        });
    }

    public void markAll(long accountId) {
        transactions.required(() -> {
            List<NotificationReadStore.Basic> initial = store.unreadBasics(accountId);
            if (initial.isEmpty()) return null;
            var users = new ArrayList<Long>(); users.add(accountId);
            initial.stream().map(NotificationReadStore.Basic::actorId).forEach(users::add);
            // Consent subjects are locked in user_id order before any notification row can be locked or written.
            store.lockConsentSubjects(users);
            List<Long> observedIds = initial.stream().map(NotificationReadStore.Basic::id).toList();
            List<NotificationReadStore.Basic> locked = new ArrayList<>();
            for (List<Long> chunk : chunks(observedIds)) locked.addAll(store.unreadBasics(accountId, chunk, true));
            if (locked.isEmpty()) return null;
            List<Long> lockedIds = locked.stream().map(NotificationReadStore.Basic::id).toList();
            store.markRecipientManaged(accountId);
            List<NotificationReadStore.Notice> stable = new ArrayList<>();
            for (List<Long> chunk : chunks(lockedIds)) stable.addAll(store.stableNotices(accountId, chunk));
            recordIntent(stable);
            // The batch and every stable member are durable before the local monotonic read_at update.
            for (List<Long> chunk : chunks(lockedIds)) {
                if(history!=null)history.captureRead(accountId,chunk);
                store.markRead(accountId, chunk);
            }
            return null;
        });
    }

    private void recordIntent(List<NotificationReadStore.Notice> notices) {
        if (notices.isEmpty()) return;
        NotificationReadStore.Notice first = notices.getFirst();
        UUID batch = UUID.randomUUID();
        store.createBatch(batch, first.recipientGid(), first.recipientEpoch(), notices);
    }

    private static <T> List<List<T>> chunks(List<T> values) {
        List<List<T>> result = new ArrayList<>();
        for (int start = 0; start < values.size(); start += 500) {
            result.add(values.subList(start, Math.min(values.size(), start + 500)));
        }
        return result;
    }

    /** Appends one immutable operation in a fresh aggregate with an empty transport base. */
    void publish(NotificationReadStore.Member member, NotificationReadStore.Epoch actor,
                  NotificationReadStore.Epoch recipient) {
        var authorization = consent.outboundAuthorization(member.actorGid(), actor.epoch(),
                member.recipientGid(), recipient.epoch());
        if (!authorization.allowed()) {
            store.setMemberStatus(member, "WAIT");
            return;
        }

        String dotSite = NotificationReadEvents.dotSite(site.getId(), actor.epoch(), recipient.epoch());
        var membership=new you.v50to.eatwhat.cluster.ClusterMembershipView(jdbc);
        var remoteDots=membership.bound()?membership.retained().stream().filter(m->!m.siteId().equals(site.getId()))
                .map(m->NotificationReadEvents.dotSite(m.siteId(),actor.epoch(),recipient.epoch())).collect(java.util.stream.Collectors.toSet())
                :java.util.Set.of(NotificationReadEvents.dotSite(peerSite,actor.epoch(),recipient.epoch()));
        NotificationReadStore.Dot dot = store.nextDot(member.notificationGid(), member.recipientGid(), dotSite, remoteDots);
        UUID eventId = UUID.randomUUID();
        var operation = new NotificationReadEvents.Operation(dot.numericId(), member.notificationGid(),
                member.notificationId(), member.notificationOrigin(), member.recipientGid(), recipient.epoch(),
                member.actorGid(), actor.epoch(), dotSite, dot.counter(), dot.context());
        store.insertOperation(eventId, dot, operation);
        SyncEvent event = new SyncEvent(eventId, site.getId(), site.getInstanceId(),
                NotificationReadEvents.AGGREGATE, eventId, dot.numericId(), NotificationReadEvents.EVENT, 1,
                VersionVector.empty(), VersionVector.empty().increment(site.getId()),
                NotificationReadEvents.dependencies(operation), member.recipientGid(), "notification-read",
                eventId, NotificationReadEvents.payload(operation));
        outbox.append(event, you.v50to.eatwhat.cluster.ClusterRouting.legacyRecipients(site,peerSite));
        store.updateMember(member, recipient.epoch(), actor.epoch(), eventId, "WAIT");
    }
}
