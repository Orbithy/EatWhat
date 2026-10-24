package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import you.v50to.eatwhat.account.AccountAuthService;
import you.v50to.eatwhat.config.StpInterfaceImpl;
import you.v50to.eatwhat.data.dto.ChangePwdDTO;
import you.v50to.eatwhat.data.dto.LoginDTO;
import you.v50to.eatwhat.data.dto.PhoneLoginDTO;
import you.v50to.eatwhat.data.dto.RegisterDTO;
import you.v50to.eatwhat.data.dto.ResetPasswordByCodeDTO;
import you.v50to.eatwhat.data.dto.SetLoginUsernameDTO;
import you.v50to.eatwhat.data.vo.LoginUsernameVO;
import you.v50to.eatwhat.utils.LoginUsernameRules;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.Scene;
import you.v50to.eatwhat.data.po.Contact;
import you.v50to.eatwhat.data.po.Privacy;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.data.po.Verification;
import you.v50to.eatwhat.data.vo.PhoneLoginVO;
import you.v50to.eatwhat.data.vo.SsoStartVO;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.ContactMapper;
import you.v50to.eatwhat.mapper.PrivacyMapper;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.mapper.VerificationMapper;
import you.v50to.eatwhat.utils.JwtUtil;
import you.v50to.eatwhat.utils.SsoUrls;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;
import java.util.HexFormat;
import java.util.UUID;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

@Service
public class AuthService {
    @Autowired(required=false) private you.v50to.eatwhat.control.independent.IndependentControlAccounts independentControlAccounts;
    @Autowired(required=false) private you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts multiwriter;

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
    @Resource
    private PlatformTransactionManager transactionManager;
    @Autowired(required = false)
    private AccountAuthService accountAuth;
    @Autowired(required=false) private you.v50to.eatwhat.cluster.ClusterSessions clusterSessions;

    @Value("${app.jwt.key}")
    private String secret;

    private static final SecureRandom RANDOM = new SecureRandom();
    static final Duration SSO_STATE_TTL = Duration.ofMinutes(5);
    private static final Pattern SSO_STATE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    @Value("${app.oauth-url}")
    private String oauthUrl;
    /** 回跳地址前缀，默认取 oauth-url 里 forward 的主机加 /api/callback，见 SsoUrls.callbackBase。 */
    @Value("${app.oauth-callback-url:}")
    private String oauthCallbackUrl;
    /** 认证成功后浏览器跳到哪里。 */
    @Value("${app.oauth-success-redirect:/home}")
    private String oauthSuccessRedirect = "/home";
    /** 仅由服务端配置，客户端不能提供任意重定向地址。 */
    @Value("${app.oauth-app-success-redirect:https://v50to.you/app/auth/callback}")
    private String oauthAppSuccessRedirect = "https://v50to.you/app/auth/callback";
    @Value("${app.site.id:legacy}")
    private String siteId = "legacy";
    /** 用户名加密码注册默认关闭，新用户只能用手机号验证码注册；旧账号的用户名登录不受影响。 */
    @Value("${app.auth.username-registration-enabled:false}")
    private boolean usernameRegistrationEnabled;
    /** 手机号注册账号的内部登录名前缀；长度超过旧用户名上限 10，不会和用户自选的用户名冲突。 */
    private static final String PHONE_HANDLE_PREFIX = "p_";

    @Autowired(required=false) private you.v50to.eatwhat.control.ControlAccountSecurity controlAccountSecurity;

    private boolean usernameExists(String username) {
        return (multiwriter != null && multiwriter.enabled() && multiwriter.usernameClaimed(username))
                || userMapper.exists(new LambdaQueryWrapper<User>()
                .eq(User::getUserName, username));
    }

    public Result<Void> checkUsername(String username) {
        if (!LoginUsernameRules.valid(username)) return Result.fail(BizCode.PARAM_INVALID, "用户名只能包含3到10位字母、数字或下划线");
        if (usernameExists(username)) {
            return Result.fail(BizCode.USERNAME_EXISTS);
        }
        return Result.ok();
    }

