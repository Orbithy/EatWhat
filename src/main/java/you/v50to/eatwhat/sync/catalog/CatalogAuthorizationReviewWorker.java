package you.v50to.eatwhat.sync.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import you.v50to.eatwhat.sync.*;
import java.util.*;

/** Reassesses effective operations when a disconnected site later learns a concurrent security revocation. */
public final class CatalogAuthorizationReviewWorker {
    private final JdbcTemplate jdbc;private final SyncTransactions transactions;private final CatalogMultiwriterSupport support;
    private final CatalogMultiwriterHandler handler;private UUID cursor;private UUID decisionCursor;private final CatalogDecisionService decisions;private CatalogMultiwriterHandler bridgeHandler;
    public CatalogAuthorizationReviewWorker(JdbcTemplate jdbc,SyncTransactions transactions,CatalogMultiwriterSupport support,CatalogMultiwriterHandler handler,CatalogDecisionService decisions) {
        this.jdbc=jdbc;this.transactions=transactions;this.support=support;this.handler=handler;this.decisions=decisions;
    }
    public void setBridgeHandler(CatalogMultiwriterHandler value){bridgeHandler=value;}
    @Scheduled(fixedDelayString="${app.sync.catalog.authorization-review-delay-ms:5000}")
    public synchronized void scheduled(){runOnce();}
    public synchronized int runOnce(){
        var ids=jdbc.queryForList("SELECT DISTINCT a.operation_id FROM eatwhat_sync.catalog_object o CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h JOIN eatwhat_sync.catalog_actor_evidence a ON a.operation_id=(h->>'operationId')::uuid WHERE (?::uuid IS NULL OR a.operation_id>?) ORDER BY a.operation_id LIMIT 64",UUID.class,cursor,cursor);
        if(ids.isEmpty())cursor=null;
        int count=0;
        for(UUID id:ids) {
            transactions.required(()->{
                var evidence=support.find(id);
                if(support.assess(evidence)!=CatalogActorVerifier.Assessment.CONCURRENT_REVOCATION_REVIEW)return null;
                // Reconstruct only from an authenticated durable local event, never from arbitrary public row data.
                var events=jdbc.queryForList("SELECT envelope::text FROM eatwhat_sync.outbox WHERE event_id=? UNION ALL SELECT envelope::text FROM eatwhat_sync.inbox WHERE event_id=? AND status='APPLIED'",String.class,id,id);
                if(events.isEmpty() && bridgeHandler!=null)events=jdbc.queryForList("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='catalog.operation' AND envelope->>'eventType'='catalog.state' AND envelope->>'schemaVersion'='2' AND ((envelope->>'payloadJson')::jsonb->'heads') @> jsonb_build_array(jsonb_build_object('operationId',?::text)) UNION ALL SELECT envelope::text FROM eatwhat_sync.inbox WHERE status='APPLIED' AND aggregate_type='catalog.operation' AND envelope->>'eventType'='catalog.state' AND envelope->>'schemaVersion'='2' AND ((envelope->>'payloadJson')::jsonb->'heads') @> jsonb_build_array(jsonb_build_object('operationId',?::text))",String.class,id.toString(),id.toString());
                if(events.isEmpty())return null;
                var event=new EventCodec().decode(events.getFirst());
                if(event.schemaVersion()==2){if(CatalogEvents.ACTION.equals(event.eventType()))handler.apply(event);else if(bridgeHandler!=null)bridgeHandler.apply(event);}
                return null;
            });cursor=id;count++;
        }
        var decisionIds=jdbc.queryForList("SELECT DISTINCT h::uuid FROM eatwhat_sync.catalog_decision_state s CROSS JOIN LATERAL jsonb_array_elements_text(s.heads) h WHERE (?::uuid IS NULL OR h::uuid>?) ORDER BY 1 LIMIT 64",UUID.class,decisionCursor,decisionCursor);
        if(decisionIds.isEmpty())decisionCursor=null;
        for(UUID id:decisionIds){transactions.required(()->{decisions.reassessDecision(id);return null;});decisionCursor=id;count++;}
        return count;
    }
}
