package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.*;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.ActivityService;

@SaCheckLogin
@RestController
@RequestMapping("/activity")
public class ActivityController {

    @Resource
    private ActivityService activityService;

    /**
     * 发布地域性美食
     * 仅通过认证（verified）的用户可发布
     */
    @SaCheckRole("verified")
    @PostMapping("/foods")
    public Result<Void> publishFood(@Valid @RequestBody CreateActivityFoodDTO dto) {
        return activityService.publishFood(dto);
    }

    /**
     * 分页获取美食列表
     * 仅登录用户可访问
     */
    @GetMapping("/foods")
    public Result<PageResult<ActivityFoodDTO>> listFoods(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return activityService.listFoods(page, pageSize);
    }

    /**
     * 按点赞数分页获取美食列表
     * 仅登录用户可访问
     */
    @GetMapping("/foods/by-likes")
    public Result<PageResult<ActivityFoodDTO>> listFoodsByLikes(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return activityService.listFoodsByLikes(page, pageSize);
    }

    /**
     * 获取美食详情
     * 仅登录用户可访问
     */
    @GetMapping("/foods/{id}")
    public Result<ActivityFoodDTO> getFoodDetail(@PathVariable Long id) {
        return activityService.getFoodDetail(id);
    }

    /**
     * 软删除自己发布的美食
     * 仅通过认证（verified）的用户可操作
     */
    @SaCheckRole("verified")
    @DeleteMapping("/foods/{id}")
    public Result<Void> deleteFood(@PathVariable Long id) {
        return activityService.deleteFood(id);
    }

    /**
     * 发布年夜饭
     * 仅通过认证（verified）的用户可发布
     */
    @SaCheckRole("verified")
    @PostMapping("/dinners")
    public Result<Void> publishDinner(@Valid @RequestBody CreateActivityDinnerDTO dto) {
        return activityService.publishDinner(dto);
    }

    /**
     * 分页获取年夜饭列表
     * 仅登录用户可访问
     */
    @GetMapping("/dinners")
    public Result<PageResult<ActivityDinnerDTO>> listDinners(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return activityService.listDinners(page, pageSize);
    }

    /**
     * 按点赞数分页获取年夜饭列表
     * 仅登录用户可访问
     */
    @GetMapping("/dinners/by-likes")
    public Result<PageResult<ActivityDinnerDTO>> listDinnersByLikes(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return activityService.listDinnersByLikes(page, pageSize);
    }

    /**
     * 获取年夜饭详情
     * 仅登录用户可访问
     */
    @GetMapping("/dinners/{id}")
    public Result<ActivityDinnerDTO> getDinnerDetail(@PathVariable Long id) {
        return activityService.getDinnerDetail(id);
    }

    /**
     * 软删除自己发布的年夜饭
     * 仅通过认证（verified）的用户可操作
     */
    @SaCheckRole("verified")
    @DeleteMapping("/dinners/{id}")
    public Result<Void> deleteDinner(@PathVariable Long id) {
        return activityService.deleteDinner(id);
    }

    /**
     * 点赞（支持 food/dinner）
     * 仅通过认证（verified）的用户可操作
     */
    @SaCheckRole("verified")
    @PostMapping("/likes")
    public Result<Void> like(@Valid @RequestBody ActivityLikeReqDTO dto) {
        return activityService.like(dto);
    }

    /**
     * 取消点赞（支持 food/dinner）
     * 仅通过认证（verified）的用户可操作
     */
    @SaCheckRole("verified")
    @DeleteMapping("/likes")
    public Result<Void> unlike(@Valid @RequestBody ActivityLikeReqDTO dto) {
        return activityService.unlike(dto);
    }
}
