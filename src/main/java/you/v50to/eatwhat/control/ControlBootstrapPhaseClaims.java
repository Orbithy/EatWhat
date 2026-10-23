package you.v50to.eatwhat.control;

/** Bridges real admission and durable claims; performs no remote dispatch.
 * Concrete native drivers and the post-JOIN successor binding are still needed.
 */
public final class ControlBootstrapPhaseClaims {
    private final ControlBootstrapJournal journal;private final ControlBootstrapAdmission admission;
    public ControlBootstrapPhaseClaims(ControlBootstrapJournal journal,ControlBootstrapAdmission admission){this.journal=journal;this.admission=admission;}
    private void bound(ControlBootstrapAdmission.Frozen frozen){
        admission.requireBound(frozen);journal.requireAdmission(frozen.spec().id(),frozen.spec(),frozen.actor(),frozen.review(),frozen.binding());
    }
    public ControlBootstrapJournal.Dispatch claim(ControlBootstrapAdmission.Frozen frozen,ControlBootstrapJournal.Phase phase){
        bound(frozen);
        if(phase.ordinal()>ControlBootstrapJournal.Phase.BUSINESS_INSTALL.ordinal())throw new ControlFailure(409,"BOOTSTRAP_MEMBERSHIP_DRIVER_REQUIRED");
        return journal.claimFenced(frozen.spec().id(),phase,intent->{
            bound(frozen);
            if(phase==ControlBootstrapJournal.Phase.SOURCE_RUNTIME)admission.beforeInitialIntent(frozen,()->{intent.run();return null;});
            else admission.beforeMembershipIntent(frozen,()->{intent.run();return null;});
        });
    }
    public ControlBootstrapJournal.DependencyDispatch dependency(ControlBootstrapAdmission.Frozen frozen,ControlBootstrapJournal.DependencyOperation operation,
                                                                  String nativeBinding,String agentSha256){
        bound(frozen);
        return journal.claimDependencyOperationFenced(frozen.spec().id(),operation,nativeBinding,agentSha256,
                intent->{bound(frozen);admission.beforeMembershipIntent(frozen,()->{intent.run();return null;});});
    }
    void controllerIntent(ControlBootstrapAdmission.Frozen frozen,Runnable durableIntent){
        bound(frozen);
        journal.controllerIntent(frozen.spec().id(),intent->{
            bound(frozen);admission.beforeMembershipIntent(frozen,()->{intent.run();return null;});
        },durableIntent);
    }
    void controllerArtifactDrift(ControlBootstrapAdmission.Frozen frozen){bound(frozen);journal.controllerArtifactDrift(frozen.spec().id());}
    void businessIntent(ControlBootstrapAdmission.Frozen frozen,Runnable durableIntent){
        bound(frozen);
        journal.businessIntent(frozen.spec().id(),intent->{
            bound(frozen);admission.beforeMembershipIntent(frozen,()->{intent.run();return null;});
        },durableIntent);
    }
    ControlBootstrapJournal.Dispatch memberConfiguration(ControlBootstrapAdmission.Frozen frozen){
        bound(frozen);return journal.claimFenced(frozen.spec().id(),ControlBootstrapJournal.Phase.MEMBER_CONFIGURE,
                intent->{bound(frozen);admission.beforeMembershipIntent(frozen,()->{intent.run();return null;});});
    }
    void memberConfigurationIntent(ControlBootstrapAdmission.Frozen frozen,Runnable durableIntent){
        bound(frozen);journal.memberConfigurationIntent(frozen.spec().id(),intent->{
            bound(frozen);admission.beforeMembershipIntent(frozen,()->{intent.run();return null;});
        },durableIntent);
    }
    /** Only a child driver, after authenticating its retained native failure,
     * may finish the composite phase as failed. Missing/unknown observations
     * are deliberately excluded. No new dispatch permission is obtained. */
    java.util.Map<String,Object> memberConfigurationFailed(ControlBootstrapAdmission.Frozen f,String component,java.util.Map<String,Object> receipt){
        bound(f);
        if(!java.util.Set.of("RELAY","AUTH","INGRESS","RUNTIME").contains(component)||!"FAILED".equals(receipt.get("state")))
            throw new ControlFailure(409,"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        var job=journal.job(f.spec().id());if("FAILED".equals(job.get("state")))return job;
        var permit=journal.claimFenced(f.spec().id(),ControlBootstrapJournal.Phase.MEMBER_CONFIGURE,intent->{throw new ControlFailure(409,"BOOTSTRAP_MEMBER_CONFIGURATION_CLAIM_REQUIRED");});
        return journal.observe(f.spec().id(),ControlBootstrapJournal.Phase.MEMBER_CONFIGURE,permit.claimToken(),java.util.Map.of(
                "id",permit.id().toString(),"protocol",ControlBootstrapJournal.Phase.MEMBER_CONFIGURE.protocol,"state","FAILED",
                "component",component,"nativeReceiptSha256",ControlBootstrapNativeJson.digest(receipt)));
    }
}
