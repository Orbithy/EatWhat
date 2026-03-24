package you.v50to.eatwhat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
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

import static you.v50to.eatwhat.utils.ValidUtil.validPage;
import static you.v50to.eatwhat.utils.ValidUtil.validPageSize;

@Service
public class HubService {

    @Resource
    private HubMapper hubMapper;

    public Result<PageResult<Hub>> listHubs(String keyword, Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        LambdaQueryWrapper<Hub> wrapper = new LambdaQueryWrapper<Hub>()
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
        Hub hub = new Hub();
        fillHub(hub, dto);
        hubMapper.insert(hub);
        return Result.ok();
    }

    public Result<Void> editHub(Long id, HubDTO dto) {
        Hub hub = hubMapper.selectById(id);
        if (hub == null) {
            return Result.fail(BizCode.HUB_NOT_FOUND);
        }
        fillHub(hub, dto);
        hubMapper.updateById(hub);
        return Result.ok();
    }

    public Result<Void> deleteHub(Long id) {
        Hub hub = hubMapper.selectById(id);
        if (hub == null) {
            return Result.fail(BizCode.HUB_NOT_FOUND);
        }
        hubMapper.clearRestaurantBindings(id);
        hubMapper.deleteById(id);
        return Result.ok();
    }

    public Result<Integer> rebindRestaurants(Long id) {
        Hub hub = hubMapper.selectById(id);
        if (hub == null) {
            return Result.fail(BizCode.HUB_NOT_FOUND);
        }
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
