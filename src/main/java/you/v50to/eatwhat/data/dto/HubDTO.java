package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class HubDTO {
    @NotBlank(message = "名称不能为空")
    private String name;

    @NotNull(message = "gcjLng不能为空")
    @DecimalMin(value = "72.004", message = "gcjLng超出范围")
    @DecimalMax(value = "137.8347", message = "gcjLng超出范围")
    private Double gcjLng;

    @NotNull(message = "gcjLat不能为空")
    @DecimalMin(value = "0.8293", message = "gcjLat超出范围")
    @DecimalMax(value = "55.8271", message = "gcjLat超出范围")
    private Double gcjLat;

    /**
     * 商场边界 GeoJSON，允许 Polygon 或 MultiPolygon，坐标按 GCJ-02 传入。
     */
    private String boundaryGeoJson;

    /**
     * 所属校区（GET /campus/list 的 id）。新建时不传表示不属于任何校区（算在默认校区下面）；编辑时不传表示不变。
     */
    @Min(value = 1, message = "校区ID必须大于0")
    private Long campusId;
}
