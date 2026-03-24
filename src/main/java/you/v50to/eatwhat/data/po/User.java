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
@TableName(value = "users", autoResultMap = true)
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("nick_name")
    private String userName;

    private String passwordHash;
    private String avatar;
    private String role;
    private Boolean banned;
    private String banReason;
    @TableField(value = "banned_at", typeHandler = TimestampTypeHandler.class)
    private Long bannedAt;
    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;
    @TableField(value = "updated_at", typeHandler = TimestampTypeHandler.class)
    private Long updatedAt;
}
