package you.v50to.eatwhat.data.vo;

import java.math.BigDecimal;
import java.util.List;

/** Private food-interest profile; weights are heuristic strengths, not probabilities. */
public record RecommendationProfileVO(
        String modelVersion, long generatedAt, String dataScope, boolean coldStart,
        Long lastBehaviorAt, SignalCounts signalCounts, ManualPreferences manualPreferences, int historyWindowDays,
        int maxSignalsPerSource, int maxFeaturesPerDimension,
        List<Feature> tastePreferences, List<Feature> categoryPreferences,
        List<Feature> restaurantPreferences) {

    public record SignalCounts(int likes, int favorites, int taggings,
                               int browsedFoods, int browsedRestaurants) { }

    public record ManualPreferences(List<String> likedTags, List<String> dislikedTags, Long updatedAt) { }

    public record Feature(String key, Long targetId, String label, BigDecimal weight,
                          Evidence evidence) { }

    /** Raw source contributions, before the aggregate feature weight is capped. */
    public record Evidence(BigDecimal tagging, BigDecimal likes,
                           BigDecimal favorites, BigDecimal browsing, BigDecimal manual) { }
}
