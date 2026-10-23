package you.v50to.eatwhat.control;

import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import java.time.Instant;
import java.util.*;

/** Current SOURCE facts under the original, authenticated profile successor.
 * No execution/lease fence, claim, profile write or approval replacement. */
public final class ControlBootstrapSourceInspection {
    private final ControlBootstrapProfileState profiles;private final ControlBootstrapNativePhases nativePhases;private final ControlBootstrapSources sources;
    public ControlBootstrapSourceInspection(ControlBootstrapProfileState profiles,ControlBootstrapNativePhases nativePhases,ControlBootstrapSources sources){this.profiles=profiles;this.nativePhases=nativePhases;this.sources=sources;}
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    Map<String,Object> inspectJoined(ControlBootstrapAdmission.Frozen f){
        nativePhases.bound(f);var profile=profiles.original(f.spec().id());var progress=profiles.progress(profile);
        require(f.binding().equals(profile.frozen().binding())&&progress.keySet().equals(Set.of("SOURCE","TARGET"))&&progress.values().stream().allMatch("SUCCEEDED"::equals),"BOOTSTRAP_PROFILES_NOT_READY");
        var original=profile.plans().sourceCaptureRequest();var e=original.expected();
        var joined=new ControllerSourceSnapshot.Expected(e.clusterId(),e.authoritySiteId(),e.authorityInstanceId(),e.authorityDatabaseFingerprint(),e.stateId(),f.spec().epoch()+1,e.controllerName(),e.applicationSha256(),e.profilesSha256(),e.sharedKeySha256());
        var request=new ControlBootstrapSources.Request(original.sourceArtifactSha256(),original.probeArtifactSha256(),original.unitSha256(),joined);
        var seen=sources.inspectOriginal(f.spec().sourceServerId(),f.spec().sourceRevision(),f.sourceRoute(),request);
        require(seen.serverId().equals(f.spec().sourceServerId())&&seen.revision()==f.spec().sourceRevision()&&seen.routeBinding().equals(f.sourceRoute())
                &&seen.privatePayload().isEmpty(),"BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE");
        require(f.sourceEvidence().get("sourceProcess") instanceof Map<?,?> before&&!before.isEmpty()
                &&profile.plans().sourceCaptureFileHashes()!=null&&!profile.plans().sourceCaptureFileHashes().isEmpty()
                &&ControlBootstrapProfileState.canonical(profile.plans().sourceCaptureFileHashes()).equals(ControlBootstrapProfileState.canonical(seen.evidence().get("fileHashes")))
                &&ControlBootstrapProfileState.canonical(before).equals(ControlBootstrapProfileState.canonical(seen.evidence().get("sourceProcess"))),"BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED");
        // Re-read authenticated profile progress after remote I/O; never merge a
        // new file publication into an observation of the old original.
        var after=profiles.original(f.spec().id());require(profile.binding().equals(after.binding())&&f.binding().equals(after.frozen().binding())
                &&profiles.progress(after).equals(progress),"BOOTSTRAP_PROFILE_RECORD_CHANGED");
        return Map.of("sourceConfigurationRevalidated",true,"sourceProcessRevalidated",true,"sourceCheckedAt",Instant.now().toString());
    }
}
