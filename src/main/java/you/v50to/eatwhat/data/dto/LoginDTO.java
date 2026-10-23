package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import you.v50to.eatwhat.data.enums.DeviceType;

@Data
public class LoginDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 64)
    private String username;

    @NotBlank(message = "密码不能为空")
    private  String password;

    @NotNull(message = "登录端不能为空")
    private DeviceType device;

    @Override public String toString() { return "LoginDTO[redacted]"; }
}
