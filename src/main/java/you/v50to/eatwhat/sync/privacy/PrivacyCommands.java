package you.v50to.eatwhat.sync.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentModels;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.dto.PrivacyDTO;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.identity.EntityType;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures only explicitly changed privacy flags and preserves local intent while authorization is unavailable. */
public final class PrivacyCommands {
    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final PrivacyStore store;
    private final OutboxService outbox;
    private final EntityIdentityService identities;
    private final ConsentStore consent;
    private final SiteProperties site;
    private final String peer;
    private final PrivacyEvents events;
    private final AtomicBoolean workerActive = new AtomicBoolean();
    private record ReadySubject(long userId, UUID subject, long epoch) { }

    public PrivacyCommands(JdbcTemplate jdbc, SyncTransactions transactions, PrivacyStore store,
                           OutboxService outbox, EntityIdentityService identities, ConsentStore consent,
                           SiteProperties site, String peer) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.store = store;
        this.outbox = outbox;
        this.identities = identities;
        this.consent = consent;
        this.site = site;
        this.peer = peer;
        this.events = new PrivacyEvents(consent);
    }

    public Map<String, Boolean> snapshotBeforeChange(long userId) { return store.readPublic(userId); }

    /** Persist domain ownership before the legacy privacy mapper triggers account snapshot bookkeeping. */
    public void prepareChange(long userId, Map<String, Boolean> before) {
        UUID subject = identities.findGlobalId(EntityType.USERS, userId)
                .orElseThrow(() -> new IllegalStateException("PRIVACY_IDENTITY_REQUIRED"));
        store.ensure(subject, before);
    }

    public <T> T atomic(Supplier<T> operation) { return transactions.required(operation); }

    public void lockSubject(long userId) {
        jdbc.query("SELECT subject_id FROM eatwhat_consent.subject WHERE user_id=? FOR UPDATE",
                (row, index) -> row.getObject(1, UUID.class), userId);
    }

    /** Must share the transaction that updates public.privacy. */
    public void capture(long userId, PrivacyDTO dto, Map<String, Boolean> before) {
        transactions.required(() -> {
            UUID subject = identities.findGlobalId(EntityType.USERS, userId)
                    .orElseThrow(() -> new IllegalStateException("PRIVACY_IDENTITY_REQUIRED"));
            jdbc.query("SELECT subject_id FROM eatwhat_consent.subject WHERE user_id=? FOR UPDATE",
                    (row, index) -> row.getObject(1, UUID.class), userId);
            PrivacyStore.Snapshot initial = store.ensure(subject, before);
            Map<String, Boolean> after = store.readPublic(userId);
            Map<String, Boolean> changes = new LinkedHashMap<>();
            if (dto.getFollowing() != null && !dto.getFollowing().equals(before.get("following"))) {
                changes.put("following", after.get("following"));
            }
            if (dto.getFollower() != null && !dto.getFollower().equals(before.get("follower"))) {
                changes.put("follower", after.get("follower"));
            }
            if (changes.isEmpty()) return null;
            ConsentModels.State consentState = consent.get(userId);
            long epoch = consentState == null ? 0 : consentState.epoch();
            initial = store.beginEpoch(subject, epoch);
            VersionVector context = initial.vector();
            String dotSite = site.getId();
            long counter = context.counters().getOrDefault(dotSite, 0L) + 1;
            Map<String, Map<String, Long>> contexts = new LinkedHashMap<>();
            for (String field : changes.keySet()) {
                VersionVector fieldVector = field.equals("following")
                        ? initial.followingVector() : initial.followerVector();
                Map<String, Long> fieldContext = new LinkedHashMap<>(fieldVector.counters());
                fieldContext.put(dotSite, counter - 1);
                contexts.put(field, Map.copyOf(fieldContext));
            }
            UUID eventId = UUID.randomUUID();
            PrivacyEvents.Operation operation = new PrivacyEvents.Operation(subject, epoch, dotSite, counter,
                    Map.copyOf(changes), Map.copyOf(contexts));
            store.insertOperation(eventId, operation);
            VersionVector next = context.increment(dotSite);
            for (var change : changes.entrySet()) {
                VersionVector fieldNext = new VersionVector(contexts.get(change.getKey())).increment(dotSite);
                VersionVector oldFalse = change.getKey().equals("following")
                        ? initial.followingFalseContext() : initial.followerFalseContext();
                VersionVector falseContext = change.getValue() ? oldFalse : fieldNext;
                store.apply(subject, change.getKey(), change.getValue(), fieldNext, falseContext);
            }
            boolean allowed = consent.replicationAllowed(userId);
            store.mergeVector(subject, next, !allowed);
            if (allowed && consentState != null) {
                append(eventId, operation);
                store.markPublished(subject, epoch);
            }
            return null;
        });
    }

    private void append(UUID eventId, PrivacyEvents.Operation operation) {
        long numericId = jdbc.queryForObject("SELECT eatwhat_sync.next_relation_number()", Long.class);
        SyncEvent event = new SyncEvent(eventId, site.getId(), site.getInstanceId(), PrivacyEvents.AGGREGATE,
                eventId, numericId, PrivacyEvents.EVENT, 1, VersionVector.empty(),
                VersionVector.empty().increment(site.getId()),
                List.of(new SyncEvent.Dependency("users", operation.subject())), operation.subject(),
                "privacy:" + eventId, eventId, PrivacyEvents.payload(operation));
        events.validate(event, new NodeIdentity(site.getId(), site.getInstanceId()));
        outbox.append(event, you.v50to.eatwhat.cluster.ClusterRouting.legacyRecipients(site,peer));
    }

    public boolean isManaged(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity i JOIN eatwhat_sync.privacy_domain d
                  ON d.subject_gid=i.global_id WHERE i.entity_type='users' AND i.numeric_id=? AND d.managed)
                """, Boolean.class, userId));
    }

    /** Relays the existing causal privacy heads without inventing new true/false user intent. */
    public void runOnce() {
        if (Thread.currentThread().isInterrupted() || !workerActive.compareAndSet(false, true)) return;
        try {
            List<ReadySubject> ready = jdbc.query("""
                    SELECT s.user_id,s.subject_id,s.epoch FROM eatwhat_consent.subject s
                    JOIN public.users u ON u.id=s.user_id
                    LEFT JOIN eatwhat_sync.privacy_domain d ON d.subject_gid=s.subject_id
                    WHERE s.status='GRANTED' AND s.identity_ready AND s.withdrawal_request_id IS NULL
                      AND s.policy_version=? AND NOT u.banned AND (NOT ? OR s.valid_until>clock_timestamp())
                      AND (d.subject_gid IS NULL OR d.published_epoch<s.epoch OR d.local_dirty)
                    ORDER BY s.subject_id LIMIT 64
                    """, (row, index) -> new ReadySubject(row.getLong(1), row.getObject(2, UUID.class), row.getLong(3)),
                    consent.policyVersion(), site.getMode() == SiteProperties.Mode.EXTERNAL && !consent.multiwriterEnabled());
            for (ReadySubject candidate : ready) {
                if (Thread.currentThread().isInterrupted()) return;
                try { transactions.required(() -> publishCurrent(candidate)); }
                catch (RuntimeException retryLater) { /* Durable domain state is retried on the next account cycle. */ }
            }
        } finally { workerActive.set(false); }
    }

    private Void publishCurrent(ReadySubject candidate) {
        jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id=? FOR UPDATE",
                (row, index) -> row.getLong(1), candidate.subject());
        ConsentModels.State state = consent.getBySubject(candidate.subject());
        if (state == null || state.epoch() != candidate.epoch() || !consent.replicationAllowed(candidate.userId())) return null;
        Map<String, Boolean> publicState = store.readPublic(candidate.userId());
        PrivacyStore.Snapshot snapshot = store.ensure(candidate.subject(), publicState);
        snapshot = store.beginEpoch(candidate.subject(), state.epoch());

        String dotSite = site.getId();
        Map<String, Boolean> changes = Map.of();
        Map<String, Map<String, Long>> contexts = Map.of();
        UUID eventId = UUID.randomUUID();
        PrivacyEvents.Operation operation = new PrivacyEvents.Operation(candidate.subject(), state.epoch(), dotSite,
                1, changes, contexts, store.stateBridge(candidate.subject(), snapshot));
        store.insertOperation(eventId, operation);
        append(eventId, operation);
        store.markPublished(candidate.subject(), state.epoch());
        return null;
    }
}
