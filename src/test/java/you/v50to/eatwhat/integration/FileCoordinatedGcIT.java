package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import you.v50to.eatwhat.config.FileGcProperties;
import you.v50to.eatwhat.service.storage.replication.*;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.file.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PostgreSQL roles and pinned mTLS; races use independent connections and explicit lock barriers. */
class FileCoordinatedGcIT {
    @TempDir static Path certs;
    @BeforeAll static void start() throws Exception {FileEventChannelIT.certs=certs;FileEventChannelIT.start();}
    @AfterAll static void stop() {FileEventChannelIT.stop();}
    static class Fixture implements AutoCloseable {
        final FileEventChannelIT.Pair p=new FileEventChannelIT.Pair();
        final FileReplicaCatalog.PeerRevision f;
        final MemoryStore campusStore=new MemoryStore(),externalStore=new MemoryStore();
        final FileGcProperties campusSettings=settings(),externalSettings=settings();
        final FileGcService campus,external;
        Fixture() throws Exception {this(false,"a".repeat(64),12);}
        Fixture(boolean externalOrigin,String sha,long size) throws Exception {this(externalOrigin,sha,size,true);}
        Fixture(boolean externalOrigin,String sha,long size,boolean createReferences) throws Exception {
            f=p.seed(externalOrigin?p.external:p.campus,"avatar",sha,size);p.exchange();
            for(var side:List.of(p.campus,p.external)) {
                side.sql().update("UPDATE eatwhat_file.file_replica SET state=CASE site WHEN 'LOCAL' THEN 'AVAILABLE_LOCAL' ELSE 'PEER_VERIFIED' END,verified_sha256=?,verified_size=?,verified_at=clock_timestamp() WHERE file_id=?",f.sha256(),f.sizeBytes(),f.fileId());
                side.sql().update("UPDATE eatwhat_file.file_transfer_job SET status='DONE',completed_at=clock_timestamp() WHERE file_id=?",f.fileId());
                if(createReferences) {
                    side.sql().update("INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id,visibility) VALUES(?,'test','released','PRIVATE')",f.logicalKey());
                    release(side,"released");
                }
                age(side);
            }
            campusStore.present=true;externalStore.present=true;
            campus=gc(p.campus,campusSettings,campusStore);external=gc(p.external,externalSettings,externalStore);
        }
        FileGcService gc(FileEventChannelIT.Side side,FileGcProperties settings,DeletableObjectStore store) {
            var gc=new FileGcService(side.sql(),new DataSourceTransactionManager(side.sql().getDataSource()),settings,side.db.site(),()->store);
            gc.setGcProtocol(side.gcProtocol);return gc;
        }
        void release(FileEventChannelIT.Side side,String id) {side.sql().update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'test',?,'RELEASE','ENTITY_DELETED')",f.logicalKey(),id);}
        void age(FileEventChannelIT.Side side) {side.db.admin().update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp()-interval '31 days' WHERE file_id=?",f.fileId());}
        FileGcService.RunResult run(FileEventChannelIT.Side side) {
            side.sql().update("UPDATE eatwhat_file.file_gc_candidate SET eligible_after=NULL WHERE state IN ('BLOCKED','DELETING')");
            return (side==p.campus?campus:external).runCycle();
        }
        String state(FileEventChannelIT.Side side) {return side.sql().queryForObject("SELECT state FROM eatwhat_file.file_gc_coordination WHERE file_id=?",String.class,f.fileId());}
        void prepare() throws Exception {
            assertEquals(0,run(p.campus).deleted());assertEquals("PREPARING",state(p.campus));
            p.exchange();assertEquals("REQUESTED",state(p.external));assertEquals(0,run(p.external).deleted());
            assertEquals("PREPARED",state(p.external));p.exchange();
        }
        void finish() throws Exception {
            prepare();assertEquals(1,run(p.campus).deleted());p.exchange();
            assertEquals("COMMITTED",state(p.external));assertEquals(1,run(p.external).deleted());p.exchange();
        }
        @Override public void close() {p.close();}
    }
    static FileGcProperties settings() {
        var s=new FileGcProperties();s.setEnabled(true);s.setInventoryEnabled(true);s.setExecuteEnabled(true);s.setDryRun(false);
        s.setCoordinatedEnabled(true);s.setExecuteAck("synthetic-restore-drill");s.setExecuteClasses(Set.of("UNREFERENCED_REVISION"));return s;
    }
    @Test void localDeletionFenceStopsFirstReplicationMetadataJobsAndLateReplicaProof() throws Exception {
        try(var p=new FileEventChannelIT.Pair()) {
            var side=p.campus;var sql=side.sql();UUID file=UUID.randomUUID(),intent=UUID.randomUUID();
            String key="avatar/"+side.uid+"/"+file,physical="verified/"+file,sha="a".repeat(64);
            sql.update("INSERT INTO eatwhat_file.upload_intent(intent_id,owner_id,owner_gid,biz,logical_key,staging_key,declared_size,declared_type,expires_at,status) VALUES(?,?,?,'avatar',?,?,12,'image/png',clock_timestamp()+interval '1 hour','COMPLETE')",intent,side.uid,p.owner,key,"staging/"+intent);
            sql.update("INSERT INTO eatwhat_file.file_object(file_id,owner_gid,biz,logical_key) VALUES(?,?,'avatar',?)",file,p.owner,key);
            sql.update("INSERT INTO eatwhat_file.file_revision(file_id,revision,final_key,sha256,size_bytes,content_type,state,verified_at) VALUES(?,1,?,?,12,'image/png','VERIFIED',clock_timestamp())",file,physical,sha);
            sql.update("INSERT INTO eatwhat_file.file_key_alias(logical_key,file_id,revision,owner_gid,biz) VALUES(?,?,1,?,'avatar')",key,file,p.owner);
            sql.update("INSERT INTO eatwhat_file.file_replica(file_id,revision,site,object_key,state,verified_sha256,verified_size) VALUES(?,1,'LOCAL',?,'AVAILABLE_LOCAL',?,12)",file,physical,sha);
            sql.update("INSERT INTO eatwhat_file.file_publication(file_id,revision,global_state) VALUES(?,1,'LOCAL_ONLY')",file);
            sql.update("INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id,visibility) VALUES(?,'test','local-only','PRIVATE')",key);
            sql.update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'test','local-only','RELEASE','ENTITY_DELETED')",key);
            sql.update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp()-interval '31 days' WHERE file_id=?",file);
            var store=new MemoryStore();store.present=true;store.failAfterDelete=true;
            var gc=new FileGcService(sql,new DataSourceTransactionManager(sql.getDataSource()),settings(),side.db.site(),()->store);gc.setGcProtocol(side.gcProtocol);
            assertEquals(1,gc.runCycle().errors());assertEquals("DELETING",sql.queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=?",String.class,file));
            assertEquals(0,side.count("eatwhat_file.file_gc_coordination"),"local-only deletion uses the replica fence");
            var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("consent",side.consent);
            var enqueuer=org.mockito.Mockito.spy(new FileReplicationEnqueuer(sql,beans.getBeanProvider(you.v50to.eatwhat.account.ConsentStore.class)));
            var publisher=side.newPublisher();publisher.setEnqueuer(enqueuer);publisher.runOnce();
            org.mockito.Mockito.verify(enqueuer,org.mockito.Mockito.never()).onRevisionVerified(file,1,physical);
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type IN ('file.metadata','file.replica')",Integer.class));
            var jobs=new FileTransferJobStore(sql,new FilePublicationService(sql));
            assertThrows(DataAccessException.class,()->jobs.enqueue(file,1,"COPY_TO_PEER","PEER",1L));
            assertThrows(DataAccessException.class,()->sql.update("INSERT INTO eatwhat_file.file_replica(file_id,revision,site,object_key,state) VALUES(?,1,'PEER',?,'PENDING_SYNC')",file,physical));
            var late=FileEvents.event(FileEvents.REPLICA,p.external.node,file,1,9,p.owner,new FileEvents.Replica(file,1,p.owner,1,9,sha,12,true,false));
            side.receive(late);
            assertEquals("IGNORED",sql.queryForObject("SELECT outcome FROM eatwhat_file.file_sync_receipt WHERE event_id=?",String.class,late.eventId()));
            assertEquals(0,side.count("eatwhat_file.file_transfer_job"));assertEquals(0,sql.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE site='PEER'",Integer.class));
            store.failAfterDelete=false;sql.update("UPDATE eatwhat_file.file_gc_candidate SET eligible_after=NULL");assertEquals(1,gc.runCycle().deleted());
            publisher.runOnce();assertEquals(0,sql.queryForObject("SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type IN ('file.metadata','file.replica')",Integer.class));
        }
    }
    @Test void bilateralApplicationPreparationAndCommitDeleteBothLocalCopiesThenConfirmPeerDeletion() throws Exception {
        try(var t=new Fixture()) {
            t.finish();
            for(var side:List.of(t.p.campus,t.p.external)) {
                assertEquals("DELETED",t.state(side));
                assertTrue(side.sql().queryForObject("SELECT peer_deleted FROM eatwhat_file.file_gc_coordination",Boolean.class));
                assertEquals(2,side.sql().queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE state='DELETED'",Integer.class));
            }
            assertFalse(t.campusStore.present);assertFalse(t.externalStore.present);
            long events=t.p.campus.count("eatwhat_sync.outbox")+t.p.external.count("eatwhat_sync.outbox");
            t.p.exchange();assertEquals(events,t.p.campus.count("eatwhat_sync.outbox")+t.p.external.count("eatwhat_sync.outbox"));
        }
    }
    @Test void copiedVersionsWithNoHistoricalReferencesStillEnterConservativeLocalRetentionAndCanCoordinate() throws Exception {
        try(var t=new Fixture(false,"a".repeat(64),12,false)) {
            for(var side:List.of(t.p.campus,t.p.external)) {
                assertEquals(0,side.count("eatwhat_file.file_reference"));
                assertNotNull(side.sql().queryForObject("SELECT unreferenced_since FROM eatwhat_file.file_tombstone WHERE file_id=?",java.sql.Timestamp.class,t.f.fileId()));
            }
            t.finish();assertFalse(t.campusStore.present);assertFalse(t.externalStore.present);
        }
    }
    @Test void realRustfsCopiesFromEitherOriginAreDeletedOnlyAfterBilateralPreparation() throws Exception {
        try(var rustfs=IsolatedContainers.rustfs("coordinated-gc-bytes")) {
            rustfs.start();String endpoint="http://"+rustfs.getHost()+":"+rustfs.getMappedPort(9000);
            String cb="campus-"+UUID.randomUUID(),pb="external-"+UUID.randomUUID();
            try(var s3=software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(java.net.URI.create(endpoint))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(IsolatedContainers.RUSTFS_ACCESS_KEY,IsolatedContainers.RUSTFS_SECRET_KEY)))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
                s3.createBucket(software.amazon.awssdk.services.s3.model.CreateBucketRequest.builder().bucket(cb).build());
                s3.createBucket(software.amazon.awssdk.services.s3.model.CreateBucketRequest.builder().bucket(pb).build());
            }
            byte[] bytes={1,2,3,4};String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            Path body=Files.createTempFile("coordinated-gc-synthetic-",".bin");Files.write(body,bytes);
            try(var c=new S3ReplicaObjectStore(endpoint,"us-east-1",cb,IsolatedContainers.RUSTFS_ACCESS_KEY,IsolatedContainers.RUSTFS_SECRET_KEY,true);
                var p=new S3ReplicaObjectStore(endpoint,"us-east-1",pb,IsolatedContainers.RUSTFS_ACCESS_KEY,IsolatedContainers.RUSTFS_SECRET_KEY,true)) {
                for(boolean externalOrigin:List.of(false,true)) try(var t=new Fixture(externalOrigin,sha,bytes.length)) {
                    (externalOrigin?p:c).putFile(t.f.finalKey(),"image/png",body,sha);
                    var jobs=new FileTransferJobStore(t.p.campus.sql(),new FilePublicationService(t.p.campus.sql()));
                    if(externalOrigin)t.p.campus.sql().update("UPDATE eatwhat_file.file_transfer_job SET status='READY',completed_at=NULL WHERE file_id=?",t.f.fileId());
                    else jobs.enqueue(t.f.fileId(),1,"COPY_TO_PEER","PEER",1L);
                    var copier=new FileReplicationWorker(jobs,new JdbcReplicationConsentGate(t.p.campus.sql(),t.p.campus.consent),c,p,new you.v50to.eatwhat.config.FileReplicationProperties());
                    assertEquals(1,copier.runOnce());t.p.exchange();
                    assertEquals(sha,c.readbackDigest(t.f.finalKey(),bytes.length).sha256Hex());
                    assertEquals(sha,p.readbackDigest(t.f.finalKey(),bytes.length).sha256Hex());
                    var campus=t.gc(t.p.campus,t.campusSettings,c);var external=t.gc(t.p.external,t.externalSettings,p);
                    campus.runCycle();t.p.exchange();external.runCycle();t.p.exchange();
                    assertTrue(c.head(t.f.finalKey()).isPresent());assertTrue(p.head(t.f.finalKey()).isPresent());
                    t.p.campus.sql().update("UPDATE eatwhat_file.file_gc_candidate SET eligible_after=NULL");
                    assertEquals(1,campus.runCycle().deleted());assertTrue(p.head(t.f.finalKey()).isPresent());
                    t.p.exchange();t.p.external.sql().update("UPDATE eatwhat_file.file_gc_candidate SET eligible_after=NULL");
                    assertEquals(1,external.runCycle().deleted());t.p.exchange();
                    assertTrue(c.head(t.f.finalKey()).isEmpty());assertTrue(p.head(t.f.finalKey()).isEmpty());
                }
            } finally {Files.deleteIfExists(body);}
        }
    }
    @Test void peerRestoreBeforePreparationPreventsBothDeletesUntilReleasedAndRetentionExpiresAgain() throws Exception {
        try(var t=new Fixture()) {
            t.p.external.sql().update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'test','released','RESTORE','ADMIN')",t.f.logicalKey());
            t.run(t.p.campus);t.p.exchange();assertEquals(0,t.run(t.p.external).deleted());
            assertEquals("REQUESTED",t.state(t.p.external));t.p.exchange();assertEquals(0,t.run(t.p.campus).deleted());
            assertEquals(0,t.campusStore.deletes.get());assertEquals(0,t.externalStore.deletes.get());
            t.release(t.p.external,"released");assertEquals(0,t.run(t.p.external).deleted());
            t.age(t.p.external);t.run(t.p.external);assertEquals("PREPARED",t.state(t.p.external));
            t.p.exchange();assertEquals(1,t.run(t.p.campus).deleted());t.p.exchange();assertEquals(1,t.run(t.p.external).deleted());
        }
    }
    @Test void prepareWinsAndFencesNewReferenceRestoreProjectionConflictHeadAliasAndNewTransfer() throws Exception {
        try(var t=new Fixture()) {
            t.prepare();
            for(var side:List.of(t.p.campus,t.p.external)) {
                assertThrows(DataAccessException.class,()->side.sql().update("INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id) VALUES(?,'test','new')",t.f.logicalKey()));
                assertThrows(DataAccessException.class,()->side.sql().update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'test','released','RESTORE','ADMIN')",t.f.logicalKey()));
                assertThrows(DataAccessException.class,()->side.sql().update("UPDATE users SET avatar=? WHERE id=?",t.f.logicalKey(),side.uid));
                assertThrows(DataAccessException.class,()->side.sql().update("INSERT INTO restaurant(name,account_id,location,gcj_lng,gcj_lat,picture_url) VALUES('synthetic',?,ST_SetSRID(ST_Point(117,36),4326),117,36,ARRAY[?])",side.uid,t.f.logicalKey()));
                assertThrows(DataAccessException.class,()->side.sql().update("INSERT INTO activity_foods(account_id,food_name,picture_url) VALUES(?,'synthetic',ARRAY[?])",side.uid,t.f.logicalKey()));
                long deleted=side.sql().queryForObject("INSERT INTO activity_foods(account_id,food_name,picture_url,deleted_at) VALUES(?,'synthetic',ARRAY[?],clock_timestamp()) RETURNING id",Long.class,side.uid,t.f.logicalKey());
                assertThrows(DataAccessException.class,()->side.sql().update("UPDATE activity_foods SET deleted_at=NULL WHERE id=?",deleted));
                assertEquals(1,side.sql().update("DELETE FROM activity_foods WHERE id=?",deleted));
                assertThrows(DataAccessException.class,()->side.sql().update("INSERT INTO eatwhat_sync.profile_field_state(subject_gid,field_group,value,head_set) VALUES(?,'avatar','null'::jsonb,?::jsonb)",t.p.owner,"[{\"value\":\""+t.f.logicalKey()+"\"}]"));
                assertThrows(DataAccessException.class,()->side.sql().update("INSERT INTO eatwhat_file.file_key_alias(logical_key,file_id,revision,owner_gid,biz) VALUES(?,?,1,?,'avatar')",t.f.logicalKey()+"-alias",t.f.fileId(),t.p.owner));
                assertThrows(DataAccessException.class,()->new FileTransferJobStore(side.sql(),new FilePublicationService(side.sql())).enqueue(t.f.fileId(),1,"COPY_TO_PEER","PEER",1L));
                assertThrows(DataAccessException.class,()->side.sql().update("UPDATE eatwhat_file.file_gc_coordination SET state='REQUESTED'"));
                assertThrows(DataAccessException.class,()->side.sql().execute("DELETE FROM eatwhat_file.file_gc_coordination"));
                t.release(side,"released"); // Cleanup remains legal while fenced.
            }
            assertEquals(0,t.campusStore.deletes.get());assertEquals(0,t.externalStore.deletes.get());
        }
    }
    @Test void restoringAccountKeepsFrozenAvatarHeadsHistoricalAndAllowsFreshAvatar() throws Exception {
        try(var t=new Fixture()) {
            var side=t.p.campus;
            side.db.admin().queryForObject("SELECT setval(pg_get_serial_sequence('public.users','id'),(SELECT max(id) FROM users),true)",Long.class);
            long ghost=side.sql().queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('synthetic-ghost','synthetic') RETURNING id",Long.class);
            UUID gid=side.sql().queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",UUID.class,ghost);
            side.sql().update("INSERT INTO eatwhat_sync.profile_field_state(subject_gid,field_group,value,head_set) VALUES(?,'avatar','null'::jsonb,?::jsonb)",gid,"[{\"value\":\""+t.f.logicalKey()+"\"}]");
            side.sql().update("DELETE FROM users WHERE id=?",ghost);
            t.prepare();
            // Model the account importer's reserved identity. Only identity capture is bypassed; the GC guard
            // remains enabled: account activation with a fresh avatar works; the frozen avatar cannot return.
            side.db.admin().execute("ALTER TABLE users DISABLE TRIGGER guard_global_identity");
            side.db.admin().execute("ALTER TABLE users DISABLE TRIGGER record_global_identity");
            try {
                var failure=assertThrows(DataAccessException.class,()->side.sql().update("INSERT INTO users(id,nick_name,password_hash,avatar) VALUES(?,'synthetic-restore','synthetic',?)",ghost,t.f.logicalKey()));
                var sql=assertInstanceOf(java.sql.SQLException.class,failure.getMostSpecificCause());
                assertEquals("23514",sql.getSQLState());assertTrue(sql.getMessage().contains("frozen for physical deletion"));
                assertEquals(1,side.sql().update("INSERT INTO users(id,nick_name,password_hash,avatar) VALUES(?,'synthetic-restore','synthetic',?)",ghost,"avatar/"+ghost+"/fresh.png"));
                assertEquals(0,side.sql().queryForObject("SELECT count(*) FROM eatwhat_file.file_profile_avatar_keys WHERE logical_key=?",Integer.class,t.f.logicalKey()));
                assertTrue(side.sql().queryForObject("SELECT head_set::text FROM eatwhat_sync.profile_field_state WHERE subject_gid=?",String.class,gid).contains(t.f.logicalKey()),"frozen heads are retained as history");
                assertThrows(DataAccessException.class,()->side.sql().update("UPDATE eatwhat_sync.profile_field_state SET value=to_jsonb(?::text) WHERE subject_gid=?",t.f.logicalKey(),gid));
                assertEquals(1,side.sql().update("UPDATE eatwhat_sync.profile_field_state SET local_dirty=false WHERE subject_gid=?",gid));
                assertEquals(1,side.sql().update("UPDATE eatwhat_sync.profile_field_state SET value='null'::jsonb,head_set='[]'::jsonb WHERE subject_gid=?",gid));
            } finally {
                side.db.admin().execute("ALTER TABLE users ENABLE TRIGGER guard_global_identity");
                side.db.admin().execute("ALTER TABLE users ENABLE TRIGGER record_global_identity");
            }
        }
    }
    @Test void accountActivationWinsVersionLockAndItsRetainedHeadPreventsPreparation() throws Exception {
        try(var t=new Fixture()) {
            var side=t.p.campus;
            side.db.admin().queryForObject("SELECT setval(pg_get_serial_sequence('public.users','id'),(SELECT max(id) FROM users),true)",Long.class);
            long ghost=side.sql().queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('synthetic-ghost-first','synthetic') RETURNING id",Long.class);
            UUID gid=side.sql().queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",UUID.class,ghost);
            side.sql().update("INSERT INTO eatwhat_sync.profile_field_state(subject_gid,field_group,value,head_set) VALUES(?,'avatar','null'::jsonb,?::jsonb)",gid,"[{\"value\":\""+t.f.logicalKey()+"\"}]");
            side.sql().update("DELETE FROM users WHERE id=?",ghost);
            side.db.admin().execute("ALTER TABLE users DISABLE TRIGGER guard_global_identity");
            side.db.admin().execute("ALTER TABLE users DISABLE TRIGGER record_global_identity");
            var executor=Executors.newSingleThreadExecutor();
            try(var connection=side.sql().getDataSource().getConnection()) {
                connection.setAutoCommit(false);
                try(var statement=connection.prepareStatement("INSERT INTO users(id,nick_name,password_hash,avatar) VALUES(?,'synthetic-restore-first','synthetic',NULL)")) {statement.setLong(1,ghost);statement.executeUpdate();}
                assertTrue(executor.submit(()->t.run(side)).get(10,TimeUnit.SECONDS).skipped()>0);
                connection.commit();
                var result=t.run(side);assertEquals(0,result.deleted());assertEquals(1,result.blockReasons().get("BUSINESS_REFERENCE_PRESENT"));
                assertEquals(0,side.count("eatwhat_file.file_gc_coordination"));assertTrue(t.campusStore.present);
            } finally {
                executor.shutdownNow();side.db.admin().execute("ALTER TABLE users ENABLE TRIGGER guard_global_identity");
                side.db.admin().execute("ALTER TABLE users ENABLE TRIGGER record_global_identity");
            }
        }
    }
    @Test void referenceTransactionWinsRevisionLockAndGcCannotPrepareAgainstAnUncommittedReference() throws Exception {
        try(var t=new Fixture();var c=t.p.campus.sql().getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try(var statement=c.prepareStatement("INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id) VALUES(?,'test','racing')")) {
                statement.setString(1,t.f.logicalKey());statement.executeUpdate();
            }
            var executor=Executors.newSingleThreadExecutor();
            try {
                var gc=executor.submit(()->t.run(t.p.campus));assertTrue(gc.get(10,TimeUnit.SECONDS).skipped()>0);
                c.commit();assertEquals(0,t.run(t.p.campus).deleted());
                assertEquals(0,t.p.campus.count("eatwhat_file.file_gc_coordination"));assertTrue(t.campusStore.present);
            } finally {executor.shutdownNow();}
        }
    }
    @Test void prepareTransactionWinsAndConcurrentRestoreWaitsThenFailsWithoutCreatingALiveReference() throws Exception {
        try(var t=new Fixture()) {
            var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
            try {
                var preparing=executor.submit(()->t.p.campus.db.transactions().independent(()->{
                    t.p.campus.gcProtocol.lock(t.f.fileId(),1);assertFalse(t.p.campus.gcProtocol.prepareOrCommit(t.f.fileId(),1));
                    locked.countDown();try {assertTrue(release.await(10,TimeUnit.SECONDS));} catch(InterruptedException e) {throw new IllegalStateException(e);}return null;
                }));
                assertTrue(locked.await(10,TimeUnit.SECONDS));
                var restoring=executor.submit(()->t.p.campus.sql().update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'test','released','RESTORE','ADMIN')",t.f.logicalKey()));
                assertThrows(TimeoutException.class,()->restoring.get(200,TimeUnit.MILLISECONDS));
                release.countDown();preparing.get(10,TimeUnit.SECONDS);
                var failure=assertThrows(ExecutionException.class,()->restoring.get(10,TimeUnit.SECONDS));assertInstanceOf(DataAccessException.class,failure.getCause());
                assertEquals(0,t.p.campus.count("eatwhat_file.file_reference_live"));assertTrue(t.campusStore.present);
            } finally {release.countDown();executor.shutdownNow();}
        }
    }
    @Test void transferIoFenceBlocksPrepareAndLateWorkerCannotTouchACommittedRevision() throws Exception {
        try(var t=new Fixture()) {
            var jobs=new FileTransferJobStore(t.p.campus.sql(),new FilePublicationService(t.p.campus.sql()));
            var job=new FileTransferJobStore.Job(UUID.randomUUID(),t.f.fileId(),1,"COPY_TO_PEER","PEER",1,1,1L,t.f.sha256(),t.f.sizeBytes(),"image/png",t.f.finalKey(),t.p.owner);
            try(var fence=jobs.acquireIoFence(job)) {
                assertTrue(t.run(t.p.campus).skipped()>0);assertEquals(0,t.p.campus.count("eatwhat_file.file_gc_coordination"));
            }
            t.prepare();var gate=org.mockito.Mockito.mock(ReplicationConsentGate.class);
            var worker=new FileReplicationWorker(jobs,gate,t.campusStore,t.externalStore,new you.v50to.eatwhat.config.FileReplicationProperties());
            assertEquals(FileReplicationWorker.Outcome.LOST_LEASE,worker.process(job));
            assertEquals(0,t.campusStore.reads.get());assertEquals(0,t.externalStore.reads.get());
        }
    }
    @Test void dryRunAndDisabledParticipantCannotAcknowledgePrepareOrDelete() throws Exception {
        try(var t=new Fixture()) {
            t.campusSettings.setDryRun(true);t.run(t.p.campus);assertEquals(0,t.p.campus.count("eatwhat_file.file_gc_coordination"));
            t.campusSettings.setDryRun(false);t.run(t.p.campus);t.p.exchange();
            t.externalSettings.setDryRun(true);t.run(t.p.external);assertEquals("REQUESTED",t.state(t.p.external));
            t.externalSettings.setDryRun(false);t.externalSettings.setCoordinatedEnabled(false);t.run(t.p.external);
            assertEquals("REQUESTED",t.state(t.p.external));assertEquals(0,t.externalStore.deletes.get());
            t.p.exchange();assertEquals(0,t.run(t.p.campus).deleted());
        }
    }
    @Test void applyThenLoseDeleteResponseAndPublisherRestartResumeWithoutSkippingCommit() throws Exception {
        try(var t=new Fixture()) {
            t.prepare();t.campusStore.failAfterDelete=true;
            assertEquals(1,t.run(t.p.campus).errors());assertEquals("COMMITTED",t.state(t.p.campus));
            assertFalse(t.campusStore.present);assertTrue(t.externalStore.present);
            t.p.campus.newPublisher().runOnce();t.p.exchange();assertEquals("COMMITTED",t.state(t.p.external));
            assertEquals(1,t.run(t.p.external).deleted());t.p.exchange();
            t.campusStore.failAfterDelete=false;assertEquals(1,t.run(t.p.campus).deleted());t.p.exchange();
            assertTrue(t.p.campus.sql().queryForObject("SELECT peer_deleted FROM eatwhat_file.file_gc_coordination",Boolean.class));
            assertEquals("DELETED",t.state(t.p.external));
        }
    }
    @Test void foreignOperationAndDigestAreRejectedAndOutOfOrderCommitWaitsForPrepare() throws Exception {
        try(var t=new Fixture()) {
            t.run(t.p.campus);t.p.campus.worker.runOnce();
            var prepare=t.p.campus.event(FileGcEvents.TYPE);var d=FileGcEvents.decision(prepare.payloadJson());
            var commit=FileGcEvents.envelope(d.withPhase("COMMIT"),t.p.campus.node);
            t.p.external.receive(commit);
            assertEquals("WAITING_DEPENDENCY",t.p.external.sql().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?",String.class,commit.eventId()));
            t.p.external.receive(prepare);assertEquals("REQUESTED",t.state(t.p.external));
            t.run(t.p.external);assertTrue(t.p.external.inbox.retryWaiting(commit.eventId()));t.p.external.drain();
            assertEquals("COMMITTED",t.state(t.p.external));
            var bad=new FileGcEvents.Decision(UUID.randomUUID(),d.fileId(),1,d.sha256(),d.sizeBytes(),d.coordinator(),d.participant(),"PREPARED");
            assertThrows(IllegalArgumentException.class,()->t.p.campus.db.transactions().independent(()->{t.p.campus.gcProtocol.apply(FileGcEvents.envelope(bad,t.p.external.node));return null;}));
            var wrongDigest=new FileGcEvents.Decision(d.operationId(),d.fileId(),1,"b".repeat(64),d.sizeBytes(),d.coordinator(),d.participant(),"PREPARED");
            assertThrows(IllegalArgumentException.class,()->t.p.campus.db.transactions().independent(()->{t.p.campus.gcProtocol.apply(FileGcEvents.envelope(wrongDigest,t.p.external.node));return null;}));
            assertFalse(t.p.campus.sql().queryForObject("SELECT peer_prepared FROM eatwhat_file.file_gc_coordination",Boolean.class));
        }
    }
    @Test void lostPrepareTransportResponseReplaysOneDurableOperationWithoutAuthorizingDeletion() throws Exception {
        try(var t=new Fixture()) {
            t.run(t.p.campus);t.p.campus.worker.runOnce();var lost=new java.util.concurrent.atomic.AtomicBoolean();
            var network=t.p.networkWorker((path,request)->{
                var response=t.p.client.exchange(path,request);
                if(path.endsWith("/push")&&lost.compareAndSet(false,true))throw new java.io.IOException("synthetic-lost-GC-response");
                return response;
            });
            network.runOnce();assertTrue(lost.get());t.p.external.drain();assertEquals("REQUESTED",t.state(t.p.external));
            t.p.campus.newPublisher().runOnce();Thread.sleep(100);t.p.exchange();
            assertEquals(1,t.p.campus.sql().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='file.gc'",Integer.class));
            assertEquals(0,t.campusStore.deletes.get());assertEquals(0,t.externalStore.deletes.get());
        }
    }
    @Test void outboxFailureRollsBackPhaseProgressAndRestartPublishesExactlyOnce() throws Exception {
        try(var t=new Fixture()) {
            t.run(t.p.campus);
            t.p.campus.db.admin().execute("CREATE FUNCTION public.reject_synthetic_gc_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.aggregate_type='file.gc' THEN RAISE EXCEPTION 'synthetic-gc-outbox-failure'; END IF;RETURN NEW;END $$");
            t.p.campus.db.admin().execute("CREATE TRIGGER reject_synthetic_gc_outbox BEFORE INSERT ON eatwhat_sync.outbox FOR EACH ROW EXECUTE FUNCTION public.reject_synthetic_gc_outbox()");
            assertThrows(DataAccessException.class,()->t.p.campus.worker.runOnce());
            assertEquals(0,t.p.campus.sql().queryForObject("SELECT published_phase FROM eatwhat_file.file_gc_coordination",Integer.class));
            assertEquals(0,t.p.campus.sql().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='file.gc'",Integer.class));
            t.p.campus.db.admin().execute("DROP TRIGGER reject_synthetic_gc_outbox ON eatwhat_sync.outbox");
            t.p.campus.db.admin().execute("DROP FUNCTION public.reject_synthetic_gc_outbox()");
            t.p.campus.newPublisher().runOnce();t.p.campus.newPublisher().runOnce();
            assertEquals(1,t.p.campus.sql().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='file.gc'",Integer.class));
            assertEquals(1,t.p.campus.sql().queryForObject("SELECT published_phase FROM eatwhat_file.file_gc_coordination",Integer.class));
        }
    }
    @Test void failedV35RollsBackFencesAndNumericChecksThenRetryAppliesOnlyV35(@TempDir Path directory) throws Exception {
        String name="gc_migration_"+UUID.randomUUID().toString().replace("-","");
        var server=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(IsolatedContainers.jdbcUrl(FileEventChannelIT.PG,IsolatedContainers.DATABASE),IsolatedContainers.USER,IsolatedContainers.PASSWORD));
        server.execute("CREATE DATABASE "+name+" TEMPLATE template0");String url=IsolatedContainers.jdbcUrl(FileEventChannelIT.PG,name);
        org.flywaydb.core.Flyway.configure().dataSource(url,IsolatedContainers.USER,IsolatedContainers.PASSWORD).target("34").load().migrate();
        var sql=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,IsolatedContainers.USER,IsolatedContainers.PASSWORD));
        long owner=sql.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('synthetic-upgrade','synthetic') RETURNING id",Long.class);
        UUID gid=sql.queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",UUID.class,owner);
        UUID file=UUID.randomUUID();String key="avatar/"+owner+"/"+file;
        sql.update("INSERT INTO eatwhat_file.file_object(file_id,owner_gid,biz,logical_key) VALUES(?,?,'avatar',?)",file,gid,key);
        sql.update("INSERT INTO eatwhat_file.file_revision(file_id,revision,final_key,sha256,size_bytes,content_type,state,verified_at) VALUES(?,1,?,?,12,'image/png','VERIFIED',clock_timestamp())",file,"verified/"+file,"a".repeat(64));
        sql.update("INSERT INTO eatwhat_file.file_key_alias(logical_key,file_id,revision,owner_gid,biz) VALUES(?,?,1,?,'avatar')",key,file,gid);
        sql.update("INSERT INTO eatwhat_file.file_replica(file_id,revision,site,object_key,state) VALUES(?,1,'PEER',?,'PEER_VERIFIED')",file,"verified/"+file);
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM eatwhat_file.file_tombstone",Integer.class));
        String oldGuard=sql.queryForObject("SELECT pg_get_functiondef('eatwhat_file.guard_reference_target_live()'::regprocedure)",String.class);
        Path migrations=Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        try(var files=Files.list(migrations)) {for(Path f:files.filter(f->f.getFileName().toString().endsWith(".sql")).toList())Files.copy(f,directory.resolve(f.getFileName()));}
        Files.writeString(directory.resolve("V35__coordinated_file_gc.sql"),"\nSELECT 1/0;\n",StandardOpenOption.APPEND);
        var broken=org.flywaydb.core.Flyway.configure().dataSource(url,IsolatedContainers.USER,IsolatedContainers.PASSWORD).locations("filesystem:"+directory).load();
        assertThrows(org.flywaydb.core.api.FlywayException.class,broken::migrate);
        assertEquals("34",broken.info().current().getVersion().getVersion());
        assertNull(sql.queryForObject("SELECT to_regclass('eatwhat_file.file_gc_coordination')::text",String.class));
        assertEquals(oldGuard,sql.queryForObject("SELECT pg_get_functiondef('eatwhat_file.guard_reference_target_live()'::regprocedure)",String.class));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM eatwhat_file.file_tombstone",Integer.class));
        java.sql.Timestamp before=sql.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class);
        var retry=org.flywaydb.core.Flyway.configure().dataSource(url,IsolatedContainers.USER,IsolatedContainers.PASSWORD).target("35").load();
        assertEquals(1,retry.migrate().migrationsExecuted);assertEquals(0,retry.migrate().migrationsExecuted);retry.validate();
        assertNotNull(sql.queryForObject("SELECT to_regclass('eatwhat_file.file_gc_coordination')::text",String.class));
        assertFalse(sql.queryForObject("SELECT unreferenced_since FROM eatwhat_file.file_tombstone WHERE file_id=?",java.sql.Timestamp.class,file).before(before));
    }
    static class MemoryStore implements DeletableObjectStore {
        volatile boolean present,failAfterDelete;
        final AtomicInteger deletes=new AtomicInteger(),reads=new AtomicInteger();
        @Override public Optional<ObjectMeta> head(String key) {reads.incrementAndGet();if(failAfterDelete)throw new IllegalStateException("synthetic-head-unavailable");return present?Optional.of(new ObjectMeta(12,"image/png")):Optional.empty();}
        @Override public DigestResult downloadTo(String key,Path path,long max) {reads.incrementAndGet();throw new UnsupportedOperationException();}
        @Override public void putFile(String key,String type,Path path,String sha) {throw new UnsupportedOperationException();}
        @Override public DigestResult readbackDigest(String key,long max) {reads.incrementAndGet();throw new UnsupportedOperationException();}
        @Override public ObjectPage listObjects(String prefix,String token,int max) {throw new UnsupportedOperationException();}
        @Override public void deleteObject(String key) {deletes.incrementAndGet();present=false;if(failAfterDelete)throw new IllegalStateException("synthetic-lost-response");}
    }
}
