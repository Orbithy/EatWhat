package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import you.v50to.eatwhat.utils.TimestampTypeHandler;

@Data
@NoArgsConstructor
@TableName(value = "notifications", autoResultMap = true)
public class Notification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private Long actorId;
    private String type;
    private String targetType;
    private Long targetId;
    private String data;
    @TableField(value = "read_at", typeHandler = TimestampTypeHandler.class)
    private Long readAt;
    @TableField(value = "expires_at", typeHandler = TimestampTypeHandler.class)
    private Long expiresAt;
    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;
}
