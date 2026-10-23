package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.*;
import you.v50to.eatwhat.config.identity.GlobalIdentityMigrationCallback;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL migration, restricted roles, sequence allocation and retained membership/GC evidence. */
class ClusterMembershipIT extends ClusterMembershipFixture {
    @Test void threeAllocationDomainsRemainDistinctAndCannotBeDowngraded()throws Exception {
        var second=database(external,true);var newSite=database(third,true);
        long a=db.runtime().queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('cluster-a','synthetic') RETURNING id",Long.class);
        long b=second.runtime().queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('cluster-b','synthetic') RETURNING id",Long.class);
        long c=newSite.runtime().queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('cluster-c','synthetic') RETURNING id",Long.class);
        assertEquals(1_000_000_001L,a);assertEquals(1_000_000_002L,b);assertEquals(GlobalIdentityMigrationCallback.CLUSTER_ID_FLOOR+2,c);
        assertEquals(1024,newSite.admin().queryForObject("SELECT seqincrement FROM pg_sequence WHERE seqrelid=pg_get_serial_sequence('users','id')::regclass",Integer.class));
        for(String function:List.of("next_relation_number","next_notification_read_number","next_catalog_number")){
            assertEquals(1L,db.admin().queryForObject("SELECT eatwhat_sync."+function+"()",Long.class));
            assertEquals(2L,second.admin().queryForObject("SELECT eatwhat_sync."+function+"()",Long.class));
            assertEquals(GlobalIdentityMigrationCallback.CLUSTER_ID_FLOOR+2,newSite.admin().queryForObject("SELECT eatwhat_sync."+function+"()",Long.class));
        }
        assertThrows(DataAccessException.class,()->newSite.runtime().update("INSERT INTO users(id,nick_name,password_hash) VALUES(?,'wrong-slot','synthetic')",c+1));
        var old=new SiteProperties();old.setId(third.siteId());old.setInstanceId(third.instanceId());old.setMode(SiteProperties.Mode.EXTERNAL);
        try(var connection=newSite.admin().getDataSource().getConnection()){assertThrows(IllegalStateException.class,()->new GlobalIdentityMigrationCallback(old,newSite.runtimeUsername()).activateOrVerify(connection));}
        try(var connection=newSite.admin().getDataSource().getConnection()){new GlobalIdentityMigrationCallback(newSite.site(),newSite.runtimeUsername()).activateOrVerify(connection);}
    }
    @Test void sameRequestIsIdempotentAndSlotIdentityEpochAndRetainedRowsAreEnforced(){
        UUID request=UUID.randomUUID();var result=store.register("admin",request,0,"campus",campus);assertEquals(result.get("siteId"),store.register("admin",request,0,"campus",campus).get("siteId"));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(ClusterFailure.class,()->store.register("admin",request,0,"different",campus)).getMessage());
        assertEquals("STALE_MEMBERSHIP_EPOCH",assertThrows(ClusterFailure.class,()->store.register("admin",UUID.randomUUID(),1,"external",external)).getMessage());
        var duplicate=new ClusterMember("clone",campus.instanceId(),"CAMPUS",2);assertEquals("MEMBER_IDENTITY_OR_SLOT_RETAINED",assertThrows(ClusterFailure.class,()->store.register("admin",UUID.randomUUID(),0,"clone",duplicate)).getMessage());
        assertThrows(DataAccessException.class,()->db.runtime().update("DELETE FROM eatwhat_cluster.member"));assertThrows(DataAccessException.class,()->db.runtime().update("UPDATE eatwhat_cluster.member SET instance_id=?",UUID.randomUUID()));
    }
    @Test void threeSiteJoinNeedsEveryExactIdentityAndApplyAckBeforeAnotherChange(){
        UUID first=beginJoin(campus,0);assertEquals("MEMBERSHIP_CONFIRMATIONS_PENDING",assertThrows(ClusterFailure.class,()->store.decide("admin",first)).getMessage());finish(first);
        UUID second=beginJoin(external,1);finish(second);UUID next=beginJoin(third,2);
        ClusterTestVotes.membership(store,next,campus,"PREPARED");ClusterTestVotes.membership(store,next,external,"PREPARED");
        assertEquals("MEMBERSHIP_CONFIRMATIONS_PENDING",assertThrows(ClusterFailure.class,()->store.decide("admin",next)).getMessage());
        assertThrows(ClusterFailure.class,()->ClusterTestVotes.membership(store,next,new ClusterMember("third",UUID.randomUUID(),"EXTERNAL",2),"PREPARED"));
        ClusterTestVotes.membership(store,next,third,"PREPARED");store.decide("admin",next);
        assertEquals("MEMBERSHIP_CONFIRMATIONS_PENDING",assertThrows(ClusterFailure.class,()->store.finish("admin",next)).getMessage());
        for(var member:List.of(campus,external,third))ClusterTestVotes.membership(store,next,member,"APPLIED");store.finish("admin",next);
        for(var member:List.of(campus,external,third))ClusterTestVotes.membership(store,next,member,"RELEASED");store.released("admin",next);
        assertEquals(3,store.active().size());
        var restarted=new ClusterStore(db.runtime(),db.transactions(),cluster,campus.identity());restarted.initialize();assertEquals(3,restarted.active().size());
    }
    @Test void retirementRetainsIdentityAndRequiresAllOtherParticipants(){
        finish(beginJoin(campus,0));finish(beginJoin(external,1));finish(beginJoin(third,2));
        UUID retire=UUID.randomUUID();store.begin("admin",retire,3,"RETIRE",third.siteId(),fingerprint(third));
        assertEquals("DRAINING",store.member("third").get("state"));finish(retire);assertEquals("RETIRED",store.member("third").get("state"));assertEquals(2,store.active().size());
        assertEquals("SITE_ID_RETAINED",assertThrows(ClusterFailure.class,()->store.register("admin",UUID.randomUUID(),4,"third",third)).getMessage());
        assertEquals("COORDINATOR_RETIRE_REQUIRES_TRANSFER",assertThrows(ClusterFailure.class,()->store.begin("admin",UUID.randomUUID(),4,"RETIRE","campus",fingerprint(campus))).getMessage());
    }
    @Test void multiPartyGcCannotCommitWithoutThirdVoteAndMembershipCannotDropAnOutstandingParticipant(){
        finish(beginJoin(campus,0));finish(beginJoin(external,1));finish(beginJoin(third,2));UUID file=UUID.randomUUID();seed(db,file,UUID.randomUUID());
        var gc=new ClusterGcStore(db.runtime(),db.transactions(),store);UUID operation=UUID.randomUUID();var decision=gc.begin(operation,file,1,3);
        assertEquals(3,decision.participants().size());assertEquals(decision,gc.begin(operation,file,1,3));
        ClusterTestVotes.gc(gc,operation,campus,"PREPARED");ClusterTestVotes.gc(gc,operation,external,"PREPARED");assertEquals("PREPARING",gc.advance(operation));
        assertEquals("GC_DECISIONS_PENDING",assertThrows(ClusterFailure.class,()->store.begin("admin",UUID.randomUUID(),3,"RETIRE","third",fingerprint(third))).getMessage());
        ClusterTestVotes.gc(gc,operation,third,"PREPARED");assertEquals("COMMITTED",gc.advance(operation));
        ClusterTestVotes.gc(gc,operation,campus,"DELETED");ClusterTestVotes.gc(gc,operation,external,"DELETED");assertEquals("COMMITTED",gc.advance(operation));
        ClusterTestVotes.gc(gc,operation,third,"DELETED");assertEquals("DELETED",gc.advance(operation));
        assertThrows(DataAccessException.class,()->db.runtime().update("UPDATE eatwhat_cluster.gc_operation SET participants='[]'::jsonb WHERE operation_id=?",operation));
    }
    @Test void realThreeDatabaseFencesRejectConcurrentReferenceRestorationAndRemainAfterRestart(){
        finish(beginJoin(campus,0));finish(beginJoin(external,1));finish(beginJoin(third,2));var second=database(external,true);var thirdDb=database(third,true);
        UUID file=UUID.randomUUID(),owner=UUID.randomUUID();for(var node:List.of(db,second,thirdDb))seed(node,file,owner);
        var gc=new ClusterGcStore(db.runtime(),db.transactions(),store);var decision=gc.begin(UUID.randomUUID(),file,1,3);
        for(var pair:List.of(Map.entry(db,campus),Map.entry(second,external),Map.entry(thirdDb,third))){
            var node=pair.getKey();String role="\""+node.runtimeUsername()+"\"";
            node.admin().execute("GRANT USAGE ON SCHEMA eatwhat_cluster,eatwhat_file TO "+role);node.admin().execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA eatwhat_cluster,eatwhat_file TO "+role);node.admin().execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA eatwhat_file TO "+role);
            node.runtime().update("INSERT INTO eatwhat_cluster.local_state(singleton,cluster_id,epoch,site_id,instance_id,state,members) VALUES(true,?,3,?,?,'ACTIVE',?::jsonb)",cluster,pair.getValue().siteId(),pair.getValue().instanceId(),tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(List.of(campus,external,third)));
            var settings=new FileGcProperties();settings.setEnabled(true);settings.setExecuteEnabled(true);settings.setDryRun(false);settings.setCoordinatedEnabled(true);settings.setExecuteAck("synthetic-restore-drill");settings.setExecuteClasses(Set.of("UNREFERENCED_REVISION"));
            var participant=new ClusterGcParticipant(node.runtime(),node.transactions(),pair.getValue(),settings);String key="avatar/1000000001/"+file;
            node.runtime().update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'test','released','RESTORE','ADMIN')",key);
            assertEquals("GC_LOCAL_RETENTION_OR_REPLICA_PENDING",assertThrows(ClusterFailure.class,()->participant.prepare(decision)).getMessage());
            node.runtime().update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'test','released','RELEASE','ENTITY_DELETED')",key);node.admin().update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp()-interval '31 days' WHERE file_id=?",file);
            assertEquals("PREPARED",participant.prepare(decision));assertEquals("PREPARED",participant.prepare(decision));
            assertThrows(DataAccessException.class,()->node.runtime().update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'test','released','RESTORE','ADMIN')",key));
            assertThrows(DataAccessException.class,()->node.runtime().update("INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id,visibility) VALUES(?,'test','new','PRIVATE')",key));
            var restarted=new ClusterGcParticipant(node.runtime(),node.transactions(),pair.getValue(),settings);assertEquals("COMMITTED",restarted.commit(decision));
            assertThrows(DataAccessException.class,()->node.runtime().update("DELETE FROM eatwhat_cluster.gc_fence WHERE file_id=?",file));
        }
    }
    void seed(EventFoundationTestSupport.Database node,UUID file,UUID owner){
        String key="avatar/1000000001/"+file,physical="verified/"+file,sha="a".repeat(64);
        node.admin().update("INSERT INTO eatwhat_file.file_object(file_id,owner_gid,biz,logical_key) VALUES(?,?,'avatar',?)",file,owner,key);
        node.admin().update("INSERT INTO eatwhat_file.file_revision(file_id,revision,final_key,sha256,size_bytes,content_type,state,verified_at) VALUES(?,1,?,?,12,'image/png','VERIFIED',clock_timestamp()-interval '31 days')",file,physical,sha);
        node.admin().update("INSERT INTO eatwhat_file.file_key_alias(logical_key,file_id,revision,owner_gid,biz) VALUES(?,?,1,?,'avatar')",key,file,owner);
        node.admin().update("INSERT INTO eatwhat_file.file_replica(file_id,revision,site,object_key,state,verified_sha256,verified_size) VALUES(?,1,'LOCAL',?,'AVAILABLE_LOCAL',?,12)",file,physical,sha);
        node.admin().update("INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id,visibility) VALUES(?,'test','released','PRIVATE')",key);
        node.admin().update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'test','released','RELEASE','ENTITY_DELETED')",key);
        node.admin().update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp()-interval '31 days' WHERE file_id=?",file);
    }
}
