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
@TableName(value = "likes", autoResultMap = true)
public class LikeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    private String targetType;

    private Long targetId;

    @TableField(value = "deleted_at", typeHandler = TimestampTypeHandler.class)
    private Long deletedAt;

    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;

    @TableField(value = "updated_at", typeHandler = TimestampTypeHandler.class)
    private Long updatedAt;
}
