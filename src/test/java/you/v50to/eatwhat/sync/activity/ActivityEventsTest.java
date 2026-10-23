package you.v50to.eatwhat.sync.activity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ActivityEventsTest {
    private static final UUID GID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AUTHOR = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static ActivityEvents.Created food() {
        return new ActivityEvents.Created(1_000_000_003L, GID, "campus-test", AUTHOR, "dumpling", "good",
                1, 10, List.of("activity/a.png", "activity/b.png"), 1_700_000_000_123L);
    }

    @Test void foodAndDinnerRoundTripThroughTheCanonicalPayload() {
        var created = food();
        assertEquals(created, ActivityEvents.parseCreated(ActivityEvents.FOODS, ActivityEvents.createdPayload(ActivityEvents.FOODS, created)));
        var dinner = new ActivityEvents.Created(1_000_000_005L, GID, "campus-test", AUTHOR, null, null, null, null,
                List.of("activity/d.png"), 1_700_000_000_000L);
        assertEquals(dinner, ActivityEvents.parseCreated(ActivityEvents.DINNERS, ActivityEvents.createdPayload(ActivityEvents.DINNERS, dinner)));
        var noLocation = new ActivityEvents.Created(1_000_000_007L, GID, "campus-test", AUTHOR, "plain", null, null, null,
                List.of("activity/p.png"), 1_700_000_000_000L);
        assertEquals(noLocation, ActivityEvents.parseCreated(ActivityEvents.FOODS, ActivityEvents.createdPayload(ActivityEvents.FOODS, noLocation)));
    }

    @Test void activityPostsCarryTheirDefinitionTitleAndExtraFields() {
        UUID activity = UUID.fromString("33333333-3333-4333-8333-333333333333");
        var post = new ActivityEvents.Created(1_000_000_009L, GID, "campus-test", AUTHOR, null, "fried", 1, 10,
                List.of("activity/p.png"), 1_700_000_000_000L, activity, "spring roll", "{\"dish\":\"roll\",\"spicy\":true}");
        String payload = ActivityEvents.createdPayload(ActivityEvents.POSTS, post);
        assertEquals(post, ActivityEvents.parseCreated(ActivityEvents.POSTS, payload));
        assertFalse(payload.contains("food_name"));
        var untitled = new ActivityEvents.Created(1_000_000_011L, GID, "campus-test", AUTHOR, null, null, null, null,
                List.of("activity/q.png"), 1_700_000_000_000L, activity, null, "{}");
        assertEquals(untitled, ActivityEvents.parseCreated(ActivityEvents.POSTS, ActivityEvents.createdPayload(ActivityEvents.POSTS, untitled)));
        // A post without its definition, or with a non-object extra, is not a valid post.
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseCreated(ActivityEvents.POSTS,
                payload.replace("\"activity_global_id\":\"" + activity + "\",", "")));
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseCreated(ActivityEvents.POSTS,
                payload.replace("\"extra\":{\"dish\":\"roll\",\"spicy\":true}", "\"extra\":[1]")));
        // Food payloads are not posts and posts are not foods.
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseCreated(ActivityEvents.FOODS, payload));
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseCreated(ActivityEvents.POSTS,
                ActivityEvents.createdPayload(ActivityEvents.FOODS, food())));
        String huge = "{\"note\":\"" + "x".repeat(ActivityEvents.MAX_EXTRA_BYTES) + "\"}";
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseCreated(ActivityEvents.POSTS,
                ActivityEvents.createdPayload(ActivityEvents.POSTS, new ActivityEvents.Created(1_000_000_013L, GID, "campus-test",
                        AUTHOR, null, null, null, null, List.of("activity/r.png"), 1_700_000_000_000L, activity, null, huge))));
    }

    @Test void derivedOrSensitiveFieldsNeverAppearInTheWirePayload() {
        String payload = ActivityEvents.createdPayload(ActivityEvents.FOODS, food());
        for (String forbidden : List.of("likes_count", "updated_at", "deleted_at", "account_id", "uploader", "http"))
            assertFalse(payload.contains(forbidden), forbidden);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ",\"likes_count\":99", ",\"deleted_at\":1700000000000", ",\"account_id\":1000000001", ",\"extra\":true"})
    void unknownFieldsAreRejected(String extra) {
        String valid = ActivityEvents.createdPayload(ActivityEvents.FOODS, food());
        String tampered = valid.substring(0, valid.length() - 1) + extra + "}";
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseCreated(ActivityEvents.FOODS, tampered));
    }

    @Test void foodOnlyFieldsAreRejectedOnADinnerAndRequiredFieldsMustExist() {
        String foodPayload = ActivityEvents.createdPayload(ActivityEvents.FOODS, food());
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseCreated(ActivityEvents.DINNERS, foodPayload));
        String missingName = foodPayload.replace("\"food_name\":\"dumpling\",", "");
        assertNotEquals(foodPayload, missingName);
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseCreated(ActivityEvents.FOODS, missingName));
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseCreated("users", foodPayload));
    }

    @Test void identityPictureAndTextBoundsAreEnforced() {
        var base = food();
        assertThrows(IllegalArgumentException.class, () -> parse(new ActivityEvents.Created(1_000_000_000L, GID, "s", AUTHOR, "n", null, null, null, List.of("activity/a"), 1)));
        assertThrows(IllegalArgumentException.class, () -> parse(new ActivityEvents.Created(base.numericId(), GID, "s", AUTHOR, "n", null, null, null, List.of(), 1)));
        assertThrows(IllegalArgumentException.class, () -> parse(new ActivityEvents.Created(base.numericId(), GID, "s", AUTHOR, "n", null, null, null,
                java.util.Collections.nCopies(10, "activity/a.png"), 1)));
        assertThrows(IllegalArgumentException.class, () -> parse(new ActivityEvents.Created(base.numericId(), GID, "s", AUTHOR, "n", null, null, null, List.of("other/a.png"), 1)));
        assertThrows(IllegalArgumentException.class, () -> parse(new ActivityEvents.Created(base.numericId(), GID, "s", AUTHOR, "n", null, null, null, List.of("activity/http://x"+"\n"), 1)));
        assertThrows(IllegalArgumentException.class, () -> parse(new ActivityEvents.Created(base.numericId(), GID, "s", AUTHOR, " ", null, null, null, List.of("activity/a.png"), 1)));
        assertThrows(IllegalArgumentException.class, () -> parse(new ActivityEvents.Created(base.numericId(), GID, "s", AUTHOR, "x".repeat(65), null, null, null, List.of("activity/a.png"), 1)));
        assertThrows(IllegalArgumentException.class, () -> parse(new ActivityEvents.Created(base.numericId(), GID, "s", AUTHOR, "n", "d".repeat(2001), null, null, List.of("activity/a.png"), 1)));
        assertThrows(IllegalArgumentException.class, () -> parse(new ActivityEvents.Created(base.numericId(), GID, "s", AUTHOR, "n", null, 0, null, List.of("activity/a.png"), 1)));
        assertThrows(IllegalArgumentException.class, () -> parse(new ActivityEvents.Created(base.numericId(), new UUID(0, 0), "s", AUTHOR, "n", null, null, null, List.of("activity/a.png"), 1)));
        assertThrows(IllegalArgumentException.class, () -> parse(new ActivityEvents.Created(base.numericId(), GID, "s", AUTHOR, "n", null, null, null, List.of("activity/a.png"), 0)));
        assertDoesNotThrow(() -> parse(new ActivityEvents.Created(base.numericId(), GID, "s", AUTHOR, "x".repeat(64), "d".repeat(2000), null, null,
                java.util.Collections.nCopies(9, "activity/a.png"), 1)));
    }

    @Test void malformedAndNonCanonicalInputIsRejectedWithoutEchoingPayloads() {
        for (String bad : List.of("", "[]", "null", "{", "{\"numeric_id\":\"1000000003\"}")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseCreated(ActivityEvents.FOODS, bad));
            assertFalse(failure.getMessage() != null && failure.getMessage().contains("1000000003"));
        }
        UUID lettered = UUID.fromString("abcdefab-abcd-4abc-8abc-abcdefabcdef");
        var created = new ActivityEvents.Created(1_000_000_003L, lettered, "campus-test", AUTHOR, "dumpling", null, null, null,
                List.of("activity/a.png"), 1_700_000_000_123L);
        String canonical = ActivityEvents.createdPayload(ActivityEvents.FOODS, created);
        assertDoesNotThrow(() -> ActivityEvents.parseCreated(ActivityEvents.FOODS, canonical));
        String upper = canonical.replace(lettered.toString(), lettered.toString().toUpperCase());
        assertNotEquals(canonical, upper);
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseCreated(ActivityEvents.FOODS, upper));
    }

    @Test void deletionPayloadHasExactlyOneBoundedField() {
        assertEquals(1_700_000_000_123L, ActivityEvents.parseDeleted(ActivityEvents.deletedPayload(1_700_000_000_123L)));
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.deletedPayload(0));
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseDeleted("{\"deleted_at\":1,\"reason\":\"x\"}"));
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseDeleted("{\"deleted_at\":-5}"));
        assertThrows(IllegalArgumentException.class, () -> ActivityEvents.parseDeleted("{}"));
    }

    private static ActivityEvents.Created parse(ActivityEvents.Created created) {
        return ActivityEvents.parseCreated(ActivityEvents.FOODS, ActivityEvents.createdPayload(ActivityEvents.FOODS, created));
    }
}
