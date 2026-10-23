package you.v50to.eatwhat.control;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.*;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.util.Set;

/** Credential changes revoke admin tokens on all application routes, not just account settings. */
public final class ControlAccountWeb implements WebMvcConfigurer,HandlerInterceptor {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.control.independent.IndependentControlAccounts independentControlAccounts;
    private final ControlAccountSecurity security;
    public ControlAccountWeb(ControlAccountSecurity security){this.security=security;}
    public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(this).order(-250);}
    public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception{
        String path=request.getServletPath();
        if(independentControlAccounts!=null){
            if(path.startsWith("/admin/control/")){try{independentControlAccounts.requireAdmin();}catch(ControlFailure failure){response.setStatus(failure.status);response.setContentType("application/json");response.getWriter().write("{\"code\":\""+failure.code+"\"}");return false;}}
            return true;
        }
        if(!security.authority()||path.startsWith("/internal/")||path.startsWith("/actuator/")||Set.of("/auth/login","/auth/phone/code","/auth/phone/login","/auth/register","/auth/logout").contains(path))return true;
        try{Object id=StpUtil.getLoginIdDefaultNull();if(id==null)return true;if(path.startsWith("/admin/control/")||!security.independentBusinessAuthentication())security.requireSession(Long.parseLong(id.toString()));else security.requireBusinessSession(Long.parseLong(id.toString()));return true;}
        catch(ControlFailure failure){response.setStatus(failure.status);response.setContentType("application/json");response.setHeader("Cache-Control","no-store");response.getWriter().write("{\"code\":\""+failure.code+"\"}");return false;}
        catch(RuntimeException failure){response.setStatus(503);response.setContentType("application/json");response.setHeader("Cache-Control","no-store");response.getWriter().write("{\"code\":\"ACCOUNT_SECURITY_UNAVAILABLE\"}");return false;}
    }
}
