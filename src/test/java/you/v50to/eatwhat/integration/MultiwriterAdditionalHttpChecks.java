package you.v50to.eatwhat.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Extra isolated HTTP writes. The caller owns the partition and calls recovered before banning the author. */
final class MultiwriterAdditionalHttpChecks {
    private MultiwriterAdditionalHttpChecks() { }

    static final class State {
        private final List<Rows> rows;
        private final List<String> adminTokens;
        private final List<String> authorTokens;
        private final long authorId;
        private State(List<Rows> rows, List<String> admins, List<String> authors, long authorId) {
            this.rows = List.copyOf(rows);
            this.adminTokens = List.copyOf(admins);
            this.authorTokens = List.copyOf(authors);
            this.authorId = authorId;
        }
        /** Deliberate omissions, rather than pretending that these adapters have been exercised. */
        List<String> gaps() {
            return List.of("Provider-dependent SSO/SMS/email routes and account/password conflicts are separate acceptance cases.",
                    "Catalog/profile administrator conflict decisions require a deliberately concurrent fixture, not this CRUD helper.");
        }
    }

    private record Rows(int source, String prefix, long hub, long deletedHub, long restaurant,
                        long deletedRestaurant, long food, long deletedFood, long systemTag,
                        long deletedSystemTag, long customTag, long deletedCustomTag,
                        long activity, long deletedActivity, long notificationOwner, String notificationOwnerName,
                        List<Long> notices, long adminId, List<UUID> cancellationOperations) { }

