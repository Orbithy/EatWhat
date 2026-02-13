package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.AuthService;

@RequestMapping
@RestController
public class CallbackController {

    @Resource
    private AuthService authService;

    @SaCheckLogin
    @RequestMapping("/callback")
    public Result<Void> callback(@RequestParam String token, HttpServletResponse response) {
        return authService.callBack(token, response);
    }
}
