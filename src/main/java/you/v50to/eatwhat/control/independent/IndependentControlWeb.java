package you.v50to.eatwhat.control.independent;

import jakarta.servlet.http.*;
import jakarta.servlet.*;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.util.Set;

/** A copied business schema never turns a management process into a second business node. */
@Order(-3000)
public final class IndependentControlWeb extends OncePerRequestFilter implements WebMvcConfigurer,HandlerInterceptor {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private IndependentControlAccounts accounts;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<you.v50to.eatwhat.cluster.ClusterControlLease> lease;
    public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(this).order(-1000);}
    public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
        String path=request.getServletPath();
        if(permitted(path)){
            if(path.startsWith("/admin/control/v1/")&&Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod())
                    &&!path.startsWith("/admin/control/v1/cluster/leadership/handoffs")){
                accounts.requireAdmin();
                if(request.getMethod().equals("POST")&&path.matches("/admin/control/v1/deployments/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/reconcile"))
                    lease.getObject().requireHeld();
                else lease.getObject().requireAccepting();
            }
            return true;
        }
        response.setStatus(404);return false;
    }
    private boolean permitted(String path){
        if(path.startsWith("/admin/control/v1/")||path.equals("/admin/control/v1")
                ||path.startsWith("/internal/deployment/v1/fences/")
                ||Set.of("/auth/login","/auth/logout","/actuator/health","/actuator/health/readiness","/actuator/health/liveness","/error").contains(path))return true;
        return false;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws java.io.IOException,ServletException{
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(permitted(path))chain.doFilter(request,response);else response.setStatus(404);
    }
}
