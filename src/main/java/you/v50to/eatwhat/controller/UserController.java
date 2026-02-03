package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.data.dto.UserInfoDTO;
import you.v50to.eatwhat.service.UserService;
import you.v50to.eatwhat.utils.IpUtil;

@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;
    @Resource
    private HttpServletRequest request;

    @SaCheckLogin
    @GetMapping("/info")
    public Result<UserInfoDTO> info() {
        return userService.getInfo();
    }

    /**
     *
     * @param mobile 手机号
     * @param scene 枚举值 auth/change/forget/bind/verifybind
     * @return 结果
     */
    @SaCheckLogin
    @GetMapping("/getCode")
    public Result<Void> getCode(@RequestParam String mobile, @RequestParam String scene) {
        String ip = IpUtil.getClientIp(request);
        return userService.getCode(scene, mobile, ip);
    }

    @SaCheckLogin
    @PostMapping("/bindMobile")
    public Result<Void> bindMobile(@RequestParam String scene, @RequestParam String mobile, @RequestParam String code) {
        return userService.bindMobile(scene, mobile, code);
    }
}
