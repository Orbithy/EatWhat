package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import you.v50to.eatwhat.data.enums.FoodCategory;
import you.v50to.eatwhat.utils.StringArrayTypeHandler;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@TableName(value = "foods", autoResultMap = true)
public class Food {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    private Long restaurantId;

    private String name;

    private String description;

    private BigDecimal price;

    private FoodCategory category;

    @TableField(value = "picture_url", typeHandler = StringArrayTypeHandler.class)
    private String[] pictureUrl;

    private Integer likesCount;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}