    public Result<LoginUsernameVO> setLoginUsername(SetLoginUsernameDTO dto) {
        long userId = StpUtil.getLoginIdAsLong();
        Result<LoginUsernameVO> result;
        // Catch uniqueness failures outside the transaction, so a losing write fully rolls back.
        try {
            result = new TransactionTemplate(transactionManager).execute(status -> {
                User user = userMapper.selectByIdForUpdate(userId);
                if (user == null) return Result.fail(BizCode.USER_NOT_FOUND);
                if (Boolean.TRUE.equals(user.getBanned()) || StpUtil.isDisable(userId)) return Result.fail(BizCode.USER_DISABLED);
                if (!LoginUsernameRules.generated(user.getUserName())) {
                    return Result.fail(BizCode.STATE_NOT_ALLOWED, "已设置登录用户名，不能重复设置");
                }
                Result<Void> available = checkUsername(dto.getUsername());
                if (available.getCode() != BizCode.SUCCESS.getCode()) return new Result<LoginUsernameVO>(available.getCode(), null, available.getMsg());
                if (dto.getNewPassword() != null && user.hasPassword()) {
                    return Result.fail(BizCode.STATE_NOT_ALLOWED, "已设置密码，请使用修改密码接口");
                }
                String phone = boundPhone(userId);
                if (phone == null) return Result.fail(BizCode.MOBILE_NOT_BOUND);
                if (!smsService.verifyCode(Scene.change, phone, dto.getCode(), userId)) return Result.fail(BizCode.VERIFY_CODE_ERROR);
                java.util.function.Supplier<Result<LoginUsernameVO>> update = () -> {
                    User changes = new User();
                    changes.setId(userId);
                    changes.setUserName(dto.getUsername());
                    if (dto.getNewPassword() != null) changes.setPasswordHash(passwordEncoder.encode(dto.getNewPassword()));
                    userMapper.updateById(changes);
                    return Result.ok(new LoginUsernameVO(dto.getUsername(), true,
                            dto.getNewPassword() != null || user.hasPassword(), true));
                };
                return multiwriter != null && multiwriter.enabled() ? multiwriter.command(userId, userId, update) : update.get();
            });
        } catch (DuplicateKeyException conflict) {
            return Result.fail(BizCode.USERNAME_EXISTS);
        }
        if (result != null && result.getCode() == BizCode.SUCCESS.getCode()) StpUtil.logout(userId);
        return result;
    }

    public Result<?> register(RegisterDTO registerDTO) {
        if (!usernameRegistrationEnabled) return Result.fail(BizCode.USERNAME_REGISTRATION_CLOSED);
        if (accountAuth != null) return accountAuth.register(registerDTO);
        // The database must commit before a Redis session or response cookie exists.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Registration requires its own commit boundary");
        }
        String username = registerDTO.getUsername();
        String password = registerDTO.getPassword();
        String device = registerDTO.getDevice().name();
        if (usernameExists(username)) {
            return Result.fail(BizCode.USERNAME_EXISTS);
        }
        User user = new TransactionTemplate(transactionManager).execute(status -> {
            User created = new User();
            created.setUserName(username);
            created.setPasswordHash(passwordEncoder.encode(password));
            userMapper.insert(created);
            Privacy privacy = new Privacy();
            privacy.setAccountId(created.getId());
            privacy.setFollowing(true);
            privacy.setFollower(true);
            privacyMapper.insert(privacy);
            return created;
        });

