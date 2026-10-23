package you.v50to.eatwhat.sync.relation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RelationCausalEventsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CAMPUS = "causal-campus";
    private static final UUID ACTOR = UUID.fromString("4f8e3455-2ce5-483d-8827-96d4cdcaec01");
    private static final UUID OWNER = UUID.fromString("03adfb13-03bd-4f33-9cbd-69d3110ab79a");
    private static final UUID ROW_GID = UUID.fromString("c0145634-5b9d-4571-80e8-b886663a1aae");
    private static final UUID EVENT_ID = UUID.fromString("43c3795e-e70c-4f5b-b575-d60dca0a9c48");
    private static final long ROW_ID = 1_000_000_001L;
    @Test void bridgePreservesThreeNativeFrontiersAndStillRejectsAnUnknownOrigin(){
        UUID natural=RelationStore.naturalId(RelationKind.follow,ACTOR,OWNER,OWNER,null);var seen=new java.util.LinkedHashMap<String,Long>();var dots=new java.util.ArrayList<RelationCausalEvents.Dot>();
        for(String site:List.of(CAMPUS,"causal-external","causal-third")){String stream=RelationCausalEvents.nativeStream(site);seen.put(stream,1L);dots.add(new RelationCausalEvents.Dot(stream,1,UUID.randomUUID(),Map.of()));}
        var bridge=new RelationCausalEvents.Bridge(natural,RelationKind.follow,ACTOR,OWNER,OWNER,null,1,1,ROW_ID,ROW_GID,CAMPUS,seen,dots,List.of(),null);
        assertEquals(3,RelationCausalEvents.parseBridge(RelationCausalEvents.payload(bridge)).addWitnesses().size());
        UUID id=UUID.randomUUID();var event=new SyncEvent(id,CAMPUS,UUID.randomUUID(),RelationEvents.AGGREGATE,id,1,RelationCausalEvents.BRIDGE,2,VersionVector.empty(),VersionVector.empty().increment(CAMPUS),RelationCausalEvents.actionDependencies(RelationKind.follow,ACTOR,OWNER,OWNER,null),ACTOR,"relation:"+id,id,RelationCausalEvents.payload(bridge));
        assertDoesNotThrow(()->RelationCausalEvents.validateEvent(event,java.util.Set.of(CAMPUS,"causal-external","causal-third")));
        assertThrows(IllegalArgumentException.class,()->RelationCausalEvents.validateEvent(event,java.util.Set.of(CAMPUS,"causal-external")));
    }

    private static RelationCausalEvents.Action action() {
        String stream = RelationCausalEvents.nativeStream(CAMPUS);
        UUID natural = RelationStore.naturalId(RelationKind.follow, ACTOR, OWNER, OWNER, null);
        var dot = new RelationCausalEvents.Dot(stream, 1, EVENT_ID, Map.of());
        var state = new RelationCausalEvents.Bridge(natural, RelationKind.follow, ACTOR, OWNER, OWNER, null,
                1, 1, ROW_ID, ROW_GID, CAMPUS, Map.of(stream, 1L), List.of(dot), List.of(), null);
        var evidence = new RelationEvents.Operation(RelationKind.follow, ACTOR, OWNER, OWNER, null,
                1, 1, ROW_ID, ROW_GID, CAMPUS, Map.of(), CAMPUS, 1, true, null);
        return new RelationCausalEvents.Action(natural, RelationKind.follow, ACTOR, OWNER, OWNER, null,
                1, 1, ROW_ID, ROW_GID, CAMPUS, stream, 1, Map.of(), true, evidence, state);
    }

    private static SyncEvent actionEvent(RelationCausalEvents.Action action) {
        return new SyncEvent(EVENT_ID, CAMPUS, UUID.fromString("e0f0320e-c78b-4c79-a0ad-c2beac32979c"),
                RelationEvents.AGGREGATE, EVENT_ID, 1, RelationCausalEvents.ACTION,
                RelationCausalEvents.SCHEMA_VERSION, VersionVector.empty(),
                VersionVector.empty().increment(CAMPUS),
                RelationCausalEvents.actionDependencies(action.kind(), action.actor(), action.target(),
                        action.owner(), action.tag()), action.actor(), "relation:" + EVENT_ID, EVENT_ID,
                RelationCausalEvents.payload(action));
    }

    @Test
    void actionRoundTripsWithStableNativeStreamAndSelfContainedLegacyEvidence() {
        var action = action();
        var parsed = RelationCausalEvents.parseAction(RelationCausalEvents.payload(action));

        assertEquals(action, parsed);
        assertEquals(RelationCausalEvents.nativeStream(CAMPUS), parsed.stream());
        var nextEpoch = actionAtEpoch(action, 24);
        assertEquals(action.stream(), nextEpoch.stream(),
                "A consent-epoch change must not rename a site's logical action stream");
        assertEquals(action.stream(), RelationCausalEvents.parseAction(
                RelationCausalEvents.payload(nextEpoch)).stream());
        assertEquals(RelationEvents.Operation.class, parsed.v1Evidence().getClass());
    }

    @Test
    void actionEnvelopeBindsOriginTypeVersionActorAndDependencies() {
        var event = actionEvent(action());
        RelationCausalEvents.validateEvent(event);
        new RelationEvents().validate(event, new NodeIdentity(event.originSite(), event.originInstanceId()));

        assertThrows(IllegalArgumentException.class, () -> new RelationEvents().validate(event,
                new NodeIdentity("other-site", event.originInstanceId())));
        assertThrows(IllegalArgumentException.class, () -> RelationCausalEvents.validateEvent(
                new SyncEvent(event.eventId(), event.originSite(), event.originInstanceId(),
                        event.aggregateType(), event.aggregateId(), event.numericId(), event.eventType(),
                        1, event.baseVersion(), event.nextVersion(), event.dependencies(), event.actorId(),
                        event.commandKey(), event.correlationId(), event.payloadJson())));
    }

    @Test
    void nativeActionRequiresConfiguredLocalOrPeerSite() {
        RelationCausalEvents.validateEvent(actionEvent(action()), java.util.Set.of(CAMPUS, "causal-external"));
        RelationCausalEvents.validateEvent(actionEvent(action()), java.util.Set.of(CAMPUS));

        String third = "causal-third";
        String stream = RelationCausalEvents.nativeStream(third);
        UUID eventId = UUID.randomUUID();
        UUID natural = RelationStore.naturalId(RelationKind.follow, ACTOR, OWNER, OWNER, null);
        var dot = new RelationCausalEvents.Dot(stream, 1, eventId, Map.of());
        var state = new RelationCausalEvents.Bridge(natural, RelationKind.follow, ACTOR, OWNER, OWNER, null,
                1, 1, ROW_ID, ROW_GID, third, Map.of(stream, 1L), List.of(dot), List.of(), null);
        var evidence = new RelationEvents.Operation(RelationKind.follow, ACTOR, OWNER, OWNER, null,
                1, 1, ROW_ID, ROW_GID, third, Map.of(), third, 1, true, null);
        var action = new RelationCausalEvents.Action(natural, RelationKind.follow, ACTOR, OWNER, OWNER, null,
                1, 1, ROW_ID, ROW_GID, third, stream, 1, Map.of(), true, evidence, state);
        var thirdSiteEvent = new SyncEvent(eventId, third,
                UUID.fromString("e0f0320e-c78b-4c79-a0ad-c2beac32979c"), RelationEvents.AGGREGATE,
                eventId, 1, RelationCausalEvents.ACTION, RelationCausalEvents.SCHEMA_VERSION,
                VersionVector.empty(), VersionVector.empty().increment(third),
                RelationCausalEvents.actionDependencies(action.kind(), action.actor(), action.target(),
                        action.owner(), action.tag()), action.actor(), "relation:" + eventId, eventId,
                RelationCausalEvents.payload(action));

        assertThrows(IllegalArgumentException.class, () ->
                RelationCausalEvents.validateEvent(thirdSiteEvent, java.util.Set.of(CAMPUS, "causal-external")));
        RelationCausalEvents.validateEvent(thirdSiteEvent, java.util.Set.of(third));
    }

    @Test
    void actionRejectsV1EvidenceForAnotherRelationKind() {
        ObjectNode payload = (ObjectNode) JSON.readTree(RelationCausalEvents.payload(action()));
        ((ObjectNode) payload.get("v1Evidence")).put("kind", RelationKind.food_like.name());

        assertThrows(IllegalArgumentException.class,
                () -> RelationCausalEvents.parseAction(payload.toString()));

        ObjectNode wrongEpoch = (ObjectNode) JSON.readTree(RelationCausalEvents.payload(action()));
        ((ObjectNode) wrongEpoch.get("v1Evidence")).put("actorEpoch", 2);
        assertThrows(IllegalArgumentException.class,
                () -> RelationCausalEvents.parseAction(wrongEpoch.toString()));
    }

    @Test
    void actionEnvelopeRejectsNoticeFromAnotherOriginSite() {
        var original = action();
        ObjectNode payload = (ObjectNode) JSON.readTree(RelationCausalEvents.payload(original));
        ObjectNode evidence = (ObjectNode) payload.get("v1Evidence");
        ObjectNode notice = JSON.createObjectNode();
        notice.put("numericId", 1_000_000_001L);
        notice.put("globalId", "7c2b8a70-c0eb-4bf5-a08c-991e9ec4ea72");
        notice.put("recipientGid", OWNER.toString());
        notice.put("originSite", "causal-external");
        notice.put("kind", "follow");
        notice.put("targetType", "user");
        notice.put("createdAt", 1_760_000_000_000L);
        notice.put("expiresAt", 1_762_592_000_000L);
        evidence.set("notification", notice);

        var event = actionEvent(RelationCausalEvents.parseAction(payload.toString()));
        assertThrows(IllegalArgumentException.class, () -> RelationCausalEvents.validateEvent(event),
                "A notice must retain the same authorized origin as its relation action");
    }

    @Test
    void actionRejectsAnUnprovedCounterInItsEmbeddedStateFrontier() {
        ObjectNode payload = (ObjectNode) JSON.readTree(RelationCausalEvents.payload(action()));
        ObjectNode state = (ObjectNode) payload.get("state");
        ((ObjectNode) state.get("seen")).put(RelationCausalEvents.nativeStream(CAMPUS), 2);

        assertThrows(IllegalArgumentException.class,
                () -> RelationCausalEvents.parseAction(payload.toString()));
    }

    @Test
    void bridgeRejectsAnUnprovedCounterAndConflictingBlockedState() {
        var action = action();
        ObjectNode payload = (ObjectNode) JSON.readTree(RelationCausalEvents.payload(action.state()));
        ((ObjectNode) payload.get("seen")).put(RelationCausalEvents.nativeStream(CAMPUS), 2);
        assertThrows(IllegalArgumentException.class,
                () -> RelationCausalEvents.parseBridge(payload.toString()));

        ObjectNode blockedWithFacts = (ObjectNode) JSON.readTree(RelationCausalEvents.payload(action.state()));
        blockedWithFacts.put("blockedReason", "LEGACY_CAUSALITY_AMBIGUOUS");
        assertThrows(IllegalArgumentException.class,
                () -> RelationCausalEvents.parseBridge(blockedWithFacts.toString()));
    }

    @Test
    void bridgeRejectsSeenCounterWithNoAddOrRemoveProof() {
        var action = action();
        ObjectNode payload = (ObjectNode) JSON.readTree(RelationCausalEvents.payload(action.state()));
        ((ObjectNode) payload.get("seen")).put(RelationCausalEvents.nativeStream("causal-external"), 1);
        ((ObjectNode) payload).putArray("addWitnesses");

        assertThrows(IllegalArgumentException.class,
                () -> RelationCausalEvents.parseBridge(payload.toString()),
                "A bridge cannot claim to have observed a dot without carrying its causal proof");
    }

    @Test
    void higherCounterActionCannotDropThePriorSameStreamContext() {
        var original = action();
        String stream = original.stream();
        UUID eventId = UUID.randomUUID();
        var evidence = new RelationEvents.Operation(original.kind(), original.actor(), original.target(),
                original.owner(), original.tag(), original.actorEpoch(), original.ownerEpoch(), original.rowId(),
                original.rowGid(), original.rowOrigin(), Map.of(), CAMPUS, 2, true, null);
        var state = new RelationCausalEvents.Bridge(original.naturalId(), original.kind(), original.actor(),
                original.target(), original.owner(), original.tag(), original.actorEpoch(), original.ownerEpoch(),
                original.rowId(), original.rowGid(), original.rowOrigin(), Map.of(stream, 2L),
                List.of(new RelationCausalEvents.Dot(stream, 2, eventId, Map.of())), List.of(), null);
        var malformed = new RelationCausalEvents.Action(original.naturalId(), original.kind(), original.actor(),
                original.target(), original.owner(), original.tag(), original.actorEpoch(), original.ownerEpoch(),
                original.rowId(), original.rowGid(), original.rowOrigin(), stream, 2, Map.of(), true, evidence, state);

        assertThrows(IllegalArgumentException.class,
                () -> RelationCausalEvents.parseAction(RelationCausalEvents.payload(malformed)),
                "Counter 2 must carry the preceding counter in its same-stream context");
    }

    @Test
    void baselineProofIsOnlyAnAddAtCounterOneWithEmptyContext() {
        var action = action();
        String baseline = RelationCausalEvents.baselineStream(action.naturalId());
        UUID baselineEvent = UUID.nameUUIDFromBytes(("relation-baseline:" + action.naturalId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var valid = new RelationCausalEvents.Bridge(action.naturalId(), action.kind(), action.actor(),
                action.target(), action.owner(), action.tag(), action.actorEpoch(), action.ownerEpoch(),
                action.rowId(), action.rowGid(), action.rowOrigin(), Map.of(baseline, 1L),
                List.of(new RelationCausalEvents.Dot(baseline, 1, baselineEvent, Map.of())), List.of(), null);
        assertEquals(valid, RelationCausalEvents.parseBridge(RelationCausalEvents.payload(valid)));

        var invalidCounter = new RelationCausalEvents.Bridge(action.naturalId(), action.kind(), action.actor(),
                action.target(), action.owner(), action.tag(), action.actorEpoch(), action.ownerEpoch(),
                action.rowId(), action.rowGid(), action.rowOrigin(), Map.of(baseline, 2L),
                List.of(new RelationCausalEvents.Dot(baseline, 2, UUID.randomUUID(), Map.of(baseline, 1L))),
                List.of(), null);
        assertThrows(IllegalArgumentException.class,
                () -> RelationCausalEvents.parseBridge(RelationCausalEvents.payload(invalidCounter)));

        var invalidRemove = new RelationCausalEvents.Bridge(action.naturalId(), action.kind(), action.actor(),
                action.target(), action.owner(), action.tag(), action.actorEpoch(), action.ownerEpoch(),
                action.rowId(), action.rowGid(), action.rowOrigin(), Map.of(baseline, 1L), List.of(),
                List.of(new RelationCausalEvents.Dot(baseline, 1, baselineEvent, Map.of())), null);
        assertThrows(IllegalArgumentException.class,
                () -> RelationCausalEvents.parseBridge(RelationCausalEvents.payload(invalidRemove)));
    }

    private static RelationCausalEvents.Action actionAtEpoch(RelationCausalEvents.Action original, long epoch) {
        var evidence = original.v1Evidence();
        var updatedEvidence = new RelationEvents.Operation(evidence.kind(), evidence.actor(), evidence.target(),
                evidence.owner(), evidence.tag(), epoch, epoch, evidence.rowId(), evidence.rowGid(),
                evidence.rowOrigin(), evidence.context(), evidence.dotSite(), evidence.dotCounter(),
                evidence.adding(), evidence.notification());
        var updatedState = new RelationCausalEvents.Bridge(original.naturalId(), original.kind(), original.actor(),
                original.target(), original.owner(), original.tag(), epoch, epoch, original.rowId(),
                original.rowGid(), original.rowOrigin(), original.state().seen(),
                original.state().addWitnesses(), original.state().removeFrontier(), null);
        return new RelationCausalEvents.Action(original.naturalId(), original.kind(), original.actor(),
                original.target(), original.owner(), original.tag(), epoch, epoch, original.rowId(),
                original.rowGid(), original.rowOrigin(), original.stream(), original.counter(),
                original.context(), original.adding(), updatedEvidence, updatedState);
    }
}
