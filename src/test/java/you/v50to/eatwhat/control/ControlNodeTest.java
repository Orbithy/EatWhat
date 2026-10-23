package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.*;
import you.v50to.eatwhat.config.SiteProperties;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ControlNodeTest {
    @TempDir Path root;
    ControlProperties p;JdbcTemplate jdbc;SiteProperties site;ControlAudit audit;
    @BeforeEach void setup()throws Exception {
        var token=root.resolve("token");Files.writeString(token,"t".repeat(50));Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));
        p=new ControlProperties();p.setNodeTokenFile(token);jdbc=mock(JdbcTemplate.class);site=new SiteProperties();site.setId("campus");site.setMode(SiteProperties.Mode.CAMPUS);site.setInstanceId(UUID.randomUUID());audit=new ControlAudit(root.resolve("state"));
    }
    @Test void failedQueryNeverReportsZeroQueuesOrObserved(){try(var node=new ControlNode(p,jdbc,site,audit)){when(jdbc.execute(any(ConnectionCallback.class))).thenThrow(new RuntimeException("private details"));var s=node.snapshot();assertEquals("UNKNOWN",s.get("observationStatus"));assertFalse(s.containsKey("database"));assertFalse(s.toString().contains("private details"));assertThrows(ControlFailure.class,()->node.authenticate("wrong"));node.authenticate("t".repeat(50));}}
    @Test void identityMismatchCannotClaimObserved(){when(jdbc.execute(any(ConnectionCallback.class))).thenReturn("{\"siteId\":\"other\"}");try(var node=new ControlNode(p,jdbc,site,audit)){assertEquals("UNKNOWN",node.snapshot().get("observationStatus"));}}
    @Test void restoreStillWorksWhenDatabaseUnavailableAndIsAudited()throws Exception {
        int port;try(var s=new ServerSocket(0)){port=s.getLocalPort();}var channel=new ControlProperties.Proxy();channel.setListenPort(port);channel.setTargetHost("127.0.0.1");channel.setTargetPort(1);p.getProxies().put("events",channel);
        when(jdbc.execute(any(ConnectionCallback.class))).thenThrow(new RuntimeException("db down"));
        try(var node=new ControlNode(p,jdbc,site,audit)) {
            assertEquals("NODE_IDENTITY_NOT_VERIFIED",assertThrows(ControlFailure.class,()->node.fault("events","BLOCK",1,UUID.randomUUID())).code);
            assertEquals("FORWARDING",node.fault("events","RESTORE",1,UUID.randomUUID()).get("state"));
            assertTrue(Files.readString(audit.directory().resolve("audit.jsonl")).contains("RESTORE"));
            assertThrows(ControlFailure.class,()->node.fault("events","DELETE",1,UUID.randomUUID()));
        }
    }
}
