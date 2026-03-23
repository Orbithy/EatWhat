package you.v50to.eatwhat.data.dto;

import lombok.Data;

@Data
public class FoodSystemTagAggregateRow {
    private Long foodId;
    private Long tagId;
    private String tagName;
    private Long tagCount;
    private Boolean taggedByMe;
}
