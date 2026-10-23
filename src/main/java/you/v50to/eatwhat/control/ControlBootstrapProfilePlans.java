package you.v50to.eatwhat.control;

import you.v50to.eatwhat.cluster.ClusterProfilePublication;
import java.nio.file.Path;
import java.util.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Exact source/target additions from accepted originals. Does not publish,
 * reload, update admission captures or JOIN. A driver must force/seal this pair
 * before the first profile effect and retain it across both hosts. */
public final class ControlBootstrapProfilePlans {
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final ControlBootstrapAdmission admission;private final ControlBootstrapJournal journal;private final ControlBootstrapNativePhases nativePhases;
    private final ControlBootstrapMemberRuntime runtime;private final ControlBootstrapMemberIngress ingress;
    public ControlBootstrapProfilePlans(ControlBootstrapAdmission admission,ControlBootstrapJournal journal,ControlBootstrapNativePhases nativePhases,
            ControlBootstrapMemberRuntime runtime,ControlBootstrapMemberIngress ingress){this.admission=admission;this.journal=journal;this.nativePhases=nativePhases;this.runtime=runtime;this.ingress=ingress;}
    public record Pair(ClusterProfilePublication.Plan source,ClusterProfilePublication.Plan target,Map<String,String> sourceCaptureFileHashes,ControlBootstrapSources.Request sourceCaptureRequest){
        public Pair{sourceCaptureFileHashes=Map.copyOf(sourceCaptureFileHashes);}
        @Override public String toString(){return "BootstrapProfilePlans[redacted]";}
    }
    private static String hash(String encoded){try{return you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot.sha(Base64.getDecoder().decode(encoded));}catch(Exception invalid){throw new ControlFailure(409,"BOOTSTRAP_PROFILE_ORIGINAL_CHANGED");}}
    @SuppressWarnings("unchecked") public Pair prepare(ControlBootstrapAdmission.Frozen f){
        nativePhases.bound(f);if(!journal.approvedPermissions(f.spec().id()).contains("INSTALL_BUSINESS_AND_JOIN"))throw new ControlFailure(409,"BOOTSTRAP_PROFILE_SCOPE_REQUIRED");
        runtime.ready(f);var material=ingress.materials(f);String ca=Objects.toString(material.get("caPem"));
        var source=admission.privateSourcePayload(f);var sourceFiles=(Map<String,String>)source.get("privateFiles");
        var hashes=(Map<String,String>)f.sourceEvidence().get("fileHashes");var properties=(Map<String,String>)source.get("properties");
        String approval=journal.job(f.spec().id()).get("requestHash").toString();var controller=nativePhases.saved(f,CONTROLLER_CONFIG,approval);
        var bundle=(Map<String,Object>)controller.header().get("bundle");var targetFiles=(Map<String,String>)bundle.get("privateFiles");
        var targetHashes=new TreeMap<String,String>();targetFiles.forEach((name,value)->targetHashes.put(name,hash(value)));
        sourceFiles.forEach((name,value)->{if(!hash(value).equals(hashes.get(name)))throw new ControlFailure(409,"BOOTSTRAP_PROFILE_ORIGINAL_CHANGED");});
        var business=(Map<String,Object>)nativePhases.saved(f,BUSINESS_INSTALL,approval).header().get("business");var member=ControlBootstrapBusinessAllocation.identity(f.spec());
        if(!f.spec().targetServerId().toString().equals(business.get("serverId"))||!member.siteId().equals(business.get("siteId"))||!member.instanceId().toString().equals(business.get("instanceId"))
                ||!f.review().get("targetAddress").equals(material.get("targetAddress")))throw new ControlFailure(409,"BOOTSTRAP_PROFILE_ORIGINAL_CHANGED");
        String token=Objects.toString(business.get("nodeToken")),address=Objects.toString(f.review().get("targetAddress"));
        var sourcePlan=ClusterProfilePublication.create(f.spec().id(),f.spec().targetServerId(),member,address,Path.of(properties.get("app.cluster.profiles-file")),
                Base64.getDecoder().decode(sourceFiles.get("cluster-profiles.json")),hashes,token,ca);
        var targetPlan=ClusterProfilePublication.create(f.spec().id(),f.spec().targetServerId(),member,address,Path.of("/opt/eatwhat-control/config/cluster-profiles.json"),
                Base64.getDecoder().decode(targetFiles.get("cluster-profiles.json")),targetHashes,token,ca);
        var afterFiles=new TreeMap<>(sourceFiles);afterFiles.put("cluster-profiles.json",sourcePlan.afterBase64());
        sourcePlan.additionsBase64().forEach((path,raw)->{if(afterFiles.putIfAbsent(Path.of(path).getFileName().toString(),raw)!=null)throw new ControlFailure(409,"BOOTSTRAP_PROFILE_ORIGINAL_CHANGED");});
        String index=you.v50to.eatwhat.control.bootstrap.ControllerSourceMaterials.INDEX;
        if(afterFiles.containsKey(index)){
            var prior=JSON.readValue(Base64.getDecoder().decode(afterFiles.get(index)),Map.class);
            if(!prior.keySet().equals(Set.of("version","files"))||!Integer.valueOf(1).equals(prior.get("version"))||!(prior.get("files") instanceof Map<?,?>))throw new ControlFailure(409,"BOOTSTRAP_PROFILE_ORIGINAL_CHANGED");
            var names=new TreeMap<String,String>((Map<String,String>)prior.get("files"));sourcePlan.additionsBase64().keySet().forEach(path->names.put(path,Path.of(path).getFileName().toString()));
            byte[] raw=JSON.writeValueAsBytes(new TreeMap<>(Map.of("version",1,"files",names)));if(raw.length>32768)throw new ControlFailure(409,"BOOTSTRAP_PROFILE_CAPTURE_TOO_LARGE");afterFiles.put(index,Base64.getEncoder().encodeToString(raw));
        }
        if(JSON.writeValueAsBytes(Map.of("properties",properties,"privateFiles",afterFiles)).length>48*1024)throw new ControlFailure(409,"BOOTSTRAP_PROFILE_CAPTURE_TOO_LARGE");
        var postHashes=new TreeMap<>(hashes);afterFiles.forEach((name,value)->postHashes.put(name,hash(value)));
        var old=f.sourceRequest().expected();var expected=new you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot.Expected(old.clusterId(),old.authoritySiteId(),old.authorityInstanceId(),
                old.authorityDatabaseFingerprint(),old.stateId(),old.epoch(),old.controllerName(),old.applicationSha256(),sourcePlan.afterManifest().get(sourcePlan.profilesFile()),old.sharedKeySha256());
        var request=new ControlBootstrapSources.Request(f.sourceRequest().sourceArtifactSha256(),f.sourceRequest().probeArtifactSha256(),f.sourceRequest().unitSha256(),expected);
        return admission.beforeMembershipIntent(f,()->new Pair(sourcePlan,targetPlan,postHashes,request));
    }
}
