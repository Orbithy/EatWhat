package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.po.ActivityDinner;

import java.util.List;

@Mapper
public interface ActivityDinnerMapper extends BaseMapper<ActivityDinner> {

    @Select("""
            SELECT COUNT(*)
            FROM activity_dinners
            WHERE deleted_at IS NULL
            """)
    Long countActiveDinners();

    @Select("""
            SELECT *
            FROM activity_dinners
            WHERE deleted_at IS NULL
            ORDER BY created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ActivityDinner> selectActiveDinners(@Param("offset") Integer offset,
                                             @Param("limit") Integer limit);

    @Select("""
            SELECT *
            FROM activity_dinners
            WHERE id = #{id} AND deleted_at IS NULL
            LIMIT 1
            """)
    ActivityDinner selectActiveDinnerById(@Param("id") Long id);
}
