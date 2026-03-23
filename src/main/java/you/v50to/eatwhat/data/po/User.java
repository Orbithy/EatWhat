package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@TableName("users")
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
    private OffsetDateTime bannedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
