package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.RestaurantDTO;
import you.v50to.eatwhat.data.dto.SearchRestaurantsDTO;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.RestaurantService;

@SaCheckLogin
@RestController
@RequestMapping("/restaurant")
public class RestaurantController {

    @Resource
    private RestaurantService restaurantService;

    @GetMapping("/search")
    public Result<PageResult<Restaurant>> searchRestaurants(SearchRestaurantsDTO dto) {
        return restaurantService.searchRestaurants(dto);
    }

    @SaCheckRole("verified")
    @PostMapping("/add")
    public Result<Void> addRestaurant(@Valid @RequestBody RestaurantDTO restaurant) {
        return restaurantService.addRestaurant(restaurant);
    }

    @SaCheckRole("verified")
    @GetMapping("/detail/{id}")
    public Result<Restaurant> getRestaurantDetail(@PathVariable Long id) {
        return restaurantService.getRestaurantDetail(id);
    }

// ==================== Admin API ====================

    @SaCheckRole("admin")
    @PutMapping("/edit/{id}")
    public Result<Void> editRestaurant(@PathVariable Long id, @Valid @RequestBody RestaurantDTO dto) {
        return restaurantService.editRestaurant(id, dto);
    }

    @SaCheckRole("admin")
    @DeleteMapping("/delete/{id}")
    public Result<Void> deleteRestaurant(@PathVariable Long id) {
        return restaurantService.deleteRestaurant(id);
    }

    @GetMapping("/list")
    @SaCheckRole("admin")
    public Result<PageResult<Restaurant>> listRestaurants(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return restaurantService.listRestaurants(keyword, page, pageSize);
    }

}
