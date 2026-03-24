package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import you.v50to.eatwhat.utils.TimestampTypeHandler;

/**
 * 城市实体
 */
@Data
@TableName(value = "cities", autoResultMap = true)
public class City {
    
    /**
     * 城市ID
     */
    @TableId(type = IdType.AUTO)
    private Integer id;
    
    /**
     * 省份ID
     */
    private Integer provinceId;
    
    /**
     * 城市名称
     */
    private String name;
    
    /**
     * 创建时间
     */
    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;
}
