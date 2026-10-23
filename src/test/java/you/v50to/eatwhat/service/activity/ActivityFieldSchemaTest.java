package you.v50to.eatwhat.service.activity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivityFieldSchemaTest {

    private static List<Map<String, Object>> schema() {
        Map<String, Object> price = new HashMap<>(Map.of("key", "price", "label", "价格", "type", "NUMBER", "required", false));
        price.put("min", 0);
        price.put("max", 500);
        return ActivityFieldSchema.validateDefinition(List.of(
                Map.of("key", "dish", "label", "菜名", "type", "TEXT", "required", true, "maxLength", 10),
                price,
                Map.of("key", "spicy", "label", "辣不辣", "type", "BOOLEAN", "required", false),
                Map.of("key", "meal", "label", "饭点", "type", "SELECT", "required", false, "options", List.of("午饭", "晚饭"))));
    }

    @Test void definitionsAreNormalizedAndBounded() {
        List<Map<String, Object>> fields = schema();
        assertEquals(List.of("dish", "price", "spicy", "meal"), fields.stream().map(f -> f.get("key")).toList());
        assertEquals(List.of(), ActivityFieldSchema.validateDefinition(null));
        assertThrows(IllegalArgumentException.class, () -> ActivityFieldSchema.validateDefinition(List.of(
                Map.of("key", "dish", "label", "菜名", "type", "TEXT", "required", true, "maxLength", 10),
                Map.of("key", "dish", "label", "又一个", "type", "BOOLEAN", "required", false))));
        assertThrows(IllegalArgumentException.class, () -> ActivityFieldSchema.validateDefinition(List.of(
                Map.of("key", "Dish", "label", "菜名", "type", "BOOLEAN", "required", false))));
        assertThrows(IllegalArgumentException.class, () -> ActivityFieldSchema.validateDefinition(List.of(
                Map.of("key", "dish", "label", "菜名", "type", "DATE", "required", false))));
        assertThrows(IllegalArgumentException.class, () -> ActivityFieldSchema.validateDefinition(List.of(
                Map.of("key", "meal", "label", "饭点", "type", "SELECT", "required", false, "options", List.of("午饭", "午饭")))));
        assertThrows(IllegalArgumentException.class, () -> ActivityFieldSchema.validateDefinition(List.of(
                Map.of("key", "price", "label", "价格", "type", "NUMBER", "required", false, "min", 5, "max", 1))));
        assertThrows(IllegalArgumentException.class, () -> ActivityFieldSchema.validateDefinition(
                java.util.stream.IntStream.range(0, 11).mapToObj(i -> (Object) Map.of("key", "f" + i, "label", "字段",
                        "type", "BOOLEAN", "required", false)).toList()));
    }

    @Test void extraValuesFollowTheDefinition() {
        Map<String, Object> extra = ActivityFieldSchema.validateExtra(schema(),
                Map.of("dish", "  春卷  ", "price", new BigDecimal("12.5"), "spicy", true, "meal", "午饭"));
        assertEquals("春卷", extra.get("dish"));
        assertEquals(Map.of("dish", "春卷"), ActivityFieldSchema.validateExtra(schema(), Map.of("dish", "春卷", "meal", "")));
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                Map.of(),
                Map.of("dish", "   "),
                Map.of("dish", "一二三四五六七八九十十"),
                Map.of("dish", "春卷", "price", 501),
                Map.of("dish", "春卷", "price", "12"),
                Map.of("dish", "春卷", "spicy", "yes"),
                Map.of("dish", "春卷", "meal", "早饭"),
                Map.of("dish", "春卷", "unknown", 1))) {
            assertThrows(IllegalArgumentException.class, () -> ActivityFieldSchema.validateExtra(schema(), invalid), invalid.toString());
        }
    }
}
