package you.v50to.eatwhat.data.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.Locale;

@Getter
public enum FoodCategory {

    STAPLE("staple", "主食"),
    DRINK("drink", "饮品"),
    SNACK("snack", "小吃"),
    DESSERT("dessert", "甜品"),
    SOUP("soup", "汤类"),
    HOT_POT("hot_pot", "火锅"),
    GRILL("grill", "烧烤"),
    COLD_DISH("cold_dish", "凉菜"),
    SIDE_DISH("side_dish", "配菜"),
    OTHER("other", "其他");

    @EnumValue
    @JsonValue
    private final String value;
    private final String label;

    FoodCategory(String value, String label) {
        this.value = value;
        this.label = label;
    }

    @JsonCreator
    public static FoodCategory fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("未知的菜品分类: null");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (FoodCategory c : values()) {
            if (c.value.equals(normalized)) return c;
        }
        throw new IllegalArgumentException("未知的菜品分类: " + value);
    }
}
