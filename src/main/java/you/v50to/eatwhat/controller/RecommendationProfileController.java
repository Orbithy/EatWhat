package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import you.v50to.eatwhat.data.vo.RecommendationProfileVO;
import you.v50to.eatwhat.data.dto.FoodPreferencesDTO;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.RecommendationProfileService;

/** Manages explicit tastes only; inferred recommendation profiles remain server-internal. */
@SaCheckLogin
@RestController
@RequestMapping("/user/recommendation-profile")
public class RecommendationProfileController {
    private final RecommendationProfileService service;

    public RecommendationProfileController(RecommendationProfileService service) {
        this.service = service;
    }

    @GetMapping("/preferences")
    public Result<RecommendationProfileVO.ManualPreferences> preferences(HttpServletResponse response) {
        noStore(response);
        return service.getPreferences(StpUtil.getLoginIdAsLong());
    }

    @PutMapping("/preferences")
    public Result<RecommendationProfileVO.ManualPreferences> preferences(@Valid @RequestBody FoodPreferencesDTO preferences,
                                                       HttpServletResponse response) {
        noStore(response);
        return service.updatePreferences(StpUtil.getLoginIdAsLong(), preferences);
    }

    @DeleteMapping("/preferences")
    public Result<RecommendationProfileVO.ManualPreferences> clear(HttpServletResponse response) {
        noStore(response);
        return service.clearPreferences(StpUtil.getLoginIdAsLong());
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
    }
}
