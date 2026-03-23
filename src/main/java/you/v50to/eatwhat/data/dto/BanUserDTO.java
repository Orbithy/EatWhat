package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BanUserDTO {
    @NotBlank(message = "封禁原因不能为空")
    @Size(max = 255, message = "封禁原因不能超过255个字符")
    private String reason;
}

