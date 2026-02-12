package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;
import you.v50to.eatwhat.data.dto.NotificationDTO;
import you.v50to.eatwhat.data.po.Notification;

import java.util.List;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    @Insert("""
            INSERT INTO notifications(account_id, actor_id, type, target_type, target_id, data)
            VALUES (#{accountId}, #{actorId}, #{type}, #{targetType}, #{targetId}, CAST(#{data} AS jsonb))
            """)
    Integer insertNotification(@Param("accountId") Long accountId,
                               @Param("actorId") Long actorId,
                               @Param("type") String type,
                               @Param("targetType") String targetType,
                               @Param("targetId") Long targetId,
                               @Param("data") String data);

    @Select("""
            SELECT
                n.id,
                n.actor_id AS actorId,
                u.nick_name AS actorName,
                u.avatar AS actorAvatar,
                n.type,
                n.target_type AS targetType,
                n.target_id AS targetId,
                n.data::text AS data,
                (n.read_at IS NOT NULL) AS read,
                CAST(EXTRACT(EPOCH FROM n.created_at) * 1000 AS bigint) AS createdAt
            FROM notifications n
            INNER JOIN users u ON u.id = n.actor_id
            WHERE n.account_id = #{accountId}
              AND n.expires_at > now()
            ORDER BY n.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<NotificationDTO> selectByAccountId(@Param("accountId") Long accountId,
                                            @Param("offset") Integer offset,
                                            @Param("limit") Integer limit);

    @Select("""
            SELECT COUNT(*)
            FROM notifications
            WHERE account_id = #{accountId}
              AND expires_at > now()
            """)
    Long countByAccountId(@Param("accountId") Long accountId);

    @Select("""
            SELECT COUNT(*)
            FROM notifications
            WHERE account_id = #{accountId}
              AND read_at IS NULL
              AND expires_at > now()
            """)
    Long countUnreadByAccountId(@Param("accountId") Long accountId);

    @Update("""
            UPDATE notifications
            SET read_at = now()
            WHERE id = #{id}
              AND account_id = #{accountId}
              AND read_at IS NULL
            """)
    Integer markOneAsRead(@Param("accountId") Long accountId,
                          @Param("id") Long id);

    @Update("""
            UPDATE notifications
            SET read_at = now()
            WHERE account_id = #{accountId}
              AND read_at IS NULL
            """)
    Integer markAllAsRead(@Param("accountId") Long accountId);
}
