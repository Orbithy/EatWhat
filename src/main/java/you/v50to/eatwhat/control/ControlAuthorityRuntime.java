package you.v50to.eatwhat.control;

import you.v50to.eatwhat.cluster.ClusterMember;
import java.util.*;

/** Fixed business-node portion of authority activation. Never apply this map to a management controller. */
public final class ControlAuthorityRuntime {
    private ControlAuthorityRuntime() {}
    public static Map<String,String> business(UUID cluster,ClusterMember authority,boolean privateCa){
        if(cluster==null||cluster.equals(new UUID(0,0))||authority==null
                ||!"CAMPUS".equals(authority.mode())||authority.allocationSlot()!=0)
            throw new ControlFailure(409,"AUTHORITY_RUNTIME_BINDING_REQUIRED");
        var p=new LinkedHashMap<String,String>();
        p.put("app.site.cluster-id",cluster.toString());p.put("app.site.id",authority.siteId());p.put("app.site.instance-id",authority.instanceId().toString());p.put("app.site.mode","CAMPUS");p.put("app.site.allocation-slot","0");
        p.put("app.control.enabled","true");p.put("app.control.node-enabled","true");p.put("app.control.environment-kind","managed-deployment");p.put("app.control.enrollment-required","true");
        p.put("app.cluster.enabled","true");p.put("app.cluster.coordinator","true");p.put("app.cluster.single-site-only","false");p.put("app.cluster.automatic-gc-enabled","false");
        // The business process serves authenticated node operations; only management processes may drive coordination.
        p.put("app.cluster.control-lease.enabled","true");p.put("app.cluster.control-lease.candidate","false");p.put("app.cluster.control-lease.automatic-takeover","false");p.put("app.cluster.auth-store.enabled","false");
        p.put("app.account.enabled","true");p.put("app.account.synthetic-only","false");p.put("app.sync.transport-enabled","true");p.put("app.sync.synthetic-only","false");p.put("app.sync.capture-enabled","false");
        for(String domain:List.of("activity","relation","profile","privacy","notification","catalog","files"))p.put("app.sync."+domain+".enabled","true");
        p.put("app.storage.files.lifecycle-enabled","true");p.put("app.storage.files.replication.enabled","true");p.put("app.storage.files.replication.worker-enabled","false");p.put("app.storage.files.gc.execute-enabled","false");
        p.put("app.control.deployment.enabled","false");p.put("app.control.servers.installation-enabled","false");p.put("app.control.servers.cluster-enrollment-enabled","false");
        if(privateCa){p.put("app.cluster.node-ca-file","/config/authority-ca.pem");p.put("app.control.node-ca-file","/config/authority-ca.pem");}
        return Collections.unmodifiableMap(p);
    }
}
