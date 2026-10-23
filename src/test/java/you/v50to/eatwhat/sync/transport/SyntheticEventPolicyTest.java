package you.v50to.eatwhat.sync.transport;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticEventPolicyTest {
    private final NodeIdentity origin = new NodeIdentity("campus-test", UUID.randomUUID());

    @Test
    void boundedSyntheticShapeAndFutureVersionsCanBeDurablyQuarantined() {
        for (int version : List.of(1, 2, Integer.MAX_VALUE)) {
            assertDoesNotThrow(() -> SyntheticEventPolicy.validate(event("synthetic.counter", "counter.incremented", version,
                    "{\"delta\":1000}", null, List.of()), origin));
        }
    }

    @Test
    void payloadCannotCarryBusinessFieldsOrUnboundedEffects() {
        for (String payload : List.of("{}", "[]", "{\"delta\":0}", "{\"delta\":1001}", "{\"delta\":-1}",
                "{\"delta\":1.5}", "{\"delta\":\"1\"}", "{\"delta\":true}", "{\"delta\":null}",
                "{\"delta\":1,\"email\":\"synthetic@example.invalid\"}", "{\"delta\":1,\"delta\":2}")) {
            assertThrows(IllegalArgumentException.class, () -> SyntheticEventPolicy.validate(event("synthetic.counter",
                    "counter.incremented", 1, payload, null, List.of()), origin));
        }
    }

    @Test
    void originIncarnationActorDependenciesAndBusinessTypesFailClosed() {
        var valid = event("synthetic.counter", "counter.incremented", 1, "{\"delta\":1}", null, List.of());
        assertThrows(IllegalArgumentException.class, () -> SyntheticEventPolicy.validate(valid, new NodeIdentity(origin.siteId(), UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class, () -> SyntheticEventPolicy.validate(valid, new NodeIdentity("other", origin.instanceId())));
        for (SyncEvent invalid : List.of(
                event("users", "counter.incremented", 1, "{\"delta\":1}", null, List.of()),
                event("synthetic.counter", "user.created", 1, "{\"delta\":1}", null, List.of()),
                event("synthetic.counter", "counter.incremented", 1, "{\"delta\":1}", UUID.randomUUID(), List.of()),
                event("synthetic.counter", "counter.incremented", 1, "{\"delta\":1}", null,
                        List.of(new SyncEvent.Dependency("users", UUID.randomUUID()))))) {
            assertThrows(IllegalArgumentException.class, () -> SyntheticEventPolicy.validate(invalid, origin));
        }
    }

    private SyncEvent event(String aggregate, String eventType, int version, String payload, UUID actor, List<SyncEvent.Dependency> dependencies) {
        return new SyncEvent(UUID.randomUUID(), origin.siteId(), origin.instanceId(), aggregate, UUID.randomUUID(), 1,
                eventType, version, VersionVector.empty(), VersionVector.empty().increment(origin.siteId()), dependencies,
                actor, "synthetic-command", UUID.randomUUID(), payload);
    }
}
