package you.v50to.eatwhat.data.po;

import org.locationtech.jts.geom.MultiPolygon;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.annotation.JsonSerialize;
import you.v50to.eatwhat.config.JacksonConfig;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
public class Hub {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String center;
    private Double gcjLng;
    private Double gcjLat;
    @JsonSerialize(using = JacksonConfig.GeometrySerializer.class)
    private MultiPolygon boundary;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
