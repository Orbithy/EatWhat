package you.v50to.eatwhat.sync.transport;

import you.v50to.eatwhat.sync.DeliveryService;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.InboxService;
import you.v50to.eatwhat.sync.SyncEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static you.v50to.eatwhat.sync.transport.TransportProtocol.*;

/** Campus is the only connection initiator. Database leases survive this worker or process. */
public final class CampusSyncWorker {
    private static final Set<String> DURABLE = Set.of("APPLIED", "ALREADY_APPLIED", "DURABLY_QUEUED", "CONFLICT");
    private static final Set<String> PUSH_RESULTS = Set.of("APPLIED", "ALREADY_APPLIED", "DURABLY_QUEUED", "CONFLICT",
            "REJECTED", "RETRYABLE_ERROR");
    private static final Set<String> ACK_RESULTS = Set.of("ACKED", "ALREADY_ACKED", "RETRIED", "QUARANTINED",
            "STALE_LEASE", "REJECTED", "RETRYABLE_ERROR");
    private final NodeIdentity local;
    private final NodeIdentity peer;
    private final DeliveryService delivery;
    private final InboxService inbox;
    private final EventCodec codec;
    private final TransportClient client;
    private final Duration leaseDuration;
    private final RetryPolicy retry;
    private final TransportMetrics metrics;
    private final EventPolicy policy;
    private final AtomicBoolean running = new AtomicBoolean();
    private int pullFailures;
    private long nextPullAt;

    public CampusSyncWorker(NodeIdentity local, NodeIdentity peer, DeliveryService delivery, InboxService inbox,
                            EventCodec codec, TransportClient client, Duration leaseDuration,
                            RetryPolicy retry, TransportMetrics metrics) {
        this(local, peer, delivery, inbox, codec, client, leaseDuration, retry, metrics, EventPolicy.SYNTHETIC_ONLY);
    }

