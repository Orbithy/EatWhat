package you.v50to.eatwhat.sync.catalog;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.EventCodec;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V72 food display fields: optional, canonical, and absent at their defaults. */
class CatalogFoodDisplayPayloadTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Map<String, Object> legacy() {
        Map<String, Object> food = new HashMap<>();
        food.put("restaurantGid", UUID.randomUUID().toString());
        food.put("name", "Noodle");
        food.put("description", null);
        food.put("price", new BigDecimal("12.5"));
        food.put("category", "staple");
        food.put("pictureKeys", List.of("foods/4201/a"));
        return food;
    }

    private static Map<String, Object> full() {
        Map<String, Object> food = legacy();
        food.put("mealTimes", List.of("lunch", "night"));
        food.put("stageColor", "chili");
        food.put("stageAlternates", List.of("mango", "soy"));
        food.put("coverCrop", Map.of("centerX", new BigDecimal("0.4"), "centerY", new BigDecimal("0.5500"), "diameter", 1));
        food.put("removeBackground", true);
        return food;
    }

    @Test
    void legacyPayloadWithoutDisplayFieldsIsUnchanged() {
        Map<String, Object> food = legacy();
        assertEquals(EventCodec.canonicalJson(JSON.writeValueAsString(food)),
                EventCodec.canonicalJson(JSON.writeValueAsString(CatalogPayload.validate(CatalogKind.FOOD, food))));
    }

    @Test
    void displayFieldsSurviveAJsonRoundTrip() {
        Map<String, Object> accepted = CatalogPayload.validate(CatalogKind.FOOD, full());
        @SuppressWarnings("unchecked")
        Map<String, Object> decoded = JSON.readValue(JSON.writeValueAsString(accepted), Map.class);
        Map<String, Object> again = CatalogPayload.validate(CatalogKind.FOOD, decoded);
        assertEquals(EventCodec.canonicalJson(JSON.writeValueAsString(accepted)),
                EventCodec.canonicalJson(JSON.writeValueAsString(again)));
    }

    @Test
    void defaultsAndNonCanonicalValuesAreRejected() {
        List<Consumer<Map<String, Object>>> invalid = List.of(
                f -> f.put("mealTimes", List.of()),
                f -> f.put("mealTimes", List.of("night", "lunch")),
                f -> f.put("mealTimes", List.of("lunch", "lunch")),
                f -> f.put("mealTimes", List.of("brunch")),
                f -> f.remove("stageAlternates"),
                f -> f.put("stageColor", "purple"),
                f -> f.put("stageAlternates", List.of("chili", "soy")),
                f -> f.put("stageAlternates", List.of("mango")),
                f -> f.put("coverCrop", Map.of("centerX", 0.5, "centerY", 0.5)),
                f -> f.put("coverCrop", Map.of("centerX", new BigDecimal("1.1"), "centerY", 0, "diameter", 1)),
                f -> f.put("coverCrop", Map.of("centerX", 0, "centerY", 0, "diameter", 0)),
                f -> f.put("coverCrop", Map.of("centerX", new BigDecimal("0.12345"), "centerY", 0, "diameter", 1)),
                f -> f.put("removeBackground", false),
                f -> f.put("unknownDisplayField", true));
        for (Consumer<Map<String, Object>> change : invalid) {
            Map<String, Object> food = full();
            change.accept(food);
            assertThrows(IllegalArgumentException.class, () -> CatalogPayload.validate(CatalogKind.FOOD, food), food.toString());
        }
        Map<String, Object> zeroCenter = full();
        zeroCenter.put("coverCrop", Map.of("centerX", 0, "centerY", 0, "diameter", 1));
        assertDoesNotThrow(() -> CatalogPayload.validate(CatalogKind.FOOD, zeroCenter));
    }

    @Test
    void displayFieldsAreFoodOnly() {
        Map<String, Object> tag = new HashMap<>(Map.of("name", "辣", "normalizedName", "辣", "tagType", "system"));
        tag.put("mealTimes", List.of("lunch"));
        assertThrows(IllegalArgumentException.class, () -> CatalogPayload.validate(CatalogKind.SYSTEM_TAG, tag));
    }
}
