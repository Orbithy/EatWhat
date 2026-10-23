package you.v50to.eatwhat.sync.activity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.EventCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Wire contract for activity creation / deletion. Field whitelists are explicit: likes_count, updated_at,
 * deleted_at on creation and any signed URL never travel. Pictures are stable object keys only.
 */
public final class ActivityEvents {
    public static final String CREATED = "activity.created";
    public static final String DELETED = "activity.deleted";
    public static final int SCHEMA_VERSION = 1;
    public static final String FOODS = "activity_foods";
    public static final String DINNERS = "activity_dinners";
    /** Generic activity content; carries the activity definition's GID, an optional title and the extra fields. */
    public static final String POSTS = "activity_posts";
    public static final Set<String> TABLES = Set.of(FOODS, DINNERS, POSTS);
    public static final int MAX_EXTRA_BYTES = 8192;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> COMMON = Set.of("numeric_id", "global_id", "origin_site", "author_global_id",
            "description", "picture_url", "created_at");
    private static final Set<String> FOOD_ONLY = Set.of("food_name", "province_id", "city_id");
    private static final Set<String> POST_ONLY = Set.of("activity_global_id", "title", "province_id", "city_id", "extra");
    private static final Set<String> REQUIRED = Set.of("numeric_id", "global_id", "origin_site", "author_global_id",
            "picture_url", "created_at");

    private ActivityEvents() { }

    public record Created(long numericId, UUID globalId, String originSite, UUID authorGlobalId, String foodName,
                          String description, Integer provinceId, Integer cityId, List<String> pictures, long createdAt,
                          UUID activityGlobalId, String title, String extraJson) {
        /** Food and dinner content has no activity definition, title or extra fields. */
        public Created(long numericId, UUID globalId, String originSite, UUID authorGlobalId, String foodName,
                       String description, Integer provinceId, Integer cityId, List<String> pictures, long createdAt) {
            this(numericId, globalId, originSite, authorGlobalId, foodName, description, provinceId, cityId, pictures,
                    createdAt, null, null, null);
        }
    }

    public static boolean supportsTable(String table) { return TABLES.contains(table); }

    public static String createdPayload(String table, Created created) {
        requireTable(table);
        var node = JSON.createObjectNode();
        node.put("numeric_id", created.numericId());
        node.put("global_id", created.globalId().toString());
        node.put("origin_site", created.originSite());
        node.put("author_global_id", created.authorGlobalId().toString());
        if (created.description() == null) node.putNull("description");
        else node.put("description", created.description());
        var pictures = node.putArray("picture_url");
        created.pictures().forEach(pictures::add);
        node.put("created_at", created.createdAt());
        if (FOODS.equals(table)) {
            node.put("food_name", created.foodName());
            if (created.provinceId() == null) node.putNull("province_id"); else node.put("province_id", created.provinceId());
            if (created.cityId() == null) node.putNull("city_id"); else node.put("city_id", created.cityId());
        }
        if (POSTS.equals(table)) {
            node.put("activity_global_id", created.activityGlobalId().toString());
            if (created.title() == null) node.putNull("title"); else node.put("title", created.title());
            if (created.provinceId() == null) node.putNull("province_id"); else node.put("province_id", created.provinceId());
            if (created.cityId() == null) node.putNull("city_id"); else node.put("city_id", created.cityId());
            node.set("extra", JSON.readTree(created.extraJson() == null ? "{}" : created.extraJson()));
        }
        return EventCodec.canonicalJson(node.toString());
    }

    /** Strict decoder shared by transport policy, dependency checks and application. */
    public static Created parseCreated(String table, String payloadJson) { return parseCreated(table,payloadJson,false); }

