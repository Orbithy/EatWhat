package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import you.v50to.eatwhat.data.enums.DeviceType;

/** 手机号验证码登录，新号码自动注册。 */
@Data
public class PhoneLoginDTO {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String mobile;

    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码必须为6位数字")
    private String code;

    @NotNull(message = "登录端不能为空")
    private DeviceType device;

    /** 只在新号码注册时使用，含义同注册接口。 */
    private Boolean crossBorderConsent;

    @Size(max = 128, message = "同意政策版本过长")
    private String consentPolicyVersion;

    @Override public String toString() { return "PhoneLoginDTO[redacted]"; }
}
