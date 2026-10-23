package you.v50to.eatwhat.sync.catalog;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogAuthorityPolicyTest {
    private static final String CAMPUS = "campus-test";
    private static final String EXTERNAL = "external-test";
    private static final Set<String> SITES = Set.of(CAMPUS, EXTERNAL);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void externalCannotClaimCampusAuthorityForRestaurantOrFoodManagement() {
        UUID restaurantAuthor = UUID.randomUUID();
        CatalogEvents.Action restaurantUpdate = action(CatalogKind.RESTAURANT, CatalogOperation.UPDATE,
                CAMPUS, EXTERNAL, restaurantAuthor, null, null);
        CatalogEvents.Action restaurantDelete = action(CatalogKind.RESTAURANT, CatalogOperation.DELETE,
                CAMPUS, EXTERNAL, restaurantAuthor, null, null);
        UUID foodAuthor = UUID.randomUUID();
        CatalogEvents.Action foodUpdate = action(CatalogKind.FOOD, CatalogOperation.UPDATE,
                CAMPUS, EXTERNAL, foodAuthor, UUID.randomUUID(), null);
        CatalogEvents.Action foodDelete = action(CatalogKind.FOOD, CatalogOperation.DELETE,
                CAMPUS, EXTERNAL, foodAuthor, UUID.randomUUID(), null);

        for (CatalogEvents.Action forged : List.of(restaurantUpdate, restaurantDelete, foodUpdate, foodDelete)) {
            SyncEvent event = actionEvent(forged);
            assertDoesNotThrow(() -> CatalogEvents.validateEvent(event, EXTERNAL, SITES));
            assertThrows(IllegalArgumentException.class,
                    () -> CatalogEvents.validateAuthoritySite(event, CAMPUS));
        }
    }

    @Test
    void authorityMustBeCampusEvenForExternalCreateAndCustomTagOperations() {
        UUID author = UUID.randomUUID();
        CatalogEvents.Action restaurantCreate = action(CatalogKind.RESTAURANT, CatalogOperation.CREATE,
                EXTERNAL, EXTERNAL, author, null, null);
        CatalogEvents.Action foodCreate = action(CatalogKind.FOOD, CatalogOperation.CREATE,
                EXTERNAL, EXTERNAL, author, UUID.randomUUID(), null);
        CatalogEvents.Action customCreate = action(CatalogKind.CUSTOM_TAG, CatalogOperation.CREATE,
                EXTERNAL, EXTERNAL, author, null, null);
        CatalogEvents.Action customUpdate = action(CatalogKind.CUSTOM_TAG, CatalogOperation.UPDATE,
                EXTERNAL, EXTERNAL, author, null, null);
        CatalogEvents.Action customDelete = action(CatalogKind.CUSTOM_TAG, CatalogOperation.DELETE,
                EXTERNAL, EXTERNAL, author, null, null);

        for (CatalogEvents.Action forged : List.of(restaurantCreate, foodCreate, customCreate, customUpdate, customDelete)) {
            SyncEvent event = actionEvent(forged);
            assertThrows(IllegalArgumentException.class,
                    () -> CatalogEvents.validateAuthoritySite(event, CAMPUS));
        }
    }

    @Test
    void legalExternalRestaurantFoodAndCustomTagWritesAreAllowed() {
        UUID author = UUID.randomUUID();
        CatalogEvents.Action restaurantCreate = action(CatalogKind.RESTAURANT, CatalogOperation.CREATE,
                CAMPUS, EXTERNAL, author, null, null);
        CatalogEvents.Action foodCreate = action(CatalogKind.FOOD, CatalogOperation.CREATE,
                CAMPUS, EXTERNAL, author, UUID.randomUUID(), null);
        CatalogEvents.Action customCreate = action(CatalogKind.CUSTOM_TAG, CatalogOperation.CREATE,
                CAMPUS, EXTERNAL, author, null, null);
        CatalogEvents.Action customRename = action(CatalogKind.CUSTOM_TAG, CatalogOperation.UPDATE,
                CAMPUS, EXTERNAL, author, null, null);
        CatalogEvents.Action customDelete = action(CatalogKind.CUSTOM_TAG, CatalogOperation.DELETE,
                CAMPUS, EXTERNAL, author, null, null);

        for (CatalogEvents.Action allowed : List.of(restaurantCreate, foodCreate, customCreate, customRename, customDelete)) {
            SyncEvent event = actionEvent(allowed);
            assertDoesNotThrow(() -> CatalogEvents.validateEvent(event, EXTERNAL, SITES));
            assertDoesNotThrow(() -> CatalogEvents.validateAuthoritySite(event, CAMPUS));
        }
    }

    @Test
    void externalTransportMayForwardCampusHeadButCannotForgeItsSourceOrResolutionProof() {
        UUID author = UUID.randomUUID();
        CatalogEvents.Action campusCreate = action(CatalogKind.RESTAURANT, CatalogOperation.CREATE,
                CAMPUS, CAMPUS, author, null, null);
        CatalogEvents.Head head = head(campusCreate, CAMPUS);
        CatalogEvents.StateBridge bridge = new CatalogEvents.StateBridge(CatalogKind.RESTAURANT,
                campusCreate.objectGid(), campusCreate.numericId(), CAMPUS, CAMPUS, null,
                campusCreate.consentEpochs(), List.of(head));
        SyncEvent forwarded = bridgeEvent(EXTERNAL, bridge);
        assertDoesNotThrow(() -> CatalogEvents.validateEvent(forwarded, EXTERNAL, SITES));
        assertDoesNotThrow(() -> CatalogEvents.validateAuthoritySite(forwarded, CAMPUS));

        CatalogEvents.Head mismatchedSource = head(campusCreate, EXTERNAL);
        CatalogEvents.StateBridge forgedSourceBridge = new CatalogEvents.StateBridge(CatalogKind.RESTAURANT,
                campusCreate.objectGid(), campusCreate.numericId(), CAMPUS, CAMPUS, null,
                campusCreate.consentEpochs(), List.of(mismatchedSource));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.validateAuthoritySite(bridgeEvent(EXTERNAL, forgedSourceBridge), CAMPUS));

        CatalogEvents.ResolutionProof proof = new CatalogEvents.ResolutionProof(UUID.randomUUID(),
                List.of(UUID.randomUUID(), UUID.randomUUID()), CatalogDecision.KEEP, UUID.randomUUID());
        CatalogEvents.Action externalProof = action(CatalogKind.RESTAURANT, CatalogOperation.CREATE,
                CAMPUS, EXTERNAL, author, null, proof);
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.validateAuthoritySite(actionEvent(externalProof), CAMPUS));

        CatalogEvents.Action campusProof = action(CatalogKind.RESTAURANT, CatalogOperation.CREATE,
                CAMPUS, CAMPUS, author, null, proof);
        CatalogEvents.Head proofHead = head(campusProof, CAMPUS);
        CatalogEvents.StateBridge proofBridge = new CatalogEvents.StateBridge(CatalogKind.RESTAURANT,
                campusProof.objectGid(), campusProof.numericId(), CAMPUS, CAMPUS, null,
                campusProof.consentEpochs(), List.of(proofHead));
        assertDoesNotThrow(() -> CatalogEvents.validateAuthoritySite(bridgeEvent(EXTERNAL, proofBridge), CAMPUS));

        CatalogEvents.Head forgedProofSource = head(campusProof, EXTERNAL);
        CatalogEvents.StateBridge forgedProofBridge = new CatalogEvents.StateBridge(CatalogKind.RESTAURANT,
                campusProof.objectGid(), campusProof.numericId(), CAMPUS, CAMPUS, null,
                campusProof.consentEpochs(), List.of(forgedProofSource));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.validateAuthoritySite(bridgeEvent(EXTERNAL, forgedProofBridge), CAMPUS));
    }

    @Test
    void nullCampusIdentityFailsClosedAndPolicyEntrypointsApplyAuthorityChecks() {
        CatalogEvents.Action externalCreate = action(CatalogKind.RESTAURANT, CatalogOperation.CREATE,
                CAMPUS, EXTERNAL, UUID.randomUUID(), null, null);
        SyncEvent event = actionEvent(externalCreate);
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.validateAuthoritySite(event, null));

        CatalogEventPolicy policy = new CatalogEventPolicy(null, SITES, CAMPUS);
        assertDoesNotThrow(() -> policy.validate(event, new NodeIdentity(EXTERNAL, event.originInstanceId())));
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogEventPolicy(null, SITES, null).validate(
                        event, new NodeIdentity(EXTERNAL, event.originInstanceId())));

        CatalogEvents.Action campusSystemTag = action(CatalogKind.SYSTEM_TAG, CatalogOperation.CREATE,
                CAMPUS, CAMPUS, null, null, null);
        SyncEvent systemTagEvent = actionEvent(campusSystemTag);
        assertDoesNotThrow(() -> policy.validate(systemTagEvent,
                new NodeIdentity(CAMPUS, systemTagEvent.originInstanceId())));
        assertEquals(CatalogEventPolicy.OutboundDecision.ALLOW, policy.outbound(systemTagEvent));

        CatalogEvents.Action wrongAuthority = action(CatalogKind.RESTAURANT, CatalogOperation.CREATE,
                EXTERNAL, EXTERNAL, UUID.randomUUID(), null, null);
        SyncEvent forged = actionEvent(wrongAuthority);
        assertEquals(CatalogEventPolicy.OutboundDecision.POLICY_BLOCKED, policy.outbound(forged));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validate(forged, new NodeIdentity(EXTERNAL, forged.originInstanceId())));
    }

    @Test void unavailableMemberAuthorityProofIsRetryableInsteadOfPermanentlyBlockingPublication(){
        SyncEvent event=actionEvent(action(CatalogKind.SYSTEM_TAG,CatalogOperation.CREATE,CAMPUS,CAMPUS,null,null,null));
        var unavailable=new CatalogEventPolicy(null,()->{throw new you.v50to.eatwhat.cluster.ClusterFailure(503,"MEMBERSHIP_PROOF_UNAVAILABLE");},SITES);
        assertEquals("MEMBERSHIP_PROOF_UNAVAILABLE",assertThrows(you.v50to.eatwhat.cluster.ClusterFailure.class,()->unavailable.outbound(event)).getMessage());
        var authority=new java.util.concurrent.atomic.AtomicReference<>(CAMPUS);var live=new CatalogEventPolicy(null,authority::get,SITES);
        assertEquals(CatalogEventPolicy.OutboundDecision.ALLOW,live.outbound(event));authority.set(EXTERNAL);
        assertEquals(CatalogEventPolicy.OutboundDecision.POLICY_BLOCKED,live.outbound(event));
    }

    private static CatalogEvents.Action action(CatalogKind kind, CatalogOperation operation,
                                                String authority, String dot, UUID author, UUID parent,
                                                CatalogEvents.ResolutionProof proof) {
        UUID object = UUID.randomUUID();
        long revision = operation == CatalogOperation.CREATE ? 1 : 2;
        long expectedRevision = revision - 1;
        Map<String, Object> payload;
        if (operation == CatalogOperation.DELETE) {
            payload = Map.of();
        } else if (kind == CatalogKind.RESTAURANT) {
            payload = restaurantPayload();
        } else if (kind == CatalogKind.FOOD) {
            UUID restaurant = parent == null ? UUID.randomUUID() : parent;
            parent = restaurant;
            payload = Map.of("restaurantGid", restaurant.toString(), "name", "Noodles", "description", "menu",
                    "price", 12.5, "category", "staple", "pictureKeys", List.of());
        } else if (kind == CatalogKind.CUSTOM_TAG) {
            payload = Map.of("name", "Late Night", "normalizedName", "late night", "tagType", "custom",
                    "ownerGid", author.toString());
        } else if (kind == CatalogKind.SYSTEM_TAG) {
            payload = Map.of("name", "Vegan", "normalizedName", "vegan", "tagType", "system");
        } else {
            throw new IllegalArgumentException("Test helper only builds personal catalog kinds");
        }
        Map<String, Object> canonical = operation == CatalogOperation.DELETE
                ? payload : CatalogPayload.validate(kind, payload);
        List<CatalogEvents.ConsentEpoch> epochs = (kind == CatalogKind.RESTAURANT || kind == CatalogKind.FOOD
                || kind == CatalogKind.CUSTOM_TAG)
                ? List.of(new CatalogEvents.ConsentEpoch(author, 1)) : List.of();
        UUID operationId = UUID.randomUUID();
        return new CatalogEvents.Action(kind, object, revision, expectedRevision, operation, authority,
                author, parent, 8201, EXTERNAL.equals(dot) ? EXTERNAL : CAMPUS,
                canonical, hash(canonical), operationId, dot, 1, Map.of(), epochs, null, proof);
    }

    private static CatalogEvents.Head head(CatalogEvents.Action action, String sourceSite) {
        return new CatalogEvents.Head(action.operationId(), sourceSite, action.authoritySite(), action.authorGid(),
                action.parentGid(), action.numericId(), action.identityOrigin(), action.revision(),
                action.expectedRevision(), action.operation(), action.dotSite(), action.dotCounter(), action.context(),
                action.consentEpochs(), action.operation() == CatalogOperation.DELETE,
                action.canonicalPayload(), action.contentHash(), action.resolutionProof());
    }

    private static SyncEvent actionEvent(CatalogEvents.Action action) {
        return event(action.operationId(), action.dotSite(), CatalogEvents.ACTION, action.authorGid(),
                CatalogEvents.payload(action));
    }

    private static SyncEvent bridgeEvent(String origin, CatalogEvents.StateBridge bridge) {
        return event(UUID.randomUUID(), origin, CatalogEvents.BRIDGE, null, CatalogEvents.bridgePayload(bridge));
    }

    private static SyncEvent event(UUID id, String origin, String type, UUID actor, String payload) {
        return new SyncEvent(id, origin, UUID.randomUUID(), CatalogEvents.AGGREGATE, id, 1, type,
                CatalogEvents.SCHEMA_VERSION, VersionVector.empty(), VersionVector.empty().increment(origin),
                List.of(), actor, "catalog-authority-policy-test", UUID.randomUUID(), payload);
    }

    private static Map<String, Object> restaurantPayload() {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("name", "Campus Kitchen");
        payload.put("address", null);
        payload.put("cityId", null);
        payload.put("poi", "authority-test-poi");
        payload.put("locationWgs", Map.of("lng", 117.0, "lat", 36.0));
        payload.put("locationGcj", Map.of("lng", 117.01, "lat", 36.01));
        payload.put("hubGid", null);
        payload.put("pictureKeys", List.of());
        return payload;
    }

    private static String hash(Map<String, Object> payload) {
        return EventCodec.sha256(EventCodec.canonicalJson(JSON.writeValueAsString(payload)));
    }
}
