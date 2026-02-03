package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.data.vo.UserInfoVO;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    @Select("""
            SELECT
                u.id,
                u.nick_name AS userName,
                u.avatar,
                u.created_at AS createdAt,
                u.updated_at AS updatedAt,
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
    UserInfoVO selectUserInfoById(@Param("userId") Long userId);
}
