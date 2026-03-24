package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import you.v50to.eatwhat.data.po.FoodTag;
import you.v50to.eatwhat.data.vo.AdminFoodTagVO;
import you.v50to.eatwhat.data.vo.FoodTagSummaryVO;
import you.v50to.eatwhat.data.vo.MyCustomTagVO;
import you.v50to.eatwhat.utils.TimestampTypeHandler;

import java.util.List;

@Mapper
public interface FoodTagMapper extends BaseMapper<FoodTag> {

    @Select("""
            <script>
            SELECT id, name
            FROM food_tags
            WHERE tag_type = 'system'
            ORDER BY name ASC, id ASC
            </script>
            """)
    List<FoodTagSummaryVO> selectSystemTagSummaries();

    @Select("""
            <script>
            SELECT *
            FROM food_tags
            WHERE tag_type = 'system'
              AND id IN
              <foreach collection='ids' item='id' open='(' separator=',' close=')'>
                #{id}
              </foreach>
            </script>
            """)
    @Results(value = {
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class)
    })
    List<FoodTag> selectSystemTagsByIds(@Param("ids") List<Long> ids);

    @Select("""
            <script>
            SELECT *
            FROM food_tags
            WHERE tag_type = 'custom'
              AND owner_id = #{ownerId}
              AND normalized_name IN
              <foreach collection='normalizedNames' item='name' open='(' separator=',' close=')'>
                #{name}
              </foreach>
            </script>
            """)
    @Results(value = {
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class)
    })
    List<FoodTag> selectCustomTagsByOwnerAndNormalizedNames(@Param("ownerId") Long ownerId,
                                                            @Param("normalizedNames") List<String> normalizedNames);

    @Select("""
            <script>
            SELECT
              ft.id,
              ft.name,
              COUNT(ftg.id) AS usageCount,
              EXTRACT(EPOCH FROM ft.created_at) * 1000 AS createdAt,
              EXTRACT(EPOCH FROM ft.updated_at) * 1000 AS updatedAt
            FROM food_tags ft
            LEFT JOIN food_taggings ftg ON ftg.tag_id = ft.id
            WHERE ft.tag_type = 'system'
            GROUP BY ft.id
            ORDER BY ft.created_at DESC, ft.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AdminFoodTagVO> selectSystemTagsForAdmin(@Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM food_tags
            WHERE tag_type = 'system'
            """)
    Long countSystemTags();

    @Select("""
            SELECT
              ft.id,
              ft.name,
              COUNT(ftg.id) AS usageCount
            FROM food_tags ft
            LEFT JOIN food_taggings ftg ON ftg.tag_id = ft.id AND ftg.account_id = #{ownerId}
            WHERE ft.tag_type = 'custom'
              AND ft.owner_id = #{ownerId}
            GROUP BY ft.id, ft.name
            ORDER BY ft.created_at DESC, ft.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<MyCustomTagVO> selectCustomTagsByOwner(@Param("ownerId") Long ownerId,
                                                @Param("offset") int offset,
                                                @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM food_tags
            WHERE tag_type = 'custom'
              AND owner_id = #{ownerId}
            """)
    Long countCustomTagsByOwner(@Param("ownerId") Long ownerId);
}
