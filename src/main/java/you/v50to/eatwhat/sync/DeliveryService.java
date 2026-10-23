package you.v50to.eatwhat.sync;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Database delivery bookkeeping only; network I/O always runs outside these transactions. */
public final class DeliveryService {
    private static final Duration MAX_LEASE = Duration.ofMinutes(15);
    private static final Duration MAX_RETRY = Duration.ofDays(1);
    private static final Set<String> RECEIPTS = Set.of("APPLIED", "ALREADY_APPLIED", "DURABLY_QUEUED", "CONFLICT");
    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final EventCodec codec;

    public DeliveryService(JdbcTemplate jdbc, SyncTransactions transactions, EventCodec codec) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.transactions.requireSameDataSource(jdbc);
        this.codec = Objects.requireNonNull(codec);
    }

    public record Lease(UUID eventId, String targetSite, UUID leaseToken, Instant leaseUntil, int attempts, SyncEvent event) { }

    public List<Lease> claim(String targetSite, int limit, Duration leaseDuration) {
        return claimInternal(targetSite, limit, leaseDuration, null);
    }

    /** Same pre-lock filtering for a transport that permits several explicitly registered aggregate types. */
    public List<Lease> claimForAggregates(String targetSite, int limit, Duration leaseDuration, Set<String> aggregateTypes) {
        Objects.requireNonNull(aggregateTypes, "aggregateTypes");
        if (aggregateTypes.isEmpty() || aggregateTypes.size() > 64) {
            throw new IllegalArgumentException("A transport requires between one and sixty-four aggregate types");
        }
        aggregateTypes.forEach(type -> SyncEvent.requireName(type, "aggregateType", 64));
        return claimInternal(targetSite, limit, leaseDuration, aggregateTypes.stream().sorted().toArray(String[]::new));
    }

    /** Filter before locking so a synthetic-only transport cannot claim or disclose business events. */
    public List<Lease> claimForAggregate(String targetSite, int limit, Duration leaseDuration, String aggregateType) {
        SyncEvent.requireName(aggregateType, "aggregateType", 64);
        return claimInternal(targetSite, limit, leaseDuration, new String[]{aggregateType});
    }

    private List<Lease> claimInternal(String targetSite, int limit, Duration leaseDuration, String[] aggregateTypes) {
        SyncEvent.requireName(targetSite, "targetSite", 64);
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Claim limit must be between 1 and 500");
        long millis = durationMillis(leaseDuration, MAX_LEASE, false);
        return transactions.independent(() -> jdbc.query("""
                WITH candidates AS (
                  SELECT d.event_id,d.target_site FROM eatwhat_sync.delivery d
                    JOIN eatwhat_sync.outbox o ON o.event_id=d.event_id
                  WHERE d.target_site=? AND (?::text[] IS NULL OR o.aggregate_type=ANY(?::text[]))
                    AND ((d.status='READY' AND d.available_at<=clock_timestamp())
                     OR (d.status='LEASED' AND d.lease_until<=clock_timestamp()))
                  ORDER BY d.available_at,o.local_order
                  LIMIT ? FOR UPDATE OF d SKIP LOCKED
                )
                UPDATE eatwhat_sync.delivery d SET status='LEASED',attempts=d.attempts+1,
                  lease_token=gen_random_uuid(),lease_until=clock_timestamp()+(? * interval '1 millisecond'),last_error=NULL
                FROM candidates c JOIN eatwhat_sync.outbox o ON o.event_id=c.event_id
                WHERE d.event_id=c.event_id AND d.target_site=c.target_site
                RETURNING d.event_id,d.target_site,d.lease_token,d.lease_until,d.attempts,o.envelope::text,o.envelope_hash
                """, (row, number) -> {
                    SyncEvent event = codec.decode(row.getString(6));
                    if (!event.eventId().equals(row.getObject(1, UUID.class))
                            || !codec.encode(event).hash().equals(row.getString(7))) {
                        throw new IllegalStateException("Persisted outbound event identity or hash is inconsistent");
                    }
                    return new Lease(row.getObject(1, UUID.class), row.getString(2), row.getObject(3, UUID.class),
                            row.getTimestamp(4).toInstant(), row.getInt(5), event);
                },
                targetSite, aggregateTypes, aggregateTypes, limit, millis));
    }

    /**
     * Correlate a remote receipt with the persisted destination and immutable envelope hash.
     * ALREADY_ACKED reports an already committed fact; a cleared or stale token never authorizes a mutation.
     */
    public String settleRemote(UUID eventId, String targetSite, UUID token, String envelopeHash,
                               String receipt, Duration retryDelay) {
        SyncEvent.requireUuid(eventId, "eventId");
        SyncEvent.requireName(targetSite, "targetSite", 64);
        SyncEvent.requireUuid(token, "leaseToken");
        if (envelopeHash == null || !envelopeHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid envelope hash");
        }
        long retryMillis = durationMillis(retryDelay, MAX_RETRY, true);
        if (receipt == null || (!RECEIPTS.contains(receipt)
                && !receipt.equals("REJECTED") && !receipt.equals("RETRYABLE_ERROR"))) return "REJECTED";
        return transactions.independent(() -> {
            // Wait first, then evaluate the DB clock in the mutation below. A pure row-lock wait
            // can cross expiry without changing the row version or re-evaluating an earlier predicate.
            var rows = jdbc.query("""
                    SELECT d.status,d.lease_token,o.envelope_hash
                    FROM eatwhat_sync.delivery d JOIN eatwhat_sync.outbox o ON o.event_id=d.event_id
                    WHERE d.event_id=? AND d.target_site=? FOR UPDATE OF d
                    """, (row, number) -> new RemoteState(row.getString(1), row.getObject(2, UUID.class), row.getString(3)),
                    eventId, targetSite);
            if (rows.isEmpty()) return "REJECTED";
            RemoteState state = rows.getFirst();
            if (!envelopeHash.equals(state.hash())) return "REJECTED";
            if (state.status().equals("ACKED") && RECEIPTS.contains(receipt)) return "ALREADY_ACKED";
            if (!state.status().equals("LEASED") || !token.equals(state.token())) return "STALE_LEASE";
            int changed;
            String result;
            if (RECEIPTS.contains(receipt)) {
                changed = jdbc.update("""
                        UPDATE eatwhat_sync.delivery SET status='ACKED',acked_at=clock_timestamp(),receipt_status=?,
                          lease_token=NULL,lease_until=NULL,last_error=NULL
                        WHERE event_id=? AND target_site=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()
                        """, receipt, eventId, targetSite, token);
                result = "ACKED";
            } else if (receipt.equals("REJECTED")) {
                changed = jdbc.update("""
                        UPDATE eatwhat_sync.delivery SET status='QUARANTINED',lease_token=NULL,lease_until=NULL,last_error='REMOTE_REJECTED'
                        WHERE event_id=? AND target_site=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()
                        """, eventId, targetSite, token);
                result = "QUARANTINED";
            } else {
                changed = jdbc.update("""
                        UPDATE eatwhat_sync.delivery SET status='READY',available_at=clock_timestamp()+(? * interval '1 millisecond'),
                          lease_token=NULL,lease_until=NULL,last_error='REMOTE_RETRYABLE_ERROR'
                        WHERE event_id=? AND target_site=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()
                        """, retryMillis, eventId, targetSite, token);
                result = "RETRIED";
            }
            return changed == 1 ? result : "STALE_LEASE";
        });
    }

    private record RemoteState(String status, UUID token, String hash) { }

    /** A receipt acknowledges durable reception, not necessarily application or convergence. */
    public boolean acknowledge(Lease lease, String receiptStatus) {
        validateLease(lease);
        if (receiptStatus == null || !RECEIPTS.contains(receiptStatus)) {
            throw new IllegalArgumentException("Only a durable receipt can acknowledge delivery");
        }
        return transactions.independent(() -> {
            if (!lockLease(lease)) return false;
            return jdbc.update("""
                UPDATE eatwhat_sync.delivery SET status='ACKED',acked_at=clock_timestamp(),receipt_status=?,
                  lease_token=NULL,lease_until=NULL,last_error=NULL
                WHERE event_id=? AND target_site=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()
                """, receiptStatus, lease.eventId(), lease.targetSite(), lease.leaseToken()) == 1;
        });
    }

    public boolean retry(Lease lease, Duration delay, String error) {
        validateLease(lease);
        long millis = durationMillis(delay, MAX_RETRY, true);
        validateError(error);
        return transactions.independent(() -> {
            if (!lockLease(lease)) return false;
            return jdbc.update("""
                UPDATE eatwhat_sync.delivery SET status='READY',available_at=clock_timestamp()+(? * interval '1 millisecond'),
                  lease_token=NULL,lease_until=NULL,last_error=?
                WHERE event_id=? AND target_site=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()
                """, millis, error, lease.eventId(), lease.targetSite(), lease.leaseToken()) == 1;
        });
    }

    public boolean quarantine(Lease lease, String error) {
        validateLease(lease);
        validateError(error);
        return transactions.independent(() -> {
            if (!lockLease(lease)) return false;
            return jdbc.update("""
                UPDATE eatwhat_sync.delivery SET status='QUARANTINED',lease_token=NULL,lease_until=NULL,last_error=?
                WHERE event_id=? AND target_site=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()
                """, error, lease.eventId(), lease.targetSite(), lease.leaseToken()) == 1;
        });
    }

    private boolean lockLease(Lease lease) {
        // Wait for any current row locker first. A separate following UPDATE evaluates its DB-clock
        // expiry condition after the wait, even if that locker never changed the row version.
        return !jdbc.queryForList("""
                SELECT event_id FROM eatwhat_sync.delivery
                WHERE event_id=? AND target_site=? AND status='LEASED' AND lease_token=? FOR UPDATE
                """, UUID.class, lease.eventId(), lease.targetSite(), lease.leaseToken()).isEmpty();
    }

    private static void validateLease(Lease lease) {
        Objects.requireNonNull(lease, "lease");
        SyncEvent.requireUuid(lease.eventId(), "eventId");
        SyncEvent.requireUuid(lease.leaseToken(), "leaseToken");
        SyncEvent.requireName(lease.targetSite(), "targetSite", 64);
    }

    private static long durationMillis(Duration duration, Duration maximum, boolean allowZero) {
        if (duration == null || duration.isNegative() || duration.compareTo(maximum) > 0
                || (!allowZero && duration.toMillis() < 1)) {
            throw new IllegalArgumentException("Duration is outside the allowed range");
        }
        return duration.toMillis();
    }

    private static void validateError(String error) {
        if (error == null || error.isBlank() || error.length() > 512) {
            throw new IllegalArgumentException("A bounded error code or diagnostic is required");
        }
    }
}
