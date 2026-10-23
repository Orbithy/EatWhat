package you.v50to.eatwhat.data.vo;

/** 设置成功后需重新登录，客户端可使用新用户名或原手机号登录。 */
public record LoginUsernameVO(String loginUsername, boolean hasUsername, boolean hasPassword,
                              boolean reauthenticationRequired) { }
