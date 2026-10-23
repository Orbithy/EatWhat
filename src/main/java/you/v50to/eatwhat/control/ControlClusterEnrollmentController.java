package you.v50to.eatwhat.control;

import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import you.v50to.eatwhat.cluster.ClusterControlHandoff;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
@RequestMapping("/admin/control/v1")
@ConditionalOnProperty(name="app.control.enabled",havingValue="true")
public class ControlClusterEnrollmentController {
    private final ControlController auth;private final ControlClusterEnrollment enrollment;private final ObjectProvider<ClusterControlHandoff> handoff;
    public ControlClusterEnrollmentController(ControlController auth,ControlClusterEnrollment enrollment,ObjectProvider<ClusterControlHandoff> handoff){this.auth=auth;this.enrollment=enrollment;this.handoff=handoff;}
    private <T>T operation(Supplier<T> work){var h=handoff.getIfAvailable();return h==null?work.get():h.operation(work);}
    @PostMapping("/servers/{id}/cluster-plans") @ResponseStatus(HttpStatus.CREATED) public Object create(@PathVariable UUID id,@Valid @RequestBody ControlServerController.Revision input){String actor=auth.admin();return operation(()->enrollment.create(actor,id,input.expectedRevision()));}
    @PostMapping("/server-cluster-jobs/{id}/recovery-plans") @ResponseStatus(HttpStatus.CREATED) public Object recovery(@PathVariable UUID id){String actor=auth.admin();return operation(()->enrollment.createRecovery(actor,id));}
    @GetMapping("/server-cluster-plans/{id}") public Object plan(@PathVariable UUID id){auth.admin();return enrollment.plan(id);}
    @PostMapping("/server-cluster-plans/{id}/execute") @ResponseStatus(HttpStatus.ACCEPTED) public Object execute(@PathVariable UUID id){String actor=auth.admin();return operation(()->enrollment.execute(actor,id));}
    @GetMapping("/server-cluster-jobs/{id}") public Object job(@PathVariable UUID id){auth.admin();return enrollment.job(id);}
    @PostMapping("/server-cluster-jobs/{id}/reconcile") public Object reconcile(@PathVariable UUID id){String actor=auth.admin();return operation(()->enrollment.reconcile(actor,id));}
}
