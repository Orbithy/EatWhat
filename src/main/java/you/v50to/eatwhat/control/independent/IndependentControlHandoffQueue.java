package you.v50to.eatwhat.control.independent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import you.v50to.eatwhat.cluster.*;
import java.util.*;

/** A standby forwards an authenticated administrator's exact request; it never
 * modifies its local authority row to manufacture a takeover. */
public final class IndependentControlHandoffQueue {
    private final JdbcTemplate jdbc;private final IndependentControlAccounts accounts;
    private final IndependentControlProperties settings;private final ObjectProvider<ClusterControlLease> lease;private final TransactionTemplate tx;
    public IndependentControlHandoffQueue(JdbcTemplate jdbc,IndependentControlAccounts accounts,IndependentControlProperties settings,ObjectProvider<ClusterControlLease> lease,PlatformTransactionManager manager){this.jdbc=jdbc;this.accounts=accounts;this.settings=settings;this.lease=lease;tx=new TransactionTemplate(manager);tx.setTimeout(10);}
    public boolean standby(){return !Boolean.TRUE.equals(lease.getObject().status().get("localLeader"));}
    public Map<String,Object> request(String actor,UUID id,long generation,UUID target){
        if(!accounts.requireAdmin().equals(actor))throw new ClusterFailure(403,"ADMIN_REQUIRED");
        return tx.execute(ignored->{
            var prior=rows(id);if(!prior.isEmpty()){
                var r=prior.getFirst();if(!target.equals(r.get("target_holder"))||generation!=((Number)r.get("expected_generation")).longValue()||!actor.equals(r.get("actor_id").toString()))throw new ClusterFailure(409,"IDEMPOTENCY_CONFLICT");
                return json(r);
            }
            var current=jdbc.queryForMap("SELECT holder_id,generation,expires_at>clock_timestamp()AS live FROM eatwhat_cluster.control_lease WHERE singleton FOR SHARE");
            if(!Boolean.TRUE.equals(current.get("live"))||generation!=((Number)current.get("generation")).longValue())throw new ClusterFailure(409,"CONTROL_GENERATION_CHANGED");
            if(!target.equals(settings.getControllerId())||target.equals(current.get("holder_id")))throw new ClusterFailure(409,"CONTROL_TARGET_ALREADY_LEADER");
            Long version=jdbc.queryForObject("SELECT credential_version FROM eatwhat_controller.account WHERE id=? AND NOT disabled FOR SHARE",Long.class,Long.parseLong(actor));
            jdbc.update("INSERT INTO eatwhat_controller.handoff_outbox(request_id,actor_id,credential_version,source_holder,target_holder,expected_generation)VALUES(?,?,?,?,?,?)",id,Long.parseLong(actor),version,current.get("holder_id"),target,generation);
            return json(rows(id).getFirst());
        });
    }
    private List<Map<String,Object>> rows(UUID id){return jdbc.queryForList("SELECT * FROM eatwhat_controller.handoff_outbox WHERE request_id=?",id);}
    public Optional<Map<String,Object>> get(UUID id){var r=rows(id);return r.isEmpty()?Optional.empty():Optional.of(json(r.getFirst()));}
    public Optional<Map<String,Object>> pending(){var rows=jdbc.queryForList("SELECT * FROM eatwhat_controller.handoff_outbox q WHERE q.state IN ('PENDING','DISPATCHING','FORWARDED')AND NOT EXISTS(SELECT 1 FROM eatwhat_cluster.control_handoff h WHERE h.request_id=q.request_id)ORDER BY q.requested_at DESC LIMIT 1");return rows.isEmpty()?Optional.empty():Optional.of(json(rows.getFirst()));}
    public Optional<Map<String,Object>> cancel(String actor,UUID id){return tx.execute(ignored->{
        var rows=jdbc.queryForList("SELECT * FROM eatwhat_controller.handoff_outbox WHERE request_id=? FOR UPDATE",id);if(rows.isEmpty())return Optional.empty();
        if(!accounts.requireAdmin().equals(actor))throw new ClusterFailure(403,"ADMIN_REQUIRED");
        var row=rows.getFirst();if("CANCELLED".equals(row.get("state")))return Optional.of(json(row));
        if(!"PENDING".equals(row.get("state")))throw new ClusterFailure(409,"CONTROL_HANDOFF_NOT_CANCELLABLE");
        jdbc.update("UPDATE eatwhat_controller.handoff_outbox SET state='CANCELLED',reason='ADMIN_CANCELLED'WHERE request_id=?",id);return get(id);
    });}
    private static Map<String,Object> json(Map<String,Object> row){
        String state=switch(row.get("state").toString()){case "CANCELLED"->"CANCELLED";case "REJECTED"->"FAILED";default->"WAITING";};
        return Map.of("id",row.get("request_id").toString(),"sourceControllerId",row.get("source_holder").toString(),"targetControllerId",row.get("target_holder").toString(),
                "expectedGeneration",row.get("expected_generation"),"state",state,"reason",row.get("reason"),"requestedAt",((java.util.Date)row.get("requested_at")).toInstant().toString(),"finishedAt","");
    }
}
