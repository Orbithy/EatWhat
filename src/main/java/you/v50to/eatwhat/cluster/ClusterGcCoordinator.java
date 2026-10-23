package you.v50to.eatwhat.cluster;

import java.util.*;
/** Remote votes are accepted only from exact authenticated installations and immutable decision hashes. */
public final class ClusterGcCoordinator {
    private final ClusterGcStore gc;private final ClusterStore membership;private final ClusterProfiles profiles;private final ClusterHttpNodes nodes;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;private final you.v50to.eatwhat.config.FileGcProperties settings;
    private final ClusterProperties cluster;
    public ClusterGcCoordinator(ClusterGcStore gc,ClusterStore membership,ClusterProfiles profiles,ClusterHttpNodes nodes,org.springframework.jdbc.core.JdbcTemplate jdbc,you.v50to.eatwhat.config.FileGcProperties settings,ClusterProperties cluster){this.gc=gc;this.membership=membership;this.profiles=profiles;this.nodes=nodes;this.jdbc=jdbc;this.settings=settings;this.cluster=cluster;}
    public void discover(){if(!cluster.isAutomaticGcEnabled()||!settings.isEnabled()||!settings.isInventoryEnabled()||!settings.isExecuteEnabled()||settings.isDryRun()||!settings.isCoordinatedEnabled()||!you.v50to.eatwhat.config.FileGcProperties.isAckUsable(settings.getExecuteAck())||!settings.getExecuteClasses().contains("UNREFERENCED_REVISION"))return;
        var rows=jdbc.queryForList("""
          SELECT t.file_id,t.revision FROM eatwhat_file.file_tombstone t
          JOIN eatwhat_file.file_replica r USING(file_id,revision)
          WHERE r.site='LOCAL' AND r.state='AVAILABLE_LOCAL'
            AND t.unreferenced_since<=clock_timestamp()-(? * interval '1 millisecond')
            AND NOT EXISTS(SELECT 1 FROM eatwhat_cluster.gc_operation g WHERE g.file_id=t.file_id AND g.revision=t.revision)
            AND (EXISTS(SELECT 1 FROM eatwhat_file.file_sync_dirty d WHERE d.file_id=t.file_id AND d.revision=t.revision AND d.metadata_epoch>=1)
              OR EXISTS(SELECT 1 FROM eatwhat_file.file_sync_origin o,eatwhat_identity.allocation a WHERE a.singleton AND o.file_id=t.file_id AND o.origin_site<>a.site_id))
          ORDER BY t.unreferenced_since,t.file_id LIMIT 4
          """,settings.getRetention().toMillis());long epoch=((Number)membership.status().get("epoch")).longValue();
        for(var row:rows){UUID file=(UUID)row.get("file_id");int revision=((Number)row.get("revision")).intValue();UUID id=UUID.nameUUIDFromBytes((membership.status().get("clusterId")+":gc:"+file+":"+revision).getBytes(java.nio.charset.StandardCharsets.UTF_8));gc.begin(id,file,revision,epoch);}
    }
    public Map<String,Object> resume(UUID id){var d=gc.get(id);var participants=d.participants().stream().map(m->{var p=profiles.get(membership.profile(m.siteId()));if(!p.member().equals(m))throw new ClusterFailure(409,"GC_PROFILE_IDENTITY_CHANGED");return p;}).toList();String state=gc.state(id);
        if(state.equals("PREPARING")){for(var node:participants){proof(node,"gc-prepare",d,"PREPARED");gc.vote(id,node.member(),"PREPARED");}state=gc.advance(id);}
        if(state.equals("COMMITTED")){for(var node:participants)proof(node,"gc-commit",d,"COMMITTED");for(var node:participants){nodes.call(node,"gc-run",Map.of());var response=proof(node,"gc-state",d,null);if(response.get("state").equals("DELETED"))gc.vote(id,node.member(),"DELETED");}state=gc.advance(id);}
        return gc.snapshot(id);
    }
    private Map<String,Object> proof(ClusterProfiles.Entry node,String operation,ClusterGcStore.Decision decision,String expected){var r=nodes.call(node,operation,decision);if(!decision.operationId().toString().equals(Objects.toString(r.get("operationId")))||!ClusterStore.hash(decision).equals(r.get("decisionHash"))||(expected!=null&&!expected.equals(r.get("state"))))throw new ClusterFailure(409,"GC_NODE_PROOF_MISMATCH");return r;}
}
