package you.v50to.eatwhat.cluster;

import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.util.*;

/** Configuration shape only. Actual recipients always come from committed/staged database membership. */
public final class ClusterRouting {
    private ClusterRouting(){}
    public static boolean configured(SiteProperties site){
        if(site.getClusterId()==null)return false;
        SyncEvent.requireUuid(site.getClusterId(),"cluster id");new NodeIdentity(site.getId(),site.getInstanceId());
        Integer slot=site.getAllocationSlot();
        if(slot==null||slot<0||slot>1023||site.getMode()==null
                ||site.getMode()==SiteProperties.Mode.LEGACY
                ||site.getMode()==SiteProperties.Mode.CAMPUS&&slot!=0
                ||site.getMode()==SiteProperties.Mode.EXTERNAL&&slot==0)throw new IllegalArgumentException("Invalid cluster allocation");
        return true;
    }
    public static List<String> legacyRecipients(SiteProperties site,String peer){
        if(configured(site))return List.of();
        SyncEvent.requireName(peer,"legacy peer",64);return List.of(peer);
    }
    public static Set<String> legacySites(SiteProperties site,String peer){
        if(configured(site))return Set.of();
        return Set.of(site.getId(),peer);
    }
    public static String authority(ClusterMembershipView membership,SiteProperties site,String legacyPeer){
        if(!configured(site))return site.getMode()==SiteProperties.Mode.CAMPUS?site.getId():legacyPeer;
        var campus=membership.retained().stream().filter(m->m.mode().equals("CAMPUS")&&m.allocationSlot()==0).toList();
        if(campus.size()!=1)throw new ClusterFailure(409,"CLUSTER_AUTHORITY_IDENTITY_REQUIRED");return campus.getFirst().siteId();
    }
}
