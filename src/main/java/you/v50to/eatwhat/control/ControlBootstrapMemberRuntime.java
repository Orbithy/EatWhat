package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.ClusterMember;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.function.Supplier;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Delivers the original reserved member's runtime through its pinned SSH route.
 * Credentials come only from sealed installation/role inputs. This child never
 * edits controller profiles or registers/JOINs a member. */
public final class ControlBootstrapMemberRuntime {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ControlServerStore store;private final ControlBootstrapJournal journal;private final ControlBootstrapNativePhases nativePhases;
    private final ControlBootstrapPhaseClaims claims;private final ControlBootstrapNativeInputs inputs;
    private final ControlBootstrapMemberRelay relay;private final ControlBootstrapMemberAuth auth;private final ControlBootstrapMemberIngress ingress;
    public ControlBootstrapMemberRuntime(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapNativePhases nativePhases,
            ControlBootstrapPhaseClaims claims,ControlBootstrapNativeInputs inputs,ControlBootstrapMemberRelay relay,ControlBootstrapMemberAuth auth,ControlBootstrapMemberIngress ingress){
        this.store=store;this.journal=journal;this.nativePhases=nativePhases;this.claims=claims;this.inputs=inputs;this.relay=relay;this.auth=auth;this.ingress=ingress;
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private static UUID id(ControlBootstrapAdmission.Frozen f,String suffix){return UUID.nameUUIDFromBytes((f.spec().id()+":"+suffix).getBytes(StandardCharsets.UTF_8));}
    private Path path(ControlBootstrapAdmission.Frozen f){return store.directory().resolve("controller-member-runtime-"+f.spec().id()+".json");}
    private String approval(ControlBootstrapAdmission.Frozen f){return journal.job(f.spec().id()).get("requestHash").toString();}
    private ControlBootstrapNativeInputs.Sealed original(ControlBootstrapAdmission.Frozen f,String suffix,boolean source){
        return inputs.load(id(f,suffix),MEMBER_CONFIGURE,approval(f),source?f.spec().sourceServerId():f.spec().targetServerId(),
                source?f.spec().sourceRevision():f.spec().targetRevision(),source?f.sourceRoute():f.targetRoute());
    }
    private ControlBootstrapNativeInputs.Sealed input(ControlBootstrapAdmission.Frozen f){nativePhases.bound(f);return original(f,"MEMBER_RUNTIME_INPUTS",false);}
    static String agent(){
        String recovery=ControlServers.resource("first-install-recovery.py").replace("__EATWHAT_BOOTSTRAP_BASE64__",Base64.getEncoder().encodeToString(ControlServers.resource("first-install.py").getBytes(StandardCharsets.UTF_8)));
        return ControlServers.resource("member-configure.py").replace("__EATWHAT_RECOVERY_BASE64__",Base64.getEncoder().encodeToString(recovery.getBytes(StandardCharsets.UTF_8)));
    }
    private void prerequisites(ControlBootstrapAdmission.Frozen f){
        require(journal.approvedPermissions(f.spec().id()).containsAll(Set.of("INSTALL_BUSINESS_AND_JOIN",ControlBootstrapMemberRelay.PERMISSION,
                ControlBootstrapMemberAuth.PERMISSION,ControlBootstrapMemberIngress.PERMISSION)),"BOOTSTRAP_MEMBER_RUNTIME_SCOPE_REQUIRED");
        auth.ready(f);ingress.ready(f);
    }
    @SuppressWarnings("unchecked") private ControlBootstrapNativeInputs.Prepared prepare(ControlBootstrapAdmission.Frozen f){
        prerequisites(f);var material=relay.materials(f);var config=(Map<String,Object>)material.get("config");
        var business=nativePhases.saved(f,BUSINESS_INSTALL,approval(f));var b=(Map<String,Object>)business.header().get("business");
        var role=original(f,"MEMBER_AUTH_ROLE_INPUTS",true);var r=(Map<String,Object>)role.header().get("role");
        var entry=original(f,"MEMBER_HTTPS_INGRESS_INPUTS",false);var expected=f.sourceRequest().expected();
        var member=ControlBootstrapBusinessAllocation.identity(f.spec());String username="eatwhat_auth_"+f.spec().targetServerId().toString().replace("-","");
        require(f.spec().targetServerId().toString().equals(b.get("serverId"))&&member.siteId().equals(b.get("siteId"))&&member.instanceId().toString().equals(b.get("instanceId"))
                &&f.spec().clusterId().toString().equals(b.get("clusterId"))&&"EXTERNAL".equals(b.get("mode"))
                &&b.get("allocationSlot") instanceof Number slot&&slot.intValue()==member.allocationSlot()
                &&f.artifacts().business().sha256().equals(b.get("artifactSha256")),"BOOTSTRAP_MEMBER_RUNTIME_ORIGINAL_CHANGED");
        require(f.spec().id().toString().equals(r.get("bootstrapId"))&&f.spec().targetServerId().toString().equals(r.get("targetServerId"))
                &&f.spec().clusterId().toString().equals(r.get("clusterId"))&&expected.authoritySiteId().equals(r.get("authoritySiteId"))
                &&expected.authorityInstanceId().toString().equals(r.get("authorityInstanceId"))&&username.equals(config.get("databaseUsername")),"BOOTSTRAP_MEMBER_RUNTIME_ORIGINAL_CHANGED");
        String gateway=Objects.toString(config.get("listenAddress")),database=Objects.toString(config.get("databaseName"));
        require(privateIpv4(gateway)&&database.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"),"BOOTSTRAP_MEMBER_RUNTIME_ROUTE_INVALID");
        var controller=nativePhases.saved(f,CONTROLLER_CONFIG,approval(f));var bundle=(Map<String,Object>)controller.header().get("bundle");
        require(expected.authoritySiteId().equals(bundle.get("authoritySiteId"))&&expected.authorityInstanceId().toString().equals(bundle.get("authorityInstanceId")),"BOOTSTRAP_MEMBER_RUNTIME_ORIGINAL_CHANGED");
        var files=(Map<String,String>)bundle.get("privateFiles");
        var profiles=JSON.readValue(Base64.getDecoder().decode(files.get("cluster-profiles.json")),Map.class);
        var authorities=profiles.values().stream().filter(v->v instanceof Map<?,?> p&&expected.authoritySiteId().equals(p.get("siteId"))).toList();
        require(authorities.size()==1,"BOOTSTRAP_MEMBER_RUNTIME_ORIGINAL_CHANGED");var profile=(Map<String,Object>)authorities.getFirst();
        require(expected.authorityInstanceId().toString().equals(profile.get("instanceId"))&&"CAMPUS".equals(profile.get("mode"))
                &&Integer.valueOf(0).equals(profile.get("allocationSlot")),"BOOTSTRAP_MEMBER_RUNTIME_ORIGINAL_CHANGED");String prefix=Objects.toString(profile.getOrDefault("apiPrefix",""));
        var authority=new ClusterMember(expected.authoritySiteId(),expected.authorityInstanceId(),"CAMPUS",0);
        var properties=new LinkedHashMap<>(ControlMemberConfiguration.runtime(f.spec().clusterId(),member,authority,"https://"+gateway+":28443",prefix,
                "jdbc:postgresql://"+gateway+":25443/"+database,username,ControlMemberConfiguration.AuthTransport.TLS_TERMINATED));
        properties.put("app.cluster.node-ca-file","/config/authority-ca.pem");properties.put("app.control.node-ca-file","/config/authority-ca.pem");
        String ca=Objects.toString(material.get("caPem"));validateCa(ca);String password=Objects.toString(r.get("password")),token=Objects.toString(b.get("nodeToken"));
        require(password.matches("[A-Za-z0-9_-]{43,128}")&&token.matches("[A-Za-z0-9_-]{43,128}"),"BOOTSTRAP_MEMBER_RUNTIME_ORIGINAL_CHANGED");
        var prepared=new ControlMemberConfiguration.Prepared(member.siteId(),member,properties,password,ca,token,ControlMemberConfiguration.AuthTransport.TLS_TERMINATED);
        String agent=agent();var h=new LinkedHashMap<String,Object>();h.put("id",id(f,"MEMBER_RUNTIME").toString());
        for(String key:List.of("serverId","siteId","instanceId","clusterId","allocationSlot","artifactSha256"))h.put(key,b.get(key));
        h.put("runtime",prepared.privatePayload());h.put("approvalDigest",approval(f));h.put("agentSha256",ControlBootstrapNativeJson.sha(agent));
        h.put("bootstrapContext",Map.of("bootstrapId",f.spec().id().toString(),"ingressId",entry.header().get("id"),"ingressBinding",entry.binding(),"ingressAgentSha256",entry.header().get("agentSha256"),"baseEpoch",f.spec().epoch()));
        var seen=nativePhases.call(nativePhases.server(f,MEMBER_CONFIGURE),agent,List.of("inspect"),h);identity(h,seen);
        require(Boolean.TRUE.equals(seen.get("ready"))&&seen.get("freezeHash") instanceof String hash&&hash.matches("[a-f0-9]{64}")
                &&seen.get("databaseFingerprint") instanceof String fp&&fp.matches("[a-f0-9]{32}"),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        h.put("freezeHash",seen.get("freezeHash"));h.put("databaseFingerprint",seen.get("databaseFingerprint"));
        return new ControlBootstrapNativeInputs.Prepared(h,agent);
    }
    private static boolean privateIpv4(String value){
        try{var p=value.split("\\.",-1);if(p.length!=4)return false;int[] n=new int[4];for(int i=0;i<4;i++){n[i]=Integer.parseInt(p[i]);if(n[i]<0||n[i]>255||!p[i].equals(Integer.toString(n[i])))return false;}
            return n[0]==10||n[0]==172&&n[1]>=16&&n[1]<=31||n[0]==192&&n[1]==168;
        }catch(Exception ignored){return false;}
    }
    private static void validateCa(String ca){
        try{
            require(ca.length()<=65536&&!ca.contains("PRIVATE KEY")&&ca.strip().startsWith("-----BEGIN CERTIFICATE-----")&&ca.strip().endsWith("-----END CERTIFICATE-----")
                    &&ca.chars().allMatch(c->c<128),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
            var certs=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(ca.getBytes(StandardCharsets.US_ASCII)));
            require(!certs.isEmpty()&&certs.size()<=16,"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");for(var c:certs){var cert=(java.security.cert.X509Certificate)c;cert.checkValidity();require(cert.getBasicConstraints()>=0,"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");}
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");}
    }
    private static void identity(Map<String,Object> h,Map<String,Object> receipt){
        require("eatwhat-member-configure-v1".equals(receipt.get("protocol"))&&h.get("id").equals(receipt.get("id"))&&h.get("serverId").equals(receipt.get("serverId"))
                &&JSON.writeValueAsBytes(receipt).length<=8192,"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
    }
    private <T>T locked(Supplier<T> action){
        Path p=store.directory().resolve("member-runtime-operations.lock");
        try{
            try{Files.createFile(p,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException ignored){}
            ControlServerStore.privateFile(p,1024);try(var ch=FileChannel.open(p,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                FileLock lock;try{lock=ch.tryLock();}catch(OverlappingFileLockException busy){throw new ControlFailure(409,"BOOTSTRAP_MEMBER_RUNTIME_BUSY");}
                require(lock!=null,"BOOTSTRAP_MEMBER_RUNTIME_BUSY");try(lock){return action.get();}
            }
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_MEMBER_RUNTIME_UNAVAILABLE");}
    }
    private void save(ControlBootstrapAdmission.Frozen f,Map<String,Object> body){String raw=JSON.writeValueAsString(body);store.saveJson(path(f),Map.of("version",1,"body",store.encrypt(raw),"seal",store.fingerprint(raw)));}
    private Map<String,Object> load(ControlBootstrapAdmission.Frozen f,ControlBootstrapNativeInputs.Sealed input){
        require(Files.exists(path(f),LinkOption.NOFOLLOW_LINKS),"BOOTSTRAP_MEMBER_RUNTIME_RECORD_MISSING");var saved=store.readJson(path(f));
        require(Integer.valueOf(1).equals(saved.get("version")),"BOOTSTRAP_MEMBER_RUNTIME_RECORD_CHANGED");String raw=store.decrypt(saved.get("body").toString());
        require(store.fingerprint(raw).equals(saved.get("seal")),"BOOTSTRAP_MEMBER_RUNTIME_RECORD_CHANGED");var body=JSON.readValue(raw,LinkedHashMap.class);
        require(f.spec().id().toString().equals(body.get("bootstrapId"))&&input.binding().equals(body.get("inputBinding"))&&input.approvalDigest().equals(body.get("approvalDigest"))
                &&Set.of("PENDING","CLAIMED","CONFIGURED","FAILED").contains(body.get("state")),"BOOTSTRAP_MEMBER_RUNTIME_RECORD_CHANGED");return body;
    }
    private void ensure(ControlBootstrapAdmission.Frozen f){
        nativePhases.bound(f);nativePhases.server(f,MEMBER_CONFIGURE);
        inputs.prepare(id(f,"MEMBER_RUNTIME_INPUTS"),MEMBER_CONFIGURE,approval(f),f.spec().targetServerId(),f.spec().targetRevision(),f.targetRoute(),
                !Files.exists(path(f),LinkOption.NOFOLLOW_LINKS),()->prepare(f),input->{locked(()->{save(f,new LinkedHashMap<>(Map.of("bootstrapId",f.spec().id().toString(),
                    "inputBinding",input.binding(),"approvalDigest",input.approvalDigest(),"state","PENDING")));return null;});claims.memberConfiguration(f);});
    }
    private Map<String,Object> result(ControlBootstrapAdmission.Frozen f,ControlBootstrapNativeInputs.Sealed input){
        var retained=locked(()->load(f,input));
        if("FAILED".equals(retained.get("state"))){
            @SuppressWarnings("unchecked") var receipt=(Map<String,Object>)retained.get("receipt");identity(input.header(),receipt);
            if(!"FAILED".equals(receipt.get("state")))throw new ControlFailure(409,"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
            return claims.memberConfigurationFailed(f,"RUNTIME",receipt);
        }
        var result=new LinkedHashMap<>(journal.job(f.spec().id()));result.put("memberRuntimeState",retained.get("state"));
        result.put("controllerProfilesPending",true);result.put("clusterJoinPending",true);return result;
    }
    private Map<String,Object> verify(ControlBootstrapAdmission.Frozen f,ControlBootstrapNativeInputs.Sealed input){
        var seen=nativePhases.call(nativePhases.server(f,MEMBER_CONFIGURE),input.agent(),List.of("verify"),input.header());identity(input.header(),seen);
        for(String key:List.of("siteId","instanceId","clusterId","allocationSlot","artifactSha256","databaseFingerprint"))require(Objects.equals(input.header().get(key),seen.get(key)),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        require("CONFIGURED".equals(seen.get("state"))&&Boolean.TRUE.equals(seen.get("configurationRevalidated"))&&Boolean.TRUE.equals(seen.get("authStoreVerified"))
                &&Boolean.TRUE.equals(seen.get("dataPreserved"))&&"NOT_JOINED".equals(seen.get("clusterMembership"))&&Boolean.FALSE.equals(seen.get("joinAuthorized")),"BOOTSTRAP_MEMBER_RUNTIME_NOT_VERIFIED");return seen;
    }
    public Map<String,Object> advance(ControlBootstrapAdmission.Frozen f){
        ensure(f);var input=input(f);
        String state=locked(()->load(f,input).get("state").toString());if("FAILED".equals(state))return result(f,input);
        if("CONFIGURED".equals(state))return result(f,input);
        boolean dispatch=locked(()->{var body=load(f,input);if(!"PENDING".equals(body.get("state")))return false;
            prerequisites(f);relay.ready(f);claims.memberConfigurationIntent(f,()->{body.put("state","CLAIMED");save(f,body);});return true;});
        var payload=new LinkedHashMap<>(input.header());if(dispatch)payload.put("agent",input.agent());
        var seen=nativePhases.call(nativePhases.server(f,MEMBER_CONFIGURE),input.agent(),List.of(dispatch?"dispatch":"status"),payload);identity(input.header(),seen);
        require(Set.of("ACCEPTED","CONFIGURED","FAILED","NOT_STARTED","OUTCOME_UNKNOWN","REJECTED").contains(seen.get("state")),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        if("CONFIGURED".equals(seen.get("state")))seen=verify(f,input);var receipt=seen;
        locked(()->{var body=load(f,input);require("CLAIMED".equals(body.get("state")),"BOOTSTRAP_MEMBER_RUNTIME_RECORD_CHANGED");
            if(Set.of("CONFIGURED","FAILED").contains(receipt.get("state")))body.put("state",receipt.get("state"));body.put("receipt",receipt);save(f,body);return null;});return result(f,input);
    }
    /** Fresh final proof is required by the future profile/JOIN driver. */
    Map<String,Object> ready(ControlBootstrapAdmission.Frozen f){var input=input(f);require("CONFIGURED".equals(locked(()->load(f,input).get("state"))),"BOOTSTRAP_MEMBER_RUNTIME_NOT_READY");prerequisites(f);return verify(f,input);}
    /** Read-only successor verification uses the original saved agent/header.
     * It never overlays a new script or changes an old configuration receipt. */
    Map<String,Object> joined(ControlBootstrapAdmission.Frozen f,Map<String,Object> proof){
        var input=input(f);require("CONFIGURED".equals(locked(()->load(f,input).get("state"))),"BOOTSTRAP_MEMBER_RUNTIME_NOT_READY");prerequisites(f);
        return joined(f,input,proof);
    }
    /** Reads the original input and configured ledger without ensuring/locking
     * or projecting them. Target diagnostics do not grant source-side authority. */
    Map<String,Object> inspectJoined(ControlBootstrapAdmission.Frozen f,Map<String,Object> proof){
        var input=input(f);var before=load(f,input);require("CONFIGURED".equals(before.get("state")),"BOOTSTRAP_MEMBER_RUNTIME_NOT_READY");
        require(journal.approvedPermissions(f.spec().id()).containsAll(Set.of("INSTALL_BUSINESS_AND_JOIN",ControlBootstrapMemberRelay.PERMISSION,
                ControlBootstrapMemberAuth.PERMISSION,ControlBootstrapMemberIngress.PERMISSION)),"BOOTSTRAP_MEMBER_RUNTIME_SCOPE_REQUIRED");
        var seen=joined(f,input,proof);require(before.equals(load(f,input)),"BOOTSTRAP_MEMBER_RUNTIME_RECORD_CHANGED");return seen;
    }
    private Map<String,Object> joined(ControlBootstrapAdmission.Frozen f,ControlBootstrapNativeInputs.Sealed input,Map<String,Object> proof){
        require(proof.keySet().equals(Set.of("bootstrapId","changeId","baseEpoch","epoch","databaseFingerprint","members","membershipBinding"))
                &&f.spec().id().toString().equals(proof.get("bootstrapId"))&&ControlBootstrapJournal.phaseId(f.spec().id(),MEMBER_JOIN).toString().equals(proof.get("changeId"))
                &&proof.get("baseEpoch") instanceof Number base&&base.longValue()==f.spec().epoch()&&proof.get("epoch") instanceof Number epoch&&epoch.longValue()==f.spec().epoch()+1
                &&Objects.equals(input.header().get("databaseFingerprint"),proof.get("databaseFingerprint"))&&proof.get("membershipBinding") instanceof String binding&&binding.matches("[A-Za-z0-9+/]{43}="),"BOOTSTRAP_JOIN_SUCCESSOR_CHANGED");
        require(input.header().get("bootstrapContext") instanceof Map<?,?> context&&context.get("baseEpoch") instanceof Number original&&original.longValue()==f.spec().epoch(),"BOOTSTRAP_JOINED_VERIFICATION_UPGRADE_REQUIRED");
        var members=List.of(JSON.convertValue(proof.get("members"),ClusterMember[].class));var local=ControlBootstrapBusinessAllocation.identity(f.spec());var authority=f.sourceRequest().expected();
        require(members.size()>=2&&members.size()<=16&&members.contains(local)&&members.contains(new ClusterMember(authority.authoritySiteId(),authority.authorityInstanceId(),"CAMPUS",0))
                &&members.stream().map(ClusterMember::siteId).distinct().count()==members.size()&&members.stream().map(ClusterMember::instanceId).distinct().count()==members.size()
                &&members.stream().map(ClusterMember::allocationSlot).distinct().count()==members.size(),"BOOTSTRAP_JOIN_SUCCESSOR_CHANGED");
        var plan=new you.v50to.eatwhat.cluster.ClusterMembershipCoordinator.Plan(f.spec().clusterId(),ControlBootstrapJournal.phaseId(f.spec().id(),MEMBER_JOIN),f.spec().epoch(),f.spec().epoch()+1,"JOIN",local.siteId(),members,members);
        Map<String,Object> transport=JSON.readValue(JSON.writeValueAsString(proof),Map.class);
        var seen=nativePhases.call(nativePhases.server(f,MEMBER_CONFIGURE),input.agent(),List.of("verify-joined"),Map.of("original",input.header(),"joinProof",transport));
        require("eatwhat-member-joined-runtime-v1".equals(seen.get("protocol"))&&"JOINED_VERIFIED".equals(seen.get("state"))&&"ACTIVE".equals(seen.get("clusterMembership"))
                &&JSON.writeValueAsBytes(seen).length<=8192&&ControlBootstrapNativeJson.sha(JSON.writeValueAsString(plan)).equals(seen.get("planHash")),"BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED");
        for(String key:List.of("id","serverId","siteId","instanceId","clusterId","allocationSlot","artifactSha256","databaseFingerprint"))require(Objects.equals(input.header().get(key),seen.get(key)),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        for(String key:List.of("bootstrapId","changeId","epoch","membershipBinding"))require(ControlBootstrapNativeJson.encode(proof.get(key)).equals(ControlBootstrapNativeJson.encode(seen.get(key))),"BOOTSTRAP_JOIN_SUCCESSOR_CHANGED");
        for(String key:List.of("configurationRevalidated","authStoreVerified","localMembershipVerified","dataPreserved","nativeServicesRevalidated","networkBaselineRevalidated","applicationReady","databaseReady","redisReady","storageReadCheckVerified"))require(Boolean.TRUE.equals(seen.get(key)),"BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED");
        require(Boolean.FALSE.equals(seen.get("storageWriteProbePerformed"))&&Boolean.FALSE.equals(seen.get("joinAuthorized")),"BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED");
        try{var checked=java.time.Instant.parse(seen.get("healthCheckedAt").toString());var now=java.time.Instant.now();require(!checked.isBefore(now.minusSeconds(30))&&!checked.isAfter(now.plusSeconds(2)),"BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED");}
        catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED");}
        return seen;
    }
}
