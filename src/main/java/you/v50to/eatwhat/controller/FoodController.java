package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.CreateFoodDTO;
import you.v50to.eatwhat.data.dto.EditFoodDTO;
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
    @SaCheckRole("verified")
    @PostMapping("/upload")
    public Result<Void> upload(@Valid @RequestBody CreateFoodDTO dto) {
        return foodService.uploadFood(dto);
    }

    /**
     * 查询某餐厅的菜品列表
     */
    @SaCheckRole("verified")
    @GetMapping("/list")
    public Result<PageResult<FoodVO>> listByRestaurant(
            @RequestParam Long restaurantId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return foodService.listByRestaurant(restaurantId, page, pageSize);
    }

    @SaCheckRole("verified")
    @GetMapping("/detail/{id}")
    public Result<FoodVO> getFoodDetail(@PathVariable Long id) {
        return foodService.getFoodDetail(id);
    }

    /**
     * 获取自己上传的菜品
     */
    @SaCheckRole("verified")
    @GetMapping("/my")
    public Result<PageResult<FoodVO>> getMyFood(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        Long userId = StpUtil.getLoginIdAsLong();
        return foodService.getMyFood(userId, page, pageSize);
    }
// ==================== Admin API ====================

    @SaCheckRole("admin")
    @DeleteMapping("/delete/{id}")
    public Result<Void> deleteFood(@NotNull @PathVariable Long id) {
        return foodService.deleteFood(id);
    }

    @SaCheckRole("admin")
    @PutMapping("/edit/{id}")
    public Result<Void> editFood(@NotNull @PathVariable Long id, @Valid @RequestBody EditFoodDTO dto) {
        return foodService.editFood(id, dto);
    }



}
