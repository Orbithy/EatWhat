package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.*;
import you.v50to.eatwhat.data.enums.Scene;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.UserService;
import you.v50to.eatwhat.utils.IpUtil;

import java.util.List;

@Validated
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;
    @Resource
    private HttpServletRequest request;

    /**
     * 获取用户个人信息
     */
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

    /**
     * 获取粉丝列表
     *
     * @param userId 用户ID，不传则为当前用户
     * @param page 页码（从1开始）
     * @param pageSize 每页大小
     * @return 粉丝列表
     */
    @SaCheckLogin
    @GetMapping("/followers")
    public Result<PageResult<FansDTO>> getFollowers(
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return userService.getFollowers(userId, page, pageSize);
    }

    /**
     * 获取关注列表
     *
     * @param userId 用户ID，不传则为当前用户
     * @param page 页码（从1开始）
     * @param pageSize 每页大小
     * @return 关注列表
     */
    @SaCheckLogin
    @GetMapping("/followings")
    public Result<PageResult<FansDTO>> getFollowings(
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return userService.getFollowings(userId, page, pageSize);
    }

    /**
     * 更改隐私设置
     */
    @SaCheckLogin
    @PostMapping("/changePrivacy")
    public Result<Void> changePrivacy(@RequestBody PrivacyDTO dto) {
        return userService.changePrivacy(dto);
    }

    /**
     * 获取其他用户信息
     */
    @SaCheckLogin
    @GetMapping("/userInfo")
    public Result<OtherUserInfoDTO> getUserInfo(@RequestParam Long userId) {
        return userService.getUserInfo(userId);
    }

    /**
     * 关注他人
     */
    @SaCheckLogin
    @PostMapping("/follow")
    public Result<Void> follow(@RequestParam Long userId) {
        return userService.follow(userId);
    }

    /**
     * 取消关注
     */
    @SaCheckLogin
    @PostMapping("/unfollow")
    public Result<Void> unfollow(@RequestParam Long userId) {
        return userService.unfollow(userId);
    }
}
