package you.v50to.eatwhat.data.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 用户自己的信息
 */
@Data
@NoArgsConstructor
public class UserInfoDTO {
    private Long id;
    private String userName;
    /** 唯一登录用户名；尚未设置或别名冲突时为 null，userName 仍为展示昵称。 */
    private String loginUsername;
    /** 是否已设置可用的登录用户名。 */
    private Boolean hasUsername;
    private String avatar;
    private String role;
    private Boolean banned;
    private String banReason;
    private Long bannedAt;
    private String email;
    private String phone;
    private Boolean verified;
    private String method;
    private String studentId;
    private String realName;
    private String verifiedEmail;
    /** 是否设过密码；验证码注册的账号为 false，前端据此显示「设置密码」或「修改密码」。 */
    private Boolean hasPassword;
    private String gender;
    private LocalDate birthday;
    private String signature;
    private Integer hometownProvinceId;
    private Integer hometownCityId;
    private Long createdAt;
    private Long updatedAt;
}
