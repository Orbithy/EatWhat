package you.v50to.eatwhat.control;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import you.v50to.eatwhat.account.AccountAuthService;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.data.enums.BizCode;

@RestController
@RequestMapping("/admin/control/v1")
@ConditionalOnProperty(name="app.control.enabled",havingValue="true")
public class ControlController {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.control.independent.IndependentControlAccounts independentControlAccounts;
    private final ControlService control;private final ControlRuns runs;private final ObjectProvider<AccountAuthService> accounts;
    public ControlController(ControlService control,ControlRuns runs,ObjectProvider<AccountAuthService> accounts){this.control=control;this.runs=runs;this.accounts=accounts;}
    @org.springframework.beans.factory.annotation.Autowired private ObjectProvider<ControlAccountSecurity> credentialSecurity;
    public String admin(){
        if(independentControlAccounts!=null)return independentControlAccounts.requireAdmin();
        StpUtil.checkRole("admin");
        var account=accounts.getIfAvailable();
        if(account!=null && account.external()) {
            try{account.assertExternalSession(StpUtil.getLoginIdAsLong());}
            catch(BizException e){throw new ControlFailure(e.getBizCode()==BizCode.AUTH_UNAVAILABLE?503:403,"EXTERNAL_SESSION_REQUIRED");}
            catch(RuntimeException e){throw new ControlFailure(503,"AUTHORIZATION_UNAVAILABLE");}
        }
        if(credentialSecurity!=null){var security=credentialSecurity.getIfAvailable();if(security!=null)security.requireAdmin(StpUtil.getLoginIdAsLong());}
        return StpUtil.getLoginIdAsString();
    }
    @GetMapping("/capabilities") public Object capabilities(){admin();return Map.of("apiVersion","1","environmentKind",control.environmentKind(),"deploymentExecutionEnabled",control.deploymentEnabled(),"deploymentRecoveryEnabled",control.deploymentRecoveryEnabled(),"remoteFaultMatrixAutomated",false,"testSuites",runs.suites(),"configuredChannels",control.channelCatalog());}
    @GetMapping("/sites") public Object sites(){admin();return control.sites();}
    public record Fault(@NotBlank String channel,@NotBlank String action,@Min(1) @Max(300) int seconds){}
    @PostMapping("/sites/{site}/faults") public Object fault(@PathVariable String site,@Valid @RequestBody Fault input){return control.fault(admin(),site,input.channel(),input.action(),input.seconds());}
    @GetMapping("/test-suites") public Object suites(){admin();return runs.suites();}
    public record Run(@NotBlank String suite){}
    @PostMapping("/test-runs") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Object run(@Valid @RequestBody Run input){return runs.start(admin(),input.suite());}
    @GetMapping("/test-runs") public Object runs(){admin();return runs.list();}
    @GetMapping("/test-runs/{id}") public Object run(@PathVariable UUID id){admin();return runs.get(id);}
    @PostMapping("/test-runs/{id}/cancel") public Object cancel(@PathVariable UUID id){return runs.cancel(admin(),id);}
    @GetMapping("/releases") public Object releases(){admin();return control.releases();}
    public record Plan(@NotBlank String releaseId,@NotNull List<String> sites){}
    @PostMapping("/deployment-plans") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Object plan(@Valid @RequestBody Plan input){return control.plan(admin(),input.releaseId(),input.sites());}
    @GetMapping("/deployment-plans/{id}") public Object plan(@PathVariable UUID id){admin();return control.loadPlan(id);}
    @PostMapping("/deployment-plans/{id}/execute") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) public Object execute(@PathVariable UUID id){return control.execute(admin(),id);}
    @GetMapping("/deployments/{id}") public Object deployment(@PathVariable UUID id){admin();return control.deployment(id);}
    @PostMapping("/deployments/{id}/reconcile") public Object reconcile(@PathVariable UUID id){return control.reconcileDeployment(admin(),id);}
    @GetMapping("/audit") public Object audit(@RequestParam(defaultValue="50") int limit){admin();return control.audit(limit);}
}
