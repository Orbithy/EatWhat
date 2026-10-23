package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.po.Activity;

@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {

    /** 可见的活动按 code 查找；同步冲突中被隐藏的副本不算。 */
    @Select("""
            SELECT id FROM activities
            WHERE code = #{code} AND catalog_visible
            LIMIT 1
            """)
    Long selectVisibleIdByCode(@Param("code") String code);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM activities WHERE id = #{id} AND catalog_visible)
            """)
    boolean visible(@Param("id") Long id);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM activity_posts WHERE activity_id = #{id})
            """)
    boolean hasPosts(@Param("id") Long id);
}
