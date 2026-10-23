package you.v50to.eatwhat.integration;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.sync.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class InboxRetentionIT {
 static GenericContainer<?> pg;
 @BeforeAll static void start(){pg=IsolatedContainers.startedPostgresWithInspectRetry("inbox-retention");}
 @AfterAll static void stop(){if(pg!=null)pg.stop();}
 @Test void withdrawnNeverReceivedEnvelopeIsRejectedBeforePayloadPersistence() throws Exception {
  var pair=RelationReplicationIT.pair(pg);var sender=BrowseReplicationIT.site(pair.a());var receiver=BrowseReplicationIT.site(pair.b());
  assertTrue(sender.commands().record(pair.actor(),"food",pair.food()));
  var event=BrowseReplicationIT.events(sender).getFirst();
  receiver.db().admin().execute("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true");
  receiver.db().admin().update("UPDATE eatwhat_consent.subject SET status='WITHDRAWN',identity_ready=false WHERE user_id=?",pair.actor());
  assertThrows(IllegalArgumentException.class,()->receiver.inbox().receive(event,event.originSite()));
  assertEquals(0,receiver.db().admin().queryForObject("SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=?",Integer.class,event.eventId()));
 }
 @Test void purgedEnvelopeCanBeAcknowledgedWithoutRestoringPayloadButChangedHashIsRejected() throws Exception {
  var pair=RelationReplicationIT.pair(pg);var sender=BrowseReplicationIT.site(pair.a());var receiver=BrowseReplicationIT.site(pair.b());
  assertTrue(sender.commands().record(pair.actor(),"food",pair.food()));
  var event=BrowseReplicationIT.events(sender).getFirst();
  receiver.db().admin().update("INSERT INTO eatwhat_sync.purged_personal_event(event_id,envelope_hash) VALUES(?,?)",event.eventId(),new EventCodec().encode(event).hash());
  assertEquals(InboxService.ReceiptStatus.ALREADY_APPLIED,receiver.inbox().receive(event,event.originSite()).receiptStatus());
  var altered=new SyncEvent(event.eventId(),event.originSite(),event.originInstanceId(),event.aggregateType(),event.aggregateId(),event.numericId(),event.eventType(),event.schemaVersion(),event.baseVersion(),event.nextVersion(),event.dependencies(),event.actorId(),event.commandKey(),UUID.randomUUID(),event.payloadJson());
  assertThrows(IllegalArgumentException.class,()->receiver.inbox().receive(altered,altered.originSite()));
  assertEquals(0,receiver.db().admin().queryForObject("SELECT count(*) FROM eatwhat_sync.inbox WHERE event_id=?",Integer.class,event.eventId()));
 }
}
