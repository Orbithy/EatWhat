package you.v50to.eatwhat.data.vo;

import lombok.Data;

@Data
public class UserListItemVO {
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
    private AccountStatusVO accountStatus;
}

