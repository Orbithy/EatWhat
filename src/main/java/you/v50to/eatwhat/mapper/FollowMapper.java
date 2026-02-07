package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.dto.FansDTO;
import you.v50to.eatwhat.data.po.Follow;

import java.util.List;

@Mapper
public interface FollowMapper extends BaseMapper<Follow> {
    
    /**
     * 查询某个用户的粉丝列表（分页）
     * @param targetId 被关注者的用户ID
     * @param offset 偏移量
     * @param limit 每页大小
     * @return 粉丝列表
     */
    @Select("""
            SELECT
                u.id,
                u.nick_name AS userName,
                u.avatar,
                CAST(EXTRACT(EPOCH FROM f.created_at) * 1000 AS bigint) AS createdAt
            FROM follow f
            INNER JOIN users u ON f.account_id = u.id
            WHERE f.target_id = #{targetId}
            ORDER BY f.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<FansDTO> selectFollowersByTargetId(@Param("targetId") Long targetId,
                                            @Param("offset") Integer offset,
                                            @Param("limit") Integer limit);

    /**
     * 查询某个用户的关注列表（分页）
     * @param accountId 用户ID
     * @param offset 偏移量
     * @param limit 每页大小
     * @return 关注列表
     */
    @Select("""
            SELECT
                u.id,
                u.nick_name AS userName,
                u.avatar,
                CAST(EXTRACT(EPOCH FROM f.created_at) * 1000 AS bigint) AS createdAt
            FROM follow f
            INNER JOIN users u ON f.target_id = u.id
            WHERE f.account_id = #{accountId}
            ORDER BY f.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<FansDTO> selectFollowingsByAccountId(@Param("accountId") Long accountId,
                                               @Param("offset") Integer offset,
                                               @Param("limit") Integer limit);

    /**
     * 统计某个用户的粉丝数量
     * @param targetId 被关注者的用户ID
     * @return 粉丝数量
     */
    @Select("""
            SELECT COUNT(*)
            FROM follow
            WHERE target_id = #{targetId}
            """)
    Long countFollowersByTargetId(@Param("targetId") Long targetId);

    /**
     * 统计某个用户的关注数量
     * @param accountId 用户ID
     * @return 关注数量
     */
    @Select("""
            SELECT COUNT(*)
            FROM follow
            WHERE account_id = #{accountId}
            """)
    Long countFollowingsByAccountId(@Param("accountId") Long accountId);
}

