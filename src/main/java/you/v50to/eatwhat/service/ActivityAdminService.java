package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.config.sync.ManagedDomainWriteGuard;
import you.v50to.eatwhat.data.dto.ActivityStatusDTO;
import you.v50to.eatwhat.data.dto.SaveActivityDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.Activity;
import you.v50to.eatwhat.data.vo.ActivityVO;
import you.v50to.eatwhat.data.vo.CreatedResourceVO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.ActivityMapper;
import you.v50to.eatwhat.service.activity.ActivityDefinitions;
import you.v50to.eatwhat.service.activity.ActivityFieldSchema;
import you.v50to.eatwhat.sync.catalog.CatalogCommands;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.Status;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.UpdatePlan;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.WriteOutcome;
import you.v50to.eatwhat.sync.catalog.CatalogKind;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static you.v50to.eatwhat.utils.ValidUtil.validPage;
import static you.v50to.eatwhat.utils.ValidUtil.validPageSize;

/**
 * 管理员维护活动定义。开启目录同步时写入经 CatalogCommands（kind=ACTIVITY）同步到另一站，
 * 站点规则与商场、系统标签相同：未开启多写时只有校内站能改。
 */
@Service
public class ActivityAdminService {

    private static final java.util.Set<String> RESERVED_CODES = java.util.Set.of("list", "posts");

    @Resource
    private ActivityMapper activityMapper;
    @Resource
    private ObjectMapper objectMapper;
    @Autowired(required = false)
    private CatalogCommands catalogCommands;
    @Autowired(required = false)
    private ManagedDomainWriteGuard managedDomainWriteGuard;
    @Autowired(required = false)
    private SiteProperties siteProperties;

