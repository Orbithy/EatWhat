package you.v50to.eatwhat.control;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Authenticated read-only inspection, including after approval invalidation. */
@RestController
@RequestMapping("/admin/control/v1/controller-jobs")
@ConditionalOnProperty(name={"app.control.enabled","app.control.controller-bootstrap.enabled"},havingValue="true")
public class ControlBootstrapInspectionController {
    private final ControlController authorization;private final ControlBootstrapInspection inspection;
    public ControlBootstrapInspectionController(ControlController authorization,ControlBootstrapInspection inspection){this.authorization=authorization;this.inspection=inspection;}
    @GetMapping("/{id}/inspection") public Object get(@PathVariable UUID id,@RequestParam(defaultValue="false") boolean current){authorization.admin();return inspection.inspect(id,current);}
}
