package you.v50to.eatwhat.data.vo;

/**
 * 发起统一认证：url 是带一次性 state 的统一认证地址，客户端在浏览器里打开；expiresIn 秒内有效，只能用一次。
 */
public record SsoStartVO(String url, long expiresIn) { }
