package you.v50to.eatwhat.sync.transport;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.sync.SyncEvent;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EventSchemaCapabilitiesTest {
    @Test void oldPeerOnlyAcceptsKnownLegacyFamiliesAtVersionOne() {
        var legacy = EventSchemas.fromProbe(Map.of());
        assertTrue(EventSchemas.supports(legacy, "catalog.operation", 1));
        assertFalse(EventSchemas.supports(legacy, "catalog.operation", 2));
        assertFalse(EventSchemas.supports(legacy, "activity_state", 1),
                "A new family at version one is still unreadable by an old receiver");
    }

    @Test void explicitCapabilityDoesNotFallBackToLegacyOrInferOtherVersions() {
        var advertised = EventSchemas.fromProbe(Map.of("eventSchemas",
                Map.of("catalog.operation", List.of(1, 2), "activity_state", List.of(1))));
        assertTrue(EventSchemas.supports(advertised, "catalog.operation", 2));
        assertTrue(EventSchemas.supports(advertised, "activity_state", 1));
        assertFalse(EventSchemas.supports(advertised, "profile.operation", 1));
        assertFalse(EventSchemas.supports(advertised, "catalog.operation", 3));
        assertTrue(EventSchemas.fromProbe(Map.of("eventSchemas", Map.of())).isEmpty());
    }

    @Test void malformedCapabilityFailsClosedInsteadOfBecomingLegacy() {
        for (Object invalid : List.of("v2", Map.of("catalog.operation", List.of(0)),
                Map.of("catalog.operation", List.of(1.5)), Map.of("catalog.operation", List.of("2")),
                Map.of("catalog.operation", List.of(1, 1)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> EventSchemas.fromProbe(Map.of("eventSchemas", invalid)));
        }
        var nullValue = new HashMap<String,Object>();
        nullValue.put("eventSchemas", null);
        assertThrows(IllegalArgumentException.class, () -> EventSchemas.fromProbe(nullValue));
    }

    @Test void composedPolicyReportsOnlyTheOwnersActualReaderVersions() {
        EventPolicy old = policy("old.operation", null);
        EventPolicy modern = policy("new.operation", Set.of(1, 2));
        var result = EventPolicy.compose(List.of(old, modern)).supportedSchemaVersions();
        assertEquals(Map.of("old.operation", Set.of(1), "new.operation", Set.of(1,2)), result);
        assertThrows(UnsupportedOperationException.class, () -> result.put("extra", Set.of(1)));
        assertThrows(UnsupportedOperationException.class, () -> result.get("new.operation").add(3));
        EventPolicy liar = new EventPolicy() {
            public Set<String> aggregateTypes() { return Set.of("owned"); }
            public void validate(SyncEvent event, NodeIdentity origin) { }
            public Map<String,Set<Integer>> supportedSchemaVersions() { return Map.of("not.owned", Set.of(2)); }
        };
        assertThrows(IllegalArgumentException.class, () -> EventPolicy.compose(List.of(liar)).supportedSchemaVersions());
    }

    private EventPolicy policy(String type, Set<Integer> versions) {
        return new EventPolicy() {
            public Set<String> aggregateTypes() { return Set.of(type); }
            public void validate(SyncEvent event, NodeIdentity origin) { }
            public Map<String,Set<Integer>> supportedSchemaVersions() {
                return versions == null ? EventPolicy.super.supportedSchemaVersions() : Map.of(type,versions);
            }
        };
    }
}
