package you.v50to.eatwhat.data.dto;

import lombok.Data;
@Data
public class BrowseHistoryFoodRow {
    private String targetType;
    private Long targetId;
    private Long viewedAt;

    private Long foodId;
    private String foodName;
    private String[] foodPictureUrl;
    private Integer foodLikesCount;
}
