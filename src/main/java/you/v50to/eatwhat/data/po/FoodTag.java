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
@TableName(value = "food_tags", autoResultMap = true)
public class FoodTag {
    @com.baomidou.mybatisplus.annotation.TableField(value="catalog_visible",
        insertStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
        updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean catalogVisible;


    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String tagType;

    private Long ownerId;

    private String normalizedName;

    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;

    @TableField(value = "updated_at", typeHandler = TimestampTypeHandler.class)
    private Long updatedAt;
}
