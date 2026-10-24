package you.v50to.eatwhat.account.multiwriter;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.TencentCaptchaService;
import you.v50to.eatwhat.utils.IpUtil;

@RestController
@ConditionalOnProperty(name="app.account.multiwriter.enabled",havingValue="true")
@RequestMapping("/auth/password-conflict")
public class AccountPasswordRecoveryController {
    @Resource
    private TencentCaptchaService captchaService;
    private final AccountPasswordRecovery recovery;
    public AccountPasswordRecoveryController(AccountPasswordRecovery recovery){this.recovery=recovery;}
    public record Request(@NotBlank @Size(max=64) String identifier){ }
    public record Reset(@NotBlank @Size(max=256) String token,@NotBlank @Size(min=8,max=128) String newPassword){@Override public String toString(){return "PasswordRecovery[redacted]";}}
    @PostMapping("/challenge")
    public Result<?> challenge(@Valid @RequestBody Request body, HttpServletRequest request, HttpServletResponse response,
            @RequestHeader(value = TencentCaptchaService.TICKET_HEADER, required = false) String captchaTicket,
            @RequestHeader(value = TencentCaptchaService.RANDSTR_HEADER, required = false) String captchaRandstr) {
        response.setHeader("Cache-Control", "no-store");
        captchaService.verify(captchaTicket, captchaRandstr, IpUtil.getClientIp(request));
        recovery.request(body.identifier(), request.getRemoteAddr());
        return Result.ok();
    }
    @PostMapping("/reset") public Result<?> reset(@Valid @RequestBody Reset body,HttpServletResponse response){response.setHeader("Cache-Control","no-store");recovery.reset(body.token(),body.newPassword());return Result.ok();}
}
