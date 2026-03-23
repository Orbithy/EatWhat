package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.data.po.FoodFavorite;

import java.util.List;

@Mapper
public interface FoodFavoriteMapper extends BaseMapper<FoodFavorite> {

    @Select("""
            <script>
            SELECT food_id
            FROM food_favorites
            WHERE account_id = #{accountId}
              AND food_id IN
              <foreach collection='foodIds' item='foodId' open='(' separator=',' close=')'>
                #{foodId}
              </foreach>
            </script>
            """)
    List<Long> selectFavoriteFoodIds(@Param("accountId") Long accountId,
                                     @Param("foodIds") List<Long> foodIds);

    @Select("""
            SELECT f.*, u.nick_name AS uploaderName
            FROM food_favorites ff
            JOIN foods f ON f.id = ff.food_id
            JOIN users u ON u.id = f.account_id
            WHERE ff.account_id = #{accountId}
            ORDER BY ff.created_at DESC, ff.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Food> selectFavoriteFoodsByAccountId(@Param("accountId") Long accountId,
                                              @Param("offset") int offset,
                                              @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM food_favorites WHERE account_id = #{accountId}")
    Long countFavoritesByAccountId(@Param("accountId") Long accountId);
}
