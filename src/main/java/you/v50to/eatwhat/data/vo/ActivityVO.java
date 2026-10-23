package you.v50to.eatwhat.data.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ActivityVO {
    private Long id;
    private String code;
    private String name;
    private String description;
    private String coverUrl;
    /** DRAFT / ONLINE / OFFLINE */
    private String status;
    private Long startsAt;
    private Long endsAt;
    /** 当前能否在这个活动下发内容：已上线且在发布时间内 */
    private Boolean postingOpen;
    private Integer sort;
    private Boolean requireLocation;
    private Integer maxPictures;
    private List<Map<String, Object>> fieldSchema;
    private Long createdAt;
    private Long updatedAt;
}
