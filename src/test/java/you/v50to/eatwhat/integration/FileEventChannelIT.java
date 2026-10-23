package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.*;
import you.v50to.eatwhat.service.storage.FileAliasResolver;
import you.v50to.eatwhat.service.storage.replication.*;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.file.*;
import you.v50to.eatwhat.sync.transport.*;
import java.net.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Two real databases, restricted runtime roles, pinned mTLS and committed application receipts. */
class FileEventChannelIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("file-event-channel");
    @TempDir static Path certs;
    static TransportTlsFixture tls;
    @BeforeAll static void start() throws Exception { PG.start(); tls=TransportTlsFixture.create(certs); }
    @AfterAll static void stop() { PG.stop(); }

    @Test void metadataAndApplicationReceiptTraverseActualTlsWithoutInventingBusinessIds() throws Exception {
        try(Pair p=new Pair()) {
            var f=p.seed(p.campus,"avatar");
            p.campus.db.runtime().update("UPDATE users SET avatar=? WHERE id=?",f.logicalKey(),p.campus.uid);
            p.campus.worker.runOnce();
            var e=p.campus.event(FileEvents.METADATA);
            p.network.runOnce();
            assertEquals("ACKED",p.campus.sql().queryForObject("SELECT status FROM eatwhat_sync.delivery WHERE event_id=?",String.class,e.eventId()));
            assertEquals(0,p.campus.count("eatwhat_file.file_sync_ack"),"receipt of the envelope is not application confirmation");
            p.external.drain();
            assertEquals(1,p.external.count("eatwhat_file.file_revision"));
            assertEquals("PENDING_SYNC",p.external.sql().queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL'",String.class,f.fileId()));
            assertNull(new FileAliasResolver(p.external.sql()).resolve(f.logicalKey()).readableObjectKey());
            p.exchange();
            assertNotNull(p.campus.sql().queryForObject("SELECT peer_confirmed_at FROM eatwhat_file.file_publication WHERE file_id=?",java.sql.Timestamp.class,f.fileId()));
            assertEquals(0,p.external.sql().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='file.metadata'",Integer.class));
            assertEquals(1,p.campus.sql().queryForObject("SELECT count(*) FROM eatwhat_identity.entity_identity WHERE entity_type='users'",Integer.class));
            long before=p.campus.count("eatwhat_sync.outbox"); p.exchange(); assertEquals(before,p.campus.count("eatwhat_sync.outbox"),"stable state creates no echo loop");
        }
    }
    @Test void actualObjectCopiesAndTlsEventsConvergeBothDirectionsBeforeUrlsBecomeReadable() throws Exception {
        try(Pair p=new Pair();var rustfs=IsolatedContainers.rustfs("file-channel-bytes")) {
            rustfs.start();String endpoint="http://"+rustfs.getHost()+":"+rustfs.getMappedPort(9000);
            String campusBucket="campus-"+UUID.randomUUID(),externalBucket="external-"+UUID.randomUUID();
            try(var s3=software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(URI.create(endpoint))
                    .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                    .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(IsolatedContainers.RUSTFS_ACCESS_KEY,IsolatedContainers.RUSTFS_SECRET_KEY)))
                    .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
                s3.createBucket(software.amazon.awssdk.services.s3.model.CreateBucketRequest.builder().bucket(campusBucket).build());
                s3.createBucket(software.amazon.awssdk.services.s3.model.CreateBucketRequest.builder().bucket(externalBucket).build());
            }
            try(var localStore=new S3ReplicaObjectStore(endpoint,"us-east-1",campusBucket,IsolatedContainers.RUSTFS_ACCESS_KEY,IsolatedContainers.RUSTFS_SECRET_KEY,true);
                var peerStore=new S3ReplicaObjectStore(endpoint,"us-east-1",externalBucket,IsolatedContainers.RUSTFS_ACCESS_KEY,IsolatedContainers.RUSTFS_SECRET_KEY,true)) {
                var jobs=new FileTransferJobStore(p.campus.sql(),new FilePublicationService(p.campus.sql()));
                var copier=new FileReplicationWorker(jobs,new JdbcReplicationConsentGate(p.campus.sql(),p.campus.consent),localStore,peerStore,new FileReplicationProperties(),new Random(1));
                byte[] bytes={(byte)137,'P','N','G',13,10,26,10,1,2,3,4};
                String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                Path body=java.nio.file.Files.createTempFile("synthetic-file-channel-",".png");
                try {
                    java.nio.file.Files.write(body,bytes);
                    for(Side source:List.of(p.campus,p.external)) {
                        Side receiver=source==p.campus?p.external:p.campus;
                        var f=p.seed(source,"avatar",sha,bytes.length);
                        (source==p.campus?localStore:peerStore).putFile(f.finalKey(),"image/png",body,sha);
                        source.sql().update("UPDATE eatwhat_file.file_replica SET verified_sha256=?,verified_size=?,verified_at=clock_timestamp() WHERE file_id=? AND site='LOCAL'",sha,bytes.length,f.fileId());
                        if(source==p.campus)jobs.enqueue(f.fileId(),1,"COPY_TO_PEER","PEER",1L);
                        p.exchange();receiver.sql().update("UPDATE users SET avatar=? WHERE id=?",f.logicalKey(),receiver.uid);
                        assertNull(new FileAliasResolver(receiver.sql()).resolve(f.logicalKey()).readableObjectKey());
                        assertEquals(1,copier.runOnce());p.exchange();
                        assertEquals(sha,localStore.readbackDigest(f.finalKey(),bytes.length).sha256Hex());
                        assertEquals(sha,peerStore.readbackDigest(f.finalKey(),bytes.length).sha256Hex());
                        assertEquals(f.finalKey(),new FileAliasResolver(receiver.sql()).resolve(f.logicalKey()).readableObjectKey());
                        assertEquals("AVAILABLE_GLOBAL",receiver.sql().queryForObject("SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=?",String.class,f.fileId()));
                        assertEquals("AVAILABLE_GLOBAL",source.sql().queryForObject("SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=?",String.class,f.fileId()));
                    }
                } finally {java.nio.file.Files.deleteIfExists(body);}
            }
        }
    }
    @Test void externalMetadataIsPulledByCampusAndQueuesLocalByteVerification() throws Exception {
        try(Pair p=new Pair()) {
            var f=p.seed(p.external,"avatar");p.exchange();
            assertEquals(1,p.campus.count("eatwhat_file.file_revision"));
            assertEquals("PULL_FROM_PEER",p.campus.sql().queryForObject("SELECT kind FROM eatwhat_file.file_transfer_job WHERE file_id=?",String.class,f.fileId()));
            assertEquals("PENDING_SYNC",p.campus.sql().queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL'",String.class,f.fileId()));
        }
    }
    @Test void withdrawnAccountPurgeReleasesAvatarAndKeepsUploadHistoryThenPublishesCleanup() throws Exception {
        try(Pair p=new Pair()) {
            var f=p.seed(p.external,"avatar");p.external.sql().update("UPDATE users SET avatar=? WHERE id=?",f.logicalKey(),p.external.uid);
            UUID intent=UUID.randomUUID();
            p.external.sql().update("INSERT INTO eatwhat_file.upload_intent(intent_id,owner_id,owner_gid,biz,logical_key,staging_key,declared_size,declared_type,expires_at,status) VALUES(?,?,?,'avatar',?,?,12,'image/png',clock_timestamp()+interval '1 hour','COMPLETE')",intent,p.external.uid,p.owner,"avatar/"+intent,"staging/"+intent);
            p.exchange();
            p.external.sql().update("UPDATE eatwhat_consent.subject SET status='WITHDRAWN',epoch=2 WHERE user_id=?",p.external.uid);
            assertTrue(p.external.sql().queryForObject("SELECT eatwhat_consent.purge_account(?,?)",Boolean.class,p.owner,p.external.uid));
            assertEquals(0,p.external.count("eatwhat_file.file_reference_live"));
            assertEquals(1,p.external.count("eatwhat_file.upload_intent"));
            p.exchange();
            assertTrue(p.campus.sql().queryForObject("SELECT tombstoned FROM eatwhat_file.file_sync_peer_state WHERE file_id=?",Boolean.class,f.fileId()));
        }
    }
    @Test void tombstoneBeforeMetadataAndLateOlderStateCannotResurrectReferences() throws Exception {
        try(Pair p=new Pair()) {
            UUID file=UUID.randomUUID();
            var old=FileEvents.event(FileEvents.REFERENCES,p.campus.node,file,1,1,null,new FileEvents.References(file,1,1,2,1,false));
            var tomb=FileEvents.event(FileEvents.REFERENCES,p.campus.node,file,1,2,null,new FileEvents.References(file,1,2,0,0,true));
            p.external.receive(tomb); p.external.receive(old);
            assertEquals(true,p.external.sql().queryForObject("SELECT tombstoned FROM eatwhat_file.file_sync_peer_state WHERE file_id=?",Boolean.class,file));
            assertEquals(2L,p.external.sql().queryForObject("SELECT generation FROM eatwhat_file.file_sync_peer_state WHERE file_id=?",Long.class,file));
            assertEquals(0,p.external.count("eatwhat_file.file_object"));
            assertEquals(2,p.external.count("eatwhat_file.file_sync_receipt"));
            assertEquals(InboxService.ReceiptStatus.ALREADY_APPLIED,p.external.inbox.receive(tomb,p.campus.node.siteId()).receiptStatus());
        }
    }
    @Test void revokedConsentBlocksMetadataButStillTransportsDeletionStateAndRegrantGetsFreshPublication() throws Exception {
        try(Pair p=new Pair()) {
            var f=p.seed(p.campus,"avatar"); p.campus.worker.runOnce(); var old=p.campus.event(FileEvents.METADATA);
            for(Side s:List.of(p.campus,p.external)) s.db.admin().update("UPDATE eatwhat_consent.subject SET epoch=2,status='WITHDRAWN' WHERE user_id=?",s.uid);
            assertEquals(EventPolicy.OutboundDecision.POLICY_BLOCKED,p.campus.policy.outbound(old));
            p.external.receive(old);
            assertEquals(0,p.external.count("eatwhat_file.file_object"));
            assertEquals("IGNORED",p.external.sql().queryForObject("SELECT outcome FROM eatwhat_file.file_sync_receipt WHERE event_id=?",String.class,old.eventId()));
            p.exchange();
            assertTrue(p.external.sql().queryForObject("SELECT tombstoned FROM eatwhat_file.file_sync_peer_state WHERE file_id=?",Boolean.class,f.fileId()));
            for(Side s:List.of(p.campus,p.external)) s.db.admin().update("UPDATE eatwhat_consent.subject SET epoch=3,status='GRANTED',identity_ready=true,valid_until=clock_timestamp()+interval '5 min' WHERE user_id=?",s.uid);
            p.exchange();
            assertEquals(1,p.external.count("eatwhat_file.file_revision"));
            assertEquals(2,p.campus.sql().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='file.metadata'",Integer.class));
        }
    }
    @Test void localAndPeerReferencesRemainIndependentAndRestoreGetsANewGeneration() throws Exception {
        try(Pair p=new Pair()) {
            var f=p.seed(p.campus,"avatar"); p.exchange();
            p.campus.sql().update("UPDATE users SET avatar=? WHERE id=?",f.logicalKey(),p.campus.uid);
            p.external.sql().update("UPDATE users SET avatar=? WHERE id=?",f.logicalKey(),p.external.uid);
            p.exchange();
            p.campus.sql().update("UPDATE users SET avatar=NULL WHERE id=?",p.campus.uid); p.exchange();
            assertEquals(1,p.external.count("eatwhat_file.file_reference_live"),"remote tombstone must not erase a local live reference");
            assertTrue(p.external.sql().queryForObject("SELECT tombstoned FROM eatwhat_file.file_sync_peer_state WHERE file_id=?",Boolean.class,f.fileId()));
            p.campus.sql().update("UPDATE users SET avatar=? WHERE id=?",f.logicalKey(),p.campus.uid); p.exchange();
            assertFalse(p.external.sql().queryForObject("SELECT tombstoned FROM eatwhat_file.file_sync_peer_state WHERE file_id=?",Boolean.class,f.fileId()));
        }
    }
    @Test void incomingMetadataReconcilesAnEarlierBusinessProjectionAndReplacingAvatarReleasesIt() throws Exception {
        try(Pair p=new Pair()) {
            var f=p.seed(p.campus,"avatar");
            p.external.sql().update("UPDATE users SET avatar=? WHERE id=?",f.logicalKey(),p.external.uid);
            assertEquals(0,p.external.count("eatwhat_file.file_reference")); p.exchange();
            assertEquals(1,p.external.count("eatwhat_file.file_reference_live"));
            p.external.sql().update("UPDATE users SET avatar=NULL WHERE id=?",p.external.uid);
            assertEquals(0,p.external.count("eatwhat_file.file_reference_live"));
            assertEquals(1,p.external.count("eatwhat_file.file_cdn_invalidation"));
            assertNotNull(p.external.sql().queryForObject("SELECT unreferenced_since FROM eatwhat_file.file_tombstone WHERE file_id=?",java.sql.Timestamp.class,f.fileId()));
        }
    }
    @Test void byteAttestationArrivingBeforeMetadataWaitsThenConvergesWithoutFalseAvailability() throws Exception {
        try(Pair p=new Pair()) {
            var f=p.seed(p.campus,"avatar");
            var a=FileEvents.event(FileEvents.REPLICA,p.campus.node,f.fileId(),1,99,p.owner,new FileEvents.Replica(f.fileId(),1,p.owner,1,99,f.sha256(),f.sizeBytes(),true,true));
            p.external.receive(a);
            assertEquals("WAITING_DEPENDENCY",p.external.sql().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?",String.class,a.eventId()));
            p.exchange();
            p.external.inbox.retryWaiting(a.eventId()); p.external.drain();
            assertEquals("AVAILABLE_LOCAL",p.external.sql().queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL'",String.class,f.fileId()));
            assertEquals("AVAILABLE_GLOBAL",p.external.sql().queryForObject("SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=?",String.class,f.fileId()));
        }
    }
    @Test void forgedApplicationReceiptCannotConfirmMetadataOrOtherRevision() throws Exception {
        try(Pair p=new Pair()) {
            var f=p.seed(p.campus,"avatar"); p.campus.worker.runOnce(); var m=p.campus.event(FileEvents.METADATA);
            var receipt=FileEvents.event(FileEvents.RECEIPT,p.external.node,m.eventId(),1,1,null,new FileEvents.Applied(m.eventId(),"f".repeat(64),f.fileId(),1,"APPLIED"));
            p.campus.inbox.receive(receipt,p.external.node.siteId());
            var lease=p.campus.inbox.claim(1,Duration.ofSeconds(10)).getFirst();
            assertThrows(IllegalArgumentException.class,()->p.campus.inbox.process(lease));
            assertEquals(0,p.campus.count("eatwhat_file.file_sync_ack"));
            assertNull(p.campus.sql().queryForObject("SELECT peer_confirmed_at FROM eatwhat_file.file_publication WHERE file_id=?",java.sql.Timestamp.class,f.fileId()));
        }
    }
    @Test void immutableMetadataMismatchRollsBackImportAndDoesNotAcknowledgeApplication() throws Exception {
        try(Pair p=new Pair()) {
            var f=p.seed(p.campus,"avatar"); p.exchange();
            for(Side s:List.of(p.campus,p.external)) s.db.admin().update("UPDATE eatwhat_consent.subject SET epoch=2 WHERE user_id=?",s.uid);
            var altered=new FileReplicaCatalog.PeerRevision(f.fileId(),1,p.owner,"avatar",f.logicalKey(),"verified/"+UUID.randomUUID(),f.sha256(),f.sizeBytes(),f.contentType());
            var e=FileEvents.event(FileEvents.METADATA,p.campus.node,f.fileId(),1,2,p.owner,new FileEvents.Metadata(altered,2));
            p.external.inbox.receive(e,p.campus.node.siteId()); var lease=p.external.inbox.claim(100,Duration.ofSeconds(10)).stream().filter(l->l.eventId().equals(e.eventId())).findFirst().orElseThrow();
            assertThrows(IllegalArgumentException.class,()->p.external.inbox.process(lease));
            assertEquals(f.finalKey(),p.external.sql().queryForObject("SELECT final_key FROM eatwhat_file.file_revision WHERE file_id=?",String.class,f.fileId()));
            assertEquals(0,p.external.sql().queryForObject("SELECT count(*) FROM eatwhat_file.file_sync_receipt WHERE event_id=?",Integer.class,e.eventId()));
        }
    }
    @Test void rolledBackDeletionKeepsReferenceAndDirtyGenerationAndCdnQueueUnchanged() throws Exception {
        try(Pair p=new Pair()) {
            var f=p.seed(p.campus,"avatar"); p.campus.sql().update("UPDATE users SET avatar=? WHERE id=?",f.logicalKey(),p.campus.uid);
            long generation=p.campus.sql().queryForObject("SELECT generation FROM eatwhat_file.file_sync_dirty WHERE file_id=?",Long.class,f.fileId());
            assertThrows(IllegalStateException.class,()->p.campus.db.transactions().required(()->{p.campus.sql().update("UPDATE users SET avatar=NULL WHERE id=?",p.campus.uid);throw new IllegalStateException("rollback");}));
            assertEquals(1,p.campus.count("eatwhat_file.file_reference_live"));
            assertEquals(generation,p.campus.sql().queryForObject("SELECT generation FROM eatwhat_file.file_sync_dirty WHERE file_id=?",Long.class,f.fileId()));
            assertEquals(0,p.campus.count("eatwhat_file.file_cdn_invalidation"));
        }
    }
    @Test void outboxFailureRollsBackPublicationProgressAndRestartPublishesItExactlyOnce() throws Exception {
        try(Pair p=new Pair()) {
            var f=p.seed(p.campus,"avatar");
            p.campus.db.admin().execute("CREATE FUNCTION public.reject_file_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic publication failure'; END $$");
            p.campus.db.admin().execute("CREATE TRIGGER reject_file_outbox BEFORE INSERT ON eatwhat_sync.outbox FOR EACH ROW EXECUTE FUNCTION public.reject_file_outbox()");
            p.campus.worker.runOnce();
            assertEquals(0,p.campus.count("eatwhat_sync.outbox"));
            assertEquals(0L,p.campus.sql().queryForObject("SELECT published_generation FROM eatwhat_file.file_sync_dirty WHERE file_id=?",Long.class,f.fileId()));
            p.campus.db.admin().execute("DROP TRIGGER reject_file_outbox ON eatwhat_sync.outbox");
            p.campus.newPublisher().runOnce();assertEquals(2,p.campus.count("eatwhat_sync.outbox"));
            p.campus.newPublisher().runOnce();assertEquals(2,p.campus.count("eatwhat_sync.outbox"));
            p.exchange();assertEquals(1,p.external.count("eatwhat_file.file_revision"));
        }
    }
    @Test void committedTransportResponseLossAndPublisherRestartReuseExistingIds() throws Exception {
        try(Pair p=new Pair()) {
            p.seed(p.campus,"avatar"); p.campus.worker.runOnce(); var before=p.campus.events();
            AtomicBoolean lost=new AtomicBoolean();
            var worker=p.networkWorker((path,request)->{var response=p.client.exchange(path,request);if(path.endsWith("/push")&&lost.compareAndSet(false,true))throw new java.io.IOException("lost response");return response;});
            worker.runOnce(); assertTrue(lost.get()); p.external.drain();
            p.campus.newPublisher().runOnce(); assertEquals(before,p.campus.events());
            Thread.sleep(100); p.exchange();
            assertEquals(1,p.external.count("eatwhat_file.file_revision"));
            assertEquals(0,p.campus.sql().queryForObject("SELECT count(*) FROM eatwhat_sync.delivery WHERE status NOT IN ('ACKED','QUARANTINED')",Integer.class));
        }
    }

    static class Side {
        final EventFoundationTestSupport.Database db;
        final NodeIdentity node,peer;
        final ConsentStore consent;
        final FileEventPolicy policy;
        final InboxService inbox;
        final OutboxService outbox;
        final FileSyncWorker worker;
        final FileGcProtocol gcProtocol;
        long uid;
        Side(EventFoundationTestSupport.Database db,NodeIdentity peer) {
            this.db=db;this.peer=peer;node=new NodeIdentity(db.site().getId(),db.site().getInstanceId());
            Flyway.configure().dataSource(db.url(),IsolatedContainers.USER,IsolatedContainers.PASSWORD).callbacks(new FileSchemaMigrationCallback(db.runtimeUsername())).load().migrate();
            consent=new ConsentStore(sql(),db.transactions(),db.site(),"p1",Duration.ofMinutes(5),Duration.ofMinutes(5),peer.siteId(),peer.instanceId());
            policy=new FileEventPolicy(consent); outbox=new OutboxService(sql(),db.transactions(),new EventCodec(),db.site());
            gcProtocol=new FileGcProtocol(sql(),node,peer,db.site().getMode());
            var handlers=new ArrayList<EventHandler>(FileEvents.TYPES.stream().map(t->(EventHandler)new FileEventHandler(t,sql(),consent,new FileReplicaCatalog(sql()),new FilePublicationService(sql()),peer,50*1024*1024,db.site().getMode())).toList());
            handlers.add(gcProtocol);
            inbox=new InboxService(sql(),db.transactions(),new EventCodec(),new EventHandlerRegistry(handlers));worker=newPublisher();
        }
        FileSyncWorker newPublisher() { var w=new FileSyncWorker(sql(),db.transactions(),outbox,consent,node,peer,100);w.setGcProtocol(gcProtocol);return w; }
        JdbcTemplate sql() { return db.runtime(); }
        int count(String table) { return sql().queryForObject("SELECT count(*) FROM "+table,Integer.class); }
        void receive(SyncEvent e) { inbox.receive(e,peer.siteId());drain(); }
        void drain() { for(var lease:inbox.claim(100,Duration.ofSeconds(20))) inbox.process(lease); }
        SyncEvent event(String type) { return new EventCodec().decode(sql().queryForObject("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type=? ORDER BY local_order DESC LIMIT 1",String.class,type)); }
        List<UUID> events() { return sql().queryForList("SELECT event_id FROM eatwhat_sync.outbox ORDER BY local_order",UUID.class); }
    }
    static class Pair implements AutoCloseable {
        final UUID owner=UUID.randomUUID();
        final Side campus,external;
        final NodeHttpsServer server;
        final SyncHttpClient client;
        final CampusSyncWorker network;
        Pair() throws Exception {
            var c=database(SiteProperties.Mode.CAMPUS,"file-campus"); var x=database(SiteProperties.Mode.EXTERNAL,"file-external");
            campus=new Side(c,new NodeIdentity(x.site().getId(),x.site().getInstanceId()));external=new Side(x,campus.node);
            for(Side s:List.of(campus,external)) seedUser(s);
            server=new NodeHttpsServer(new InetSocketAddress("127.0.0.1",0),tls.context(tls.external(),tls.campus()),
                    new NodeEndpoint(external.node,campus.node,external.inbox,delivery(external),new EventCodec(),Duration.ofSeconds(2),Duration.ofMillis(20),external.policy),Duration.ofSeconds(3));server.start();
            client=new SyncHttpClient(URI.create("https://127.0.0.1:"+server.port()),tls.context(tls.campus(),tls.external()),Duration.ofSeconds(2)); network=networkWorker(client);
        }
        CampusSyncWorker networkWorker(TransportClient client) { return new CampusSyncWorker(campus.node,external.node,delivery(campus),campus.inbox,new EventCodec(),client,Duration.ofSeconds(2),new RetryPolicy(Duration.ofMillis(20),Duration.ofMillis(100),()->.5),new TransportMetrics(),campus.policy); }
        void seedUser(Side s) {
            // Migration-owner fixture imports only synthetic identities, without changing runtime privileges.
            s.db.admin().execute("ALTER TABLE public.users DISABLE TRIGGER USER");
            s.uid=1000000001L;
            s.db.admin().update("INSERT INTO users(id,nick_name,password_hash) VALUES(?,?,?)",s.uid,"synthetic-"+owner.toString().substring(0,12),"synthetic-hash");
            s.db.admin().execute("ALTER TABLE public.users ENABLE TRIGGER USER");
            s.db.admin().update("INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site) VALUES('users',?,?,?)",s.uid,owner,campus.node.siteId());
            s.db.admin().update("INSERT INTO eatwhat_consent.subject(subject_id,user_id,origin_site,epoch,status,policy_version,identity_ready,backfill_status,valid_until,source_archived) VALUES(?,?,?,1,'GRANTED','p1',true,'DONE',clock_timestamp()+interval '5 min',true)",owner,s.uid,campus.node.siteId());
        }
        FileReplicaCatalog.PeerRevision seed(Side s,String biz) {return seed(s,biz,"a".repeat(64),12);}
        FileReplicaCatalog.PeerRevision seed(Side s,String biz,String sha,long size) {
            UUID file=UUID.randomUUID();String key=biz+"/"+s.uid+"/"+file+".png",finalKey="verified/"+file;
            s.sql().update("INSERT INTO eatwhat_file.file_object(file_id,owner_gid,biz,logical_key) VALUES(?,?,?,?)",file,owner,biz,key);
            s.sql().update("INSERT INTO eatwhat_file.file_revision(file_id,revision,final_key,sha256,size_bytes,content_type,state,verified_at) VALUES(?,1,?, ?,?,'image/png','VERIFIED',clock_timestamp())",file,finalKey,sha,size);
            s.sql().update("INSERT INTO eatwhat_file.file_key_alias(logical_key,file_id,revision,owner_gid,biz) VALUES(?,?,1,?,?)",key,file,owner,biz);
            s.sql().update("INSERT INTO eatwhat_file.file_sync_origin VALUES(?,?,?)",file,s.node.siteId(),s.node.instanceId());
            s.sql().update("INSERT INTO eatwhat_file.file_replica(file_id,revision,site,object_key,state) VALUES(?,1,'LOCAL',?,'AVAILABLE_LOCAL'),(?,1,'PEER',?,'PENDING_SYNC')",file,finalKey,file,finalKey);
            s.sql().update("INSERT INTO eatwhat_file.file_publication(file_id,revision,global_state) VALUES(?,1,'PEER_PENDING')",file);
            return new FileReplicaCatalog.PeerRevision(file,1,owner,biz,key,finalKey,sha,size,"image/png");
        }
        void exchange() throws Exception {
            for(int i=0;i<12;i++) {campus.worker.runOnce();external.worker.runOnce();network.runOnce();campus.drain();external.drain();Thread.sleep(25);}
        }
        @Override public void close() {client.close();server.close();}
    }
    static DeliveryService delivery(Side s) { return new DeliveryService(s.sql(),s.db.transactions(),new EventCodec()); }
    static EventFoundationTestSupport.Database database(SiteProperties.Mode mode,String id) {
        var p=new SiteProperties();p.setMode(mode);p.setId(id);p.setInstanceId(UUID.randomUUID());return EventFoundationTestSupport.createDatabase(PG,p);
    }
}
