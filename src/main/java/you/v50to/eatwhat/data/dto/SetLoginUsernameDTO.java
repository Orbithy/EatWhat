package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import you.v50to.eatwhat.utils.LoginUsernameRules;

/** 手机号账号首次设置登录用户名，可同时设置初始密码。 */
@Data
public class SetLoginUsernameDTO {
    /** 唯一登录用户名，3–10 位字母、数字或下划线，区分大小写；不修改昵称。 */
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = LoginUsernameRules.PATTERN, message = "用户名只能包含3到10位字母、数字或下划线")
    private String username;

    /** /auth/password/code 发到当前账号绑定手机的6位验证码。 */
    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码必须为6位数字")
    private String code;

    /** 可选初始密码；省略则保留原密码状态，已有密码请用原修改密码接口。 */
    @Size(min = 6, max = 64, message = "密码长度必须在6和64之间")
    @Pattern(regexp = "^[\\s\\S]*\\S[\\s\\S]*$", message = "密码不能为空白")
    private String newPassword;

    @Override public String toString() { return "SetLoginUsernameDTO[redacted]"; }
}
