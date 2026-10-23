package you.v50to.eatwhat.mapper;

/** Shared, request-time taste profile and content-based ranking from local business data. */
public final class FoodRecommendationSql {
    private FoodRecommendationSql() { }

    static String profileCtes() {
        return """
                WITH explicit_preferences AS (
                    SELECT p.* FROM public.user_food_preferences p WHERE account_id=#{accountId}
                ), liked_tastes AS (
                    SELECT unnest(liked_tags) AS normalized_name FROM explicit_preferences
                ), disliked_tastes AS (
                    SELECT unnest(disliked_tags) AS normalized_name FROM explicit_preferences
                ), recent_likes AS (
                    SELECT target_id AS food_id, 3.0 AS weight, updated_at
                    FROM likes
                    WHERE account_id = #{accountId} AND target_type = 'food' AND deleted_at IS NULL
                    ORDER BY updated_at DESC, id DESC LIMIT 200
                ), recent_favorites AS (
                    SELECT food_id, 5.0 AS weight, created_at
                    FROM food_favorites
                    WHERE account_id = #{accountId}
                    ORDER BY created_at DESC, id DESC LIMIT 200
                ), recent_taggings AS (
                    SELECT t.normalized_name, g.created_at
                    FROM food_taggings g JOIN food_tags t ON t.id = g.tag_id
                    WHERE g.account_id = #{accountId} AND t.catalog_visible
                      AND EXISTS (SELECT 1 FROM foods f JOIN restaurant r ON r.id=f.restaurant_id WHERE f.id=g.food_id AND f.catalog_visible AND r.catalog_visible)
                      AND (t.tag_type = 'system' OR (t.tag_type = 'custom' AND t.owner_id = #{accountId}))
                    ORDER BY g.created_at DESC, g.id DESC LIMIT 200
                ), history_events AS (
                    (SELECT target_type, target_id, created_at
                     FROM browse_history
                     WHERE account_id = #{accountId} AND target_type = 'food'
                       AND created_at >= now() - interval '30 days'
                     ORDER BY created_at DESC, sync_visit_id DESC NULLS LAST, id DESC LIMIT 200)
                    UNION ALL
                    (SELECT target_type, target_id, created_at
                     FROM browse_history
                     WHERE account_id = #{accountId} AND target_type = 'restaurant'
                       AND created_at >= now() - interval '30 days'
                     ORDER BY created_at DESC, sync_visit_id DESC NULLS LAST, id DESC LIMIT 200)
                ), recent_history AS (
                    SELECT target_type, target_id, MAX(created_at) AS viewed_at,
                           1.0 / (1.0 + GREATEST(0, EXTRACT(EPOCH FROM (now() - MAX(created_at)))) / 604800.0) AS weight
                    FROM history_events GROUP BY target_type, target_id
                ), food_signals AS (
                    SELECT s.food_id, SUM(s.weight) AS weight, SUM(s.like_weight) AS like_weight,
                           SUM(s.favorite_weight) AS favorite_weight, SUM(s.browse_weight) AS browse_weight
                    FROM (
                        SELECT food_id, weight, weight AS like_weight, 0.0 AS favorite_weight, 0.0 AS browse_weight FROM recent_likes
                        UNION ALL SELECT food_id, weight, 0.0, weight, 0.0 FROM recent_favorites
                        UNION ALL SELECT target_id, weight, 0.0, 0.0, weight FROM recent_history WHERE target_type = 'food'
                    ) s JOIN foods f ON f.id = s.food_id JOIN restaurant r ON r.id=f.restaurant_id
                    WHERE f.catalog_visible AND r.catalog_visible
                    GROUP BY s.food_id
                ), signal_tags AS (
                    SELECT DISTINCT s.food_id, t.normalized_name, s.weight,
                           s.like_weight, s.favorite_weight, s.browse_weight
                    FROM food_signals s
                    JOIN food_taggings g ON g.food_id = s.food_id
                    JOIN food_tags t ON t.id = g.tag_id
                    WHERE t.catalog_visible AND (t.tag_type = 'system'
                       OR (t.tag_type = 'custom' AND t.owner_id = #{accountId} AND g.account_id = #{accountId}))
                ), tag_preferences AS (
                    SELECT normalized_name, LEAST(25.0, SUM(weight)) AS weight,
                           SUM(tagging_weight) AS tagging_weight, SUM(like_weight) AS like_weight,
                           SUM(favorite_weight) AS favorite_weight, SUM(browse_weight) AS browse_weight,
                           SUM(manual_weight) AS manual_weight
                    FROM (
                        SELECT normalized_name, 4.0 AS weight, 4.0 AS tagging_weight,
                               0.0 AS like_weight, 0.0 AS favorite_weight, 0.0 AS browse_weight,
                               0.0 AS manual_weight FROM recent_taggings
                        UNION ALL SELECT normalized_name, weight, 0.0, like_weight, favorite_weight, browse_weight, 0.0 FROM signal_tags
                        UNION ALL SELECT normalized_name, 25.0, 0.0, 0.0, 0.0, 0.0, 25.0 FROM liked_tastes
                    ) p WHERE NOT EXISTS(SELECT 1 FROM disliked_tastes d WHERE d.normalized_name=p.normalized_name)
                    GROUP BY normalized_name
                ), category_preferences AS (
                    SELECT f.category, LEAST(10.0, SUM(s.weight)) AS weight,
                           SUM(s.like_weight) AS like_weight, SUM(s.favorite_weight) AS favorite_weight,
                           SUM(s.browse_weight) AS browse_weight
                    FROM food_signals s JOIN foods f ON f.id = s.food_id
                    WHERE f.category IS NOT NULL GROUP BY f.category
                ), restaurant_preferences AS (
                    SELECT restaurant_id, LEAST(10.0, SUM(weight)) AS weight,
                           SUM(like_weight) AS like_weight, SUM(favorite_weight) AS favorite_weight,
                           SUM(browse_weight) AS browse_weight
                    FROM (
                        SELECT f.restaurant_id, s.weight, s.like_weight, s.favorite_weight, s.browse_weight FROM food_signals s JOIN foods f ON f.id = s.food_id
                        UNION ALL
                        SELECT target_id, weight, 0.0, 0.0, weight FROM recent_history WHERE target_type = 'restaurant'
                    ) p JOIN restaurant r ON r.id=p.restaurant_id AND r.catalog_visible GROUP BY restaurant_id
                )
                """;
    }

