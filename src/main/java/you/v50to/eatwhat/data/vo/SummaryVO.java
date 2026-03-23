package you.v50to.eatwhat.data.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SummaryVO {
    private Long totalUsers;
    private Long totalFoods;
    private Long totalRestaurants;
    private Long todayUsers;
    private Long todayFoods;
    private Long todayRestaurants;
    private double verifiedRate;
}
