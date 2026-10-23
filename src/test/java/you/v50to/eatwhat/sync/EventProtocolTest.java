package you.v50to.eatwhat.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventProtocolTest {
    private final EventCodec codec = new EventCodec();

    @Test
    void vectorsDistinguishCausalityFromConcurrencyAndAreImmutable() {
        var mutable = new HashMap<>(Map.of("campus", 1L, "external", 0L));
        var first = new VersionVector(mutable);
        mutable.put("campus", 100L);
        assertEquals(Map.of("campus", 1L), first.counters());
        assertThrows(UnsupportedOperationException.class, () -> first.counters().put("campus", 2L));
        assertEquals(VersionVector.Relation.EQUAL, first.compare(new VersionVector(Map.of("campus", 1L))));
        assertEquals(VersionVector.Relation.BEFORE, first.compare(first.increment("external")));
        assertEquals(VersionVector.Relation.AFTER, first.compare(VersionVector.empty()));
        assertEquals(VersionVector.Relation.CONCURRENT, first.compare(VersionVector.empty().increment("external")));
        assertEquals(first, EventCodec.vectorFromJson(EventCodec.vectorJson(first)));
        assertThrows(IllegalArgumentException.class, () -> new VersionVector(Map.of("campus", -1L)));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionVector(Map.of("campus", VersionVector.MAX_COUNTER)).increment("campus"));
        var history = new HashMap<String, Long>();for(int i=0;i<17;i++)history.put("retired-site-"+i,1L);assertEquals(17,new VersionVector(history).counters().size());
        var tooMany = new HashMap<String, Long>();
        for (int i = 0; i <= VersionVector.MAX_SITES; i++) tooMany.put("site" + i, 1L);
        assertThrows(IllegalArgumentException.class, () -> new VersionVector(tooMany));
    }

    @Test
    void canonicalEnvelopeHashCoversHeadersAndPreservesDecimalPrecision() {
        SyncEvent event = event("{\"amount\":0.10000000000000000001,\"a\":1.0}");
        var first = codec.encode(event);
        SyncEvent reordered = copy(event, event.aggregateId(), event.numericId(), event.originInstanceId(),
                "{\"a\":1,\"amount\":0.10000000000000000001}");
        assertEquals(first, codec.encode(reordered));
        assertEquals(first, codec.encode(codec.decode(first.json())));
        assertNotEquals(first.hash(), codec.encode(copy(event, UUID.randomUUID(), event.numericId(), event.originInstanceId(), event.payloadJson())).hash());
        assertNotEquals(first.hash(), codec.encode(copy(event, event.aggregateId(), event.numericId() + 1, event.originInstanceId(), event.payloadJson())).hash());
        assertNotEquals(first.hash(), codec.encode(copy(event, event.aggregateId(), event.numericId(), UUID.randomUUID(), event.payloadJson())).hash());
        assertNotEquals(first.hash(), codec.encode(copy(event, event.aggregateId(), event.numericId(), event.originInstanceId(), "{\"a\":2}")).hash());
        assertTrue(first.json().contains("0.10000000000000000001"));
        assertEquals(64, first.hash().length());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":1,\"a\":2}", "{}{}", "{\"a\":NaN}", "{\"a\":1e10000}", "{\"a\":1e1000}", "{\"a\":\"\\uD800\"}", "{\"a\":\"\\u0000\"}", ""})
    void ambiguousOrUnsafeJsonIsRejected(String input) {
        assertThrows(IllegalArgumentException.class, () -> EventCodec.canonicalJson(input));
    }

    @Test
    void jsonArrayOrderRemainsMeaningfulButObjectKeyOrderDoesNot() {
        assertEquals(EventCodec.canonicalJson("{\"b\":-0.0,\"a\":1e1}"), EventCodec.canonicalJson("{\"a\":10,\"b\":0}"));
        assertNotEquals(EventCodec.sha256(EventCodec.canonicalJson("[1,2]")),
                EventCodec.sha256(EventCodec.canonicalJson("[2,1]")));
    }

    @Test
    void payloadAndEnvelopeSizeDepthAndExactFieldsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(event("{\"x\":\"" + "界".repeat(50_000) + "\"}")));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(event("[]")));
        assertThrows(IllegalArgumentException.class, () -> EventCodec.canonicalJson("[".repeat(33) + "0" + "]".repeat(33)));
        String encoded = codec.encode(event("{}" )).json();
        assertThrows(IllegalArgumentException.class, () -> codec.decode(encoded.replace("{", "{\"extra\":true,")));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(encoded.replace("\"numericId\":1000000001", "\"numericId\":\"1000000001\"")));
    }

    @Test
    void eventRequiresExactlyOneOriginIncrementAndTypedUniqueDependencies() {
        SyncEvent valid = event("{}");
        assertThrows(IllegalArgumentException.class, () -> new SyncEvent(valid.eventId(), valid.originSite(), valid.originInstanceId(),
                valid.aggregateType(), valid.aggregateId(), valid.numericId(), valid.eventType(), 1,
                valid.baseVersion(), valid.nextVersion().increment("external"), List.of(), null, "cmd", UUID.randomUUID(), "{}"));
        var dependencies = new ArrayList<SyncEvent.Dependency>();
        var dependency = new SyncEvent.Dependency("users", UUID.randomUUID());
        dependencies.add(dependency);
        SyncEvent immutable = new SyncEvent(valid.eventId(), valid.originSite(), valid.originInstanceId(), valid.aggregateType(),
                valid.aggregateId(), valid.numericId(), valid.eventType(), 2, valid.baseVersion(), valid.nextVersion(), dependencies,
                null, "cmd", UUID.randomUUID(), "{}");
        dependencies.clear();
        assertEquals(List.of(dependency), immutable.dependencies());
        assertEquals(2, codec.decode(codec.encode(immutable).json()).schemaVersion(), "Unknown versions are available for durable quarantine");
        assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.aggregateId(), VersionVector.MAX_COUNTER + 1, valid.originInstanceId(), "{}"));
    }

    @Test
    void inboundScopeAlwaysClearsAfterFailureAndRejectsNestedReplay() {
        assertFalse(InboundApplicationScope.isActive());
        assertThrows(IllegalStateException.class, () -> InboundApplicationScope.run(() -> {
            assertTrue(InboundApplicationScope.isActive());
            return InboundApplicationScope.run(() -> null);
        }));
        assertFalse(InboundApplicationScope.isActive());
        assertEquals("done", InboundApplicationScope.run(() -> "done"));
        assertFalse(InboundApplicationScope.isActive());
    }

    private SyncEvent event(String payload) {
        return new SyncEvent(UUID.randomUUID(), "campus", UUID.randomUUID(), "test_counter", UUID.randomUUID(),
                1_000_000_001L, "counter.created", 1, VersionVector.empty(), VersionVector.empty().increment("campus"),
                List.of(), null, "command-1", UUID.randomUUID(), payload);
    }

    private SyncEvent copy(SyncEvent e, UUID gid, long numeric, UUID incarnation, String payload) {
        return new SyncEvent(e.eventId(), e.originSite(), incarnation, e.aggregateType(), gid, numeric, e.eventType(),
                e.schemaVersion(), e.baseVersion(), e.nextVersion(), e.dependencies(), e.actorId(), e.commandKey(), e.correlationId(), payload);
    }
}
