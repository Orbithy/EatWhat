package you.v50to.eatwhat.cluster;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClusterHttpNodesTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"","/api","/backend/v1"}) void sameSiteAndInstallationUuidOnAnotherDatabaseCannotVoteForOriginalParticipant(String prefix)throws Exception{
        UUID cluster=UUID.randomUUID();var member=new ClusterMember("third",UUID.randomUUID(),"EXTERNAL",2);String expected="a".repeat(32);var actual=new AtomicReference<>("b".repeat(32));
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var source=mock(javax.sql.DataSource.class);when(jdbc.getDataSource()).thenReturn(source);when(jdbc.queryForList("SELECT * FROM eatwhat_cluster.member WHERE site_id=?","third")).thenReturn(List.of(Map.of("database_fingerprint",expected)));
        var transactions=new you.v50to.eatwhat.sync.SyncTransactions(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));var membership=new ClusterStore(jdbc,transactions,cluster,new you.v50to.eatwhat.sync.transport.NodeIdentity("campus",UUID.randomUUID()));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext(prefix+"/internal/cluster/v1/probe",exchange->{assertEquals(prefix+"/internal/cluster/v1/probe",exchange.getRequestURI().getPath());assertEquals("x".repeat(64),exchange.getRequestHeaders().getFirst("X-Control-Node-Token"));byte[] body=JsonMapper.builder().build().writeValueAsBytes(Map.of("clusterId",cluster,"siteId",member.siteId(),"instanceId",member.instanceId(),"mode",member.mode(),"allocationSlot",2,"protocolVersion",1,"dataPlaneReady",true,"databaseFingerprint",actual.get()));exchange.sendResponseHeaders(200,body.length);try(var output=exchange.getResponseBody()){output.write(body);}});server.start();
        try{var token=directory.resolve("node.token");Files.writeString(token,"x".repeat(64));Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));var profile=new ClusterProfiles.Entry("third",member,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),token,Map.of(),prefix);var nodes=new ClusterHttpNodes(cluster,(plan,peers)->{},membership);
            assertEquals("CLUSTER_NODE_DATABASE_MISMATCH",assertThrows(ClusterFailure.class,()->nodes.fingerprint(profile)).getMessage());actual.set(expected);assertEquals(expected,nodes.fingerprint(profile));
        }finally{server.stop(0);}
    }
}
