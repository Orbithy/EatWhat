package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.utils.StringArrayTypeHandler;
import you.v50to.eatwhat.utils.TimestampTypeHandler;

import java.util.List;
import java.util.Map;

@Mapper
public interface FoodMapper extends BaseMapper<Food> {

    @Select("""
            SELECT f.*, u.nick_name AS uploaderName
            FROM foods f
            JOIN users u ON u.id = f.account_id
            WHERE f.restaurant_id = #{restaurantId}
            ORDER BY f.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @Results(value = {
            @Result(property = "uploaderName", column = "uploaderName"),
            @Result(property = "pictureUrl", column = "picture_url", typeHandler = StringArrayTypeHandler.class),
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class)
    })
    List<Food> selectByRestaurantId(@Param("restaurantId") Long restaurantId,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    @SelectProvider(type = FoodSqlProvider.class, method = "selectByRestaurantIdWithFilters")
    @Results(value = {
            @Result(property = "uploaderName", column = "uploaderName"),
            @Result(property = "pictureUrl", column = "picture_url", typeHandler = StringArrayTypeHandler.class),
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class)
    })
    List<Food> selectByRestaurantIdWithFilters(@Param("restaurantId") Long restaurantId,
                                               @Param("offset") int offset,
                                               @Param("limit") int limit,
                                               @Param("currentUserId") Long currentUserId,
                                               @Param("systemTagIds") List<Long> systemTagIds,
                                               @Param("myCustomTagIds") List<Long> myCustomTagIds,
                                               @Param("myCustomTagNormalizedNames") List<String> myCustomTagNormalizedNames);

    @Select("""
            SELECT f.*, u.nick_name AS uploaderName
            FROM foods f
            JOIN users u ON u.id = f.account_id
            WHERE f.account_id = #{accountId}
            ORDER BY f.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @Results(value = {
            @Result(property = "uploaderName", column = "uploaderName"),
            @Result(property = "pictureUrl", column = "picture_url", typeHandler = StringArrayTypeHandler.class),
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class)
    })
    List<Food> selectByAccountId(@Param("accountId") Long accountId,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM foods WHERE restaurant_id = #{restaurantId}")
    Long countByRestaurantId(@Param("restaurantId") Long restaurantId);

    @SelectProvider(type = FoodSqlProvider.class, method = "countByRestaurantIdWithFilters")
    Long countByRestaurantIdWithFilters(@Param("restaurantId") Long restaurantId,
                                        @Param("currentUserId") Long currentUserId,
                                        @Param("systemTagIds") List<Long> systemTagIds,
                                        @Param("myCustomTagIds") List<Long> myCustomTagIds,
                                        @Param("myCustomTagNormalizedNames") List<String> myCustomTagNormalizedNames);

    @Select("SELECT COUNT(*) FROM foods WHERE account_id = #{accountId}")
    Long countByAccountId(@Param("accountId") Long accountId);

    @Select("SELECT COUNT(*) FROM foods")
    Long countTotal();

    @Select("SELECT COUNT(*) FROM foods WHERE created_at >= CURRENT_DATE")
    Long countToday();

    class FoodSqlProvider {

        public String selectByRestaurantIdWithFilters(Map<String, Object> params) {
            StringBuilder sql = new StringBuilder("""
                    <script>
                    SELECT f.*, u.nick_name AS uploaderName
                    FROM foods f
                    JOIN users u ON u.id = f.account_id
                    WHERE f.restaurant_id = #{restaurantId}
                    """);
            appendTagFilters(sql, params);
            sql.append("""
                    
                    ORDER BY f.created_at DESC
                    LIMIT #{limit} OFFSET #{offset}
                    </script>
                    """);
            return sql.toString();
        }

        public String countByRestaurantIdWithFilters(Map<String, Object> params) {
            StringBuilder sql = new StringBuilder("""
                    <script>
                    SELECT COUNT(*)
                    FROM foods f
                    WHERE f.restaurant_id = #{restaurantId}
                    """);
            appendTagFilters(sql, params);
            sql.append("""
                    </script>
                    """);
            return sql.toString();
        }

        @SuppressWarnings("unchecked")
        private void appendTagFilters(StringBuilder sql, Map<String, Object> params) {
            List<Long> systemTagIds = (List<Long>) params.get("systemTagIds");
            List<Long> myCustomTagIds = (List<Long>) params.get("myCustomTagIds");
            List<String> myCustomTagNormalizedNames = (List<String>) params.get("myCustomTagNormalizedNames");

            if (systemTagIds != null && !systemTagIds.isEmpty()) {
                sql.append("""
                        
                        AND (
                          SELECT COUNT(DISTINCT ft.id)
                          FROM food_taggings ftg
                          JOIN food_tags ft ON ft.id = ftg.tag_id
                          WHERE ftg.food_id = f.id
                            AND ft.tag_type = 'system'
                            AND ft.id IN
                            <foreach collection='systemTagIds' item='tagId' open='(' separator=',' close=')'>
                              #{tagId}
                            </foreach>
                        ) = """).append(systemTagIds.size());
            }

            if (myCustomTagIds != null && !myCustomTagIds.isEmpty()) {
                sql.append("""
                        
                        AND (
                          SELECT COUNT(DISTINCT ft.id)
                          FROM food_taggings ftg
                          JOIN food_tags ft ON ft.id = ftg.tag_id
                          WHERE ftg.food_id = f.id
                            AND ftg.account_id = #{currentUserId}
                            AND ft.tag_type = 'custom'
                            AND ft.id IN
                            <foreach collection='myCustomTagIds' item='tagId' open='(' separator=',' close=')'>
                              #{tagId}
                            </foreach>
                        ) = """).append(myCustomTagIds.size());
            }

            if (myCustomTagNormalizedNames != null && !myCustomTagNormalizedNames.isEmpty()) {
                sql.append("""
                        
                        AND (
                          SELECT COUNT(DISTINCT ft.normalized_name)
                          FROM food_taggings ftg
                          JOIN food_tags ft ON ft.id = ftg.tag_id
                          WHERE ftg.food_id = f.id
                            AND ftg.account_id = #{currentUserId}
                            AND ft.tag_type = 'custom'
                            AND ft.normalized_name IN
                            <foreach collection='myCustomTagNormalizedNames' item='tagName' open='(' separator=',' close=')'>
                              #{tagName}
                            </foreach>
                        ) = """).append(myCustomTagNormalizedNames.size());
            }
        }
    }
}
