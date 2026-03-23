package you.v50to.eatwhat.data.vo;

import lombok.Data;

import java.util.List;

@Data
public class FoodTagViewVO {
    private List<FoodSystemTagVO> systemTags;
    private List<FoodTagSummaryVO> myCustomTags;
}
