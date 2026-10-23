package you.v50to.eatwhat.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import you.v50to.eatwhat.controller.FoodController;
import you.v50to.eatwhat.controller.RestaurantController;
import you.v50to.eatwhat.data.dto.RecommendQueryDTO;
import you.v50to.eatwhat.data.enums.MealTime;
import you.v50to.eatwhat.data.dto.CreateFoodDTO;
import you.v50to.eatwhat.data.dto.FoodQueryDTO;
import you.v50to.eatwhat.data.vo.CreatedResourceVO;
import you.v50to.eatwhat.data.dto.EditRestaurantDTO;
import you.v50to.eatwhat.data.dto.FoodVO;
import you.v50to.eatwhat.data.dto.RestaurantDTO;
import you.v50to.eatwhat.data.dto.SearchRestaurantsDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.FoodCategory;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.data.vo.FoodTagSummaryVO;
import you.v50to.eatwhat.data.vo.FoodTagViewVO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.service.FoodService;
import you.v50to.eatwhat.service.FoodTagService;
import you.v50to.eatwhat.service.RestaurantService;
import you.v50to.eatwhat.support.MvcContractSupport;
import you.v50to.eatwhat.utils.GeoUtil;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP binding and JSON contracts only; service/database behavior is tested separately. */
class CatalogApiContractTest extends MvcContractSupport {
    private RestaurantService restaurants;
    private FoodService foods;
    private FoodTagService tags;
    private MockMvc http;

    @BeforeEach
    void createControllers() {
        restaurants = mock(RestaurantService.class);
        foods = mock(FoodService.class);
        tags = mock(FoodTagService.class);
        RestaurantController restaurantController = new RestaurantController();
        ReflectionTestUtils.setField(restaurantController, "restaurantService", restaurants);
        FoodController foodController = new FoodController();
        ReflectionTestUtils.setField(foodController, "foodService", foods);
        ReflectionTestUtils.setField(foodController, "foodTagService", tags);
        http = mvc(restaurantController, foodController);
    }

