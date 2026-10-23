package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.dto.HubDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.Hub;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.HubMapper;
import you.v50to.eatwhat.utils.GeoUtil;
import you.v50to.eatwhat.utils.HubGeometryUtil;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.config.sync.ManagedDomainWriteGuard;
import you.v50to.eatwhat.sync.catalog.CatalogCommands;
import you.v50to.eatwhat.sync.catalog.CatalogKind;
import you.v50to.eatwhat.sync.catalog.CatalogHubRebindResult;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.Status;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.UpdatePlan;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.WriteOutcome;

import static you.v50to.eatwhat.utils.ValidUtil.validPage;
import static you.v50to.eatwhat.utils.ValidUtil.validPageSize;

@Service
public class HubService {

    @Autowired(required = false)
    private CatalogCommands catalogCommands;
    @Autowired(required = false)
    private ManagedDomainWriteGuard managedDomainWriteGuard;
    @Autowired(required = false)
    private SiteProperties siteProperties;

    @Resource
    private HubMapper hubMapper;
    @Resource
    private CampusService campusService;

    public Result<PageResult<Hub>> listHubs(String keyword, Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        LambdaQueryWrapper<Hub> wrapper = new LambdaQueryWrapper<Hub>().apply("catalog_visible")
                .like(keyword != null && !keyword.isBlank(), Hub::getName, keyword)
                .orderByDesc(Hub::getCreatedAt);
        IPage<Hub> result = hubMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return Result.ok(PageResult.of(result.getRecords(), result.getCurrent(), result.getSize(), result.getTotal()));
    }

    public Result<Hub> getHubDetail(Long id) {
        Hub hub = hubMapper.selectHubById(id);
        if (hub == null) {
            return Result.fail(BizCode.HUB_NOT_FOUND);
        }
        return Result.ok(hub);
    }

    public Result<Void> addHub(HubDTO dto) {
        if (dto.getCampusId() != null && !campusService.exists(dto.getCampusId())) {
            return Result.fail(BizCode.PARAM_INVALID, "校区不存在");
        }
        if (siteProperties != null && siteProperties.getMode() == SiteProperties.Mode.EXTERNAL && (catalogCommands == null || !catalogCommands.multiWriterEnabled())) return Result.fail(BizCode.NO_PERMISSION);
        Hub hub = new Hub();
        fillHub(hub, dto);
        if (catalogCommands == null) {
            hubMapper.insert(hub);
        } else {
            WriteOutcome<Integer> outcome = catalogCommands.create(
                    CatalogKind.HUB, StpUtil.getLoginIdAsLong(), null, null, java.util.Map.of(),
                    () -> hubMapper.insert(hub), hub::getId, rows -> rows != null && rows > 0);
            if (outcome.status() != Status.APPLIED) return Result.fail(BizCode.DATA_SYNC_PENDING);
        }
        return Result.ok();
    }

    public Result<Void> editHub(Long id, HubDTO dto) {
        if (dto.getCampusId() != null && !campusService.exists(dto.getCampusId())) {
            return Result.fail(BizCode.PARAM_INVALID, "校区不存在");
        }
        if (siteProperties != null && siteProperties.getMode() == SiteProperties.Mode.EXTERNAL && (catalogCommands == null || !catalogCommands.multiWriterEnabled())) return Result.fail(BizCode.NO_PERMISSION);
        if (catalogCommands == null && managedDomainWriteGuard != null && managedDomainWriteGuard.catalogManaged(CatalogKind.HUB, id)) {
            return Result.fail(BizCode.DATA_SYNC_PENDING);
        }
        if (catalogCommands == null) {
            Hub hub = hubMapper.selectById(id);
            if (hub == null) return Result.fail(BizCode.HUB_NOT_FOUND);
            fillHub(hub, dto);
            hubMapper.updateById(hub);
        } else {
            if (hubMapper.selectById(id) == null) return Result.fail(BizCode.HUB_NOT_FOUND);
            WriteOutcome<Integer> outcome = catalogCommands.update(CatalogKind.HUB, id, StpUtil.getLoginIdAsLong(), oldPayload -> {
                Hub hub = hubMapper.selectById(id);
                if (hub == null) return new UpdatePlan<>(null, java.util.Map.of(), () -> 0, rows -> false);
                fillHub(hub, dto);
                return new UpdatePlan<>(null, java.util.Map.of(), () -> hubMapper.updateById(hub), rows -> rows != null && rows > 0);
            });
            if (outcome.status() != Status.APPLIED) {
                if (outcome.value() != null && outcome.value() == 0) return Result.fail(BizCode.HUB_NOT_FOUND);
                if (hubMapper.selectById(id) == null) return Result.fail(BizCode.HUB_NOT_FOUND);
                return Result.fail(BizCode.DATA_SYNC_PENDING);
            }
        }
        return Result.ok();
    }

