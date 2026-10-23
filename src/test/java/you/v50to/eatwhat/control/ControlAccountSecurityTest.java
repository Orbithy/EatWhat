package you.v50to.eatwhat.control;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlAccountSecurityTest {
    @Test void persistentCredentialFenceBlocksOrdinaryRoutesBeforeTheirHandler()throws Exception {
        var security=mock(ControlAccountSecurity.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));when(security.authority()).thenReturn(true);doThrow(new ControlFailure(401,"ACCOUNT_CREDENTIALS_CHANGED")).when(security).requireSession(17L);
        var request=new MockHttpServletRequest();request.setServletPath("/user/info");var response=new MockHttpServletResponse();
        try(var auth=mockStatic(StpUtil.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE))){auth.when(StpUtil::getLoginIdDefaultNull).thenReturn(17L);assertFalse(new ControlAccountWeb(security).preHandle(request,response,new Object()));}
        assertEquals(401,response.getStatus());assertTrue(response.getContentAsString().contains("ACCOUNT_CREDENTIALS_CHANGED"));assertEquals("no-store",response.getHeader("Cache-Control"));
    }
    @Test void independentBusinessSessionsNeverBypassControlCredentialFence()throws Exception {
        var security=mock(ControlAccountSecurity.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));when(security.authority()).thenReturn(true);when(security.independentBusinessAuthentication()).thenReturn(true);
        doThrow(new ControlFailure(401,"ACCOUNT_CREDENTIALS_CHANGED")).when(security).requireSession(17L);
        var web=new ControlAccountWeb(security);var business=new MockHttpServletRequest();business.setServletPath("/hub/add");var control=new MockHttpServletRequest();control.setServletPath("/admin/control/account");
        try(var auth=mockStatic(StpUtil.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE))){auth.when(StpUtil::getLoginIdDefaultNull).thenReturn(17L);assertTrue(web.preHandle(business,new MockHttpServletResponse(),new Object()));var response=new MockHttpServletResponse();assertFalse(web.preHandle(control,response,new Object()));assertEquals(401,response.getStatus());}
        verify(security).requireBusinessSession(17L);verify(security).requireSession(17L);
    }
    @Test void credentialDtoNeverPrintsEitherPassword(){
        var dto=new ControlAccountController.Credentials("private-current","new_name","private-new");assertEquals("Credentials[redacted]",dto.toString());
    }
}