    static State disconnected(ClusterSeamlessIT fixture, List<String> adminTokens,
                              List<String> authorTokens, long authorId) throws Exception {
        assertEquals(2, adminTokens.size());
        assertEquals(2, authorTokens.size());
        var all = new ArrayList<Rows>();
        String run = UUID.randomUUID().toString().substring(0, 8);
        for (int node = 0; node < 2; node++) {
            var db = fixture.databases.get(node).admin();
            String prefix = "extra" + run + "n" + node;
            String admin = adminTokens.get(node), author = authorTokens.get(node);
            long adminId = ok(fixture, node, "GET", "/user/info", null, admin).path("data").path("id").asLong();
            // UserInfo DTOs need not expose the local id; the fixture's unique administrator is known in SQL.
            if (adminId == 0) adminId = db.queryForObject("SELECT id FROM users WHERE nick_name='meshadmin'", Long.class);
            double longitude = 120.3 + node;
            long hub = hub(fixture, node, admin, prefix + "hub", longitude, 30.3);
            ok(fixture, node, "PUT", "/hub/edit/" + hub, hubBody(prefix + "hub-edited", longitude, 30.3), admin);
            long deletedHub = hub(fixture, node, admin, prefix + "hub-delete", longitude, 31.3);
            long restaurant = restaurant(fixture, node, author, prefix + "restaurant", longitude, 30.3);
            ok(fixture, node, "PUT", "/restaurant/edit/" + restaurant, Map.of("name", prefix + "restaurant-edited"), admin);
            ok(fixture, node, "POST", "/hub/rebind-restaurants/" + hub, Map.of(), admin);
            assertEquals(hub, db.queryForObject("SELECT hub_id FROM restaurant WHERE id=?", Long.class, restaurant));
            long deletedRestaurant = restaurant(fixture, node, author, prefix + "restaurant-delete", longitude, 31.3);
            ok(fixture, node, "POST", "/hub/rebind-restaurants/" + deletedHub, Map.of(), admin);
            // Deleting a dedicated hub also tests its independent restaurant unassignment operation.
            assertEquals(deletedHub, db.queryForObject("SELECT hub_id FROM restaurant WHERE id=?", Long.class, deletedRestaurant));
            ok(fixture, node, "DELETE", "/hub/delete/" + deletedHub, null, admin);
            assertNull(db.queryForObject("SELECT hub_id FROM restaurant WHERE id=?", Long.class, deletedRestaurant));
            ok(fixture, node, "PUT", "/restaurant/edit/" + deletedRestaurant, Map.of("name", prefix + "restaurant-deleted-edited"), admin);
            ok(fixture, node, "DELETE", "/restaurant/delete/" + deletedRestaurant, null, admin);

            String foodKey = upload(fixture, node, "foods", author);
            long food = food(fixture, node, author, restaurant, prefix + "food", foodKey);
            ok(fixture, node, "PUT", "/foods/edit/" + food, Map.of("name", prefix + "food-edited",
                    "mealTimes", List.of("lunch", "night"), "stageColor", "chili",
                    "stageAlternates", List.of("mango", "soy"),
                    "coverCrop", Map.of("centerX", 0.4, "centerY", 0.55, "diameter", 0.8),
                    "removeBackground", true), admin);
            long deletedFood = food(fixture, node, author, restaurant, prefix + "food-delete", foodKey);
            ok(fixture, node, "PUT", "/foods/edit/" + deletedFood, Map.of("name", prefix + "food-deleted-edited"), admin);
            ok(fixture, node, "DELETE", "/foods/delete/" + deletedFood, null, admin);
            for (String relation : List.of("like", "favorite", "unlike", "unfavorite"))
                ok(fixture, node, "POST", "/foods/" + food + "/" + relation, Map.of(), author);

            long systemTag = systemTag(fixture, node, admin, prefix + "system");
            ok(fixture, node, "PUT", "/admin/food-tags/" + systemTag, Map.of("name", prefix + "system-edited"), admin);
            ok(fixture, node, "POST", "/foods/" + food + "/tags/system", Map.of("tagIds", List.of(systemTag)), author);
            ok(fixture, node, "DELETE", "/foods/" + food + "/tags/" + systemTag, null, author);
            long deletedSystem = systemTag(fixture, node, admin, prefix + "system-delete");
            ok(fixture, node, "DELETE", "/admin/food-tags/" + deletedSystem, null, admin);
            long customTag = customTag(fixture, node, author, authorId, food, prefix + "custom");
            ok(fixture, node, "PUT", "/foods/tags/my/" + customTag, Map.of("name", prefix + "custom-edited"), author);
            ok(fixture, node, "DELETE", "/foods/" + food + "/tags/" + customTag, null, author);
            long deletedCustom = customTag(fixture, node, author, authorId, food, prefix + "custom-delete");
            ok(fixture, node, "DELETE", "/foods/" + food + "/tags/" + deletedCustom, null, author);
            ok(fixture, node, "DELETE", "/foods/tags/my/" + deletedCustom, null, author);

            // Each partitioned site's administrator opens its own activity; posts there wait for the definition elsewhere.
            onlineActivity(fixture, node, admin, prefix + "act");
            String activityKey = upload(fixture, node, "activity", author);
            long activity = post(fixture, node, author, prefix + "act", prefix + "activity", activityKey);
            ok(fixture, node, "POST", "/activity/posts/" + activity + "/like", null, author);
            ok(fixture, node, "DELETE", "/activity/posts/" + activity + "/like", null, author);
            long deletedActivity = post(fixture, node, author, prefix + "act", prefix + "activity-delete", activityKey);
            ok(fixture, node, "DELETE", "/activity/posts/" + deletedActivity, null, author);

            // Dedicated unverified users suffice for follow/read/preferences. No provider verification is fabricated.
            String username = "r" + run + node; // 10 characters: registration permits only 3..10.
            var registered = ok(fixture, node, "POST", "/auth/register", Map.of("username", username,
                    "password", "synthetic-password", "device", "WEB", "crossBorderConsent", true,
                    "consentPolicyVersion", "cross-border-preferences-v3"), null);
            String reader = registered.path("data").path("tokenValue").asText();
            assertFalse(reader.isBlank());
            long owner = db.queryForObject("SELECT id FROM users WHERE nick_name=?", Long.class, username);
            ok(fixture, node, "PUT", "/user/updateInfo", Map.of("userName", "昵称" + username), reader);
            ok(fixture, node, "PUT", "/user/recommendation-profile/preferences", Map.of("likedTags", List.of(prefix), "dislikedTags", List.of()), reader);
            ok(fixture, node, "DELETE", "/user/recommendation-profile/preferences", null, reader);
            ok(fixture, node, "PUT", "/user/recommendation-profile/preferences", Map.of("likedTags", List.of(prefix + "restored"), "dislikedTags", List.of()), reader);
            ok(fixture, node, "POST", "/user/follow?userId=" + owner, Map.of(), author);
            long firstNotice = db.queryForObject("SELECT id FROM notifications WHERE account_id=? AND actor_id=? AND type='follow'", Long.class, owner, authorId);
            ok(fixture, node, "POST", "/notification/read/" + firstNotice, Map.of(), reader);
            ok(fixture, node, "POST", "/user/follow?userId=" + owner, Map.of(), admin);
            var notices = db.queryForList("SELECT id FROM notifications WHERE account_id=? ORDER BY id", Long.class, owner);
            assertEquals(2, notices.size());
            ok(fixture, node, "POST", "/notification/read/all", Map.of(), reader);
            ok(fixture, node, "POST", "/user/unfollow?userId=" + owner, Map.of(), author);
            ok(fixture, node, "POST", "/user/unfollow?userId=" + owner, Map.of(), admin);
            all.add(new Rows(node, prefix, hub, deletedHub, restaurant, deletedRestaurant, food, deletedFood,
                    systemTag, deletedSystem, customTag, deletedCustom, activity, deletedActivity, owner, username,
                    List.copyOf(notices), adminId, db.queryForList("SELECT event_id FROM eatwhat_sync.relation_operation WHERE NOT adding AND payload->>'target' IN (SELECT global_id::text FROM eatwhat_identity.entity_identity WHERE (entity_type='foods' AND numeric_id=?) OR (entity_type='activity_posts' AND numeric_id=?) OR (entity_type='users' AND numeric_id=?)) ORDER BY event_id", UUID.class, food, activity, owner)));
            assertTrue(all.getLast().cancellationOperations.size() >= 7, "Dedicated cancellation intents must be durable before reconnect");
            System.out.println("Additional HTTP checkpoint: dedicated writes completed at node " + node);
        }
        return new State(all, adminTokens, authorTokens, authorId);
    }

