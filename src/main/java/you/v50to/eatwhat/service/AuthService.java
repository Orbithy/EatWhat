package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import you.v50to.eatwhat.config.StpInterfaceImpl;
import you.v50to.eatwhat.data.dto.ChangePwdDTO;
import you.v50to.eatwhat.data.dto.LoginDTO;
import you.v50to.eatwhat.data.dto.RegisterDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.Scene;
import you.v50to.eatwhat.data.po.Contact;
import you.v50to.eatwhat.data.po.Privacy;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.data.po.Verification;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.ContactMapper;
import you.v50to.eatwhat.mapper.PrivacyMapper;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.mapper.VerificationMapper;
import you.v50to.eatwhat.utils.JwtUtil;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
public class AuthService {

    @Resource
    private UserMapper userMapper;
    @Resource
    private VerificationMapper verificationMapper;
    @Resource
    private PasswordEncoder passwordEncoder;
    @Resource
    private ContactMapper contactMapper;
    @Resource
    private PrivacyMapper privacyMapper;
    @Resource
    private EmailService emailService;
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private SmsService smsService;

    @Value("${app.base-url")
    private String BASE_URL;
    @Value("${app.jwt.key}")
    private String secret;

    private boolean usernameExists(String username) {
        return userMapper.exists(new LambdaQueryWrapper<User>()
                .eq(User::getUserName, username));
    }

    public Result<Void> checkUsername(String username) {
        if (usernameExists(username)) {
            return Result.fail(BizCode.USERNAME_EXISTS);
        }
        return Result.ok();
    }

