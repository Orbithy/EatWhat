package you.v50to.eatwhat.control;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/admin/control/v1/tasks")
@ConditionalOnProperty(name="app.control.enabled",havingValue="true")
public class ControlTaskController {
    private final ControlController authorization;private final ControlTaskHistory history;
    public ControlTaskController(ControlController authorization,ControlAudit audit,ControlInstallExecutor installer,ControlClusterEnrollment enrollment,DeploymentExecutor deployment,ControlBootstrapJournal bootstrap){this.authorization=authorization;this.history=new ControlTaskHistory(audit.directory(),(kind,id)->switch(kind){case "SERVER"->installer.job(id);case "ENROLLMENT"->enrollment.job(id);case "DEPLOYMENT"->deployment.get(id);case "CONTROLLER_INSTALL"->bootstrap.job(id);default->throw new ControlFailure(400,"INVALID_TASK_TYPE");});}
    @GetMapping public Object list(@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String type,@RequestParam(required=false) String state,@RequestParam(required=false) UUID serverId,@RequestParam(required=false) String cursor){authorization.admin();return history.list(limit,type,state,serverId,cursor);}
}