    static void recovered(ClusterSeamlessIT fixture, State state) throws Exception {
        for (Rows row : state.rows) {
            ClusterSeamlessIT.await(120, () -> fixture.databases.subList(0, 2).stream()
                    .allMatch(db -> converged(db.admin(), row, state.authorId)));
            for (int node = 0; node < 2; node++) {
                assertTrue(ok(fixture, node, "GET", "/hub/detail/" + row.hub, null, state.adminTokens.get(node))
                        .toString().contains(row.prefix + "hub-edited"));
                assertTrue(ok(fixture, node, "GET", "/restaurant/detail/" + row.restaurant, null, state.authorTokens.get(node))
                        .toString().contains(row.prefix + "restaurant-edited"));
                assertTrue(ok(fixture, node, "GET", "/foods/detail/" + row.food, null, state.authorTokens.get(node))
                        .toString().contains(row.prefix + "food-edited"));
                ok(fixture, node, "GET", "/activity/posts/" + row.activity, null, state.authorTokens.get(node));
                String reader = fixture.login(node, row.notificationOwnerName);
                assertEquals("昵称" + row.notificationOwnerName, ok(fixture, node, "GET", "/user/info", null, reader)
                        .path("data").path("userName").asText());
                assertTrue(ok(fixture, node, "GET", "/user/recommendation-profile/preferences", null, reader)
                        .toString().contains(row.prefix + "restored"));
                assertEquals(0, ok(fixture, node, "GET", "/notification/unread/count", null, reader).path("data").asLong(-1));
                var notices = ok(fixture, node, "GET", "/notification/list", null, reader);
                for (long id : row.notices) assertTrue(notices.toString().contains(Long.toString(id)));
            }
        }
        System.out.println("Additional HTTP checkpoint: dedicated edits/deletes/cancellations/reads converged at both sites");
    }

