package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.data.vo.SummaryVO;
import you.v50to.eatwhat.service.AdminService;

@RestController
@SaCheckRole("admin")
public class AdminController {

    @Resource
    private AdminService adminService;

    @GetMapping("/summary")
    public Result<SummaryVO> summary() {
        return adminService.summary();
    }
}
