package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.po.Food;

import java.util.List;

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
    List<Food> selectByRestaurantId(@Param("restaurantId") Long restaurantId,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM foods WHERE restaurant_id = #{restaurantId}")
    Long countByRestaurantId(@Param("restaurantId") Long restaurantId);

    @Select("SELECT COUNT(*) FROM foods")
    Long countTotal();

    @Select("SELECT COUNT(*) FROM foods WHERE created_at >= CURRENT_DATE")
    Long countToday();
}

