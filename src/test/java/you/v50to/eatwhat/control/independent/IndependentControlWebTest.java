package you.v50to.eatwhat.control.independent;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import you.v50to.eatwhat.cluster.ClusterControlLease;
import you.v50to.eatwhat.cluster.ClusterFailure;
import you.v50to.eatwhat.control.ControlFailure;

class IndependentControlWebTest {
    private static final String RECONCILE="/admin/control/v1/deployments/77b9df38-c512-43aa-b3f6-83fbe02bac04/reconcile";
    private final IndependentControlAccounts accounts=mock(IndependentControlAccounts.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    private final ClusterControlLease lease=mock(ClusterControlLease.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    @SuppressWarnings("unchecked") private IndependentControlWeb guarded(){
        var provider=(ObjectProvider<ClusterControlLease>)mock(ObjectProvider.class);when(provider.getObject()).thenReturn(lease);
        var web=new IndependentControlWeb();ReflectionTestUtils.setField(web,"accounts",accounts);ReflectionTestUtils.setField(web,"lease",provider);return web;
    }
    private boolean post(IndependentControlWeb web,String path,String method){
        var request=new MockHttpServletRequest(method,path);request.setServletPath(path);
        return web.preHandle(request,new MockHttpServletResponse(),new Object());
    }
    @Test void heldLeaderCanReconcileAnExistingUnknownDeployment(){
        doThrow(new ClusterFailure(409,"DEPLOYMENT_RECONCILIATION_REQUIRED")).when(lease).requireAccepting();
        assertTrue(post(guarded(),RECONCILE,"POST"));verify(accounts).requireAdmin();verify(lease).requireHeld();verify(lease,never()).requireAccepting();
    }
    @Test void standbyCannotReconcile(){
        doThrow(new ClusterFailure(503,"CONTROL_LEASE_NOT_HELD")).when(lease).requireHeld();
        assertThrows(ClusterFailure.class,()->post(guarded(),RECONCILE,"POST"));verify(accounts).requireAdmin();
    }
    @Test void reconciliationStillRequiresTheIndependentAdmin(){
        doThrow(new ControlFailure(401,"AUTHENTICATION_REQUIRED")).when(accounts).requireAdmin();
        assertThrows(ControlFailure.class,()->post(guarded(),RECONCILE,"POST"));verifyNoInteractions(lease);
    }
    @Test void unknownDeploymentStillBlocksNewPlansAndExecution(){
        doThrow(new ClusterFailure(409,"DEPLOYMENT_RECONCILIATION_REQUIRED")).when(lease).requireAccepting();
        var web=guarded();
        for(String path:new String[]{"/admin/control/v1/deployment-plans",RECONCILE.replace("/reconcile","/execute"),RECONCILE+"/extra",RECONCILE.replace("77b9df38-c512-43aa-b3f6-83fbe02bac04","anything")})
            assertThrows(ClusterFailure.class,()->post(web,path,"POST"),path);
        assertThrows(ClusterFailure.class,()->post(web,RECONCILE,"PUT"));verify(lease,never()).requireHeld();
    }
    @Test void localReplicaCannotServeBusinessOrBusinessNodeProtocols(){
        for(String path:new String[]{"/user/info","/auth/register","/auth/phone/login","/internal/cluster/v1/probe","/internal/cluster/v1/replay-push","/internal/control/v1/health","/admin/control/v10/cluster"}){
            var request=new MockHttpServletRequest();request.setServletPath(path);var response=new MockHttpServletResponse();
            assertFalse(new IndependentControlWeb().preHandle(request,response,new Object()),path);assertEquals(404,response.getStatus());
        }
    }
    @Test void onlyManagementLoginQueriesAndScopedDeploymentCallbackAreAdmitted(){
        for(String path:new String[]{"/auth/login","/auth/logout","/admin/control/v1/cluster/capabilities","/admin/control/v1/account","/internal/deployment/v1/fences/test","/actuator/health/readiness"}){
            var request=new MockHttpServletRequest();request.setServletPath(path);
            assertTrue(new IndependentControlWeb().preHandle(request,new MockHttpServletResponse(),new Object()),path);
        }
    }
}
