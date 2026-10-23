package you.v50to.eatwhat.integration;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.FileLifecycleProperties;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.data.dto.CreateActivityPostDTO;
import you.v50to.eatwhat.data.dto.CreateFoodDTO;
import you.v50to.eatwhat.data.dto.EditFoodDTO;
import you.v50to.eatwhat.data.dto.EditRestaurantDTO;
import you.v50to.eatwhat.data.dto.RestaurantDTO;
import you.v50to.eatwhat.data.dto.UpdateUserInfoDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.data.po.Activity;
import you.v50to.eatwhat.data.po.ActivityPost;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.data.po.UserInfo;
import you.v50to.eatwhat.mapper.ActivityMapper;
import you.v50to.eatwhat.mapper.ActivityPostMapper;
import you.v50to.eatwhat.mapper.FoodMapper;
import you.v50to.eatwhat.mapper.RestaurantMapper;
import you.v50to.eatwhat.mapper.UserInfoMapper;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.service.ActivityService;
import you.v50to.eatwhat.service.FoodService;
import you.v50to.eatwhat.service.RestaurantService;
import you.v50to.eatwhat.service.UserService;
import you.v50to.eatwhat.service.storage.FileAliasResolver;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.service.storage.FileObjectStorage;
import you.v50to.eatwhat.support.MvcContractSupport;
import you.v50to.eatwhat.utils.LocationValidationUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Business write paths release their file references inside the business transaction: deletes, key replacement,
 * restaurant-to-food cascades and avatar changes, against a real PostgreSQL schema (V29 view and tombstone).
 */
