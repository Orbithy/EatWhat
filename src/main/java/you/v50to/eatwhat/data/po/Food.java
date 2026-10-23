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
import you.v50to.eatwhat.utils.TimestampTypeHandler;

@Data
@NoArgsConstructor
@TableName(value = "foods", autoResultMap = true)
public class Food {
    @com.baomidou.mybatisplus.annotation.TableField(value="catalog_visible",
        insertStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
        updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean catalogVisible;


    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    @TableField(exist = false)
    private String uploaderName;

    private Long restaurantId;

    @TableField(exist = false)
    private String restaurantName;

    private String name;

    private String description;

    private BigDecimal price;

    private FoodCategory category;

    @TableField(value = "picture_url", typeHandler = StringArrayTypeHandler.class)
    private String[] pictureUrl;

    /** 适合的饭点，MealTime 取值，按规范顺序；空数组表示不限。 */
    @TableField(value = "meal_times", typeHandler = StringArrayTypeHandler.class)
    private String[] mealTimes;

    /** StageColor 取值；为空时客户端用分类默认色。和 stageAlternates 一起存。 */
    private String stageColor;

    @TableField(value = "stage_alternates", typeHandler = StringArrayTypeHandler.class)
    private String[] stageAlternates;

    /** 封面圆形裁切，三个值要么都有要么都没有，见 CoverCropDTO。 */
    private BigDecimal coverCenterX;
    private BigDecimal coverCenterY;
    private BigDecimal coverDiameter;

    private Boolean removeBackground;

    private Integer likesCount;

    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;

    @TableField(value = "updated_at", typeHandler = TimestampTypeHandler.class)
    private Long updatedAt;
}
