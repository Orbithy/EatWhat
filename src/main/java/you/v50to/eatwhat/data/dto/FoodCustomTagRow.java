package you.v50to.eatwhat.data.dto;

import lombok.Data;

@Data
public class FoodCustomTagRow {
    private Long foodId;
    private Long tagId;
    private String tagName;
}
