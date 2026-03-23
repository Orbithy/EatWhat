package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.CreateSystemFoodTagDTO;
import you.v50to.eatwhat.data.dto.UpdateSystemFoodTagDTO;
import you.v50to.eatwhat.data.vo.AdminFoodTagVO;
import you.v50to.eatwhat.data.vo.FoodTagSummaryVO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.FoodTagService;

@RestController
@CrossOrigin
@SaCheckRole("admin")
@RequestMapping("/admin/food-tags")
public class FoodTagAdminController {

    @Resource
    private FoodTagService foodTagService;

    @GetMapping
    public Result<PageResult<AdminFoodTagVO>> listSystemTags(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return foodTagService.listSystemTagsForAdmin(page, pageSize);
    }

    @PostMapping
    public Result<FoodTagSummaryVO> createSystemTag(@Valid @RequestBody CreateSystemFoodTagDTO dto) {
        return foodTagService.createSystemTag(dto);
    }

    @PutMapping("/{id}")
    public Result<Void> updateSystemTag(@PathVariable @NotNull Long id,
                                        @Valid @RequestBody UpdateSystemFoodTagDTO dto) {
        return foodTagService.updateSystemTag(id, dto);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteSystemTag(@PathVariable @NotNull Long id) {
        return foodTagService.deleteSystemTag(id);
    }
}
