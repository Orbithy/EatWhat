package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.dto.CreateFoodDTO;
import you.v50to.eatwhat.data.dto.FoodVO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.FoodMapper;
import you.v50to.eatwhat.mapper.RestaurantMapper;
import you.v50to.eatwhat.service.storage.ObjectStorageService;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static you.v50to.eatwhat.utils.ValidUtil.validPage;
import static you.v50to.eatwhat.utils.ValidUtil.validPageSize;

@Service
public class FoodService {

    @Resource
    private FoodMapper foodMapper;
    @Resource
    private RestaurantMapper restaurantMapper;
    @Resource
    private ObjectStorageService objectStorageService;

    public Result<Void> uploadFood(CreateFoodDTO dto) {
        // 校验餐厅是否存在
        Restaurant restaurant = restaurantMapper.selectById(dto.getRestaurantId());
        if (restaurant == null) {
            return Result.fail(BizCode.OP_FAILED, "餐厅不存在");
        }

        Food food = new Food();
        food.setAccountId(StpUtil.getLoginIdAsLong());
        food.setRestaurantId(dto.getRestaurantId());
        food.setName(dto.getName());
        food.setDescription(dto.getDescription());
        food.setPrice(dto.getPrice());
        food.setCategory(dto.getCategory());
        food.setPictureUrl(dto.getPictureUrl() != null
                ? dto.getPictureUrl().toArray(new String[0])
                : new String[0]);

        foodMapper.insert(food);
        return Result.ok();
    }

    public Result<PageResult<FoodVO>> listByRestaurant(Long restaurantId, Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        int offset = (page - 1) * pageSize;
        List<Food> foods = foodMapper.selectByRestaurantId(restaurantId, offset, pageSize);
        Long total = foodMapper.countByRestaurantId(restaurantId);

        List<FoodVO> items = foods.stream().map(this::toVO).toList();
        return Result.ok(PageResult.of(items, page.longValue(), pageSize.longValue(), total));
    }

    private FoodVO toVO(Food food) {
        FoodVO vo = new FoodVO();
        vo.setId(food.getId());
        vo.setAccountId(food.getAccountId());
        vo.setUploaderName(food.getUploaderName());
        vo.setRestaurantId(food.getRestaurantId());
        vo.setName(food.getName());
        vo.setDescription(food.getDescription());
        vo.setPrice(food.getPrice());
        vo.setCategory(food.getCategory());
        vo.setPictureUrl(objectStorageService.signGetUrls(toList(food.getPictureUrl())));
        vo.setLikesCount(food.getLikesCount());
        vo.setCreatedAt(food.getCreatedAt() != null ? food.getCreatedAt().toInstant().toEpochMilli() : null);
        vo.setUpdatedAt(food.getUpdatedAt() != null ? food.getUpdatedAt().toInstant().toEpochMilli() : null);
        return vo;
    }

    private List<String> toList(String[] arr) {
        if (arr == null) return Collections.emptyList();
        return Arrays.asList(arr);
    }
}

