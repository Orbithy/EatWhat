package you.v50to.eatwhat.data.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

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
}

