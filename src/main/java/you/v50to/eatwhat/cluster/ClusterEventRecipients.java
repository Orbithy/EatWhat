package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.file.FileEvents;
import java.util.*;

/** Authority-only recipient gate, independent of the source's global consent gate. */
final class ClusterEventRecipients {
    private final JdbcTemplate jdbc;private final ClusterRecipientConsent consent;
    ClusterEventRecipients(JdbcTemplate jdbc){this.jdbc=jdbc;consent=new ClusterRecipientConsent(jdbc);}
    boolean allowed(SyncEvent event,ClusterMember target){
        if(FileEvents.RECEIPT.equals(event.aggregateType()))return true; // source delivery retains the exact original recipient
        if(FileEvents.REFERENCES.equals(event.aggregateType())){
            var file=FileEvents.references(event.payloadJson());var owners=jdbc.queryForList("SELECT owner_gid FROM eatwhat_file.file_object WHERE file_id=?",file.fileId());
            return owners.size()==1&&consent.historicallyAllowed((UUID)owners.getFirst().get("owner_gid"),target);
        }
        var subjects=new HashSet<UUID>();if(event.actorId()!=null)subjects.add(event.actorId());
        for(var d:event.dependencies())if(d.aggregateType().equals("users"))subjects.add(d.aggregateId());
        for(var id:subjects){var rows=jdbc.queryForList("SELECT epoch FROM eatwhat_consent.subject WHERE subject_id=?",id);if(rows.size()!=1||!consent.allowed(id,((Number)rows.getFirst().get("epoch")).longValue(),target))return false;}
        return true;
    }
}
