package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import you.v50to.eatwhat.data.po.ActivityFood;
import you.v50to.eatwhat.utils.StringArrayTypeHandler;
import you.v50to.eatwhat.utils.TimestampTypeHandler;

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
            SELECT f.*, u.nick_name AS uploaderName
            FROM activity_foods f
            JOIN users u ON u.id = f.account_id
            WHERE f.deleted_at IS NULL
            ORDER BY f.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @Results(value = {
            @Result(property = "pictureUrl", column = "picture_url", typeHandler = StringArrayTypeHandler.class),
            @Result(property = "deletedAt", column = "deleted_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "uploaderName", column = "uploaderName"),
            @Result(property = "isLiked", column = "is_liked")
    })
    List<ActivityFood> selectActiveFoods(@Param("offset") Integer offset,
                                         @Param("limit") Integer limit);

    @Select("""
            SELECT f.*, u.nick_name AS uploaderName
            FROM activity_foods f
            JOIN users u ON u.id = f.account_id
            WHERE f.deleted_at IS NULL
            ORDER BY f.likes_count DESC, f.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @Results(value = {
            @Result(property = "pictureUrl", column = "picture_url", typeHandler = StringArrayTypeHandler.class),
            @Result(property = "deletedAt", column = "deleted_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "uploaderName", column = "uploaderName")
    })
    List<ActivityFood> selectActiveFoodsOrderByLikes(@Param("offset") Integer offset,
                                                     @Param("limit") Integer limit);

    @Select("""
            SELECT f.*, u.nick_name AS uploaderName
            FROM activity_foods f
            JOIN users u ON u.id = f.account_id
            WHERE f.id = #{id} AND f.deleted_at IS NULL
            LIMIT 1
            """)
    @Results(value = {
            @Result(property = "pictureUrl", column = "picture_url", typeHandler = StringArrayTypeHandler.class),
            @Result(property = "deletedAt", column = "deleted_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "uploaderName", column = "uploaderName")
    })
    ActivityFood selectActiveFoodById(@Param("id") Long id);

    @Select("""
            SELECT f.*,
                   u.nick_name AS uploaderName,
                   CASE WHEN al.id IS NULL THEN FALSE ELSE TRUE END AS is_liked
            FROM activity_foods f
            JOIN users u ON u.id = f.account_id
            LEFT JOIN activity_likes al
              ON al.activity_id = f.id
             AND al.account_id = #{accountId}
              AND al.target_type = 'food'
              AND al.deleted_at IS NULL
            WHERE f.province_id = #{provinceId}
              AND f.deleted_at IS NULL
            ORDER BY f.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @Results(value = {
            @Result(property = "pictureUrl", column = "picture_url", typeHandler = StringArrayTypeHandler.class),
            @Result(property = "deletedAt", column = "deleted_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "uploaderName", column = "uploaderName"),
            @Result(property = "isLiked", column = "is_liked")
    })
    List<ActivityFood> selectFoodsByProvinceWithLiked(@Param("provinceId") Integer provinceId,
                                                      @Param("accountId") Long accountId,
                                                      @Param("offset") Integer offset,
                                                      @Param("limit") Integer limit);

    @Select("SELECT COUNT(*)" +
            " FROM activity_foods" +
            " WHERE province_id = #{provinceId} AND deleted_at IS NULL")
    Long countActiveFoodsByProvince(@Param("provinceId") Integer provinceId);
}
