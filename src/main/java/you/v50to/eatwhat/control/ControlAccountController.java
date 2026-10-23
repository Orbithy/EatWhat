package you.v50to.eatwhat.control;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.cluster.ClusterControlHandoff;

@RestController
@RequestMapping("/admin/control/v1/account")
@ConditionalOnProperty(name="app.control.enabled",havingValue="true")
public class ControlAccountController {
    private final ControlController authorization;private final ControlAccountService accounts;private final ObjectProvider<ClusterControlHandoff> handoff;
    public ControlAccountController(ControlController authorization,ControlAccountService accounts,ObjectProvider<ClusterControlHandoff> handoff){this.authorization=authorization;this.accounts=accounts;this.handoff=handoff;}
    public record Credentials(@NotBlank @Size(max=256) String currentPassword,@Size(max=32) String newUsername,@Size(max=128) String newPassword){@Override public String toString(){return "Credentials[redacted]";}}
    @GetMapping public Object profile(){return accounts.profile(Long.parseLong(authorization.admin()));}
    @PostMapping("/credentials") public Object change(@Valid @RequestBody Credentials request){long id=Long.parseLong(authorization.admin());var transfer=handoff.getIfAvailable();java.util.function.Supplier<Object> change=()->accounts.change(id,request.currentPassword(),request.newUsername(),request.newPassword());return transfer==null?change.get():transfer.operation(change);}
}
