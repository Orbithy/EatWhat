package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.EditRestaurantDTO;
import you.v50to.eatwhat.data.dto.RestaurantDTO;
import you.v50to.eatwhat.data.dto.SearchRestaurantsDTO;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.data.vo.CreatedResourceVO;
import you.v50to.eatwhat.service.RestaurantService;

@RestController
@RequestMapping("/restaurant")
public class RestaurantController {

    @Resource
    private RestaurantService restaurantService;

    @GetMapping("/search")
    public Result<PageResult<Restaurant>> searchRestaurants(@Valid SearchRestaurantsDTO dto) {
        return restaurantService.searchRestaurants(dto);
    }

    @SaCheckRole("verified")
    @PostMapping("/add")
    public Result<CreatedResourceVO> addRestaurant(@Valid @RequestBody RestaurantDTO restaurant) {
        return restaurantService.addRestaurant(restaurant);
    }

    @GetMapping("/detail/{id}")
    public Result<Restaurant> getRestaurantDetail(@PathVariable Long id) {
        return restaurantService.getRestaurantDetail(id);
    }

    /** 无需设备定位的餐厅目录，读取权限与餐厅详情一致（游客可访问）。 */
    @GetMapping("/browse")
    public Result<PageResult<Restaurant>> browseRestaurants(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long hubId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return restaurantService.listRestaurants(keyword, hubId, page, pageSize);
    }

// ==================== Admin API ====================

    @SaCheckRole("admin")
    @PutMapping("/edit/{id}")
    public Result<Void> editRestaurant(@PathVariable Long id, @Valid @RequestBody EditRestaurantDTO dto) {
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
            @RequestParam(required = false) Long hubId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return restaurantService.listRestaurants(keyword, hubId, page, pageSize);
    }

}
