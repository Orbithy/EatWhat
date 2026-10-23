package you.v50to.eatwhat.control;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Authenticated read-only task details; writes use the reviewed plan API. */
@RestController
@RequestMapping("/admin/control/v1/controller-jobs")
@ConditionalOnProperty(name="app.control.enabled",havingValue="true")
public class ControlBootstrapJobController {
    private final ControlController authorization;private final ControlBootstrapJournal journal;
    private final ObjectProvider<ControlBootstrapInspection> inspection;
    public ControlBootstrapJobController(ControlController authorization,ControlBootstrapJournal journal,ObjectProvider<ControlBootstrapInspection> inspection){this.authorization=authorization;this.journal=journal;this.inspection=inspection;}
    @GetMapping("/{id}") public Object get(@PathVariable UUID id){authorization.admin();var result=new LinkedHashMap<>(journal.job(id));
        boolean supported=inspection.getIfAvailable()!=null;result.put("readOnlyInspectionSupported",supported);result.put("currentInspectionSupported",supported);return result;}
}
