package you.v50to.eatwhat.sync.catalog;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CatalogEventsTest {
    private static final String CAMPUS = "campus-test";
    private static final String EXTERNAL = "external-test";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Test void thirdSiteContextIsAcceptedOnlyWhenEveryOriginIsConfigured(){
        UUID object=UUID.randomUUID(),author=UUID.randomUUID(),operation=UUID.randomUUID();var payload=CatalogPayload.validate(CatalogKind.RESTAURANT,restaurantPayload("three-sites","Shared Hall"));
        var action=new CatalogEvents.Action(CatalogKind.RESTAURANT,object,2,1,CatalogOperation.UPDATE,CAMPUS,author,null,4201,CAMPUS,payload,hash(payload),operation,CAMPUS,2,Map.of(CAMPUS,1L,EXTERNAL,1L,"third",1L),List.of(new CatalogEvents.ConsentEpoch(author,2)),null,null);
        var event=event(operation,CatalogEvents.ACTION,CatalogEvents.SCHEMA_VERSION,author,CatalogEvents.payload(action));
        assertDoesNotThrow(()->CatalogEvents.validateEvent(event,CAMPUS,Set.of(CAMPUS,EXTERNAL,"third")));
        assertThrows(IllegalArgumentException.class,()->CatalogEvents.validateEvent(event,CAMPUS,Set.of(CAMPUS,EXTERNAL)));
    }

    @Test
    void actionRoundTripsAsAnIndependentEmptyBaseOperationWithStableEvidence() {
        UUID object = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        Map<String, Object> payload = CatalogPayload.validate(CatalogKind.RESTAURANT,
                restaurantPayload("POI-42", "North Hall"));
        UUID operationId = UUID.randomUUID();
        CatalogEvents.Action action = new CatalogEvents.Action(CatalogKind.RESTAURANT, object, 1, 0,
                CatalogOperation.CREATE, CAMPUS, author, null, 4201, CAMPUS, payload, hash(payload), operationId,
                CAMPUS, 1, Map.of(), List.of(new CatalogEvents.ConsentEpoch(author, 2)), "source-row-42", null);
        String wire = CatalogEvents.payload(action);

        assertEquals(wire, CatalogEvents.payload(CatalogEvents.parseAction(wire)));
        assertEquals(action.contentHash(), CatalogEvents.parseAction(wire).contentHash());

        SyncEvent event = event(operationId, CatalogEvents.ACTION, CatalogEvents.SCHEMA_VERSION, author, wire);
        assertDoesNotThrow(() -> CatalogEvents.validateEvent(event, CAMPUS, Set.of(CAMPUS, EXTERNAL)));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.validateEvent(event, CAMPUS, Set.of(EXTERNAL)));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.validateEvent(event, EXTERNAL, Set.of(CAMPUS, EXTERNAL)));
    }

    @Test
    void bridgeRoundTripsOriginalHeadsWithoutMintingAReplacementAction() {
        UUID object = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        UUID parent = null;
        Map<String, Object> payload = CatalogPayload.validate(CatalogKind.RESTAURANT,
                restaurantPayload("POI-84", "East Hall"));
        CatalogEvents.Head head = new CatalogEvents.Head(revision, CAMPUS, CAMPUS, author, parent,
                4202, CAMPUS, 1, 0, CatalogOperation.CREATE, CAMPUS, 1, Map.of(),
                List.of(new CatalogEvents.ConsentEpoch(author, 3)), false, payload, hash(payload), null);
        CatalogEvents.StateBridge bridge = new CatalogEvents.StateBridge(
                CatalogKind.RESTAURANT, object, 4202, CAMPUS, CAMPUS, parent,
                List.of(new CatalogEvents.ConsentEpoch(author, 3)), List.of(head));
        String wire = CatalogEvents.bridgePayload(bridge);

        assertEquals(wire, CatalogEvents.bridgePayload(CatalogEvents.parseBridge(wire)));
        SyncEvent event = event(UUID.randomUUID(), CatalogEvents.BRIDGE, CatalogEvents.SCHEMA_VERSION, null, wire);
        assertDoesNotThrow(() -> CatalogEvents.validateEvent(event, CAMPUS, Set.of(CAMPUS, EXTERNAL)));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.validateEvent(event(UUID.randomUUID(), CatalogEvents.BRIDGE, 2, null, wire),
                        CAMPUS, Set.of(CAMPUS, EXTERNAL)));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.validateEvent(event(UUID.randomUUID(), CatalogEvents.ACTION,
                                CatalogEvents.SCHEMA_VERSION, author, wire), CAMPUS, Set.of(CAMPUS, EXTERNAL)));
    }

    @Test
    void actionParserRejectsUnknownRootFieldsAndDoesNotAcceptASecondPayloadShape() {
        UUID object = UUID.randomUUID();
        Map<String, Object> restaurant = CatalogPayload.validate(CatalogKind.RESTAURANT,
                restaurantPayload("POI-1", "Hall"));
        CatalogEvents.Action action = new CatalogEvents.Action(CatalogKind.RESTAURANT, object, 1, 0,
                CatalogOperation.CREATE, CAMPUS, UUID.randomUUID(), null, 9001, CAMPUS, restaurant,
                hash(restaurant), UUID.randomUUID(), CAMPUS, 1, Map.of(), List.of(), null, null);
        String valid = CatalogEvents.payload(action);
        String withUnknownRoot = valid.substring(0, valid.length() - 1) + ",\"unsigned\":true}";

        assertThrows(IllegalArgumentException.class, () -> CatalogEvents.parseAction(withUnknownRoot));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.parseAction(valid.replace("\"kind\":\"RESTAURANT\"", "\"kind\":\"RESTAURANT\",\"extra\":1")));
    }

    @Test
    void catalogPayloadsRejectSignedUrlsAndUnknownFieldsButPreserveStableKeys() {
        Map<String, Object> restaurant = restaurantPayload("Exact POI", "Hall");
        assertEquals(EventCodec.canonicalJson(JSON.writeValueAsString(restaurant)),
                EventCodec.canonicalJson(JSON.writeValueAsString(
                        CatalogPayload.validate(CatalogKind.RESTAURANT, restaurant))));

        Map<String, Object> withSignedUrl = new java.util.HashMap<>(restaurant);
        withSignedUrl.put("pictureKeys", List.of("https://cdn.invalid/signed?token=secret"));
        assertThrows(IllegalArgumentException.class, () -> CatalogPayload.validate(CatalogKind.RESTAURANT, withSignedUrl));

        Map<String, Object> withDerivedCounter = new java.util.HashMap<>(restaurant);
        withDerivedCounter.put("likes_count", 17);
        assertThrows(IllegalArgumentException.class,
                () -> CatalogPayload.validate(CatalogKind.RESTAURANT, withDerivedCounter));

        Map<String, Object> food = Map.of("restaurantGid", UUID.randomUUID().toString(), "name", "Noodle",
                "description", "test", "price", new BigDecimal("12.50"), "category", "staple",
                "pictureKeys", List.of("foods/4201/picture-a"));
        assertEquals(EventCodec.canonicalJson(JSON.writeValueAsString(food)),
                EventCodec.canonicalJson(JSON.writeValueAsString(CatalogPayload.validate(CatalogKind.FOOD, food))));

        Map<String, Object> invalidPrice = new java.util.HashMap<>(food);
        invalidPrice.put("price", new BigDecimal("12.501"));
        assertThrows(IllegalArgumentException.class, () -> CatalogPayload.validate(CatalogKind.FOOD, invalidPrice));
        Map<String, Object> invalidCategory = new java.util.HashMap<>(food);
        invalidCategory.put("category", "secret-extra-category");
        assertThrows(IllegalArgumentException.class, () -> CatalogPayload.validate(CatalogKind.FOOD, invalidCategory));
    }

    @Test
    void nullableLegacyFoodFieldsAndHubBoundarySurviveCatalogPayloadValidation() {
        Map<String, Object> food = new java.util.LinkedHashMap<>();
        food.put("restaurantGid", UUID.randomUUID().toString());
        food.put("name", "未定价菜品");
        food.put("description", null);
        food.put("price", null);
        food.put("category", null);
        food.put("pictureKeys", List.of("foods/4201/no-price"));
        Map<String, Object> acceptedFood = CatalogPayload.validate(CatalogKind.FOOD, food);
        assertNull(acceptedFood.get("price"));
        assertNull(acceptedFood.get("category"));
        assertTrue(acceptedFood.containsKey("price"));
        assertTrue(acceptedFood.containsKey("category"));

        Map<String, Object> hub = new java.util.LinkedHashMap<>();
        hub.put("name", "无边界商圈");
        hub.put("centerWgs", Map.of("lng", 117.0, "lat", 36.0));
        hub.put("centerGcj", Map.of("lng", 117.01, "lat", 36.01));
        hub.put("boundaryGeoJson", null);
        Map<String, Object> acceptedHub = CatalogPayload.validate(CatalogKind.HUB, hub);
        assertNull(acceptedHub.get("boundaryGeoJson"));
        assertTrue(acceptedHub.containsKey("boundaryGeoJson"));
    }

    @Test
    void foodAndCustomTagDeleteActionsAcceptEmptyReplacementPayloads() {
        UUID author = UUID.randomUUID();
        UUID restaurant = UUID.randomUUID();
        UUID foodId = UUID.randomUUID();
        UUID foodOperation = UUID.randomUUID();
        CatalogEvents.Action deleteFood = new CatalogEvents.Action(CatalogKind.FOOD, foodId, 2, 1,
                CatalogOperation.DELETE, CAMPUS, author, restaurant, 8101, CAMPUS, Map.of(), hash(Map.of()),
                foodOperation, CAMPUS, 1, Map.of(), List.of(new CatalogEvents.ConsentEpoch(author, 3)), null, null);
        SyncEvent foodEvent = eventWithNumericId(foodOperation, 19, author, CatalogEvents.payload(deleteFood));
        assertDoesNotThrow(() -> CatalogEvents.validateEvent(foodEvent, CAMPUS, Set.of(CAMPUS, EXTERNAL)));
        assertNotEquals(foodEvent.numericId(), CatalogEvents.parseAction(foodEvent.payloadJson()).numericId(),
                "Transport operation numbering is separate from the stable catalog row ID");

        UUID tagId = UUID.randomUUID();
        UUID tagOperation = UUID.randomUUID();
        CatalogEvents.Action deleteCustomTag = new CatalogEvents.Action(CatalogKind.CUSTOM_TAG, tagId, 2, 1,
                CatalogOperation.DELETE, CAMPUS, author, null, 8102, CAMPUS, Map.of(), hash(Map.of()),
                tagOperation, CAMPUS, 1, Map.of(), List.of(new CatalogEvents.ConsentEpoch(author, 3)), null, null);
        SyncEvent tagEvent = eventWithNumericId(tagOperation, 21, author, CatalogEvents.payload(deleteCustomTag));
        assertDoesNotThrow(() -> CatalogEvents.validateEvent(tagEvent, CAMPUS, Set.of(CAMPUS, EXTERNAL)));
        assertNotEquals(tagEvent.numericId(), CatalogEvents.parseAction(tagEvent.payloadJson()).numericId());
    }

    @Test
    void normalizedCustomTagKeyUsesOwnerAndCanonicalNameRatherThanDisplaySpelling() {
        UUID owner = UUID.randomUUID();
        Map<String, Object> first = Map.of("name", "Late Night", "normalizedName", "late night",
                "tagType", "custom", "ownerGid", owner.toString());
        Map<String, Object> second = Map.of("name", "late   night", "normalizedName", "late night",
                "tagType", "custom", "ownerGid", owner.toString());
        CatalogPayload.NaturalKey firstKey = CatalogPayload.naturalKey(CatalogKind.CUSTOM_TAG,
                CatalogPayload.validate(CatalogKind.CUSTOM_TAG, first), owner.toString(), null);
        CatalogPayload.NaturalKey secondKey = CatalogPayload.naturalKey(CatalogKind.CUSTOM_TAG,
                CatalogPayload.validate(CatalogKind.CUSTOM_TAG, second), owner.toString(), null);

        assertEquals(firstKey, secondKey);
        assertNull(CatalogPayload.naturalKey(CatalogKind.RESTAURANT,
                CatalogPayload.validate(CatalogKind.RESTAURANT, restaurantPayload(null, "Hall")), null, null));
    }

    @Test
    void foodActionCannotBeReboundToADifferentRestaurantOrAuthor() {
        UUID food = UUID.randomUUID();
        UUID restaurant = UUID.randomUUID();
        UUID otherRestaurant = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        Map<String, Object> foodPayload = CatalogPayload.validate(CatalogKind.FOOD, Map.of(
                "restaurantGid", restaurant.toString(), "name", "Noodle", "description", "test",
                "price", new BigDecimal("12.50"), "category", "staple", "pictureKeys", List.of()));
        CatalogEvents.Action wrongParent = new CatalogEvents.Action(CatalogKind.FOOD, food, 1, 0,
                CatalogOperation.CREATE, CAMPUS, author, otherRestaurant, 42, CAMPUS, foodPayload, hash(foodPayload),
                UUID.randomUUID(), CAMPUS, 1, Map.of(), List.of(new CatalogEvents.ConsentEpoch(author, 1)), null, null);
        String wire = CatalogEvents.payload(wrongParent);
        SyncEvent event = event(wrongParent.operationId(), CatalogEvents.ACTION,
                CatalogEvents.SCHEMA_VERSION, author, wire);
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.validateEvent(event, CAMPUS, Set.of(CAMPUS, EXTERNAL)));

        SyncEvent forgedActor = event(wrongParent.operationId(), CatalogEvents.ACTION,
                CatalogEvents.SCHEMA_VERSION, UUID.randomUUID(), wire);
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.validateEvent(forgedActor, CAMPUS, Set.of(CAMPUS, EXTERNAL)));
    }

    @Test
    void restaurantFoodAndBridgeProofsRejectMissingOrMismatchedOwners() {
        UUID object = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        Map<String, Object> restaurant = CatalogPayload.validate(CatalogKind.RESTAURANT,
                restaurantPayload("POI-owner-proof", "Owner proof"));
        CatalogEvents.Action missingRestaurantAuthor = new CatalogEvents.Action(CatalogKind.RESTAURANT,
                object, 1, 0, CatalogOperation.CREATE, CAMPUS, null, null, 5101, CAMPUS,
                restaurant, hash(restaurant), UUID.randomUUID(), CAMPUS, 1, Map.of(), List.of(), null, null);
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.parseAction(CatalogEvents.payload(missingRestaurantAuthor)));

        UUID actualRestaurant = UUID.randomUUID();
        UUID wrongRestaurant = UUID.randomUUID();
        Map<String, Object> food = CatalogPayload.validate(CatalogKind.FOOD, Map.of(
                "restaurantGid", actualRestaurant.toString(), "name", "Child", "description", "test",
                "price", new BigDecimal("1.00"), "category", "staple", "pictureKeys", List.of()));
        CatalogEvents.Action mismatchedFoodParent = new CatalogEvents.Action(CatalogKind.FOOD,
                UUID.randomUUID(), 1, 0, CatalogOperation.CREATE, CAMPUS, author, wrongRestaurant,
                5102, CAMPUS, food, hash(food), UUID.randomUUID(), CAMPUS, 1, Map.of(),
                List.of(new CatalogEvents.ConsentEpoch(author, 1)), null, null);
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.parseAction(CatalogEvents.payload(mismatchedFoodParent)));

        CatalogEvents.Head missingOwnerHead = new CatalogEvents.Head(UUID.randomUUID(), CAMPUS, CAMPUS,
                author, null, 5103, CAMPUS, 1, 0, CatalogOperation.CREATE, CAMPUS, 1,
                Map.of(), List.of(), false, restaurant, hash(restaurant), null);
        CatalogEvents.StateBridge bridge = new CatalogEvents.StateBridge(CatalogKind.RESTAURANT,
                UUID.randomUUID(), 5103, CAMPUS, CAMPUS, null, List.of(), List.of(missingOwnerHead));
        assertThrows(IllegalArgumentException.class, () -> CatalogEvents.parseBridge(CatalogEvents.bridgePayload(bridge)));
    }

    @Test
    void operationDotMustAdvanceItsOwnSiteAndObserveAnyHigherLocalCounter() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogEvents.Action(CatalogKind.RESTAURANT,
                UUID.randomUUID(), 1, 0, CatalogOperation.CREATE, CAMPUS, UUID.randomUUID(), null,
                5, CAMPUS, CatalogPayload.validate(CatalogKind.RESTAURANT, restaurantPayload("POI-5", "Hall")),
                hash(CatalogPayload.validate(CatalogKind.RESTAURANT, restaurantPayload("POI-5", "Hall"))),
                UUID.randomUUID(), CAMPUS, 2, Map.of(), List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new CatalogEvents.Action(CatalogKind.RESTAURANT,
                UUID.randomUUID(), 1, 0, CatalogOperation.CREATE, CAMPUS, UUID.randomUUID(), null,
                5, CAMPUS, CatalogPayload.validate(CatalogKind.RESTAURANT, restaurantPayload("POI-5", "Hall")),
                hash(CatalogPayload.validate(CatalogKind.RESTAURANT, restaurantPayload("POI-5", "Hall"))),
                UUID.randomUUID(), CAMPUS, 3, Map.of(CAMPUS, 1L), List.of(), null, null));
    }

    @Test
    void authorityPolicyAllowsExternalCreationAndForwardingButRejectsExternalManagement() {
        UUID author = UUID.randomUUID();
        Map<String, Object> restaurantPayload = CatalogPayload.validate(CatalogKind.RESTAURANT,
                restaurantPayload("POI-external-create", "External create"));
        CatalogEvents.Action externalCreate = new CatalogEvents.Action(CatalogKind.RESTAURANT,
                UUID.randomUUID(), 1, 0, CatalogOperation.CREATE, CAMPUS, author, null, 7201, EXTERNAL,
                restaurantPayload, hash(restaurantPayload), UUID.randomUUID(), EXTERNAL, 1, Map.of(),
                List.of(new CatalogEvents.ConsentEpoch(author, 1)), null, null);
        SyncEvent createEvent = eventAtSite(externalCreate.operationId(), EXTERNAL, CatalogEvents.ACTION,
                author, CatalogEvents.payload(externalCreate));
        assertDoesNotThrow(() -> CatalogEvents.validateEvent(createEvent, EXTERNAL, Set.of(CAMPUS, EXTERNAL)));
        assertDoesNotThrow(() -> CatalogEvents.validateAuthoritySite(createEvent, CAMPUS));

        CatalogEvents.Action externalUpdate = new CatalogEvents.Action(CatalogKind.RESTAURANT,
                UUID.randomUUID(), 2, 1, CatalogOperation.UPDATE, CAMPUS, author, null, 7201, EXTERNAL,
                restaurantPayload, hash(restaurantPayload), UUID.randomUUID(), EXTERNAL, 1, Map.of(),
                List.of(new CatalogEvents.ConsentEpoch(author, 1)), null, null);
        SyncEvent updateEvent = eventAtSite(externalUpdate.operationId(), EXTERNAL, CatalogEvents.ACTION,
                author, CatalogEvents.payload(externalUpdate));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.validateAuthoritySite(updateEvent, CAMPUS));

        Map<String, Object> hubPayload = new java.util.LinkedHashMap<>();
        hubPayload.put("name", "Forbidden external hub");
        hubPayload.put("centerWgs", Map.of("lng", 117.0, "lat", 36.0));
        hubPayload.put("centerGcj", Map.of("lng", 117.01, "lat", 36.01));
        hubPayload.put("boundaryGeoJson", null);
        Map<String, Object> canonicalHub = CatalogPayload.validate(CatalogKind.HUB, hubPayload);
        CatalogEvents.Action externalHub = new CatalogEvents.Action(CatalogKind.HUB,
                UUID.randomUUID(), 1, 0, CatalogOperation.CREATE, CAMPUS, null, null, 7202, EXTERNAL,
                canonicalHub, hash(canonicalHub), UUID.randomUUID(), EXTERNAL, 1, Map.of(), List.of(), null, null);
        SyncEvent hubEvent = eventAtSite(externalHub.operationId(), EXTERNAL, CatalogEvents.ACTION,
                null, CatalogEvents.payload(externalHub));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEvents.validateAuthoritySite(hubEvent, CAMPUS));

        CatalogEvents.Head campusHead = new CatalogEvents.Head(UUID.randomUUID(), CAMPUS, CAMPUS, author,
                null, 7203, CAMPUS, 1, 0, CatalogOperation.CREATE, CAMPUS, 1, Map.of(),
                List.of(new CatalogEvents.ConsentEpoch(author, 1)), false, restaurantPayload,
                hash(restaurantPayload), null);
        CatalogEvents.StateBridge forwarded = new CatalogEvents.StateBridge(CatalogKind.RESTAURANT,
                UUID.randomUUID(), 7203, CAMPUS, CAMPUS, null,
                List.of(new CatalogEvents.ConsentEpoch(author, 1)), List.of(campusHead));
        SyncEvent forwardedEvent = eventAtSite(UUID.randomUUID(), EXTERNAL, CatalogEvents.BRIDGE,
                null, CatalogEvents.bridgePayload(forwarded));
        assertDoesNotThrow(() -> CatalogEvents.validateEvent(forwardedEvent, EXTERNAL, Set.of(CAMPUS, EXTERNAL)));
        assertDoesNotThrow(() -> CatalogEvents.validateAuthoritySite(forwardedEvent, CAMPUS));
    }

    private static Map<String, Object> restaurantPayload(String poi, String name) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("name", name);
        payload.put("address", null);
        payload.put("cityId", null);
        payload.put("poi", poi);
        payload.put("locationWgs", Map.of("lng", 117.0, "lat", 36.0));
        payload.put("locationGcj", Map.of("lng", 117.01, "lat", 36.01));
        payload.put("hubGid", null);
        payload.put("pictureKeys", List.of("restaurants/4201/picture-a"));
        return payload;
    }

    private static String hash(Map<String, Object> payload) {
        return EventCodec.sha256(EventCodec.canonicalJson(JSON.writeValueAsString(payload)));
    }

    private static SyncEvent event(UUID id, String type, int version, UUID actor, String payload) {
        return new SyncEvent(id, CAMPUS, UUID.randomUUID(), CatalogEvents.AGGREGATE,
                id, 1, type, version, VersionVector.empty(), VersionVector.empty().increment(CAMPUS),
                List.of(), actor, "catalog-test-command", UUID.randomUUID(), payload);
    }

    private static SyncEvent eventAtSite(UUID id, String origin, String type, UUID actor, String payload) {
        return new SyncEvent(id, origin, UUID.randomUUID(), CatalogEvents.AGGREGATE,
                id, 1, type, CatalogEvents.SCHEMA_VERSION, VersionVector.empty(),
                VersionVector.empty().increment(origin), List.of(), actor, "catalog-authority-test",
                UUID.randomUUID(), payload);
    }

    private static SyncEvent eventWithNumericId(UUID id, long numericId, UUID actor, String payload) {
        return new SyncEvent(id, CAMPUS, UUID.randomUUID(), CatalogEvents.AGGREGATE,
                id, numericId, CatalogEvents.ACTION, CatalogEvents.SCHEMA_VERSION,
                VersionVector.empty(), VersionVector.empty().increment(CAMPUS),
                List.of(), actor, "catalog-delete-test", UUID.randomUUID(), payload);
    }
}
