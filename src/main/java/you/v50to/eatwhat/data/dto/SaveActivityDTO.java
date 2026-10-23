package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 管理员新建或修改活动。修改时 code 可以不传；传了必须与原值一致（code 不可修改）。 */
@Data
public class SaveActivityDTO {

    @Pattern(regexp = "^[a-z][a-z0-9-]{1,31}$", message = "code 需以小写字母开头，只含小写字母、数字和连字符，2-32 个字符")
    private String code;

    @NotBlank(message = "活动名称不能为空")
    @Size(max = 64, message = "活动名称不能超过64个字符")
    private String name;

    @Size(max = 2000, message = "活动介绍不能超过2000个字符")
    private String description;

    @Size(max = 512, message = "封面地址不能超过512个字符")
    @Pattern(regexp = "^https://\\S+$", message = "封面需为 https 图片地址")
    private String coverUrl;

    /** 发布开始时间（毫秒），不传表示不限 */
    @Positive(message = "开始时间无效")
    private Long startsAt;

    /** 发布结束时间（毫秒），不传表示不限 */
    @Positive(message = "结束时间无效")
    private Long endsAt;

    /** 列表排序，小的在前，默认 0 */
    private Integer sort;

    /** 发内容是否必须选择省市，默认否 */
    private Boolean requireLocation;

    /** 每条内容最多几张图，1-9，默认 9 */
    @Min(value = 1, message = "图片数量上限需在1-9之间")
    @Max(value = 9, message = "图片数量上限需在1-9之间")
    private Integer maxPictures;

    /** 扩展字段定义，最多 10 个；格式见接口文档 */
    @NotNull(message = "扩展字段定义不能为空，没有时传空数组")
    @Size(max = 10, message = "扩展字段最多10个")
    private List<Map<String, Object>> fieldSchema;
}
