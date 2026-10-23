package you.v50to.eatwhat.controller;

import you.v50to.eatwhat.data.dto.CityDTO;
import you.v50to.eatwhat.data.dto.ProvinceDTO;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.LocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 地理位置控制器
 */
@RestController
@RequestMapping("/api/location")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    /**
     * 获取所有省份
     */
    @GetMapping("/provinces")
    public Result<List<ProvinceDTO>> getAllProvinces() {
        return Result.ok(locationService.getAllProvinces());
    }

    /**
     * 根据省份ID获取城市列表
     */
    @GetMapping("/provinces/{provinceId}/cities")
    public Result<List<CityDTO>> getCitiesByProvinceId(@PathVariable Integer provinceId) {
        return Result.ok(locationService.getCitiesByProvinceId(provinceId));
    }
}

