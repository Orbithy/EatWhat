package you.v50to.eatwhat.identity;

import java.util.Arrays;
import java.util.List;

/** Database namespaces, deliberately distinct from ambiguous API target types such as "food". */
public enum EntityType {
    USERS("users"), CONTACTS("contacts"), VERIFICATIONS("verifications"),
    ACTIVITY_FOODS("activity_foods"), ACTIVITY_DINNERS("activity_dinners"),
    ACTIVITY_LIKES("activity_likes"), NOTIFICATIONS("notifications"), FOLLOW("follow"),
    PRIVACY("privacy"), HUB("hub"), RESTAURANT("restaurant"), FOODS("foods"),
    BROWSE_HISTORY("browse_history"), FOOD_TAGS("food_tags"), FOOD_TAGGINGS("food_taggings"),
    FOOD_FAVORITES("food_favorites"), LIKES("likes"), ACTIVITIES("activities"), ACTIVITY_POSTS("activity_posts"),
    USER_INFO("user_info");

    private final String tableName;

    EntityType(String tableName) {
        this.tableName = tableName;
    }

    public String tableName() {
        return tableName;
    }

    /** user_info is an optional extension of the user, not a separately allocated identity. */
    public EntityType identityType() {
        return this == USER_INFO ? USERS : this;
    }

    public static List<EntityType> independentTypes() {
        return Arrays.stream(values()).filter(type -> type != USER_INFO).toList();
    }

    public static EntityType fromTableName(String tableName) {
        return Arrays.stream(values()).filter(type -> type.tableName.equals(tableName))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported entity type"));
    }
}
