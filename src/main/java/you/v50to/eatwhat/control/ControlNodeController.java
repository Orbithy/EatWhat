package you.v50to.eatwhat.control;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/internal/control/v1")
@ConditionalOnProperty(name={"app.control.enabled","app.control.node-enabled"},havingValue="true")
public class ControlNodeController {
    private final ControlNode node;
    public ControlNodeController(ControlNode node){this.node=node;}
    @GetMapping("/snapshot") public Object snapshot(@RequestHeader(value="X-Control-Node-Token",required=false) String token){node.authenticate(token);return node.snapshot();}
    public record Fault(@NotNull UUID requestId,@NotBlank String channel,@NotBlank String action,@Min(1) @Max(300) int seconds){}
    @PostMapping("/faults") public Object fault(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@Valid @RequestBody Fault input){node.authenticate(token);return node.fault(input.channel(),input.action(),input.seconds(),input.requestId());}
}
