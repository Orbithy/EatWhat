package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.BindMobileReq;
import you.v50to.eatwhat.data.dto.SendCodeReq;
import you.v50to.eatwhat.data.dto.UpdateUserInfoDTO;
import you.v50to.eatwhat.data.enums.Scene;
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
     * 根据请求参数和客户端 IP 发送验证码
     *
     * @param sendCodeReq 发送验证码所需的请求参数
     * @return 发送结果
     */
    @SaCheckLogin
    @PostMapping("/getCode")
    public Result<Void> getCode(@Valid @RequestBody SendCodeReq sendCodeReq) {
        String ip = IpUtil.getClientIp(request);
        return userService.getCode(sendCodeReq, ip);
    }

    @SaCheckLogin
    @PostMapping("/bindMobile")
    public Result<Void> bindMobile(@Valid @RequestBody BindMobileReq bindMobileReq) {
        return userService.bindMobile(Scene.bind, bindMobileReq);
    }

    /**
     * 更新用户个人信息
     * 
     * @param dto 更新数据
     * @return 更新结果
     */
    @SaCheckLogin
    @PutMapping("/updateInfo")
    public Result<Void> updateInfo(@Valid @RequestBody UpdateUserInfoDTO dto) {
        return userService.updateUserInfo(dto);
    }

}
