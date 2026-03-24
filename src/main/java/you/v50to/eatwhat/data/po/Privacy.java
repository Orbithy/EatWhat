package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import you.v50to.eatwhat.utils.TimestampTypeHandler;

@Data
@NoArgsConstructor
@TableName(value = "privacy", autoResultMap = true)
public class Privacy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private Boolean following;
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private Boolean follower;
    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;
}
