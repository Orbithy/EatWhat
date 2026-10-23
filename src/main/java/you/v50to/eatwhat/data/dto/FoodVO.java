package you.v50to.eatwhat.data.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import you.v50to.eatwhat.data.enums.FoodCategory;
import you.v50to.eatwhat.data.enums.MealTime;
import you.v50to.eatwhat.data.enums.StageColor;
import you.v50to.eatwhat.data.vo.FoodSystemTagVO;
import you.v50to.eatwhat.data.vo.FoodTagSummaryVO;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
public class FoodVO {
    private Long id;
    private Long accountId;
    private String uploaderName;
    private Long restaurantId;
    private String restaurantName;
    /** 以下五项只在推荐接口里返回：店的地址描述、GCJ-02 坐标、所属商圈。 */
    private String restaurantAddress;
    private Double restaurantGcjLng;
    private Double restaurantGcjLat;
    private Long hubId;
    private String hubName;
    private String name;
    private String description;
    private BigDecimal price;
    private FoodCategory category;
    private String categoryLabel;
    private List<String> pictureUrl;
    /** Stable storage references for edits; never use a signed read URL as an upload key. */
    private List<String> pictureKeys;
    private List<FoodSystemTagVO> systemTags;
    private List<FoodTagSummaryVO> myCustomTags;
    private Boolean isLiked;
    private Boolean isFavorite;
    private Integer likesCount;
    /** 适合的饭点；空列表表示不限。 */
    private List<MealTime> mealTimes;
    /** 为空时客户端按分类默认色。 */
    private StageColor stageColor;
    private List<StageColor> stageAlternates;
    /** 为空时按居中内切圆显示。 */
    private CoverCropDTO coverCrop;
    private Boolean removeBackground;
    private Long createdAt;
    private Long updatedAt;
}
