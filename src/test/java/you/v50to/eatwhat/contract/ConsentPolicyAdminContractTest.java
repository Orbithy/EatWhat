package you.v50to.eatwhat.contract;

import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import you.v50to.eatwhat.account.ConsentPolicyService;
import you.v50to.eatwhat.controller.ConsentPolicyAdminController;
import you.v50to.eatwhat.data.dto.SaveConsentPolicyDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.ConsentPolicySettingsVO;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConsentPolicyAdminContractTest extends MvcContractSupport {
    private static final String PATH="/admin/consent/policy";
    private ConsentPolicyService policies;
    private MockMvc http;
    @BeforeEach void controller(){policies=mock(ConsentPolicyService.class);http=mvc(new ConsentPolicyAdminController(policies));}
    @Test void anonymousAndOrdinaryAccountsCannotReadOrEditPolicy()throws Exception{
        http.perform(get(PATH)).andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
        String user=loginAs(99L);
        http.perform(get(PATH).header(TOKEN_HEADER,"Bearer "+user)).andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()));
        http.perform(put(PATH).header(TOKEN_HEADER,"Bearer "+user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"v4\",\"url\":\"/policy.html\",\"expectedRevision\":0}"))
                .andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()));
        verifyNoInteractions(policies);
    }
    @Test void administratorReadsRevisionAndSavesWithSessionIdentity()throws Exception{
        String admin=loginAs(99L,"admin");
        when(policies.settings()).thenReturn(new ConsentPolicySettingsVO("v3","/old.html",4));
        http.perform(get(PATH).header(TOKEN_HEADER,"Bearer "+admin)).andExpect(jsonPath("$.data.revision").value(4))
                .andExpect(header().string("Cache-Control","no-store"));
        var edit=new SaveConsentPolicyDTO("v4","https://example.com/policy-v4.html",4L);
        when(policies.save(edit,99L)).thenReturn(new ConsentPolicySettingsVO(edit.version(),edit.url(),5));
        http.perform(put(PATH).header(TOKEN_HEADER,"Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"v4\",\"url\":\"https://example.com/policy-v4.html\",\"expectedRevision\":4}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.version").value("v4"))
                .andExpect(jsonPath("$.data.revision").value(5)).andExpect(header().string("Cache-Control","no-store"));
        verify(policies).save(edit,99L);
    }
    @Test void malformedVersionMissingRevisionAndEmptyUrlNeverReachTheStore()throws Exception{
        String admin=loginAs(99L,"admin");
        for(String body:List.of("{}","{\"version\":\"v4\",\"url\":\"/policy.html\"}",
                "{\"version\":\"bad version\",\"url\":\"/policy.html\",\"expectedRevision\":0}",
                "{\"version\":\"v4\",\"url\":\"\",\"expectedRevision\":0}",
                "{\"version\":\"v4\",\"url\":\"/policy.html\",\"expectedRevision\":-1}"))
            http.perform(put(PATH).header(TOKEN_HEADER,"Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        verifyNoInteractions(policies);
    }
}
