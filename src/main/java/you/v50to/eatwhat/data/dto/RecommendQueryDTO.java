package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import you.v50to.eatwhat.data.enums.FoodCategory;
import you.v50to.eatwhat.data.enums.MealTime;

/** GET /foods/recommend 的查询参数，全部可选。 */
@Data
public class RecommendQueryDTO {

    /** 校区，不传用默认校区。 */
    @Min(value = 1, message = "校区ID必须大于0")
    private Long campusId;

    /** 饭点，不传按服务器当前时间（北京时间）推算。 */
    private MealTime mealTime;

    /** 分类，不传是「今日推荐」。 */
    private FoodCategory category;

    /** 一批几道，默认 8，最多 20。 */
    @Min(value = 1, message = "size 至少为 1")
    @Max(value = 20, message = "size 最多为 20")
    private Integer size;

    /** 旧参数，等同 size，超出范围时截到 1–20。 */
    private Integer limit;

    /** 游客用来做当天去重的设备标识，由客户端生成并保存；登录用户不需要。 */
    @Pattern(regexp = "^[A-Za-z0-9_-]{8,64}$", message = "deviceId 只能是 8–64 位字母、数字、下划线或连字符")
    private String deviceId;
}