    public static Created parseCreated(String table,String payloadJson,boolean registeredLegacy) {
        requireTable(table);
        JsonNode node = parse(payloadJson);
        Set<String> allowed = new java.util.HashSet<>(COMMON);
        Set<String> required = new java.util.HashSet<>(REQUIRED);
        if (FOODS.equals(table)) { allowed.addAll(FOOD_ONLY); required.add("food_name"); }
        if (POSTS.equals(table)) { allowed.addAll(POST_ONLY); required.addAll(Set.of("activity_global_id", "extra")); }
        var keys = Set.copyOf(node.propertyNames());
        if (!node.isObject() || !allowed.containsAll(keys) || !keys.containsAll(required)) {
            throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
        }
        long numeric = integer(node, "numeric_id");
        long createdAt = integer(node, "created_at");
        if (numeric <= (registeredLegacy?0:1_000_000_000L) || numeric>9_007_199_254_740_991L || createdAt <= 0) throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
        String origin = text(node, "origin_site");
        String description = nullableText(node, "description", 2000);
        JsonNode array = node.get("picture_url");
        if (array == null || !array.isArray() || array.size() < 1 || array.size() > 9) {
            throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
        }
        var pictures = new ArrayList<String>();
        for (JsonNode item : array) {
            if (!item.isString() || !item.stringValue().matches("activity/[^\\p{Cntrl}]{1,500}")) {
                throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
            }
            pictures.add(item.stringValue());
        }
        String foodName = null;
        Integer province = null;
        Integer city = null;
        if (FOODS.equals(table)) {
            foodName = text(node, "food_name");
            if (foodName.isBlank() || foodName.length() > 64) throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
            province = nullableInt(node, "province_id");
            city = nullableInt(node, "city_id");
        }
        UUID activity = null;
        String title = null;
        String extra = null;
        if (POSTS.equals(table)) {
            activity = uuid(node, "activity_global_id");
            title = nullableText(node, "title", 64);
            province = nullableInt(node, "province_id");
            city = nullableInt(node, "city_id");
            JsonNode extraNode = node.get("extra");
            if (extraNode == null || !extraNode.isObject()) throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
            extra = EventCodec.canonicalJson(extraNode.toString());
            if (extra.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_EXTRA_BYTES) {
                throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
            }
        }
        return new Created(numeric, uuid(node, "global_id"), origin, uuid(node, "author_global_id"), foodName,
                description, province, city, List.copyOf(pictures), createdAt, activity, title, extra);
    }

    /** A post waits until its activity definition has been projected on this site by catalog sync. */
    public static boolean activityDefinitionPresent(org.springframework.jdbc.core.JdbcTemplate jdbc, UUID activityGid) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity i JOIN public.activities a ON a.id=i.numeric_id
                  WHERE i.entity_type='activities' AND i.global_id=?)
                """, Boolean.class, activityGid));
    }

    public static String deletedPayload(long deletedAtMillis) {
        if (deletedAtMillis <= 0) throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
        return EventCodec.canonicalJson(JSON.createObjectNode().put("deleted_at", deletedAtMillis).toString());
    }

    public static long parseDeleted(String payloadJson) {
        JsonNode node = parse(payloadJson);
        if (!node.isObject() || node.size() != 1) throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
        long value = integer(node, "deleted_at");
        if (value <= 0) throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
        return value;
    }

    private static void requireTable(String table) {
        if (!supportsTable(table)) throw new IllegalArgumentException("UNSUPPORTED_ACTIVITY_TABLE");
    }

    private static JsonNode parse(String json) {
        try { return JSON.readTree(EventCodec.canonicalJson(json)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD"); }
    }

    private static long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
        }
        return value.longValue();
    }

    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
        }
        return value.intValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
        return value.stringValue();
    }

    private static String nullableText(JsonNode node, String field, int limit) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString() || value.stringValue().length() > limit) throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD");
        return value.stringValue();
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            String text = text(node, field);
            UUID parsed = UUID.fromString(text);
            if (!parsed.toString().equals(text) || parsed.equals(new UUID(0, 0))) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("INVALID_ACTIVITY_PAYLOAD"); }
    }
}
