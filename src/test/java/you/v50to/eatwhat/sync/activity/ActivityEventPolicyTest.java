package you.v50to.eatwhat.sync.activity;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.EventPolicy;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import you.v50to.eatwhat.sync.transport.SyntheticEventPolicy;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ActivityEventPolicyTest {
    private static final ActivityEventPolicy POLICY = new ActivityEventPolicy();
    private static final UUID INSTANCE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final NodeIdentity ORIGIN = new NodeIdentity("campus-test", INSTANCE);
    private static final UUID GID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AUTHOR = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final long NUMERIC = 1_000_000_003L;

    private static String createdPayload() {
        return ActivityEvents.createdPayload(ActivityEvents.DINNERS, new ActivityEvents.Created(NUMERIC, GID, "campus-test",
                AUTHOR, null, "d", null, null, List.of("activity/d.png"), 1_700_000_000_000L));
    }

    private static SyncEvent created(String table, VersionVector base, UUID actor, List<SyncEvent.Dependency> deps, String payload) {
        return new SyncEvent(UUID.randomUUID(), "campus-test", INSTANCE, table, GID, NUMERIC, ActivityEvents.CREATED, 1,
                base, base.increment("campus-test"), deps, actor, "activity.created:" + GID, UUID.randomUUID(), payload);
    }

    private static SyncEvent validCreated() {
        return created(ActivityEvents.DINNERS, VersionVector.empty(), AUTHOR, List.of(new SyncEvent.Dependency("users", AUTHOR)), createdPayload());
    }

    private static SyncEvent deleted(VersionVector base) {
        return new SyncEvent(UUID.randomUUID(), "campus-test", INSTANCE, ActivityEvents.DINNERS, GID, NUMERIC, ActivityEvents.DELETED, 1,
                base, base.increment("campus-test"), List.of(new SyncEvent.Dependency("users", AUTHOR)), AUTHOR,
                "activity.deleted:" + GID, UUID.randomUUID(), ActivityEvents.deletedPayload(1_700_000_000_500L));
    }

    @Test void wellFormedCreationAndDeletionPass() {
        assertDoesNotThrow(() -> POLICY.validate(validCreated(), ORIGIN));
        assertDoesNotThrow(() -> POLICY.validate(deleted(VersionVector.empty().increment("campus-test")), ORIGIN));
        assertDoesNotThrow(() -> POLICY.validate(deleted(VersionVector.empty().increment("external-test")), ORIGIN),
                "A replica author on another site may delete against the remote creation version");
    }

    @Test void originSchemaActorAndDependencyMismatchesAreRejected() {
        var valid = validCreated();
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(valid, new NodeIdentity("other-site", INSTANCE)));
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(valid, new NodeIdentity("campus-test", UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(null, ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(
                created(ActivityEvents.DINNERS, VersionVector.empty(), null, List.of(), createdPayload()), ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(
                created(ActivityEvents.DINNERS, VersionVector.empty(), AUTHOR, List.of(new SyncEvent.Dependency("users", GID)), createdPayload()), ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(
                created(ActivityEvents.DINNERS, VersionVector.empty(), AUTHOR, List.of(), createdPayload()), ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(
                created(ActivityEvents.DINNERS, VersionVector.empty(), AUTHOR, List.of(
                        new SyncEvent.Dependency("users", AUTHOR), new SyncEvent.Dependency("hub", GID)), createdPayload()), ORIGIN));
    }

    @Test void creationMustBeTheFirstVersionAndAgreeWithItsEnvelope() {
        var deps = List.of(new SyncEvent.Dependency("users", AUTHOR));
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(
                created(ActivityEvents.DINNERS, VersionVector.empty().increment("campus-test"), AUTHOR, deps, createdPayload()), ORIGIN));
        // Envelope says another global identity, numeric identity, origin or author than the payload.
        String otherGid = ActivityEvents.createdPayload(ActivityEvents.DINNERS, new ActivityEvents.Created(NUMERIC, UUID.randomUUID(), "campus-test",
                AUTHOR, null, "d", null, null, List.of("activity/d.png"), 1_700_000_000_000L));
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(created(ActivityEvents.DINNERS, VersionVector.empty(), AUTHOR, deps, otherGid), ORIGIN));
        String otherNumeric = ActivityEvents.createdPayload(ActivityEvents.DINNERS, new ActivityEvents.Created(NUMERIC + 2, GID, "campus-test",
                AUTHOR, null, "d", null, null, List.of("activity/d.png"), 1_700_000_000_000L));
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(created(ActivityEvents.DINNERS, VersionVector.empty(), AUTHOR, deps, otherNumeric), ORIGIN));
        String otherOrigin = ActivityEvents.createdPayload(ActivityEvents.DINNERS, new ActivityEvents.Created(NUMERIC, GID, "external-test",
                AUTHOR, null, "d", null, null, List.of("activity/d.png"), 1_700_000_000_000L));
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(created(ActivityEvents.DINNERS, VersionVector.empty(), AUTHOR, deps, otherOrigin), ORIGIN));
        String otherAuthor = ActivityEvents.createdPayload(ActivityEvents.DINNERS, new ActivityEvents.Created(NUMERIC, GID, "campus-test",
                UUID.randomUUID(), null, "d", null, null, List.of("activity/d.png"), 1_700_000_000_000L));
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(created(ActivityEvents.DINNERS, VersionVector.empty(), AUTHOR, deps, otherAuthor), ORIGIN));
    }

    @Test void aDeletionCannotBeTheFirstVersionAndOtherEventFamiliesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(deleted(VersionVector.empty()), ORIGIN));
        var update = new SyncEvent(UUID.randomUUID(), "campus-test", INSTANCE, ActivityEvents.DINNERS, GID, NUMERIC, "activity.updated", 1,
                VersionVector.empty(), VersionVector.empty().increment("campus-test"), List.of(new SyncEvent.Dependency("users", AUTHOR)), AUTHOR,
                "activity.updated:" + GID, UUID.randomUUID(), "{}");
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(update, ORIGIN));
        var wrongSchema = new SyncEvent(UUID.randomUUID(), "campus-test", INSTANCE, ActivityEvents.DINNERS, GID, NUMERIC, ActivityEvents.CREATED, 2,
                VersionVector.empty(), VersionVector.empty().increment("campus-test"), List.of(new SyncEvent.Dependency("users", AUTHOR)), AUTHOR,
                "activity.created:" + GID, UUID.randomUUID(), createdPayload());
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(wrongSchema, ORIGIN));
        var user = new SyncEvent(UUID.randomUUID(), "campus-test", INSTANCE, "users", GID, NUMERIC, ActivityEvents.CREATED, 1,
                VersionVector.empty(), VersionVector.empty().increment("campus-test"), List.of(new SyncEvent.Dependency("users", AUTHOR)), AUTHOR,
                "x:" + GID, UUID.randomUUID(), createdPayload());
        assertThrows(IllegalArgumentException.class, () -> POLICY.validate(user, ORIGIN));
    }

    @Test void composedPolicyRoutesByAggregateTypeAndNeverCrossAccepts() {
        var composed = EventPolicy.compose(List.of(POLICY));
        assertEquals(ActivityEvents.TABLES, composed.aggregateTypes());
        assertDoesNotThrow(() -> composed.validate(validCreated(), ORIGIN));
        var synthetic = new SyncEvent(UUID.randomUUID(), "campus-test", INSTANCE, SyntheticEventPolicy.AGGREGATE_TYPE, GID, NUMERIC,
                SyntheticEventPolicy.EVENT_TYPE, 1, VersionVector.empty(), VersionVector.empty().increment("campus-test"), List.of(), null,
                "c:" + GID, UUID.randomUUID(), "{\"delta\":1}");
        assertThrows(IllegalArgumentException.class, () -> composed.validate(synthetic, ORIGIN), "Synthetic is not carried in domain mode");
        assertDoesNotThrow(() -> EventPolicy.SYNTHETIC_ONLY.validate(synthetic, ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> EventPolicy.SYNTHETIC_ONLY.validate(validCreated(), ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> EventPolicy.compose(List.of(POLICY, new ActivityEventPolicy())),
                "Two policies may not own the same aggregate type");
        assertTrue(EventPolicy.compose(List.of()).aggregateTypes().isEmpty());
    }
}
