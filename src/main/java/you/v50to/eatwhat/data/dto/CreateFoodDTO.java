package you.v50to.eatwhat.data.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import you.v50to.eatwhat.data.enums.FoodCategory;
import you.v50to.eatwhat.data.enums.MealTime;
import you.v50to.eatwhat.data.enums.StageColor;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateFoodDTO {

    @NotNull(message = "餐厅ID不能为空")
    @Min(value = 1, message = "餐厅ID必须大于0")
    private Long restaurantId;

    @NotBlank(message = "菜品名称不能为空")
    @Size(max = 64, message = "菜品名称长度不能超过64")
    private String name;

    @Size(max = 2000, message = "菜品描述长度不能超过2000")
    private String description;

    @DecimalMin(value = "0.00", message = "价格不能为负数")
    @Digits(integer = 6, fraction = 2, message = "价格格式不正确，最多6位整数2位小数")
    private BigDecimal price;

    private FoodCategory category;

    @Size(max = 20)
    private List<@NotNull @Min(1) Long> systemTagIds;

    @Size(max = 20)
    private List<@NotBlank @Size(max = 64) String> myCustomTagNames;

    @NotNull(message = "菜品图片不能为空")
    @Size(min = 1, max = 9, message = "菜品图片数量需在1-9张之间")
    private List<@NotBlank(message = "图片链接不能为空") @Pattern(regexp = "^foods/.+", message = "图片必须是有效的 foods key，请传入 presign 返回的 key 字段而非 putUrl") String> pictureUrl;

    /** 适合哪顿，可多选。新客户端必填至少 1 个；不传表示不限（兼容旧客户端）。 */
    @Size(min = 1, max = 4, message = "适合的饭点需选 1 到 4 个")
    private List<@NotNull(message = "饭点不能为空") MealTime> mealTimes;

    /** 舞台色和两个备选色，要么都传要么都不传；三者互不相同。 */
    private StageColor stageColor;

    @Size(min = 2, max = 2, message = "备选舞台色必须是 2 个")
    private List<@NotNull(message = "备选舞台色不能为空") StageColor> stageAlternates;

    @Valid
    private CoverCropDTO coverCrop;

    /** 「自动去掉背景」开关，只存状态。 */
    private Boolean removeBackground;
}
