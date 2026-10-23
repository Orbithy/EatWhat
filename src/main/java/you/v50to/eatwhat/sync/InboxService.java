package you.v50to.eatwhat.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable internal Inbox foundation. No endpoint, scheduler or production domain handler is enabled. */
public final class InboxService {
    public enum InboxStatus { RECEIVED, PROCESSING, APPLIED, WAITING_DEPENDENCY, QUARANTINED, CONFLICT }

    public enum ReceiptStatus { APPLIED, ALREADY_APPLIED, DURABLY_QUEUED, CONFLICT }

    public record Receipt(UUID eventId, InboxStatus status, ReceiptStatus receiptStatus) {}

    public record Lease(UUID eventId, UUID token, Instant expiresAt) {
        public Lease {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public static final class StaleLeaseException extends IllegalStateException {
        public StaleLeaseException() {
            super("Inbox lease is no longer current and unexpired");
        }
    }

    private record SavedEvent(SyncEvent event, String hash, InboxStatus status, UUID leaseToken, boolean leaseValid) {}
    private record EntityState(long numericId, VersionVector vector, UUID lastEventId, boolean conflicted) {}

    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final EventCodec codec;
    private final EventHandlerRegistry handlers;
    private final JsonMapper metadataMapper = JsonMapper.builder().build();

    public InboxService(JdbcTemplate jdbc, SyncTransactions transactions, EventCodec codec, EventHandlerRegistry handlers) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        transactions.requireSameDataSource(jdbc);
        this.codec = Objects.requireNonNull(codec, "codec");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
    }

