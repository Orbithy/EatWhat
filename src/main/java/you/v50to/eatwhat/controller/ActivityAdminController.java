package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.ActivityStatusDTO;
import you.v50to.eatwhat.data.dto.SaveActivityDTO;
import you.v50to.eatwhat.data.vo.ActivityVO;
import you.v50to.eatwhat.data.vo.CreatedResourceVO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.ActivityAdminService;

/**
 * 活动管理：新建的活动是草稿，上线后 app 才能看到和发内容。
 */
@RestController
@SaCheckRole("admin")
@RequestMapping("/admin/activities")
public class ActivityAdminController {

    @Resource
    private ActivityAdminService activityAdminService;

    /**
     * 获取全部活动（含草稿和已下线）
     */
    @GetMapping
    public Result<PageResult<ActivityVO>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return activityAdminService.list(page, pageSize);
    }

    /**
     * 获取活动详情
     */
    @GetMapping("/{id}")
    public Result<ActivityVO> detail(@PathVariable Long id) {
        return activityAdminService.detail(id);
    }

    /**
     * 新建活动（草稿）
     */
    @PostMapping
    public Result<CreatedResourceVO> create(@Valid @RequestBody SaveActivityDTO dto) {
        return activityAdminService.create(dto);
    }

    /**
     * 修改活动；code 不能修改
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SaveActivityDTO dto) {
        return activityAdminService.update(id, dto);
    }

    /**
     * 上线、下线或改回草稿
     */
    @PostMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody ActivityStatusDTO dto) {
        return activityAdminService.changeStatus(id, dto);
    }

    /**
     * 删除活动；只有还没有任何内容的活动能删除，有内容的请下线
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        return activityAdminService.delete(id);
    }
}
