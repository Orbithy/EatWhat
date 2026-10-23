package you.v50to.eatwhat.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import you.v50to.eatwhat.data.dto.FoodPreferencesDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.data.dto.RecommendationProfileRow;
import you.v50to.eatwhat.data.enums.FoodCategory;
import you.v50to.eatwhat.data.vo.RecommendationProfileVO;
import you.v50to.eatwhat.data.vo.RecommendationProfileVO.*;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.RecommendationProfileMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RecommendationProfileService {
    private final RecommendationProfileMapper mapper;
    private you.v50to.eatwhat.sync.preferences.PreferenceCommands preferenceCommands;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPreferenceCommands(you.v50to.eatwhat.sync.preferences.PreferenceCommands commands) { this.preferenceCommands = commands; }

    public RecommendationProfileService(RecommendationProfileMapper mapper) {
        this.mapper = mapper;
    }

    /** Internal derived features, never returned by a public controller. */
    public Result<RecommendationProfileVO> getProfile(long accountId) {
        Map<String, BigDecimal> signals = new HashMap<>();
        List<Feature> tastes = new ArrayList<>();
        List<Feature> categories = new ArrayList<>();
        List<Feature> restaurants = new ArrayList<>();
        List<String> liked = new ArrayList<>();
        List<String> disliked = new ArrayList<>();
        for (RecommendationProfileRow row : mapper.selectProfile(accountId)) {
            switch (row.getDimension()) {
                case "signal" -> signals.put(row.getFeatureKey(), row.getWeight());
                case "manualLike" -> liked.add(row.getFeatureKey());
                case "manualDislike" -> disliked.add(row.getFeatureKey());
                case "taste" -> tastes.add(feature(row, null, row.getLabel()));
                case "category" -> categories.add(feature(row, null,
                        FoodCategory.fromValue(row.getFeatureKey()).getLabel()));
                case "restaurant" -> restaurants.add(feature(row,
                        Long.valueOf(row.getFeatureKey()), row.getLabel()));
                default -> throw new IllegalStateException("Unknown recommendation profile dimension");
            }
        }
        BigDecimal last = signals.get("lastBehaviorAt");
        BigDecimal updated = signals.get("preferencesUpdatedAt");
        var counts = new SignalCounts(count(signals, "likes"), count(signals, "favorites"),
                count(signals, "taggings"), count(signals, "browsedFoods"),
                count(signals, "browsedRestaurants"));
        return Result.ok(new RecommendationProfileVO("behavior-v1", System.currentTimeMillis(),
                "CURRENT_SITE", tastes.isEmpty() && categories.isEmpty() && restaurants.isEmpty()
                        && liked.isEmpty() && disliked.isEmpty(),
                last == null ? null : last.longValue(), counts,
                new ManualPreferences(List.copyOf(liked), List.copyOf(disliked),
                        updated == null ? null : updated.longValue()), 30, 200, 20,
                List.copyOf(tastes), List.copyOf(categories), List.copyOf(restaurants)));
    }

    public Result<ManualPreferences> getPreferences(long accountId) {
        var row = mapper.selectPreferences(accountId);
        return Result.ok(row == null ? new ManualPreferences(List.of(), List.of(), null)
                : new ManualPreferences(List.of(row.getLikedTags()), List.of(row.getDislikedTags()),
                        row.getUpdatedAt()));
    }

    @Transactional
    public Result<ManualPreferences> updatePreferences(long accountId, FoodPreferencesDTO dto) {
        List<String> liked = normalize(dto.likedTags());
        List<String> disliked = normalize(dto.dislikedTags());
        if (liked.stream().anyMatch(disliked::contains)) {
            throw new BizException(BizCode.PARAM_INVALID, "同一口味不能同时喜欢和不喜欢");
        }
        if (preferenceCommands != null) preferenceCommands.replace(accountId, liked, disliked);
        else if (liked.isEmpty() && disliked.isEmpty()) mapper.deletePreferences(accountId);
        else mapper.savePreferences(accountId, liked, disliked);
        return getPreferences(accountId);
    }

    @Transactional
    public Result<ManualPreferences> clearPreferences(long accountId) {
        if (preferenceCommands != null) preferenceCommands.replace(accountId, List.of(), List.of());
        else mapper.deletePreferences(accountId);
        return getPreferences(accountId);
    }

    private static List<String> normalize(List<String> tags) {
        if (tags == null || tags.size() > 20 || tags.stream().anyMatch(
                tag -> tag == null || tag.isBlank() || tag.length() > 32)) {
            throw new BizException(BizCode.PARAM_INVALID, "每类口味最多20个，每个1至32个字符");
        }
        var normalized = tags.stream().map(FoodTagService::normalizeTagName).distinct().sorted().toList();
        if (normalized.stream().anyMatch(tag -> tag.isBlank() || tag.length() > 32)) {
            throw new BizException(BizCode.PARAM_INVALID, "规范化口味须为1至32个字符");
        }
        return normalized;
    }

    private static int count(Map<String, BigDecimal> signals, String key) {
        return signals.getOrDefault(key, BigDecimal.ZERO).intValueExact();
    }

    private static Feature feature(RecommendationProfileRow row, Long targetId, String label) {
        return new Feature(row.getFeatureKey(), targetId, label, row.getWeight(),
                new Evidence(row.getTaggingWeight(), row.getLikeWeight(),
                        row.getFavoriteWeight(), row.getBrowseWeight(), row.getManualWeight()));
    }
}
