package you.v50to.eatwhat.account;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.data.vo.ConsentPolicyVO;

/** Additive account endpoints; an opaque grant proof is never accepted by ordinary business APIs. */
@RestController
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true")
@RequestMapping("/auth/consent")
public class AccountConsentController {
    private final AccountAuthService accounts;

    public AccountConsentController(AccountAuthService accounts) { this.accounts = accounts; }

    @GetMapping("/policy")
    public Result<ConsentPolicyVO> policy(HttpServletResponse response) { noStore(response); return accounts.policy(); }

    @SaCheckLogin
    @GetMapping
    public Result<?> status(HttpServletResponse response) { noStore(response); return accounts.status(); }

    @PostMapping("/grant")
    public Result<?> grant(@Valid @RequestBody GrantRequest request, HttpServletResponse response) {
        noStore(response);
        return accounts.grant(request.proof(), request.policyVersion());
    }

    @SaCheckLogin
    @PostMapping("/withdraw")
    public Result<?> withdraw(HttpServletResponse response) { noStore(response); return accounts.withdraw(); }

    @PostMapping("/withdraw-with-password")
    public Result<?> withdrawWithPassword(@Valid @RequestBody WithdrawalRequest request, HttpServletResponse response) {
        noStore(response);
        return accounts.withdrawWithPassword(request.username(), request.password());
    }

    public record GrantRequest(@Size(max = 512, message = "同意凭证过长") String proof,
                               @NotBlank(message = "同意政策版本不能为空") @Size(max = 128, message = "同意政策版本过长") String policyVersion) {
        @Override public String toString() { return "GrantRequest[redacted]"; }
    }

    public record WithdrawalRequest(@NotBlank(message = "用户名不能为空") @Size(min = 3, max = 64) String username,
                                    @NotBlank(message = "密码不能为空") String password) {
        @Override public String toString() { return "WithdrawalRequest[redacted]"; }
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }
}
