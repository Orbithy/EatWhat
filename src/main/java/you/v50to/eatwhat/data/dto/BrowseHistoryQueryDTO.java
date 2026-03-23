package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class BrowseHistoryQueryDTO {
    @NotBlank(message = "targetType 不能为空")
    @Pattern(regexp = "^(restaurant|food)$", message = "targetType 必须是 restaurant 或 food")
    private String targetType;

    private Integer page = 1;

    private Integer pageSize = 20;
}