    private static boolean converged(JdbcTemplate db, Rows r, long author) {
        if (count(db, "SELECT count(*) FROM hub WHERE id=? AND catalog_visible AND campus_id=1 AND name=?", r.hub, r.prefix + "hub-edited") != 1
                || count(db, "SELECT count(*) FROM hub WHERE id=? AND catalog_visible", r.deletedHub) != 0
                || count(db, "SELECT count(*) FROM restaurant WHERE id=? AND catalog_visible AND name=? AND hub_id=?", r.restaurant, r.prefix + "restaurant-edited", r.hub) != 1
                || count(db, "SELECT count(*) FROM restaurant WHERE id=? AND catalog_visible", r.deletedRestaurant) != 0
                || count(db, "SELECT count(*) FROM foods WHERE id=? AND catalog_visible AND meal_times=ARRAY['lunch','night']::text[] AND stage_color='chili' AND stage_alternates=ARRAY['mango','soy']::text[] AND cover_center_x=0.4 AND cover_center_y=0.55 AND cover_diameter=0.8 AND remove_background AND name=?", r.food, r.prefix + "food-edited") != 1
                || count(db, "SELECT count(*) FROM foods WHERE id=? AND catalog_visible", r.deletedFood) != 0
                || count(db, "SELECT count(*) FROM food_tags WHERE id=? AND catalog_visible AND name=?", r.systemTag, r.prefix + "system-edited") != 1
                || count(db, "SELECT count(*) FROM food_tags WHERE id=? AND catalog_visible AND name=?", r.customTag, r.prefix + "custom-edited") != 1
                || count(db, "SELECT count(*) FROM food_tags WHERE id IN (?,?) AND catalog_visible", r.deletedSystemTag, r.deletedCustomTag) != 0
                || count(db, "SELECT count(*) FROM activities WHERE code=? AND status='ONLINE' AND catalog_visible", r.prefix + "act") != 1
                || count(db, "SELECT count(*) FROM activity_posts WHERE id=? AND deleted_at IS NULL AND likes_count=0", r.activity) != 1
                || count(db, "SELECT count(*) FROM activity_posts WHERE id=? AND deleted_at IS NULL", r.deletedActivity) != 0
                || count(db, "SELECT count(*) FROM users WHERE id=? AND nick_name=? AND display_name=?", r.notificationOwner, r.notificationOwnerName, "昵称" + r.notificationOwnerName) != 1) return false;
        // A missing delete target must not masquerade as convergence: require its replicated durable identity/tombstone.
        for (var item : List.of(Map.entry("HUB", r.deletedHub), Map.entry("RESTAURANT", r.deletedRestaurant),
                Map.entry("FOOD", r.deletedFood), Map.entry("SYSTEM_TAG", r.deletedSystemTag), Map.entry("CUSTOM_TAG", r.deletedCustomTag)))
            if (count(db, "SELECT count(*) FROM eatwhat_sync.catalog_object WHERE kind=? AND numeric_id=? AND tombstone", item.getKey(), item.getValue()) != 1) return false;
        if (count(db, "SELECT count(*) FROM activity_posts WHERE id=? AND deleted_at IS NOT NULL", r.deletedActivity) != 1) return false;
        if (count(db, "SELECT count(*) FROM follow WHERE target_id=? AND account_id IN (?,?)", r.notificationOwner, author, r.adminId) != 0
                || count(db, "SELECT count(*) FROM food_taggings WHERE food_id=? AND account_id=?", r.food, author) != 0
                || count(db, "SELECT count(*) FROM likes WHERE target_type='food' AND target_id=? AND account_id=? AND deleted_at IS NULL", r.food, author) != 0
                || count(db, "SELECT count(*) FROM food_favorites WHERE food_id=? AND account_id=?", r.food, author) != 0
                || count(db, "SELECT count(*) FROM activity_likes WHERE target_type='post' AND activity_id=? AND account_id=? AND deleted_at IS NULL", r.activity, author) != 0) return false;
        for (long id : r.notices)
            if (count(db, "SELECT count(*) FROM notifications WHERE id=? AND account_id=? AND read_at IS NOT NULL", id, r.notificationOwner) != 1) return false;
        // Receipt of remove operations, rather than mere absent rows, proves that cancellations crossed the partition.
        for (UUID operation : r.cancellationOperations)
            if (count(db, "SELECT count(*) FROM eatwhat_sync.relation_operation WHERE event_id=? AND NOT adding", operation) != 1) return false;
        return true;
    }

