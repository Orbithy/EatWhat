package you.v50to.eatwhat.control;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Internal authenticated execution of an existing sealed/claimed operation.
 * This cannot create a bootstrap plan, approve one or select file contents. */
@RestController
@RequestMapping("/internal/control/v1/controller-profiles")
@ConditionalOnProperty(name={"app.control.enabled","app.control.node-enabled","app.control.controller-bootstrap.enabled"},havingValue="true")
public final class ControlBootstrapProfileNodeController {
    private final ControlNode node;private final ControlBootstrapProfileReceiver receiver;
    public ControlBootstrapProfileNodeController(ControlNode node,ControlBootstrapProfileReceiver receiver){this.node=node;this.receiver=receiver;}
    record Frame(UUID id,ControlBootstrapProfileState.Role role,String binding){}
    static Frame frame(Map<String,Object> body){
        try{
            if(!body.keySet().equals(Set.of("id","role","binding"))||!(body.get("id") instanceof String id)||!(body.get("role") instanceof String role)
                    ||!(body.get("binding") instanceof String binding)||!binding.matches("[A-Za-z0-9+/]{43}="))throw new IllegalArgumentException();
            UUID uuid=UUID.fromString(id);if(!uuid.toString().equals(id)||uuid.equals(new UUID(0,0)))throw new IllegalArgumentException();
            return new Frame(uuid,ControlBootstrapProfileState.Role.valueOf(role),binding);
        }catch(Exception failure){throw new ControlFailure(400,"BOOTSTRAP_PROFILE_REQUEST_INVALID");}
    }
    @PostMapping("/apply") public Object apply(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody Map<String,Object> body){
        node.authenticate(token);var f=frame(body);try{return receiver.apply(f.id(),f.role(),f.binding());}
        catch(you.v50to.eatwhat.cluster.ClusterFailure failure){throw new ControlFailure(failure.status(),failure.getMessage());}
    }
    @PostMapping("/status") public Object status(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody Map<String,Object> body){
        node.authenticate(token);var f=frame(body);try{return receiver.observe(f.id(),f.role(),f.binding());}
        catch(you.v50to.eatwhat.cluster.ClusterFailure failure){throw new ControlFailure(failure.status(),failure.getMessage());}
    }
    @PostMapping("/services-status") public Object servicesStatus(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody Map<String,Object> body){
        node.authenticate(token);var f=frame(body);try{return receiver.servicesStatus(f.id(),f.role(),f.binding());}
        catch(you.v50to.eatwhat.cluster.ClusterFailure failure){throw new ControlFailure(failure.status(),failure.getMessage());}
    }
}
