package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class RestaurantDTO {
    @NotBlank(message = "名称不能为空")
    private String name;
    @NotBlank(message = "地址不能为空")
    private String address;
    private Integer cityId;
    @NotNull(message = "gcjLng不能为空")
    @DecimalMin(value = "72.004", message = "gcjLng超出范围")
    @DecimalMax(value = "137.8347", message = "gcjLng超出范围")
    private Double gcjLng;
    @NotNull(message = "gcjLat不能为空")
    @DecimalMin(value = "0.8293", message = "gcjLat超出范围")
    @DecimalMax(value = "55.8271", message = "gcjLat超出范围")
    private Double gcjLat;
    private Long hubId;
    private String POI;
    @Size(max = 9, message = "餐厅图片最多9张")
    private List<@NotBlank(message = "图片链接不能为空") @Pattern(regexp = "^restaurants/.+", message = "图片必须是有效的 restaurants key") String> pictureUrl;
}
