package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.ClusterProperties;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine Spring HTTP gate and local member protocol; not a simulated ACTIVE database row. */
@TestPropertySource(properties="app.control.enrollment-required=true")
class ManagedEnrollmentGateIT extends SingleAuthorityEnrollmentIT {
    @Test void businessLoginStaysClosedUntilActualLocalJoinIsApplied()throws Exception{
        var http=HttpClient.newHttpClient();String base="http://127.0.0.1:"+port;var json=JsonMapper.builder().build();
        var login=HttpRequest.newBuilder(URI.create(base+"/auth/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"fixture-admin\",\"password\":\"synthetic-password\",\"device\":\"WEB\"}")).build();
        var pending=http.send(login,HttpResponse.BodyHandlers.ofString());assertEquals(503,pending.statusCode());assertEquals("SITE_ENROLLMENT_REQUIRED",json.readTree(pending.body()).path("code").asText());assertTrue(pending.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var privateNode=HttpRequest.newBuilder(URI.create(base+"/internal/cluster/v1/probe")).GET();assertEquals(401,http.send(privateNode.build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        var profile=new ClusterProperties.Profile();profile.setSiteId("installed-campus");profile.setInstanceId(INSTANCE);profile.setMode("CAMPUS");profile.setAllocationSlot(0);profile.setOrigin(base);profile.setTokenFile(tokenFile);settings.setProfiles(Map.of("authority",profile));profiles.reload(List.of());assertTrue(lease.tick());
        coordinator.register(Long.toString(user),UUID.randomUUID(),0,"authority");UUID join=UUID.randomUUID();coordinator.begin(Long.toString(user),join,0,"JOIN","installed-campus");for(int i=0;i<3&&!"APPLIED".equals(membership.change(join).get("state"));i++)coordinator.reconcile(Long.toString(user),join);assertEquals("APPLIED",membership.change(join).get("state"));
        var admitted=http.send(login,HttpResponse.BodyHandlers.ofString());assertEquals(200,admitted.statusCode());assertEquals(0,json.readTree(admitted.body()).path("code").asInt(-1));assertEquals(originalNode,db.admin().queryForObject("SELECT row_to_json(n)::text FROM eatwhat_identity.node_state n",String.class));assertEquals(user,db.admin().queryForObject("SELECT id FROM users WHERE nick_name='fixture-admin'",Long.class));
    }
}
