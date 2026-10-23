package you.v50to.eatwhat.contract;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import you.v50to.eatwhat.config.JacksonConfig;
import you.v50to.eatwhat.config.SecurityConfig;
import you.v50to.eatwhat.controller.AuthController;
import you.v50to.eatwhat.controller.CallbackController;
import you.v50to.eatwhat.data.enums.BizCode;
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

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Current HTTP contracts: real controller, validation, service, BCrypt and Sa-Token; no DB or network. */
class AuthContractTest extends MvcContractSupport {

    private static final long USER_ID = 41L;
    private static final String PASSWORD = "contract-password";
    private MockMvc http;
    private UserMapper userMapper;
    private ContactMapper contactMapper;
    private PrivacyMapper privacyMapper;
    private EmailService emailService;
    private PasswordEncoder passwordEncoder;
    private AuthService service;

    @BeforeEach
    void createApi() {
        userMapper = mock(UserMapper.class);
        contactMapper = mock(ContactMapper.class);
        privacyMapper = mock(PrivacyMapper.class);
        emailService = mock(EmailService.class);
        passwordEncoder = new SecurityConfig().passwordEncoder();

        service = new AuthService();
        // These contracts cover the username registration that deployments now keep closed by default.
        ReflectionTestUtils.setField(service, "usernameRegistrationEnabled", true);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "contactMapper", contactMapper);
        ReflectionTestUtils.setField(service, "privacyMapper", privacyMapper);
        ReflectionTestUtils.setField(service, "verificationMapper", mock(VerificationMapper.class));
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "smsService", mock(SmsService.class));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(service, "oauthUrl", "https://identity.example.invalid/login?forward=https%3A%2F%2Fapp.example.invalid%2Fapi%2Fv1%2Fcallback");
        ReflectionTestUtils.setField(service, "redis", mock(org.springframework.data.redis.core.StringRedisTemplate.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));

        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "authService", service);
        CallbackController callback = new CallbackController();
        ReflectionTestUtils.setField(callback, "authService", service);
        http = mvc(controller, callback);
    }

    @Test
    void usernameRegistrationIsClosedByDefaultAndCreatesNothing() throws Exception {
        ReflectionTestUtils.setField(service, "usernameRegistrationEnabled", false);
        http.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.USERNAME_REGISTRATION_CLOSED.getCode()))
                .andExpect(jsonPath("$.msg").value("请使用手机号验证码注册"));
        verifyNoInteractions(userMapper, contactMapper, privacyMapper);
        http.perform(post("/auth/checkUsername").param("username", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(userMapper).exists(any());
    }

    @Test
    void legacyRegistrationWithoutConsentFieldReturnsOrdinaryTokenAndCreatesDefaultPrivacy() throws Exception {
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(USER_ID);
            return 1;
        }).when(userMapper).insert(any(User.class));

        http.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("ok"))
                .andExpect(jsonPath("$.timestamp").isNumber())
                .andExpect(jsonPath("$.data.tokenName").value(TOKEN_HEADER))
                .andExpect(jsonPath("$.data.tokenValue").isNotEmpty())
                .andExpect(jsonPath("$.data.isLogin").value(true))
                .andExpect(jsonPath("$.data.loginDeviceType").value("WEB"));

        ArgumentCaptor<User> account = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(account.capture());
        assertEquals("alice", account.getValue().getUserName());
        assertNotEquals(PASSWORD, account.getValue().getPasswordHash());
        assertTrue(passwordEncoder.matches(PASSWORD, account.getValue().getPasswordHash()));
        ArgumentCaptor<Privacy> privacy = ArgumentCaptor.forClass(Privacy.class);
        verify(privacyMapper).insert(privacy.capture());
        assertEquals(USER_ID, privacy.getValue().getAccountId());
        assertTrue(privacy.getValue().getFollowing());
        assertTrue(privacy.getValue().getFollower());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"username\":\"ab\",\"password\":\"abcdef\",\"device\":\"WEB\"}",
            "{\"username\":\"alice\",\"password\":\"short\",\"device\":\"WEB\"}",
            "{\"username\":\"alice\",\"password\":\"abcdef\"}",
            "{\"username\":\"alice\",\"password\":\"abcdef\",\"device\":\"UNKNOWN\"}"
    })
    void invalidRegistrationUsesExistingErrorEnvelopeBeforeWriting(String body) throws Exception {
        http.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()))
                .andExpect(jsonPath("$.data", is(nullValue())))
                .andExpect(jsonPath("$.msg").isString())
                .andExpect(jsonPath("$.timestamp").isNumber());
        verifyNoInteractions(userMapper, privacyMapper, contactMapper);
    }

    @Test
    void duplicateRegistrationKeepsUsernameExistsCodeAndDoesNotWrite() throws Exception {
        when(userMapper.exists(any())).thenReturn(true);
        http.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.USERNAME_EXISTS.getCode()));
        verify(userMapper, never()).insert(any(User.class));
        verifyNoInteractions(privacyMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "alice@example.invalid", "13800000000"})
    void existingUsernameEmailAndMobileLoginPayloadsReturnSaToken(String identifier) throws Exception {
        givenAccount();
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(identifier, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("ok"))
                .andExpect(jsonPath("$.timestamp").isNumber())
                .andExpect(jsonPath("$.data.tokenName").value(TOKEN_HEADER))
                .andExpect(jsonPath("$.data.tokenValue").isNotEmpty())
                .andExpect(jsonPath("$.data.isLogin").value(true));
        if (identifier.equals("alice")) {
            verify(userMapper).selectOne(any());
        } else {
            verify(contactMapper).selectOne(any());
            verify(userMapper, times(2)).selectById(USER_ID);
        }
    }

    @Test
    void wrongPasswordKeepsExistingPasswordErrorCode() throws Exception {
        givenAccount();
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", "wrong-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.PASSWORD_ERROR.getCode()))
                .andExpect(jsonPath("$.data", is(nullValue())));
    }

    @Test
    void unknownAccountKeepsCurrentDistinctErrorUntilRelayIsImplemented() throws Exception {
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("unknown", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.USER_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.data", is(nullValue())));
    }

    @Test
    void missingLoginDeviceIsRejectedBeforeAccountLookup() throws Exception {
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"contract-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        verifyNoInteractions(userMapper, contactMapper);
    }

    @Test
    void actualLoginTokenAuthorizesVerifyAndLogoutRevokesIt() throws Exception {
        givenAccount();
        String response = http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", PASSWORD)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String token = new JacksonConfig().objectMapper().readTree(response)
                .get("data").get("tokenValue").asString();

        http.perform(get("/auth/verify").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
        http.perform(post("/auth/logout").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        http.perform(get("/auth/verify").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/verify", "/auth/sdu"})
    void protectedAuthenticationRoutesRejectAnonymousRequests(String path) throws Exception {
        http.perform(get(path).param("token", "synthetic-callback-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
        verifyNoInteractions(userMapper, contactMapper);
    }

    @Test
    void loggedInUserWithoutVerifiedRoleCanStartSchoolVerification() throws Exception {
        String token = loginAs(USER_ID);
        http.perform(get("/auth/sdu").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.startsWith(
                        "https://identity.example.invalid/login?forward=https%3A%2F%2Fapp.example.invalid%2Fapi%2Fcallback%2F")));
    }

    @Test
    void emailVerificationRemainsPublicAndKeepsInvalidTokenCode() throws Exception {
        when(emailService.verifyToken("expired-token"))
                .thenThrow(new BizException(BizCode.EMAIL_TOKEN_INVALID));
        http.perform(get("/auth/verifyEmail").param("token", "expired-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.EMAIL_TOKEN_INVALID.getCode()));
        verify(emailService).verifyToken("expired-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "alice@example.invalid", "13800000000"})
    void persistedBanRejectsEveryLoginIdentifierEvenWithoutRedisDisableMarker(String identifier) throws Exception {
        User account = givenAccount();
        account.setBanned(true);
        assertFalse(StpUtil.isDisable(USER_ID), "A lost Redis marker must not bypass the persistent ban");
        assertRejectedWithoutSession(identifier, PASSWORD, BizCode.USER_DISABLED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "alice@example.invalid", "13800000000"})
    void saTokenDisableRejectsEveryLoginIdentifierBeforeDatabaseFlagChanges(String identifier) throws Exception {
        givenAccount();
        StpUtil.disable(USER_ID, -1);
        assertRejectedWithoutSession(identifier, PASSWORD, BizCode.USER_DISABLED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "alice@example.invalid", "13800000000"})
    void wrongPasswordDoesNotRevealBanOrIssueSession(String identifier) throws Exception {
        User account = givenAccount();
        account.setBanned(true);
        StpUtil.disable(USER_ID, -1);
        assertRejectedWithoutSession(identifier, "wrong-password", BizCode.PASSWORD_ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "alice@example.invalid", "13800000000"})
    void loginResumesOnlyAfterBothPersistentBanAndSaTokenDisableAreCleared(String identifier) throws Exception {
        User account = givenAccount();
        account.setBanned(true);
        StpUtil.disable(USER_ID, -1);
        assertRejectedWithoutSession(identifier, PASSWORD, BizCode.USER_DISABLED);
        account.setBanned(false);
        assertRejectedWithoutSession(identifier, PASSWORD, BizCode.USER_DISABLED);
        StpUtil.untieDisable(USER_ID);

        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(identifier, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.isLogin").value(true))
                .andExpect(jsonPath("$.data.tokenValue").isNotEmpty());
        assertEquals(1, StpUtil.getTokenValueListByLoginId(USER_ID).size());
    }

    @Test
    void completedBanBetweenInitialCheckAndTokenCreationCannotLeaveTheNewTokenUsable() throws Exception {
        User initialSnapshot = givenAccount();
        AtomicReference<String> issuedToken = installLoginInterleaving(() -> {
            // This ordering matches banUser: the marker precedes eviction and DB persistence.
            StpUtil.disable(USER_ID, -1);
            StpUtil.logout(USER_ID);
            returnBannedSnapshotOnNextDatabaseRead();
        }, () -> { });
        assertPostLoginFailureRevokesIssuedToken(issuedToken, BizCode.USER_DISABLED);
        assertFalse(initialSnapshot.getBanned(), "The initial user snapshot must remain stale in this interleaving");
    }

    @Test
    void disableMarkerWrittenAfterTokenCreationRejectsLoginBeforeTheBanDatabaseWrite() throws Exception {
        User initialSnapshot = givenAccount();
        AtomicReference<String> issuedToken = installLoginInterleaving(() -> { },
                () -> StpUtil.disable(USER_ID, -1));
        assertPostLoginFailureRevokesIssuedToken(issuedToken, BizCode.USER_DISABLED);
        assertFalse(initialSnapshot.getBanned());
    }

    @Test
    void freshDatabaseBanAfterTokenCreationIsHonoredWhenRedisMarkerIsMissing() throws Exception {
        User initialSnapshot = givenAccount();
        AtomicReference<String> issuedToken = installLoginInterleaving(() -> { },
                this::returnBannedSnapshotOnNextDatabaseRead);
        assertPostLoginFailureRevokesIssuedToken(issuedToken, BizCode.USER_DISABLED);
        assertFalse(initialSnapshot.getBanned(), "Rechecking the stale Java object would miss this ban");
        assertFalse(StpUtil.isDisable(USER_ID));
    }

    @Test
    void databaseRecheckFailureRevokesOnlyThisAttemptAndClearsItsCookie() throws Exception {
        givenAccount();
        SaManager.getConfig().setIsConcurrent(true);
        String otherSession = loginAs(USER_ID);
        AtomicReference<String> issuedToken = installLoginInterleaving(() -> { }, () ->
                when(userMapper.selectById(USER_ID)).thenThrow(
                        new DataAccessResourceFailureException("synthetic post-login database failure")));
        assertPostLoginFailureRevokesIssuedToken(issuedToken, BizCode.UNKNOWN_ERROR, otherSession);
        http.perform(get("/auth/verify").header(TOKEN_HEADER, "Bearer " + otherSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private void returnBannedSnapshotOnNextDatabaseRead() {
        User latest = new User();
        latest.setId(USER_ID);
        latest.setBanned(true);
        when(userMapper.selectById(USER_ID)).thenReturn(latest);
    }

    private AtomicReference<String> installLoginInterleaving(Runnable beforeMint, Runnable afterMint) {
        AtomicReference<String> issuedToken = new AtomicReference<>();
        // Real token/session writes, with deterministic scheduling hooks instead of sleeps or static mocks.
        // MvcContractSupport restores the original StpLogic and registry after every test.
        StpUtil.setStpLogic(new StpLogic(StpUtil.TYPE) {
            @Override
            public void login(Object loginId, SaLoginParameter parameter) {
                beforeMint.run();
                super.login(loginId, parameter);
                issuedToken.set(getTokenValue());
                afterMint.run();
            }
        });
        return issuedToken;
    }

    private void assertPostLoginFailureRevokesIssuedToken(AtomicReference<String> issuedToken, BizCode error,
                                                        String... preservedTokens) throws Exception {
        var response = http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(error.getCode()))
                .andExpect(jsonPath("$.msg").value(error.getMsg()))
                .andExpect(jsonPath("$.data", is(nullValue())))
                .andExpect(jsonPath("$.timestamp").isNumber())
                .andReturn().getResponse();
        assertNotNull(issuedToken.get(), "The interleaving must occur after a real token was issued");
        assertNull(StpUtil.getLoginIdByToken(issuedToken.get()));
        assertEquals(Set.of(preservedTokens), Set.copyOf(StpUtil.getTokenValueListByLoginId(USER_ID)));
        if (preservedTokens.length == 0) {
            assertNull(StpUtil.getSessionByLoginId(USER_ID, false));
        }
        Cookie finalCookie = null;
        for (Cookie cookie : response.getCookies()) {
            if (TOKEN_HEADER.equals(cookie.getName())) {
                finalCookie = cookie;
            }
        }
        assertTrue(finalCookie == null || finalCookie.getMaxAge() == 0,
                "A compensated login must not leave its newly issued cookie in the response");
        http.perform(get("/auth/verify").header(TOKEN_HEADER, "Bearer " + issuedToken.get()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
    }

    private void assertRejectedWithoutSession(String identifier, String password, BizCode error) throws Exception {
        Set<String> existingKeys = Set.copyOf(SaManager.getSaTokenDao().searchData("", "", 0, -1, true));
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(identifier, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(error.getCode()))
                .andExpect(jsonPath("$.msg").value(error.getMsg()))
                .andExpect(jsonPath("$.data", is(nullValue())))
                .andExpect(jsonPath("$.timestamp").isNumber())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().doesNotExist(TOKEN_HEADER));
        assertNull(StpUtil.getSessionByLoginId(USER_ID, false));
        assertTrue(StpUtil.getTokenValueListByLoginId(USER_ID).isEmpty());
        assertEquals(existingKeys, Set.copyOf(SaManager.getSaTokenDao().searchData("", "", 0, -1, true)),
                "Rejected authentication must not write Sa-Token session or token data");
    }

    private User givenAccount() {
        User user = new User();
        user.setId(USER_ID);
        user.setUserName("alice");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole("user");
        user.setBanned(false);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(userMapper.selectById(anyLong())).thenReturn(user);
        Contact contact = new Contact();
        contact.setAccountId(USER_ID);
        when(contactMapper.selectOne(any())).thenReturn(contact);
        return user;
    }

    private String loginJson(String identifier, String password) {
        return "{\"username\":\"" + identifier + "\",\"password\":\"" + password + "\",\"device\":\"WEB\"}";
    }
}
