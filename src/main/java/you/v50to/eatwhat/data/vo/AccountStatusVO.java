package you.v50to.eatwhat.data.vo;

import lombok.Data;

@Data
public class AccountStatusVO {
    /** NORMAL 或 BANNED */
    private String accountStatus;
    /** 封禁时间（ISO 8601），未封禁为 null */
    private String bannedAt;
    /** 封禁原因，未封禁为 null */
    private String banReason;
    /** 封禁到期时间，永久封禁为 null */
    private String banExpireAt;
}

