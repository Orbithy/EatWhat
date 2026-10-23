package you.v50to.eatwhat.control;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** No arbitrary membership request or changed identity enters through HTTP. */
@RestController
@RequestMapping("/internal/control/v1/controller-profiles")
@ConditionalOnProperty(name={"app.control.enabled","app.control.node-enabled","app.control.controller-bootstrap.enabled"},havingValue="true")
public final class ControlBootstrapMembershipNodeController {
    private final ControlNode node;private final ControlBootstrapMembership membership;
    public ControlBootstrapMembershipNodeController(ControlNode node,ControlBootstrapMembership membership){this.node=node;this.membership=membership;}
    private Object call(String token,Map<String,Object> body,boolean execute){
        node.authenticate(token);var frame=ControlBootstrapProfileNodeController.frame(body);
        try{return membership.localRegistration(frame.id(),frame.role(),frame.binding(),execute);}
        catch(you.v50to.eatwhat.cluster.ClusterFailure failure){throw new ControlFailure(failure.status(),failure.getMessage());}
    }
    @PostMapping("/register") public Object register(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody Map<String,Object> body){return call(token,body,true);}
    @PostMapping("/register-status") public Object status(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody Map<String,Object> body){return call(token,body,false);}
    private Object join(String token,Map<String,Object> body,ControlBootstrapMembership.JoinAction action){
        node.authenticate(token);var frame=ControlBootstrapProfileNodeController.frame(body);
        try{return membership.localJoin(frame.id(),frame.role(),frame.binding(),action);}
        catch(you.v50to.eatwhat.cluster.ClusterFailure failure){throw new ControlFailure(failure.status(),failure.getMessage());}
    }
    @PostMapping("/join") public Object join(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody Map<String,Object> body){return join(token,body,ControlBootstrapMembership.JoinAction.BEGIN);}
    @PostMapping("/join-status") public Object joinStatus(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody Map<String,Object> body){return join(token,body,ControlBootstrapMembership.JoinAction.STATUS);}
    @PostMapping("/join-reconcile") public Object reconcile(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody Map<String,Object> body){return join(token,body,ControlBootstrapMembership.JoinAction.RECONCILE);}
}
