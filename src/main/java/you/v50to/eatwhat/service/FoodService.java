package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import you.v50to.eatwhat.utils.LoginUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import you.v50to.eatwhat.sync.relation.RelationCommands;
import you.v50to.eatwhat.sync.relation.RelationKind;
import you.v50to.eatwhat.config.sync.ManagedDomainWriteGuard;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.dto.CoverCropDTO;
import you.v50to.eatwhat.data.dto.CreateFoodDTO;
import you.v50to.eatwhat.data.dto.FoodCandidateRow;
import you.v50to.eatwhat.data.dto.RecommendQueryDTO;
import you.v50to.eatwhat.data.po.Campus;
import you.v50to.eatwhat.data.dto.EditFoodDTO;
import you.v50to.eatwhat.data.dto.FoodVO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.MealTime;
import you.v50to.eatwhat.data.enums.StageColor;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.data.po.FoodFavorite;
import you.v50to.eatwhat.data.po.LikeRecord;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.FoodFavoriteMapper;
import you.v50to.eatwhat.mapper.FoodMapper;
import you.v50to.eatwhat.mapper.LikeMapper;
import you.v50to.eatwhat.mapper.RestaurantMapper;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.sync.catalog.CatalogCommands;
import you.v50to.eatwhat.sync.catalog.CatalogKind;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.WriteOutcome;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.Status;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.UpdatePlan;
import you.v50to.eatwhat.config.SiteProperties;

import you.v50to.eatwhat.data.dto.FoodQueryDTO;
import you.v50to.eatwhat.data.dto.AddSystemFoodTagsDTO;
import you.v50to.eatwhat.data.dto.AddCustomFoodTagsDTO;
import you.v50to.eatwhat.data.vo.CreatedResourceVO;
import you.v50to.eatwhat.exception.BizException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static you.v50to.eatwhat.utils.ValidUtil.validPage;
import static you.v50to.eatwhat.utils.ValidUtil.validPageSize;

@Service
public class FoodService {

    @Autowired(required = false)
    private FileLifecycleService fileLifecycleService;

    @Autowired(required = false)
    private CatalogCommands catalogCommands;

    @Autowired(required=false)
    private RelationCommands relations;
    @Autowired(required=false)
    private ManagedDomainWriteGuard managedDomainWriteGuard;
    @Autowired(required = false)
    private SiteProperties siteProperties;

    @Resource
    private FoodMapper foodMapper;
    @Resource
    private RestaurantMapper restaurantMapper;
    @Resource
    private ObjectStorageService objectStorageService;
    @Resource
    private BrowseHistoryService browseHistoryService;
    @Resource
    private FoodTagService foodTagService;
    @Resource
    private FoodFavoriteMapper foodFavoriteMapper;
    @Resource
    private LikeMapper likeMapper;
    @Resource
    private CampusService campusService;
    /** 为空时（如部分测试装配）推荐不做当天去重。 */
    @Autowired(required = false)
    private RecommendationSeenStore seenStore;

