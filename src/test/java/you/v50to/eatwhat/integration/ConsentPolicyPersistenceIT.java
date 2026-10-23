package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.account.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.dto.SaveConsentPolicyDTO;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real restricted runtime roles, two databases, production migrations and durable Outbox/Inbox. */
class ConsentPolicyPersistenceIT {
    private static final GenericContainer<?> PG=IsolatedContainers.postgres("policy-config");
    private static final EventCodec CODEC=new EventCodec();
    private static final String INITIAL=PreferenceConsentPolicy.VERSION;
    @BeforeAll static void start(){PG.start();}
    @AfterAll static void stop(){PG.stop();}
    record Node(EventFoundationTestSupport.Database db,ConsentPolicyService policies,InboxService inbox,ConsentPolicyEvents events,long administrator){}
    record Pair(Node a,Node b){}
    private Node node(EventFoundationTestSupport.Database db,NodeIdentity authority,String peer,OutboxService outbox){
        var service=new ConsentPolicyService(db.runtime(),db.transactions(),outbox,db.site(),INITIAL,()->authority,List.of(peer));
        var events=new ConsentPolicyEvents(service);
        var admins=db.admin().queryForList("SELECT id FROM users WHERE nick_name='policy-admin'",Long.class);
        long administrator=admins.isEmpty()?db.admin().queryForObject("INSERT INTO users(nick_name,password_hash,role) VALUES('policy-admin','synthetic','admin') RETURNING id",Long.class):admins.getFirst();
        return new Node(db,service,new InboxService(db.runtime(),db.transactions(),CODEC,new EventHandlerRegistry(List.of(events))),events,administrator);
    }
    private Pair pair(){
        var campus=new SiteProperties();campus.setMode(SiteProperties.Mode.CAMPUS);campus.setId("campus-test");campus.setInstanceId(UUID.randomUUID());
        var external=new SiteProperties();external.setMode(SiteProperties.Mode.EXTERNAL);external.setId("external-test");external.setInstanceId(UUID.randomUUID());
        var a=EventFoundationTestSupport.createDatabase(PG,campus);var b=EventFoundationTestSupport.createDatabase(PG,external);
        var authority=new NodeIdentity(campus.getId(),campus.getInstanceId());
        return new Pair(node(a,authority,external.getId(),new OutboxService(a.runtime(),a.transactions(),CODEC,campus)),
                node(b,authority,campus.getId(),new OutboxService(b.runtime(),b.transactions(),CODEC,external)));
    }
    private List<SyncEvent> events(Node node){return node.db.runtime().query("SELECT envelope::text FROM eatwhat_sync.outbox ORDER BY local_order",(r,n)->CODEC.decode(r.getString(1)));}
    private void receive(Node node,SyncEvent event){
        node.events.validate(event,new NodeIdentity(event.originSite(),event.originInstanceId()));
        node.inbox.receive(event,event.originSite());
        for(var lease:node.inbox.claim(20,Duration.ofSeconds(30)))assertEquals(InboxService.InboxStatus.APPLIED,node.inbox.process(lease).status());
    }
    private void save(Node node,String version,String url,long revision){node.policies.save(new SaveConsentPolicyDTO(version,url,revision),node.administrator);}

