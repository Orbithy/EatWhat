package you.v50to.eatwhat.cluster;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import you.v50to.eatwhat.control.ControlNode;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClusterNodeEnrollmentProofTest {
    @Test void independentAuthUsesDatabaseBoundNodeProofWithoutContactingAuthority() {
        var auth=mock(ControlNode.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));var agent=mock(ClusterNodeAgent.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));var sessions=mock(ClusterSessions.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        ObjectProvider<ClusterAuthStore> store=mock(ObjectProvider.class);
        var controller=new ClusterNodeController(auth,agent,mock(ClusterGcParticipant.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)));
        ReflectionTestUtils.setField(controller,"sessions",sessions);ReflectionTestUtils.setField(controller,"authStore",store);
        when(sessions.independentAuthentication()).thenReturn(true);when(sessions.localAuthenticationHealthy()).thenReturn(true);
        when(agent.probe()).thenReturn(Map.of("databaseFingerprint","a".repeat(32),"membership",Map.of("state","UNJOINED")));
        Map<?,?> proof=(Map<?,?>)controller.enrollmentProof("synthetic-node-token");
        assertEquals("LOCAL_MULTIWRITER",proof.get("authenticationMode"));
        assertEquals("NOT_REQUIRED",proof.get("authStoreBinding"));
        assertEquals("a".repeat(32),proof.get("databaseFingerprint"));
        verify(auth).authenticate("synthetic-node-token");verify(agent).probe();verifyNoInteractions(store);
        when(sessions.localAuthenticationHealthy()).thenReturn(false);
        assertThrows(ClusterFailure.class,()->controller.enrollmentProof("synthetic-node-token"));
    }
    @Test void legacyEnrollmentStillRequiresVerifiedAuthorityBinding() {
        var auth=mock(ControlNode.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));var agent=mock(ClusterNodeAgent.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));var sessions=mock(ClusterSessions.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        ObjectProvider<ClusterAuthStore> stores=mock(ObjectProvider.class);var store=mock(ClusterAuthStore.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(stores.getIfAvailable()).thenReturn(store);when(agent.probe()).thenReturn(Map.of());
        var controller=new ClusterNodeController(auth,agent,mock(ClusterGcParticipant.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE)));
        ReflectionTestUtils.setField(controller,"sessions",sessions);ReflectionTestUtils.setField(controller,"authStore",stores);
        assertEquals("VERIFIED",((Map<?,?>)controller.enrollmentProof("synthetic-node-token")).get("authStoreBinding"));
        verify(store).verify();
        when(stores.getIfAvailable()).thenReturn(null);
        assertThrows(ClusterFailure.class,()->controller.enrollmentProof("synthetic-node-token"));
    }
}
