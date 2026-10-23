package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import you.v50to.eatwhat.data.po.ActivityPost;
import you.v50to.eatwhat.utils.JsonbTypeHandler;
import you.v50to.eatwhat.utils.StringArrayTypeHandler;
import you.v50to.eatwhat.utils.TimestampTypeHandler;

import java.util.List;

@Mapper
public interface ActivityPostMapper extends BaseMapper<ActivityPost> {

    @Select("""
            SELECT COUNT(*)
            FROM activity_posts
            WHERE activity_id = #{activityId} AND deleted_at IS NULL
            """)
    Long countActive(@Param("activityId") Long activityId);

    /** 按时间倒序或按点赞数排序分页；byLikes 为 true 时点赞多的在前。 */
    @Select("""
            <script>
            SELECT p.*, COALESCE(u.display_name, u.nick_name) AS uploaderName
            FROM activity_posts p
            JOIN users u ON u.id = p.account_id
            WHERE p.activity_id = #{activityId} AND p.deleted_at IS NULL
            <choose>
              <when test="byLikes">ORDER BY p.likes_count DESC, p.created_at DESC, p.id DESC</when>
              <otherwise>ORDER BY p.created_at DESC, p.id DESC</otherwise>
            </choose>
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @Results(id = "activityPost", value = {
            @Result(property = "pictureUrl", column = "picture_url", typeHandler = StringArrayTypeHandler.class),
            @Result(property = "extra", column = "extra", typeHandler = JsonbTypeHandler.class),
            @Result(property = "deletedAt", column = "deleted_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "uploaderName", column = "uploaderName")
    })
    List<ActivityPost> selectActivePage(@Param("activityId") Long activityId,
                                        @Param("byLikes") boolean byLikes,
                                        @Param("offset") Integer offset,
                                        @Param("limit") Integer limit);

    @Select("""
            SELECT p.*, COALESCE(u.display_name, u.nick_name) AS uploaderName
            FROM activity_posts p
            JOIN users u ON u.id = p.account_id
            WHERE p.id = #{id} AND p.deleted_at IS NULL
            LIMIT 1
            """)
    @ResultMap("activityPost")
    ActivityPost selectActiveById(@Param("id") Long id);
}
