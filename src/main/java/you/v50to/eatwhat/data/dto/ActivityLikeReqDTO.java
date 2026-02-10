package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ActivityLikeReqDTO {

    @NotBlank(message = "目标类型不能为空")
    @Pattern(regexp = "^(food|dinner)$", message = "目标类型必须是 food 或 dinner")
    private String targetType;

    @NotNull(message = "活动ID不能为空")
    @Min(value = 1, message = "活动ID必须大于0")
    private Long activityId;
}
