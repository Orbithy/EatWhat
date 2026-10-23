package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.sync.catalog.CatalogConflictService;
import you.v50to.eatwhat.sync.catalog.CatalogConflictService.ResolveRequest;
import you.v50to.eatwhat.sync.catalog.CatalogConflictService.ResolutionResult;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.Status;

import java.util.List;
import java.util.UUID;

@RestController
@SaCheckRole("admin")
@ConditionalOnProperty(name = "app.sync.catalog.enabled", havingValue = "true")
@RequestMapping("/admin/sync/catalog/conflicts")
public class CatalogConflictController {
    private final CatalogConflictService conflicts;

    public CatalogConflictController(CatalogConflictService conflicts) {
        this.conflicts = conflicts;
    }

    @GetMapping
    public Result<List<CatalogConflictService.ConflictView>> listOpenConflicts() {
        return Result.ok(conflicts.listOpenConflicts());
    }

    @GetMapping("/reviews")
    public Result<List<CatalogConflictService.AuthorizationReviewView>> listAuthorizationReviews() {
        return Result.ok(conflicts.listAuthorizationReviews());
    }

    @GetMapping("/decisions")
    public Result<List<you.v50to.eatwhat.sync.catalog.CatalogDecisionService.DecisionView>> listDecisions() {
        return Result.ok(conflicts.listDecisions());
    }

    @GetMapping("/decisions/{conflictId}")
    public Result<you.v50to.eatwhat.sync.catalog.CatalogDecisionService.DecisionView> decisionSnapshot(@PathVariable UUID conflictId) {
        return Result.ok(conflicts.decisionSnapshot(conflictId));
    }

    @PostMapping("/decisions/{conflictId}/resolve")
    public Result<ResolutionResult> resolveDecision(@PathVariable UUID conflictId,@RequestBody CatalogConflictService.ResolveDecisionRequest request) {
        if(request==null||request.candidateOperations()==null||request.candidateOperations().isEmpty()||request.candidateOperations().size()>128
                ||request.decisionHeads()==null||request.decisionHeads().size()>128||request.decision()==null||request.reason()==null||request.reason().isBlank()||request.reason().length()>1000
                ||(request.decision()==you.v50to.eatwhat.sync.catalog.CatalogDecision.KEEP)!=(request.winnerOperationId()!=null))return Result.fail(BizCode.PARAM_INVALID);
        ResolutionResult result=conflicts.resolveDecision(conflictId,request,StpUtil.getLoginIdAsLong());
        return result.status()==Status.APPLIED?Result.ok(result):Result.fail(BizCode.DATA_SYNC_PENDING,result);
    }

    @GetMapping("/objects")
    public Result<List<CatalogConflictService.ObjectHeadConflictView>> listOpenObjectHeadConflicts() {
        return Result.ok(conflicts.listOpenObjectHeadConflicts());
    }

    @PostMapping("/objects/{conflictId}/resolve")
    public Result<ResolutionResult> resolveObjectHeads(@PathVariable UUID conflictId,
            @Valid @RequestBody CatalogConflictService.ResolveHeadRequest request) {
        if (request == null || request.expectedRevision() < 1 || request.heads() == null
                || request.heads().isEmpty() || request.decision() == null
                || (request.decision() == you.v50to.eatwhat.sync.catalog.CatalogDecision.KEEP)
                != (request.winnerOperationId() != null)) return Result.fail(BizCode.PARAM_INVALID);
        ResolutionResult result = conflicts.resolveObjectHeads(conflictId, request, StpUtil.getLoginIdAsLong());
        if (result.status() != Status.APPLIED) return Result.fail(BizCode.DATA_SYNC_PENDING, result);
        return Result.ok(result);
    }

    @PostMapping("/{conflictId}/resolve")
    public Result<ResolutionResult> resolve(@PathVariable UUID conflictId,
                                            @Valid @RequestBody ResolveRequest request) {
        if (request == null || !conflictId.equals(request.conflictId()) || request.expectedRevision() < 1
                || request.candidates() == null || request.candidates().isEmpty()
                || request.decision() == null
                || (request.decision().name().equals("KEEP") && request.winnerGid() == null)
                || (request.decision().name().equals("REJECT") && request.winnerGid() != null)) {
            return Result.fail(BizCode.PARAM_INVALID);
        }
        ResolutionResult result = conflicts.resolve(request, StpUtil.getLoginIdAsLong());
        if (result == null || result.status() != Status.APPLIED) {
            return Result.fail(BizCode.DATA_SYNC_PENDING, result);
        }
        return Result.ok(result);
    }
}
