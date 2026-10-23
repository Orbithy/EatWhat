package you.v50to.eatwhat.service;

import you.v50to.eatwhat.data.dto.FoodCandidateRow;
import you.v50to.eatwhat.data.enums.MealTime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把按个人排序的候选排成一批推荐（产品文档第 8 节）。纯函数，不做 IO：
 * <ol>
 *   <li>当天出现过的菜往后放；没看过的不够一批时用看过的补，并通知调用方从头再来。</li>
 *   <li>饭点分三层：适合当前饭点的、不限饭点的、其他饭点的。</li>
 *   <li>每层里一半以上是大家公认的好菜（质量分），和个人排序交替取，避免越推越窄。</li>
 *   <li>同一批里同一家店最多 2 道，相邻两道尽量不同分类。</li>
 * </ol>
 */
public final class FoodRecommender {
    static final int MAX_PER_RESTAURANT = 2;
    /** 为了换分类最多往后看几道，避免把排名很靠后的菜提上来。 */
    static final int CATEGORY_LOOKAHEAD = 6;

    private FoodRecommender() { }

    public record Batch(List<FoodCandidateRow> items, boolean exhausted) { }

    public static Batch pick(List<FoodCandidateRow> candidates, MealTime mealTime, boolean personalized,
                             Set<Long> seen, int size) {
        List<FoodCandidateRow> unseen = candidates.stream().filter(c -> !seen.contains(c.getId())).toList();
        List<FoodCandidateRow> repeated = candidates.stream().filter(c -> seen.contains(c.getId())).toList();
        boolean exhausted = unseen.size() < size && !repeated.isEmpty();

        List<FoodCandidateRow> ordered = new ArrayList<>(order(unseen, mealTime, personalized));
        if (exhausted) ordered.addAll(order(repeated, mealTime, personalized));
        return new Batch(diversify(ordered, size), exhausted);
    }

    private static List<FoodCandidateRow> order(List<FoodCandidateRow> pool, MealTime mealTime, boolean personalized) {
        List<FoodCandidateRow> result = new ArrayList<>(pool.size());
        for (int tier = 0; tier < 3; tier++) {
            final int current = tier;
            List<FoodCandidateRow> layer = pool.stream().filter(c -> mealTier(c, mealTime) == current).toList();
            result.addAll(mix(layer, personalized));
        }
        return result;
    }

    static int mealTier(FoodCandidateRow candidate, MealTime mealTime) {
        String[] meals = candidate.getMealTimes();
        if (meals == null || meals.length == 0) return 1;
        return Arrays.asList(meals).contains(mealTime.getValue()) ? 0 : 2;
    }

    /** 质量和个人交替取，质量先取，所以至少一半来自质量排序。不带个人因素时只按质量。 */
    private static List<FoodCandidateRow> mix(List<FoodCandidateRow> layer, boolean personalized) {
        List<FoodCandidateRow> byQuality = layer.stream()
                .sorted(Comparator.comparing(FoodRecommender::marked)
                        .thenComparing(c -> score(c.getQualityScore()), Comparator.reverseOrder()))
                .toList();
        if (!personalized) return byQuality;
        LinkedHashSet<FoodCandidateRow> merged = new LinkedHashSet<>();
        for (int i = 0; i < layer.size(); i++) {
            merged.add(byQuality.get(i));
            merged.add(layer.get(i));
        }
        return List.copyOf(merged);
    }

    private static boolean marked(FoodCandidateRow c) {
        return Boolean.TRUE.equals(c.getOwnMark()) || Boolean.TRUE.equals(c.getViewedMark());
    }

    private static double score(Double value) {
        return value == null ? 0 : value;
    }

    private static List<FoodCandidateRow> diversify(List<FoodCandidateRow> ordered, int size) {
        List<FoodCandidateRow> remaining = new ArrayList<>(ordered);
        List<FoodCandidateRow> picked = new ArrayList<>(size);
        Map<Long, Integer> perRestaurant = new HashMap<>();
        while (picked.size() < size && !remaining.isEmpty()) {
            Object previousCategory = picked.isEmpty() ? null : picked.getLast().getCategory();
            int choice = -1, firstAllowed = -1;
            for (int i = 0; i < remaining.size(); i++) {
                FoodCandidateRow c = remaining.get(i);
                if (perRestaurant.getOrDefault(c.getRestaurantId(), 0) >= MAX_PER_RESTAURANT) continue;
                if (firstAllowed < 0) firstAllowed = i;
                if (i - firstAllowed >= CATEGORY_LOOKAHEAD) break;
                if (previousCategory == null || !Objects.equals(previousCategory, c.getCategory())) {
                    choice = i;
                    break;
                }
            }
            if (choice < 0) choice = firstAllowed;
            // Every remaining dish is from a full restaurant: a short batch beats breaking the per-shop cap.
            if (choice < 0) break;
            FoodCandidateRow c = remaining.remove(choice);
            perRestaurant.merge(c.getRestaurantId(), 1, Integer::sum);
            picked.add(c);
        }
        return picked;
    }
}
