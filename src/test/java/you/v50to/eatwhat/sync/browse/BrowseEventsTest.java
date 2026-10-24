package you.v50to.eatwhat.sync.browse;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowseEventsTest {
    private final UUID instance = UUID.randomUUID();
    private final BrowseEvents policy = new BrowseEvents(null);
    private BrowseEvents.Observation observation() { return new BrowseEvents.Observation(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "food", UUID.randomUUID(), 1, 1, 1_750_000_000_000_000L); }
    private SyncEvent event(BrowseEvents.Observation op, String payload) {
        UUID eventId = UUID.randomUUID();
        return new SyncEvent(eventId, "campus", instance, BrowseEvents.AGGREGATE, eventId, 1, BrowseEvents.EVENT, 1,
                VersionVector.empty(), VersionVector.empty().increment("campus"), op.dependencies(), op.actor(),
                "browse:" + eventId, eventId, payload);
    }
    @Test void immutableObservationRoundTripsWithPrivateRendering() {
        var op = observation(); var event = event(op, BrowseEvents.payload(op));
        policy.validate(event, new NodeIdentity("campus", instance));
        assertEquals(op, BrowseEvents.parse(event.payloadJson()));
        assertEquals("Observation[redacted]", op.toString());
        assertEquals(EventPolicy.OutboundDecision.WAIT, policy.outbound(event));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(event, new NodeIdentity("external", instance)));
    }
    @Test void rejectsExtraFieldsMalformedIdentitiesAndCoercedNumbers() {
        String payload = BrowseEvents.payload(observation());
        for (String invalid : List.of(payload.replaceFirst("\\{", "{\"url\":\"private-value\","),
                payload.replace("\"food\"", "\"dinner\""), payload.replace("\"actorEpoch\":1", "\"actorEpoch\":0"),
                payload.replace("\"ownerEpoch\":1", "\"ownerEpoch\":\"1\""),
                payload.replace("1750000000000000", "1.5"))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> BrowseEvents.parse(invalid));
            assertEquals("INVALID_BROWSE_EVENT", failure.getMessage()); assertNull(failure.getCause());
        }
    }
    @Test void deletionFrontierRequiresV2AndBindsTheAuthenticatedActor() {
        UUID actor=UUID.randomUUID(),id=UUID.randomUUID();
        var clear=new BrowseEvents.Clear(actor,1,"food",Map.of("campus",1L));
        var event=new SyncEvent(id,"campus",instance,BrowseEvents.AGGREGATE,id,1,BrowseEvents.CLEAR,2,
                VersionVector.empty(),VersionVector.empty().increment("campus"),
                List.of(new SyncEvent.Dependency("users",actor)),actor,"clear:"+id,id,BrowseEvents.payload(clear));
        policy.validate(event,new NodeIdentity("campus",instance));
        assertEquals(clear,BrowseEvents.parseClear(event.payloadJson()));
        assertEquals(Set.of(1,2),policy.supportedSchemaVersions().get(BrowseEvents.AGGREGATE));
        for(String bad:List.of(event.payloadJson().replace("\"food\"","\"user\""),
                event.payloadJson().replace("\"campus\":1","\"campus\":1.5"),
                event.payloadJson().replace("\"actorEpoch\":1","\"actorEpoch\":0"))) {
            assertThrows(IllegalArgumentException.class,()->BrowseEvents.parseClear(bad));
        }
        var old=observation();
        var fresh=new BrowseEvents.Observation(old.visitId(),old.actor(),old.owner(),old.targetType(),old.target(),1,1,old.viewedAtMicros(),Map.of("campus",1L));
        assertThrows(IllegalArgumentException.class,()->policy.validate(event(fresh,BrowseEvents.payload(fresh)),new NodeIdentity("campus",instance)));
    }

    @Test void actorAndDependenciesMustMatchThePayload() {
        var op = observation(); var other = observation();
        var bad = event(op, BrowseEvents.payload(other));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(bad, new NodeIdentity("campus", instance)));
    }
}
