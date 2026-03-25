package you.v50to.eatwhat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import you.v50to.eatwhat.data.dto.AddCustomFoodTagsDTO;
import you.v50to.eatwhat.data.dto.AddSystemFoodTagsDTO;
import you.v50to.eatwhat.data.dto.CreateSystemFoodTagDTO;
import you.v50to.eatwhat.data.dto.FoodCustomTagRow;
import you.v50to.eatwhat.data.dto.FoodSystemTagAggregateRow;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.data.po.FoodTag;
import you.v50to.eatwhat.data.po.FoodTagging;
import you.v50to.eatwhat.data.vo.FoodTagViewVO;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.FoodMapper;
import you.v50to.eatwhat.mapper.FoodTagMapper;
import you.v50to.eatwhat.mapper.FoodTaggingMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FoodTagServiceTest {

    private FoodMapper foodMapper;
    private FoodTagMapper foodTagMapper;
    private FoodTaggingMapper foodTaggingMapper;
    private FoodTagService foodTagService;

    @BeforeEach
    void setUp() {
        foodMapper = mock(FoodMapper.class);
        foodTagMapper = mock(FoodTagMapper.class);
        foodTaggingMapper = mock(FoodTaggingMapper.class);

        foodTagService = new FoodTagService();
        ReflectionTestUtils.setField(foodTagService, "foodMapper", foodMapper);
        ReflectionTestUtils.setField(foodTagService, "foodTagMapper", foodTagMapper);
        ReflectionTestUtils.setField(foodTagService, "foodTaggingMapper", foodTaggingMapper);
    }

    @Test
    void addSystemTagsShouldDeduplicateAndIgnoreRepeatedTagging() {
        when(foodMapper.selectById(10L)).thenReturn(new Food());

        FoodTag spicy = new FoodTag();
        spicy.setId(1L);
        spicy.setTagType("system");
        FoodTag sweet = new FoodTag();
        sweet.setId(2L);
        sweet.setTagType("system");
        when(foodTagMapper.selectSystemTagsByIds(List.of(1L, 2L))).thenReturn(List.of(spicy, sweet));
        doThrow(new DuplicateKeyException("duplicate"))
                .when(foodTaggingMapper)
                .insert(any(FoodTagging.class));

        AddSystemFoodTagsDTO dto = new AddSystemFoodTagsDTO();
        dto.setTagIds(List.of(1L, 1L, 2L));

        Result<Void> result = foodTagService.addSystemTagsToFood(10L, 99L, dto);

        assertEquals(0, result.getCode());
        verify(foodTagMapper).selectSystemTagsByIds(List.of(1L, 2L));
        verify(foodTaggingMapper, times(2)).insert(any(FoodTagging.class));
    }

    @Test
    void addCustomTagsShouldReuseExistingDefinitionAndCreateMissingOne() {
        when(foodMapper.selectById(10L)).thenReturn(new Food());

        FoodTag existing = new FoodTag();
        existing.setId(11L);
        existing.setName("Spicy");
        existing.setTagType("custom");
        existing.setOwnerId(99L);
        existing.setNormalizedName("spicy");
        when(foodTagMapper.selectCustomTagsByOwnerAndNormalizedNames(eq(99L), anyList()))
                .thenReturn(List.of(existing));
        doAnswer(invocation -> {
            FoodTag created = invocation.getArgument(0);
            created.setId(12L);
            return 1;
        }).when(foodTagMapper).insert(any(FoodTag.class));

        AddCustomFoodTagsDTO dto = new AddCustomFoodTagsDTO();
        dto.setNames(List.of("  Spicy ", "Chef Special"));

        Result<Void> result = foodTagService.addCustomTagsToFood(10L, 99L, dto);

        assertEquals(0, result.getCode());

        ArgumentCaptor<FoodTag> tagCaptor = ArgumentCaptor.forClass(FoodTag.class);
        verify(foodTagMapper).insert(tagCaptor.capture());
        assertEquals("Chef Special", tagCaptor.getValue().getName());
        assertEquals("chef special", tagCaptor.getValue().getNormalizedName());

        ArgumentCaptor<FoodTagging> taggingCaptor = ArgumentCaptor.forClass(FoodTagging.class);
        verify(foodTaggingMapper, times(2)).insert(taggingCaptor.capture());
        List<FoodTagging> taggings = taggingCaptor.getAllValues();
        assertEquals(List.of(11L, 12L), taggings.stream().map(FoodTagging::getTagId).toList());
    }

    @Test
    void getFoodTagViewShouldExposeSystemTagsAndOnlyMyCustomTags() {
        when(foodMapper.selectById(10L)).thenReturn(new Food());

        FoodSystemTagAggregateRow row = new FoodSystemTagAggregateRow();
        row.setFoodId(10L);
        row.setTagId(1L);
        row.setTagName("辣");
        row.setTagCount(2L);
        row.setTaggedByMe(true);
        when(foodTaggingMapper.aggregateSystemTagsByFoodIds(List.of(10L), 99L)).thenReturn(List.of(row));

        FoodCustomTagRow customRow = new FoodCustomTagRow();
        customRow.setFoodId(10L);
        customRow.setTagId(7L);
        customRow.setTagName("我的私藏");
        when(foodTaggingMapper.selectMyCustomTagsByFoodIds(List.of(10L), 99L)).thenReturn(List.of(customRow));

        Result<FoodTagViewVO> result = foodTagService.getFoodTagView(10L, 99L);

        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().getSystemTags().size());
        assertEquals(2L, result.getData().getSystemTags().getFirst().getCount());
        assertTrue(result.getData().getSystemTags().getFirst().getTaggedByMe());
        assertEquals(1, result.getData().getMyCustomTags().size());
        assertEquals("我的私藏", result.getData().getMyCustomTags().getFirst().getName());
    }

    @Test
    void createSystemTagShouldReturnFailOnDuplicateName() {
        doThrow(new DuplicateKeyException("duplicate")).when(foodTagMapper).insert(any(FoodTag.class));

        CreateSystemFoodTagDTO dto = new CreateSystemFoodTagDTO();
        dto.setName("辣");

        Result<?> result = foodTagService.createSystemTag(dto);

        assertFalse(result.getCode() == 0);
        assertTrue(result.getMsg().contains("系统标签已存在"));
    }
}
