package you.v50to.eatwhat.data.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class ActivityFoodDTO {
    private Long id;
    private Long accountId;
    /** 上传者昵称 */
    private String uploaderName;
    private String foodName;
    private String description;
    private Integer provinceId;
    private Integer cityId;
    private List<String> pictureUrl;
    private Integer likesCount;
    private Boolean isLiked;
    private Long createdAt;
    private Long updatedAt;
}
