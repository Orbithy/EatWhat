package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import you.v50to.eatwhat.utils.LoginUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.dto.EditRestaurantDTO;
import you.v50to.eatwhat.data.dto.RestaurantDTO;
import you.v50to.eatwhat.data.dto.SearchRestaurantsDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.data.vo.CreatedResourceVO;
import you.v50to.eatwhat.mapper.FoodMapper;
import you.v50to.eatwhat.mapper.RestaurantMapper;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.data.enums.UploadBiz;
import org.springframework.transaction.annotation.Transactional;
import you.v50to.eatwhat.utils.CoordinateTransformUtil;
import you.v50to.eatwhat.config.sync.ManagedDomainWriteGuard;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.catalog.CatalogCommands;
import you.v50to.eatwhat.sync.catalog.CatalogKind;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.WriteOutcome;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.Status;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.UpdatePlan;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static you.v50to.eatwhat.utils.GeoUtil.gcjToWgsPoint;
import static you.v50to.eatwhat.utils.ValidUtil.validPage;
import static you.v50to.eatwhat.utils.ValidUtil.validPageSize;

@Service
public class RestaurantService {

    @Autowired(required = false)
    private FileLifecycleService fileLifecycleService;

    @Autowired(required = false)
    private CatalogCommands catalogCommands;
    @Autowired(required = false)
    private ManagedDomainWriteGuard managedDomainWriteGuard;
    @Autowired(required = false)
    private SiteProperties siteProperties;

    @Resource
    private RestaurantMapper restaurantMapper;
    /** Only used to find the foods that PostgreSQL cascades away with a restaurant. */
    @Autowired(required = false)
    private FoodMapper foodMapper;
    @Resource
    private ObjectStorageService objectStorageService;
    @Resource
    private BrowseHistoryService browseHistoryService;

    public Result<PageResult<Restaurant>> searchRestaurants(SearchRestaurantsDTO dto) {
        int page = validPage(dto.getPage());
        int pageSize = validPageSize(dto.getPageSize());

        double[] wgs = CoordinateTransformUtil.gcj02ToWgs84(dto.getGcjLng(), dto.getGcjLat());

        IPage<Restaurant> restaurants = restaurantMapper.searchRestaurants(
                dto.getKeyword(),
                dto.getHubId(),
                wgs[0], wgs[1],
                dto.getRadius(),
                new Page<>(page, pageSize));
        restaurants.getRecords().forEach(this::signPictureUrls);
        hideOwnerForGuest(restaurants.getRecords());
        return Result.ok(PageResult.of(restaurants.getRecords(), restaurants.getCurrent(), restaurants.getSize(), restaurants.getTotal()));
    }

    @Transactional
    public Result<CreatedResourceVO> addRestaurant(RestaurantDTO dto) {
        Restaurant restaurant = new Restaurant();
        BeanUtils.copyProperties(dto, restaurant);
        restaurant.setLocation(gcjToWgsPoint(dto.getGcjLng(), dto.getGcjLat()));
        restaurant.setPictureUrl(toArray(dto.getPictureUrl()));
        restaurant.setAccountId(StpUtil.getLoginIdAsLong());
        if (catalogCommands == null) {
            restaurantMapper.insert(restaurant);
        } else {
            WriteOutcome<Integer> outcome = catalogCommands.create(
                    CatalogKind.RESTAURANT, restaurant.getAccountId(), restaurant.getHubId(),
                    restaurantNaturalKey(restaurant),
                    () -> restaurantMapper.insert(restaurant), restaurant::getId,
                    rows -> rows != null && rows > 0);
            if (outcome.status() != Status.APPLIED) {
                return Result.fail(BizCode.DATA_SYNC_PENDING);
            }
        }
        if (fileLifecycleService != null) fileLifecycleService.finalizeAndReferenceMany(restaurant.getAccountId(), UploadBiz.RESTAURANTS,
                dto.getPictureUrl(), "restaurant", restaurant.getId().toString(), "PUBLIC");
        return Result.ok(new CreatedResourceVO(restaurant.getId()));
    }

    public Result<Restaurant> getRestaurantDetail(Long id) {
        Restaurant restaurant = restaurantMapper.selectById(id);
        if (restaurant != null && Boolean.FALSE.equals(restaurant.getCatalogVisible())) return Result.fail(BizCode.DATA_SYNC_PENDING);
        if (restaurant != null) {
            Long currentUserId = LoginUtil.currentUserIdOrNull();
            if (currentUserId != null) browseHistoryService.recordBrowse(currentUserId, "restaurant", id);
            else restaurant.setAccountId(null);
            signPictureUrls(restaurant);
        }
        return Result.ok(restaurant);
    }

