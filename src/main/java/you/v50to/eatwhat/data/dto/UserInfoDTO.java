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
    private String gender;
    private LocalDate birthday;
    private String signature;
    private Integer hometownProvinceId;
    private Integer hometownCityId;
    private Long createdAt;
    private Long updatedAt;
}
