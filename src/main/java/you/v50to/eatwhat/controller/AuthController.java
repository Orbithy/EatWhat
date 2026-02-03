package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.SaTokenInfo;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.ChangePwdDTO;
import you.v50to.eatwhat.data.dto.LoginDTO;
import you.v50to.eatwhat.data.dto.RegisterDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.AuthService;
import you.v50to.eatwhat.service.SmsService;
import you.v50to.eatwhat.utils.IpUtil;
import jakarta.validation.Valid;

@CrossOrigin
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthService authService;
    @Resource
    private SmsService smsService;
    @Resource
    private HttpServletRequest request;

    @Value("${app.oauth-url}")
    String oauthUrl;

    /**
     * @param username 预选用户名
     * @return 获取用户名是否可用（前端自己校验格式）
     */
    @PostMapping("/checkUsername")
    public Result<Void> checkUsername(@RequestParam String username) {
        return authService.checkUsername(username);
    }

    /**
     * @param registerDTO 注册请求体
     * @return token
     * <p>
     * 用户名只能包含字母数字下划线，且不能是邮箱，长度3-10
     * 密码长度6-64
     */
    @PostMapping("/register")
    public Result<SaTokenInfo> register(@Valid @RequestBody RegisterDTO registerDTO) {
        return authService.register(registerDTO);
    }

    /**
     * @param loginDTO 手机号/邮箱/用户名和密码
     * @return token
     */
    @PostMapping("/login")
    public Result<SaTokenInfo> login(@Valid @RequestBody LoginDTO loginDTO) {
        return authService.login(loginDTO);
    }

    @SaCheckLogin
    @PostMapping("/logout")
    public Result<Void> logout() {
        return authService.logout();
    }

    /**
     * 重定向到统一认证登录页面
     */
    @SaCheckLogin
    @PostMapping("/sdu")
    public Result<Void> sdu(HttpServletResponse response) {
        try {
            response.sendRedirect(oauthUrl);
        } catch (Exception e) {
            return Result.fail(BizCode.THIRD_PARTY_UNAVAILABLE, "重定向失败");
        }
        return null;
    }

    @SaCheckLogin
    @RequestMapping("/callback")
    public Result<Void> callback(@RequestParam String token, HttpServletResponse response) {
        return authService.callBack(token);
    }

    /**
     * 发送验证链接
     *
     * @param email 邮箱地址
     * @return 是否发送成功
     * 发送一个链接 点击绑定
     */
    @SaCheckLogin
    @GetMapping("/sendEmail")
    public Result<Void> sendEmail(@RequestParam String email, HttpServletRequest request) {
        String clientIp = IpUtil.getClientIp(request);
        return authService.sendEmail(email, clientIp);
    }

    /**
     * 验证邮箱链接
     *
     * @param token 验证token
     * @return 验证结果
     */
    @GetMapping("/verifyEmail")
    public Result<Void> verifyEmail(@RequestParam String token, HttpServletResponse response) {
        return authService.verifyEmail(token, response);
    }

    /**
     * 获取短信验证码
     *
     * @param mobile 手机号
     * @return 发送结果
     */
    @SaCheckLogin
    @PostMapping("/getCode")
    public Result<Void> sendCode(String mobile) {
        String ip = IpUtil.getClientIp(request);

        return authService.sendCode("auth", mobile, ip);
    }

    /**
     * 修改密码
     *
     * @param changePwdDTO 修改密码请求体
     * @return 修改结果
     */
    @SaCheckLogin
    @PostMapping("/changePassword")
    public Result<Void> changePassword(@RequestBody ChangePwdDTO changePwdDTO) {
        return authService.changePassword(changePwdDTO);
    }
}

