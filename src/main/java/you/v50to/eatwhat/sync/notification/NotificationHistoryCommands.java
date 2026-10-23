package you.v50to.eatwhat.sync.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;

/** Stable source identities make historical publication restartable without inventing old relation operations. */
public final class NotificationHistoryCommands {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final SyncTransactions tx;private final OutboxService outbox;private final ConsentStore consent;
    private final SiteProperties site;private final String peer;private final int batch;private final AtomicBoolean active=new AtomicBoolean();private long scanCursor;
    public NotificationHistoryCommands(JdbcTemplate jdbc,SyncTransactions tx,OutboxService outbox,ConsentStore consent,SiteProperties site,String peer,int batch){if(batch<1||batch>1000)throw new IllegalArgumentException("NOTICE_BATCH_SIZE");this.jdbc=jdbc;this.tx=tx;this.outbox=outbox;this.consent=consent;this.site=site;this.peer=peer;this.batch=batch;tx.requireSameDataSource(jdbc);}
    public void captureRead(long recipient,Collection<Long> ids){tx.mandatory(()->{
        for(long id:ids){seed(id);jdbc.update("""
            INSERT INTO eatwhat_sync.notification_history_read(notification_gid,recipient_gid,read_at)
            SELECT h.notification_gid,h.recipient_gid,(extract(epoch FROM clock_timestamp())*1000)::bigint
            FROM eatwhat_sync.notification_history h JOIN public.notifications n ON n.id=h.numeric_id
            WHERE n.id=? AND n.account_id=? ON CONFLICT DO NOTHING
            """,id,recipient);}
        return null;});}
    private void seed(long id){
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.relation_notification WHERE numeric_id=?)",Boolean.class,id)))return;
        var rows=jdbc.queryForList("""
            SELECT n.*,ni.global_id AS gid,ni.origin_site,ai.global_id AS actor_gid,ri.global_id AS recipient_gid,
              ti.global_id AS target_gid,(extract(epoch FROM n.created_at)*1000)::bigint AS at,
              (extract(epoch FROM n.expires_at)*1000)::bigint AS expires,
              (extract(epoch FROM n.read_at)*1000)::bigint AS read
            FROM public.notifications n LEFT JOIN eatwhat_identity.entity_identity ni ON ni.entity_type='notifications' AND ni.numeric_id=n.id
            LEFT JOIN eatwhat_identity.entity_identity ai ON ai.entity_type='users' AND ai.numeric_id=n.actor_id
            LEFT JOIN eatwhat_identity.entity_identity ri ON ri.entity_type='users' AND ri.numeric_id=n.account_id
            LEFT JOIN eatwhat_identity.entity_identity ti ON ti.entity_type=CASE n.target_type WHEN 'user' THEN 'users' WHEN 'food' THEN 'activity_foods' WHEN 'post' THEN 'activity_posts' ELSE 'activity_dinners' END AND ti.numeric_id=n.target_id
            WHERE n.id=?
            """,id);
        if(rows.isEmpty())return;var row=rows.getFirst();
        if(row.get("gid")==null||row.get("actor_gid")==null||row.get("recipient_gid")==null||row.get("target_gid")==null){issue(id,"STABLE_SOURCE_IDENTITY_MISSING");return;}
        if(row.get("origin_site")==null){issue(id,"SOURCE_ORIGIN_UNASSIGNED");return;}
        if(!site.getId().equals(row.get("origin_site")))return;
        var n=new NotificationHistoryEvents.Notice(id,(UUID)row.get("gid"),(String)row.get("origin_site"),(UUID)row.get("actor_gid"),(UUID)row.get("recipient_gid"),(UUID)row.get("target_gid"),(String)row.get("type"),(String)row.get("target_type"),((Number)row.get("at")).longValue(),((Number)row.get("expires")).longValue());
        try{NotificationHistoryEvents.parse(NotificationHistoryEvents.payload(new NotificationHistoryEvents.Operation("PUBLISH",n,1,1,null)));}
        catch(IllegalArgumentException malformed){issue(id,"SOURCE_NOTICE_INVALID");return;}
        jdbc.update("INSERT INTO eatwhat_sync.notification_history(notification_gid,numeric_id,origin_site,actor_gid,recipient_gid,payload) VALUES(?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",n.gid(),id,n.origin(),n.actor(),n.recipient(),NotificationHistoryEvents.notice(n));
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.notification_history WHERE notification_gid=? AND payload=?::jsonb)",Boolean.class,n.gid(),NotificationHistoryEvents.notice(n))))throw new IllegalStateException("NOTICE_SOURCE_COLLISION");
        if(row.get("read")!=null)jdbc.update("INSERT INTO eatwhat_sync.notification_history_read(notification_gid,recipient_gid,read_at) VALUES(?,?,?) ON CONFLICT DO NOTHING",n.gid(),n.recipient(),row.get("read"));
    }
    private void issue(long id,String reason){jdbc.update("INSERT INTO eatwhat_sync.notification_history_issue(numeric_id,reason) VALUES(?,?) ON CONFLICT DO NOTHING",id,reason);}
    public List<Map<String,Object>> migrationIssues(){return jdbc.queryForList("SELECT i.numeric_id,i.reason FROM eatwhat_sync.notification_history_issue i WHERE NOT EXISTS(SELECT 1 FROM eatwhat_sync.notification_history h WHERE h.numeric_id=i.numeric_id) ORDER BY i.numeric_id LIMIT 100");}
    public int runOnce(){if(Thread.currentThread().isInterrupted()||!active.compareAndSet(false,true))return 0;
        try{
            tx.required(()->{var ids=jdbc.queryForList("""
                SELECT n.id FROM public.notifications n LEFT JOIN eatwhat_sync.notification_history h ON h.numeric_id=n.id
                LEFT JOIN eatwhat_sync.notification_history_read r ON r.notification_gid=h.notification_gid
                WHERE n.id>? AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.relation_notification rn WHERE rn.numeric_id=n.id)
                  AND (h.notification_gid IS NULL OR n.read_at IS NOT NULL AND r.notification_gid IS NULL)
                ORDER BY n.id LIMIT ?
                """,Long.class,scanCursor,batch);for(long id:ids)seed(id);scanCursor=ids.isEmpty()?0:ids.getLast();return null;});
            int sent=0;
            for(String action:List.of("PUBLISH","READ")){
                String table=action.equals("READ")?"notification_history_read":"notification_history";
                var ids=jdbc.queryForList("SELECT h.notification_gid FROM eatwhat_sync.notification_history h JOIN eatwhat_sync."+table+" v USING(notification_gid) JOIN eatwhat_consent.subject a ON a.subject_id=h.actor_gid JOIN eatwhat_consent.subject r ON r.subject_id=h.recipient_gid WHERE a.status='GRANTED' AND r.status='GRANTED' AND (v.published_actor_epoch<>a.epoch OR v.published_recipient_epoch<>r.epoch)"+" ORDER BY v.attempted_at NULLS FIRST,h.notification_gid LIMIT ?",UUID.class,batch);
                for(UUID id:ids)try{if(publish(action,id))sent++;}catch(RuntimeException retry){tx.required(()->{jdbc.update("UPDATE eatwhat_sync."+table+" SET attempted_at=clock_timestamp() WHERE notification_gid=?",id);return null;});}
            }return sent;
        }finally{active.set(false);}}
    public boolean publish(String action,UUID id){return tx.required(()->{
        if(!Set.of("PUBLISH","READ").contains(action))throw new IllegalArgumentException("NOTICE_ACTION");
        String table=action.equals("READ")?"notification_history_read":"notification_history";
        var rows=jdbc.queryForList("SELECT payload::text FROM eatwhat_sync.notification_history WHERE notification_gid=?",String.class,id);if(rows.isEmpty())return false;
        var n=JSON.readValue(rows.getFirst(),NotificationHistoryEvents.Notice.class);
        jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id IN (?,?) ORDER BY user_id FOR SHARE",r->{},n.actor(),n.recipient());
        var actor=consent.getBySubject(n.actor());var recipient=consent.getBySubject(n.recipient());
        if(actor==null||recipient==null||!consent.outboundAuthorization(n.actor(),actor.epoch(),n.recipient(),recipient.epoch()).allowed())return false;
        var state=jdbc.queryForMap("SELECT * FROM eatwhat_sync."+table+" WHERE notification_gid=? FOR UPDATE",id);
        if(((Number)state.get("published_actor_epoch")).longValue()==actor.epoch()&&((Number)state.get("published_recipient_epoch")).longValue()==recipient.epoch())return false;
        Long read=action.equals("READ")?((Number)state.get("read_at")).longValue():null;
        String digest=EventCodec.sha256(NotificationHistoryEvents.notice(n));
        jdbc.update("INSERT INTO eatwhat_sync.notification_source_proof(notification_gid,origin_site,content_sha256) VALUES(?,?,?) ON CONFLICT DO NOTHING",n.gid(),n.origin(),digest);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT content_sha256=? AND origin_site=? FROM eatwhat_sync.notification_source_proof WHERE notification_gid=?",Boolean.class,digest,n.origin(),n.gid())))throw new IllegalStateException("NOTICE_SOURCE_PROOF_MISMATCH");
        var op=new NotificationHistoryEvents.Operation(action,n,actor.epoch(),recipient.epoch(),read);
        UUID event=UUID.nameUUIDFromBytes((site.getId()+":notice:"+id+":"+action+":"+actor.epoch()+":"+recipient.epoch()).getBytes(StandardCharsets.UTF_8));
        var e=new SyncEvent(event,site.getId(),site.getInstanceId(),NotificationHistoryEvents.AGGREGATE,event,jdbc.queryForObject("SELECT eatwhat_sync.next_relation_number()",Long.class),NotificationHistoryEvents.EVENT,1,
            VersionVector.empty(),VersionVector.empty().increment(site.getId()),NotificationHistoryEvents.dependencies(op),action.equals("READ")?n.recipient():n.actor(),"notice-history:"+event,event,NotificationHistoryEvents.payload(op));
        new NotificationHistoryEvents(consent).validate(e,new you.v50to.eatwhat.sync.transport.NodeIdentity(site.getId(),site.getInstanceId()));outbox.append(e,you.v50to.eatwhat.cluster.ClusterRouting.legacyRecipients(site,peer));
        jdbc.update("UPDATE eatwhat_sync."+table+" SET published_actor_epoch=?,published_recipient_epoch=?,attempted_at=clock_timestamp() WHERE notification_gid=?",actor.epoch(),recipient.epoch(),id);return true;
    });}
}