    @Test
    void recommendationBindsQueryParametersAndKeepsListEnvelope() throws Exception {
        FoodVO item = new FoodVO();
        item.setId(6001L);
        item.setName("口味匹配菜品");
        item.setIsLiked(false);
        item.setIsFavorite(false);
        when(foods.recommendFoods(any(RecommendQueryDTO.class))).thenReturn(Result.ok(List.of(item)));

        http.perform(as(get("/foods/recommend").param("campusId", "1").param("mealTime", "night")
                        .param("category", "hot_pot").param("size", "8").param("deviceId", "device-123456"), "verified"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(6001))
                .andExpect(jsonPath("$.data[0].isLiked").value(false))
                .andExpect(jsonPath("$.data[0].isFavorite").value(false));
        ArgumentCaptor<RecommendQueryDTO> query = ArgumentCaptor.forClass(RecommendQueryDTO.class);
        verify(foods).recommendFoods(query.capture());
        assertEquals(1L, query.getValue().getCampusId());
        assertEquals(MealTime.NIGHT, query.getValue().getMealTime());
        assertEquals(FoodCategory.HOT_POT, query.getValue().getCategory());
        assertEquals(8, query.getValue().getSize());
        assertEquals("device-123456", query.getValue().getDeviceId());
    }

    @Test
    void recommendationRejectsOutOfRangeSizeAndMalformedDeviceId() throws Exception {
        http.perform(get("/foods/recommend").param("size", "21"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10001));
        http.perform(get("/foods/recommend").param("deviceId", "bad id"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10001));
        verify(foods, never()).recommendFoods(any(RecommendQueryDTO.class));
    }

    @Test
    void recommendationIsOpenToGuestsAndUnverifiedUsers() throws Exception {
        when(foods.recommendFoods(any(RecommendQueryDTO.class))).thenReturn(Result.ok(List.of()));
        http.perform(get("/foods/recommend"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        http.perform(as(get("/foods/recommend")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(foods, times(2)).recommendFoods(any(RecommendQueryDTO.class));
    }

    @Test
    void restaurantSearchBindsHubFilterAndDefaultPagingWithoutRequiringVerified() throws Exception {
        Restaurant item = new Restaurant();
        item.setId(4201L);
        item.setAccountId(99L);
        item.setHubId(71L);
        item.setName("校园餐厅");
        item.setLocation(GeoUtil.createPoint(117.02, 36.67));
        item.setPictureUrl(new String[]{"https://assets.example.test/restaurants/photo.jpg"});
        when(restaurants.searchRestaurants(any())).thenReturn(Result.ok(PageResult.of(List.of(item), 1L, 20L, 21L)));

        http.perform(as(get("/restaurant/search").param("keyword", "餐厅").param("hubId", "71")
                        .param("gcjLng", "117.03").param("gcjLat", "36.68").param("radius", "800")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("ok")).andExpect(jsonPath("$.timestamp").isNumber())
                .andExpect(jsonPath("$.data.currentPage").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.totalItems").value(21))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value(4201))
                .andExpect(jsonPath("$.data.items[0].hubId").value(71))
                .andExpect(jsonPath("$.data.items[0].location.type").value("Point"))
                .andExpect(jsonPath("$.data.items[0].location.coordinates[0]").value(117.02))
                .andExpect(jsonPath("$.data.items[0].location.srid").value(4326));

        ArgumentCaptor<SearchRestaurantsDTO> query = ArgumentCaptor.forClass(SearchRestaurantsDTO.class);
        verify(restaurants).searchRestaurants(query.capture());
        assertEquals(71L, query.getValue().getHubId());
        assertEquals("餐厅", query.getValue().getKeyword());
        assertEquals(117.03, query.getValue().getGcjLng());
        assertEquals(36.68, query.getValue().getGcjLat());
        assertEquals(800D, query.getValue().getRadius());
        assertEquals(1, query.getValue().getPage());
        assertEquals(20, query.getValue().getPageSize());
    }

    @Test
    void restaurantSearchMissingCoordinatesReturnsValidationEnvelope() throws Exception {
        http.perform(as(get("/restaurant/search").param("hubId", "71")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.timestamp").isNumber());
        verifyNoInteractions(restaurants);
    }

    @Test
    void restaurantCreateBindsNumericReferencesAndStorageKeys() throws Exception {
        when(restaurants.addRestaurant(any())).thenReturn(Result.ok(new CreatedResourceVO(4201L)));
        http.perform(as(post("/restaurant/add").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"测试餐厅","address":"测试路1号","gcjLng":117.03,"gcjLat":36.68,
                         "cityId":5,"hubId":71,"pictureUrl":["restaurants/99/test.jpg"]}
                        """), "verified"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        ArgumentCaptor<RestaurantDTO> body = ArgumentCaptor.forClass(RestaurantDTO.class);
        verify(restaurants).addRestaurant(body.capture());
        assertEquals(71L, body.getValue().getHubId());
        assertEquals(5, body.getValue().getCityId());
        assertEquals(List.of("restaurants/99/test.jpg"), body.getValue().getPictureUrl());
    }

    @Test
    void restaurantAdminEditPreservesPartialDtoAndDeleteMethod() throws Exception {
        when(restaurants.editRestaurant(eq(4201L), any())).thenReturn(Result.ok());
        when(restaurants.deleteRestaurant(4201L)).thenReturn(Result.ok());
        String token = loginAs(99L, "admin");
        http.perform(put("/restaurant/edit/4201").header("satoken", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hubId\":72}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        ArgumentCaptor<EditRestaurantDTO> edit = ArgumentCaptor.forClass(EditRestaurantDTO.class);
        verify(restaurants).editRestaurant(eq(4201L), edit.capture());
        assertEquals(72L, edit.getValue().getHubId());
        assertNull(edit.getValue().getName());
        assertNull(edit.getValue().getGcjLng());
        http.perform(delete("/restaurant/delete/4201").header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(restaurants).deleteRestaurant(4201L);
    }

    @Test
    void restaurantListBindsNewHubFilterAlongsideExplicitPagination() throws Exception {
        when(restaurants.listRestaurants("饭", 71L, 2, 5))
                .thenReturn(Result.ok(PageResult.of(List.of(), 2L, 5L, 0L)));
        http.perform(as(get("/restaurant/list").param("keyword", "饭").param("hubId", "71")
                        .param("page", "2").param("pageSize", "5"), "admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.currentPage").value(2));
        verify(restaurants).listRestaurants("饭", 71L, 2, 5);
    }

    @Test
    void foodListBindsRepeatedTagFiltersAndPreservesFoodJson() throws Exception {
        FoodVO item = new FoodVO();
        item.setId(6001L);
        item.setRestaurantId(4201L);
        item.setName("米饭");
        item.setCategory(FoodCategory.STAPLE);
        item.setPrice(new BigDecimal("12.50"));
        item.setIsLiked(true);
        item.setIsFavorite(false);
        item.setPictureUrl(List.of("https://assets.example.test/foods/rice.jpg"));
        when(foods.queryFoods(any(), eq(false)))
                .thenReturn(Result.ok(PageResult.of(List.of(item), 1L, 20L, 1L)));
        http.perform(as(get("/foods/list").param("restaurantId", "4201")
                        .param("systemTagIds", "2", "3").param("myCustomTagIds", "7")
                        .param("myCustomTagNames", "清淡", "午餐"), "verified"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(6001))
                .andExpect(jsonPath("$.data.items[0].restaurantId").value(4201))
                .andExpect(jsonPath("$.data.items[0].category").value("staple"))
                .andExpect(jsonPath("$.data.items[0].price").value(12.5))
                .andExpect(jsonPath("$.data.items[0].isLiked").value(true))
                .andExpect(jsonPath("$.data.items[0].isFavorite").value(false))
                .andExpect(jsonPath("$.data.items[0].pictureUrl[0]").value("https://assets.example.test/foods/rice.jpg"));
        verify(foods).queryFoods(argThat(q -> q.getRestaurantId().equals(4201L)
                && q.getPage() == 1 && q.getPageSize() == 20
                && q.getSystemTagIds().equals(List.of(2L, 3L))
                && q.getMyCustomTagIds().equals(List.of(7L))
                && q.getMyCustomTagNames().equals(List.of("清淡", "午餐"))), eq(false));
    }

    @Test
    void foodCreateAcceptsCaseInsensitiveCategoryAndObjectKey() throws Exception {
        when(foods.uploadFood(any())).thenReturn(Result.ok(new CreatedResourceVO(6001L)));
        http.perform(as(post("/foods/upload").contentType(MediaType.APPLICATION_JSON).content("""
                        {"restaurantId":4201,"name":"米饭","price":12.50,"category":"STAPLE",
                         "pictureUrl":["foods/99/rice.jpg"]}
                        """), "verified"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        ArgumentCaptor<CreateFoodDTO> dto = ArgumentCaptor.forClass(CreateFoodDTO.class);
        verify(foods).uploadFood(dto.capture());
        assertEquals(FoodCategory.STAPLE, dto.getValue().getCategory());
        assertEquals(new BigDecimal("12.50"), dto.getValue().getPrice());
        assertEquals(4201L, dto.getValue().getRestaurantId());
    }

    @Test
    void foodCreateKeepsNullablePriceAndCategoryCompatible() throws Exception {
        when(foods.uploadFood(any())).thenReturn(Result.ok(new CreatedResourceVO(6001L)));
        http.perform(as(post("/foods/upload").contentType(MediaType.APPLICATION_JSON).content("""
                        {"restaurantId":4201,"name":"未定价菜品","pictureUrl":["foods/99/no-price"]}
                        """), "verified"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<CreateFoodDTO> dto = ArgumentCaptor.forClass(CreateFoodDTO.class);
        verify(foods).uploadFood(dto.capture());
        assertEquals(4201L, dto.getValue().getRestaurantId());
        assertNull(dto.getValue().getPrice());
        assertNull(dto.getValue().getCategory());
    }

    @Test
    void foodCreateRejectsDownloadUrlInPlaceOfStorageKey() throws Exception {
        http.perform(as(post("/foods/upload").contentType(MediaType.APPLICATION_JSON).content("""
                        {"restaurantId":4201,"name":"米饭","pictureUrl":["https://assets.example.test/foods/rice.jpg"]}
                        """), "verified"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.msg").value(containsString("foods key")));
        verifyNoInteractions(foods);
    }

    @Test
    void foodUnlikeAndFavoriteKeepPostRoutes() throws Exception {
        when(foods.unlikeFood(6001L)).thenReturn(Result.ok());
        when(foods.favoriteFood(6001L)).thenReturn(Result.ok());
        String token = loginAs(99L, "verified");
        http.perform(post("/foods/6001/unlike").header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        http.perform(post("/foods/6001/favorite").header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(foods).unlikeFood(6001L);
        verify(foods).favoriteFood(6001L);
    }

    @Test
    void customTagsUseAuthenticatedAccountAndReturnSeparatePrivateList() throws Exception {
        when(tags.addCustomTagsToFood(eq(6001L), eq(99L), any())).thenReturn(Result.ok());
        FoodTagViewVO view = new FoodTagViewVO();
        view.setSystemTags(List.of());
        view.setMyCustomTags(List.of(new FoodTagSummaryVO(7L, "我的午餐")));
        when(tags.getFoodTagView(6001L, 99L)).thenReturn(Result.ok(view));
        String token = loginAs(99L, "verified");
        http.perform(post("/foods/6001/tags/custom").header("satoken", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"names\":[\"我的午餐\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(tags).addCustomTagsToFood(eq(6001L), eq(99L), argThat(dto -> dto.getNames().equals(List.of("我的午餐"))));
        http.perform(get("/foods/6001/tags").header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.systemTags").isEmpty())
                .andExpect(jsonPath("$.data.myCustomTags[0].id").value(7))
                .andExpect(jsonPath("$.data.myCustomTags[0].name").value("我的午餐"));
    }

    @Test
    void foodCatalogReadsAreOpenToGuests() throws Exception {
        FoodVO item = new FoodVO();
        item.setId(6001L);
        when(foods.getFoodDetail(6001L)).thenReturn(Result.ok(item));
        when(foods.queryFoods(any(), eq(false))).thenReturn(Result.ok(PageResult.of(List.of(), 1L, 20L, 0L)));
        when(tags.listSystemTags()).thenReturn(Result.ok(List.of()));
        http.perform(get("/foods/detail/6001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(6001));
        http.perform(get("/foods/list")).andExpect(jsonPath("$.code").value(0));
        http.perform(get("/foods/tags/system")).andExpect(jsonPath("$.code").value(0));
        http.perform(get("/foods/6001/tags")).andExpect(status().isOk());
        verify(tags).getFoodTagView(6001L, null);
    }

    @Test
    void writesWithoutSessionReturnExistingNotLoginEnvelope() throws Exception {
        http.perform(post("/foods/6001/like"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(20001))
                .andExpect(jsonPath("$.msg").value("用户未登录"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.timestamp").isNumber());
        http.perform(post("/foods/6001/favorite"))
                .andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
        http.perform(get("/foods/favorites"))
                .andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
        verifyNoInteractions(foods);
    }

    @Test
    void unverifiedUsersCanManageFavoritesButNotLike() throws Exception {
        when(foods.favoriteFood(6001L)).thenReturn(Result.ok());
        http.perform(as(post("/foods/6001/favorite")))
                .andExpect(jsonPath("$.code").value(0));
        verify(foods).favoriteFood(6001L);
    }

    @Test
    void verifiedRoleFailureRetainsBusinessErrorInsteadOfCallingService() throws Exception {
        http.perform(as(post("/foods/6001/like")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(20004))
                .andExpect(jsonPath("$.msg").value("权限不足：用户未验证"));
        verifyNoInteractions(foods);
    }

    @Test
    void businessExceptionRetainsBusinessCodeMessageAndNullData() throws Exception {
        when(foods.getFoodDetail(6001L)).thenThrow(new BizException(BizCode.FOOD_NOT_FOUND, "测试目标"));
        http.perform(as(get("/foods/detail/6001"), "verified"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(40008))
                .andExpect(jsonPath("$.msg").value("菜品不存在：测试目标"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void globalDiscoveryBindsLowercaseCategoryBudgetAndSorting() throws Exception {
        when(foods.queryFoods(any(), eq(false))).thenReturn(Result.ok(PageResult.of(List.of(), 1L, 20L, 0L)));
        http.perform(as(get("/foods/list").param("category", "staple").param("maxPrice", "20.50")
                .param("hubId", "71").param("keyword", "饭").param("sort", "PRICE_ASC"), "verified"))
                .andExpect(jsonPath("$.code").value(0));
        verify(foods).queryFoods(argThat(q -> q.getRestaurantId() == null
                && q.getCategory() == FoodCategory.STAPLE && q.getHubId() == 71L
                && q.getMaxPrice().equals(new BigDecimal("20.50"))
                && q.getSort() == FoodQueryDTO.Sort.PRICE_ASC), eq(false));
    }

    @Test
    void favoritesAcceptFiltersAndRejectInvalidPagingOrSort() throws Exception {
        when(foods.queryFoods(any(), eq(true))).thenReturn(Result.ok(PageResult.of(List.of(), 2L, 5L, 0L)));
        http.perform(as(get("/foods/favorites").param("myCustomTagIds", "7", "8")
                .param("page", "2").param("pageSize", "5"), "verified"))
                .andExpect(jsonPath("$.code").value(0));
        verify(foods).queryFoods(argThat(q -> q.getMyCustomTagIds().equals(List.of(7L, 8L))
                && q.getPage() == 2 && q.getPageSize() == 5), eq(true));
        http.perform(as(get("/foods/list").param("pageSize", "101"), "verified"))
                .andExpect(jsonPath("$.code").value(10001));
        http.perform(as(get("/foods/list").param("sort", "id;drop table foods"), "verified"))
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void createReturnsIdAndAcceptsInitialTags() throws Exception {
        when(foods.uploadFood(any())).thenReturn(Result.ok(new CreatedResourceVO(6001L)));
        http.perform(as(post("/foods/upload").contentType(MediaType.APPLICATION_JSON).content("""
                {"restaurantId":4201,"name":"米饭","pictureUrl":["foods/99/test.jpg"],
                 "systemTagIds":[2,3],"myCustomTagNames":["想吃"]}
                """), "verified"))
                .andExpect(jsonPath("$.data.id").value(6001));
        verify(foods).uploadFood(argThat(q -> q.getSystemTagIds().equals(List.of(2L, 3L))
                && q.getMyCustomTagNames().equals(List.of("想吃"))));
    }

    @Test
    void verifiedAuthorRoutesDoNotGrantAdminOperations() throws Exception {
        when(foods.editMyFood(eq(6001L), any())).thenReturn(Result.ok());
        when(foods.deleteMyFood(6001L)).thenReturn(Result.ok());
        http.perform(as(put("/foods/my/6001").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新名称\"}"), "verified")).andExpect(jsonPath("$.code").value(0));
        http.perform(as(delete("/foods/my/6001"), "verified")).andExpect(jsonPath("$.code").value(0));
        http.perform(as(delete("/foods/delete/6001"), "verified"))
                .andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()));
        verify(foods).editMyFood(eq(6001L), any());
        verify(foods).deleteMyFood(6001L);
    }

    @Test
    void directoryBrowseNeedsNoCoordinatesAndIsOpenToGuests() throws Exception {
        when(restaurants.listRestaurants(null, 71L, 1, 20))
                .thenReturn(Result.ok(PageResult.of(List.of(), 1L, 20L, 0L)));
        http.perform(get("/restaurant/browse").param("hubId", "71"))
                .andExpect(jsonPath("$.code").value(0));
        http.perform(as(get("/restaurant/browse").param("hubId", "71")))
                .andExpect(jsonPath("$.code").value(0));
        http.perform(get("/restaurant/list"))
                .andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
        verify(restaurants, times(2)).listRestaurants(null, 71L, 1, 20);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String... roles) {
        return request.header("satoken", "Bearer " + loginAs(99L, roles));
    }
}
