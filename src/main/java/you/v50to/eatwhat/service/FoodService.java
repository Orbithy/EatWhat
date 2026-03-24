package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.dto.CreateFoodDTO;
import you.v50to.eatwhat.data.dto.EditFoodDTO;
import you.v50to.eatwhat.data.dto.FoodVO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.data.po.FoodFavorite;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.FoodFavoriteMapper;
import you.v50to.eatwhat.mapper.FoodMapper;
import you.v50to.eatwhat.mapper.RestaurantMapper;
import you.v50to.eatwhat.service.storage.ObjectStorageService;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static you.v50to.eatwhat.utils.ValidUtil.validPage;
import static you.v50to.eatwhat.utils.ValidUtil.validPageSize;

@Service
public class FoodService {

    @Resource
    private FoodMapper foodMapper;
    @Resource
    private RestaurantMapper restaurantMapper;
    @Resource
    private ObjectStorageService objectStorageService;
    @Resource
    private BrowseHistoryService browseHistoryService;
    @Resource
    private FoodTagService foodTagService;
    @Resource
    private FoodFavoriteMapper foodFavoriteMapper;

    public Result<Void> uploadFood(CreateFoodDTO dto) {
        // 校验餐厅是否存在
        Restaurant restaurant = restaurantMapper.selectById(dto.getRestaurantId());
        if (restaurant == null) {
            return Result.fail(BizCode.OP_FAILED, "餐厅不存在");
        }

        Food food = new Food();
        food.setAccountId(currentUserId());
        food.setRestaurantId(dto.getRestaurantId());
        food.setName(dto.getName());
        food.setDescription(dto.getDescription());
        food.setPrice(dto.getPrice());
        food.setCategory(dto.getCategory());
        food.setPictureUrl(dto.getPictureUrl() != null
                ? dto.getPictureUrl().toArray(new String[0])
                : new String[0]);

        foodMapper.insert(food);
        return Result.ok();
    }

    public Result<PageResult<FoodVO>> listByRestaurant(Long restaurantId,
                                                       Integer page,
                                                       Integer pageSize,
                                                       List<Long> systemTagIds,
                                                       List<Long> myCustomTagIds,
                                                       List<String> myCustomTagNames) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        int offset = (page - 1) * pageSize;
        Long currentUserId = currentUserId();
        List<String> normalizedCustomTagNames = foodTagService.normalizeTagNames(myCustomTagNames);

        boolean hasFilters = (systemTagIds != null && !systemTagIds.isEmpty())
                || (myCustomTagIds != null && !myCustomTagIds.isEmpty())
                || !normalizedCustomTagNames.isEmpty();

        List<Food> foods = hasFilters
                ? foodMapper.selectByRestaurantIdWithFilters(
                restaurantId,
                offset,
                pageSize,
                currentUserId,
                systemTagIds,
                myCustomTagIds,
                normalizedCustomTagNames)
                : foodMapper.selectByRestaurantId(restaurantId, offset, pageSize);
        Long total = hasFilters
                ? foodMapper.countByRestaurantIdWithFilters(
                restaurantId,
                currentUserId,
                systemTagIds,
                myCustomTagIds,
                normalizedCustomTagNames)
                : foodMapper.countByRestaurantId(restaurantId);