    @Transactional
    public Result<SaTokenInfo> register(RegisterDTO registerDTO) {
        String username = registerDTO.getUsername();
        String password = registerDTO.getPassword();
        String device = registerDTO.getDevice().name();
        if (usernameExists(username)) {
            return Result.fail(BizCode.USERNAME_EXISTS);
        }
        User user = new User();
        user.setUserName(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        userMapper.insert(user);

        // 创建默认隐私设置（默认公开）
        Privacy privacy = new Privacy();
        privacy.setAccountId(user.getId());
        privacy.setFollowing(true);
        privacy.setFollower(true);
        privacyMapper.insert(privacy);

        StpUtil.login(user.getId(), device);
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();

        return Result.ok(tokenInfo);
    }

    private boolean looksLikeEmail(String s) {
        return s.contains("@");
    }

    private boolean looksLikePhone(String s) {
        return s.matches("^\\d{11}$");
    }

    public Result<SaTokenInfo> login(LoginDTO loginDTO) {
        String username = loginDTO.getUsername();
        String password = loginDTO.getPassword();
        String device = loginDTO.getDevice().name();

        User user;

        if (looksLikeEmail(username)) {
            Contact c = contactMapper.selectOne(
                    new LambdaQueryWrapper<Contact>()
                            .eq(Contact::getEmail, username)
            );
            if (c == null) {
                return Result.fail(BizCode.USER_NOT_FOUND);
            }
            user = userMapper.selectById(c.getAccountId());
        } else if (looksLikePhone(username)) {
            Contact c = contactMapper.selectOne(
                    new LambdaQueryWrapper<Contact>()
                            .eq(Contact::getPhone, username)
            );
            if (c == null) {
                return Result.fail(BizCode.USER_NOT_FOUND);
            }
            user = userMapper.selectById(c.getAccountId());
        } else {
            user = userMapper.selectOne(
                    new LambdaQueryWrapper<User>()
                            .eq(User::getUserName, username)
            );
        }
        if (user == null) {
            return Result.fail(BizCode.USER_NOT_FOUND);
        }
        if (passwordEncoder.matches(password, user.getPasswordHash())) {
            StpUtil.login(user.getId(), device);
            SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
            return Result.ok(tokenInfo);
        } else {
            return Result.fail(BizCode.PASSWORD_ERROR);
        }
    }

    public Result<Void> logout() {
        StpUtil.logout();
        return Result.ok();
    }

    @SneakyThrows
    @Transactional
    public Result<Void> callBack(String token, HttpServletResponse response) {
        String key = secret;
        Long userId = StpUtil.getLoginIdAsLong();
        Optional<JwtUtil.User> userOpt = JwtUtil.getClaim(token, key);

        if (userOpt.isEmpty()) {
            response.sendRedirect(BASE_URL + "/error");
            return Result.fail(BizCode.THIRD_PARTY_BAD_RESPONSE);
        } else {
            JwtUtil.User user = userOpt.get();
            String casID = user.casId();
            String name = user.name();
            Verification v = new Verification();
            v.setAccountId(userId);
            v.setMethod("sso");
            v.setVerified(true);
            v.setRealName(name);
            v.setStudentId(casID);
            verificationMapper.insert(v);
            String redisKey = "auth:" + userId;
            redis.opsForValue().set(redisKey, "sso", StpInterfaceImpl.TTL);
            response.sendRedirect(BASE_URL + "/home");
            return Result.ok();
        }
    }

    public Result<Void> sendEmail(String email, String clientIp) {
        Long userId = StpUtil.getLoginIdAsLong();

        // 检查该用户是否已经通过验证
        Verification existingVerification = verificationMapper.selectOne(
                new LambdaQueryWrapper<Verification>()
                        .eq(Verification::getAccountId, userId)
                        .eq(Verification::getVerified, true)
        );

        if (existingVerification != null) {
            return Result.fail(BizCode.EMAIL_ALREADY_VERIFIED);
        }

        try {
            emailService.sendVerificationLink(email, userId, clientIp, EmailService.EmailPurpose.VERIFICATION);
            return Result.ok();
        } catch (you.v50to.eatwhat.exception.BizException e) {
            return Result.fail(e.getBizCode());
        }
    }

    @Transactional
    public Result<Void> verifyEmail(String token, HttpServletResponse response) {
        try {
            EmailService.EmailVerification verification = emailService.verifyToken(token);
            Long userId = verification.userId();
            String email = verification.email();

            // 检查该用户是否已经通过验证
            Verification existingVerification = verificationMapper.selectOne(
                    new LambdaQueryWrapper<Verification>()
                            .eq(Verification::getAccountId, userId)
                            .eq(Verification::getVerified, true)
            );

            if (existingVerification != null) {
                return Result.fail(BizCode.EMAIL_ALREADY_VERIFIED);
            }

            Verification v = verificationMapper.selectOne(
                    new LambdaQueryWrapper<Verification>()
                            .eq(Verification::getAccountId, userId)
            );

            if (v == null) {
                // 创建新的验证记录
                v = new Verification();
                v.setAccountId(userId);
                v.setMethod("school_email");
                v.setVerified(true);
                v.setVerifiedEmail(email);
                verificationMapper.insert(v);
                redis.opsForValue().set("auth:" + userId, "school_email", StpInterfaceImpl.TTL);
            } else {
                // 更新验证记录
                v.setMethod("school_email");
                v.setVerified(true);
                v.setVerifiedEmail(email);
                v.setStudentId(null);
                v.setRealName(null);
                verificationMapper.updateById(v);
                redis.opsForValue().set("auth:" + userId, "school_email", StpInterfaceImpl.TTL);
            }

            response.sendRedirect("/email-verified-success"); // TODO: 修改为实际的成功页面URL
            return Result.ok();
        } catch (you.v50to.eatwhat.exception.BizException e) {
            return Result.fail(e.getBizCode());
        } catch (IOException e) {
            return Result.fail(BizCode.UNKNOWN_ERROR, "重定向失败");
        }
    }


    public Result<Void> sendCode(Scene auth, String mobile, String ip) {
        if (!StpUtil.hasRole("verified")) {
            return Result.fail(BizCode.STATE_NOT_ALLOWED, "未通过认证，无法发送验证码");
        }
        smsService.sendCode(auth, mobile, ip);
        return Result.ok();
    }

    public Result<Void> changePassword(ChangePwdDTO changePwdDTO) {
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.fail(BizCode.USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(changePwdDTO.getOldPassword(), user.getPasswordHash())) {
            return Result.fail(BizCode.PASSWORD_ERROR);
        }
        user.setPasswordHash(passwordEncoder.encode(changePwdDTO.getNewPassword()));
        userMapper.updateById(user);
        return Result.ok();
    }

    public Result<List<String>> verify() {
        return Result.ok(StpUtil.getPermissionList());
    }
}
