package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.control.bootstrap.ControllerRuntimeProbe;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real migrated PostgreSQL with synthetic controller/member rows. Not JOIN or a running controller. */
class ControllerRuntimeProbeIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("controller-runtime-probe");
    @BeforeAll static void start(){PG.start();}
    @AfterAll static void stop(){PG.stop();}
    EventFoundationTestSupport.Database db;
    UUID cluster,instance,state,holder,leader;
    Map<String,Object> expected;
    String fingerprint;
    @BeforeEach void fixture(){createFixture(null);}
    void createFixture(String storedMemberFingerprint){
        cluster=UUID.randomUUID();instance=UUID.randomUUID();state=UUID.randomUUID();holder=UUID.randomUUID();leader=UUID.randomUUID();
        var site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId("campus");site.setInstanceId(instance);site.setClusterId(cluster);site.setAllocationSlot(0);
        db=EventFoundationTestSupport.createDatabase(PG,site);
        fingerprint=db.admin().queryForObject("SELECT md5(cluster_identifier||':'||database_oid::text) FROM eatwhat_identity.node_state WHERE singleton",String.class);
        String role="\""+db.runtimeUsername()+"\"";
        db.admin().execute("GRANT USAGE ON SCHEMA eatwhat_cluster TO "+role);
        db.admin().execute("GRANT SELECT,UPDATE ON eatwhat_cluster.state,eatwhat_cluster.member,eatwhat_cluster.control_process,eatwhat_cluster.control_lease TO "+role);
        db.admin().update("INSERT INTO eatwhat_cluster.state(cluster_id,coordinator_site,coordinator_instance,epoch) VALUES(?,'campus',?,2)",cluster,instance);
        db.admin().update("INSERT INTO eatwhat_cluster.member(site_id,instance_id,profile_id,mode,allocation_slot,state,database_fingerprint,joined_epoch) VALUES('campus',?,'campus','CAMPUS',0,'ACTIVE',?,2)",instance,storedMemberFingerprint==null?fingerprint:storedMemberFingerprint);
        for(var row:List.of(Map.entry(holder,"control-"+"b".repeat(24)),Map.entry(leader,"campus-control")))
            db.admin().update("INSERT INTO eatwhat_cluster.control_process(holder_id,cluster_id,name,state_id,lease_seconds,expires_at) VALUES(?,?,?,?,120,clock_timestamp()+interval '120 seconds')",row.getKey(),cluster,row.getValue(),state);
        db.admin().update("INSERT INTO eatwhat_cluster.control_lease(singleton,cluster_id,holder_id,generation,expires_at) VALUES(true,?,?,5,clock_timestamp()+interval '120 seconds')",cluster,leader);
        expected=new LinkedHashMap<>(Map.of("clusterId",cluster.toString(),"authoritySiteId","campus","authorityInstanceId",instance.toString(),
                "authorityDatabaseFingerprint",fingerprint,"stateId",state.toString(),"controllerName","control-"+"b".repeat(24),"requireStandby",true));
    }
    Connection connection()throws SQLException{return DriverManager.getConnection(db.url(),db.runtimeUsername(),IsolatedContainers.PASSWORD);}
    @Test void realJdbcProvesStandbyAndCannotWriteEvenWithUpdatePrivilege()throws Exception {
        try(var c=connection()){
            var proof=ControllerRuntimeProbe.snapshot(c,expected);
            assertEquals(false,proof.get("localLeader"));assertEquals(true,proof.get("leaseLive"));assertEquals(2L,proof.get("epoch"));
            assertTrue(c.isReadOnly());
            try(var statement=c.createStatement()){
                SQLException denied=assertThrows(SQLException.class,()->statement.executeUpdate("UPDATE eatwhat_cluster.state SET epoch=99 WHERE singleton"));
                assertEquals("25006",denied.getSQLState());
            }finally{c.rollback();}
        }
        assertEquals(2L,db.admin().queryForObject("SELECT epoch FROM eatwhat_cluster.state",Long.class));
        assertEquals(5L,db.admin().queryForObject("SELECT generation FROM eatwhat_cluster.control_lease",Long.class));
    }
    @Test void differentSharedStateIsRejectedWithoutChangingAnyRow()throws Exception {
        expected.put("stateId",UUID.randomUUID().toString());
        try(var c=connection()){assertThrows(IllegalStateException.class,()->ControllerRuntimeProbe.snapshot(c,expected));}
        assertEquals(state,db.admin().queryForObject("SELECT state_id FROM eatwhat_cluster.control_process WHERE holder_id=?",UUID.class,holder));
    }
    @Test void expiredLeaseIsRejectedInActualDatabaseTime()throws Exception {
        db.admin().update("UPDATE eatwhat_cluster.control_lease SET expires_at=clock_timestamp()-interval '1 second'");
        try(var c=connection()){assertThrows(IllegalStateException.class,()->ControllerRuntimeProbe.snapshot(c,expected));}
    }
    @Test void matchingStoredMemberFingerprintCannotHideAnotherActualDatabase()throws Exception {
        // Prepare inconsistent synthetic seed data at creation time; never
        // disable the production schema's immutable-member trigger to alter it.
        createFixture("e".repeat(32));
        expected.put("authorityDatabaseFingerprint","e".repeat(32));
        try(var c=connection()){assertThrows(IllegalStateException.class,()->ControllerRuntimeProbe.snapshot(c,expected));}
    }
    @Test void unexpectedLeaderIsRejectedInitiallyButLaterManualRoleCanBeRead()throws Exception {
        db.admin().update("UPDATE eatwhat_cluster.control_lease SET holder_id=?",holder);
        try(var c=connection()){assertThrows(IllegalStateException.class,()->ControllerRuntimeProbe.snapshot(c,expected));}
        expected.put("requireStandby",false);
        try(var c=connection()){assertEquals(true,ControllerRuntimeProbe.snapshot(c,expected).get("localLeader"));}
    }
}
