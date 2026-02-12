package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@TableName("notifications")
public class Notification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private Long actorId;
    private String type;
    private String targetType;
    private Long targetId;
    private String data;
    private OffsetDateTime readAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime createdAt;
}
