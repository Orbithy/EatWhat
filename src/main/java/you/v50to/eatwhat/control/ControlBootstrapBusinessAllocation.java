package you.v50to.eatwhat.control;

import org.springframework.beans.factory.ObjectProvider;
import you.v50to.eatwhat.cluster.ClusterMember;
import you.v50to.eatwhat.cluster.ClusterStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Normal authority-side reservation before a composite business dispatch.
 * Does not run an installer, register/JOIN a member or grant database roles.
 * Identity derives from the original immutable composite request. */
public final class ControlBootstrapBusinessAllocation {
    private final ControlBootstrapAdmission admission;private final ControlBootstrapJournal journal;private final ObjectProvider<ClusterStore> membership;
    public ControlBootstrapBusinessAllocation(ControlBootstrapAdmission admission,ControlBootstrapJournal journal,ObjectProvider<ClusterStore> membership){this.admission=admission;this.journal=journal;this.membership=membership;}
    private static void require(boolean value,String code){if(!value)throw new ControlFailure(409,code);}
    public static ClusterMember identity(ControlBootstrapJournal.Spec spec){
        return new ClusterMember("server-"+spec.targetServerId().toString().replace("-","").substring(0,12),
                UUID.nameUUIDFromBytes((spec.id()+":BUSINESS_INSTANCE").getBytes(StandardCharsets.UTF_8)),"EXTERNAL",spec.allocationSlot());
    }
    public ClusterMember reserve(ControlBootstrapAdmission.Frozen f){
        admission.requireBound(f);journal.requireAdmission(f.spec().id(),f.spec(),f.actor(),f.review(),f.binding());
        var verified=journal.successfulReceipt(f.spec().id(),CONTROLLER_VERIFY);
        require(CONTROLLER_VERIFY.protocol.equals(verified.get("protocol"))&&ControlBootstrapJournal.phaseId(f.spec().id(),CONTROLLER_VERIFY).toString().equals(verified.get("id"))
                &&"VERIFIED".equals(verified.get("state"))&&Boolean.TRUE.equals(verified.get("controllerHealthProven")),"BOOTSTRAP_CONTROLLER_VERIFICATION_REQUIRED");
        var member=identity(f.spec());var cluster=membership.getIfAvailable();require(cluster!=null,"INSTALLATION_CLUSTER_CONFIGURATION_REQUIRED");
        return admission.beforeMembershipIntent(f,()->{
            require(f.spec().clusterId().toString().equals(cluster.status().get("clusterId")),"INSTALLATION_CLUSTER_CONFIGURATION_REQUIRED");
            var saved=cluster.installationAllocation(f.spec().targetServerId());
            var step=(Map<?,?>)((List<?>)journal.job(f.spec().id()).get("steps")).get(BUSINESS_INSTALL.ordinal());
            require(!saved.isEmpty()||"PENDING".equals(step.get("state")),"BOOTSTRAP_BUSINESS_ALLOCATION_MISSING");
            if(!saved.isEmpty())require(f.spec().clusterId().equals(saved.get("cluster_id"))&&member.siteId().equals(saved.get("site_id"))
                    &&member.instanceId().equals(saved.get("instance_id"))&&member.mode().equals(saved.get("mode"))&&((Number)saved.get("allocation_slot")).intValue()==member.allocationSlot()
                    &&ControlBootstrapJournal.phaseId(f.spec().id(),BUSINESS_INSTALL).equals(saved.get("first_job_id")),"INSTALLATION_ALLOCATION_RETAINED");
            cluster.reserveInstallation(f.actor(),ControlBootstrapJournal.phaseId(f.spec().id(),BUSINESS_INSTALL),f.spec().targetServerId(),member);return member;
        });
    }
}