    @Transactional
    public Result<Void> editRestaurant(Long id, EditRestaurantDTO dto) {
        if (siteProperties != null && siteProperties.getMode() == SiteProperties.Mode.EXTERNAL && (catalogCommands == null || !catalogCommands.multiWriterEnabled())) return Result.fail(BizCode.NO_PERMISSION);
        Restaurant beforeEdit = restaurantMapper.selectById(id);
        if (beforeEdit == null) return Result.fail(BizCode.RESTAURANT_NOT_FOUND);
        // MyBatis may return the same entity again inside this transaction. Capture the old keys before applyEdit.
        String[] previousPictureKeys = beforeEdit.getPictureUrl() == null ? null : beforeEdit.getPictureUrl().clone();
        if (catalogCommands == null && managedDomainWriteGuard != null && managedDomainWriteGuard.catalogManaged(CatalogKind.RESTAURANT, id)) {
            return Result.fail(BizCode.DATA_SYNC_PENDING);
        }
        if (catalogCommands == null) {
            Restaurant restaurant = restaurantMapper.selectById(id);
            if (restaurant == null) return Result.fail(BizCode.RESTAURANT_NOT_FOUND);
            applyEdit(restaurant, dto);
            restaurantMapper.updateById(restaurant);
        } else {
            WriteOutcome<Integer> outcome = catalogCommands.update(
                    CatalogKind.RESTAURANT, id, StpUtil.getLoginIdAsLong(), oldPayload -> {
                        Restaurant restaurant = restaurantMapper.selectById(id);
                        if (restaurant == null) {
                            return new UpdatePlan<>(null, Map.of(), () -> 0, rows -> false);
                        }
                        applyEdit(restaurant, dto);
                        return new UpdatePlan<>(restaurant.getHubId(), restaurantNaturalKey(restaurant),
                                () -> restaurantMapper.updateById(restaurant), rows -> rows != null && rows > 0);
                    });
            if (outcome.status() != Status.APPLIED) {
                if (outcome.value() != null && outcome.value() == 0) return Result.fail(BizCode.RESTAURANT_NOT_FOUND);
                if (restaurantMapper.selectById(id) == null) return Result.fail(BizCode.RESTAURANT_NOT_FOUND);
                return Result.fail(BizCode.DATA_SYNC_PENDING);
            }
        }
        if (fileLifecycleService != null && dto.getPictureUrl() != null) fileLifecycleService.finalizeAndReferenceManyExcluding(
                StpUtil.getLoginIdAsLong(), UploadBiz.RESTAURANTS, dto.getPictureUrl(),
                previousPictureKeys == null ? List.of() : Arrays.asList(previousPictureKeys), "restaurant", id.toString(), "PUBLIC");
        releaseReplacedPictures(id, previousPictureKeys, dto.getPictureUrl());
        return Result.ok();
    }

    /** Releases only the keys that were dropped by the edit; keys that stay referenced are untouched. */
    private void releaseReplacedPictures(Long id, String[] before, List<String> after) {
        if (fileLifecycleService == null || before == null || after == null) return;
        Set<String> removed = new HashSet<>(Arrays.asList(before));
        removed.removeAll(after);
        removed.remove(null);
        if (!removed.isEmpty()) fileLifecycleService.releaseReferences("restaurant", id.toString(), removed, "KEY_REPLACED");
    }

