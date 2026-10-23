package you.v50to.eatwhat.control;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import you.v50to.eatwhat.cluster.ClusterControlHandoff;
import java.util.*;
import java.util.function.Supplier;

@RestController
@RequestMapping("/admin/control/v1/controller-plans")
@ConditionalOnProperty(name={"app.control.enabled","app.control.controller-bootstrap.enabled"},havingValue="true")
public class ControlBootstrapPlanController {
    private final ControlController authorization;private final ControlBootstrapPlans plans;private final ControlBootstrapJournal journal;
    private final ObjectProvider<ClusterControlHandoff> handoff;
    public ControlBootstrapPlanController(ControlController authorization,ControlBootstrapPlans plans,ControlBootstrapJournal journal,ObjectProvider<ClusterControlHandoff> handoff){
        this.authorization=authorization;this.plans=plans;this.journal=journal;this.handoff=handoff;
    }
    public record Approval(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String requestHash,@NotNull Set<String> permissions){}
    private <T>T operation(Supplier<T> action){var transfer=handoff.getIfAvailable();return transfer==null?action.get():transfer.operation(action);}
    @GetMapping public Object capabilities(){authorization.admin();return plans.capabilities();}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Object preview(@RequestBody ControlBootstrapPlans.Request input){
        String actor=authorization.admin();return operation(()->plans.preview(actor,input));
    }
    @GetMapping("/{id}") public Object get(@PathVariable UUID id){authorization.admin();return journal.plan(id);}
    @PostMapping("/{id}/execute") @ResponseStatus(HttpStatus.ACCEPTED) public Object execute(@PathVariable UUID id,@Valid @RequestBody Approval input){
        String actor=authorization.admin();return operation(()->{
            if(!input.requestHash().equals(journal.plan(id).get("requestHash")))throw new ControlFailure(409,"BOOTSTRAP_PLAN_CHANGED");
            return plans.accept(actor,id,input.permissions());
        });
    }
}
