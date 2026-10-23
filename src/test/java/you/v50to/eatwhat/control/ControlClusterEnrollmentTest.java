package you.v50to.eatwhat.control;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ControlClusterEnrollmentTest {
 @TempDir Path temp;ControlServerStore store;ControlClusterEnrollment enrollment;ControlSsh ssh;JdbcTemplate jdbc;ControlProperties settings;UUID server=UUID.randomUUID();ControlServers servers;DeploymentExecutor deployment;ControlAudit audit;ClusterStore membership;SiteProperties site;
 @BeforeEach void setup()throws Exception {
  Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwx------"));audit=new ControlAudit(temp);store=new ControlServerStore(audit);settings=new ControlProperties();settings.getServers().setClusterEnrollmentEnabled(true);deployment=new DeploymentExecutor(settings,audit);ssh=mock(ControlSsh.class);jdbc=mock(JdbcTemplate.class);servers=mock(ControlServers.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));membership=mock(ClusterStore.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId("old-campus");site.setInstanceId(UUID.randomUUID());site.setClusterId(UUID.randomUUID());
  when(membership.status()).thenReturn(Map.of("epoch",0L,"members",List.of()));when(jdbc.queryForList(anyString(),eq(123L))).thenReturn(List.of(Map.of("id",123L,"nick_name","fixture-admin","password_hash","private-hash")));
  when(ssh.call(anyMap(),anyString(),eq(List.of("inspect")),any(),anyInt())).thenReturn(Map.of("protocol","eatwhat-authority-v1","ready",true,"preflightHash","a".repeat(64),"administratorUsername","fixture-admin"));
  when(ssh.call(anyMap(),anyString(),eq(List.of("status")),any(),anyInt())).thenReturn(Map.of("state","NOT_STARTED"));
  store.mutate(d->{((Map<String,Object>)d.get("servers")).put(server.toString(),new LinkedHashMap<>(Map.of("id",server.toString(),"revision",2,"location","CONTROL_HOST","username","root","credential",store.encrypt("private-credential"),"installation",Map.of("state","SUCCEEDED","siteId","business-campus","instanceId",UUID.randomUUID().toString(),"httpPort",18090))));return null;});
  var beans=new StaticListableBeanFactory();beans.addBean("membership",membership);var c=new ClusterProperties();c.setEnabled(true);c.setCoordinator(true);beans.addBean("cluster",c);
  enrollment=new ControlClusterEnrollment(store,ssh,servers,deployment,settings,audit,site,jdbc,beans.getBeanProvider(ClusterStore.class),beans.getBeanProvider(ClusterMembershipCoordinator.class),beans.getBeanProvider(ClusterProperties.class));enrollment.start();
 }
 @AfterEach void close(){enrollment.stop();}
 UUID plan(){return UUID.fromString(enrollment.create("123",server,2).get("id").toString());}
 @Test void planHasNoSecretsAndPreservesIdentity(){var p=enrollment.create("123",server,2);assertEquals("READY",p.get("state"));assertEquals("business-campus",p.get("siteId"));assertEquals(true,p.get("singleSiteOnly"));assertFalse(p.toString().contains("private"));assertFalse(p.keySet().stream().anyMatch(k->k.startsWith("_")));}
 @Test void disabledAndRemoteAndInitializedPlansAreBlocked() {
  settings.getServers().setClusterEnrollmentEnabled(false);assertTrue(((List<?>)enrollment.create("123",server,2).get("blockers")).contains("CLUSTER_ENROLLMENT_DISABLED"));settings.getServers().setClusterEnrollmentEnabled(true);
  store.mutate(d->{((Map<String,Object>)((Map<String,Object>)d.get("servers")).get(server.toString())).put("location","REMOTE");return null;});assertTrue(((List<?>)enrollment.create("123",server,2).get("blockers")).contains("INITIAL_AUTHORITY_CONTROL_HOST_REQUIRED"));
  when(membership.status()).thenReturn(Map.of("epoch",1L,"members",List.of(Map.of("state","ACTIVE"))));assertTrue(((List<?>)enrollment.create("123",server,2).get("blockers")).contains("CLUSTER_ALREADY_INITIALIZED"));verifyNoInteractions(ssh);
 }
 @Test void revisionAndExpiredPlanFailBeforeEffects()throws Exception{assertEquals("SERVER_REVISION_CHANGED",assertThrows(ControlFailure.class,()->enrollment.create("123",server,1)).code);UUID id=plan();var p=store.readJson(store.directory().resolve("server-cluster-plan-"+id+".json"));p.put("expiresAt","2020-01-01T00:00:00Z");store.saveJson(store.directory().resolve("server-cluster-plan-"+id+".json"),p);assertEquals("AUTHORITY_PLAN_EXPIRED",assertThrows(ControlFailure.class,()->enrollment.execute("123",id)).code);verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());}
 @Test void changedAdministratorInvalidatesApproval(){UUID id=plan();when(jdbc.queryForList(anyString(),eq(123L))).thenReturn(List.of(Map.of("id",123L,"nick_name","fixture-admin","password_hash","changed-private")));assertEquals("AUTHORITY_PLAN_CHANGED",assertThrows(ControlFailure.class,()->enrollment.execute("123",id)).code);}
 @Test void acceptedExecutionIsIdempotentAndUnknownIsNeverRedispatched()throws Exception {
  UUID id=plan();when(ssh.call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt())).thenThrow(new java.io.IOException("secret remote text"));enrollment.execute("123",id);
  for(int i=0;i<100&&!"OUTCOME_UNKNOWN".equals(enrollment.job(id).get("state"));i++)Thread.sleep(10);
  assertEquals("OUTCOME_UNKNOWN",enrollment.job(id).get("state"));enrollment.execute("123",id);enrollment.reconcile("123",id);
  verify(ssh,times(1)).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());assertFalse(enrollment.job(id).toString().contains("secret"));assertEquals("DEPLOYMENT_RECONCILIATION_REQUIRED",deployment.whenIdle(()->fail("must block concurrent writes")));
 }
 @Test void failedInspectionCannotBeExecuted()throws Exception {when(ssh.call(anyMap(),anyString(),eq(List.of("inspect")),any(),anyInt())).thenThrow(new java.io.IOException("private stdout"));UUID id=plan();assertEquals("BLOCKED",enrollment.plan(id).get("state"));assertEquals("AUTHORITY_PLAN_BLOCKED",assertThrows(ControlFailure.class,()->enrollment.execute("123",id)).code);}
 @Test void lookupIsReadOnlyAndMissingJobIsExplicit()throws Exception{UUID id=plan();enrollment.plan(id);assertEquals("AUTHORITY_JOB_NOT_FOUND",assertThrows(ControlFailure.class,()->enrollment.job(id)).code);verifyNoInteractionsExceptInspection();}
 void verifyNoInteractionsExceptInspection()throws Exception{verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("dispatch")),any(),anyInt());}
}
