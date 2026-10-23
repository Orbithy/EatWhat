package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.CreateActivityPostDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.ActivityPostVO;
import you.v50to.eatwhat.data.vo.ActivityVO;
import you.v50to.eatwhat.data.vo.CreatedResourceVO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.ActivityService;

import java.util.List;

/**
 * 通用活动接口：app 先取活动列表，再按活动 code 读取和发布内容。
 * 活动由管理员在 {@link ActivityAdminController} 创建和上下线，新增活动不需要发版。
 */
@RestController
@RequestMapping("/activity")
public class ActivityController {

    @Resource
    private ActivityService activityService;

    /**
     * 获取已上线的活动列表
     * 游客可访问
     */
    @GetMapping("/list")
    public Result<List<ActivityVO>> list() {
        return activityService.listOnline();
    }

    /**
     * 获取活动详情（含扩展字段定义）
     * 游客可访问；已下线的活动仍可读取，草稿不可见
     */
    @GetMapping("/{code}")
    public Result<ActivityVO> detail(@PathVariable String code) {
        return activityService.getActivity(code);
    }

    /**
     * 分页获取活动下的内容
     * 游客可访问；登录后返回个人点赞状态。sort 为 NEWEST（默认，按时间倒序）或 LIKES（按点赞数）
     */
    @GetMapping("/{code}/posts")
    public Result<PageResult<ActivityPostVO>> listPosts(
            @PathVariable String code,
            @RequestParam(defaultValue = "NEWEST") String sort,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        if (!"NEWEST".equals(sort) && !"LIKES".equals(sort)) {
            return Result.fail(BizCode.PARAM_INVALID, "sort 只能是 NEWEST 或 LIKES");
        }
        return activityService.listPosts(code, sort, page, pageSize);
    }

    /**
     * 在活动下发布内容
     * 仅通过认证（verified）的用户可发布；活动需已上线且在发布时间内
     */
    @SaCheckRole("verified")
    @PostMapping("/{code}/posts")
    public Result<CreatedResourceVO> publish(@PathVariable String code, @Valid @RequestBody CreateActivityPostDTO dto) {
        return activityService.publishPost(code, dto);
    }

    /**
     * 获取内容详情
     * 游客可访问；登录后返回个人点赞状态
     */
    @GetMapping("/posts/{id}")
    public Result<ActivityPostVO> postDetail(@PathVariable Long id) {
        return activityService.getPost(id);
    }

    /**
     * 删除自己发布的内容（软删除）
     * 仅通过认证（verified）的用户可操作
     */
    @SaCheckRole("verified")
    @DeleteMapping("/posts/{id}")
    public Result<Void> deletePost(@PathVariable Long id) {
        return activityService.deletePost(id);
    }

    /**
     * 点赞内容
     * 仅通过认证（verified）的用户可操作
     */
    @SaCheckRole("verified")
    @PostMapping("/posts/{id}/like")
    public Result<Void> like(@PathVariable Long id) {
        return activityService.like(id);
    }

    /**
     * 取消点赞
     * 仅通过认证（verified）的用户可操作；内容不存在或已删除时视为成功
     */
    @SaCheckRole("verified")
    @DeleteMapping("/posts/{id}/like")
    public Result<Void> unlike(@PathVariable Long id) {
        return activityService.unlike(id);
    }
}