        List<FoodVO> items = foods.stream().map(this::toVO).toList();
        fillFoodViews(items, currentUserId);
        return Result.ok(PageResult.of(items, page.longValue(), pageSize.longValue(), total));
    }

    private FoodVO toVO(Food food) {
        FoodVO vo = new FoodVO();
        vo.setId(food.getId());
        vo.setAccountId(food.getAccountId());
        vo.setUploaderName(food.getUploaderName());
        vo.setRestaurantId(food.getRestaurantId());
        vo.setName(food.getName());
        vo.setDescription(food.getDescription());
        vo.setPrice(food.getPrice());
        vo.setCategory(food.getCategory());
        vo.setPictureUrl(objectStorageService.signGetUrls(toList(food.getPictureUrl())));
        vo.setLikesCount(food.getLikesCount());
        vo.setCreatedAt(food.getCreatedAt());
        vo.setUpdatedAt(food.getUpdatedAt());
        return vo;
    }

    private List<String> toList(String[] arr) {
        if (arr == null) return Collections.emptyList();
        return Arrays.asList(arr);
    }

    public Result<Void> deleteFood(Long id) {
        int rows = foodMapper.deleteById(id);
        if (rows <= 0) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }

        return Result.ok();
    }

    public Result<FoodVO> getFoodDetail(Long id) {
        Food food = foodMapper.selectById(id);
        if (food == null) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }
        Long currentUserId = currentUserId();
        browseHistoryService.recordBrowse(currentUserId, "food", id);
        FoodVO vo = toVO(food);
        fillFoodViews(List.of(vo), currentUserId);
        return Result.ok(vo);
    }

    public Result<Void> editFood(Long id, @Valid EditFoodDTO dto) {
        Food food = foodMapper.selectById(id);
        if (food == null) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }

        if (dto.getRestaurantId() != null) {
            Restaurant restaurant = restaurantMapper.selectById(dto.getRestaurantId());
            if (restaurant == null) {
                return Result.fail(BizCode.RESTAURANT_NOT_FOUND, "餐厅不存在");
            }
            food.setRestaurantId(dto.getRestaurantId());
        }
        if (dto.getName() != null) {
            food.setName(dto.getName());
        }
        if (dto.getDescription() != null) {
            food.setDescription(dto.getDescription());
        }
        if (dto.getPrice() != null) {
            food.setPrice(dto.getPrice());
        }
        if (dto.getCategory() != null) {
            food.setCategory(dto.getCategory());
        }
        if (dto.getPictureUrl() != null) {
            food.setPictureUrl(dto.getPictureUrl().toArray(new String[0]));
        }

        int rows = foodMapper.updateById(food);
        if (rows <= 0) {
            return Result.fail(BizCode.OP_FAILED, "操作失败");
        }
        return Result.ok();
    }

    public Result<PageResult<FoodVO>> getMyFood(Long userId, Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        int offset = (page - 1) * pageSize;
        List<Food> foods = foodMapper.selectByAccountId(userId, offset, pageSize);
        Long total = foodMapper.countByAccountId(userId);

        List<FoodVO> items = foods.stream().map(this::toVO).toList();
        fillFoodViews(items, userId);
        return Result.ok(PageResult.of(items, page.longValue(), pageSize.longValue(), total));
    }

    public Result<Void> favoriteFood(Long foodId) {
        if (foodMapper.selectById(foodId) == null) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }

        Long userId = currentUserId();
        FoodFavorite favorite = foodFavoriteMapper.selectOne(new LambdaQueryWrapper<FoodFavorite>()
                .eq(FoodFavorite::getAccountId, userId)
                .eq(FoodFavorite::getFoodId, foodId));
        if (favorite != null) {
            return Result.ok();
        }

        FoodFavorite record = new FoodFavorite();
        record.setAccountId(userId);
        record.setFoodId(foodId);
        foodFavoriteMapper.insert(record);
        return Result.ok();
    }

    public Result<Void> unfavoriteFood(Long foodId) {
        if (foodMapper.selectById(foodId) == null) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }

        Long userId = currentUserId();
        foodFavoriteMapper.delete(new LambdaQueryWrapper<FoodFavorite>()
                .eq(FoodFavorite::getAccountId, userId)
                .eq(FoodFavorite::getFoodId, foodId));
        return Result.ok();
    }

    public Result<PageResult<FoodVO>> getMyFavoriteFoods(Long userId, Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        int offset = (page - 1) * pageSize;
        List<Food> foods = foodFavoriteMapper.selectFavoriteFoodsByAccountId(userId, offset, pageSize);
        Long total = foodFavoriteMapper.countFavoritesByAccountId(userId);

        List<FoodVO> items = foods.stream().map(this::toVO).toList();
        fillFoodViews(items, userId);
        return Result.ok(PageResult.of(items, page.longValue(), pageSize.longValue(), total));
    }

    private void fillFoodViews(List<FoodVO> items, Long currentUserId) {
        foodTagService.fillFoodTagViews(items, currentUserId);
        fillFavoriteState(items, currentUserId);
    }

    private void fillFavoriteState(List<FoodVO> items, Long currentUserId) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<Long> foodIds = items.stream().map(FoodVO::getId).toList();
        List<Long> favoriteIds = foodFavoriteMapper.selectFavoriteFoodIds(currentUserId, foodIds);
        Set<Long> favoriteFoodIds = favoriteIds == null ? Collections.emptySet() : new HashSet<>(favoriteIds);
        for (FoodVO item : items) {
            item.setIsFavorite(favoriteFoodIds.contains(item.getId()));
        }
    }

    protected Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }
}
