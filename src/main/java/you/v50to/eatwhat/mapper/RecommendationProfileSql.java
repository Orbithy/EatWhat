package you.v50to.eatwhat.mapper;

/** Profile inspection and ranking deliberately share the complete feature calculation. */
public final class RecommendationProfileSql {
    private RecommendationProfileSql() { }

    public static String selectProfile() {
        return FoodRecommendationSql.profileCtes() + """
                , profile_rows(dimension, feature_key, label, weight, tagging_weight,
                               like_weight, favorite_weight, browse_weight, manual_weight) AS (
                    SELECT 'taste', normalized_name, normalized_name, weight,
                           tagging_weight, like_weight, favorite_weight, browse_weight, manual_weight
                    FROM tag_preferences
                    UNION ALL
                    SELECT 'category', category::text, category::text, weight,
                           0.0, like_weight, favorite_weight, browse_weight, 0.0 FROM category_preferences
                    UNION ALL
                    SELECT 'restaurant', p.restaurant_id::text, r.name, p.weight,
                           0.0, p.like_weight, p.favorite_weight, p.browse_weight, 0.0
                    FROM restaurant_preferences p JOIN restaurant r ON r.id=p.restaurant_id
                    UNION ALL
                    SELECT 'manualLike', normalized_name, normalized_name, 25.0, 0.0, 0.0, 0.0, 0.0, 25.0 FROM liked_tastes
                    UNION ALL
                    SELECT 'manualDislike', normalized_name, normalized_name, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0 FROM disliked_tastes
                    UNION ALL
                    SELECT 'signal', 'preferencesUpdatedAt', NULL, EXTRACT(EPOCH FROM updated_at)*1000,
                           0.0, 0.0, 0.0, 0.0, 0.0 FROM explicit_preferences
                    UNION ALL
                    SELECT 'signal', 'likes', NULL, count(*)::numeric, 0.0, 0.0, 0.0, 0.0, 0.0
                    FROM recent_likes l JOIN foods f ON f.id=l.food_id
                    UNION ALL
                    SELECT 'signal', 'favorites', NULL, count(*)::numeric, 0.0, 0.0, 0.0, 0.0, 0.0
                    FROM recent_favorites
                    UNION ALL
                    SELECT 'signal', 'taggings', NULL, count(*)::numeric, 0.0, 0.0, 0.0, 0.0, 0.0
                    FROM recent_taggings
                    UNION ALL
                    SELECT 'signal', 'browsedFoods', NULL, count(*)::numeric, 0.0, 0.0, 0.0, 0.0, 0.0
                    FROM recent_history h JOIN foods f ON f.id=h.target_id WHERE h.target_type='food'
                    UNION ALL
                    SELECT 'signal', 'browsedRestaurants', NULL, count(*)::numeric, 0.0, 0.0, 0.0, 0.0, 0.0
                    FROM recent_history h JOIN restaurant r ON r.id=h.target_id WHERE h.target_type='restaurant'
                    UNION ALL
                    SELECT 'signal', 'lastBehaviorAt', NULL,
                           EXTRACT(EPOCH FROM MAX(at))*1000, 0.0, 0.0, 0.0, 0.0, 0.0
                    FROM (
                        SELECT l.updated_at AS at FROM recent_likes l JOIN foods f ON f.id=l.food_id
                        UNION ALL SELECT created_at FROM recent_favorites
                        UNION ALL SELECT created_at FROM recent_taggings
                        UNION ALL SELECT viewed_at FROM recent_history h
                            JOIN foods f ON f.id=h.target_id WHERE h.target_type='food'
                        UNION ALL SELECT viewed_at FROM recent_history h
                            JOIN restaurant r ON r.id=h.target_id WHERE h.target_type='restaurant'
                    ) activity
                ), ordered_profile AS (
                    SELECT *, row_number() OVER (
                        PARTITION BY dimension ORDER BY weight DESC NULLS LAST, feature_key COLLATE "C"
                    ) AS position FROM profile_rows
                )
                SELECT dimension, feature_key, label, weight, tagging_weight,
                       like_weight, favorite_weight, browse_weight, manual_weight
                FROM ordered_profile WHERE dimension='signal' OR position <= 20
                ORDER BY dimension, position
                """;
    }
}
