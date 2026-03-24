package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import you.v50to.eatwhat.data.po.ActivityDinner;
import you.v50to.eatwhat.utils.StringArrayTypeHandler;
import you.v50to.eatwhat.utils.TimestampTypeHandler;

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
    @Results(value = {
            @Result(property = "pictureUrl", column = "picture_url", typeHandler = StringArrayTypeHandler.class),
            @Result(property = "deletedAt", column = "deleted_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class)
    })
    List<ActivityDinner> selectActiveDinners(@Param("offset") Integer offset,
                                             @Param("limit") Integer limit);

    @Select("""
            SELECT *
            FROM activity_dinners
            WHERE deleted_at IS NULL
            ORDER BY likes_count DESC, created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @Results(value = {
            @Result(property = "pictureUrl", column = "picture_url", typeHandler = StringArrayTypeHandler.class),
            @Result(property = "deletedAt", column = "deleted_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class)
    })
    List<ActivityDinner> selectActiveDinnersOrderByLikes(@Param("offset") Integer offset,
                                                         @Param("limit") Integer limit);

    @Select("""
            SELECT *
            FROM activity_dinners
            WHERE id = #{id} AND deleted_at IS NULL
            LIMIT 1
            """)
    @Results(value = {
            @Result(property = "pictureUrl", column = "picture_url", typeHandler = StringArrayTypeHandler.class),
            @Result(property = "deletedAt", column = "deleted_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class)
    })
    ActivityDinner selectActiveDinnerById(@Param("id") Long id);
}