    @Transactional
    public Result<CreatedResourceVO> uploadFood(CreateFoodDTO dto) {
        String displayError = displayFieldsError(dto.getStageColor(), dto.getStageAlternates());
        if (displayError != null) {
            return Result.fail(BizCode.PARAM_INVALID, displayError);
        }
        // 校验餐厅是否存在
        Restaurant restaurant = restaurantMapper.selectById(dto.getRestaurantId());
        if (restaurant == null || Boolean.FALSE.equals(restaurant.getCatalogVisible())) {
            return Result.fail(BizCode.OP_FAILED, "餐厅不存在");
        }

        Food food = new Food();
        food.setAccountId(currentUserId());
        food.setRestaurantId(dto.getRestaurantId());
        food.setName(dto.getName().strip());
        food.setDescription(dto.getDescription());
        food.setPrice(dto.getPrice());
        food.setCategory(dto.getCategory());
        food.setPictureUrl(dto.getPictureUrl() != null
                ? dto.getPictureUrl().toArray(new String[0])
                : new String[0]);
        applyDisplayFields(food, dto.getMealTimes(), dto.getStageColor(), dto.getStageAlternates(),
                dto.getCoverCrop(), dto.getRemoveBackground());

        if (catalogCommands == null) {
            insertFoodRow(food);
        } else {
            WriteOutcome<Integer> outcome = catalogCommands.create(
                    CatalogKind.FOOD, food.getAccountId(), food.getRestaurantId(),
                    Map.of("restaurantId", food.getRestaurantId(), "name", food.getName()),
                    () -> insertFoodRow(food), food::getId, rows -> rows != null && rows > 0);
            if (outcome.status() != Status.APPLIED) {
                return Result.fail(BizCode.DATA_SYNC_PENDING);
            }
        }
        if (fileLifecycleService != null) fileLifecycleService.finalizeAndReferenceMany(food.getAccountId(), UploadBiz.FOODS,
                dto.getPictureUrl(), "food", food.getId().toString(), "PUBLIC");
        if (dto.getSystemTagIds() != null && !dto.getSystemTagIds().isEmpty()) {
            AddSystemFoodTagsDTO tags = new AddSystemFoodTagsDTO();
            tags.setTagIds(dto.getSystemTagIds());
            requireTagSuccess(foodTagService.addSystemTagsToFood(food.getId(), food.getAccountId(), tags));
        }
        if (dto.getMyCustomTagNames() != null && !dto.getMyCustomTagNames().isEmpty()) {
            AddCustomFoodTagsDTO tags = new AddCustomFoodTagsDTO();
            tags.setNames(dto.getMyCustomTagNames());
            requireTagSuccess(foodTagService.addCustomTagsToFood(food.getId(), food.getAccountId(), tags));
        }
        return Result.ok(new CreatedResourceVO(food.getId()));
    }

    // Throwing is intentional: a failed initial tagging must roll back the resource, files and outbox.
    private void requireTagSuccess(Result<Void> result) {
        if (result == null) throw new BizException(BizCode.OP_FAILED);
        if (!Objects.equals(result.getCode(), 0)) {
            BizCode code = Arrays.stream(BizCode.values()).filter(c -> c.getCode() == result.getCode())
                    .findFirst().orElse(BizCode.OP_FAILED);
            throw new BizException(code);
        }
    }

    public Result<PageResult<FoodVO>> queryFoods(FoodQueryDTO query, boolean favoritesOnly) {
        if (query.getMinPrice() != null && query.getMaxPrice() != null
                && query.getMinPrice().compareTo(query.getMaxPrice()) > 0) {
            return Result.fail(BizCode.PARAM_INVALID, "最低价格不能大于最高价格");
        }
        if (!favoritesOnly && query.getSort() == FoodQueryDTO.Sort.FAVORITED_AT) {
            return Result.fail(BizCode.PARAM_INVALID, "收藏时间排序仅适用于收藏列表");
        }
        int page = validPage(query.getPage()), size = validPageSize(query.getPageSize());
        long offset = ((long) page - 1) * size;
        query.setSystemTagIds(distinctIds(query.getSystemTagIds()));
        query.setMyCustomTagIds(distinctIds(query.getMyCustomTagIds()));
        Long userId = favoritesOnly || query.getUploaderId() != null ? currentUserId() : currentUserIdOrNull();
        List<String> names = foodTagService.normalizeTagNames(query.getMyCustomTagNames());
        List<Food> foods = foodMapper.selectFoods(query, userId, favoritesOnly, names, offset, size);
        Long total = foodMapper.countFoods(query, userId, favoritesOnly, names);
        List<FoodVO> items = foods.stream().map(this::toVO).toList();
        fillFoodViews(items, userId);
        return Result.ok(PageResult.of(items, (long) page, (long) size, total));
    }

