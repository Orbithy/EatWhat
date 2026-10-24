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
import you.v50to.eatwhat.utils.LoginUtil;

import java.util.List;
import you.v50to.eatwhat.data.dto.FoodQueryDTO;
import you.v50to.eatwhat.data.dto.RecommendQueryDTO;
import you.v50to.eatwhat.data.vo.CreatedResourceVO;

@CrossOrigin
@RestController
@RequestMapping("/foods")
public class FoodController {

    @InitBinder
    void bindCategory(org.springframework.web.bind.WebDataBinder binder) {
        binder.registerCustomEditor(you.v50to.eatwhat.data.enums.FoodCategory.class, new java.beans.PropertyEditorSupport() {
            @Override public void setAsText(String text) {
                setValue(you.v50to.eatwhat.data.enums.FoodCategory.fromValue(text));
            }
        });
        binder.registerCustomEditor(you.v50to.eatwhat.data.enums.MealTime.class, new java.beans.PropertyEditorSupport() {
            @Override public void setAsText(String text) {
                setValue(you.v50to.eatwhat.data.enums.MealTime.fromValue(text));
            }
        });
    }

    @Resource
    private FoodService foodService;
    @Resource
    private FoodTagService foodTagService;

    /**
     * 上传菜品
     */
    @SaCheckRole("verified")
    @PostMapping("/upload")
    public Result<CreatedResourceVO> upload(@Valid @RequestBody CreateFoodDTO dto) {
        return foodService.uploadFood(dto);
    }

    /**
     * 查询菜品：可跨餐厅按分类、预算、关键词和标签筛选
     */
    /** 游客可访问；登录后返回个人点赞、收藏和自定义标签状态。 */
    @GetMapping("/list")
    public Result<PageResult<FoodVO>> listByRestaurant(@Valid FoodQueryDTO query) {
        return foodService.queryFoods(query, false);
    }

    @GetMapping("/detail/{id}")
    public Result<FoodVO> getFoodDetail(@PathVariable Long id) {
        return foodService.getFoodDetail(id);
    }

    /**
     * 推荐一批菜（产品文档第 8 节）：按校区、饭点、分类出菜，一批默认 8 道；同一天推荐过的不再出现，
     * 都推荐过一遍后从头再来。已认证用户参考打标、点赞、收藏和浏览记录，每批至少一半是大家公认的好菜；
     * 游客和未认证账号不带个人因素。游客传 deviceId 才能去重。
     */
    @GetMapping("/recommend")
    public Result<List<FoodVO>> recommendFoods(@Valid RecommendQueryDTO query) {
        return foodService.recommendFoods(query);
    }

    @SaCheckLogin
    @PostMapping("/{id}/favorite")
    public Result<Void> favoriteFood(@PathVariable Long id) {
        return foodService.favoriteFood(id);
    }

    @SaCheckRole("verified")
    @PostMapping("/{id}/like")
    public Result<Void> likeFood(@PathVariable Long id) {
        return foodService.likeFood(id);
    }

    @SaCheckRole("verified")
    @PostMapping("/{id}/unlike")
    public Result<Void> unlikeFood(@PathVariable Long id) {
        return foodService.unlikeFood(id);
    }

    @SaCheckLogin
    @PostMapping("/{id}/unfavorite")
    public Result<Void> unfavoriteFood(@PathVariable Long id) {
        return foodService.unfavoriteFood(id);
    }

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

    @GetMapping("/{id}/tags")
    public Result<FoodTagViewVO> getFoodTags(@PathVariable Long id) {
        return foodTagService.getFoodTagView(id, LoginUtil.currentUserIdOrNull());
    }

    /**
     * 获取自己上传的菜品
     */
    @SaCheckLogin
    @GetMapping("/my")
    public Result<PageResult<FoodVO>> getMyFood(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        Long userId = StpUtil.getLoginIdAsLong();
        return foodService.getMyFood(userId, page, pageSize);
    }

    @SaCheckLogin
    @GetMapping("/favorites")
    public Result<PageResult<FoodVO>> getMyFavoriteFoods(@Valid FoodQueryDTO query) {
        return foodService.queryFoods(query, true);
    }

    /** 他人主页的公开上传列表；个人点赞、收藏和私有标签始终按访问者计算。 */
    @SaCheckLogin
    @GetMapping("/by-user/{userId}")
    public Result<PageResult<FoodVO>> getUserFoods(@PathVariable Long userId,
                                                 @Valid FoodQueryDTO query) {
        if (userId < 1) return Result.fail(you.v50to.eatwhat.data.enums.BizCode.PARAM_INVALID);
        query.setUploaderId(userId);
        return foodService.queryFoods(query, false);
    }

    /** 作者修改自己的菜品；开启多写后可在本站独立编辑；旧模式须在校内权威站进行。 */
    @SaCheckRole("verified")
    @PutMapping("/my/{id}")
    public Result<Void> editMyFood(@PathVariable Long id, @Valid @RequestBody EditFoodDTO dto) {
        return foodService.editMyFood(id, dto);
    }

    /** 作者撤回自己的菜品；开启多写后可在本站独立删除；旧模式须在校内权威站进行。 */
    @SaCheckRole("verified")
    @DeleteMapping("/my/{id}")
    public Result<Void> deleteMyFood(@PathVariable Long id) {
        return foodService.deleteMyFood(id);
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
