package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import you.v50to.eatwhat.data.dto.BrowseHistoryItemDTO;
import you.v50to.eatwhat.data.dto.BrowseHistoryQueryDTO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.BrowseHistoryService;

@SaCheckLogin
@RestController
@RequestMapping("/history")
public class HistoryController {

    @Resource
    private BrowseHistoryService browseHistoryService;

    @SaCheckRole("verified")
    @GetMapping("/browse")
    public Result<PageResult<BrowseHistoryItemDTO>> getBrowseHistory(@Valid BrowseHistoryQueryDTO dto) {
        return browseHistoryService.getBrowseHistory(StpUtil.getLoginIdAsLong(), dto);
    }
}
