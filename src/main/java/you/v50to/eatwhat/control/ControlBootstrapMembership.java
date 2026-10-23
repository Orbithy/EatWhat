package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.ObjectProvider;
import you.v50to.eatwhat.cluster.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Original configuration, once-only REGISTER/BEGIN and guarded resumable JOIN.
 * Frozen approval is retained; only its own durable change may advance epoch. */
public final class ControlBootstrapMembership {
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final ControlServerStore store;private final ControlBootstrapJournal journal;private final ControlBootstrapAdmission admission;
    private final ControlBootstrapProfileState profiles;private final ControlBootstrapMemberRuntime runtime;
    private final ObjectProvider<ClusterStore> membership;private final ObjectProvider<ClusterMembershipCoordinator> coordinator;
    private final ControlBootstrapNativePhases nativePhases;private final ControlBootstrapProfileReceiver receiver;
    public ControlBootstrapMembership(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapAdmission admission,ControlBootstrapProfileState profiles,
            ControlBootstrapMemberRuntime runtime,ObjectProvider<ClusterStore> membership,ObjectProvider<ClusterMembershipCoordinator> coordinator,ControlBootstrapNativePhases nativePhases,ControlBootstrapProfileReceiver receiver){
        this.store=store;this.journal=journal;this.admission=admission;this.profiles=profiles;this.runtime=runtime;this.membership=membership;this.coordinator=coordinator;this.nativePhases=nativePhases;this.receiver=receiver;}
    public record Accepted(UUID id,String profileBinding,String databaseFingerprint,UUID registerId,UUID joinId,Map<String,Object> configuredReceipt,String binding){
        public Accepted{configuredReceipt=Map.copyOf(configuredReceipt);}
        @Override public String toString(){return "BootstrapMembershipAcceptance[redacted]";}
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private static UUID operation(UUID id,String name){return UUID.nameUUIDFromBytes((id+":"+name).getBytes(StandardCharsets.UTF_8));}
    private Path input(UUID id){return store.directory().resolve("controller-membership-input-"+id+".json");}
    private Path ledger(UUID id){return store.directory().resolve("controller-membership-operations-"+id+".json");}
    private Path joinLedger(UUID id){return store.directory().resolve("controller-membership-join-"+id+".json");}
    private String binding(Accepted a){return store.fingerprint(ControlBootstrapNativeJson.encode(List.of(a.id().toString(),a.profileBinding(),a.databaseFingerprint(),a.registerId().toString(),a.joinId().toString(),a.configuredReceipt())));}
    private void save(Path path,Object value){String raw=JSON.writeValueAsString(value);store.saveJson(path,Map.of("version",1,"body",store.encrypt(raw),"seal",store.fingerprint(raw)));}
    private String raw(Path path){
        require(Files.exists(path,LinkOption.NOFOLLOW_LINKS),"BOOTSTRAP_MEMBERSHIP_RECORD_MISSING");var value=store.readJson(path);require(Integer.valueOf(1).equals(value.get("version")),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        String raw=store.decrypt(value.get("body").toString());require(store.fingerprint(raw).equals(value.get("seal")),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");return raw;
    }
    private <T>T locked(Supplier<T> work){return locked(store.directory().resolve("membership-bootstrap-operations.lock"),work);}
    private <T>T locked(Path path,Supplier<T> work){
        try{try{Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException ignored){}
            ControlServerStore.privateFile(path,1024);try(var channel=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new ControlFailure(409,"BOOTSTRAP_MEMBERSHIP_BUSY");}require(lock!=null,"BOOTSTRAP_MEMBERSHIP_BUSY");try(lock){return work.get();}}
        }catch(ControlFailure failure){throw failure;}catch(ClusterFailure failure){throw new ControlFailure(failure.status(),failure.getMessage());}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_MEMBERSHIP_STATE_UNAVAILABLE");}
    }
    private ControlBootstrapProfileState.Accepted profile(Accepted a){var p=profiles.original(a.id());require(p.binding().equals(a.profileBinding()),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        require(profiles.progress(p).values().stream().allMatch("SUCCEEDED"::equals),"BOOTSTRAP_PROFILES_NOT_READY");return p;}
    private void validate(Accepted a){
        require(a.id()!=null&&a.registerId()!=null&&a.joinId()!=null&&a.databaseFingerprint()!=null&&a.databaseFingerprint().matches("[a-f0-9]{32}")
                &&operation(a.id(),"MEMBER_REGISTER").equals(a.registerId())&&ControlBootstrapJournal.phaseId(a.id(),MEMBER_JOIN).equals(a.joinId())
                &&binding(a).equals(a.binding())&&a.binding().equals(journal.membershipInputBinding(a.id())),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        var p=profile(a);var f=p.frozen();var m=ControlBootstrapBusinessAllocation.identity(f.spec());var receipt=a.configuredReceipt();
        require("eatwhat-member-configure-v1".equals(receipt.get("protocol"))&&ControlBootstrapJournal.phaseId(a.id(),MEMBER_CONFIGURE).toString().equals(receipt.get("id"))
                &&"CONFIGURED".equals(receipt.get("state"))&&m.siteId().equals(receipt.get("siteId"))&&m.instanceId().toString().equals(receipt.get("instanceId"))
                &&f.spec().clusterId().toString().equals(receipt.get("clusterId"))&&a.databaseFingerprint().equals(receipt.get("databaseFingerprint"))
                &&f.spec().businessSha256().equals(receipt.get("artifactSha256"))&&Boolean.FALSE.equals(receipt.get("joinAuthorized"))
                &&a.profileBinding().equals(receipt.get("profileAcceptanceBinding"))&&operation(a.id(),"MEMBER_RUNTIME").toString().equals(receipt.get("runtimeId"))
                &&receipt.get("allocationSlot") instanceof Number slot&&slot.intValue()==m.allocationSlot()&&"NOT_JOINED".equals(receipt.get("clusterMembership"))
                &&Boolean.TRUE.equals(receipt.get("dataPreserved"))&&Boolean.TRUE.equals(receipt.get("configurationRevalidated"))&&Boolean.TRUE.equals(receipt.get("authStoreVerified")),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
    }
    @SuppressWarnings("unchecked") private Map<String,Object> operations(Accepted a){var body=JSON.readValue(raw(ledger(a.id())),LinkedHashMap.class);
        require(body.keySet().equals(Set.of("binding","registration"))&&a.binding().equals(body.get("binding"))&&body.get("registration") instanceof String s
                &&Set.of("PENDING","CLAIMED","APPLYING","SUCCEEDED").contains(s),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");return body;}
    private Map<String,Object> joinOperations(Accepted a){require(Integer.valueOf(2).equals(a.configuredReceipt().get("joinProtocolVersion")),"BOOTSTRAP_MEMBERSHIP_JOIN_UPGRADE_REQUIRED");var value=JSON.readValue(raw(joinLedger(a.id())),LinkedHashMap.class);
        require(value.keySet().equals(Set.of("binding","state"))&&a.binding().equals(value.get("binding"))&&Set.of("PENDING","CLAIMED","APPLYING","STARTED","SUCCEEDED").contains(value.get("state")),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");return value;}
    public Accepted original(UUID id){var a=JSON.readValue(raw(input(id)),Accepted.class);require(id.equals(a.id()),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");validate(a);operations(a);if(Integer.valueOf(2).equals(a.configuredReceipt().get("joinProtocolVersion")))joinOperations(a);return a;}
    public record Inspection(Map<String,Object> observation,Optional<Map<String,Object>> joinedProof){
        public Inspection{observation=Map.copyOf(observation);joinedProof=joinedProof.map(Map::copyOf);}
        @Override public String toString(){return "BootstrapMembershipInspection[redacted]";}
    }
    /** Independent observation does not call observeRegistration, observeJoin,
     * joinReceipt, joinedState or any coordinator/remote mutation method. */
    @SuppressWarnings("unchecked") public Inspection inspectOriginal(ControlBootstrapAdmission.Frozen f){
        nativePhases.bound(f);var a=original(f.spec().id());var p=profile(a);
        require(f.binding().equals(p.frozen().binding()),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        var registration=operations(a);var join=joinOperations(a);var member=ControlBootstrapBusinessAllocation.identity(f.spec());
        var cluster=membership.getIfAvailable();require(cluster!=null,"BOOTSTRAP_MEMBERSHIP_OBSERVATION_UNAVAILABLE");
        ClusterStore.OwnedJoinSnapshot snapshot;
        try{snapshot=cluster.inspectOwnedJoin(a.id(),a.binding(),f.actor(),a.registerId(),a.joinId(),f.spec().epoch(),member,a.databaseFingerprint());}
        catch(ClusterFailure changed){throw new ControlFailure(changed.status(),"BOOTSTRAP_MEMBERSHIP_PROOF_CHANGED");}
        catch(org.springframework.dao.DataAccessException unavailable){throw new ControlFailure(503,"BOOTSTRAP_MEMBERSHIP_OBSERVATION_UNAVAILABLE");}
        catch(org.springframework.transaction.TransactionException unavailable){throw new ControlFailure(503,"BOOTSTRAP_MEMBERSHIP_OBSERVATION_UNAVAILABLE");}
        require(f.spec().clusterId().equals(snapshot.clusterId()),"BOOTSTRAP_MEMBERSHIP_PROOF_CHANGED");
        require(!"PENDING".equals(registration.get("registration"))||snapshot.registration().isEmpty(),"BOOTSTRAP_MEMBERSHIP_PROOF_CHANGED");
        require(!"SUCCEEDED".equals(registration.get("registration"))||snapshot.registration().isPresent(),"BOOTSTRAP_MEMBERSHIP_PROOF_CHANGED");
        require(snapshot.change().isEmpty()||snapshot.registration().isPresent()&&!"PENDING".equals(join.get("state")),"BOOTSTRAP_MEMBERSHIP_PROOF_CHANGED");
        require(!"SUCCEEDED".equals(join.get("state"))||snapshot.change().isPresent(),"BOOTSTRAP_MEMBERSHIP_PROOF_CHANGED");
        var report=new LinkedHashMap<String,Object>();report.put("inspectionComponent","MEMBER_JOIN");
        report.put("registrationCommandObserved",snapshot.registration().isPresent());report.put("joinCommandObserved",snapshot.change().isPresent());
        report.put("clusterEpoch",snapshot.epoch());report.put("expectedJoinedEpoch",f.spec().epoch()+1);report.put("changeId",a.joinId().toString());
        String state=snapshot.change().map(c->Objects.toString(c.get("state"))).orElse("PENDING".equals(join.get("state"))&&snapshot.registration().isPresent()?"REGISTERED":"OUTCOME_UNKNOWN");
        require(Set.of("PREPARING","COMMITTED","INSTALLED","APPLIED","REGISTERED","OUTCOME_UNKNOWN").contains(state),"BOOTSTRAP_MEMBERSHIP_PROOF_CHANGED");report.put("state",state);
        Optional<Map<String,Object>> proof=Optional.empty();boolean confirmations=false,current=false;
        if(snapshot.change().isPresent()){
            var change=snapshot.change().get();var participants=(List<ClusterMember>)change.get("participants");var votes=(List<Map<String,Object>>)change.get("votes");
            confirmations=participants.size()>=2&&participants.size()<=16&&votes.size()==participants.size()*3
                    &&participants.stream().allMatch(m->List.of("PREPARED","APPLIED","RELEASED").stream().allMatch(phase->votes.stream().filter(v->m.siteId().equals(v.get("site_id"))&&m.instanceId().equals(v.get("instance_id"))&&phase.equals(v.get("phase"))).count()==1));
            var active=snapshot.members().stream().filter(m->"ACTIVE".equals(m.get("state"))).toList();
            current=snapshot.epoch()==f.spec().epoch()+1&&active.size()==participants.size()&&snapshot.members().stream().filter(m->!"RETIRED".equals(m.get("state"))).count()==participants.size()
                    &&participants.stream().allMatch(m->active.stream().filter(row->m.siteId().equals(row.get("site_id"))&&m.instanceId().equals(row.get("instance_id"))&&m.mode().equals(row.get("mode"))
                        &&row.get("allocation_slot") instanceof Number slot&&slot.intValue()==m.allocationSlot()).count()==1)
                    &&active.stream().anyMatch(row->member.siteId().equals(row.get("site_id"))&&a.databaseFingerprint().equals(row.get("database_fingerprint"))&&row.get("joined_epoch") instanceof Number epoch&&epoch.longValue()==f.spec().epoch()+1);
            if("APPLIED".equals(state)&&confirmations&&current)proof=Optional.of(Map.of("bootstrapId",a.id().toString(),"changeId",a.joinId().toString(),"baseEpoch",f.spec().epoch(),"epoch",snapshot.epoch(),
                    "databaseFingerprint",a.databaseFingerprint(),"members",JSON.readValue(JSON.writeValueAsString(participants),List.class),"membershipBinding",a.binding()));
        }
        report.put("allJoinConfirmationsObserved",confirmations);report.put("currentMembershipMatchesOriginalJoin",current);
        // Re-read the sealed file ledgers after SQL; an active worker's progress
        // cannot silently be merged into this observation's original inputs.
        require(a.equals(original(a.id()))&&registration.equals(operations(a))&&join.equals(joinOperations(a)),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        return new Inspection(report,proof);
    }
    private Map<String,Object> ready(ControlBootstrapAdmission.Frozen f){
        var ready=runtime.ready(f);var member=ControlBootstrapBusinessAllocation.identity(f.spec());
        require(operation(f.spec().id(),"MEMBER_RUNTIME").toString().equals(ready.get("id"))&&"eatwhat-member-configure-v1".equals(ready.get("protocol"))
                &&"CONFIGURED".equals(ready.get("state"))&&Boolean.TRUE.equals(ready.get("configurationRevalidated"))&&Boolean.TRUE.equals(ready.get("authStoreVerified"))
                &&Boolean.TRUE.equals(ready.get("dataPreserved"))&&Boolean.FALSE.equals(ready.get("joinAuthorized"))&&"NOT_JOINED".equals(ready.get("clusterMembership"))
                &&member.siteId().equals(ready.get("siteId"))&&member.instanceId().toString().equals(ready.get("instanceId"))&&f.spec().clusterId().toString().equals(ready.get("clusterId"))
                &&ready.get("allocationSlot") instanceof Number slot&&slot.intValue()==member.allocationSlot()&&f.spec().businessSha256().equals(ready.get("artifactSha256"))
                &&ready.get("databaseFingerprint") instanceof String fp&&fp.matches("[a-f0-9]{32}"),"BOOTSTRAP_MEMBER_RUNTIME_NOT_VERIFIED");
        return ready;
    }
    private Accepted ensure(ControlBootstrapAdmission.Frozen f){
        admission.requireBound(f);var p=profiles.original(f.spec().id());require(f.binding().equals(p.frozen().binding()),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        require(profiles.progress(p).values().stream().allMatch("SUCCEEDED"::equals),"BOOTSTRAP_PROFILES_NOT_READY");
        // Slow, actual role/relay/ingress/runtime checks run without file/SQL locks.
        if(Files.exists(input(f.spec().id()),LinkOption.NOFOLLOW_LINKS))return original(f.spec().id());
        require(journal.membershipInputBinding(f.spec().id())==null&&!Files.exists(ledger(f.spec().id()),LinkOption.NOFOLLOW_LINKS)&&!Files.exists(joinLedger(f.spec().id()),LinkOption.NOFOLLOW_LINKS),"BOOTSTRAP_MEMBERSHIP_RECORD_MISSING");
        var ready=ready(f);
        var receipt=new LinkedHashMap<String,Object>();for(String key:List.of("state","siteId","instanceId","clusterId","allocationSlot","artifactSha256","databaseFingerprint","configurationRevalidated","authStoreVerified","dataPreserved","clusterMembership","joinAuthorized"))receipt.put(key,ready.get(key));
        receipt.put("runtimeId",ready.get("id"));receipt.put("id",ControlBootstrapJournal.phaseId(f.spec().id(),MEMBER_CONFIGURE).toString());receipt.put("protocol","eatwhat-member-configure-v1");
        receipt.put("profileAcceptanceBinding",p.binding());receipt.put("joinProtocolVersion",2);require(JSON.writeValueAsBytes(receipt).length<=8192,"BOOTSTRAP_PHASE_RECEIPT_TOO_LARGE");
        var draft=new Accepted(f.spec().id(),p.binding(),ready.get("databaseFingerprint").toString(),operation(f.spec().id(),"MEMBER_REGISTER"),ControlBootstrapJournal.phaseId(f.spec().id(),MEMBER_JOIN),receipt,"");
        var a=new Accepted(draft.id(),draft.profileBinding(),draft.databaseFingerprint(),draft.registerId(),draft.joinId(),draft.configuredReceipt(),binding(draft));
        return locked(()->{
            if(Files.exists(input(a.id()),LinkOption.NOFOLLOW_LINKS))return original(a.id());
            require(journal.membershipInputBinding(a.id())==null&&!Files.exists(ledger(a.id()),LinkOption.NOFOLLOW_LINKS)&&!Files.exists(joinLedger(a.id()),LinkOption.NOFOLLOW_LINKS),"BOOTSTRAP_MEMBERSHIP_RECORD_MISSING");
            journal.membershipInputIntent(a.id(),a.binding(),intent->admission.beforeProfileIntent(p,journal,()->{intent.run();return null;}),()->{
                save(input(a.id()),a);save(ledger(a.id()),Map.of("binding",a.binding(),"registration","PENDING"));save(joinLedger(a.id()),Map.of("binding",a.binding(),"state","PENDING"));
            });return original(a.id());
        });
    }
    /** Completion of already performed configuration is observation, not a new
     * remote effect. Every later membership effect obtains its own authority. */
    public Map<String,Object> configure(ControlBootstrapAdmission.Frozen f){var a=ensure(f);
        var dispatch=journal.claimFenced(a.id(),MEMBER_CONFIGURE,intent->{throw new ControlFailure(409,"BOOTSTRAP_MEMBER_CONFIGURATION_CLAIM_REQUIRED");});
        require(!dispatch.dispatchAllowed(),"BOOTSTRAP_MEMBER_CONFIGURATION_CLAIM_REQUIRED");return journal.observe(a.id(),MEMBER_CONFIGURE,dispatch.claimToken(),a.configuredReceipt());
    }
    public Map<String,Object> register(ControlBootstrapAdmission.Frozen f){
        var a=original(f.spec().id());var p=profile(a);require(f.binding().equals(p.frozen().binding()),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        nativePhases.server(f,SOURCE_RUNTIME);
        if("PENDING".equals(operations(a).get("registration")))require(a.databaseFingerprint().equals(ready(f).get("databaseFingerprint")),"BOOTSTRAP_MEMBER_RUNTIME_NOT_VERIFIED");
        journal.claimFenced(a.id(),MEMBER_JOIN,intent->admission.beforeProfileIntent(p,journal,()->{intent.run();return null;}));
        boolean dispatch=locked(()->{var body=operations(a);if(!"PENDING".equals(body.get("registration")))return false;
            journal.memberJoinIntent(a.id(),intent->admission.beforeProfileIntent(p,journal,()->{intent.run();return null;}),()->{body.put("registration","CLAIMED");save(ledger(a.id()),body);});return true;});
        var payload=new LinkedHashMap<String,Object>();payload.put("id",a.id().toString());payload.put("role","SOURCE");payload.put("binding",a.binding());
        payload.put("applicationSha256",f.sourceRequest().expected().applicationSha256());payload.put("tokenFile",p.bridge().sourceTokenFile());payload.put("artifactSha256",f.sourceRequest().sourceArtifactSha256());payload.put("sourceProcess",p.frozen().sourceEvidence().get("sourceProcess"));
        var receipt=nativePhases.call(nativePhases.server(f,SOURCE_RUNTIME),p.bridge().agent(),List.of(dispatch?"register":"register-status"),payload);
        validateRegistrationReceipt(a,p,receipt);
        return observeRegistration(a);
    }
    private Map<String,Object> registrationReceipt(Accepted a,ControlBootstrapProfileState.Accepted p,String state){return ControlBootstrapProfileState.sign(store,Map.ofEntries(
        Map.entry("protocol","eatwhat-controller-profile-publication-v1"),Map.entry("bootstrapId",a.id().toString()),Map.entry("role","SOURCE"),Map.entry("binding",a.binding()),Map.entry("planBinding",p.plans().source().binding()),
        Map.entry("controllerId",p.sourceControllerId().toString()),Map.entry("controllerName",ControlBootstrapProfileState.controllerName(p,ControlBootstrapProfileState.Role.SOURCE)),
        Map.entry("applicationSha256",p.frozen().sourceRequest().expected().applicationSha256()),Map.entry("state",state),Map.entry("observedBinding",p.plans().source().afterBinding()),Map.entry("joinAuthorized",false)));
    }
    private void validateRegistrationReceipt(Accepted a,ControlBootstrapProfileState.Accepted p,Map<String,Object> receipt){
        require(Set.of("REGISTERED","OUTCOME_UNKNOWN").contains(receipt.get("state"))
                &&ControlBootstrapNativeJson.encode(registrationReceipt(a,p,receipt.get("state").toString())).equals(ControlBootstrapNativeJson.encode(receipt)),"BOOTSTRAP_MEMBERSHIP_RECEIPT_CHANGED");
    }
    /** The physical SOURCE owns the reachable peer routes. A standby SOURCE
     * executes only this sealed registration under the original primary's
     * transaction; the calling primary need not rebase its own route catalog. */
    public Map<String,Object> localRegistration(UUID id,ControlBootstrapProfileState.Role role,String binding,boolean execute){
        require(role==ControlBootstrapProfileState.Role.SOURCE,"BOOTSTRAP_PROFILE_REQUEST_INVALID");var a=original(id);var p=profile(a);require(a.binding().equals(binding),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        var local=receiver.observe(id,role,p.binding());require("RUNTIME_RELOADED".equals(local.get("state")),"BOOTSTRAP_PROFILES_NOT_READY");
        var f=p.frozen();var member=ControlBootstrapBusinessAllocation.identity(f.spec());
        return locked(()->{
            var body=operations(a);require(!"PENDING".equals(body.get("registration")),"BOOTSTRAP_MEMBER_JOIN_CLAIM_REQUIRED");
            var command=membership.getObject().registeredCommand(f.actor(),a.registerId(),f.spec().epoch(),member.siteId(),member);
            if(command.isPresent())return registrationReceipt(a,p,"REGISTERED");
            require(!"SUCCEEDED".equals(body.get("registration")),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
            if(!execute||!"CLAIMED".equals(body.get("registration")))return registrationReceipt(a,p,"OUTCOME_UNKNOWN");
            admission.applyProfile(p,role,()->{
                var job=journal.job(id);require(!Boolean.TRUE.equals(job.get("requiresReapproval")),"BOOTSTRAP_REAPPROVAL_REQUIRED");
                require(!Set.of("FAILED","SUCCEEDED").contains(job.get("state"))&&journal.approvedPermissions(id).contains("INSTALL_BUSINESS_AND_JOIN"),"BOOTSTRAP_TASK_TERMINAL");
                var join=((List<Map<String,Object>>)job.get("steps")).get(MEMBER_JOIN.ordinal());require(Set.of("DISPATCHED","RUNNING","OUTCOME_UNKNOWN").contains(join.get("state")),"BOOTSTRAP_MEMBER_JOIN_CLAIM_REQUIRED");
                body.put("registration","APPLYING");save(ledger(id),body);
                coordinator.getObject().registerExpected(f.actor(),a.registerId(),f.spec().epoch(),member.siteId(),member);return null;
            });
            require(membership.getObject().registeredCommand(f.actor(),a.registerId(),f.spec().epoch(),member.siteId(),member).isPresent(),"BOOTSTRAP_MEMBERSHIP_RECEIPT_CHANGED");
            return registrationReceipt(a,p,"REGISTERED");
        });
    }
    public Map<String,Object> observeRegistration(Accepted a){validate(a);var p=profile(a);var f=p.frozen();var member=ControlBootstrapBusinessAllocation.identity(f.spec());
        var body=operations(a);require(!"PENDING".equals(body.get("registration")),"BOOTSTRAP_MEMBER_JOIN_CLAIM_REQUIRED");
        var command=membership.getObject().registeredCommand(f.actor(),a.registerId(),f.spec().epoch(),member.siteId(),member);
        if(command.isPresent()){
            if(!"SUCCEEDED".equals(body.get("registration")))locked(()->{var current=operations(a);current.put("registration","SUCCEEDED");save(ledger(a.id()),current);return null;});
        }else require(!"SUCCEEDED".equals(body.get("registration")),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        return Map.of("id",a.id().toString(),"registration",command.isPresent()?"SUCCEEDED":"OUTCOME_UNKNOWN","joinPending",true,"joinAuthorized",false);
    }
    private void task(Accepted a){var job=journal.job(a.id());require(!Boolean.TRUE.equals(job.get("requiresReapproval")),"BOOTSTRAP_REAPPROVAL_REQUIRED");
        require(!Set.of("FAILED","SUCCEEDED").contains(job.get("state"))&&journal.approvedPermissions(a.id()).contains("INSTALL_BUSINESS_AND_JOIN"),"BOOTSTRAP_TASK_TERMINAL");
        var step=((List<Map<String,Object>>)job.get("steps")).get(MEMBER_JOIN.ordinal());require(Set.of("DISPATCHED","RUNNING","OUTCOME_UNKNOWN").contains(step.get("state")),"BOOTSTRAP_MEMBER_JOIN_CLAIM_REQUIRED");}
    private Optional<Map<String,Object>> joined(Accepted a,ControlBootstrapProfileState.Accepted p){var f=p.frozen();return membership.getObject().ownedJoinedCommand(a.id(),a.binding(),f.actor(),a.joinId(),f.spec().epoch(),ControlBootstrapBusinessAllocation.identity(f.spec()),a.databaseFingerprint());}
    private void joinedState(Accepted a,String state){locked(()->{var value=joinOperations(a);require(!"PENDING".equals(value.get("state")),"BOOTSTRAP_MEMBER_JOIN_CLAIM_REQUIRED");if(!"SUCCEEDED".equals(value.get("state"))&&!state.equals(value.get("state"))){value.put("state",state);save(joinLedger(a.id()),value);}return null;});}
    /** Resume the original registration/JOIN. A completed registration is not
     * reissued after JOIN advanced the epoch. Reconcile only a command whose
     * task ownership has been proved by the authoritative store. */
    public Map<String,Object> advance(ControlBootstrapAdmission.Frozen f){
        var a=original(f.spec().id());var p=profile(a);
        require(f.binding().equals(p.frozen().binding()),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        if(!"SUCCEEDED".equals(operations(a).get("registration"))){
            if("PENDING".equals(operations(a).get("registration")))return register(f);
            var seen=observeRegistration(a);if(!"SUCCEEDED".equals(seen.get("registration")))return register(f);
            return seen;
        }
        if("PENDING".equals(joinOperations(a).get("state"))||joined(a,p).isEmpty())return join(f);
        return reconcileJoin(f);
    }
    public Map<String,Object> join(ControlBootstrapAdmission.Frozen f){
        var a=original(f.spec().id());var p=profile(a);require(f.binding().equals(p.frozen().binding()),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        require("SUCCEEDED".equals(observeRegistration(a).get("registration")),"BOOTSTRAP_REGISTRATION_REQUIRED");
        boolean pending="PENDING".equals(joinOperations(a).get("state"));
        if(pending)require(a.databaseFingerprint().equals(ready(f).get("databaseFingerprint")),"BOOTSTRAP_MEMBER_RUNTIME_NOT_VERIFIED");
        boolean dispatch=locked(()->{var value=joinOperations(a);if(!"PENDING".equals(value.get("state")))return false;
            journal.memberJoinIntent(a.id(),intent->admission.beforeJoinIntent(p,a,journal,membership.getObject(),true,()->{intent.run();return null;}),()->{value.put("state","CLAIMED");save(joinLedger(a.id()),value);});return true;});
        return sendJoin(a,p,dispatch?"join":"join-status");
    }
    /** Only a known original command may resume its idempotent node protocol.
     * Absence of BEGIN evidence never grants another first dispatch. */
    public Map<String,Object> reconcileJoin(ControlBootstrapAdmission.Frozen f){
        var a=original(f.spec().id());var p=profile(a);require(f.binding().equals(p.frozen().binding()),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        require(!"PENDING".equals(joinOperations(a).get("state")),"BOOTSTRAP_MEMBER_JOIN_CLAIM_REQUIRED");
        var change=joined(a,p).orElseThrow(()->new ControlFailure(409,"BOOTSTRAP_JOIN_COMMAND_REQUIRED"));
        if("APPLIED".equals(change.get("state")))return sendJoin(a,p,"join-status");
        journal.memberJoinIntent(a.id(),intent->admission.beforeJoinIntent(p,a,journal,membership.getObject(),false,()->{task(a);intent.run();return null;}),()->{});
        return sendJoin(a,p,"join-reconcile");
    }
    private Map<String,Object> sendJoin(Accepted a,ControlBootstrapProfileState.Accepted p,String action){var f=p.frozen();
        var payload=new LinkedHashMap<String,Object>();payload.put("id",a.id().toString());payload.put("role","SOURCE");payload.put("binding",a.binding());payload.put("applicationSha256",f.sourceRequest().expected().applicationSha256());
        payload.put("tokenFile",p.bridge().sourceTokenFile());payload.put("artifactSha256",f.sourceRequest().sourceArtifactSha256());payload.put("sourceProcess",f.sourceEvidence().get("sourceProcess"));
        var receipt=nativePhases.call(nativePhases.server(f,SOURCE_RUNTIME),p.bridge().agent(),List.of(action),payload);
        require(Set.of("PREPARING","COMMITTED","INSTALLED","ACTIVE","OUTCOME_UNKNOWN").contains(receipt.get("state"))
                &&ControlBootstrapNativeJson.encode(registrationReceipt(a,p,receipt.get("state").toString())).equals(ControlBootstrapNativeJson.encode(receipt)),"BOOTSTRAP_MEMBERSHIP_RECEIPT_CHANGED");
        return observeJoin(a);
    }
    public enum JoinAction {BEGIN,STATUS,RECONCILE}
    public Map<String,Object> localJoin(UUID id,ControlBootstrapProfileState.Role role,String binding,JoinAction action){
        require(role==ControlBootstrapProfileState.Role.SOURCE,"BOOTSTRAP_PROFILE_REQUEST_INVALID");var a=original(id);var p=profile(a);require(a.binding().equals(binding),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        require("RUNTIME_RELOADED".equals(receiver.observe(id,role,p.binding()).get("state")),"BOOTSTRAP_PROFILES_NOT_READY");
        var value=joinOperations(a);require(!"PENDING".equals(value.get("state")),"BOOTSTRAP_MEMBER_JOIN_CLAIM_REQUIRED");
        var prior=joined(a,p);
        if(action==JoinAction.STATUS||prior.isPresent()&&"APPLIED".equals(prior.get().get("state"))||action==JoinAction.BEGIN&&prior.isPresent())return joinReceipt(a,p,prior);
        return locked(store.directory().resolve("membership-join-execution-"+id+".lock"),()->{
            if(action==JoinAction.BEGIN){
                if(joined(a,p).isPresent())return joinReceipt(a,p,joined(a,p));
                boolean execute=locked(()->{var current=joinOperations(a);if(!"CLAIMED".equals(current.get("state")))return false;
                    return admission.applyJoin(p,a,journal,membership.getObject(),()->{task(a);current.put("state","APPLYING");save(joinLedger(a.id()),current);return true;});});
                if(!execute)return joinReceipt(a,p,Optional.empty());
                coordinator.getObject().beginOwnedJoinExpected(a.id(),a.binding(),p.frozen().actor(),a.joinId(),p.frozen().spec().epoch(),ControlBootstrapBusinessAllocation.identity(p.frozen().spec()),a.databaseFingerprint(),joinBoundary(a,p));
            }else{
                require(joined(a,p).isPresent(),"BOOTSTRAP_JOIN_COMMAND_REQUIRED");
                coordinator.getObject().reconcileOwnedJoinExpected(a.id(),a.binding(),p.frozen().actor(),a.joinId(),p.frozen().spec().epoch(),ControlBootstrapBusinessAllocation.identity(p.frozen().spec()),a.databaseFingerprint(),joinBoundary(a,p));
            }
            return joinReceipt(a,p,joined(a,p));
        });
    }
    private ClusterMembershipCoordinator.Boundary joinBoundary(Accepted a,ControlBootstrapProfileState.Accepted p){return new ClusterMembershipCoordinator.Boundary(){
        public void check(String operation){write(operation,()->{});}
        public void write(String operation,Runnable effect){
            require("RUNTIME_RELOADED".equals(receiver.observe(a.id(),ControlBootstrapProfileState.Role.SOURCE,p.binding()).get("state")),"BOOTSTRAP_PROFILES_NOT_READY");
            admission.applyJoin(p,a,journal,membership.getObject(),()->{task(a);effect.run();return null;});
        }
    };}
    private Map<String,Object> joinReceipt(Accepted a,ControlBootstrapProfileState.Accepted p,Optional<Map<String,Object>> command){
        if(command.isPresent()){if("APPLIED".equals(command.get().get("state")))completedVotes(command.get());joinedState(a,"APPLIED".equals(command.get().get("state"))?"SUCCEEDED":"STARTED");}
        else require(!Set.of("STARTED","SUCCEEDED").contains(joinOperations(a).get("state")),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        return registrationReceipt(a,p,command.map(c->"APPLIED".equals(c.get("state"))?"ACTIVE":c.get("state").toString()).orElse("OUTCOME_UNKNOWN"));
    }
    public Map<String,Object> observeJoin(Accepted a){validate(a);var p=profile(a);require(!"PENDING".equals(joinOperations(a).get("state")),"BOOTSTRAP_MEMBER_JOIN_CLAIM_REQUIRED");
        var command=joined(a,p);joinReceipt(a,p,command);
        if(command.isPresent()){
            var change=command.get();boolean complete="APPLIED".equals(change.get("state"));
            var receipt=new LinkedHashMap<String,Object>();receipt.put("protocol","eatwhat-controller-bootstrap-membership-v1");receipt.put("id",a.joinId().toString());receipt.put("state",complete?"ACTIVE":"RUNNING");
            receipt.put("changeId",a.joinId().toString());receipt.put("bootstrapId",a.id().toString());receipt.put("membershipBinding",a.binding());receipt.put("siteId",change.get("site_id"));receipt.put("baseEpoch",change.get("base_epoch"));receipt.put("epoch",change.get("next_epoch"));receipt.put("databaseFingerprint",a.databaseFingerprint());
            if(!Set.of("SUCCEEDED","FAILED").contains(journal.job(a.id()).get("state"))){var claim=journal.claimFenced(a.id(),MEMBER_JOIN,intent->{throw new ControlFailure(409,"BOOTSTRAP_MEMBER_JOIN_CLAIM_REQUIRED");});journal.observe(a.id(),MEMBER_JOIN,claim.claimToken(),receipt);}
        }
        return Map.of("id",a.id().toString(),"changeId",a.joinId().toString(),"state",command.map(c->"APPLIED".equals(c.get("state"))?"ACTIVE":c.get("state")).orElse("OUTCOME_UNKNOWN"),"joinAuthorized",false);
    }
    private void completedVotes(Map<String,Object> change){for(var member:(List<ClusterMember>)change.get("participants"))for(String phase:List.of("PREPARED","APPLIED","RELEASED"))
        require(((List<Map<String,Object>>)change.get("votes")).stream().filter(v->member.siteId().equals(v.get("site_id"))&&member.instanceId().equals(v.get("instance_id"))&&phase.equals(v.get("phase"))).count()==1,"BOOTSTRAP_JOIN_CONFIRMATIONS_REQUIRED");}
    Map<String,Object> joinedProof(ControlBootstrapAdmission.Frozen f){var a=original(f.spec().id());var p=profile(a);require(f.binding().equals(p.frozen().binding()),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        require("SUCCEEDED".equals(joinOperations(a).get("state")),"BOOTSTRAP_JOIN_CONFIRMATIONS_REQUIRED");var command=joined(a,p).orElseThrow(()->new ControlFailure(409,"BOOTSTRAP_JOIN_COMMAND_REQUIRED"));
        require("APPLIED".equals(command.get("state")),"BOOTSTRAP_JOIN_CONFIRMATIONS_REQUIRED");completedVotes(command);
        return Map.of("bootstrapId",a.id().toString(),"changeId",a.joinId().toString(),"baseEpoch",command.get("base_epoch"),"epoch",command.get("next_epoch"),"databaseFingerprint",a.databaseFingerprint(),"members",JSON.readValue(JSON.writeValueAsString(command.get("participants")),List.class),"membershipBinding",a.binding());
    }
}
