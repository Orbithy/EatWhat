package you.v50to.eatwhat.data.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

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
    private String email;
    private String phone;
    private Boolean verified;
    private String method;
    private String studentId;
    private String realName;
    private String verifiedEmail;
    private Long createdAt;
    private Long updatedAt;
}
