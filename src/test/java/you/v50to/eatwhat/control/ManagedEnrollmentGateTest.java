package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import you.v50to.eatwhat.config.SiteProperties;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ManagedEnrollmentGateTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final SiteProperties site=new SiteProperties();
    final MockEnvironment environment=new MockEnvironment();
    ManagedEnrollmentGate gate(){site.setId("external");site.setInstanceId(UUID.randomUUID());site.setClusterId(UUID.randomUUID());return new ManagedEnrollmentGate(jdbc,site,environment);}
    @Test void unjoinedSiteCannotRegisterLoginOrReadBusinessRoutes()throws Exception{
        var gate=gate();for(String path:new String[]{"/auth/register","/auth/login","/user/info","/upload/presign","/admin/control/v1/tasks"}){var request=new MockHttpServletRequest("POST",path);request.setServletPath(path);var response=new MockHttpServletResponse();gate.doFilter(request,response,(a,b)->fail("Business handler must remain closed"));assertEquals(503,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertTrue(response.getContentAsString().contains("SITE_ENROLLMENT_REQUIRED"));}verifyNoInteractions(jdbc);
    }
    @Test void readinessAndPrivateNodeProtocolsKeepTheirOwnDownstreamAuthorization()throws Exception{
        var gate=gate();for(String path:new String[]{"/actuator/health/readiness","/internal/control/v1/snapshot","/internal/cluster/v1/stage"}){var request=new MockHttpServletRequest("GET",path);request.setServletPath(path);var response=new MockHttpServletResponse();gate.doFilter(request,response,(a,b)->((jakarta.servlet.http.HttpServletResponse)b).setStatus(401));assertEquals(401,response.getStatus());}verifyNoInteractions(jdbc);
    }
    @Test void onlyCurrentReleasedLocalMembershipOpensBusinessTraffic()throws Exception{
        environment.setProperty("app.cluster.enabled","true");var gate=gate();when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(site.getClusterId()),eq(site.getId()),eq(site.getInstanceId()))).thenReturn(false,true);
        for(int attempt=0;attempt<2;attempt++){var request=new MockHttpServletRequest("GET","/api/user/info");request.setContextPath("/api");request.setServletPath("/user/info");var response=new MockHttpServletResponse();gate.doFilter(request,response,(a,b)->((jakarta.servlet.http.HttpServletResponse)b).setStatus(204));assertEquals(attempt==0?503:204,response.getStatus());}
    }
    @Test void unavailableDatabaseDoesNotOpenBusinessTraffic()throws Exception{
        environment.setProperty("app.cluster.enabled","true");var gate=gate();when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(),any(),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private"));var request=new MockHttpServletRequest("GET","/user/info");request.setServletPath("/user/info");var response=new MockHttpServletResponse();gate.doFilter(request,response,(a,b)->fail());assertEquals(503,response.getStatus());assertFalse(response.getContentAsString().contains("private"));
    }
}
