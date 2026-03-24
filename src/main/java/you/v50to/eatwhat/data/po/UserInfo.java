package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import you.v50to.eatwhat.utils.TimestampTypeHandler;

import java.time.LocalDate;

/**
 * 用户信息实体
 */
@Data
@TableName(value = "user_info", autoResultMap = true)
public class UserInfo {
    
    /**
     * 用户ID（关联 users 表）
     */
    @TableId
    private Long id;
    
    /**
     * 性别：male, female, other
     */
    private String gender;
    
    /**
     * 生日
     */
    private LocalDate birthday;
    
    /**
     * 个性签名
     */
    private String signature;
    
    /**
     * IP所在省份ID
     */
    private Integer locationProvinceId;
    
    /**
     * IP所在城市ID
     */
    private Integer locationCityId;
    
    /**
     * 家乡省份ID
     */
    private Integer hometownProvinceId;
    
    /**
     * 家乡城市ID
     */
    private Integer hometownCityId;
    
    /**
     * 创建时间
     */
    @TableField(value = "created_at", typeHandler = TimestampTypeHandler.class)
    private Long createdAt;
    
    /**
     * 更新时间
     */
    @TableField(value = "updated_at", typeHandler = TimestampTypeHandler.class)
    private Long updatedAt;
}
