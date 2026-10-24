package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.SaTokenInfo;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.ChangePwdDTO;
import you.v50to.eatwhat.data.dto.LoginDTO;
import you.v50to.eatwhat.data.dto.PhoneCodeReq;
import you.v50to.eatwhat.data.dto.PhoneLoginDTO;
import you.v50to.eatwhat.data.dto.RegisterDTO;
import you.v50to.eatwhat.data.dto.ResetPasswordByCodeDTO;
import you.v50to.eatwhat.data.dto.SetLoginUsernameDTO;
import you.v50to.eatwhat.data.vo.LoginUsernameVO;
import you.v50to.eatwhat.data.enums.Scene;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.data.vo.SsoStartVO;
import you.v50to.eatwhat.service.AuthService;
import you.v50to.eatwhat.service.TencentCaptchaService;
import you.v50to.eatwhat.utils.IpUtil;

import java.util.HashMap;
import java.util.List;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthService authService;
    @Resource
    private TencentCaptchaService captchaService;
    @Resource
    private HttpServletRequest request;

    /** Fresh, single-use widget initialization parameters; secret keys are never returned. */
    @GetMapping("/captcha/config")
    public Result<TencentCaptchaService.PublicConfig> captchaConfig(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        return Result.ok(captchaService.publicConfig());
    }

    /**
     * @param username 预选用户名
     * @return 检查唯一登录用户名是否可用：3–10位字母、数字或下划线，区分大小写。
     * 无需登录，不受用户名注册开关限制；可用返回0，占用返回30003，格式错误返回10001。
     */
    @PostMapping("/checkUsername")
    public Result<Void> checkUsername(@RequestParam String username) {
        return authService.checkUsername(username);
    }

    /**
     * 手机号账号首次设置唯一登录用户名，可同时设置初始密码，不修改展示昵称。
     * 需登录并使用 /auth/password/code 获取的绑定手机验证码；已有自选用户名不能通过此接口改名。
     * 成功后当前设备及其他设备均须重新登录。首次手机号登录后可跳过，稍后在账号与安全中设置。
     */
    @SaCheckLogin
    @PostMapping("/username/set")
    public Result<LoginUsernameVO> setLoginUsername(@Valid @RequestBody SetLoginUsernameDTO dto) {
        return authService.setLoginUsername(dto);
    }

    /**
     * @param registerDTO 注册请求体
     * @return token
     * <p>
     * 用户名只能包含字母数字下划线，且不能是邮箱，长度3-10
     * 密码长度6-64
     * <p>
     * 默认关闭（app.auth.username-registration-enabled=false），关闭时返回 30014，新用户用 /auth/phone/login 注册。
     */
    @PostMapping("/register")
    public Result<?> register(@Valid @RequestBody RegisterDTO registerDTO) {
        return authService.register(registerDTO);
    }

    /**
     * @param loginDTO 手机号/邮箱/用户名和密码
     * @return token
     */
    @PostMapping("/login")
    public Result<?> login(@Valid @RequestBody LoginDTO loginDTO) {
        return authService.login(loginDTO);
    }

    /**
     * 发送手机号登录验证码，不需要登录。
     * 同一手机号 60 秒一次，5 分钟内有效，连续输错 5 次要重新获取。
     */
    @PostMapping("/phone/code")
    public Result<Void> sendLoginCode(@Valid @RequestBody PhoneCodeReq req,
            @RequestHeader(value = TencentCaptchaService.TICKET_HEADER, required = false) String captchaTicket,
            @RequestHeader(value = TencentCaptchaService.RANDSTR_HEADER, required = false) String captchaRandstr) {
        captchaService.verify(captchaTicket, captchaRandstr, IpUtil.getClientIp(request));
        return authService.sendLoginCode(req.getMobile(), IpUtil.getClientIp(request));
    }

    /**
     * 手机号验证码登录：注册过的号码直接登录，新号码自动注册后登录。
     *
     * @return 会话信息，另带 newUser 表示本次是否新注册
     */
    @PostMapping("/phone/login")
    public Result<?> phoneLogin(@Valid @RequestBody PhoneLoginDTO phoneLoginDTO) {
        return authService.phoneLogin(phoneLoginDTO);
    }

    @SaCheckLogin
    @PostMapping("/logout")
    public Result<Void> logout() {
        return authService.logout();
    }

    /**
     * 发起统一认证：返回带一次性 state 的统一认证地址（5 分钟内有效，只能用一次），客户端在浏览器里打开。
     * 认证完成后统一认证跳回 /api/callback/{state}，不需要登录态。
     * client=app 时成功后跳到 https://v50to.you/app/auth/callback，App 收到链接后查询 /user/info 确认结果；
     * 省略 client 或 client=web 时保留网页成功跳转。只接受 app/web，不接受客户端指定回跳地址。
     */
    @SaCheckLogin
    @RequestMapping("/sdu")
    public Result<SsoStartVO> sdu(@RequestParam(name = "client", defaultValue = "web") String client) {
        return authService.startSso(client);
    }

    /**
     * 统一认证回跳，凭路径里的 state 认证发起的账号，不要求登录。成功后重定向，失败返回业务码。
     */
    @RequestMapping("/callback/{state}")
    public Result<HashMap<String, Object>> callbackWithState(@PathVariable String state,
                                                             @RequestParam(required = false) String token,
                                                             HttpServletResponse response) {
        return authService.callBack(state, token, response);
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
    public Result<Void> sendEmail(@RequestParam String email, HttpServletRequest request,
            @RequestHeader(value = TencentCaptchaService.TICKET_HEADER, required = false) String captchaTicket,
            @RequestHeader(value = TencentCaptchaService.RANDSTR_HEADER, required = false) String captchaRandstr) {
        captchaService.verify(captchaTicket, captchaRandstr, IpUtil.getClientIp(request));
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
    public Result<Void> sendCode(String mobile,
            @RequestHeader(value = TencentCaptchaService.TICKET_HEADER, required = false) String captchaTicket,
            @RequestHeader(value = TencentCaptchaService.RANDSTR_HEADER, required = false) String captchaRandstr) {
        captchaService.verify(captchaTicket, captchaRandstr, IpUtil.getClientIp(request));
        String ip = IpUtil.getClientIp(request);
        return authService.sendCode(Scene.auth, mobile, ip);
    }

    /**
     * 修改密码
     *
     * @param changePwdDTO 修改密码请求体
     * @return 修改结果
     */
    @SaCheckLogin
    @PostMapping("/changePassword")
    public Result<Void> changePassword(@Valid @RequestBody ChangePwdDTO changePwdDTO) {
        return authService.changePassword(changePwdDTO);
    }

    /**
     * 给当前账号绑定的手机发验证码，用于首次设置登录用户名（可同时设置初始密码）或设置、修改密码
     */
    @SaCheckLogin
    @PostMapping("/password/code")
    public Result<Void> sendPasswordCode(
            @RequestHeader(value = TencentCaptchaService.TICKET_HEADER, required = false) String captchaTicket,
            @RequestHeader(value = TencentCaptchaService.RANDSTR_HEADER, required = false) String captchaRandstr) {
        captchaService.verify(captchaTicket, captchaRandstr, IpUtil.getClientIp(request));
        return authService.sendPasswordCode(IpUtil.getClientIp(request));
    }

    /**
     * 用绑定手机的验证码设置或修改密码，不需要旧密码。成功后其他设备要重新登录。
     */
    @SaCheckLogin
    @PostMapping("/password/reset")
    public Result<Void> resetPasswordByCode(@Valid @RequestBody ResetPasswordByCodeDTO dto) {
        return authService.resetPasswordByCode(dto);
    }

    /**
     * 校验三方认证是否完成
     * @return 完成的认证类型列表
     */
    @SaCheckLogin
    @GetMapping("/verify")
    public Result<List<String>> verify() {
        return authService.verify();
    }
}
