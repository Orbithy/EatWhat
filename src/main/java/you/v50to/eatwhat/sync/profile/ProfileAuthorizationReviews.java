package you.v50to.eatwhat.sync.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.VersionVector;
import java.util.*;

/** Security review preserves the proposed operation and reuses the complete-head conflict protocol. */
final class ProfileAuthorizationReviews {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final ProfileStore store;
    ProfileAuthorizationReviews(JdbcTemplate jdbc,ProfileStore store){this.jdbc=jdbc;this.store=store;}
    void record(UUID event,UUID actor,ProfileEvents.Operation op,String reason) {
        if(jdbc.update("INSERT INTO eatwhat_sync.profile_authorization_review(event_id,subject_gid,actor_gid,operation,reason) VALUES(?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING",event,op.subject(),actor,ProfileEvents.payload(op),reason)==0)return;
        var baseline=store.baseline(op.subject()).orElseGet(()->{var b=store.createIndependentBaseline(op.subject());store.insertInitialFieldValues(op.subject(),b.value());return b;});
        // An approved decision can arrive before the rejected original operation.
        var decisions=jdbc.queryForList("SELECT event_id FROM eatwhat_sync.profile_operation WHERE subject_gid=? AND resolution->'expectedHeads' @> ?::jsonb",UUID.class,op.subject(),JSON.writeValueAsString(List.of(event)));
        if(!decisions.isEmpty()){for(UUID decision:decisions)jdbc.update("INSERT INTO eatwhat_sync.profile_authorization_review_decision(review_event_id,decision_event_id) VALUES(?,?) ON CONFLICT DO NOTHING",event,decision);return;}
        Long user=jdbc.queryForObject("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id=?",Long.class,op.subject());
        for(var change:op.changes().entrySet()) {
            String group=change.getKey();var heads=new LinkedHashMap<UUID,ProfileEvents.Head>();
            for(var head:store.fieldHeads(op.subject(),group,baseline.value().get(group)))heads.put(head.id(),head);
            var prior=jdbc.queryForList("SELECT heads::text FROM eatwhat_sync.profile_conflict WHERE subject_gid=? AND field_group=?",String.class,op.subject(),group);
            if(!prior.isEmpty())for(var node:JSON.readTree(prior.getFirst())){var head=JSON.convertValue(node,ProfileEvents.Head.class);heads.put(head.id(),head);}
            var vector=new LinkedHashMap<>(op.contexts().get(group));vector.put(op.dotSite(),op.dotCounter());
            heads.put(event,new ProfileEvents.Head(event,change.getValue(),Map.copyOf(vector)));
            String payload=JSON.writeValueAsString(heads.values().stream().sorted(Comparator.comparing(h->h.id().toString())).toList());
            jdbc.update("""
                INSERT INTO eatwhat_sync.profile_conflict(conflict_id,subject_gid,field_group,revision,heads,status)
                VALUES(?,?,?,1,?::jsonb,'OPEN') ON CONFLICT(subject_gid,field_group) DO UPDATE
                SET revision=profile_conflict.revision+1,heads=EXCLUDED.heads,status='OPEN',resolved_head=NULL,updated_at=clock_timestamp()
                """,UUID.randomUUID(),op.subject(),group,payload);
            store.applyPublicValue(user,group,ProfileStore.emptyProfile().get(group));
        }
    }
    void decided(UUID subject,UUID decision,List<UUID> expectedHeads) {
        for(UUID expected:expectedHeads)jdbc.update("INSERT INTO eatwhat_sync.profile_authorization_review_decision(review_event_id,decision_event_id) SELECT event_id,? FROM eatwhat_sync.profile_authorization_review WHERE event_id=? AND subject_gid=? ON CONFLICT DO NOTHING",decision,expected,subject);
    }
}
