package you.v50to.eatwhat.utils;

import cn.dev33.satoken.stp.StpUtil;

/** 游客可访问的接口用：未登录或 token 失效时返回 null，而不是抛出未登录异常。 */
public final class LoginUtil {
    private LoginUtil() {}

    public static Long currentUserIdOrNull() {
        Object id = StpUtil.getLoginIdDefaultNull();
        return id == null ? null : Long.valueOf(id.toString());
    }
}
