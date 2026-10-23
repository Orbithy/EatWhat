package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Two authority processes against one PostgreSQL authority: DB-clock takeover and transaction fencing. */
class ClusterControlLeaseIT {
    static final GenericContainer<?> POSTGRES=IsolatedContainers.postgres("cluster-control-lease");
    @BeforeAll static void start(){POSTGRES.start();}
    @AfterAll static void stop(){POSTGRES.stop();}
    EventFoundationTestSupport.Database db;
    UUID cluster;
    ClusterMember campus;
    ClusterControlLease first,standby;
    ClusterStore firstStore,standbyStore;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path stateDirectory;
    you.v50to.eatwhat.control.DeploymentExecutor firstExecutor,standbyExecutor;

    @BeforeEach void setup(){
        cluster=UUID.randomUUID();campus=new ClusterMember("campus",UUID.randomUUID(),"CAMPUS",0);
        var site=new SiteProperties();site.setId(campus.siteId());site.setInstanceId(campus.instanceId());site.setMode(SiteProperties.Mode.CAMPUS);site.setClusterId(cluster);site.setAllocationSlot(0);
        db=EventFoundationTestSupport.createDatabase(POSTGRES,site);
        String role="\""+db.runtimeUsername()+"\"";
        db.admin().execute("GRANT USAGE ON SCHEMA eatwhat_cluster TO "+role);
        db.admin().execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA eatwhat_cluster TO "+role);
        db.admin().execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA eatwhat_cluster TO "+role);
        first=new ClusterControlLease(db.runtime(),cluster,5);standby=new ClusterControlLease(db.runtime(),cluster,5);
        firstStore=new ClusterStore(db.runtime(),db.transactions(),cluster,campus.identity(),first);
        standbyStore=new ClusterStore(db.runtime(),db.transactions(),cluster,campus.identity(),standby);
        firstStore.initialize();standbyStore.initialize();
        var settings=new you.v50to.eatwhat.control.ControlProperties();var audit=new you.v50to.eatwhat.control.ControlAudit(stateDirectory.resolve("shared"));firstExecutor=new you.v50to.eatwhat.control.DeploymentExecutor(settings,audit);standbyExecutor=new you.v50to.eatwhat.control.DeploymentExecutor(settings,audit);new ClusterControlHandoff(db.runtime(),db.transactions(),first,firstExecutor,"first");new ClusterControlHandoff(db.runtime(),db.transactions(),standby,standbyExecutor,"standby");
    }
    @AfterEach void close(){if(firstExecutor!=null)firstExecutor.close();if(standbyExecutor!=null)standbyExecutor.close();}

    @Test void takeoverFencesStaleMembershipAndGcDecisions(){
        var unfenced=new ClusterStore(db.runtime(),db.transactions(),cluster,campus.identity());
        unfenced.initialize();
        assertTrue(first.tick());assertFalse(standby.tick());
        assertEquals("CONTROL_LEASE_REQUIRED",assertThrows(ClusterFailure.class,()->new ClusterStore(db.runtime(),db.transactions(),cluster,campus.identity()).initialize()).getMessage(),"An unfenced coordinator may not join a leased cluster");
        assertEquals("CONTROL_LEASE_REQUIRED",assertThrows(ClusterFailure.class,()->unfenced.register("operator",UUID.randomUUID(),0,"unsafe",new ClusterMember("unsafe",UUID.randomUUID(),"EXTERNAL",2))).getMessage(),"An already-running unfenced coordinator must stop writing when HA starts");
        firstStore.register("operator",UUID.randomUUID(),0,"campus",campus);
        assertEquals("CONTROL_LEASE_NOT_HELD",assertThrows(ClusterFailure.class,()->standbyStore.register("operator",UUID.randomUUID(),0,"other",new ClusterMember("other",UUID.randomUUID(),"EXTERNAL",1))).getMessage());
        db.admin().update("UPDATE eatwhat_cluster.control_lease SET expires_at=clock_timestamp()-interval '1 second' WHERE singleton");
        assertTrue(standby.tick());assertEquals(2,((Number)standby.status().get("generation")).intValue());
        assertEquals("CONTROL_LEASE_NOT_HELD",assertThrows(ClusterFailure.class,()->firstStore.register("operator",UUID.randomUUID(),0,"stale",new ClusterMember("stale",UUID.randomUUID(),"EXTERNAL",2))).getMessage());
        var other=new ClusterMember("other",UUID.randomUUID(),"EXTERNAL",1);
        standbyStore.register("operator",UUID.randomUUID(),0,"other",other);
        assertEquals("REGISTERED",standbyStore.member("other").get("state"));
        var staleGc=new ClusterGcStore(db.runtime(),db.transactions(),firstStore);
        assertEquals("CONTROL_LEASE_NOT_HELD",assertThrows(ClusterFailure.class,()->staleGc.begin(UUID.randomUUID(),UUID.randomUUID(),1,1)).getMessage());
        assertFalse(first.tick(),"The old process cannot renew a new holder's live generation");
    }

