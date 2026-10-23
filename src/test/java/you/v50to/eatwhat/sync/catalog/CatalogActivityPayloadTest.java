package you.v50to.eatwhat.sync.catalog;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.EventCodec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V75 activity definitions: every key present, natural key is the code. */
class CatalogActivityPayloadTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Map<String, Object> activity() {
        Map<String, Object> value = new HashMap<>();
        value.put("code", "spring-food");
        value.put("name", "春天的味道");
        value.put("description", null);
        value.put("coverUrl", "https://cdn.example.test/spring.png");
        value.put("status", "ONLINE");
        value.put("startsAt", 1_700_000_000_000L);
        value.put("endsAt", null);
        value.put("sort", 3);
        value.put("requireLocation", false);
        value.put("maxPictures", 4);
        value.put("fieldSchema", List.of(
                Map.of("key", "dish", "label", "菜名", "type", "TEXT", "required", true, "maxLength", 20),
                priceField()));
        return value;
    }

    private static Map<String, Object> priceField() {
        Map<String, Object> field = new HashMap<>(Map.of("key", "price", "label", "价格", "type", "NUMBER", "required", false));
        field.put("min", 0);
        field.put("max", null);
        return field;
    }

    private static Map<String, Object> with(Consumer<Map<String, Object>> change) {
        Map<String, Object> value = activity();
        change.accept(value);
        return value;
    }

    @Test void validDefinitionSurvivesTheWireRoundTrip() {
        Map<String, Object> payload = CatalogPayload.validate(CatalogKind.ACTIVITY, activity());
        String canonical = EventCodec.canonicalJson(JSON.writeValueAsString(payload));
        Map<String, Object> decoded = CatalogPayload.validate(CatalogKind.ACTIVITY, JSON.readValue(canonical, Map.class));
        assertEquals(canonical, EventCodec.canonicalJson(JSON.writeValueAsString(decoded)));
    }

    @Test void naturalKeyIsTheCodeOnly() {
        var first = CatalogPayload.naturalKey(CatalogKind.ACTIVITY, CatalogPayload.validate(CatalogKind.ACTIVITY, activity()), null, null);
        var renamed = CatalogPayload.naturalKey(CatalogKind.ACTIVITY,
                CatalogPayload.validate(CatalogKind.ACTIVITY, with(v -> v.put("name", "另一个名字"))), null, null);
        var otherCode = CatalogPayload.naturalKey(CatalogKind.ACTIVITY,
                CatalogPayload.validate(CatalogKind.ACTIVITY, with(v -> v.put("code", "autumn"))), null, null);
        assertEquals(first, renamed);
        assertNotEquals(first, otherCode);
    }

    @Test void invalidDefinitionsAreRejected() {
        for (Consumer<Map<String, Object>> change : List.<Consumer<Map<String, Object>>>of(
                v -> v.remove("endsAt"),
                v -> v.put("code", "Spring Food"),
                v -> v.put("coverUrl", "http://cdn.example.test/a.png"),
                v -> v.put("status", "PUBLISHED"),
                v -> v.put("maxPictures", 10),
                v -> v.put("endsAt", 1_600_000_000_000L),
                v -> v.put("requireLocation", "yes"),
                v -> v.put("fieldSchema", List.of(Map.of("key", "dish", "label", "菜名", "type", "TEXT", "required", true))),
                v -> v.put("unexpected", 1))) {
            assertThrows(IllegalArgumentException.class, () -> CatalogPayload.validate(CatalogKind.ACTIVITY, with(change)));
        }
        assertDoesNotThrow(() -> CatalogPayload.validate(CatalogKind.ACTIVITY, with(v -> {
            v.put("coverUrl", null);
            v.put("startsAt", null);
            v.put("fieldSchema", List.of());
        })));
    }
}
