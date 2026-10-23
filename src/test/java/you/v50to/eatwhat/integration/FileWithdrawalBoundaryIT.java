package you.v50to.eatwhat.integration;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.springframework.test.util.ReflectionTestUtils;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.file.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import you.v50to.eatwhat.service.storage.replication.*;
import you.v50to.eatwhat.config.FileReplicationProperties;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FileWithdrawalBoundaryIT {
 static GenericContainer<?> pg;
 @BeforeAll static void start(){pg=IsolatedContainers.startedPostgresWithInspectRetry("file-withdraw-boundary");}
 @AfterAll static void stop(){if(pg!=null)pg.stop();}
 @Test void externalWithdrawalStopsUrlOutputWithoutDeletingUnarchivedBytesOrIdentity() throws Exception {
  var p=RelationReplicationIT.pair(pg);var side=p.b();var db=side.db();fileGrants(db);
  UUID owner=db.admin().queryForObject("SELECT subject_id FROM eatwhat_consent.subject WHERE user_id=?",UUID.class,p.actor());
  UUID file=UUID.randomUUID();String key="avatar/"+p.actor()+"/"+file;String physical="verified/"+file;
  db.admin().update("INSERT INTO eatwhat_file.file_object(file_id,owner_gid,biz,logical_key) VALUES(?,?,'avatar',?)",file,owner,key);
  db.admin().update("INSERT INTO eatwhat_file.file_revision(file_id,revision,final_key,sha256,size_bytes,content_type,state,verified_at) VALUES(?,1,?,?,12,'image/png','VERIFIED',now())",file,physical,"a".repeat(64));
  db.admin().update("INSERT INTO eatwhat_file.file_key_alias(logical_key,file_id,revision,owner_gid,biz) VALUES(?,?,1,?,'avatar')",key,file,owner);
  db.admin().update("INSERT INTO eatwhat_file.file_replica(file_id,revision,site,object_key,state) VALUES(?,1,'LOCAL',?,'AVAILABLE_LOCAL')",file,physical);
  var resolver=new FileUrlResolver(db.runtime(),new FileReplicationProperties(),mock(ReplicationConsentGate.class));
  ReflectionTestUtils.setField(resolver,"multiwriter",true);
  assertEquals(physical,resolver.describe(key).finalKey());
  db.admin().update("UPDATE eatwhat_consent.subject SET status='WITHDRAWN' WHERE user_id=?",p.actor());
  assertNull(resolver.describe(key).finalKey());assertFalse(resolver.describe(key).cdnEligible());
  var properties=new you.v50to.eatwhat.config.S3StorageProperties();properties.setProvider(you.v50to.eatwhat.config.S3StorageProperties.Provider.RUSTFS);
  properties.setEndpoint("http://127.0.0.1:9000");properties.setRegion("us-east-1");properties.setBucket("isolated-test");
  properties.setAccessKey(IsolatedContainers.RUSTFS_ACCESS_KEY);properties.setSecretKey(IsolatedContainers.RUSTFS_SECRET_KEY);
  try(var storage=new you.v50to.eatwhat.service.storage.S3CompatibleStorageService(properties,new you.v50to.eatwhat.service.storage.FileAliasResolver(db.runtime()))) {
   storage.setFileUrlResolver(resolver);assertNull(storage.signGetUrl(key),"Actual URL signer must honor withdrawal, even if the local replica remains available");
  }
  assertEquals("AVAILABLE_LOCAL",db.admin().queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL'",String.class,file));
 }
 @Test void erasedReceiptDoesNotStrandThePublisherAndLateAckCannotRestorePublication() throws Exception {
  var p=RelationReplicationIT.pair(pg);var side=p.b();var db=side.db();fileGrants(db);
  var local=new NodeIdentity(db.site().getId(),db.site().getInstanceId());var peer=new NodeIdentity(p.a().db().site().getId(),p.a().db().site().getInstanceId());
  UUID original=UUID.randomUUID(),file=UUID.randomUUID();String hash="b".repeat(64);
  db.admin().update("INSERT INTO eatwhat_sync.purged_personal_event(event_id,envelope_hash) VALUES(?,?)",original,hash);
  db.admin().update("INSERT INTO eatwhat_file.file_sync_receipt(event_id,envelope_hash,file_id,revision,outcome) VALUES(?,?,?,1,'APPLIED')",original,hash,file);
  var worker=new FileSyncWorker(db.runtime(),db.transactions(),new OutboxService(db.runtime(),db.transactions(),new EventCodec(),db.site()),side.consent(),local,null,16);
  var membership=new you.v50to.eatwhat.cluster.ClusterMembershipView(db.runtime());ReflectionTestUtils.setField(membership,"bound",true);ReflectionTestUtils.setField(worker,"membership",membership);
  worker.runOnce();
  assertTrue(db.admin().queryForObject("SELECT published FROM eatwhat_file.file_sync_receipt WHERE event_id=?",Boolean.class,original));
  assertEquals(0,db.admin().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox",Integer.class));
  var handler=new FileEventHandler(FileEvents.RECEIPT,db.runtime(),side.consent(),new FileReplicaCatalog(db.runtime()),new FilePublicationService(db.runtime()),peer,1024,db.site().getMode());
  var applied=new FileEvents.Applied(original,hash,file,1,"APPLIED");
  handler.apply(FileEvents.event(FileEvents.RECEIPT,peer,original,1,1,null,applied));
  assertEquals(0,db.admin().queryForObject("SELECT count(*) FROM eatwhat_file.file_sync_ack",Integer.class));
  var forged=new FileEvents.Applied(original,"c".repeat(64),file,1,"APPLIED");
  assertThrows(IllegalArgumentException.class,()->handler.apply(FileEvents.event(FileEvents.RECEIPT,peer,original,1,1,null,forged)));
 }
 static void fileGrants(EventFoundationTestSupport.Database db) {
  org.flywaydb.core.Flyway.configure().dataSource(db.url(),IsolatedContainers.USER,IsolatedContainers.PASSWORD)
   .callbacks(new you.v50to.eatwhat.config.FileSchemaMigrationCallback(db.runtimeUsername())).load().migrate();
 }

}
