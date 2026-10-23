package you.v50to.eatwhat.cluster;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.control.ControlNode;
import java.util.*;

@RestController
@RequestMapping("/internal/cluster/v1")
@ConditionalOnProperty(name="app.cluster.enabled",havingValue="true")
public class ClusterReplayController {
    private final ControlNode authentication;private final ClusterNodeAgent agent;private final ClusterReplayService replay;
    public ClusterReplayController(ControlNode authentication,ClusterNodeAgent agent,ClusterReplayService replay){this.authentication=authentication;this.agent=agent;this.replay=replay;}
    private Map<String,Object> result(String key,Object value){var response=new LinkedHashMap<>(agent.probe());response.put(key,value);return response;}
    @PostMapping("/replay-seed") public Object seed(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody ClusterMembershipCoordinator.Plan plan){authentication.authenticate(token);return result("progress",replay.seed(plan));}
    @PostMapping("/replay-pull") public Object pull(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody ClusterReplayService.Target target){authentication.authenticate(token);return result("events",replay.pull(target));}
    @PostMapping("/replay-push") public Object push(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody ClusterReplayService.Push input){authentication.authenticate(token);return result("acks",replay.push(input));}
    @PostMapping("/replay-ack") public Object ack(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody ClusterReplayService.Settle input){authentication.authenticate(token);return result("progress",replay.settle(input));}
    @GetMapping("/replay-status") public Object status(@RequestHeader(value="X-Control-Node-Token",required=false) String token){authentication.authenticate(token);return result("progress",replay.status());}
    @PostMapping("/flush") public Object flush(@RequestHeader(value="X-Control-Node-Token",required=false) String token){authentication.authenticate(token);return result("progress",replay.flush());}
    @PostMapping("/reseed-consent") public Object reseed(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody ClusterReplayService.Target target){authentication.authenticate(token);return result("progress",replay.reseed(target.targetSite()));}
}
