package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import you.v50to.eatwhat.config.FileLifecycleProperties;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.data.dto.CreateFoodDTO;
import you.v50to.eatwhat.data.dto.EditFoodDTO;
import you.v50to.eatwhat.data.dto.CreateActivityPostDTO;
import you.v50to.eatwhat.data.dto.RestaurantDTO;
import you.v50to.eatwhat.data.dto.UpdateUserInfoDTO;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.data.po.UserInfo;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.mapper.FoodMapper;
import you.v50to.eatwhat.mapper.RestaurantMapper;
import you.v50to.eatwhat.mapper.ActivityMapper;
import you.v50to.eatwhat.mapper.ActivityPostMapper;
import you.v50to.eatwhat.mapper.UserInfoMapper;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.service.ActivityService;
import you.v50to.eatwhat.service.FoodService;
import you.v50to.eatwhat.service.RestaurantService;
import you.v50to.eatwhat.service.UserService;
import you.v50to.eatwhat.utils.LocationValidationUtil;
import you.v50to.eatwhat.service.storage.FileAliasResolver;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.service.storage.FileObjectStorage;
import you.v50to.eatwhat.service.storage.S3CompatibleStorageService;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Lifecycle state, authorization and recovery checks against isolated PostgreSQL and deterministic object storage. */
@ResourceLock("sa-token-global")
class FileLifecycleServiceIT extends MvcContractSupport {
    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4};

    @Test
    void lifecycleEnforcesOwnershipAndContentAndSurvivesRacesAndDatabaseFailure() throws Exception {
        try (var postgres = IsolatedContainers.postgres("file-lifecycle-service")) {
            postgres.start();
            String database = "file_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                    .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                    IsolatedContainers.USER, IsolatedContainers.PASSWORD);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            long owner = user(jdbc, "file-owner");
            long stranger = user(jdbc, "file-stranger");
            FakeObjectStorage objects = new FakeObjectStorage();
            S3StorageProperties storage = storageProperties();
            FileLifecycleProperties lifecycle = new FileLifecycleProperties();
            lifecycle.setLifecycleEnabled(true);
            lifecycle.setIntentTtlSeconds(900);
            lifecycle.setClaimLeaseSeconds(120);
            var tx = new DataSourceTransactionManager(dataSource);
            FileLifecycleService service = new FileLifecycleService(jdbc, objects, storage, lifecycle, tx);

            var upload = service.presign(owner, UploadBiz.FOODS, "dish.png", "image/png", (long) PNG.length);
            long intentExpiry = jdbc.queryForObject("SELECT extract(epoch from expires_at)*1000 FROM eatwhat_file.upload_intent WHERE logical_key=?", Long.class, upload.key());
            long now = System.currentTimeMillis();
            assertTrue(upload.expireAt() >= now + 299_000 && upload.expireAt() <= now + 301_000,
                    "The legacy response expiry continues to describe the signed PUT URL");
            assertTrue(intentExpiry >= upload.expireAt() + 598_000,
                    "The persistent intent may outlive the shorter PUT authorization");
            String stagingKey = stagingKey(jdbc, upload.key());
            objects.stage(stagingKey, PNG, "image/png");
            BizException wrongOwner = assertThrows(BizException.class, () -> service.complete(stranger, upload.key()));
            assertEquals(BizCode.NO_PERMISSION, wrongOwner.getBizCode());
            BizException wrongBiz = assertThrows(BizException.class,
                    () -> service.finalizeIfPending(owner, UploadBiz.ACTIVITY, upload.key()));
            assertEquals(BizCode.NO_PERMISSION, wrongBiz.getBizCode());

            FileLifecycleService.CompletedFile completed = service.complete(owner, upload.key());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PNG)), completed.sha256());
            assertEquals(PNG.length, completed.size());
            assertEquals("image/png", completed.contentType());
            assertEquals(1, objects.immutableWrites.get());
            assertEquals(completed, service.complete(owner, upload.key()), "Retry returns the canonical revision");
            objects.stage(stagingKey, pngBytes("late staging overwrite"), "image/png");
            assertEquals(completed, service.complete(owner, upload.key()));
            assertEquals(1, objects.immutableWrites.get(), "A completed staging key cannot replace the immutable object");
            service.finalizeAndReference(owner, UploadBiz.FOODS, upload.key(), "food", "synthetic-food", "PUBLIC");
            assertArrayEquals(PNG, objects.immutable.get(service.resolveAlias(upload.key())).bytes());

            FileAliasResolver aliases = new FileAliasResolver(jdbc);
            assertEquals(service.resolveAlias(upload.key()), aliases.resolve(upload.key()).readableObjectKey());
            FileLifecycleProperties closed = new FileLifecycleProperties();
            closed.setLifecycleEnabled(false);
            closed.setLegacyReadEnabled(false);
            S3StorageProperties signedStorage = storageProperties();
            signedStorage.setEndpoint("https://s3.example.invalid");
            signedStorage.setRegion("us-east-1");
            signedStorage.setBucket("files");
            signedStorage.setAccessKey("synthetic-access");
            signedStorage.setSecretKey("synthetic-secret");
            try (var adapter = new S3CompatibleStorageService(signedStorage, aliases, closed)) {
                String read = adapter.signGetUrl(upload.key());
                assertNotNull(read, "A managed alias remains readable when new uploads are disabled");
                assertTrue(read.contains(service.resolveAlias(upload.key())));
                assertNull(adapter.signGetUrl("legacy/old-file.png"), "Legacy read can be independently closed");
            }

            var wrongSize = service.presign(owner, UploadBiz.FOODS, "size.png", "image/png", (long) PNG.length + 1);
            objects.stage(stagingKey(jdbc, wrongSize.key()), PNG, "image/png");
            assertThrows(BizException.class, () -> service.complete(owner, wrongSize.key()));
            assertEquals(0, aliasCount(jdbc, wrongSize.key()));

            var wrongMime = service.presign(owner, UploadBiz.FOODS, "mime.png", "image/jpeg", (long) PNG.length);
            objects.stage(stagingKey(jdbc, wrongMime.key()), PNG, "image/png");
            assertThrows(BizException.class, () -> service.complete(owner, wrongMime.key()));
            assertEquals(0, aliasCount(jdbc, wrongMime.key()));

            lifecycle.setIntentTtlSeconds(0);
            var expired = service.presign(owner, UploadBiz.FOODS, "expired.png", "image/png", (long) PNG.length);
            lifecycle.setIntentTtlSeconds(900);
            objects.stage(stagingKey(jdbc, expired.key()), PNG, "image/png");
            BizException expiredFailure = assertThrows(BizException.class, () -> service.complete(owner, expired.key()));
            assertEquals(BizCode.PARAM_INVALID, expiredFailure.getBizCode());
            assertEquals(0, aliasCount(jdbc, expired.key()));

            assertConcurrentCompleteHasOneCanonicalRevision(jdbc, service, objects, owner);
            assertStaleLeaseWorkerCannotReplaceWinner(jdbc, service, objects, owner);
            assertDatabaseFailureCanRetryAfterRestart(jdbc, service, objects, owner, url, storage, lifecycle, tx);
            assertStorageFailuresDoNotCreateReadableAliases(jdbc, service, objects, owner);
        }
    }

    @Test
    void replicationHookEnqueuesOnlyWhenEnqueuerIsPresent() throws Exception {
        try (var postgres = IsolatedContainers.postgres("file-lifecycle-hook")) {
            postgres.start();
            String database = "file_hook_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                    .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                    IsolatedContainers.USER, IsolatedContainers.PASSWORD);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            long owner = user(jdbc, "hook-owner");
            FakeObjectStorage objects = new FakeObjectStorage();
            S3StorageProperties storage = storageProperties();
            FileLifecycleProperties lifecycle = new FileLifecycleProperties();
            lifecycle.setLifecycleEnabled(true);
            lifecycle.setIntentTtlSeconds(900);
            lifecycle.setClaimLeaseSeconds(120);
            var tx = new DataSourceTransactionManager(dataSource);

            // Without the enqueuer bean (replication disabled) a normal complete writes no peer rows.
            FileLifecycleService plain = new FileLifecycleService(jdbc, objects, storage, lifecycle, tx);
            var first = plain.presign(owner, UploadBiz.FOODS, "a.png", "image/png", (long) PNG.length);
            objects.stage(stagingKey(jdbc, first.key()), PNG, "image/png");
            plain.complete(owner, first.key());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE site='PEER'", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_transfer_job", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_publication", Integer.class));

            // With the enqueuer bean present, the same transaction creates exactly one PEER replica, publication and job.
            var enqueuer = new you.v50to.eatwhat.service.storage.replication.FileReplicationEnqueuer(jdbc,
                    new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(you.v50to.eatwhat.account.ConsentStore.class));
            var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            factory.registerSingleton("fileReplicationEnqueuer", enqueuer);
            FileLifecycleService replicating = new FileLifecycleService(jdbc, objects, storage, lifecycle, tx,
                    factory.getBeanProvider(you.v50to.eatwhat.service.storage.replication.FileReplicationEnqueuer.class));
            byte[] secondBytes = pngBytes("second file");
            var second = replicating.presign(owner, UploadBiz.FOODS, "b.png", "image/png", (long) secondBytes.length);
            objects.stage(stagingKey(jdbc, second.key()), secondBytes, "image/png");
            replicating.complete(owner, second.key());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE site='PEER' AND state='PENDING_SYNC'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_publication WHERE global_state='PEER_PENDING'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_transfer_job WHERE kind='COPY_TO_PEER' AND target_site='PEER' AND status='READY'", Integer.class));
            replicating.complete(owner, second.key());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_transfer_job", Integer.class),
                    "A retried complete does not enqueue a second job");
        }
    }

    @Test
    void legacyBusinessSubmissionFinalizesAndReferencesWithinBusinessTransaction() throws Exception {
        try (var postgres = IsolatedContainers.postgres("file-lifecycle-business")) {
            postgres.start();
            String database = "file_business_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                    .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                    IsolatedContainers.USER, IsolatedContainers.PASSWORD);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            long owner = user(jdbc, "legacy-food-owner");
            long restaurantId = jdbc.queryForObject("""
                    INSERT INTO public.restaurant(account_id,name,location,gcj_lng,gcj_lat)
                    VALUES (?, 'Synthetic restaurant', ST_SetSRID(ST_MakePoint(120,36),4326)::geography,120,36)
                    RETURNING id
                    """, Long.class, owner);
            FakeObjectStorage objects = new FakeObjectStorage();
            S3StorageProperties storage = storageProperties();
            FileLifecycleProperties lifecycle = new FileLifecycleProperties();
            lifecycle.setLifecycleEnabled(true);
            FileLifecycleService files = new FileLifecycleService(jdbc, objects, storage, lifecycle,
                    new DataSourceTransactionManager(dataSource));
            FoodMapper foodMapper = mock(FoodMapper.class);
            RestaurantMapper restaurantMapper = mock(RestaurantMapper.class);
            Restaurant restaurant = new Restaurant();
            restaurant.setId(restaurantId);
            when(restaurantMapper.selectById(restaurantId)).thenReturn(restaurant);
            when(foodMapper.insert(any(Food.class))).thenAnswer(invocation -> {
                Food food = invocation.getArgument(0);
                Long id = jdbc.queryForObject("""
                        INSERT INTO public.foods(account_id,restaurant_id,name,description,picture_url)
                        VALUES (?,?,?, ?, ARRAY[?]::text[]) RETURNING id
                        """, Long.class, food.getAccountId(), food.getRestaurantId(), food.getName(),
                        food.getDescription(), food.getPictureUrl()[0]);
                food.setId(id);
                return 1;
            });
            FoodService target = new FoodService();
            ReflectionTestUtils.setField(target, "foodMapper", foodMapper);
            ReflectionTestUtils.setField(target, "restaurantMapper", restaurantMapper);
            ReflectionTestUtils.setField(target, "fileLifecycleService", files);
            ProxyFactory proxyFactory = new ProxyFactory(target);
            proxyFactory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                    new AnnotationTransactionAttributeSource()));
            FoodService foodService = (FoodService) proxyFactory.getProxy();

            try {
                bindToken(loginAs(owner));
                var accepted = files.presign(owner, UploadBiz.FOODS, "legacy-client.png", "image/png", (long) PNG.length);
                objects.stage(stagingKey(jdbc, accepted.key()), PNG, "image/png");
                CreateFoodDTO acceptedDto = foodDto(restaurantId, accepted.key());
                assertEquals(BizCode.SUCCESS.getCode(), foodService.uploadFood(acceptedDto).getCode());
                Long foodId = jdbc.queryForObject("SELECT id FROM public.foods WHERE name='Legacy client food'", Long.class);
                assertEquals("COMPLETE", jdbc.queryForObject("SELECT status FROM eatwhat_file.upload_intent WHERE logical_key=?", String.class, accepted.key()));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference WHERE logical_key=? AND ref_kind='food' AND ref_id=? AND visibility='PUBLIC'", Integer.class, accepted.key(), foodId + ":0"));

                when(foodMapper.selectById(foodId)).thenAnswer(invocation -> {
                    Food stored = new Food();
                    stored.setId(foodId);
                    stored.setAccountId(owner);
                    stored.setRestaurantId(restaurantId);
                    stored.setName(jdbc.queryForObject("SELECT name FROM public.foods WHERE id=?", String.class, foodId));
                    stored.setPictureUrl(jdbc.queryForObject("SELECT picture_url FROM public.foods WHERE id=?", (rs, row) -> (String[]) rs.getArray(1).getArray(), foodId));
                    return stored;
                });
                when(foodMapper.updateById(any(Food.class))).thenAnswer(invocation -> {
                    Food update = invocation.getArgument(0);
                    return jdbc.update("UPDATE public.foods SET name=?,picture_url=ARRAY[?]::text[] WHERE id=?",
                            update.getName(), update.getPictureUrl()[0], update.getId());
                });
                var invalidEditUpload = files.presign(owner, UploadBiz.FOODS, "bad-content.jpg", "image/jpeg", (long) PNG.length);
                objects.stage(stagingKey(jdbc, invalidEditUpload.key()), PNG, "image/jpeg");
                EditFoodDTO invalidEdit = new EditFoodDTO();
                invalidEdit.setName("Must roll back after file verification fails");
                invalidEdit.setPictureUrl(List.of(invalidEditUpload.key()));
                assertThrows(BizException.class, () -> foodService.editFood(foodId, invalidEdit));
                assertEquals("Legacy client food", jdbc.queryForObject("SELECT name FROM public.foods WHERE id=?", String.class, foodId));
                assertEquals(accepted.key(), jdbc.queryForObject("SELECT picture_url[1] FROM public.foods WHERE id=?", String.class, foodId));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference WHERE logical_key=?", Integer.class, invalidEditUpload.key()));

                var rejected = files.presign(owner, UploadBiz.FOODS, "rollback-client.png", "image/png", (long) PNG.length);
                objects.stage(stagingKey(jdbc, rejected.key()), PNG, "image/png");
                jdbc.execute("""
                        CREATE FUNCTION public.fail_file_reference_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'Synthetic business reference failure' USING ERRCODE='P0001'; END $$
                        """);
                jdbc.execute("CREATE TRIGGER fail_file_reference_for_test BEFORE INSERT ON eatwhat_file.file_reference "
                        + "FOR EACH ROW EXECUTE FUNCTION public.fail_file_reference_for_test()");
                CreateFoodDTO rejectedDto = foodDto(restaurantId, rejected.key(), "Rollback client food");
                assertThrows(DataAccessException.class, () -> foodService.uploadFood(rejectedDto));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.foods WHERE name='Rollback client food'", Integer.class));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference WHERE logical_key=?", Integer.class, rejected.key()));
                assertEquals("COMPLETE", jdbc.queryForObject("SELECT status FROM eatwhat_file.upload_intent WHERE logical_key=?", String.class, rejected.key()),
                        "File verification has its own recovery boundary, while its business reference follows the business transaction");
                jdbc.execute("DROP TRIGGER fail_file_reference_for_test ON eatwhat_file.file_reference");
                jdbc.execute("DROP FUNCTION public.fail_file_reference_for_test()");
            } finally {
                cn.dev33.satoken.stp.StpUtil.logout();
            }
        }
    }

    @Test
    void legacyAvatarRestaurantAndActivitySubmissionsFinalizeAndReferenceWithoutCompleteCall() throws Exception {
        try (var postgres = IsolatedContainers.postgres("file-lifecycle-legacy-entries")) {
            postgres.start();
            String database = "file_entries_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                    .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                    IsolatedContainers.USER, IsolatedContainers.PASSWORD);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            long owner = user(jdbc, "legacy-entry-owner");
            FakeObjectStorage objects = new FakeObjectStorage();
            FileLifecycleProperties lifecycle = new FileLifecycleProperties();
            lifecycle.setLifecycleEnabled(true);
            FileLifecycleService files = new FileLifecycleService(jdbc, objects, storageProperties(),
                    lifecycle, new DataSourceTransactionManager(dataSource));
            var tx = new DataSourceTransactionManager(dataSource);
            bindToken(loginAs(owner));
            try {
                UserMapper userMapper = mock(UserMapper.class);
                User oldUser = new User();
                oldUser.setId(owner);
                oldUser.setAvatar("old-avatar.png");
                when(userMapper.selectById(owner)).thenReturn(oldUser);
                when(userMapper.updateById(any(User.class))).thenAnswer(invocation -> {
                    User update = invocation.getArgument(0);
                    return jdbc.update("UPDATE public.users SET avatar=? WHERE id=?", update.getAvatar(), update.getId());
                });
                UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
                UserInfo priorInfo = new UserInfo();
                priorInfo.setId(owner);
                when(userInfoMapper.selectById(owner)).thenReturn(priorInfo);
                when(userInfoMapper.updateById(any(UserInfo.class))).thenReturn(1);
                UserService userTarget = new UserService();
                ReflectionTestUtils.setField(userTarget, "userMapper", userMapper);
                ReflectionTestUtils.setField(userTarget, "userInfoMapper", userInfoMapper);
                ReflectionTestUtils.setField(userTarget, "fileLifecycleService", files);
                ReflectionTestUtils.setField(userTarget, "locationValidationUtil", mock(LocationValidationUtil.class));
                var avatar = files.presign(owner, UploadBiz.AVATAR, "avatar.png", "image/png", (long) PNG.length);
                objects.stage(stagingKey(jdbc, avatar.key()), PNG, "image/png");
                UpdateUserInfoDTO avatarDto = new UpdateUserInfoDTO();
                avatarDto.setAvatar(avatar.key());
                assertEquals(BizCode.SUCCESS.getCode(), transactionalProxy(userTarget, tx).updateUserInfo(avatarDto).getCode());
                assertReference(jdbc, avatar.key(), "user_avatar", Long.toString(owner));
                assertEquals(avatar.key(), jdbc.queryForObject("SELECT avatar FROM public.users WHERE id=?", String.class, owner));

                RestaurantMapper restaurantMapper = mock(RestaurantMapper.class);
                when(restaurantMapper.insert(any(Restaurant.class))).thenAnswer(invocation -> {
                    Restaurant restaurant = invocation.getArgument(0);
                    Long id = jdbc.queryForObject("""
                            INSERT INTO public.restaurant(account_id,name,address,location,gcj_lng,gcj_lat,picture_url)
                            VALUES (?,?,?,ST_SetSRID(ST_MakePoint(120,36),4326)::geography,?,?,ARRAY[?]::text[]) RETURNING id
                            """, Long.class, restaurant.getAccountId(), restaurant.getName(), restaurant.getAddress(),
                            restaurant.getGcjLng(), restaurant.getGcjLat(), restaurant.getPictureUrl()[0]);
                    restaurant.setId(id);
                    return 1;
                });
                RestaurantService restaurantTarget = new RestaurantService();
                ReflectionTestUtils.setField(restaurantTarget, "restaurantMapper", restaurantMapper);
                ReflectionTestUtils.setField(restaurantTarget, "fileLifecycleService", files);
                var restaurantUpload = files.presign(owner, UploadBiz.RESTAURANTS, "restaurant.png", "image/png", (long) PNG.length);
                objects.stage(stagingKey(jdbc, restaurantUpload.key()), PNG, "image/png");
                RestaurantDTO restaurantDto = new RestaurantDTO();
                restaurantDto.setName("Synthetic legacy restaurant");
                restaurantDto.setAddress("Synthetic address");
                restaurantDto.setGcjLng(120.0);
                restaurantDto.setGcjLat(36.0);
                restaurantDto.setPictureUrl(List.of(restaurantUpload.key()));
                assertEquals(BizCode.SUCCESS.getCode(), transactionalProxy(restaurantTarget, tx).addRestaurant(restaurantDto).getCode());
                Long restaurantId = jdbc.queryForObject("SELECT id FROM public.restaurant WHERE name='Synthetic legacy restaurant'", Long.class);
                assertReference(jdbc, restaurantUpload.key(), "restaurant", restaurantId.toString() + ":0");

                Long activityId = jdbc.queryForObject("INSERT INTO public.activities(code,name,status) VALUES ('file-test','Synthetic activity','ONLINE') RETURNING id", Long.class);
                you.v50to.eatwhat.data.po.Activity definition = new you.v50to.eatwhat.data.po.Activity();
                definition.setId(activityId);
                definition.setCode("file-test");
                definition.setStatus("ONLINE");
                definition.setRequireLocation(false);
                definition.setMaxPictures(9);
                definition.setFieldSchema("[]");
                ActivityMapper activityMapper = mock(ActivityMapper.class);
                when(activityMapper.selectVisibleIdByCode("file-test")).thenReturn(activityId);
                when(activityMapper.selectById(activityId)).thenReturn(definition);
                ActivityPostMapper activityPostMapper = mock(ActivityPostMapper.class);
                when(activityPostMapper.insert(any(you.v50to.eatwhat.data.po.ActivityPost.class))).thenAnswer(invocation -> {
                    var post = invocation.getArgument(0, you.v50to.eatwhat.data.po.ActivityPost.class);
                    Long id = jdbc.queryForObject("INSERT INTO public.activity_posts(activity_id,account_id,description,picture_url) VALUES (?,?,?,ARRAY[?]::text[]) RETURNING id",
                            Long.class, post.getActivityId(), post.getAccountId(), post.getDescription(), post.getPictureUrl()[0]);
                    post.setId(id);
                    return 1;
                });
                ActivityService activityTarget = new ActivityService();
                ReflectionTestUtils.setField(activityTarget, "activityMapper", activityMapper);
                ReflectionTestUtils.setField(activityTarget, "activityPostMapper", activityPostMapper);
                ReflectionTestUtils.setField(activityTarget, "fileLifecycleService", files);
                ReflectionTestUtils.setField(activityTarget, "locationValidationUtil", mock(LocationValidationUtil.class));
                ReflectionTestUtils.setField(activityTarget, "redis", mock(StringRedisTemplate.class));
                ReflectionTestUtils.setField(activityTarget, "objectMapper", tools.jackson.databind.json.JsonMapper.builder().build());
                ActivityService activity = transactionalProxy(activityTarget, tx);
                var postUpload = files.presign(owner, UploadBiz.ACTIVITY, "activity-post.png", "image/png", (long) PNG.length);
                objects.stage(stagingKey(jdbc, postUpload.key()), PNG, "image/png");
                CreateActivityPostDTO postDto = new CreateActivityPostDTO();
                postDto.setDescription("Synthetic activity post");
                postDto.setPictureUrl(List.of(postUpload.key()));
                assertEquals(BizCode.SUCCESS.getCode(), activity.publishPost("file-test", postDto).getCode());
                Long postId = jdbc.queryForObject("SELECT id FROM public.activity_posts WHERE description='Synthetic activity post'", Long.class);
                assertReference(jdbc, postUpload.key(), "activity_post", postId + ":0");
            } finally {
                cn.dev33.satoken.stp.StpUtil.logout();
            }
        }
    }

    private static CreateFoodDTO foodDto(long restaurantId, String key) {
        return foodDto(restaurantId, key, "Legacy client food");
    }

    private static CreateFoodDTO foodDto(long restaurantId, String key, String name) {
        CreateFoodDTO dto = new CreateFoodDTO();
        dto.setRestaurantId(restaurantId);
        dto.setName(name);
        dto.setPictureUrl(List.of(key));
        return dto;
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactionalProxy(T target, DataSourceTransactionManager tx) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static void assertReference(JdbcTemplate jdbc, String key, String refKind, String refId) {
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference WHERE logical_key=? AND ref_kind=? AND ref_id=? AND visibility='PUBLIC'",
                Integer.class, key, refKind, refId));
        assertEquals("COMPLETE", jdbc.queryForObject("SELECT status FROM eatwhat_file.upload_intent WHERE logical_key=?", String.class, key));
    }

    private static void assertConcurrentCompleteHasOneCanonicalRevision(JdbcTemplate jdbc,
            FileLifecycleService service, FakeObjectStorage objects, long owner) throws Exception {
        var upload = service.presign(owner, UploadBiz.FOODS, "concurrent.png", "image/png", (long) PNG.length);
        objects.stage(stagingKey(jdbc, upload.key()), PNG, "image/png");
        CountDownLatch downloadEntered = new CountDownLatch(1);
        CountDownLatch continueDownload = new CountDownLatch(1);
        objects.blockNextDownload(downloadEntered, continueDownload);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.complete(owner, upload.key()));
            assertTrue(downloadEntered.await(10, TimeUnit.SECONDS), "The first completer reaches object verification");
            BizException inProgress = assertThrows(BizException.class, () -> service.complete(owner, upload.key()));
            assertEquals(BizCode.OP_FAILED, inProgress.getBizCode());
            continueDownload.countDown();
            var winner = first.get(10, TimeUnit.SECONDS);
            assertEquals(winner, service.complete(owner, upload.key()), "A retry after the winner commits is idempotent");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_object WHERE logical_key=?", Integer.class, upload.key()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_revision r JOIN eatwhat_file.file_object o USING(file_id) WHERE o.logical_key=? AND r.state='VERIFIED'", Integer.class, upload.key()));
        } finally {
            continueDownload.countDown();
        }
    }

    private static void assertStaleLeaseWorkerCannotReplaceWinner(JdbcTemplate jdbc,
            FileLifecycleService service, FakeObjectStorage objects, long owner) throws Exception {
        byte[] content = PNG.clone();
        content[content.length - 1] = 99;
        var upload = service.presign(owner, UploadBiz.FOODS, "fenced.png", "image/png", (long) content.length);
        objects.stage(stagingKey(jdbc, upload.key()), content, "image/png");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        objects.blockNextDownload(firstEntered, releaseFirst);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(() -> service.complete(owner, upload.key()));
            assertTrue(firstEntered.await(10, TimeUnit.SECONDS));
            jdbc.update("UPDATE eatwhat_file.upload_intent SET lease_until=now()-interval '1 second' WHERE logical_key=?", upload.key());
            var winner = service.complete(owner, upload.key());
            releaseFirst.countDown();
            var staleFailure = assertThrows(java.util.concurrent.ExecutionException.class, () -> stale.get(10, TimeUnit.SECONDS));
            assertInstanceOf(BizException.class, staleFailure.getCause());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)), winner.sha256());
            assertEquals(1, aliasCount(jdbc, upload.key()), "The fenced winner owns the only canonical alias");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_revision r JOIN eatwhat_file.file_object o USING(file_id) WHERE o.logical_key=? AND r.state='VERIFIED'", Integer.class, upload.key()));
        } finally {
            releaseFirst.countDown();
        }
    }

    private static void assertStorageFailuresDoNotCreateReadableAliases(JdbcTemplate jdbc,
            FileLifecycleService service, FakeObjectStorage objects, long owner) {
        var headFailure = service.presign(owner, UploadBiz.FOODS, "head-failure.png", "image/png", (long) PNG.length);
        objects.stage(stagingKey(jdbc, headFailure.key()), PNG, "image/png");
        objects.failNextHead = true;
        assertThrows(IllegalStateException.class, () -> service.complete(owner, headFailure.key()));
        assertEquals(0, aliasCount(jdbc, headFailure.key()));
        assertNull(service.resolveAlias(headFailure.key()));

        var getFailure = service.presign(owner, UploadBiz.FOODS, "get-failure.png", "image/png", (long) PNG.length);
        objects.stage(stagingKey(jdbc, getFailure.key()), PNG, "image/png");
        objects.failNextDownload = true;
        assertThrows(IllegalStateException.class, () -> service.complete(owner, getFailure.key()));
        assertEquals(0, aliasCount(jdbc, getFailure.key()));
        assertNull(service.resolveAlias(getFailure.key()));

        var putFailure = service.presign(owner, UploadBiz.FOODS, "put-failure.png", "image/png", (long) PNG.length);
        objects.stage(stagingKey(jdbc, putFailure.key()), PNG, "image/png");
        objects.failNextPut = true;
        assertThrows(IllegalStateException.class, () -> service.complete(owner, putFailure.key()));
        assertEquals(0, aliasCount(jdbc, putFailure.key()));
        assertNull(service.resolveAlias(putFailure.key()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference WHERE logical_key IN (?,?,?)",
                Integer.class, headFailure.key(), getFailure.key(), putFailure.key()));
    }

    private static void assertDatabaseFailureCanRetryAfterRestart(JdbcTemplate jdbc, FileLifecycleService service,
            FakeObjectStorage objects, long owner, String url, S3StorageProperties storage,
            FileLifecycleProperties lifecycle, DataSourceTransactionManager tx) {
        var upload = service.presign(owner, UploadBiz.FOODS, "recovery.png", "image/png", (long) PNG.length);
        objects.stage(stagingKey(jdbc, upload.key()), PNG, "image/png");
        jdbc.execute("""
                CREATE FUNCTION public.fail_file_revision_insert_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Synthetic file metadata commit failure' USING ERRCODE='P0001'; END $$
                """);
        jdbc.execute("CREATE TRIGGER fail_file_revision_insert_for_test BEFORE INSERT ON eatwhat_file.file_revision "
                + "FOR EACH ROW EXECUTE FUNCTION public.fail_file_revision_insert_for_test()");
        assertThrows(DataAccessException.class, () -> service.complete(owner, upload.key()));
        assertEquals(0, aliasCount(jdbc, upload.key()), "Object success alone cannot publish a readable alias");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_orphan_candidate WHERE intent_id=(SELECT intent_id FROM eatwhat_file.upload_intent WHERE logical_key=?) AND state='ORPHANED'", Integer.class, upload.key()));
        String writtenObject = jdbc.queryForObject("SELECT object_key FROM eatwhat_file.file_orphan_candidate WHERE intent_id=(SELECT intent_id FROM eatwhat_file.upload_intent WHERE logical_key=?) AND state='ORPHANED'", String.class, upload.key());
        assertTrue(objects.immutable.containsKey(writtenObject), "The uploaded immutable candidate survives the failed DB transaction");

        jdbc.execute("DROP TRIGGER fail_file_revision_insert_for_test ON eatwhat_file.file_revision");
        jdbc.execute("DROP FUNCTION public.fail_file_revision_insert_for_test()");
        jdbc.update("UPDATE eatwhat_file.upload_intent SET lease_until=now()-interval '1 second' WHERE logical_key=?", upload.key());
        FileLifecycleService afterRestart = new FileLifecycleService(jdbc, objects, storage, lifecycle, tx);
        assertNotNull(afterRestart.complete(owner, upload.key()));
        assertEquals(1, aliasCount(jdbc, upload.key()), "A fresh service can reclaim the expired lease and publish a verified alias");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_orphan_candidate WHERE intent_id=(SELECT intent_id FROM eatwhat_file.upload_intent WHERE logical_key=?) AND state='CANONICAL'", Integer.class, upload.key()));
    }

    private static long user(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("INSERT INTO public.users(nick_name,password_hash) VALUES (?, 'synthetic') RETURNING id", Long.class, name);
    }

    private static String stagingKey(JdbcTemplate jdbc, String key) {
        return jdbc.queryForObject("SELECT staging_key FROM eatwhat_file.upload_intent WHERE logical_key=?", String.class, key);
    }

    private static int aliasCount(JdbcTemplate jdbc, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_key_alias WHERE logical_key=?", Integer.class, key);
    }

    private static S3StorageProperties storageProperties() {
        S3StorageProperties properties = new S3StorageProperties();
        properties.setMaxFileSizeBytes(1024);
        properties.setAllowedContentTypes(List.of("image/png", "image/jpeg"));
        return properties;
    }

    private static byte[] pngBytes(String text) {
        byte[] suffix = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bytes = java.util.Arrays.copyOf(PNG, PNG.length + suffix.length);
        System.arraycopy(suffix, 0, bytes, PNG.length, suffix.length);
        return bytes;
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
    }

    private static final class FakeObjectStorage implements FileObjectStorage {
        private final Map<String, StoredObject> staging = new ConcurrentHashMap<>();
        private final Map<String, StoredObject> immutable = new ConcurrentHashMap<>();
        private final AtomicInteger immutableWrites = new AtomicInteger();
        private volatile CountDownLatch downloadEntered;
        private volatile CountDownLatch continueDownload;
        private volatile boolean failNextHead;
        private volatile boolean failNextDownload;
        private volatile boolean failNextPut;

        @Override public String presignStagingPut(String stagingKey, String contentType, long ttlSeconds) {
            return "https://objects.example.invalid/" + stagingKey;
        }

        @Override public FileObjectStorage.StagedMetadata head(String key) {
            if (failNextHead) { failNextHead = false; throw new IllegalStateException("synthetic HEAD failure"); }
            StoredObject object = staging.get(key);
            if (object == null) throw new IllegalStateException("staging object missing");
            return new FileObjectStorage.StagedMetadata(object.bytes().length, object.contentType());
        }

        void stage(String key, byte[] bytes, String contentType) {
            staging.put(key, new StoredObject(bytes.clone(), contentType));
        }

        void blockNextDownload(CountDownLatch entered, CountDownLatch release) {
            downloadEntered = entered;
            continueDownload = release;
        }

        @Override public FileObjectStorage.StagedMetadata downloadToFile(String key, Path destination, long maxBytes) {
            if (failNextDownload) { failNextDownload = false; throw new IllegalStateException("synthetic GET failure"); }
            CountDownLatch entered = downloadEntered;
            if (entered != null) {
                downloadEntered = null;
                entered.countDown();
                try {
                    if (!continueDownload.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            StoredObject object = staging.get(key);
            if (object == null) throw new IllegalStateException("staging object missing");
            if (object.bytes().length > maxBytes) throw new IllegalArgumentException("object too large");
            try { Files.write(destination, object.bytes()); }
            catch (IOException e) { throw new IllegalStateException(e); }
            return new FileObjectStorage.StagedMetadata(object.bytes().length, object.contentType());
        }

        @Override public void putImmutableFile(String key, String contentType, Path source) {
            if (failNextPut) { failNextPut = false; throw new IllegalStateException("synthetic PUT failure"); }
            try {
                immutable.put(key, new StoredObject(Files.readAllBytes(source), contentType));
                immutableWrites.incrementAndGet();
            } catch (IOException e) { throw new IllegalStateException(e); }
        }
    }

    private record StoredObject(byte[] bytes, String contentType) { }
}
