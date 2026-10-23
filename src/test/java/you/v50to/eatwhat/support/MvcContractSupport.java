package you.v50to.eatwhat.support;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import you.v50to.eatwhat.config.GlobalExceptionHandler;
import you.v50to.eatwhat.config.JacksonConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** MVC contracts use real binding/validation/serialization/auth, without application config or network services. */
@ResourceLock("sa-token-global")
public abstract class MvcContractSupport {
    protected static final String TOKEN_HEADER = "satoken";
    /**
     * The JSON converter Spring Boot actually wires into MVC in production. JacksonConfig's epoch-millis
     * objectMapper bean is present but not used for HTTP, so dates go out as ISO-8601 strings.
     */
    private static final JacksonJsonHttpMessageConverter WIRE_CONVERTER = productionJsonConverter();
    private final Map<String, List<String>> rolesByAccount = new HashMap<>();
    private final List<LocalValidatorFactoryBean> validators = new ArrayList<>();
    private SaTokenConfig previousConfig;
    private SaTokenDao previousDao;
    private SaTokenContext previousContext;
    private StpInterface previousInterface;
    private StpLogic previousLogic;
    private Map<String, StpLogic> previousLogicMap;
    private RequestAttributes previousRequest;

    @BeforeEach
    protected void initializeContractAuthentication() {
        previousConfig = SaManager.getConfig();
        previousDao = SaManager.getSaTokenDao();
        previousContext = SaManager.getSaTokenContext();
        previousInterface = SaManager.getStpInterface();
        previousLogic = StpUtil.getStpLogic();
        previousLogicMap = new HashMap<>(SaManager.stpLogicMap);
        previousRequest = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        SaManager.setConfig(new SaTokenConfig().setTokenName(TOKEN_HEADER).setTokenPrefix("Bearer")
                .setTimeout(-1).setActiveTimeout(259200).setIsConcurrent(false).setIsShare(false)
                .setCookieAutoFillPrefix(true).setIsPrint(false).setDataRefreshPeriod(-1));
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        SaManager.setStpInterface(new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return List.of();
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                return rolesByAccount.getOrDefault(loginId.toString(), List.of());
            }
        });
        StpUtil.setStpLogic(new StpLogic(StpUtil.TYPE));
    }

    @AfterEach
    protected void restoreContractAuthentication() {
        validators.forEach(LocalValidatorFactoryBean::close);
        validators.clear();
        rolesByAccount.clear();
        RequestContextHolder.resetRequestAttributes();
        SaManager.setSaTokenDao(previousDao);
        SaManager.setSaTokenContext(previousContext);
        SaManager.setStpInterface(previousInterface);
        SaManager.setConfig(previousConfig);
        StpUtil.setStpLogic(previousLogic);
        SaManager.stpLogicMap.clear();
        SaManager.stpLogicMap.putAll(previousLogicMap);
        if (previousRequest != null) {
            RequestContextHolder.setRequestAttributes(previousRequest);
        }
    }

    protected MockMvc mvc(Object... controllers) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        validators.add(validator);
        return MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(WIRE_CONVERTER)
                .setValidator(validator)
                .addInterceptors(new SaInterceptor())
                .build();
    }

    /** For contracts that build their own MockMvc: serialize exactly like production HTTP responses. */
    protected static JacksonJsonHttpMessageConverter wireConverter() {
        return WIRE_CONVERTER;
    }

    private static JacksonJsonHttpMessageConverter productionJsonConverter() {
        AtomicReference<JacksonJsonHttpMessageConverter> converter = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> {
                    HttpMessageConverters.ServerBuilder builder = HttpMessageConverters.forServer();
                    context.getBeanProvider(ServerHttpMessageConvertersCustomizer.class).orderedStream()
                            .forEach(customizer -> customizer.customize(builder));
                    for (var candidate : builder.build()) {
                        if (candidate instanceof JacksonJsonHttpMessageConverter json) converter.compareAndSet(null, json);
                    }
                });
        if (converter.get() == null) throw new IllegalStateException("Spring Boot registered no Jackson JSON converter");
        return converter.get();
    }

    protected String loginAs(long id, String... roles) {
        rolesByAccount.put(Long.toString(id), List.of(roles));
        RequestAttributes current = RequestContextHolder.getRequestAttributes();
        try {
            bindToken(null);
            StpUtil.login(id, "web");
            return StpUtil.getTokenValue();
        } finally {
            RequestContextHolder.resetRequestAttributes();
            if (current != null) {
                RequestContextHolder.setRequestAttributes(current);
            }
        }
    }

    protected void bindToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader(TOKEN_HEADER, "Bearer " + token);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }
}
