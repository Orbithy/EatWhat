package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import you.v50to.eatwhat.config.sync.ManagedDomainWriteGuard;
import you.v50to.eatwhat.data.dto.CreateActivityPostDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.data.po.Activity;
import you.v50to.eatwhat.data.po.ActivityLike;
import you.v50to.eatwhat.data.po.ActivityPost;
import you.v50to.eatwhat.data.vo.ActivityPostVO;
import you.v50to.eatwhat.data.vo.ActivityVO;
import you.v50to.eatwhat.data.vo.CreatedResourceVO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.ActivityLikeMapper;
import you.v50to.eatwhat.mapper.ActivityMapper;
import you.v50to.eatwhat.mapper.ActivityPostMapper;
import you.v50to.eatwhat.service.activity.ActivityDefinitions;
import you.v50to.eatwhat.service.activity.ActivityFieldSchema;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import you.v50to.eatwhat.sync.activity.ActivityEventCapture;
import you.v50to.eatwhat.sync.activity.ActivityEvents;
import you.v50to.eatwhat.sync.relation.RelationCommands;
import you.v50to.eatwhat.sync.relation.RelationKind;
import you.v50to.eatwhat.utils.LocationValidationUtil;
import you.v50to.eatwhat.utils.LoginUtil;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static you.v50to.eatwhat.utils.ValidUtil.validPage;
import static you.v50to.eatwhat.utils.ValidUtil.validPageSize;

/**
 * 通用活动：读取活动、在活动下发内容、点赞。活动定义由 {@link ActivityAdminService} 维护。
 * 内容同步走活动同步（activity_posts），点赞走关系同步（activity_post_like）。
 */
@Service
public class ActivityService {

    /** 点赞 target_type，与 activity_likes、notifications 中的取值一致。 */
    public static final String POST_TARGET = "post";
    private static final String BY_LIKES_CACHE_PREFIX = "activity:posts:byLikes:";
    private static final Duration BY_LIKES_CACHE_TTL = Duration.ofMinutes(2);
    private static final TypeReference<Map<String, Object>> EXTRA = new TypeReference<>() { };

    @Autowired(required = false)
    private FileLifecycleService fileLifecycleService;
    @Autowired(required = false)
    private RelationCommands relations;
    @Autowired(required = false)
    private ManagedDomainWriteGuard managedDomainWriteGuard;
    /** Present only while app.sync.activity.enabled=true; absent means the original local-only behavior. */
    @Autowired(required = false)
    private ActivityEventCapture capture;

    @Resource
    private ActivityMapper activityMapper;
    @Resource
    private ActivityPostMapper activityPostMapper;
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

    /** 已上线的活动，按排序值、ID 排列。 */
    public Result<List<ActivityVO>> listOnline() {
        long now = System.currentTimeMillis();
        List<ActivityVO> items = activityMapper.selectList(new LambdaQueryWrapper<Activity>()
                        .apply("catalog_visible")
                        .eq(Activity::getStatus, ActivityDefinitions.ONLINE)
                        .orderByAsc(Activity::getSort)
                        .orderByAsc(Activity::getId))
                .stream().map(activity -> ActivityDefinitions.toVO(activity, objectMapper, now)).toList();
        return Result.ok(items);
    }

    public Result<ActivityVO> getActivity(String code) {
        Activity activity = readableActivity(code);
        if (activity == null) return Result.fail(BizCode.ACTIVITY_NOT_FOUND);
        return Result.ok(ActivityDefinitions.toVO(activity, objectMapper, System.currentTimeMillis()));
    }

    public Result<PageResult<ActivityPostVO>> listPosts(String code, String sort, Integer page, Integer pageSize) {
        Activity activity = readableActivity(code);
        if (activity == null) return Result.fail(BizCode.ACTIVITY_NOT_FOUND);
        page = validPage(page);
        pageSize = validPageSize(pageSize);
        boolean byLikes = "LIKES".equals(sort);
        String cacheKey = byLikes ? byLikesCacheKey(activity.getId(), page, pageSize) : null;
        PageResult<ActivityPostVO> result = cacheKey == null ? null : readByLikesCache(cacheKey);
        if (result == null) {
            int offset = (page - 1) * pageSize;
            List<ActivityPostVO> items = activityPostMapper.selectActivePage(activity.getId(), byLikes, offset, pageSize)
                    .stream().map(post -> toVO(post, activity.getCode())).toList();
            result = PageResult.of(items, page.longValue(), pageSize.longValue(), activityPostMapper.countActive(activity.getId()));
            // 缓存里不含个人点赞状态，读出后再按当前用户补上。
            if (cacheKey != null) writeByLikesCache(cacheKey, result);
        }
        personalize(result.getItems());
        return Result.ok(result);
    }

    public Result<ActivityPostVO> getPost(Long id) {
        ActivityPost post = activityPostMapper.selectActiveById(id);
        Activity activity = post == null ? null : activityMapper.selectById(post.getActivityId());
        if (activity == null || !ActivityDefinitions.publiclyReadable(activity) || !activityMapper.visible(activity.getId())) {
            return Result.fail(BizCode.ACTIVITY_POST_NOT_FOUND);
        }
        ActivityPostVO vo = toVO(post, activity.getCode());
        personalize(List.of(vo));
        return Result.ok(vo);
    }

