package you.v50to.eatwhat.contract;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import you.v50to.eatwhat.config.SecurityConfig;
import you.v50to.eatwhat.controller.AuthController;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.Scene;
import you.v50to.eatwhat.data.po.Contact;
import you.v50to.eatwhat.data.po.Privacy;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.mapper.ContactMapper;
import you.v50to.eatwhat.mapper.PrivacyMapper;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.mapper.VerificationMapper;
import you.v50to.eatwhat.service.AuthService;
import you.v50to.eatwhat.service.EmailService;
import you.v50to.eatwhat.service.SmsService;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phone login, auto-registration and code-based password setting on the legacy (single-site) path. */
class PhoneAuthContractTest extends MvcContractSupport {

    private static final long USER_ID = 52L;
    private static final String MOBILE = "13800000001";
    private MockMvc http;
    private UserMapper userMapper;
    private ContactMapper contactMapper;
    private PrivacyMapper privacyMapper;
    private SmsService smsService;
    private PasswordEncoder passwordEncoder;
    private AuthService service;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void createApi() {
        userMapper = mock(UserMapper.class);
        contactMapper = mock(ContactMapper.class);
        privacyMapper = mock(PrivacyMapper.class);
        smsService = mock(SmsService.class);
        passwordEncoder = new SecurityConfig().passwordEncoder();
        when(smsService.verifyLoginCode(anyString(), anyString())).thenReturn(true);
        when(smsService.verifyCode(any(), anyString(), anyString(), anyLong())).thenReturn(true);

        service = new AuthService();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "contactMapper", contactMapper);
        ReflectionTestUtils.setField(service, "privacyMapper", privacyMapper);
        ReflectionTestUtils.setField(service, "verificationMapper", mock(VerificationMapper.class));
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "emailService", mock(EmailService.class));
        ReflectionTestUtils.setField(service, "smsService", smsService);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);

        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "authService", service);
        http = mvc(controller);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"mobile\":\"12800000000\"}", "{\"mobile\":\"1380000000\"}", "{\"mobile\":\"+8613800000000\"}"})
    void loginCodeRejectsNonMainlandNumbersBeforeSending(String body) throws Exception {
        http.perform(post("/auth/phone/code").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        verifyNoInteractions(smsService);
    }

    @Test
    void loginCodeIsSentWithoutSession() throws Exception {
        http.perform(post("/auth/phone/code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"" + MOBILE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(smsService).sendLoginCode(eq(MOBILE), any());
    }

    @Test
    void registeredNumberLogsInWithoutCreatingAnAccount() throws Exception {
        givenAccount(passwordEncoder.encode("secret-1"));
        http.perform(post("/auth/phone/login").contentType(MediaType.APPLICATION_JSON).content(phoneLoginJson("123456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tokenValue").isNotEmpty())
                .andExpect(jsonPath("$.data.isLogin").value(true))
                .andExpect(jsonPath("$.data.loginId").value(String.valueOf(USER_ID)))
                .andExpect(jsonPath("$.data.newUser").value(false));
        verify(smsService).verifyLoginCode(MOBILE, "123456");
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void newNumberIsRegisteredWithNicknameAndNoPassword() throws Exception {
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(USER_ID);
            return 1;
        }).when(userMapper).insert(any(User.class));

        http.perform(post("/auth/phone/login").contentType(MediaType.APPLICATION_JSON).content(phoneLoginJson("123456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tokenValue").isNotEmpty())
                .andExpect(jsonPath("$.data.newUser").value(true));

        ArgumentCaptor<User> account = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(account.capture());
        User created = account.getValue();
        assertTrue(created.getDisplayName().matches("同学\\d{4}"), created.getDisplayName());
        assertTrue(created.getUserName().matches("p_[0-9a-f]{12}"), created.getUserName());
        assertFalse(created.hasPassword());
        assertFalse(passwordEncoder.matches("", created.getPasswordHash()));
        ArgumentCaptor<Contact> contact = ArgumentCaptor.forClass(Contact.class);
        verify(contactMapper).insert(contact.capture());
        assertEquals(USER_ID, contact.getValue().getAccountId());
        assertEquals(MOBILE, contact.getValue().getPhone());
        verify(privacyMapper).insert(any(Privacy.class));
    }

    @Test
    void concurrentRegistrationOfTheSameNumberLogsIntoTheWinner() throws Exception {
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(USER_ID + 1);
            return 1;
        }).when(userMapper).insert(any(User.class));
        doThrow(new DuplicateKeyException("contacts_phone_key")).when(contactMapper).insert(any(Contact.class));
        User winner = account(passwordEncoder.encode("secret-1"));
        Contact winnerContact = new Contact();
        winnerContact.setAccountId(USER_ID);
        winnerContact.setPhone(MOBILE);
        when(contactMapper.selectOne(any())).thenReturn(null, winnerContact);
        when(userMapper.selectById(USER_ID)).thenReturn(winner);

        http.perform(post("/auth/phone/login").contentType(MediaType.APPLICATION_JSON).content(phoneLoginJson("123456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.loginId").value(String.valueOf(USER_ID)))
                .andExpect(jsonPath("$.data.newUser").value(false));
    }

    @Test
    void wrongCodeIssuesNoSessionAndCreatesNothing() throws Exception {
        when(smsService.verifyLoginCode(MOBILE, "000000")).thenThrow(new BizException(BizCode.VERIFY_CODE_ERROR));
        http.perform(post("/auth/phone/login").contentType(MediaType.APPLICATION_JSON).content(phoneLoginJson("000000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.VERIFY_CODE_ERROR.getCode()));
        verifyNoInteractions(userMapper, contactMapper, privacyMapper);
    }

    @Test
    void passwordLoginOnCodeOnlyAccountSaysPasswordNotSet() throws Exception {
        givenAccount(User.UNSET_PASSWORD_PREFIX + "x");
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + MOBILE + "\",\"password\":\"anything\",\"device\":\"APP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.PASSWORD_NOT_SET.getCode()));
        assertTrue(StpUtil.getTokenValueListByLoginId(USER_ID).isEmpty());
    }

    @Test
    void codeSetsFirstPasswordUsingTheBoundPhoneAndKeepsOnlyThisDeviceLoggedIn() throws Exception {
        User user = givenAccount(User.UNSET_PASSWORD_PREFIX + "x");
        // Sessions are not concurrent per device type, so the other device must be a different type.
        String other = loginOnApp();
        String current = loginAs(USER_ID);

        http.perform(post("/auth/password/reset").header(TOKEN_HEADER, "Bearer " + current)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"654321\",\"newPassword\":\"new-secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(smsService).verifyCode(Scene.change, MOBILE, "654321", USER_ID);
        assertTrue(passwordEncoder.matches("new-secret", user.getPasswordHash()));
        verify(userMapper).updateById(user);
        assertNull(StpUtil.getLoginIdByToken(other));
        assertEquals(List.of(current), StpUtil.getTokenValueListByLoginId(USER_ID));
    }

    @Test
    void codeResetWithoutBoundPhoneIsRejected() throws Exception {
        givenAccount(passwordEncoder.encode("secret-1"));
        when(contactMapper.selectOne(any())).thenReturn(null);
        String token = loginAs(USER_ID);
        http.perform(post("/auth/password/reset").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"654321\",\"newPassword\":\"new-secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.MOBILE_NOT_BOUND.getCode()));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void passwordCodeGoesToTheBoundPhone() throws Exception {
        givenAccount(passwordEncoder.encode("secret-1"));
        String token = loginAs(USER_ID);
        http.perform(post("/auth/password/code").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(smsService).sendCode(eq(Scene.change), eq(MOBILE), any(), eq(USER_ID));
    }

    @Test
    void oldPasswordChangeOnCodeOnlyAccountSaysPasswordNotSet() throws Exception {
        givenAccount(User.UNSET_PASSWORD_PREFIX + "x");
        String token = loginAs(USER_ID);
        http.perform(post("/auth/changePassword").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"anything\",\"newPassword\":\"new-secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.PASSWORD_NOT_SET.getCode()));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void oldPasswordChangeRejectsShortNewPassword() throws Exception {
        givenAccount(passwordEncoder.encode("secret-1"));
        String token = loginAs(USER_ID);
        http.perform(post("/auth/changePassword").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"secret-1\",\"newPassword\":\"123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void usernameAvailabilityWorksWhileRegistrationIsClosedAndRejectsOccupiedNames() throws Exception {
        http.perform(post("/auth/checkUsername").param("username", "student_1"))
                .andExpect(jsonPath("$.code").value(0));
        when(userMapper.exists(any())).thenReturn(true);
        http.perform(post("/auth/checkUsername").param("username", "student_1"))
                .andExpect(jsonPath("$.code").value(BizCode.USERNAME_EXISTS.getCode()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ab", "too_long_name", "13800000001", "中文名", "student@x", " user_1", "~mw~123", "id:123"})
    void usernameCheckValidatesOnTheServer(String username) throws Exception {
        http.perform(post("/auth/checkUsername").param("username", username))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        verifyNoInteractions(userMapper);
    }

    @Test
    void settingUsernameRequiresLogin() throws Exception {
        http.perform(post("/auth/username/set").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student_1\",\"code\":\"654321\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
        verifyNoInteractions(userMapper, smsService);
    }

    @Test
    void firstUsernameAndPasswordAreSavedTogetherWithoutChangingNicknameAndCanLogin() throws Exception {
        User user = givenAccount(User.UNSET_PASSWORD_PREFIX + "x");
        when(userMapper.selectOne(any())).thenReturn(user);
        doAnswer(invocation -> {
            User changes = invocation.getArgument(0);
            assertNull(changes.getDisplayName());
            user.setUserName(changes.getUserName());
            user.setPasswordHash(changes.getPasswordHash());
            return 1;
        }).when(userMapper).updateById(any(User.class));
        String other = loginOnApp();
        String current = loginAs(USER_ID);
        http.perform(post("/auth/username/set").header(TOKEN_HEADER, "Bearer " + current)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student_1\",\"code\":\"654321\",\"newPassword\":\"new-secret\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.loginUsername").value("student_1"))
                .andExpect(jsonPath("$.data.hasUsername").value(true))
                .andExpect(jsonPath("$.data.hasPassword").value(true))
                .andExpect(jsonPath("$.data.reauthenticationRequired").value(true));
        assertTrue(passwordEncoder.matches("new-secret", user.getPasswordHash()));
        assertEquals("同学0001", user.getDisplayName());
        verify(smsService).verifyCode(Scene.change, MOBILE, "654321", USER_ID);
        verify(transactionManager).commit(any());
        assertNull(StpUtil.getLoginIdByToken(other));
        assertNull(StpUtil.getLoginIdByToken(current));
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student_1\",\"password\":\"new-secret\",\"device\":\"APP\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tokenValue").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usernameCanBeSetSeparatelyAndDoesNotChangeThePassword(boolean hasPassword) throws Exception {
        User user = givenAccount(hasPassword ? passwordEncoder.encode("old-secret") : User.UNSET_PASSWORD_PREFIX + "x");
        String current = loginAs(USER_ID);
        http.perform(post("/auth/username/set").header(TOKEN_HEADER, "Bearer " + current)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"student_1\",\"code\":\"654321\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hasPassword").value(hasPassword));
        ArgumentCaptor<User> changed = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(changed.capture());
        assertEquals("student_1", changed.getValue().getUserName());
        assertNull(changed.getValue().getPasswordHash());
        assertNull(changed.getValue().getDisplayName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"username\":\"中文\",\"code\":\"654321\"}",
            "{\"username\":\"student_1\",\"code\":\"12\"}",
            "{\"username\":\"student_1\",\"code\":\"654321\",\"newPassword\":\"123\"}",
            "{\"username\":\"student_1\",\"code\":\"654321\",\"newPassword\":\"      \"}"})
    void invalidSetupCannotConsumeCodeOrWrite(String body) throws Exception {
        String current = loginAs(USER_ID);
        http.perform(post("/auth/username/set").header(TOKEN_HEADER, "Bearer " + current)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        verifyNoInteractions(userMapper, smsService);
    }

    @Test
    void anExistingCustomUsernameCannotBeReplacedByThisFirstSetupEndpoint() throws Exception {
        User user = givenAccount(User.UNSET_PASSWORD_PREFIX + "x");
        user.setUserName("legacy_1");
        assertSetupRejected(BizCode.STATE_NOT_ALLOWED);
        verifyNoInteractions(smsService);
    }

    @Test
    void settingUsernameWithoutABoundPhoneIsRejected() throws Exception {
        givenAccount(User.UNSET_PASSWORD_PREFIX + "x");
        when(contactMapper.selectOne(any())).thenReturn(null);
        assertSetupRejected(BizCode.MOBILE_NOT_BOUND);
        verifyNoInteractions(smsService);
    }

    @Test
    void invalidBoundPhoneCodeDoesNotWriteOrRevokeTheCurrentSession() throws Exception {
        givenAccount(User.UNSET_PASSWORD_PREFIX + "x");
        when(smsService.verifyCode(any(), anyString(), anyString(), anyLong())).thenReturn(false);
        assertSetupRejected(BizCode.VERIFY_CODE_ERROR);
    }

    @Test
    void existingPasswordCannotBeOverwrittenByInitialSetup() throws Exception {
        givenAccount(passwordEncoder.encode("old-secret"));
        String current = loginAs(USER_ID);
        http.perform(post("/auth/username/set").header(TOKEN_HEADER, "Bearer " + current)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student_1\",\"code\":\"654321\",\"newPassword\":\"new-secret\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.STATE_NOT_ALLOWED.getCode()));
        verify(userMapper, never()).updateById(any(User.class));
        verifyNoInteractions(smsService);
    }

    @Test
    void aConcurrentUniquenessFailureRollsBackAndKeepsTheSession() throws Exception {
        givenAccount(User.UNSET_PASSWORD_PREFIX + "x");
        doThrow(new DuplicateKeyException("synthetic duplicate")).when(userMapper).updateById(any(User.class));
        String current = loginAs(USER_ID);
        http.perform(post("/auth/username/set").header(TOKEN_HEADER, "Bearer " + current)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student_1\",\"code\":\"654321\",\"newPassword\":\"new-secret\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.USERNAME_EXISTS.getCode()));
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        assertEquals(String.valueOf(USER_ID), StpUtil.getLoginIdByToken(current).toString());
    }

    private void assertSetupRejected(BizCode code) throws Exception {
        String current = loginAs(USER_ID);
        http.perform(post("/auth/username/set").header(TOKEN_HEADER, "Bearer " + current)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"student_1\",\"code\":\"654321\"}"))
                .andExpect(jsonPath("$.code").value(code.getCode()));
        verify(userMapper, never()).updateById(any(User.class));
        assertEquals(String.valueOf(USER_ID), StpUtil.getLoginIdByToken(current).toString());
    }

    private String loginOnApp() {
        try {
            bindToken(null);
            StpUtil.login(USER_ID, "APP");
            return StpUtil.getTokenValue();
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    private User account(String passwordHash) {
        User user = new User();
        user.setId(USER_ID);
        user.setUserName("p_000000000000");
        user.setDisplayName("同学0001");
        user.setPasswordHash(passwordHash);
        user.setRole("user");
        user.setBanned(false);
        return user;
    }

    private User givenAccount(String passwordHash) {
        User user = account(passwordHash);
        when(userMapper.selectById(anyLong())).thenReturn(user);
        when(userMapper.selectByIdForUpdate(anyLong())).thenReturn(user);
        Contact contact = new Contact();
        contact.setAccountId(USER_ID);
        contact.setPhone(MOBILE);
        when(contactMapper.selectOne(any())).thenReturn(contact);
        return user;
    }

    private static String phoneLoginJson(String code) {
        return "{\"mobile\":\"" + MOBILE + "\",\"code\":\"" + code + "\",\"device\":\"APP\"}";
    }
}
