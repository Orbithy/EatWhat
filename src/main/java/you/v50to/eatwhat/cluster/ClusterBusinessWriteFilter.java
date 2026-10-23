package you.v50to.eatwhat.cluster;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;

/** Fast admission check. Managed command transactions take the shared lock again at their commit boundary. */
@Component
@Order(-2000)
@ConditionalOnProperty(name="app.cluster.enabled",havingValue="true")
public class ClusterBusinessWriteFilter extends OncePerRequestFilter {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts multiwriter;
    private final ClusterBarrier barrier;private final JdbcTemplate jdbc;private final TransactionTemplate transaction;private final ClusterProperties cluster;private final boolean controlUi;
    public ClusterBusinessWriteFilter(JdbcTemplate jdbc,PlatformTransactionManager manager,ClusterProperties cluster,Environment environment){barrier=new ClusterBarrier(jdbc);this.jdbc=jdbc;this.cluster=cluster;transaction=new TransactionTemplate(manager);transaction.setTimeout(30);controlUi=environment.getProperty("app.control.enabled",Boolean.class,false)&&environment.getProperty("app.control.ui-enabled",Boolean.class,false);}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String path=request.getServletPath();
        if(controlUi&&Set.of("GET","HEAD").contains(request.getMethod())&&(path.equals("/control-ui")||path.startsWith("/control-ui/"))){chain.doFilter(request,response);return;}
        if(path.startsWith("/admin/control/")||path.startsWith("/internal/")||path.equals("/auth/logout")
                ||path.equals("/actuator/health")||path.startsWith("/actuator/health/")){chain.doFilter(request,response);return;}
        var states=jdbc.queryForList("SELECT state FROM eatwhat_cluster.local_state WHERE singleton");
        if(!states.isEmpty()&&states.getFirst().get("state").equals("RETIRED")){denied(response,"SITE_RETIRED");return;}
        if(path.equals("/auth/login")){chain.doFilter(request,response);return;}
        if(states.isEmpty()||states.getFirst().get("state").equals("JOINING")){denied(response,"SITE_JOIN_PENDING");return;}
        // Legacy security writes still require the authority. Independent account commands
        // are admitted only after both the writer and durable database guard are ready;
        // endpoint authorization and the local transaction barrier still apply.
        if(cluster.getAuthStore().isEnabled()&&!cluster.isCoordinator()&&
                !(multiwriter!=null&&multiwriter.enabled()&&multiwriter.healthy())&&
                (path.equals("/auth/changePassword")||path.equals("/auth/password/reset")||path.matches("/user/(?:ban|unban)/[0-9]+"))){unavailable(response,"AUTHORITY_SECURITY_WRITE_REQUIRED");return;}
        if(!Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod())){chain.doFilter(request,response);return;}
        try{transaction.execute(status->{barrier.requireWritable();return null;});}
        catch(ClusterFailure failure){denied(response,failure.getMessage());return;}
        chain.doFilter(request,response);
    }
    private void denied(HttpServletResponse response,String code)throws IOException {response.setStatus(423);response.setHeader("Cache-Control","no-store");response.setContentType("application/json");response.getWriter().write("{\"code\":\""+code+"\"}");}
    private void unavailable(HttpServletResponse response,String code)throws IOException {response.setStatus(503);response.setHeader("Cache-Control","no-store");response.setContentType("application/json");response.getWriter().write("{\"code\":\""+code+"\"}");}
}
