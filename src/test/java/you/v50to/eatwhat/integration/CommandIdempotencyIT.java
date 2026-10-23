package you.v50to.eatwhat.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.sync.CommandIdempotencyService;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.sync.VersionVector;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real MyBatis writes and Spring proxies, using only a migrated, test-owned PostgreSQL database. */
class CommandIdempotencyIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("command-idempotency");
    private static final String PRINCIPAL = "user/00000000-0000-4000-8000-000000000001";

    @BeforeAll static void startDatabase() { POSTGRES.start(); }
    @AfterAll static void stopDatabase() { POSTGRES.stop(); }

    @Test
    void realSpringProxyAndMybatisCommitBusinessIdentityEventVersionDeliveryAndResultTogether() {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        try (var context = context(db)) {
            FixtureCommands commands = context.getBean(FixtureCommands.class);
            assertTrue(AopUtils.isAopProxy(commands));
            String result = commands.create("atomic", "atomic_user", Failure.NONE, () -> assertCounts(db, 0));
            assertCounts(db, 1);
            long numericId = db.admin().queryForObject("SELECT id FROM users", Long.class);
            UUID gid = db.admin().queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity", UUID.class);
            assertTrue(numericId > 1_000_000_000L);
            assertEquals(1, numericId % 2);
            assertEquals("{\"globalId\":\"" + gid + "\",\"userId\":" + numericId + "}", result);
            assertEquals("{\"campus-test\":1}", EventCodec.canonicalJson(db.admin().queryForObject(
                    "SELECT applied_vector::text FROM eatwhat_sync.entity_state", String.class)));
            assertEquals("COMPLETED", db.admin().queryForObject("SELECT status FROM eatwhat_sync.command_dedup", String.class));
            assertFalse(db.runtime().queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class));
        }
    }

    @Test
    void failuresAfterMapperAfterOutboxAndAfterCommandCompletionAllRollbackAndTheSameKeyCanRecover() {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        try (var context = context(db)) {
            FixtureCommands commands = context.getBean(FixtureCommands.class);
            for (Failure failure : List.of(Failure.AFTER_MAPPER, Failure.AFTER_OUTBOX, Failure.AFTER_COMMAND)) {
                assertThrows(InjectedFailure.class,
                        () -> commands.create("retry", "retry_user", failure, () -> { }));
                assertCounts(db, 0);
            }
            String result = commands.create("retry", "retry_user", Failure.NONE, () -> { });
            assertCounts(db, 1);
            assertEquals(result, commands.create("retry", "retry_user", Failure.NONE,
                    () -> fail("Completed command must not repeat the callback")));
            assertCounts(db, 1);
        }
    }

    @Test
    void canonicalRequestsAndFreshServiceInstancesReturnTheSameStableResultWithoutMutation() {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        AtomicInteger callbacks = new AtomicInteger();
        String first = commandService(db).execute(PRINCIPAL, "synthetic.create", "canonical",
                "{\"b\":2.00,\"a\":1}", () -> {
                    callbacks.incrementAndGet();
                    return "{\"z\":2.0,\"reference\":\"stable-record\"}";
                });
        assertEquals("{\"reference\":\"stable-record\",\"z\":2}", first);
        for (int retry = 0; retry < 10; retry++) {
            assertEquals(first, commandService(db).execute(PRINCIPAL, "synthetic.create", "canonical",
                    "{ \"a\":1.0, \"b\":2e0 }", () -> fail("Durable completion must survive a fresh service")));
        }
        assertEquals(1, callbacks.get());
        assertEquals(1, count(db, "eatwhat_sync.command_dedup"));
        assertEquals(EventCodec.sha256("{\"a\":1,\"b\":2}"), db.admin().queryForObject(
                "SELECT request_hash FROM eatwhat_sync.command_dedup", String.class));
    }

    @Test
    void differentRequestHashIsRejectedAndPrincipalAndCommandTypeAreIndependentScopes() {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        var service = commandService(db);
        assertEquals("{\"reference\":1}", service.execute(PRINCIPAL, "synthetic.create", "same", "{\"n\":1}",
                () -> "{\"reference\":1}"));
        assertThrows(IllegalStateException.class, () -> service.execute(PRINCIPAL, "synthetic.create", "same", "{\"n\":2}",
                () -> fail("A different request cannot run under an existing key")));
        assertEquals("{}", service.execute("user/another", "synthetic.create", "same", "{\"n\":2}", () -> "{}"));
        assertEquals("{}", service.execute(PRINCIPAL, "synthetic.update", "same", "{\"n\":2}", () -> "{}"));
        assertEquals(3, count(db, "eatwhat_sync.command_dedup"));
    }

    @Test
    void concurrentIdenticalCommandsWaitForTheFirstCommitAndRunTheMybatisCallbackOnce() throws Exception {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        Future<String> first = null;
        Future<String> second = null;
        try (var context = context(db)) {
            FixtureCommands commands = context.getBean(FixtureCommands.class);
            try {
                first = executor.submit(() -> commands.create("concurrent", "concurrent_user", Failure.NONE, () -> {
                    callbacks.incrementAndGet();
                    callbackEntered.countDown();
                    await(release);
                }));
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
                second = executor.submit(() -> commands.create("concurrent", "concurrent_user", Failure.NONE,
                        () -> callbacks.incrementAndGet()));
                awaitBlockedDedupInsert(db);
                assertFalse(second.isDone(), "The duplicate must await the uncommitted command's decision");
                assertCounts(db, 0);
                release.countDown();
                assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                assertEquals(1, callbacks.get());
                assertCounts(db, 1);
            } finally {
                release.countDown();
                if (first != null) first.cancel(true);
                if (second != null) second.cancel(true);
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(12, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void concurrentWaiterRunsAfterTheFirstTransactionRollsBack() throws Exception {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Future<String> first = null;
        Future<String> second = null;
        try (var context = context(db)) {
            FixtureCommands commands = context.getBean(FixtureCommands.class);
            try {
                first = executor.submit(() -> commands.create("recover", "recover_user", Failure.AFTER_OUTBOX, () -> {
                    firstEntered.countDown();
                    await(release);
                }));
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                second = executor.submit(() -> commands.create("recover", "recover_user", Failure.NONE, () -> { }));
                awaitBlockedDedupInsert(db);
                release.countDown();
                Future<String> failed = first;
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> failed.get(10, TimeUnit.SECONDS));
                assertInstanceOf(InjectedFailure.class, failure.getCause());
                assertNotNull(second.get(10, TimeUnit.SECONDS));
                assertCounts(db, 1);
            } finally {
                release.countDown();
                if (first != null) first.cancel(true);
                if (second != null) second.cancel(true);
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(12, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void reentrantOrPersistedIncompleteCommandsCannotRunAndCaughtNestedFailurePoisonsTheOuterTransaction() {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        var service = commandService(db);
        assertThrows(IllegalStateException.class, () -> service.execute(PRINCIPAL, "synthetic.create", "nested", "{}",
                () -> service.execute(PRINCIPAL, "synthetic.create", "nested", "{}", () -> fail("Reentrant callback"))));
        assertEquals(0, count(db, "eatwhat_sync.command_dedup"));
        assertThrows(UnexpectedRollbackException.class, () -> db.transactions().required(() -> {
            UUID gid = UUID.randomUUID();
            db.runtime().update("INSERT INTO business_counter(gid,numeric_id,value) VALUES (?,1,1)", gid);
            assertThrows(IllegalArgumentException.class, () -> service.execute(PRINCIPAL, "synthetic.create", "invalid", "[]", () -> "{}"));
            return null;
        }));
        assertEquals(0, count(db, "public.business_counter"));
        db.admin().update("""
                INSERT INTO eatwhat_sync.command_dedup(site_id,principal_key,command_type,request_key,request_hash)
                VALUES (?,?,?,? ,?)
                """, db.site().getId(), PRINCIPAL, "synthetic.create", "incomplete", EventCodec.sha256("{}"));
        assertThrows(IllegalStateException.class, () -> service.execute(PRINCIPAL, "synthetic.create", "incomplete", "{}",
                () -> fail("A pre-existing incomplete record requires explicit investigation")));
        assertEquals("RUNNING", db.admin().queryForObject("SELECT status FROM eatwhat_sync.command_dedup", String.class));
    }

    @Test
    void invalidOrOversizedResultRollsBackItsBusinessMutationAndDoesNotConsumeTheKey() {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        var service = commandService(db);
        for (String invalidResult : List.of("[]", "{\"duplicate\":1,\"duplicate\":2}", "{\"n\":1e1000}",
                "{\"tooLarge\":\"" + "x".repeat(EventCodec.MAX_PAYLOAD_BYTES) + "\"}")) {
            assertThrows(IllegalArgumentException.class, () -> service.execute(PRINCIPAL, "synthetic.create", "invalid-result", "{}", () -> {
                db.runtime().update("INSERT INTO business_counter(gid,numeric_id,value) VALUES (?,1,1)", UUID.randomUUID());
                return invalidResult;
            }));
            assertEquals(0, count(db, "public.business_counter"));
            assertEquals(0, count(db, "eatwhat_sync.command_dedup"));
        }
        assertEquals("{}", service.execute(PRINCIPAL, "synthetic.create", "invalid-result", "{}", () -> "{}"));
    }

    @Test
    void requestValidationPreventsUnboundedKeysAndResultsRemainImmutableForTheRuntimeRole() {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        var service = commandService(db);
        assertThrows(IllegalArgumentException.class, () -> service.execute(PRINCIPAL, "synthetic.create", "x".repeat(129), "{}", () -> "{}"));
        assertThrows(IllegalArgumentException.class, () -> service.execute(PRINCIPAL, "synthetic.create", "bad-request", "{\"x\":1,\"x\":2}", () -> "{}"));
        assertThrows(IllegalArgumentException.class, () -> service.execute(PRINCIPAL, "synthetic.create", "oversized", "{\"x\":\"" + "x".repeat(EventCodec.MAX_PAYLOAD_BYTES) + "\"}", () -> "{}"));
        assertEquals(0, count(db, "eatwhat_sync.command_dedup"));
        service.execute(PRINCIPAL, "synthetic.create", "protected", "{}", () -> "{\"reference\":1}");
        assertThrows(DataAccessException.class, () -> db.runtime().update("UPDATE eatwhat_sync.command_dedup SET result_json='{}'"));
        assertThrows(DataAccessException.class, () -> db.runtime().update("UPDATE eatwhat_sync.command_dedup SET request_hash=repeat('a',64)"));
        assertThrows(DataAccessException.class, () -> db.runtime().update("DELETE FROM eatwhat_sync.command_dedup"));
        assertThrows(DataAccessException.class, () -> db.runtime().execute("TRUNCATE eatwhat_sync.command_dedup"));
        assertEquals("{\"reference\":1}", service.execute(PRINCIPAL, "synthetic.create", "protected", "{}", () -> fail("Must replay")));
    }

    @Test
    void wrongDataSourceWiringFailsBeforeAnyCommandCanEscapeTheLocalTransaction() {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        var otherDataSource = new DriverManagerDataSource(db.url(), db.runtimeUsername(), IsolatedContainers.PASSWORD);
        // Even the same JDBC URL is unsafe when Spring binds transactions to different DataSource objects.
        assertThrows(IllegalArgumentException.class, () -> new CommandIdempotencyService(
                new JdbcTemplate(otherDataSource), db.transactions(), db.site()));
        assertThrows(IllegalArgumentException.class, () -> new SyncTransactions(
                db.runtime(), new DataSourceTransactionManager(otherDataSource)));
        assertEquals(0, count(db, "eatwhat_sync.command_dedup"));
    }

    @Test
    void readOnlyAndRepeatableReadCallersFailWithoutExecutingTheCallbackAndLeaveNoTransactionState() {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        var service = commandService(db);
        TransactionTemplate readOnly = new TransactionTemplate(db.transactionManager());
        readOnly.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> readOnly.execute(status -> service.execute(
                PRINCIPAL, "synthetic.create", "read-only", "{}", () -> fail("Read-only callback must not run"))));
        TransactionTemplate repeatableRead = new TransactionTemplate(db.transactionManager());
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(IllegalStateException.class, () -> repeatableRead.execute(status -> service.execute(
                PRINCIPAL, "synthetic.create", "repeatable-read", "{}", () -> fail("Wrong-isolation callback must not run"))));
        assertEquals(0, count(db, "eatwhat_sync.command_dedup"));
        assertEquals("{}", service.execute(PRINCIPAL, "synthetic.create", "healthy", "{}", () -> "{}"));
        assertEquals(1, count(db, "eatwhat_sync.command_dedup"));
    }

    @Test
    void aTransactionOnAnotherDataSourceCannotSilentlyCommitTheInnerCommandIndependently() {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        var otherDataSource = new DriverManagerDataSource(db.url(), db.runtimeUsername(), IsolatedContainers.PASSWORD);
        JdbcTemplate otherJdbc = new JdbcTemplate(otherDataSource);
        TransactionTemplate outer = new TransactionTemplate(new DataSourceTransactionManager(otherDataSource));
        UUID outerBusiness = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> outer.execute(status -> {
            otherJdbc.update("INSERT INTO business_counter(gid,numeric_id,value) VALUES (?,1,1)", outerBusiness);
            return commandService(db).execute(PRINCIPAL, "synthetic.create", "foreign-transaction", "{}", () -> {
                db.runtime().update("INSERT INTO business_counter(gid,numeric_id,value) VALUES (?,2,1)", UUID.randomUUID());
                return "{}";
            });
        }));
        assertEquals(0, count(db, "public.business_counter"));
        assertEquals(0, count(db, "eatwhat_sync.command_dedup"));
        assertEquals("{}", commandService(db).execute(PRINCIPAL, "synthetic.create", "foreign-transaction", "{}", () -> "{}"));
    }

    private static CommandIdempotencyService commandService(EventFoundationTestSupport.Database db) {
        return new CommandIdempotencyService(db.runtime(), db.transactions(), db.site());
    }

    private static AnnotationConfigApplicationContext context(EventFoundationTestSupport.Database db) {
        var context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, db::transactionManager);
        context.registerBean(SqlSessionFactory.class, () -> {
            try {
                var configuration = new MybatisConfiguration();
                configuration.setMapUnderscoreToCamelCase(true);
                configuration.addMapper(UserMapper.class);
                var factory = new MybatisSqlSessionFactoryBean();
                factory.setDataSource(db.dataSource());
                factory.setConfiguration(configuration);
                return factory.getObject();
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot create isolated MyBatis fixture", failure);
            }
        });
        context.registerBean(SqlSessionTemplate.class, () -> new SqlSessionTemplate(context.getBean(SqlSessionFactory.class)));
        context.registerBean(UserMapper.class, () -> context.getBean(SqlSessionTemplate.class).getMapper(UserMapper.class));
        context.registerBean(FixtureCommands.class, () -> new FixtureCommands(db, context.getBean(UserMapper.class)));
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration { }

    public static class FixtureCommands {
        private final EventFoundationTestSupport.Database db;
        private final UserMapper users;
        private final CommandIdempotencyService commands;
        private final OutboxService outbox;

        FixtureCommands(EventFoundationTestSupport.Database db, UserMapper users) {
            this.db = db;
            this.users = users;
            this.commands = commandService(db);
            this.outbox = new OutboxService(db.runtime(), db.transactions(), new EventCodec(), db.site());
        }

        @Transactional
        public String create(String key, String name, Failure failure, Runnable afterAppend) {
            String result = commands.execute(PRINCIPAL, "synthetic.user.create", key, "{\"name\":\"" + name + "\"}", () -> {
                User user = new User();
                user.setUserName(name);
                user.setPasswordHash("synthetic-test-only");
                assertEquals(1, users.insert(user));
                assertNotNull(user.getId(), "The actual MyBatis auto-key contract must survive");
                if (failure == Failure.AFTER_MAPPER) throw new InjectedFailure();
                UUID gid = db.runtime().queryForObject("""
                        SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?
                        """, UUID.class, user.getId());
                VersionVector base = VersionVector.empty();
                SyncEvent event = new SyncEvent(UUID.randomUUID(), db.site().getId(), db.site().getInstanceId(),
                        "users", gid, user.getId(), "synthetic.user.created", 1, base, base.increment(db.site().getId()),
                        List.of(), null, key, UUID.randomUUID(), "{\"name\":\"" + name + "\"}");
                outbox.append(event, List.of("external-test"));
                afterAppend.run();
                if (failure == Failure.AFTER_OUTBOX) throw new InjectedFailure();
                return "{\"userId\":" + user.getId() + ",\"globalId\":\"" + gid + "\"}";
            });
            if (failure == Failure.AFTER_COMMAND) throw new InjectedFailure();
            return result;
        }
    }

    enum Failure { NONE, AFTER_MAPPER, AFTER_OUTBOX, AFTER_COMMAND }
    static class InjectedFailure extends RuntimeException { }

    private static void assertCounts(EventFoundationTestSupport.Database db, int expected) {
        for (String table : List.of("public.users", "eatwhat_identity.entity_identity", "eatwhat_sync.outbox",
                "eatwhat_sync.entity_state", "eatwhat_sync.delivery", "eatwhat_sync.command_dedup")) {
            assertEquals(expected, count(db, table), table);
        }
    }

    private static int count(EventFoundationTestSupport.Database db, String table) {
        return db.admin().queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test barrier");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted test barrier", interrupted);
        }
    }

    private static void awaitBlockedDedupInsert(EventFoundationTestSupport.Database db) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(db.admin().queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE datname=current_database() AND usename=? AND wait_event_type='Lock'
                      AND query LIKE '%INSERT INTO eatwhat_sync.command_dedup%')
                    """, Boolean.class, db.runtimeUsername()))) return;
            Thread.sleep(20);
        }
        fail("The duplicate command never demonstrably waited on the first database transaction");
    }
}
