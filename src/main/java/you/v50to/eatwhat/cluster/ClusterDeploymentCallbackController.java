package you.v50to.eatwhat.cluster;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Read-only private callback. The scoped capability cannot invoke ordinary node operations. */
@RestController
@RequestMapping("/internal/deployment/v1/fences")
@ConditionalOnProperty(name={"app.cluster.enabled","app.cluster.coordinator","app.cluster.control-lease.enabled"},havingValue="true")
public final class ClusterDeploymentCallbackController {
    private final ClusterDeploymentFence fence;
    public ClusterDeploymentCallbackController(ClusterDeploymentFence fence){this.fence=fence;}
    @GetMapping("/{id}") public Object proof(@PathVariable UUID id,@RequestParam String manifest,@RequestHeader(value="X-Deployment-Token",required=false)String token){return fence.callback(id,manifest,token);}
}
