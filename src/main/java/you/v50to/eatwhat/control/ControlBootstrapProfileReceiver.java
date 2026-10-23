package you.v50to.eatwhat.control;

import org.springframework.beans.factory.ObjectProvider;
import you.v50to.eatwhat.cluster.*;
import java.nio.file.*;
import java.util.*;

/** Closed local receiver. A request identifies a sealed/claimed task, never
 * supplies URLs, private paths, file contents or a replacement approval.
 * File and catalog effects run in the actual holder/admin transaction.
 * Status never invokes that transaction or repairs a partial publication. */
public final class ControlBootstrapProfileReceiver {
    private final ControlServerStore store;private final ControlBootstrapProfileState state;private final ControlBootstrapAdmission admission;
    private final ClusterControlLease lease;private final ClusterProperties cluster;private final ObjectProvider<ClusterProfiles> profiles;
    private final Path control;
    public ControlBootstrapProfileReceiver(ControlServerStore store,ControlBootstrapProfileState state,ControlBootstrapAdmission admission,ClusterControlLease lease,
            ClusterProperties cluster,ObjectProvider<ClusterProfiles> profiles,Path control){this.store=store;this.state=state;this.admission=admission;this.lease=lease;this.cluster=cluster;this.profiles=profiles;this.control=control;}
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private ControlBootstrapProfileState.Accepted accepted(UUID id,ControlBootstrapProfileState.Role role,String binding){
        var a=state.original(id);require(a.binding().equals(binding),"BOOTSTRAP_PROFILE_ACCEPTANCE_CHANGED");state.requireClaimed(a,role);
        require(ControlBootstrapProfileState.controllerId(a,role).equals(lease.controllerId())&&ControlBootstrapProfileState.controllerName(a,role).equals(cluster.getControlLease().getControllerName()),"BOOTSTRAP_PROFILE_PROCESS_CHANGED");
        require(control.isAbsolute()&&control.normalize().equals(control)&&control.resolve("config/cluster-profiles.json").toString().equals(ControlBootstrapProfileState.plan(a,role).profilesFile())
                &&control.resolve("state").equals(store.directory()),"BOOTSTRAP_PROFILE_LOCAL_CONFIGURATION_CHANGED");
        try{require(you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot.sha(ControlMemberConfiguration.privateBytes(control.resolve("config/application.properties"),262144))
                .equals(ControlBootstrapProfileState.applicationHash(a,role)),"BOOTSTRAP_PROFILE_LOCAL_CONFIGURATION_CHANGED");}
        catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_PROFILE_LOCAL_CONFIGURATION_CHANGED");}
        return a;
    }
    private Map<String,Object> receipt(ControlBootstrapProfileState.Accepted a,ControlBootstrapProfileState.Role role,String result,String observed){
        return ControlBootstrapProfileState.sign(store,Map.ofEntries(Map.entry("protocol","eatwhat-controller-profile-publication-v1"),Map.entry("bootstrapId",a.frozen().spec().id().toString()),
                Map.entry("role",role.name()),Map.entry("binding",a.binding()),Map.entry("planBinding",ControlBootstrapProfileState.plan(a,role).binding()),Map.entry("controllerId",lease.controllerId().toString()),
                Map.entry("controllerName",ControlBootstrapProfileState.controllerName(a,role)),Map.entry("applicationSha256",ControlBootstrapProfileState.applicationHash(a,role)),Map.entry("state",result),
                Map.entry("observedBinding",observed),Map.entry("joinAuthorized",false)));
    }
    public Map<String,Object> observe(UUID id,ControlBootstrapProfileState.Role role,String binding){
        var a=accepted(id,role,binding);var catalog=profiles.getObject();var plan=ControlBootstrapProfileState.plan(a,role);String seen=catalog.publicationBinding();
        if(plan.afterBinding().equals(seen)){
            catalog.publishAndReload(plan,List.of(),null);
            if(role==ControlBootstrapProfileState.Role.TARGET)ControlBootstrapProfileServices.observe(a);
            return receipt(a,role,"RUNTIME_RELOADED",seen);
        }
        return receipt(a,role,"OUTCOME_UNKNOWN",seen);
    }
    public Map<String,Object> apply(UUID id,ControlBootstrapProfileState.Role role,String binding){
        var a=accepted(id,role,binding);var catalog=profiles.getObject();var plan=ControlBootstrapProfileState.plan(a,role);
        // A completed lost ACK is read-only even if the approving credentials
        // changed afterwards. It never grants another operation intent.
        if(plan.afterBinding().equals(catalog.publicationBinding()))return observe(id,role,binding);
        catalog.publishAndReload(plan,List.of(),effect->admission.applyProfile(a,role,()->{
            accepted(id,role,binding);state.requireEffect(a,role);effect.run();
            if(role==ControlBootstrapProfileState.Role.TARGET)ControlBootstrapProfileServices.publish(a);
            return null;
        }));
        // applyProfile uses its own committed transaction before this ACK.
        return receipt(a,role,"RUNTIME_RELOADED",catalog.publicationBinding());
    }
    /** Local-token signature lets the bounded native verifier authenticate
     * status without reading a hard-mounted shared state key itself. */
    public Map<String,Object> servicesStatus(UUID id,ControlBootstrapProfileState.Role role,String binding){
        require(role==ControlBootstrapProfileState.Role.TARGET,"BOOTSTRAP_PROFILE_REQUEST_INVALID");
        var a=accepted(id,role,binding);return ControlBootstrapProfileServices.runtime(a,observe(id,role,binding));
    }
}
