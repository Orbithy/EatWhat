package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.dto.EditRestaurantDTO;
import you.v50to.eatwhat.data.dto.RestaurantDTO;
import you.v50to.eatwhat.data.dto.SearchRestaurantsDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.RestaurantMapper;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import you.v50to.eatwhat.utils.CoordinateTransformUtil;

import java.util.Arrays;
import java.util.List;

import static you.v50to.eatwhat.utils.GeoUtil.gcjToWgsPoint;
import static you.v50to.eatwhat.utils.ValidUtil.validPage;
import static you.v50to.eatwhat.utils.ValidUtil.validPageSize;

@Service
public class RestaurantService {

    @Resource
    private RestaurantMapper restaurantMapper;
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
                wgs[0], wgs[1],
                dto.getRadius(),
                new Page<>(page, pageSize));
        restaurants.getRecords().forEach(this::signPictureUrls);
        return Result.ok(PageResult.of(restaurants.getRecords(), restaurants.getCurrent(), restaurants.getSize(), restaurants.getTotal()));
    }

    public Result<Void> addRestaurant(RestaurantDTO dto) {
        Restaurant restaurant = new Restaurant();
        BeanUtils.copyProperties(dto, restaurant);
        restaurant.setLocation(gcjToWgsPoint(dto.getGcjLng(), dto.getGcjLat()));
        restaurant.setPictureUrl(toArray(dto.getPictureUrl()));
        restaurant.setAccountId(StpUtil.getLoginIdAsLong());
        restaurantMapper.insert(restaurant);
        return Result.ok();
    }

    public Result<Restaurant> getRestaurantDetail(Long id) {
        Restaurant restaurant = restaurantMapper.selectById(id);
        if (restaurant != null) {
            browseHistoryService.recordBrowse(StpUtil.getLoginIdAsLong(), "restaurant", id);
            signPictureUrls(restaurant);
        }
        return Result.ok(restaurant);
    }

    public Result<Void> editRestaurant(Long id, EditRestaurantDTO dto) {
        Restaurant restaurant = restaurantMapper.selectById(id);
        if (restaurant == null) {
            return Result.fail(BizCode.RESTAURANT_NOT_FOUND);
        }
        if (dto.getName() != null) {
            restaurant.setName(dto.getName());
        }
        if (dto.getAddress() != null) {
            restaurant.setAddress(dto.getAddress());
        }
        if (dto.getCityId() != null) {
            restaurant.setCityId(dto.getCityId());
        }
        if (dto.getHubId() != null) {
            restaurant.setHubId(dto.getHubId());
        }
        if (dto.getPOI() != null) {
            restaurant.setPOI(dto.getPOI());
        }
        if (dto.getPictureUrl() != null) {
            restaurant.setPictureUrl(toArray(dto.getPictureUrl()));
        }
        if (dto.getGcjLng() != null && dto.getGcjLat() != null) {
            restaurant.setGcjLng(dto.getGcjLng());
            restaurant.setGcjLat(dto.getGcjLat());
            restaurant.setLocation(gcjToWgsPoint(dto.getGcjLng(), dto.getGcjLat()));
        }
        restaurantMapper.updateById(restaurant);
        return Result.ok();
    }

    public Result<Void> deleteRestaurant(Long id) {
        Restaurant restaurant = restaurantMapper.selectById(id);
        if (restaurant == null) {
            return Result.fail(BizCode.RESTAURANT_NOT_FOUND);
        }
        restaurantMapper.deleteById(id);
        return Result.ok();
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

    public Result<PageResult<Restaurant>> listRestaurants(String keyword, Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        LambdaQueryWrapper<Restaurant> wrapper = new LambdaQueryWrapper<Restaurant>()
                .like(keyword != null && !keyword.isBlank(), Restaurant::getName, keyword)
                .orderByDesc(Restaurant::getCreatedAt);
        IPage<Restaurant> result = restaurantMapper.selectPage(new Page<>(page, pageSize), wrapper);
        result.getRecords().forEach(this::signPictureUrls);
        return Result.ok(PageResult.of(result.getRecords(), result.getCurrent(), result.getSize(), result.getTotal()));
    }
}
