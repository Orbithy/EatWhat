package you.v50to.eatwhat.control;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import you.v50to.eatwhat.config.SiteProperties;
import java.io.IOException;
import java.util.*;

/** A newly allocated managed site serves business traffic only after its genuine local JOIN is released. */
@Component @Order(-2000)
@ConditionalOnProperty(name="app.control.enrollment-required",havingValue="true")
public final class ManagedEnrollmentGate extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;private final SiteProperties site;private final Environment environment;
    public ManagedEnrollmentGate(JdbcTemplate jdbc,SiteProperties site,Environment environment){this.jdbc=jdbc;this.site=site;this.environment=environment;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String path=request.getServletPath();
        if(path.equals("/actuator/health")||path.equals("/actuator/health/readiness")||path.equals("/actuator/health/liveness")||path.startsWith("/internal/control/v1/")||path.startsWith("/internal/cluster/v1/")){chain.doFilter(request,response);return;}
        boolean joined=false;
        if(environment.getProperty("app.cluster.enabled",Boolean.class,false))try{
            joined=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.local_state WHERE singleton AND cluster_id=? AND site_id=? AND instance_id=? AND state='ACTIVE' AND pending_change IS NULL AND epoch>0)",Boolean.class,site.getClusterId(),site.getId(),site.getInstanceId()));
        }catch(Exception unavailable){/* An unavailable identity proof never opens business traffic. */}
        if(joined){chain.doFilter(request,response);return;}
        response.setStatus(503);response.setHeader("Cache-Control","no-store");response.setContentType("application/json");response.getWriter().write("{\"code\":\"SITE_ENROLLMENT_REQUIRED\"}");
    }
}
