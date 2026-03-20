package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.CreateFoodDTO;
import you.v50to.eatwhat.data.dto.FoodVO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.FoodService;

@SaCheckLogin
@CrossOrigin
@RestController
@RequestMapping("/foods")
public class FoodController {

    @Resource
    private FoodService foodService;

    /**
     * 上传菜品
     */
    @PostMapping
    public Result<Void> upload(@Valid @RequestBody CreateFoodDTO dto) {
        return foodService.uploadFood(dto);
    }

    /**
     * 查询某餐厅的菜品列表
     */
    @GetMapping
    public Result<PageResult<FoodVO>> listByRestaurant(
            @RequestParam Long restaurantId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return foodService.listByRestaurant(restaurantId, page, pageSize);
    }
}