        try {
            StpUtil.login(user.getId(), device);
            SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
            return Result.ok(tokenInfo);
        } catch (RuntimeException e) {
            // The row is already committed, so a failed session store must not leave an account the caller never received.
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                privacyMapper.delete(new LambdaQueryWrapper<Privacy>().eq(Privacy::getAccountId, user.getId()));
                userMapper.deleteById(user.getId());
            });
            throw e;
        }
    }

    private boolean looksLikeEmail(String s) {
        return s.contains("@");
    }

    private boolean looksLikePhone(String s) {
        return s.matches("^\\d{11}$");
    }

    public Result<?> login(LoginDTO loginDTO) {
        if(independentControlAccounts!=null)return independentControlAccounts.login(loginDTO);
        if (accountAuth != null) return accountAuth.login(loginDTO);
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
        if (!user.hasPassword()) {
            return Result.fail(BizCode.PASSWORD_NOT_SET);
        }
        if (passwordEncoder.matches(password, user.getPasswordHash())) {
            return issueLegacySession(user, device);
        } else {
            return Result.fail(BizCode.PASSWORD_ERROR);
        }
    }

    private Result<?> issueLegacySession(User user, String device) {
        // The persistent flag still applies if Redis loses its permanent disable marker.
        if (Boolean.TRUE.equals(user.getBanned()) || StpUtil.isDisable(user.getId())) {
            return Result.fail(BizCode.USER_DISABLED);
        }
        Long controlVersion;
        try{controlVersion=controlAccountSecurity==null?null:controlAccountSecurity.loginVersion(user);}
        catch(you.v50to.eatwhat.control.ControlFailure failure){return Result.fail(BizCode.AUTH_UNAVAILABLE);}
        if(controlVersion!=null)redis.delete("role:"+user.getId());
        if(controlVersion==null)StpUtil.login(user.getId(),device);
        else StpUtil.login(user.getId(),new cn.dev33.satoken.stp.parameter.SaLoginParameter().setDeviceType(device).setIsShare(false));
        String issuedToken = StpUtil.getTokenValue();
        try {
            if(controlAccountSecurity!=null)controlAccountSecurity.stamp(controlVersion);
            // A ban may start after the first check. Read a fresh DB snapshot after issuance;
            // banUser publishes the disable marker before evicting existing sessions.
            User current = userMapper.selectById(user.getId());
            if (current == null || Boolean.TRUE.equals(current.getBanned()) || StpUtil.isDisable(user.getId())) {
                revokeLoginAttempt(issuedToken);
                return Result.fail(current == null ? BizCode.USER_NOT_FOUND : BizCode.USER_DISABLED);
            }
            SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
            if (StpUtil.hasRole("admin")) {
                tokenInfo.setTag("admin");
            }
            return Result.ok(tokenInfo);
        } catch (RuntimeException failure) {
            try {
                revokeLoginAttempt(issuedToken);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void revokeLoginAttempt(String issuedToken) {
        // Pin cleanup to this attempt even if the request also supplied an older token.
        StpUtil.setTokenValueToStorage(issuedToken);
        StpUtil.logout(); // Revokes only the current token and expires its response cookie.
    }

    public Result<Void> logout() {
        if(independentControlAccounts!=null)return independentControlAccounts.logout();
        try{if(clusterSessions!=null&&StpUtil.getTokenValue()!=null)clusterSessions.revoke(new you.v50to.eatwhat.cluster.ClusterSessions.Token(StpUtil.getTokenValue()));}
        catch(you.v50to.eatwhat.cluster.ClusterFailure failure){return Result.fail(BizCode.AUTH_UNAVAILABLE);}
        StpUtil.logout();
        return Result.ok();
    }

    public Result<Void> sendLoginCode(String mobile, String clientIp) {
        smsService.sendLoginCode(mobile, clientIp);
        return Result.ok();
    }

    /** 手机号验证码登录：注册过的号码直接登录，新号码建号后登录。 */
    public Result<?> phoneLogin(PhoneLoginDTO dto) {
        // The database must commit before a Redis session or response cookie exists.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Phone login requires its own commit boundary");
        }
        String mobile = dto.getMobile();
        String device = dto.getDevice().name();
        smsService.verifyLoginCode(mobile, dto.getCode());
        User user = findUserByPhone(mobile);
        if (user != null) {
            return withNewUserFlag(loginVerifiedUser(user, device), false);
        }
        try {
            return withNewUserFlag(registerPhone(dto, device), true);
        } catch (DuplicateKeyException race) {
            // A concurrent request registered the same number first; the code already proved ownership.
            user = findUserByPhone(mobile);
            if (user == null) throw race;
            return withNewUserFlag(loginVerifiedUser(user, device), false);
        }
    }

    private User findUserByPhone(String mobile) {
        if (multiwriter != null && multiwriter.enabled()) {
            Long id = multiwriter.resolvePhoneLogin(mobile);
            return id == null ? null : userMapper.selectById(id);
        }
        Contact c = contactMapper.selectOne(new LambdaQueryWrapper<Contact>().eq(Contact::getPhone, mobile));
        return c == null ? null : userMapper.selectById(c.getAccountId());
    }

    private Result<?> loginVerifiedUser(User user, String device) {
        if (accountAuth != null) return accountAuth.loginVerifiedUser(user, device);
        return issueLegacySession(user, device);
    }

    private Result<?> registerPhone(PhoneLoginDTO dto, String device) {
        String mobile = dto.getMobile();
        if (accountAuth != null) {
            return accountAuth.registerPhone(this::insertPhoneAccount, created -> attachPhone(created, mobile),
                    dto.getCrossBorderConsent(), dto.getConsentPolicyVersion(), device);
        }
        User user = new TransactionTemplate(transactionManager).execute(status -> {
            User created = insertPhoneAccount();
            attachPhone(created, mobile);
            return created;
        });
        try {
            StpUtil.login(user.getId(), device);
            return Result.ok(StpUtil.getTokenInfo());
        } catch (RuntimeException e) {
            // Same as register: never leave a committed account the caller never received.
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                contactMapper.delete(new LambdaQueryWrapper<Contact>().eq(Contact::getAccountId, user.getId()));
                privacyMapper.delete(new LambdaQueryWrapper<Privacy>().eq(Privacy::getAccountId, user.getId()));
                userMapper.deleteById(user.getId());
            });
            throw e;
        }
    }

    /** 新账号没有用户名和密码：登录名是随机句柄，昵称默认「同学」加 4 位数字。 */
    private User insertPhoneAccount() {
        User created = new User();
        created.setUserName(PHONE_HANDLE_PREFIX + HexFormat.of().formatHex(randomBytes(6)));
        created.setDisplayName(String.format("同学%04d", RANDOM.nextInt(10_000)));
        created.setPasswordHash(User.UNSET_PASSWORD_PREFIX + UUID.randomUUID());
        userMapper.insert(created);
        Privacy privacy = new Privacy();
        privacy.setAccountId(created.getId());
        privacy.setFollowing(true);
        privacy.setFollower(true);
        privacyMapper.insert(privacy);
        return created;
    }

    private void attachPhone(User user, String mobile) {
        Contact contact = new Contact();
        contact.setAccountId(user.getId());
        contact.setPhone(mobile);
        // Multiwriter records contact changes only inside an account command.
        if (multiwriter != null && multiwriter.enabled()) {
            multiwriter.command(user.getId(), user.getId(), () -> contactMapper.insert(contact));
        } else {
            contactMapper.insert(contact);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static Result<?> withNewUserFlag(Result<?> result, boolean newUser) {
        if (result.getCode() == BizCode.SUCCESS.getCode() && result.getData() instanceof SaTokenInfo info) {
            return Result.ok(PhoneLoginVO.of(info, newUser));
        }
        return result;
    }

    /**
     * 发起统一认证：生成一次性 state 绑定当前账号，返回统一认证地址。回跳地址形如 .../api/callback/{state}，
     * 浏览器跳回时不需要带登录态（App 的 token 在请求头里，整页跳转带不上）。
     */
    public Result<SsoStartVO> startSso(String client) {
        if (!"app".equals(client) && !"web".equals(client)) {
            return Result.fail(BizCode.PARAM_INVALID, "client 只能是 app 或 web");
        }
        long userId = StpUtil.getLoginIdAsLong();
        if (accountAuth != null) accountAuth.assertExternalAccess(userId);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        // Keep numeric web values compatible with outstanding links from earlier releases.
        String owner = "app".equals(client) ? "app:" + userId : Long.toString(userId);
        redis.opsForValue().set(ssoStateKey(state), owner, SSO_STATE_TTL);
        String callback = SsoUrls.callbackBase(oauthUrl, oauthCallbackUrl) + "/" + state;
        return Result.ok(new SsoStartVO(SsoUrls.loginUrl(oauthUrl, callback), SSO_STATE_TTL.toSeconds()));
    }

    /** 统一认证回跳：凭 state 找到发起认证的账号，state 只能用一次，不要求登录。 */
    @Transactional
    public Result<HashMap<String, Object>> callBack(String state, String token, HttpServletResponse response) {
        if (state == null || !SSO_STATE.matcher(state).matches()) {
            return Result.fail(BizCode.SSO_STATE_INVALID);
        }
        String owner = redis.opsForValue().getAndDelete(ssoStateKey(state));
        if (owner == null) {
            return Result.fail(BizCode.SSO_STATE_INVALID);
        }
        boolean app = owner.startsWith("app:");
        long userId = Long.parseLong(app ? owner.substring(4) : owner);
        String successRedirect = app ? oauthAppSuccessRedirect : oauthSuccessRedirect;
        if(multiwriter!=null && multiwriter.enabled() && !multiwriter.inCommand()) return multiwriter.command(userId,userId,()->completeSso(userId,token,response,successRedirect));
        return completeSso(userId, token, response, successRedirect);
    }

    @SneakyThrows
    private Result<HashMap<String, Object>> completeSso(long userId, String token, HttpServletResponse response,
                                                       String successRedirect) {
        String key = secret;
        if (accountAuth != null) accountAuth.assertExternalAccess(userId);
        Optional<JwtUtil.User> userOpt = JwtUtil.getClaim(token, key);
        HashMap<String, Object> data = new HashMap<>();
        data.put("redirect", "/error");
        data.put("user", userOpt);

        if (userOpt.isEmpty()) {
            return Result.fail(BizCode.THIRD_PARTY_BAD_RESPONSE, data);
        } else {
            JwtUtil.User user = userOpt.get();
            String casID = user.casId();
            String name = user.name();
            // 一个学号只能认证一个账号。只查本站数据：两站同时认证同一学号仍可能各成功一次。
            if (verificationMapper.exists(new LambdaQueryWrapper<Verification>()
                    .eq(Verification::getStudentId, casID)
                    .eq(Verification::getVerified, true)
                    .ne(Verification::getAccountId, userId))) {
                return Result.fail(BizCode.STUDENT_ID_TAKEN);
            }
            Verification v = verificationMapper.selectOne(
                    new LambdaQueryWrapper<Verification>()
                            .eq(Verification::getAccountId, userId)
            );

            if (v == null) {
                v = new Verification();
                v.setAccountId(userId);
                v.setMethod("sso");
                v.setVerified(true);
                v.setRealName(name);
                v.setStudentId(casID);
                verificationMapper.insert(v);
            } else {
                v.setMethod("sso");
                v.setVerified(true);
                v.setRealName(name);
                v.setStudentId(casID);
                v.setVerifiedEmail(null);
                verificationMapper.updateById(v);
            }

            // The App can query immediately when opened; publish success only after the DB commit.
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        finishSsoResponse(userId, response, successRedirect);
                    }
                });
            } else {
                finishSsoResponse(userId, response, successRedirect);
            }
            return Result.ok();
        }
    }

    @SneakyThrows
    private void finishSsoResponse(long userId, HttpServletResponse response, String successRedirect) {
        redis.opsForValue().set("auth:" + userId, "sso", StpInterfaceImpl.TTL);
        response.sendRedirect(successRedirect);
    }

    private String ssoStateKey(String state) {
        return "sso:state:v1:" + siteId + ":" + state;
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
            if (accountAuth != null) accountAuth.assertExternalAccess(userId);
            if(multiwriter!=null&&multiwriter.enabled())return multiwriter.command(userId,userId,()->completeEmailVerification(verification,response));
            return completeEmailVerification(verification,response);
        } catch (you.v50to.eatwhat.exception.BizException e) {
            return Result.fail(e.getBizCode());
        }
    }

    private Result<Void> completeEmailVerification(EmailService.EmailVerification verification,HttpServletResponse response) {
        Long userId=verification.userId();
        try {
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
        } catch(IOException e) {return Result.fail(BizCode.UNKNOWN_ERROR,"重定向失败");}
    }

    public Result<Void> sendCode(Scene auth, String mobile, String ip) {
        if (!StpUtil.hasRole("verified")) {
            return Result.fail(BizCode.STATE_NOT_ALLOWED, "未通过认证，无法发送验证码");
        }
        smsService.sendCode(auth, mobile, ip, StpUtil.getLoginIdAsLong());
        return Result.ok();
    }

    public Result<Void> changePassword(ChangePwdDTO changePwdDTO) {
        if(multiwriter!=null && multiwriter.enabled() && !multiwriter.inCommand()) return multiwriter.command(StpUtil.getLoginIdAsLong(),StpUtil.getLoginIdAsLong(),()->changePassword(changePwdDTO));
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.fail(BizCode.USER_NOT_FOUND);
        }
        if (!user.hasPassword()) {
            return Result.fail(BizCode.PASSWORD_NOT_SET);
        }
        if (!passwordEncoder.matches(changePwdDTO.getOldPassword(), user.getPasswordHash())) {
            return Result.fail(BizCode.PASSWORD_ERROR);
        }
        user.setPasswordHash(passwordEncoder.encode(changePwdDTO.getNewPassword()));
        userMapper.updateById(user);
        logoutOtherSessions(userId);
        return Result.ok();
    }

    /** 给当前账号绑定的手机发验证码，用于设置或修改密码。 */
    public Result<Void> sendPasswordCode(String clientIp) {
        Long userId = StpUtil.getLoginIdAsLong();
        String phone = boundPhone(userId);
        if (phone == null) {
            return Result.fail(BizCode.MOBILE_NOT_BOUND);
        }
        smsService.sendCode(Scene.change, phone, clientIp, userId);
        return Result.ok();
    }

    /** 用绑定手机的验证码设置密码（还没设过）或修改密码（不需要旧密码）。 */
    public Result<Void> resetPasswordByCode(ResetPasswordByCodeDTO dto) {
        if(multiwriter!=null && multiwriter.enabled() && !multiwriter.inCommand()) return multiwriter.command(StpUtil.getLoginIdAsLong(),StpUtil.getLoginIdAsLong(),()->resetPasswordByCode(dto));
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.fail(BizCode.USER_NOT_FOUND);
        }
        String phone = boundPhone(userId);
        if (phone == null) {
            return Result.fail(BizCode.MOBILE_NOT_BOUND);
        }
        smsService.verifyCode(Scene.change, phone, dto.getCode(), userId);
        user.setPasswordHash(passwordEncoder.encode(dto.getNewPassword()));
        userMapper.updateById(user);
        logoutOtherSessions(userId);
        return Result.ok();
    }

    private String boundPhone(Long userId) {
        Contact c = contactMapper.selectOne(new LambdaQueryWrapper<Contact>().eq(Contact::getAccountId, userId));
        return c == null || c.getPhone() == null || c.getPhone().isBlank() ? null : c.getPhone();
    }

    /** 改密码后其他设备要重新登录，当前设备保留。 */
    private void logoutOtherSessions(Long userId) {
        String current = StpUtil.getTokenValue();
        for (String token : List.copyOf(StpUtil.getTokenValueListByLoginId(userId))) {
            if (!token.equals(current)) StpUtil.logoutByTokenValue(token);
        }
    }

    public Result<List<String>> verify() {
        return Result.ok(StpUtil.getPermissionList());
    }
}
