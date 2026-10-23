package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Evaluated at the authority just before each recipient send. Site UUIDs and consent epochs are never interchangeable. */
public final class ClusterRecipientConsent {
    private final JdbcTemplate jdbc;private final ClusterMembershipView membership;
    public ClusterRecipientConsent(JdbcTemplate jdbc){this.jdbc=jdbc;membership=new ClusterMembershipView(jdbc);}
    public boolean allowed(UUID subject,long epoch,ClusterMember target){
        var rows=jdbc.queryForList("SELECT status,epoch,origin_site,withdrawal_request_id FROM eatwhat_consent.subject WHERE subject_id=?",subject);
        if(rows.size()!=1)return false;var state=rows.getFirst();if(!state.get("status").equals("GRANTED")||((Number)state.get("epoch")).longValue()!=epoch||state.get("withdrawal_request_id")!=null)return false;
        if(target.allocationSlot()==0)return true;
        if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.recipient_grant WHERE subject_id=? AND epoch=? AND site_id=? AND instance_id=?",Long.class,subject,epoch,target.siteId(),target.instanceId())>0)return true;
        if(target.allocationSlot()!=1)return false;
        return membership.retained().stream().anyMatch(m->m.allocationSlot()<2&&m.siteId().equals(state.get("origin_site")));
    }
    public boolean historicallyAllowed(UUID subject,ClusterMember target){
        if(target.allocationSlot()==0)return true;
        if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.recipient_grant WHERE subject_id=? AND site_id=? AND instance_id=?",Long.class,subject,target.siteId(),target.instanceId())>0)return true;
        if(target.allocationSlot()!=1)return false;
        var rows=jdbc.queryForList("SELECT origin_site,epoch FROM eatwhat_consent.subject WHERE subject_id=?",subject);
        return rows.size()==1&&((Number)rows.getFirst().get("epoch")).longValue()>0&&membership.retained().stream().anyMatch(m->m.allocationSlot()<2&&m.siteId().equals(rows.getFirst().get("origin_site")));
    }
    /** Must run in the existing consent transaction after password proof or authenticated-user authorization. */
    public boolean grant(UUID subject,long epoch,String audience){
        jdbc.query("SELECT epoch FROM eatwhat_cluster.state WHERE singleton FOR SHARE",rs->{});
        if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.gc_operation g JOIN eatwhat_file.file_object f ON f.file_id=g.file_id WHERE f.owner_gid=? AND g.state<>'DELETED'",Long.class,subject)>0)throw new ClusterFailure(409,"RECIPIENT_GC_PENDING");
        var target=membership.current().stream().filter(m->m.siteId().equals(audience)&&m.mode().equals("EXTERNAL")).findFirst().orElseThrow(()->new ClusterFailure(409,"CONSENT_RECIPIENT_NOT_ACTIVE"));
        return jdbc.update("INSERT INTO eatwhat_cluster.recipient_grant(subject_id,epoch,site_id,instance_id) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",subject,epoch,target.siteId(),target.instanceId())==1;
    }
    public long generation(String target){return jdbc.queryForObject("SELECT coalesce(max(grant_id),0) FROM eatwhat_cluster.recipient_grant WHERE site_id=?",Long.class,target);}
    public long generation(){return jdbc.queryForObject("SELECT coalesce(max(grant_id),0) FROM eatwhat_cluster.recipient_grant",Long.class);}
}
