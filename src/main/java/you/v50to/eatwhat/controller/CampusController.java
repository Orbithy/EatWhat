package you.v50to.eatwhat.controller;

import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import you.v50to.eatwhat.data.po.Campus;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.CampusService;

import java.util.List;

@RestController
@RequestMapping("/campus")
public class CampusController {

    @Resource
    private CampusService campusService;

    /**
     * 已开放的校区，游客可访问。客户端按定位选最近的，没有定位选 isDefault 的那个。
     */
    @GetMapping("/list")
    public Result<List<Campus>> list() {
        return campusService.listOpen();
    }
}
