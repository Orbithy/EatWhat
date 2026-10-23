package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.function.Supplier;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Original two-host profile acceptance and once-only operation intents. The
 * accepted pair is forced before its ledger. Losing the latter never recreates
 * dispatch permission. Inputs and receipts remain encrypted in shared state. */
public final class ControlBootstrapProfileState {
    public enum Role { SOURCE, TARGET }
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final ControlServerStore store;private final ControlBootstrapJournal journal;private final ControlBootstrapAdmission admission;
    private final ControlBootstrapProfilePlans plans;private final ControlBootstrapNativePhases nativePhases;
    public ControlBootstrapProfileState(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapAdmission admission,
            ControlBootstrapProfilePlans plans,ControlBootstrapNativePhases nativePhases){this.store=store;this.journal=journal;this.admission=admission;this.plans=plans;this.nativePhases=nativePhases;}
    public record Bridge(String agent,String sha256,String sourceTokenFile,String targetTokenFile){
        @Override public String toString(){return "BootstrapProfileBridge[redacted]";}
    }
    public record Services(UUID businessJobId,String businessBinding,String baselineSha256,String controllerBinding){
        @Override public String toString(){return "BootstrapProfileServices[redacted]";}
    }
    public record Accepted(ControlBootstrapAdmission.Frozen frozen,ControlBootstrapProfilePlans.Pair plans,String approvalDigest,
                           UUID sourceControllerId,UUID targetControllerId,String targetApplicationSha256,Bridge bridge,Services services,String binding){
        @Override public String toString(){return "BootstrapProfileAcceptance[redacted]";}
    }
    public record Permit(Accepted accepted,Role role,boolean dispatchAllowed){@Override public String toString(){return "BootstrapProfilePermit[redacted]";}}
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    static String canonical(Object value){return JSON.writeValueAsString(ordered(JSON.readValue(JSON.writeValueAsBytes(value),Object.class)));}
    private static Object ordered(Object value){if(value instanceof Map<?,?> m){var next=new TreeMap<String,Object>();m.forEach((k,v)->next.put(k.toString(),ordered(v)));return next;}if(value instanceof List<?> a)return a.stream().map(ControlBootstrapProfileState::ordered).toList();return value;}
    private static String binding(ControlServerStore store,Accepted a){return store.fingerprint(canonical(List.of(a.frozen().binding(),a.plans(),a.approvalDigest(),a.sourceControllerId(),a.targetControllerId(),a.targetApplicationSha256(),a.bridge(),a.services())));}
    private static Path inputPath(ControlServerStore s,UUID id){return s.directory().resolve("controller-profile-input-"+id+".json");}
    private static Path operationPath(ControlServerStore s,UUID id){return s.directory().resolve("controller-profile-operations-"+id+".json");}
    static String controllerName(Accepted a,Role role){return role==Role.SOURCE?a.frozen().sourceRequest().expected().controllerName():"control-"+a.frozen().spec().targetServerId().toString().replace("-","").substring(0,24);}
    static UUID controllerId(Accepted a,Role role){return role==Role.SOURCE?a.sourceControllerId():a.targetControllerId();}
    static String applicationHash(Accepted a,Role role){return role==Role.SOURCE?a.frozen().sourceRequest().expected().applicationSha256():a.targetApplicationSha256();}
    static String tokenFile(Accepted a,Role role){return role==Role.SOURCE?a.bridge().sourceTokenFile():a.bridge().targetTokenFile();}
    static you.v50to.eatwhat.cluster.ClusterProfilePublication.Plan plan(Accepted a,Role role){return role==Role.SOURCE?a.plans().source():a.plans().target();}
    static void verify(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapAdmission admission,Accepted a){
        validate(store,journal,admission,a,true);
    }
    private static void validate(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapAdmission admission,Accepted a,boolean committed){
        admission.requireBound(a.frozen());var f=a.frozen();journal.requireAdmission(f.spec().id(),f.spec(),f.actor(),f.review(),f.binding());
        require(a.bridge()!=null&&a.bridge().agent()!=null&&a.bridge().agent().length()<=131072&&a.bridge().sha256()!=null
                &&ControlBootstrapNativeJson.sha(a.bridge().agent()).equals(a.bridge().sha256()),"BOOTSTRAP_PROFILE_ACCEPTANCE_CHANGED");
        require(a.services()!=null&&ControlBootstrapJournal.phaseId(f.spec().id(),BUSINESS_INSTALL).equals(a.services().businessJobId())
                &&a.services().businessBinding()!=null&&a.services().businessBinding().matches("[a-f0-9]{64}")
                &&a.services().baselineSha256()!=null&&a.services().baselineSha256().matches("[a-f0-9]{64}")
                &&a.services().controllerBinding()!=null&&a.services().controllerBinding().matches("[a-f0-9]{64}"),"BOOTSTRAP_PROFILE_ACCEPTANCE_CHANGED");
        require(a.sourceControllerId()!=null&&a.targetControllerId()!=null&&!a.sourceControllerId().equals(new UUID(0,0))&&!a.targetControllerId().equals(new UUID(0,0))
                &&!a.sourceControllerId().equals(a.targetControllerId())&&a.targetApplicationSha256().matches("[a-f0-9]{64}")&&binding(store,a).equals(a.binding())
                &&a.approvalDigest().equals(journal.job(f.spec().id()).get("requestHash")),"BOOTSTRAP_PROFILE_ACCEPTANCE_CHANGED");
        if(committed)require(a.binding().equals(journal.profileInputBinding(f.spec().id())),"BOOTSTRAP_PROFILE_RECORD_CHANGED");
        require(f.sourceEvidence().get("sourceProcess") instanceof Map<?,?> p&&p.get("pid") instanceof Number pid&&pid.longValue()>1&&p.get("startTicks") instanceof String ticks&&ticks.matches("[0-9]+"),"BOOTSTRAP_PROFILE_PROCESS_PROOF_REQUIRED");
        for(Role role:Role.values()){var p=plan(a,role);you.v50to.eatwhat.cluster.ClusterProfilePublication.validate(p);
            require(f.spec().id().equals(p.bootstrapId())&&f.spec().targetServerId().equals(p.serverId())&&ControlBootstrapBusinessAllocation.identity(f.spec()).equals(p.member()),"BOOTSTRAP_PROFILE_ACCEPTANCE_CHANGED");
            require(tokenFile(a,role)!=null,"BOOTSTRAP_PROFILE_ACCEPTANCE_CHANGED");var token=Path.of(tokenFile(a,role));
            require(token.isAbsolute()&&token.normalize().equals(token)&&token.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                    &&token.getParent().equals(Path.of(p.profilesFile()).getParent()),"BOOTSTRAP_PROFILE_ACCEPTANCE_CHANGED");}
    }
    private static void save(ControlServerStore store,Path path,Object body){String raw=JSON.writeValueAsString(body);store.saveJson(path,Map.of("version",1,"body",store.encrypt(raw),"seal",store.fingerprint(raw)));}
    private static String raw(ControlServerStore store,Path path){
        require(Files.exists(path,LinkOption.NOFOLLOW_LINKS),"BOOTSTRAP_PROFILE_RECORD_MISSING");var file=store.readJson(path);
        require(Integer.valueOf(1).equals(file.get("version")),"BOOTSTRAP_PROFILE_RECORD_CHANGED");String raw=store.decrypt(file.get("body").toString());
        require(store.fingerprint(raw).equals(file.get("seal")),"BOOTSTRAP_PROFILE_RECORD_CHANGED");return raw;
    }
    public Accepted original(UUID id){var a=JSON.readValue(raw(store,inputPath(store,id)),Accepted.class);require(id.equals(a.frozen().spec().id()),"BOOTSTRAP_PROFILE_ACCEPTANCE_CHANGED");verify(store,journal,admission,a);operations(store,a);return a;}
    @SuppressWarnings("unchecked") private static Map<String,Object> operations(ControlServerStore store,Accepted a){
        var body=JSON.readValue(raw(store,operationPath(store,a.frozen().spec().id())),LinkedHashMap.class);
        require(a.binding().equals(body.get("inputBinding"))&&body.get("operations") instanceof Map<?,?> m&&m.keySet().equals(Set.of("SOURCE","TARGET")),"BOOTSTRAP_PROFILE_RECORD_CHANGED");
        boolean prior=true;for(Role role:Role.values()){var row=row(body,role);require(Set.of("PENDING","CLAIMED","SUCCEEDED").contains(row.get("state"))&& (prior||"PENDING".equals(row.get("state"))),"BOOTSTRAP_PROFILE_RECORD_CHANGED");
            if("SUCCEEDED".equals(row.get("state"))){require(row.get("receipt") instanceof Map<?,?>,"BOOTSTRAP_PROFILE_RECORD_CHANGED");validateReceipt(store,a,role,(Map<String,Object>)row.get("receipt"));}
            prior="SUCCEEDED".equals(row.get("state"));}return body;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> row(Map<String,Object> body,Role role){Object r=((Map<?,?>)body.get("operations")).get(role.name());require(r instanceof Map<?,?>,"BOOTSTRAP_PROFILE_RECORD_CHANGED");return (Map<String,Object>)r;}
    private <T>T locked(Supplier<T> work){Path p=store.directory().resolve("profile-publication-operations.lock");
        try{try{Files.createFile(p,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException ignored){}
            ControlServerStore.privateFile(p,1024);try(var ch=FileChannel.open(p,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                FileLock lock;try{lock=ch.tryLock();}catch(OverlappingFileLockException busy){throw new ControlFailure(409,"BOOTSTRAP_PROFILE_RECORD_BUSY");}require(lock!=null,"BOOTSTRAP_PROFILE_RECORD_BUSY");try(lock){return work.get();}}
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_PROFILE_STATE_UNAVAILABLE");}
    }
    @SuppressWarnings("unchecked") public Accepted ensure(ControlBootstrapAdmission.Frozen f){
        nativePhases.bound(f);return locked(()->{
            if(Files.exists(inputPath(store,f.spec().id()),LinkOption.NOFOLLOW_LINKS))return original(f.spec().id());
            require(journal.profileInputBinding(f.spec().id())==null&&!Files.exists(operationPath(store,f.spec().id()),LinkOption.NOFOLLOW_LINKS),"BOOTSTRAP_PROFILE_RECORD_MISSING");
            var sourceProperties=(Map<String,String>)admission.privateSourcePayload(f).get("properties");
            require("true".equals(sourceProperties.get("app.control.controller-bootstrap.enabled")),"BOOTSTRAP_SOURCE_PROFILE_RECEIVER_REQUIRED");
            var pair=plans.prepare(f);String approval=journal.job(f.spec().id()).get("requestHash").toString();
            var proof=(Map<?,?>)f.sourceEvidence().get("proof");var target=(Map<?,?>)journal.successfulReceipt(f.spec().id(),CONTROLLER_VERIFY).get("database");
            require(proof!=null&&target!=null,"BOOTSTRAP_PROFILE_PROCESS_PROOF_REQUIRED");
            UUID sourceId=UUID.fromString(Objects.toString(proof.get("holderId"))),targetId=UUID.fromString(Objects.toString(target.get("holderId")));
            require(("control-"+f.spec().targetServerId().toString().replace("-","").substring(0,24)).equals(target.get("controllerName")),"BOOTSTRAP_PROFILE_PROCESS_PROOF_REQUIRED");
            var bundle=(Map<String,Object>)nativePhases.saved(f,CONTROLLER_CONFIG,approval).header().get("bundle");
            String application;try{application=you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot.sha(ControlBusinessRuntime.render((Map<String,String>)bundle.get("properties")).getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
            catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_PROFILE_ORIGINAL_CHANGED");}
            String agent=ControlServers.resource("controller-bootstrap-profile-request.py");
            var targetProperties=(Map<String,String>)bundle.get("properties");
            var bridge=new Bridge(agent,ControlBootstrapNativeJson.sha(agent),sourceProperties.get("app.control.node-token-file"),targetProperties.get("app.control.node-token-file"));
            var business=nativePhases.saved(f,BUSINESS_INSTALL,approval);var businessHeader=business.header();
            require(ControlBootstrapJournal.phaseId(f.spec().id(),BUSINESS_INSTALL).toString().equals(businessHeader.get("id")),"BOOTSTRAP_PROFILE_ORIGINAL_CHANGED");
            var services=new Services(ControlBootstrapJournal.phaseId(f.spec().id(),BUSINESS_INSTALL),business.binding(),Objects.toString(businessHeader.get("expectedServicesBaselineSha256")),Objects.toString(businessHeader.get("controllerBinding")));
            var draft=new Accepted(f,pair,approval,sourceId,targetId,application,bridge,services,"");var a=new Accepted(f,pair,approval,sourceId,targetId,application,bridge,services,binding(store,draft));validate(store,journal,admission,a,false);
            var body=new LinkedHashMap<String,Object>();body.put("inputBinding",a.binding());var rows=new LinkedHashMap<String,Object>();for(Role role:Role.values())rows.put(role.name(),new LinkedHashMap<>(Map.of("state","PENDING")));body.put("operations",rows);
            journal.profileInputIntent(f.spec().id(),a.binding(),intent->admission.beforeMembershipIntent(f,()->{intent.run();return null;}),()->{
                save(store,inputPath(store,f.spec().id()),a);save(store,operationPath(store,f.spec().id()),body);
            });return original(f.spec().id());
        });
    }
    public Permit claim(ControlBootstrapAdmission.Frozen f,Role role){var a=ensure(f);require(f.binding().equals(a.frozen().binding()),"BOOTSTRAP_PROFILE_ACCEPTANCE_CHANGED");
        return locked(()->{var body=operations(store,a);var r=row(body,role);if(!"PENDING".equals(r.get("state")))return new Permit(a,role,false);
            if(role==Role.TARGET)require("SUCCEEDED".equals(row(body,Role.SOURCE).get("state")),"BOOTSTRAP_PREVIOUS_PHASE_REQUIRED");
            require(journal.approvedPermissions(f.spec().id()).contains("INSTALL_BUSINESS_AND_JOIN"),"BOOTSTRAP_PROFILE_SCOPE_REQUIRED");
            journal.memberConfigurationIntent(f.spec().id(),intent->admission.beforeProfileIntent(a,journal,()->{intent.run();return null;}),()->{r.put("state","CLAIMED");save(store,operationPath(store,f.spec().id()),body);});return new Permit(a,role,true);});
    }
    static boolean sourcePublished(ControlServerStore store,Accepted a){return "SUCCEEDED".equals(row(operations(store,a),Role.SOURCE).get("state"));}
    void requireClaimed(Accepted a,Role role){require(Set.of("CLAIMED","SUCCEEDED").contains(row(operations(store,a),role).get("state")),"BOOTSTRAP_PROFILE_CLAIM_REQUIRED");}
    void requireEffect(Accepted a,Role role){
        requireClaimed(a,role);var job=journal.job(a.frozen().spec().id());
        require(!Boolean.TRUE.equals(job.get("requiresReapproval")),"BOOTSTRAP_REAPPROVAL_REQUIRED");
        require(!Set.of("SUCCEEDED","FAILED").contains(job.get("state"))&&journal.approvedPermissions(a.frozen().spec().id()).contains("INSTALL_BUSINESS_AND_JOIN"),"BOOTSTRAP_TASK_TERMINAL");
    }
    static Map<String,Object> sign(ControlServerStore store,Map<String,Object> fields){var result=new LinkedHashMap<>(fields);result.put("signature",store.fingerprint(canonical(fields)));return result;}
    private static void validateReceipt(ControlServerStore store,Accepted a,Role role,Map<String,Object> receipt){
        require(JSON.writeValueAsBytes(receipt).length<=8192&&receipt.keySet().equals(Set.of("protocol","bootstrapId","role","binding","planBinding","controllerId","controllerName","applicationSha256","state","observedBinding","joinAuthorized","signature")),"BOOTSTRAP_PROFILE_RECEIPT_CHANGED");
        var fields=new LinkedHashMap<>(receipt);Object sig=fields.remove("signature");require(sig instanceof String&&java.security.MessageDigest.isEqual(store.fingerprint(canonical(fields)).getBytes(java.nio.charset.StandardCharsets.US_ASCII),sig.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)),"BOOTSTRAP_PROFILE_RECEIPT_CHANGED");
        require("eatwhat-controller-profile-publication-v1".equals(receipt.get("protocol"))&&a.frozen().spec().id().toString().equals(receipt.get("bootstrapId"))&&role.name().equals(receipt.get("role"))
                &&a.binding().equals(receipt.get("binding"))&&plan(a,role).binding().equals(receipt.get("planBinding"))&&controllerId(a,role).toString().equals(receipt.get("controllerId"))
                &&controllerName(a,role).equals(receipt.get("controllerName"))&&applicationHash(a,role).equals(receipt.get("applicationSha256"))&&Boolean.FALSE.equals(receipt.get("joinAuthorized"))
                &&Set.of("RUNTIME_RELOADED","OUTCOME_UNKNOWN").contains(receipt.get("state")),"BOOTSTRAP_PROFILE_RECEIPT_CHANGED");
        if("RUNTIME_RELOADED".equals(receipt.get("state")))require(plan(a,role).afterBinding().equals(receipt.get("observedBinding")),"BOOTSTRAP_PROFILE_RECEIPT_CHANGED");
    }
    public void observe(Accepted a,Role role,Map<String,Object> receipt){verify(store,journal,admission,a);validateReceipt(store,a,role,receipt);
        locked(()->{var body=operations(store,a);var r=row(body,role);require(Set.of("CLAIMED","SUCCEEDED").contains(r.get("state")),"BOOTSTRAP_PROFILE_CLAIM_REQUIRED");
            if("SUCCEEDED".equals(r.get("state"))){require("RUNTIME_RELOADED".equals(receipt.get("state")),"BOOTSTRAP_PROFILE_RECEIPT_CHANGED");return null;}
            r.put("receipt",receipt);if("RUNTIME_RELOADED".equals(receipt.get("state")))r.put("state","SUCCEEDED");save(store,operationPath(store,a.frozen().spec().id()),body);return null;});}
    public Map<String,Object> progress(Accepted a){verify(store,journal,admission,a);var body=operations(store,a);return Map.of("SOURCE",row(body,Role.SOURCE).get("state"),"TARGET",row(body,Role.TARGET).get("state"));}
}
