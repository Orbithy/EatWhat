package you.v50to.eatwhat.contract;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.jsonwebtoken.Jwts;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import you.v50to.eatwhat.config.SecurityConfig;
import you.v50to.eatwhat.config.StpInterfaceImpl;
import you.v50to.eatwhat.controller.AuthController;
import you.v50to.eatwhat.controller.CallbackController;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.data.po.Verification;
import you.v50to.eatwhat.mapper.ContactMapper;
import you.v50to.eatwhat.mapper.PrivacyMapper;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.mapper.VerificationMapper;
import you.v50to.eatwhat.service.AuthService;
import you.v50to.eatwhat.service.EmailService;
import you.v50to.eatwhat.service.SmsService;
import you.v50to.eatwhat.support.MvcContractSupport;
import you.v50to.eatwhat.utils.JwtUtil;

import tools.jackson.databind.json.JsonMapper;

import javax.crypto.SecretKey;
import javax.sql.DataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一认证（现状差距 2.1「统一认证只用于认证」、2.2 前两问）：/auth/sdu 发一次性 state，浏览器回跳凭 state 认证，
 * 不需要登录态。真实的 AuthService、StpInterfaceImpl 和 Sa-Token，数据库和 Redis 用内存替身。
 */
class SduCallbackContractTest extends MvcContractSupport {
    private static final long USER_ID = 61L;
    private static final String SECRET = "callback-contract-secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MockMvc http;
    private AuthService service;
    private VerificationMapper verificationMapper;
    private final Map<String, String> redisValues = new HashMap<>();
    private final AtomicReference<Verification> stored = new AtomicReference<>();
    private boolean studentIdTaken;

