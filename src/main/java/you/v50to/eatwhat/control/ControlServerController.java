package you.v50to.eatwhat.control;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.cluster.ClusterControlHandoff;
import java.util.*;
import java.util.function.Supplier;

@RestController
@RequestMapping("/admin/control/v1")
@ConditionalOnProperty(name="app.control.enabled",havingValue="true")
public class ControlServerController {
    private final ControlController auth;private final ControlServers servers;private final ControlInstallExecutor installer;private final ObjectProvider<ClusterControlHandoff> handoff;
    public ControlServerController(ControlController auth,ControlServers servers,ControlInstallExecutor installer,ObjectProvider<ClusterControlHandoff> handoff){this.auth=auth;this.servers=servers;this.installer=installer;this.handoff=handoff;}
    public record ServerInput(@NotNull UUID requestId,Long expectedRevision,@NotBlank @Size(max=64) String name,@NotBlank @Size(max=253) String host,@Min(1) @Max(65535) int port,@NotBlank @Size(max=128) String username,@NotBlank String location,@NotBlank String authentication,@Size(max=16384) String credential,@NotBlank @Size(max=8192) String hostKey,@NotBlank @Size(max=128) String confirmedFingerprint,@Pattern(regexp="NONE|SUDO") String privilege,@Size(max=64) @Pattern(regexp="[A-Za-z][A-Za-z0-9_-]{0,63}") String credentialId){
        public ServerInput(UUID requestId,Long expectedRevision,String name,String host,int port,String username,String location,String authentication,String credential,String hostKey,String confirmedFingerprint){this(requestId,expectedRevision,name,host,port,username,location,authentication,credential,hostKey,confirmedFingerprint,null,null);}
        public ServerInput(UUID requestId,Long expectedRevision,String name,String host,int port,String username,String location,String authentication,String credential,String hostKey,String confirmedFingerprint,String privilege){this(requestId,expectedRevision,name,host,port,username,location,authentication,credential,hostKey,confirmedFingerprint,privilege,null);}
        @Override public String toString(){return "ServerInput[redacted]";}
    }
    public record HostInput(@NotBlank @Size(max=253) String host,@Min(1) @Max(65535) int port){}
    public record Revision(@Min(1) long expectedRevision){}
    public record InstallInput(@Min(1) long expectedRevision,@NotBlank @Size(max=64) String releaseId,@Min(1024) @Max(65535) int httpPort,@NotBlank @Size(max=512) String appBaseUrl,boolean installDocker,@Pattern(regexp="CAMPUS|EXTERNAL") String siteMode,@Min(0) @Max(1023) Integer allocationSlot){
        public InstallInput(long expectedRevision,String releaseId,int httpPort,String appBaseUrl,boolean installDocker){this(expectedRevision,releaseId,httpPort,appBaseUrl,installDocker,null,null);}
    }
    public record RecoveryInput(@Min(1) long expectedRevision,@NotNull UUID sourceJobId){}
    private <T>T operation(Supplier<T> action){var transfer=handoff.getIfAvailable();return transfer==null?action.get():transfer.operation(action);}
    @GetMapping("/servers") public Object list(){auth.admin();String reason=servers.reason();var result=new LinkedHashMap<String,Object>();result.put("servers",servers.list());result.put("canManage",reason.isEmpty());result.put("reason",reason);result.put("installationEnabled",installer.enabled());result.put("clusterEnrollmentEnabled",installer.clusterEnrollmentEnabled());result.put("remoteMemberEnrollmentSupported",true);result.put("releases",installer.releases());result.put("installationModes",List.of("CAMPUS","EXTERNAL"));result.put("installationIdentityRequired",installer.installationIdentityRequired());result.put("sshPrivileges",List.of("NONE","SUDO"));result.put("sshCredentials",servers.credentials());return result;}
    @PostMapping("/servers/host-key") public Object hostKey(@Valid @RequestBody HostInput input){auth.admin();return operation(()->Map.of("keys",servers.hostKeys(input.host(),input.port())));}
    @PostMapping("/servers") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public Object add(@Valid @RequestBody ServerInput input){String actor=auth.admin();return operation(()->servers.save(actor,input.requestId(),input,true));}
    @PutMapping("/servers/{id}") public Object edit(@PathVariable UUID id,@Valid @RequestBody ServerInput input){String actor=auth.admin();return operation(()->servers.save(actor,id,input,false));}
    @DeleteMapping("/servers/{id}") public Object delete(@PathVariable UUID id,@RequestParam long expectedRevision){String actor=auth.admin();return operation(()->{servers.delete(actor,id,expectedRevision);return Map.of("removed",true,"remoteDataDeleted",false);});}
    @PostMapping("/servers/{id}/test") public Object test(@PathVariable UUID id,@Valid @RequestBody Revision input){String actor=auth.admin();return operation(()->servers.test(actor,id,input.expectedRevision()));}
    @PostMapping("/servers/{id}/installation-plans") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public Object plan(@PathVariable UUID id,@Valid @RequestBody InstallInput input){String actor=auth.admin();return operation(()->installer.plan(actor,id,input,"INSTALL"));}
    @PostMapping("/servers/{id}/update-plans") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public Object updatePlan(@PathVariable UUID id,@Valid @RequestBody InstallInput input){String actor=auth.admin();return operation(()->installer.plan(actor,id,input,"UPDATE"));}
    @PostMapping("/servers/{id}/recovery-plans") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public Object recoveryPlan(@PathVariable UUID id,@Valid @RequestBody RecoveryInput input){String actor=auth.admin();return operation(()->installer.recoveryPlan(actor,id,input));}
    @GetMapping("/server-plans/{id}") public Object plan(@PathVariable UUID id){auth.admin();return installer.getPlan(id);}
    @PostMapping("/server-plans/{id}/execute") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) public Object execute(@PathVariable UUID id){String actor=auth.admin();return operation(()->installer.execute(actor,id));}
    @GetMapping("/server-jobs/{id}") public Object job(@PathVariable UUID id){auth.admin();return installer.job(id);}
    @PostMapping("/server-jobs/{id}/reconcile") public Object reconcile(@PathVariable UUID id){String actor=auth.admin();return operation(()->installer.reconcile(actor,id));}
}
