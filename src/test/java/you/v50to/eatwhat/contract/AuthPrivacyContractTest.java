package you.v50to.eatwhat.contract;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import you.v50to.eatwhat.account.AuthPrivacyExceptionHandler;
import you.v50to.eatwhat.config.GlobalExceptionHandler;
import you.v50to.eatwhat.controller.AuthController;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.service.AuthService;
import you.v50to.eatwhat.service.SmsService;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Account module absent: original AuthService, no consent hook, no new controller or gate. */
class AuthPrivacyContractTest extends MvcContractSupport {
    private static final long ID = 51L;
    private SmsService sms;
    private AuthService service;
    private MockMvc protectedHttp;
    private MockMvc originalHttp;
    private LocalValidatorFactoryBean validator;

    @BeforeEach void configure() {
        sms = mock(SmsService.class);
        service = spy(new AuthService());
        ReflectionTestUtils.setField(service, "smsService", sms);
        assertNull(ReflectionTestUtils.getField(service, "accountAuth"));
        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "captchaService", mock(you.v50to.eatwhat.service.TencentCaptchaService.class));
        ReflectionTestUtils.setField(controller, "authService", service);
        validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        originalHttp = mvc(controller);
        protectedHttp = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AuthPrivacyExceptionHandler(), new GlobalExceptionHandler())
                .setMessageConverters(wireConverter())
                .setValidator(validator).addInterceptors(new SaInterceptor()).build();
    }

    @AfterEach void closeValidator() { validator.close(); }

    @ParameterizedTest
    @CsvSource({
            "TOO_MANY_REQUESTS,请求过于频繁，请稍后再试",
            "TOO_MANY_REQUESTS,短信发送过于频繁",
            "THIRD_PARTY_TIMEOUT,短信服务请求超时",
            "THIRD_PARTY_UNAVAILABLE,短信服务不可用",
            "PARAM_MISSING,scene不能为空",
            "PARAM_MISSING,手机号不能为空",
            "PARAM_INVALID,手机号格式不正确"
    })
    void disabledModulePreservesAuditedStaticSmsFailureCodeAndMessage(BizCode code, String detail) throws Exception {
        String token = loginAs(ID, "verified");
        doThrow(new BizException(code, detail)).when(sms).sendCode(eq(you.v50to.eatwhat.data.enums.Scene.auth), eq("13800000000"), anyString(), eq(ID));
        for (MockMvc http : new MockMvc[]{originalHttp, protectedHttp}) {
            http.perform(post("/auth/getCode").param("mobile", "13800000000").header(TOKEN_HEADER, "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(code.getCode()))
                    .andExpect(jsonPath("$.msg").value(code.getMsg() + "：" + detail));
        }
        verify(sms, times(2)).sendCode(eq(you.v50to.eatwhat.data.enums.Scene.auth), eq("13800000000"), anyString(), eq(ID));
    }

    @ParameterizedTest @ValueSource(strings = {"TOO_MANY_REQUESTS", "THIRD_PARTY_BAD_RESPONSE", "UNKNOWN_ERROR"})
    void disabledModuleStillRedactsUnauditedBusinessDetails(BizCode code) throws Exception {
        String token = loginAs(ID, "verified");
        doThrow(new BizException(code, "请求过于频繁，请稍后再试 password-sentinel proof-sentinel"))
                .when(sms).sendCode(eq(you.v50to.eatwhat.data.enums.Scene.auth), eq("13800000000"), anyString(), eq(ID));
        String response = protectedHttp.perform(post("/auth/getCode").param("mobile", "13800000000")
                        .header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(code.getCode())).andExpect(jsonPath("$.msg").value(code.getMsg()))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("sentinel"));
    }

    @ParameterizedTest @CsvSource({"verified,用户未验证", "admin,需要管理员权限", "other,权限不足"})
    void disabledModuleKeepsOriginalRoleFailureMessages(String role, String detail) throws Exception {
        String token = loginAs(ID);
        doAnswer(ignored -> { StpUtil.checkRole(role); return Result.ok(); }).when(service).verify();
        for (MockMvc http : new MockMvc[]{originalHttp, protectedHttp}) {
            http.perform(get("/auth/verify").header(TOKEN_HEADER, "Bearer " + token))
                    .andExpect(jsonPath("$.code").value(20004)).andExpect(jsonPath("$.msg").value("权限不足：" + detail));
        }
    }

    @Test void disabledModuleKeepsDatabaseFailureContractWithoutItsQueryOrCause() throws Exception {
        String token = loginAs(ID);
        doThrow(new BadSqlGrammarException("proof-sentinel", "SELECT password_sentinel", new SQLException("password-sentinel")))
                .when(service).verify();
        String response = protectedHttp.perform(get("/auth/verify").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(90002))
                .andExpect(jsonPath("$.msg").value("数据库异常：数据库未初始化或表不存在"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("sentinel"));
    }

    @Test void disabledModuleStillRedactsMalformedLoginInputAndDoesNotExposeConsentRoutes() throws Exception {
        String response = protectedHttp.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password-sentinel\",\"device\":\"proof-sentinel\"}"))
                .andExpect(jsonPath("$.code").value(10001)).andExpect(jsonPath("$.msg").value("参数非法"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("sentinel"));
        var mappings = protectedHttp.getDispatcherServlet().getWebApplicationContext()
                .getBean(RequestMappingHandlerMapping.class).getHandlerMethods();
        assertFalse(mappings.keySet().stream().flatMap(mapping -> mapping.getPatternValues().stream())
                .anyMatch(path -> path.startsWith("/auth/consent")));
        verifyNoInteractions(sms);
    }
}
