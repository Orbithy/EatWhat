package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import you.v50to.eatwhat.control.ControlBusinessRuntime;
import you.v50to.eatwhat.support.SourceMaterialFixture;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses genuine migrated PG and nonowner runtime role; no source configuration,
 * OS process or actual JOIN claim is made by this database-only test. */
class ControllerSourceSnapshotIT {
    @TempDir Path root;
    ControllerRuntimeProbeIT fixture;
    ControllerSourceSnapshot.Expected expected;
    @BeforeAll static void start(){ControllerRuntimeProbeIT.PG.start();}
    @AfterAll static void stop(){ControllerRuntimeProbeIT.PG.stop();}
    @BeforeEach void setup(){
        fixture=new ControllerRuntimeProbeIT();fixture.fixture();expected=expected(2,fixture.fingerprint,fixture.state);
    }
    ControllerSourceSnapshot.Expected expected(long epoch,String fingerprint,UUID state){return new ControllerSourceSnapshot.Expected(fixture.cluster,"campus",fixture.instance,
            fingerprint,state,epoch,"control-"+"b".repeat(24),"a".repeat(64),"b".repeat(64),"c".repeat(64));}
    @Test void discoveredFilesProduceAnExpectationForTheActualReadOnlyAuthoritySnapshot()throws Exception{
        var files=new SourceMaterialFixture(root);files.properties.put("app.site.cluster-id",fixture.cluster.toString());files.properties.put("app.site.instance-id",fixture.instance.toString());
        files.properties.put("app.cluster.control-lease.controller-name",expected.controllerName());files.properties.put("spring.datasource.url",fixture.db.url());files.properties.put("spring.datasource.username",fixture.db.runtimeUsername());
        SourceMaterialFixture.write(root.resolve("state/controller-state-id"),fixture.state.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        SourceMaterialFixture.write(files.config.resolve("cluster-profiles.json"),files.json.writeValueAsBytes(Map.of("campus",Map.of("siteId","campus","instanceId",fixture.instance.toString(),"mode","CAMPUS","allocationSlot",0,"tokenFile",files.properties.get("app.control.node-token-file")))));
        byte[] application=ControlBusinessRuntime.render(files.properties).getBytes(java.nio.charset.StandardCharsets.US_ASCII);SourceMaterialFixture.write(files.config.resolve("application.properties"),application);
        var anchor=new ControllerSourceSnapshot.Authority(fixture.cluster,"campus",fixture.instance,fixture.fingerprint,fixture.state,2,ControllerSourceSnapshot.sha(new byte[32]));
        var discovered=ControllerSourceSnapshot.discover(root.toRealPath(),anchor);assertEquals(ControllerSourceSnapshot.sha(application),discovered.applicationSha256());
        try(var c=fixture.connection()){var proof=ControllerSourceSnapshot.databaseSnapshot(c,discovered);assertEquals(2L,proof.get("epoch"));assertEquals(false,proof.get("sourceIsLeader"));assertEquals(true,proof.get("readOnlyTransaction"));}
    }
    @Test void physicalSourceMayBeStandbyAndProofRemainsReadOnly()throws Exception{
        try(var c=fixture.connection()){
            var proof=ControllerSourceSnapshot.databaseSnapshot(c,expected);assertEquals(false,proof.get("sourceIsLeader"));
            assertEquals("campus-control",proof.get("leaderName"));assertEquals(5L,proof.get("generation"));
            assertEquals(true,proof.get("actualDatabaseNameAndOidVerified"));
            try(var statement=c.createStatement()){
                SQLException denied=assertThrows(SQLException.class,()->statement.executeUpdate("UPDATE eatwhat_cluster.state SET epoch=9 WHERE singleton"));
                assertEquals("25006",denied.getSQLState());
            }finally{c.rollback();}
        }
        assertEquals(2L,fixture.db.admin().queryForObject("SELECT epoch FROM eatwhat_cluster.state",Long.class));
    }
    @Test void frozenEpochAndSharedStateDriftAreRejected()throws Exception{
        for(var e:List.of(expected(3,fixture.fingerprint,fixture.state),expected(2,fixture.fingerprint,UUID.randomUUID())))
            try(var c=fixture.connection()){assertThrows(IllegalStateException.class,()->ControllerSourceSnapshot.databaseSnapshot(c,e));}
    }
    @Test void expiredSourceProcessOrLeaderCannotBeAccepted()throws Exception{
        fixture.db.admin().update("UPDATE eatwhat_cluster.control_process SET expires_at=clock_timestamp()-interval '1 second' WHERE holder_id=?",fixture.leader);
        try(var c=fixture.connection()){assertThrows(IllegalStateException.class,()->ControllerSourceSnapshot.databaseSnapshot(c,expected));}
        fixture.db.admin().update("UPDATE eatwhat_cluster.control_process SET expires_at=clock_timestamp()+interval '120 seconds' WHERE holder_id=?",fixture.leader);
        fixture.db.admin().update("UPDATE eatwhat_cluster.control_process SET expires_at=clock_timestamp()-interval '1 second' WHERE holder_id=?",fixture.holder);
        try(var c=fixture.connection()){assertThrows(IllegalStateException.class,()->ControllerSourceSnapshot.databaseSnapshot(c,expected));}
    }
    @Test void copiedMemberFingerprintCannotHideActualDatabaseIdentity()throws Exception{
        fixture.createFixture("e".repeat(32));var e=expected(2,"e".repeat(32),fixture.state);
        try(var c=fixture.connection()){assertThrows(IllegalStateException.class,()->ControllerSourceSnapshot.databaseSnapshot(c,e));}
    }
}
