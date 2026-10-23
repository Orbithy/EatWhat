package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ActivityStatusDTO {

    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "^(DRAFT|ONLINE|OFFLINE)$", message = "状态只能是 DRAFT、ONLINE 或 OFFLINE")
    private String status;
}
