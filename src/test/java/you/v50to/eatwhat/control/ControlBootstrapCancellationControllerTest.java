package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlBootstrapCancellationControllerTest {
    static <T>T stub(Class<T> type){return mock(type,withSettings().mockMaker(org.mockito.MockMakers.INLINE));}
    ControlController auth=stub(ControlController.class);ControlBootstrapPlans plans=stub(ControlBootstrapPlans.class);
    ControlBootstrapCancellationController controller=new ControlBootstrapCancellationController(auth,plans);
    @Test void anonymousOrOrdinaryUsersCannotCancel(){
        when(auth.admin()).thenThrow(new ControlFailure(403,"ADMIN_REQUIRED"));assertThrows(ControlFailure.class,()->controller.cancel(UUID.randomUUID(),new ControlBootstrapCancellationController.Cancellation("a".repeat(64))));verifyNoInteractions(plans);
    }
    @Test void actualHttpRequiresTheOriginalHashAndPassesOnlyIdHashAndCurrentActor()throws Exception{
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();UUID id=UUID.randomUUID();when(auth.admin()).thenReturn("456");
        String url="/admin/control/v1/controller-jobs/"+id+"/cancel";
        assertEquals(400,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).contentType("application/json").content("{}")).andReturn().getResponse().getStatus());verifyNoInteractions(plans);
        when(plans.cancelUnstarted("456",id,"a".repeat(64))).thenReturn(Map.of("state","FAILED","cancelledBeforeDispatch",true,"effectsStarted",false));
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).contentType("application/json").content("{\"requestHash\":\""+"a".repeat(64)+"\"}")).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertTrue(response.getContentAsString().contains("cancelledBeforeDispatch"));verify(plans).cancelUnstarted("456",id,"a".repeat(64));verifyNoMoreInteractions(plans);
    }
}
