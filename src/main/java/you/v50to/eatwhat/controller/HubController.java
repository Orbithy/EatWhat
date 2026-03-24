package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.HubDTO;
import you.v50to.eatwhat.data.po.Hub;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.HubService;

@SaCheckLogin
@RestController
@RequestMapping("/hub")
public class HubController {

    @Resource
    private HubService hubService;

    /**
     * 判断餐厅所在的hub
     */
    @GetMapping("/locate")
    public Result<Hub> locateHub(
            @RequestParam Double gcjLng,
            @RequestParam Double gcjLat) {
        return hubService.findHubByLocation(gcjLng, gcjLat);
    }

    @GetMapping("/list")
    public Result<PageResult<Hub>> listHubs(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return hubService.listHubs(keyword, page, pageSize);
    }

    @GetMapping("/detail/{id}")
    public Result<Hub> getHubDetail(@PathVariable Long id) {
        return hubService.getHubDetail(id);
    }

    @SaCheckRole("admin")
    @PostMapping("/add")
    public Result<Void> addHub(@Valid @RequestBody HubDTO dto) {
        return hubService.addHub(dto);
    }

    @SaCheckRole("admin")
    @PutMapping("/edit/{id}")
    public Result<Void> editHub(@PathVariable Long id, @Valid @RequestBody HubDTO dto) {
        return hubService.editHub(id, dto);
    }

    @SaCheckRole("admin")
    @DeleteMapping("/delete/{id}")
    public Result<Void> deleteHub(@PathVariable Long id) {
        return hubService.deleteHub(id);
    }

    @SaCheckRole("admin")
    @PostMapping("/rebind-restaurants/{id}")
    public Result<Integer> rebindRestaurants(@PathVariable Long id) {
        return hubService.rebindRestaurants(id);
    }
}
