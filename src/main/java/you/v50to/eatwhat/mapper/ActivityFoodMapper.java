package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.po.ActivityFood;

import java.util.List;

@Mapper
public interface ActivityFoodMapper extends BaseMapper<ActivityFood> {

    @Select("""
            SELECT COUNT(*)
            FROM activity_foods
            WHERE deleted_at IS NULL
            """)
    Long countActiveFoods();

    @Select("""
            SELECT *
            FROM activity_foods
            WHERE deleted_at IS NULL
            ORDER BY created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ActivityFood> selectActiveFoods(@Param("offset") Integer offset,
                                         @Param("limit") Integer limit);

    @Select("""
            SELECT *
            FROM activity_foods
            WHERE deleted_at IS NULL
            ORDER BY likes_count DESC, created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ActivityFood> selectActiveFoodsOrderByLikes(@Param("offset") Integer offset,
                                                     @Param("limit") Integer limit);

    @Select("""
            SELECT *
            FROM activity_foods
            WHERE id = #{id} AND deleted_at IS NULL
            LIMIT 1
            """)
    ActivityFood selectActiveFoodById(@Param("id") Long id);

    @Select("""
            SELECT f.*,
                   CASE WHEN al.id IS NULL THEN FALSE ELSE TRUE END AS is_liked
            FROM activity_foods f
            LEFT JOIN activity_likes al
              ON al.activity_id = f.id
             AND al.account_id = #{accountId}
             AND al.target_type = 'food'
             AND al.deleted_at IS NULL
            WHERE f.province_id = #{provinceId}
              AND f.deleted_at IS NULL
            ORDER BY f.created_at
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ActivityFood> selectFoodsByProvinceWithLiked(@Param("provinceId") Integer provinceId,
                                                      @Param("accountId") Long accountId,
                                                      @Param("offset") Integer offset,
                                                      @Param("limit") Integer limit);

    @Select("SELECT COUNT(*)" +
            "FROM activity_foods " +
            "WHERE province_id = #{provinceId}")
    Long countActiveFoodsByProvince(@Param("provinceId") Integer provinceId);
}
