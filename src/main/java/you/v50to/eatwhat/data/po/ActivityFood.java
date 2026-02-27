package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import you.v50to.eatwhat.utils.StringArrayTypeHandler;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@TableName(value = "activity_foods", autoResultMap = true)
public class ActivityFood {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    @TableField(exist = false)
    private String uploaderName;
    private String foodName;
    private String description;
    private Integer provinceId;
    private Integer cityId;
    @TableField(value = "picture_url", typeHandler = StringArrayTypeHandler.class)
    private String[] pictureUrl;
    private Integer likesCount;
    @TableField(exist = false)
    private Boolean isLiked;
    private OffsetDateTime deletedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
