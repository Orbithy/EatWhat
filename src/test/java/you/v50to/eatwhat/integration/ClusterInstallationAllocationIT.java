package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.dao.DataAccessException;
import you.v50to.eatwhat.cluster.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import you.v50to.eatwhat.control.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real PG/transactions/triggers; no installation effects or remote nodes. */
class ClusterInstallationAllocationIT extends ClusterMembershipFixture {
    @TempDir Path directory;
    @BeforeEach void activeAuthority(){finish(beginJoin(campus,0));}
    @Test void acceptedAllocationIsRetainedAcrossControllerInstancesAndRegistrationRemoval(){
        UUID server=UUID.randomUUID(),job=UUID.randomUUID();store.reserveInstallation("admin",job,server,external);
        var other=new ClusterStore(db.runtime(),db.transactions(),cluster,campus.identity());other.initialize();other.reserveInstallation("admin",job,server,external);other.reserveInstallation("admin",UUID.randomUUID(),server,external);
        assertEquals(external.instanceId(),other.installationAllocation(server).get("instance_id"));assertEquals(job,other.installationAllocation(server).get("first_job_id"));assertEquals(1,db.admin().queryForObject("SELECT count(*) FROM eatwhat_cluster.installation_allocation",Integer.class));
        assertEquals("INSTALLATION_ALLOCATION_RETAINED",assertThrows(ClusterFailure.class,()->other.reserveInstallation("admin",UUID.randomUUID(),UUID.randomUUID(),new ClusterMember("another",UUID.randomUUID(),"EXTERNAL",1))).getMessage());
        assertThrows(DataAccessException.class,()->db.admin().update("DELETE FROM eatwhat_cluster.installation_allocation WHERE server_id=?",server));
        assertThrows(DataAccessException.class,()->db.admin().update("UPDATE eatwhat_cluster.installation_allocation SET allocation_slot=2 WHERE server_id=?",server));
        assertThrows(DataAccessException.class,()->db.admin().update("INSERT INTO eatwhat_cluster.member(site_id,instance_id,profile_id,mode,allocation_slot,state) VALUES('wrong',?,'wrong','EXTERNAL',1,'REGISTERED')",UUID.randomUUID()));
        assertEquals("REGISTERED",other.register("admin",UUID.randomUUID(),1,"external",external).get("state"));
    }
    @Test void concurrentDifferentServersCannotClaimTheSameUnusedSlot()throws Exception{
        var other=new ClusterStore(db.runtime(),db.transactions(),cluster,campus.identity());other.initialize();var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var results=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++){final int n=i;results.add(pool.submit(()->{start.await();try{(n==0?store:other).reserveInstallation("admin",UUID.randomUUID(),UUID.randomUUID(),new ClusterMember("target"+n,UUID.randomUUID(),"EXTERNAL",1));return true;}catch(ClusterFailure conflict){assertEquals("INSTALLATION_ALLOCATION_RETAINED",conflict.getMessage());return false;}}));}
            start.countDown();int success=0;for(var r:results)if(r.get(10,TimeUnit.SECONDS))success++;assertEquals(1,success);assertEquals(1,db.admin().queryForObject("SELECT count(*) FROM eatwhat_cluster.installation_allocation",Integer.class));
        }
    }
    @Test void previouslyRetainedMemberAndWrongRoleCannotBeReallocated(){
        register(external,1);assertEquals("MEMBER_IDENTITY_OR_SLOT_RETAINED",assertThrows(ClusterFailure.class,()->store.reserveInstallation("admin",UUID.randomUUID(),UUID.randomUUID(),new ClusterMember("fresh",UUID.randomUUID(),"EXTERNAL",1))).getMessage());
        assertEquals("CLUSTER_ROLE_SLOT_MISMATCH",assertThrows(ClusterFailure.class,()->store.reserveInstallation("admin",UUID.randomUUID(),UUID.randomUUID(),new ClusterMember("wrong",UUID.randomUUID(),"CAMPUS",2))).getMessage());assertEquals(0,db.admin().queryForObject("SELECT count(*) FROM eatwhat_cluster.installation_allocation",Integer.class));
    }
    @Test void installationAdmissionReportsAllocationConflictWithoutDispatchingSsh()throws Exception{
        store.reserveInstallation("admin",UUID.randomUUID(),UUID.randomUUID(),external);
        long user=db.runtime().queryForObject("INSERT INTO users(nick_name,password_hash,role) VALUES('allocation-admin','synthetic-private-hash','admin') RETURNING id",Long.class);String actor=Long.toString(user);
        var audit=new ControlAudit(directory);var registry=new ControlServerStore(audit);var settings=new ControlProperties();settings.setStateDirectory(directory);settings.getServers().setInstallationEnabled(true);
        Path jar=directory.resolve("inert-fixture.jar");Files.writeString(jar,"Synthetic artifact; conflict must prevent upload");var release=new ControlProperties.Release();release.setArtifact(jar);release.setSha256(DeploymentExecutor.artifactSha(jar));release.setAcceptanceEvidenceId("synthetic-unit-boundary");settings.getReleases().put("fixture",release);
        var beans=new StaticListableBeanFactory(Map.of("membership",store));var ssh=mock(ControlSsh.class);
        try(var deployment=new DeploymentExecutor(settings,audit)){
            var servers=new ControlServers(registry,ssh,deployment,audit,db.site(),beans.getBeanProvider(ClusterProperties.class),beans.getBeanProvider(ClusterControlLease.class));UUID server=UUID.randomUUID();byte[] type="ssh-ed25519".getBytes();String key="ssh-ed25519 "+Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(4+type.length+4+32).putInt(type.length).put(type).putInt(32).put(new byte[32]).array());
            servers.save(actor,server,new ControlServerController.ServerInput(server,null,"allocation target","target.example.invalid",22,"root","REMOTE","PASSWORD","synthetic-private-password",key,ControlSsh.key(key).get("fingerprint")),true);
            when(ssh.call(anyMap(),anyString(),eq(List.of("inspect")),any(),anyInt())).thenReturn(Map.of("protocol","eatwhat-install-v1","serverId",server.toString(),"root","/opt/eatwhat-sites/"+server,"rootAvailable",true,"portAvailable",true,"rootUser",true,"dockerReady",true,"projectAvailable",true,"diskFreeMiB",10000));
            var environment=new MockEnvironment().withProperty("app.cluster.enabled","true").withProperty("app.site.cluster-id",cluster.toString());
            try(var installer=new ControlInstallExecutor(registry,ssh,servers,settings,audit,environment,db.runtime(),beans.getBeanProvider(ClusterStore.class))){
                var plan=installer.plan(actor,server,new ControlServerController.InstallInput(1,"fixture",18090,"https://business.example.invalid/api",false,"EXTERNAL",1),"INSTALL");assertEquals("READY",plan.get("state"));UUID task=UUID.fromString(plan.get("id").toString());
                var failure=assertThrows(ControlFailure.class,()->installer.execute(actor,task));assertEquals(409,failure.status);assertEquals("INSTALLATION_ALLOCATION_RETAINED",failure.code);assertFalse(Files.exists(directory.resolve("server-job-"+task+".json")));verify(ssh,never()).call(anyMap(),anyString(),eq(List.of("install")),any(),anyInt());assertEquals(1,db.admin().queryForObject("SELECT count(*) FROM eatwhat_cluster.installation_allocation",Integer.class));
            }
        }
    }
}
