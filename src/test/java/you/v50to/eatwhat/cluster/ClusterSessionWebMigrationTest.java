package you.v50to.eatwhat.cluster;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClusterSessionWebMigrationTest {
    @Test void independentModeNeverAdoptsRedisOnlyToken()throws Exception{run(true);}
    @Test void legacyModeKeepsItsExistingRegistrationPath()throws Exception{run(false);}
    private void run(boolean independent)throws Exception{
        var sessions=mock(ClusterSessions.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));when(sessions.active()).thenReturn(true);when(sessions.independentAuthentication()).thenReturn(independent);
        var token=new ClusterSessions.Token("synthetic_legacy_token_012345678901234567890");when(sessions.authorize(token)).thenThrow(new ClusterFailure(401,"CLUSTER_SESSION_INVALID"));
        var authorization=new ClusterSessions.Authorization(7,UUID.randomUUID(),0,0,100);when(sessions.register(token)).thenReturn(authorization);
        var request=new MockHttpServletRequest();request.setServletPath("/user/info");var response=new MockHttpServletResponse();
        try(var auth=mockStatic(StpUtil.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE))){auth.when(StpUtil::getTokenValue).thenReturn(token.token());auth.when(StpUtil::isLogin).thenReturn(true);assertEquals(!independent,new ClusterSessionWeb(sessions).preHandle(request,response,new Object()));}
        if(independent){assertEquals(401,response.getStatus());verify(sessions,never()).register(any());verify(sessions,never()).attach(any(),any());}
        else{verify(sessions).register(token);verify(sessions).attach(token,authorization);}
    }
}
