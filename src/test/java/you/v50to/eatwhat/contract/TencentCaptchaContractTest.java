package you.v50to.eatwhat.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import you.v50to.eatwhat.account.AccountAccessGate;
import you.v50to.eatwhat.account.AccountAuthService;
import you.v50to.eatwhat.account.multiwriter.AccountPasswordRecovery;
import you.v50to.eatwhat.account.multiwriter.AccountPasswordRecoveryController;
import you.v50to.eatwhat.config.TencentCaptchaProperties;
import you.v50to.eatwhat.controller.AuthController;
import you.v50to.eatwhat.controller.UserController;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.service.AuthService;
import you.v50to.eatwhat.service.TencentCaptchaService;
import you.v50to.eatwhat.service.UserService;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TencentCaptchaContractTest extends MvcContractSupport {
    private static final String CLIENT_IP = "203.0.113.7";
    private AuthService auth;
    private UserService users;
    private AccountPasswordRecovery recovery;
    private AuthController authController;
    private UserController userController;
    private AccountPasswordRecoveryController recoveryController;
    private MockMvc http;
    private String session;

    @BeforeEach void configure() {
        auth = mock(AuthService.class);
        users = mock(UserService.class);
        recovery = mock(AccountPasswordRecovery.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        authController = new AuthController();
        userController = new UserController();
        recoveryController = new AccountPasswordRecoveryController(recovery);
        ReflectionTestUtils.setField(authController, "authService", auth);
        ReflectionTestUtils.setField(userController, "userService", users);
        var sourceRequest = new org.springframework.mock.web.MockHttpServletRequest();
        sourceRequest.setRemoteAddr(CLIENT_IP);
        ReflectionTestUtils.setField(authController, "request", sourceRequest);
        ReflectionTestUtils.setField(userController, "request", sourceRequest);
        useVerifier(new TencentCaptchaService(new TencentCaptchaProperties()));
        session = loginAs(52, "verified");
    }

    static Stream<String> routes() {
        return Stream.of("/auth/phone/code", "/auth/getCode", "/auth/password/code", "/auth/sendEmail",
                "/user/getCode", "/auth/password-conflict/challenge");
    }

    @ParameterizedTest @MethodSource("routes")
    void missingTokenBlocksEverySendBeforeBusinessSideEffects(String path) throws Exception {
        http.perform(request(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.CAPTCHA_REQUIRED.getCode()));
        verifyNoInteractions(auth, users, recovery);
    }

    @ParameterizedTest @MethodSource("routes")
    void eachHeaderIsRequiredIndependently(String path) throws Exception {
        for (String header : new String[]{TencentCaptchaService.TICKET_HEADER, TencentCaptchaService.RANDSTR_HEADER}) {
            http.perform(request(path).header(header, "synthetic-value"))
                    .andExpect(jsonPath("$.code").value(BizCode.CAPTCHA_REQUIRED.getCode()));
        }
        verifyNoInteractions(auth, users, recovery);
    }

    @ParameterizedTest @MethodSource("routes")
    void missingKeysNeverSilentlyDisableProtection(String path) throws Exception {
        http.perform(request(path).header(TencentCaptchaService.TICKET_HEADER, "synthetic-token").header(TencentCaptchaService.RANDSTR_HEADER, "synthetic-randstr"))
                .andExpect(jsonPath("$.code").value(BizCode.CAPTCHA_UNAVAILABLE.getCode()));
        verifyNoInteractions(auth, users, recovery);
    }

    @ParameterizedTest @MethodSource("routes")
    void invalidTokenBlocksEverySend(String path) throws Exception {
        TencentCaptchaService verifier = mock(TencentCaptchaService.class);
        doThrow(new BizException(BizCode.CAPTCHA_INVALID)).when(verifier).verify(eq("synthetic-invalid-token"), eq("synthetic-randstr"), anyString());
        useVerifier(verifier);
        http.perform(request(path).header(TencentCaptchaService.TICKET_HEADER, "synthetic-invalid-token").header(TencentCaptchaService.RANDSTR_HEADER, "synthetic-randstr"))
                .andExpect(jsonPath("$.code").value(BizCode.CAPTCHA_INVALID.getCode()));
        verify(verifier).verify(eq("synthetic-invalid-token"), eq("synthetic-randstr"), anyString());
        verifyNoInteractions(auth, users, recovery);
    }

    @ParameterizedTest @MethodSource("routes")
    void verifiedTokenReachesExistingSendLogicInOrder(String path) throws Exception {
        TencentCaptchaService verifier = mock(TencentCaptchaService.class);
        useVerifier(verifier);
        when(auth.sendLoginCode(anyString(), anyString())).thenReturn(Result.ok());
        when(auth.sendCode(any(), anyString(), anyString())).thenReturn(Result.ok());
        when(auth.sendPasswordCode(anyString())).thenReturn(Result.ok());
        when(auth.sendEmail(anyString(), anyString())).thenReturn(Result.ok());
        when(users.getCode(any(), anyString())).thenReturn(Result.ok());
        http.perform(request(path).header(TencentCaptchaService.TICKET_HEADER, "synthetic-valid-token").header(TencentCaptchaService.RANDSTR_HEADER, "synthetic-randstr"))
                .andExpect(jsonPath("$.code").value(0));
        var order = inOrder(verifier, auth, users, recovery);
        order.verify(verifier).verify("synthetic-valid-token", "synthetic-randstr", CLIENT_IP);
        switch (path) {
            case "/auth/phone/code" -> order.verify(auth).sendLoginCode(eq("13800000001"), anyString());
            case "/auth/getCode" -> order.verify(auth).sendCode(any(), eq("13800000001"), anyString());
            case "/auth/password/code" -> order.verify(auth).sendPasswordCode(anyString());
            case "/auth/sendEmail" -> order.verify(auth).sendEmail(eq("student@mail.sdu.edu.cn"), anyString());
            case "/user/getCode" -> order.verify(users).getCode(any(), anyString());
            default -> order.verify(recovery).request(eq("synthetic-user"), anyString());
        }
        order.verifyNoMoreInteractions();
    }

    @Test void widgetConfigIsPublicFreshAndUncacheableWithoutExposingSecrets() throws Exception {
        var properties = new TencentCaptchaProperties();
        properties.setAppId(123456L);
        properties.setSecretId("synthetic-secret-id");
        properties.setSecretKey("synthetic-cloud-secret-never-public");
        properties.setAppSecretKey("synthetic-secret-never-public");
        useVerifier(new TencentCaptchaService(properties));
        var body = http.perform(get("/auth/captcha/config"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.provider").value("tencent-cloud-intl"))
                .andExpect(jsonPath("$.data.appId").value("123456"))
                .andExpect(jsonPath("$.data.scriptUrl").value("https://ca.turing.captcha.qcloud.com/TJNCaptcha-global.js"))
                .andExpect(jsonPath("$.data.aidEncrypted").isNotEmpty())
                .andExpect(jsonPath("$.data.aidEncryptedType").value("cbc"))
                .andExpect(jsonPath("$.data.expiresAt").isNumber())
                .andExpect(jsonPath("$.data.secretKey").doesNotExist())
                .andExpect(jsonPath("$.data.appSecretKey").doesNotExist())
                .andExpect(jsonPath("$.data.secretId").doesNotExist())
                .andExpect(jsonPath("$.data.sessionToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("synthetic-secret"));
        assertFalse(body.contains("synthetic-cloud-secret"));
        var second = http.perform(get("/auth/captcha/config"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertNotEquals(json.readTree(body).path("data").path("aidEncrypted").textValue(),
                json.readTree(second).path("data").path("aidEncrypted").textValue());
        var accounts = mock(AccountAuthService.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(accounts.external()).thenReturn(true);
        var handler = http.getDispatcherServlet().getWebApplicationContext()
                .getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class)
                .getHandlerMethods().values().stream().filter(it -> it.getMethod().getName().equals("captchaConfig"))
                .findFirst().orElseThrow();
        assertTrue(new AccountAccessGate(accounts).preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                new org.springframework.mock.web.MockHttpServletResponse(), handler));
        verify(accounts, never()).assertExternalSession(anyLong());
    }

    @Test void captchaCannotReplaceRequiredLogin() throws Exception {
        var verifier = mock(TencentCaptchaService.class);
        useVerifier(verifier);
        http.perform(post("/auth/password/code").header(TencentCaptchaService.TICKET_HEADER, "synthetic-valid-token").header(TencentCaptchaService.RANDSTR_HEADER, "synthetic-randstr"))
                .andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
        verifyNoInteractions(verifier, auth, users, recovery);
    }

    private void useVerifier(TencentCaptchaService verifier) {
        ReflectionTestUtils.setField(authController, "captchaService", verifier);
        ReflectionTestUtils.setField(userController, "captchaService", verifier);
        ReflectionTestUtils.setField(recoveryController, "captchaService", verifier);
        http = mvc(authController, userController, recoveryController);
    }

    private MockHttpServletRequestBuilder request(String path) {
        var request = switch (path) {
            case "/auth/sendEmail" -> get(path).param("email", "student@mail.sdu.edu.cn");
            case "/auth/getCode" -> post(path).param("mobile", "13800000001");
            case "/auth/password/code" -> post(path);
            case "/auth/phone/code" -> post(path).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"mobile\":\"13800000001\"}");
            case "/user/getCode" -> post(path).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"mobile\":\"13800000001\",\"scene\":\"bind\"}");
            default -> post(path).contentType(MediaType.APPLICATION_JSON).content("{\"identifier\":\"synthetic-user\"}");
        };
        request.with(raw -> { raw.setRemoteAddr(CLIENT_IP); return raw; });
        return path.equals("/auth/phone/code") || path.equals("/auth/password-conflict/challenge")
                ? request : request.header(TOKEN_HEADER, "Bearer " + session);
    }
}
