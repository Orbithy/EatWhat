package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import you.v50to.eatwhat.cluster.ClusterControlHandoff;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlBootstrapPlanControllerTest {
    static <T>T stub(Class<T> type){return mock(type,withSettings().mockMaker(org.mockito.MockMakers.INLINE));}
    ControlController auth=stub(ControlController.class);ControlBootstrapPlans plans=stub(ControlBootstrapPlans.class);ControlBootstrapJournal journal=stub(ControlBootstrapJournal.class);
    ControlBootstrapPlanController controller=new ControlBootstrapPlanController(auth,plans,journal,new StaticListableBeanFactory().getBeanProvider(ClusterControlHandoff.class));
    @Test void everyReadAndWriteRequiresAnAdministrator(){
        when(auth.admin()).thenThrow(new ControlFailure(403,"ADMIN_REQUIRED"));UUID id=UUID.randomUUID();
        assertThrows(ControlFailure.class,()->controller.get(id));assertThrows(ControlFailure.class,()->controller.preview(null));assertThrows(ControlFailure.class,()->controller.execute(id,new ControlBootstrapPlanController.Approval("a".repeat(64),Set.of())));
        assertThrows(ControlFailure.class,()->controller.capabilities());
        verifyNoInteractions(plans,journal);
    }
    @Test void actualHttpMappingsReceiveWriteOnlyStorageAndValidateApprovalBeforeQueueing()throws Exception{
        when(auth.admin()).thenReturn("123");
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        var input=new ControlBootstrapPlans.Request(UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),1,"fixture",1,"10.253.74.1","10.253.74.3","target.example.invalid",51820,18090,"https://business.example.invalid/api",null);
        String body="{\"id\":\""+input.id()+"\",\"sourceServerId\":\""+input.sourceServerId()+"\",\"sourceRevision\":1,\"targetServerId\":\""+input.targetServerId()+"\",\"targetRevision\":1,\"releaseId\":\"fixture\",\"allocationSlot\":1,\"sourceAddress\":\"10.253.74.1\",\"targetAddress\":\"10.253.74.3\",\"targetEndpoint\":\"target.example.invalid\",\"wireguardPort\":51820,\"httpPort\":18090,\"appBaseUrl\":\"https://business.example.invalid/api\",\"storage\":{\"provider\":\"R2\",\"endpoint\":\"https://r2.example.invalid\",\"region\":\"auto\",\"bucket\":\"fixture\",\"accessKey\":\"fixture-access\",\"secretKey\":\"fixture-secret\"}}";
        when(plans.preview(eq("123"),any())).thenReturn(Map.of("state","PREVIEW_ONLY"));
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/control/v1/controller-plans").contentType("application/json").content(body)).andReturn().getResponse();
        assertEquals(201,response.getStatus(),response.getContentAsString());assertFalse(response.getContentAsString().contains("fixture-secret"));
        var captured=org.mockito.ArgumentCaptor.forClass(ControlBootstrapPlans.Request.class);verify(plans).preview(eq("123"),captured.capture());
        assertEquals("fixture-access",captured.getValue().storage().accessKey());assertEquals("fixture-secret",captured.getValue().storage().secretKey());
        assertFalse(new tools.jackson.databind.json.JsonMapper().writeValueAsString(captured.getValue()).contains("fixture-secret"));
        var invalid=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/control/v1/controller-plans/"+input.id()+"/execute").contentType("application/json").content("{\"permissions\":[]}")).andReturn().getResponse();
        assertEquals(400,invalid.getStatus());verify(plans,never()).accept(any(),any(),any());
        when(plans.capabilities()).thenReturn(Map.of("supported",true,"backgroundExecution",true));
        assertEquals(200,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/control/v1/controller-plans")).andReturn().getResponse().getStatus());
    }
    @Test void approvalMustMatchTheActualReviewedHashBeforeRemoteRechecks(){
        UUID id=UUID.randomUUID();when(auth.admin()).thenReturn("123");when(journal.plan(id)).thenReturn(Map.of("requestHash","a".repeat(64)));
        assertEquals("BOOTSTRAP_PLAN_CHANGED",assertThrows(ControlFailure.class,()->controller.execute(id,new ControlBootstrapPlanController.Approval("b".repeat(64),ControlBootstrapJournal.PERMISSIONS))).code);verifyNoInteractions(plans);
        var queued=Map.<String,Object>of("state","QUEUED");when(plans.accept("123",id,ControlBootstrapJournal.PERMISSIONS)).thenReturn(queued);
        assertSame(queued,controller.execute(id,new ControlBootstrapPlanController.Approval("a".repeat(64),ControlBootstrapJournal.PERMISSIONS)));
    }
    @Test void handoffOperationFenceSurroundsPlanCreationAndAcceptance(){
        var transfer=stub(ClusterControlHandoff.class);var factory=new StaticListableBeanFactory(Map.of("handoff",transfer));
        var fenced=new ControlBootstrapPlanController(auth,plans,journal,factory.getBeanProvider(ClusterControlHandoff.class));when(auth.admin()).thenReturn("123");
        when(transfer.operation(any())).thenThrow(new you.v50to.eatwhat.cluster.ClusterFailure(409,"CONTROL_HANDOFF_PENDING"));
        assertThrows(you.v50to.eatwhat.cluster.ClusterFailure.class,()->fenced.preview(null));assertThrows(you.v50to.eatwhat.cluster.ClusterFailure.class,()->fenced.execute(UUID.randomUUID(),new ControlBootstrapPlanController.Approval("a".repeat(64),Set.of())));
        verifyNoInteractions(plans,journal);
    }
}
