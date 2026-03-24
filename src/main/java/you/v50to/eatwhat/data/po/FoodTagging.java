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
@TableName(value = "food_taggings", autoResultMap = true)
public class FoodTagging {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long foodId;

    private Long tagId;

    private Long accountId;

    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;
}
