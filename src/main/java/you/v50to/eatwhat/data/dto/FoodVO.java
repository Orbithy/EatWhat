package you.v50to.eatwhat.data.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import you.v50to.eatwhat.data.enums.FoodCategory;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
public class FoodVO {
    private Long id;
    private Long accountId;
    private String uploaderName;
    private Long restaurantId;
    private String name;
    private String description;
    private BigDecimal price;
    private FoodCategory category;
    private List<String> pictureUrl;
    private Integer likesCount;
    private Long createdAt;
    private Long updatedAt;
}

