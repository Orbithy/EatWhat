package you.v50to.eatwhat.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import you.v50to.eatwhat.data.dto.FoodVO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.FoodCategory;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.data.po.FoodFavorite;
import you.v50to.eatwhat.data.po.LikeRecord;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.FoodFavoriteMapper;
import you.v50to.eatwhat.mapper.FoodMapper;
import you.v50to.eatwhat.mapper.LikeMapper;
import you.v50to.eatwhat.mapper.RestaurantMapper;
import you.v50to.eatwhat.service.storage.ObjectStorageService;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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

        when(objectStorageService.signGetUrls(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(99L).when(foodService).currentUserId();
    }

    @AfterEach
    void tearDown() {
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
        Food food = buildFood(10L, "鱼香肉丝");
        when(foodMapper.selectRecommended(10)).thenReturn(List.of(food));
        when(foodFavoriteMapper.selectFavoriteFoodIds(99L, List.of(10L))).thenReturn(List.of(10L));
        when(likeMapper.selectLikedTargetIds(99L, "food", List.of(10L))).thenReturn(List.of(10L));

        Result<List<FoodVO>> result = foodService.recommendFoods(10);

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals(1, result.getData().size());
        FoodVO item = result.getData().getFirst();
        assertEquals("川味小馆", item.getRestaurantName());
        assertEquals(FoodCategory.STAPLE, item.getCategory());
        assertEquals("主食", item.getCategoryLabel());
        assertEquals(List.of("food-1.png"), item.getPictureUrl());
        assertTrue(Boolean.TRUE.equals(item.getIsLiked()));
        assertTrue(Boolean.TRUE.equals(item.getIsFavorite()));
        verify(foodTagService).fillFoodTagViews(any(), eq(99L));
    }

    @Test
    void recommendFoodsShouldClampLimit() {
        when(foodMapper.selectRecommended(20)).thenReturn(List.of());

        Result<List<FoodVO>> result = foodService.recommendFoods(99);

        assertEquals(0, result.getCode());
        verify(foodMapper).selectRecommended(20);
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
        like.setDeletedAt(1L);
        when(likeMapper.selectOne(any())).thenReturn(like);

        Result<Void> result = foodService.likeFood(10L);

        assertEquals(0, result.getCode());
        verify(likeMapper).updateById(like);
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
