package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import you.v50to.eatwhat.data.dto.*;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.ActivityDinner;
import you.v50to.eatwhat.data.po.ActivityFood;
import you.v50to.eatwhat.data.po.ActivityLike;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.ActivityDinnerMapper;
import you.v50to.eatwhat.mapper.ActivityFoodMapper;
import you.v50to.eatwhat.mapper.ActivityLikeMapper;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import you.v50to.eatwhat.utils.LocationValidationUtil;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
public class ActivityService {

    private static final String FOODS_BY_LIKES_CACHE_PREFIX = "activity:foods:byLikes:";
    private static final String DINNERS_BY_LIKES_CACHE_PREFIX = "activity:dinners:byLikes:";
    private static final Duration BY_LIKES_CACHE_TTL = Duration.ofMinutes(2);

    @Resource
    private ActivityFoodMapper activityFoodMapper;
    @Resource
    private ActivityDinnerMapper activityDinnerMapper;
    @Resource
    private ActivityLikeMapper activityLikeMapper;
    @Resource
    private LocationValidationUtil locationValidationUtil;
    @Resource
    private ObjectStorageService objectStorageService;
    @Resource
    private NotificationService notificationService;
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private ObjectMapper objectMapper;

    @Transactional
    public Result<Void> publishFood(CreateActivityFoodDTO dto) {
        // 发布地域美食前，先校验省市是否存在且归属正确
        Result<Void> validationResult = locationValidationUtil.validateProvinceAndCity(dto.getProvinceId(), dto.getCityId());
        if (validationResult != null) {
            return validationResult;
        }

        ActivityFood food = new ActivityFood();
        food.setAccountId(StpUtil.getLoginIdAsLong());
        food.setFoodName(dto.getFoodName());
        food.setDescription(dto.getDescription());
        food.setProvinceId(dto.getProvinceId());
        food.setCityId(dto.getCityId());
        food.setPictureUrl(toArray(dto.getPictureUrl()));

        activityFoodMapper.insert(food);
        clearFoodByLikesCache();
        return Result.ok();
    }

    public Result<PageResult<ActivityFoodDTO>> listFoods(Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        // 只查询未软删除记录，按创建时间倒序返回
        int offset = (page - 1) * pageSize;
        List<ActivityFood> foods = activityFoodMapper.selectActiveFoods(offset, pageSize);
        Long totalItems = activityFoodMapper.countActiveFoods();

        List<ActivityFoodDTO> items = foods.stream().map(this::toFoodDTO).toList();
        return Result.ok(PageResult.of(items, page, pageSize, totalItems));
    }

    public Result<ActivityFoodDTO> getFoodDetail(Long id) {
        ActivityFood food = activityFoodMapper.selectActiveFoodById(id);
        if (food == null) {
            return Result.fail(BizCode.OP_FAILED, "菜品不存在");
        }
        return Result.ok(toFoodDTO(food));
    }

    public Result<PageResult<ActivityFoodDTO>> listFoodsByLikes(Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        String cacheKey = byLikesCacheKey(FOODS_BY_LIKES_CACHE_PREFIX, page, pageSize);
        PageResult<ActivityFoodDTO> cached = readByLikesCache(cacheKey, new TypeReference<PageResult<ActivityFoodDTO>>() {
        });
        if (cached != null) {
            return Result.ok(cached);
        }

        int offset = (page - 1) * pageSize;
        List<ActivityFood> foods = activityFoodMapper.selectActiveFoodsOrderByLikes(offset, pageSize);
        Long totalItems = activityFoodMapper.countActiveFoods();
        List<ActivityFoodDTO> items = foods.stream().map(this::toFoodDTO).toList();

        PageResult<ActivityFoodDTO> result = PageResult.of(items, page, pageSize, totalItems);
        writeByLikesCache(cacheKey, result);
        return Result.ok(result);
    }

    @Transactional
    public Result<Void> deleteFood(Long id) {
        ActivityFood food = activityFoodMapper.selectById(id);
        if (food == null || food.getDeletedAt() != null) {
            return Result.fail(BizCode.OP_FAILED, "菜品不存在");
        }

        // 仅允许发布者本人删除
        Long currentUserId = StpUtil.getLoginIdAsLong();
        if (food.getAccountId() == null || !food.getAccountId().equals(currentUserId)) {
            return Result.fail(BizCode.NO_PERMISSION, "仅可删除自己发布的菜品");
        }

        // 软删除
        food.setDeletedAt(OffsetDateTime.now());
        activityFoodMapper.updateById(food);
        clearFoodByLikesCache();
        return Result.ok();
    }

    @Transactional
    public Result<Void> publishDinner(CreateActivityDinnerDTO dto) {
        ActivityDinner dinner = new ActivityDinner();
        dinner.setAccountId(StpUtil.getLoginIdAsLong());
        dinner.setDescription(dto.getDescription());
        dinner.setPictureUrl(toArray(dto.getPictureUrl()));

        activityDinnerMapper.insert(dinner);
        clearDinnerByLikesCache();
        return Result.ok();
    }

