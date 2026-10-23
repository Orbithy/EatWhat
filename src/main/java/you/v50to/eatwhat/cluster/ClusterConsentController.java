package you.v50to.eatwhat.cluster;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.account.ConsentStore;
import java.util.*;

/** Only the authenticated account can extend its recipient consent; no admin/user-id override. */
@RestController @RequestMapping("/auth/consent/recipients")
@ConditionalOnProperty(name={"app.cluster.enabled","app.cluster.coordinator","app.account.enabled"},havingValue="true")
public class ClusterConsentController {
    private final ConsentStore accounts;private final ClusterStore membership;
    public ClusterConsentController(ConsentStore accounts,ClusterStore membership){this.accounts=accounts;this.membership=membership;}
    @SaCheckLogin @GetMapping public Object recipients(){long id=StpUtil.getLoginIdAsLong();var state=accounts.get(id);return membership.active().stream().filter(m->m.mode().equals("EXTERNAL")).map(m->Map.of("member",m,"authorized",state!=null&&accounts.recipientAllowed(state,m.siteId()))).toList();}
    public record Grant(@NotBlank @Size(max=128) String policyVersion){}
    @SaCheckLogin @PostMapping("/{site}") public Object grant(@PathVariable String site,@Valid @RequestBody Grant input){var state=accounts.grantRecipient(StpUtil.getLoginIdAsLong(),site,input.policyVersion());return Map.of("siteId",site,"epoch",state.epoch(),"authorized",true,"catchup","PENDING");}
}
