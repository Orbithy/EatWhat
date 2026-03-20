package you.v50to.eatwhat.controller;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.RestaurantDTO;
import you.v50to.eatwhat.data.dto.SearchRestaurantsDTO;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.RestaurantService;

@RestController
@RequestMapping("/restaurant")
public class RestaurantController {

    @Resource
    private RestaurantService restaurantService;

    @GetMapping("/search")
    public Result<PageResult<Restaurant>> searchRestaurants(SearchRestaurantsDTO dto) {
        return restaurantService.searchRestaurants(dto);
    }

    @PostMapping("/add")
    public Result<Void> addRestaurant(@Valid @RequestBody RestaurantDTO restaurant) {
        return restaurantService.addRestaurant(restaurant);
    }



}
