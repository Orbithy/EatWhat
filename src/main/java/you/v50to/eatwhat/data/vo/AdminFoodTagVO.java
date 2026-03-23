package you.v50to.eatwhat.data.vo;

import lombok.Data;

@Data
public class AdminFoodTagVO {
    private Long id;
    private String name;
    private Long usageCount;
    private Long createdAt;
    private Long updatedAt;
}
