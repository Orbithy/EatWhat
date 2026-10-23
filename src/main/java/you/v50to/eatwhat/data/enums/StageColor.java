package you.v50to.eatwhat.data.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.Locale;

/** 舞台色（产品文档 4.7），对应设计规范的 9 个 stage-* 令牌，值去掉了 stage- 前缀。 */
@Getter
public enum StageColor {

    BROTH("broth"),
    CHILI("chili"),
    MANGO("mango"),
    PORCELAIN("porcelain"),
    BAMBOO("bamboo"),
    REDOIL("redoil"),
    SOY("soy"),
    MATCHA("matcha"),
    TOMATO("tomato");

    @JsonValue
    private final String value;

    StageColor(String value) {
        this.value = value;
    }

    @JsonCreator
    public static StageColor fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("未知的舞台色: null");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("stage-")) normalized = normalized.substring("stage-".length());
        for (StageColor c : values()) {
            if (c.value.equals(normalized)) return c;
        }
        throw new IllegalArgumentException("未知的舞台色: " + value);
    }
}
