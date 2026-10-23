package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 在活动下发布内容。图片上限和是否必须选省市由活动决定，extra 按活动的扩展字段填写。 */
@Data
public class CreateActivityPostDTO {

    @Size(max = 64, message = "标题不能超过64个字符")
    private String title;

    @Size(max = 2000, message = "内容不能超过2000个字符")
    private String description;

    @Min(value = 1, message = "省份ID必须大于0")
    private Integer provinceId;

    @Min(value = 1, message = "城市ID必须大于0")
    private Integer cityId;

    @NotNull(message = "图片不能为空")
    @Size(min = 1, max = 9, message = "图片数量需在1-9张之间")
    private List<@NotBlank(message = "图片链接不能为空") @Pattern(regexp = "^activity/.+", message = "图片必须是有效的 activity key，请传入 presign 返回的 key 字段而非 putUrl") String> pictureUrl;

    /** 扩展字段的值，键为活动定义的 key */
    private Map<String, Object> extra;
}