    public Result<PageResult<ActivityVO>> list(Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);
        IPage<Activity> result = activityMapper.selectPage(new Page<>(page, pageSize), new LambdaQueryWrapper<Activity>()
                .apply("catalog_visible")
                .orderByAsc(Activity::getSort)
                .orderByAsc(Activity::getId));
        long now = System.currentTimeMillis();
        List<ActivityVO> items = result.getRecords().stream()
                .map(activity -> ActivityDefinitions.toVO(activity, objectMapper, now)).toList();
        return Result.ok(PageResult.of(items, page.longValue(), pageSize.longValue(), result.getTotal()));
    }

    public Result<ActivityVO> detail(Long id) {
        Activity activity = visible(id);
        if (activity == null) return Result.fail(BizCode.ACTIVITY_NOT_FOUND);
        return Result.ok(ActivityDefinitions.toVO(activity, objectMapper, System.currentTimeMillis()));
    }

    public Result<CreatedResourceVO> create(SaveActivityDTO dto) {
        if (writesBlockedOnThisSite()) return Result.fail(BizCode.NO_PERMISSION);
        if (dto.getCode() == null) return Result.fail(BizCode.PARAM_MISSING, "code不能为空");
        // 与 /activity/list、/activity/posts/{id} 路径冲突
        if (RESERVED_CODES.contains(dto.getCode())) return Result.fail(BizCode.PARAM_INVALID, "code 不能使用 list 或 posts");
        Result<CreatedResourceVO> invalid = validate(dto);
        if (invalid != null) return invalid;
        if (activityMapper.selectVisibleIdByCode(dto.getCode()) != null) return Result.fail(BizCode.ACTIVITY_CODE_EXISTS);
        Activity activity = new Activity();
        activity.setCode(dto.getCode());
        activity.setStatus(ActivityDefinitions.DRAFT);
        fill(activity, dto);
        try {
            if (catalogCommands == null) {
                activityMapper.insert(activity);
            } else {
                WriteOutcome<Integer> outcome = catalogCommands.create(
                        CatalogKind.ACTIVITY, StpUtil.getLoginIdAsLong(), null, null, Map.of("code", dto.getCode()),
                        () -> activityMapper.insert(activity), activity::getId, rows -> rows != null && rows > 0);
                if (outcome.status() != Status.APPLIED) return Result.fail(BizCode.DATA_SYNC_PENDING);
            }
        } catch (DuplicateKeyException duplicate) {
            return Result.fail(BizCode.ACTIVITY_CODE_EXISTS);
        }
        return Result.ok(new CreatedResourceVO(activity.getId()));
    }

    public Result<Void> update(Long id, SaveActivityDTO dto) {
        if (writesBlockedOnThisSite()) return Result.fail(BizCode.NO_PERMISSION);
        Activity current = visible(id);
        if (current == null) return Result.fail(BizCode.ACTIVITY_NOT_FOUND);
        if (dto.getCode() != null && !dto.getCode().equals(current.getCode())) {
            return Result.fail(BizCode.PARAM_INVALID, "活动 code 不能修改");
        }
        Result<Void> invalid = validate(dto);
        if (invalid != null) return invalid;
        return change(id, activity -> fill(activity, dto));
    }

    public Result<Void> changeStatus(Long id, ActivityStatusDTO dto) {
        if (writesBlockedOnThisSite()) return Result.fail(BizCode.NO_PERMISSION);
        if (visible(id) == null) return Result.fail(BizCode.ACTIVITY_NOT_FOUND);
        return change(id, activity -> activity.setStatus(dto.getStatus()));
    }

    /** 只有还没有任何内容的活动能删除；有内容的请下线。 */
    public Result<Void> delete(Long id) {
        if (writesBlockedOnThisSite()) return Result.fail(BizCode.NO_PERMISSION);
        if (visible(id) == null) return Result.fail(BizCode.ACTIVITY_NOT_FOUND);
        if (activityMapper.hasPosts(id)) return Result.fail(BizCode.ACTIVITY_HAS_POSTS);
        if (catalogCommands == null && managedDomainWriteGuard != null
                && managedDomainWriteGuard.catalogManaged(CatalogKind.ACTIVITY, id)) {
            return Result.fail(BizCode.DATA_SYNC_PENDING);
        }
        if (catalogCommands == null) {
            activityMapper.deleteById(id);
            return Result.ok();
        }
        WriteOutcome<Integer> outcome = catalogCommands.delete(CatalogKind.ACTIVITY, id, StpUtil.getLoginIdAsLong(),
                () -> activityMapper.deleteById(id), rows -> rows != null && rows > 0);
        if (outcome.status() != Status.APPLIED) {
            if (activityMapper.hasPosts(id)) return Result.fail(BizCode.ACTIVITY_HAS_POSTS);
            return Result.fail(BizCode.DATA_SYNC_PENDING);
        }
        return Result.ok();
    }

    private Result<Void> change(Long id, Consumer<Activity> edit) {
        if (catalogCommands == null && managedDomainWriteGuard != null
                && managedDomainWriteGuard.catalogManaged(CatalogKind.ACTIVITY, id)) {
            return Result.fail(BizCode.DATA_SYNC_PENDING);
        }
        if (catalogCommands == null) {
            Activity activity = activityMapper.selectById(id);
            if (activity == null) return Result.fail(BizCode.ACTIVITY_NOT_FOUND);
            edit.accept(activity);
            activityMapper.updateById(activity);
            return Result.ok();
        }
        WriteOutcome<Integer> outcome = catalogCommands.update(CatalogKind.ACTIVITY, id, StpUtil.getLoginIdAsLong(), oldPayload -> {
            Activity locked = activityMapper.selectById(id);
            if (locked == null) return new UpdatePlan<>(null, Map.of(), () -> 0, rows -> false);
            edit.accept(locked);
            return new UpdatePlan<>(null, Map.of("code", locked.getCode()), () -> activityMapper.updateById(locked),
                    rows -> rows != null && rows > 0);
        });
        if (outcome.status() != Status.APPLIED) {
            if (outcome.value() != null && outcome.value() == 0) return Result.fail(BizCode.ACTIVITY_NOT_FOUND);
            return Result.fail(BizCode.DATA_SYNC_PENDING);
        }
        return Result.ok();
    }

    private <T> Result<T> validate(SaveActivityDTO dto) {
        if (dto.getStartsAt() != null && dto.getEndsAt() != null && dto.getStartsAt() >= dto.getEndsAt()) {
            return Result.fail(BizCode.PARAM_INVALID, "结束时间需晚于开始时间");
        }
        try {
            dto.setFieldSchema(ActivityFieldSchema.validateDefinition(dto.getFieldSchema()));
        } catch (IllegalArgumentException invalid) {
            return Result.fail(BizCode.PARAM_INVALID, invalid.getMessage());
        }
        return null;
    }

    private void fill(Activity activity, SaveActivityDTO dto) {
        activity.setName(dto.getName().strip());
        activity.setDescription(dto.getDescription());
        activity.setCoverUrl(dto.getCoverUrl());
        activity.setStartsAt(dto.getStartsAt());
        activity.setEndsAt(dto.getEndsAt());
        activity.setSort(dto.getSort() == null ? 0 : dto.getSort());
        activity.setRequireLocation(Boolean.TRUE.equals(dto.getRequireLocation()));
        activity.setMaxPictures(dto.getMaxPictures() == null ? 9 : dto.getMaxPictures());
        activity.setFieldSchema(objectMapper.writeValueAsString(dto.getFieldSchema()));
    }

    private Activity visible(Long id) {
        if (id == null || !activityMapper.visible(id)) return null;
        return activityMapper.selectById(id);
    }

    /** 与商场、系统标签一致：海外站只有开启多写后才能管理。 */
    private boolean writesBlockedOnThisSite() {
        return siteProperties != null && siteProperties.getMode() == SiteProperties.Mode.EXTERNAL
                && (catalogCommands == null || !catalogCommands.multiWriterEnabled());
    }
}
