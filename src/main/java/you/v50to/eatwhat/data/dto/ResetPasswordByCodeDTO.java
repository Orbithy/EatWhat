package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 用绑定手机的验证码设置或修改密码。 */
@Data
public class ResetPasswordByCodeDTO {

    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码必须为6位数字")
    private String code;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度必须在 6 和 64 之间")
    private String newPassword;

    @Override public String toString() { return "ResetPasswordByCodeDTO[redacted]"; }
}
