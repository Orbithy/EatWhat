package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;
import you.v50to.eatwhat.utils.PointTypeHandler;
import you.v50to.eatwhat.utils.StringArrayTypeHandler;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@TableName(value = "restaurant", autoResultMap = true)
public class Restaurant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String address;
    private Integer cityId;
    @TableField(typeHandler = PointTypeHandler.class)
    private Point location;
    private Double gcjLng;
    private Double gcjLat;
    private Long hubId;
    @TableField("poi")
    private String POI;
    @TableField(value = "picture_url", typeHandler = StringArrayTypeHandler.class)
    private String[] pictureUrl;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
