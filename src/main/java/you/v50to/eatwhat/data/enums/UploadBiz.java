package you.v50to.eatwhat.data.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.Locale;

@Getter
public enum UploadBiz {

    ACTIVITY("activity", true),   // 需要 verified 角色
    AVATAR("avatar", false),      // 仅需登录
    FOODS("foods", true),
    RESTAURANTS("restaurants", true);

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
        if (value == null) {
            throw new IllegalArgumentException("不支持的 biz 类型: null");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (UploadBiz b : values()) {
            if (b.value.equals(normalized)) return b;
        }
        throw new IllegalArgumentException("不支持的 biz 类型: " + value);
    }
}