    private List<Long> distinctIds(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().distinct().toList();
    }

    public Result<PageResult<FoodVO>> listByRestaurant(Long restaurantId,
                                                       Integer page,
                                                       Integer pageSize,
                                                       List<Long> systemTagIds,
                                                       List<Long> myCustomTagIds,
                                                       List<String> myCustomTagNames) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        int offset = (page - 1) * pageSize;
        Long currentUserId = currentUserId();
        List<String> normalizedCustomTagNames = foodTagService.normalizeTagNames(myCustomTagNames);

        boolean hasFilters = (systemTagIds != null && !systemTagIds.isEmpty())
                || (myCustomTagIds != null && !myCustomTagIds.isEmpty())
                || !normalizedCustomTagNames.isEmpty();

        List<Food> foods = hasFilters
                ? foodMapper.selectByRestaurantIdWithFilters(
                restaurantId,
                offset,
                pageSize,
                currentUserId,
                systemTagIds,
                myCustomTagIds,
                normalizedCustomTagNames)
                : foodMapper.selectByRestaurantId(restaurantId, offset, pageSize);
        Long total = hasFilters
                ? foodMapper.countByRestaurantIdWithFilters(
                restaurantId,
                currentUserId,
                systemTagIds,
                myCustomTagIds,
                normalizedCustomTagNames)
                : foodMapper.countByRestaurantId(restaurantId);

