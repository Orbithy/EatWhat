package you.v50to.eatwhat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.dto.AddCustomFoodTagsDTO;
import you.v50to.eatwhat.data.dto.AddSystemFoodTagsDTO;
import you.v50to.eatwhat.data.dto.CreateSystemFoodTagDTO;
import you.v50to.eatwhat.data.dto.FoodCustomTagRow;
import you.v50to.eatwhat.data.dto.FoodSystemTagAggregateRow;
import you.v50to.eatwhat.data.dto.FoodVO;
import you.v50to.eatwhat.data.dto.RenameCustomTagDTO;
import you.v50to.eatwhat.data.dto.UpdateSystemFoodTagDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.data.po.FoodTag;
import you.v50to.eatwhat.data.po.FoodTagging;
import you.v50to.eatwhat.data.vo.AdminFoodTagVO;
import you.v50to.eatwhat.data.vo.FoodSystemTagVO;
import you.v50to.eatwhat.data.vo.FoodTagSummaryVO;
import you.v50to.eatwhat.data.vo.FoodTagViewVO;
import you.v50to.eatwhat.data.vo.MyCustomTagVO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.FoodMapper;
import you.v50to.eatwhat.mapper.FoodTagMapper;
import you.v50to.eatwhat.mapper.FoodTaggingMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static you.v50to.eatwhat.utils.ValidUtil.validPage;
import static you.v50to.eatwhat.utils.ValidUtil.validPageSize;

@Service
public class FoodTagService {

    private static final String TAG_TYPE_SYSTEM = "system";
    private static final String TAG_TYPE_CUSTOM = "custom";

    @Resource
    private FoodMapper foodMapper;
    @Resource
    private FoodTagMapper foodTagMapper;
    @Resource
    private FoodTaggingMapper foodTaggingMapper;

    public Result<List<FoodTagSummaryVO>> listSystemTags() {
        return Result.ok(foodTagMapper.selectSystemTagSummaries());
    }

    public Result<Void> addSystemTagsToFood(Long foodId, Long accountId, AddSystemFoodTagsDTO dto) {
        if (requireFood(foodId) == null) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }

        List<Long> tagIds = dedupeLongs(dto.getTagIds());
        if (tagIds.isEmpty()) {
            return Result.fail(BizCode.PARAM_INVALID, "系统标签不能为空");
        }

        List<FoodTag> tags = foodTagMapper.selectSystemTagsByIds(tagIds);
        if (tags.size() != tagIds.size()) {
            return Result.fail(BizCode.PARAM_INVALID, "系统标签不存在");
        }

