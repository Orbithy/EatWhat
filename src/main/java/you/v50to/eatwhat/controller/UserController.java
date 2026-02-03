package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.data.dto.UserInfoDTO;
import you.v50to.eatwhat.service.UserService;

@RestController
@RequestMapping("/user")
public class UserController {
    @Resource
    private UserService userService;

    @SaCheckLogin
    @GetMapping("/info")
    public Result<UserInfoDTO> info() {
        return userService.getInfo();
    }
}
