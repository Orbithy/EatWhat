package you.v50to.eatwhat.integration;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.dto.*;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.MealTime;
import you.v50to.eatwhat.data.enums.StageColor;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.mapper.*;
import you.v50to.eatwhat.service.*;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real committed transactions: IDs, initial tags, rollback and owner checks. Test-owned database only. */
class FoodPublishingContractIT extends you.v50to.eatwhat.support.MvcContractSupport {
    static GenericContainer<?> postgres;
    JdbcTemplate jdbc;
    FoodService service;
    RestaurantService restaurants;
    FoodMapper mapper;
    FoodTagService tags;
    FileLifecycleService files;
    long me, other, restaurant, systemTag;
    SiteProperties site;

    @BeforeAll static void start() { postgres = IsolatedContainers.startedPostgresWithInspectRetry("food-publishing"); }
    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }

    @BeforeEach void setup() throws Exception {
        site = new SiteProperties(); site.setMode(SiteProperties.Mode.LEGACY);
        var db = EventFoundationTestSupport.createDatabase(postgres, site);
        jdbc = db.runtime();
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        for (Class<?> type : List.of(FoodMapper.class, RestaurantMapper.class, FoodTagMapper.class, FoodTaggingMapper.class))
            config.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(db.dataSource()); factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(FoodMapper.class);
        me = jdbc.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES ('author','synthetic') RETURNING id", Long.class);
        other = jdbc.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES ('other','synthetic') RETURNING id", Long.class);
        tags = new FoodTagService();
        ReflectionTestUtils.setField(tags, "foodMapper", mapper);
        ReflectionTestUtils.setField(tags, "foodTagMapper", session.getMapper(FoodTagMapper.class));
        ReflectionTestUtils.setField(tags, "foodTaggingMapper", session.getMapper(FoodTaggingMapper.class));
        ReflectionTestUtils.setField(tags, "jdbc", jdbc);
        var target = new FoodService() { @Override protected Long currentUserId() { return me; } };
        ReflectionTestUtils.setField(target, "foodMapper", mapper);
        ReflectionTestUtils.setField(target, "restaurantMapper", session.getMapper(RestaurantMapper.class));
        ReflectionTestUtils.setField(target, "foodTagService", tags);
        ReflectionTestUtils.setField(target, "siteProperties", site);
        files = mock(FileLifecycleService.class);
        ReflectionTestUtils.setField(target, "fileLifecycleService", files);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(db.transactionManager(), new AnnotationTransactionAttributeSource()));
        service = (FoodService) proxy.getProxy();
        var restaurantTarget = new RestaurantService();
        ReflectionTestUtils.setField(restaurantTarget, "restaurantMapper", session.getMapper(RestaurantMapper.class));
        ReflectionTestUtils.setField(restaurantTarget, "objectStorageService", mock(ObjectStorageService.class));
        var restaurantProxy = new ProxyFactory(restaurantTarget); restaurantProxy.setProxyTargetClass(true);
        restaurantProxy.addAdvice(new TransactionInterceptor(db.transactionManager(), new AnnotationTransactionAttributeSource()));
        restaurants = (RestaurantService) restaurantProxy.getProxy();
        bindToken(loginAs(me, "verified"));
        {
            var dto = new RestaurantDTO(); dto.setName("New restaurant"); dto.setGcjLng(117D); dto.setGcjLat(36D);
            var created = restaurants.addRestaurant(dto);
            assertEquals(0, created.getCode()); assertNotNull(created.getData().id());
            restaurant = created.getData().id();
        }
        systemTag = jdbc.queryForObject("INSERT INTO food_tags(name,normalized_name,tag_type) VALUES ('实惠','实惠','system') RETURNING id", Long.class);
    }

    CreateFoodDTO dto(String name) {
        var dto = new CreateFoodDTO(); dto.setRestaurantId(restaurant); dto.setName(name);
        dto.setPictureUrl(List.of("foods/test/key")); dto.setSystemTagIds(List.of(systemTag, systemTag));
        dto.setMyCustomTagNames(List.of(" 想吃 ", "想吃")); return dto;
    }

    @Test void returnsPersistedIdAndSavesInitialTagsOnce() {
        var result = service.uploadFood(dto("午餐"));
        assertEquals(0, result.getCode()); long id = result.getData().id();
        Food stored = mapper.selectById(id);
        assertEquals("午餐", stored.getName()); assertEquals(restaurant, stored.getRestaurantId());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM food_taggings WHERE food_id=?", Integer.class, id));
        assertEquals(me, jdbc.queryForObject("SELECT owner_id FROM food_tags WHERE normalized_name='想吃'", Long.class));
    }

    @Test void invalidInitialTagRollsBackCreatedFoodAndDoesNotReturnAnId() {
        var dto = dto("必须回滚"); dto.setSystemTagIds(List.of(Long.MAX_VALUE));
        assertEquals(BizCode.PARAM_INVALID, assertThrows(BizException.class, () -> service.uploadFood(dto)).getBizCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM foods WHERE name='必须回滚'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM food_tags WHERE normalized_name='想吃'", Integer.class));
    }

    @Test void displayFieldsArePersistedReadBackAndEditable() {
        var dto = dto("带舞台色的菜");
        dto.setMealTimes(List.of(MealTime.NIGHT, MealTime.LUNCH, MealTime.NIGHT));
        dto.setStageColor(StageColor.CHILI); dto.setStageAlternates(List.of(StageColor.MANGO, StageColor.SOY));
        dto.setCoverCrop(new CoverCropDTO(new BigDecimal("0.4"), new BigDecimal("0.55"), BigDecimal.ONE));
        dto.setRemoveBackground(true);
        long id = service.uploadFood(dto).getData().id();

        Food stored = mapper.selectById(id);
        assertArrayEquals(new String[]{"lunch", "night"}, stored.getMealTimes());
        assertEquals("chili", stored.getStageColor());
        assertArrayEquals(new String[]{"mango", "soy"}, stored.getStageAlternates());
        assertEquals(0, new BigDecimal("0.55").compareTo(stored.getCoverCenterY()));
        assertTrue(stored.getRemoveBackground());
        // Custom @Results mappings must carry the array columns too.
        Food listed = mapper.selectByAccountId(me, 0, 10).stream().filter(f -> f.getId() == id).findFirst().orElseThrow();
        assertArrayEquals(new String[]{"lunch", "night"}, listed.getMealTimes());
        assertArrayEquals(new String[]{"mango", "soy"}, listed.getStageAlternates());

        var edit = new EditFoodDTO();
        edit.setStageColor(StageColor.MANGO); edit.setStageAlternates(List.of(StageColor.CHILI, StageColor.SOY));
        edit.setMealTimes(List.of(MealTime.BREAKFAST));
        assertEquals(0, service.editMyFood(id, edit).getCode());
        Food edited = mapper.selectById(id);
        assertEquals("mango", edited.getStageColor());
        assertArrayEquals(new String[]{"breakfast"}, edited.getMealTimes());
        assertTrue(edited.getRemoveBackground(), "fields that were not sent stay unchanged");

        var unpaired = new EditFoodDTO(); unpaired.setStageColor(StageColor.SOY);
        assertEquals(BizCode.PARAM_INVALID.getCode(), service.editMyFood(id, unpaired).getCode());
        var repeated = new EditFoodDTO(); repeated.setStageColor(StageColor.SOY);
        repeated.setStageAlternates(List.of(StageColor.SOY, StageColor.MANGO));
        assertEquals(BizCode.PARAM_INVALID.getCode(), service.editMyFood(id, repeated).getCode());
        assertEquals("mango", mapper.selectById(id).getStageColor());
    }

    @Test void legacyPublishWithoutDisplayFieldsUsesDatabaseDefaults() {
        long id = service.uploadFood(dto("旧客户端的菜")).getData().id();
        Food stored = mapper.selectById(id);
        assertArrayEquals(new String[0], stored.getMealTimes());
        assertNull(stored.getStageColor()); assertNull(stored.getCoverCenterX());
        assertFalse(stored.getRemoveBackground());
    }

    @Test void databaseRejectsInconsistentDisplayFields() {
        long id = service.uploadFood(dto("约束")).getData().id();
        for (String update : List.of(
                "UPDATE foods SET meal_times=ARRAY['brunch'] WHERE id=?",
                "UPDATE foods SET stage_color='chili' WHERE id=?",
                "UPDATE foods SET stage_color='purple',stage_alternates=ARRAY['mango','soy'] WHERE id=?",
                "UPDATE foods SET cover_center_x=0.5 WHERE id=?",
                "UPDATE foods SET cover_center_x=0.5,cover_center_y=0.5,cover_diameter=0 WHERE id=?"))
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(update, id), update);
    }

    @Test void authorCanEditAndDeleteOwnFoodButCannotManageOthersOrWriteFromExternalSite() {
        long id = service.uploadFood(dto("自己的菜")).getData().id();
        var edit = new EditFoodDTO(); edit.setName("修改后的菜");
        assertEquals(0, service.editMyFood(id, edit).getCode());
        assertEquals("修改后的菜", mapper.selectById(id).getName());
        jdbc.update("UPDATE foods SET account_id=? WHERE id=?", other, id);
        assertEquals(BizCode.NO_PERMISSION.getCode(), service.editMyFood(id, edit).getCode());
        assertEquals(BizCode.NO_PERMISSION.getCode(), service.deleteMyFood(id).getCode());
        assertNotNull(mapper.selectById(id));
        jdbc.update("UPDATE foods SET account_id=? WHERE id=?", me, id);
        site.setMode(SiteProperties.Mode.EXTERNAL);
        assertEquals(BizCode.NO_PERMISSION.getCode(), service.deleteMyFood(id).getCode());
        site.setMode(SiteProperties.Mode.LEGACY);
        assertEquals(0, service.deleteMyFood(id).getCode()); assertNull(mapper.selectById(id));
        verify(files).releaseReferences("food", Long.toString(id), null, "ENTITY_DELETED");
    }
}
