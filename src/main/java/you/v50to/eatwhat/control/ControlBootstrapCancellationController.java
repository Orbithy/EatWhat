package you.v50to.eatwhat.control;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Only before any durable dispatch. It never resets or deletes a remote job. */
@RestController
@RequestMapping("/admin/control/v1/controller-jobs")
@ConditionalOnProperty(name={"app.control.enabled","app.control.controller-bootstrap.enabled"},havingValue="true")
public class ControlBootstrapCancellationController {
    private final ControlController authorization;private final ControlBootstrapPlans plans;
    public ControlBootstrapCancellationController(ControlController authorization,ControlBootstrapPlans plans){this.authorization=authorization;this.plans=plans;}
    public record Cancellation(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String requestHash){}
    @PostMapping("/{id}/cancel") public Object cancel(@PathVariable UUID id,@Valid @RequestBody Cancellation input){
        return plans.cancelUnstarted(authorization.admin(),id,input.requestHash());
    }
}
