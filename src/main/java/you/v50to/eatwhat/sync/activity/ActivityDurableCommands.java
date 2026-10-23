package you.v50to.eatwhat.sync.activity;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;

/** Business mutation and durable intent commit together; a bounded restartable scan also captures legacy source rows. */
public final class ActivityDurableCommands {
    private final JdbcTemplate jdbc;private final SyncTransactions tx;private final OutboxService outbox;
    private final ConsentStore consent;private final SiteProperties site;private final String peer;private final int batch;
    private final AtomicBoolean active=new AtomicBoolean();
    public ActivityDurableCommands(JdbcTemplate jdbc,SyncTransactions tx,OutboxService outbox,ConsentStore consent,SiteProperties site,String peer,int batch){
        this.jdbc=jdbc;this.tx=tx;this.outbox=outbox;this.consent=consent;this.site=site;this.peer=peer;this.batch=batch;
        tx.requireSameDataSource(jdbc);if(batch<1||batch>1000)throw new IllegalArgumentException("ACTIVITY_BATCH");
    }
    public void capture(String table,long id){
        if(!ActivityEvents.supportsTable(table))throw new IllegalArgumentException("ACTIVITY_TABLE");
        tx.mandatory(()->{jdbc.update("""
            INSERT INTO eatwhat_sync.activity_intent(table_name,object_gid,numeric_id,author_gid,deleted_at)
            SELECT ?,i.global_id,a.id,u.global_id,(extract(epoch FROM a.deleted_at)*1000)::bigint
            FROM public.%s a JOIN eatwhat_identity.entity_identity i ON i.entity_type=? AND i.numeric_id=a.id
            JOIN eatwhat_identity.entity_identity u ON u.entity_type='users' AND u.numeric_id=a.account_id
            WHERE a.id=? ON CONFLICT(table_name,object_gid) DO UPDATE SET
              deleted_at=coalesce(activity_intent.deleted_at,excluded.deleted_at),
              revision=activity_intent.revision+CASE WHEN activity_intent.deleted_at IS NULL AND excluded.deleted_at IS NOT NULL THEN 1 ELSE 0 END
            """.formatted(table),table,table,id);return null;});
    }
    private void seed(){
        for(String table:ActivityEvents.TABLES)tx.required(()->{
            var ids=jdbc.queryForList("""
                SELECT a.id FROM public.%s a JOIN eatwhat_identity.entity_identity i ON i.entity_type=? AND i.numeric_id=a.id
                LEFT JOIN eatwhat_sync.activity_intent v ON v.table_name=? AND v.object_gid=i.global_id
                WHERE i.origin_site IS NOT NULL AND (v.object_gid IS NULL OR v.deleted_at IS NULL AND a.deleted_at IS NOT NULL)
                ORDER BY a.id LIMIT ?
                """.formatted(table),Long.class,table,table,batch);
            for(long id:ids)capture(table,id);return null;});
    }
    public int runOnce(){
        if(Thread.currentThread().isInterrupted()||!active.compareAndSet(false,true))return 0;
        try{
            seed();int sent=0;
            var candidates=jdbc.queryForList("""
                SELECT v.table_name,v.numeric_id FROM eatwhat_sync.activity_intent v
                JOIN eatwhat_consent.subject s ON s.subject_id=v.author_gid JOIN public.users u ON u.id=s.user_id
                WHERE s.status='GRANTED' AND s.identity_ready AND s.withdrawal_request_id IS NULL AND (NOT u.banned OR v.deleted_at IS NOT NULL)
                AND (v.published_revision<v.revision OR v.published_epoch<>s.epoch)
                ORDER BY v.attempted_at NULLS FIRST,v.table_name,v.numeric_id LIMIT ?
                """,batch);
            for(var row:candidates){if(Thread.currentThread().isInterrupted())break;
                try{if(publish((String)row.get("table_name"),((Number)row.get("numeric_id")).longValue()))sent++;}
                catch(RuntimeException retry){tx.required(()->{jdbc.update("UPDATE eatwhat_sync.activity_intent SET attempted_at=clock_timestamp() WHERE table_name=? AND numeric_id=?",row.get("table_name"),row.get("numeric_id"));return null;});}
            }return sent;
        }finally{active.set(false);}
    }
    public boolean publish(String table,long id){return tx.required(()->{
        if(!ActivityEvents.supportsTable(table))throw new IllegalArgumentException("ACTIVITY_TABLE");
        var owners=jdbc.queryForList("SELECT account_id FROM public."+table+" WHERE id=?",Long.class,id);if(owners.isEmpty())return false;
        long owner=owners.getFirst();jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE user_id=? FOR SHARE",r->{},owner);
        var grant=consent.get(owner);if(grant==null)return false;
        var intents=jdbc.queryForList("SELECT * FROM eatwhat_sync.activity_intent WHERE table_name=? AND numeric_id=? FOR UPDATE",table,id);if(intents.isEmpty())return false;
        var intent=intents.getFirst();long rev=((Number)intent.get("revision")).longValue();
        boolean removal=intent.get("deleted_at")!=null;
        if(!(removal?consent.removalReplicationAllowed(owner):consent.replicationAllowed(owner)))return false;
        if(((Number)intent.get("published_epoch")).longValue()==grant.epoch()&&((Number)intent.get("published_revision")).longValue()==rev)return false;
        var rows=jdbc.queryForList("SELECT a.*,i.global_id,i.origin_site,(extract(epoch FROM a.created_at)*1000)::bigint AS at FROM public."+table+" a JOIN eatwhat_identity.entity_identity i ON i.entity_type=? AND i.numeric_id=a.id WHERE a.id=? FOR SHARE OF a",table,id);
        if(rows.isEmpty())return false;var a=rows.getFirst();
        var pictures=jdbc.queryForList("SELECT unnest(picture_url) FROM public."+table+" WHERE id=?",String.class,id);
        UUID activityGid=null;String extra=null;
        if(ActivityEvents.POSTS.equals(table)){
            activityGid=jdbc.queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='activities' AND numeric_id=?",UUID.class,a.get("activity_id"));
            extra=EventCodec.canonicalJson(jdbc.queryForObject("SELECT extra::text FROM public.activity_posts WHERE id=?",String.class,id));
        }
        var created=new ActivityEvents.Created(id,(UUID)a.get("global_id"),(String)a.get("origin_site"),grant.subjectId(),
                (String)a.get("food_name"),(String)a.get("description"),(Integer)a.get("province_id"),(Integer)a.get("city_id"),pictures,((Number)a.get("at")).longValue(),
                activityGid,(String)a.get("title"),extra);
        String contentHash=EventCodec.sha256(ActivityEvents.createdPayload(table,created));
        jdbc.update("INSERT INTO eatwhat_sync.activity_source_proof(table_name,object_gid,author_gid,origin_site,content_sha256) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",table,created.globalId(),created.authorGlobalId(),created.originSite(),contentHash);
        var proof=jdbc.queryForMap("SELECT content_sha256,author_gid,origin_site FROM eatwhat_sync.activity_source_proof WHERE table_name=? AND object_gid=?",table,created.globalId());
        if(!created.authorGlobalId().equals(proof.get("author_gid"))||!created.originSite().equals(proof.get("origin_site")))throw new IllegalStateException("ACTIVITY_SOURCE_PROOF_MISMATCH");
        created=canonicalCreated(table,created,(String)proof.get("content_sha256"));
        Long deleted=intent.get("deleted_at")==null?null:((Number)intent.get("deleted_at")).longValue();
        var op=new ActivityStateEvents.Snapshot(table,created,grant.epoch(),deleted);
        UUID eventId=UUID.nameUUIDFromBytes((site.getId()+":"+table+":"+created.globalId()+":"+grant.epoch()+":"+rev).getBytes(StandardCharsets.UTF_8));
        var event=new SyncEvent(eventId,site.getId(),site.getInstanceId(),ActivityStateEvents.AGGREGATE,eventId,
                jdbc.queryForObject("SELECT eatwhat_sync.next_relation_number()",Long.class),ActivityStateEvents.EVENT,1,
                VersionVector.empty(),VersionVector.empty().increment(site.getId()),List.of(new SyncEvent.Dependency("users",grant.subjectId())),
                grant.subjectId(),"activity-state:"+eventId,eventId,ActivityStateEvents.payload(op));
        new ActivityStateEvents(consent).validate(event,new you.v50to.eatwhat.sync.transport.NodeIdentity(site.getId(),site.getInstanceId()));
        outbox.append(event,you.v50to.eatwhat.cluster.ClusterRouting.legacyRecipients(site,peer));
        jdbc.update("UPDATE eatwhat_sync.activity_intent SET published_revision=?,published_epoch=?,attempted_at=clock_timestamp() WHERE table_name=? AND numeric_id=?",rev,grant.epoch(),table,id);
        return true;
    });}

    /** Recover only a one-millisecond timestamp conversion drift, with the complete immutable content hash as proof. */
    static ActivityEvents.Created canonicalCreated(String table,ActivityEvents.Created current,String expectedHash) {
        for(long offset:new long[]{0,-1,1}) {
            var candidate=new ActivityEvents.Created(current.numericId(),current.globalId(),current.originSite(),current.authorGlobalId(),
                    current.foodName(),current.description(),current.provinceId(),current.cityId(),current.pictures(),current.createdAt()+offset,
                    current.activityGlobalId(),current.title(),current.extraJson());
            if(EventCodec.sha256(ActivityEvents.createdPayload(table,candidate)).equals(expectedHash))return candidate;
        }
        throw new IllegalStateException("ACTIVITY_SOURCE_PROOF_MISMATCH");
    }
}
