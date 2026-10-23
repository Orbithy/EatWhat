package you.v50to.eatwhat.control.independent;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import you.v50to.eatwhat.control.ControlFailure;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IndependentControlAccountsTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    IndependentControlAccounts accounts=new IndependentControlAccounts(jdbc,mock(PasswordEncoder.class),mock(StringRedisTemplate.class),mock(PlatformTransactionManager.class),mock(ObjectProvider.class));
    Map<String,Object> row(){return Map.of("id",17L,"username","operator","password_hash","private-synthetic-hash","credential_version",2L,"disabled",false);}
    @Test void businessSessionWithSameNumericIdCannotBecomeAManagementSession(){
        when(jdbc.queryForList(anyString(),eq(17L))).thenReturn(List.of(row()));
        var session=mock(SaSession.class);when(session.get("control:independentVersion")).thenReturn(2L);
        try(var auth=mockStatic(StpUtil.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE))){
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(17L);auth.when(StpUtil::getTokenSession).thenReturn(session);
            assertEquals("ACCOUNT_CREDENTIALS_CHANGED",assertThrows(ControlFailure.class,()->accounts.requireSession(17)).code);
        }
        verify(jdbc).queryForList(startsWith("SELECT id,username,password_hash,credential_version,disabled FROM eatwhat_controller.account"),eq(17L));
    }
    @Test void aCommittedVersionChangeDeniesAStillPresentRedisSession(){
        when(jdbc.queryForList(anyString(),eq(17L))).thenReturn(List.of(row()));
        var session=mock(SaSession.class);when(session.get("control:realm")).thenReturn("eatwhat:independent-control:v1");when(session.get("control:independentVersion")).thenReturn(1L,2L);
        try(var auth=mockStatic(StpUtil.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE))){
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(17L);auth.when(StpUtil::getTokenSession).thenReturn(session);
            assertEquals(401,assertThrows(ControlFailure.class,()->accounts.requireSession(17)).status);
            assertDoesNotThrow(()->accounts.requireSession(17));
        }
    }
    @Test void missingOrDisabledManagementRecordDoesNotFallBackToBusinessAdmin(){
        when(jdbc.queryForList(anyString(),eq(17L))).thenReturn(List.of());
        try(var auth=mockStatic(StpUtil.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE))){
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(17L);
            assertEquals(401,assertThrows(ControlFailure.class,()->accounts.requireSession(17)).status);
        }
        when(jdbc.queryForList(anyString(),eq(17L))).thenReturn(List.of(Map.of("disabled",true)));
        assertEquals(401,assertThrows(ControlFailure.class,()->accounts.admissionBinding(17)).status);
    }
}