    public Result<Void> deleteHub(Long id) {
        if (siteProperties != null && siteProperties.getMode() == SiteProperties.Mode.EXTERNAL && (catalogCommands == null || !catalogCommands.multiWriterEnabled())) return Result.fail(BizCode.NO_PERMISSION);
        if (hubMapper.selectById(id) == null) return Result.fail(BizCode.HUB_NOT_FOUND);
        if (catalogCommands == null && managedDomainWriteGuard != null && managedDomainWriteGuard.catalogManaged(CatalogKind.HUB, id)) {
            return Result.fail(BizCode.DATA_SYNC_PENDING);
        }
        if (catalogCommands == null) {
            Hub hub = hubMapper.selectById(id);
            if (hub == null) return Result.fail(BizCode.HUB_NOT_FOUND);
            hubMapper.clearRestaurantBindings(id);
            hubMapper.deleteById(id);
        } else {
            WriteOutcome<Integer> outcome = catalogCommands.deleteHub(
                    id, StpUtil.getLoginIdAsLong(), () -> hubMapper.deleteById(id), rows -> rows != null && rows > 0);
            if (outcome.status() != Status.APPLIED) {
                if (outcome.value() != null && outcome.value() == 0) return Result.fail(BizCode.HUB_NOT_FOUND);
                return Result.fail(BizCode.DATA_SYNC_PENDING);
            }
        }
        return Result.ok();
    }

    public Result<Integer> rebindRestaurants(Long id) {
        if (siteProperties != null && siteProperties.getMode() == SiteProperties.Mode.EXTERNAL && (catalogCommands == null || !catalogCommands.multiWriterEnabled())) return Result.fail(BizCode.NO_PERMISSION);
        if (catalogCommands != null) {
            try {
                CatalogHubRebindResult result = catalogCommands.rebindHub(id, StpUtil.getLoginIdAsLong());
                return Result.ok(result.coveredCount());
            } catch (IllegalArgumentException ex) {
                if ("HUB_NOT_FOUND".equals(ex.getMessage())) return Result.fail(BizCode.HUB_NOT_FOUND);
                return Result.fail(BizCode.DATA_SYNC_PENDING);
            }
        }
        if (managedDomainWriteGuard != null && managedDomainWriteGuard.catalogManaged(CatalogKind.HUB, id)) {
            return Result.fail(BizCode.DATA_SYNC_PENDING);
        }
        Hub hub = hubMapper.selectById(id);
        if (hub == null) return Result.fail(BizCode.HUB_NOT_FOUND);
        hubMapper.clearRestaurantBindings(id);
        int updated = hubMapper.bindCoveredRestaurants(id);
        return Result.ok(updated);
    }

    public Result<Hub> findHubByLocation(double gcjLng, double gcjLat) {
        double[] wgs = you.v50to.eatwhat.utils.CoordinateTransformUtil.gcj02ToWgs84(gcjLng, gcjLat);
        Hub hub = hubMapper.findHubByLocation(wgs[0], wgs[1]);
        return Result.ok(hub);
    }

    private void fillHub(Hub hub, HubDTO dto) {
        BeanUtils.copyProperties(dto, hub, "boundaryGeoJson");
        hub.setCenter(GeoUtil.gcjToWgsPoint(dto.getGcjLng(), dto.getGcjLat()));
        hub.setBoundary(HubGeometryUtil.parseBoundaryGeoJson(dto.getBoundaryGeoJson()));
    }
}
