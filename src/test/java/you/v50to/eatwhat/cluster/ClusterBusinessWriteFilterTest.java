package you.v50to.eatwhat.cluster;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClusterBusinessWriteFilterTest {
    private static final List<String> SECURITY_PATHS=List.of("/auth/changePassword","/auth/password/reset","/user/ban/17","/user/unban/17");
    private record Fixture(ClusterBusinessWriteFilter filter,MultiwriterAccounts accounts,ClusterBarrier barrier) { }
    private Fixture fixture(boolean writer,boolean schemaReady,String state) {
        var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForList("SELECT state FROM eatwhat_cluster.local_state WHERE singleton")).thenReturn(List.of(Map.of("state",state)));
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var cluster=new ClusterProperties();cluster.setCoordinator(false);cluster.getAuthStore().setEnabled(true);
        var filter=new ClusterBusinessWriteFilter(jdbc,manager,cluster,new MockEnvironment());
        var accounts=mock(MultiwriterAccounts.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));when(accounts.enabled()).thenReturn(writer);when(accounts.healthy()).thenReturn(schemaReady);
        ReflectionTestUtils.setField(filter,"multiwriter",accounts);
        var barrier=mock(ClusterBarrier.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));ReflectionTestUtils.setField(filter,"barrier",barrier);
        return new Fixture(filter,accounts,barrier);
    }
    private boolean call(Fixture fixture,String path,int expected)throws Exception {
        var request=new MockHttpServletRequest("POST",path);request.setServletPath(path);var response=new MockHttpServletResponse();var called=new AtomicBoolean();
        fixture.filter.doFilter(request,response,(a,b)->called.set(true));assertEquals(expected,response.getStatus(),path+": "+response.getContentAsString());return called.get();
    }
    @Test void independentSecurityCommandsProceedToAuthenticationAndTransactionChecks()throws Exception {
        for(String path:SECURITY_PATHS){var f=fixture(true,true,"ACTIVE");assertTrue(call(f,path,200));verify(f.barrier).requireWritable();}
    }
    @Test void legacyOrUnreadyWriterCannotBypassAuthorityRestriction()throws Exception {
        for(String path:SECURITY_PATHS)for(boolean writer:List.of(false,true)){var f=fixture(writer,false,"ACTIVE");assertFalse(call(f,path,503));verify(f.barrier,never()).requireWritable();}
    }
    @Test void writerDoesNotBypassMembershipOrWriteBarrier()throws Exception {
        for(String state:List.of("RETIRED","JOINING"))assertFalse(call(fixture(true,true,state),"/user/ban/17",423));
        var f=fixture(true,true,"ACTIVE");doThrow(new ClusterFailure(423,"CLUSTER_WRITE_BARRIER")).when(f.barrier).requireWritable();assertFalse(call(f,"/user/ban/17",423));
    }
}
