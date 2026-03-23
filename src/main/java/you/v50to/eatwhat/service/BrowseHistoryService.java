package you.v50to.eatwhat.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.dto.BrowseHistoryFoodRow;
import you.v50to.eatwhat.data.dto.BrowseHistoryFoodDTO;
import you.v50to.eatwhat.data.dto.BrowseHistoryItemDTO;
import you.v50to.eatwhat.data.dto.BrowseHistoryQueryDTO;
import you.v50to.eatwhat.data.dto.BrowseHistoryRestaurantRow;
import you.v50to.eatwhat.data.dto.BrowseHistoryRestaurantDTO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.BrowseHistoryMapper;
import you.v50to.eatwhat.service.storage.ObjectStorageService;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static you.v50to.eatwhat.utils.ValidUtil.validPage;
import static you.v50to.eatwhat.utils.ValidUtil.validPageSize;

@Service
public class BrowseHistoryService {

    @Resource
    private BrowseHistoryMapper browseHistoryMapper;
    @Resource
    private ObjectStorageService objectStorageService;

    public void recordBrowse(Long accountId, String targetType, Long targetId) {
        browseHistoryMapper.insertIfNotRecent(accountId, targetType, targetId);
    }

    public Result<PageResult<BrowseHistoryItemDTO>> getBrowseHistory(Long accountId, BrowseHistoryQueryDTO dto) {
        int page = validPage(dto.getPage());
        int pageSize = validPageSize(dto.getPageSize());

        if ("restaurant".equals(dto.getTargetType())) {
            return Result.ok(queryRestaurantHistory(accountId, page, pageSize));
        }
        return Result.ok(queryFoodHistory(accountId, page, pageSize));
    }

    private PageResult<BrowseHistoryItemDTO> queryRestaurantHistory(Long accountId, int page, int pageSize) {
        IPage<BrowseHistoryRestaurantRow> result = browseHistoryMapper.selectRestaurantHistoryPage(
                new Page<>(page, pageSize), accountId);
        Map<Long, BrowseHistoryRestaurantDTO> restaurantMap = new LinkedHashMap<>();
        for (BrowseHistoryRestaurantRow row : result.getRecords()) {
            restaurantMap.computeIfAbsent(row.getRestaurantId(), ignored -> toRestaurant(row));
        }
        signRestaurants(restaurantMap.values());
        List<BrowseHistoryItemDTO> items = result.getRecords().stream()
                .map(row -> toHistoryItem(row, restaurantMap.get(row.getRestaurantId())))
                .toList();
        return PageResult.of(items, result.getCurrent(), result.getSize(), result.getTotal());
    }

    private PageResult<BrowseHistoryItemDTO> queryFoodHistory(Long accountId, int page, int pageSize) {
        IPage<BrowseHistoryFoodRow> result = browseHistoryMapper.selectFoodHistoryPage(
                new Page<>(page, pageSize), accountId);
        Map<Long, BrowseHistoryFoodDTO> foodMap = new LinkedHashMap<>();
        for (BrowseHistoryFoodRow row : result.getRecords()) {
            foodMap.computeIfAbsent(row.getFoodId(), ignored -> toFood(row));
        }
        signFoods(foodMap.values());
        List<BrowseHistoryItemDTO> items = result.getRecords().stream()
                .map(row -> toHistoryItem(row, foodMap.get(row.getFoodId())))
                .toList();
        return PageResult.of(items, result.getCurrent(), result.getSize(), result.getTotal());
    }

    private BrowseHistoryItemDTO toHistoryItem(BrowseHistoryRestaurantRow row, BrowseHistoryRestaurantDTO restaurant) {
        BrowseHistoryItemDTO item = new BrowseHistoryItemDTO();
        item.setTargetType(row.getTargetType());
        item.setTargetId(row.getTargetId());
        item.setViewedAt(row.getViewedAt());
        item.setRestaurant(restaurant);
        return item;
    }

    private BrowseHistoryItemDTO toHistoryItem(BrowseHistoryFoodRow row, BrowseHistoryFoodDTO food) {
        BrowseHistoryItemDTO item = new BrowseHistoryItemDTO();
        item.setTargetType(row.getTargetType());
        item.setTargetId(row.getTargetId());
        item.setViewedAt(row.getViewedAt());
        item.setFood(food);
        return item;
    }

    private BrowseHistoryRestaurantDTO toRestaurant(BrowseHistoryRestaurantRow row) {
        BrowseHistoryRestaurantDTO restaurant = new BrowseHistoryRestaurantDTO();
        restaurant.setId(row.getRestaurantId());
        restaurant.setName(row.getRestaurantName());
        restaurant.setPictureUrl(toList(row.getRestaurantPictureUrl()));
        return restaurant;
    }

    private BrowseHistoryFoodDTO toFood(BrowseHistoryFoodRow row) {
        BrowseHistoryFoodDTO food = new BrowseHistoryFoodDTO();
        food.setId(row.getFoodId());
        food.setName(row.getFoodName());
        food.setPictureUrl(toList(row.getFoodPictureUrl()));
        food.setLikesCount(row.getFoodLikesCount());
        return food;
    }

    private void signRestaurants(Iterable<BrowseHistoryRestaurantDTO> restaurants) {
        for (BrowseHistoryRestaurantDTO restaurant : restaurants) {
            if (restaurant.getPictureUrl() == null || restaurant.getPictureUrl().isEmpty()) {
                continue;
            }
            restaurant.setPictureUrl(objectStorageService.signGetUrls(restaurant.getPictureUrl()));
        }
    }

    private void signFoods(Iterable<BrowseHistoryFoodDTO> foods) {
        for (BrowseHistoryFoodDTO food : foods) {
            if (food.getPictureUrl() == null || food.getPictureUrl().isEmpty()) {
                continue;
            }
            food.setPictureUrl(objectStorageService.signGetUrls(food.getPictureUrl()));
        }
    }

    private List<String> toList(String[] values) {
        if (values == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(values);
    }

}
