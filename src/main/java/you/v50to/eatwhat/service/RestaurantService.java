package you.v50to.eatwhat.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.dto.RestaurantDTO;
import you.v50to.eatwhat.data.dto.SearchRestaurantsDTO;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.RestaurantMapper;
import you.v50to.eatwhat.utils.CoordinateTransformUtil;

import static you.v50to.eatwhat.utils.GeoUtil.gcjToWgsPoint;
import static you.v50to.eatwhat.utils.ValidUtil.validPage;
import static you.v50to.eatwhat.utils.ValidUtil.validPageSize;

@Service
public class RestaurantService {

    @Resource
    private RestaurantMapper restaurantMapper;

    public Result<PageResult<Restaurant>> searchRestaurants(SearchRestaurantsDTO dto) {
        int page = validPage(dto.getPage());
        int pageSize = validPageSize(dto.getPageSize());

        double[] wgs = CoordinateTransformUtil.gcj02ToWgs84(dto.getGcjLng(), dto.getGcjLat());

        IPage<Restaurant> restaurants = restaurantMapper.searchRestaurants(
                dto.getKeyword(),
                wgs[0], wgs[1],
                dto.getRadius(),
                new Page<>(page, pageSize));
        return Result.ok(PageResult.of(restaurants.getRecords(), restaurants.getCurrent(), restaurants.getSize(), restaurants.getTotal()));
    }

    public Result<Void> addRestaurant(RestaurantDTO dto) {
        Restaurant restaurant = new Restaurant();
        BeanUtils.copyProperties(dto, restaurant);

        restaurant.setLocation(gcjToWgsPoint(dto.getGcjLng(),dto.getGcjLat()));
        restaurantMapper.insert(restaurant);
        return Result.ok();
    }

    public Result<Restaurant> getRestaurantDetail(Long id) {
        Restaurant restaurant = restaurantMapper.selectById(id);
        return Result.ok(restaurant);
    }

    public Result<Void> editRestaurant(Long id, RestaurantDTO dto) {
        Restaurant restaurant = restaurantMapper.selectById(id);
        if (restaurant == null) {
            return Result.fail(BizCode.RESTAURANT_NOT_FOUND);
        }
        BeanUtils.copyProperties(dto, restaurant);
        restaurant.setLocation(gcjToWgsPoint(dto.getGcjLng(), dto.getGcjLat()));
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
}
