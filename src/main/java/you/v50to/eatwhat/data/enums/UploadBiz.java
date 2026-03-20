package you.v50to.eatwhat.data.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum UploadBiz {

    ACTIVITY("activity", true),   // 需要 verified 角色
    AVATAR("avatar", false),      // 仅需登录
    FOODS("foods", false);        // 仅需登录

    @JsonValue
    private final String value;

    /** 是否需要 verified 角色才能上传 */
    private final boolean requireVerified;

    UploadBiz(String value, boolean requireVerified) {
        this.value = value;
        this.requireVerified = requireVerified;
    }

    @JsonCreator
    public static UploadBiz fromValue(String value) {
        for (UploadBiz b : values()) {
            if (b.value.equals(value)) return b;
        }
        throw new IllegalArgumentException("不支持的 biz 类型: " + value);
    }
}