    @Test void editsPersistAcrossRestartAndBothSitesConvergeEvenWithOutOfOrderAndDuplicateDelivery(){
        var p=pair();save(p.a,"v4","https://example.com/v4.html",0);save(p.a,"v5","/api/policies/v5.html",1);
        assertEquals(2,p.a.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.delivery WHERE status='READY'",Integer.class));
        var sent=events(p.a);receive(p.b,sent.getLast());receive(p.b,sent.getFirst());receive(p.b,sent.getLast());
        assertEquals(p.a.policies.settings(),p.b.policies.settings());
        var restarted=node(p.b.db,p.a.policies.authority(),p.a.db.site().getId(),new OutboxService(p.b.db.runtime(),p.b.db.transactions(),CODEC,p.b.db.site()));
        assertEquals("v5",restarted.policies.current().version());assertEquals("/api/policies/v5.html",restarted.policies.current().url());
        assertTrue(events(p.b).isEmpty(),"Inbound policy changes must not echo into the Outbox");
        assertEquals(2,p.b.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.consent_policy_history",Integer.class));
        assertFalse(p.a.db.runtime().queryForObject("SELECT has_table_privilege(current_user,'eatwhat_sync.consent_policy_history','UPDATE,DELETE')",Boolean.class));
    }
    @Test void replicaWritesStaleSavesUnsafeUrlsAndRetiredVersionsAreRejected(){
        var p=pair();assertThrows(BizException.class,()->save(p.b,"v4","/v4.html",0));
        for(String url:List.of("javascript:alert(1)","//example.com/a","https://user:pass@example.com/a","http://example.com/a","/a\\b"," /a","/a\nb"))
            assertThrows(BizException.class,()->save(p.a,"v4",url,0));
        save(p.a,"v4","/v4.html",0);
        assertThrows(BizException.class,()->save(p.a,"v5","/v5.html",0));
        assertThrows(BizException.class,()->save(p.a,INITIAL,"/old.html",1));
        save(p.a,"v5","/v5.html",1);
        assertThrows(BizException.class,()->save(p.a,"v4","/v4.html",2));
        save(p.a,"v5","/relocated-v5.html",2);
        assertEquals(3,p.a.policies.settings().revision());
        p.a.db.admin().update("UPDATE users SET role='user' WHERE id=?",p.a.administrator);
        assertThrows(BizException.class,()->save(p.a,"v6","/v6.html",3));
        assertEquals(3,p.a.policies.settings().revision(),"A stale cached role cannot authorize a database policy edit");
    }
    @Test void outboxFailureRollsBackTheConfigurationAndAuditAtomically(){
        var p=pair();
        // The real Outbox rejects a local delivery target after policy/audit writes.
        var broken=node(p.a.db,p.a.policies.authority(),p.a.db.site().getId(),
                new OutboxService(p.a.db.runtime(),p.a.db.transactions(),CODEC,p.a.db.site()));
        assertThrows(IllegalArgumentException.class,()->save(broken,"v4","/v4.html",0));
        assertEquals(0,p.a.policies.settings().revision());
        assertEquals(0,p.a.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.consent_policy_history",Integer.class));
    }
    @Test void concurrentAdministrativeSavesCannotSilentlyOverwriteEachOther()throws Exception{
        var p=pair();var ready=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var tasks=new ArrayList<Future<Boolean>>();
            for(String version:List.of("v4a","v4b"))tasks.add(pool.submit(()->{ready.await();try{save(p.a,version,"/"+version+".html",0);return true;}catch(BizException conflict){return false;}}));
            ready.countDown();int accepted=0;for(var task:tasks)if(task.get(30,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);assertEquals(1,p.a.policies.settings().revision());assertEquals(1,events(p.a).size());
        }
    }
    @Test void changedPolicyImmediatelyDrivesGrantValidationAndOldConsentIsNotUpgraded(){
        var p=pair();var db=p.a.db;
        var consent=new ConsentStore(db.runtime(),db.transactions(),db.site(),INITIAL,Duration.ofMinutes(5),Duration.ofMinutes(5),p.b.db.site().getId(),p.b.db.site().getInstanceId());
        consent.setPolicySettings(p.a.policies);
        long user=db.runtime().queryForObject("INSERT INTO users(nick_name,password_hash) VALUES('policy-user','synthetic') RETURNING id",Long.class);
        consent.onRegistered(user,false,null);
        save(p.a,"v4","/v4.html",0);
        assertEquals(INITIAL,consent.configuredPolicyVersion());assertEquals("v4",consent.policyVersion());
        assertEquals("POLICY_CHANGED",assertThrows(ConsentStore.ConsentException.class,()->consent.grant(user,INITIAL)).code());
        assertEquals(INITIAL,consent.get(user).policyVersion());
        assertEquals("v4",consent.grant(user,"v4").policyVersion());
        assertEquals("v4",consent.get(user).policyVersion());
        var event=events(p.a).getFirst();
        assertThrows(IllegalArgumentException.class,()->p.b.events.validate(event,new NodeIdentity(p.b.db.site().getId(),p.b.db.site().getInstanceId())));
    }
}
