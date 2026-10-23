package you.v50to.eatwhat.sync.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import you.v50to.eatwhat.sync.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static you.v50to.eatwhat.sync.transport.TransportProtocol.*;

class CampusSyncWorkerTest {
    private final NodeIdentity local = new NodeIdentity("campus-test", UUID.randomUUID());
    private final NodeIdentity peer = new NodeIdentity("external-test", UUID.randomUUID());
    private final EventCodec codec = new EventCodec();
    private final DeliveryService delivery = mock(DeliveryService.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    private final InboxService inbox = mock(InboxService.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));

    @Test void missingPerEventAckRetriesOnlyTheMissingItem() {
        var first = lease(); var second = lease();
        when(delivery.claimForAggregates(anyString(), anyInt(), any(), anySet())).thenReturn(List.of(first, second));
        worker((path, request) -> new Response(request.header().reply(), List.of(), path.endsWith("/push")
                ? List.of(ack(request.events().getFirst(), "DURABLY_QUEUED")) : List.of())).runOnce();
        verify(delivery).acknowledge(first, "DURABLY_QUEUED");
        verify(delivery).retry(eq(second), any(), eq("TRANSPORT_RETRY"));
        verify(delivery, never()).acknowledge(eq(second), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "token", "event", "request", "sender", "receiver", "version"})
    void forgedOrMisroutedResponseCannotAcknowledgeAnyEvent(String fault) {
        var lease = lease();
        when(delivery.claimForAggregates(anyString(), anyInt(), any(), anySet())).thenReturn(List.of(lease));
        worker((path, request) -> {
            if (!path.endsWith("/push")) return new Response(request.header().reply(), List.of(), List.of());
            EventItem original = request.events().getFirst();
            Header header = request.header().reply();
            if (fault.equals("request")) header = new Header(1, UUID.randomUUID(), peer, local);
            if (fault.equals("sender")) header = new Header(1, header.requestId(), local, local);
            if (fault.equals("receiver")) header = new Header(1, header.requestId(), peer, peer);
            if (fault.equals("version")) header = new Header(2, header.requestId(), peer, local);
            var ack = new Ack(fault.equals("event") ? UUID.randomUUID() : original.eventId(),
                    fault.equals("hash") ? "f".repeat(64) : original.envelopeHash(),
                    fault.equals("token") ? UUID.randomUUID() : original.leaseToken(), "DURABLY_QUEUED");
            return new Response(header, List.of(), List.of(ack));
        }).runOnce();
        verify(delivery, never()).acknowledge(any(), anyString());
        verify(delivery).retry(eq(lease), any(), eq("TRANSPORT_RETRY"));
    }

    @Test void partialSuccessRejectAndTransientFailuresSettleIndependently() {
        var success = lease(); var rejected = lease(); var transientFailure = lease();
        when(delivery.claimForAggregates(anyString(), anyInt(), any(), anySet()))
                .thenReturn(List.of(success, rejected, transientFailure));
        worker((path, request) -> new Response(request.header().reply(), List.of(), !path.endsWith("/push") ? List.of()
                : List.of(ack(request.events().get(0), "APPLIED"), ack(request.events().get(1), "REJECTED"),
                ack(request.events().get(2), "RETRYABLE_ERROR")))).runOnce();
        verify(delivery).acknowledge(success, "APPLIED");
        verify(delivery).quarantine(eq(rejected), anyString());
        verify(delivery).retry(eq(transientFailure), any(), anyString());
        verify(delivery, times(1)).acknowledge(any(), anyString());
    }

    @Test void failureInPushStillAllowsTheIndependentPullDirection() throws Exception {
        var lease = lease(); var client = mock(TransportClient.class);
        when(delivery.claimForAggregates(anyString(), anyInt(), any(), anySet())).thenReturn(List.of(lease));
        when(client.exchange(anyString(), any())).thenAnswer(call -> {
            String path = call.getArgument(0); Request request = call.getArgument(1);
            if (path.endsWith("/push")) throw new IOException("synthetic");
            return new Response(request.header().reply(), List.of(), List.of());
        });
        worker(client).runOnce();
        verify(client).exchange(eq(PREFIX + "/pull"), any());
        verify(delivery).retry(eq(lease), any(), anyString());
    }

    @Test void oneWorkerDoesNotOverlapItsOwnNetworkCycles() throws Exception {
        var lease = lease(); var entered = new CountDownLatch(1); var released = new CountDownLatch(1);
        when(delivery.claimForAggregates(anyString(), anyInt(), any(), anySet())).thenReturn(List.of(lease));
        var worker = worker((path, request) -> {
            if (path.endsWith("/push")) { entered.countDown(); assertTrue(released.await(5, TimeUnit.SECONDS)); }
            return new Response(request.header().reply(), List.of(), List.of());
        });
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(worker::runOnce);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                threads.submit(worker::runOnce).get(2, TimeUnit.SECONDS);
                verify(delivery, times(1)).claimForAggregates(anyString(), anyInt(), any(), anySet());
            } finally { released.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void backoffSaturatesWithoutOverflowAndJitterStaysBounded() {
        var minimum = new RetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1), () -> 0.0);
        var maximum = new RetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1), () -> 0.999);
        assertEquals(Duration.ofMillis(500), minimum.delay(1));
        assertEquals(Duration.ofSeconds(30), minimum.delay(Integer.MAX_VALUE));
        assertEquals(Duration.ofMinutes(1), maximum.delay(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(Duration.ZERO, Duration.ofSeconds(1)));
    }

    private CampusSyncWorker worker(TransportClient client) {
        return new CampusSyncWorker(local, peer, delivery, inbox, codec, client, Duration.ofSeconds(30),
                new RetryPolicy(Duration.ofMillis(10), Duration.ofSeconds(1), () -> 0.5), new TransportMetrics());
    }
    private DeliveryService.Lease lease() {
        var event = new SyncEvent(UUID.randomUUID(), local.siteId(), local.instanceId(), "synthetic.counter", UUID.randomUUID(),
                1_000_000_001L, "counter.incremented", 1, VersionVector.empty(), VersionVector.empty().increment(local.siteId()),
                List.of(), null, "synthetic", UUID.randomUUID(), "{\"delta\":1}");
        return new DeliveryService.Lease(event.eventId(), peer.siteId(), UUID.randomUUID(), Instant.now().plusSeconds(30), 1, event);
    }
    private Ack ack(EventItem item, String status) { return new Ack(item.eventId(), item.envelopeHash(), item.leaseToken(), status); }
}
