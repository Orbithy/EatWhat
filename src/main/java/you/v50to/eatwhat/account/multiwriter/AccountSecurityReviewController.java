package you.v50to.eatwhat.account.multiwriter;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.vo.Result;
import java.util.UUID;

/** Private review metadata only; passwords, contact claims and account payloads never enter responses. */
@RestController
@ConditionalOnProperty(name="app.account.multiwriter.enabled",havingValue="true")
@RequestMapping("/auth/security/account-reviews")
@SaCheckRole("admin")
public class AccountSecurityReviewController {
    private final MultiwriterAccounts accounts;private final AccountPasswordRecovery recovery;
    public AccountSecurityReviewController(MultiwriterAccounts accounts,AccountPasswordRecovery recovery){this.accounts=accounts;this.recovery=recovery;}
    @GetMapping public Result<?> list(HttpServletResponse response){response.setHeader("Cache-Control","no-store");return Result.ok(accounts.reviews());}
    public record Decision(@NotNull Boolean approved) { }
    public record PasswordReset(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min=8,max=128) String newPassword){@Override public String toString(){return "AdministratorPasswordReset[redacted]";}}
    @PostMapping("/password-conflicts/{userId}/reset") public Result<?> reset(@PathVariable long userId,@Valid @RequestBody PasswordReset body,HttpServletResponse response){response.setHeader("Cache-Control","no-store");recovery.resetByAdministrator(StpUtil.getLoginIdAsLong(),userId,body.newPassword());return Result.ok();}
    @PostMapping("/{eventId}/decision") public Result<?> decide(@PathVariable UUID eventId,@Valid @RequestBody Decision decision,HttpServletResponse response){response.setHeader("Cache-Control","no-store");accounts.decideReview(StpUtil.getLoginIdAsLong(),eventId,decision.approved());return Result.ok();}
}
