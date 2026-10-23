package you.v50to.eatwhat.mapper;

import java.util.Map;
import you.v50to.eatwhat.data.dto.FoodQueryDTO;

/** The page and its count deliberately share every join and predicate. Sorts are allowlisted. */
public final class FoodQuerySql {
    private FoodQuerySql() { }

    public static String select(Map<String, Object> params) {
        FoodQueryDTO query = (FoodQueryDTO) params.get("query");
        boolean favorites = Boolean.TRUE.equals(params.get("favoritesOnly"));
        FoodQueryDTO.Sort sort = query.getSort() == null
                ? (favorites ? FoodQueryDTO.Sort.FAVORITED_AT : FoodQueryDTO.Sort.NEWEST) : query.getSort();
        String ordering = switch (sort) {
            case NEWEST -> "f.created_at DESC, f.id DESC";
            case PRICE_ASC -> "f.price ASC NULLS LAST, f.id DESC";
            case PRICE_DESC -> "f.price DESC NULLS LAST, f.id DESC";
            case POPULAR -> "f.likes_count DESC NULLS LAST, f.id DESC";
            case FAVORITED_AT -> "ff.created_at DESC, ff.id DESC";
        };
        return "<script>SELECT f.*, COALESCE(u.display_name, u.nick_name) AS uploaderName, r.name AS restaurantName "
                + candidates() + " ORDER BY " + ordering + " LIMIT #{limit} OFFSET #{offset}</script>";
    }

    public static String count(Map<String, Object> params) {
        return "<script>SELECT COUNT(*) " + candidates() + "</script>";
    }

    private static String candidates() {
        return """
            FROM foods f
            JOIN users u ON u.id = f.account_id
            JOIN restaurant r ON r.id = f.restaurant_id
            <if test="favoritesOnly">
              JOIN food_favorites ff ON ff.food_id = f.id AND ff.account_id = #{currentUserId}
            </if>
            WHERE f.catalog_visible AND r.catalog_visible
            <if test="query.restaurantId != null">AND f.restaurant_id = #{query.restaurantId}</if>
            <if test="query.hubId != null">AND r.hub_id = #{query.hubId}</if>
            <if test="query.keyword != null and query.keyword.trim() != ''">
              AND strpos(lower(f.name), lower(trim(#{query.keyword}))) &gt; 0
            </if>
            <if test="query.category != null">AND f.category = #{query.category.value}</if>
            <if test="query.minPrice != null">AND f.price &gt;= #{query.minPrice}</if>
            <if test="query.maxPrice != null">AND f.price &lt;= #{query.maxPrice}</if>
            <if test="query.systemTagIds != null and !query.systemTagIds.isEmpty()">
              <bind name="systemCount" value="query.systemTagIds.size()"/>
              AND (SELECT COUNT(DISTINCT ft.id) FROM food_taggings g
                JOIN food_tags ft ON ft.id = g.tag_id
                WHERE g.food_id = f.id AND ft.catalog_visible AND ft.tag_type = 'system'
                AND ft.id IN
                <foreach collection="query.systemTagIds" item="tag" open="(" separator="," close=")">#{tag}</foreach>
              ) = #{systemCount}
            </if>
            <if test="query.myCustomTagIds != null and !query.myCustomTagIds.isEmpty()">
              <bind name="customCount" value="query.myCustomTagIds.size()"/>
              AND (SELECT COUNT(DISTINCT ft.id) FROM food_taggings g
                JOIN food_tags ft ON ft.id = g.tag_id
                WHERE g.food_id = f.id AND ft.catalog_visible AND ft.tag_type = 'custom'
                AND g.account_id = #{currentUserId} AND ft.owner_id = #{currentUserId}
                AND ft.id IN
                <foreach collection="query.myCustomTagIds" item="tag" open="(" separator="," close=")">#{tag}</foreach>
              ) = #{customCount}
            </if>
            <if test="tagNames != null and !tagNames.isEmpty()">
              <bind name="nameCount" value="tagNames.size()"/>
              AND (SELECT COUNT(DISTINCT ft.normalized_name) FROM food_taggings g
                JOIN food_tags ft ON ft.id = g.tag_id
                WHERE g.food_id = f.id AND ft.catalog_visible AND ft.tag_type = 'custom'
                AND g.account_id = #{currentUserId} AND ft.owner_id = #{currentUserId}
                AND ft.normalized_name IN
                <foreach collection="tagNames" item="tag" open="(" separator="," close=")">#{tag}</foreach>
              ) = #{nameCount}
            </if>
            """;
    }
}