    @Transactional
    public Result<Void> deleteRestaurant(Long id) {
        if (siteProperties != null && siteProperties.getMode() == SiteProperties.Mode.EXTERNAL && (catalogCommands == null || !catalogCommands.multiWriterEnabled())) return Result.fail(BizCode.NO_PERMISSION);
        if (restaurantMapper.selectById(id) == null) return Result.fail(BizCode.RESTAURANT_NOT_FOUND);
        if (catalogCommands == null && managedDomainWriteGuard != null && managedDomainWriteGuard.catalogManaged(CatalogKind.RESTAURANT, id)) {
            return Result.fail(BizCode.DATA_SYNC_PENDING);
        }
        // The database cascades the restaurant's foods away, so their ids must be read before the delete.
        List<Long> cascadedFoodIds = cascadedFoodIds(id);
        if (catalogCommands == null) {
            int rows = restaurantMapper.deleteById(id);
            if (rows <= 0) return Result.fail(BizCode.RESTAURANT_NOT_FOUND);
        } else {
            WriteOutcome<Integer> outcome = catalogCommands.delete(
                    CatalogKind.RESTAURANT, id, StpUtil.getLoginIdAsLong(), oldPayload -> new CatalogCommands.DeletePlan<>(
                            () -> restaurantMapper.deleteById(id), rows -> rows != null && rows > 0));
            if (outcome.status() != Status.APPLIED) {
                if (outcome.value() != null && outcome.value() == 0) return Result.fail(BizCode.RESTAURANT_NOT_FOUND);
                if (restaurantMapper.selectById(id) == null) return Result.fail(BizCode.RESTAURANT_NOT_FOUND);
                return Result.fail(BizCode.DATA_SYNC_PENDING);
            }
        }
        // Hard delete: release the restaurant's and its cascaded foods' file references in the same transaction.
        if (fileLifecycleService != null && (catalogCommands == null || !catalogCommands.multiWriterEnabled())) {
            fileLifecycleService.releaseReferences("restaurant", id.toString(), null, "ENTITY_DELETED");
            for (Long foodId : cascadedFoodIds) fileLifecycleService.releaseReferences("food", foodId.toString(), null, "PARENT_CASCADE");
        }
        return Result.ok();
    }

    private List<Long> cascadedFoodIds(Long restaurantId) {
        if (fileLifecycleService == null || foodMapper == null) return List.of();
        return foodMapper.selectList(new QueryWrapper<Food>().select("id").eq("restaurant_id", restaurantId))
                .stream().map(Food::getId).toList();
    }

    private void signPictureUrls(Restaurant restaurant) {
        if (restaurant.getPictureUrl() == null || restaurant.getPictureUrl().length == 0) {
            return;
        }
        List<String> signed = objectStorageService.signGetUrls(Arrays.asList(restaurant.getPictureUrl()));
        restaurant.setPictureUrl(signed.toArray(new String[0]));
    }

    private String[] toArray(List<String> list) {
        if (list == null) {
            return new String[0];
        }
        return list.toArray(new String[0]);
    }

    private Map<String, Object> restaurantNaturalKey(Restaurant restaurant) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("poi", restaurant.getPOI());
        return key;
    }

    private void applyEdit(Restaurant restaurant, EditRestaurantDTO dto) {
        if (dto.getName() != null) restaurant.setName(dto.getName());
        if (dto.getAddress() != null) restaurant.setAddress(dto.getAddress());
        if (dto.getCityId() != null) restaurant.setCityId(dto.getCityId());
        if (dto.getHubId() != null) restaurant.setHubId(dto.getHubId());
        if (dto.getPOI() != null) restaurant.setPOI(dto.getPOI());
        if (dto.getPictureUrl() != null) restaurant.setPictureUrl(toArray(dto.getPictureUrl()));
        if (dto.getGcjLng() != null && dto.getGcjLat() != null) {
            restaurant.setGcjLng(dto.getGcjLng());
            restaurant.setGcjLat(dto.getGcjLat());
            restaurant.setLocation(gcjToWgsPoint(dto.getGcjLng(), dto.getGcjLat()));
        }
    }

    public Result<PageResult<Restaurant>> listRestaurants(String keyword, Long hubId, Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        LambdaQueryWrapper<Restaurant> wrapper = new LambdaQueryWrapper<Restaurant>().apply("catalog_visible")
                .like(keyword != null && !keyword.isBlank(), Restaurant::getName, keyword)
                .eq(hubId != null, Restaurant::getHubId, hubId)
                .orderByDesc(Restaurant::getCreatedAt, Restaurant::getId);
        IPage<Restaurant> result = restaurantMapper.selectPage(new Page<>(page, pageSize), wrapper);
        result.getRecords().forEach(this::signPictureUrls);
        hideOwnerForGuest(result.getRecords());
        return Result.ok(PageResult.of(result.getRecords(), result.getCurrent(), result.getSize(), result.getTotal()));
    }

    /** 游客不返回创建者 ID，避免枚举用户。 */
    private void hideOwnerForGuest(List<Restaurant> restaurants) {
        if (LoginUtil.currentUserIdOrNull() == null) restaurants.forEach(r -> r.setAccountId(null));
    }
}
