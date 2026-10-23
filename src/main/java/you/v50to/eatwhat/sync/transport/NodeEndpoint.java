package you.v50to.eatwhat.sync.transport;

import you.v50to.eatwhat.sync.DeliveryService;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.InboxService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Authenticated node-only operations. Each durable item owns its independent short transaction. */
public final class NodeEndpoint {
    private final NodeIdentity local;
    private final NodeIdentity peer;
    private final InboxService inbox;
    private final DeliveryService delivery;
    private final EventCodec codec;
    private final Duration leaseDuration;
    private final Duration retryDelay;
    private final EventPolicy policy;

    public NodeEndpoint(NodeIdentity local, NodeIdentity peer, InboxService inbox, DeliveryService delivery,
                        EventCodec codec, Duration leaseDuration, Duration retryDelay) {
        this(local, peer, inbox, delivery, codec, leaseDuration, retryDelay, EventPolicy.SYNTHETIC_ONLY);
    }

    public NodeEndpoint(NodeIdentity local, NodeIdentity peer, InboxService inbox, DeliveryService delivery,
                        EventCodec codec, Duration leaseDuration, Duration retryDelay, EventPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
        this.local = Objects.requireNonNull(local);
        this.peer = Objects.requireNonNull(peer);
        this.inbox = Objects.requireNonNull(inbox);
        this.delivery = Objects.requireNonNull(delivery);
        this.codec = Objects.requireNonNull(codec);
        this.leaseDuration = Objects.requireNonNull(leaseDuration);
        this.retryDelay = Objects.requireNonNull(retryDelay);
    }

    public TransportProtocol.Response handle(String path, TransportProtocol.Request request) {
        if (request == null || request.header().version() != TransportProtocol.VERSION
                || !peer.equals(request.header().sender()) || !local.equals(request.header().receiver())) {
            throw new TransportException(403, "NODE_IDENTITY_MISMATCH");
        }
        return switch (path) {
            case TransportProtocol.PREFIX + "/push" -> push(request);
            case TransportProtocol.PREFIX + "/pull" -> pull(request);
            case TransportProtocol.PREFIX + "/ack" -> acknowledge(request);
            default -> throw new TransportException(404, "UNKNOWN_SYNC_PATH");
        };
    }

    private TransportProtocol.Response push(TransportProtocol.Request request) {
        shape(request.acks().isEmpty() && request.limit() == 0);
        var acks = new ArrayList<TransportProtocol.Ack>();
        for (var item : request.events()) {
            String status;
            try {
                var event = codec.decode(item.envelope());
                var encoded = codec.encode(event);
                if (!event.eventId().equals(item.eventId()) || !encoded.hash().equals(item.envelopeHash())) {
                    throw new IllegalArgumentException("INVALID_EVENT_CORRELATION");
                }
                policy.validate(event, peer);
                status = inbox.receive(event, peer.siteId()).receiptStatus().name();
            } catch (IllegalArgumentException rejected) { status = "REJECTED"; }
            catch (RuntimeException unavailable) { status = "RETRYABLE_ERROR"; }
            acks.add(new TransportProtocol.Ack(item.eventId(), item.envelopeHash(), item.leaseToken(), status));
        }
        return new TransportProtocol.Response(request.header().reply(), List.of(), acks);
    }

    private TransportProtocol.Response pull(TransportProtocol.Request request) {
        shape(request.events().isEmpty() && request.acks().isEmpty() && request.limit() >= 1
                && request.limit() <= TransportProtocol.MAX_BATCH);
        List<DeliveryService.Lease> leases;
        try {
            leases = delivery.claimForAggregates(peer.siteId(), request.limit(), leaseDuration, policy.aggregateTypes());
        } catch (RuntimeException unavailable) { throw new TransportException(503, "DELIVERY_UNAVAILABLE"); }
        var events = new ArrayList<TransportProtocol.EventItem>();
        for (var lease : leases) {
            try {
                policy.validate(lease.event(), local);
                EventPolicy.OutboundDecision decision = policy.outbound(lease.event());
                if (decision == EventPolicy.OutboundDecision.WAIT) {
                    delivery.retry(lease, retryDelay, "CONSENT_NOT_READY");
                    continue;
                }
                if (decision == EventPolicy.OutboundDecision.POLICY_BLOCKED) {
                    delivery.quarantine(lease, "CONSENT_EPOCH_BLOCKED");
                    continue;
                }
                var encoded = codec.encode(lease.event());
                events.add(new TransportProtocol.EventItem(lease.eventId(), encoded.hash(), lease.leaseToken(), encoded.json()));
            } catch (IllegalArgumentException rejected) {
                try { delivery.quarantine(lease, "EVENT_POLICY_REJECTED"); }
                catch (RuntimeException unavailable) { /* Expired leases remain recoverable on the next pull. */ }
            } catch (RuntimeException unavailable) {
                try { delivery.retry(lease, retryDelay, "OUTBOUND_POLICY_UNAVAILABLE"); }
                catch (RuntimeException ignored) { /* Expired leases remain recoverable on the next pull. */ }
            }
        }
        return new TransportProtocol.Response(request.header().reply(), events, List.of());
    }

    private TransportProtocol.Response acknowledge(TransportProtocol.Request request) {
        shape(request.events().isEmpty() && request.limit() == 0);
        var acks = new ArrayList<TransportProtocol.Ack>();
        for (var ack : request.acks()) {
            String status;
            try {
                status = delivery.settleRemote(ack.eventId(), peer.siteId(), ack.leaseToken(), ack.envelopeHash(), ack.status(), retryDelay);
            } catch (IllegalArgumentException rejected) { status = "REJECTED"; }
            catch (RuntimeException unavailable) { status = "RETRYABLE_ERROR"; }
            acks.add(new TransportProtocol.Ack(ack.eventId(), ack.envelopeHash(), ack.leaseToken(), status));
        }
        return new TransportProtocol.Response(request.header().reply(), List.of(), acks);
    }

    private static void shape(boolean valid) {
        if (!valid) throw new TransportException(400, "INVALID_OPERATION_SHAPE");
    }
}
