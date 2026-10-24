package you.v50to.eatwhat.sync.catalog;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.service.activity.ActivityFieldSchema;
import you.v50to.eatwhat.sync.EventCodec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Canonical allowlisted public catalog data; no signed URLs, counters, or file bytes. */
public final class CatalogPayload {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Set<String> FOOD_CATEGORIES = Set.of("staple", "drink", "snack", "dessert", "soup",
            "hot_pot", "grill", "cold_dish", "side_dish", "other");
    private static final List<String> MEAL_TIMES = List.of("breakfast", "lunch", "dinner", "night");
    private static final Set<String> STAGE_COLORS = Set.of("broth", "chili", "mango", "porcelain", "bamboo",
            "redoil", "soy", "matcha", "tomato");

    private static final java.util.regex.Pattern ACTIVITY_CODE = java.util.regex.Pattern.compile("^[a-z][a-z0-9-]{1,31}$");
    private static final java.util.regex.Pattern ACTIVITY_COVER = java.util.regex.Pattern.compile("^https://\\S+$");
    private static final Set<String> ACTIVITY_STATUSES = Set.of("DRAFT", "ONLINE", "OFFLINE");

    private CatalogPayload() { }

    public record NaturalKey(String canonical, String hash) { }

    public static Map<String, Object> validate(CatalogKind kind, Map<String, Object> source) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        Set<String> fields = fields(kind);
        Set<String> optional = optionalFields(kind);
        if (!source.keySet().containsAll(fields)) invalid();
        for (String key : source.keySet()) if (!fields.contains(key) && !optional.contains(key)) invalid();
        Map<String, Object> value = new TreeMap<>();
        source.forEach((key, item) -> value.put(key, normalizeValue(key, item)));
        requireText(value, "name", 1, kind == CatalogKind.FOOD || kind == CatalogKind.CUSTOM_TAG
                || kind == CatalogKind.SYSTEM_TAG || kind == CatalogKind.ACTIVITY ? 64 : 255);

