package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.account.ConsentPolicyService;
import you.v50to.eatwhat.data.dto.SaveConsentPolicyDTO;
import you.v50to.eatwhat.data.vo.*;

@RestController
@ConditionalOnProperty(name="app.account.enabled",havingValue="true")
@RequestMapping("/admin/consent/policy")
@SaCheckRole("admin")
public class ConsentPolicyAdminController {
    private final ConsentPolicyService policies;
    public ConsentPolicyAdminController(ConsentPolicyService policies){this.policies=policies;}
    /** 读取政策配置及用于防止覆盖修改的修订号 */
    @GetMapping public Result<ConsentPolicySettingsVO> get(HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");return Result.ok(policies.settings());
    }
    /** 修改并持久化政策版本和 URL，自动同步到所有参与站点；仅权威站可写 */
    @PutMapping public Result<ConsentPolicySettingsVO> save(@Valid @RequestBody SaveConsentPolicyDTO body,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");return Result.ok(policies.save(body,StpUtil.getLoginIdAsLong()));
    }
}
