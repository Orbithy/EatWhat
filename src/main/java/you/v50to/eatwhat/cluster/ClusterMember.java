package you.v50to.eatwhat.cluster;

import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.util.UUID;

public record ClusterMember(String siteId,UUID instanceId,String mode,int allocationSlot) {
    public ClusterMember {
        new NodeIdentity(siteId,instanceId);
        if(!java.util.Set.of("CAMPUS","EXTERNAL").contains(mode)||allocationSlot<0||allocationSlot>1023)
            throw new ClusterFailure(400,"INVALID_CLUSTER_MEMBER");
    }
    public NodeIdentity identity(){return new NodeIdentity(siteId,instanceId);}
}
