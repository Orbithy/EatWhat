package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.control.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Two actual databases and two actual state directories. An absent mirror is a
 * partition, never evidence that the peer released its authority. */
class IndependentControlHandoffIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("independent-control-handoff");
    @BeforeAll static void start(){PG.start();}
    @AfterAll static void stop(){PG.stop();}
    @TempDir Path directory;
    UUID cluster=UUID.randomUUID(),sourceId=UUID.randomUUID(),targetId=UUID.randomUUID();
    EventFoundationTestSupport.Database a,b;
    ClusterControlLease first,second;
    ClusterControlHandoff firstHandoff,secondHandoff;
    DeploymentExecutor firstExecutor,secondExecutor;
    @BeforeEach void setup()throws Exception{
        var site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId("campus");site.setInstanceId(UUID.randomUUID());site.setClusterId(cluster);site.setAllocationSlot(0);
        a=EventFoundationTestSupport.createDatabase(PG,site);
        var other=new SiteProperties();other.setMode(SiteProperties.Mode.EXTERNAL);other.setId("singapore");other.setInstanceId(UUID.randomUUID());other.setClusterId(cluster);other.setAllocationSlot(1);
        b=EventFoundationTestSupport.createDatabase(PG,other);
        for(var db:List.of(a,b)){
            org.flywaydb.core.Flyway.configure().dataSource(db.admin().getDataSource()).locations("classpath:db/control-independent").schemas("eatwhat_controller").table("independent_schema_history").load().migrate();
            db.admin().update("INSERT INTO eatwhat_controller.account(id,username,password_hash)VALUES(17,'operator','synthetic-hash')");
            String role="\""+db.runtimeUsername()+"\"";db.admin().execute("GRANT USAGE ON SCHEMA eatwhat_cluster TO "+role);
            db.admin().execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA eatwhat_cluster TO "+role);
            db.admin().execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA eatwhat_cluster TO "+role);
            db.admin().update("INSERT INTO eatwhat_cluster.state(singleton,cluster_id,coordinator_site,coordinator_instance)VALUES(true,?,'campus',?)",cluster,site.getInstanceId());
            db.admin().update("INSERT INTO eatwhat_cluster.control_lease(singleton,cluster_id,holder_id,generation,expires_at)VALUES(true,?,?,1,clock_timestamp()+interval '30 seconds')",cluster,sourceId);
        }
        first=new ClusterControlLease(a.runtime(),cluster,15,true,false,sourceId);
        second=new ClusterControlLease(b.runtime(),cluster,15,true,false,targetId);
        var settings=new ControlProperties();var audit=new ControlAudit(directory.resolve("campus"));
        firstExecutor=new DeploymentExecutor(settings,audit);firstHandoff=new ClusterControlHandoff(a.runtime(),a.transactions(),first,firstExecutor,"campus-control");
        Files.write(audit.directory().resolve("servers.key"),new byte[32]);Files.setPosixFilePermissions(audit.directory().resolve("servers.key"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var remoteAudit=new ControlAudit(directory.resolve("singapore"));
        Files.copy(audit.directory().resolve("controller-state-id"),remoteAudit.directory().resolve("controller-state-id"));
        secondExecutor=new DeploymentExecutor(settings,remoteAudit);secondHandoff=new ClusterControlHandoff(b.runtime(),b.transactions(),second,secondExecutor,"singapore-control");
        assertTrue(first.tick());assertFalse(second.tick());copyProcesses(a.runtime(),b.admin());copyProcesses(b.runtime(),a.admin());
    }
    static void copyProcesses(JdbcTemplate from,JdbcTemplate to){
        for(var row:from.queryForList("SELECT * FROM eatwhat_cluster.control_process"))to.update("""
            INSERT INTO eatwhat_cluster.control_process(holder_id,cluster_id,name,state_id,lease_seconds,expires_at,deployment_protocol)
            VALUES(?,?,?,?,?,?,?)ON CONFLICT(holder_id)DO UPDATE SET expires_at=excluded.expires_at
            """,row.get("holder_id"),row.get("cluster_id"),row.get("name"),row.get("state_id"),row.get("lease_seconds"),row.get("expires_at"),row.get("deployment_protocol"));
    }
    static void installReleasedSnapshot(EventFoundationTestSupport.Database from,EventFoundationTestSupport.Database to){
        copyProcesses(from.runtime(),to.admin());
        var row=from.runtime().queryForMap("SELECT * FROM eatwhat_cluster.control_lease WHERE singleton");
        to.admin().update("UPDATE eatwhat_cluster.control_lease SET holder_id=?,generation=?,expires_at=? WHERE singleton",row.get("holder_id"),row.get("generation"),row.get("expires_at"));
        for(var h:from.runtime().queryForList("SELECT * FROM eatwhat_cluster.control_handoff"))to.admin().update("""
            INSERT INTO eatwhat_cluster.control_handoff(request_id,cluster_id,source_holder,target_holder,expected_generation,state,reason,actor)
            VALUES(?,?,?,?,?,?,?,?)ON CONFLICT(request_id)DO UPDATE SET state=excluded.state,reason=excluded.reason
            """,h.get("request_id"),h.get("cluster_id"),h.get("source_holder"),h.get("target_holder"),h.get("expected_generation"),h.get("state"),h.get("reason"),h.get("actor"));
    }
    @Test void partitionAndRestartsCannotConvertStalePeerStateIntoLeadership(){
        b.admin().update("UPDATE eatwhat_cluster.control_lease SET expires_at=clock_timestamp()-interval '1 second'");
        assertFalse(second.tick());assertThrows(ClusterFailure.class,second::requireHeld);first.requireHeld();
        var restarted=new ClusterControlLease(b.runtime(),cluster,5,true,false,targetId);
        try(var executor=new DeploymentExecutor(new ControlProperties(),new ControlAudit(directory.resolve("singapore")))){
            new ClusterControlHandoff(b.runtime(),b.transactions(),restarted,executor,"singapore-control");
            assertFalse(restarted.tick());assertThrows(ClusterFailure.class,restarted::requireHeld);
        }
    }
    @Test void aLostTransferLeavesNoLeaderUntilTheExplicitReleasedSnapshotArrives(){
        UUID request=UUID.randomUUID();firstHandoff.request("17",request,1,targetId);first.tick();
        assertEquals("TRANSFERRING",firstHandoff.get(request).get("state"));
        assertThrows(ClusterFailure.class,first::requireHeld);assertFalse(second.tick());
        assertThrows(ClusterFailure.class,second::requireHeld);
        installReleasedSnapshot(a,b);assertTrue(second.tick());second.requireHeld();
        assertEquals("COMPLETED",secondHandoff.get(request).get("state"));
        installReleasedSnapshot(b,a);assertFalse(first.tick());assertThrows(ClusterFailure.class,first::requireHeld);
        a.admin().update("UPDATE eatwhat_cluster.control_lease SET expires_at=clock_timestamp()-interval '1 second'");
        var restarted=new ClusterControlLease(a.runtime(),cluster,5,true,false,sourceId);
        try(var executor=new DeploymentExecutor(new ControlProperties(),new ControlAudit(directory.resolve("campus")))){
            new ClusterControlHandoff(a.runtime(),a.transactions(),restarted,executor,"campus-control");
            assertFalse(restarted.tick());second.requireHeld();
        }
    }
    @Test void persistentIdentityCannotBeCombinedWithAutomaticTakeover(){
        assertThrows(ClusterFailure.class,()->new ClusterControlLease(b.runtime(),cluster,5,true,true,targetId));
    }
    private Map<String,Object> actualMirror(boolean reverse,boolean corrupt)throws Exception{return actualAdapter(reverse,corrupt,null);}
    private Map<String,Object> actualAdapter(boolean reverse,boolean corrupt,Map<String,Object> forwarded)throws Exception{
        return actualAdapter(reverse,corrupt,forwarded,false);
    }
    private Map<String,Object> actualAdapter(boolean reverse,boolean corrupt,Map<String,Object> forwarded,boolean reorder)throws Exception{
        var from=reverse?b:a;var to=reverse?a:b;String fromId=(reverse?targetId:sourceId).toString(),toId=(reverse?sourceId:targetId).toString();
        Map<String,Object> data=new HashMap<>(Map.of("container",PG.getContainerId(),"root",directory.toString(),"cluster",cluster.toString(),"state",firstExecutor.stateIdentity().toString(),"corrupt",corrupt,
            "from",Map.of("id",fromId,"database",from.url().substring(from.url().lastIndexOf('/')+1).split("\\?")[0],"stateDirectory",directory.resolve(reverse?"singapore":"campus").toString()),
            "to",Map.of("id",toId,"database",to.url().substring(to.url().lastIndexOf('/')+1).split("\\?")[0],"stateDirectory",directory.resolve(reverse?"campus":"singapore").toString())));
        if(forwarded!=null)data.put("forwardRequest",forwarded);
        data.put("reorder",reorder);
        var process=new ProcessBuilder("python3","scripts/test-independent-control-peer-bridge.py").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try(var input=process.getOutputStream()){input.write(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(data));}
        String result=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertEquals(0,process.waitFor(),"Snapshot adapter failed");
        return tools.jackson.databind.json.JsonMapper.builder().build().readValue(result,Map.class);
    }
    @Test void actualPrivilegedAdapterCopiesFilesBeforeInstallingReleasedLeaseAndRejectsCorruption()throws Exception{
        assertEquals(true,actualMirror(false,true).get("corruptSnapshotRefused"));assertFalse(second.tick());
        Path interrupted=directory.resolve("singapore/.servers.key.independent-copy");
        Files.writeString(interrupted,"interrupted snapshot");Files.setPosixFilePermissions(interrupted,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertEquals("MIRROR_STANDBY",actualMirror(false,false).get("action"));assertFalse(second.tick());
        assertFalse(Files.exists(interrupted));
        UUID request=UUID.randomUUID();firstHandoff.request("17",request,1,targetId);first.tick();
        assertThrows(ClusterFailure.class,first::requireHeld);assertFalse(second.tick());
        // A delayed transport must not turn an explicit release into a new
        // generation and a false TARGET_TAKEOVER_NOT_CONFIRMED receipt.
        a.admin().update("UPDATE eatwhat_cluster.control_lease SET expires_at=clock_timestamp()-interval '120 seconds'");
        assertEquals("INSTALL_RELEASED_AUTHORITY",actualMirror(false,false).get("action"));assertTrue(second.tick());
        assertEquals(2L,((Number)second.status().get("generation")).longValue());
        assertEquals("COMPLETED",secondHandoff.get(request).get("state"));
        assertEquals("MIRROR_STANDBY",actualMirror(true,false).get("action"));assertFalse(first.tick());
        second.requireHeld();assertThrows(ClusterFailure.class,first::requireHeld);
    }
    @Test void forwardedStandbyIntentRechecksCurrentCredentialVersionAndNeverReissuesAnAcceptedRequest()throws Exception{
        a.admin().update("UPDATE eatwhat_controller.account SET credential_version=2 WHERE id=17");
        UUID request=UUID.randomUUID();
        Map<String,Object> intent=new HashMap<>(Map.of("request_id",request.toString(),"actor_id",17,"credential_version",1,
            "source_holder",sourceId.toString(),"target_holder",targetId.toString(),"expected_generation",1,
            "state","DISPATCHING","reason","AUTHORITY_FORWARD_IN_PROGRESS","requested_at","2026-10-23T00:00:00Z"));
        assertEquals(false,actualAdapter(false,false,intent).get("forwarded"));
        assertEquals(0,a.admin().queryForObject("SELECT count(*)FROM eatwhat_cluster.control_handoff",Integer.class));
        intent.put("credential_version",2);assertEquals(true,actualAdapter(false,false,intent).get("forwarded"));
        first.tick();assertThrows(ClusterFailure.class,first::requireHeld);
        assertEquals(true,actualAdapter(false,false,intent).get("forwarded"));
        assertEquals(1,a.admin().queryForObject("SELECT count(*)FROM eatwhat_cluster.control_handoff WHERE request_id=?",Integer.class,request));
        assertEquals(1,a.admin().queryForObject("SELECT count(*)FROM eatwhat_cluster.audit WHERE request_id=? AND action='CONTROL_HANDOFF_REQUEST'",Integer.class,request));
    }
    @Test void delayedSameGenerationSnapshotCannotRestoreAnOlderManagementCredential()throws Exception{
        assertEquals(true,actualAdapter(false,false,null,true).get("delayedSnapshotRefused"));
    }
    @AfterEach void close(){if(firstExecutor!=null)firstExecutor.close();if(secondExecutor!=null)secondExecutor.close();}
}
