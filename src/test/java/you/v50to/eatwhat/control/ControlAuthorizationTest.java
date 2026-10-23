package you.v50to.eatwhat.control;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import you.v50to.eatwhat.account.AccountAuthService;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.exception.BizException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlAuthorizationTest {
    @Test void externalAdminCannotBypassWithdrawnConsentBecauseControlUsesSeparatePackage(){
        var account=mock(AccountAuthService.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));when(account.external()).thenReturn(true);doThrow(new BizException(BizCode.ACCOUNT_AUTHORIZATION_EXPIRED)).when(account).assertExternalSession(1L);
        ObjectProvider<AccountAuthService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(account);
        try(var login=mockStatic(StpUtil.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE))) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            var controller=new ControlController(null,null,provider);
            assertEquals(403,assertThrows(ControlFailure.class,controller::sites).status);
            doThrow(new BizException(BizCode.AUTH_UNAVAILABLE)).when(account).assertExternalSession(1L);
            assertEquals(503,assertThrows(ControlFailure.class,controller::sites).status);
            verify(account,times(2)).assertExternalSession(1L);
        }
    }
}
