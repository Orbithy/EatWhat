package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import you.v50to.eatwhat.data.enums.FoodCategory;

import java.math.BigDecimal;
import java.util.List;

/**
 * 局部更新菜品请求 DTO，所有字段均为可选，仅传入的字段会被更新
 */
@Data
public class EditFoodDTO {

    @Min(value = 1, message = "餐厅ID必须大于0")
    private Long restaurantId;

    @Size(max = 64, message = "菜品名称长度不能超过64")
    private String name;

    @Size(max = 2000, message = "菜品描述长度不能超过2000")
    private String description;

    @DecimalMin(value = "0.00", message = "价格不能为负数")
    @Digits(integer = 6, fraction = 2, message = "价格格式不正确，最多6位整数2位小数")
    private BigDecimal price;

    private FoodCategory category;

    @Size(min = 1, max = 9, message = "菜品图片数量需在1-9张之间")
    private List<@NotBlank(message = "图片链接不能为空") @Pattern(regexp = "^foods/.+", message = "图片必须是有效的 foods key，请传入 presign 返回的 key 字段而非 putUrl") String> pictureUrl;
}