    private static long count(JdbcTemplate db, String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }
    private static Map<String, Object> hubBody(String name, double x, double y) {
        String polygon = "{\"type\":\"Polygon\",\"coordinates\":[[[" + (x-.01) + "," + (y-.01) + "],[" + (x+.01) + "," + (y-.01) + "],[" + (x+.01) + "," + (y+.01) + "],[" + (x-.01) + "," + (y+.01) + "],[" + (x-.01) + "," + (y-.01) + "]]]}";
        return Map.of("name", name, "gcjLng", x, "gcjLat", y, "boundaryGeoJson", polygon, "campusId", 1);
    }
    private static long hub(ClusterSeamlessIT f, int n, String token, String name, double x, double y) throws Exception {
        ok(f, n, "POST", "/hub/add", hubBody(name, x, y), token);
        return f.databases.get(n).admin().queryForObject("SELECT id FROM hub WHERE name=?", Long.class, name);
    }
    private static long restaurant(ClusterSeamlessIT f, int n, String token, String name, double x, double y) throws Exception {
        return ok(f, n, "POST", "/restaurant/add", Map.of("name", name, "address", "dedicated isolated fixture", "gcjLng", x, "gcjLat", y), token).path("data").path("id").asLong();
    }
    private static long food(ClusterSeamlessIT f, int n, String token, long restaurant, String name, String key) throws Exception {
        return ok(f, n, "POST", "/foods/upload", Map.of("restaurantId", restaurant, "name", name, "price", 1.5, "pictureUrl", List.of(key)), token).path("data").path("id").asLong();
    }
    private static long systemTag(ClusterSeamlessIT f, int n, String token, String name) throws Exception {
        return ok(f, n, "POST", "/admin/food-tags", Map.of("name", name), token).path("data").path("id").asLong();
    }
    private static long customTag(ClusterSeamlessIT f, int n, String token, long author, long food, String name) throws Exception {
        ok(f, n, "POST", "/foods/" + food + "/tags/custom", Map.of("names", List.of(name)), token);
        return f.databases.get(n).admin().queryForObject("SELECT id FROM food_tags WHERE owner_id=? AND name=?", Long.class, author, name);
    }
    private static void onlineActivity(ClusterSeamlessIT f, int n, String adminToken, String code) throws Exception {
        long id = ok(f, n, "POST", "/admin/activities", Map.of("code", code, "name", code, "fieldSchema", List.of()), adminToken)
                .path("data").path("id").asLong();
        ok(f, n, "POST", "/admin/activities/" + id + "/status", Map.of("status", "ONLINE"), adminToken);
    }
    private static long post(ClusterSeamlessIT f, int n, String token, String code, String name, String key) throws Exception {
        var db = f.databases.get(n).admin();
        int province = db.queryForObject("SELECT min(id) FROM provinces", Integer.class);
        int city = db.queryForObject("SELECT min(id) FROM cities WHERE province_id=?", Integer.class, province);
        return ok(f, n, "POST", "/activity/" + code + "/posts", Map.of("title", name, "description", name, "provinceId", province,
                "cityId", city, "pictureUrl", List.of(key)), token).path("data").path("id").asLong();
    }
    private static String upload(ClusterSeamlessIT f, int n, String biz, String token) throws Exception {
        var bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        var data = ok(f, n, "POST", "/upload/presign", Map.of("biz", biz, "fileName", "additional.png", "contentType", "image/png", "fileSize", bytes.size()), token).path("data");
        long started = System.nanoTime();
        var response = f.http.send(HttpRequest.newBuilder(URI.create(data.path("putUrl").asText())).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
        assertTrue(System.nanoTime()-started < Duration.ofSeconds(5).toNanos(), "Object PUT exceeded offline deadline");
        String key = data.path("key").asText();
        ok(f, n, "POST", "/upload/complete", Map.of("key", key), token);
        return key;
    }
    private static JsonNode ok(ClusterSeamlessIT f, int n, String method, String path, Object body, String token) throws Exception {
        long started = System.nanoTime();
        var response = f.request(n, method, path, body, token);
        assertEquals(200, response.statusCode(), method + " " + path + ": " + response.body());
        var json = ClusterSeamlessIT.JSON.readTree(response.body());
        assertEquals(0, json.path("code").asInt(-1), method + " " + path + ": " + response.body());
        assertTrue(System.nanoTime()-started < Duration.ofSeconds(5).toNanos(), "HTTP deadline exceeded: " + method + " " + path);
        return json;
    }
}
