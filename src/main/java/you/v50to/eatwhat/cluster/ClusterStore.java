package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

/** Coordinator-owned, retained membership decisions. Remote I/O never runs inside these transactions. */
public final class ClusterStore {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final ClusterTransactions tx;
    private final SyncTransactions txUnfenced;
    private final UUID clusterId;
    private final NodeIdentity coordinator;
    private final ClusterControlLease lease;
    private final ThreadLocal<ClusterMembershipCoordinator.Boundary> protocolAuthority=new ThreadLocal<>();
    private record BootstrapOwner(UUID bootstrapId,String binding,UUID changeId){}
    private final ThreadLocal<BootstrapOwner> bootstrapOwner=new ThreadLocal<>();
    private boolean singleSiteOnly;
    public ClusterStore singleSiteOnly(boolean value){this.singleSiteOnly=value;return this;}
    public boolean singleSiteOnly(){return singleSiteOnly;}
    public ClusterStore(JdbcTemplate jdbc,SyncTransactions tx,UUID clusterId,NodeIdentity coordinator){
        this(jdbc,tx,clusterId,coordinator,null);
    }
    public ClusterStore(JdbcTemplate jdbc,SyncTransactions tx,UUID clusterId,NodeIdentity coordinator,ClusterControlLease lease){
        this.jdbc=jdbc;this.tx=new ClusterTransactions(tx,lease,jdbc);this.txUnfenced=tx;this.clusterId=Objects.requireNonNull(clusterId);this.coordinator=Objects.requireNonNull(coordinator);this.lease=lease;tx.requireSameDataSource(jdbc);
    }
    public void initialize(){ClusterBarrier.internal(()->txUnfenced.required(()->{
        jdbc.query("SELECT pg_advisory_xact_lock(1161909335,36)",rs->{});
        jdbc.update("INSERT INTO eatwhat_cluster.state(singleton,cluster_id,coordinator_site,coordinator_instance) VALUES(true,?,?,?) ON CONFLICT DO NOTHING",clusterId,coordinator.siteId(),coordinator.instanceId());
        var state=lock();if(!clusterId.equals(state.get("cluster_id"))||!coordinator.siteId().equals(state.get("coordinator_site"))||!coordinator.instanceId().equals(state.get("coordinator_instance")))throw failure("CLUSTER_BINDING_MISMATCH");
        if(lease==null&&jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.control_lease WHERE singleton",Long.class)>0)throw new ClusterFailure(503,"CONTROL_LEASE_REQUIRED");
        return null;
    }));}
    void requireLeadership(){
        var scope=protocolAuthority.get();if(scope!=null){requireRemoteContext();scope.check("PROTOCOL_PEER");return;}
        if(lease!=null)lease.requireHeld();else if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.control_lease WHERE singleton",Long.class)>0)throw new ClusterFailure(503,"CONTROL_LEASE_REQUIRED");
    }
    <T> T fencedWrite(Supplier<T> work){var scope=protocolAuthority.get();return scope==null?tx.required(work):ClusterMembershipCoordinator.fenced(scope,"PROTOCOL_PROGRESS",()->tx.required(work));}
    /** Only a coordinator's synchronous remote callback may enter this scope.
     * Every HTTP/file/account dispatch rechecks its task boundary. Synchronizer
     * progress uses a fresh real SQL fence; ordinary SQL commands keep their
     * usual lease requirement. The scope is neither inherited nor retained. */
    <T>T remoteProtocol(ClusterMembershipCoordinator.Boundary boundary,Supplier<T> work){
        requireRemoteContext();if(protocolAuthority.get()!=null)throw failure("MEMBERSHIP_BOUNDARY_INVALID");
        protocolAuthority.set(boundary);try{return work.get();}finally{protocolAuthority.remove();}
    }
    private static void requireRemoteContext(){if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw failure("MEMBERSHIP_REMOTE_TRANSACTION_FORBIDDEN");}
    <T>T ownedJoin(UUID bootstrapId,String binding,UUID changeId,Supplier<T> work){
        if(bootstrapId==null||changeId==null||binding==null||!binding.matches("[A-Za-z0-9+/]{43}="))throw failure("INVALID_BOOTSTRAP_MEMBERSHIP_OWNER");
        if(bootstrapOwner.get()!=null)throw failure("MEMBERSHIP_BOUNDARY_INVALID");
        bootstrapOwner.set(new BootstrapOwner(bootstrapId,binding,changeId));try{return work.get();}finally{bootstrapOwner.remove();}
    }
    private Map<String,Object> owner(UUID id){var scope=bootstrapOwner.get();if(scope==null||!scope.changeId().equals(id))throw failure("BOOTSTRAP_MEMBERSHIP_TASK_REQUIRED");return Map.of("bootstrapId",scope.bootstrapId().toString(),"binding",scope.binding());}
    private Optional<Map<String,Object>> savedOwner(UUID id){
        var rows=jdbc.queryForList("SELECT result_json->'bootstrapOwner' AS owner FROM eatwhat_cluster.command WHERE request_id=? AND result_json->'bootstrapOwner' IS NOT NULL",id);
        if(rows.isEmpty())return Optional.empty();
        try{Map<String,Object> value=JSON.readValue(rows.getFirst().get("owner").toString(),Map.class);
            if(!value.keySet().equals(Set.of("bootstrapId","binding"))||!UUID.fromString(value.get("bootstrapId").toString()).toString().equals(value.get("bootstrapId"))
                    ||!(value.get("binding") instanceof String binding)||!binding.matches("[A-Za-z0-9+/]{43}="))throw failure("JOIN_PROOF_CHANGED");
            return Optional.of(value);
        }catch(ClusterFailure failure){throw failure;}catch(Exception invalid){throw failure("JOIN_PROOF_CHANGED");}
    }
    void requireChangeOwner(UUID id){var saved=savedOwner(id);if(saved.isPresent()&&!saved.get().equals(owner(id)))throw failure("BOOTSTRAP_MEMBERSHIP_OWNER_CHANGED");}
    public Optional<Map<String,Object>> ownedJoinedCommand(UUID bootstrapId,String binding,String actor,UUID request,long expectedEpoch,ClusterMember member,String fingerprint){
        var command=joinedCommand(actor,request,expectedEpoch,member,fingerprint);
        if(command.isPresent()&&!savedOwner(request).orElseThrow(()->failure("BOOTSTRAP_MEMBERSHIP_OWNER_CHANGED")).equals(Map.of("bootstrapId",bootstrapId.toString(),"binding",binding)))throw failure("BOOTSTRAP_MEMBERSHIP_OWNER_CHANGED");
        return command;
    }
    /** One database-enforced read-only, repeatable snapshot. This intentionally
     * bypasses write/lease contexts without entering any mutation capability. */
    public record OwnedJoinSnapshot(UUID clusterId,long epoch,Optional<Map<String,Object>> registration,Optional<Map<String,Object>> change,List<Map<String,Object>> members){
        @Override public String toString(){return "OwnedJoinSnapshot[redacted]";}
    }
    public OwnedJoinSnapshot inspectOwnedJoin(UUID bootstrapId,String binding,String actor,UUID registerId,UUID joinId,long expectedEpoch,ClusterMember member,String fingerprint){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                ||org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw failure("MEMBERSHIP_OBSERVATION_TRANSACTION_FORBIDDEN");
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));manager.setEnforceReadOnly(true);
        var read=new org.springframework.transaction.support.TransactionTemplate(manager);
        read.setReadOnly(true);read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);read.setTimeout(10);
        return read.execute(ignored->{
            var state=jdbc.queryForMap("SELECT cluster_id,epoch FROM eatwhat_cluster.state WHERE singleton");
            if(!clusterId.equals(state.get("cluster_id")))throw failure("CLUSTER_BINDING_MISMATCH");
            var registered=registeredCommand(actor,registerId,expectedEpoch,member.siteId(),member);
            var joined=ownedJoinedCommand(bootstrapId,binding,actor,joinId,expectedEpoch,member,fingerprint);
            var members=jdbc.queryForList("SELECT site_id,instance_id,profile_id,mode,allocation_slot,state,database_fingerprint,joined_epoch,retired_epoch FROM eatwhat_cluster.member ORDER BY allocation_slot");
            return new OwnedJoinSnapshot(clusterId,((Number)state.get("epoch")).longValue(),registered,joined,List.copyOf(members));
        });
    }
    ClusterControlLease lease(){return lease;}
    Map<String,Object> lock(){var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.state WHERE singleton FOR UPDATE");if(rows.size()!=1)throw failure("CLUSTER_NOT_INITIALIZED");return rows.getFirst();}
    public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();result.put("clusterId",clusterId.toString());
        result.put("singleSiteOnly",singleSiteOnly);
        result.put("epoch",jdbc.queryForObject("SELECT epoch FROM eatwhat_cluster.state WHERE singleton",Long.class));
        result.put("members",jdbc.queryForList("SELECT site_id,instance_id,profile_id,mode,allocation_slot,state,database_fingerprint,joined_epoch,retired_epoch FROM eatwhat_cluster.member ORDER BY allocation_slot"));
        result.put("pendingChanges",jdbc.queryForList("SELECT change_id FROM eatwhat_cluster.change WHERE state<>'APPLIED' ORDER BY created_at",UUID.class).stream().map(this::change).toList());return result;
    }
    public List<ClusterMember> active(){return jdbc.query("SELECT site_id,instance_id,mode,allocation_slot FROM eatwhat_cluster.member WHERE state IN ('ACTIVE','DRAINING') ORDER BY allocation_slot",(r,n)->new ClusterMember(r.getString(1),r.getObject(2,UUID.class),r.getString(3),r.getInt(4)));}
    public Map<String,Object> installationAllocation(UUID serverId){var rows=jdbc.queryForList("SELECT cluster_id,site_id,instance_id,mode,allocation_slot,first_job_id FROM eatwhat_cluster.installation_allocation WHERE server_id=?",serverId);return rows.isEmpty()?Map.of():rows.getFirst();}
    public void reserveInstallation(String actor,UUID jobId,UUID serverId,ClusterMember member){
        if(member.mode().equals("CAMPUS")&&member.allocationSlot()!=0||member.mode().equals("EXTERNAL")&&member.allocationSlot()==0)throw failure("CLUSTER_ROLE_SLOT_MISMATCH");
        command(jobId,List.of("INSTALL_ALLOCATION",serverId,member),()->{
            var state=lock();if(member.mode().equals("EXTERNAL")&&epoch(state)<1)throw failure("COORDINATOR_MUST_JOIN_FIRST");if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.change WHERE state<>'APPLIED'",Long.class)>0)throw failure("MEMBERSHIP_CHANGE_PENDING");
            if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.deployment_fence WHERE state='ACTIVE'",Long.class)>0)throw failure("DEPLOYMENT_RECONCILIATION_REQUIRED");
            var saved=installationAllocation(serverId);
            if(saved.isEmpty()){
                if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.installation_allocation WHERE site_id=? OR instance_id=? OR allocation_slot=?",Long.class,member.siteId(),member.instanceId(),member.allocationSlot())>0)throw failure("INSTALLATION_ALLOCATION_RETAINED");
                var retained=jdbc.queryForList("SELECT site_id,instance_id,mode,allocation_slot FROM eatwhat_cluster.member WHERE site_id=? OR instance_id=? OR allocation_slot=?",member.siteId(),member.instanceId(),member.allocationSlot());
                if(retained.stream().anyMatch(r->!fromRow(r).equals(member)))throw failure("MEMBER_IDENTITY_OR_SLOT_RETAINED");
                jdbc.update("INSERT INTO eatwhat_cluster.installation_allocation(server_id,cluster_id,site_id,instance_id,mode,allocation_slot,first_job_id) VALUES(?,?,?,?,?,?,?)",serverId,clusterId,member.siteId(),member.instanceId(),member.mode(),member.allocationSlot(),jobId);
            }else if(!clusterId.equals(saved.get("cluster_id"))||!fromRow(saved).equals(member))throw failure("INSTALLATION_ALLOCATION_RETAINED");
            audit(jobId,actor,"INSTALL_ALLOCATE",member.siteId(),epoch(lock()));return Map.of("serverId",serverId.toString(),"siteId",member.siteId(),"allocationSlot",member.allocationSlot());
        });
    }
    public Map<String,Object> register(String actor,UUID request,long expectedEpoch,String profile,ClusterMember member){
        if(singleSiteOnly&&!member.identity().equals(coordinator))throw failure("SINGLE_SITE_CLUSTER_ONLY");
        name(profile);return command(request,List.of("REGISTER",expectedEpoch,profile,member),()->{
            long epoch=epoch(lock());requireEpoch(epoch,expectedEpoch);
            var existing=jdbc.queryForList("SELECT * FROM eatwhat_cluster.member WHERE site_id=?",member.siteId());
            if(!existing.isEmpty())throw failure("SITE_ID_RETAINED");
            if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.member WHERE state<>'RETIRED'",Long.class)>=16)throw failure("CLUSTER_MEMBER_LIMIT");
            if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.member WHERE instance_id=? OR allocation_slot=? OR profile_id=?",Long.class,member.instanceId(),member.allocationSlot(),profile)>0)throw failure("MEMBER_IDENTITY_OR_SLOT_RETAINED");
            if(member.mode().equals("CAMPUS")&&(!member.identity().equals(coordinator)||member.allocationSlot()!=0)||member.mode().equals("EXTERNAL")&&member.allocationSlot()==0)throw failure("CLUSTER_ROLE_SLOT_MISMATCH");
            jdbc.update("INSERT INTO eatwhat_cluster.member(site_id,instance_id,profile_id,mode,allocation_slot,state) VALUES(?,?,?,?,?,'REGISTERED')",member.siteId(),member.instanceId(),profile,member.mode(),member.allocationSlot());
            audit(request,actor,"REGISTER",member.siteId(),epoch);return Map.of("siteId",member.siteId(),"state","REGISTERED","epoch",epoch);
        });
    }
    /** Read-only reconciliation of an original registration command. A missing
     * command is unknown, never permission to send REGISTER again. */
    public Optional<Map<String,Object>> registeredCommand(String actor,UUID request,long expectedEpoch,String profile,ClusterMember member){
        name(profile);var rows=jdbc.queryForList("SELECT request_hash,result_json::text AS result FROM eatwhat_cluster.command WHERE request_id=?",request);
        if(rows.isEmpty())return Optional.empty();
        if(rows.size()!=1||!hash(List.of("REGISTER",expectedEpoch,profile,member)).equals(rows.getFirst().get("request_hash")))throw failure("IDEMPOTENCY_CONFLICT");
        Map<String,Object> result=JSON.readValue(rows.getFirst().get("result").toString(),Map.class);
        if(!member.siteId().equals(result.get("siteId"))||!"REGISTERED".equals(result.get("state"))||!(result.get("epoch") instanceof Number epoch)||epoch.longValue()!=expectedEpoch
                ||jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.audit WHERE request_id=? AND actor=? AND action='REGISTER' AND site_id=? AND epoch=?",Long.class,request,actor,member.siteId(),expectedEpoch)!=1)
            throw failure("REGISTRATION_PROOF_CHANGED");
        return Optional.of(Map.copyOf(result));
    }
    /** Original JOIN evidence, including the retained command, preparing actor
     * and immutable participant set. Observation never invokes BEGIN again. */
    public Optional<Map<String,Object>> joinedCommand(String actor,UUID request,long expectedEpoch,ClusterMember member,String fingerprint){
        if(expectedEpoch<0||expectedEpoch==Long.MAX_VALUE||fingerprint==null||!fingerprint.matches("[0-9a-f]{32}"))throw failure("INVALID_JOIN_PROOF");
        var commands=jdbc.queryForList("SELECT request_hash,result_json::text AS result FROM eatwhat_cluster.command WHERE request_id=?",request);
        var changes=jdbc.queryForList("SELECT request_hash,actor FROM eatwhat_cluster.change WHERE change_id=?",request);
        if(commands.isEmpty()){if(!changes.isEmpty())throw failure("JOIN_PROOF_CHANGED");return Optional.empty();}
        String expectedHash=hash(List.of("JOIN",expectedEpoch,member.siteId(),fingerprint));
        if(commands.size()!=1||!expectedHash.equals(commands.getFirst().get("request_hash")))throw failure("IDEMPOTENCY_CONFLICT");
        if(changes.size()!=1||!expectedHash.equals(changes.getFirst().get("request_hash"))||!Objects.equals(actor,changes.getFirst().get("actor")))throw failure("JOIN_PROOF_CHANGED");
        var current=change(request);Map<String,Object> original;
        try{original=JSON.readValue(commands.getFirst().get("result").toString(),Map.class);}catch(Exception invalid){throw failure("JOIN_PROOF_CHANGED");}
        var keys=new HashSet<>(original.keySet());if(keys.remove("bootstrapOwner"))savedOwner(request).orElseThrow(()->failure("JOIN_PROOF_CHANGED"));
        if(!keys.equals(Set.of("change_id","base_epoch","next_epoch","kind","site_id","state","participants","votes"))
                ||!request.toString().equals(Objects.toString(original.get("change_id")))||!"PREPARING".equals(original.get("state"))
                ||!List.of().equals(original.get("votes")))throw failure("JOIN_PROOF_CHANGED");
        for(var proof:List.of(original,current)){
            if(!"JOIN".equals(proof.get("kind"))||!member.siteId().equals(proof.get("site_id"))
                    ||!(proof.get("base_epoch") instanceof Number base)||base.longValue()!=expectedEpoch
                    ||!(proof.get("next_epoch") instanceof Number next)||next.longValue()!=expectedEpoch+1)throw failure("JOIN_PROOF_CHANGED");
        }
        List<ClusterMember> participants;
        try{participants=List.of(JSON.convertValue(original.get("participants"),ClusterMember[].class));}catch(Exception invalid){throw failure("JOIN_PROOF_CHANGED");}
        if(!participants.equals(current.get("participants"))||!participants.contains(member)
                ||jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.audit WHERE request_id=? AND action='JOIN_PREPARE'",Long.class,request)!=1
                ||jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.audit WHERE request_id=? AND actor=? AND action='JOIN_PREPARE' AND site_id=? AND epoch=?",Long.class,request,actor,member.siteId(),expectedEpoch)!=1)
            throw failure("JOIN_PROOF_CHANGED");
        return Optional.of(Collections.unmodifiableMap(current));
    }
    public Map<String,Object> begin(String actor,UUID request,long expectedEpoch,String kind,String site,String fingerprint){
        name(site);if(!Set.of("JOIN","RETIRE").contains(kind))throw new ClusterFailure(400,"INVALID_MEMBERSHIP_ACTION");
        if(fingerprint==null||!fingerprint.matches("[0-9a-f]{32}"))throw failure("DATABASE_PROOF_REQUIRED");
        return command(request,List.of(kind,expectedEpoch,site,fingerprint),()->{
            long epoch=epoch(lock());requireEpoch(epoch,expectedEpoch);quiescent();
            var target=member(site);if(!target.get("state").equals(kind.equals("JOIN")?"REGISTERED":"ACTIVE"))throw failure("MEMBER_STATE_MISMATCH");
            if(kind.equals("JOIN")&&((Number)target.get("allocation_slot")).intValue()>=2&&jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.member WHERE allocation_slot=1 AND state='ACTIVE'",Long.class)!=1)throw failure("BOOTSTRAP_PAIR_REQUIRED");
            if(kind.equals("RETIRE")&&site.equals(coordinator.siteId()))throw failure("COORDINATOR_RETIRE_REQUIRES_TRANSFER");
            if(target.get("database_fingerprint")!=null&&!fingerprint.equals(target.get("database_fingerprint")))throw failure("DATABASE_IDENTITY_CHANGED");
            if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.member WHERE database_fingerprint=? AND site_id<>?",Long.class,fingerprint,site)>0)throw failure("SAME_DATABASE_REJECTED");
            var participants=new ArrayList<>(active());if(kind.equals("JOIN"))participants.add(fromRow(target));
            if(participants.isEmpty()||participants.size()>16)throw failure("INVALID_PARTICIPANT_SET");
            if(epoch==0&&(!site.equals(coordinator.siteId())||!target.get("instance_id").equals(coordinator.instanceId())))throw failure("COORDINATOR_MUST_JOIN_FIRST");
            jdbc.update("UPDATE eatwhat_cluster.member SET state=?,database_fingerprint=? WHERE site_id=?",kind.equals("JOIN")?"JOINING":"DRAINING",fingerprint,site);
            jdbc.update("INSERT INTO eatwhat_cluster.change(change_id,request_hash,base_epoch,next_epoch,kind,site_id,state,participants,actor) VALUES(?,?,?,?,?,?,'PREPARING',?::jsonb,?)",request,hash(List.of(kind,expectedEpoch,site,fingerprint)),epoch,epoch+1,kind,site,json(participants),actor);
            audit(request,actor,kind+"_PREPARE",site,epoch);var result=change(request);
            if(bootstrapOwner.get()!=null){if(!kind.equals("JOIN"))throw failure("INVALID_BOOTSTRAP_MEMBERSHIP_OWNER");result.put("bootstrapOwner",owner(request));}
            return result;
        });
    }
    public Map<String,Object> cancelRegistration(String actor,UUID request,long expectedEpoch,String site){name(site);return command(request,List.of("CANCEL_REGISTRATION",expectedEpoch,site),()->{
        long epoch=epoch(lock());requireEpoch(epoch,expectedEpoch);var member=member(site);if(!member.get("state").equals("REGISTERED"))throw failure("MEMBER_STATE_MISMATCH");
        jdbc.update("UPDATE eatwhat_cluster.member SET state='RETIRED',retired_epoch=? WHERE site_id=?",epoch,site);audit(request,actor,"CANCEL_REGISTRATION",site,epoch);return Map.of("siteId",site,"state","RETIRED","epoch",epoch);
    });}
    public Map<String,Object> change(UUID id){var rows=jdbc.queryForList("SELECT change_id,base_epoch,next_epoch,kind,site_id,state,participants::text AS participants FROM eatwhat_cluster.change WHERE change_id=?",id);if(rows.isEmpty())throw new ClusterFailure(404,"CHANGE_NOT_FOUND");var result=new LinkedHashMap<>(rows.getFirst());result.put("participants",parseMembers((String)result.get("participants")));result.put("votes",jdbc.queryForList("SELECT site_id,instance_id,phase,created_at FROM eatwhat_cluster.change_vote WHERE change_id=? ORDER BY created_at,site_id,phase",id).stream().map(ClusterStore::external).toList());return result;}
    public List<Map<String,Object>> changes(int limit){bounded(limit);return jdbc.queryForList("SELECT change_id FROM eatwhat_cluster.change ORDER BY created_at DESC LIMIT ?",UUID.class,limit).stream().map(this::change).toList();}
    public List<Map<String,Object>> audit(int limit){bounded(limit);return jdbc.queryForList("SELECT id,request_id,actor,action,site_id,epoch,created_at FROM eatwhat_cluster.audit ORDER BY id DESC LIMIT ?",limit).stream().map(ClusterStore::external).toList();}
    static void bounded(int limit){if(limit<1||limit>100)throw new ClusterFailure(400,"INVALID_LIMIT");}
    static Map<String,Object> external(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();row.forEach((key,value)->result.put(key,value instanceof java.sql.Timestamp timestamp?timestamp.toInstant().toString():value));return result;}
    public List<ClusterMember> participants(UUID change){return (List<ClusterMember>)change(change).get("participants");}
    /** Package-scoped: votes come from the authenticated node gateway, never an admin-supplied boolean. */
    void vote(UUID change,ClusterMember actual,String phase,String proofHash){tx.required(()->{
        lock();requireChangeOwner(change);var c=change(change);if(!Set.of("PREPARED","APPLIED","RELEASED").contains(phase)||phase.equals("APPLIED")&&c.get("state").equals("PREPARING")||phase.equals("RELEASED")&&!Set.of("INSTALLED","APPLIED").contains(c.get("state")))throw failure("INVALID_CHANGE_PHASE");
        if(!((List<ClusterMember>)c.get("participants")).contains(actual))throw failure("UNEXPECTED_PARTICIPANT");
        if(proofHash==null||!proofHash.matches("[0-9a-f]{64}"))throw failure("INVALID_NODE_PROOF");
        jdbc.update("INSERT INTO eatwhat_cluster.change_vote VALUES(?,?,?,?,?,clock_timestamp()) ON CONFLICT DO NOTHING",change,actual.siteId(),actual.instanceId(),phase,proofHash);
        var saved=jdbc.queryForMap("SELECT instance_id,proof_hash FROM eatwhat_cluster.change_vote WHERE change_id=? AND site_id=? AND phase=?",change,actual.siteId(),phase);
        if(!actual.instanceId().equals(saved.get("instance_id"))||!proofHash.equals(saved.get("proof_hash")))throw failure("NODE_PROOF_CONFLICT");return null;
    });}
    public Map<String,Object> decide(String actor,UUID id){return tx.required(()->{
        var s=lock();requireChangeOwner(id);var c=change(id);if(!c.get("state").equals("PREPARING"))return c;
        requireEpoch(epoch(s),((Number)c.get("base_epoch")).longValue());requireVotes(id,c,"PREPARED");
        long next=((Number)c.get("next_epoch")).longValue();String target=(String)c.get("site_id");
        jdbc.update("UPDATE eatwhat_cluster.state SET epoch=? WHERE singleton",next);
        jdbc.update("UPDATE eatwhat_cluster.change SET state='COMMITTED' WHERE change_id=?",id);
        if(c.get("kind").equals("JOIN"))jdbc.update("UPDATE eatwhat_cluster.member SET state='ACTIVE',joined_epoch=? WHERE site_id=?",next,target);
        else jdbc.update("UPDATE eatwhat_cluster.member SET state='RETIRED',retired_epoch=? WHERE site_id=?",next,target);
        audit(id,actor,"MEMBERSHIP_COMMIT",target,next);return change(id);
    });}
    public Map<String,Object> finish(String actor,UUID id){return tx.required(()->{
        lock();requireChangeOwner(id);var c=change(id);if(Set.of("INSTALLED","APPLIED").contains(c.get("state")))return c;if(!c.get("state").equals("COMMITTED"))throw failure("MEMBERSHIP_NOT_COMMITTED");
        requireVotes(id,c,"APPLIED");jdbc.update("UPDATE eatwhat_cluster.change SET state='INSTALLED' WHERE change_id=?",id);audit(id,actor,"MEMBERSHIP_INSTALLED",(String)c.get("site_id"),((Number)c.get("next_epoch")).longValue());return change(id);
    });}
    public Map<String,Object> released(String actor,UUID id){return tx.required(()->{
        lock();requireChangeOwner(id);var c=change(id);if(c.get("state").equals("APPLIED"))return c;if(!c.get("state").equals("INSTALLED"))throw failure("MEMBERSHIP_NOT_INSTALLED");
        requireVotes(id,c,"RELEASED");jdbc.update("UPDATE eatwhat_cluster.change SET state='APPLIED' WHERE change_id=?",id);audit(id,actor,"MEMBERSHIP_APPLIED",(String)c.get("site_id"),((Number)c.get("next_epoch")).longValue());return change(id);
    });}
    private void requireVotes(UUID id,Map<String,Object> change,String phase){for(var participant:(List<ClusterMember>)change.get("participants"))if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.change_vote WHERE change_id=? AND site_id=? AND instance_id=? AND phase=?",Long.class,id,participant.siteId(),participant.instanceId(),phase)!=1)throw failure("MEMBERSHIP_CONFIRMATIONS_PENDING");}
    public Map<String,Object> member(String site){var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.member WHERE site_id=?",site);if(rows.isEmpty())throw new ClusterFailure(404,"MEMBER_NOT_FOUND");return rows.getFirst();}
    public String profile(String site){return (String)member(site).get("profile_id");}
    private void quiescent(){if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.change WHERE state<>'APPLIED'",Long.class)>0)throw failure("MEMBERSHIP_CHANGE_PENDING");if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.gc_operation WHERE state<>'DELETED'",Long.class)>0)throw failure("GC_DECISIONS_PENDING");}
    private Map<String,Object> command(UUID id,Object input,Supplier<Map<String,Object>> action){Objects.requireNonNull(id);String hash=hash(input);return tx.required(()->{
        lock();var prior=jdbc.queryForList("SELECT request_hash,result_json::text AS result FROM eatwhat_cluster.command WHERE request_id=?",id);
        if(!prior.isEmpty()){if(!hash.equals(prior.getFirst().get("request_hash")))throw failure("IDEMPOTENCY_CONFLICT");return JSON.readValue((String)prior.getFirst().get("result"),Map.class);}
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.deployment_fence WHERE cluster_id=? AND state='ACTIVE')",Boolean.class,clusterId)))throw failure("DEPLOYMENT_ACTIVE");
        var result=action.get();jdbc.update("INSERT INTO eatwhat_cluster.command(request_id,request_hash,result_json) VALUES(?,?,?::jsonb)",id,hash,json(result));return result;
    });}
    private void audit(UUID request,String actor,String action,String site,long epoch){if(actor==null||actor.isBlank()||actor.length()>128)throw new ClusterFailure(400,"INVALID_ACTOR");jdbc.update("INSERT INTO eatwhat_cluster.audit(request_id,actor,action,site_id,epoch) VALUES(?,?,?,?,?)",request,actor,action,site,epoch);}
    private static long epoch(Map<String,Object> state){return ((Number)state.get("epoch")).longValue();}
    private static void requireEpoch(long current,long expected){if(current!=expected)throw failure("STALE_MEMBERSHIP_EPOCH");}
    private static ClusterMember fromRow(Map<String,Object> row){return new ClusterMember((String)row.get("site_id"),(UUID)row.get("instance_id"),(String)row.get("mode"),((Number)row.get("allocation_slot")).intValue());}
    static List<ClusterMember> parseMembers(String json){return List.of(JSON.readValue(json,ClusterMember[].class));}
    static String json(Object value){return JSON.writeValueAsString(value);}
    static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(value).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    static void name(String value){if(value==null||!value.matches("[a-zA-Z0-9_-]{1,64}"))throw new ClusterFailure(400,"INVALID_CLUSTER_IDENTIFIER");}
    static ClusterFailure failure(String code){return new ClusterFailure(409,code);}
}
