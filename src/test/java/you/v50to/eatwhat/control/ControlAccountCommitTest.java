package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ControlAccountCommitTest {
    @Test void aLostCommitAcknowledgementIsReportedAsUnknownRatherThanRejected(){
        var jdbc=mock(JdbcTemplate.class);var passwords=mock(PasswordEncoder.class);var security=mock(ControlAccountSecurity.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));var manager=mock(PlatformTransactionManager.class);var redis=mock(StringRedisTemplate.class);
        when(security.authority()).thenReturn(true);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForList(anyString(),eq(17L))).thenReturn(List.of(Map.of("nick_name","old_admin","password_hash","hash","role","admin","banned",false)));
        when(passwords.matches("current","hash")).thenReturn(true);
        doReturn(1L).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList());
        doThrow(new DataAccessResourceFailureException("synthetic lost commit acknowledgement")).when(manager).commit(any());
        var service=new ControlAccountService(jdbc,passwords,security,manager,redis,mock(ObjectProvider.class),mock(ObjectProvider.class));
        var error=assertThrows(ControlFailure.class,()->service.change(17L,"current","new_admin",null));
        assertEquals(503,error.status);assertEquals("ACCOUNT_UPDATE_OUTCOME_UNKNOWN",error.code);
        verify(jdbc).update(startsWith("UPDATE users"),eq("new_admin"),eq("hash"),eq(17L));
    }
}