    public Result<PageResult<ActivityDinnerDTO>> listDinners(Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        // 只查询未软删除记录，按创建时间倒序返回
        int offset = (page - 1) * pageSize;
        List<ActivityDinner> dinners = activityDinnerMapper.selectActiveDinners(offset, pageSize);
        Long totalItems = activityDinnerMapper.countActiveDinners();

        List<ActivityDinnerDTO> items = dinners.stream().map(this::toDinnerDTO).toList();
        return Result.ok(PageResult.of(items, page, pageSize, totalItems));
    }

    public Result<ActivityDinnerDTO> getDinnerDetail(Long id) {
        ActivityDinner dinner = activityDinnerMapper.selectActiveDinnerById(id);
        if (dinner == null) {
            return Result.fail(BizCode.OP_FAILED, "年夜饭不存在");
        }
        return Result.ok(toDinnerDTO(dinner));
    }

    public Result<PageResult<ActivityDinnerDTO>> listDinnersByLikes(Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        String cacheKey = byLikesCacheKey(DINNERS_BY_LIKES_CACHE_PREFIX, page, pageSize);
        PageResult<ActivityDinnerDTO> cached = readByLikesCache(cacheKey, new TypeReference<PageResult<ActivityDinnerDTO>>() {
        });
        if (cached != null) {
            return Result.ok(cached);
        }

        int offset = (page - 1) * pageSize;
        List<ActivityDinner> dinners = activityDinnerMapper.selectActiveDinnersOrderByLikes(offset, pageSize);
        Long totalItems = activityDinnerMapper.countActiveDinners();
        List<ActivityDinnerDTO> items = dinners.stream().map(this::toDinnerDTO).toList();

        PageResult<ActivityDinnerDTO> result = PageResult.of(items, page, pageSize, totalItems);
        writeByLikesCache(cacheKey, result);
        return Result.ok(result);
    }

    @Transactional
    public Result<Void> deleteDinner(Long id) {
        ActivityDinner dinner = activityDinnerMapper.selectById(id);
        if (dinner == null || dinner.getDeletedAt() != null) {
            return Result.fail(BizCode.OP_FAILED, "年夜饭不存在");
        }

        // 仅允许发布者本人删除
        Long currentUserId = StpUtil.getLoginIdAsLong();
        if (dinner.getAccountId() == null || !dinner.getAccountId().equals(currentUserId)) {
            return Result.fail(BizCode.NO_PERMISSION, "仅可删除自己发布的年夜饭");
        }

        // 软删除：写 deleted_at
        dinner.setDeletedAt(OffsetDateTime.now());
        activityDinnerMapper.updateById(dinner);
        clearDinnerByLikesCache();
        return Result.ok();
    }

    @Transactional
    public Result<Void> like(ActivityLikeReqDTO dto) {
        // 点赞前校验目标是否存在且未删除
        if (!targetExistsAndActive(dto.getTargetType(), dto.getActivityId())) {
            return Result.fail(BizCode.OP_FAILED, "目标内容不存在");
        }

        Long userId = StpUtil.getLoginIdAsLong();
        ActivityLike record = activityLikeMapper.selectOne(new LambdaQueryWrapper<ActivityLike>()
                .eq(ActivityLike::getAccountId, userId)
                .eq(ActivityLike::getTargetType, dto.getTargetType())
                .eq(ActivityLike::getActivityId, dto.getActivityId()));

        // 首次点赞：直接插入
        if (record == null) {
            ActivityLike like = new ActivityLike();
            like.setAccountId(userId);
            like.setTargetType(dto.getTargetType());
            like.setActivityId(dto.getActivityId());
            activityLikeMapper.insert(like);
            clearByTargetType(dto.getTargetType());
            createLikeNotification(userId, dto.getTargetType(), dto.getActivityId());
            return Result.ok();
        }

        // 已经是点赞状态，保持幂等
        if (record.getDeletedAt() == null) {
            return Result.ok();
        }

        // 之前取消过点赞，恢复点赞
        record.setDeletedAt(null);
        activityLikeMapper.updateById(record);
        clearByTargetType(dto.getTargetType());
        createLikeNotification(userId, dto.getTargetType(), dto.getActivityId());
        return Result.ok();
    }

    @Transactional
    public Result<Void> unlike(ActivityLikeReqDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        ActivityLike record = activityLikeMapper.selectOne(new LambdaQueryWrapper<ActivityLike>()
                .eq(ActivityLike::getAccountId, userId)
                .eq(ActivityLike::getTargetType, dto.getTargetType())
                .eq(ActivityLike::getActivityId, dto.getActivityId()));

        // 未点赞或已取消点赞，保持幂等
        if (record == null || record.getDeletedAt() != null) {
            return Result.ok();
        }

        // 软删除点赞记录
        record.setDeletedAt(OffsetDateTime.now());
        activityLikeMapper.updateById(record);
        clearByTargetType(dto.getTargetType());
        return Result.ok();
    }

