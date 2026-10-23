package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Closed frame and authentication order. The receiver is explicitly mocked. */
class ControlBootstrapProfileNodeControllerTest {
    ControlNode node=mock(ControlNode.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    ControlBootstrapProfileReceiver receiver=mock(ControlBootstrapProfileReceiver.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    ControlBootstrapProfileNodeController controller=new ControlBootstrapProfileNodeController(node,receiver);
    @Test void anonymousRequestCannotReachEvenAnExistingClaim(){
        doThrow(new ControlFailure(401,"NODE_AUTHENTICATION_REQUIRED")).when(node).authenticate(null);
        assertEquals(401,assertThrows(ControlFailure.class,()->controller.apply(null,Map.of())).status);verifyNoInteractions(receiver);
    }
    @Test void frameRejectsUrlsPathsPermissionsAndCallerSuppliedConfiguration(){
        var body=new HashMap<String,Object>(Map.of("id",UUID.randomUUID().toString(),"role","SOURCE","binding","a".repeat(43)+"="));
        for(String field:List.of("url","path","permission","properties","approval")){body.put(field,"unaccepted");assertEquals("BOOTSTRAP_PROFILE_REQUEST_INVALID",assertThrows(ControlFailure.class,()->controller.apply("node-token",body)).code);body.remove(field);}
        body.put("role","SOURCE;run");assertThrows(ControlFailure.class,()->controller.status("node-token",body));verifyNoInteractions(receiver);
    }
    @Test void statusCanOnlyObserveTheReferencedOriginal(){
        UUID id=UUID.randomUUID();String binding="a".repeat(43)+"=";when(receiver.observe(id,ControlBootstrapProfileState.Role.TARGET,binding)).thenReturn(Map.of("state","OUTCOME_UNKNOWN"));
        assertEquals(Map.of("state","OUTCOME_UNKNOWN"),controller.status("node-token",Map.of("id",id.toString(),"role","TARGET","binding",binding)));
        verify(node).authenticate("node-token");verify(receiver).observe(id,ControlBootstrapProfileState.Role.TARGET,binding);verifyNoMoreInteractions(receiver);
    }
    @Test void nativeServicesStatusIsAuthenticatedAndCannotSelectOtherInputs(){
        UUID id=UUID.randomUUID();String binding="a".repeat(43)+"=";var frame=Map.<String,Object>of("id",id.toString(),"role","TARGET","binding",binding);
        when(receiver.servicesStatus(id,ControlBootstrapProfileState.Role.TARGET,binding)).thenReturn(Map.of("state","RUNTIME_RELOADED"));
        assertEquals(Map.of("state","RUNTIME_RELOADED"),controller.servicesStatus("node-token",frame));verify(node).authenticate("node-token");verify(receiver).servicesStatus(id,ControlBootstrapProfileState.Role.TARGET,binding);
        var invalid=new HashMap<>(frame);invalid.put("path","/unapproved");assertThrows(ControlFailure.class,()->controller.servicesStatus("node-token",invalid));
        doThrow(new ControlFailure(401,"NODE_AUTHENTICATION_REQUIRED")).when(node).authenticate(null);assertThrows(ControlFailure.class,()->controller.servicesStatus(null,Map.of()));verifyNoMoreInteractions(receiver);
    }
    @Test void clusterFenceRefusalUsesTheStableControlErrorContract(){
        UUID id=UUID.randomUUID();String binding="a".repeat(43)+"=";when(receiver.apply(id,ControlBootstrapProfileState.Role.SOURCE,binding)).thenThrow(new you.v50to.eatwhat.cluster.ClusterFailure(409,"CONTROL_LEASE_NOT_HELD"));
        assertEquals("CONTROL_LEASE_NOT_HELD",assertThrows(ControlFailure.class,()->controller.apply("node-token",Map.of("id",id.toString(),"role","SOURCE","binding",binding))).code);
    }
}
