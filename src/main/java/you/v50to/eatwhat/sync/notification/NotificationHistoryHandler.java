package you.v50to.eatwhat.sync.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.*;
import java.util.*;

public final class NotificationHistoryHandler implements EventHandler {
    private final JdbcTemplate jdbc;private final ConsentStore consent;
    public NotificationHistoryHandler(JdbcTemplate jdbc,ConsentStore consent){this.jdbc=jdbc;this.consent=consent;}
    @Override public String aggregateType(){return NotificationHistoryEvents.AGGREGATE;}
    @Override public String eventType(){return NotificationHistoryEvents.EVENT;}
    @Override public int schemaVersion(){return 1;}
    private boolean stale(NotificationHistoryEvents.Operation op){var a=consent.getBySubject(op.notice().actor());var r=consent.getBySubject(op.notice().recipient());return a!=null&&(a.epoch()>op.actorEpoch()||a.epoch()==op.actorEpoch()&&a.status().equals("WITHDRAWN"))||r!=null&&(r.epoch()>op.recipientEpoch()||r.epoch()==op.recipientEpoch()&&r.status().equals("WITHDRAWN"));}
    @Override public boolean dependenciesSatisfied(SyncEvent e){new NotificationHistoryEvents(consent).validate(e,new you.v50to.eatwhat.sync.transport.NodeIdentity(e.originSite(),e.originInstanceId()));var op=NotificationHistoryEvents.parse(e.payloadJson());
        jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id IN (?,?) ORDER BY user_id FOR SHARE",r->{},op.notice().actor(),op.notice().recipient());
        if(stale(op))return true;var a=consent.getBySubject(op.notice().actor());var r=consent.getBySubject(op.notice().recipient());
        if(a==null||r==null||a.epoch()!=op.actorEpoch()||r.epoch()!=op.recipientEpoch()||!consent.replicationAllowed(a.userId())||!consent.replicationAllowed(r.userId()))return false;
        if(op.action().equals("READ"))return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.notification_history WHERE notification_gid=? AND payload=?::jsonb)",Boolean.class,op.notice().gid(),NotificationHistoryEvents.notice(op.notice())));
        if(!e.originSite().equals(op.notice().origin())&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.notification_source_proof WHERE notification_gid=? AND origin_site=? AND content_sha256=?)",Boolean.class,op.notice().gid(),op.notice().origin(),EventCodec.sha256(NotificationHistoryEvents.notice(op.notice())))))return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE entity_type=? AND global_id=?)",Boolean.class,NotificationHistoryEvents.targetTable(op.notice()),op.notice().target()));
    }
    @Override public void apply(SyncEvent e){var op=NotificationHistoryEvents.parse(e.payloadJson());if(stale(op))return;var n=op.notice();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->{},"notice-history:"+n.gid());
        if(op.action().equals("PUBLISH")){
            jdbc.queryForObject("SELECT eatwhat_sync.import_notification_history(?::jsonb)",String.class,NotificationHistoryEvents.notice(n));
            jdbc.update("INSERT INTO eatwhat_sync.notification_history(notification_gid,numeric_id,origin_site,actor_gid,recipient_gid,payload,published_actor_epoch,published_recipient_epoch) VALUES(?,?,?,?,?,?::jsonb,?,?) ON CONFLICT DO NOTHING",n.gid(),n.id(),n.origin(),n.actor(),n.recipient(),NotificationHistoryEvents.notice(n),op.actorEpoch(),op.recipientEpoch());
        }
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.notification_history WHERE notification_gid=? AND numeric_id=? AND payload=?::jsonb)",Boolean.class,n.gid(),n.id(),NotificationHistoryEvents.notice(n))))throw new IllegalArgumentException("NOTICE_HISTORY_COLLISION");
        String digest=EventCodec.sha256(NotificationHistoryEvents.notice(n));
        jdbc.update("INSERT INTO eatwhat_sync.notification_source_proof(notification_gid,origin_site,content_sha256) VALUES(?,?,?) ON CONFLICT DO NOTHING",n.gid(),n.origin(),digest);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT content_sha256=? AND origin_site=? FROM eatwhat_sync.notification_source_proof WHERE notification_gid=?",Boolean.class,digest,n.origin(),n.gid())))throw new IllegalArgumentException("NOTICE_SOURCE_PROOF_MISMATCH");
        if(op.action().equals("READ")){
            jdbc.update("INSERT INTO eatwhat_sync.notification_history_read(notification_gid,recipient_gid,read_at,published_actor_epoch,published_recipient_epoch) VALUES(?,?,?,?,?) ON CONFLICT(notification_gid) DO UPDATE SET read_at=least(notification_history_read.read_at,excluded.read_at)",n.gid(),n.recipient(),op.readAt(),op.actorEpoch(),op.recipientEpoch());
            var r=consent.getBySubject(n.recipient());jdbc.update("UPDATE public.notifications SET read_at=least(read_at,to_timestamp(?/1000.0)) WHERE id=? AND account_id=?",op.readAt(),n.id(),r.userId());}
    }
}
