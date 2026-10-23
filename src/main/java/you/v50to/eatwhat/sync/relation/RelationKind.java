package you.v50to.eatwhat.sync.relation;

/** Target kinds are explicit: a directory food is never an activity food. */
public enum RelationKind {
    food_like("likes", "foods", "target_id", "food"),
    activity_food_like("activity_likes", "activity_foods", "activity_id", "food"),
    activity_dinner_like("activity_likes", "activity_dinners", "activity_id", "dinner"),
    favorite("food_favorites", "foods", "food_id", null),
    follow("follow", "users", "target_id", null),
    tagging("food_taggings", "foods", "food_id", null),
    // Appended last: relation backfill jobs persist the kind's ordinal as their phase.
    activity_post_like("activity_likes", "activity_posts", "activity_id", "post");

    public final String table, targetTable, targetColumn, targetType;
    RelationKind(String table, String targetTable, String targetColumn, String targetType) {
        this.table=table; this.targetTable=targetTable; this.targetColumn=targetColumn; this.targetType=targetType;
    }
}
