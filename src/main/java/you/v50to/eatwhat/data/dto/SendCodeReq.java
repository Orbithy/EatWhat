package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import you.v50to.eatwhat.data.enums.Scene;

@Getter
public class SendCodeReq {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String mobile;

    @NotNull(message = "scene不能为空")
    private Scene scene;
}
