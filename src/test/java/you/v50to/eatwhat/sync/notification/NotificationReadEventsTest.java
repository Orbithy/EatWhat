package you.v50to.eatwhat.sync.notification;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationReadEventsTest {
    @Test
    void regrantUsesANewDotStreamForCurrentActorAndRecipientEpochs() {
        String prior = NotificationReadEvents.dotSite("campus", 4, 7);
        String afterActorRegrant = NotificationReadEvents.dotSite("campus", 5, 7);
        String afterRecipientRegrant = NotificationReadEvents.dotSite("campus", 4, 8);

        assertNotEquals(prior, afterActorRegrant);
        assertNotEquals(prior, afterRecipientRegrant);
        assertNotEquals(afterActorRegrant, afterRecipientRegrant);

        var operation = operation(5, 8, afterActorRegrant, 1, Map.of());
        assertTrue(NotificationReadEvents.parse(NotificationReadEvents.payload(operation)).context().isEmpty(),
                "A new consent-epoch stream must not depend on a withheld prior-epoch dot");
    }

    @Test
    void payloadMustHaveAContextImmediatelyBeforeItsDot() {
        String stream = NotificationReadEvents.dotSite("campus", 2, 3);
        var valid = operation(2, 3, stream, 2, Map.of(stream, 1L));
        assertEquals(valid, NotificationReadEvents.parse(NotificationReadEvents.payload(valid)));

        var skippedCounter = operation(2, 3, stream, 3, Map.of(stream, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> NotificationReadEvents.parse(NotificationReadEvents.payload(skippedCounter)));
    }

    @Test
    void transportAggregateMustBeEmptyBaseAndMatchTheOriginIdentity() {
        String site = "campus";
        UUID instance = UUID.randomUUID();
        String stream = NotificationReadEvents.dotSite(site, 2, 3);
        var operation = operation(2, 3, stream, 1, Map.of());
        UUID eventId = UUID.randomUUID();
        var event = new SyncEvent(eventId, site, instance, NotificationReadEvents.AGGREGATE, eventId,
                operation.operationNumericId(), NotificationReadEvents.EVENT, 1,
                VersionVector.empty(), VersionVector.empty().increment(site),
                NotificationReadEvents.dependencies(operation), operation.recipientGid(),
                "notification-read", eventId, NotificationReadEvents.payload(operation));

        new NotificationReadEvents(null).validate(event, new NodeIdentity(site, instance));
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationReadEvents(null).validate(event, new NodeIdentity("external", instance)));
    }

    private static NotificationReadEvents.Operation operation(long actorEpoch, long recipientEpoch,
                                                               String dotSite, long dotCounter,
                                                               Map<String, Long> context) {
        return new NotificationReadEvents.Operation(2_000_000_001L, UUID.randomUUID(), 12_345L,
                "campus", UUID.randomUUID(), recipientEpoch, UUID.randomUUID(), actorEpoch,
                dotSite, dotCounter, context);
    }
}
