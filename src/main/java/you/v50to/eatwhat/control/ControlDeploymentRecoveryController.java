package you.v50to.eatwhat.control;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/admin/control/v1")
@ConditionalOnProperty(name="app.control.enabled",havingValue="true")
public class ControlDeploymentRecoveryController {
    private final ControlController auth;private final ControlDeploymentRecovery recovery;
    public ControlDeploymentRecoveryController(ControlController auth,ControlDeploymentRecovery recovery){this.auth=auth;this.recovery=recovery;}
    public record Preview(@NotNull UUID requestId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String siteId){}
    public record Confirm(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String restoreSha256){}
    @PostMapping("/deployments/{id}/recovery-plans") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Object create(@PathVariable UUID id,@Valid @RequestBody Preview request){return recovery.create(auth.admin(),id,request.requestId(),request.siteId());}
    @GetMapping("/deployment-recovery-plans/{id}") public Object get(@PathVariable UUID id){auth.admin();return recovery.get(id);}
    @PostMapping("/deployment-recovery-plans/{id}/execute") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Object execute(@PathVariable UUID id,@Valid @RequestBody Confirm request){return recovery.execute(auth.admin(),id,request.restoreSha256());}
    @PostMapping("/deployment-recovery-plans/{id}/reconcile") public Object reconcile(@PathVariable UUID id){return recovery.reconcile(auth.admin(),id);}
}
