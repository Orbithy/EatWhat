package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.sync.catalog.CatalogImportService;
import you.v50to.eatwhat.sync.catalog.CatalogHubImportRequest;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.CatalogImportResult;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.ImportStatus;

@RestController
@SaCheckRole("admin")
@ConditionalOnProperty(name = "app.sync.catalog.enabled", havingValue = "true")
@RequestMapping("/admin/sync/catalog/import")
public class CatalogImportController {
    private final CatalogImportService catalog;

    public CatalogImportController(CatalogImportService catalog) {
        this.catalog = catalog;
    }

    @PostMapping("/hubs")
    public Result<CatalogImportResult> importHubs(@Valid @RequestBody CatalogHubImportRequest request) {
        CatalogImportResult result = catalog.importHubs(request);
        if (result == null || (result.status() != ImportStatus.APPLIED && result.status() != ImportStatus.REPLAYED)) {
            return Result.fail(BizCode.DATA_SYNC_PENDING, result);
        }
        return Result.ok(result);
    }
}
