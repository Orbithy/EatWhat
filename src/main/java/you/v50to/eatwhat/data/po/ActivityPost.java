package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import you.v50to.eatwhat.utils.JsonbTypeHandler;
import you.v50to.eatwhat.utils.StringArrayTypeHandler;
import you.v50to.eatwhat.utils.TimestampTypeHandler;

/** 用户在某个活动下发布的内容。 */
@Data
@NoArgsConstructor
@TableName(value = "activity_posts", autoResultMap = true)
public class ActivityPost {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long activityId;
    private Long accountId;
    @TableField(exist = false)
    private String uploaderName;
    private String title;
    private String description;
    private Integer provinceId;
    private Integer cityId;
    @TableField(value = "picture_url", typeHandler = StringArrayTypeHandler.class)
    private String[] pictureUrl;
    /** 扩展字段的值，JSON 对象文本 */
    @TableField(value = "extra", typeHandler = JsonbTypeHandler.class)
    private String extra;
    private Integer likesCount;
    @TableField(value = "deleted_at", typeHandler = TimestampTypeHandler.class)
    private Long deletedAt;
    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;
    @TableField(value = "updated_at", typeHandler = TimestampTypeHandler.class)
    private Long updatedAt;
}
