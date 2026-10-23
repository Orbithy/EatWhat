package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.SiteProperties;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BusinessPayloadErasureIT {
 private static final GenericContainer<?> POSTGRES=IsolatedContainers.postgres("business-erasure");
 @BeforeAll static void start(){POSTGRES.start();}
 @AfterAll static void stop(){POSTGRES.stop();}
 private EventFoundationTestSupport.Database database(){
  var p=new SiteProperties();p.setMode(SiteProperties.Mode.EXTERNAL);p.setId("external-erasure");p.setInstanceId(UUID.randomUUID());
  return EventFoundationTestSupport.createDatabase(POSTGRES,p);
 }
 private UUID subject(EventFoundationTestSupport.Database db,String status,long epoch){
  UUID id=UUID.randomUUID();
  db.admin().update("INSERT INTO eatwhat_consent.subject(subject_id,user_id,origin_site,epoch,status,policy_version,backfill_status) VALUES(?,7,'campus',?,?,'test','DONE')",id,epoch,status);
  db.admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true");return id;
 }
 private boolean blocked(EventFoundationTestSupport.Database db,String aggregate,String payload){return db.admin().queryForObject("SELECT eatwhat_sync.personal_event_retention_blocked(jsonb_build_object('aggregateType',?::text,'payloadJson',?::jsonb))",Boolean.class,aggregate,payload);}
 @Test void withdrawalAndStaleEpochsCannotBeStoredButCurrentRegrantCan(){
  var db=database();UUID subject=subject(db,"WITHDRAWN",2);
  assertTrue(blocked(db,"profile.operation","{\"operation\":{\"subject\":\""+subject+"\",\"epoch\":1}}"));
  assertTrue(blocked(db,"catalog.operation","{\"consentEpochs\":[{\"subjectGid\":\""+subject+"\",\"epoch\":1}]}"));
  assertFalse(blocked(db,"catalog.operation","{\"consentEpochs\":[]}"));
  db.admin().update("INSERT INTO eatwhat_account_multiwriter.operation(event_id,subject_id,field,value,source_site,source_instance,actor_id) VALUES(?,?,'consent','WITHDRAWN','campus',?,7)",UUID.randomUUID(),subject,UUID.randomUUID());
  db.admin().update("UPDATE eatwhat_consent.subject SET status='GRANTED',epoch=3 WHERE subject_id=?",subject);
  assertTrue(blocked(db,"activity_foods","{\"author_global_id\":\""+subject+"\"}"));
  assertTrue(blocked(db,"activity_state","{\"created\":{\"author_global_id\":\""+subject+"\"},\"epoch\":1}"));
  assertFalse(blocked(db,"activity_state","{\"created\":{\"author_global_id\":\""+subject+"\"},\"epoch\":3}"));
  db.admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=false");
  assertFalse(blocked(db,"activity_foods","{\"author_global_id\":\""+subject+"\"}"));
 }
 @Test void firstWithdrawnSubjectCreationSerializesBeforePersonalEnvelopeRetention() throws Exception {
  var db=database();UUID id=UUID.randomUUID();db.admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true");
  try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
   try(var connection=db.admin().getDataSource().getConnection()){
    connection.setAutoCommit(false);
    try(var statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))")){statement.setString(1,id.toString());statement.execute();}
    var started=new java.util.concurrent.CountDownLatch(1);
    var incoming=pool.submit(()->{started.countDown();return blocked(db,"profile.operation","{\"subject\":\""+id+"\",\"epoch\":1}");});
    assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
    assertThrows(java.util.concurrent.TimeoutException.class,()->incoming.get(150,java.util.concurrent.TimeUnit.MILLISECONDS),"Unknown subjects must share the account creation fence");
    try(var statement=connection.prepareStatement("INSERT INTO eatwhat_consent.subject(subject_id,user_id,origin_site,epoch,status,policy_version,backfill_status) VALUES(?,7,'campus',2,'WITHDRAWN','test','DONE')")){statement.setObject(1,id);statement.executeUpdate();}
    connection.commit();assertTrue(incoming.get(5,java.util.concurrent.TimeUnit.SECONDS));
   }
  }
 }
 @Test void preciseOwnerErasureKeepsOnlyHashesAndCannotBeCalledByRuntime(){
  var db=database();UUID subject=subject(db,"WITHDRAWN",2);UUID event=UUID.randomUUID();UUID other=UUID.randomUUID();
  for(UUID id:new UUID[]{event,other})db.admin().update("INSERT INTO eatwhat_sync.inbox(event_id,envelope,envelope_hash,origin_site,aggregate_type,aggregate_id,numeric_id) VALUES(?,jsonb_build_object('aggregateType','profile.operation','payloadJson',jsonb_build_object('subject',?::text,'epoch',1,'private','personal-body')),?,'campus','profile.operation',?,7)",id,(id.equals(event)?subject:other),"a".repeat(64),id);
  db.admin().update("INSERT INTO eatwhat_sync.profile_authorization_review(event_id,subject_gid,actor_gid,operation,reason) VALUES(?,?,?,'{\"private\":\"personal-body\"}','KNOWN_REVOKED')",event,subject,other);
  db.admin().update("INSERT INTO eatwhat_sync.relation_state(relation_id) VALUES(?)",event);
  db.admin().update("INSERT INTO eatwhat_sync.relation_operation(event_id,relation_id,dot_site,dot_counter,context,adding,payload) VALUES(?,?,'campus',1,'{}',true,jsonb_build_object('actor',?::text,'owner',?::text))",event,event,subject,other);
  assertThrows(org.springframework.dao.DataAccessException.class,()->db.runtime().queryForObject("SELECT eatwhat_sync.erase_withdrawn_business_payload(?)",Boolean.class,subject));
  assertTrue(db.admin().queryForObject("SELECT eatwhat_sync.erase_withdrawn_business_payload(?)",Boolean.class,subject));
  assertEquals(1,db.admin().queryForObject("SELECT count(*) FROM eatwhat_sync.inbox",Integer.class));
  assertEquals("a".repeat(64),db.admin().queryForObject("SELECT envelope_hash FROM eatwhat_sync.purged_personal_event WHERE event_id=?",String.class,event));
  assertEquals(0,db.admin().queryForObject("SELECT count(*) FROM eatwhat_sync.profile_authorization_review",Integer.class));
  assertEquals(0,db.admin().queryForObject("SELECT count(*) FROM eatwhat_sync.relation_operation",Integer.class));
  assertThrows(org.springframework.dao.DataAccessException.class,()->db.runtime().update("INSERT INTO eatwhat_sync.purged_personal_event VALUES(?,?)",UUID.randomUUID(),"b".repeat(64)));
  assertTrue(db.admin().queryForObject("SELECT eatwhat_sync.erase_withdrawn_business_payload(?)",Boolean.class,subject));
 }
}
