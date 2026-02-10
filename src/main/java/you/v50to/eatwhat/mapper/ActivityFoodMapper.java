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
            WHERE id = #{id} AND deleted_at IS NULL
            LIMIT 1
            """)
    ActivityFood selectActiveFoodById(@Param("id") Long id);
}