    private String byLikesCacheKey(String prefix, Integer page, Integer pageSize) {
        return prefix + "page:" + page + ":size:" + pageSize;
    }

    private <T> PageResult<T> readByLikesCache(String key, TypeReference<PageResult<T>> typeReference) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, typeReference);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeByLikesCache(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redis.opsForValue().set(key, json, BY_LIKES_CACHE_TTL);
        } catch (Exception ignored) {
        }
    }

    private void clearByTargetType(String targetType) {
        if ("food".equals(targetType)) {
            clearFoodByLikesCache();
            return;
        }
        clearDinnerByLikesCache();
    }

    private void clearFoodByLikesCache() {
        clearByPrefix(FOODS_BY_LIKES_CACHE_PREFIX);
    }

    private void clearDinnerByLikesCache() {
        clearByPrefix(DINNERS_BY_LIKES_CACHE_PREFIX);
    }

    private void clearByPrefix(String prefix) {
        try {
            Set<String> keys = redis.keys(prefix + "*");
            if (keys == null || keys.isEmpty()) {
                return;
            }
            redis.delete(keys);
        } catch (Exception ignored) {
        }
    }

    private void createLikeNotification(Long actorId, String targetType, Long activityId) {
        Long receiverId = getTargetOwnerId(targetType, activityId);
        if (receiverId == null) {
            return;
        }
        notificationService.notifyLike(actorId, receiverId, targetType, activityId);
    }

    private Long getTargetOwnerId(String targetType, Long activityId) {
        if ("food".equals(targetType)) {
            ActivityFood food = activityFoodMapper.selectById(activityId);
            if (food == null || food.getDeletedAt() != null) {
                return null;
            }
            return food.getAccountId();
        }
        ActivityDinner dinner = activityDinnerMapper.selectById(activityId);
        if (dinner == null || dinner.getDeletedAt() != null) {
            return null;
        }
        return dinner.getAccountId();
    }

    private boolean targetExistsAndActive(String targetType, Long activityId) {
        if ("food".equals(targetType)) {
            ActivityFood food = activityFoodMapper.selectById(activityId);
            return food != null && food.getDeletedAt() == null;
        }
        ActivityDinner dinner = activityDinnerMapper.selectById(activityId);
        return dinner != null && dinner.getDeletedAt() == null;
    }

    private ActivityFoodDTO toFoodDTO(ActivityFood food) {
        ActivityFoodDTO dto = new ActivityFoodDTO();
        dto.setId(food.getId());
        dto.setAccountId(food.getAccountId());
        dto.setFoodName(food.getFoodName());
        dto.setDescription(food.getDescription());
        dto.setProvinceId(food.getProvinceId());
        dto.setCityId(food.getCityId());
        dto.setPictureUrl(objectStorageService.signGetUrls(toList(food.getPictureUrl())));
        dto.setLikesCount(food.getLikesCount());
        dto.setIsLiked(Boolean.TRUE.equals(food.getIsLiked()));
        dto.setCreatedAt(toEpochMilli(food.getCreatedAt()));
        dto.setUpdatedAt(toEpochMilli(food.getUpdatedAt()));
        return dto;
    }

    private ActivityDinnerDTO toDinnerDTO(ActivityDinner dinner) {
        ActivityDinnerDTO dto = new ActivityDinnerDTO();
        dto.setId(dinner.getId());
        dto.setAccountId(dinner.getAccountId());
        dto.setPictureUrl(objectStorageService.signGetUrls(toList(dinner.getPictureUrl())));
        dto.setDescription(dinner.getDescription());
        dto.setLikesCount(dinner.getLikesCount());
        dto.setCreatedAt(toEpochMilli(dinner.getCreatedAt()));
        dto.setUpdatedAt(toEpochMilli(dinner.getUpdatedAt()));
        return dto;
    }

    private Long toEpochMilli(OffsetDateTime time) {
        return time == null ? null : time.toInstant().toEpochMilli();
    }

    private String[] toArray(List<String> pictureUrl) {
        if (pictureUrl == null) {
            return new String[0];
        }
        return pictureUrl.toArray(new String[0]);
    }

    private List<String> toList(String[] pictureUrl) {
        if (pictureUrl == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(pictureUrl);
    }

    private Integer validPage(Integer page) {
        if (page == null || page < 1) {
            return 1;
        }
        return page;
    }

    private Integer validPageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    public Result<PageResult<ActivityFoodDTO>> getFoodsByProvinceId(Integer provinceId,  Integer page, Integer pageSize) {
        if (page == null || page < 1) {
            page = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 20;
        }
        int offset = (page - 1) * pageSize;
        Long userId = StpUtil.getLoginIdAsLong();
        List<ActivityFood> records = activityFoodMapper.selectFoodsByProvinceWithLiked(provinceId, userId, offset, pageSize);
        Long total = activityFoodMapper.countActiveFoodsByProvince(provinceId);
        List<ActivityFoodDTO> foods = records.stream().map(this::toFoodDTO).toList();
        return Result.ok(PageResult.of(foods, page, pageSize, total));
    }
}