    @Transactional
    public Result<CreatedResourceVO> publishPost(String code, CreateActivityPostDTO dto) {
        Activity activity = readableActivity(code);
        if (activity == null) return Result.fail(BizCode.ACTIVITY_NOT_FOUND);
        if (!ActivityDefinitions.postingOpen(activity, System.currentTimeMillis())) return Result.fail(BizCode.ACTIVITY_NOT_OPEN);
        if (dto.getPictureUrl().size() > activity.getMaxPictures()) {
            return Result.fail(BizCode.PARAM_INVALID, "这个活动最多上传 " + activity.getMaxPictures() + " 张图片");
        }
        if (Boolean.TRUE.equals(activity.getRequireLocation()) && (dto.getProvinceId() == null || dto.getCityId() == null)) {
            return Result.fail(BizCode.PARAM_MISSING, "这个活动需要选择省份和城市");
        }
        if (dto.getProvinceId() != null || dto.getCityId() != null) {
            Result<Void> location = locationValidationUtil.validateProvinceAndCity(dto.getProvinceId(), dto.getCityId());
            if (location != null) return new Result<>(location.getCode(), null, location.getMsg());
        }
        String extra;
        try {
            Map<String, Object> values = ActivityFieldSchema.validateExtra(
                    ActivityDefinitions.schema(activity, objectMapper), dto.getExtra());
            extra = objectMapper.writeValueAsString(values);
        } catch (IllegalArgumentException invalid) {
            return Result.fail(BizCode.PARAM_INVALID, invalid.getMessage());
        }
        if (extra.getBytes(StandardCharsets.UTF_8).length > ActivityFieldSchema.MAX_EXTRA_BYTES) {
            return Result.fail(BizCode.PARAM_INVALID, "扩展字段内容过长");
        }

        ActivityPost post = new ActivityPost();
        post.setActivityId(activity.getId());
        post.setAccountId(StpUtil.getLoginIdAsLong());
        post.setTitle(blankToNull(dto.getTitle()));
        post.setDescription(dto.getDescription());
        post.setProvinceId(dto.getProvinceId());
        post.setCityId(dto.getCityId());
        post.setPictureUrl(dto.getPictureUrl().toArray(new String[0]));
        post.setExtra(extra);
        activityPostMapper.insert(post);
        if (fileLifecycleService != null) fileLifecycleService.finalizeAndReferenceMany(post.getAccountId(), UploadBiz.ACTIVITY,
                dto.getPictureUrl(), "activity_post", post.getId().toString(), "PUBLIC");
        if (capture != null) {
            capture.createdPost(post.getId(), post.getAccountId(), activity.getId(), post.getTitle(), post.getDescription(),
                    post.getProvinceId(), post.getCityId(), List.of(post.getPictureUrl()), extra);
        }
        clearByLikesCache(activity.getId());
        return Result.ok(new CreatedResourceVO(post.getId()));
    }

    @Transactional
    public Result<Void> deletePost(Long id) {
        ActivityPost post = activityPostMapper.selectById(id);
        if (post == null || post.getDeletedAt() != null) return Result.fail(BizCode.ACTIVITY_POST_NOT_FOUND);
        // 仅允许发布者本人删除
        if (!post.getAccountId().equals(StpUtil.getLoginIdAsLong())) {
            return Result.fail(BizCode.NO_PERMISSION, "仅可删除自己发布的内容");
        }
        post.setDeletedAt(System.currentTimeMillis());
        activityPostMapper.updateById(post);
        // Soft delete: release the file references in the same transaction.
        if (fileLifecycleService != null) fileLifecycleService.releaseReferences("activity_post", post.getId().toString(), null, "ENTITY_DELETED");
        if (capture != null) capture.deleted(ActivityEvents.POSTS, post.getId(), post.getAccountId(), post.getDeletedAt());
        clearByLikesCache(post.getActivityId());
        return Result.ok();
    }

    @Transactional
    public Result<Void> like(Long postId) {
        ActivityPost post = activePost(postId);
        if (post == null) return Result.fail(BizCode.ACTIVITY_POST_NOT_FOUND);
        Long userId = StpUtil.getLoginIdAsLong();
        if (relations != null) {
            relations.change(RelationKind.activity_post_like, userId, postId, null, true);
            clearByLikesCache(post.getActivityId());
            return Result.ok();
        }
        if (managedDomainWriteGuard != null && managedDomainWriteGuard.relationManaged(
                RelationKind.activity_post_like, userId, postId, null)) return Result.fail(BizCode.DATA_SYNC_PENDING);
        ActivityLike record = findLike(userId, postId);
        if (record != null && record.getDeletedAt() == null) return Result.ok();
        if (record == null) {
            ActivityLike like = new ActivityLike();
            like.setAccountId(userId);
            like.setTargetType(POST_TARGET);
            like.setActivityId(postId);
            activityLikeMapper.insert(like);
        } else {
            // 之前取消过点赞，恢复点赞
            activityLikeMapper.restore(record.getId());
        }
        clearByLikesCache(post.getActivityId());
        notificationService.notifyLike(userId, post.getAccountId(), POST_TARGET, postId);
        return Result.ok();
    }

