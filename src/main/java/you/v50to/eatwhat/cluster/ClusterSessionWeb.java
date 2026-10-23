package you.v50to.eatwhat.cluster;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.*;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.util.Set;

/** Runs before Sa-Token login annotations. The same bearer token follows a client across participating servers. */
public final class ClusterSessionWeb implements WebMvcConfigurer,HandlerInterceptor {
    @org.springframework.beans.factory.annotation.Value("${app.control.independent.enabled:false}") private boolean independentControl;
    private final ClusterSessions sessions;
    public ClusterSessionWeb(ClusterSessions sessions){this.sessions=sessions;}
    public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(this).order(-300);}
    public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception{
        String path=request.getServletPath();if(path.startsWith("/internal/")||Set.of("/auth/login","/auth/phone/code","/auth/phone/login","/auth/register","/auth/logout","/auth/consent/policy").contains(path))return true;
        if(independentControl&&path.startsWith("/admin/control/"))return true;
        if(!sessions.active())return true;String value=StpUtil.getTokenValue();if(value==null||value.isBlank())return true;
        try{var token=new ClusterSessions.Token(value);ClusterSessions.Authorization authorization;
            try{authorization=sessions.authorize(token);}catch(ClusterFailure failure){if(sessions.independentAuthentication()||failure.status()!=401||!failure.getMessage().equals("CLUSTER_SESSION_INVALID")||!StpUtil.isLogin())throw failure;authorization=sessions.register(token);}
            sessions.attach(token,authorization);return true;}
        catch(ClusterFailure failure){response.setStatus(failure.status());response.setContentType("application/json");response.setHeader("Cache-Control","no-store");response.getWriter().write("{\"code\":\""+failure.getMessage()+"\"}");return false;}
    }
}
