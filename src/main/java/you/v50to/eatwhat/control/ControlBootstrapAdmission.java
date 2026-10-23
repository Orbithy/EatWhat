package you.v50to.eatwhat.control;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** Actual authority/administrator/catalog/registry admission for the composite
 * installer. Slow remote/artifact reads happen before the short lease/state
 * fence; only the durable local intent belongs inside the fence, never SSH.
 * Initial empty-host checks are not reused after approved native effects.
 */
public final class ControlBootstrapAdmission {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.control.independent.IndependentControlAccounts independentControlAccounts;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ControlProperties settings;private final SiteProperties site;private final ClusterProperties cluster;
    private final JdbcTemplate jdbc;private final SyncTransactions tx;private final ClusterControlLease lease;
    private final DeploymentExecutor deployment;private final ControlServerStore store;private final ControlServers servers;private final ControlSsh ssh;
    private final ControlBootstrapArtifacts artifacts;private final ControlBootstrapPreflights preflights;private final ControlBootstrapSources sources;
    public ControlBootstrapAdmission(ControlProperties settings,SiteProperties site,ClusterProperties cluster,JdbcTemplate jdbc,SyncTransactions tx,
                                     ClusterControlLease lease,DeploymentExecutor deployment,ControlServerStore store,ControlServers servers,ControlSsh ssh,
                                     ControlBootstrapArtifacts artifacts,ControlBootstrapPreflights preflights,ControlBootstrapSources sources){
        this.settings=settings;this.site=site;this.cluster=cluster;this.jdbc=jdbc;this.tx=tx;this.lease=lease;this.deployment=deployment;
        this.store=store;this.servers=servers;this.ssh=ssh;this.artifacts=artifacts;this.preflights=preflights;this.sources=sources;tx.requireSameDataSource(jdbc);
    }
    public record Frozen(ControlBootstrapJournal.Spec spec,Map<String,Object> review,String actor,String adminBinding,UUID stateId,UUID holderId,long generation,
                         String sourceConnection,String targetConnection,String sourceRoute,String targetRoute,String rosterBinding,
                         ControlBootstrapPreflights.Captured sourceInitial,ControlBootstrapPreflights.Captured targetInitial,
                         ControlBootstrapSources.Request sourceRequest,Map<String,Object> sourceEvidence,String encryptedSourcePayload,
                         ControlBootstrapArtifacts.Frozen artifacts,String binding) {
        @Override public String toString(){return "BootstrapAdmission[redacted]";}
    }
    private record Local(String admin,UUID state,UUID holder,long generation,String sourceConnection,String targetConnection,String sourceRoute,String targetRoute,String roster) {}
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private String seal(Object value){try{return store.fingerprint(JSON.writeValueAsString(ordered(value)));}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_ADMISSION_UNAVAILABLE");}}
    private static Object ordered(Object value){
        var encoded=JSON.readValue(JSON.writeValueAsString(value),Object.class);
        return sort(encoded);
    }
    private static Object sort(Object value){
        if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),sort(v)));return sorted;}
        if(value instanceof List<?> list)return list.stream().map(ControlBootstrapAdmission::sort).toList();return value;
    }
    private String bind(Frozen f){return seal(Arrays.asList(f.spec(),f.review(),f.actor(),f.adminBinding(),f.stateId(),f.holderId(),f.generation(),
            f.sourceConnection(),f.targetConnection(),f.sourceRoute(),f.targetRoute(),f.rosterBinding(),f.sourceInitial(),f.targetInitial(),f.sourceRequest(),
            f.sourceEvidence(),f.encryptedSourcePayload(),f.artifacts()));}
    private void managed(){
        require(settings.isEnabled()&&settings.isNodeEnabled()&&settings.getControllerBootstrap().isEnabled()&&"managed-deployment".equals(settings.getEnvironmentKind())
                &&site.getMode()==SiteProperties.Mode.CAMPUS&&Integer.valueOf(0).equals(site.getAllocationSlot())
                &&cluster.isEnabled()&&cluster.isCoordinator()&&!cluster.isSingleSiteOnly()&&cluster.getControlLease().isEnabled(),"BOOTSTRAP_MANAGED_AUTHORITY_REQUIRED");
    }
    String planningReason(){try{managed();servers.requireWrite();return "";}catch(ControlFailure failure){return failure.code;}catch(ClusterFailure failure){return failure.getMessage();}}
    /** Cancellation grants no remote effects and therefore uses the current
     * administrator/held lease, even if the old approval's process has ended.
     * The journal must prove that no phase was ever dispatched under its lock. */
    <T>T beforeUnstartedCancellation(Frozen f,String actor,Supplier<T> durableIntent){
        bound(f);managed();servers.requireExecuting();
        return lease.write(tx,()->{admin(actor);require(f.stateId().equals(deployment.stateIdentity()),"BOOTSTRAP_PLAN_CHANGED");return durableIntent.get();});
    }
    private String admin(String actor){
        long id;try{id=Long.parseLong(actor);require(id>0&&Long.toString(id).equals(actor),"ADMIN_REQUIRED");}catch(NumberFormatException failure){throw new ControlFailure(409,"ADMIN_REQUIRED");}
        if(independentControlAccounts!=null)return seal(independentControlAccounts.admissionBinding(id));
        var rows=jdbc.queryForList("SELECT u.id,u.nick_name,u.password_hash,coalesce(v.version,0) AS version FROM users u LEFT JOIN eatwhat_control.credential_version v ON v.user_id=u.id WHERE u.id=? AND u.role='admin' AND NOT u.banned FOR SHARE OF u",id);
        require(rows.size()==1,"ADMIN_REQUIRED");var row=rows.getFirst();
        return seal(List.of(row.get("id"),row.get("nick_name"),row.get("password_hash"),row.get("version")));
    }
    private String connection(Map<String,Object> server){
        var values=new TreeMap<String,Object>();for(String key:List.of("id","host","port","username","privilege","authentication","hostKey","credential","credentialSource","credentialId"))
            if(server.containsKey(key))values.put(key,server.get(key));return seal(values);
    }
    /** Initial planning anchor from the actual authority, not request fields.
     * No remote I/O or missing state/key initialization occurs under this lock. */
    public ControllerSourceSnapshot.Authority sourceAuthority(String actor){
        managed();servers.requireWrite();
        return lease.write(tx,()->{
            admin(actor);
            var rows=jdbc.queryForList("""
                SELECT s.epoch,md5(n.cluster_identifier||':'||n.database_oid::text) AS fingerprint
                FROM eatwhat_cluster.state s JOIN eatwhat_identity.node_state n ON n.singleton
                JOIN eatwhat_identity.allocation a ON a.singleton
                JOIN eatwhat_cluster.member m ON m.site_id=n.site_id AND m.instance_id=n.instance_id
                JOIN eatwhat_cluster.local_state l ON l.singleton
                WHERE s.singleton AND s.cluster_id=? AND s.coordinator_site=? AND s.coordinator_instance=?
                  AND n.site_id=s.coordinator_site AND n.instance_id=s.coordinator_instance AND n.mode='CAMPUS'
                  AND n.database_name=current_database() AND n.database_oid=(SELECT oid::bigint FROM pg_database WHERE datname=current_database())
                  AND a.cluster_id=s.cluster_id AND a.site_id=n.site_id AND a.instance_id=n.instance_id AND a.slot=0
                  AND m.mode='CAMPUS' AND m.allocation_slot=0 AND m.state='ACTIVE' AND m.database_fingerprint=md5(n.cluster_identifier||':'||n.database_oid::text)
                  AND l.cluster_id=s.cluster_id AND l.site_id=n.site_id AND l.instance_id=n.instance_id
                  AND l.epoch=s.epoch AND l.state='ACTIVE' AND l.pending_change IS NULL
                FOR SHARE OF s,m
                """,site.getClusterId(),site.getId(),site.getInstanceId());
            require(rows.size()==1,"BOOTSTRAP_SOURCE_IDENTITY_CHANGED");
            try{
                byte[] id=ControlMemberConfiguration.privateBytes(store.directory().resolve("controller-state-id"),36);
                byte[] key=ControlMemberConfiguration.privateBytes(store.directory().resolve("servers.key"),32);
                UUID state=UUID.fromString(new String(id,java.nio.charset.StandardCharsets.US_ASCII));
                require(id.length==36&&key.length==32&&state.equals(deployment.stateIdentity()),"BOOTSTRAP_PLAN_CHANGED");
                require(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.control_process WHERE holder_id=? AND cluster_id=? AND state_id=? AND deployment_protocol=1 AND expires_at>clock_timestamp())",Boolean.class,
                        lease.controllerId(),site.getClusterId(),state)),"CONTROL_LEASE_NOT_HELD");
                var authority=new ControllerSourceSnapshot.Authority(site.getClusterId(),site.getId(),site.getInstanceId(),rows.getFirst().get("fingerprint").toString(),state,
                        ((Number)rows.getFirst().get("epoch")).longValue(),ControllerSourceSnapshot.sha(key));
                ControllerSourceSnapshot.validate(authority);return authority;
            }catch(ControlFailure failure){throw failure;}
            catch(Exception invalid){throw new ControlFailure(409,"BOOTSTRAP_PLAN_CHANGED");}
        });
    }
    private void membership(ControlBootstrapJournal.Spec spec,ControlBootstrapSources.Request request){
        var expected=request.expected();
        require(spec.clusterId().equals(site.getClusterId())&&spec.clusterId().equals(expected.clusterId())
                &&Objects.equals(site.getId(),expected.authoritySiteId())&&Objects.equals(site.getInstanceId(),expected.authorityInstanceId()),"BOOTSTRAP_SOURCE_IDENTITY_CHANGED");
        var rows=jdbc.queryForList("SELECT cluster_id,epoch,coordinator_site,coordinator_instance FROM eatwhat_cluster.state WHERE singleton FOR SHARE");
        require(rows.size()==1&&spec.clusterId().equals(rows.getFirst().get("cluster_id")),"BOOTSTRAP_SOURCE_IDENTITY_CHANGED");
        require(((Number)rows.getFirst().get("epoch")).longValue()==spec.epoch()&&expected.epoch()==spec.epoch(),"STALE_MEMBERSHIP_EPOCH");
        require(expected.authoritySiteId().equals(rows.getFirst().get("coordinator_site"))&&expected.authorityInstanceId().equals(rows.getFirst().get("coordinator_instance")),"BOOTSTRAP_SOURCE_IDENTITY_CHANGED");
        Boolean identity=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM eatwhat_identity.node_state n JOIN eatwhat_identity.allocation a ON a.singleton
              JOIN eatwhat_cluster.member m ON m.site_id=n.site_id AND m.instance_id=n.instance_id
              JOIN eatwhat_cluster.local_state l ON l.singleton
              WHERE n.singleton AND n.site_id=? AND n.instance_id=? AND n.mode='CAMPUS'
                AND n.database_name=current_database() AND n.database_oid=(SELECT oid::bigint FROM pg_database WHERE datname=current_database())
                AND md5(n.cluster_identifier||':'||n.database_oid::text)=?
                AND a.cluster_id=? AND a.site_id=n.site_id AND a.instance_id=n.instance_id AND a.slot=0
                AND m.mode='CAMPUS' AND m.allocation_slot=0 AND m.state='ACTIVE' AND m.database_fingerprint=md5(n.cluster_identifier||':'||n.database_oid::text)
                AND l.cluster_id=a.cluster_id AND l.site_id=n.site_id AND l.instance_id=n.instance_id AND l.epoch=? AND l.state='ACTIVE' AND l.pending_change IS NULL)
            """,Boolean.class,expected.authoritySiteId(),expected.authorityInstanceId(),expected.authorityDatabaseFingerprint(),spec.clusterId(),spec.epoch());
        require(Boolean.TRUE.equals(identity),"BOOTSTRAP_SOURCE_IDENTITY_CHANGED");
    }
    private Local local(String actor,ControlBootstrapJournal.Spec spec,ControlBootstrapSources.Request request,boolean initial){
        managed();String administrator=admin(actor);membership(spec,request);
        // No NFS initialization or key creation during admission.
        UUID state;
        try {
            Path directory=store.directory();byte[] id=ControlMemberConfiguration.privateBytes(directory.resolve("controller-state-id"),36);
            state=UUID.fromString(new String(id,java.nio.charset.StandardCharsets.US_ASCII));
            byte[] key=ControlMemberConfiguration.privateBytes(directory.resolve("servers.key"),32);
            require(id.length==36&&key.length==32&&state.equals(request.expected().stateId())&&state.equals(deployment.stateIdentity())
                    &&you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot.sha(key).equals(request.expected().sharedKeySha256()),"BOOTSTRAP_PLAN_CHANGED");
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_PLAN_CHANGED");}
        var status=lease.status();require(Boolean.TRUE.equals(status.get("localLeader"))&&status.get("localControllerId") instanceof String,"CONTROL_LEASE_NOT_HELD");
        UUID holder=UUID.fromString(status.get("localControllerId").toString());long generation=((Number)status.get("generation")).longValue();
        require(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.control_process WHERE holder_id=? AND cluster_id=? AND state_id=? AND expires_at>clock_timestamp() AND deployment_protocol=1)",Boolean.class,holder,spec.clusterId(),state)),"CONTROL_LEASE_NOT_HELD");
        var source=store.server(spec.sourceServerId());var target=store.server(spec.targetServerId());
        ControlServers.expected(source,spec.sourceRevision());ControlServers.expected(target,spec.targetRevision());
        require(ControlSsh.rootExecution(source)&&ControlSsh.rootExecution(target),"BOOTSTRAP_LINUX_ROOT_REQUIRED");
        if(initial){
            require(target.get("installation") instanceof Map<?,?> install&&"NOT_INSTALLED".equals(install.get("state")),"BOOTSTRAP_TARGET_BUSINESS_RETAINED");
            require(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.member WHERE allocation_slot=?) OR EXISTS(SELECT 1 FROM eatwhat_cluster.installation_allocation WHERE server_id=? OR allocation_slot=?)",Boolean.class,spec.allocationSlot(),spec.targetServerId(),spec.allocationSlot())),"INSTALLATION_ALLOCATION_RETAINED");
            require(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.change WHERE state<>'APPLIED')",Boolean.class)),"MEMBERSHIP_CHANGE_PENDING");
            require(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.deployment_fence WHERE state='ACTIVE')",Boolean.class)),"DEPLOYMENT_RECONCILIATION_REQUIRED");
            require(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.member WHERE state<>'RETIRED'",Long.class)<16,"CLUSTER_MEMBER_LIMIT");
        }
        require(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.installation_allocation WHERE allocation_slot=? AND (server_id<>? OR first_job_id<>? OR cluster_id<>?))",Boolean.class,
                spec.allocationSlot(),spec.targetServerId(),ControlBootstrapJournal.phaseId(spec.id(),ControlBootstrapJournal.Phase.BUSINESS_INSTALL),spec.clusterId())),"INSTALLATION_ALLOCATION_RETAINED");
        String roster=seal(jdbc.queryForList("SELECT site_id,instance_id,profile_id,mode,allocation_slot,state,database_fingerprint,joined_epoch,retired_epoch FROM eatwhat_cluster.member ORDER BY site_id"));
        return new Local(administrator,state,holder,generation,connection(source),connection(target),ssh.routeBinding(source),ssh.routeBinding(target),roster);
    }
    private void inputs(ControlBootstrapJournal.Spec spec,ControlBootstrapPreflights.Captured source,ControlBootstrapPreflights.Captured target,
                        ControlBootstrapSources.Request request,ControlBootstrapSources.Captured captured,ControlBootstrapArtifacts.Frozen artifact){
        require(spec!=null&&spec.id()!=null&&spec.clusterId()!=null&&spec.sourceServerId()!=null&&spec.targetServerId()!=null&&!spec.sourceServerId().equals(spec.targetServerId())
                &&spec.sourceRevision()>0&&spec.targetRevision()>0&&spec.epoch()>0&&spec.allocationSlot()>0&&spec.allocationSlot()<1024,"BOOTSTRAP_INVALID_BINDING");
        require(source.serverId().equals(spec.sourceServerId())&&source.revision()==spec.sourceRevision()&&"SOURCE".equals(source.request().get("role"))
                &&target.serverId().equals(spec.targetServerId())&&target.revision()==spec.targetRevision()&&"TARGET".equals(target.request().get("role"))
                &&captured.serverId().equals(spec.sourceServerId())&&captured.revision()==spec.sourceRevision(),"BOOTSTRAP_INVALID_BINDING");
        require(spec.controllerSha256().equals(artifact.controller().sha256())&&spec.businessSha256().equals(artifact.business().sha256())
                &&spec.releaseId().equals(artifact.releaseId())&&request.probeArtifactSha256().equals(artifact.controller().sha256()),"BOOTSTRAP_ARTIFACT_CHANGED");
        require(captured.privatePayload().get("properties") instanceof Map<?,?>,"BOOTSTRAP_SOURCE_RELEASE_CATALOG_INVALID");
        var sourceProperties=new TreeMap<String,String>();((Map<?,?>)captured.privatePayload().get("properties")).forEach((key,value)->{
            require(key instanceof String&&value instanceof String,"BOOTSTRAP_SOURCE_RELEASE_CATALOG_INVALID");sourceProperties.put(key.toString(),value.toString());
        });ControlBootstrapArtifacts.requireSourceCatalog(artifact,sourceProperties);
        require(List.of("app.control.enabled","app.control.node-enabled","app.control.controller-bootstrap.enabled").stream()
                .allMatch(key->"true".equals(sourceProperties.get(key))),"BOOTSTRAP_SOURCE_PROFILE_RECEIVER_REQUIRED");
        var expected=request.expected();you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot.validate(expected);
        for(var initial:List.of(source,target)){
            var r=initial.request();require(spec.sourceServerId().toString().equals(r.get("sourceServerId"))&&spec.targetServerId().toString().equals(r.get("targetServerId"))
                    &&spec.controllerSha256().equals(r.get("controllerArtifactSha256"))&&expected.stateId().toString().equals(r.get("sharedStateId"))
                    &&r.get("businessAllocationSlot") instanceof Number slot&&slot.intValue()==spec.allocationSlot(),"BOOTSTRAP_INVALID_BINDING");
            var phase=initial==source?ControlBootstrapJournal.Phase.SOURCE_RUNTIME:ControlBootstrapJournal.Phase.TARGET_RUNTIME;
            require(ControlBootstrapJournal.phaseId(spec.id(),phase).toString().equals(r.get("id")),"BOOTSTRAP_INVALID_BINDING");
        }
        require(source.routeBinding().equals(captured.routeBinding()),"SSH_ROUTE_BINDING_CHANGED");
        for(String key:List.of("sourceAddress","targetAddress","targetEndpoint","wireguardPort"))require(Objects.equals(source.request().get(key),target.request().get(key)),"BOOTSTRAP_INVALID_BINDING");
        require(captured.evidence().get("fileHashes") instanceof Map<?,?> files&&expected.applicationSha256().equals(files.get("application.properties"))
                &&expected.profilesSha256().equals(files.get("cluster-profiles.json")),"BOOTSTRAP_PLAN_CHANGED");
    }
    public Frozen freeze(String actor,ControlBootstrapJournal.Spec spec,Map<String,Object> review,ControlBootstrapPreflights.Captured source,ControlBootstrapPreflights.Captured target,
                         ControlBootstrapSources.Request request,ControlBootstrapSources.Captured captured,ControlBootstrapArtifacts.Frozen artifact){
        servers.requireWrite();ControlBootstrapJournal.validateSpec(spec,review);inputs(spec,source,target,request,captured,artifact);
        for(String key:List.of("sourceAddress","targetAddress","targetEndpoint","wireguardPort"))require(Objects.equals(review.get(key),source.request().get(key)),"BOOTSTRAP_INVALID_BINDING");
        artifacts.requireUnchanged(artifact);
        return lease.write(tx,()->{
            var local=local(actor,spec,request,true);
            require(local.sourceRoute().equals(source.routeBinding())&&local.targetRoute().equals(target.routeBinding()),"SSH_ROUTE_BINDING_CHANGED");
            require(captured.evidence().get("proof") instanceof Map<?,?> proof&&proof.get("generation") instanceof Number generation&&generation.longValue()==local.generation(),"BOOTSTRAP_PLAN_CHANGED");
            String payload;try{payload=store.encrypt(JSON.writeValueAsString(captured.privatePayload()));}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_ADMISSION_UNAVAILABLE");}
            var f=new Frozen(spec,Map.copyOf(review),actor,local.admin(),local.state(),local.holder(),local.generation(),local.sourceConnection(),local.targetConnection(),local.sourceRoute(),local.targetRoute(),local.roster(),
                    source,target,request,Map.copyOf(captured.evidence()),payload,artifact,"");
            return new Frozen(spec,f.review(),actor,f.adminBinding(),f.stateId(),f.holderId(),f.generation(),f.sourceConnection(),f.targetConnection(),f.sourceRoute(),f.targetRoute(),f.rosterBinding(),
                    source,target,request,f.sourceEvidence(),payload,artifact,bind(f));
        });
    }
    private void bound(Frozen f){require(f!=null&&Objects.equals(f.binding(),bind(f)),"BOOTSTRAP_PLAN_CHANGED");}
    public void requireBound(Frozen frozen){bound(frozen);}
    /** Observation/projection can continue without a fresh dispatch permission,
     * but must still use the exact registered connection approved originally. */
    void requireRegisteredServer(Frozen f,boolean source,Map<String,Object> row){
        bound(f);ControlServers.expected(row,source?f.spec().sourceRevision():f.spec().targetRevision());
        require((source?f.spec().sourceServerId():f.spec().targetServerId()).toString().equals(row.get("id"))
                &&(source?f.sourceConnection():f.targetConnection()).equals(connection(row)),"SERVER_REVISION_CHANGED");
        require((source?f.sourceRoute():f.targetRoute()).equals(ssh.routeBinding(row)),"SSH_ROUTE_BINDING_CHANGED");
    }
    /** Private planning view of the two registered endpoints, with the same
     * connection and physical-route bindings used by the dispatch fence. */
    Map<UUID,Map<String,Object>> registeredServers(Frozen f){
        bound(f);var result=new LinkedHashMap<UUID,Map<String,Object>>();
        for(boolean source:List.of(true,false)){
            var row=store.server(source?f.spec().sourceServerId():f.spec().targetServerId());
            requireRegisteredServer(f,source,row);
            result.put(source?f.spec().sourceServerId():f.spec().targetServerId(),Map.copyOf(row));
        }return Map.copyOf(result);
    }
    private void compare(Frozen f,Local live){
        require(f.adminBinding().equals(live.admin()),"ADMIN_REQUIRED");
        require(f.stateId().equals(live.state())&&f.holderId().equals(live.holder())&&f.generation()==live.generation(),"BOOTSTRAP_PLAN_CHANGED");
        require(f.sourceConnection().equals(live.sourceConnection())&&f.targetConnection().equals(live.targetConnection()),"SERVER_REVISION_CHANGED");
        require(f.sourceRoute().equals(live.sourceRoute())&&f.targetRoute().equals(live.targetRoute()),"SSH_ROUTE_BINDING_CHANGED");
        require(f.rosterBinding().equals(live.roster()),"BOOTSTRAP_PLAN_CHANGED");
    }
    /** Entire initial recheck before acceptance/first effect. The action only
     * writes its durable local intent, inside a real DB lease/state fence.
     */
    public <T>T beforeEffects(Frozen f,Supplier<T> durableIntent){
        return beforeInitial(f,durableIntent,false);
    }
    /** An accepted task may start while a requested handoff is draining. It
     * still repeats every initial host/source/admin/lease check; only admission
     * of new work is closed during that drain. */
    public <T>T beforeInitialIntent(Frozen f,Supplier<T> durableIntent){
        return beforeInitial(f,durableIntent,true);
    }
    private <T>T beforeInitial(Frozen f,Supplier<T> durableIntent,boolean executing){
        bound(f);if(executing)servers.requireExecuting();else servers.requireWrite();
        // Classify authoritative local drift before a remote probe can mask it
        // as a generic timeout/unavailable error. Release these locks for I/O;
        // the final fence below repeats them around the actual intent write.
        lease.write(tx,()->{compare(f,local(f.actor(),f.spec(),f.sourceRequest(),true));return null;});
        artifacts.requireUnchanged(f.artifacts());
        if(executing){preflights.requireInitialExecutingUnchanged(f.sourceInitial());preflights.requireInitialExecutingUnchanged(f.targetInitial());}
        else{preflights.requireInitialUnchanged(f.sourceInitial());preflights.requireInitialUnchanged(f.targetInitial());}
        var fresh=executing?sources.observeExecuting(f.spec().sourceServerId(),f.spec().sourceRevision(),f.sourceRequest())
                :sources.capture(f.spec().sourceServerId(),f.spec().sourceRevision(),f.sourceRequest());
        require(Objects.equals(f.sourceEvidence().get("captureBinding"),fresh.evidence().get("captureBinding")),"BOOTSTRAP_PLAN_CHANGED");
        return lease.write(tx,()->{compare(f,local(f.actor(),f.spec(),f.sourceRequest(),true));return durableIntent.get();});
    }
    /** Immutable local guard for pre-membership stages, which each also have a
     * native phase-specific remote guard. An own JOIN/projection needs a separately
     * verified successor binding; this method never accepts an arbitrary new epoch.
     */
    public <T>T beforeMembershipIntent(Frozen f,Supplier<T> durableIntent){
        bound(f);servers.requireExecuting();
        lease.write(tx,()->{compare(f,local(f.actor(),f.spec(),f.sourceRequest(),false));return null;});
        artifacts.requireUnchanged(f.artifacts());
        var source=sources.observeExecuting(f.spec().sourceServerId(),f.spec().sourceRevision(),f.sourceRequest());
        require(Objects.equals(f.sourceEvidence().get("captureBinding"),source.evidence().get("captureBinding")),"BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED");
        return lease.write(tx,()->{compare(f,local(f.actor(),f.spec(),f.sourceRequest(),false));return durableIntent.get();});
    }
    /** Only the once-sealed profile operation may use its exact own SOURCE
     * successor. Original admission and epoch are retained, never rebased. */
    public <T>T beforeProfileIntent(ControlBootstrapProfileState.Accepted a,ControlBootstrapJournal journal,Supplier<T> durableIntent){
        ControlBootstrapProfileState.verify(store,journal,this,a);
        var f=a.frozen();servers.requireExecuting();
        lease.write(tx,()->{compare(f,local(f.actor(),f.spec(),f.sourceRequest(),false));return null;});artifacts.requireUnchanged(f.artifacts());
        boolean published=ControlBootstrapProfileState.sourcePublished(store,a);var request=published?a.plans().sourceCaptureRequest():f.sourceRequest();
        var source=sources.observeExecuting(f.spec().sourceServerId(),f.spec().sourceRevision(),request);
        if(published){
            require(canonicalEquals(a.plans().sourceCaptureFileHashes(),source.evidence().get("fileHashes"))
                    &&canonicalEquals(f.sourceEvidence().get("sourceProcess"),source.evidence().get("sourceProcess")),"BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED");
        }else require(Objects.equals(f.sourceEvidence().get("captureBinding"),source.evidence().get("captureBinding")),"BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED");
        return lease.write(tx,()->{compare(f,local(f.actor(),f.spec(),f.sourceRequest(),false));return durableIntent.get();});
    }
    private static boolean canonicalEquals(Object a,Object b){return ControlBootstrapProfileState.canonical(a).equals(ControlBootstrapProfileState.canonical(b));}
    /** Local SOURCE or standby TARGET file/cache application under the
     * original live holder, approving administrator and membership rows.
     * No remote I/O, elections or schema/role changes occur inside this fence. */
    <T>T applyProfile(ControlBootstrapProfileState.Accepted a,ControlBootstrapProfileState.Role role,Supplier<T> effect){
        return applyLocalProfile(a,role,()->{
            var f=a.frozen();membership(f.spec(),f.sourceRequest());
            require(f.rosterBinding().equals(seal(jdbc.queryForList("SELECT site_id,instance_id,profile_id,mode,allocation_slot,state,database_fingerprint,joined_epoch,retired_epoch FROM eatwhat_cluster.member ORDER BY site_id"))),"BOOTSTRAP_PLAN_CHANGED");
            var m=ControlBootstrapBusinessAllocation.identity(f.spec());
            require(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.member WHERE site_id=? OR instance_id=? OR allocation_slot=?) OR EXISTS(SELECT 1 FROM eatwhat_cluster.change WHERE state<>'APPLIED') OR EXISTS(SELECT 1 FROM eatwhat_cluster.deployment_fence WHERE state='ACTIVE')",Boolean.class,
                    m.siteId(),m.instanceId(),m.allocationSlot())),"MEMBERSHIP_CHANGE_PENDING");
            return effect.get();
        });
    }
    private <T>T applyLocalProfile(ControlBootstrapProfileState.Accepted a,ControlBootstrapProfileState.Role role,Supplier<T> effect){
        var f=a.frozen();bound(f);managed();var e=f.sourceRequest().expected();
        require(cluster.getControlLease().getControllerName()!=null&&cluster.getControlLease().getControllerName().equals(ControlBootstrapProfileState.controllerName(a,role))
                &&lease.controllerId().equals(ControlBootstrapProfileState.controllerId(a,role)),"BOOTSTRAP_PROFILE_PROCESS_CHANGED");
        require(site.getClusterId().equals(f.spec().clusterId())&&site.getId().equals(e.authoritySiteId())&&site.getInstanceId().equals(e.authorityInstanceId()),"BOOTSTRAP_SOURCE_IDENTITY_CHANGED");
        try{byte[] state=ControlMemberConfiguration.privateBytes(store.directory().resolve("controller-state-id"),36),key=ControlMemberConfiguration.privateBytes(store.directory().resolve("servers.key"),32);
            require(new String(state,java.nio.charset.StandardCharsets.US_ASCII).equals(f.stateId().toString())&&state.length==36&&key.length==32
                    &&you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot.sha(key).equals(e.sharedKeySha256())&&deployment.stateIdentity().equals(f.stateId()),"BOOTSTRAP_PLAN_CHANGED");
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_PLAN_CHANGED");}
        return lease.writeUnderApprovedHolder(tx,f.holderId(),f.generation(),f.stateId(),()->{
            require(f.adminBinding().equals(admin(f.actor())),"ADMIN_REQUIRED");
            require(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.control_process WHERE holder_id=? AND name=? AND cluster_id=? AND state_id=? AND deployment_protocol=1 AND expires_at>clock_timestamp())",Boolean.class,
                    lease.controllerId(),ControlBootstrapProfileState.controllerName(a,role),f.spec().clusterId(),f.stateId())),"BOOTSTRAP_PROFILE_PROCESS_CHANGED");
            var m=ControlBootstrapBusinessAllocation.identity(f.spec());
            // This reservation is append-only: its trigger rejects every
            // UPDATE/DELETE. A row lock would require UPDATE privileges that
            // the runtime deliberately does not have. The holder/admin fence
            // and immutable identity checks still surround the actual effect.
            var rows=jdbc.queryForList("SELECT cluster_id,site_id,instance_id,mode,allocation_slot,first_job_id FROM eatwhat_cluster.installation_allocation WHERE server_id=?",f.spec().targetServerId());
            require(rows.size()==1&&f.spec().clusterId().equals(rows.getFirst().get("cluster_id"))&&m.siteId().equals(rows.getFirst().get("site_id"))&&m.instanceId().equals(rows.getFirst().get("instance_id"))
                    &&m.mode().equals(rows.getFirst().get("mode"))&&((Number)rows.getFirst().get("allocation_slot")).intValue()==m.allocationSlot()
                    &&ControlBootstrapJournal.phaseId(f.spec().id(),ControlBootstrapJournal.Phase.BUSINESS_INSTALL).equals(rows.getFirst().get("first_job_id")),"INSTALLATION_ALLOCATION_RETAINED");
            return effect.get();
        });
    }
    /** Only the original task's registered member and own JOIN successor are
     * allowed. Nothing updates the original frozen roster/epoch approval. */
    <T>T applyJoin(ControlBootstrapProfileState.Accepted profile,ControlBootstrapMembership.Accepted accepted,ControlBootstrapJournal journal,ClusterStore membership,Supplier<T> effect){
        ControlBootstrapProfileState.verify(store,journal,this,profile);var f=profile.frozen();
        require(accepted.id().equals(f.spec().id())&&accepted.profileBinding().equals(profile.binding())&&accepted.binding().equals(journal.membershipInputBinding(accepted.id()))
                &&Integer.valueOf(2).equals(accepted.configuredReceipt().get("joinProtocolVersion")),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        registeredServers(f);
        return applyLocalProfile(profile,ControlBootstrapProfileState.Role.SOURCE,()->{joinScope(f,accepted,membership);return effect.get();});
    }
    <T>T beforeJoinIntent(ControlBootstrapProfileState.Accepted profile,ControlBootstrapMembership.Accepted accepted,ControlBootstrapJournal journal,ClusterStore membership,boolean initial,Supplier<T> effect){
        ControlBootstrapProfileState.verify(store,journal,this,profile);var f=profile.frozen();servers.requireExecuting();
        lease.write(tx,()->{joinCaller(f,accepted,membership);return null;});
        if(initial){
            artifacts.requireUnchanged(f.artifacts());var source=sources.observeExecuting(f.spec().sourceServerId(),f.spec().sourceRevision(),profile.plans().sourceCaptureRequest());
            require(canonicalEquals(profile.plans().sourceCaptureFileHashes(),source.evidence().get("fileHashes"))
                    &&canonicalEquals(f.sourceEvidence().get("sourceProcess"),source.evidence().get("sourceProcess")),"BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED");
        }
        return lease.write(tx,()->{joinCaller(f,accepted,membership);return effect.get();});
    }
    private void joinCaller(Frozen f,ControlBootstrapMembership.Accepted a,ClusterStore membership){
        bound(f);managed();registeredServers(f);
        var e=f.sourceRequest().expected();require(f.spec().clusterId().equals(site.getClusterId())&&e.authoritySiteId().equals(site.getId())&&e.authorityInstanceId().equals(site.getInstanceId()),"BOOTSTRAP_SOURCE_IDENTITY_CHANGED");
        require(lease.controllerId().equals(f.holderId())&&((Number)lease.status().get("generation")).longValue()==f.generation(),"CONTROL_LEASE_NOT_HELD");
        require(f.adminBinding().equals(admin(f.actor())),"ADMIN_REQUIRED");
        try{byte[] id=ControlMemberConfiguration.privateBytes(store.directory().resolve("controller-state-id"),36),key=ControlMemberConfiguration.privateBytes(store.directory().resolve("servers.key"),32);
            require(id.length==36&&new String(id,java.nio.charset.StandardCharsets.US_ASCII).equals(f.stateId().toString())&&key.length==32&&deployment.stateIdentity().equals(f.stateId())
                    &&you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot.sha(key).equals(f.sourceRequest().expected().sharedKeySha256()),"BOOTSTRAP_PLAN_CHANGED");
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_PLAN_CHANGED");}
        joinScope(f,a,membership);
    }
    <T>T beforeVerifyIntent(ControlBootstrapProfileState.Accepted profile,ControlBootstrapMembership.Accepted accepted,ControlBootstrapJournal journal,ClusterStore membership,Supplier<T> effect){
        ControlBootstrapProfileState.verify(store,journal,this,profile);var f=profile.frozen();servers.requireExecuting();
        require(accepted.id().equals(f.spec().id())&&accepted.profileBinding().equals(profile.binding())&&accepted.binding().equals(journal.membershipInputBinding(accepted.id())),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        lease.write(tx,()->{completedJoin(f,accepted,membership);return null;});artifacts.requireUnchanged(f.artifacts());
        var original=profile.plans().sourceCaptureRequest();var e=original.expected();
        var successor=new you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot.Expected(e.clusterId(),e.authoritySiteId(),e.authorityInstanceId(),e.authorityDatabaseFingerprint(),e.stateId(),f.spec().epoch()+1,e.controllerName(),e.applicationSha256(),e.profilesSha256(),e.sharedKeySha256());
        var request=new ControlBootstrapSources.Request(original.sourceArtifactSha256(),original.probeArtifactSha256(),original.unitSha256(),successor);
        var source=sources.observeExecuting(f.spec().sourceServerId(),f.spec().sourceRevision(),request);
        require(canonicalEquals(profile.plans().sourceCaptureFileHashes(),source.evidence().get("fileHashes"))&&canonicalEquals(f.sourceEvidence().get("sourceProcess"),source.evidence().get("sourceProcess")),"BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED");
        return lease.write(tx,()->{completedJoin(f,accepted,membership);return effect.get();});
    }
    private void completedJoin(Frozen f,ControlBootstrapMembership.Accepted a,ClusterStore membership){
        joinCaller(f,a,membership);var command=membership.ownedJoinedCommand(a.id(),a.binding(),f.actor(),a.joinId(),f.spec().epoch(),ControlBootstrapBusinessAllocation.identity(f.spec()),a.databaseFingerprint());
        require(command.isPresent()&&"APPLIED".equals(command.get().get("state")),"BOOTSTRAP_JOIN_CONFIRMATIONS_REQUIRED");
    }
    private void joinScope(Frozen f,ControlBootstrapMembership.Accepted a,ClusterStore membership){
        var expected=f.sourceRequest().expected();var member=ControlBootstrapBusinessAllocation.identity(f.spec());long base=f.spec().epoch();
        var states=jdbc.queryForList("SELECT cluster_id,epoch,coordinator_site,coordinator_instance FROM eatwhat_cluster.state WHERE singleton FOR UPDATE");
        require(states.size()==1&&f.spec().clusterId().equals(states.getFirst().get("cluster_id"))&&expected.authoritySiteId().equals(states.getFirst().get("coordinator_site"))
                &&expected.authorityInstanceId().equals(states.getFirst().get("coordinator_instance")),"BOOTSTRAP_SOURCE_IDENTITY_CHANGED");
        long epoch=((Number)states.getFirst().get("epoch")).longValue();
        Boolean physical=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM eatwhat_identity.node_state n JOIN eatwhat_identity.allocation a ON a.singleton
              WHERE n.singleton AND n.site_id=? AND n.instance_id=? AND n.mode='CAMPUS'
                AND n.database_name=current_database() AND n.database_oid=(SELECT oid::bigint FROM pg_database WHERE datname=current_database())
                AND md5(n.cluster_identifier||':'||n.database_oid::text)=? AND a.cluster_id=? AND a.site_id=n.site_id AND a.instance_id=n.instance_id AND a.slot=0)
            """,Boolean.class,expected.authoritySiteId(),expected.authorityInstanceId(),expected.authorityDatabaseFingerprint(),f.spec().clusterId());
        require(Boolean.TRUE.equals(physical),"BOOTSTRAP_SOURCE_IDENTITY_CHANGED");
        var roster=jdbc.queryForList("SELECT site_id,instance_id,profile_id,mode,allocation_slot,state,database_fingerprint,joined_epoch,retired_epoch FROM eatwhat_cluster.member ORDER BY site_id FOR SHARE");
        var original=roster.stream().filter(row->!member.siteId().equals(row.get("site_id"))).toList();
        require(f.rosterBinding().equals(seal(original))&&roster.size()==original.size()+1,"BOOTSTRAP_PLAN_CHANGED");
        var target=roster.stream().filter(row->member.siteId().equals(row.get("site_id"))).findFirst().orElseThrow(()->new ControlFailure(409,"BOOTSTRAP_PLAN_CHANGED"));
        require(member.instanceId().equals(target.get("instance_id"))&&member.siteId().equals(target.get("profile_id"))&&member.mode().equals(target.get("mode"))
                &&member.allocationSlot()==((Number)target.get("allocation_slot")).intValue()&&target.get("retired_epoch")==null,"BOOTSTRAP_PLAN_CHANGED");
        require(membership.registeredCommand(f.actor(),a.registerId(),base,member.siteId(),member).isPresent(),"BOOTSTRAP_REGISTRATION_REQUIRED");
        require(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.change WHERE change_id<>? AND state<>'APPLIED') OR EXISTS(SELECT 1 FROM eatwhat_cluster.deployment_fence WHERE state='ACTIVE')",Boolean.class,a.joinId())),"MEMBERSHIP_CHANGE_PENDING");
        var own=membership.ownedJoinedCommand(a.id(),a.binding(),f.actor(),a.joinId(),base,member,a.databaseFingerprint());
        String state=own.map(change->change.get("state").toString()).orElse("REGISTERED");
        boolean before=Set.of("REGISTERED","PREPARING").contains(state);
        require(epoch==(before?base:base+1),"STALE_MEMBERSHIP_EPOCH");
        require((state.equals("REGISTERED")?"REGISTERED":before?"JOINING":"ACTIVE").equals(target.get("state"))
                &&(state.equals("REGISTERED")?target.get("database_fingerprint")==null:a.databaseFingerprint().equals(target.get("database_fingerprint")))
                &&(before?target.get("joined_epoch")==null:target.get("joined_epoch") instanceof Number joined&&joined.longValue()==base+1),"BOOTSTRAP_PLAN_CHANGED");
        var locals=jdbc.queryForList("SELECT cluster_id,site_id,instance_id,epoch,state,pending_change,members::text AS members FROM eatwhat_cluster.local_state WHERE singleton FOR SHARE");
        require(locals.size()==1,"BOOTSTRAP_SOURCE_IDENTITY_CHANGED");var local=locals.getFirst();long localEpoch=((Number)local.get("epoch")).longValue();Object pending=local.get("pending_change");
        require(f.spec().clusterId().equals(local.get("cluster_id"))&&expected.authoritySiteId().equals(local.get("site_id"))&&expected.authorityInstanceId().equals(local.get("instance_id"))
                &&"ACTIVE".equals(local.get("state")),"BOOTSTRAP_SOURCE_IDENTITY_CHANGED");
        boolean valid=switch(state){
            case "REGISTERED"->localEpoch==base&&pending==null;
            case "PREPARING"->localEpoch==base&&(pending==null||a.joinId().equals(pending));
            case "COMMITTED"->(localEpoch==base||localEpoch==base+1)&&a.joinId().equals(pending);
            case "INSTALLED"->localEpoch==base+1&&(pending==null||a.joinId().equals(pending));
            case "APPLIED"->localEpoch==base+1&&pending==null;
            default->false;
        };require(valid,"BOOTSTRAP_JOIN_SUCCESSOR_CHANGED");
        var members=new ArrayList<>(original.stream().filter(row->"ACTIVE".equals(row.get("state"))).map(row->new ClusterMember(row.get("site_id").toString(),(UUID)row.get("instance_id"),row.get("mode").toString(),((Number)row.get("allocation_slot")).intValue())).toList());
        if(localEpoch==base+1)members.add(member);
        List<ClusterMember> actual;try{actual=List.of(JSON.readValue(local.get("members").toString(),ClusterMember[].class));}catch(Exception invalid){throw new ControlFailure(409,"BOOTSTRAP_JOIN_SUCCESSOR_CHANGED");}
        require(actual.size()==members.size()&&new HashSet<>(actual).equals(new HashSet<>(members)),"BOOTSTRAP_JOIN_SUCCESSOR_CHANGED");
        if(own.isPresent()){
            var participants=new ArrayList<>(original.stream().filter(row->"ACTIVE".equals(row.get("state"))).map(row->new ClusterMember(row.get("site_id").toString(),(UUID)row.get("instance_id"),row.get("mode").toString(),((Number)row.get("allocation_slot")).intValue())).toList());participants.sort(Comparator.comparingInt(ClusterMember::allocationSlot));participants.add(member);
            require(participants.equals(own.get().get("participants")),"BOOTSTRAP_JOIN_SUCCESSOR_CHANGED");
        }
    }
    public Map<String,Object> privateSourcePayload(Frozen f){
        bound(f);try{return JSON.readValue(store.decrypt(f.encryptedSourcePayload()),Map.class);}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_ADMISSION_UNAVAILABLE");}
    }
}
