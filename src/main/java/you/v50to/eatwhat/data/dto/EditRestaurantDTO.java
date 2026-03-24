package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/**
 * 局部更新餐厅请求 DTO，所有字段均为可选，仅传入的字段会被更新
 */
@Data
public class EditRestaurantDTO {

    @Size(min = 1, message = "名称不能为空")
    private String name;

    private String address;

    private Integer cityId;

    @DecimalMin(value = "72.004", message = "gcjLng超出范围")
    @DecimalMax(value = "137.8347", message = "gcjLng超出范围")
    private Double gcjLng;

    @DecimalMin(value = "0.8293", message = "gcjLat超出范围")
    @DecimalMax(value = "55.8271", message = "gcjLat超出范围")
    private Double gcjLat;

    private Long hubId;

    private String POI;

    @Size(max = 9, message = "餐厅图片最多9张")
    private List<@NotBlank(message = "图片链接不能为空") @Pattern(regexp = "^restaurants/.+", message = "图片必须是有效的 restaurants key") String> pictureUrl;
}