    @Test void sameProcessGetsANewGenerationAfterItsOwnLeaseExpires(){
        assertTrue(first.tick());
        db.admin().update("UPDATE eatwhat_cluster.control_lease SET expires_at=clock_timestamp()-interval '1 second' WHERE singleton");
        assertTrue(first.tick());
        assertEquals(2,((Number)first.status().get("generation")).intValue());
        assertFalse(standby.tick());
    }

    @Test void manualStandbyHeartbeatsButCannotBootstrapOrClaimAnExpiredForeignLease(){
        var manual=new ClusterControlLease(db.runtime(),cluster,5,true,false);
        new ClusterControlHandoff(db.runtime(),db.transactions(),manual,standbyExecutor,"manual-only");
        assertFalse(manual.tick());assertEquals("UNCLAIMED",manual.status().get("state"));
        assertEquals("MANUAL_HANDOFF_ONLY",manual.status().get("takeoverMode"));
        assertEquals(1,db.admin().queryForObject("SELECT count(*) FROM eatwhat_cluster.control_process WHERE name='manual-only' AND expires_at>clock_timestamp()",Integer.class));
        assertTrue(first.tick());assertFalse(manual.tick());
        db.admin().update("UPDATE eatwhat_cluster.control_lease SET expires_at=clock_timestamp()-interval '1 second' WHERE singleton");
        for(int n=0;n<3;n++)assertFalse(manual.tick());
        assertEquals(1,((Number)manual.status().get("generation")).intValue());assertFalse((Boolean)manual.status().get("localLeader"));
        assertThrows(ClusterFailure.class,manual::requireHeld);
    }

    @Test void takeoverWaitsForAnInFlightFencedWrite()throws Exception{
        assertTrue(first.tick());
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var oldWrite=pool.submit(()->first.write(db.transactions(),()->{
                entered.countDown();try{if(!finish.await(15,TimeUnit.SECONDS))throw new IllegalStateException("write timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                db.runtime().update("INSERT INTO eatwhat_cluster.audit(request_id,actor,action,epoch) VALUES(?,'operator','REGISTER',0)",UUID.randomUUID());return true;
            }));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Thread.sleep(5200);
            var takeover=pool.submit(standby::tick);
            assertThrows(TimeoutException.class,()->takeover.get(250,TimeUnit.MILLISECONDS),"Takeover must wait for the old row lock");
            finish.countDown();assertTrue(oldWrite.get(10,TimeUnit.SECONDS));assertTrue(takeover.get(10,TimeUnit.SECONDS));
        }finally{finish.countDown();}
        assertEquals(1,db.admin().queryForObject("SELECT count(*) FROM eatwhat_cluster.audit",Integer.class));
        assertEquals("CONTROL_LEASE_NOT_HELD",assertThrows(ClusterFailure.class,first::requireHeld).getMessage());
    }
}
