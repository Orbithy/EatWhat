package you.v50to.eatwhat.data.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import you.v50to.eatwhat.data.po.Food;

/** 推荐候选：菜本身加上店的位置、商圈和两项排序分，见 FoodRecommendationSql.selectCandidates。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FoodCandidateRow extends Food {
    private String restaurantAddress;
    private Double restaurantGcjLng;
    private Double restaurantGcjLat;
    private Long hubId;
    private String hubName;
    /** 个人偏好分，未认证或游客为 0。 */
    private Double personalScore;
    /** 大家公认的好菜：赞、口味标签、新菜加分。 */
    private Double qualityScore;
    /** 自己赞过或收藏过。 */
    private Boolean ownMark;
    /** 一天内看过详情。 */
    private Boolean viewedMark;
}
