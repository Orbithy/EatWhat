package you.v50to.eatwhat.data.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.time.LocalTime;
import java.util.Collection;
import java.util.Locale;

/** 饭点（产品文档 4.5）。声明顺序就是存库和同步时的规范顺序。 */
@Getter
public enum MealTime {

    BREAKFAST("breakfast", "早饭"),
    LUNCH("lunch", "午饭"),
    DINNER("dinner", "晚饭"),
    NIGHT("night", "夜宵");

    @JsonValue
    private final String value;
    private final String label;

    MealTime(String value, String label) {
        this.value = value;
        this.label = label;
    }

    @JsonCreator
    public static MealTime fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("未知的饭点: null");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MealTime m : values()) {
            if (m.value.equals(normalized)) return m;
        }
        throw new IllegalArgumentException("未知的饭点: " + value);
    }

    /** 按时间段推算饭点：早 05:00–10:30、午 10:30–14:30、晚 14:30–20:30，其余是夜宵。 */
    public static MealTime at(LocalTime time) {
        if (time.isBefore(LocalTime.of(5, 0))) return NIGHT;
        if (time.isBefore(LocalTime.of(10, 30))) return BREAKFAST;
        if (time.isBefore(LocalTime.of(14, 30))) return LUNCH;
        if (time.isBefore(LocalTime.of(20, 30))) return DINNER;
        return NIGHT;
    }

    /** 去重并按规范顺序排列后的取值数组。 */
    public static String[] canonical(Collection<MealTime> mealTimes) {
        return mealTimes.stream().distinct().sorted().map(MealTime::getValue).toArray(String[]::new);
    }
}
