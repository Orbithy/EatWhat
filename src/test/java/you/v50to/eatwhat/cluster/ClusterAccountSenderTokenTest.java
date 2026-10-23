package you.v50to.eatwhat.cluster;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.AccountNodeDispatcher;
import you.v50to.eatwhat.account.transport.AccountProtocol;
import you.v50to.eatwhat.config.SiteProperties;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the actual receiver authentication, not a guessed mock-server token rule. */
class ClusterAccountSenderTokenTest {
 @TempDir Path root;
 @Test void authorityAcceptsActiveSenderTokenAndRejectsItsOwnReceiverToken()throws Exception{
  root=root.toRealPath();var senderToken=root.resolve("sender.token");var receiverToken=root.resolve("receiver.token");
  Files.writeString(senderToken,"s".repeat(64));Files.writeString(receiverToken,"r".repeat(64));
  for(var p:List.of(senderToken,receiverToken))Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));
  var sender=new ClusterMember("external",UUID.randomUUID(),"EXTERNAL",1);var receiver=new ClusterMember("campus",UUID.randomUUID(),"CAMPUS",0);
  var settings=new ClusterProperties();settings.setCoordinator(true);
  var profile=new ClusterProperties.Profile();profile.setSiteId(sender.siteId());profile.setInstanceId(sender.instanceId());profile.setMode(sender.mode());profile.setAllocationSlot(sender.allocationSlot());profile.setOrigin("https://external.example.invalid");profile.setTokenFile(senderToken);settings.setProfiles(Map.of("external",profile));
  var profiles=new ClusterProfiles(settings);
  @SuppressWarnings("unchecked") ObjectProvider<ClusterProfiles> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(profiles);
  var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Boolean.class))).thenReturn(true);
  var json=JsonMapper.builder().build();when(jdbc.queryForList("SELECT members::text AS members FROM eatwhat_cluster.local_state WHERE singleton")).thenReturn(List.of(Map.of("members",json.writeValueAsString(List.of(receiver,sender)))));
  var site=new SiteProperties();site.setId(receiver.siteId());site.setInstanceId(receiver.instanceId());site.setMode(SiteProperties.Mode.CAMPUS);
  var controller=new ClusterAccountController(settings,provider,jdbc,null,new AccountNodeDispatcher(site,null,null),site);
  // CAMPUS deliberately rejects project after authenticating; no account store is touched.
  var protocol=new AccountProtocol();var frame=new AccountProtocol.Frame(1,UUID.randomUUID(),sender.identity(),receiver.identity(),Instant.now(),"project",json.createObjectNode());
  var request=new MockHttpServletRequest();request.setContent(protocol.encode(frame));
  assertThrows(IllegalArgumentException.class,()->controller.call(Files.readString(senderToken),request));
  var wrong=new MockHttpServletRequest();wrong.setContent(protocol.encode(new AccountProtocol.Frame(1,UUID.randomUUID(),sender.identity(),receiver.identity(),Instant.now(),"project",json.createObjectNode())));
  var failure=assertThrows(ClusterFailure.class,()->controller.call(Files.readString(receiverToken),wrong));assertEquals(401,failure.status());assertEquals("NODE_AUTH_REQUIRED",failure.getMessage());
 }
}