    /**
     * 推荐候选（产品文档第 8 节）：当前校区内、可见、有封面的菜，按个人排序，带个人分、质量分和两个后置标记。
     * 饭点分层、多样性、个人因素占比和当天去重在 FoodRecommender 里做。accountId 为空时个人分全为 0。
     */
    public static String selectCandidates(java.util.Map<String, Object> params) {
        String category = params.get("category") == null ? ""
                : "\n                  AND f.category = #{category}";
        return profileCtes() + """
                , matching_tags AS (
                    SELECT DISTINCT g.food_id, p.normalized_name, p.weight
                    FROM tag_preferences p
                    JOIN food_tags t ON t.normalized_name = p.normalized_name
                    JOIN food_taggings g ON g.tag_id = t.id
                    WHERE t.catalog_visible AND (t.tag_type = 'system'
                       OR (t.tag_type = 'custom' AND t.owner_id = #{accountId} AND g.account_id = #{accountId}))
                ), tag_scores AS (
                    SELECT food_id, LEAST(25.0, SUM(weight)) AS weight
                    FROM matching_tags GROUP BY food_id
                )
                SELECT * FROM (
                SELECT f.*, COALESCE(u.display_name, u.nick_name) AS uploaderName, r.name AS restaurantName,
                       r.address AS restaurant_address, r.gcj_lng AS restaurant_gcj_lng, r.gcj_lat AS restaurant_gcj_lat,
                       r.hub_id AS hub_id, hb.name AS hub_name,
                       (4.0 * COALESCE(ts.weight, 0) + 2.0 * COALESCE(cp.weight, 0) + COALESCE(rp.weight, 0)) AS personal_score,
                       -- 赞多、口味标签多的菜优先；新发布的菜一周内有加分，避免一直被老菜压住。
                       (3.0 * ln(1 + GREATEST(f.likes_count, 0))
                        + 1.5 * ln(1 + (SELECT count(*) FROM food_taggings g JOIN food_tags t ON t.id = g.tag_id
                                        WHERE g.food_id = f.id AND t.tag_type = 'system' AND t.catalog_visible))
                        + 0.5 * GREATEST(0, 7 - EXTRACT(EPOCH FROM (now() - f.created_at)) / 86400.0)) AS quality_score,
                       (known_like.id IS NOT NULL OR known_favorite.id IS NOT NULL) AS own_mark,
                       COALESCE(h.viewed_at >= now() - interval '1 day', false) AS viewed_mark
                FROM foods f
                JOIN users u ON u.id = f.account_id
                JOIN restaurant r ON r.id = f.restaurant_id
                LEFT JOIN hub hb ON hb.id = r.hub_id
                LEFT JOIN tag_scores ts ON ts.food_id = f.id
                LEFT JOIN category_preferences cp ON cp.category = f.category
                LEFT JOIN restaurant_preferences rp ON rp.restaurant_id = f.restaurant_id
                LEFT JOIN likes known_like ON known_like.account_id = #{accountId}
                    AND known_like.target_type = 'food' AND known_like.target_id = f.id AND known_like.deleted_at IS NULL
                LEFT JOIN food_favorites known_favorite ON known_favorite.account_id = #{accountId} AND known_favorite.food_id = f.id
                LEFT JOIN recent_history h ON h.target_type = 'food' AND h.target_id = f.id
                WHERE f.catalog_visible AND r.catalog_visible AND f.picture_url IS NOT NULL AND cardinality(f.picture_url) > 0
                  -- 没有归属校区的商圈、不属于任何商圈的店算在默认校区下面。
                  AND (hb.campus_id = #{campusId} OR (#{defaultCampus} AND (r.hub_id IS NULL OR hb.campus_id IS NULL)))"""
                + category + """

                  AND NOT EXISTS (
                      SELECT 1 FROM food_taggings g JOIN food_tags t ON t.id=g.tag_id
                      JOIN disliked_tastes d ON d.normalized_name=t.normalized_name
                      WHERE g.food_id=f.id AND t.catalog_visible AND (t.tag_type='system'
                          OR (t.tag_type='custom' AND t.owner_id=#{accountId} AND g.account_id=#{accountId}))
                  )
                ) candidate
                -- 候选按个人排序给出：自己赞过收藏过的、一天内看过的往后放，然后是个人分和热度。
                ORDER BY own_mark, viewed_mark, personal_score DESC, likes_count DESC, updated_at DESC, id DESC
                LIMIT #{limit}
                """;
    }
}
