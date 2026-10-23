package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.sync.profile.ProfileConflictService;

import java.util.List;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "app.sync.profile.enabled", havingValue = "true")
@RequestMapping("/admin/sync/profile-conflicts")
@SaCheckRole("admin")
public class ProfileConflictController {
    private final ProfileConflictService conflicts;

    public record ResolveRequest(long expectedRevision, UUID selectedHeadId) { }

    public ProfileConflictController(ProfileConflictService conflicts) { this.conflicts = conflicts; }

    @GetMapping
    public Result<List<ProfileConflictService.Conflict>> list() {
        return Result.ok(conflicts.openConflicts());
    }

    @GetMapping("/authorization-reviews")
    public Result<List<ProfileConflictService.AuthorizationReview>> authorizationReviews() {
        return Result.ok(conflicts.authorizationReviews());
    }

    @PostMapping("/authorization-reviews/{eventId}/resolve")
    public Result<Void> resolveAuthorizationReview(@PathVariable UUID eventId,@RequestBody ResolveRequest request) {
        if(request==null||request.expectedRevision()<1||request.selectedHeadId()==null)return Result.fail(BizCode.PARAM_INVALID);
        return conflicts.resolveAuthorizationReview(eventId,request.expectedRevision(),request.selectedHeadId())
                ?Result.ok():Result.fail(BizCode.DATA_SYNC_PENDING,"复核版本已变化");
    }

    @PostMapping("/{conflictId}/resolve")
    public Result<Void> resolve(@PathVariable UUID conflictId, @RequestBody ResolveRequest request) {
        if (request == null || request.expectedRevision() < 1 || request.selectedHeadId() == null) {
            return Result.fail(BizCode.PARAM_INVALID);
        }
        return conflicts.resolve(conflictId, request.expectedRevision(), request.selectedHeadId())
                ? Result.ok() : Result.fail(BizCode.DATA_SYNC_PENDING, "冲突版本已变化");
    }
}