        for (Long tagId : tagIds) {
            createTaggingIfAbsent(foodId, tagId, accountId);
        }
        return Result.ok();
    }

    public Result<Void> addCustomTagsToFood(Long foodId, Long accountId, AddCustomFoodTagsDTO dto) {
        if (requireFood(foodId) == null) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }

        Map<String, String> normalizedToDisplay = new LinkedHashMap<>();
        for (String rawName : dto.getNames()) {
            String normalized = normalizeTagName(rawName);
            if (normalized.isEmpty()) {
                return Result.fail(BizCode.PARAM_INVALID, "标签名称不能为空");
            }
            normalizedToDisplay.putIfAbsent(normalized, rawName.trim().replaceAll("\\s+", " "));
        }

        List<String> normalizedNames = new ArrayList<>(normalizedToDisplay.keySet());
        List<FoodTag> existingTags = normalizedNames.isEmpty()
                ? Collections.emptyList()
                : foodTagMapper.selectCustomTagsByOwnerAndNormalizedNames(accountId, normalizedNames);
        Map<String, FoodTag> tagByNormalizedName = new LinkedHashMap<>();
        for (FoodTag existingTag : existingTags) {
            tagByNormalizedName.put(existingTag.getNormalizedName(), existingTag);
        }

        for (Map.Entry<String, String> entry : normalizedToDisplay.entrySet()) {
            FoodTag tag = tagByNormalizedName.get(entry.getKey());
            if (tag == null) {
                tag = createCustomTag(accountId, entry.getValue(), entry.getKey());
                tagByNormalizedName.put(entry.getKey(), tag);
            }
            createTaggingIfAbsent(foodId, tag.getId(), accountId);
        }

        return Result.ok();
    }

    public Result<Void> deleteMyTagging(Long foodId, Long tagId, Long accountId) {
        if (requireFood(foodId) == null) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }
        int rows = foodTaggingMapper.deleteMyTagging(foodId, tagId, accountId);
        if (rows <= 0) {
            return Result.fail(BizCode.OP_FAILED, "标签不存在或不属于当前用户");
        }
        return Result.ok();
    }

    public Result<FoodTagViewVO> getFoodTagView(Long foodId, Long accountId) {
        if (requireFood(foodId) == null) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }
        Map<Long, FoodTagViewVO> views = buildFoodTagViews(List.of(foodId), accountId);
        return Result.ok(views.getOrDefault(foodId, emptyView()));
    }

    public Result<FoodTagSummaryVO> createSystemTag(CreateSystemFoodTagDTO dto) {
        String normalized = normalizeTagName(dto.getName());
        if (normalized.isEmpty()) {
            return Result.fail(BizCode.PARAM_INVALID, "标签名称不能为空");
        }
        FoodTag tag = new FoodTag();
        tag.setName(dto.getName().trim().replaceAll("\\s+", " "));
        tag.setTagType(TAG_TYPE_SYSTEM);
        tag.setNormalizedName(normalized);
        try {
            foodTagMapper.insert(tag);
        } catch (DuplicateKeyException ex) {
            return Result.fail(BizCode.OP_FAILED, "系统标签已存在");
        }
        return Result.ok(new FoodTagSummaryVO(tag.getId(), tag.getName()));
    }

    public Result<Void> updateSystemTag(Long id, UpdateSystemFoodTagDTO dto) {
        FoodTag tag = foodTagMapper.selectById(id);
        if (tag == null || !TAG_TYPE_SYSTEM.equals(tag.getTagType())) {
            return Result.fail(BizCode.OP_FAILED, "系统标签不存在");
        }

        String normalized = normalizeTagName(dto.getName());
        if (normalized.isEmpty()) {
            return Result.fail(BizCode.PARAM_INVALID, "标签名称不能为空");
        }

        tag.setName(dto.getName().trim().replaceAll("\\s+", " "));
        tag.setNormalizedName(normalized);
        try {
            foodTagMapper.updateById(tag);
        } catch (DuplicateKeyException ex) {
            return Result.fail(BizCode.OP_FAILED, "系统标签已存在");
        }
        return Result.ok();
    }

    public Result<Void> deleteSystemTag(Long id) {
        FoodTag tag = foodTagMapper.selectById(id);
        if (tag == null || !TAG_TYPE_SYSTEM.equals(tag.getTagType())) {
            return Result.fail(BizCode.OP_FAILED, "系统标签不存在");
        }
        foodTagMapper.deleteById(id);
        return Result.ok();
    }

    public Result<PageResult<MyCustomTagVO>> listMyCustomTags(Long accountId, Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);
        int offset = (page - 1) * pageSize;

        List<MyCustomTagVO> items = foodTagMapper.selectCustomTagsByOwner(accountId, offset, pageSize);
        Long total = foodTagMapper.countCustomTagsByOwner(accountId);
        return Result.ok(PageResult.of(items, page.longValue(), pageSize.longValue(), total));
    }

    public Result<Void> renameMyCustomTag(Long tagId, Long accountId, RenameCustomTagDTO dto) {
        FoodTag tag = foodTagMapper.selectOne(new LambdaQueryWrapper<FoodTag>()
                .eq(FoodTag::getId, tagId)
                .eq(FoodTag::getTagType, TAG_TYPE_CUSTOM)
                .eq(FoodTag::getOwnerId, accountId));
        if (tag == null) {
            return Result.fail(BizCode.OP_FAILED, "标签不存在或不属于当前用户");
        }

        String normalized = normalizeTagName(dto.getName());
        if (normalized.isEmpty()) {
            return Result.fail(BizCode.PARAM_INVALID, "标签名称不能为空");
        }

        tag.setName(dto.getName().trim().replaceAll("\\s+", " "));
        tag.setNormalizedName(normalized);
        try {
            foodTagMapper.updateById(tag);
        } catch (DuplicateKeyException ex) {
            return Result.fail(BizCode.OP_FAILED, "已存在同名标签");
        }
        return Result.ok();
    }

    public Result<Void> deleteMyCustomTag(Long tagId, Long accountId) {
        FoodTag tag = foodTagMapper.selectOne(new LambdaQueryWrapper<FoodTag>()
                .eq(FoodTag::getId, tagId)
                .eq(FoodTag::getTagType, TAG_TYPE_CUSTOM)
                .eq(FoodTag::getOwnerId, accountId));
        if (tag == null) {
            return Result.fail(BizCode.OP_FAILED, "标签不存在或不属于当前用户");
        }
        foodTaggingMapper.deleteAllTaggingsByTagId(tagId, accountId);
        foodTagMapper.deleteById(tagId);
        return Result.ok();
    }

    public Result<PageResult<AdminFoodTagVO>> listSystemTagsForAdmin(Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);
        int offset = (page - 1) * pageSize;

        List<AdminFoodTagVO> items = foodTagMapper.selectSystemTagsForAdmin(offset, pageSize);
        Long total = foodTagMapper.countSystemTags();
        return Result.ok(PageResult.of(items, page.longValue(), pageSize.longValue(), total));
    }

    public void fillFoodTagViews(List<FoodVO> foods, Long accountId) {
        if (foods == null || foods.isEmpty()) {
            return;
        }

        List<Long> foodIds = foods.stream()
                .map(FoodVO::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, FoodTagViewVO> tagViewMap = buildFoodTagViews(foodIds, accountId);
        for (FoodVO food : foods) {
            FoodTagViewVO view = tagViewMap.getOrDefault(food.getId(), emptyView());
            food.setSystemTags(view.getSystemTags());
            food.setMyCustomTags(view.getMyCustomTags());
        }
    }

    public List<String> normalizeTagNames(Collection<String> rawNames) {
        if (rawNames == null || rawNames.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawName : rawNames) {
            String value = normalizeTagName(rawName);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return new ArrayList<>(normalized);
    }

    static String normalizeTagName(String rawName) {
        if (rawName == null) {
            return "";
        }
        return rawName.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private Food requireFood(Long foodId) {
        return foodMapper.selectById(foodId);
    }

    private FoodTag createCustomTag(Long accountId, String displayName, String normalizedName) {
        FoodTag customTag = new FoodTag();
        customTag.setName(displayName);
        customTag.setTagType(TAG_TYPE_CUSTOM);
        customTag.setOwnerId(accountId);
        customTag.setNormalizedName(normalizedName);
        try {
            foodTagMapper.insert(customTag);
            return customTag;
        } catch (DuplicateKeyException ex) {
            return foodTagMapper.selectOne(new LambdaQueryWrapper<FoodTag>()
                    .eq(FoodTag::getOwnerId, accountId)
                    .eq(FoodTag::getTagType, TAG_TYPE_CUSTOM)
                    .eq(FoodTag::getNormalizedName, normalizedName));
        }
    }

    private void createTaggingIfAbsent(Long foodId, Long tagId, Long accountId) {
        FoodTagging tagging = new FoodTagging();
        tagging.setFoodId(foodId);
        tagging.setTagId(tagId);
        tagging.setAccountId(accountId);
        try {
            foodTaggingMapper.insert(tagging);
        } catch (DuplicateKeyException ignored) {
            // 幂等：重复打相同标签时直接忽略。
        }
    }

    private Map<Long, FoodTagViewVO> buildFoodTagViews(List<Long> foodIds, Long accountId) {
        if (foodIds == null || foodIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, FoodTagViewVO> viewMap = new LinkedHashMap<>();
        for (Long foodId : foodIds) {
            viewMap.put(foodId, emptyView());
        }

        List<FoodSystemTagAggregateRow> systemRows = foodTaggingMapper.aggregateSystemTagsByFoodIds(foodIds, accountId);
        for (FoodSystemTagAggregateRow row : systemRows) {
            FoodTagViewVO view = viewMap.computeIfAbsent(row.getFoodId(), ignored -> emptyView());
            view.getSystemTags().add(toSystemTagVO(row));
        }

        List<FoodCustomTagRow> customRows = foodTaggingMapper.selectMyCustomTagsByFoodIds(foodIds, accountId);
        for (FoodCustomTagRow row : customRows) {
            FoodTagViewVO view = viewMap.computeIfAbsent(row.getFoodId(), ignored -> emptyView());
            view.getMyCustomTags().add(new FoodTagSummaryVO(row.getTagId(), row.getTagName()));
        }

        return viewMap;
    }

    private FoodSystemTagVO toSystemTagVO(FoodSystemTagAggregateRow row) {
        FoodSystemTagVO vo = new FoodSystemTagVO();
        vo.setId(row.getTagId());
        vo.setName(row.getTagName());
        vo.setCount(row.getTagCount());
        vo.setTaggedByMe(Boolean.TRUE.equals(row.getTaggedByMe()));
        return vo;
    }

    private FoodTagViewVO emptyView() {
        FoodTagViewVO view = new FoodTagViewVO();
        view.setSystemTags(new ArrayList<>());
        view.setMyCustomTags(new ArrayList<>());
        return view;
    }

    private List<Long> dedupeLongs(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(new LinkedHashSet<>(values));
    }
}
