package you.v50to.eatwhat.data.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class BrowseHistoryItemDTO {
    private String targetType;
    private Long targetId;
    private Long viewedAt;
    private BrowseHistoryRestaurantDTO restaurant;
    private BrowseHistoryFoodDTO food;
}
