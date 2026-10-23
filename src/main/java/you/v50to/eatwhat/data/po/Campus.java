package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 校区（产品文档 4.1）。由迁移按固定 id 写入，各站点一致，见 V73__campus.sql。 */
@Data
@NoArgsConstructor
@TableName("campus")
public class Campus {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String name;
    private Double gcjLng;
    private Double gcjLat;
    /** 默认校区：没有定位时选它；没有归属校区的商圈、不属于任何商圈的店也算在它下面。 */
    private Boolean isDefault;
    private Boolean open;
    private Integer sort;
}
