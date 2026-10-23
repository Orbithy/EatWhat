package you.v50to.eatwhat.data.enums;

import lombok.Getter;

@Getter
public enum BizCode {

    SUCCESS(0, "ok"),

    // 1xxxx 通用错误
    PARAM_INVALID(10001, "参数非法"),
    PARAM_MISSING(10002, "参数缺失"),
    TOO_MANY_REQUESTS(10003, "请求频率过高"),
    NOT_SUPPORTED(10004, "不支持的操作"),

    // 2xxxx 认证与鉴权
    NOT_LOGIN(20001, "用户未登录"),
    TOKEN_INVALID(20002, "Token 无效"),
    TOKEN_EXPIRED(20003, "Token 已过期"),
    NO_PERMISSION(20004, "权限不足"),
    CROSS_BORDER_CONSENT_REQUIRED(20005, "需要跨境同步同意"),
    DATA_SYNC_PENDING(20006, "账号正在等待协调或同步"),
    AUTH_UNAVAILABLE(20007, "身份验证暂不可用"),
    CONSENT_PROOF_INVALID(20008, "同意凭证无效或已过期"),
    CONSENT_POLICY_CHANGED(20009, "同意政策已更新"),
    ACCOUNT_AUTHORIZATION_EXPIRED(20010, "账号授权已过期，请重新验证"),
    ACCOUNT_SECURITY_CONFLICT(20011, "账号存在并发密码冲突，请在本站通过已验证邮箱恢复"),

    // 3xxxx 用户模块
    USER_NOT_FOUND(30001, "用户不存在"),
    USER_DISABLED(30002, "用户被禁用"),
    USERNAME_EXISTS(30003, "用户名已存在"),
    PASSWORD_ERROR(30004, "密码错误"),
    VERIFY_CODE_ERROR(30005, "验证码错误"),
    EMAIL_ALREADY_VERIFIED(30006, "该用户已通过邮箱验证"),
    EMAIL_INVALID(30007, "邮箱格式不正确"),
    EMAIL_TOKEN_INVALID(30008, "邮箱验证链接无效或已过期"),
    EMAIL_NOT_SDU(30009, "仅支持山东大学邮箱（@sdu.edu.cn 或 @mail.sdu.edu.cn）"),
    PASSWORD_NOT_SET(30010, "这个号码还没设密码，用验证码登录吧"),
    MOBILE_NOT_BOUND(30011, "还没有绑定手机号"),
    SSO_STATE_INVALID(30012, "认证链接已失效，请回到 App 重新发起认证"),
    STUDENT_ID_TAKEN(30013, "这个学号已经认证过其他账号"),

    // 4xxxx 业务逻辑错误
    OP_FAILED(40001, "操作失败"),
    STATE_NOT_ALLOWED(40002, "当前状态不允许该操作"),
    RESOURCE_NOT_ENOUGH(40003, "资源不足"),
    DUPLICATE_SUBMIT(40004, "重复提交"),
    CITY_PROVINCE_MISMATCH(40005, "城市与省份不匹配"),
    PROVINCE_NOT_FOUND(40006, "省份不存在"),
    CITY_NOT_FOUND(40007, "城市不存在"),
    FOOD_NOT_FOUND(40008, "菜品不存在"),
    RESTAURANT_NOT_FOUND(40009, "餐厅不存在"),
    HUB_NOT_FOUND(40010, "商场不存在"),
    ACTIVITY_NOT_FOUND(40011, "活动不存在"),
    ACTIVITY_POST_NOT_FOUND(40012, "活动内容不存在"),
    ACTIVITY_NOT_OPEN(40013, "活动当前不能发布内容"),
    ACTIVITY_CODE_EXISTS(40014, "活动 code 已存在"),
    ACTIVITY_HAS_POSTS(40015, "活动已有内容，不能删除，请改为下线"),

    // 5xxxx 外部依赖错误
    THIRD_PARTY_UNAVAILABLE(50001, "第三方服务不可用"),
    THIRD_PARTY_TIMEOUT(50002, "第三方接口超时"),
    THIRD_PARTY_BAD_RESPONSE(50003, "第三方返回异常"),

    // 9xxxx 系统级错误
    SYSTEM_ERROR(90001, "系统内部异常"),
    DB_ERROR(90002, "数据库异常"),
    REDIS_ERROR(90003, "Redis 异常"),
    UNKNOWN_ERROR(90004, "未捕获异常");

    private final int code;
    private final String msg;

    BizCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
