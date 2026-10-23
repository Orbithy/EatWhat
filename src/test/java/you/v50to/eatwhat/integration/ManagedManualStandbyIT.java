package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import work.foofish.smsverify.SmsTemplate;
import you.v50to.eatwhat.EatwhatApplication;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.control.DeploymentExecutor;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring binding, PostgreSQL/Redis and normal HTTP login; all data is isolated synthetic test data. */
@SpringBootTest(classes=EatwhatApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
 properties={"spring.config.location=classpath:/application-integration.properties","spring.config.additional-location=","spring.config.import="})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ManagedManualStandbyIT {
 @Value("${local.server.port}") int port;
 @Autowired ApplicationContext context;
 @MockitoBean JavaMailSender mail;
 @MockitoBean SmsTemplate sms;
 static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
 @DynamicPropertySource static void configuration(DynamicPropertyRegistry r)throws Exception{
  ManagedControlBootstrapIT.configuration(r);
  r.add("app.cluster.control-lease.automatic-takeover",()->false);
  r.add("app.cluster.control-lease.controller-name",()->"manual-http-standby");
 }
 @Test void actualConfiguredStandbyNeverClaimsButNormalAdminHttpCanRequestAndFinishHandoff()throws Exception{
  var db=ManagedControlBootstrapIT.db;var manual=context.getBean(ClusterControlLease.class);
  assertFalse(context.getBean(ClusterProperties.class).getControlLease().isAutomaticTakeover());
  assertFalse(manual.tick());assertEquals("UNCLAIMED",manual.status().get("state"));
  assertEquals(0,db.admin().queryForObject("SELECT count(*) FROM eatwhat_cluster.control_lease",Integer.class));
  UUID cluster=db.admin().queryForObject("SELECT cluster_id FROM eatwhat_cluster.state WHERE singleton",UUID.class);
  var source=new ClusterControlLease(db.runtime(),cluster,15);
  var transfer=new ClusterControlHandoff(db.runtime(),db.transactions(),source,context.getBean(DeploymentExecutor.class),"test-source");
  assertTrue(source.tick());assertFalse(manual.tick());
  db.admin().update("UPDATE eatwhat_cluster.control_lease SET expires_at=clock_timestamp()-interval '1 second'");
  assertFalse(manual.tick());assertFalse((Boolean)manual.status().get("localLeader"));assertTrue(source.tick());
  db.runtime().update("INSERT INTO users(nick_name,password_hash,role) VALUES('manualadmin',?,'admin')",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("synthetic-manual-password"));
  var login=request("POST","/auth/login",Map.of("username","manualadmin","password","synthetic-manual-password","device","WEB"),null);
  assertEquals(200,login.statusCode());String token=JSON.readTree(login.body()).path("data").path("tokenValue").asText();assertFalse(token.isBlank());
  String endpoint="/admin/control/v1/cluster/leadership";
  var before=request("GET",endpoint,null,token);assertEquals(200,before.statusCode());var view=JSON.readTree(before.body());
  assertEquals("MANUAL_HANDOFF_ONLY",view.path("takeoverMode").asText());assertFalse(view.path("localLeader").asBoolean());
  UUID id=UUID.randomUUID();var payload=Map.of("requestId",id,"expectedGeneration",view.path("generation").asLong(),"targetControllerId",view.path("localControllerId").asText());
  assertEquals(401,request("POST",endpoint+"/handoffs",payload,null).statusCode());
  var accepted=request("POST",endpoint+"/handoffs",payload,token);assertEquals(202,accepted.statusCode(),accepted.body());
  source.tick();manual.tick();assertEquals("COMPLETED",transfer.get(id).get("state"));manual.requireHeld();
  assertTrue(JSON.readTree(request("GET",endpoint,null,token).body()).path("localLeader").asBoolean());
 }
 HttpResponse<String> request(String method,String path,Object body,String token)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
  if(token!=null)b.header("eatwhat-test-token","Bearer "+token);
  return HttpClient.newHttpClient().send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
 }
 @AfterAll static void cleanup(@Autowired LettuceConnectionFactory redis,@Autowired ClusterRuntime runtime)throws Exception{
  ManagedControlBootstrapIT.cleanup(redis,runtime);
 }
}
