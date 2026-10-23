package you.v50to.eatwhat.data.dto;

import lombok.Data;
import java.math.BigDecimal;

/** One feature or sampled-signal statistic from the same database snapshot. */
@Data
public class RecommendationProfileRow {
    private String dimension;
    private String featureKey;
    private String label;
    private BigDecimal weight;
    private BigDecimal taggingWeight;
    private BigDecimal likeWeight;
    private BigDecimal favoriteWeight;
    private BigDecimal browseWeight;
    private BigDecimal manualWeight;
}
