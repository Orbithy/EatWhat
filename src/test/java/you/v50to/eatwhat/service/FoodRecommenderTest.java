package you.v50to.eatwhat.service;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.data.dto.FoodCandidateRow;
import you.v50to.eatwhat.data.enums.FoodCategory;
import you.v50to.eatwhat.data.enums.MealTime;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodRecommenderTest {

    private static FoodCandidateRow dish(long id, long restaurant, FoodCategory category, double personal,
                                         double quality, String... meals) {
        FoodCandidateRow row = new FoodCandidateRow();
        row.setId(id);
        row.setRestaurantId(restaurant);
        row.setCategory(category);
        row.setPersonalScore(personal);
        row.setQualityScore(quality);
        row.setMealTimes(meals);
        return row;
    }

    private static List<Long> ids(FoodRecommender.Batch batch) {
        return batch.items().stream().map(FoodCandidateRow::getId).toList();
    }

    @Test
    void mealTimeBoundariesFollowTheProductTable() {
        assertEquals(MealTime.NIGHT, MealTime.at(LocalTime.of(4, 59)));
        assertEquals(MealTime.BREAKFAST, MealTime.at(LocalTime.of(5, 0)));
        assertEquals(MealTime.LUNCH, MealTime.at(LocalTime.of(10, 30)));
        assertEquals(MealTime.DINNER, MealTime.at(LocalTime.of(14, 30)));
        assertEquals(MealTime.NIGHT, MealTime.at(LocalTime.of(20, 30)));
    }

    @Test
    void currentMealFirstThenUnrestrictedThenOtherMeals() {
        List<FoodCandidateRow> candidates = List.of(
                dish(1, 1, FoodCategory.STAPLE, 0, 9, "breakfast"),
                dish(2, 2, FoodCategory.SNACK, 0, 1),
                dish(3, 3, FoodCategory.DRINK, 0, 5, "lunch", "dinner"));
        FoodRecommender.Batch batch = FoodRecommender.pick(candidates, MealTime.LUNCH, false, Set.of(), 8);
        assertEquals(List.of(3L, 2L, 1L), ids(batch));
    }

    @Test
    void atMostTwoDishesPerRestaurantEvenIfTheBatchIsShort() {
        List<FoodCandidateRow> candidates = new ArrayList<>();
        for (long id = 1; id <= 5; id++) candidates.add(dish(id, 7, FoodCategory.values()[(int) id], 0, 10 - id));
        candidates.add(dish(6, 8, FoodCategory.STAPLE, 0, 0));
        FoodRecommender.Batch batch = FoodRecommender.pick(candidates, MealTime.LUNCH, false, Set.of(), 8);
        assertEquals(List.of(1L, 2L, 6L), ids(batch));
    }

    @Test
    void adjacentDishesAvoidTheSameCategoryWithinTheLookahead() {
        List<FoodCandidateRow> candidates = List.of(
                dish(1, 1, FoodCategory.STAPLE, 0, 9),
                dish(2, 2, FoodCategory.STAPLE, 0, 8),
                dish(3, 3, FoodCategory.DRINK, 0, 7),
                dish(4, 4, FoodCategory.STAPLE, 0, 6));
        List<Long> picked = ids(FoodRecommender.pick(candidates, MealTime.LUNCH, false, Set.of(), 4));
        assertEquals(List.of(1L, 3L, 2L, 4L), picked);
    }

    @Test
    void atLeastHalfOfAPersonalBatchComesFromTheQualityRanking() {
        // Personal order (SQL) is 1..8; quality prefers 8..1. One category keeps diversity out of the way.
        List<FoodCandidateRow> candidates = new ArrayList<>();
        for (long id = 1; id <= 8; id++) candidates.add(dish(id, id, FoodCategory.STAPLE, 10 - id, id));
        List<Long> picked = ids(FoodRecommender.pick(candidates, MealTime.LUNCH, true, Set.of(), 4));
        assertEquals(List.of(8L, 1L, 7L, 2L), picked);
        List<Long> guest = ids(FoodRecommender.pick(candidates, MealTime.LUNCH, false, Set.of(), 4));
        assertEquals(List.of(8L, 7L, 6L, 5L), guest);
    }

    @Test
    void ownLikedOrRecentlyViewedDishesGoLastInTheQualityRanking() {
        FoodCandidateRow liked = dish(1, 1, FoodCategory.STAPLE, 0, 100);
        liked.setOwnMark(true);
        FoodCandidateRow viewed = dish(2, 2, FoodCategory.SNACK, 0, 90);
        viewed.setViewedMark(true);
        FoodCandidateRow fresh = dish(3, 3, FoodCategory.DRINK, 0, 1);
        assertEquals(List.of(3L, 1L, 2L),
                ids(FoodRecommender.pick(List.of(liked, viewed, fresh), MealTime.LUNCH, false, Set.of(), 3)));
    }

    @Test
    void seenDishesAreSkippedUntilTheCatalogIsExhausted() {
        List<FoodCandidateRow> candidates = new ArrayList<>();
        for (long id = 1; id <= 10; id++) candidates.add(dish(id, id, FoodCategory.values()[(int) (id % 3)], 0, 20 - id));
        FoodRecommender.Batch first = FoodRecommender.pick(candidates, MealTime.LUNCH, false, Set.of(), 4);
        assertFalse(first.exhausted());
        Set<Long> seen = Set.copyOf(ids(first));
        FoodRecommender.Batch second = FoodRecommender.pick(candidates, MealTime.LUNCH, false, seen, 4);
        assertFalse(second.exhausted());
        assertTrue(ids(second).stream().noneMatch(seen::contains));

        Set<Long> almostAll = Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        FoodRecommender.Batch wrap = FoodRecommender.pick(candidates, MealTime.LUNCH, false, almostAll, 4);
        assertTrue(wrap.exhausted());
        assertEquals(List.of(9L, 10L), ids(wrap).subList(0, 2));
        assertEquals(4, wrap.items().size());
    }

    @Test
    void picksNeverRepeatInsideOneBatch() {
        List<FoodCandidateRow> candidates = new ArrayList<>();
        for (long id = 1; id <= 40; id++) {
            candidates.add(dish(id, id % 9, FoodCategory.values()[(int) (id % 4)], id % 7, id % 5,
                    id % 3 == 0 ? new String[]{"dinner"} : new String[0]));
        }
        List<Long> picked = ids(FoodRecommender.pick(candidates, MealTime.DINNER, true, Set.of(), 20));
        assertEquals(picked.size(), Set.copyOf(picked).size());
        Map<Long, Integer> perRestaurant = new HashMap<>();
        for (Long id : picked) perRestaurant.merge(candidates.get((int) (id - 1)).getRestaurantId(), 1, Integer::sum);
        assertTrue(perRestaurant.values().stream().allMatch(n -> n <= FoodRecommender.MAX_PER_RESTAURANT));
        assertNotEquals(0, picked.size());
    }
}
