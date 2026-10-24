package you.v50to.eatwhat.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import you.v50to.eatwhat.data.dto.EditFoodDTO;
import you.v50to.eatwhat.data.dto.FoodCandidateRow;
import you.v50to.eatwhat.data.dto.FoodVO;
import you.v50to.eatwhat.data.dto.RecommendQueryDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.FoodCategory;
import you.v50to.eatwhat.data.po.Campus;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.data.po.FoodFavorite;
import you.v50to.eatwhat.data.po.LikeRecord;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.FoodFavoriteMapper;
import you.v50to.eatwhat.mapper.FoodMapper;
import you.v50to.eatwhat.mapper.LikeMapper;
import you.v50to.eatwhat.mapper.RestaurantMapper;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.service.storage.ObjectStorageService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FoodServiceTest {

    private FoodMapper foodMapper;
    private RestaurantMapper restaurantMapper;
    private ObjectStorageService objectStorageService;
    private BrowseHistoryService browseHistoryService;
    private FoodTagService foodTagService;
    private FoodFavoriteMapper foodFavoriteMapper;
    private LikeMapper likeMapper;
    private FoodService foodService;
    private CampusService campusService;
    private RecommendationSeenStore seenStore;

    @BeforeEach
    void setUp() {
        foodMapper = mock(FoodMapper.class);
        restaurantMapper = mock(RestaurantMapper.class);
        objectStorageService = mock(ObjectStorageService.class);
        browseHistoryService = mock(BrowseHistoryService.class);
        foodTagService = mock(FoodTagService.class);
        foodFavoriteMapper = mock(FoodFavoriteMapper.class);
        likeMapper = mock(LikeMapper.class);

        foodService = spy(new FoodService());
        ReflectionTestUtils.setField(foodService, "foodMapper", foodMapper);
        ReflectionTestUtils.setField(foodService, "restaurantMapper", restaurantMapper);
        ReflectionTestUtils.setField(foodService, "objectStorageService", objectStorageService);
        ReflectionTestUtils.setField(foodService, "browseHistoryService", browseHistoryService);
        ReflectionTestUtils.setField(foodService, "foodTagService", foodTagService);
        ReflectionTestUtils.setField(foodService, "foodFavoriteMapper", foodFavoriteMapper);
        ReflectionTestUtils.setField(foodService, "likeMapper", likeMapper);

        campusService = mock(CampusService.class);
        Campus softwarePark = new Campus();
        softwarePark.setId(1L);
        softwarePark.setIsDefault(true);
        when(campusService.resolve(null)).thenReturn(softwarePark);
        seenStore = mock(RecommendationSeenStore.class);
        when(seenStore.key(any(), anyLong(), any(), any())).thenReturn("seen-key");
        when(seenStore.seen("seen-key")).thenReturn(java.util.Set.of());
        ReflectionTestUtils.setField(foodService, "campusService", campusService);
        ReflectionTestUtils.setField(foodService, "seenStore", seenStore);
        doReturn(true).when(foodService).personalizationAllowed();

        when(objectStorageService.signGetUrls(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(99L).when(foodService).currentUserId();
        doReturn(99L).when(foodService).currentUserIdOrNull();
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void ownerCannotEditOrDeleteAnotherAuthorsFood() {
        Food food = buildFood(10L, "别人的菜");
        food.setAccountId(100L);
        when(foodMapper.selectById(10L)).thenReturn(food);
        assertEquals(BizCode.NO_PERMISSION.getCode(), foodService.editMyFood(10L, new EditFoodDTO()).getCode());
        assertEquals(BizCode.NO_PERMISSION.getCode(), foodService.deleteMyFood(10L).getCode());
        verify(foodMapper, never()).update(any(Food.class), any());
        verify(foodMapper, never()).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void invertedBudgetAndFavoriteSortOnDiscoveryAreRejectedBeforeQuery() {
        var query = new you.v50to.eatwhat.data.dto.FoodQueryDTO();
        query.setMinPrice(new BigDecimal("30"));
        query.setMaxPrice(new BigDecimal("20"));
        assertEquals(BizCode.PARAM_INVALID.getCode(), foodService.queryFoods(query, false).getCode());
        query.setMinPrice(null);
        query.setSort(you.v50to.eatwhat.data.dto.FoodQueryDTO.Sort.FAVORITED_AT);
        assertEquals(BizCode.PARAM_INVALID.getCode(), foodService.queryFoods(query, false).getCode());
        org.mockito.Mockito.verifyNoInteractions(foodMapper);
    }

    @Test
    void guestCannotBypassUserUploadListAuthenticationThroughThePublicFilter() {
        var query=new you.v50to.eatwhat.data.dto.FoodQueryDTO();query.setUploaderId(100L);
        org.mockito.Mockito.doThrow(new IllegalStateException("login-required")).when(foodService).currentUserId();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->foodService.queryFoods(query,false));
        org.mockito.Mockito.verifyNoInteractions(foodMapper);
    }

    @Test
    void anotherAuthorsUploadsUseTheVisitorsPrivateTagsLikesAndFavorites() {
        var query=new you.v50to.eatwhat.data.dto.FoodQueryDTO();query.setUploaderId(100L);
        Food food=buildFood(10L,"作者上传的菜");food.setAccountId(100L);
        when(foodMapper.selectFoods(eq(query),eq(99L),eq(false),any(),anyLong(),anyInt()))
                .thenReturn(List.of(food));
        when(foodMapper.countFoods(eq(query),eq(99L),eq(false),any())).thenReturn(1L);
        when(likeMapper.selectLikedTargetIds(99L,"food",List.of(10L))).thenReturn(List.of(10L));
        when(foodFavoriteMapper.selectFavoriteFoodIds(99L,List.of(10L))).thenReturn(List.of());
        var result=foodService.queryFoods(query,false);var item=result.getData().getItems().getFirst();
        assertTrue(item.getIsLiked());assertFalse(item.getIsFavorite());
        verify(foodTagService).fillFoodTagViews(any(),eq(99L));
        verify(foodTagService,never()).fillFoodTagViews(any(),eq(100L));
        verify(likeMapper,never()).selectLikedTargetIds(eq(100L),any(),any());
        verify(foodFavoriteMapper,never()).selectFavoriteFoodIds(eq(100L),any());
    }

    @Test
    void favoriteFoodShouldFailWhenFoodNotExists() {
        when(foodMapper.selectById(10L)).thenReturn(null);

        Result<Void> result = foodService.favoriteFood(10L);

        assertEquals(BizCode.FOOD_NOT_FOUND.getCode(), result.getCode());
        verify(foodFavoriteMapper, never()).insert(any(FoodFavorite.class));
    }

    @Test
    void favoriteFoodShouldBeIdempotentWhenAlreadyFavorited() {
        when(foodMapper.selectById(10L)).thenReturn(new Food());
        when(foodFavoriteMapper.selectOne(any())).thenReturn(new FoodFavorite());

        Result<Void> result = foodService.favoriteFood(10L);

        assertEquals(0, result.getCode());
        verify(foodFavoriteMapper, never()).insert(any(FoodFavorite.class));
    }

    @Test
    void favoriteFoodShouldInsertWhenFirstTimeFavorited() {
        when(foodMapper.selectById(10L)).thenReturn(new Food());
        when(foodFavoriteMapper.selectOne(any())).thenReturn(null);

        Result<Void> result = foodService.favoriteFood(10L);

        assertEquals(0, result.getCode());
        verify(foodFavoriteMapper).insert(any(FoodFavorite.class));
    }

    @Test
    void unfavoriteFoodShouldFailWhenFoodNotExists() {
        when(foodMapper.selectById(10L)).thenReturn(null);

        Result<Void> result = foodService.unfavoriteFood(10L);

        assertEquals(BizCode.FOOD_NOT_FOUND.getCode(), result.getCode());
        verify(foodFavoriteMapper, never()).delete(any());
    }

    @Test
    void unfavoriteFoodShouldBeIdempotent() {
        when(foodMapper.selectById(10L)).thenReturn(new Food());

        Result<Void> result = foodService.unfavoriteFood(10L);

        assertEquals(0, result.getCode());
        verify(foodFavoriteMapper).delete(any());
    }

    @Test
    void getMyFavoriteFoodsShouldReturnFavoritedItemsWithSignedPictures() {
        Food food = buildFood(10L, "糖醋里脊");
        when(foodFavoriteMapper.selectFavoriteFoodsByAccountId(99L, 0, 20)).thenReturn(List.of(food));
        when(foodFavoriteMapper.countFavoritesByAccountId(99L)).thenReturn(1L);
        when(foodFavoriteMapper.selectFavoriteFoodIds(99L, List.of(10L))).thenReturn(List.of(10L));
        when(likeMapper.selectLikedTargetIds(99L, "food", List.of(10L))).thenReturn(List.of());

        Result<PageResult<you.v50to.eatwhat.data.dto.FoodVO>> result = foodService.getMyFavoriteFoods(99L, 1, 20);

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals(1, result.getData().getItems().size());
        assertTrue(Boolean.TRUE.equals(result.getData().getItems().getFirst().getIsFavorite()));
        assertEquals(List.of("food-1.png"), result.getData().getItems().getFirst().getPictureUrl());
        verify(foodTagService).fillFoodTagViews(any(), eq(99L));
        verify(foodFavoriteMapper).selectFavoriteFoodIds(99L, List.of(10L));
    }

    @Test
    void getFoodDetailShouldMarkFavoriteState() {
        Food food = buildFood(10L, "红烧肉");
        when(foodMapper.selectById(10L)).thenReturn(food);
        when(foodFavoriteMapper.selectFavoriteFoodIds(99L, List.of(10L))).thenReturn(List.of(10L));
        when(likeMapper.selectLikedTargetIds(99L, "food", List.of(10L))).thenReturn(List.of(10L));

        Result<you.v50to.eatwhat.data.dto.FoodVO> result = foodService.getFoodDetail(10L);

        assertEquals(0, result.getCode());
        assertTrue(Boolean.TRUE.equals(result.getData().getIsFavorite()));
        assertTrue(Boolean.TRUE.equals(result.getData().getIsLiked()));
        verify(browseHistoryService).recordBrowse(99L, "food", 10L);
    }

    @Test
    void guestFoodDetailSkipsPersonalStateHistoryAndOwner() {
        doReturn(null).when(foodService).currentUserIdOrNull();
        when(foodMapper.selectById(10L)).thenReturn(buildFood(10L, "红烧肉"));

        Result<FoodVO> result = foodService.getFoodDetail(10L);

        assertEquals(0, result.getCode());
        assertFalse(result.getData().getIsLiked());
        assertFalse(result.getData().getIsFavorite());
        assertNull(result.getData().getAccountId());
        assertEquals("tester", result.getData().getUploaderName());
        verify(browseHistoryService, never()).recordBrowse(any(), any(), any());
        verify(likeMapper, never()).selectLikedTargetIds(any(), any(), any());
        verify(foodFavoriteMapper, never()).selectFavoriteFoodIds(any(), any());
        verify(foodTagService).fillFoodTagViews(any(), eq(null));
    }

    @Test
    void guestRecommendationUsesAnonymousProfile() {
        doReturn(null).when(foodService).currentUserIdOrNull();
        when(foodMapper.selectRecommendationCandidates(null, 1L, true, null, FoodService.CANDIDATE_LIMIT))
                .thenReturn(List.of(candidate(10L, "鱼香肉丝")));

        Result<List<FoodVO>> result = foodService.recommendFoods(new RecommendQueryDTO());

        assertEquals(1, result.getData().size());
        assertNull(result.getData().getFirst().getAccountId());
        verify(foodService, never()).currentUserId();
        verify(foodService, never()).personalizationAllowed();
        verifyNoInteractions(seenStore);
    }

    @Test
    void getMyFoodShouldMarkNonFavoriteState() {
        Food food = buildFood(10L, "鱼香肉丝");
        when(foodMapper.selectByAccountId(99L, 0, 20)).thenReturn(List.of(food));
        when(foodMapper.countByAccountId(99L)).thenReturn(1L);
        when(foodFavoriteMapper.selectFavoriteFoodIds(99L, List.of(10L))).thenReturn(List.of());
        when(likeMapper.selectLikedTargetIds(99L, "food", List.of(10L))).thenReturn(List.of());

        Result<PageResult<you.v50to.eatwhat.data.dto.FoodVO>> result = foodService.getMyFood(99L, 1, 20);

        assertEquals(0, result.getCode());
        assertFalse(Boolean.TRUE.equals(result.getData().getItems().getFirst().getIsFavorite()));
        assertFalse(Boolean.TRUE.equals(result.getData().getItems().getFirst().getIsLiked()));
    }

    @Test
    void recommendFoodsShouldReturnRestaurantAndCategoryFields() {
        FoodCandidateRow food = candidate(10L, "鱼香肉丝");
        food.setRestaurantAddress("万达金街 1 层");
        food.setRestaurantGcjLng(117.1);
        food.setRestaurantGcjLat(36.6);
        food.setHubId(3L);
        food.setHubName("万达金街");
        when(foodMapper.selectRecommendationCandidates(99L, 1L, true, null, FoodService.CANDIDATE_LIMIT)).thenReturn(List.of(food));
        when(foodFavoriteMapper.selectFavoriteFoodIds(99L, List.of(10L))).thenReturn(List.of(10L));
        when(likeMapper.selectLikedTargetIds(99L, "food", List.of(10L))).thenReturn(List.of(10L));

        Result<List<FoodVO>> result = foodService.recommendFoods(new RecommendQueryDTO());

        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().size());
        FoodVO item = result.getData().getFirst();
        assertEquals("川味小馆", item.getRestaurantName());
        assertEquals("万达金街 1 层", item.getRestaurantAddress());
        assertEquals(117.1, item.getRestaurantGcjLng());
        assertEquals("万达金街", item.getHubName());
        assertEquals(FoodCategory.STAPLE, item.getCategory());
        assertEquals("主食", item.getCategoryLabel());
        assertEquals(List.of("food-1.png"), item.getPictureUrl());
        assertTrue(Boolean.TRUE.equals(item.getIsLiked()));
        assertTrue(Boolean.TRUE.equals(item.getIsFavorite()));
        verify(foodTagService).fillFoodTagViews(any(), eq(99L));
        verify(seenStore).record("seen-key", List.of(10L), false);
    }

    @Test
    void recommendSizeDefaultsToEightAndLegacyLimitIsClamped() {
        List<FoodCandidateRow> many = new java.util.ArrayList<>();
        for (long id = 1; id <= 30; id++) {
            FoodCandidateRow row = candidate(id, "菜" + id);
            row.setRestaurantId(id);
            many.add(row);
        }
        when(foodMapper.selectRecommendationCandidates(any(), any(), anyBoolean(), any(), anyInt())).thenReturn(many);

        assertEquals(8, foodService.recommendFoods(new RecommendQueryDTO()).getData().size());
        RecommendQueryDTO legacy = new RecommendQueryDTO();
        legacy.setLimit(99);
        assertEquals(20, foodService.recommendFoods(legacy).getData().size());
        legacy.setLimit(-5);
        assertEquals(1, foodService.recommendFoods(legacy).getData().size());
        RecommendQueryDTO sized = new RecommendQueryDTO();
        sized.setSize(3);
        sized.setLimit(10);
        assertEquals(3, foodService.recommendFoods(sized).getData().size());
        verify(browseHistoryService, never()).recordBrowse(any(), any(), any());
    }

    @Test
    void unverifiedAccountGetsNoPersonalFactorsButStillDeduplicates() {
        doReturn(false).when(foodService).personalizationAllowed();
        when(foodMapper.selectRecommendationCandidates(null, 1L, true, "staple", FoodService.CANDIDATE_LIMIT))
                .thenReturn(List.of(candidate(10L, "鱼香肉丝")));
        RecommendQueryDTO query = new RecommendQueryDTO();
        query.setCategory(FoodCategory.STAPLE);

        assertEquals(1, foodService.recommendFoods(query).getData().size());
        verify(seenStore).key(eq("u99"), eq(1L), eq("staple"), any());
    }

    @Test
    void unknownOrClosedCampusIsRejected() {
        when(campusService.resolve(5L)).thenReturn(null);
        RecommendQueryDTO query = new RecommendQueryDTO();
        query.setCampusId(5L);

        assertEquals(BizCode.PARAM_INVALID.getCode(), foodService.recommendFoods(query).getCode());
        verifyNoInteractions(foodMapper);
    }

    @Test
    void mealTimeDefaultsToBeijingTimeAndGuestsDeduplicateByDevice() {
        doReturn(null).when(foodService).currentUserIdOrNull();
        doReturn(LocalDateTime.of(2026, 10, 23, 21, 0)).when(foodService).now();
        FoodCandidateRow lunch = candidate(1L, "午饭菜");
        lunch.setMealTimes(new String[]{"lunch"});
        FoodCandidateRow night = candidate(2L, "夜宵菜");
        night.setMealTimes(new String[]{"night"});
        night.setRestaurantId(8L);
        when(foodMapper.selectRecommendationCandidates(null, 1L, true, null, FoodService.CANDIDATE_LIMIT))
                .thenReturn(List.of(lunch, night));
        RecommendQueryDTO query = new RecommendQueryDTO();
        query.setDeviceId("device-123456");

        List<FoodVO> items = foodService.recommendFoods(query).getData();

        assertEquals(List.of(2L, 1L), items.stream().map(FoodVO::getId).toList());
        verify(seenStore).key("ddevice-123456", 1L, null, java.time.LocalDate.of(2026, 10, 23));
    }

    @Test
    void likeFoodShouldFailWhenFoodNotExists() {
        when(foodMapper.selectById(10L)).thenReturn(null);

        Result<Void> result = foodService.likeFood(10L);

        assertEquals(BizCode.FOOD_NOT_FOUND.getCode(), result.getCode());
        verify(likeMapper, never()).insert(any(LikeRecord.class));
    }

    @Test
    void likeFoodShouldBeIdempotentWhenAlreadyLiked() {
        when(foodMapper.selectById(10L)).thenReturn(new Food());
        LikeRecord like = new LikeRecord();
        like.setDeletedAt(null);
        when(likeMapper.selectOne(any())).thenReturn(like);

        Result<Void> result = foodService.likeFood(10L);

        assertEquals(0, result.getCode());
        verify(likeMapper, never()).insert(any(LikeRecord.class));
        verify(likeMapper, never()).updateById(any(LikeRecord.class));
    }

    @Test
    void likeFoodShouldInsertAndIncreaseCount() {
        when(foodMapper.selectById(10L)).thenReturn(new Food());
        when(likeMapper.selectOne(any())).thenReturn(null);

        Result<Void> result = foodService.likeFood(10L);

        assertEquals(0, result.getCode());
        verify(likeMapper).insert(any(LikeRecord.class));
    }

    @Test
    void likeFoodShouldRestoreSoftDeletedLike() {
        when(foodMapper.selectById(10L)).thenReturn(new Food());
        LikeRecord like = new LikeRecord();
        like.setId(123L);
        like.setDeletedAt(1L);
        when(likeMapper.selectOne(any())).thenReturn(like);

        Result<Void> result = foodService.likeFood(10L);

        assertEquals(0, result.getCode());
        verify(likeMapper).restore(123L);
    }

    @Test
    void unlikeFoodShouldBeIdempotent() {
        when(foodMapper.selectById(10L)).thenReturn(new Food());
        when(likeMapper.selectOne(any())).thenReturn(null);

        Result<Void> result = foodService.unlikeFood(10L);

        assertEquals(0, result.getCode());
        verify(likeMapper, never()).updateById(any(LikeRecord.class));
    }

    @Test
    void unlikeFoodShouldSoftDeleteLikeWhenDeleted() {
        when(foodMapper.selectById(10L)).thenReturn(new Food());
        LikeRecord like = new LikeRecord();
        like.setDeletedAt(null);
        when(likeMapper.selectOne(any())).thenReturn(like);

        Result<Void> result = foodService.unlikeFood(10L);

        assertEquals(0, result.getCode());
        verify(likeMapper).updateById(like);
    }

    @Test
    void deleteFoodShouldReleaseAllReferencesOfTheFoodAfterTheDelete() {
        FileLifecycleService files = mock(FileLifecycleService.class);
        ReflectionTestUtils.setField(foodService, "fileLifecycleService", files);
        when(foodMapper.selectById(10L)).thenReturn(new Food());
        when(foodMapper.deleteById(10L)).thenReturn(1);

        assertEquals(0, foodService.deleteFood(10L).getCode());

        verify(files).releaseReferences("food", "10", null, "ENTITY_DELETED");
    }

    @Test
    void deleteFoodShouldNotReleaseWhenTheFoodIsMissingOrNotDeleted() {
        FileLifecycleService files = mock(FileLifecycleService.class);
        ReflectionTestUtils.setField(foodService, "fileLifecycleService", files);
        when(foodMapper.selectById(10L)).thenReturn(null);
        assertEquals(BizCode.FOOD_NOT_FOUND.getCode(), foodService.deleteFood(10L).getCode());

        when(foodMapper.selectById(11L)).thenReturn(new Food());
        when(foodMapper.deleteById(11L)).thenReturn(0);
        assertEquals(BizCode.FOOD_NOT_FOUND.getCode(), foodService.deleteFood(11L).getCode());

        verify(files, never()).releaseReferences(any(), any(), any(), any());
    }

    @Test
    void deleteFoodWithoutLifecycleServiceKeepsLegacyBehavior() {
        when(foodMapper.selectById(10L)).thenReturn(new Food());
        when(foodMapper.deleteById(10L)).thenReturn(1);

        assertEquals(0, foodService.deleteFood(10L).getCode());
    }

    @Test
    void editFoodShouldReleaseOnlyTheDroppedPictureKeys() {
        FileLifecycleService files = mock(FileLifecycleService.class);
        ReflectionTestUtils.setField(foodService, "fileLifecycleService", files);
        // A fresh row per read, as the real mapper returns, so the edit cannot mutate the "before" snapshot.
        when(foodMapper.selectById(10L)).thenAnswer(invocation -> {
            Food stored = buildFood(10L, "红烧肉");
            stored.setPictureUrl(new String[]{"keep.png", "drop.png"});
            return stored;
        });
        when(foodMapper.updateById(any(Food.class))).thenReturn(1);
        EditFoodDTO dto = new EditFoodDTO();
        dto.setPictureUrl(List.of("keep.png", "new.png"));

        assertEquals(0, foodService.editFood(10L, dto).getCode());

        verify(files).releaseReferences("food", "10", Set.of("drop.png"), "KEY_REPLACED");
    }

    @Test
    void editFoodShouldNotReleaseWhenPictureKeysAreUnchangedOrAbsent() {
        FileLifecycleService files = mock(FileLifecycleService.class);
        ReflectionTestUtils.setField(foodService, "fileLifecycleService", files);
        when(foodMapper.selectById(10L)).thenAnswer(invocation -> {
            Food stored = buildFood(10L, "红烧肉");
            stored.setPictureUrl(new String[]{"a.png", "b.png"});
            return stored;
        });
        when(foodMapper.updateById(any(Food.class))).thenReturn(1);

        EditFoodDTO reordered = new EditFoodDTO();
        reordered.setPictureUrl(List.of("b.png", "a.png"));
        assertEquals(0, foodService.editFood(10L, reordered).getCode());

        EditFoodDTO nameOnly = new EditFoodDTO();
        nameOnly.setName("renamed");
        assertEquals(0, foodService.editFood(10L, nameOnly).getCode());

        verify(files, never()).releaseReferences(any(), any(), any(), any());
    }

    private FoodCandidateRow candidate(Long id, String name) {
        FoodCandidateRow row = new FoodCandidateRow();
        org.springframework.beans.BeanUtils.copyProperties(buildFood(id, name), row);
        row.setQualityScore(0.0);
        row.setPersonalScore(0.0);
        return row;
    }

    private Food buildFood(Long id, String name) {
        Food food = new Food();
        food.setId(id);
        food.setAccountId(88L);
        food.setUploaderName("tester");
        food.setRestaurantId(7L);
        food.setRestaurantName("川味小馆");
        food.setName(name);
        food.setDescription("desc");
        food.setPrice(new BigDecimal("12.50"));
        food.setCategory(FoodCategory.STAPLE);
        food.setPictureUrl(new String[]{"food-1.png"});
        return food;
    }
}
