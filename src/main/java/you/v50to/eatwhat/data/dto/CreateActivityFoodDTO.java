package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateActivityFoodDTO {

    @NotBlank(message = "菜品名称不能为空")
    @Size(max = 64, message = "菜品名称长度不能超过64")
    private String foodName;

    @Size(max = 2000, message = "菜品介绍长度不能超过2000")
    private String description;

    @Min(value = 1, message = "省份ID必须大于0")
    private Integer provinceId;

    @Min(value = 1, message = "城市ID必须大于0")
    private Integer cityId;

    @NotNull(message = "菜品图片不能为空")
    @Size(min = 1, max = 9, message = "菜品图片数量需在1-9张之间")
    private List<@NotBlank(message = "图片链接不能为空") String> pictureUrl;
}
