package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual registration SQL/standby scoping is covered by the integration test. */
class ControlBootstrapMembershipNodeControllerTest {
    ControlNode node=mock(ControlNode.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    ControlBootstrapMembership membership=mock(ControlBootstrapMembership.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    ControlBootstrapMembershipNodeController controller=new ControlBootstrapMembershipNodeController(node,membership);
    @Test void anonymousOrCallerSelectedMembershipInputsCannotReachTheDriver(){
        doThrow(new ControlFailure(401,"NODE_AUTHENTICATION_REQUIRED")).when(node).authenticate(null);
        assertEquals(401,assertThrows(ControlFailure.class,()->controller.register(null,Map.of())).status);
        var body=new HashMap<String,Object>(Map.of("id",UUID.randomUUID().toString(),"role","SOURCE","binding","a".repeat(43)+"="));
        for(String key:List.of("siteId","instanceId","epoch","profileId","actor","expectedMember","url")){
            body.put(key,"caller-selected");assertEquals("BOOTSTRAP_PROFILE_REQUEST_INVALID",assertThrows(ControlFailure.class,()->controller.register("token",body)).code);body.remove(key);
        }verifyNoInteractions(membership);
    }
    @Test void statusOnlyPassesReadonlyObservationForTheOriginalFrame(){
        UUID id=UUID.randomUUID();String binding="a".repeat(43)+"=";when(membership.localRegistration(id,ControlBootstrapProfileState.Role.SOURCE,binding,false)).thenReturn(Map.of("state","OUTCOME_UNKNOWN"));
        assertEquals(Map.of("state","OUTCOME_UNKNOWN"),controller.status("token",Map.of("id",id.toString(),"role","SOURCE","binding",binding)));
        verify(node).authenticate("token");verify(membership).localRegistration(id,ControlBootstrapProfileState.Role.SOURCE,binding,false);verifyNoMoreInteractions(membership);
    }
    @Test void actualLeaseRefusalKeepsItsStableControlCode(){
        UUID id=UUID.randomUUID();String binding="a".repeat(43)+"=";when(membership.localRegistration(id,ControlBootstrapProfileState.Role.SOURCE,binding,true)).thenThrow(new you.v50to.eatwhat.cluster.ClusterFailure(409,"CONTROL_LEASE_NOT_HELD"));
        assertEquals("CONTROL_LEASE_NOT_HELD",assertThrows(ControlFailure.class,()->controller.register("token",Map.of("id",id.toString(),"role","SOURCE","binding",binding))).code);
    }
    @Test void joinActionsUseOnlyTheClosedOriginalFrameAndSeparateBeginStatusAndReconcile(){
        UUID id=UUID.randomUUID();String binding="a".repeat(43)+"=";var frame=Map.<String,Object>of("id",id.toString(),"role","SOURCE","binding",binding);
        controller.join("token",frame);controller.joinStatus("token",frame);controller.reconcile("token",frame);
        verify(membership).localJoin(id,ControlBootstrapProfileState.Role.SOURCE,binding,ControlBootstrapMembership.JoinAction.BEGIN);
        verify(membership).localJoin(id,ControlBootstrapProfileState.Role.SOURCE,binding,ControlBootstrapMembership.JoinAction.STATUS);
        verify(membership).localJoin(id,ControlBootstrapProfileState.Role.SOURCE,binding,ControlBootstrapMembership.JoinAction.RECONCILE);
        var changed=new HashMap<>(frame);changed.put("epoch",2);assertEquals("BOOTSTRAP_PROFILE_REQUEST_INVALID",assertThrows(ControlFailure.class,()->controller.join("token",changed)).code);verifyNoMoreInteractions(membership);
        doThrow(new ControlFailure(401,"NODE_AUTHENTICATION_REQUIRED")).when(node).authenticate(null);assertEquals(401,assertThrows(ControlFailure.class,()->controller.reconcile(null,frame)).status);
    }
}
