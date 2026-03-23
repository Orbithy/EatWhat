package you.v50to.eatwhat.data.dto;

import lombok.Data;
@Data
public class BrowseHistoryRestaurantRow {
    private String targetType;
    private Long targetId;
    private Long viewedAt;

    private Long restaurantId;
    private String restaurantName;
    private String[] restaurantPictureUrl;
}