    @BeforeEach
    void createApi() {
        // StpInterfaceImpl uses lambda wrappers, which need MyBatis-Plus table metadata.
        for (Class<?> entity : List.of(Verification.class, User.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "sdu-callback-test"), entity);
        }
        UserMapper userMapper = mock(UserMapper.class);
        User user = new User();
        user.setId(USER_ID);
        user.setRole("user");
        when(userMapper.selectOne(any())).thenReturn(user);
        verificationMapper = mock(VerificationMapper.class);
        when(verificationMapper.selectOne(any())).thenAnswer(ignored -> stored.get());
        doAnswer(i -> { stored.set(i.getArgument(0)); return 1; }).when(verificationMapper).insert(any(Verification.class));

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> redisValues.get(i.<String>getArgument(0)));
        doAnswer(i -> { redisValues.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.getAndDelete(anyString())).thenAnswer(i -> redisValues.remove(i.<String>getArgument(0)));
        when(verificationMapper.exists(any())).thenAnswer(ignored -> studentIdTaken);

        service = new AuthService();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "contactMapper", mock(ContactMapper.class));
        ReflectionTestUtils.setField(service, "privacyMapper", mock(PrivacyMapper.class));
        ReflectionTestUtils.setField(service, "verificationMapper", verificationMapper);
        ReflectionTestUtils.setField(service, "passwordEncoder", new SecurityConfig().passwordEncoder());
        ReflectionTestUtils.setField(service, "emailService", mock(EmailService.class));
        ReflectionTestUtils.setField(service, "smsService", mock(SmsService.class));
        ReflectionTestUtils.setField(service, "transactionManager", mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "redis", redis);
        ReflectionTestUtils.setField(service, "secret", SECRET);
        // The deployed config still forwards to /api/v1/callback; the callback base is derived from its host.
        ReflectionTestUtils.setField(service, "oauthUrl",
                "https://cas.example.invalid/cas/proxy/login/page?forward=https://app.example.invalid/api/v1/callback");

        // Production role resolution instead of the support class's fixed role table.
        StpInterfaceImpl roles = new StpInterfaceImpl();
        ReflectionTestUtils.setField(roles, "verificationMapper", verificationMapper);
        ReflectionTestUtils.setField(roles, "userMapper", userMapper);
        ReflectionTestUtils.setField(roles, "redis", redis);
        SaManager.setStpInterface(roles);

        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "authService", service);
        CallbackController callback = new CallbackController();
        ReflectionTestUtils.setField(callback, "authService", service);
        http = mvc(controller, callback);
    }

    private static String casToken(String casId, String name) {
        SecretKey key = ReflectionTestUtils.invokeMethod(JwtUtil.class, "generateSecretKey", SECRET);
        return Jwts.builder().claim("casID", casId).claim("name", name).signWith(key).compact();
    }

    /** Calls /auth/sdu with the App's header token and returns the state embedded in the forward URL. */
    private String startVerification(String token) throws Exception {
        return startVerification(token, null);
    }

    private String startVerification(String token, String client) throws Exception {
        var request = get("/auth/sdu").header(TOKEN_HEADER, "Bearer " + token);
        if (client != null) request.param("client", client);
        String body = http.perform(request)
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.expiresIn").value(300))
                .andReturn().getResponse().getContentAsString();
        String url = JSON.readTree(body).get("data").get("url").asText();
        String prefix = "https://cas.example.invalid/cas/proxy/login/page?forward="
                + URLEncoder.encode("https://app.example.invalid/api/callback/", StandardCharsets.UTF_8);
        assertTrue(url.startsWith(prefix), url);
        return url.substring(prefix.length());
    }

    @Test
    void appCallbackOpensTheRegisteredHttpsLinkAndKeepsTheExistingSession() throws Exception {
        String token = loginAs(USER_ID);
        String state = startVerification(token, "app");
        assertEquals("app:" + USER_ID, redisValues.get("sso:state:v1:legacy:" + state));
        http.perform(get("/callback/" + state).param("token", casToken("202300001", "张三")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://v50to.you/app/auth/callback"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(USER_ID, stored.get().getAccountId());
        assertEquals(List.of(token), StpUtil.getTokenValueListByLoginId(USER_ID));
        http.perform(get("/auth/verify").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data[0]").value("verify:sso"));
        http.perform(get("/callback/" + state).param("token", casToken("202300001", "张三")))
                .andExpect(jsonPath("$.code").value(BizCode.SSO_STATE_INVALID.getCode()))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void callbackCannotChangeTheClientOrSupplyAnArbitraryRedirect() throws Exception {
        String token = loginAs(USER_ID);
        String webState = startVerification(token, "web");
        http.perform(get("/callback/" + webState).param("token", casToken("202300001", "张三"))
                        .param("client", "app").param("redirect", "https://untrusted.invalid"))
                .andExpect(redirectedUrl("/home"));
        String appState = startVerification(token, "app");
        http.perform(get("/auth/callback/" + appState).param("token", casToken("202300001", "张三"))
                        .param("client", "web").param("redirect", "https://untrusted.invalid"))
                .andExpect(redirectedUrl("https://v50to.you/app/auth/callback"));
    }

    @Test
    void unsupportedClientDoesNotIssueAState() throws Exception {
        http.perform(get("/auth/sdu").param("client", "https://untrusted.invalid")
                        .header(TOKEN_HEADER, "Bearer " + loginAs(USER_ID)))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        assertTrue(redisValues.keySet().stream().noneMatch(key -> key.startsWith("sso:state:")));
    }

    @Test
    void failedAppVerificationDoesNotOpenTheAppAndRequiresANewLink() throws Exception {
        String token = loginAs(USER_ID);
        String state = startVerification(token, "app");
        http.perform(get("/callback/" + state).param("token", "not-a-jwt"))
                .andExpect(jsonPath("$.code").value(BizCode.THIRD_PARTY_BAD_RESPONSE.getCode()))
                .andExpect(header().doesNotExist("Location"));
        http.perform(get("/callback/" + state).param("token", casToken("202300001", "张三")))
                .andExpect(jsonPath("$.code").value(BizCode.SSO_STATE_INVALID.getCode()));
        studentIdTaken = true;
        String anotherState = startVerification(token, "app");
        http.perform(get("/callback/" + anotherState).param("token", casToken("202300001", "张三")))
                .andExpect(jsonPath("$.code").value(BizCode.STUDENT_ID_TAKEN.getCode()))
                .andExpect(header().doesNotExist("Location"));
        verify(verificationMapper, never()).insert(any(Verification.class));
    }

    private TransactionTemplate transaction(Connection connection) throws SQLException {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    private MockHttpServletResponse callbackResponse(String state) {
        // Match production: the service transaction commits before MVC writes the response body.
        var response = new MockHttpServletResponse();
        assertEquals(0, service.callBack(state, casToken("202300001", "张三"), response).getCode());
        return response;
    }

    @Test
    void appRedirectAndPermissionsArePublishedOnlyAfterCommit() throws Exception {
        String state = startVerification(loginAs(USER_ID), "app");
        var connection = mock(Connection.class);
        var response = transaction(connection).execute(status -> {
            var pending = callbackResponse(state);
            assertNull(pending.getRedirectedUrl());
            assertNull(redisValues.get("auth:" + USER_ID));
            return pending;
        });
        verify(connection).commit();
        assertEquals("https://v50to.you/app/auth/callback", response.getRedirectedUrl());
        assertEquals("sso", redisValues.get("auth:" + USER_ID));
    }

    @Test
    void rolledBackVerificationNeverRedirectsOrPublishesPermissions() throws Exception {
        String state = startVerification(loginAs(USER_ID), "app");
        var connection = mock(Connection.class);
        var response = transaction(connection).execute(status -> {
            var pending = callbackResponse(state);
            status.setRollbackOnly();
            return pending;
        });
        verify(connection).rollback();
        assertNull(response.getRedirectedUrl());
        assertNull(redisValues.get("auth:" + USER_ID));
    }

    @Test
    void failedDatabaseCommitNeverOpensTheApp() throws Exception {
        String state = startVerification(loginAs(USER_ID), "app");
        var connection = mock(Connection.class);
        doThrow(new SQLException("synthetic commit failure")).when(connection).commit();
        var pending = new AtomicReference<MockHttpServletResponse>();
        var template = transaction(connection);
        assertThrows(org.springframework.transaction.TransactionSystemException.class, () ->
                template.execute(status -> {
                    pending.set(callbackResponse(state));
                    return null;
                }));
        assertNull(pending.get().getRedirectedUrl());
        assertNull(redisValues.get("auth:" + USER_ID));
    }

    @Test
    void startBindsASingleUseStateToTheCurrentAccount() throws Exception {
        String state = startVerification(loginAs(USER_ID));
        assertEquals(43, state.length());
        assertEquals(Long.toString(USER_ID), redisValues.get("sso:state:v1:legacy:" + state));
    }

    @Test
    void browserCallbackWithStateVerifiesWithoutAnySessionAndIssuesNone() throws Exception {
        String token = loginAs(USER_ID);
        String state = startVerification(token);

        // The gateway strips /api/, so /api/callback/{state} reaches /callback/{state}. No header, no cookie.
        http.perform(get("/callback/" + state).param("token", casToken("202300001", "张三")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/home"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        ArgumentCaptor<Verification> written = ArgumentCaptor.forClass(Verification.class);
        verify(verificationMapper).insert(written.capture());
        assertEquals(USER_ID, written.getValue().getAccountId());
        assertEquals("sso", written.getValue().getMethod());
        assertEquals("202300001", written.getValue().getStudentId());
        assertEquals("张三", written.getValue().getRealName());
        assertEquals(List.of(token), StpUtil.getTokenValueListByLoginId(USER_ID));
    }

    @Test
    void stateCanBeUsedOnlyOnce() throws Exception {
        String state = startVerification(loginAs(USER_ID));
        http.perform(get("/auth/callback/" + state).param("token", casToken("202300001", "张三")))
                .andExpect(status().isFound());
        http.perform(get("/auth/callback/" + state).param("token", casToken("202300001", "张三")))
                .andExpect(jsonPath("$.code").value(BizCode.SSO_STATE_INVALID.getCode()));
        verify(verificationMapper).insert(any(Verification.class));
    }

    @Test
    void retiredCallbacksHaveNoRegisteredRoutes() {
        var mapping = http.getDispatcherServlet().getWebApplicationContext()
                .getBean(RequestMappingHandlerMapping.class);
        var paths = mapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream()).toList();
        assertFalse(paths.contains("/callback"));
        assertFalse(paths.contains("/auth/callback"));
    }

    @Test
    void unknownOrMalformedStateWritesNothing() throws Exception {
        String token = loginAs(USER_ID);
        for (String path : List.of("/callback/" + "A".repeat(43), "/callback/short", "/auth/callback/short")) {
            // A session on the request does not substitute for the state.
            http.perform(get(path).param("token", casToken("202300001", "张三")).header(TOKEN_HEADER, "Bearer " + token))
                    .andExpect(jsonPath("$.code").value(BizCode.SSO_STATE_INVALID.getCode()));
        }
        verify(verificationMapper, never()).insert(any(Verification.class));
    }

    @Test
    void verifiedRoleAppliesToTheExistingTokenOnTheNextRequest() throws Exception {
        String token = loginAs(USER_ID);
        // Before verification the "not verified" answer is cached, as it would be after any earlier request.
        http.perform(get("/auth/verify").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").isEmpty());
        assertEquals(List.of(), StpUtil.getRoleList(USER_ID));

        String state = startVerification(token);
        http.perform(get("/callback/" + state).param("token", casToken("202300001", "张三"))).andExpect(status().isFound());

        http.perform(get("/auth/verify").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data[0]").value("verify:sso"));
        assertTrue(StpUtil.getRoleList(USER_ID).contains("verified"));
    }

    @Test
    void invalidCasTokenConsumesTheStateAndWritesNothing() throws Exception {
        String state = startVerification(loginAs(USER_ID));
        http.perform(get("/callback/" + state).param("token", "not-a-jwt"))
                .andExpect(jsonPath("$.code").value(BizCode.THIRD_PARTY_BAD_RESPONSE.getCode()));
        verify(verificationMapper, never()).insert(any(Verification.class));
        assertNull(redisValues.get("auth:" + USER_ID));
        assertNull(redisValues.get("sso:state:v1:legacy:" + state));
    }

    @Test
    void aStudentIdVerifiedOnAnotherAccountIsRejected() throws Exception {
        studentIdTaken = true;
        String state = startVerification(loginAs(USER_ID));
        http.perform(get("/callback/" + state).param("token", casToken("202300001", "张三")))
                .andExpect(jsonPath("$.code").value(BizCode.STUDENT_ID_TAKEN.getCode()));
        verify(verificationMapper, never()).insert(any(Verification.class));
        assertNull(redisValues.get("auth:" + USER_ID));
    }

    @Test
    void startingVerificationStillRequiresLogin() throws Exception {
        http.perform(get("/auth/sdu")).andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
    }
}
