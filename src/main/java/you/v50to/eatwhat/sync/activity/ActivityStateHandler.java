package you.v50to.eatwhat.sync.activity;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.*;
import java.util.*;

/** Independent immutable snapshots converge to the existing identity and a terminal deletion. */
public final class ActivityStateHandler implements EventHandler {
    private final JdbcTemplate jdbc;private final ConsentStore consent;
    public ActivityStateHandler(JdbcTemplate jdbc,ConsentStore consent){this.jdbc=jdbc;this.consent=consent;}
    @Override public String aggregateType(){return ActivityStateEvents.AGGREGATE;}
    @Override public String eventType(){return ActivityStateEvents.EVENT;}
    @Override public int schemaVersion(){return 1;}
    private boolean stale(ActivityStateEvents.Snapshot op){var grant=consent.getBySubject(op.created().authorGlobalId());
        return grant!=null&&(grant.epoch()>op.epoch()||grant.epoch()==op.epoch()&&grant.status().equals("WITHDRAWN"));}
    @Override public boolean dependenciesSatisfied(SyncEvent event){
        new ActivityStateEvents(consent).validate(event,new you.v50to.eatwhat.sync.transport.NodeIdentity(event.originSite(),event.originInstanceId()));
        var op=ActivityStateEvents.parse(event.payloadJson());
        jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id=? FOR SHARE",r->{},op.created().authorGlobalId());
        if(stale(op))return true;var grant=consent.getBySubject(op.created().authorGlobalId());
        if(grant==null||grant.epoch()!=op.epoch()||!(op.deletedAt()!=null
                ?consent.removalReplicationAllowed(grant.userId()):consent.replicationAllowed(grant.userId())))return false;
        var c=op.created();
        if(!event.originSite().equals(c.originSite())) {
            boolean proof=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.activity_source_proof WHERE table_name=? AND object_gid=? AND author_gid=? AND origin_site=? AND content_sha256=?)",Boolean.class,op.table(),c.globalId(),c.authorGlobalId(),c.originSite(),EventCodec.sha256(ActivityEvents.createdPayload(op.table(),c))));
            boolean localRow=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public."+op.table()+" a JOIN eatwhat_identity.entity_identity i ON i.entity_type=? AND i.numeric_id=a.id WHERE a.id=? AND i.global_id=? AND i.origin_site=?)",Boolean.class,op.table(),c.numericId(),c.globalId(),c.originSite()));
            if(!proof&&!localRow)return false;
        }
        if(ActivityEvents.POSTS.equals(op.table())&&!ActivityEvents.activityDefinitionPresent(jdbc,c.activityGlobalId()))return false;
        return c.provinceId()==null&&c.cityId()==null||Boolean.TRUE.equals(jdbc.queryForObject("SELECT (?::int IS NULL OR EXISTS(SELECT 1 FROM public.provinces WHERE id=?)) AND (?::int IS NULL OR EXISTS(SELECT 1 FROM public.cities WHERE id=? AND (?::int IS NULL OR province_id=?)))",Boolean.class,c.provinceId(),c.provinceId(),c.cityId(),c.cityId(),c.provinceId(),c.provinceId()));
    }
    @Override public void apply(SyncEvent event){
        var op=ActivityStateEvents.parse(event.payloadJson());if(stale(op))return;var c=op.created();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->{},"activity-state:"+c.globalId());
        var existing=jdbc.queryForList("SELECT global_id,origin_site FROM eatwhat_identity.entity_identity WHERE entity_type=? AND numeric_id=?",op.table(),c.numericId());
        if(!existing.isEmpty()&&(!c.globalId().equals(existing.getFirst().get("global_id"))||!c.originSite().equals(existing.getFirst().get("origin_site"))))throw new IllegalArgumentException("ACTIVITY_IDENTITY_MISMATCH");
        jdbc.queryForObject("SELECT eatwhat_sync.apply_activity_state_created(?,?::jsonb)",String.class,op.table(),ActivityEvents.createdPayload(op.table(),c));
        var owners=jdbc.queryForList("SELECT account_id FROM public."+op.table()+" WHERE id=? FOR UPDATE",Long.class,c.numericId());
        if(owners.isEmpty())return;var grant=consent.getBySubject(c.authorGlobalId());
        if(grant==null||!owners.getFirst().equals(grant.userId()))throw new IllegalArgumentException("ACTIVITY_OWNER_MISMATCH");
        String match="SELECT description IS NOT DISTINCT FROM ? AND to_jsonb(picture_url)=?::jsonb AND (extract(epoch FROM created_at)*1000)::bigint=?";
        var arguments=new ArrayList<Object>();arguments.add(c.description());arguments.add(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(c.pictures()));arguments.add(c.createdAt());
        if(op.table().equals(ActivityEvents.FOODS)){match+=" AND food_name=? AND province_id IS NOT DISTINCT FROM ?::int AND city_id IS NOT DISTINCT FROM ?::int";arguments.add(c.foodName());arguments.add(c.provinceId());arguments.add(c.cityId());}
        if(op.table().equals(ActivityEvents.POSTS)){
            match+=" AND title IS NOT DISTINCT FROM ? AND province_id IS NOT DISTINCT FROM ?::int AND city_id IS NOT DISTINCT FROM ?::int AND extra=?::jsonb"
                    +" AND activity_id=(SELECT numeric_id FROM eatwhat_identity.entity_identity WHERE entity_type='activities' AND global_id=?)";
            arguments.add(c.title());arguments.add(c.provinceId());arguments.add(c.cityId());arguments.add(c.extraJson());arguments.add(c.activityGlobalId());
        }
        match+=" FROM public."+op.table()+" WHERE id=?";arguments.add(c.numericId());
        if(!Boolean.TRUE.equals(jdbc.queryForObject(match,Boolean.class,arguments.toArray())))throw new IllegalArgumentException("ACTIVITY_CONTENT_IDENTITY_MISMATCH");
        String digest=EventCodec.sha256(ActivityEvents.createdPayload(op.table(),c));
        jdbc.update("INSERT INTO eatwhat_sync.activity_source_proof(table_name,object_gid,author_gid,origin_site,content_sha256) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",op.table(),c.globalId(),c.authorGlobalId(),c.originSite(),digest);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT content_sha256=? AND author_gid=? AND origin_site=? FROM eatwhat_sync.activity_source_proof WHERE table_name=? AND object_gid=?",Boolean.class,digest,c.authorGlobalId(),c.originSite(),op.table(),c.globalId())))throw new IllegalArgumentException("ACTIVITY_SOURCE_PROOF_MISMATCH");
        jdbc.update("INSERT INTO eatwhat_sync.activity_intent(table_name,object_gid,numeric_id,author_gid,deleted_at,published_revision,published_epoch) VALUES(?,?,?,?,?,1,?) ON CONFLICT DO NOTHING",op.table(),c.globalId(),c.numericId(),c.authorGlobalId(),op.deletedAt(),op.epoch());
        if(op.deletedAt()!=null)jdbc.queryForObject("SELECT eatwhat_sync.apply_activity_deleted(?,?,?,to_timestamp(?/1000.0))",String.class,op.table(),c.numericId(),c.globalId(),op.deletedAt());
    }
}
