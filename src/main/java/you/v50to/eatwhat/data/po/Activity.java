package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import you.v50to.eatwhat.utils.JsonbTypeHandler;
import you.v50to.eatwhat.utils.TimestampTypeHandler;

/** 管理员维护的活动定义。 */
@Data
@NoArgsConstructor
@TableName(value = "activities", autoResultMap = true)
public class Activity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String coverUrl;
    /** DRAFT / ONLINE / OFFLINE */
    private String status;
    /** 发布时间窗（毫秒），只限制发内容，不影响读取 */
    @TableField(value = "starts_at", typeHandler = TimestampTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private Long startsAt;
    @TableField(value = "ends_at", typeHandler = TimestampTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private Long endsAt;
    private Integer sort;
    private Boolean requireLocation;
    private Integer maxPictures;
    /** 扩展字段定义，JSON 数组文本 */
    @TableField(value = "field_schema", typeHandler = JsonbTypeHandler.class)
    private String fieldSchema;
    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;
    @TableField(value = "updated_at", typeHandler = TimestampTypeHandler.class)
    private Long updatedAt;
}
