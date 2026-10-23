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
    /** 验证码注册、还没设过密码的账号存这个前缀加随机串，任何密码都匹配不上。 */
    public static final String UNSET_PASSWORD_PREFIX = "!unset:";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 内部唯一登录名：旧账号的用户名，手机号注册账号是随机句柄。对外展示用 displayName。 */
    @TableField("nick_name")
    private String userName;

    /** 昵称：可中文、不唯一；为空时展示 userName。 */
    @TableField("display_name")
    private String displayName;

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

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.startsWith(UNSET_PASSWORD_PREFIX);
    }
}
