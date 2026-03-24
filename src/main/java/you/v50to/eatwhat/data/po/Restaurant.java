package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;
import tools.jackson.databind.annotation.JsonSerialize;
import you.v50to.eatwhat.config.JacksonConfig;
import you.v50to.eatwhat.utils.PointTypeHandler;
import you.v50to.eatwhat.utils.StringArrayTypeHandler;
import you.v50to.eatwhat.utils.TimestampTypeHandler;



@Data
@NoArgsConstructor
@TableName(value = "restaurant", autoResultMap = true)
public class Restaurant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private String name;
    private String address;
    private Integer cityId;
    @JsonSerialize(using = JacksonConfig.GeometrySerializer.class)
    @TableField(typeHandler = PointTypeHandler.class)
    private Point location;
    private Double gcjLng;
    private Double gcjLat;
    private Long hubId;
    @TableField("poi")
    private String POI;
    @TableField(value = "picture_url", typeHandler = StringArrayTypeHandler.class)
    private String[] pictureUrl;
    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;
    @TableField(value = "updated_at", typeHandler = TimestampTypeHandler.class)
    private Long updatedAt;
}
