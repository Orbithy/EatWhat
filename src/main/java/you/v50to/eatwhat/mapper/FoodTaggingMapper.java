package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.dto.FoodCustomTagRow;
import you.v50to.eatwhat.data.dto.FoodSystemTagAggregateRow;
import you.v50to.eatwhat.data.po.FoodTagging;

import java.util.List;

@Mapper
public interface FoodTaggingMapper extends BaseMapper<FoodTagging> {

    @Delete("""
            DELETE FROM food_taggings
            WHERE food_id = #{foodId}
              AND tag_id = #{tagId}
              AND account_id = #{accountId}
            """)
    int deleteMyTagging(@Param("foodId") Long foodId,
                        @Param("tagId") Long tagId,
                        @Param("accountId") Long accountId);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM food_taggings
            WHERE food_id = #{foodId}
              AND account_id = #{accountId}
              AND tag_id IN
              <foreach collection='tagIds' item='tagId' open='(' separator=',' close=')'>
                #{tagId}
              </foreach>
            </script>
            """)
    Long countExistingTaggings(@Param("foodId") Long foodId,
                               @Param("accountId") Long accountId,
                               @Param("tagIds") List<Long> tagIds);

    @Select("""
            <script>
            SELECT
              ftg.food_id AS foodId,
              ft.id AS tagId,
              ft.name AS tagName,
              COUNT(*) AS tagCount,
              BOOL_OR(ftg.account_id = #{accountId}) AS taggedByMe
            FROM food_taggings ftg
            JOIN food_tags ft ON ft.id = ftg.tag_id
            WHERE ft.tag_type = 'system'
              AND ftg.food_id IN
              <foreach collection='foodIds' item='foodId' open='(' separator=',' close=')'>
                #{foodId}
              </foreach>
            GROUP BY ftg.food_id, ft.id, ft.name
            ORDER BY ftg.food_id ASC, COUNT(*) DESC, ft.id ASC
            </script>
            """)
    List<FoodSystemTagAggregateRow> aggregateSystemTagsByFoodIds(@Param("foodIds") List<Long> foodIds,
                                                                 @Param("accountId") Long accountId);

    @Select("""
            <script>
            SELECT
              ftg.food_id AS foodId,
              ft.id AS tagId,
              ft.name AS tagName
            FROM food_taggings ftg
            JOIN food_tags ft ON ft.id = ftg.tag_id
            WHERE ft.tag_type = 'custom'
              AND ftg.account_id = #{accountId}
              AND ftg.food_id IN
              <foreach collection='foodIds' item='foodId' open='(' separator=',' close=')'>
                #{foodId}
              </foreach>
            ORDER BY ftg.food_id ASC, ft.created_at ASC, ft.id ASC
            </script>
            """)
    List<FoodCustomTagRow> selectMyCustomTagsByFoodIds(@Param("foodIds") List<Long> foodIds,
                                                       @Param("accountId") Long accountId);

    @Delete("""
            DELETE FROM food_taggings
            WHERE tag_id = #{tagId}
              AND account_id = #{accountId}
            """)
    int deleteAllTaggingsByTagId(@Param("tagId") Long tagId,
                                 @Param("accountId") Long accountId);
}
