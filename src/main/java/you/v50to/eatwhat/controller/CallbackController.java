package you.v50to.eatwhat.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.AuthService;

import java.util.HashMap;

@RequestMapping
@RestController
public class CallbackController {

    @Resource
    private AuthService authService;

    /** 统一认证回跳（网关上是 /api/callback/{state}），同 /auth/callback/{state}，不要求登录。 */
    @RequestMapping("/callback/{state}")
    public Result<HashMap<String, Object>> callbackWithState(@PathVariable String state,
                                                             @RequestParam(required = false) String token,
                                                             HttpServletResponse response) {
        return authService.callBack(state, token, response);
    }

}
