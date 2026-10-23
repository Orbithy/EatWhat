package you.v50to.eatwhat.cluster;

import org.springframework.context.SmartLifecycle;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded worker per node, and one authority driver. Durable leases/decisions own retries after restarts. */
public final class ClusterRuntime implements SmartLifecycle {
    @org.springframework.beans.factory.annotation.Value("${app.control.independent.enabled:false}") private boolean independentControl;
    private final ClusterReplayService local;private final JdbcTemplate jdbc;private final ObjectProvider<ClusterStore> store;private final ObjectProvider<ClusterProfiles> profiles;private final ObjectProvider<ClusterMembershipCoordinator> membership;private final ObjectProvider<ClusterMeshSynchronizer> mesh;private final ObjectProvider<ClusterHttpNodes> http;private final ObjectProvider<ClusterGcCoordinator> gc;private final ObjectProvider<ClusterControlLease> lease;
    private ScheduledExecutorService executor;private ScheduledExecutorService heartbeat;private final AtomicBoolean active=new AtomicBoolean();private volatile String lastCode="NOT_STARTED";
    private final ObjectProvider<ClusterControlHandoff> handoff;
    public ClusterRuntime(ClusterReplayService local,JdbcTemplate jdbc,ObjectProvider<ClusterStore> store,ObjectProvider<ClusterProfiles> profiles,ObjectProvider<ClusterMembershipCoordinator> membership,ObjectProvider<ClusterMeshSynchronizer> mesh,ObjectProvider<ClusterHttpNodes> http,ObjectProvider<ClusterGcCoordinator> gc,ObjectProvider<ClusterControlLease> lease,ObjectProvider<ClusterControlHandoff> handoff){this.local=local;this.jdbc=jdbc;this.store=store;this.profiles=profiles;this.membership=membership;this.mesh=mesh;this.http=http;this.gc=gc;this.lease=lease;this.handoff=handoff;}
    public void cycle(){if(!active.compareAndSet(false,true))return;try{
        var controller=lease.getIfAvailable();if(controller!=null&&controller.draining()){lastCode="CONTROL_HANDOFF_PENDING";return;}
        var coordinator=store.getIfAvailable();if(coordinator!=null)coordinator.requireLeadership();if(!independentControl)local.flush();if(coordinator==null){lastCode="OK";return;}
        var changes=jdbc.queryForList("SELECT change_id FROM eatwhat_cluster.change WHERE state<>'APPLIED'",UUID.class);if(!changes.isEmpty()){membership.getObject().reconcile("cluster-worker",changes.getFirst());lastCode="OK";return;}
        var nodes=coordinator.active().stream().map(m->profiles.getObject().get(coordinator.profile(m.siteId()))).toList();if(nodes.isEmpty()){lastCode="AWAITING_FIRST_JOIN";return;}
        if(local.singleSiteOnly()){if(nodes.size()!=1)throw new ClusterFailure(409,"SINGLE_SITE_CLUSTER_ONLY");lastCode="SINGLE_SITE_ACTIVE";return;}
        reseed(nodes);mesh.getObject().cycle(http.getObject(),nodes);
        gc.getObject().discover();for(var operation:jdbc.queryForList("SELECT operation_id FROM eatwhat_cluster.gc_operation WHERE state<>'DELETED' ORDER BY created_at LIMIT 4",UUID.class))gc.getObject().resume(operation);
        lastCode="OK";
    }catch(ClusterFailure failure){lastCode=failure.getMessage();}catch(RuntimeException failure){lastCode="CLUSTER_CYCLE_RETRY";}finally{active.set(false);}}
    private void reseed(List<ClusterProfiles.Entry> nodes){var consent=new ClusterRecipientConsent(jdbc);for(var target:nodes){long generation=consent.generation(target.member().siteId());long old=jdbc.queryForObject("SELECT coalesce(max(generation),0) FROM eatwhat_cluster.recipient_replay WHERE site_id=? AND instance_id=?",Long.class,target.member().siteId(),target.member().instanceId());if(generation<=old)continue;
        for(var source:nodes)if(!source.member().equals(target.member()))http.getObject().call(source,"reseed-consent",new ClusterReplayService.Target(target.member().siteId()));
        store.getObject().fencedWrite(()->jdbc.update("INSERT INTO eatwhat_cluster.recipient_replay(site_id,instance_id,generation) VALUES(?,?,?) ON CONFLICT(site_id,instance_id) DO UPDATE SET generation=greatest(eatwhat_cluster.recipient_replay.generation,excluded.generation)",target.member().siteId(),target.member().instanceId(),generation));
    }}
    public String lastCode(){return lastCode;}
    public Map<String,Object> leadership(){var current=lease.getIfAvailable();return current==null?Map.of("state","SINGLE_PROCESS","localLeader",true):current.status();}
    public synchronized void start(){if(executor!=null)return;var transfer=handoff.getIfAvailable();if(transfer!=null)transfer.runtimeIdle(()->!active.get());var current=lease.getIfAvailable();if(current!=null){heartbeat=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"cluster-control-lease");thread.setDaemon(true);return thread;});heartbeat.scheduleWithFixedDelay(()->{try{current.tick();}catch(RuntimeException failure){lastCode="CONTROL_LEASE_UNAVAILABLE";}},0,1,TimeUnit.SECONDS);}executor=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"cluster-coordination");thread.setDaemon(true);return thread;});executor.scheduleWithFixedDelay(this::cycle,1,5,TimeUnit.SECONDS);}
    public synchronized void stop(){if(executor==null)return;executor.shutdownNow();if(heartbeat!=null)heartbeat.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);if(heartbeat!=null)heartbeat.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}executor=null;heartbeat=null;var current=lease.getIfAvailable();if(current!=null)try{current.release();}catch(RuntimeException ignored){/* expiry still fences after its bounded deadline */}}
    public boolean isRunning(){return executor!=null&&!executor.isShutdown();}
    public int getPhase(){return 1000;}
}
