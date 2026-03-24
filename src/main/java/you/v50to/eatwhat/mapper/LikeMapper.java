package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.po.LikeRecord;

import java.util.List;

@Mapper
public interface LikeMapper extends BaseMapper<LikeRecord> {

    @Select("""
            <script>
            SELECT target_id
            FROM likes
            WHERE account_id = #{accountId}
              AND target_type = #{targetType}
              AND deleted_at IS NULL
              AND target_id IN
              <foreach collection='targetIds' item='targetId' open='(' separator=',' close=')'>
                #{targetId}
              </foreach>
            </script>
            """)
    List<Long> selectLikedTargetIds(@Param("accountId") Long accountId,
                                    @Param("targetType") String targetType,
                                    @Param("targetIds") List<Long> targetIds);
}
