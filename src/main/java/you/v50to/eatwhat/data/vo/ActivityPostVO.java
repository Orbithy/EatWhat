package you.v50to.eatwhat.data.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ActivityPostVO {
    private Long id;
    private Long activityId;
    private String activityCode;
    /** 游客访问时不返回 */
    private Long accountId;
    private String uploaderName;
    private String title;
    private String description;
    private Integer provinceId;
    private Integer cityId;
    /** 可直接显示的图片地址（可能是临时签名地址） */
    private List<String> pictureUrl;
    /** 扩展字段的值 */
    private Map<String, Object> extra;
    private Integer likesCount;
    private Boolean isLiked;
    private Long createdAt;
    private Long updatedAt;
}