    public CampusSyncWorker(NodeIdentity local, NodeIdentity peer, DeliveryService delivery, InboxService inbox,
                            EventCodec codec, TransportClient client, Duration leaseDuration,
                            RetryPolicy retry, TransportMetrics metrics, EventPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
        this.local = Objects.requireNonNull(local); this.peer = Objects.requireNonNull(peer);
        if (local.siteId().equals(peer.siteId()) || local.instanceId().equals(peer.instanceId())) {
            throw new IllegalArgumentException("Peer and local identities must differ");
        }
        this.delivery = Objects.requireNonNull(delivery); this.inbox = Objects.requireNonNull(inbox);
        this.codec = Objects.requireNonNull(codec); this.client = Objects.requireNonNull(client);
        this.leaseDuration = Objects.requireNonNull(leaseDuration); this.retry = Objects.requireNonNull(retry);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public void runOnce() {
        if (Thread.currentThread().isInterrupted()) return;
        if (!running.compareAndSet(false, true)) return;
        try {
            try { push(); } catch (Exception failure) { metrics.increment("push_failed"); }
            if (!Thread.currentThread().isInterrupted()
                    && (pullFailures == 0 || System.nanoTime() - nextPullAt >= 0)) {
                try { pull(); pullFailures = 0; }
                catch (Exception failure) {
                    metrics.increment("pull_failed");
                    pullFailures = Math.min(31, pullFailures + 1);
                    nextPullAt = System.nanoTime() + retry.delay(pullFailures).toNanos();
                }
            }
        } finally { running.set(false); }
    }

    private void push() {
        List<DeliveryService.Lease> claimed = delivery.claimForAggregates(peer.siteId(), MAX_BATCH,
                leaseDuration, policy.aggregateTypes());
        var leases = new HashMap<UUID, DeliveryService.Lease>();
        var items = new ArrayList<EventItem>();
        for (var lease : claimed) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                policy.validate(lease.event(), local);
                EventPolicy.OutboundDecision decision = policy.outbound(lease.event());
                if (decision == EventPolicy.OutboundDecision.WAIT) {
                    delivery.retry(lease, retry.delay(lease.attempts()), "CONSENT_NOT_READY");
                    metrics.increment("push_waiting_consent");
                    continue;
                }
                if (decision == EventPolicy.OutboundDecision.POLICY_BLOCKED) {
                    delivery.quarantine(lease, "CONSENT_EPOCH_BLOCKED");
                    metrics.increment("push_quarantined");
                    continue;
                }
                var encoded = codec.encode(lease.event());
                items.add(new EventItem(lease.eventId(), encoded.hash(), lease.leaseToken(), encoded.json()));
                leases.put(lease.eventId(), lease);
            } catch (IllegalArgumentException invalid) {
                try { delivery.quarantine(lease, "EVENT_POLICY_REJECTED"); metrics.increment("push_quarantined"); }
                catch (RuntimeException failure) { metrics.increment("push_failed"); }
            } catch (RuntimeException unavailable) {
                safeRetry(lease);
            }
        }
        if (items.isEmpty()) return;
        Request request = request(items, List.of(), 0);
        Map<UUID, Ack> receipts;
        try {
            Response response = client.exchange(PREFIX + "/push", request);
            validateHeader(request, response);
            if (!response.events().isEmpty()) throw new IllegalArgumentException("Unexpected push events");
            receipts = correlate(items, response.acks(), PUSH_RESULTS);
        } catch (Exception failure) {
            metrics.increment("push_failed");
            leases.values().forEach(this::safeRetry);
            return;
        }
        for (EventItem item : items) {
            if (Thread.currentThread().isInterrupted()) return;
            var lease = leases.get(item.eventId());
            Ack ack = receipts.get(item.eventId());
            if (ack == null || "RETRYABLE_ERROR".equals(ack.status())) { safeRetry(lease); continue; }
            try {
                boolean changed;
                if (DURABLE.contains(ack.status())) {
                    changed = delivery.acknowledge(lease, ack.status());
                    metrics.increment(changed ? "push_acked" : "stale_lease");
                } else {
                    changed = delivery.quarantine(lease, "PEER_REJECTED_EVENT");
                    metrics.increment(changed ? "push_quarantined" : "stale_lease");
                }
            } catch (RuntimeException failure) { metrics.increment("push_failed"); }
        }
    }

    private void safeRetry(DeliveryService.Lease lease) {
        if (Thread.currentThread().isInterrupted()) return;
        try {
            boolean changed = delivery.retry(lease, retry.delay(lease.attempts()), "TRANSPORT_RETRY");
            metrics.increment(changed ? "push_retried" : "stale_lease");
        } catch (RuntimeException failure) {
            // A DB outage leaves the committed lease intact; expiry is the recovery path.
            metrics.increment("push_failed");
        }
    }

    private void pull() throws Exception {
        Request request = request(List.of(), List.of(), MAX_BATCH);
        Response response = client.exchange(PREFIX + "/pull", request);
        validateHeader(request, response);
        if (!response.acks().isEmpty() || response.events().size() > MAX_BATCH) {
            throw new IllegalArgumentException("Invalid pull response shape");
        }
        var ids = new HashSet<UUID>();
        for (EventItem item : response.events()) {
            if (!ids.add(item.eventId())) throw new IllegalArgumentException("Repeated pull event");
        }
        var acks = new ArrayList<Ack>();
        for (EventItem item : response.events()) {
            String receipt;
            if (Thread.currentThread().isInterrupted()) return;
            try {
                SyncEvent event = codec.decode(item.envelope());
                if (!event.eventId().equals(item.eventId()) || !codec.encode(event).hash().equals(item.envelopeHash())) {
                    throw new IllegalArgumentException("Pull envelope mismatch");
                }
                policy.validate(event, peer);
                receipt = inbox.receive(event, peer.siteId()).receiptStatus().name();
                metrics.increment("pull_received");
            } catch (IllegalArgumentException invalid) {
                receipt = "REJECTED"; metrics.increment("pull_rejected");
            } catch (RuntimeException failure) { receipt = "RETRYABLE_ERROR"; }
            acks.add(new Ack(item.eventId(), item.envelopeHash(), item.leaseToken(), receipt));
        }
        if (acks.isEmpty()) return;
        Request acknowledgement = request(List.of(), acks, 0);
        try {
            Response accepted = client.exchange(PREFIX + "/ack", acknowledgement);
            validateHeader(acknowledgement, accepted);
            if (!accepted.events().isEmpty()) throw new IllegalArgumentException("Unexpected ACK events");
            Map<UUID, Ack> statuses = correlate(response.events(), accepted.acks(), ACK_RESULTS);
            for (EventItem item : response.events()) {
                Ack status = statuses.get(item.eventId());
                if (status == null || "REJECTED".equals(status.status()) || "RETRYABLE_ERROR".equals(status.status())) {
                    metrics.increment("ack_failed");
                } else metrics.increment("STALE_LEASE".equals(status.status()) ? "stale_lease" : "ack_sent");
            }
        } catch (Exception failure) {
            metrics.increment("ack_failed");
            // Local receipts are already durable. Do not delete or regenerate anything; re-pull deduplicates.
            throw failure;
        }
    }

    private Request request(List<EventItem> events, List<Ack> acks, int limit) {
        return new Request(new Header(VERSION, UUID.randomUUID(), local, peer), events, acks, limit);
    }

    private void validateHeader(Request request, Response response) {
        if (response == null || !request.header().reply().equals(response.header())) {
            throw new IllegalArgumentException("Mismatched transport response identity");
        }
    }

    private Map<UUID, Ack> correlate(List<EventItem> requested, List<Ack> replies, Set<String> statuses) {
        var expected = new HashMap<UUID, EventItem>();
        requested.forEach(item -> expected.put(item.eventId(), item));
        var result = new HashMap<UUID, Ack>();
        for (Ack ack : replies) {
            EventItem original = expected.get(ack.eventId());
            if (original == null || !original.envelopeHash().equals(ack.envelopeHash())
                    || !original.leaseToken().equals(ack.leaseToken()) || !statuses.contains(ack.status())
                    || result.putIfAbsent(ack.eventId(), ack) != null) {
                throw new IllegalArgumentException("Mismatched per-event receipt");
            }
        }
        return result;
    }
}