        switch (kind) {
            case HUB -> {
                coordinate(value, "centerWgs", -180, 180, -90, 90);
                coordinate(value, "centerGcj", 72.004, 137.8347, 0.8293, 55.8271);
                requireNullableText(value, "boundaryGeoJson", 65_536);
                if (value.containsKey("campusId")) {
                    Object campus = value.get("campusId");
                    if (!(campus instanceof Integer || campus instanceof Long) || ((Number) campus).longValue() <= 0) invalid();
                }
            }
            case RESTAURANT -> {
                requireNullableText(value, "address", 2_000);
                requireNullableLong(value, "cityId");
                requireNullableUuid(value, "hubGid");
                requireNullableText(value, "poi", 512);
                coordinate(value, "locationWgs", -180, 180, -90, 90);
                coordinate(value, "locationGcj", 72.004, 137.8347, 0.8293, 55.8271);
                requireStringList(value, "pictureKeys", 32);
            }
            case FOOD -> {
                requireUuid(value, "restaurantGid");
                requireNullableText(value, "description", 2_000);
                requireNullableText(value, "category", 64);
                Object category = value.get("category");
                if (category != null && !FOOD_CATEGORIES.contains(category)) invalid();
                requireNullableDecimal(value, "price", 0, 999_999.99, 2);
                requireStringList(value, "pictureKeys", 9);
                foodDisplayFields(value);
            }
            case SYSTEM_TAG, CUSTOM_TAG -> {
                requireText(value, "normalizedName", 1, 64);
                requireText(value, "tagType", 1, 16);
                String expected = kind == CatalogKind.SYSTEM_TAG ? "system" : "custom";
                if (!expected.equals(value.get("tagType"))) invalid();
                if (kind == CatalogKind.CUSTOM_TAG) requireUuid(value, "ownerGid");
            }
            case ACTIVITY -> activityFields(value);
        }
        return Collections.unmodifiableMap(value);
    }

    public static NaturalKey naturalKey(CatalogKind kind, Map<String, Object> payload,
                                        String ownerGid, String restaurantGid) {
        Map<String, Object> key = new LinkedHashMap<>();
        switch (kind) {
            case HUB -> { return null; }
            case RESTAURANT -> {
                Object poi = payload.get("poi");
                if (poi == null) return null;
                key.put("poi", poi);
            }
            case FOOD -> {
                key.put("restaurantGid", Objects.requireNonNull(restaurantGid));
                key.put("name", you.v50to.eatwhat.utils.FoodNames.key((String) Objects.requireNonNull(payload.get("name"))));
            }
            case SYSTEM_TAG -> key.put("normalizedName", Objects.requireNonNull(payload.get("normalizedName")));
            case ACTIVITY -> key.put("code", Objects.requireNonNull(payload.get("code")));
            case CUSTOM_TAG -> {
                key.put("ownerGid", Objects.requireNonNull(ownerGid));
                key.put("normalizedName", Objects.requireNonNull(payload.get("normalizedName")));
            }
        }
        String canonical = EventCodec.canonicalJson(MAPPER.writeValueAsString(key));
        return new NaturalKey(canonical, EventCodec.sha256(canonical));
    }

    public static String normalizedTagName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Set<String> fields(CatalogKind kind) {
        return switch (kind) {
            case HUB -> Set.of("name", "centerWgs", "centerGcj", "boundaryGeoJson");
            case RESTAURANT -> Set.of("name", "address", "cityId", "poi", "locationWgs", "locationGcj",
                    "hubGid", "pictureKeys");
            case FOOD -> Set.of("restaurantGid", "name", "description", "price", "category", "pictureKeys");
            case SYSTEM_TAG -> Set.of("name", "normalizedName", "tagType");
            case CUSTOM_TAG -> Set.of("name", "normalizedName", "tagType", "ownerGid");
            // Every key is always present; JSON null stands for an unset optional value.
            case ACTIVITY -> Set.of("code", "name", "description", "coverUrl", "status", "startsAt", "endsAt",
                    "sort", "requireLocation", "maxPictures", "fieldSchema");
        };
    }

    /**
     * Fields added after the first catalog release (food display fields in V72, hub campus in V73). They are present
     * only when they differ from their defaults, so payloads and content hashes of older objects are unchanged.
     */
    private static Set<String> optionalFields(CatalogKind kind) {
        return switch (kind) {
            case FOOD -> Set.of("mealTimes", "stageColor", "stageAlternates", "coverCrop", "removeBackground");
            // V73: present only when the hub belongs to a campus.
            case HUB -> Set.of("campusId");
            default -> Set.of();
        };
    }

    private static void foodDisplayFields(Map<String, Object> value) {
        if (value.containsKey("mealTimes")) {
            Object raw = value.get("mealTimes");
            if (!(raw instanceof List<?>) || ((List<?>) raw).isEmpty()) invalid();
            int previous = -1;
            for (Object item : (List<?>) raw) {
                int index = MEAL_TIMES.indexOf(item);
                // Canonical order and no duplicates keep one content hash per meal-time set.
                if (index <= previous) invalid();
                previous = index;
            }
        }
        if (value.containsKey("stageColor") != value.containsKey("stageAlternates")) invalid();
        if (value.containsKey("stageColor")) {
            Object color = value.get("stageColor");
            Object alternates = value.get("stageAlternates");
            if (!STAGE_COLORS.contains(color) || !(alternates instanceof List<?>) || ((List<?>) alternates).size() != 2) invalid();
            List<?> pair = (List<?>) alternates;
            if (!STAGE_COLORS.contains(pair.get(0)) || !STAGE_COLORS.contains(pair.get(1))
                    || Set.of(color, pair.get(0), pair.get(1)).size() != 3) invalid();
        }
        if (value.containsKey("coverCrop")) {
            Object raw = value.get("coverCrop");
            if (!(raw instanceof Map<?, ?>)) invalid();
            Map<?, ?> crop = (Map<?, ?>) raw;
            if (!crop.keySet().equals(Set.of("centerX", "centerY", "diameter"))) invalid();
            cropNumber(crop.get("centerX"), true);
            cropNumber(crop.get("centerY"), true);
            cropNumber(crop.get("diameter"), false);
        }
        if (value.containsKey("removeBackground") && !Boolean.TRUE.equals(value.get("removeBackground"))) invalid();
    }

    private static void activityFields(Map<String, Object> value) {
        Object code = value.get("code");
        if (!(code instanceof String text) || !ACTIVITY_CODE.matcher(text).matches()) invalid();
        requireNullableText(value, "description", 2_000);
        requireNullableText(value, "coverUrl", 512);
        Object cover = value.get("coverUrl");
        if (cover != null && !ACTIVITY_COVER.matcher((String) cover).matches()) invalid();
        if (!ACTIVITY_STATUSES.contains(value.get("status"))) invalid();
        Long startsAt = nullableEpochMillis(value.get("startsAt"));
        Long endsAt = nullableEpochMillis(value.get("endsAt"));
        if (startsAt != null && endsAt != null && startsAt >= endsAt) invalid();
        Object sort = value.get("sort");
        if (!(sort instanceof Integer || sort instanceof Long) || ((Number) sort).longValue() < Integer.MIN_VALUE
                || ((Number) sort).longValue() > Integer.MAX_VALUE) invalid();
        if (!(value.get("requireLocation") instanceof Boolean)) invalid();
        Object maxPictures = value.get("maxPictures");
        if (!(maxPictures instanceof Integer || maxPictures instanceof Long) || ((Number) maxPictures).longValue() < 1
                || ((Number) maxPictures).longValue() > 9) invalid();
        try {
            ActivityFieldSchema.validateDefinition(value.get("fieldSchema"));
        } catch (IllegalArgumentException failure) {
            invalid();
        }
    }

    private static Long nullableEpochMillis(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() <= 0) invalid();
        return ((Number) raw).longValue();
    }

    private static void cropNumber(Object raw, boolean zeroAllowed) {
        // Whole numbers arrive as Integer after JSON decoding (1.0000 is serialized as 1).
        if (!(raw instanceof BigDecimal || raw instanceof Integer || raw instanceof Long)) invalid();
        BigDecimal decimal = new BigDecimal(raw.toString());
        if (decimal.signum() < 0 || (!zeroAllowed && decimal.signum() == 0) || decimal.compareTo(BigDecimal.ONE) > 0
                || Math.max(0, decimal.scale()) > 4) invalid();
    }

    private static Object normalizeValue(String key, Object value) {
        if (value == null || value instanceof Boolean || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof String text) {
            if (text.length() > 65_536 || text.indexOf('\0') >= 0) invalid();
            if (key.equals("boundaryGeoJson")) return EventCodec.canonicalJson(text);
            return text;
        }
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros();
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (!Double.isFinite(decimal)) invalid();
            return BigDecimal.valueOf(decimal).stripTrailingZeros();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new TreeMap<>();
            for (var entry : map.entrySet()) {
                Object rawKey = entry.getKey();
                if (!(rawKey instanceof String)) invalid();
                String nestedKey = (String) rawKey;
                nested.put(nestedKey, normalizeValue(nestedKey, entry.getValue()));
            }
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof List<?> list) {
            if (list.size() > 32) invalid();
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) normalized.add(normalizeValue(key, item));
            return List.copyOf(normalized);
        }
        invalid();
        return null;
    }

    private static void coordinate(Map<String, Object> payload, String field,
                                   double minLng, double maxLng, double minLat, double maxLat) {
        Object raw = payload.get(field);
        if (!(raw instanceof Map<?, ?>)) invalid();
        Map<?, ?> coordinate = (Map<?, ?>) raw;
        if (!coordinate.keySet().equals(Set.of("lng", "lat"))) invalid();
        double longitude = numeric(coordinate.get("lng"));
        double latitude = numeric(coordinate.get("lat"));
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < minLng || longitude > maxLng || latitude < minLat || latitude > maxLat) invalid();
    }

    private static double numeric(Object value) {
        if (!(value instanceof Number)) invalid();
        return ((Number) value).doubleValue();
    }

    private static void requireStringList(Map<String, Object> payload, String field, int limit) {
        Object raw = payload.get(field);
        if (!(raw instanceof List<?>)) invalid();
        List<?> list = (List<?>) raw;
        if (list.size() > limit) invalid();
        for (Object item : list) {
            if (!(item instanceof String)) invalid();
            String key = (String) item;
            if (key.isBlank() || key.length() > 512 || key.startsWith("http://") || key.startsWith("https://")
                    || key.contains("?")) invalid();
        }
    }

    private static void requireText(Map<String, Object> value, String key, int min, int max) {
        Object raw = value.get(key);
        if (!(raw instanceof String)) invalid();
        int length = ((String) raw).length();
        if (length < min || length > max) invalid();
    }

    private static void requireNullableText(Map<String, Object> value, String key, int max) {
        if (!value.containsKey(key)) invalid();
        Object raw = value.get(key);
        if (raw != null && (!(raw instanceof String) || ((String) raw).length() > max)) invalid();
    }

    private static void requireNullableLong(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (raw != null && (!(raw instanceof Number) || ((Number) raw).longValue() <= 0)) invalid();
    }

    private static void requireUuid(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof String)) invalid();
        parseUuid((String) raw);
    }

    private static void requireNullableUuid(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (raw != null) {
            if (!(raw instanceof String)) invalid();
            parseUuid((String) raw);
        }
    }

    private static void parseUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) invalid();
        } catch (IllegalArgumentException failure) {
            invalid();
        }
    }

    private static void requireNullableDecimal(Map<String, Object> value, String key,
                                               double minimum, double maximum, int scale) {
        Object raw = value.get(key);
        if (raw == null) return;
        if (!(raw instanceof Number)) invalid();
        Number number = (Number) raw;
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal) || decimal < minimum || decimal > maximum) invalid();
        if (number instanceof BigDecimal exact && Math.max(0, exact.stripTrailingZeros().scale()) > scale) invalid();
    }

    private static void invalid() {
        throw new IllegalArgumentException("INVALID_CATALOG_PAYLOAD");
    }
}
