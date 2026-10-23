package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import you.v50to.eatwhat.data.enums.DeviceType;

@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 10, message = "用户名长度需为3-10")
    @Pattern(
            regexp = "^(?!.*@)(?!\\d{11}$)[a-zA-Z0-9_]{3,10}$",
            message = "用户名只能包含字母数字下划线，且不能是邮箱或11位纯数字手机号"
    )
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度必须在 6 和 64 之间")
    private String password;

    @NotNull(message = "登录端不能为空")
    private DeviceType device;

    private Boolean crossBorderConsent;

    @Size(max = 128, message = "同意政策版本过长")
    private String consentPolicyVersion;

    @Override public String toString() { return "RegisterDTO[redacted]"; }
}
