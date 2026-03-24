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
@TableName("browse_history")
public class BrowseHistory {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    private String targetType;

    private Long targetId;

    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;
}