        List<FoodVO> items = foods.stream().map(this::toVO).toList();
        fillFoodViews(items, currentUserId);
        return Result.ok(PageResult.of(items, page.longValue(), pageSize.longValue(), total));
    }

    static final int DEFAULT_BATCH = 8;
    /** 候选上限：一个校区的菜量级在几百道以内，取前 500 道再在 Java 里排一批。 */
    static final int CANDIDATE_LIMIT = 500;
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");

    /** 推荐一批菜（产品文档第 8 节），规则见 FoodRecommender。游客、未认证账号不带个人因素。 */
    public Result<List<FoodVO>> recommendFoods(RecommendQueryDTO query) {
        int size = query.getSize() != null ? query.getSize()
                : query.getLimit() != null ? Math.max(1, Math.min(query.getLimit(), 20)) : DEFAULT_BATCH;
        Campus campus = campusService.resolve(query.getCampusId());
        if (campus == null) {
            return Result.fail(BizCode.PARAM_INVALID, "校区不存在或未开放");
        }
        ZonedDateTime now = now().atZone(CAMPUS_ZONE);
        MealTime mealTime = query.getMealTime() != null ? query.getMealTime() : MealTime.at(now.toLocalTime());
        String category = query.getCategory() == null ? null : query.getCategory().getValue();

        Long currentUserId = currentUserIdOrNull();
        boolean personalized = currentUserId != null && personalizationAllowed();
        List<FoodCandidateRow> candidates = foodMapper.selectRecommendationCandidates(
                personalized ? currentUserId : null, campus.getId(), Boolean.TRUE.equals(campus.getIsDefault()),
                category, CANDIDATE_LIMIT);

        String actor = currentUserId != null ? "u" + currentUserId
                : query.getDeviceId() != null ? "d" + query.getDeviceId() : null;
        String seenKey = actor == null || seenStore == null ? null
                : seenStore.key(actor, campus.getId(), category, now.toLocalDate());
        Set<Long> seen = seenKey == null ? Set.of() : seenStore.seen(seenKey);
        FoodRecommender.Batch batch = FoodRecommender.pick(candidates, mealTime, personalized, seen, size);
        if (seenKey != null) {
            seenStore.record(seenKey, batch.items().stream().map(Food::getId).toList(), batch.exhausted());
        }

        List<FoodVO> items = batch.items().stream().map(row -> {
            FoodVO vo = toVO(row);
            vo.setRestaurantAddress(row.getRestaurantAddress());
            vo.setRestaurantGcjLng(row.getRestaurantGcjLng());
            vo.setRestaurantGcjLat(row.getRestaurantGcjLat());
            vo.setHubId(row.getHubId());
            vo.setHubName(row.getHubName());
            return vo;
        }).toList();
        fillFoodViews(items, currentUserId);
        return Result.ok(items);
    }

    /** 未认证账号看到的推荐不带个人因素（产品文档第 8 节）。 */
    protected boolean personalizationAllowed() {
        return StpUtil.hasRole("verified");
    }

    protected LocalDateTime now() {
        return LocalDateTime.now(CAMPUS_ZONE);
    }

    private FoodVO toVO(Food food) {
        FoodVO vo = new FoodVO();
        vo.setId(food.getId());
        vo.setAccountId(food.getAccountId());
        vo.setUploaderName(food.getUploaderName());
        vo.setRestaurantId(food.getRestaurantId());
        vo.setRestaurantName(food.getRestaurantName());
        vo.setName(food.getName());
        vo.setDescription(food.getDescription());
        vo.setPrice(food.getPrice());
        vo.setCategory(food.getCategory());
        vo.setCategoryLabel(food.getCategory() == null ? null : food.getCategory().getLabel());
        vo.setPictureKeys(toList(food.getPictureUrl()));
        vo.setPictureUrl(objectStorageService.signGetUrls(toList(food.getPictureUrl())));
        vo.setLikesCount(food.getLikesCount());
        vo.setMealTimes(food.getMealTimes() == null ? List.of()
                : Arrays.stream(food.getMealTimes()).map(MealTime::fromValue).toList());
        vo.setStageColor(food.getStageColor() == null ? null : StageColor.fromValue(food.getStageColor()));
        vo.setStageAlternates(food.getStageAlternates() == null ? null
                : Arrays.stream(food.getStageAlternates()).map(StageColor::fromValue).toList());
        vo.setCoverCrop(food.getCoverCenterX() == null ? null
                : new CoverCropDTO(food.getCoverCenterX(), food.getCoverCenterY(), food.getCoverDiameter()));
        vo.setRemoveBackground(Boolean.TRUE.equals(food.getRemoveBackground()));
        vo.setCreatedAt(food.getCreatedAt());
        vo.setUpdatedAt(food.getUpdatedAt());
        return vo;
    }

    private List<String> toList(String[] arr) {
        if (arr == null) return Collections.emptyList();
        return Arrays.asList(arr);
    }

    @Transactional
    public Result<Void> deleteFood(Long id) {
        return deleteFood(id, false);
    }

    @Transactional
    public Result<Void> deleteMyFood(Long id) {
        return deleteFood(id, true);
    }

    private Result<Void> deleteFood(Long id, boolean ownerOnly) {
        if (siteProperties != null && siteProperties.getMode() == SiteProperties.Mode.EXTERNAL && (catalogCommands == null || !catalogCommands.multiWriterEnabled())) return Result.fail(BizCode.NO_PERMISSION);
        Food existing = foodMapper.selectById(id);
        if (existing == null) return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        if (ownerOnly && !Objects.equals(existing.getAccountId(), currentUserId())) return Result.fail(BizCode.NO_PERMISSION);
        if (catalogCommands == null && managedDomainWriteGuard != null && managedDomainWriteGuard.catalogManaged(CatalogKind.FOOD, id)) {
            return Result.fail(BizCode.DATA_SYNC_PENDING);
        }
        int rows;
        if (catalogCommands == null) {
            rows = deleteFoodRow(id, ownerOnly);
        } else {
            WriteOutcome<Integer> outcome = catalogCommands.delete(
                    CatalogKind.FOOD, id, currentUserId(), oldPayload -> new CatalogCommands.DeletePlan<>(
                            () -> deleteFoodRow(id, ownerOnly), affected -> affected != null && affected > 0));
            if (outcome.status() != Status.APPLIED) {
                if (outcome.value() != null && outcome.value() == 0) return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
                if (foodMapper.selectById(id) == null) return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
                return Result.fail(BizCode.DATA_SYNC_PENDING);
            }
            rows = outcome.value();
        }
        if (rows <= 0) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }
        // Hard delete: release the file references in the same transaction.
        if (fileLifecycleService != null && (catalogCommands == null || !catalogCommands.multiWriterEnabled())) fileLifecycleService.releaseReferences("food", id.toString(), null, "ENTITY_DELETED");

        return Result.ok();
    }

    public Result<FoodVO> getFoodDetail(Long id) {
        Food food = foodMapper.selectById(id);
        if (food == null || Boolean.FALSE.equals(food.getCatalogVisible())) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }
        Restaurant parent = restaurantMapper.selectById(food.getRestaurantId());
        if (parent != null && Boolean.FALSE.equals(parent.getCatalogVisible())) return Result.fail(BizCode.DATA_SYNC_PENDING);
        Long currentUserId = currentUserIdOrNull();
        if (currentUserId != null) browseHistoryService.recordBrowse(currentUserId, "food", id);
        FoodVO vo = toVO(food);
        fillFoodViews(List.of(vo), currentUserId);
        return Result.ok(vo);
    }

    @Transactional
    public Result<Void> editFood(Long id, @Valid EditFoodDTO dto) {
        return editFood(id, dto, false);
    }

    @Transactional
    public Result<Void> editMyFood(Long id, EditFoodDTO dto) {
        return editFood(id, dto, true);
    }

    private Result<Void> editFood(Long id, EditFoodDTO dto, boolean ownerOnly) {
        String displayError = displayFieldsError(dto.getStageColor(), dto.getStageAlternates());
        if (displayError != null) {
            return Result.fail(BizCode.PARAM_INVALID, displayError);
        }
        if (siteProperties != null && siteProperties.getMode() == SiteProperties.Mode.EXTERNAL && (catalogCommands == null || !catalogCommands.multiWriterEnabled())) return Result.fail(BizCode.NO_PERMISSION);
        Food beforeEdit = foodMapper.selectById(id);
        if (beforeEdit == null) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }
        if (ownerOnly && !Objects.equals(beforeEdit.getAccountId(), currentUserId())) return Result.fail(BizCode.NO_PERMISSION);
        if (dto.getRestaurantId() != null && restaurantMapper.selectById(dto.getRestaurantId()) == null) {
            return Result.fail(BizCode.RESTAURANT_NOT_FOUND, "餐厅不存在");
        }
        // MyBatis may return the same entity again inside this transaction. Capture the old keys before applyEdit.
        String[] previousPictureKeys = beforeEdit.getPictureUrl() == null ? null : beforeEdit.getPictureUrl().clone();
        if (catalogCommands == null && managedDomainWriteGuard != null && managedDomainWriteGuard.catalogManaged(CatalogKind.FOOD, id)) {
            return Result.fail(BizCode.DATA_SYNC_PENDING);
        }
        int rows;
        if (catalogCommands == null) {
            Food food = foodMapper.selectById(id);
            if (food == null) return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
            applyEdit(food, dto);
            rows = updateFoodRow(food, ownerOnly);
        } else {
            WriteOutcome<Integer> outcome = catalogCommands.update(
                    CatalogKind.FOOD, id, currentUserId(), oldPayload -> {
                        Food food = foodMapper.selectById(id);
                        if (food == null) {
                            return new UpdatePlan<>(null, Map.of(), () -> 0, affected -> false);
                        }
                        applyEdit(food, dto);
                        return new UpdatePlan<>(food.getRestaurantId(), Map.of("restaurantId", food.getRestaurantId(), "name", food.getName()),
                                () -> updateFoodRow(food, ownerOnly), affected -> affected != null && affected > 0);
                    });
            if (outcome.status() != Status.APPLIED) {
                if (outcome.value() != null && outcome.value() == 0) return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
                if (foodMapper.selectById(id) == null) return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
                return Result.fail(BizCode.DATA_SYNC_PENDING);
            }
            rows = outcome.value();
        }
        if (rows <= 0) {
            return Result.fail(BizCode.OP_FAILED, "操作失败");
        }
        if (fileLifecycleService != null && dto.getPictureUrl() != null) fileLifecycleService.finalizeAndReferenceManyExcluding(
                currentUserId(), UploadBiz.FOODS, dto.getPictureUrl(), previousPictureKeys == null ? List.of() : Arrays.asList(previousPictureKeys), "food", id.toString(), "PUBLIC");
        releaseReplacedPictures(id, previousPictureKeys, dto.getPictureUrl());
        return Result.ok();
    }

    private int deleteFoodRow(Long id, boolean ownerOnly) {
        if (!ownerOnly) return foodMapper.deleteById(id);
        return foodMapper.delete(new LambdaQueryWrapper<Food>().eq(Food::getId, id)
                .eq(Food::getAccountId, currentUserId()));
    }

    private void requireUniqueName(Food food) {
        if (you.v50to.eatwhat.utils.FoodNames.key(food.getName()).isEmpty()) {
            throw new BizException(BizCode.PARAM_INVALID, "菜名不能为空");
        }
        foodMapper.lockName(food.getRestaurantId(), food.getName());
        Long duplicate = foodMapper.findDuplicateName(food.getRestaurantId(), food.getName(), food.getId());
        if (duplicate != null && duplicate > 0) throw new you.v50to.eatwhat.exception.DuplicateFoodException(duplicate);
    }

    private int insertFoodRow(Food food) {
        requireUniqueName(food);
        return foodMapper.insert(food);
    }

    private int updateFoodRow(Food food, boolean ownerOnly) {
        requireUniqueName(food);
        if (!ownerOnly) return foodMapper.updateById(food);
        return foodMapper.update(food, new LambdaQueryWrapper<Food>().eq(Food::getId, food.getId())
                .eq(Food::getAccountId, currentUserId()));
    }

    /** Releases only the keys that were dropped by the edit; keys that stay referenced are untouched. */
    private void releaseReplacedPictures(Long id, String[] before, List<String> after) {
        if (fileLifecycleService == null || before == null || after == null) return;
        Set<String> removed = new HashSet<>(Arrays.asList(before));
        removed.removeAll(after);
        removed.remove(null);
        if (!removed.isEmpty()) fileLifecycleService.releaseReferences("food", id.toString(), removed, "KEY_REPLACED");
    }

    private void applyEdit(Food food, EditFoodDTO dto) {
        if (dto.getRestaurantId() != null) {
            food.setRestaurantId(dto.getRestaurantId());
        }
        if (dto.getName() != null) food.setName(dto.getName().strip());
        if (dto.getDescription() != null) food.setDescription(dto.getDescription());
        if (dto.getPrice() != null) food.setPrice(dto.getPrice());
        if (dto.getCategory() != null) food.setCategory(dto.getCategory());
        if (dto.getPictureUrl() != null) food.setPictureUrl(dto.getPictureUrl().toArray(new String[0]));
        applyDisplayFields(food, dto.getMealTimes(), dto.getStageColor(), dto.getStageAlternates(),
                dto.getCoverCrop(), dto.getRemoveBackground());
    }

    /** 舞台色和备选色要一起传，三者互不相同（产品文档 4.7：推荐的三个颜色）。 */
    private static String displayFieldsError(StageColor stageColor, List<StageColor> alternates) {
        if ((stageColor == null) != (alternates == null)) return "舞台色和备选色要一起提交";
        if (stageColor != null && new HashSet<>(List.of(stageColor, alternates.get(0), alternates.get(1))).size() != 3) {
            return "舞台色和两个备选色不能重复";
        }
        return null;
    }

    /** 只覆盖传了的字段；调用前已经过 displayFieldsError 校验。 */
    private static void applyDisplayFields(Food food, List<MealTime> mealTimes, StageColor stageColor,
                                           List<StageColor> alternates, CoverCropDTO crop, Boolean removeBackground) {
        if (mealTimes != null) food.setMealTimes(MealTime.canonical(mealTimes));
        if (stageColor != null) {
            food.setStageColor(stageColor.getValue());
            food.setStageAlternates(alternates.stream().map(StageColor::getValue).toArray(String[]::new));
        }
        if (crop != null) {
            food.setCoverCenterX(crop.getCenterX());
            food.setCoverCenterY(crop.getCenterY());
            food.setCoverDiameter(crop.getDiameter());
        }
        if (removeBackground != null) food.setRemoveBackground(removeBackground);
    }

    public Result<PageResult<FoodVO>> getMyFood(Long userId, Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        int offset = (page - 1) * pageSize;
        List<Food> foods = foodMapper.selectByAccountId(userId, offset, pageSize);
        Long total = foodMapper.countByAccountId(userId);

        List<FoodVO> items = foods.stream().map(this::toVO).toList();
        fillFoodViews(items, userId);
        return Result.ok(PageResult.of(items, page.longValue(), pageSize.longValue(), total));
    }

    @Transactional
    public Result<Void> favoriteFood(Long foodId) {
        if (foodMapper.selectById(foodId) == null) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }

        Long userId = currentUserId();
        if (relations != null) {
            relations.change(RelationKind.favorite, userId, foodId, null, true);
            return Result.ok();
        }
        if (managedDomainWriteGuard != null && managedDomainWriteGuard.relationManaged(
                RelationKind.favorite, userId, foodId, null)) return Result.fail(BizCode.DATA_SYNC_PENDING);
        FoodFavorite favorite = foodFavoriteMapper.selectOne(new LambdaQueryWrapper<FoodFavorite>()
                .eq(FoodFavorite::getAccountId, userId)
                .eq(FoodFavorite::getFoodId, foodId));
        if (favorite != null) {
            return Result.ok();
        }

        FoodFavorite record = new FoodFavorite();
        record.setAccountId(userId);
        record.setFoodId(foodId);
        foodFavoriteMapper.insert(record);
        return Result.ok();
    }

    @Transactional
    public Result<Void> likeFood(Long foodId) {
        if (foodMapper.selectById(foodId) == null) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }

        Long userId = currentUserId();
        if (relations != null) {
            relations.change(RelationKind.food_like, userId, foodId, null, true);
            return Result.ok();
        }
        if (managedDomainWriteGuard != null && managedDomainWriteGuard.relationManaged(
                RelationKind.food_like, userId, foodId, null)) return Result.fail(BizCode.DATA_SYNC_PENDING);
        LikeRecord record = likeMapper.selectOne(new LambdaQueryWrapper<LikeRecord>()
                .eq(LikeRecord::getAccountId, userId)
                .eq(LikeRecord::getTargetType, "food")
                .eq(LikeRecord::getTargetId, foodId));
        if (record != null && record.getDeletedAt() == null) {
            return Result.ok();
        }

        if (record == null) {
            LikeRecord like = new LikeRecord();
            like.setAccountId(userId);
            like.setTargetType("food");
            like.setTargetId(foodId);
            likeMapper.insert(like);
            return Result.ok();
        }

        likeMapper.restore(record.getId());
        return Result.ok();
    }

    @Transactional
    public Result<Void> unlikeFood(Long foodId) {
        if (foodMapper.selectById(foodId) == null) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }

        Long userId = currentUserId();
        if (relations != null) {
            relations.change(RelationKind.food_like, userId, foodId, null, false);
            return Result.ok();
        }
        if (managedDomainWriteGuard != null && managedDomainWriteGuard.relationManaged(
                RelationKind.food_like, userId, foodId, null)) return Result.fail(BizCode.DATA_SYNC_PENDING);
        LikeRecord record = likeMapper.selectOne(new LambdaQueryWrapper<LikeRecord>()
                .eq(LikeRecord::getAccountId, userId)
                .eq(LikeRecord::getTargetType, "food")
                .eq(LikeRecord::getTargetId, foodId));
        if (record == null || record.getDeletedAt() != null) {
            return Result.ok();
        }

        record.setDeletedAt(System.currentTimeMillis());
        likeMapper.updateById(record);
        return Result.ok();
    }

    @Transactional
    public Result<Void> unfavoriteFood(Long foodId) {
        if (foodMapper.selectById(foodId) == null) {
            return Result.fail(BizCode.FOOD_NOT_FOUND, "菜品不存在");
        }

        Long userId = currentUserId();
        if (relations != null) {
            relations.change(RelationKind.favorite, userId, foodId, null, false);
            return Result.ok();
        }
        if (managedDomainWriteGuard != null && managedDomainWriteGuard.relationManaged(
                RelationKind.favorite, userId, foodId, null)) return Result.fail(BizCode.DATA_SYNC_PENDING);
        foodFavoriteMapper.delete(new LambdaQueryWrapper<FoodFavorite>()
                .eq(FoodFavorite::getAccountId, userId)
                .eq(FoodFavorite::getFoodId, foodId));
        return Result.ok();
    }

    public Result<PageResult<FoodVO>> getMyFavoriteFoods(Long userId, Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        int offset = (page - 1) * pageSize;
        List<Food> foods = foodFavoriteMapper.selectFavoriteFoodsByAccountId(userId, offset, pageSize);
        Long total = foodFavoriteMapper.countFavoritesByAccountId(userId);

        List<FoodVO> items = foods.stream().map(this::toVO).toList();
        fillFoodViews(items, userId);
        return Result.ok(PageResult.of(items, page.longValue(), pageSize.longValue(), total));
    }

    private void fillFoodViews(List<FoodVO> items, Long currentUserId) {
        // 游客不返回上传者 ID，避免枚举用户。
        if (currentUserId == null && items != null) items.forEach(item -> item.setAccountId(null));
        foodTagService.fillFoodTagViews(items, currentUserId);
        fillLikedState(items, currentUserId);
        fillFavoriteState(items, currentUserId);
    }

    private void fillLikedState(List<FoodVO> items, Long currentUserId) {
        if (items == null || items.isEmpty()) {
            return;
        }
        if (currentUserId == null) {
            items.forEach(item -> item.setIsLiked(false));
            return;
        }
        List<Long> foodIds = items.stream().map(FoodVO::getId).toList();
        List<Long> likedIds = likeMapper.selectLikedTargetIds(currentUserId, "food", foodIds);
        Set<Long> likedFoodIds = likedIds == null ? Collections.emptySet() : new HashSet<>(likedIds);
        for (FoodVO item : items) {
            item.setIsLiked(likedFoodIds.contains(item.getId()));
        }
    }

    private void fillFavoriteState(List<FoodVO> items, Long currentUserId) {
        if (items == null || items.isEmpty()) {
            return;
        }
        if (currentUserId == null) {
            items.forEach(item -> item.setIsFavorite(false));
            return;
        }
        List<Long> foodIds = items.stream().map(FoodVO::getId).toList();
        List<Long> favoriteIds = foodFavoriteMapper.selectFavoriteFoodIds(currentUserId, foodIds);
        Set<Long> favoriteFoodIds = favoriteIds == null ? Collections.emptySet() : new HashSet<>(favoriteIds);
        for (FoodVO item : items) {
            item.setIsFavorite(favoriteFoodIds.contains(item.getId()));
        }
    }

    protected Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    /** 游客可访问的读接口用，未登录时为 null。 */
    protected Long currentUserIdOrNull() {
        return LoginUtil.currentUserIdOrNull();
    }
}
