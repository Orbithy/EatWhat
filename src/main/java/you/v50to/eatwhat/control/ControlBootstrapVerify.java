package you.v50to.eatwhat.control;

import org.springframework.beans.factory.ObjectProvider;
import you.v50to.eatwhat.cluster.*;
import java.time.Instant;
import java.util.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Terminal publication follows fresh original-source and joined-runtime
 * verification. Registry projection precedes journal success, so losing that
 * acknowledgement can repeat only read-only checks and the same projection. */
public final class ControlBootstrapVerify {
    private final ControlServerStore store;private final ControlBootstrapJournal journal;private final ControlBootstrapAdmission admission;
    private final ControlBootstrapProfileState profiles;private final ControlBootstrapMembership membership;private final ControlBootstrapMemberRuntime runtime;
    private final ObjectProvider<ClusterStore> cluster;
    public ControlBootstrapVerify(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapAdmission admission,ControlBootstrapProfileState profiles,
            ControlBootstrapMembership membership,ControlBootstrapMemberRuntime runtime,ObjectProvider<ClusterStore> cluster){this.store=store;this.journal=journal;this.admission=admission;this.profiles=profiles;this.membership=membership;this.runtime=runtime;this.cluster=cluster;}
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private void task(UUID id){var job=journal.job(id);require(!Boolean.TRUE.equals(job.get("requiresReapproval")),"BOOTSTRAP_REAPPROVAL_REQUIRED");
        require(!Set.of("FAILED","SUCCEEDED").contains(job.get("state"))&&journal.approvedPermissions(id).contains("INSTALL_BUSINESS_AND_JOIN"),"BOOTSTRAP_TASK_TERMINAL");}
    private static void fresh(Map<String,Object> seen){try{var checked=Instant.parse(seen.get("healthCheckedAt").toString());var now=Instant.now();require(!checked.isBefore(now.minusSeconds(60))&&!checked.isAfter(now.plusSeconds(2)),"BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED");}
        catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED");}}
    public Map<String,Object> advance(ControlBootstrapAdmission.Frozen f){
        admission.requireBound(f);journal.requireAdmission(f.spec().id(),f.spec(),f.actor(),f.review(),f.binding());
        var job=journal.job(f.spec().id());if("SUCCEEDED".equals(job.get("state")))return job;
        var accepted=membership.original(f.spec().id());var profile=profiles.original(f.spec().id());require(f.binding().equals(profile.frozen().binding())&&accepted.profileBinding().equals(profile.binding()),"BOOTSTRAP_MEMBERSHIP_RECORD_CHANGED");
        var proof=membership.joinedProof(f);var joined=journal.successfulReceipt(f.spec().id(),MEMBER_JOIN);
        require("ACTIVE".equals(joined.get("state"))&&accepted.joinId().toString().equals(joined.get("changeId"))&&accepted.binding().equals(joined.get("membershipBinding")),"BOOTSTRAP_JOIN_CONFIRMATIONS_REQUIRED");
        var claim=journal.claimFenced(f.spec().id(),VERIFY,intent->admission.beforeVerifyIntent(profile,accepted,journal,cluster.getObject(),()->{task(f.spec().id());intent.run();return null;}));
        if(!claim.dispatchAllowed())admission.beforeVerifyIntent(profile,accepted,journal,cluster.getObject(),()->{task(f.spec().id());return null;});
        var seen=runtime.joined(f,proof);fresh(seen);
        require(Boolean.TRUE.equals(seen.get("nativeServicesRevalidated"))&&Boolean.TRUE.equals(seen.get("networkBaselineRevalidated")),"BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED");
        return admission.beforeVerifyIntent(profile,accepted,journal,cluster.getObject(),()->{
            task(f.spec().id());fresh(seen);
            require(ControlBootstrapNativeJson.encode(proof).equals(ControlBootstrapNativeJson.encode(membership.joinedProof(f))),"BOOTSTRAP_JOIN_SUCCESSOR_CHANGED");
            project(f,accepted,seen);
            var receipt=new LinkedHashMap<String,Object>();receipt.put("protocol",VERIFY.protocol);receipt.put("id",claim.id().toString());receipt.put("state","VERIFIED");
            receipt.put("bootstrapId",f.spec().id().toString());receipt.put("changeId",accepted.joinId().toString());receipt.put("epoch",f.spec().epoch()+1);receipt.put("membershipBinding",accepted.binding());
            receipt.put("runtimeProof",seen);receipt.put("sourceConfigurationRevalidated",true);receipt.put("serverRegistrationProjected",true);receipt.put("originalApprovalRetained",true);
            return journal.observe(f.spec().id(),VERIFY,claim.claimToken(),receipt);
        });
    }
    @SuppressWarnings("unchecked") private void project(ControlBootstrapAdmission.Frozen f,ControlBootstrapMembership.Accepted accepted,Map<String,Object> seen){
        store.mutate(data->{var servers=(Map<String,Object>)data.get("servers");require(servers.get(f.spec().targetServerId().toString()) instanceof Map<?,?>,"SERVER_NOT_FOUND");
            var server=(Map<String,Object>)servers.get(f.spec().targetServerId().toString());admission.requireRegisteredServer(f,false,server);
            require(server.get("installation") instanceof Map<?,?>,"BOOTSTRAP_BUSINESS_REGISTRATION_CHANGED");var old=(Map<String,Object>)server.get("installation");var member=ControlBootstrapBusinessAllocation.identity(f.spec());
            require("SUCCEEDED".equals(old.get("state"))&&ControlBootstrapJournal.phaseId(f.spec().id(),BUSINESS_INSTALL).toString().equals(old.get("jobId"))
                    &&f.spec().id().toString().equals(old.get("bootstrapJobId"))&&member.siteId().equals(old.get("siteId"))&&member.instanceId().toString().equals(old.get("instanceId"))
                    &&f.spec().clusterId().toString().equals(old.get("clusterId"))&&"EXTERNAL".equals(old.get("mode"))&&old.get("allocationSlot") instanceof Number slot&&slot.intValue()==member.allocationSlot()
                    &&f.spec().businessSha256().equals(old.get("currentSha256")),"BOOTSTRAP_BUSINESS_REGISTRATION_CHANGED");
            require("NOT_JOINED".equals(old.get("clusterMembership"))||"ACTIVE".equals(old.get("clusterMembership"))&&accepted.joinId().toString().equals(old.get("membershipChangeId"))
                    &&old.get("joinedEpoch") instanceof Number epoch&&epoch.longValue()==f.spec().epoch()+1,"BOOTSTRAP_BUSINESS_REGISTRATION_CHANGED");
            var value=new LinkedHashMap<>(old);value.put("clusterMembership","ACTIVE");value.put("membershipChangeId",accepted.joinId().toString());value.put("joinedEpoch",f.spec().epoch()+1);
            value.put("businessHealthRevalidated",true);value.put("authStoreVerified",true);value.put("storageReadCheckVerified",true);value.put("healthCheckedAt",seen.get("healthCheckedAt"));
            value.put("controllerServicesRevalidated",true);value.put("networkRevalidationRequired",false);server.put("installation",value);return null;
        });
    }
}
