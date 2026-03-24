package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import tools.jackson.databind.annotation.JsonSerialize;
import you.v50to.eatwhat.config.JacksonConfig;
import you.v50to.eatwhat.utils.MultiPolygonTypeHandler;
import you.v50to.eatwhat.utils.PointTypeHandler;
import you.v50to.eatwhat.utils.TimestampTypeHandler;

@Data
@NoArgsConstructor
@TableName(value = "hub", autoResultMap = true)
public class Hub {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    @JsonSerialize(using = JacksonConfig.GeometrySerializer.class)
    @TableField(typeHandler = PointTypeHandler.class)
    private Point center;
    private Double gcjLng;
    private Double gcjLat;
    @JsonSerialize(using = JacksonConfig.GeometrySerializer.class)
    @TableField(typeHandler = MultiPolygonTypeHandler.class)
    private MultiPolygon boundary;
    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;
    @TableField(value = "updated_at", typeHandler = TimestampTypeHandler.class)
    private Long updatedAt;
}
