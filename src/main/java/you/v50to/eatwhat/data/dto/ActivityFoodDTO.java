package you.v50to.eatwhat.data.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class ActivityFoodDTO {
    private Long id;
    private Long accountId;
    private String foodName;
    private String description;
    private Integer provinceId;
    private Integer cityId;
    private List<String> pictureUrl;
    private Integer likesCount;
    private Long createdAt;
    private Long updatedAt;
}
