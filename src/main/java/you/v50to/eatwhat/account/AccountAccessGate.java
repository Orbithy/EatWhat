package you.v50to.eatwhat.account;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import you.v50to.eatwhat.controller.AuthController;
import you.v50to.eatwhat.controller.CallbackController;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.exception.BizException;

import java.util.Set;

/** Conditionally registered with the opt-in account module; no path or proxy header can select a site. */
public final class AccountAccessGate implements WebMvcConfigurer, HandlerInterceptor {
    private static final Set<String> PUBLIC_AUTH = Set.of("register", "login", "checkUsername", "logout", "sendLoginCode", "phoneLogin", "callback", "callbackWithState");
    private final AccountAuthService accounts;

    public AccountAccessGate(AccountAuthService accounts) { this.accounts = accounts; }

    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this).order(-100); }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        Class<?> owner = method.getBeanType();
        if (AuthController.class.isAssignableFrom(owner) || AccountConsentController.class.isAssignableFrom(owner)) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }
        if (!accounts.external() || AccountConsentController.class.isAssignableFrom(owner)
                || you.v50to.eatwhat.controller.ConsentPolicyAdminController.class.isAssignableFrom(owner)
                || (AuthController.class.isAssignableFrom(owner) && PUBLIC_AUTH.contains(method.getMethod().getName()))
                // The school callback is authorized by its one-time state, not by whatever session the browser carries.
                || CallbackController.class.isAssignableFrom(owner)
                || !owner.getPackageName().startsWith("you.v50to.eatwhat.controller")) return true;
        // Existing Sa-Token annotations retain their original unauthenticated error contracts.
        if (!StpUtil.isLogin()) return true;
        try { accounts.assertExternalSession(StpUtil.getLoginIdAsLong()); }
        catch (BizException rejected) { throw rejected; }
        catch (RuntimeException unavailable) { throw new BizException(BizCode.AUTH_UNAVAILABLE); }
        return true;
    }
}
