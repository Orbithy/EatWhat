package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.dto.BrowseHistoryFoodRow;
import you.v50to.eatwhat.data.dto.BrowseHistoryRestaurantRow;
import you.v50to.eatwhat.data.po.BrowseHistory;
import you.v50to.eatwhat.utils.StringArrayTypeHandler;

@Mapper
public interface BrowseHistoryMapper extends BaseMapper<BrowseHistory> {

    @Insert("""
            INSERT INTO browse_history (account_id, target_type, target_id, created_at)
            SELECT #{accountId}, #{targetType}, #{targetId}, now()
            WHERE NOT EXISTS (
                SELECT 1
                FROM browse_history
                WHERE account_id = #{accountId}
                  AND target_type = #{targetType}
                  AND target_id = #{targetId}
                  AND created_at >= now() - interval '5 minutes'
            )
            """)
    int insertIfNotRecent(@Param("accountId") Long accountId,
                          @Param("targetType") String targetType,
                          @Param("targetId") Long targetId);

    @org.apache.ibatis.annotations.Results(id = "browseHistoryRestaurantRowMap", value = {
            @org.apache.ibatis.annotations.Result(property = "restaurantPictureUrl", column = "restaurant_picture_url", typeHandler = StringArrayTypeHandler.class)
    })
    @Select("""
            SELECT
                bh.target_type AS targetType,
                bh.target_id AS targetId,
                CAST(EXTRACT(EPOCH FROM bh.created_at) * 1000 AS bigint) AS viewedAt,
                r.id AS restaurantId,
                r.name AS restaurantName,
                r.picture_url AS restaurant_picture_url
            FROM browse_history bh
            JOIN restaurant r ON r.id = bh.target_id
            WHERE bh.account_id = #{accountId}
              AND bh.target_type = 'restaurant'
            ORDER BY bh.created_at DESC, bh.id DESC
            """)
    IPage<BrowseHistoryRestaurantRow> selectRestaurantHistoryPage(IPage<BrowseHistoryRestaurantRow> page,
                                                                  @Param("accountId") Long accountId);

    @org.apache.ibatis.annotations.Results(id = "browseHistoryFoodRowMap", value = {
            @org.apache.ibatis.annotations.Result(property = "foodPictureUrl", column = "food_picture_url", typeHandler = StringArrayTypeHandler.class)
    })
    @Select("""
            SELECT
                bh.target_type AS targetType,
                bh.target_id AS targetId,
                CAST(EXTRACT(EPOCH FROM bh.created_at) * 1000 AS bigint) AS viewedAt,
                f.id AS foodId,
                f.name AS foodName,
                f.picture_url AS food_picture_url,
                f.likes_count AS foodLikesCount
            FROM browse_history bh
            JOIN foods f ON f.id = bh.target_id
            WHERE bh.account_id = #{accountId}
              AND bh.target_type = 'food'
            ORDER BY bh.created_at DESC, bh.id DESC
            """)
    IPage<BrowseHistoryFoodRow> selectFoodHistoryPage(IPage<BrowseHistoryFoodRow> page,
                                                      @Param("accountId") Long accountId);
}