    /** expectedOrigin is supplied by a trusted internal caller; this parameter is not node authentication. */
    public Receipt receive(SyncEvent event, String expectedOrigin) {
        Objects.requireNonNull(event, "event");
        if (!event.originSite().equals(expectedOrigin)) {
            throw new IllegalArgumentException("Event origin does not match the expected source");
        }
        var encoded = codec.encode(event);
        return transactions.independent(() -> {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT to_regclass('eatwhat_sync.purged_personal_event') IS NOT NULL", Boolean.class))) {
                // The retention predicate locks subjects before the event lock, matching the erasure lock order.
                boolean blocked = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT eatwhat_sync.personal_event_retention_blocked(?::jsonb)", Boolean.class, encoded.json()));
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,20261021))", row -> {}, event.eventId().toString());
                var hashes = jdbc.queryForList("SELECT envelope_hash FROM eatwhat_sync.purged_personal_event WHERE event_id=?",
                        String.class, event.eventId());
                if (!hashes.isEmpty()) {
                    if (!encoded.hash().equals(hashes.getFirst())) throw new IllegalArgumentException("Purged event hash mismatch");
                    return new Receipt(event.eventId(), InboxStatus.APPLIED, ReceiptStatus.ALREADY_APPLIED);
                }
                if (blocked) throw new IllegalArgumentException("Personal event retention is withdrawn");
            }
            InboxStatus initial = handlers.find(event).isPresent() ? InboxStatus.RECEIVED : InboxStatus.QUARANTINED;
            jdbc.update("""
                    INSERT INTO eatwhat_sync.inbox(event_id,envelope,envelope_hash,origin_site,
                        aggregate_type,aggregate_id,numeric_id,status,last_error)
                    VALUES (?,?::jsonb,?,?,?,?,?,?,?) ON CONFLICT(event_id) DO NOTHING
                    """, event.eventId(), encoded.json(), encoded.hash(), event.originSite(), event.aggregateType(),
                    event.aggregateId(), event.numericId(), initial.name(),
                    initial == InboxStatus.QUARANTINED ? "UNSUPPORTED_EVENT_SCHEMA" : null);
            SavedEvent saved = lockedEvent(event.eventId());
            if (!encoded.hash().equals(saved.hash())) {
                throw new IllegalArgumentException("Event ID was already received with different immutable contents");
            }
            return receipt(event.eventId(), saved.status(), true);
        });
    }

    /** Claim a committed bounded batch. Expired processing leases can be reclaimed after worker failure. */
    public List<Lease> claim(int limit, Duration leaseDuration) {
        if (limit < 1 || limit > 100 || leaseDuration == null || leaseDuration.isNegative()
                || leaseDuration.isZero() || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0
                || leaseDuration.toMillis() < 1) {
            throw new IllegalArgumentException("Inbox claim requires 1..100 rows and a 1ms..5min lease");
        }
        return transactions.independent(() -> jdbc.query("""
                WITH claimable AS (
                    SELECT event_id FROM eatwhat_sync.inbox
                    WHERE available_at<=clock_timestamp()
                      AND (status='RECEIVED' OR (status='PROCESSING' AND lease_until<=clock_timestamp()))
                    ORDER BY received_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE eatwhat_sync.inbox i SET status='PROCESSING',attempts=i.attempts+1,
                    lease_token=gen_random_uuid(),lease_until=clock_timestamp()+(? * interval '1 millisecond'),last_error=NULL
                FROM claimable c WHERE i.event_id=c.event_id
                RETURNING i.event_id,i.lease_token,i.lease_until
                """, (row, index) -> new Lease(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getTimestamp(3).toInstant()), limit, leaseDuration.toMillis()));
    }

    /** Atomically apply same-database effects, causal state and APPLIED under the current lease. */
    public Receipt process(Lease lease) {
        Objects.requireNonNull(lease, "lease");
        return transactions.independent(() -> {
            SavedEvent saved = lockedEvent(lease.eventId());
            if (saved.status() != InboxStatus.PROCESSING || !lease.token().equals(saved.leaseToken()) || !saved.leaseValid()) {
                throw new StaleLeaseException();
            }
            SyncEvent event = saved.event();
            EventHandler handler = handlers.find(event).orElse(null);
            if (handler == null) {
                finish(lease, InboxStatus.QUARANTINED, "UNSUPPORTED_EVENT_SCHEMA");
                return receipt(event.eventId(), InboxStatus.QUARANTINED, false);
            }
            EntityState state = lockedState(event);
            if (state.numericId() != event.numericId()) {
                throw new IllegalStateException("Aggregate global/numeric identity mismatch");
            }
            return InboundApplicationScope.run(() -> {
                if (state.conflicted()) {
                    return waiting(lease, event, "AGGREGATE_CONFLICTED");
                }
                var relation = state.vector().compare(event.baseVersion());
                if (relation == VersionVector.Relation.BEFORE) {
                    return waiting(lease, event, "MISSING_PREDECESSOR");
                }
                if (relation != VersionVector.Relation.EQUAL) {
                    return conflict(lease, event, state, "CONCURRENT_OR_UNPROVEN_STALE_EVENT");
                }
                if (!handler.dependenciesSatisfied(event)) {
                    return waiting(lease, event, "MISSING_DEPENDENCY");
                }
                handler.apply(event);
                jdbc.update("""
                        UPDATE eatwhat_sync.entity_state SET applied_vector=?::jsonb,last_event_id=?
                        WHERE aggregate_type=? AND aggregate_id=?
                        """, EventCodec.vectorJson(event.nextVersion()), event.eventId(), event.aggregateType(), event.aggregateId());
                // Checked after effects: expiry here must roll back effects and vector along with this transaction.
                finish(lease, InboxStatus.APPLIED, null);
                return receipt(event.eventId(), InboxStatus.APPLIED, false);
            });
        });
    }

    /** Explicit bounded recovery hook; no scheduler, conflict resolution or unconditional quarantine release. */
    public boolean retryWaiting(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return transactions.independent(() -> {
            SavedEvent saved = lockedEvent(eventId);
            if ((saved.status() != InboxStatus.WAITING_DEPENDENCY && saved.status() != InboxStatus.QUARANTINED)
                    || handlers.find(saved.event()).isEmpty()) {
                return false;
            }
            return jdbc.update("""
                    UPDATE eatwhat_sync.inbox SET status='RECEIVED',available_at=clock_timestamp(),
                        lease_token=NULL,lease_until=NULL,last_error=NULL
                    WHERE event_id=? AND status IN ('WAITING_DEPENDENCY','QUARANTINED')
                    """, eventId) == 1;
        });
    }

    /**
     * Re-queue events whose dependency wait is older than minWait. Waiting events are not polled by claim();
     * this bounded hook is what lets a late-arriving parent unblock them without an operator.
     */
    public int requeueWaiting(int limit, Duration minWait) {
        if (limit < 1 || limit > 500 || minWait == null || minWait.isNegative() || minWait.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Requeue requires 1..500 rows and a wait between zero and one day");
        }
        return transactions.independent(() -> jdbc.update("""
                WITH due AS (
                    SELECT i.event_id FROM eatwhat_sync.inbox i JOIN eatwhat_sync.pending p ON p.event_id=i.event_id
                    WHERE i.status='WAITING_DEPENDENCY' AND p.resolved_at IS NULL
                      AND p.retry_at<=clock_timestamp()-(? * interval '1 millisecond')
                    ORDER BY p.retry_at,i.event_id LIMIT ? FOR UPDATE OF i SKIP LOCKED
                )
                UPDATE eatwhat_sync.inbox i SET status='RECEIVED',available_at=clock_timestamp(),
                    lease_token=NULL,lease_until=NULL,last_error=NULL
                FROM due WHERE i.event_id=due.event_id
                """, minWait.toMillis(), limit));
    }

    private Receipt waiting(Lease lease, SyncEvent event, String reason) {
        String dependencies = EventCodec.canonicalJson(metadataMapper.writeValueAsString(event.dependencies()));
        jdbc.update("""
                INSERT INTO eatwhat_sync.pending(event_id,dependencies,retry_at,reason)
                VALUES (?,?::jsonb,clock_timestamp(),?)
                ON CONFLICT(event_id) DO UPDATE SET dependencies=EXCLUDED.dependencies,
                    retry_at=EXCLUDED.retry_at,reason=EXCLUDED.reason,resolved_at=NULL
                """, event.eventId(), dependencies, reason);
        finish(lease, InboxStatus.WAITING_DEPENDENCY, reason);
        return receipt(event.eventId(), InboxStatus.WAITING_DEPENDENCY, false);
    }

    private Receipt conflict(Lease lease, SyncEvent event, EntityState state, String reason) {
        String currentEnvelope = currentBranchEvidence(event, state);
        jdbc.update("""
                INSERT INTO eatwhat_sync.conflict(conflict_id,event_id,current_vector,last_local_event_id,
                    current_envelope,incoming_envelope,reason)
                VALUES (?,?,?::jsonb,?,?::jsonb,?::jsonb,?)
                """, UUID.randomUUID(), event.eventId(), EventCodec.vectorJson(state.vector()), state.lastEventId(),
                currentEnvelope, codec.encode(event).json(), reason);
        jdbc.update("UPDATE eatwhat_sync.entity_state SET conflicted=true WHERE aggregate_type=? AND aggregate_id=?",
                event.aggregateType(), event.aggregateId());
        finish(lease, InboxStatus.CONFLICT, reason);
        return receipt(event.eventId(), InboxStatus.CONFLICT, false);
    }

    private void finish(Lease lease, InboxStatus status, String reason) {
        int updated = jdbc.update("""
                UPDATE eatwhat_sync.inbox SET status=?,last_error=?,lease_token=NULL,lease_until=NULL,
                    applied_at=CASE WHEN ?='APPLIED' THEN clock_timestamp() ELSE applied_at END
                WHERE event_id=? AND status='PROCESSING' AND lease_token=? AND lease_until>clock_timestamp()
                """, status.name(), reason, status.name(), lease.eventId(), lease.token());
        if (updated != 1) {
            throw new StaleLeaseException();
        }
        if (status != InboxStatus.WAITING_DEPENDENCY) {
            jdbc.update("UPDATE eatwhat_sync.pending SET resolved_at=clock_timestamp() WHERE event_id=? AND resolved_at IS NULL",
                    lease.eventId());
        }
    }

    private String currentBranchEvidence(SyncEvent incoming, EntityState state) {
        if (state.lastEventId() == null) {
            throw new IllegalStateException("Current aggregate has no provable applied event");
        }
        List<String> envelopes = jdbc.query("""
                SELECT envelope::text,envelope_hash FROM eatwhat_sync.outbox WHERE event_id=?
                UNION ALL
                SELECT envelope::text,envelope_hash FROM eatwhat_sync.inbox WHERE event_id=? AND status='APPLIED'
                """, (row, index) -> {
            SyncEvent applied = codec.decode(row.getString(1));
            var encoded = codec.encode(applied);
            if (!encoded.hash().equals(row.getString(2)) || !applied.eventId().equals(state.lastEventId())
                    || !applied.aggregateType().equals(incoming.aggregateType())
                    || !applied.aggregateId().equals(incoming.aggregateId()) || applied.numericId() != incoming.numericId()
                    || !applied.nextVersion().equals(state.vector())) {
                throw new IllegalStateException("Current aggregate event evidence does not match its applied state");
            }
            return encoded.json();
        }, state.lastEventId(), state.lastEventId());
        if (envelopes.isEmpty() || envelopes.stream().anyMatch(envelope -> !envelope.equals(envelopes.getFirst()))) {
            throw new IllegalStateException("Current aggregate event evidence is missing or inconsistent");
        }
        return envelopes.getFirst();
    }

    private SavedEvent lockedEvent(UUID eventId) {
        List<SavedEvent> rows = jdbc.query("""
                SELECT envelope::text,envelope_hash,status,lease_token,
                    COALESCE(lease_until>clock_timestamp(),false)
                FROM eatwhat_sync.inbox WHERE event_id=? FOR UPDATE
                """, (row, index) -> new SavedEvent(codec.decode(row.getString(1)), row.getString(2),
                InboxStatus.valueOf(row.getString(3)), row.getObject(4, UUID.class), row.getBoolean(5)), eventId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Inbox event does not exist");
        }
        SavedEvent saved = rows.getFirst();
        if (!eventId.equals(saved.event().eventId()) || !codec.encode(saved.event()).hash().equals(saved.hash())) {
            throw new IllegalStateException("Persisted Inbox envelope integrity mismatch");
        }
        return saved;
    }

    private EntityState lockedState(SyncEvent event) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.entity_state(aggregate_type,aggregate_id,numeric_id)
                VALUES (?,?,?) ON CONFLICT DO NOTHING
                """, event.aggregateType(), event.aggregateId(), event.numericId());
        // Read in a separate statement after unique-key competition commits. Both the GID key and
        // the numeric key can win a concurrent insert, so an arbitrary constraint target is unsafe.
        List<EntityState> states = jdbc.query("""
                SELECT numeric_id,applied_vector::text,last_event_id,conflicted FROM eatwhat_sync.entity_state
                WHERE aggregate_type=? AND aggregate_id=? FOR UPDATE
                """, (row, index) -> new EntityState(row.getLong(1),
                EventCodec.vectorFromJson(row.getString(2)),
                row.getObject(3, UUID.class), row.getBoolean(4)), event.aggregateType(), event.aggregateId());
        if (states.isEmpty()) {
            throw new IllegalStateException("Aggregate numeric identity is already assigned to another global identity");
        }
        return states.getFirst();
    }

    private static Receipt receipt(UUID eventId, InboxStatus status, boolean duplicate) {
        ReceiptStatus receipt = switch (status) {
            case APPLIED -> duplicate ? ReceiptStatus.ALREADY_APPLIED : ReceiptStatus.APPLIED;
            case CONFLICT -> ReceiptStatus.CONFLICT;
            default -> ReceiptStatus.DURABLY_QUEUED;
        };
        return new Receipt(eventId, status, receipt);
    }
}