    @Transactional
    public Result<Void> unlike(Long postId) {
        ActivityPost post = activePost(postId);
        // 内容已删除时取消点赞视为成功，与原有点赞接口一致。
        if (post == null) return Result.ok();
        Long userId = StpUtil.getLoginIdAsLong();
        if (relations != null) {
            relations.change(RelationKind.activity_post_like, userId, postId, null, false);
            clearByLikesCache(post.getActivityId());
            return Result.ok();
        }
        if (managedDomainWriteGuard != null && managedDomainWriteGuard.relationManaged(
                RelationKind.activity_post_like, userId, postId, null)) return Result.fail(BizCode.DATA_SYNC_PENDING);
        ActivityLike record = findLike(userId, postId);
        if (record == null || record.getDeletedAt() != null) return Result.ok();
        record.setDeletedAt(System.currentTimeMillis());
        activityLikeMapper.updateById(record);
        clearByLikesCache(post.getActivityId());
        return Result.ok();
    }

    private Activity readableActivity(String code) {
        Long id = code == null ? null : activityMapper.selectVisibleIdByCode(code);
        Activity activity = id == null ? null : activityMapper.selectById(id);
        return activity != null && ActivityDefinitions.publiclyReadable(activity) ? activity : null;
    }

    private ActivityPost activePost(Long postId) {
        ActivityPost post = postId == null ? null : activityPostMapper.selectById(postId);
        return post == null || post.getDeletedAt() != null ? null : post;
    }

    private ActivityLike findLike(Long userId, Long postId) {
        return activityLikeMapper.selectOne(new LambdaQueryWrapper<ActivityLike>()
                .eq(ActivityLike::getAccountId, userId)
                .eq(ActivityLike::getTargetType, POST_TARGET)
                .eq(ActivityLike::getActivityId, postId));
    }

    /** 游客不返回发布者 ID，避免枚举用户；登录用户补上自己的点赞状态。 */
    private void personalize(List<ActivityPostVO> items) {
        if (items == null || items.isEmpty()) return;
        Long userId = LoginUtil.currentUserIdOrNull();
        if (userId == null) {
            items.forEach(item -> { item.setAccountId(null); item.setIsLiked(false); });
            return;
        }
        Set<Long> liked = likedPostIds(userId, items.stream().map(ActivityPostVO::getId).toList());
        items.forEach(item -> item.setIsLiked(liked.contains(item.getId())));
    }

    private Set<Long> likedPostIds(Long accountId, List<Long> postIds) {
        List<ActivityLike> records = activityLikeMapper.selectList(new LambdaQueryWrapper<ActivityLike>()
                .eq(ActivityLike::getAccountId, accountId)
                .eq(ActivityLike::getTargetType, POST_TARGET)
                .isNull(ActivityLike::getDeletedAt)
                .in(ActivityLike::getActivityId, postIds));
        if (records == null || records.isEmpty()) return Collections.emptySet();
        return records.stream().map(ActivityLike::getActivityId).collect(Collectors.toCollection(HashSet::new));
    }

    private ActivityPostVO toVO(ActivityPost post, String activityCode) {
        ActivityPostVO vo = new ActivityPostVO();
        vo.setId(post.getId());
        vo.setActivityId(post.getActivityId());
        vo.setActivityCode(activityCode);
        vo.setAccountId(post.getAccountId());
        vo.setUploaderName(post.getUploaderName());
        vo.setTitle(post.getTitle());
        vo.setDescription(post.getDescription());
        vo.setProvinceId(post.getProvinceId());
        vo.setCityId(post.getCityId());
        vo.setPictureUrl(objectStorageService.signGetUrls(post.getPictureUrl() == null ? List.of() : Arrays.asList(post.getPictureUrl())));
        vo.setExtra(post.getExtra() == null ? Map.of() : objectMapper.readValue(post.getExtra(), EXTRA));
        vo.setLikesCount(post.getLikesCount());
        vo.setCreatedAt(post.getCreatedAt());
        vo.setUpdatedAt(post.getUpdatedAt());
        return vo;
    }

    private String byLikesCacheKey(Long activityId, Integer page, Integer pageSize) {
        return BY_LIKES_CACHE_PREFIX + activityId + ":page:" + page + ":size:" + pageSize;
    }

    private PageResult<ActivityPostVO> readByLikesCache(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) return null;
            return objectMapper.readValue(json, new TypeReference<PageResult<ActivityPostVO>>() { });
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeByLikesCache(String key, Object value) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), BY_LIKES_CACHE_TTL);
        } catch (Exception ignored) {
        }
    }

    private void clearByLikesCache(Long activityId) {
        try {
            Set<String> keys = redis.keys(BY_LIKES_CACHE_PREFIX + activityId + ":*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        } catch (Exception ignored) {
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
