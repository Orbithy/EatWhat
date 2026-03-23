package you.v50to.eatwhat.data.vo;

import lombok.Data;

@Data
public class FoodSystemTagVO {
    private Long id;
    private String name;
    private Long count;
    private Boolean taggedByMe;
}
