package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.data.dto.UserInfoDTO;
import you.v50to.eatwhat.data.dto.OtherUserInfoDTO;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    @Select("""
            SELECT
                u.id,
                u.nick_name AS userName,
                u.avatar,
                CAST(EXTRACT(EPOCH FROM u.created_at) * 1000 AS bigint) AS createdAt,
                CAST(EXTRACT(EPOCH FROM u.updated_at) * 1000 AS bigint) AS updatedAt,
                c.email,
                c.phone,
                v.verified,
                v.method,
                v.student_id AS studentId,
                v.real_name AS realName,
                v.verified_email AS verifiedEmail
            FROM users u
            LEFT JOIN contacts c ON c.account_id = u.id
            LEFT JOIN verifications v ON v.account_id = u.id
            WHERE u.id = #{userId}
            LIMIT 1
            """)
    UserInfoDTO selectUserInfoById(@Param("userId") Long userId);

    /**
     * 查询用户获得的总点赞数
     * @param userId 用户ID
     * @return 总点赞数
     */
    @Select("""
            SELECT COALESCE(
                (SELECT SUM(likes_count) FROM activity_foods WHERE account_id = #{userId} AND deleted_at IS NULL), 0
            ) + COALESCE(
                (SELECT SUM(likes_count) FROM activity_dinners WHERE account_id = #{userId} AND deleted_at IS NULL), 0
            ) AS total
            """)
    Integer countEarnedLikes(@Param("userId") Long userId);

    /**
     * 获取其他用户的完整信息
     * @param userId 目标用户ID
     * @param currentUserId 当前登录用户ID
     * @return 其他用户信息
     */
    @Select("""
            SELECT
                u.nick_name AS userName,
                u.avatar,
                COALESCE(
                    (SELECT SUM(likes_count) FROM activity_foods WHERE account_id = #{userId} AND deleted_at IS NULL), 0
                ) + COALESCE(
                    (SELECT SUM(likes_count) FROM activity_dinners WHERE account_id = #{userId} AND deleted_at IS NULL), 0
                ) AS earnedLikes,
                (SELECT COUNT(*) FROM follow WHERE target_id = #{userId}) AS fansCount,
                (SELECT COUNT(*) FROM follow WHERE account_id = #{userId}) AS followingsCount,
                COALESCE((SELECT follower FROM privacy WHERE account_id = #{userId}), true) AS fansVisible,
                COALESCE((SELECT following FROM privacy WHERE account_id = #{userId}), true) AS followingsVisible,
                EXISTS(SELECT 1 FROM follow WHERE account_id = #{userId} AND target_id = #{currentUserId}) AS isFollowingMe,
                EXISTS(SELECT 1 FROM follow WHERE account_id = #{currentUserId} AND target_id = #{userId}) AS isFollowingHim
            FROM users u
            WHERE u.id = #{userId}
            LIMIT 1
            """)
    OtherUserInfoDTO selectOtherUserInfo(@Param("userId") Long userId, @Param("currentUserId") Long currentUserId);

    @Select("""
            SELECT
                u.id,
                u.nick_name AS userName,
                u.avatar,
                u.role,
                u.banned,
                u.ban_reason AS banReason,
                CAST(EXTRACT(EPOCH FROM u.banned_at) * 1000 AS bigint) AS bannedAt,
                CAST(EXTRACT(EPOCH FROM u.created_at) * 1000 AS bigint) AS createdAt,
                CAST(EXTRACT(EPOCH FROM u.updated_at) * 1000 AS bigint) AS updatedAt,
                c.email,
                c.phone,
                v.verified,
                v.method,
                v.student_id AS studentId,
                v.real_name AS realName,
                v.verified_email AS verifiedEmail
            FROM users u
            LEFT JOIN contacts c ON c.account_id = u.id
            LEFT JOIN verifications v ON v.account_id = u.id
            ORDER BY u.id ASC
            """)
    IPage<UserInfoDTO> selectUserPage(IPage<UserInfoDTO> page);

    @Select("SELECT COUNT(*) FROM users")
    Long countTotal();

    @Select("SELECT COUNT(*) FROM users WHERE created_at >= CURRENT_DATE")
    Long countToday();

    @Select("""
            SELECT CASE WHEN COUNT(*) = 0 THEN 0
                        ELSE ROUND(COUNT(v.id) * 1.0 / COUNT(u.id), 4)
                   END
            FROM users u
            LEFT JOIN verifications v ON v.account_id = u.id AND v.verified = true
            """)
    Double selectVerifiedRate();
}
