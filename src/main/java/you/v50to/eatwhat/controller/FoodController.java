package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.AddCustomFoodTagsDTO;
import you.v50to.eatwhat.data.dto.AddSystemFoodTagsDTO;
import you.v50to.eatwhat.data.dto.CreateFoodDTO;
import you.v50to.eatwhat.data.dto.EditFoodDTO;
import you.v50to.eatwhat.data.dto.FoodVO;
import you.v50to.eatwhat.data.dto.RenameCustomTagDTO;
import you.v50to.eatwhat.data.vo.FoodTagSummaryVO;
import you.v50to.eatwhat.data.vo.FoodTagViewVO;
import you.v50to.eatwhat.data.vo.MyCustomTagVO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.FoodService;
import you.v50to.eatwhat.service.FoodTagService;

import java.util.List;

@SaCheckLogin
@CrossOrigin
@RestController
@RequestMapping("/foods")
public class FoodController {

    @Resource
    private FoodService foodService;
    @Resource
    private FoodTagService foodTagService;

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
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) List<Long> systemTagIds,
            @RequestParam(required = false) List<Long> myCustomTagIds,
            @RequestParam(required = false) List<String> myCustomTagNames) {
        return foodService.listByRestaurant(restaurantId, page, pageSize, systemTagIds, myCustomTagIds, myCustomTagNames);
    }

    @SaCheckRole("verified")
    @GetMapping("/detail/{id}")
    public Result<FoodVO> getFoodDetail(@PathVariable Long id) {
        return foodService.getFoodDetail(id);
    }

    @SaCheckRole("verified")
    @PostMapping("/{id}/favorite")
    public Result<Void> favoriteFood(@PathVariable Long id) {
        return foodService.favoriteFood(id);
    }

    @SaCheckRole("verified")
    @PostMapping("/{id}/unfavorite")
    public Result<Void> unfavoriteFood(@PathVariable Long id) {
        return foodService.unfavoriteFood(id);
    }

    @SaCheckRole("verified")
    @GetMapping("/tags/system")
    public Result<List<FoodTagSummaryVO>> listSystemTags() {
        return foodTagService.listSystemTags();
    }

    @SaCheckRole("verified")
    @GetMapping("/tags/my")
    public Result<PageResult<MyCustomTagVO>> listMyCustomTags(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return foodTagService.listMyCustomTags(StpUtil.getLoginIdAsLong(), page, pageSize);
    }

    @SaCheckRole("verified")
    @PutMapping("/tags/my/{tagId}")
    public Result<Void> renameMyCustomTag(@PathVariable Long tagId,
                                          @Valid @RequestBody RenameCustomTagDTO dto) {
        return foodTagService.renameMyCustomTag(tagId, StpUtil.getLoginIdAsLong(), dto);
    }

    @SaCheckRole("verified")
    @DeleteMapping("/tags/my/{tagId}")
    public Result<Void> deleteMyCustomTag(@PathVariable Long tagId) {
        return foodTagService.deleteMyCustomTag(tagId, StpUtil.getLoginIdAsLong());
    }

    @SaCheckRole("verified")
    @PostMapping("/{id}/tags/system")
    public Result<Void> addSystemTags(@PathVariable Long id, @Valid @RequestBody AddSystemFoodTagsDTO dto) {
        return foodTagService.addSystemTagsToFood(id, StpUtil.getLoginIdAsLong(), dto);
    }

    @SaCheckRole("verified")
    @PostMapping("/{id}/tags/custom")
    public Result<Void> addCustomTags(@PathVariable Long id, @Valid @RequestBody AddCustomFoodTagsDTO dto) {
        return foodTagService.addCustomTagsToFood(id, StpUtil.getLoginIdAsLong(), dto);
    }

    @SaCheckRole("verified")
    @DeleteMapping("/{id}/tags/{tagId}")
    public Result<Void> deleteTag(@PathVariable Long id, @PathVariable Long tagId) {
        return foodTagService.deleteMyTagging(id, tagId, StpUtil.getLoginIdAsLong());
    }

    @SaCheckRole("verified")
    @GetMapping("/{id}/tags")
    public Result<FoodTagViewVO> getFoodTags(@PathVariable Long id) {
        return foodTagService.getFoodTagView(id, StpUtil.getLoginIdAsLong());
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

    @SaCheckRole("verified")
    @GetMapping("/favorites")
    public Result<PageResult<FoodVO>> getMyFavoriteFoods(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        Long userId = StpUtil.getLoginIdAsLong();
        return foodService.getMyFavoriteFoods(userId, page, pageSize);
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