@ResourceLock("sa-token-global")
class FileReferenceReleaseBusinessIT extends MvcContractSupport {
    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4};

    private static GenericContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager tx;
    private static FakeObjectStorage objects;
    private static FileLifecycleService files;
    private static FileAliasResolver aliases;

    @BeforeAll
    static void start() {
        postgres = IsolatedContainers.postgres("file-release-business");
        postgres.start();
        String database = "release_business_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE),
                IsolatedContainers.USER, IsolatedContainers.PASSWORD)).execute("CREATE DATABASE " + database + " TEMPLATE template0");
        String url = IsolatedContainers.jdbcUrl(postgres, database);
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        tx = new DataSourceTransactionManager(dataSource);
        objects = new FakeObjectStorage();
        S3StorageProperties storage = new S3StorageProperties();
        storage.setMaxFileSizeBytes(1024);
        storage.setAllowedContentTypes(List.of("image/png", "image/jpeg"));
        FileLifecycleProperties lifecycle = new FileLifecycleProperties();
        lifecycle.setLifecycleEnabled(true);
        files = new FileLifecycleService(jdbc, objects, storage, lifecycle, tx);
        aliases = new FileAliasResolver(jdbc);
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void deletingAFoodReleasesItsPublicReferenceAndLeavesATombstone() {
        long owner = user("delete-food-owner");
        long restaurantId = restaurant(owner);
        FoodService foodService = foodService(owner);
        try {
            bindToken(loginAs(owner));
            String key = upload(owner, UploadBiz.FOODS);
            assertEquals(BizCode.SUCCESS.getCode(), foodService.uploadFood(foodDto(restaurantId, "Delete me", key)).getCode());
            long foodId = foodId("Delete me");
            assertEquals(1, live(key));
            assertNotNull(aliases.resolve(key).readableObjectKey(), "A live PUBLIC reference is readable");

            assertEquals(BizCode.SUCCESS.getCode(), foodService.deleteFood(foodId).getCode());

            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.foods WHERE id=?", Integer.class, foodId));
            assertEquals(0, live(key), "file_reference_live no longer contains the key");
            assertNull(aliases.resolve(key).readableObjectKey(), "A released PUBLIC key is no longer readable");
            assertNull(files.resolveAlias(key));
            assertEquals(List.of("RELEASE:ENTITY_DELETED"), events(key));
            Map<String, Object> tombstone = tombstone(key);
            assertEquals(Boolean.TRUE, tombstone.get("had_public_ref"));
            assertNotNull(tombstone.get("unreferenced_since"));
            assertNull(tombstone.get("revived_at"));
        } finally {
            cn.dev33.satoken.stp.StpUtil.logout();
        }
    }

    @Test
    void deleteInsideARolledBackBusinessTransactionLeavesTheReferenceLive() {
        long owner = user("rollback-owner");
        long restaurantId = restaurant(owner);
        FoodService foodService = foodService(owner);
        try {
            bindToken(loginAs(owner));
            String key = upload(owner, UploadBiz.FOODS);
            assertEquals(BizCode.SUCCESS.getCode(), foodService.uploadFood(foodDto(restaurantId, "Rollback food", key)).getCode());
            long foodId = foodId("Rollback food");

            assertThrows(IllegalStateException.class, () -> new TransactionTemplate(tx).executeWithoutResult(status -> {
                assertEquals(BizCode.SUCCESS.getCode(), foodService.deleteFood(foodId).getCode());
                assertEquals(0, live(key), "Released inside the transaction");
                throw new IllegalStateException("synthetic business failure after the delete");
            }));

            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public.foods WHERE id=?", Integer.class, foodId));
            assertEquals(1, live(key), "The release rolled back with the business transaction");
            assertEquals(List.of(), events(key));
            assertNotNull(aliases.resolve(key).readableObjectKey());
        } finally {
            cn.dev33.satoken.stp.StpUtil.logout();
        }
    }

    @Test
    void reAddingAReleasedKeyReReferencesItAndClearsTheTombstone() {
        long owner = user("re-add-owner");
        long restaurantId = restaurant(owner);
        FoodService foodService = foodService(owner);
        try {
            bindToken(loginAs(owner));
            String key = upload(owner, UploadBiz.FOODS);
            assertEquals(BizCode.SUCCESS.getCode(), foodService.uploadFood(foodDto(restaurantId, "First owner", key)).getCode());
            assertEquals(BizCode.SUCCESS.getCode(), foodService.deleteFood(foodId("First owner")).getCode());
            assertEquals(0, live(key));
            assertNotNull(tombstone(key).get("unreferenced_since"));

            assertEquals(BizCode.SUCCESS.getCode(), foodService.uploadFood(foodDto(restaurantId, "Second owner", key)).getCode());

            assertEquals(1, live(key), "The key is referenced again by the new food");
            assertNotNull(aliases.resolve(key).readableObjectKey());
            Map<String, Object> tombstone = tombstone(key);
            assertNull(tombstone.get("unreferenced_since"), "A live reference clears the unreferenced marker");
            assertNotNull(tombstone.get("revived_at"));
            assertEquals(Boolean.TRUE, tombstone.get("had_public_ref"));
        } finally {
            cn.dev33.satoken.stp.StpUtil.logout();
        }
    }

    @Test
    void editingAFoodReleasesOnlyTheReplacedPictureKeys() {
        long owner = user("edit-food-owner");
        long restaurantId = restaurant(owner);
        FoodService foodService = foodService(owner);
        try {
            bindToken(loginAs(owner));
            String kept = upload(owner, UploadBiz.FOODS);
            String replaced = upload(owner, UploadBiz.FOODS);
            String added = upload(owner, UploadBiz.FOODS);
            assertEquals(BizCode.SUCCESS.getCode(), foodService.uploadFood(foodDto(restaurantId, "Edit me", kept, replaced)).getCode());
            long foodId = foodId("Edit me");

            EditFoodDTO edit = new EditFoodDTO();
            edit.setPictureUrl(List.of(kept, added));
            assertEquals(BizCode.SUCCESS.getCode(), foodService.editFood(foodId, edit).getCode());

            assertEquals(1, live(kept));
            assertEquals(List.of(), events(kept), "A key that stays is never released");
            assertEquals(1, live(added));
            assertEquals(0, live(replaced));
            assertEquals(List.of("RELEASE:KEY_REPLACED"), events(replaced));
            assertNull(aliases.resolve(replaced).readableObjectKey());
            assertNotNull(aliases.resolve(kept).readableObjectKey());

            EditFoodDTO sameKeys = new EditFoodDTO();
            sameKeys.setPictureUrl(List.of(added, kept));
            assertEquals(BizCode.SUCCESS.getCode(), foodService.editFood(foodId, sameKeys).getCode());
            assertEquals(List.of(), events(kept));
            assertEquals(List.of(), events(added));
        } finally {
            cn.dev33.satoken.stp.StpUtil.logout();
        }
    }

    @Test
    void deletingARestaurantReleasesItsOwnAndItsCascadedFoodsReferences() {
        long owner = user("delete-restaurant-owner");
        RestaurantService restaurantService = restaurantService(owner);
        FoodService foodService = foodService(owner);
        try {
            bindToken(loginAs(owner));
            String restaurantKey = upload(owner, UploadBiz.RESTAURANTS);
            RestaurantDTO restaurantDto = new RestaurantDTO();
            restaurantDto.setName("Cascade restaurant");
            restaurantDto.setAddress("Synthetic address");
            restaurantDto.setGcjLng(120.0);
            restaurantDto.setGcjLat(36.0);
            restaurantDto.setPictureUrl(List.of(restaurantKey));
            assertEquals(BizCode.SUCCESS.getCode(), restaurantService.addRestaurant(restaurantDto).getCode());
            long restaurantId = jdbc.queryForObject("SELECT id FROM public.restaurant WHERE name='Cascade restaurant'", Long.class);

            String firstFoodKey = upload(owner, UploadBiz.FOODS);
            String secondFoodKey = upload(owner, UploadBiz.FOODS);
            String otherFoodKey = upload(owner, UploadBiz.FOODS);
            assertEquals(BizCode.SUCCESS.getCode(), foodService.uploadFood(foodDto(restaurantId, "Cascade food 1", firstFoodKey)).getCode());
            assertEquals(BizCode.SUCCESS.getCode(), foodService.uploadFood(foodDto(restaurantId, "Cascade food 2", secondFoodKey)).getCode());
            long otherRestaurantId = restaurant(owner);
            assertEquals(BizCode.SUCCESS.getCode(), foodService.uploadFood(foodDto(otherRestaurantId, "Unrelated food", otherFoodKey)).getCode());

            restaurantUnderDelete = restaurantId;
            assertEquals(BizCode.SUCCESS.getCode(), restaurantService.deleteRestaurant(restaurantId).getCode());

            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.foods WHERE restaurant_id=?", Integer.class, restaurantId),
                    "PostgreSQL cascaded the foods away without any service call");
            assertEquals(0, live(restaurantKey));
            assertEquals(List.of("RELEASE:ENTITY_DELETED"), events(restaurantKey));
            assertEquals(0, live(firstFoodKey));
            assertEquals(0, live(secondFoodKey));
            assertEquals(List.of("RELEASE:PARENT_CASCADE"), events(firstFoodKey));
            assertEquals(List.of("RELEASE:PARENT_CASCADE"), events(secondFoodKey));
            assertNull(aliases.resolve(firstFoodKey).readableObjectKey());
            assertNull(aliases.resolve(restaurantKey).readableObjectKey());
            assertEquals(Boolean.TRUE, tombstone(firstFoodKey).get("had_public_ref"));
            assertEquals(1, live(otherFoodKey), "A food of another restaurant is untouched");
            assertEquals(List.of(), events(otherFoodKey));
        } finally {
            cn.dev33.satoken.stp.StpUtil.logout();
        }
    }

    @Test
    void editingARestaurantReleasesOnlyTheReplacedPictureKeys() {
        long owner = user("edit-restaurant-owner");
        RestaurantService restaurantService = restaurantService(owner);
        try {
            bindToken(loginAs(owner));
            String kept = upload(owner, UploadBiz.RESTAURANTS);
            String replaced = upload(owner, UploadBiz.RESTAURANTS);
            String added = upload(owner, UploadBiz.RESTAURANTS);
            RestaurantDTO restaurantDto = new RestaurantDTO();
            restaurantDto.setName("Edit restaurant");
            restaurantDto.setAddress("Synthetic address");
            restaurantDto.setGcjLng(120.0);
            restaurantDto.setGcjLat(36.0);
            restaurantDto.setPictureUrl(List.of(kept, replaced));
            assertEquals(BizCode.SUCCESS.getCode(), restaurantService.addRestaurant(restaurantDto).getCode());
            long restaurantId = jdbc.queryForObject("SELECT id FROM public.restaurant WHERE name='Edit restaurant'", Long.class);

            EditRestaurantDTO edit = new EditRestaurantDTO();
            edit.setPictureUrl(List.of(added, kept));
            assertEquals(BizCode.SUCCESS.getCode(), restaurantService.editRestaurant(restaurantId, edit).getCode());

            assertEquals(1, live(kept));
            assertEquals(List.of(), events(kept));
            assertEquals(1, live(added));
            assertEquals(0, live(replaced));
            assertEquals(List.of("RELEASE:KEY_REPLACED"), events(replaced));
        } finally {
            cn.dev33.satoken.stp.StpUtil.logout();
        }
    }

    @Test
    void replacingAnAvatarReleasesTheOldKeyAndReturningToItRestoresIt() {
        long owner = user("avatar-owner");
        UserService userService = userService(owner);
        try {
            bindToken(loginAs(owner));
            String first = upload(owner, UploadBiz.AVATAR);
            String second = upload(owner, UploadBiz.AVATAR);

            assertEquals(BizCode.SUCCESS.getCode(), userService.updateUserInfo(avatar(first)).getCode());
            assertEquals(1, live(first));
            assertEquals(List.of(), events(first), "The first avatar has no older key to release");

            assertEquals(BizCode.SUCCESS.getCode(), userService.updateUserInfo(avatar(first)).getCode());
            assertEquals(List.of(), events(first), "Submitting the unchanged avatar releases nothing");

            assertEquals(BizCode.SUCCESS.getCode(), userService.updateUserInfo(avatar(second)).getCode());
            assertEquals(0, live(first));
            assertEquals(List.of("RELEASE:KEY_REPLACED"), events(first));
            assertEquals(1, live(second));
            assertEquals(List.of(), events(second));
            assertNull(aliases.resolve(first).readableObjectKey());
            assertNotNull(aliases.resolve(second).readableObjectKey());
            assertNotNull(tombstone(first).get("unreferenced_since"));

            // Going back to the first key re-references it (RESTORE) and releases the second one.
            assertEquals(BizCode.SUCCESS.getCode(), userService.updateUserInfo(avatar(first)).getCode());
            assertEquals(1, live(first));
            assertEquals(List.of("RELEASE:KEY_REPLACED", "RESTORE:ADMIN"), events(first));
            assertNotNull(aliases.resolve(first).readableObjectKey());
            assertNull(tombstone(first).get("unreferenced_since"));
            assertNotNull(tombstone(first).get("revived_at"));
            assertEquals(0, live(second));
            assertEquals(List.of("RELEASE:KEY_REPLACED"), events(second));
        } finally {
            cn.dev33.satoken.stp.StpUtil.logout();
        }
    }

    @Test
    void softDeletingActivityPostsReleasesTheirReferences() {
        long owner = user("activity-owner");
        ActivityService activity = activityService();
        try {
            bindToken(loginAs(owner));
            String firstKey = upload(owner, UploadBiz.ACTIVITY);
            CreateActivityPostDTO first = new CreateActivityPostDTO();
            first.setDescription("Activity release first");
            first.setPictureUrl(List.of(firstKey));
            assertEquals(BizCode.SUCCESS.getCode(), activity.publishPost("release-test", first).getCode());
            long firstId = jdbc.queryForObject("SELECT id FROM public.activity_posts WHERE description='Activity release first'", Long.class);

            String secondKey = upload(owner, UploadBiz.ACTIVITY);
            CreateActivityPostDTO second = new CreateActivityPostDTO();
            second.setDescription("Activity release second");
            second.setPictureUrl(List.of(secondKey));
            assertEquals(BizCode.SUCCESS.getCode(), activity.publishPost("release-test", second).getCode());
            long secondId = jdbc.queryForObject("SELECT id FROM public.activity_posts WHERE description='Activity release second'", Long.class);
            assertEquals(1, live(firstKey));
            assertEquals(1, live(secondKey));

            assertEquals(BizCode.SUCCESS.getCode(), activity.deletePost(firstId).getCode());
            assertEquals(0, live(firstKey));
            assertEquals(1, live(secondKey), "Deleting one post does not touch another post");
            assertEquals(List.of("RELEASE:ENTITY_DELETED"), events(firstKey));
            assertNull(aliases.resolve(firstKey).readableObjectKey());

            assertEquals(BizCode.SUCCESS.getCode(), activity.deletePost(secondId).getCode());
            assertEquals(0, live(secondKey));
            assertEquals(List.of("RELEASE:ENTITY_DELETED"), events(secondKey));
            assertEquals(Boolean.TRUE, tombstone(secondKey).get("had_public_ref"));

            assertFalse(activity.deletePost(firstId).getCode() == BizCode.SUCCESS.getCode(), "Already deleted");
            assertEquals(List.of("RELEASE:ENTITY_DELETED"), events(firstKey), "A repeated delete adds no event");
        } finally {
            cn.dev33.satoken.stp.StpUtil.logout();
        }
    }

    // ---- service wiring: real services and a real lifecycle service, mappers backed by the same database ----

    private FoodService foodService(long owner) {
        FoodMapper foodMapper = mock(FoodMapper.class);
        RestaurantMapper restaurantMapper = restaurantMapper();
        when(foodMapper.insert(any(Food.class))).thenAnswer(invocation -> {
            Food food = invocation.getArgument(0);
            Long id = jdbc.queryForObject("""
                    INSERT INTO public.foods(account_id,restaurant_id,name,description,picture_url)
                    VALUES (?,?,?,?,?::text[]) RETURNING id
                    """, Long.class, food.getAccountId(), food.getRestaurantId(), food.getName(),
                    food.getDescription(), pgArray(food.getPictureUrl()));
            food.setId(id);
            return 1;
        });
        when(foodMapper.selectById(any(Long.class))).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            List<Food> rows = jdbc.query("SELECT id,account_id,restaurant_id,name,picture_url FROM public.foods WHERE id=?",
                    (rs, row) -> {
                        Food food = new Food();
                        food.setId(rs.getLong("id"));
                        food.setAccountId(rs.getLong("account_id"));
                        food.setRestaurantId(rs.getLong("restaurant_id"));
                        food.setName(rs.getString("name"));
                        food.setPictureUrl((String[]) rs.getArray("picture_url").getArray());
                        return food;
                    }, id);
            return rows.isEmpty() ? null : rows.getFirst();
        });
        when(foodMapper.updateById(any(Food.class))).thenAnswer(invocation -> {
            Food food = invocation.getArgument(0);
            return jdbc.update("UPDATE public.foods SET name=?,picture_url=?::text[] WHERE id=?",
                    food.getName(), pgArray(food.getPictureUrl()), food.getId());
        });
        when(foodMapper.deleteById(any(Long.class))).thenAnswer(invocation ->
                jdbc.update("DELETE FROM public.foods WHERE id=?", (Long) invocation.getArgument(0)));
        FoodService target = new FoodService();
        ReflectionTestUtils.setField(target, "foodMapper", foodMapper);
        ReflectionTestUtils.setField(target, "restaurantMapper", restaurantMapper);
        ReflectionTestUtils.setField(target, "fileLifecycleService", files);
        return transactional(target);
    }

    private long restaurantUnderDelete;

    private RestaurantService restaurantService(long owner) {
        FoodMapper foodMapper = mock(FoodMapper.class);
        // The service asks for the foods of the restaurant being deleted; the test records which one that is.
        when(foodMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(invocation -> {
            long restaurantId = restaurantUnderDelete;
            List<Food> foods = new ArrayList<>();
            for (Long id : jdbc.queryForList("SELECT id FROM public.foods WHERE restaurant_id=? ORDER BY id", Long.class, restaurantId)) {
                Food food = new Food();
                food.setId(id);
                foods.add(food);
            }
            return foods;
        });
        RestaurantService target = new RestaurantService();
        ReflectionTestUtils.setField(target, "restaurantMapper", restaurantMapper());
        ReflectionTestUtils.setField(target, "foodMapper", foodMapper);
        ReflectionTestUtils.setField(target, "fileLifecycleService", files);
        return transactional(target);
    }

    private RestaurantMapper restaurantMapper() {
        RestaurantMapper restaurantMapper = mock(RestaurantMapper.class);
        when(restaurantMapper.insert(any(Restaurant.class))).thenAnswer(invocation -> {
            Restaurant restaurant = invocation.getArgument(0);
            Long id = jdbc.queryForObject("""
                    INSERT INTO public.restaurant(account_id,name,address,location,gcj_lng,gcj_lat,picture_url)
                    VALUES (?,?,?,ST_SetSRID(ST_MakePoint(120,36),4326)::geography,?,?,?::text[]) RETURNING id
                    """, Long.class, restaurant.getAccountId(), restaurant.getName(), restaurant.getAddress(),
                    restaurant.getGcjLng(), restaurant.getGcjLat(), pgArray(restaurant.getPictureUrl()));
            restaurant.setId(id);
            return 1;
        });
        when(restaurantMapper.selectById(any(Long.class))).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            List<Restaurant> rows = jdbc.query("SELECT id,account_id,name,picture_url FROM public.restaurant WHERE id=?",
                    (rs, row) -> {
                        Restaurant restaurant = new Restaurant();
                        restaurant.setId(rs.getLong("id"));
                        restaurant.setAccountId(rs.getLong("account_id"));
                        restaurant.setName(rs.getString("name"));
                        java.sql.Array pictures = rs.getArray("picture_url");
                        restaurant.setPictureUrl(pictures == null ? null : (String[]) pictures.getArray());
                        return restaurant;
                    }, id);
            return rows.isEmpty() ? null : rows.getFirst();
        });
        when(restaurantMapper.updateById(any(Restaurant.class))).thenAnswer(invocation -> {
            Restaurant restaurant = invocation.getArgument(0);
            return jdbc.update("UPDATE public.restaurant SET picture_url=?::text[] WHERE id=?",
                    pgArray(restaurant.getPictureUrl()), restaurant.getId());
        });
        // The database cascades the restaurant's foods (V4 ON DELETE CASCADE); no service hook is involved.
        when(restaurantMapper.deleteById(any(Long.class))).thenAnswer(invocation ->
                jdbc.update("DELETE FROM public.restaurant WHERE id=?", (Long) invocation.getArgument(0)));
        return restaurantMapper;
    }

    private UserService userService(long owner) {
        UserMapper userMapper = mock(UserMapper.class);
        when(userMapper.selectById(any(Long.class))).thenAnswer(invocation -> {
            User user = new User();
            user.setId(invocation.getArgument(0));
            user.setAvatar(jdbc.queryForObject("SELECT avatar FROM public.users WHERE id=?", String.class, (Long) invocation.getArgument(0)));
            return user;
        });
        when(userMapper.updateById(any(User.class))).thenAnswer(invocation -> {
            User update = invocation.getArgument(0);
            return jdbc.update("UPDATE public.users SET avatar=? WHERE id=?", update.getAvatar(), update.getId());
        });
        UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
        UserInfo info = new UserInfo();
        info.setId(owner);
        when(userInfoMapper.selectById(any(Long.class))).thenReturn(info);
        when(userInfoMapper.updateById(any(UserInfo.class))).thenReturn(1);
        UserService target = new UserService();
        ReflectionTestUtils.setField(target, "userMapper", userMapper);
        ReflectionTestUtils.setField(target, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(target, "fileLifecycleService", files);
        ReflectionTestUtils.setField(target, "locationValidationUtil", mock(LocationValidationUtil.class));
        return transactional(target);
    }

    private ActivityService activityService() {
        long activityId = jdbc.queryForObject("INSERT INTO public.activities(code,name,status) VALUES ('release-test','Release test','ONLINE') RETURNING id", Long.class);
        Activity definition = new Activity();
        definition.setId(activityId);
        definition.setCode("release-test");
        definition.setStatus("ONLINE");
        definition.setRequireLocation(false);
        definition.setMaxPictures(9);
        definition.setFieldSchema("[]");
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        when(activityMapper.selectVisibleIdByCode("release-test")).thenReturn(activityId);
        when(activityMapper.selectById(activityId)).thenReturn(definition);
        ActivityPostMapper postMapper = mock(ActivityPostMapper.class);
        when(postMapper.insert(any(ActivityPost.class))).thenAnswer(invocation -> {
            ActivityPost post = invocation.getArgument(0);
            post.setId(jdbc.queryForObject("INSERT INTO public.activity_posts(activity_id,account_id,description,picture_url) VALUES (?,?,?,?::text[]) RETURNING id",
                    Long.class, post.getActivityId(), post.getAccountId(), post.getDescription(), pgArray(post.getPictureUrl())));
            return 1;
        });
        when(postMapper.selectById(any(Long.class))).thenAnswer(invocation -> {
            Map<String, Object> row = jdbc.queryForMap("SELECT activity_id, account_id, deleted_at IS NOT NULL AS deleted FROM public.activity_posts WHERE id=?",
                    (Long) invocation.getArgument(0));
            ActivityPost post = new ActivityPost();
            post.setId(invocation.getArgument(0));
            post.setActivityId(((Number) row.get("activity_id")).longValue());
            post.setAccountId(((Number) row.get("account_id")).longValue());
            post.setDeletedAt(Boolean.TRUE.equals(row.get("deleted")) ? 1L : null);
            return post;
        });
        when(postMapper.updateById(any(ActivityPost.class))).thenAnswer(invocation ->
                jdbc.update("UPDATE public.activity_posts SET deleted_at=now() WHERE id=?", ((ActivityPost) invocation.getArgument(0)).getId()));
        ActivityService target = new ActivityService();
        ReflectionTestUtils.setField(target, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(target, "activityPostMapper", postMapper);
        ReflectionTestUtils.setField(target, "fileLifecycleService", files);
        ReflectionTestUtils.setField(target, "locationValidationUtil", mock(LocationValidationUtil.class));
        ReflectionTestUtils.setField(target, "redis", mock(StringRedisTemplate.class));
        ReflectionTestUtils.setField(target, "objectMapper", tools.jackson.databind.json.JsonMapper.builder().build());
        return transactional(target);
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    // ---- fixtures and assertions ----

    private static long user(String name) {
        return jdbc.queryForObject("INSERT INTO public.users(nick_name,password_hash) VALUES (?, 'synthetic') RETURNING id",
                Long.class, name + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static long restaurant(long owner) {
        return jdbc.queryForObject("""
                INSERT INTO public.restaurant(account_id,name,location,gcj_lng,gcj_lat)
                VALUES (?, 'Synthetic restaurant', ST_SetSRID(ST_MakePoint(120,36),4326)::geography,120,36) RETURNING id
                """, Long.class, owner);
    }

    /** Presigns and stages a fresh PNG; the business write (old-client flow) completes and references it. */
    private static String upload(long owner, UploadBiz biz) {
        byte[] bytes = pngBytes(UUID.randomUUID().toString());
        var upload = files.presign(owner, biz, "image.png", "image/png", (long) bytes.length);
        objects.stage(jdbc.queryForObject("SELECT staging_key FROM eatwhat_file.upload_intent WHERE logical_key=?",
                String.class, upload.key()), bytes, "image/png");
        return upload.key();
    }

    private static CreateFoodDTO foodDto(long restaurantId, String name, String... keys) {
        CreateFoodDTO dto = new CreateFoodDTO();
        dto.setRestaurantId(restaurantId);
        dto.setName(name);
        dto.setPictureUrl(List.of(keys));
        return dto;
    }

    private static UpdateUserInfoDTO avatar(String key) {
        UpdateUserInfoDTO dto = new UpdateUserInfoDTO();
        dto.setAvatar(key);
        return dto;
    }

    private static long foodId(String name) {
        return jdbc.queryForObject("SELECT id FROM public.foods WHERE name=?", Long.class, name);
    }

    private static int live(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference_live WHERE logical_key=?", Integer.class, key);
    }

    /** RELEASE/RESTORE events of a key, oldest first, as ACTION:REASON. */
    private static List<String> events(String key) {
        return jdbc.queryForList("SELECT action || ':' || reason FROM eatwhat_file.file_reference_event WHERE logical_key=? ORDER BY event_id",
                String.class, key);
    }

    private static Map<String, Object> tombstone(String key) {
        return jdbc.queryForMap("""
                SELECT t.unreferenced_since, t.had_public_ref, t.revived_at
                FROM eatwhat_file.file_tombstone t
                JOIN eatwhat_file.file_key_alias a ON a.file_id=t.file_id AND a.revision=t.revision
                WHERE a.logical_key=?
                """, key);
    }

    /** PostgreSQL array literal for a text[] parameter; business keys contain no quoting-sensitive characters. */
    private static String pgArray(String[] values) {
        return values == null ? "{}" : "{" + String.join(",", values) + "}";
    }

    private static byte[] pngBytes(String text) {
        byte[] suffix = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bytes = java.util.Arrays.copyOf(PNG, PNG.length + suffix.length);
        System.arraycopy(suffix, 0, bytes, PNG.length, suffix.length);
        return bytes;
    }

    private static final class FakeObjectStorage implements FileObjectStorage {
        private final Map<String, StoredObject> staging = new ConcurrentHashMap<>();
        private final Map<String, StoredObject> immutable = new ConcurrentHashMap<>();

        void stage(String key, byte[] bytes, String contentType) {
            staging.put(key, new StoredObject(bytes.clone(), contentType));
        }

        @Override public String presignStagingPut(String stagingKey, String contentType, long ttlSeconds) {
            return "https://objects.example.invalid/" + stagingKey;
        }

        @Override public FileObjectStorage.StagedMetadata head(String key) {
            StoredObject object = staging.get(key);
            if (object == null) throw new IllegalStateException("staging object missing");
            return new FileObjectStorage.StagedMetadata(object.bytes().length, object.contentType());
        }

        @Override public FileObjectStorage.StagedMetadata downloadToFile(String key, Path destination, long maxBytes) {
            StoredObject object = staging.get(key);
            if (object == null) throw new IllegalStateException("staging object missing");
            if (object.bytes().length > maxBytes) throw new IllegalArgumentException("object too large");
            try { Files.write(destination, object.bytes()); }
            catch (IOException e) { throw new IllegalStateException(e); }
            return new FileObjectStorage.StagedMetadata(object.bytes().length, object.contentType());
        }

        @Override public void putImmutableFile(String key, String contentType, Path source) {
            try { immutable.put(key, new StoredObject(Files.readAllBytes(source), contentType)); }
            catch (IOException e) { throw new IllegalStateException(e); }
        }
    }

    private record StoredObject(byte[] bytes, String contentType) { }
}
