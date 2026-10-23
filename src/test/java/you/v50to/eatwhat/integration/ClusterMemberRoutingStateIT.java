package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.ClusterMembershipView;
import you.v50to.eatwhat.sync.notification.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG read-model/causal fixtures. These rows are not evidence of a membership protocol JOIN. */
class ClusterMemberRoutingStateIT extends ClusterMembershipIT {
    @Test void boundValidationDoesNotAdmitAConfiguredButUnregisteredFallbackSite(){
        db.admin().update("INSERT INTO eatwhat_cluster.local_state(singleton,cluster_id,epoch,site_id,instance_id,state,members) VALUES(true,?,0,?,?,'JOINING',?::jsonb)",cluster,campus.siteId(),campus.instanceId(),JsonMapper.builder().build().writeValueAsString(List.of(campus,external,third)));
        var sites=ClusterMembershipView.sites(db.runtime(),Set.of(campus.siteId(),"phantom-fixed-peer"));
        assertEquals(Set.of("campus","external","third"),Set.copyOf(sites));assertFalse(sites.contains("phantom-fixed-peer"));assertTrue(sites.contains("third"));
    }
    @Test void nextNotificationDotRetainsAllKnownMemberClocksAndExcludesOtherEpochs(){
        UUID notice=UUID.randomUUID(),recipient=UUID.randomUUID();String own=NotificationReadEvents.dotSite("campus",1,1),remote=NotificationReadEvents.dotSite("external",1,1),thirdDot=NotificationReadEvents.dotSite("third",1,1),old=NotificationReadEvents.dotSite("third",0,1);
        db.admin().update("INSERT INTO eatwhat_sync.notification_read_state(notification_gid,recipient_gid,vector) VALUES(?,?,?::jsonb)",notice,recipient,JsonMapper.builder().build().writeValueAsString(Map.of(own,3,remote,4,thirdDot,5,old,99)));
        // SQL read-model fixture only, before a JOIN; this does not claim business write admission.
        var store=new NotificationReadStore(db.runtime());var dot=new org.springframework.transaction.support.TransactionTemplate(db.transactionManager()).execute(status->store.nextDot(notice,recipient,own,Set.of(remote,thirdDot)));
        assertEquals(4,dot.counter());assertEquals(Map.of(own,3L,remote,4L,thirdDot,5L),dot.context());assertFalse(dot.context().containsKey(old));
    }
}
